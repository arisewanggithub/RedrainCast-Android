package org.screencopy.receiver.protocol

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.screencopy.receiver.media.HdrBitstream
import org.screencopy.receiver.media.VideoFormat
import java.util.Base64

class HdrVideoTest {
    private val caps = ReceiveCapabilities(true, true)
    private fun metadata() = HdrStaticMetadata(listOf(35400, 14600, 8500, 39850, 6550, 2300, 15635, 16450), 1000, 50, 1000, 400)
    private fun request(color: JSONObject? = HdrVideoColor(metadata()).json()): JSONObject {
        val result = dev.screencopy.sender.Settings().request()
        result.getJSONObject("video").put("codec", "h265")
        color?.let { result.put("videoColor", it) }
        return result
    }

    private fun sizedEnvelope(json: JSONObject, size: Int): String {
        val compact = json.toString()
        return "{" + " ".repeat(size - compact.toByteArray(Charsets.UTF_8).size) + compact.drop(1)
    }

    private fun rawMessage(type: Type, root: JSONObject, key: String, envelope: String): JSONObject {
        val separator = if (root.length() == 0) "" else ","
        val raw = root.toString().dropLast(1) + "$separator\"$key\":$envelope}"
        return Message(type, payload = raw.toByteArray(Charsets.UTF_8)).json()
    }

    @Test fun whitespaceHeavy2212ByteTupleCannotBypassVideoColorEnvelopeLimit() {
        val color = HdrVideoColor().json()
        assertEquals(164, color.toString().toByteArray(Charsets.UTF_8).size)
        val envelope = sizedEnvelope(color, 2212)
        assertEquals(2212, envelope.toByteArray(Charsets.UTF_8).size)
        val json = rawMessage(Type.SESSION_REQUEST, request(null), "videoColor", envelope)
        assertEquals(164, json.obj("videoColor").toString().toByteArray(Charsets.UTF_8).size)
        assertEquals("PROTOCOL", assertThrows(ProtocolException::class.java) {
            Request.parse(json, hdrNegotiated = true, receiveCapabilities = caps)
        }.code)
    }

    @Test fun videoColorOriginalUtf8EnvelopeBoundsSurviveRequestAndConfigParsing() {
        val selected = Request.parse(request(), hdrNegotiated = true, receiveCapabilities = caps)
        val config = JSONObject().put("codec", "h265").put("width", 3840).put("height", 2160)
            .put("csd", JSONArray().put(Base64.getEncoder().encodeToString(configuration())))
        for (unicode in listOf(false, true)) {
            val color = HdrVideoColor(metadata()).json()
            if (unicode) color.put("future", "\u00e9\u6c34")
            for (size in listOf(2048, 2049, 2212)) {
                val envelope = sizedEnvelope(color, size)
                assertEquals(size, envelope.toByteArray(Charsets.UTF_8).size)
                if (unicode) assertTrue(envelope.length < size)
                val requestJson = rawMessage(Type.SESSION_REQUEST, request(null), "videoColor", envelope)
                val configJson = rawMessage(Type.STREAM_CONFIG, config, "videoColor", envelope)
                assertEquals(size, StrictJson.encodedSize(requestJson.obj("videoColor")))
                assertEquals(size, StrictJson.encodedSize(configJson.obj("videoColor")))
                assertTrue(requestJson.obj("videoColor").toString().toByteArray(Charsets.UTF_8).size < 2048)
                if (size == 2048) {
                    assertEquals(selected.videoColor, HdrVideoColor.parse(requestJson.obj("videoColor")))
                    assertEquals(selected.videoColor, Request.parse(requestJson,
                        hdrNegotiated = true, receiveCapabilities = caps).videoColor)
                    assertEquals(selected.videoColor, Configuration.video(configJson, selected).videoColor)
                } else {
                    assertEquals("PROTOCOL", assertThrows(ProtocolException::class.java) {
                        HdrVideoColor.parse(requestJson.obj("videoColor"))
                    }.code)
                    assertEquals("PROTOCOL", assertThrows(ProtocolException::class.java) {
                        Request.parse(requestJson, hdrNegotiated = true, receiveCapabilities = caps)
                    }.code)
                    assertEquals("PROTOCOL", assertThrows(ProtocolException::class.java) {
                        Configuration.video(configJson, selected)
                    }.code)
                }
            }
        }
    }

    @Test fun receiveCapabilitiesOriginalUtf8EnvelopeLimitIs2048IncludingFutureVersions() {
        for (version in listOf(1, 2)) {
            for (unicode in listOf(false, true)) {
                val capabilities = caps.json().put("version", version)
                if (unicode) capabilities.put("future", "\u00e9\u6c34")
                for (size in listOf(2048, 2049, 2212)) {
                    val envelope = sizedEnvelope(capabilities, size)
                    assertEquals(size, envelope.toByteArray(Charsets.UTF_8).size)
                    if (unicode) assertTrue(envelope.length < size)
                    val json = rawMessage(Type.HELLO, JSONObject(), "receiveCapabilities", envelope)
                        .obj("receiveCapabilities")
                    assertEquals(size, StrictJson.encodedSize(json))
                    assertTrue(json.toString().toByteArray(Charsets.UTF_8).size < 2048)
                    if (size == 2048) assertEquals(if (version == 1) caps else null, ReceiveCapabilities.parse(json))
                    else assertEquals("PROTOCOL", assertThrows(ProtocolException::class.java) {
                        ReceiveCapabilities.parse(json)
                    }.code)
                }
            }
        }
    }

    @Test fun knownContentLightBoundsFrameAverageWhileZeroRemainsUnknown() {
        for ((content, average) in listOf(400 to 400, 400 to 0, 0 to 1000, 400 to 1000)) {
            val value = metadata().copy(maxContentLightNits = content, maxFrameAverageLightNits = average)
            val color = HdrVideoColor(value).json()
            if (content == 400 && average == 1000) {
                assertEquals("PROTOCOL", assertThrows(ProtocolException::class.java) {
                    HdrStaticMetadata.parse(value.json())
                }.code)
                assertEquals("PROTOCOL", assertThrows(ProtocolException::class.java) {
                    value.descriptor()
                }.code)
                assertEquals("PROTOCOL", assertThrows(ProtocolException::class.java) {
                    Request.parse(request(color), hdrNegotiated = true, receiveCapabilities = caps)
                }.code)
            } else {
                assertEquals(value, HdrStaticMetadata.parse(value.json()))
                assertEquals(value, Request.parse(request(color),
                    hdrNegotiated = true, receiveCapabilities = caps).videoColor?.staticMetadata)
                val descriptor = value.descriptor()
                assertEquals(content, descriptor.getShort(21).toInt() and 65535)
                assertEquals(average, descriptor.getShort(23).toInt() and 65535)
            }
        }
    }

    @Test fun canonicalWindowsFixtureMatchesRootSchemaMetadataAndConfiguration() {
        val fixture = checkNotNull(javaClass.getResourceAsStream("/hdr-video.json"))
            .use { StrictJson.decode(it.readBytes()) }
        assertEquals(Wire.VERSION, fixture.integer("wireVersion"))
        val capabilities = checkNotNull(ReceiveCapabilities.parse(fixture.obj("receiveCapabilities")))
        val color = checkNotNull(HdrVideoColor.parse(fixture.obj("videoColor")))
        assertEquals(ReceiveCapabilities(true, true, true), capabilities)
        assertEquals(HdrVideoColor(metadata()), color)
        assertEquals(fixture.obj("receiveCapabilities").keys().asSequence().toSet(),
            capabilities.json().keys().asSequence().toSet())
        assertEquals(fixture.obj("videoColor").keys().asSequence().toSet(),
            color.json().keys().asSequence().toSet())
        assertEquals(capabilities, ReceiveCapabilities.parse(capabilities.json()))
        assertEquals(color, HdrVideoColor.parse(color.json()))
        val selected = Request.parse(request(fixture.obj("videoColor")),
            hdrNegotiated = true, receiveCapabilities = capabilities)
        assertEquals(color, selected.videoColor)
        val config = JSONObject().put("codec", "h265").put("width", 3840).put("height", 2160)
            .put("csd", JSONArray().put(Base64.getEncoder().encodeToString(configuration())))
            .put("videoColor", fixture.obj("videoColor"))
        assertEquals(color, Configuration.video(config, selected).videoColor)
        val descriptor = checkNotNull(color.staticMetadata).descriptor()
        assertEquals(25, descriptor.remaining())
        assertEquals(1000, descriptor.getShort(17).toInt() and 65535)
        assertEquals(50, descriptor.getShort(19).toInt() and 65535)
    }

    @Test fun everyMasteringXyPairMustBeNormalizedIncludingBoundaryValues() {
        for (pair in 0..3) {
            fun primaries(y: Int) = JSONArray(metadata().masteringPrimaries)
                .put(pair * 2, 50000).put(pair * 2 + 1, y)
            val boundary = metadata().json().put("masteringPrimaries", primaries(0))
            assertEquals(50000, HdrStaticMetadata.parse(boundary).masteringPrimaries[pair * 2])
            val invalid = metadata().json().put("masteringPrimaries", primaries(1))
            assertThrows(ProtocolException::class.java) { HdrStaticMetadata.parse(invalid) }
            assertThrows(ProtocolException::class.java) {
                Request.parse(request(HdrVideoColor().json().put("staticMetadata", invalid)),
                    hdrNegotiated = true, receiveCapabilities = caps)
            }
        }
    }

    @Test fun exactHdrTupleRequiresExplicitNegotiationAndUsableOutput() {
        val json = request()
        assertEquals(HdrVideoColor(metadata()), Request.parse(json, hdrNegotiated = true, receiveCapabilities = caps).videoColor)
        assertThrows(ProtocolException::class.java) { Request.parse(json, receiveCapabilities = caps) }
        listOf(ReceiveCapabilities(true, false), ReceiveCapabilities(true, null), ReceiveCapabilities(false, true)).forEach {
            assertThrows(ProtocolException::class.java) { Request.parse(json, hdrNegotiated = true, receiveCapabilities = it) }
        }
        assertNotNull(Request.parse(json, hdrNegotiated = true, receiveCapabilities = ReceiveCapabilities(true, null, true)).videoColor)
        assertNull(Request.parse(request(null)).videoColor)
        json.getJSONObject("video").put("codec", "h264")
        assertThrows(ProtocolException::class.java) { Request.parse(json, hdrNegotiated = true, receiveCapabilities = caps) }
    }

    @Test fun strictTupleAndMetadataTypesBoundsAndUnknownAdditions() {
        HdrVideoColor.mode().keys().forEach { key ->
            val color = HdrVideoColor().json().put(key, if (key == "bitDepth") 8 else "unsupported")
            assertThrows(key, ProtocolException::class.java) { HdrVideoColor.parse(color) }
        }
        listOf("1", 1.0, 2, 0, JSONObject.NULL).forEach {
            assertThrows(ProtocolException::class.java) { HdrVideoColor.parse(HdrVideoColor().json().put("version", it)) }
        }
        val valid = metadata().json()
        listOf("maxMasteringNits", "minMasteringTenThousandths", "maxContentLightNits", "maxFrameAverageLightNits").forEach { key ->
            listOf(-1, "1000", 1000.0, 100000001).forEach { value ->
                assertThrows("$key=$value", ProtocolException::class.java) { HdrStaticMetadata.parse(metadata().json().put(key, value)) }
            }
        }
        assertEquals(metadata(), HdrStaticMetadata.parse(valid.put("future", true)))
        assertEquals(HdrVideoColor(), HdrVideoColor.parse(HdrVideoColor().json().put("future", true)))
        assertThrows(ProtocolException::class.java) {
            HdrStaticMetadata.parse(metadata().json().put("masteringPrimaries", JSONArray().put(1)))
        }
        assertThrows(ProtocolException::class.java) {
            HdrVideoColor.parse(HdrVideoColor().json().put("padding", "x".repeat(2048)))
        }
        assertThrows(ProtocolException::class.java) {
            StrictJson.decode("""{"videoColor":{"version":1,"version":1}}""".toByteArray())
        }
    }

    @Test fun descriptorUsesIdZeroRgbOrderAndCorrectLuminanceUnits() {
        val descriptor = metadata().descriptor()
        assertEquals(25, descriptor.remaining())
        assertEquals(0, descriptor.get().toInt())
        assertEquals(metadata().masteringPrimaries, List(8) { descriptor.short.toInt() and 65535 })
        assertEquals(listOf(1000, 50, 1000, 400), List(4) { descriptor.short.toInt() and 65535 })
        val boundary = metadata().copy(minMasteringTenThousandths = 65535)
        assertEquals(65535, boundary.descriptor().getShort(19).toInt() and 65535)
        assertEquals(boundary, HdrStaticMetadata.parse(boundary.json()))
        assertEquals(HdrVideoColor(boundary), HdrVideoColor.parse(HdrVideoColor(boundary).json()))
        assertEquals(HdrVideoColor(boundary), Request.parse(request(HdrVideoColor(boundary).json()),
            hdrNegotiated = true, receiveCapabilities = caps).videoColor)
        val overflow = metadata().copy(minMasteringTenThousandths = 65536)
        assertEquals("UNSUPPORTED_CONFIG", assertThrows(ProtocolException::class.java) {
            HdrStaticMetadata.parse(overflow.json())
        }.code)
        assertEquals("UNSUPPORTED_CONFIG", assertThrows(ProtocolException::class.java) {
            HdrVideoColor.parse(HdrVideoColor(overflow).json())
        }.code)
        assertEquals("UNSUPPORTED_CONFIG", assertThrows(ProtocolException::class.java) {
            overflow.descriptor()
        }.code)
        assertEquals("UNSUPPORTED_CONFIG", assertThrows(ProtocolException::class.java) {
            Request.parse(request(HdrVideoColor(overflow).json()), hdrNegotiated = true, receiveCapabilities = caps)
        }.code)
        val maximumLimited = metadata().copy(maxMasteringNits = 1, minMasteringTenThousandths = 10000)
        assertEquals(maximumLimited, HdrStaticMetadata.parse(maximumLimited.json()))
        assertThrows(ProtocolException::class.java) {
            HdrStaticMetadata.parse(maximumLimited.copy(minMasteringTenThousandths = 10001).json())
        }
    }

    @Test fun discoveryCapabilitiesBoundedFutureVersionAndUnknownNativeAreNotOptimistic() {
        assertEquals(caps, ReceiveCapabilities.parse(caps.json()))
        assertNull(ReceiveCapabilities.parse(JSONObject().put("version", 2)))
        assertFalse(ReceiveCapabilities.parse(ReceiveCapabilities(true, null).json())!!.acceptsHdr)
        assertFalse(ReceiveCapabilities.parse(ReceiveCapabilities(true, false).json())!!.acceptsHdr)
        assertTrue(ReceiveCapabilities.parse(ReceiveCapabilities(true, null, true).json())!!.acceptsHdr)
        assertThrows(ProtocolException::class.java) { ReceiveCapabilities.parse(caps.json().put("version", "1")) }
        assertThrows(ProtocolException::class.java) { ReceiveCapabilities.parse(caps.json().put("nativeHdrAvailable", "true")) }
        val modes = JSONArray()
        repeat(17) { modes.put(HdrVideoColor.mode()) }
        assertThrows(ProtocolException::class.java) { ReceiveCapabilities.parse(caps.json().put("videoModes", modes)) }
        val advertisement = JSONObject().put("protocol", "screencopy").put("version", 3).put("name", "Receiver")
            .put("port", 12345).put("state", "available").put("receiveCapabilities", caps.json())
        assertEquals(caps, dev.screencopy.sender.Discovery.decode(advertisement.toString().toByteArray(), "192.0.2.1", 0).receiveCapabilities)
        advertisement.remove("receiveCapabilities")
        assertNull(dev.screencopy.sender.Discovery.decode(advertisement.toString().toByteArray(), "192.0.2.1", 0).receiveCapabilities)
    }

    @Test fun preparationExtensionAppliesOnlyToExplicitVersionOne() {
        assertEquals(45000, HdrPreparationDeadline.milliseconds(1))
        listOf(null, 0, 2).forEach { assertEquals(10000, HdrPreparationDeadline.milliseconds(it)) }
        assertFalse(HdrPreparationDeadline.expired(1, 11000, 44999))
        assertTrue(HdrPreparationDeadline.expired(1, 0, 45000))
        assertTrue(HdrPreparationDeadline.expired(1, 45000, 0))
        listOf(null, 2).forEach {
            assertFalse(HdrPreparationDeadline.expired(it, 9999, 9999))
            assertTrue(HdrPreparationDeadline.expired(it, 0, 10000))
        }
    }

    @Test fun hardwareProbeMain10SpsAndSeiMatchNegotiatedHdrMetadata() {
        val color = HdrVideoColor(metadata())
        val format = VideoFormat("video/hevc", 3840, 2160, listOf(configuration()), 60, videoColor = color)
        HdrBitstream.validateConfiguration(format)
        HdrBitstream.Admission(format).validate(configuration())
        assertThrows(ProtocolException::class.java) {
            HdrBitstream.validateConfiguration(format.copy(width = 1920))
        }
        assertThrows(ProtocolException::class.java) {
            HdrBitstream.validateConfiguration(format.copy(videoColor = HdrVideoColor(metadata().copy(maxContentLightNits = 999))))
        }
        val main8 = configuration().clone()
        // Change only general_profile_idc in SPS from Main10 (2) to Main (1).
        val spsOffset = 4 + hex(VPS).size + 4
        main8[spsOffset + 3] = 0x21
        assertThrows(ProtocolException::class.java) { HdrBitstream.validateConfiguration(format.copy(configuration = listOf(main8))) }
        assertThrows(ProtocolException::class.java) { HdrBitstream.Admission(format).validate(main8) }
    }

    @Test fun masteringSeiMismatchReportsExactCoordinatesAndLuminanceBeyondChromaQuantization() {
        val actual = metadata()
        val mismatches = listOf(
            actual.copy(masteringPrimaries = actual.masteringPrimaries.mapIndexed { index, value -> if (index == 4) value + 2 else value }),
            actual.copy(maxMasteringNits = actual.maxMasteringNits + 1),
            actual.copy(minMasteringTenThousandths = actual.minMasteringTenThousandths + 1)
        )
        for (expected in mismatches) {
            val format = VideoFormat("video/hevc", 3840, 2160, listOf(configuration()), 60, videoColor = HdrVideoColor(expected))
            val message = "HDR mastering SEI differs from negotiated metadata: " +
                "expected_primaries=${expected.masteringPrimaries} actual_primaries=${actual.masteringPrimaries} " +
                "chromaticity_denominator=50000 " +
                "expected_max_luminance=${expected.maxMasteringNits * 10000L} actual_max_luminance=${actual.maxMasteringNits * 10000L} " +
                "expected_min_luminance=${expected.minMasteringTenThousandths} actual_min_luminance=${actual.minMasteringTenThousandths} " +
                "luminance_denominator=10000"
            val configurationError = assertThrows(ProtocolException::class.java) { HdrBitstream.validateConfiguration(format) }
            val admissionError = assertThrows(ProtocolException::class.java) { HdrBitstream.Admission(format).validate(configuration()) }
            for (error in listOf(configurationError, admissionError)) {
                assertEquals("UNSUPPORTED_CONFIG", error.code)
                assertEquals(message, error.message)
            }
        }
    }

    @Test fun reconstructedNvidiaMasteringFixtureAcceptsOneChromaQuantumWithExactLuminance() {
        val expected = HdrStaticMetadata(listOf(34087, 15913, 12954, 34887, 7087, 2308, 15635, 16450), 2353, 0, 0, 0)
        val format = VideoFormat("video/hevc", 3840, 2400, listOf(nvidiaConfiguration()), 120, videoColor = HdrVideoColor(expected))
        HdrBitstream.validateConfiguration(format)
        HdrBitstream.validateConfiguration(format.copy(configuration = listOf(nvidiaConfiguration() + nvidiaStaticSei())))
        HdrBitstream.Admission(format).validate(nvidiaConfiguration() + nvidiaStaticSei())
    }

    @Test fun encodedChromaToleranceIsOneIntegerQuantumInBothDirectionsAndKeepsNormalizedBounds() {
        val expected = HdrStaticMetadata(listOf(34087, 15913, 12954, 34887, 7087, 2308, 15635, 16450), 2353, 0, 0, 0)
        val format = VideoFormat("video/hevc", 3840, 2400, listOf(nvidiaConfiguration()), 120, videoColor = HdrVideoColor(expected))
        val encodedOrder = listOf(4, 5, 0, 1, 2, 3, 6, 7)
        for (coordinate in 0..7) {
            for (delta in -2..2) {
                val packet = mutateNvidiaMastering {
                    putShort(4 + encodedOrder[coordinate] * 2, (expected.masteringPrimaries[coordinate] + delta).toShort())
                }
                if (delta in -1..1 && !(coordinate < 2 && delta > 0)) {
                    HdrBitstream.Admission(format).validate(packet)
                } else {
                    assertEquals("coordinate=$coordinate delta=$delta", "UNSUPPORTED_CONFIG",
                        assertThrows(ProtocolException::class.java) { HdrBitstream.Admission(format).validate(packet) }.code)
                }
            }
        }
        for (invalid in listOf(50001, 65535)) {
            assertThrows(ProtocolException::class.java) {
                HdrBitstream.Admission(format).validate(mutateNvidiaMastering { putShort(8, invalid.toShort()) })
            }
        }
    }

    @Test fun chromaQuantizationNeverRelaxesLuminanceLightLevelsOrWireMetadataEquality() {
        val expected = HdrStaticMetadata(listOf(34087, 15913, 12954, 34887, 7087, 2308, 15635, 16450), 2353, 0, 0, 0)
        val format = VideoFormat("video/hevc", 3840, 2400, listOf(nvidiaConfiguration()), 120, videoColor = HdrVideoColor(expected))
        for (packet in listOf(
            mutateNvidiaMastering { putInt(20, 23530001) },
            mutateNvidiaMastering { putInt(24, 1) }
        )) {
            assertEquals("UNSUPPORTED_CONFIG",
                assertThrows(ProtocolException::class.java) { HdrBitstream.Admission(format).validate(packet) }.code)
        }
        for (metadata in listOf(expected.copy(maxContentLightNits = 1), expected.copy(maxFrameAverageLightNits = 1))) {
            val error = assertThrows(ProtocolException::class.java) {
                HdrBitstream.Admission(format.copy(videoColor = HdrVideoColor(metadata))).validate(nvidiaStaticSei())
            }
            assertEquals("HDR light-level SEI differs from negotiated metadata", error.message)
        }
        val selected = Request.parse(request(HdrVideoColor(expected).json()), hdrNegotiated = true, receiveCapabilities = caps)
        val wireChange = expected.copy(masteringPrimaries = expected.masteringPrimaries.mapIndexed { index, value -> if (index == 4) value - 1 else value })
        val config = JSONObject().put("codec", "h265").put("width", 3840).put("height", 2400)
            .put("csd", JSONArray().put(Base64.getEncoder().encodeToString(nvidiaConfiguration())))
            .put("videoColor", HdrVideoColor(wireChange).json())
        assertThrows(ProtocolException::class.java) { Configuration.video(config, selected) }
    }

    @Test fun spsRequiresExactRawMatrixAndBoundsReferenceSetsBeforeWalkingThem() {
        fun format(matrix: Int = 9, referenceSets: Int = 0, referencePictures: Int = 0): VideoFormat {
            val bits = StringBuilder()
            fun fixed(value: Int, count: Int) { bits.append(value.toString(2).padStart(count, '0')) }
            fun ue(value: Int) {
                val code = (value + 1).toString(2)
                bits.append("0".repeat(code.length - 1)).append(code)
            }
            fixed(0, 4); fixed(0, 3); fixed(1, 1)
            fixed(0, 2); fixed(0, 1); fixed(2, 5)
            bits.append("0".repeat(80)); fixed(153, 8)
            ue(0); ue(1); ue(3840); ue(2160); fixed(0, 1)
            ue(2); ue(2); ue(4); fixed(0, 1)
            ue(4); ue(0); ue(0)
            ue(0); ue(3); ue(0); ue(3); ue(0); ue(0)
            fixed(0, 1); fixed(1, 1); fixed(1, 1); fixed(0, 1)
            ue(referenceSets)
            if (referenceSets in 1..64) {
                ue(referencePictures); ue(0)
                repeat(referencePictures) { ue(0); fixed(1, 1) }
            }
            fixed(0, 1); fixed(1, 1); fixed(1, 1); fixed(1, 1)
            fixed(0, 1); fixed(0, 1); fixed(1, 1)
            fixed(5, 3); fixed(0, 1); fixed(1, 1)
            fixed(9, 8); fixed(16, 8); fixed(matrix, 8)
            fixed(1, 1)
            while (bits.length % 8 != 0) fixed(0, 1)
            val rbsp = bits.toString().chunked(8).map { it.toInt(2).toByte() }
            val escaped = mutableListOf<Byte>()
            var zeros = 0
            rbsp.forEach {
                if (zeros == 2 && (it.toInt() and 255) <= 3) { escaped.add(3); zeros = 0 }
                escaped.add(it)
                zeros = if (it == 0.toByte()) zeros + 1 else 0
            }
            val config = listOf(hex(VPS), byteArrayOf(0x42, 1) + escaped.toByteArray(), hex(PPS))
                .fold(byteArrayOf()) { result, nal -> result + byteArrayOf(0, 0, 0, 1) + nal }
            return VideoFormat("video/hevc", 3840, 2160, listOf(config), 60, videoColor = HdrVideoColor())
        }
        HdrBitstream.validateConfiguration(format())
        val wrongMatrix = assertThrows(ProtocolException::class.java) {
            HdrBitstream.validateConfiguration(format(matrix = 10))
        }
        assertEquals("UNSUPPORTED_CONFIG", wrongMatrix.code)
        listOf(format(referenceSets = 65), format(referenceSets = 1, referencePictures = 33)).forEach {
            assertEquals("PROTOCOL", assertThrows(ProtocolException::class.java) {
                HdrBitstream.validateConfiguration(it)
            }.code)
        }
        val truncated = byteArrayOf(0, 0, 0, 1) + hex(VPS) + byteArrayOf(0, 0, 0, 1, 0x42, 1) +
            byteArrayOf(0, 0, 0, 1) + hex(PPS)
        assertEquals("PROTOCOL", assertThrows(ProtocolException::class.java) {
            HdrBitstream.validateConfiguration(format().copy(configuration = listOf(truncated)))
        }.code)
    }

    @Test fun eachConfigMustRepeatExactlyTheAcceptedColorIncludingMetadata() {
        val requested = Request.parse(request(), hdrNegotiated = true, receiveCapabilities = caps)
        val config = JSONObject().put("codec", "h265").put("width", 3840).put("height", 2160)
            .put("csd", JSONArray().put(Base64.getEncoder().encodeToString(configuration())))
            .put("videoColor", HdrVideoColor(metadata()).json())
        assertEquals(requested.videoColor, Configuration.video(config, requested).videoColor)
        config.remove("videoColor")
        assertThrows(ProtocolException::class.java) { Configuration.video(config, requested) }
        config.put("videoColor", HdrVideoColor().json())
        assertThrows(ProtocolException::class.java) { Configuration.video(config, requested) }
        config.put("videoColor", HdrVideoColor(metadata()).json())
        assertThrows(ProtocolException::class.java) { Configuration.video(config, Request.parse(request(null))) }
    }

    companion object {
        private const val VPS = "40010C01FFFF222000000300900000030000030099AC09"
        private const val SPS = "420101222000000300900000030000030099A001E02002207C4B65AD2908464B0C050848804820000003002000000781"
        private const val PPS = "4401C1F3C0CC90"
        private const val MASTERING = "4E01891921349BAA199608FC8A4839083D1340420098968000000300328080"
        private const val LIGHT = "4E01900503E801908080"
        // Production NVIDIA synthetic black P010 output using a reconstructed metadata fixture, not a phone capture.
        private const val NVIDIA_VPS = "40010C01FFFF2220000003009000000300000300B7AC09"
        private const val NVIDIA_SPS = "4201012220000003009000000300000300B7A001E02002584D96B4A421192C30142122012080000003008000003C04"
        private const val NVIDIA_MASTERING = "4E018919329A88471BAE090485273E293D13404201670A1000000300008080"
        private const val NVIDIA_LIGHT = "4E01900500000300008080"
        private fun hex(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        private fun annexB(vararg units: String) = units.fold(byteArrayOf()) { result, nal -> result + byteArrayOf(0, 0, 0, 1) + hex(nal) }
        private fun nvidiaConfiguration() = annexB(NVIDIA_VPS, NVIDIA_SPS, PPS)
        private fun nvidiaStaticSei() = annexB(NVIDIA_MASTERING, NVIDIA_LIGHT)
        private fun mutateNvidiaMastering(change: java.nio.ByteBuffer.() -> Unit): ByteArray {
            val raw = dev.screencopy.sender.CodecConfig.rbsp(hex(NVIDIA_MASTERING))
            java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.BIG_ENDIAN).apply(change)
            val escaped = mutableListOf<Byte>()
            var zeros = 0
            raw.drop(2).forEach {
                if (zeros == 2 && (it.toInt() and 255) <= 3) { escaped.add(3); zeros = 0 }
                escaped.add(it)
                zeros = if (it == 0.toByte()) zeros + 1 else 0
            }
            return byteArrayOf(0, 0, 0, 1, 0x4e, 1) + escaped.toByteArray()
        }
        fun configuration(): ByteArray = listOf(VPS, SPS, PPS, MASTERING, LIGHT)
            .fold(byteArrayOf()) { result, nal -> result + byteArrayOf(0, 0, 0, 1) + hex(nal) }
    }
}

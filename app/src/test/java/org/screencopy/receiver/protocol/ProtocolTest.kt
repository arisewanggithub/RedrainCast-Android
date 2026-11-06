package org.screencopy.receiver.protocol

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream

class ProtocolTest {
    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)).use { it.readBytes() }
    private fun hex(text: String) = text.trim().chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun json(text: String) = StrictJson.decode(text.toByteArray())

    @Test fun exactSharedWireFixtures() {
        val cases = StrictJson.decode(fixture("v3.json")).array("cases")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val bytes = hex(case.string("hex"))
            if (case.boolean("valid")) {
                val packet = Wire.read(ByteArrayInputStream(bytes))
                val encoded = ByteArrayOutputStream().also { Wire.write(it, packet) }.toByteArray()
                assertArrayEquals(case.string("name"), bytes, encoded)
            } else {
                try {
                    Wire.read(ByteArrayInputStream(bytes))
                    fail("Accepted invalid shared fixture ${case.string("name")}")
                } catch (_: ProtocolException) {
                    // Invalid fields are rejected before payload allocation.
                } catch (_: EOFException) {
                    // Truncation must never become a valid packet.
                }
            }
        }
    }

    @Test fun readsAcrossEveryFragmentBoundaryAndCoalescedFrames() {
        val message = Message.json(Type.PING, JSONObject().put("nonce", 123), 23)
        val bytes = ByteArrayOutputStream().also { Wire.write(it, message); Wire.write(it, message) }.toByteArray()
        for (chunk in 1..bytes.size) {
            val input = object : InputStream() {
                val source = ByteArrayInputStream(bytes)
                override fun read(): Int = source.read()
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = source.read(buffer, offset, minOf(length, chunk))
            }
            assertEquals(123, Wire.read(input).json().integer("nonce"))
            assertEquals(123, Wire.read(input).json().integer("nonce"))
            assertEquals(-1, input.read())
        }
    }

    @Test fun allTruncationsAreErrors() {
        val message = Message.json(Type.PING, JSONObject().put("nonce", 1), 1)
        val bytes = ByteArrayOutputStream().also { Wire.write(it, message) }.toByteArray()
        for (length in bytes.indices) {
            assertThrows("length=$length", EOFException::class.java) {
                Wire.read(ByteArrayInputStream(bytes.copyOf(length)))
            }
        }
    }

    @Test fun strictJsonRejectsAmbiguousAndCoercibleInput() {
        listOf(
            """{"x":1,"x":2}""", """{"x":{"a":1,"a":2}}""",
            """{"x":NaN}""", """{"x":01}""", """{"x":+1}""",
            """{"x":2147483648}""", """{"x":1,}""", """{'x':1}""",
            """{"x":"\ud800"}""", """{"x":1}garbage"""
        ).forEach { input ->
            assertThrows(input, ProtocolException::class.java) { json(input) }
        }
        assertThrows(ProtocolException::class.java) { json("""{"x":"1"}""").integer("x") }
        assertThrows(ProtocolException::class.java) { json("""{"x":1.0}""").integer("x") }
        assertThrows(ProtocolException::class.java) { json("""{"x":"true"}""").boolean("x") }
        assertThrows(ProtocolException::class.java) { StrictJson.decode(byteArrayOf(0xc3.toByte(), 0x28)) }
    }

    @Test fun sharedCodeFreeHelloDiscoveryAndInternalSessionSchema() {
        val session = StrictJson.decode(fixture("session.json"))
        val sender = session.obj("senderHello")
        val receiver = session.obj("receiverHello")
        assertEquals("sender", sender.string("role"))
        assertEquals("receiver", receiver.string("role"))
        assertEquals(3, sender.array("versions").getInt(0))
        assertEquals(3, receiver.array("versions").getInt(0))
        assertEquals(setOf("role", "versions", "name"), sender.keys().asSequence().toSet())
        assertEquals(setOf("role", "versions", "name"), receiver.keys().asSequence().toSet())
        assertTrue(session.integer("requestSessionId") > 0)
        assertEquals("BUSY", session.obj("busyReject").string("code"))
        val discovery = StrictJson.decode(fixture("discovery.json"))
        assertEquals("screencopy", discovery.string("protocol"))
        assertEquals(3, discovery.integer("version"))
        assertEquals(27184, discovery.integer("port"))
        assertEquals("available", discovery.string("state"))
        listOf("code", "sessionCode", "receiverId", "senderId", "pairingSecret", "credential", "fingerprint", "host").forEach {
            assertFalse(discovery.has(it))
        }
    }

    @Test fun obsoleteTlsMagicVersionAndAuthAreRejectedBeforeTheRestOfTheHeader() {
        val tlsClientHelloPrefix = byteArrayOf(0x16, 0x03, 0x01, 0x00)
        assertThrows(ProtocolException::class.java) { Wire.read(ByteArrayInputStream(tlsClientHelloPrefix)) }
        for (oldVersion in listOf("534350590001", "534350590002")) {
            val rejected = assertThrows(ProtocolException::class.java) { Wire.read(ByteArrayInputStream(hex(oldVersion))) }
            assertEquals("VERSION", rejected.code)
        }
        assertThrows(ProtocolException::class.java) { Wire.read(ByteArrayInputStream(hex("5343505900030002"))) }
    }

    @Test fun canonicalOpusHeadPreskipAndGain() {
        val bytes = hex(String(fixture("opus-head.hex")))
        assertEquals(312 to 0, Configuration.opusHead(bytes, 2))
        val gain = bytes.clone().apply { this[16] = 0x80.toByte(); this[17] = 0xff.toByte() }
        assertEquals(312 to -128, Configuration.opusHead(gain, 2))
        assertThrows(ProtocolException::class.java) { Configuration.opusHead(bytes, 1) }
        assertThrows(ProtocolException::class.java) { Configuration.opusHead(bytes + 0, 2) }
    }

    @Test fun independentGenerationsAndStartupGate() {
        val state = TrackState(listOf(1, 2))
        state.configure(1, 1)
        state.ready(1)
        assertThrows(ProtocolException::class.java) { state.media(media(1, 1, 1_000_000)) }
        state.configure(2, 1)
        state.ready(2)
        state.media(media(1, 1, 1_000_000))
        state.media(media(2, 1, 1_006_500))
        state.configure(1, 2)
        state.media(media(2, 1, 1_026_500))
        assertThrows(ProtocolException::class.java) { state.media(media(1, 1, 1_033_333)) }
        state.ready(1)
        state.media(media(1, 2, 1_033_333))
        assertThrows(ProtocolException::class.java) { state.media(media(2, 1, 1_026_499)) }
        assertThrows(ProtocolException::class.java) { state.configure(1, 4) }
    }

    @Test fun audioDisabledCannotAcquireAnAudioTrack() {
        val state = TrackState(listOf(1))
        assertThrows(ProtocolException::class.java) { state.configure(2, 1) }
    }

    @Test fun opusDelayAndPcmFormatAreNotSilentlyChanged() {
        val request = Request("h264", "opus", 60, "")
        val config = json("""{"codec":"opus","sampleRate":48000,"channels":2,"csd":["T3B1c0hlYWQBAjgBgLsAAAAAAA=="],"codecDelayNs":6500000,"seekPrerollNs":80000000}""")
        assertEquals(312, Configuration.audio(config, request).preSkip)
        config.put("codecDelayNs", 6500001)
        assertThrows(ProtocolException::class.java) { Configuration.audio(config, request) }
        val raw = json("""{"codec":"raw","sampleRate":48000,"channels":2,"csd":[],"sampleFormat":"f32le"}""")
        assertThrows(ProtocolException::class.java) { Configuration.audio(raw, request.copy(audioCodec = "raw")) }
    }

    @Test fun exactWireLengthLimitsAndPreAllocationRejection() {
        val messages = listOf(
            Message(Type.VIDEO_FRAME, 1, 1, 1, 0, payload = ByteArray(4 * 1024 * 1024)),
            Message(Type.AUDIO_PACKET, 1, 2, 1, 0, payload = ByteArray(262144)),
            Message(Type.STATUS, 1, payload = """{"x":"${"a".repeat(65536 - 8)}"}""".toByteArray())
        )
        messages.forEach { message ->
            val encoded = ByteArrayOutputStream().also { Wire.write(it, message) }.toByteArray()
            assertEquals(message.payload.size, Wire.read(ByteArrayInputStream(encoded)).payload.size)
            val header = encoded.copyOf(40)
            java.nio.ByteBuffer.wrap(header).putInt(8, message.payload.size + 1)
            assertThrows(ProtocolException::class.java) { Wire.read(ByteArrayInputStream(header)) }
        }
    }

    @Test fun generationUsesSenderOwnedSigned32BitRange() {
        val packet = media(1, Int.MAX_VALUE.toLong(), 1)
        val encoded = ByteArrayOutputStream().also { Wire.write(it, packet) }.toByteArray()
        assertEquals(Int.MAX_VALUE.toLong(), Wire.read(ByteArrayInputStream(encoded)).generation)
        val header = encoded.copyOf(40)
        java.nio.ByteBuffer.wrap(header).putInt(28, Int.MIN_VALUE)
        assertThrows(ProtocolException::class.java) { Wire.read(ByteArrayInputStream(header)) }
    }

    @Test fun settingsAreAcceptedWithoutReceiverCropOrCodecSubstitution() {
        val requested = Request.parse(json("""{"video":{"codec":"h265","encoder":"","bitrate":8000000,"maxSize":0,"maxFps":60,"displayId":0,"orientation":"@90","crop":{"width":1280,"height":720,"x":10,"y":20},"codecOptions":""},"audio":{"enabled":false,"codec":"opus","encoder":"","bitrate":192000,"source":"output","codecOptions":""}}"""))
        assertEquals(listOf(1), requested.tracks)
        val actual = Configuration.video(json("""{"codec":"h265","width":720,"height":1280,"csd":["AAAAAUAB"]}"""), requested)
        assertEquals(720, actual.width)
        assertEquals(1280, actual.height)
        assertEquals(0, actual.rotation)
        assertEquals("video/hevc", actual.mime)
        assertThrows(ProtocolException::class.java) {
            Configuration.video(json("""{"codec":"h264","width":720,"height":1280,"csd":["AAAAAUAB"]}"""), requested)
        }
    }

    @Test fun trackFramesRequirePositiveSessionBeforePayloadAllocation() {
        val messages = listOf(
            Message.json(Type.STREAM_CONFIG, JSONObject().put("codec", "h264"), 1, 1, 1),
            Message.json(Type.STREAM_READY, JSONObject().put("ok", true), 1, 2, 1),
            media(1, 1, 1_000_000),
            media(2, 1, 1_000_000)
        )
        messages.forEach { message ->
            val encoded = ByteArrayOutputStream().also { Wire.write(it, message) }.toByteArray()
            assertEquals(1L, Wire.read(ByteArrayInputStream(encoded)).session)
            assertThrows(ProtocolException::class.java) {
                Wire.write(ByteArrayOutputStream(), message.copy(session = 0))
            }
            val header = encoded.copyOf(40)
            java.nio.ByteBuffer.wrap(header).putLong(16, 0)
            assertThrows(ProtocolException::class.java) { Wire.read(ByteArrayInputStream(header)) }
        }
    }

    @Test fun sharedTrackLifecycleFixtureGatesStartupAndPreservesAudioOnRotation() {
        val fixture = StrictJson.decode(fixture("track-lifecycle.json"))
        val session = fixture.integer("session").toLong()
        val sequence = fixture.array("sequence")
        val state = TrackState(listOf(1, 2))
        val generations = mutableMapOf<Int, Long>()
        val requested = Request("h264", "opus", 60, "")
        for (index in 0 until sequence.length()) {
            val step = sequence.getJSONObject(index)
            when (step.string("direction") to step.string("type")) {
                "sender" to "STREAM_CONFIG" -> {
                    val track = step.integer("track")
                    val generation = step.integer("generation").toLong()
                    assertEquals(-1, step.integer("pts"))
                    val payload = step.obj("payload")
                    if (track == 1) Configuration.video(payload, requested)
                    else Configuration.audio(payload, requested)
                    val message = Message.json(Type.STREAM_CONFIG, payload, session, track, generation)
                    val bytes = ByteArrayOutputStream().also { Wire.write(it, message) }.toByteArray()
                    assertEquals(generation, Wire.read(ByteArrayInputStream(bytes)).generation)
                    state.configure(track, generation)
                    generations[track] = generation
                }
                "receiver" to "STREAM_READY" -> {
                    val track = step.integer("track")
                    assertEquals(generations[track], step.integer("generation").toLong())
                    assertTrue(step.obj("payload").boolean("ok"))
                    state.ready(track)
                }
                "assert" to "MEDIA_BLOCKED" -> {
                    val blocked = if (step.has("tracks")) {
                        val tracks = step.array("tracks")
                        (0 until tracks.length()).map { tracks.getInt(it) }
                    } else listOf(1, 2)
                    blocked.forEach { track ->
                        assertThrows(ProtocolException::class.java) {
                            state.media(media(track, checkNotNull(generations[track]), 1_000_000))
                        }
                    }
                    if (generations[1] == 2L) state.media(media(2, 1, 1_000_000))
                }
                "assert" to "MEDIA_ALLOWED" -> {
                    val tracks = step.array("tracks")
                    repeat(tracks.length()) {
                        val track = tracks.getInt(it)
                        state.media(media(track, checkNotNull(generations[track]), 1_000_000))
                    }
                }
                else -> fail("Unsupported lifecycle fixture step")
            }
        }
        assertTrue(state.allReady())
        state.media(media(1, 2, 1_020_000))
        state.media(media(2, 1, 1_020_000))
        assertThrows(ProtocolException::class.java) { state.media(media(1, 1, 1_020_000)) }
    }

    private fun media(track: Int, generation: Long, pts: Long) =
        Message(if (track == 1) Type.VIDEO_FRAME else Type.AUDIO_PACKET, 1, track, generation, pts, payload = byteArrayOf(1))
}

package dev.screencopy.sender

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class HardwareEncodersTest {
    private fun encoder(name: String, codec: String = "h264", hardware: Boolean = true,
                        software: Boolean = false, isEncoder: Boolean = true, canonical: String = name,
                        alias: Boolean = false) = EncoderMetadata(name, canonical,
        listOf(HardwareEncoderPolicy.mimeTypes.getValue(codec)), isEncoder, hardware, software, alias)
    private fun backend(codec: String, name: String): String {
        val track = if (codec in setOf("h264", "h265", "av1")) "video" else "audio"
        return "    --$track-codec=$codec --$track-encoder='$name'"
    }

    @Test fun onlyVideoRequiresOfficialHardwareFlagsAndAllTracksRequireEncoderMimeCompatibility() {
        for (codec in HardwareEncoderPolicy.mimeTypes.keys) {
            val good = encoder("arbitrary.vendor", codec)
            assertTrue(HardwareEncoderPolicy.eligible(good, codec))
            val audio = HardwareEncoderPolicy.mimeTypes.getValue(codec).startsWith("audio/")
            assertEquals(audio, HardwareEncoderPolicy.eligible(encoder("c2.qti.fake", codec, hardware = false), codec))
            assertEquals(audio, HardwareEncoderPolicy.eligible(encoder("vendor.inconsistent", codec, software = true), codec))
            assertFalse(HardwareEncoderPolicy.eligible(encoder("vendor.decoder", codec, isEncoder = false), codec))
            assertEquals(audio, HardwareEncoderPolicy.eligible(encoder("c2.android.software", codec, false, true), codec))
            assertFalse(HardwareEncoderPolicy.eligible(good.copy(supportedTypes = listOf("wrong/mime")), codec))
            assertTrue(HardwareEncoderPolicy.eligible(good.copy(supportedTypes =
                good.supportedTypes.map { it.uppercase() }), codec))
        }
        assertFalse(HardwareEncoderPolicy.eligible(encoder("raw", "aac"), "raw"))
    }

    @Test fun reportRetainsSoftwareAudioFlagsWithoutMakingItsVideoMimeEligible() {
        val mixed = encoder("mixed.software", "opus", hardware = false, software = true).copy(
            supportedTypes = listOf("audio/opus", "video/avc"))
        val inventory = HardwareEncoders(listOf(encoder("hardware"), encoder("software", hardware = false, software = true),
            encoder("unknown", hardware = false), encoder("inconsistent", software = true),
            encoder("decoder", isEncoder = false), mixed, encoder("aac.software", "aac", false, true)))
        val restored = HardwareEncoders.fromReport(inventory.report())
        assertEquals(listOf("hardware"), restored.names("h264"))
        assertEquals(listOf("mixed.software"), restored.names("opus"))
        assertEquals(listOf("aac.software"), restored.names("aac"))
        assertEquals(listOf(mixed), restored.candidates("opus"))
        assertTrue(restored.names("raw").isEmpty())
    }

    @Test fun aliasesPreserveExactExplicitNamesAndBackendOrderingForAutomatic() {
        val primary = encoder("primary")
        val alias = encoder("alias", canonical = "primary", alias = true)
        val next = encoder("next")
        val hardware = HardwareEncoders(listOf(primary, alias, next))
        val backend = listOf(backend("h264", "next"), backend("h264", "alias"), backend("h264", "primary")).joinToString("\n")
        val listed = HardwareEncoders.fromReport(hardware.report()).listedBy(backend)
        assertEquals("next", listed.resolve("h264", ""))
        assertEquals("alias", listed.resolve("h264", "alias"))
        assertEquals(listOf("next", "alias", "primary"), listed.names("h264"))
    }

    @Test fun backendIntersectionMatchesExactTokensAndRemovesSoftwareListings() {
        val hardware = HardwareEncoders(listOf(encoder("vendor"), encoder("vendor.extra"),
            encoder("soft", hardware = false, software = true), encoder("opus.soft", "opus", false, true)))
        val backend = backend("h264", "vendor.extra") + "\n" + backend("h264", "soft") +
            "\n" + backend("opus", "opus.soft") + "\nDisplays:"
        val filtered = hardware.filterBackendReport(backend)
        assertFalse(filtered.contains("'soft'"))
        assertTrue(filtered.contains("Displays:"))
        assertTrue(filtered.contains("'opus.soft'"))
        assertEquals(listOf("vendor.extra"), hardware.listedBy(backend).names("h264"))
        assertEquals("opus.soft", hardware.listedBy(backend).resolve("opus", ""))
        assertEquals("opus.soft", hardware.listedBy(backend).resolve("opus", "opus.soft"))
        assertThrows(IOException::class.java) { hardware.listedBy(backend).resolve("h264", "vendor") }
        assertTrue(hardware.listedBy(backend("h265", "vendor")).names("h264").isEmpty())
    }

    @Test fun backendIntersectionCannotAuthorizeAnUnlistedMimeFromTheSameEncoderName() {
        val shared = encoder("shared").copy(supportedTypes = listOf("video/avc", "audio/opus"))
        val inventory = HardwareEncoders(listOf(shared))
        val videoOnly = inventory.listedBy(backend("h264", "shared"))
        assertEquals("shared", videoOnly.resolve("h264", ""))
        assertThrows(IOException::class.java) { videoOnly.resolve("opus", "shared") }
        val audioOnly = inventory.listedBy(backend("opus", "shared"))
        assertEquals("shared", audioOnly.resolve("opus", ""))
        assertThrows(IOException::class.java) { audioOnly.resolve("h264", "shared") }
    }

    @Test fun softwareAudioAutomaticAndManualAreAcceptedWithoutChangingCodecOrSavedChoices() {
        val hardware = HardwareEncoders(listOf(encoder("video"), encoder("old.software", hardware = false, software = true),
            encoder("aac.hardware", "aac"),
            encoder("opus.software", "opus", false, true)))
        val settings = Settings().apply { audioCodec = "opus" }
        val before = settings.request().toString()
        val automatic = hardware.resolve(settings)
        assertEquals("video", automatic.videoEncoder)
        assertEquals("opus.software", automatic.audioEncoder)
        assertEquals("opus", automatic.audioCodec)
        assertTrue(automatic.audio)
        assertEquals(before, settings.request().toString())
        settings.audioEncoder = "opus.software"
        assertEquals("opus.software", hardware.resolve(settings).audioEncoder)
        settings.videoEncoder = "old.software"
        assertThrows(IOException::class.java) { hardware.resolve(settings) }
        settings.videoEncoder = ""
        settings.audioCodec = "aac"
        settings.audioEncoder = ""
        val resolved = hardware.resolve(settings)
        assertEquals("video", resolved.videoEncoder)
        assertEquals("aac.hardware", resolved.audioEncoder)
        assertEquals("", settings.videoEncoder)
        assertEquals("", settings.audioEncoder)
        assertTrue(settings.audio)
    }

    @Test fun absentOrWrongMimeAudioFailsWithoutAacAudioOffOrEncoderSubstitution() {
        val inventory = HardwareEncoders(listOf(encoder("video"), encoder("aac.software", "aac", false, true)))
        val settings = Settings().apply { audioCodec = "opus" }
        val before = settings.request().toString()
        val failure = assertThrows(IOException::class.java) { inventory.resolve(settings) }
        assertTrue(failure.message!!.contains("No compatible opus encoder"))
        assertEquals(before, settings.request().toString())
        settings.audioEncoder = "aac.software"
        assertThrows(IOException::class.java) { inventory.resolve(settings) }
        settings.audioCodec = "aac"
        assertEquals("aac.software", inventory.resolve(settings).audioEncoder)
    }

    @Test fun audioAutomaticPreservesBackendPriorityAndUsesTheSameSoftwareBitrateRangeAsManual() {
        val inventory = HardwareEncoders(listOf(encoder("video"), encoder("aac.hardware", "aac"),
            encoder("aac.software", "aac", false, true)))
        val listing = backend("h264", "video") + "\n" + backend("aac", "aac.software") +
            "\n" + backend("aac", "aac.hardware")
        val listed = HardwareEncoders.fromReport(inventory.report()).listedBy(listing)
        val settings = Settings().apply { audioCodec = "aac"; audioBitrate = 128_000 }
        val automatic = listed.resolve(settings)
        assertEquals("aac.software", automatic.audioEncoder)
        val ranges = CodecBitrates(listOf(EncoderBitrate("h264", "video", 100_000, 20_000_000),
            EncoderBitrate("aac", "aac.software", 64_000, 128_000),
            EncoderBitrate("aac", "aac.hardware", 64_000, 512_000)))
        Bitrates.validate(automatic, ranges)
        settings.audioEncoder = "aac.software"
        Bitrates.validate(listed.resolve(settings), ranges)
        settings.audioEncoder = ""; settings.audioBitrate = 192_000
        assertThrows(IllegalArgumentException::class.java) { Bitrates.validate(listed.resolve(settings), ranges) }
        settings.audioEncoder = "aac.software"
        assertThrows(IllegalArgumentException::class.java) { Bitrates.validate(listed.resolve(settings), ranges) }
    }

    @Test fun automaticChecksChosenBitrateAndGeometryNotUnionOrCompatibleFallback() {
        val hardware = HardwareEncoders(listOf(encoder("first"), encoder("second")))
        val settings = Settings().apply { audio = false }
        val resolved = hardware.resolve(settings)
        assertEquals("first", resolved.videoEncoder)
        val ranges = CodecBitrates(listOf(EncoderBitrate("h264", "first", 100_000, 1_000_000),
            EncoderBitrate("h264", "second", 100_000, 20_000_000)))
        assertThrows(IllegalArgumentException::class.java) { Bitrates.validate(resolved, ranges) }
        assertEquals("first", hardware.resolve(settings).videoEncoder)
        val geometry = VideoGeometry(VideoSize(1920, 1080), VideoSize(1920, 1080), 1920, 1920, 60)
        val limits = VideoLimits(16, 1920, 16, 1080, 2, 2, 1.0, 30.0)
        assertThrows(VideoGeometryException::class.java) {
            VideoGeometryValidation.validate(geometry,
                listOf(EncoderGeometryCheck(limits, true, false)), automatic = false)
        }
        assertEquals("", settings.videoEncoder)
        assertEquals(8_000_000, settings.videoBitrate)
        assertEquals(60, settings.maxFps)
    }

    @Test fun rawAudioBypassesEncodingWithoutInventingHardwareAndUnavailableInventoryFailsClosed() {
        val hardware = HardwareEncoders(listOf(encoder("video")))
        val settings = Settings()
        assertEquals("raw", settings.audioCodec)
        assertEquals("", hardware.resolve(settings).audioEncoder)
        assertTrue(hardware.names("raw").isEmpty())
        assertThrows(IllegalStateException::class.java) { HardwareEncoders.fromReport("legacy backend report") }
        assertThrows(IOException::class.java) { HardwareEncoders(emptyList()).resolve("h265", "") }
    }
}

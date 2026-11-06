package dev.screencopy.sender

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Path
import java.util.Locale

class BitratesTest {
    private fun ranges(codec: String = "opus", name: String = "encoder", min: Int = 64_000, max: Int = 192_000) =
        CodecBitrates(listOf(EncoderBitrate(codec, name, min, max)))

    @Test fun persistedBpsDisplayAsMbpsAndRoundTripWithoutDoubleScaling() {
        assertEquals("8", Bitrates.videoMbps(Settings().videoBitrate))
        for (bps in listOf(100_000, 100_001, 999_999, 1_000_001, 8_000_000, 12_345_678, 200_000_000))
            assertEquals(bps, Bitrates.videoBps(Bitrates.videoMbps(bps)))
        val saved = Settings.from(Settings().request())
        assertEquals(8_000_000, saved.videoBitrate)
        assertEquals(192_000, saved.audioBitrate)
    }
    @Test fun decimalRatesAreExactIncludingOneBitFractionAndDecimalComma() {
        assertEquals(2_500_000, Bitrates.videoBps("2.5"))
        assertEquals(2_000_001, Bitrates.videoBps("2.000001"))
        assertEquals(2_000_001, Bitrates.videoBps("2,000001"))
        assertEquals(500_000, Bitrates.videoBps(".5"))
        assertEquals(8_000_000, Bitrates.videoBps(" 8. "))
    }
    @Test fun conversionIsLocaleIndependentAndNeverUsesGrouping() {
        val before = Locale.getDefault()
        try {
            for (locale in listOf(Locale.US, Locale.GERMANY, Locale.FRANCE)) {
                Locale.setDefault(locale)
                assertEquals("1.234567", Bitrates.videoMbps(1_234_567))
                assertEquals(1_250_000, Bitrates.videoBps("1,25"))
                assertEquals(1_250_000, Bitrates.videoBps("1.25"))
            }
        } finally { Locale.setDefault(before) }
        for (input in listOf("1,000,000", "1.000,5", "1 000", "1_000"))
            assertThrows(IllegalArgumentException::class.java) { Bitrates.videoBps(input) }
    }
    @Test fun invalidNonfiniteFractionalBitOverflowAndRangeAreExplicitErrors() {
        for (input in listOf("", "NaN", "Infinity", "-1", "+1", "1e2", "0", "0.099999", "200.000001",
            "0.1000001", "2147.483648", "9999999999999999999999999999999999999999999"))
            assertThrows("Accepted invalid Mbps $input", IllegalArgumentException::class.java) { Bitrates.videoBps(input) }
    }
    @Test fun codecRecommendationsAreDiscreteIncludeDefaultAndDoNotInventFlacSupport() {
        assertTrue(192_000 in Bitrates.audioPresets("opus"))
        assertTrue(24_000 in Bitrates.audioPresets("opus"))
        assertFalse(24_000 in Bitrates.audioPresets("aac"))
        assertTrue(Bitrates.audioPresets("raw").isEmpty())
        assertThrows(IllegalArgumentException::class.java) { Bitrates.audioPresets("flac") }
    }
    @Test fun actualListProviderMarksOnlyCodecRecommendationAndLeavesOtherPresetsPlain() {
        for (codec in listOf("opus", "aac")) {
            val expected = if (codec == "aac") 128_000 else 192_000
            val options = Bitrates.audioChoices(codec, "encoder", 128_000, ranges(codec, min = 16_000, max = 512_000))
            val recommended = options.filter { it.label.contains("recommended") }
            assertEquals(1, recommended.size)
            assertEquals(expected, recommended.single().bps)
            assertEquals("${expected / 1000} kbps (recommended)", recommended.single().label)
            assertTrue(options.filter { it.bps != expected }.all { it.label == "${it.bps!! / 1000} kbps" })
            assertEquals(128_000, options.single { it.bps == 128_000 }.bps)
        }
    }
    @Test fun filteredRecommendationIsNotReassignedAndInvalidCurrentDoesNotGetItsMarker() {
        for (codec in listOf("opus", "aac")) {
            val current = if (codec == "aac") 128_000 else 192_000
            for (metadata in listOf(ranges(codec, min = 64_000, max = if (codec == "aac") 96_000 else 128_000),
                ranges(codec, min = 256_000, max = 512_000))) {
                val options = Bitrates.audioChoices(codec, "encoder", current, metadata)
                assertTrue(options.none { it.label.contains("recommended") })
                val retained = options.single { it.bps == current }
                assertFalse(retained.valid); assertTrue(retained.label.contains("invalid"))
            }
        }
    }
    @Test fun rawAndRetainedCustomValueAreNeverMarkedRecommended() {
        for (codec in listOf("opus", "aac")) {
            val options = Bitrates.audioChoices(codec, "encoder", 150_500, ranges(codec))
            assertEquals(1, options.count { it.label.contains("recommended") })
            val retained = options.single { it.bps == 150_500 }
            assertTrue(retained.valid); assertTrue(retained.label.contains("existing"))
            assertFalse(retained.label.contains("recommended"))
        }
        assertTrue(Bitrates.audioChoices("raw", "", 192_000, CodecBitrates()).none { it.label.contains("recommended") })
    }
    @Test fun aac128RecommendationDoesNotRelabel192OrChangeSelectedOrSerializedRates() {
        val settings = Settings().apply { audioCodec = "aac"; audioBitrate = 128_000 }
        for (rate in listOf(128_000, 192_000)) {
            settings.audioBitrate = rate
            val before = settings.request().toString()
            val options = Bitrates.audioChoices("aac", "encoder", settings.audioBitrate, ranges("aac"))
            assertEquals("128 kbps (recommended)", options.single { it.bps == 128_000 }.label)
            assertEquals("192 kbps", options.single { it.bps == 192_000 }.label)
            assertTrue(options.single { it.bps == rate }.valid)
            assertEquals(before, settings.request().toString())
            assertEquals(rate, Settings.from(settings.request()).audioBitrate)
        }
        assertEquals("raw", Settings().audioCodec)
        assertEquals(192_000, Settings().audioBitrate)
        assertTrue(Settings().audio)
        val filtered = Bitrates.audioChoices("aac", "encoder", 192_000, ranges("aac", min = 160_000, max = 256_000))
        assertTrue(filtered.none { it.label.contains("recommended") })
        assertEquals("192 kbps", filtered.single { it.bps == 192_000 }.label)
        assertTrue(filtered.single { it.bps == 192_000 }.valid)
    }
    @Test fun explicitEncoderRangeFiltersPresetsButNeverReplacesInvalidCurrentChoice() {
        val options = Bitrates.audioChoices("aac", "encoder", 256_000, ranges("aac", min = 96_000))
        assertEquals(256_000, options.first().bps)
        assertFalse(options.first().valid)
        assertEquals(listOf(96_000, 128_000, 160_000, 192_000), options.drop(1).map { it.bps })
        val s = Settings().apply { audioCodec = "aac"; audioEncoder = "encoder"; audioBitrate = 256_000 }
        assertThrows(IllegalArgumentException::class.java) { Bitrates.validate(s, ranges("aac")) }
        assertEquals(256_000, s.audioBitrate)
    }
    @Test fun existingNonPresetIsRetainedExactlyWhenInRangeAndSurvivesPersistence() {
        val options = Bitrates.audioChoices("opus", "encoder", 150_500, ranges())
        assertEquals(150_500, options.first().bps); assertTrue(options.first().valid)
        assertTrue(options.first().label.contains("150.5 kbps"))
        val settings = Settings().apply { audioEncoder = "encoder"; audioBitrate = 150_500 }
        Bitrates.validate(settings, ranges())
        assertEquals(150_500, Settings.from(settings.request()).audioBitrate)
    }
    @Test fun codecAndEncoderSwitchKeepRateUntilExplicitlyChangedIncludingRawRoundTrip() {
        val metadata = CodecBitrates(listOf(EncoderBitrate("opus", "opus.encoder", 16_000, 512_000),
            EncoderBitrate("aac", "aac.encoder", 64_000, 128_000)))
        assertTrue(Bitrates.audioChoices("opus", "opus.encoder", 192_000, metadata).single { it.bps == 192_000 }.valid)
        assertFalse(Bitrates.audioChoices("aac", "aac.encoder", 192_000, metadata).single { it.bps == 192_000 }.valid)
        val raw = Bitrates.audioChoices("raw", "", 192_000, metadata).single()
        assertNull(raw.bps); assertTrue(raw.label.startsWith("N/A"))
        assertEquals(192_000, Bitrates.audioChoices("opus", "opus.encoder", 192_000, metadata).single { it.bps == 192_000 }.bps)
    }
    @Test fun automaticEncoderRangeNeverUsesCompatibleSecondCandidateAsFallback() {
        val metadata = CodecBitrates(listOf(EncoderBitrate("opus", "a", 16_000, 128_000),
            EncoderBitrate("opus", "b", 128_000, 256_000)))
        assertFalse(metadata.permits("opus", "", 192_000))
        assertFalse(metadata.permits("opus", "a", 192_000))
        assertFalse(metadata.permits("opus", "", 512_000))
        assertTrue(metadata.describe("opus", "").contains("first advertised compatible encoder"))
        assertFalse(metadata.describe("opus", "").contains("hardware encoder"))
        assertTrue(metadata.describe("h264", "").contains("first advertised hardware encoder"))
        assertFalse(metadata.describe("opus", "").contains("b:"))
        assertTrue(metadata.describe("opus", "").contains("do not guarantee"))
        val unknown = CodecBitrates(listOf(EncoderBitrate("opus", "x", null, null, "not published")))
        assertTrue(unknown.permits("opus", "x", 192_000))
        assertTrue(unknown.describe("opus", "x").contains("unavailable"))
    }
    @Test fun applyAndStartupRejectStaleVideoAndAudioSettingsAgainstFreshPrivilegedRanges() {
        val metadata = CodecBitrates(listOf(EncoderBitrate("h264", "video", 100_000, 4_000_000),
            EncoderBitrate("aac", "audio", 64_000, 128_000)))
        val restored = Settings.from(Settings().apply { videoEncoder = "video" }.request())
        assertThrows(IllegalArgumentException::class.java) { Bitrates.validate(restored, metadata) }
        restored.videoBitrate = 4_000_000; restored.audioCodec = "aac"; restored.audioEncoder = "audio"
        assertThrows(IllegalArgumentException::class.java) { Bitrates.validate(restored, metadata) }
        restored.audioBitrate = 128_000; Bitrates.validate(restored, metadata)
        restored.audioCodec = "raw"; restored.audioBitrate = 1
        Bitrates.validate(restored, metadata)
        restored.audioBitrate = 0
        assertThrows(IllegalArgumentException::class.java) { restored.validate() }
    }
    @Test fun internalMetadataRoundTripsAndMalformedDuplicateReportsFailExplicitly() {
        val metadata = ranges()
        assertEquals(metadata.encoders, CodecBitrates.fromReport("scrcpy listing\n" + metadata.report()).encoders)
        assertTrue(CodecBitrates.fromReport("old report without ranges").encoders.isEmpty())
        assertThrows(IllegalStateException::class.java) { CodecBitrates.fromReport(metadata.report() + "\n" + metadata.report()) }
        assertThrows(IllegalArgumentException::class.java) { EncoderBitrate("opus", "bad", 200_000, 100_000) }
    }
}

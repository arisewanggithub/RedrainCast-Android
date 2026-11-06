package dev.screencopy.sender

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.file.Path

class VideoGeometryTest {
    private fun inventory(rotation: Int = 0) = DisplayInventory(listOf(
        CaptureDisplay(7, "Panel", if (rotation % 2 == 0) 1440 else 3168, if (rotation % 2 == 0) 3168 else 1440, rotation, 1)))
    private fun settings() = Settings().apply { displayId = 7; videoCodec = "h265" }
    private fun limits(min: Int = 128, alignment: Int = 2, maxFps: Double = 60.0) =
        VideoLimits(min, 4096, min, 4096, alignment, alignment, 1.0, maxFps)
    @Test fun exactPinnedSizingReportsRequested100SquareAndEncoded96Square() {
        val settings = settings().apply { cropWidth = 100; cropHeight = 100 }
        val plan = ScrcpyVideoSizing.calculate(settings, inventory())
        assertEquals(VideoSize(100, 100), plan.requested)
        assertEquals(VideoSize(96, 96), plan.output)
        val before = settings.request().toString()
        val unsupported = EncoderGeometryCheck(limits(), false, false, VideoSize(128, 128) to 60)
        val error = assertThrows(VideoGeometryException::class.java) { VideoGeometryValidation.validate(plan, listOf(unsupported), false) }
        assertTrue(error.message!!.contains("100x100")); assertTrue(error.message!!.contains("96x96"))
        assertTrue(error.message!!.contains("128..4096")); assertTrue(error.message!!.contains("aligned 2"))
        assertEquals(before, settings.request().toString())
        val decoded = assertThrows(VideoGeometryException::class.java) { VideoPreflightReport.decode(VideoPreflightReport.encode(plan, error)) }
        assertEquals(error.limits, decoded.limits)
        assertEquals(VideoSize(128, 128), decoded.suggestion?.first)
    }
    @Test fun realMetadataThatAccepts96DoesNotGloballyBanSmallH265Crop() {
        val plan = ScrcpyVideoSizing.calculate(settings().apply { cropWidth = 100; cropHeight = 100 }, inventory())
        VideoGeometryValidation.validate(plan, listOf(EncoderGeometryCheck(limits(32), true, true)), false)
        assertEquals(plan, VideoPreflightReport.decode(VideoPreflightReport.encode(plan)))
    }
    @Test fun default1920ScalingUsesIntegerRatioAndPinnedRound8NotIndependentNearestDimensions() {
        assertEquals(VideoSize(872, 1920), ScrcpyVideoSizing.calculate(settings(), inventory()).output)
        val scalar100 = ScrcpyVideoSizing.calculate(settings().apply { maxSize = 100 }, inventory())
        assertEquals(96, scalar100.normalizedMaxSize)
        assertEquals(VideoSize(40, 96), scalar100.output)
        assertEquals(VideoSize(1440, 3168), ScrcpyVideoSizing.calculate(settings().apply { maxSize = 0 }, inventory()).output)
        assertThrows(IOException::class.java) { ScrcpyVideoSizing.calculate(settings().apply { maxSize = 5 }, inventory()) }
    }
    @Test fun cropUsesFixedNaturalRasterForEveryPresentationOrientation() {
        val settings = settings().apply { cropWidth = 100; cropHeight = 200; maxSize = 0 }
        assertEquals(VideoSize(104, 200), ScrcpyVideoSizing.calculate(settings, inventory(1)).output)
        settings.orientation = "@0"
        assertEquals(VideoSize(104, 200), ScrcpyVideoSizing.calculate(settings, inventory(1)).output)
        settings.orientation = "@90"
        assertEquals(VideoSize(104, 200), ScrcpyVideoSizing.calculate(settings, inventory(1)).output)
        settings.orientation = "90"
        assertEquals(VideoSize(104, 200), ScrcpyVideoSizing.calculate(settings, inventory(1)).output)
    }
    @Test fun sizeAlignmentAndFpsAreIndependentExplicitFailuresAndSuggestionsAreNotApplied() {
        val plan = ScrcpyVideoSizing.calculate(settings().apply { cropWidth = 104; cropHeight = 104 }, inventory())
        val aligned = assertThrows(VideoGeometryException::class.java) {
            VideoGeometryValidation.validate(plan, listOf(EncoderGeometryCheck(limits(32, 16), false, false, VideoSize(112, 112) to 60)), false)
        }
        assertEquals(VideoGeometryReason.SIZE_OR_ALIGNMENT, aligned.reason)
        assertEquals(VideoSize(104, 104), plan.output)
        val rate = assertThrows(VideoGeometryException::class.java) {
            VideoGeometryValidation.validate(plan, listOf(EncoderGeometryCheck(limits(32, maxFps = 30.0), true, false, plan.output to 30)), false)
        }
        assertEquals(VideoGeometryReason.FRAME_RATE, rate.reason)
        assertEquals(60, plan.fps)
    }
    @Test fun automaticMixedCandidatesCannotBePassedAsGuaranteedUnionSupport() {
        val plan = ScrcpyVideoSizing.calculate(settings().apply { cropWidth = 100; cropHeight = 100 }, inventory())
        val checks = listOf(EncoderGeometryCheck(limits(32), true, true), EncoderGeometryCheck(limits(), false, false))
        val error = assertThrows(VideoGeometryException::class.java) { VideoGeometryValidation.validate(plan, checks, true) }
        assertEquals(VideoGeometryReason.AUTO_NOT_FIXED, error.reason)
        VideoGeometryValidation.validate(plan, listOf(checks.first()), false)
        VideoGeometryValidation.validate(plan, listOf(checks.first(), checks.first()), true)
    }
    @Test fun rankedAutomaticDefaultIsNotBlockedByUnselectedSoftwareSizeOrRateMetadata() {
        val plan = ScrcpyVideoSizing.calculate(settings(), inventory())
        val checks = listOf(EncoderGeometryCheck(limits(), true, true),
            EncoderGeometryCheck(limits(maxFps = 30.0), true, false), EncoderGeometryCheck(null, null, null))
        val first = AutomaticVideoEncoderOrder.firstAttempts(listOf("c2.vendor.hevc.encoder", "c2.android.hevc.encoder", "OMX.google.hevc.encoder"), false)
        assertEquals(listOf(0), first)
        VideoGeometryValidation.validate(plan, checks, true, first)
        assertEquals(VideoSize(872, 1920), plan.output)
    }
    @Test fun rankedAutomatic100SquareStillFailsKnownMinimumWithoutAssumingSoftwareFallback() {
        val plan = ScrcpyVideoSizing.calculate(settings().apply { cropWidth = 100; cropHeight = 100 }, inventory())
        val checks = listOf(EncoderGeometryCheck(limits(), false, false, VideoSize(128, 128) to 60),
            EncoderGeometryCheck(limits(32), true, true))
        val error = assertThrows(VideoGeometryException::class.java) {
            VideoGeometryValidation.validate(plan, checks, true, listOf(0))
        }
        assertEquals(VideoGeometryReason.SIZE_OR_ALIGNMENT, error.reason)
        assertEquals(1, error.checkedEncoders)
        assertTrue(error.message!!.contains("first-attempt"))
        VideoGeometryValidation.validate(plan, checks, true, listOf(1))
    }
    @Test fun softwareOverrideUsesNativeCanonicalNameCategoriesNotGenericSoftwareFlagsOrAliases() {
        val names = listOf("c2.vendor.hevc.encoder", "OMX.google.hevc.encoder", "external.encoder", "c2.android.hevc.encoder")
        assertEquals(listOf(3), AutomaticVideoEncoderOrder.firstAttempts(names, true))
        assertEquals(listOf(0, 3), AutomaticVideoEncoderOrder.firstAttempts(names, null))
        assertEquals(listOf(0), AutomaticVideoEncoderOrder.firstAttempts(names, false))
        assertEquals(listOf(1), AutomaticVideoEncoderOrder.firstAttempts(listOf("OMX.vendor.encoder", "c2.vendor.encoder"), true))
    }
    @Test fun uncertainNativeSortTiesOnlyCheckPossibleFirstTierNotUnselectedLowerTiers() {
        val first = AutomaticVideoEncoderOrder.firstAttempts(listOf("c2.vendor.encoder", "c2.android.one", "c2.android.two"), true)
        assertEquals(listOf(1, 2), first)
        val plan = ScrcpyVideoSizing.calculate(settings(), inventory())
        val good = EncoderGeometryCheck(limits(), true, true)
        val bad = EncoderGeometryCheck(limits(maxFps = 30.0), true, false)
        VideoGeometryValidation.validate(plan, listOf(bad, good, good), true, first)
        assertEquals(VideoGeometryReason.AUTO_NOT_FIXED, assertThrows(VideoGeometryException::class.java) {
            VideoGeometryValidation.validate(plan, listOf(bad, good, bad), true, first)
        }.reason)
    }
    @Test fun unavailableMetadataZeroNormalizedOutputAndMissingDisplayNeverProduceFakeSuccess() {
        val plan = ScrcpyVideoSizing.calculate(settings(), inventory())
        assertEquals(VideoGeometryReason.METADATA_UNAVAILABLE, assertThrows(VideoGeometryException::class.java) {
            VideoGeometryValidation.validate(plan, listOf(EncoderGeometryCheck(null, null, null)), true)
        }.reason)
        val tiny = ScrcpyVideoSizing.calculate(settings().apply { cropWidth = 1; cropHeight = 1 }, inventory())
        assertEquals(VideoSize(0, 0), tiny.output)
        assertEquals(VideoGeometryReason.ZERO_OUTPUT, assertThrows(VideoGeometryException::class.java) {
            VideoGeometryValidation.validate(tiny, emptyList(), true)
        }.reason)
        assertThrows(IOException::class.java) { ScrcpyVideoSizing.calculate(Settings(), inventory()) }
    }
    @Test fun stalePreferencesAndFractionalNumericSizeCannotBypassPreflight() {
        val saved = settings().apply { cropWidth = 100; cropHeight = 100 }.request()
        val restored = Settings.from(saved)
        val plan = ScrcpyVideoSizing.calculate(restored, inventory())
        assertThrows(VideoGeometryException::class.java) {
            VideoGeometryValidation.validate(plan, listOf(EncoderGeometryCheck(limits(), false, false)), false)
        }
        saved.getJSONObject("video").put("maxSize", 100.5)
        assertThrows(org.json.JSONException::class.java) { Settings.from(saved) }
    }
    @Test fun cropSharedUiHintMatchesAllZeroAndPositiveDimensionValidation() {
        Settings().validate()
        assertThrows(IllegalArgumentException::class.java) { Settings().apply { cropHeight = 100 }.validate() }
        assertThrows(IllegalArgumentException::class.java) { Settings().apply { cropX = 1 }.validate() }
        Settings().apply { cropWidth = 100; cropHeight = 100; cropX = 2; cropY = 3 }.validate()
    }

    @Test fun fixedNaturalGeometryFitsPortraitEncoderLimitsAfterDisplayRotation() {
        val settings = Settings().apply {
            cropWidth = 1080; cropHeight = 1920; cropY = 210; maxSize = 1920
        }
        val inventory = DisplayInventory(listOf(CaptureDisplay(0, "Panel", 2340, 1080, 1, 1)))
        inventory.validate(settings)
        val natural = ScrcpyVideoSizing.calculate(settings, inventory)
        val limits = VideoLimits(128, 1080, 128, 1920, 2, 2, 1.0, 60.0)
        assertEquals(VideoSize(1080, 1920), natural.output)
        VideoGeometryValidation.validate(natural, listOf(EncoderGeometryCheck(limits, true, true)), false)
    }
}

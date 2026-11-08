package org.screencopy.receiver.media

import android.media.MediaCodecInfo
import android.media.MediaFormat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.MediaCodecInfoBuilder
import org.robolectric.shadows.MediaCodecInfoBuilder.CodecCapabilitiesBuilder
import org.screencopy.receiver.protocol.HdrVideoColor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@ConscryptMode(ConscryptMode.Mode.OFF)
class DecoderSelectionTest {
    private fun format(width: Int = 3840, height: Int = 2400, rate: Int = 120, hdr: Boolean = true) =
        VideoPlayer.mediaFormat(VideoFormat("video/hevc", width, height, emptyList(), rate,
            videoColor = if (hdr) HdrVideoColor() else null))

    private fun candidate(name: String = "fixture.hardware.hevc", hardware: Boolean = true,
        profile: Int = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10): MediaCodecInfo {
        val limits = MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, "video/hevc")
            setInteger(MediaFormat.KEY_WIDTH, 64)
            setInteger(MediaFormat.KEY_MAX_WIDTH, 3840)
            setInteger(MediaFormat.KEY_HEIGHT, 64)
            setInteger(MediaFormat.KEY_MAX_HEIGHT, 2160)
            setString("alignment", "8x8")
            setString("frame-rate-range", "1-60")
        }
        val caps = CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(limits)
            .setProfileLevels(arrayOf(MediaCodecInfo.CodecProfileLevel().apply {
                this.profile = profile
                level = MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51
            }))
            .setColorFormats(intArrayOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible))
            .build()
        return MediaCodecInfoBuilder.newBuilder()
            .setName(name)
            .setIsHardwareAccelerated(hardware)
            .setIsSoftwareOnly(!hardware)
            .setCapabilities(caps)
            .build()
    }

    @Test fun unsupportedRasterReportsRequestedFormatAndEveryHardwareCandidate() {
        val requested = format()
        val candidates = listOf(candidate())
        val error = assertThrows(PlaybackException::class.java) { findPreferredDecoder(requested, candidates) }
        assertEquals("UNSUPPORTED_CONFIG", error.code)
        for (field in listOf("video/hevc 3840x2400@120", "HEVC Main10 HDR10", "hardware_candidates=1",
            "fixture.hardware.hevc", "profile_levels=", "profile_supported=true",
            "widths=[64, 3840]", "heights=[64, 2160]", "alignment=8x8 aligned=true", "raster_supported=false",
            "rates_at_raster=unavailable_for_requested_raster", "size_rate_supported=false", "format_supported=false",
            "not a compatibility guarantee", "No settings were changed")) {
            assertTrue(error.message, checkNotNull(error.message).contains(field))
        }
        for (info in candidates) {
            val video = checkNotNull(info.getCapabilitiesForType("video/hevc").videoCapabilities)
            assertFalse(video.isSizeSupported(3840, 2400))
            assertThrows(IllegalArgumentException::class.java) { video.getSupportedFrameRatesFor(3840, 2400) }
        }
        assertNull(HdrDecoderCapabilities.find(requested, candidates))
    }

    @Test fun supportedRasterButUnsupportedRateStillFailsWithoutClamping() {
        val requested = format(1920, 1080)
        val error = assertThrows(PlaybackException::class.java) {
            findPreferredDecoder(requested, listOf(candidate()))
        }
        assertTrue(checkNotNull(error.message).contains("raster_supported=true rates_at_raster=[1.0, 60.0] size_rate_supported=false"))
        assertTrue(checkNotNull(error.message).contains("format_supported=false"))
        assertEquals(120, requested.getInteger(MediaFormat.KEY_FRAME_RATE))
        assertEquals(1920, requested.getInteger(MediaFormat.KEY_WIDTH))
    }

    @Test fun unsupportedAlignmentRemainsARejection() {
        val error = assertThrows(PlaybackException::class.java) {
            findPreferredDecoder(format(1921, 1080, 60), listOf(candidate()))
        }
        assertTrue(checkNotNull(error.message).contains("1921x1080@60"))
        assertTrue(checkNotNull(error.message).contains("alignment=8x8 aligned=false raster_supported=false"))
    }

    @Test fun hdrRequiresHardwareAndExactProfileEvenWhenFormatIsOtherwiseSupported() {
        for (info in listOf(candidate(hardware = false),
            candidate(profile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10))) {
            assertThrows(PlaybackException::class.java) { findPreferredDecoder(format(1920, 1080, 60), listOf(info)) }
        }
        val info = candidate()
        assertEquals(info.name, findPreferredDecoder(format(1920, 1080, 60), listOf(info)))
    }

    @Test fun sdrKeepsHardwarePreferenceAndExistingSoftwareFallback() {
        val software = candidate(name = "fixture.software", hardware = false)
        val hardware = candidate()
        assertEquals(hardware.name, findPreferredDecoder(format(1920, 1080, 60, hdr = false), listOf(software, hardware)))
        assertEquals(software.name, findPreferredDecoder(format(1920, 1080, 60, hdr = false), listOf(software)))
    }

    @Test fun missingHardwareIsExplicitAndCapabilityErrorsAreNotHidden() {
        val requested = format()
        val absent = assertThrows(PlaybackException::class.java) { findPreferredDecoder(requested, emptyList()) }
        assertTrue(checkNotNull(absent.message).contains("hardware_candidates=0: none"))
        val info = Mockito.mock(MediaCodecInfo::class.java)
        Mockito.`when`(info.isHardwareAccelerated).thenReturn(true)
        Mockito.`when`(info.supportedTypes).thenReturn(arrayOf("video/hevc"))
        Mockito.`when`(info.getCapabilitiesForType("video/hevc")).thenThrow(IllegalStateException("fixture capability query failed"))
        val failure = assertThrows(IllegalStateException::class.java) { findPreferredDecoder(requested, listOf(info)) }
        assertEquals("fixture capability query failed", failure.message)
    }

    @Test fun allCandidateDetailsAreLoggedWhileWireReasonRemainsBounded() {
        val logged = mutableListOf<String>()
        val candidates = (1..10).map { candidate(name = "fixture.hardware.$it",
            profile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain) }
        val reason = HdrDecoderCapabilities.rejection(format(), candidates, logged::add)
        assertTrue(reason.length <= 1100)
        assertTrue(reason.contains("constraints truncated"))
        assertEquals(11, logged.size)
        for (index in 1..10) {
            assertTrue(logged[index].contains("fixture.hardware.$index:"))
            assertTrue(logged[index].contains("profile_supported=false"))
            assertTrue(logged[index].contains("format_supported=false"))
        }
    }

    @Test fun selectionFailurePreservesReasonAndExistingStructuredErrorEnvelope() {
        for (count in listOf(1, 10)) {
            val requested = VideoFormat("video/hevc", 3840, 2400, emptyList(), 120, videoColor = HdrVideoColor())
            val candidates = (1..count).map { candidate(name = "fixture.hardware.$it") }
            val error = assertThrows(VideoPlaybackException::class.java) {
                VideoPlayer(requested, PlaybackClock(), { _, _ -> }, factory = { selected, _ ->
                    try {
                        findPreferredDecoder(VideoPlayer.mediaFormat(selected), candidates)
                        throw AssertionError("Unsupported fixture unexpectedly selected a decoder")
                    } catch (failure: PlaybackException) {
                        throw VideoInitializationFailure(VideoStage.SELECT, failure)
                    }
                })
            }
            val reason = (error.cause as VideoInitializationFailure).failure.message
            val detail = checkNotNull(error.message)
            assertTrue(detail, detail.contains(checkNotNull(reason)))
            assertTrue(("Media connection failed: $detail").length <= 2048)
            assertEquals("UNSUPPORTED_CONFIG", error.code)
            assertEquals(error.diagnostic, VideoDiagnostic.fromLocalDetail(detail))
            assertEquals(VideoStage.SELECT, error.diagnostic.stage)
            assertEquals(VideoFault.UNSUPPORTED, error.diagnostic.fault)
            assertEquals(0L, error.diagnostic.queuedFrames)
            assertEquals(0L, error.diagnostic.decodedFrames)
            assertEquals(0L, error.diagnostic.renderSubmitted)
            assertEquals(3840, requested.width)
            assertEquals(2400, requested.height)
            assertEquals(120, requested.frameRate)
            assertNotNull(requested.videoColor)
            val otherStage = VideoPlaybackException(error.diagnostic.copy(stage = VideoStage.CONFIGURE),
                PlaybackException("fixture unrelated API message", "UNSUPPORTED_CONFIG"))
            assertFalse(checkNotNull(otherStage.message).contains("fixture unrelated API message"))
            assertEquals(otherStage.diagnostic, VideoDiagnostic.fromLocalDetail(checkNotNull(otherStage.message)))
        }
    }
}

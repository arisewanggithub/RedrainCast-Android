package org.screencopy.receiver.media

import android.media.MediaCodecInfo
import android.media.MediaFormat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@ConscryptMode(ConscryptMode.Mode.OFF)
class VideoOutputMetadataTest {
    @Test fun malformedOptionalStatisticsMetadataIsLoggedAndDoesNotFailPlayback() {
        val actual = MediaFormat().apply {
            setString(MediaFormat.KEY_PROFILE, "unknown")
            setString(MediaFormat.KEY_COLOR_FORMAT, "opaque")
        }
        val result = AndroidVideoDecoder.outputStatistics(actual, "video/avc", false, false)
        assertNull(result.video)
        assertNull(result.presentation)
        val logs = org.robolectric.shadows.ShadowLog.getLogsForTag("ScreenCopyReceiver")
        assertTrue(logs.any { it.msg.contains(MediaFormat.KEY_PROFILE) })
        assertTrue(logs.any { it.msg.contains(MediaFormat.KEY_COLOR_FORMAT) })
    }

    @Test @Config(sdk = [30]) fun minimumSupportedAndroidMalformedMetadataAlsoRemainsUnknownWithoutChangingPlayback() {
        val actual = MediaFormat().apply { setString(MediaFormat.KEY_PROFILE, "unknown") }
        assertNull(AndroidVideoDecoder.outputStatistics(actual, "video/avc", false, false).video)
        assertTrue(org.robolectric.shadows.ShadowLog.getLogsForTag("ScreenCopyReceiver")
            .any { it.msg.contains(MediaFormat.KEY_PROFILE) })
    }

    @Test fun negotiatedHdrAndOpaqueSurfaceDoNotProveProfileDepthOrPresentedOutput() {
        val unknown = AndroidVideoDecoder.outputStatistics(MediaFormat(), "video/hevc", true, false)
        assertEquals(DecodedVideoStatistics(VideoStreamFormat.Hdr10, null, null, null), unknown.video)
        assertNull(unknown.presentation)
        val opaque = MediaFormat().apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        }
        assertNull(AndroidVideoDecoder.outputStatistics(opaque, "video/hevc", true, false).video!!.bitDepth)
        assertNull(AndroidVideoDecoder.outputStatistics(MediaFormat(), "video/avc", false, false).video)
        assertNull(AndroidVideoDecoder.outputStatistics(MediaFormat(), "video/avc", false, false).presentation)
    }

    @Test fun actualOutputMetadataEstablishesProfileAndComponentDepthWithoutCodecCapabilities() {
        val actual = MediaFormat().apply {
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
        }
        val hdr = AndroidVideoDecoder.outputStatistics(actual, "video/hevc", true, false)
        assertEquals(DecodedVideoStatistics(VideoStreamFormat.Hdr10, VideoTransfer.Pq, "Main 10", 10), hdr.video)
        assertEquals(VideoPresentationOutput.Hdr, hdr.presentation!!.output)
        val cache = VideoPathStatisticsCache()
        cache.decoded(hdr)
        cache.scheduled(1, hdr)
        assertNull(cache.snapshot().presentation)
        cache.rendered(1, 1)
        assertEquals(hdr.presentation, cache.snapshot().presentation)
        actual.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL)
        assertNull(AndroidVideoDecoder.outputStatistics(actual, "video/hevc", true, false).presentation)
    }

    @Test fun sdrRequiresActualTransferAndToneMapRequestNeverProvesMapping() {
        val actual = MediaFormat().apply {
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileMain)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
        }
        val absentTransfer = AndroidVideoDecoder.outputStatistics(actual, "video/avc", false, false)
        assertNull(absentTransfer.video!!.streamFormat)
        assertNull(absentTransfer.presentation)
        actual.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        val sdr = AndroidVideoDecoder.outputStatistics(actual, "video/avc", false, false)
        assertEquals(DecodedVideoStatistics(VideoStreamFormat.Sdr, VideoTransfer.Bt709, "Main", 8), sdr.video)
        assertEquals(VideoPresentationOutput.Sdr, sdr.presentation!!.output)
        actual.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        assertEquals(8, AndroidVideoDecoder.outputStatistics(actual, "video/avc", false, false).video!!.bitDepth)
        actual.setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        assertNull(AndroidVideoDecoder.outputStatistics(actual, "video/hevc", true, true).presentation)
        actual.setInteger(MediaFormat.KEY_PROFILE, 123456)
        assertNull(AndroidVideoDecoder.outputStatistics(actual, "video/avc", false, false).video!!.codecProfile)
    }
}

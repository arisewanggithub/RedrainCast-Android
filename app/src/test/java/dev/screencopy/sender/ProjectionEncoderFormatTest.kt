package dev.screencopy.sender

import android.media.MediaCodecInfo
import android.media.MediaFormat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.screencopy.receiver.media.VideoFormat
import org.screencopy.receiver.media.VideoPlayer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ProjectionEncoderFormatTest {
    @Test fun videoDefaultsMatchScrcpyWithoutIntroducingACaptureCap() {
        for (codec in listOf("h264", "h265", "av1")) {
            val settings = Settings().apply { videoCodec = codec }
            val format = ProjectionCapture.videoEncoderFormat(settings, VideoSize(1920, 1080))
            assertEquals(HardwareEncoderPolicy.mimeTypes[codec], format.getString(MediaFormat.KEY_MIME))
            assertEquals(1920, format.getInteger(MediaFormat.KEY_WIDTH))
            assertEquals(1080, format.getInteger(MediaFormat.KEY_HEIGHT))
            assertEquals(8_000_000, format.getInteger(MediaFormat.KEY_BIT_RATE))
            assertEquals(60, format.getInteger(MediaFormat.KEY_FRAME_RATE))
            assertFalse(format.containsKey(MediaFormat.KEY_MAX_FPS_TO_ENCODER))
            assertEquals(10, format.getInteger(MediaFormat.KEY_I_FRAME_INTERVAL))
            assertEquals(100_000L, format.getLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER))
            assertEquals(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                format.getInteger(MediaFormat.KEY_COLOR_FORMAT))
            assertEquals(MediaFormat.COLOR_RANGE_LIMITED, format.getInteger(MediaFormat.KEY_COLOR_RANGE))
            assertEquals(0, format.getInteger(MediaFormat.KEY_PRIORITY))
            assertEquals(1, format.getInteger(MediaFormat.KEY_LATENCY))
            assertEquals(0, format.getInteger(MediaFormat.KEY_MAX_B_FRAMES))
        }
    }

    @Test fun selectedCapsKeepTheSixtyFpsEncoderHintAndExplicitOptionsStillOverrideDefaults() {
        for (fps in listOf(15, 24, 25, 30, 47, 48, 50, 60, 90, 120, 240)) {
            val settings = Settings().apply { maxFps = fps; videoOptions = "i-frame-interval=2,bitrate-mode=1" }
            settings.validate()
            val format = ProjectionCapture.videoEncoderFormat(settings, VideoSize(640, 360))
            assertEquals(60, format.getInteger(MediaFormat.KEY_FRAME_RATE))
            assertEquals(fps.toFloat(), format.getFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER), 0f)
            assertEquals(2, format.getInteger(MediaFormat.KEY_I_FRAME_INTERVAL))
            assertEquals(1, format.getInteger(MediaFormat.KEY_BITRATE_MODE))
        }
    }

    @Test fun uncappedReceiverFormatOmitsUnknownFrameRateButCappedFormatKeepsIt() {
        for (fps in listOf(0, 15, 60, 120, 240)) {
            val format = VideoPlayer.mediaFormat(VideoFormat("video/avc", 640, 360, emptyList(), fps))
            assertEquals(fps > 0, format.containsKey(MediaFormat.KEY_FRAME_RATE))
            if (fps > 0) assertEquals(fps, format.getInteger(MediaFormat.KEY_FRAME_RATE))
        }
        for (fps in listOf(-1, 241)) assertThrows(IllegalArgumentException::class.java) {
            VideoPlayer.mediaFormat(VideoFormat("video/avc", 640, 360, emptyList(), fps))
        }
    }
}

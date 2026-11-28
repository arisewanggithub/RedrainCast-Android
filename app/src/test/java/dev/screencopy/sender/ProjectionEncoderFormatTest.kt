package dev.screencopy.sender

import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.AudioRecord
import android.content.pm.PackageManager
import android.view.Surface
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.screencopy.receiver.media.VideoFormat
import org.screencopy.receiver.media.VideoPlayer
import org.mockito.Mockito
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.MediaCodecInfoBuilder.CodecCapabilitiesBuilder
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ProjectionEncoderFormatTest {
    @Test fun audioDisabledSkipsRecordingAndAudioConfigurationAtActualCaptureStartup() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO))
        val info = Mockito.mock(MediaCodecInfo::class.java)
        Mockito.`when`(info.name).thenReturn("vendor.avc")
        Mockito.`when`(info.canonicalName).thenReturn("vendor.avc")
        Mockito.`when`(info.supportedTypes).thenReturn(arrayOf("video/avc"))
        Mockito.`when`(info.isEncoder).thenReturn(true)
        Mockito.`when`(info.isHardwareAccelerated).thenReturn(true)
        val caps = CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(MediaFormat.createVideoFormat("video/avc", 1920, 1080))
            .setIsEncoder(true)
            .setProfileLevels(arrayOf(MediaCodecInfo.CodecProfileLevel().apply {
                profile = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
                level = MediaCodecInfo.CodecProfileLevel.AVCLevel52
            }))
            .setColorFormats(intArrayOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface))
            .build()
        Mockito.`when`(info.getCapabilitiesForType("video/avc")).thenReturn(caps)
        val encoder = Mockito.mock(MediaCodec::class.java)
        Mockito.`when`(encoder.createInputSurface()).thenReturn(Mockito.mock(Surface::class.java))
        val reachedVideoAttachment = IOException("test video attachment boundary")
        Mockito.mockConstruction(MediaCodecList::class.java) { inventory, _ ->
            Mockito.`when`(inventory.codecInfos).thenReturn(arrayOf(info))
        }.use {
            Mockito.mockStatic(MediaCodec::class.java).use { codecs ->
                codecs.`when`<MediaCodec> { MediaCodec.createByCodecName("vendor.avc") }.thenReturn(encoder)
                Mockito.mockConstruction(AudioRecord.Builder::class.java).use { records ->
                    Mockito.mockConstruction(ProjectionRenderer::class.java) { renderer, _ ->
                        Mockito.`when`(renderer.start()).thenAnswer { throw reachedVideoAttachment }
                    }.use { renderers ->
                        for (enabled in listOf(false, true)) {
                            val owner = Mockito.mock(ProjectionOwner::class.java)
                            val sink = Mockito.mock(ScrcpyAdapter.Sink::class.java)
                            ProjectionCapture(context, owner, { throw AssertionError(it) }).use { capture ->
                                val error = assertThrows(IOException::class.java) {
                                    capture.start(Settings().apply { audio = enabled; videoEncoder = "vendor.avc" }, 1, sink)
                                }
                                if (enabled) assertTrue(error.message!!, error.message!!.startsWith("AUDIO_FAILED:"))
                                else assertSame(reachedVideoAttachment, error)
                                Mockito.verifyNoInteractions(sink)
                                Mockito.verify(owner, Mockito.never()).mutePlayback(Mockito.any(Settings::class.java) ?: Settings())
                            }
                        }
                        Mockito.verify(renderers.constructed()[0]).start()
                        Mockito.verify(renderers.constructed()[1], Mockito.never()).start()
                        assertTrue(records.constructed().isEmpty())
                        codecs.verify({ MediaCodec.createByCodecName("vendor.avc") }, Mockito.times(2))
                        codecs.verifyNoMoreInteractions()
                    }
                }
            }
        }
    }

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

    @Test fun selectedCapsConfigureMatchingEncoderRateAndSeparateCaptureCeiling() {
        for (fps in listOf(15, 24, 25, 30, 47, 48, 50, 60, 90, 120, 240)) {
            val settings = Settings().apply { maxFps = fps; videoOptions = "i-frame-interval=2,bitrate-mode=1" }
            settings.validate()
            val format = ProjectionCapture.videoEncoderFormat(settings, VideoSize(640, 360))
            assertEquals(fps, format.getInteger(MediaFormat.KEY_FRAME_RATE))
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

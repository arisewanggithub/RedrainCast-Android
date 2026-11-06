package org.screencopy.receiver

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES20
import android.view.Surface
import java.util.concurrent.ConcurrentSkipListMap
import org.screencopy.receiver.media.AccessUnit
import org.screencopy.receiver.media.AndroidVideoDecoder
import org.screencopy.receiver.media.VideoDecoder
import org.screencopy.receiver.media.VideoFormat
import org.screencopy.receiver.media.VideoOutput

internal fun syntheticRasterMetadata(format: MediaFormat): String =
    listOf(MediaFormat.KEY_WIDTH, MediaFormat.KEY_HEIGHT, MediaFormat.KEY_CROP_LEFT,
        MediaFormat.KEY_CROP_TOP, MediaFormat.KEY_CROP_RIGHT, MediaFormat.KEY_CROP_BOTTOM,
        MediaFormat.KEY_STRIDE, MediaFormat.KEY_SLICE_HEIGHT, MediaFormat.KEY_ROTATION).joinToString(" ") {
        "$it=${if (format.containsKey(it)) format.getInteger(it).toString() else "absent"}"
    }

/** Test-only access to our private codec instance; no platform reflection or output pixel access. */
internal class SyntheticDecoderMetadata(
    private val production: AndroidVideoDecoder,
    private val record: (String) -> Unit
) : VideoDecoder by production {
    private val codec: MediaCodec = try {
        val field = AndroidVideoDecoder::class.java.getDeclaredField("decoder").apply { isAccessible = true }
        checkNotNull(field.get(production) as? MediaCodec) { "Production decoder metadata handle unavailable" }
    } catch (e: Exception) {
        try { production.close() } catch (cleanup: Exception) { e.addSuppressed(cleanup) }
        throw e
    }

    override fun dequeueOutput(): VideoOutput? {
        val output = production.dequeueOutput()
        if (output != null) record("codec=${codec.name} hardware=${codec.codecInfo.isHardwareAccelerated}" +
            " ${syntheticRasterMetadata(codec.getOutputFormat(output.index))}" +
            " output_pts_us=${output.ptsUs} output_flags=${output.flags}")
        return output
    }
}

internal class SyntheticHevcEncoder(
    private val configured: (VideoFormat) -> Unit,
    private val packet: (AccessUnit) -> Unit,
    private val outputMetadata: (String) -> Unit = {}
) : AutoCloseable {
    private val codec: MediaCodec
    val input: Surface
    val rotations = ConcurrentSkipListMap<Long, Int>()
    private var hasFormat = false
    private val info = MediaCodec.BufferInfo()
    val description: String

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, 1080, 1920).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }
        val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
            it.isEncoder && it.isHardwareAccelerated &&
                it.supportedTypes.any { type -> type.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) } &&
                it.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC).isFormatSupported(format)
        }?.name ?: error("No hardware H265 surface encoder supports synthetic 1080x1920 at 30 fps")
        description = name
        codec = MediaCodec.createByCodecName(name)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            input = codec.createInputSurface()
            try { codec.start() } catch (e: Exception) { input.release(); throw e }
        } catch (e: Exception) {
            try { codec.release() } catch (cleanup: Exception) { e.addSuppressed(cleanup) }
            throw e
        }
    }

    fun drain() {
        val deadline = System.nanoTime() + 80_000_000L
        do {
            val index = codec.dequeueOutputBuffer(info, 10_000)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                check(!hasFormat) { "Unexpected second encoder output configuration" }
                val format = codec.outputFormat
                outputMetadata("codec=$description ${syntheticRasterMetadata(format)}")
                val csd = (0..2).mapNotNull { n ->
                    format.getByteBuffer("csd-$n")?.duplicate()?.let { buffer ->
                        ByteArray(buffer.remaining()).also { buffer.get(it) }
                    }
                }
                check(csd.isNotEmpty()) { "H265 encoder supplied no configuration" }
                configured(VideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, 1080, 1920, csd, 30))
                hasFormat = true
            } else if (index >= 0) {
                try {
                    check(info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM == 0) { "Unexpected synthetic encoder EOS" }
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        check(hasFormat) { "Picture arrived before H265 configuration" }
                        val buffer = checkNotNull(codec.getOutputBuffer(index)).duplicate()
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size).also { buffer.get(it) }
                        val rotation = checkNotNull(rotations.floorEntry(info.presentationTimeUs)) {
                            "Encoded PTS ${info.presentationTimeUs} has no synthetic picture rotation"
                        }
                        packet(AccessUnit(info.presentationTimeUs, bytes,
                            info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0, rotation.value))
                        rotations.headMap(rotation.key, false).clear()
                    }
                } finally { codec.releaseOutputBuffer(index, false) }
            }
        } while (System.nanoTime() < deadline)
    }

    override fun close() {
        try { codec.stop() } finally {
            try { codec.release() } finally { input.release() }
        }
    }
}

/** Surface-input codecs require EGL, not Surface.lockCanvas(). */
internal class SyntheticCornerGpu(surface: Surface) : AutoCloseable {
    private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private var context = EGL14.EGL_NO_CONTEXT
    private var window = EGL14.EGL_NO_SURFACE

    init {
        try {
            check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1))
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                0x3142, 1, EGL14.EGL_NONE), 0, configs, 0, 1, count, 0) && count[0] > 0)
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            window = EGL14.eglCreateWindowSurface(display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT && window != EGL14.EGL_NO_SURFACE)
            check(EGL14.eglMakeCurrent(display, window, window, context))
        } catch (e: Exception) {
            try { close() } catch (cleanup: Exception) { e.addSuppressed(cleanup) }
            throw e
        }
    }

    fun draw(ptsUs: Long) {
        GLES20.glViewport(0, 0, 1080, 1920)
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        val colors = arrayOf(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f),
            floatArrayOf(0f, 0f, 1f), floatArrayOf(1f, 1f, 0f))
        for (index in colors.indices) {
            val color = colors[index]
            GLES20.glScissor(index % 2 * 540, (1 - index / 2) * 960, 540, 960)
            GLES20.glClearColor(color[0], color[1], color[2], 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        check(GLES20.glGetError() == GLES20.GL_NO_ERROR)
        check(EGLExt.eglPresentationTimeANDROID(display, window, ptsUs * 1000))
        check(EGL14.eglSwapBuffers(display, window))
    }

    override fun close() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
    }
}

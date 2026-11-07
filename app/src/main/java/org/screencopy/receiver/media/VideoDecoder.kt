package org.screencopy.receiver.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.view.Surface
import java.nio.ByteBuffer

internal data class VideoOutput(val index: Int, val ptsUs: Long, val size: Int, val flags: Int)

internal interface VideoDecoder : AutoCloseable {
    val supportsRenderedStatistics: Boolean get() = false
    fun renderedStatistics(listener: (Long, Long) -> Unit) {}
    val description: String
    fun dequeueInput(): Int
    fun input(index: Int): ByteBuffer?
    fun queueInput(index: Int, size: Int, ptsUs: Long, keyFrame: Boolean)
    fun dequeueOutput(): VideoOutput?
    fun releaseOutput(index: Int, renderAtNs: Long?)
}

internal class VideoCodecFailure(
    val errorCode: Int, val recoverable: Boolean, val transient: Boolean, cause: Exception
) : IllegalStateException("Video codec operation failed", cause)

internal class AndroidVideoDecoder(format: VideoFormat, surface: Surface, onPresented: () -> Unit) : VideoDecoder {
    override val supportsRenderedStatistics = true
    @Volatile private var statisticsListener: ((Long, Long) -> Unit)? = null
    override fun renderedStatistics(listener: (Long, Long) -> Unit) { statisticsListener = listener }
    private val decoder: MediaCodec
    private val info = MediaCodec.BufferInfo()
    override val description: String
    private val expectedWidth = format.width
    private val expectedHeight = format.height

    init {
        var stage = VideoStage.SELECT
        var opened: MediaCodec? = null
        try {
            val mediaFormat = VideoPlayer.mediaFormat(format)
            val name = findPreferredDecoder(mediaFormat)
                ?: throw PlaybackException("No device decoder supports the requested video format", "UNSUPPORTED_CONFIG")
            stage = VideoStage.CREATE
            val created = codecCall { MediaCodec.createByCodecName(name) }
            opened = created
            stage = VideoStage.CONFIGURE
            codecCall { created.configure(mediaFormat, surface, null, 0) }
            codecCall { created.setOnFrameRenderedListener({ _, pts, renderedAtNs ->
                statisticsListener?.invoke(pts, renderedAtNs)
                onPresented()
            }, Handler(Looper.getMainLooper())) }
            stage = VideoStage.START
            codecCall { created.start() }
            val software = codecCall { isSoftwareDecoder(created.codecInfo) }
            description = "Video decoder: $name${if (software) " (software; latency may be higher)" else ""}"
            decoder = created
        } catch (e: Exception) {
            try { opened?.release() } catch (cleanup: Exception) { e.addSuppressed(cleanup) }
            throw VideoInitializationFailure(stage, e)
        }
    }

    override fun dequeueInput(): Int = codecCall { decoder.dequeueInputBuffer(0) }
    override fun input(index: Int): ByteBuffer? = codecCall { decoder.getInputBuffer(index) }
    override fun queueInput(index: Int, size: Int, ptsUs: Long, keyFrame: Boolean) = codecCall {
        decoder.queueInputBuffer(index, 0, size, ptsUs, if (keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
    }
    override fun dequeueOutput(): VideoOutput? = codecCall {
        val index = decoder.dequeueOutputBuffer(info, 0)
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)
            validateRaster(decoder.outputFormat, expectedWidth, expectedHeight)
        if (index >= 0) VideoOutput(index, info.presentationTimeUs, info.size, info.flags) else null
    }
    override fun releaseOutput(index: Int, renderAtNs: Long?): Unit = codecCall {
        if (renderAtNs == null) decoder.releaseOutputBuffer(index, false)
        else decoder.releaseOutputBuffer(index, renderAtNs)
    }
    override fun close() {
        try {
            codecCall { decoder.stop() }
        } catch (e: Exception) {
            try { decoder.release() } catch (cleanup: Exception) { e.addSuppressed(cleanup) }
            throw e
        }
        codecCall { decoder.release() }
    }

    companion object {
        internal fun validateRaster(actual: MediaFormat, expectedWidth: Int, expectedHeight: Int) {
            fun visibleSize(size: String, start: String, end: String): Int {
                if (!actual.containsKey(size)) throw PlaybackException("Missing decoder raster metadata")
                val coded = actual.getInteger(size)
                if (coded <= 0) throw PlaybackException("Invalid decoder raster metadata")
                if (!actual.containsKey(start) && !actual.containsKey(end)) return coded
                if (!actual.containsKey(start) || !actual.containsKey(end))
                    throw PlaybackException("Incomplete decoder crop metadata")
                val first = actual.getInteger(start)
                val last = actual.getInteger(end)
                if (first < 0 || last < first || last >= coded)
                    throw PlaybackException("Invalid decoder crop metadata")
                return last - first + 1
            }
            if (visibleSize(MediaFormat.KEY_WIDTH, MediaFormat.KEY_CROP_LEFT, MediaFormat.KEY_CROP_RIGHT) != expectedWidth ||
                visibleSize(MediaFormat.KEY_HEIGHT, MediaFormat.KEY_CROP_TOP, MediaFormat.KEY_CROP_BOTTOM) != expectedHeight)
                throw PlaybackException("Decoded raster differs from the configured stream")
        }
    }

    private inline fun <T> codecCall(operation: () -> T): T = try {
        operation()
    } catch (e: MediaCodec.CodecException) {
        throw VideoCodecFailure(e.errorCode, e.isRecoverable, e.isTransient, e)
    }
}

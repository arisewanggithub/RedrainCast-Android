package org.screencopy.receiver.media

import android.media.MediaCodec
import android.media.MediaCodecInfo.CodecProfileLevel as Profiles
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.view.Surface
import java.nio.ByteBuffer

internal data class VideoOutput(val index: Int, val ptsUs: Long, val size: Int, val flags: Int,
    val path: VideoPathStatistics = VideoPathStatistics(null, null))

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
    private val color = format.videoColor
    private val toneMapToSdr = format.toneMapToSdr
    private val mime = format.mime
    private var outputPath = VideoPathStatistics(
        if (color != null) DecodedVideoStatistics(VideoStreamFormat.Hdr10, null, null, null) else null, null)

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
            if (toneMapToSdr) validateToneMapInput(created.inputFormat)
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
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            val actual = decoder.outputFormat
            validateRaster(actual, expectedWidth, expectedHeight)
            if (color != null) validateColor(actual, toneMapToSdr)
            outputPath = outputStatistics(actual, mime, color != null, toneMapToSdr)
        }
        if (index >= 0) VideoOutput(index, info.presentationTimeUs, info.size, info.flags, outputPath) else null
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
        internal fun outputStatistics(actual: MediaFormat, mime: String, hdr10: Boolean,
            toneMapToSdr: Boolean): VideoPathStatistics {
            fun integer(key: String): Int? {
                if (!actual.containsKey(key)) return null
                if (actual.getValueTypeForKey(key) != MediaFormat.TYPE_INTEGER) {
                    android.util.Log.w("ScreenCopyReceiver", "Non-integer decoder statistics metadata: $key")
                    return null
                }
                return actual.getInteger(key)
            }
            val standard = integer(MediaFormat.KEY_COLOR_STANDARD)
            val transfer = integer(MediaFormat.KEY_COLOR_TRANSFER)
            val sdr = transfer == MediaFormat.COLOR_TRANSFER_SDR_VIDEO
            val pq = transfer == MediaFormat.COLOR_TRANSFER_ST2084
            val range = if (hdr10) VideoStreamFormat.Hdr10 else if (sdr) VideoStreamFormat.Sdr else null
            // Surface/FP16 buffer precision is not decoded component depth.
            val depth = when (integer(MediaFormat.KEY_COLOR_FORMAT)) {
                android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010 -> 10
                android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
                android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar,
                android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar,
                android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible -> 8
                else -> null
            }
            val profile = integer(MediaFormat.KEY_PROFILE)?.let { profileName(mime, it) }
            val decoded = DecodedVideoStatistics(range,
                if (hdr10 && pq) VideoTransfer.Pq else if (sdr && standard == MediaFormat.COLOR_STANDARD_BT709)
                    VideoTransfer.Bt709 else null, profile, depth)
                .takeIf { it.streamFormat != null || it.transfer != null || it.codecProfile != null || it.bitDepth != null }
            val output = when {
                hdr10 && !toneMapToSdr && pq && standard == MediaFormat.COLOR_STANDARD_BT2020 &&
                    integer(MediaFormat.KEY_COLOR_RANGE) == MediaFormat.COLOR_RANGE_LIMITED -> VideoPresentationOutput.Hdr
                !hdr10 && sdr -> VideoPresentationOutput.Sdr
                else -> null
            }
            // The optional transfer request has no independently observed mapping proof.
            return VideoPathStatistics(decoded, output?.let(::VideoPresentationStatistics))
        }

        private fun profileName(mime: String, profile: Int): String? {
            return when (mime) {
                "video/avc" -> when (profile) {
                    Profiles.AVCProfileBaseline, Profiles.AVCProfileConstrainedBaseline -> "Baseline"
                    Profiles.AVCProfileMain -> "Main"
                    Profiles.AVCProfileExtended -> "Extended"
                    Profiles.AVCProfileHigh, Profiles.AVCProfileConstrainedHigh -> "High"
                    Profiles.AVCProfileHigh10 -> "High 10"
                    Profiles.AVCProfileHigh422 -> "High 4:2:2"
                    Profiles.AVCProfileHigh444 -> "High 4:4:4"
                    else -> null
                }
                "video/hevc" -> when (profile) {
                    Profiles.HEVCProfileMain -> "Main"
                    Profiles.HEVCProfileMain10, Profiles.HEVCProfileMain10HDR10,
                    Profiles.HEVCProfileMain10HDR10Plus -> "Main 10"
                    Profiles.HEVCProfileMainStill -> "Main Still Picture"
                    else -> null
                }
                "video/av01" -> when (profile) {
                    Profiles.AV1ProfileMain8, Profiles.AV1ProfileMain10,
                    Profiles.AV1ProfileMain10HDR10, Profiles.AV1ProfileMain10HDR10Plus -> "Main"
                    else -> null
                }
                else -> null
            }
        }

        internal fun validateToneMapInput(actual: MediaFormat) {
            if (android.os.Build.VERSION.SDK_INT < 31 || !actual.containsKey(MediaFormat.KEY_COLOR_TRANSFER_REQUEST) ||
                actual.getInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST) != MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                throw PlaybackException("Decoder did not accept HDR-to-SDR tone mapping; reconnect using SDR", "UNSUPPORTED_CONFIG")
        }

        internal fun validateColor(actual: MediaFormat, toneMapToSdr: Boolean) {
            val transfer = if (toneMapToSdr) MediaFormat.COLOR_TRANSFER_SDR_VIDEO else MediaFormat.COLOR_TRANSFER_ST2084
            if (!actual.containsKey(MediaFormat.KEY_COLOR_TRANSFER) || actual.getInteger(MediaFormat.KEY_COLOR_TRANSFER) != transfer ||
                !actual.containsKey(MediaFormat.KEY_COLOR_STANDARD) ||
                actual.getInteger(MediaFormat.KEY_COLOR_STANDARD) !=
                    (if (toneMapToSdr) MediaFormat.COLOR_STANDARD_BT709 else MediaFormat.COLOR_STANDARD_BT2020) ||
                !actual.containsKey(MediaFormat.KEY_COLOR_RANGE) ||
                actual.getInteger(MediaFormat.KEY_COLOR_RANGE) != MediaFormat.COLOR_RANGE_LIMITED)
                throw PlaybackException("Decoder output does not match the negotiated HDR color/output policy", "UNSUPPORTED_CONFIG")
        }

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

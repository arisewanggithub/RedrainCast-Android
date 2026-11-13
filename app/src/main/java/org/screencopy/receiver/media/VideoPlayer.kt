package org.screencopy.receiver.media

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import dev.redrain.cast.diagnostics.*
import dev.redrain.cast.MediaThreadDiagnostic
import dev.redrain.cast.MediaThreadRole
import java.nio.ByteBuffer

class VideoPlayer private constructor(
    private val format: VideoFormat,
    private val clock: PlaybackClock,
    report: (String) -> Unit,
    failure: (String, String) -> Unit,
    onPresented: () -> Unit,
    factory: (VideoFormat, () -> Unit) -> VideoDecoder,
    private val generation: Long,
    private val onPresentation: (VideoDiagnostic) -> Unit,
    private val onRotation: (Int) -> Unit,
    private val outputAvailable: () -> Boolean = { true },
    prepareThread: () -> Unit = {}
) : TrackWorker("ScreenCopy-video", 8 * 1024 * 1024, failure, track = 1, prepareThread = prepareThread) {
    constructor(format: VideoFormat, surface: Surface, clock: PlaybackClock, report: (String) -> Unit,
        failure: (String, String) -> Unit, onPresented: () -> Unit = {}, generation: Long = 1,
        onRotation: (Int) -> Unit = {}, outputAvailable: () -> Boolean = { true }) :
        this(format, clock, report, failure, onPresented, { selected, presented -> AndroidVideoDecoder(selected, surface, presented) },
            generation, { android.util.Log.i("ScreenCopyReceiver", "event=VIDEO_PRESENTATION ${it.logMessage()}") }, onRotation,
            outputAvailable, { MediaThreadDiagnostic.report(MediaThreadRole.VIDEO) })

    internal constructor(format: VideoFormat, clock: PlaybackClock, failure: (String, String) -> Unit, decoder: VideoDecoder,
        generation: Long = 1, onPresentation: (VideoDiagnostic) -> Unit = {}, onRotation: (Int) -> Unit = {},
        outputAvailable: () -> Boolean = { true }) :
        this(format, clock, {}, failure, {}, { _, _ -> decoder }, generation, onPresentation, onRotation, outputAvailable)

    internal constructor(format: VideoFormat, clock: PlaybackClock, failure: (String, String) -> Unit,
        factory: (VideoFormat, () -> Unit) -> VideoDecoder, generation: Long = 1) :
        this(format, clock, {}, failure, {}, factory, generation, {}, {})

    private val decoder: VideoDecoder
    private val supportsRenderedStatistics: Boolean
    private val hdrAdmission = format.videoColor?.let { HdrBitstream.Admission(format) }
    private var waiting: AccessUnit? = null
    private var firstKeyFrame = false
    private var heldOutput = -1
    private var heldPtsUs = 0L
    private var heldRotation = 0
    private var heldPath = VideoPathStatistics(null, null)
    private val pathStatistics = VideoPathStatisticsCache(
        if (format.videoColor != null) DecodedVideoStatistics(VideoStreamFormat.Hdr10, null, null, null) else null)
    internal fun videoPathStatistics(): VideoPathStatistics =
        if (running.get()) pathStatistics.snapshot() else VideoPathStatistics(null, null)
    private var presentedRotation = -1
    private val rotations = VideoRotationTimeline()
    private var heldAtNs = 0L
    private var waitingAtNs = 0L
    private var stage = VideoStage.SELECT
    private var delayUs: Long? = null
    private var inputBytes = 0
    private var inputCapacity = 0
    private var inputKeyFrame = false
    private var outputBytes = 0
    private var outputFlags = 0
    private var lastInputPtsUs: Long? = null
    private var lastOutputPtsUs: Long? = null
    private var queuedFrames = 0L
    @Volatile private var decodedFrames = 0L
    @Volatile private var renderSubmitted = 0L
    @Volatile private var decodedSkipped = 0L
    private val renderedFrames = java.util.concurrent.atomic.AtomicLong()
    private var diagnosticConfigurationCapture: String? = null
    private fun diagnosticVideo(stage: DiagnosticVideoStage, pts: Long, bytes: Long = -1,
                                reason: DiagnosticReason = DiagnosticReason.NONE, sourceAtNs: Long? = null,
                                keyFrame: Boolean? = null) {
        if (!running.get()) return
        RemoteDiagnostics.recorder.video("receiver", diagnosticSession, diagnosticGeneration,
            stage, pts, bytes, keyFrame = keyFrame, reason = reason, sourceAtNs = sourceAtNs)
    }
    @Volatile private var renderedObservation: SourceTimestampObservation? = null
    internal fun videoStatistics(): VideoStatistics {
        val observed = renderedObservation
        return VideoStatistics(decodedFrames, renderSubmitted, decodedSkipped,
            if (supportsRenderedStatistics) renderedFrames.get() else null, observed?.ptsUs, observed?.observedAtNs)
    }
    private var lateRendered = 0L
    private var pendingOutputAtNs: Long? = null
    private var lastPresentationEventNs: Long? = null
    private var lastReportedSkipped = 0L
    private var lastReportedLate = 0L
    internal var onDecoded: (Long) -> Unit = {}
    internal var onPlaybackStep: () -> Unit = {}
    @Volatile internal var discardOutputBeforePts: Long? = null
    @Volatile internal var dropStaleOutput = false

    init {
        require(format.width in 1..8192 && format.height in 1..8192 && format.frameRate in 0..240 &&
            format.rotation in listOf(0, 90, 180, 270) && generation > 0) { "Invalid video output format" }
        decoder = try {
            requireSupportedRotation(format.videoColor != null, format.rotation)
            factory(format) { if (running.get()) onPresented() }
        } catch (e: Exception) { throw failure(e) }
        supportsRenderedStatistics = decoder.supportsRenderedStatistics
        decoder.renderedStatistics { pts, atNs ->
            diagnosticVideo(DiagnosticVideoStage.RENDERED, pts, sourceAtNs = atNs)
            if (running.get() && statisticsEnabled) {
                pathStatistics.rendered(pts, atNs)
                renderedObservation = SourceTimestampObservation(pts, atNs)
                renderedFrames.incrementAndGet()
            }
        }
        stage = VideoStage.START
        try {
            report(decoder.description)
            onPresentation(snapshot())
        } catch (e: Exception) {
            try { decoder.close() } catch (cleanup: Exception) { e.addSuppressed(cleanup) }
            throw failure(e)
        }
    }

    override fun step() {
        val diagnostics = RemoteDiagnostics.recorder
        if (diagnostics.enabled("receiver", diagnosticSession, DiagnosticKind.VIDEO_PROGRESS) &&
            diagnosticConfigurationCapture != diagnostics.captureIdentity) {
            diagnosticConfigurationCapture = diagnostics.captureIdentity
            diagnostics.videoConfigured("receiver", diagnosticSession, diagnosticGeneration, DiagnosticVideoConfiguration(
                    DiagnosticCodec.from(format.mime), format.width, format.height, format.frameRate, supportsRenderedStatistics))
        }
        onPlaybackStep()
        if (heldOutput >= 0) {
            stage = VideoStage.CLOCK
            clock.checkStartup()
            // Only decoded pictures are replaceable; keep admitting every compressed dependency.
            for (index in 0 until 8) {
                if (!running.get()) return
                stage = VideoStage.CLOCK
                delayUs = clock.delayUs(heldPtsUs)
                if (delayUs?.let { it < 0 } != true) break
                val newer = dequeuePicture() ?: break
                if (!running.get()) return
                stage = VideoStage.OUTPUT_RELEASE
                decoder.releaseOutput(heldOutput, null)
                diagnosticVideo(DiagnosticVideoStage.SKIPPED, heldPtsUs, reason = DiagnosticReason.REPLACED)
                decodedSkipped++
                hold(newer)
            }
            if (!running.get()) return
            stage = VideoStage.CLOCK
            delayUs = clock.delayUs(heldPtsUs)
            if (dropStaleOutput && delayUs?.let { it < -250_000 } == true) {
                decoder.releaseOutput(heldOutput, null)
                diagnosticVideo(DiagnosticVideoStage.SKIPPED, heldPtsUs, reason = DiagnosticReason.STALE)
                decodedSkipped++
                heldOutput = -1
                return
            }
            if (delayUs?.let { it < -250_000 } == true) lateRendered++
            stage = VideoStage.OUTPUT_RELEASE
            requireSupportedRotation(format.videoColor != null, heldRotation)
            if (presentedRotation != heldRotation) {
                onRotation(heldRotation)
                presentedRotation = heldRotation
            }
            if (format.videoColor != null && !outputAvailable())
                throw PlaybackException("HDR output became unavailable; restart casting with SDR", "UNSUPPORTED_CONFIG")
            if (statisticsEnabled) pathStatistics.scheduled(heldPtsUs, heldPath)
            val renderDelayUs = delayUs?.coerceAtLeast(0) ?: 0
            val renderAtNs = Math.addExact(clock.monotonicNs(), Math.multiplyExact(renderDelayUs, 1_000L))
            decoder.releaseOutput(heldOutput, renderAtNs)
            diagnosticVideo(DiagnosticVideoStage.RELEASED, heldPtsUs, sourceAtNs = renderAtNs)
            clock.videoPresented(heldPtsUs, renderAtNs)
            renderSubmitted++
            heldOutput = -1
            reportPresentation()
        }
        if (waiting == null) {
            waiting = next()
            waitingAtNs = clock.monotonicNs()
        }
        val packet = waiting
        if (packet != null) {
            inputBytes = packet.bytes.size
            inputKeyFrame = packet.keyFrame
            inputCapacity = 0
            stage = VideoStage.CLOCK
            delayUs = if (packet.ptsUs >= 0) clock.delayUs(packet.ptsUs) else null
            stage = VideoStage.INPUT_KEYFRAME
            if (!firstKeyFrame && !packet.keyFrame) fail(VideoFault.FIRST_KEYFRAME)
            if (packet.ptsUs < 0 || lastInputPtsUs?.let { packet.ptsUs < it } == true) fail(VideoFault.INPUT_PTS)
            requireSupportedRotation(format.videoColor != null, packet.rotation)
            stage = VideoStage.INPUT_DEQUEUE
            val index = decoder.dequeueInput()
            if (index >= 0) {
                stage = VideoStage.INPUT_BUFFER
                try { hdrAdmission?.validate(packet.bytes) }
                catch (error: org.screencopy.receiver.protocol.ProtocolException) {
                    throw PlaybackException(checkNotNull(error.message), error.code).apply { initCause(error) }
                }
                val input = decoder.input(index) ?: fail(VideoFault.INPUT_BUFFER)
                inputCapacity = input.capacity()
                if (inputCapacity < packet.bytes.size) fail(VideoFault.INPUT_CAPACITY)
                input.clear()
                input.put(packet.bytes)
                clock.anchorVideo(packet.ptsUs)
                stage = VideoStage.INPUT_QUEUE
                decoder.queueInput(index, packet.bytes.size, packet.ptsUs, packet.keyFrame)
                diagnosticVideo(DiagnosticVideoStage.DECODER_INPUT, packet.ptsUs, packet.bytes.size.toLong(),
                    keyFrame = packet.keyFrame)
                rotations.offer(packet.ptsUs, packet.rotation)
                lastInputPtsUs = packet.ptsUs
                queuedFrames++
                if (pendingOutputAtNs == null && lastOutputPtsUs?.let { packet.ptsUs > it } != false)
                    pendingOutputAtNs = clock.monotonicNs()
                firstKeyFrame = true
                waiting = null
            } else if (clock.monotonicNs() - waitingAtNs > 3_000_000_000L) fail(VideoFault.INPUT_STALL)
        }
        if (heldOutput < 0) dequeuePicture()?.let(::hold)
        if (heldOutput < 0 && pendingOutputAtNs?.let { clock.monotonicNs() - it > 3_000_000_000L } == true)
            fail(VideoFault.OUTPUT_STALL)
    }

    private fun dequeuePicture(): VideoOutput? {
        stage = VideoStage.OUTPUT_DEQUEUE
        val output = decoder.dequeueOutput()
        if (output != null) {
            stage = VideoStage.OUTPUT_VALIDATE
            outputBytes = output.size
            outputFlags = output.flags
            if (output.size < 0 || output.index == heldOutput) fail(VideoFault.OUTPUT_METADATA)
            if (output.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) fail(VideoFault.END_OF_STREAM)
            if (output.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 || output.size == 0) {
                stage = VideoStage.OUTPUT_RELEASE
                decoder.releaseOutput(output.index, null)
            } else {
                diagnosticVideo(DiagnosticVideoStage.DECODED, output.ptsUs, output.size.toLong())
                if (discardOutputBeforePts?.let { output.ptsUs < it } == true) {
                    decoder.releaseOutput(output.index, null)
                    diagnosticVideo(DiagnosticVideoStage.SKIPPED, output.ptsUs, reason = DiagnosticReason.STALE)
                    decodedSkipped++
                    return null
                }
                stage = VideoStage.CLOCK
                delayUs = if (output.ptsUs >= 0) clock.delayUs(output.ptsUs) else null
                stage = VideoStage.OUTPUT_VALIDATE
                if (output.ptsUs < 0 || lastOutputPtsUs?.let { output.ptsUs < it } == true) fail(VideoFault.OUTPUT_PTS)
                lastOutputPtsUs = output.ptsUs
                if (statisticsEnabled) pathStatistics.decoded(output.path)
                decodedFrames++
                onDecoded(output.ptsUs)
                // A codec may omit an earlier picture; latest output PTS, not 1:1 counts, proves progress.
                pendingOutputAtNs = if (lastInputPtsUs?.let { output.ptsUs >= it } != false) null else clock.monotonicNs()
                return output
            }
        }
        return null
    }

    private fun hold(output: VideoOutput) {
        heldOutput = output.index
        heldPtsUs = output.ptsUs
        heldPath = output.path
        heldRotation = rotations.at(output.ptsUs)
        heldAtNs = clock.monotonicNs()
        outputBytes = output.size
        outputFlags = output.flags
    }

    private fun reportPresentation() {
        if (decodedSkipped == lastReportedSkipped && lateRendered == lastReportedLate) return
        val now = clock.monotonicNs()
        if (lastPresentationEventNs?.let { now - it < 1_000_000_000L } == true) return
        onPresentation(snapshot())
        lastPresentationEventNs = now
        lastReportedSkipped = decodedSkipped
        lastReportedLate = lateRendered
    }

    private fun snapshot(fault: VideoFault = VideoFault.NONE, exception: String = "None", codec: VideoCodecFailure? = null) =
        VideoDiagnostic(stage, fault, exception, generation, format.width, format.height, format.rotation, format.frameRate,
            delayUs, if (heldOutput >= 0) (clock.monotonicNs() - heldAtNs).coerceAtLeast(0) / 1000 else 0,
            inputBytes, inputCapacity, inputKeyFrame, outputBytes, outputFlags, queuedFrames, decodedFrames,
            renderSubmitted, decodedSkipped, lateRendered, codec?.errorCode, codec?.recoverable ?: false, codec?.transient ?: false,
            when (format.mime) { "video/avc" -> "H264"; "video/hevc" -> "H265"; "video/av01" -> "AV1"; else -> "UNKNOWN" },
            if (waiting != null) (clock.monotonicNs() - waitingAtNs).coerceAtLeast(0) / 1000 else 0,
            pendingOutputAtNs?.let { (clock.monotonicNs() - it).coerceAtLeast(0) / 1000 } ?: 0)

    private fun fail(fault: VideoFault): Nothing = throw VideoPlaybackException(snapshot(fault, "VideoPlaybackException"))

    private fun failure(error: Exception): VideoPlaybackException {
        if (error is VideoPlaybackException) return error
        val original = if (error is VideoInitializationFailure) error.failure else error
        if (error is VideoInitializationFailure) stage = error.stage
        val codec = original as? VideoCodecFailure
        val fault = when {
            original is PlaybackException && original.code == "UNSUPPORTED_CONFIG" -> VideoFault.UNSUPPORTED
            codec?.errorCode == 1100 -> VideoFault.CODEC_RESOURCE
            codec?.errorCode == 1101 -> VideoFault.CODEC_RECLAIMED
            stage == VideoStage.CLOCK -> VideoFault.CLOCK_FAILURE
            else -> VideoFault.CODEC_API
        }
        val exception = if (codec != null) "CodecException" else
            original.javaClass.simpleName.takeIf { it.matches(Regex("[A-Za-z0-9_$]{1,64}")) } ?: "Exception"
        return VideoPlaybackException(snapshot(fault, exception, codec), error)
    }

    override fun describeFailure(error: Exception): String {
        val diagnostic = failure(error).diagnostic
        if (RemoteDiagnostics.recorder.enabled("receiver", diagnosticSession, DiagnosticKind.VIDEO_PROGRESS))
            RemoteDiagnostics.recorder.observe("receiver", diagnosticSession, DiagnosticKind.VIDEO_PROGRESS,
                DiagnosticStage.FAILED, 1, diagnosticGeneration, pts = waiting?.ptsUs ?: lastInputPtsUs ?: -1,
                reason = DiagnosticReason.FAILURE, metadata = DiagnosticVideoFailure(diagnostic.stage, diagnostic.fault))
        if (error is AudioClockException) return super.describeFailure(error)
        val admissionError = (error as? PlaybackException)?.cause as? org.screencopy.receiver.protocol.ProtocolException
        val reason = admissionError?.message?.let { ": $it" } ?: ""
        return "Video stage=${diagnostic.stage} exception=${diagnostic.exceptionType}$reason: Video failure: ${diagnostic.logMessage()}"
    }

    override fun release() {
        RemoteDiagnostics.recorder.observe("receiver", diagnosticSession, DiagnosticKind.VIDEO_PROGRESS,
            DiagnosticStage.RESET, 1, diagnosticGeneration, pts = lastOutputPtsUs ?: -1, reason = DiagnosticReason.STOPPED)
        stage = VideoStage.CLOSE
        pathStatistics.close()
        try { decoder.close() } catch (e: Exception) { throw failure(e) }
    }

    companion object {
        internal fun requireSupportedRotation(hdr: Boolean, rotation: Int) {
            require(rotation in listOf(0, 90, 180, 270))
            if (hdr && rotation != 0) {
                android.util.Log.e("ScreenCopyReceiver", "event=UNSUPPORTED_HDR_ROTATION rotation=$rotation")
                throw PlaybackException("HDR picture rotation is unsupported by direct SurfaceView output", "UNSUPPORTED_CONFIG")
            }
        }

        fun mediaFormat(format: VideoFormat): MediaFormat {
            require(format.width in 1..8192 && format.height in 1..8192) { "Unsupported video dimensions" }
            require(format.frameRate in 0..240) { "Unsupported video frame rate" }
            require(format.rotation in listOf(0, 90, 180, 270)) { "Invalid video rotation" }
            return MediaFormat.createVideoFormat(format.mime, format.width, format.height).apply {
                if (format.frameRate > 0) setInteger(MediaFormat.KEY_FRAME_RATE, format.frameRate)
                setInteger(MediaFormat.KEY_ROTATION, 0)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8 * 1024 * 1024)
                format.videoColor?.let { color ->
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10)
                    setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                    setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084)
                    setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                    color.staticMetadata?.let { setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO, it.descriptor()) }
                    if (format.toneMapToSdr) {
                        require(android.os.Build.VERSION.SDK_INT >= 31) { "Decoder HDR-to-SDR requires Android 12 or newer" }
                        setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                    }
                }
                format.configuration.forEachIndexed { index, bytes ->
                    setByteBuffer("csd-$index", ByteBuffer.wrap(bytes))
                }
            }
        }
    }
}

internal fun findPreferredDecoder(format: MediaFormat,
    candidates: List<MediaCodecInfo>? = null): String? {
    if (format.containsKey(MediaFormat.KEY_PROFILE) &&
        format.getInteger(MediaFormat.KEY_PROFILE) == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10) {
        val selected = if (candidates == null) HdrDecoderCapabilities.find(format) else HdrDecoderCapabilities.find(format, candidates)
        return selected ?: throw PlaybackException(HdrDecoderCapabilities.rejection(format,
            candidates ?: MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.toList()), "UNSUPPORTED_CONFIG")
    }
    val mime = checkNotNull(format.getString(MediaFormat.KEY_MIME))
    val available = candidates ?: MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.toList()
    return available.asSequence()
        .filter { !it.isEncoder && it.supportedTypes.any { type -> type.equals(mime, true) } }
        .sortedBy { isSoftwareDecoder(it) }
        .firstOrNull { it.getCapabilitiesForType(mime).isFormatSupported(format) }?.name
        ?: throw PlaybackException(HdrDecoderCapabilities.rejection(format, available), "UNSUPPORTED_CONFIG")
}

internal fun isSoftwareDecoder(info: MediaCodecInfo): Boolean =
    if (android.os.Build.VERSION.SDK_INT >= 29) !info.isHardwareAccelerated
    else info.name.startsWith("OMX.google.") || info.name.startsWith("c2.android.")

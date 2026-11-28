package org.screencopy.receiver.media

import android.media.AudioFormat as PlatformAudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import dev.redrain.cast.MediaThreadDiagnostic
import dev.redrain.cast.MediaThreadRole
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import dev.redrain.cast.diagnostics.*

internal fun aacPastRecoveryBoundary(boundaryUs: Long, ptsUs: Long, rate: Int): Boolean {
    val guardUs = maxOf(120_000L, (1024 * 1_000_000L + rate - 1) / rate)
    return ptsUs > boundaryUs && ptsUs - boundaryUs > guardUs
}

const val DEFAULT_AUDIO_QUEUE_SPAN_US = 1_000_000L
// Allows a one-second span of 5 ms packets while still bounding tiny-packet overhead.
private const val AUDIO_QUEUE_MAX_PACKETS = 256

internal class AudioCausalStartGate {
    private var previous: List<Long>? = null
    private var capture: String? = null
    private var generation = -1
    private var session = -1L

    @Synchronized fun reset() { previous = null }

    @Synchronized fun take(session: Long, generation: Int, capture: String?, decision: AudioStartDecision): Boolean {
        val transition = listOf(decision.reason.toLong(), decision.mode.toLong(),
            decision.targetUs, decision.revision, decision.flags)
        if (this.session == session && this.generation == generation && this.capture == capture &&
            previous == transition) return false
        this.session = session
        this.generation = generation
        this.capture = capture
        previous = transition
        return true
    }
}

class AudioPlayer(
    private val format: AudioFormat,
    private val clock: PlaybackClock,
    failure: (String, String) -> Unit,
    outputFactory: () -> AudioOutput = {
        if (dev.redrain.cast.BuildConfig.DEBUG && dev.redrain.cast.BuildConfig.VIRTUAL_AUDIO_EXPERIMENT)
            VirtualDiscardAudioOutput(format.sampleRate, format.channels)
        else PlatformAudioOutput(format, failure)
    },
    private val onClockCorrection: (AudioClockCorrection) -> Unit = {},
    private val onOutputTiming: (AudioOutputTiming) -> Unit = {},
    private val onUnderrunRecovery: (AudioUnderrunDiagnostic) -> Unit = {},
    opusFactory: (AudioFormat) -> OpusPacketDecoder = { OpusDecoder(it.channels, it.outputGainQ8) },
    private val onRegulation: (AudioRegulationDiagnostic) -> Unit = {},
    resamplerFactory: (Int, Int) -> PcmResampler = ::FfmpegPcmResampler,
    private val onServiceDiagnostic: ((String) -> Unit)? = null,
    private val threadCpuNs: () -> Long = android.os.Debug::threadCpuTimeNanos,
    prepareThread: () -> Unit = { MediaThreadDiagnostic.report(MediaThreadRole.AUDIO) },
    aacFactory: ((AudioFormat) -> MediaCodec)? = null,
    audioQueueSpanUs: Long = DEFAULT_AUDIO_QUEUE_SPAN_US
) : TrackWorker("ScreenCopy-audio", 512 * 1024, { code, detail -> failure(if (code == "DECODER_FAILED") "AUDIO_FAILED" else code, detail) },
    track = 2, prepareThread = prepareThread, maxPackets = AUDIO_QUEUE_MAX_PACKETS, maxSpanUs = audioQueueSpanUs) {
    private val output: AudioOutput
    private val opus: OpusPacketDecoder?
    private val aac: MediaCodec?
    private val resampler: PcmResampler
    private val resampledTimeline = ResampledAudioTimeline(format.sampleRate)
    private var contiguousSourceStartUs: Long? = null
    private var contiguousSourceEndUnits: Long? = null
    @Volatile private var discardCoverage: AudioPresentationTimeline.Coverage? = null
    private val info = if (format.codec == "aac") MediaCodec.BufferInfo() else null
    private var waiting: AccessUnit? = null
    private var pending: DecodedAudio? = null
    private val recoveryAudio = ArrayDeque<DecodedAudio>()
    private var decodeReservation = 0
    private var recoveryStartAtNs: Long? = null
    private val startup = StartupTrim(format.preSkip, format.sampleRate)
    @Volatile private var totalFrames = 0L
    @Volatile private var sourceWrittenUnits = 0L
    internal val sourceSubmittedFrames: Long get() = sourceWrittenUnits / 1_000_000
    internal val retainedSourceFrames: Int get() = resampledTimeline.retainedFrames
    @Volatile internal var processedSourceFrames = 0L
        private set
    @Volatile internal var staleDiscardedSourceFrames = 0L
        private set
    @Volatile private var presentedSourceUs = 0L
    @Volatile private var sourceClock: AudioSourceClock? = null
    internal fun sourcePositionUs(): Long = presentedSourceUs
    @Volatile private var firstPtsUs: Long? = null
    private val underruns = AudioUnderrunMonitor()
    private var lastUnderrunObservationNs: Long? = null
    private val regulator = AudioRegulator(format.sampleRate, format.channels)
    private val presentation = AudioPresentationTimeline(format.sampleRate)
    private val outputBlockFrames = maxOf(1, format.sampleRate * 5 / 1000)
    private val silence = ShortArray(outputBlockFrames * format.channels)
    private var deviceFrames = 0L
    private var lastOutputProgressNs = 0L
    private var lastConsumedFrames = 0L
    private var stage = "STARTUP_CHECK"
        set(value) {
            field = value
            causalPhase(CausalEvidence.phases.indexOf(value).toLong())
        }
    private var lastClockEventNs: Long? = null
    private var lastLoggedOffsetUs = 0L
    private var pendingClockCorrection: AudioClockCorrection? = null
    private var lastDecodedPtsUs: Long? = null
    @Volatile internal var recoveryCutoffUs: Long? = null
    internal var onFirstSubmitted: (Long) -> Unit = {}
    internal var onPositiveSourceSubmitted: () -> Unit = {}
    internal var onRecoveryPressure: (QueuePressure) -> Unit = {}
    private var recoveryPressureReported = false
    private var submittedReported = false
    @Volatile private var audioObservation: Pair<Long, AudioStatistics>? = null
    internal val audioStatistics: AudioStatistics? get() = audioObservation?.second
    internal fun freshAudioStatistics(nowMs: Long): AudioStatistics? {
        val observed = audioObservation ?: return null
        return observed.second.takeIf { nowMs - observed.first / 1_000_000 in 0..1000 }
    }
    private var statisticsAtNs = Long.MIN_VALUE
    private var startupCatchup = true
    @Volatile internal var preservingRecovery = false
        private set
    @Volatile internal var recoverySourceBoundary: Long? = null
        private set
    @Volatile internal var aacRecoveryInputBoundaryUs: Long? = null
        private set
    private var latestAacInputPtsUs: Long? = null
    private var aacInputPastBoundary = false
    private var cachedOutputTiming: AudioOutputTiming? = null
    private var cachedOutputAtNs = -1L
    private var cachedUnderruns = -1L
    private var outputOperation = 0L
    private var outputPoint = 0L
    private var outputCallAt = -1L
    private var outputActive = false
    private var outputCompleteAt = -1L
    private var outputDuration = -1L
    private var maxOutputDuration = -1L
    private var zeroWrites = 0L
    private var partialWrites = 0L
    private var firstZeroAt = -1L
    private var firstPartialAt = -1L
    private var lastCompletePoint = -1L
    private var trimPublished = false
    private var audioTimingProgressNs = Long.MIN_VALUE
    private var traceOverheadCounter = 0
    private val startEvidenceGate = AudioStartEvidenceGate()
    private val causalStartGate = AudioCausalStartGate()
    private fun recordTrim(reason: Long, before: TrimmedAudio, after: TrimmedAudio, cutoff: Long, position: Long) {
        if (trimPublished && before.audiblePtsUs == after.audiblePtsUs && before.frames == after.frames) return
        trimPublished = true
        TelemetryRegistry.causal(diagnosticSession, CausalEvidence.Stage.TRIM, diagnosticGeneration,
            reason, before.audiblePtsUs, after.audiblePtsUs, cutoff, position,
            before.frames.toLong(), after.frames.toLong(), atNs = clock.monotonicNs())
    }
    private fun outputCheckpoint(boundary: Int) {
        if (!TelemetryRegistry.has("receiver", diagnosticSession)) return
        val timing = cachedOutputTiming
        TelemetryRegistry.causal(diagnosticSession, CausalEvidence.Stage.OUTPUT, diagnosticGeneration,
            boundary.toLong(), outputOperation, outputPoint, outputCallAt, outputCompleteAt,
            outputDuration, maxOutputDuration, zeroWrites, partialWrites, firstZeroAt, firstPartialAt,
            cachedOutputAtNs, timing?.submittedFrames ?: -1, timing?.consumedFrames ?: -1,
            timing?.presentedFrames ?: -1, timing?.timestampAgeUs ?: -1,
            timing?.clockSource?.ordinal?.toLong() ?: -1, cachedUnderruns,
            lastOutputProgressNs.takeIf { it > 0 } ?: -1, lastCompletePoint, atNs = clock.monotonicNs())
    }
    private fun observedUnderrunCount(): Int = timedAudioWork(DiagnosticAudioTiming.UNDERRUN) {
        diagnosticCall(DiagnosticPoint.TIMING) {
            outputCheckpoint(CausalEvidence.Output.UNDERRUN_BEGIN.ordinal)
            val count = output.underrunCount
            outputCheckpoint(CausalEvidence.Output.UNDERRUN_END.ordinal)
            count
        }
    }

    private fun readOutputTiming(): AudioOutputTiming = timedAudioWork(DiagnosticAudioTiming.TIMING) {
        diagnosticCall(DiagnosticPoint.TIMING) { output.timing() }
    }
    internal fun resetAudioStartEvidence() {
        synchronized(startEvidenceGate) { startEvidenceGate.reset() }
        causalStartGate.reset()
        TelemetryRegistry.state("receiver", diagnosticSession, 60,
            DiagnosticKind.AUDIO_OUTPUT.ordinal + 1, DiagnosticStage.SAMPLE.ordinal,
            2, diagnosticGeneration, -1, listOf(DiagnosticPoint.AUDIO_START_WAIT.ordinal.toLong(),
                0, 0, 0, -1, Long.MIN_VALUE, -1, -1, 0, -1, 0, -1, -1, -1, -1, -1, -1, -1, -1, -1))
        TelemetryRegistry.state("receiver", diagnosticSession, 63,
            DiagnosticKind.AUDIO_OUTPUT.ordinal + 1, DiagnosticStage.SAMPLE.ordinal,
            2, diagnosticGeneration, -1, listOf(DiagnosticPoint.AUDIO_START_WAIT.ordinal.toLong(),
                0, -1, 0, 0, 0, 0, -1, -1, -1, -1, -1, -1, -1, -1, Long.MIN_VALUE, 0))
    }
    internal inline fun <T> diagnosticCall(point: DiagnosticPoint, pts: Long = -1, count: Long = -1,
                                         requestedSamples: Long = -1, call: (Long) -> T): T {
        val diagnostics = RemoteDiagnostics.recorder
        val session = diagnosticSession
        val detailed = diagnostics.enabled("receiver", session, DiagnosticKind.AUDIO_OUTPUT)
        if (!detailed && !TelemetryRegistry.has("receiver", session)) return call(0)
        val operation = diagnostics.operation("receiver", session, DiagnosticKind.AUDIO_OUTPUT)
        val outputCall = point != DiagnosticPoint.AUDIO_START_WAIT
        val parentActive = outputActive
        val parentOperation = outputOperation
        val parentPoint = outputPoint
        val parentCallAt = outputCallAt
        val callAt = if (outputCall) clock.monotonicNs() else -1
        if (outputCall) {
            outputOperation = operation
            outputPoint = point.ordinal.toLong()
            outputCallAt = callAt
            outputActive = true
            outputCheckpoint(CausalEvidence.Output.CALL_BEGIN.ordinal)
        }
        var restored = false
        try {
            diagnostics.observe("receiver", session, DiagnosticKind.AUDIO_OUTPUT, DiagnosticStage.BEGIN,
                2, diagnosticGeneration, operation, pts, count = count, point = point)
            val result = call(operation)
            if (result is AudioOutputTiming) {
                cachedOutputTiming = result
                cachedOutputAtNs = clock.monotonicNs()
            }
            if (outputCall) {
                outputCompleteAt = clock.monotonicNs()
                lastCompletePoint = point.ordinal.toLong()
                outputDuration = outputCompleteAt - callAt
                maxOutputDuration = maxOf(maxOutputDuration, outputDuration)
                if (point == DiagnosticPoint.WRITE && result is Int) {
                    if (result == 0) {
                        zeroWrites++
                        if (firstZeroAt < 0) firstZeroAt = outputCompleteAt
                    } else if (result > 0 && result < requestedSamples) {
                        partialWrites++
                        if (firstPartialAt < 0) firstPartialAt = outputCompleteAt
                    }
                }
                if (parentActive) {
                    outputOperation = parentOperation
                    outputPoint = parentPoint
                    outputCallAt = parentCallAt
                    restored = true
                }
                outputCheckpoint(if (parentActive) CausalEvidence.Output.CALL_BEGIN.ordinal
                    else CausalEvidence.Output.CALL_END.ordinal)
            }
            diagnostics.observe("receiver", session, DiagnosticKind.AUDIO_OUTPUT, DiagnosticStage.COMPLETE,
                2, diagnosticGeneration, operation, pts, count = if (result is Int) result.toLong() else count, point = point,
                bytes = if (point == DiagnosticPoint.WRITE && result is Int && result >= 0) result.toLong() * 2 else -1,
                metadata = if (detailed && result is AudioOutputTiming) DiagnosticOutput(result.submittedFrames, result.consumedFrames,
                    result.presentedFrames, result.timestampAgeUs, result.clockSource) else null)
            return result
        } catch (e: Exception) {
            if (outputCall) {
                outputOperation = operation
                outputPoint = point.ordinal.toLong()
                outputCallAt = callAt
                restored = false
                outputCheckpoint(CausalEvidence.Output.CALL_FAILED.ordinal)
            }
            diagnostics.observe("receiver", session, DiagnosticKind.AUDIO_OUTPUT, DiagnosticStage.FAILED,
                2, diagnosticGeneration, operation, pts, reason = DiagnosticReason.FAILURE, point = point)
            throw e
        } finally {
            if (outputCall) {
                outputActive = parentActive
                if (parentActive && !restored) {
                    outputOperation = parentOperation
                    outputPoint = parentPoint
                    outputCallAt = parentCallAt
                    outputCheckpoint(CausalEvidence.Output.CALL_BEGIN.ordinal)
                }
            }
        }
    }

    private val serviceDiagnostic = AudioServiceDiagnostic(format.sampleRate)
    private val eventHistory = onServiceDiagnostic?.let { AudioEventHistory() }

    internal fun packetReceived(ptsUs: Long, bytes: Int) {
        eventHistory?.record(clock.monotonicNs(), "receive", ptsUs, bytes.toLong())
    }
    override fun packetEvent(kind: String, packet: AccessUnit, queued: Int) {
        eventHistory?.record(clock.monotonicNs(), kind, packet.ptsUs, queued.toLong())
    }

    val decoderDescription: String
        get() = when (format.codec) {
            "opus" -> "bundled libopus 1.5.2"
            "aac" -> checkNotNull(aac).name
            else -> "PCM s16le direct"
        } + if (output is VirtualDiscardAudioOutput)
            "; FFmpeg PCM compensation, virtual discard (no audio playback)"
        else "; FFmpeg PCM compensation, fixed AudioTrack rate"

    init {
        require(format.channels in 1..2) { "Only mono/stereo audio is supported" }
        require(format.sampleRate in listOf(8000, 12000, 16000, 24000, 32000, 44100, 48000, 96000)) { "Unsupported audio sample rate" }
        require(format.preSkip in 0..65535) { "Invalid audio pre-skip" }
        require(format.codec in listOf("opus", "aac", "pcm")) { "Unsupported audio codec ${format.codec}" }
        require(format.codec != "opus" || format.sampleRate == 48000) { "Opus output clock must be 48000 Hz" }
        output = outputFactory()
        if (output is VirtualDiscardAudioOutput)
            android.util.Log.w("ScreenCopyReceiver", "event=VIRTUAL_AUDIO_EXPERIMENT mode=2 physical_playback=false pcm=discarded")
        else if ((output as? PlatformAudioOutput)?.nativeAndroid == true && dev.redrain.cast.BuildConfig.DEBUG &&
            dev.redrain.cast.BuildConfig.HYBRID_AUDIO_EXPERIMENT)
            android.util.Log.w("ScreenCopyReceiver", "event=HYBRID_AUDIO_EXPERIMENT mode=3 output=AudioTrack video_clock=virtual physical_progress=native")
        else if ((output as? PlatformAudioOutput)?.nativeAndroid == true && dev.redrain.cast.BuildConfig.DEBUG)
            android.util.Log.i("ScreenCopyReceiver", "event=AUDIO_MODE mode=1 output=AudioTrack video_clock=native")
        (output as? AudioTimingDiagnostic)?.timingCheckpoint = ::outputCheckpoint
        var createdResampler: PcmResampler? = null
        var createdOpus: OpusPacketDecoder? = null
        try {
            createdResampler = resamplerFactory(format.sampleRate, format.channels)
            resampler = createdResampler
            createdOpus = if (format.codec == "opus") opusFactory(format) else null
            opus = createdOpus
            aac = if (format.codec == "aac") (aacFactory ?: ::createAac)(format) else null
        } catch (e: Exception) {
            try { createdOpus?.close() } catch (cleanup: Exception) { e.addSuppressed(cleanup) }
            try { createdResampler?.close() } catch (cleanup: Exception) { e.addSuppressed(cleanup) }
            try { output.close() } catch (cleanup: Exception) { e.addSuppressed(cleanup) }
            throw e
        }
    }

    override fun step() {
        measured("step") { serviceStep() }
        reportService()
    }
    override fun causalNowNs(): Long = clock.monotonicNs()

    private inline fun <T> timedAudioWork(
        stage: Int,
        timing: DiagnosticAudioTiming? = RemoteDiagnostics.recorder.audioTiming(diagnosticSession),
        packetId: Long = 0,
        block: () -> T
    ): T {
        if (timing == null || !timing.workEnabled ||
            stage > DiagnosticAudioTiming.STARTUP_SUBMISSION && !timing.detailEnabled) return block()
        val sampleOverhead = timing.detailEnabled && ++traceOverheadCounter % 128 == 0
        val beforeWall = clock.monotonicNs()
        val beforeCpu = threadCpuNs().coerceAtLeast(-1)
        timing.record(beforeWall, diagnosticGeneration, packetId,
            DiagnosticAudioTiming.WORK_BEGIN, stage.toLong(), beforeCpu)
        if (sampleOverhead) {
            val afterWall = clock.monotonicNs()
            val afterCpu = threadCpuNs().coerceAtLeast(-1)
            timing.record(afterWall, diagnosticGeneration, packetId, DiagnosticAudioTiming.TRACE_OVERHEAD,
                (afterWall - beforeWall).coerceAtLeast(0),
                if (beforeCpu < 0 || afterCpu < 0) -1 else (afterCpu - beforeCpu).coerceAtLeast(0))
        }
        var failed = false
        try {
            return block()
        } catch (error: Throwable) {
            failed = true
            throw error
        } finally {
            timing.record(clock.monotonicNs(), diagnosticGeneration, packetId,
                if (failed) DiagnosticAudioTiming.WORK_FAILED else DiagnosticAudioTiming.WORK_END,
                stage.toLong(), threadCpuNs().coerceAtLeast(-1))
        }
    }

    private fun reportService(final: Boolean = false) {
        if (output is VirtualDiscardAudioOutput) return
        serviceDiagnostic.report(clock.monotonicNs(), regulator.discardedFrames, final)?.let { report ->
            onServiceDiagnostic?.let { callback ->
                // Final reporting runs on the closing caller, not the audio worker.
                if (final) callback(report)
                else timedAudioWork(DiagnosticAudioTiming.CALLBACK) { callback(report) }
            }
        }
    }

    private inline fun <T> measured(operation: String, block: () -> T): T {
        val diagnostic = serviceDiagnostic
        if (!diagnostic.started && !TelemetryRegistry.has("receiver", diagnosticSession)) return block()
        val start = clock.monotonicNs()
        val cpu = if (onServiceDiagnostic != null || TelemetryRegistry.has("receiver", diagnosticSession))
            threadCpuNs() else -1
        try {
            return block()
        } finally {
            val end = clock.monotonicNs()
            val usedCpu = if (cpu >= 0) threadCpuNs() - cpu else -1
            diagnostic.duration(operation, end - start, usedCpu.coerceAtLeast(0))
            causalInterval(start, end, usedCpu, when (operation) {
                "step" -> 0; "input" -> 1; "decode" -> 2; else -> 3
            })
        }
    }

    private fun serviceStep() {
        stage = "STARTUP_CHECK"
        clock.checkStartup()
        if (pending != null || firstPtsUs == null && recoveryAudio.isNotEmpty()) {
            flushPending()
        }
        measured("refill") { pumpOutput(allowSilence = false) }
        if (firstPtsUs != null && recoveryAudio.isNotEmpty() &&
            regulator.bufferedFrames < regulator.targetFrames) {
            val decoded = recoveryAudio.removeFirst()
            releaseDecodedInput(decoded.samples.size * 2)
            queueResampled(decoded.samples, decoded.slice, decoded.rawPtsUs)
        }
        if ((startupCatchup || preservingRecovery) && regulator.bufferedFrames >= regulator.targetFrames) {
            stage = "STARTUP_OUTPUT_WAIT"
            Thread.sleep(2)
        } else {
            val retaining = recoveryCutoffUs != null && (firstPtsUs == null || recoveryAudio.isNotEmpty())
            val maxDecodedBytes = format.sampleRate * format.channels * 2 * 120 / 1000
            val retainedPacketCapacity = recoveryAudio.size + (if (pending != null) 1 else 0) < 256
            if (retaining && !retainedPacketCapacity) RemoteDiagnostics.recorder.audioTiming(diagnosticSession)?.let {
                it.pressure(clock.monotonicNs(), diagnosticSession, diagnosticGeneration,
                    DiagnosticAudioTiming.RESERVATION_PACKETS)
            }
            val capacity = !retaining || (retainedPacketCapacity &&
                reserveInputBytes(maxDecodedBytes))
            if (!capacity) {
                // Leave encoded dependencies in arrival order for admission pressure to
                // request a track-local reset, or for the source deadline to free space.
                if (!recoveryPressureReported && firstPtsUs == null && pending == null && recoveryAudio.isEmpty()) {
                    reservationPressure(maxDecodedBytes)?.let {
                        recoveryPressureReported = true
                        onRecoveryPressure(it)
                    }
                }
                Thread.sleep(2)
            } else {
                if (retaining) decodeReservation = maxDecodedBytes
                try {
                    if (format.codec == "aac") diagnosticCall(DiagnosticPoint.DECODE) { stepAac() }
                    else measured("input") {
                        next { recoveryQueueEmpty() }
                    }?.let { packet ->
                        measured("decode") {
                            packet.diagnosticAudioTiming?.record(clock.monotonicNs(), diagnosticGeneration,
                                packet.diagnosticPacketId,
                                if (opus == null) DiagnosticAudioTiming.DECODE_BEGIN else DiagnosticAudioTiming.OPUS_DECODE_BEGIN,
                                packet.ptsUs, packet.bytes.size.toLong())
                            eventHistory?.record(clock.monotonicNs(), "decode_begin", packet.ptsUs, packet.bytes.size.toLong())
                            stage = if (opus != null) "OPUS_DECODE" else "PCM_DECODE"
                            val samples = timedAudioWork(DiagnosticAudioTiming.DECODE, packet.diagnosticAudioTiming, packet.diagnosticPacketId) {
                                diagnosticCall(DiagnosticPoint.DECODE, packet.ptsUs) {
                                    opus?.decode(packet.bytes) ?: pcm16(packet.bytes)
                                }
                            }
                            validatePcmLength(samples.size * 2)
                            packet.diagnosticAudioTiming?.record(clock.monotonicNs(), diagnosticGeneration,
                                packet.diagnosticPacketId,
                                if (opus == null) DiagnosticAudioTiming.DECODE_END else DiagnosticAudioTiming.OPUS_DECODE_END,
                                packet.ptsUs, samples.size.toLong())
                            write(samples, packet.ptsUs, packet.diagnosticAudioTiming, packet.diagnosticPacketId)
                            eventHistory?.record(clock.monotonicNs(), "decode_end", packet.ptsUs, regulator.bufferedFrames.toLong())
                        }
                    }
                } finally {
                    if (decodeReservation > 0) releaseInputReservation(decodeReservation)
                    decodeReservation = 0
                }
            }
        }
        measured("refill") { pumpOutput() }
        timedAudioWork(DiagnosticAudioTiming.UNDERRUN) { observeUnderruns() }
    }

    private fun stepAac() {
        stage = "AAC_INPUT"
        if (waiting == null) waiting = measured("input") { next { recoveryQueueEmpty() } }
        measured("decode") { timedAudioWork(DiagnosticAudioTiming.DECODE) { pumpAac() } }
    }

    // Called with the admission lock held, so packets admitted after this boundary
    // cannot accidentally be included in the already-drained recovery backlog.
    private fun recoveryQueueEmpty() {
        if (!preservingRecovery || pending != null || recoveryAudio.isNotEmpty()) return
        if (format.codec == "aac") {
            if (aacRecoveryInputBoundaryUs == null)
                aacRecoveryInputBoundaryUs = latestAacInputPtsUs
        } else if (recoverySourceBoundary == null) {
            recoverySourceBoundary = resampledTimeline.acceptedFrames
        }
        finishRecoveryPreservation()
    }

    private fun finishRecoveryPreservation() {
        val boundary = recoverySourceBoundary ?: return
        // Submitted source units include neither silence nor newer FIFO samples that
        // have not yet reached AudioTrack. Filter-held samples remain unmapped.
        if (sourceWrittenUnits / 1_000_000 >= boundary)
            preservingRecovery = false
    }

    private fun pumpAac() {
        val decoder = checkNotNull(aac)
        val info = checkNotNull(info)
        waiting?.let { packet ->
            val index = decoder.dequeueInputBuffer(0)
            if (index >= 0) {
                packet.diagnosticAudioTiming?.record(clock.monotonicNs(), diagnosticGeneration,
                    packet.diagnosticPacketId, DiagnosticAudioTiming.AAC_INPUT, packet.ptsUs, packet.bytes.size.toLong())
                eventHistory?.record(clock.monotonicNs(), "aac_input", packet.ptsUs, packet.bytes.size.toLong())
                val input = checkNotNull(decoder.getInputBuffer(index))
                require(input.capacity() >= packet.bytes.size) { "AAC packet exceeds decoder capacity" }
                input.clear()
                input.put(packet.bytes)
                decoder.queueInputBuffer(index, 0, packet.bytes.size, packet.ptsUs, 0)
                latestAacInputPtsUs = maxOf(latestAacInputPtsUs ?: packet.ptsUs, packet.ptsUs)
                aacRecoveryInputBoundaryUs?.let { boundary ->
                    if (aacPastRecoveryBoundary(boundary, packet.ptsUs, format.sampleRate))
                        aacInputPastBoundary = true
                }
                waiting = null
            }
        }
        val index = decoder.dequeueOutputBuffer(info, 0)
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            stage = "AAC_FORMAT"
            val actual = decoder.outputFormat
            require(actual.getInteger(MediaFormat.KEY_SAMPLE_RATE) == format.sampleRate &&
                actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == format.channels &&
                (!actual.containsKey(MediaFormat.KEY_PCM_ENCODING) ||
                    actual.getInteger(MediaFormat.KEY_PCM_ENCODING) == PlatformAudioFormat.ENCODING_PCM_16BIT)
            ) { "AAC decoder changed requested PCM output format" }
        } else if (index >= 0) {
            stage = "AAC_OUTPUT"
            try {
                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    validatePcmLength(info.size)
                    val boundary = aacRecoveryInputBoundaryUs
                    // A later input AND later output are required: TRY_AGAIN_LATER
                    // says nothing about buffers held by MediaCodec. AAC-LC input
                    // AUs cover at most 1024 samples; the 120 ms minimum also
                    // covers a maximum-sized decoded output buffer.
                    if (preservingRecovery && recoverySourceBoundary == null && boundary != null &&
                        aacInputPastBoundary &&
                        aacPastRecoveryBoundary(boundary, info.presentationTimeUs, format.sampleRate)) {
                        recoverySourceBoundary = recoveryAudio.fold(resampledTimeline.acceptedFrames) { total, audio ->
                            Math.addExact(total, audio.slice.frames.toLong())
                        }
                        finishRecoveryPreservation()
                    }
                    val buffer = checkNotNull(decoder.getOutputBuffer(index))
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val bytes = ByteArray(info.size)
                    buffer.get(bytes)
                    val timing = RemoteDiagnostics.recorder.audioTiming(diagnosticSession)
                    timing?.record(clock.monotonicNs(), diagnosticGeneration, 0, DiagnosticAudioTiming.AAC_OUTPUT,
                        info.presentationTimeUs, info.size.toLong())
                    write(pcm16(bytes), info.presentationTimeUs, timing, 0)
                    eventHistory?.record(clock.monotonicNs(), "decode_end", info.presentationTimeUs,
                        regulator.bufferedFrames.toLong())
                }
            } finally { decoder.releaseOutputBuffer(index, false) }
        }
    }

    private fun write(samples: ShortArray, ptsUs: Long, timing: DiagnosticAudioTiming?, packetId: Long) {
        stage = "STARTUP_TRIM"
        if (ptsUs < 0) throw PlaybackException("Decoded audio PTS is negative within a generation")
        if (format.codec != "opus" && lastDecodedPtsUs?.let { ptsUs < it } == true)
            throw PlaybackException("Decoded audio PTS decreased within a generation")
        lastDecodedPtsUs = ptsUs
        clock.noteAudioPacket()
        require(samples.size % format.channels == 0) { "PCM packet is not frame aligned" }
        var slice = startup.apply(samples.size / format.channels, ptsUs)
        var trimReason = if (slice.audiblePtsUs != ptsUs) 0L else -1L
        var trimCutoff = if (trimReason == 0L) slice.audiblePtsUs else -1L
        recordTrim(0, TrimmedAudio(0, samples.size / format.channels, ptsUs), slice, -1, -1)
        recoveryCutoffUs?.let { cutoff ->
            val position = if (firstPtsUs == null) clock.currentUs() else null
            val before = slice
            val effectiveCutoff = if (firstPtsUs == null) maxOf(cutoff, position ?: cutoff) else cutoff
            slice = trimRecoveryAudio(slice, effectiveCutoff, format.sampleRate)
            if (slice != before) {
                trimReason = 1
                trimCutoff = effectiveCutoff
            }
            recordTrim(1, before, slice, cutoff, position ?: -1)
        }
        val intervalStartUnits = Math.addExact(Math.multiplyExact(ptsUs, format.sampleRate.toLong()),
            slice.skipFrames * 1_000_000L)
        val intervalEndUnits = Math.addExact(intervalStartUnits, slice.frames * 1_000_000L)
        if (contiguousSourceStartUs?.let { slice.audiblePtsUs < it } == true ||
            contiguousSourceEndUnits?.let { end ->
                abs(intervalStartUnits - end) >= format.sampleRate && intervalEndUnits > end
            } == true) {
            presentation.disableDiscardMapping()
            discardCoverage = null
            sourceClock?.let { clock.invalidateAudioDiscardCutoff(it) }
        }
        val coverage = discardCoverage.takeIf {
            recoveryCutoffUs == null || firstPtsUs != null && !preservingRecovery
        }
        coverage?.takeIf { intervalStartUnits >= it.startUnits }?.let {
            val cutoff = it.endUnits / format.sampleRate
            val before = slice
            val delta = (it.endUnits - intervalStartUnits).coerceAtLeast(0)
            val skip = minOf(slice.frames.toLong(), delta / 1_000_000L).toInt()
            slice = TrimmedAudio(slice.skipFrames + skip, slice.frames - skip,
                Math.addExact(slice.audiblePtsUs, skip * 1_000_000L / format.sampleRate))
            if (slice != before) {
                trimReason = 4
                trimCutoff = cutoff
                staleDiscardedSourceFrames = Math.addExact(staleDiscardedSourceFrames,
                    (before.frames - slice.frames).toLong())
                recordTrim(4, before, slice, cutoff, cutoff)
            }
        }
        timing?.record(clock.monotonicNs(), diagnosticGeneration, packetId, DiagnosticAudioTiming.TRIM,
            (samples.size / format.channels).toLong(), slice.frames.toLong())
        if (slice.frames == 0) {
            timing?.record(clock.monotonicNs(), diagnosticGeneration, packetId,
                DiagnosticAudioTiming.DROPPED, ptsUs, 0)
            return
        }
        if (firstPtsUs == null) {
            val decoded = DecodedAudio(samples, slice, ptsUs, trimCutoff, trimReason, timing, packetId)
            if (recoveryCutoffUs != null) {
                val wasPending = pending != null || recoveryAudio.isNotEmpty()
                retainRecoveryAudio(decoded)
                if (!wasPending) flushPending()
            } else {
                if (pending != null) {
                    timing?.record(clock.monotonicNs(), diagnosticGeneration, packetId,
                        DiagnosticAudioTiming.REPLACED, pending!!.rawPtsUs, ptsUs)
                    causalStartGate.reset()
                }
                pending = decoded
                flushPending()
            }
        } else if (recoveryAudio.isNotEmpty()) {
            retainRecoveryAudio(DecodedAudio(samples, slice, ptsUs, trimCutoff, trimReason, timing, packetId))
        } else queueResampled(samples, slice, ptsUs)
    }

    private fun retainRecoveryAudio(decoded: DecodedAudio) {
        val bytes = decoded.samples.size * 2
        check(bytes <= decodeReservation) { "Decoded recovery audio exceeded its reservation" }
        retainDecodedInput(bytes)
        decodeReservation -= bytes
        if (firstPtsUs == null && pending == null && recoveryAudio.isEmpty()) pending = decoded
        else recoveryAudio.addLast(decoded)
    }

    private fun clearPending() {
        pending?.let { if (recoveryCutoffUs != null) releaseDecodedInput(it.samples.size * 2) }
        pending = null
        causalStartGate.reset()
    }

    private fun flushPending() {
        if (pending == null && firstPtsUs == null && recoveryAudio.isNotEmpty())
            pending = recoveryAudio.removeFirst()
        val decoded = pending ?: return
        while (running.get()) {
            var gateCutoff = decoded.cutoffUs
            var gateTrimReason = decoded.trimReason
            val slice = timedAudioWork(DiagnosticAudioTiming.PREPARATION, decoded.timing, decoded.packetId) {
                stage = "STARTUP_SYNC"
                val fresh = recoveryCutoffUs?.let { cutoff ->
                    val position = clock.currentUs()
                    val effectiveCutoff = maxOf(cutoff, position ?: cutoff)
                    trimRecoveryAudio(decoded.slice, effectiveCutoff, format.sampleRate).also {
                        if (it != decoded.slice) {
                            gateCutoff = effectiveCutoff
                            gateTrimReason = 2
                        }
                        recordTrim(2, decoded.slice, it, cutoff, position ?: -1)
                    }
                } ?: decoded.slice
                if (fresh != decoded.slice) decoded.timing?.record(clock.monotonicNs(), diagnosticGeneration,
                    decoded.packetId, DiagnosticAudioTiming.TRIM, decoded.slice.frames.toLong(), fresh.frames.toLong())
                if (fresh.frames == 0) {
                    decoded.timing?.record(clock.monotonicNs(), diagnosticGeneration, decoded.packetId,
                        DiagnosticAudioTiming.DROPPED, decoded.rawPtsUs, 0)
                    clearPending()
                    return@timedAudioWork null
                }
                clock.prepareAudio(fresh)
            } ?: return
            val recovering = recoveryCutoffUs != null
            if (recovering && recoveryStartAtNs == null) recoveryStartAtNs = clock.monotonicNs()
            if (!timedAudioWork(DiagnosticAudioTiming.START_GATE, decoded.timing, decoded.packetId) {
                diagnosticCall(DiagnosticPoint.AUDIO_START_WAIT, slice.audiblePtsUs) { operation ->
                    clock.awaitAudioStartObserved(slice.audiblePtsUs, { decision ->
                        timedAudioWork(DiagnosticAudioTiming.START_DECISION, decoded.timing, decoded.packetId) {
                            val evidenceAt = clock.monotonicNs()
                            val capture = RemoteDiagnostics.recorder.captureIdentity
                            val omissions = synchronized(startEvidenceGate) {
                                startEvidenceGate.take(diagnosticGeneration, decision, evidenceAt, capture)
                            }
                            omissions?.let { omitted ->
                                val snapshot = retentionSnapshot()
                                val observed = decision.copy(retainedDecodedBytes = snapshot.decodedBytes,
                                    retainedDecodedPackets = snapshot.decodedPackets,
                                    encodedQueueBytes = snapshot.encodedBytes, encodedQueuePackets = snapshot.encodedPackets,
                                    queueSampledAtNs = clock.monotonicNs(), omittedRepeats = omitted)
                                RemoteDiagnostics.recorder.audioStartDecision(diagnosticSession, diagnosticGeneration,
                                    operation, observed)
                                TelemetryRegistry.state("receiver", diagnosticSession, 63,
                                    DiagnosticKind.AUDIO_OUTPUT.ordinal + 1, DiagnosticStage.SAMPLE.ordinal,
                                    2, diagnosticGeneration, decision.targetUs, listOf(
                                        DiagnosticPoint.AUDIO_START_WAIT.ordinal.toLong(), operation,
                                        decision.observationAgeUs ?: -1, snapshot.decodedBytes.toLong(),
                                        observed.retainedDecodedPackets.toLong(), snapshot.encodedBytes.toLong(),
                                        observed.encodedQueuePackets.toLong(), decision.sampledAtNs ?: -1,
                                        observed.queueSampledAtNs ?: -1, decision.observationSequence ?: -1,
                                        decision.observationAtNs ?: -1, decision.observationPtsUs ?: -1,
                                        decision.outputSource?.ordinal?.toLong() ?: -1,
                                        decision.nativeTimestampAtNs ?: -1, decision.nativeTimestampFrame ?: -1,
                                        decision.appliedOffsetUs ?: Long.MIN_VALUE, decision.correctionSequence ?: 0))
                            }
                            val timing = cachedOutputTiming
                            TelemetryRegistry.state("receiver", diagnosticSession, 60,
                                DiagnosticKind.AUDIO_OUTPUT.ordinal + 1, DiagnosticStage.SAMPLE.ordinal,
                                2, diagnosticGeneration, decision.targetUs, listOf(
                                    DiagnosticPoint.AUDIO_START_WAIT.ordinal.toLong(), operation,
                                    decision.reason.toLong(), decision.mode.toLong(), decision.positionUs ?: -1,
                                    decision.delayUs ?: Long.MIN_VALUE, decision.firstVideoUs ?: -1,
                                    decision.releasedVideoUs ?: -1, decision.waitedUs, decision.revision, decision.flags,
                                    cachedOutputAtNs, timing?.submittedFrames ?: -1, timing?.consumedFrames ?: -1,
                                    timing?.presentedFrames ?: -1, timing?.timestampAgeUs ?: -1,
                                    timing?.clockSource?.ordinal?.toLong() ?: -1, cachedUnderruns,
                                    lastOutputProgressNs.takeIf { it > 0 } ?: -1, queuedPackets().toLong()))
                            if (TelemetryRegistry.has("receiver", diagnosticSession) &&
                                causalStartGate.take(diagnosticSession, diagnosticGeneration, capture, decision)) {
                                TelemetryRegistry.causal(diagnosticSession, CausalEvidence.Stage.START, diagnosticGeneration,
                                    operation, decision.reason.toLong(), decision.mode.toLong(), decision.targetUs,
                                    decision.positionUs ?: -1, decision.delayUs ?: Long.MIN_VALUE, decision.revision,
                                    decision.flags, decision.waitedUs, decision.releasedVideoUs ?: -1,
                                    decoded.rawPtsUs, gateCutoff, gateTrimReason,
                                    atNs = clock.monotonicNs())
                            }
                        }
                    }, waitForFuture = !recovering, startedAtNs = recoveryStartAtNs) { running.get() }
                }
            }) {
                if (recovering && clock.audioStartDelayUs(slice.audiblePtsUs) > 0) return
                continue
            }
            timedAudioWork(DiagnosticAudioTiming.STARTUP_SUBMISSION, decoded.timing, decoded.packetId) {
                clearPending()
                writePcm(decoded.samples, slice, decoded.rawPtsUs)
            }
            return
        }
    }

    private fun writePcm(samples: ShortArray, slice: TrimmedAudio, rawPtsUs: Long) {
        if (slice.frames == 0) return
        val audiblePtsUs = slice.audiblePtsUs
        val starting = firstPtsUs == null
        if (starting) {
            stage = "OUTPUT_START"
            firstPtsUs = audiblePtsUs
            sourceClock = timedAudioWork(DiagnosticAudioTiming.SOURCE_ANCHOR) {
                clock.anchorObservedAudio(audiblePtsUs, format.sampleRate)
            }
            preservingRecovery = recoveryCutoffUs != null
        }
        stage = "SOURCE_MAP"
        queueResampled(samples, slice, rawPtsUs)
        if (starting) {
            stage = "OUTPUT_START"
            regulator.startAfterPreroll()
            timedAudioWork(DiagnosticAudioTiming.PLAYBACK_START) { output.play() }
            lastOutputProgressNs = clock.monotonicNs()
            val count = observedUnderrunCount()
            cachedUnderruns = count.toLong()
            underruns.start(count)
            serviceDiagnostic.start(lastOutputProgressNs, count)
            eventHistory?.start(count)
        }
    }

    private fun pumpOutput(allowSilence: Boolean = true): Unit = timedAudioWork(DiagnosticAudioTiming.REFILL) {
        if (firstPtsUs == null || !running.get()) return@timedAudioWork
        stage = "OUTPUT_CLOCK"
        val timing = readOutputTiming()
        if (timing.submittedFrames != deviceFrames)
            throw AudioClockException(AudioClockDiagnostic(AudioClockFault.SUBMISSION, timing))
        if (timing.submittedFrames - timing.consumedFrames !in 0..timing.bufferFrames.toLong())
            throw AudioClockException(AudioClockDiagnostic(AudioClockFault.CLIENT_BUFFER, timing))
        val now = clock.monotonicNs()
        timedAudioWork(DiagnosticAudioTiming.CALLBACK) { observePresentation(timing, now) }
        eventHistory?.refill(now, timing.bufferedUs, regulator.bufferedFrames)
        serviceDiagnostic.beforeRefill(now,
            (timing.submittedFrames - timing.consumedFrames) * 1_000_000 / format.sampleRate,
            regulator.bufferedFrames, lastAudioAdmissionNs?.let { (now - it).coerceAtLeast(0) / 1000 })
        if (output !is VirtualDiscardAudioOutput && statisticsEnabled &&
            (statisticsAtNs == Long.MIN_VALUE || now - statisticsAtNs >= 100_000_000)) {
            statisticsAtNs = now
            val diagnostic = regulator.diagnostic()
            val rate = format.sampleRate.toDouble()
            val source = if (timing.presentedFrames > 0) clock.cachedPlayedAudioObservation() else null
            val statistics = AudioStatistics(diagnostic.bufferedFrames * 1000.0 / rate,
                diagnostic.targetFrames * 1000.0 / rate, timing.bufferedUs / 1000.0,
                diagnostic.silenceFrames * 1000.0 / rate, diagnostic.droppedFrames * 1000.0 / rate,
                source?.ptsUs, timing.presentedUs / 1000.0, source?.observedAtNs)
            audioObservation = now to statistics
        }
        if (timing.consumedFrames > lastConsumedFrames) lastOutputProgressNs = now
        lastConsumedFrames = timing.consumedFrames
        if (audioTimingProgressNs == Long.MIN_VALUE || now - audioTimingProgressNs >= 100_000_000L) {
            RemoteDiagnostics.recorder.audioTiming(diagnosticSession)?.let {
                it.record(now, diagnosticGeneration, 0, DiagnosticAudioTiming.OUTPUT_PROGRESS,
                    timing.submittedFrames, timing.consumedFrames)
                it.record(now, diagnosticGeneration, 0, DiagnosticAudioTiming.OUTPUT_PRESENTED,
                    timing.presentedFrames, timing.bufferedUs)
                if (it.detailEnabled) it.record(now, diagnosticGeneration, 0,
                    DiagnosticAudioTiming.OUTPUT_DETAIL, timing.bufferFrames.toLong(), cachedUnderruns)
                if (it.detailEnabled) it.record(now, diagnosticGeneration, 0,
                    DiagnosticAudioTiming.OUTPUT_NATIVE_CAPACITY,
                    (output as? PlatformAudioOutput)?.capacityFrames?.toLong() ?: -1, 0)
                audioTimingProgressNs = now
            }
        }
        var available = (timing.bufferFrames - (timing.submittedFrames - timing.consumedFrames)).toInt()
        val pendingOutput = regulator.peek(outputBlockFrames) != null ||
            allowSilence && timing.submittedFrames - timing.consumedFrames < outputBlockFrames
        // Service the writable native capacity, not just one chunk of a larger mixer quantum.
        while (available > 0 && running.get()) {
            val chunk = regulator.peek(outputBlockFrames)
            val needsSilence = allowSilence && chunk == null && timing.bufferFrames - available < outputBlockFrames
            if (chunk == null && !needsSilence) break
            val frames = minOf(available, chunk?.frames ?: outputBlockFrames)
            stage = "OUTPUT_WRITE"
            val timingTrace = RemoteDiagnostics.recorder.audioTiming(diagnosticSession)
            timingTrace?.record(clock.monotonicNs(), diagnosticGeneration, 0,
                if (chunk == null) DiagnosticAudioTiming.OUTPUT_SILENCE_REQUEST else DiagnosticAudioTiming.OUTPUT_REQUEST,
                frames.toLong() * format.channels, regulator.bufferedFrames.toLong())
            val written = presentation.write(chunk != null, format.channels, frames,
                sourceUnits = { regulator.sourceUnits(it) },
                originalSourceStartUnits = if (chunk != null) regulator.originalSourceStartUnits() else null) {
                timedAudioWork(DiagnosticAudioTiming.WRITE, timingTrace) {
                    diagnosticCall(DiagnosticPoint.WRITE, chunk?.ptsUs ?: -1, frames.toLong(),
                        requestedSamples = frames.toLong() * format.channels) {
                        output.write(chunk?.samples ?: silence, chunk?.offset ?: 0, frames * format.channels)
                    }
                }
            }
            timingTrace?.record(clock.monotonicNs(), diagnosticGeneration, 0,
                if (chunk == null) DiagnosticAudioTiming.OUTPUT_SILENCE_RESULT else DiagnosticAudioTiming.OUTPUT_RESULT,
                frames.toLong() * format.channels, written.toLong())
            if (timingTrace?.detailEnabled == true) timingTrace.record(clock.monotonicNs(),
                diagnosticGeneration, 0, DiagnosticAudioTiming.OUTPUT_FRAMES,
                frames.toLong(), (written / format.channels).toLong())
            if (written == 0) {
                serviceDiagnostic.wrote(0, chunk != null)
                break
            }
            val accepted = written / format.channels
            serviceDiagnostic.wrote(accepted, chunk != null)
            if (chunk != null) {
                sourcePacket(chunk.ptsUs)
                sourceWrittenUnits = Math.addExact(sourceWrittenUnits, regulator.sourceUnits(accepted))
                regulator.consumed(accepted)
                finishRecoveryPreservation()
                totalFrames += accepted
                clock.audioWritten(sourceWrittenUnits / format.sampleRate)
            } else regulator.insertedSilence(accepted)
            if (chunk == null) eventHistory?.record(clock.monotonicNs(), "silence", accepted.toLong(),
                regulator.bufferedFrames.toLong())
            deviceFrames += accepted
            available -= accepted
            lastOutputProgressNs = clock.monotonicNs()
            if (chunk != null && !submittedReported) {
                submittedReported = true
                if (output is VirtualDiscardAudioOutput)
                    android.util.Log.w("ScreenCopyReceiver", "event=VIRTUAL_AUDIO_FIRST_DISCARD physical_playback=false pts_us=${chunk.ptsUs}")
                else timedAudioWork(DiagnosticAudioTiming.CALLBACK) { onPositiveSourceSubmitted() }
                timedAudioWork(DiagnosticAudioTiming.CALLBACK) { onFirstSubmitted(chunk.ptsUs) }
            }
            // A source gap keeps the existing small silence cushion, never fills the route.
            if (chunk == null) break
        }
        // Admission locking must not precede writable output servicing.
        if (startupCatchup &&
            presentation.presentedUs(timing.presentedFrames) >= regulator.targetFrames * 1_000_000L / format.sampleRate &&
            queuedPackets() == 0)
            startupCatchup = false
        val finished = clock.monotonicNs()
        if (finished - lastOutputProgressNs > 250_000_000L && pendingOutput) {
            stage = "OUTPUT_WRITE"
            throw PlaybackException("Audio output stalled for 250 ms without observed write progress;" +
                " event=WRITE_STALL recent_progress_ms=${(finished - lastOutputProgressNs) / 1_000_000}" +
                " queued_decoded_frames=${regulator.bufferedFrames} source_frames=$totalFrames device_frames=$deviceFrames" +
                " playing_state=${output.playbackActive} ${timing.logMessage()}", "AUDIO_FAILED")
        }
    }

    private fun queueResampled(samples: ShortArray, slice: TrimmedAudio, rawPtsUs: Long): Unit = timedAudioWork(DiagnosticAudioTiming.RESAMPLING) {
        stage = "PCM_RESAMPLE"
        if (contiguousSourceStartUs == null) contiguousSourceStartUs = slice.audiblePtsUs
        // A point in a gapped/overlapping FIFO is not proof that every earlier PTS was played.
        val startUnits = Math.addExact(Math.multiplyExact(rawPtsUs, format.sampleRate.toLong()),
            slice.skipFrames * 1_000_000L)
        if (contiguousSourceEndUnits?.let { abs(it - startUnits) >= format.sampleRate } == true) {
            presentation.disableDiscardMapping()
            discardCoverage = null
            sourceClock?.let { clock.invalidateAudioDiscardCutoff(it) }
        }
        contiguousSourceEndUnits = Math.addExact(startUnits, slice.frames * 1_000_000L)
        resampledTimeline.offer(slice.frames, slice.audiblePtsUs, startUnits)
        val converted = diagnosticCall(DiagnosticPoint.RESAMPLE, slice.audiblePtsUs, slice.frames.toLong()) {
            resampler.convert(samples, slice.skipFrames, slice.frames)
        }
        if (converted.size % format.channels != 0)
            throw PlaybackException("Resampler returned unaligned PCM")
        val frames = converted.size / format.channels
        if (frames > format.sampleRate * 120 / 1000 + 256)
            throw PlaybackException("Resampler output exceeded its converted-frame bound", "CONGESTION")
        val timing = RemoteDiagnostics.recorder.audioTiming(diagnosticSession)
        val beforeDiscarded = regulator.discardedFrames
        for (span in resampledTimeline.output(frames, resampler.delayFrames)) {
            regulator.offer(converted, TrimmedAudio(span.offsetFrames, span.frames, span.ptsUs), span.sourceUnits,
                observe = false, preserve = preservingRecovery, originalStartUnits = span.originalStartUnits)
        }
        if (regulator.discardedFrames > beforeDiscarded) {
            presentation.disableDiscardMapping()
            discardCoverage = null
            sourceClock?.let { clock.invalidateAudioDiscardCutoff(it) }
        }
        if (timing != null && regulator.discardedFrames > beforeDiscarded)
            timing.record(clock.monotonicNs(), diagnosticGeneration, 0, DiagnosticAudioTiming.REGULATOR_DROP,
                regulator.discardedFrames - beforeDiscarded, regulator.bufferedFrames.toLong())
        regulator.converted(slice.frames, frames)?.let { compensation ->
            stage = "PCM_COMPENSATION"
            timedAudioWork(DiagnosticAudioTiming.COMPENSATION) {
                diagnosticCall(DiagnosticPoint.COMPENSATE, count = compensation.distance.toLong()) {
                    resampler.compensate(compensation.samples, compensation.distance)
                }
            }
            val observed = readOutputTiming()
            timedAudioWork(DiagnosticAudioTiming.CALLBACK) {
                if (output !is VirtualDiscardAudioOutput) onOutputTiming(observed)
                if (output !is VirtualDiscardAudioOutput) onRegulation(regulator.diagnostic())
            }
        }
        processedSourceFrames = Math.addExact(processedSourceFrames, slice.frames.toLong())
    }

    private fun sourcePacket(audiblePtsUs: Long) {
        stage = "SOURCE_MAP"
        val correction = clock.audioPacket(sourceWrittenUnits / format.sampleRate, audiblePtsUs)
        if (correction != null) pendingClockCorrection = correction
        pendingClockCorrection?.let {
            val now = clock.monotonicNs()
            if (abs(Math.subtractExact(it.afterOffsetUs, lastLoggedOffsetUs)) >= 1000 &&
                lastClockEventNs?.let { previous -> now - previous >= 1_000_000_000L } != false) {
                if (output !is VirtualDiscardAudioOutput)
                    timedAudioWork(DiagnosticAudioTiming.CALLBACK) { onClockCorrection(it) }
                lastClockEventNs = now
                lastLoggedOffsetUs = it.afterOffsetUs
                pendingClockCorrection = null
            }
        }
    }

    private fun observeUnderruns() {
        if (output is VirtualDiscardAudioOutput) return
        if (firstPtsUs == null || !running.get()) return
        val now = clock.monotonicNs()
        if (lastUnderrunObservationNs?.let { now - it < 20_000_000L } == true) return
        stage = "UNDERRUN_CHECK"
        lastUnderrunObservationNs = now
        val count = observedUnderrunCount()
        val timing = readOutputTiming()
        val queued = queuedPackets()
        TelemetryRegistry.evidence("receiver", diagnosticSession, 2, diagnosticGeneration,
            IncidentEvidence.Stage.AUDIO_STATE, regulator.bufferedFrames.toLong(), count.toLong(),
            timing.submittedFrames, timing.consumedFrames, timing.presentedFrames, timing.timestampAgeUs ?: -1,
            timing.clockSource.ordinal.toLong(), queued.toLong(),
            if (pending != null) 1 else 0, if (waiting != null) 1 else 0, lastOutputProgressNs)
        eventHistory?.record(now, "observe", count.toLong(), queued.toLong())
        eventHistory?.dump(now, count)?.forEach { onServiceDiagnostic?.invoke(it) }
        serviceDiagnostic.observe(count, timing.submittedFrames - timing.consumedFrames,
            regulator.bufferedFrames, queued, pending != null || waiting != null)
        cachedUnderruns = count.toLong()
        TelemetryRegistry.causal(diagnosticSession, CausalEvidence.Stage.SERVICE, diagnosticGeneration,
            *serviceDiagnostic.snapshot(), atNs = clock.monotonicNs())
        underruns.observe(now, count, timing, queued,
            pending != null || waiting != null, output.playbackActive)?.let {
            timedAudioWork(DiagnosticAudioTiming.CALLBACK) { onUnderrunRecovery(it) }
            if (RemoteDiagnostics.recorder.enabled("receiver", diagnosticSession, DiagnosticKind.AUDIO_OUTPUT))
                RemoteDiagnostics.recorder.observe("receiver", diagnosticSession, DiagnosticKind.AUDIO_OUTPUT,
                    DiagnosticStage.SAMPLE, 2, diagnosticGeneration, point = DiagnosticPoint.TIMING,
                    metadata = DiagnosticOutput(it.timing.submittedFrames, it.timing.consumedFrames,
                        it.timing.presentedFrames, it.timing.timestampAgeUs, it.timing.clockSource, it.currentCount))
        }
    }

    override fun unblock() { output.pause() }

    override fun describeFailure(error: Exception): String =
        "Audio ${if (output is VirtualDiscardAudioOutput) "mode=VIRTUAL_DISCARD physical_playback=false " else ""}" +
            "stage=$stage exception=${error.javaClass.simpleName}: ${error.message ?: "operation failed"}"

    override fun release() {
        try {
            sourceClock?.let { source ->
                try {
                    observePresentation(diagnosticCall(DiagnosticPoint.TIMING) { output.timing() }, clock.monotonicNs())
                } finally {
                    clock.detachObservedAudio(source)
                }
            }
        } finally {
            try {
                aac?.let { try { it.stop() } finally { it.release() } }
            } finally {
                try { opus?.close() } finally {
                    pending = null
                    recoveryAudio.clear()
                    clearInputRetention()
                    reportService(final = true)
                    regulator.clear()
                    presentation.clear()
                    try { resampler.close() } finally { output.close() }
                }
            }
        }
    }

    private fun observePresentation(timing: AudioOutputTiming, observedAtNs: Long) {
        val coverage = presentation.discardCoverage(timing)
        discardCoverage = coverage?.first
        val discardCutoffUs = coverage?.takeIf { it.second }?.first?.endUnits?.div(format.sampleRate)
        val sampleUs = presentation.presentedUs(timing.presentedFrames)
        presentedSourceUs = sampleUs
        sourceClock?.let {
            clock.observeAudio(it, sampleUs, observedAtNs, timing,
                retainEvidence = RemoteDiagnostics.recorder.collecting,
                presentedSourceAtFrame =
                    if (output.playbackActive == true && timing.clockSource == AudioClockSource.TIMESTAMP)
                        presentation.projection() else null,
                presentedSourceCutoffUs = discardCutoffUs,
                playedCoverage = coverage?.first?.let {
                    PlayedAudioCoverage(it.startUnits, it.endUnits, format.sampleRate)
                })?.let { observation ->
                RemoteDiagnostics.recorder.audioClockObservation(diagnosticSession, diagnosticGeneration, observation)
            }
        }
    }

    private fun pcm16(bytes: ByteArray): ShortArray {
        validatePcmLength(bytes.size)
        require(bytes.size % (format.channels * 2) == 0) { "PCM s16le data is not frame aligned" }
        return ShortArray(bytes.size / 2).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) }
    }

    private fun validatePcmLength(bytes: Int) {
        if (bytes > format.sampleRate * format.channels * 2 * 120 / 1000)
            throw PlaybackException("Decoded audio packet exceeds 120 ms budget; sender must use smaller packets", "CONGESTION")
    }

    private data class DecodedAudio(val samples: ShortArray, val slice: TrimmedAudio,
                                   val rawPtsUs: Long, val cutoffUs: Long, val trimReason: Long,
                                   val timing: DiagnosticAudioTiming?, val packetId: Long)

    companion object {
        internal fun trimRecoveryAudio(slice: TrimmedAudio, cutoff: Long, rate: Int): TrimmedAudio {
            val delta = (cutoff - slice.audiblePtsUs).coerceAtLeast(0)
            val duration = (slice.frames * 1_000_000L + rate - 1) / rate
            val skip = if (delta >= duration) slice.frames
                else ((delta * rate + 999_999) / 1_000_000).toInt()
            return TrimmedAudio(slice.skipFrames + skip, slice.frames - skip,
                Math.addExact(slice.audiblePtsUs, skip * 1_000_000L / rate))
        }

        private fun createAac(format: AudioFormat): MediaCodec {
            require(format.configuration.isNotEmpty()) { "AAC AudioSpecificConfig is required" }
            val mediaFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", format.sampleRate, format.channels).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(format.configuration))
                setInteger(MediaFormat.KEY_PCM_ENCODING, PlatformAudioFormat.ENCODING_PCM_16BIT)
            }
            val name = findPreferredDecoder(mediaFormat)
                ?: throw PlaybackException("No compatible AAC decoder")
            return MediaCodec.createByCodecName(name).also {
                try {
                    it.configure(mediaFormat, null, null, 0)
                    it.start()
                } catch (e: Exception) {
                    it.release()
                    throw e
                }
            }
        }
    }
}

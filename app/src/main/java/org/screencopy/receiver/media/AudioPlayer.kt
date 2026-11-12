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

class AudioPlayer(
    private val format: AudioFormat,
    private val clock: PlaybackClock,
    failure: (String, String) -> Unit,
    outputFactory: () -> AudioOutput = { PlatformAudioOutput(format, failure) },
    private val onClockCorrection: (AudioClockCorrection) -> Unit = {},
    private val onOutputTiming: (AudioOutputTiming) -> Unit = {},
    private val onUnderrunRecovery: (AudioUnderrunDiagnostic) -> Unit = {},
    opusFactory: (AudioFormat) -> OpusPacketDecoder = { OpusDecoder(it.channels, it.outputGainQ8) },
    private val onRegulation: (AudioRegulationDiagnostic) -> Unit = {},
    resamplerFactory: (Int, Int) -> PcmResampler = ::FfmpegPcmResampler,
    private val onServiceDiagnostic: ((String) -> Unit)? = null,
    private val threadCpuNs: () -> Long = android.os.Debug::threadCpuTimeNanos,
    prepareThread: () -> Unit = { MediaThreadDiagnostic.report(MediaThreadRole.AUDIO) }
) : TrackWorker("ScreenCopy-audio", 512 * 1024, { code, detail -> failure(if (code == "DECODER_FAILED") "AUDIO_FAILED" else code, detail) },
    track = 2, prepareThread = prepareThread) {
    private val output: AudioOutput
    private val opus: OpusPacketDecoder?
    private val aac: MediaCodec?
    private val resampler: PcmResampler
    private val resampledTimeline = ResampledAudioTimeline(format.sampleRate)
    private val info = if (format.codec == "aac") MediaCodec.BufferInfo() else null
    private var waiting: AccessUnit? = null
    private var pending: DecodedAudio? = null
    private val startup = StartupTrim(format.preSkip, format.sampleRate)
    @Volatile private var totalFrames = 0L
    @Volatile private var sourceWrittenUnits = 0L
    internal val sourceSubmittedFrames: Long get() = sourceWrittenUnits / 1_000_000
    internal val retainedSourceFrames: Int get() = resampledTimeline.retainedFrames
    @Volatile internal var processedSourceFrames = 0L
        private set
    @Volatile private var presentedSourceUs = 0L
    private var sourceClock: AudioSourceClock? = null
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
    private var lastClockEventNs: Long? = null
    private var lastLoggedOffsetUs = 0L
    private var pendingClockCorrection: AudioClockCorrection? = null
    private var lastDecodedPtsUs: Long? = null
    @Volatile internal var recoveryCutoffUs: Long? = null
    internal var onFirstSubmitted: (Long) -> Unit = {}
    private var submittedReported = false
    @Volatile private var audioObservation: Pair<Long, AudioStatistics>? = null
    internal val audioStatistics: AudioStatistics? get() = audioObservation?.second
    internal fun freshAudioStatistics(nowMs: Long): AudioStatistics? {
        val observed = audioObservation ?: return null
        return observed.second.takeIf { nowMs - observed.first / 1_000_000 in 0..1000 }
    }
    private var statisticsAtNs = Long.MIN_VALUE
    private var startupCatchup = true
    private inline fun <T> diagnosticCall(point: DiagnosticPoint, pts: Long = -1, count: Long = -1, call: () -> T): T {
        val diagnostics = RemoteDiagnostics.recorder
        val session = diagnosticSession
        if (!diagnostics.enabled("receiver", session, DiagnosticKind.AUDIO_OUTPUT)) return call()
        val operation = diagnostics.operation("receiver", session, DiagnosticKind.AUDIO_OUTPUT)
        diagnostics.observe("receiver", session, DiagnosticKind.AUDIO_OUTPUT, DiagnosticStage.BEGIN,
            2, diagnosticGeneration, operation, pts, count = count, point = point)
        try {
            val result = call()
            diagnostics.observe("receiver", session, DiagnosticKind.AUDIO_OUTPUT, DiagnosticStage.COMPLETE,
                2, diagnosticGeneration, operation, pts, count = if (result is Int) result.toLong() else count, point = point,
                metadata = if (result is AudioOutputTiming) DiagnosticOutput(result.submittedFrames, result.consumedFrames,
                    result.presentedFrames, result.timestampAgeUs, result.clockSource) else null)
            return result
        } catch (e: Exception) {
            diagnostics.observe("receiver", session, DiagnosticKind.AUDIO_OUTPUT, DiagnosticStage.FAILED,
                2, diagnosticGeneration, operation, pts, reason = DiagnosticReason.FAILURE, point = point)
            throw e
        }
    }

    private val serviceDiagnostic = onServiceDiagnostic?.let { AudioServiceDiagnostic(format.sampleRate) }
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
        } + "; FFmpeg PCM compensation, fixed AudioTrack rate"

    init {
        require(format.channels in 1..2) { "Only mono/stereo audio is supported" }
        require(format.sampleRate in listOf(8000, 12000, 16000, 24000, 32000, 44100, 48000, 96000)) { "Unsupported audio sample rate" }
        require(format.preSkip in 0..65535) { "Invalid audio pre-skip" }
        require(format.codec in listOf("opus", "aac", "pcm")) { "Unsupported audio codec ${format.codec}" }
        require(format.codec != "opus" || format.sampleRate == 48000) { "Opus output clock must be 48000 Hz" }
        output = outputFactory()
        var createdResampler: PcmResampler? = null
        var createdOpus: OpusPacketDecoder? = null
        try {
            createdResampler = resamplerFactory(format.sampleRate, format.channels)
            resampler = createdResampler
            createdOpus = if (format.codec == "opus") opusFactory(format) else null
            opus = createdOpus
            aac = if (format.codec == "aac") createAac(format) else null
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

    private fun reportService(final: Boolean = false) {
        serviceDiagnostic?.report(clock.monotonicNs(), regulator.discardedFrames, final)?.let {
            onServiceDiagnostic?.invoke(it)
        }
    }

    private inline fun <T> measured(operation: String, block: () -> T): T {
        val diagnostic = serviceDiagnostic
        if (diagnostic == null || !diagnostic.started) return block()
        val start = clock.monotonicNs()
        val cpu = threadCpuNs()
        try {
            return block()
        } finally {
            diagnostic.duration(operation, clock.monotonicNs() - start, threadCpuNs() - cpu)
        }
    }

    private fun serviceStep() {
        stage = "STARTUP_CHECK"
        clock.checkStartup()
        if (pending != null && clock.canPrepareAudio()) {
            flushPending()
        }
        measured("refill") { pumpOutput(allowSilence = false) }
        if (startupCatchup && regulator.bufferedFrames >= regulator.targetFrames) {
            stage = "STARTUP_OUTPUT_WAIT"
            Thread.sleep(2)
        } else if (format.codec == "aac") diagnosticCall(DiagnosticPoint.DECODE) { stepAac() }
        else measured("input") { next() }?.let { packet ->
            measured("decode") {
                eventHistory?.record(clock.monotonicNs(), "decode_begin", packet.ptsUs, packet.bytes.size.toLong())
                stage = if (opus != null) "OPUS_DECODE" else "PCM_DECODE"
                val samples = diagnosticCall(DiagnosticPoint.DECODE, packet.ptsUs) {
                    opus?.decode(packet.bytes) ?: pcm16(packet.bytes)
                }
                write(samples, packet.ptsUs)
                eventHistory?.record(clock.monotonicNs(), "decode_end", packet.ptsUs, regulator.bufferedFrames.toLong())
            }
        }
        measured("refill") { pumpOutput() }
        observeUnderruns()
    }

    private fun stepAac() {
        stage = "AAC_INPUT"
        if (waiting == null) waiting = measured("input") { next() }
        measured("decode") { pumpAac() }
    }

    private fun pumpAac() {
        val decoder = checkNotNull(aac)
        val info = checkNotNull(info)
        waiting?.let { packet ->
            val index = decoder.dequeueInputBuffer(0)
            if (index >= 0) {
                eventHistory?.record(clock.monotonicNs(), "aac_input", packet.ptsUs, packet.bytes.size.toLong())
                val input = checkNotNull(decoder.getInputBuffer(index))
                require(input.capacity() >= packet.bytes.size) { "AAC packet exceeds decoder capacity" }
                input.clear()
                input.put(packet.bytes)
                decoder.queueInputBuffer(index, 0, packet.bytes.size, packet.ptsUs, 0)
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
                    val buffer = checkNotNull(decoder.getOutputBuffer(index))
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val bytes = ByteArray(info.size)
                    buffer.get(bytes)
                    write(pcm16(bytes), info.presentationTimeUs)
                    eventHistory?.record(clock.monotonicNs(), "decode_end", info.presentationTimeUs,
                        regulator.bufferedFrames.toLong())
                }
            } finally { decoder.releaseOutputBuffer(index, false) }
        }
    }

    private fun write(samples: ShortArray, ptsUs: Long) {
        stage = "STARTUP_TRIM"
        if (ptsUs < 0) throw PlaybackException("Decoded audio PTS is negative within a generation")
        if (format.codec != "opus" && lastDecodedPtsUs?.let { ptsUs < it } == true)
            throw PlaybackException("Decoded audio PTS decreased within a generation")
        lastDecodedPtsUs = ptsUs
        clock.noteAudioPacket()
        require(samples.size % format.channels == 0) { "PCM packet is not frame aligned" }
        var slice = startup.apply(samples.size / format.channels, ptsUs)
        recoveryCutoffUs?.let { cutoff ->
            slice = trimRecoveryAudio(slice, if (firstPtsUs == null) maxOf(cutoff, clock.currentUs() ?: cutoff) else cutoff,
                format.sampleRate)
        }
        if (slice.frames == 0) return
        if (firstPtsUs == null) {
            // Until the first video packet, retain only the latest decoded preroll packet.
            pending = DecodedAudio(samples, slice)
            flushPending()
        } else queueResampled(samples, slice)
    }

    private fun flushPending() {
        stage = "STARTUP_SYNC"
        val decoded = pending ?: return
        while (running.get()) {
            val fresh = recoveryCutoffUs?.let { cutoff ->
                trimRecoveryAudio(decoded.slice, maxOf(cutoff, clock.currentUs() ?: cutoff), format.sampleRate)
            } ?: decoded.slice
            if (fresh.frames == 0) { pending = null; return }
            val slice = clock.prepareAudio(fresh, format.sampleRate) ?: return
            if (slice.frames == 0) { pending = null; return }
            if (!diagnosticCall(DiagnosticPoint.AUDIO_START_WAIT, slice.audiblePtsUs) {
                    clock.awaitAudioStart(slice.audiblePtsUs) { running.get() }
                }) continue
            pending = null
            writePcm(decoded.samples, slice)
            return
        }
    }

    private fun writePcm(samples: ShortArray, slice: TrimmedAudio) {
        if (slice.frames == 0) return
        val audiblePtsUs = slice.audiblePtsUs
        val starting = firstPtsUs == null
        if (starting) {
            stage = "OUTPUT_START"
            firstPtsUs = audiblePtsUs
            sourceClock = clock.anchorObservedAudio(audiblePtsUs, format.sampleRate)
        }
        stage = "SOURCE_MAP"
        queueResampled(samples, slice)
        if (starting) {
            stage = "OUTPUT_START"
            regulator.startAfterPreroll()
            output.play()
            lastOutputProgressNs = clock.monotonicNs()
            val count = output.underrunCount
            underruns.start(count)
            serviceDiagnostic?.start(lastOutputProgressNs, count)
            eventHistory?.start(count)
        }
    }

    private fun pumpOutput(allowSilence: Boolean = true) {
        if (firstPtsUs == null || !running.get()) return
        stage = "OUTPUT_CLOCK"
        val timing = diagnosticCall(DiagnosticPoint.TIMING) { output.timing() }
        if (timing.submittedFrames != deviceFrames)
            throw AudioClockException(AudioClockDiagnostic(AudioClockFault.SUBMISSION, timing))
        if (timing.submittedFrames - timing.consumedFrames !in 0..timing.bufferFrames.toLong())
            throw AudioClockException(AudioClockDiagnostic(AudioClockFault.CLIENT_BUFFER, timing))
        val now = clock.monotonicNs()
        observePresentation(timing, now)
        eventHistory?.refill(now, timing.bufferedUs, regulator.bufferedFrames)
        serviceDiagnostic?.beforeRefill(now,
            (timing.submittedFrames - timing.consumedFrames) * 1_000_000 / format.sampleRate,
            regulator.bufferedFrames, lastAudioAdmissionNs?.let { (now - it).coerceAtLeast(0) / 1000 })
        if (statisticsEnabled && (statisticsAtNs == Long.MIN_VALUE || now - statisticsAtNs >= 100_000_000)) {
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
            val written = presentation.write(chunk != null, format.channels, frames,
                sourceUnits = { regulator.sourceUnits(it) }) {
                diagnosticCall(DiagnosticPoint.WRITE, chunk?.ptsUs ?: -1, frames.toLong()) {
                    output.write(chunk?.samples ?: silence, chunk?.offset ?: 0, frames * format.channels)
                }
            }
            if (written == 0) {
                serviceDiagnostic?.wrote(0, chunk != null)
                break
            }
            val accepted = written / format.channels
            serviceDiagnostic?.wrote(accepted, chunk != null)
            if (chunk != null) {
                sourcePacket(chunk.ptsUs)
                sourceWrittenUnits = Math.addExact(sourceWrittenUnits, regulator.sourceUnits(accepted))
                regulator.consumed(accepted)
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
                onFirstSubmitted(chunk.ptsUs)
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

    private fun queueResampled(samples: ShortArray, slice: TrimmedAudio) {
        stage = "PCM_RESAMPLE"
        resampledTimeline.offer(slice.frames, slice.audiblePtsUs)
        val converted = diagnosticCall(DiagnosticPoint.RESAMPLE, slice.audiblePtsUs, slice.frames.toLong()) {
            resampler.convert(samples, slice.skipFrames, slice.frames)
        }
        if (converted.size % format.channels != 0)
            throw PlaybackException("Resampler returned unaligned PCM")
        val frames = converted.size / format.channels
        if (frames > format.sampleRate * 120 / 1000 + 256)
            throw PlaybackException("Resampler output exceeded its converted-frame bound", "CONGESTION")
        for (span in resampledTimeline.output(frames, resampler.delayFrames)) {
            regulator.offer(converted, TrimmedAudio(span.offsetFrames, span.frames, span.ptsUs), span.sourceUnits, false)
        }
        regulator.converted(slice.frames, frames)?.let { compensation ->
            stage = "PCM_COMPENSATION"
            diagnosticCall(DiagnosticPoint.COMPENSATE, count = compensation.distance.toLong()) {
                resampler.compensate(compensation.samples, compensation.distance)
            }
            onOutputTiming(output.timing())
            onRegulation(regulator.diagnostic())
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
                onClockCorrection(it)
                lastClockEventNs = now
                lastLoggedOffsetUs = it.afterOffsetUs
                pendingClockCorrection = null
            }
        }
    }

    private fun observeUnderruns() {
        if (firstPtsUs == null || !running.get()) return
        val now = clock.monotonicNs()
        if (lastUnderrunObservationNs?.let { now - it < 20_000_000L } == true) return
        stage = "UNDERRUN_CHECK"
        lastUnderrunObservationNs = now
        val count = output.underrunCount
        val timing = diagnosticCall(DiagnosticPoint.TIMING) { output.timing() }
        val queued = queuedPackets()
        eventHistory?.record(now, "observe", count.toLong(), queued.toLong())
        eventHistory?.dump(now, count)?.forEach { onServiceDiagnostic?.invoke(it) }
        serviceDiagnostic?.observe(count, timing.submittedFrames - timing.consumedFrames,
            regulator.bufferedFrames, queued, pending != null || waiting != null)
        underruns.observe(now, count, timing, queued,
            pending != null || waiting != null, output.playbackActive)?.let {
            onUnderrunRecovery(it)
            if (RemoteDiagnostics.recorder.enabled("receiver", diagnosticSession, DiagnosticKind.AUDIO_OUTPUT))
                RemoteDiagnostics.recorder.observe("receiver", diagnosticSession, DiagnosticKind.AUDIO_OUTPUT,
                    DiagnosticStage.SAMPLE, 2, diagnosticGeneration, point = DiagnosticPoint.TIMING,
                    metadata = DiagnosticOutput(it.timing.submittedFrames, it.timing.consumedFrames,
                        it.timing.presentedFrames, it.timing.timestampAgeUs, it.timing.clockSource, it.currentCount))
        }
    }

    override fun unblock() { output.pause() }

    override fun describeFailure(error: Exception): String =
        "Audio stage=$stage exception=${error.javaClass.simpleName}: ${error.message ?: "operation failed"}"

    override fun release() {
        try {
            sourceClock?.let { source ->
                try {
                    observePresentation(output.timing(), clock.monotonicNs())
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
                    reportService(final = true)
                    regulator.clear()
                    presentation.clear()
                    try { resampler.close() } finally { output.close() }
                }
            }
        }
    }

    private fun observePresentation(timing: AudioOutputTiming, observedAtNs: Long) {
        val sampleUs = presentation.presentedUs(timing.presentedFrames)
        presentedSourceUs = sampleUs
        sourceClock?.let { clock.observeAudio(it, sampleUs, observedAtNs) }
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

    private data class DecodedAudio(val samples: ShortArray, val slice: TrimmedAudio)

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

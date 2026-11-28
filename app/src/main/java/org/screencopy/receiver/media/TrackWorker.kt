package org.screencopy.receiver.media

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import dev.redrain.cast.diagnostics.*

internal class TrackStoppedException : IllegalStateException("Track decoder is stopped")

abstract class TrackWorker(
    name: String,
    private val maxBytes: Int,
    private val failure: (String, String) -> Unit,
    private val track: Int = 0,
    private val prepareThread: () -> Unit = {},
    private val maxPackets: Int = 48,
    private val maxSpanUs: Long = 250_000
) : AutoCloseable {
    init { require(maxPackets > 0 && maxSpanUs >= 0) }
    private val queue = ArrayDeque<AccessUnit>()
    private val admittedAt = ArrayDeque<Long>()
    @Volatile internal var statisticsEnabled = false
    @Volatile internal var diagnosticSession = 0L
    @Volatile internal var diagnosticGeneration = 0
    private val inputProgress = AudioInputProgress { values, at ->
        if (track == 2) TelemetryRegistry.causal(diagnosticSession, CausalEvidence.Stage.INPUT,
            diagnosticGeneration, *values, atNs = at)
    }
    private val workerProgress = AudioCausalProgress { stage, values, at ->
        if (track == 2) TelemetryRegistry.causal(diagnosticSession, stage, diagnosticGeneration, *values, atNs = at)
    }
    private fun causalEnabled(): Boolean = track == 2 && TelemetryRegistry.has("receiver", diagnosticSession)
    protected fun causalPhase(phase: Long) {
        if (causalEnabled()) workerProgress.phase(phase, causalNowNs())
    }
    protected fun causalInterval(begin: Long, end: Long, cpu: Long, kind: Long) {
        if (causalEnabled()) workerProgress.interval(begin, end, cpu, kind)
    }
    protected open fun causalNowNs(): Long = System.nanoTime()
    @Volatile protected var lastAudioAdmissionNs: Long? = null
        private set
    private fun diagnosticQueue(stage: DiagnosticStage, packet: AccessUnit) {
        val diagnostics = RemoteDiagnostics.recorder
        if (track != 2 || !diagnostics.enabled("receiver", diagnosticSession, DiagnosticKind.AUDIO_QUEUE)) return
        diagnostics.observe("receiver", diagnosticSession, DiagnosticKind.AUDIO_QUEUE, stage, track, diagnosticGeneration,
            pts = queue.firstOrNull()?.ptsUs ?: packet.ptsUs, endPts = queue.lastOrNull()?.ptsUs ?: packet.ptsUs,
            count = queue.size.toLong(), bytes = queue.sumOf { it.bytes.size.toLong() },
            reason = if (stage == DiagnosticStage.REJECTED) DiagnosticReason.CAPACITY else DiagnosticReason.NONE,
            point = DiagnosticPoint.QUEUE,
            metadata = DiagnosticQueue(queue.size, maxPackets, queue.sumOf { it.bytes.size }, maxBytes,
                admittedAt.firstOrNull()?.let { (System.nanoTime() - it).coerceAtLeast(0) },
                if (queue.isEmpty()) 0 else queue.maxOf { it.ptsUs } - queue.minOf { it.ptsUs }, maxSpanUs))
    }
    private val monitor = Object()
    private val budget = PacketBudget(maxBytes, maxSpanUs)
    protected val running = AtomicBoolean(true)
    private val thread = Thread({ runTrack() }, name)

    fun start() {
        causalPhase(CausalEvidence.phases.indexOf("THREAD_START").toLong())
        thread.start()
    }
    protected open fun packetEvent(kind: String, packet: AccessUnit, queued: Int) {}
    @Volatile protected var retainedInputBytes: Int = 0
        private set
    private var retainedDecodedBytes = 0
    private var retainedDecodedPackets = 0
    protected fun reserveInputBytes(bytes: Int): Boolean = synchronized(monitor) {
        require(bytes >= 0)
        if (queue.sumOf { it.bytes.size.toLong() } + retainedInputBytes + bytes > maxBytes) {
            if (track == 2) {
                RemoteDiagnostics.recorder.audioTiming(diagnosticSession)?.let {
                    it.pressure(causalNowNs(), diagnosticSession, diagnosticGeneration,
                        DiagnosticAudioTiming.RESERVATION_BYTES)
                }
            }
            false
        }
        else {
            retainedInputBytes += bytes
            true
        }
    }
    protected fun retainDecodedInput(bytes: Int) = synchronized(monitor) {
        require(bytes in 0..(retainedInputBytes - retainedDecodedBytes))
        retainedDecodedBytes += bytes
        retainedDecodedPackets++
    }
    protected fun releaseInputReservation(bytes: Int) = synchronized(monitor) {
        require(bytes in 0..(retainedInputBytes - retainedDecodedBytes))
        retainedInputBytes -= bytes
        monitor.notifyAll()
    }
    protected fun releaseDecodedInput(bytes: Int) = synchronized(monitor) {
        require(retainedDecodedPackets > 0 && bytes in 0..retainedDecodedBytes)
        retainedDecodedBytes -= bytes
        retainedDecodedPackets--
        retainedInputBytes -= bytes
        monitor.notifyAll()
    }
    protected fun clearInputRetention() = synchronized(monitor) {
        retainedInputBytes = 0
        retainedDecodedBytes = 0
        retainedDecodedPackets = 0
        monitor.notifyAll()
    }

    internal fun queuedPackets(): Int = synchronized(monitor) { queue.size }
    protected data class RetentionSnapshot(val decodedBytes: Int, val decodedPackets: Int,
                                           val encodedBytes: Int, val encodedPackets: Int)
    protected fun retentionSnapshot(): RetentionSnapshot = synchronized(monitor) {
        RetentionSnapshot(retainedDecodedBytes, retainedDecodedPackets, queue.sumOf { it.bytes.size }, queue.size)
    }
    protected fun reservationPressure(bytes: Int): QueuePressure? = synchronized(monitor) {
        if (queue.isEmpty()) return@synchronized null
        val encoded = queue.sumOf { it.bytes.size }
        val span = queue.maxOf { it.ptsUs } - queue.minOf { it.ptsUs }
        QueuePressure(track, QueueLimit.BYTES, queue.size, encoded + retainedInputBytes, queue.size,
            encoded.toLong() + retainedInputBytes + bytes, span, maxPackets, maxBytes, maxSpanUs,
            detectedAtNs = causalNowNs(), incomingPtsUs = queue.first().ptsUs,
            encodedBytes = encoded, retainedDecodedBytes = retainedDecodedBytes)
    }
    private data class QueueObservation(val value: EncodedQueueStatistics, val oldestAtNs: Long?)
    @Volatile private var queueObservation = QueueObservation(
        EncodedQueueStatistics(0, 0, 0, null, maxBytes, maxPackets, maxSpanUs), null)
    internal fun queueStatistics(): EncodedQueueStatistics {
        val observed = queueObservation
        return observed.value.copy(oldestAgeMs =
            observed.oldestAtNs?.let { (System.nanoTime() - it).coerceAtLeast(0) / 1_000_000 })
    }

    private fun publishQueueStatistics() {
        if (TelemetryRegistry.has("receiver", diagnosticSession) && track in 1..2) {
            val now = System.nanoTime()
            val span = if (queue.isEmpty()) 0 else queue.maxOf { it.ptsUs } - queue.minOf { it.ptsUs }
            TelemetryRegistry.evidence("receiver", diagnosticSession, track, diagnosticGeneration, IncidentEvidence.Stage.QUEUE_STATE,
                queue.size.toLong(), queue.sumOf { it.bytes.size.toLong() }, span,
                admittedAt.firstOrNull() ?: -1)
            TelemetryRegistry.state("receiver", diagnosticSession, 60 + track,
                DiagnosticKind.entries.size + 1, DiagnosticStage.SAMPLE.ordinal,
                track, diagnosticGeneration, span,
                detail = listOf(queue.size.toLong(), maxPackets.toLong(), queue.sumOf { it.bytes.size.toLong() },
                    maxBytes.toLong(), admittedAt.firstOrNull()?.let { (now - it).coerceAtLeast(0) / 1_000_000 } ?: -1))
        }
        if (!statisticsEnabled) return
        queueObservation = QueueObservation(EncodedQueueStatistics(queue.size, queue.sumOf { it.bytes.size },
            if (queue.isEmpty()) 0 else queue.maxOf { it.ptsUs } - queue.minOf { it.ptsUs },
            null, maxBytes, maxPackets, maxSpanUs), admittedAt.firstOrNull())
    }

    private fun rejected(pressure: QueuePressure, packet: AccessUnit): QueuePressure {
        val at = causalNowNs()
        val enriched = pressure.copy(detectedAtNs = at, incomingPtsUs = packet.ptsUs,
            encodedBytes = queue.sumOf { it.bytes.size }, retainedDecodedBytes = retainedDecodedBytes)
        if (causalEnabled()) {
            TelemetryRegistry.receiverRejection(diagnosticSession, diagnosticGeneration, enriched)
            TelemetryRegistry.freezeReceiver(diagnosticSession, diagnosticGeneration, at)
        }
        return enriched
    }

    private fun timingAdmission(packet: AccessUnit, pressure: QueuePressure?) {
        val timing = packet.diagnosticAudioTiming ?: return
        val now = causalNowNs()
        if (pressure != null) timing.pressure(now, diagnosticSession, diagnosticGeneration, pressure.limit.ordinal)
        timing.record(now, diagnosticGeneration, packet.diagnosticPacketId,
            if (pressure == null) DiagnosticAudioTiming.ADMITTED else DiagnosticAudioTiming.ADMISSION_PRESSURE,
            queue.size.toLong(), retainedInputBytes.toLong())
        if (timing.detailEnabled) {
            val span = if (queue.isEmpty()) 0 else queue.maxOf { it.ptsUs } - queue.minOf { it.ptsUs }
            val age = admittedAt.firstOrNull()?.let { (now - it).coerceAtLeast(0) } ?: -1
            timing.record(now, diagnosticGeneration, packet.diagnosticPacketId,
                DiagnosticAudioTiming.QUEUE_DETAIL, span, age)
        }
    }

    fun tryOffer(packet: AccessUnit, durationUs: Long = -1): QueuePressure? = synchronized(monitor) {
        if (!running.get()) throw TrackStoppedException()
        if (causalEnabled()) inputProgress.arrival(causalNowNs(), packet.ptsUs, durationUs)
        packet.diagnosticAudioTiming?.record(causalNowNs(), diagnosticGeneration, packet.diagnosticPacketId,
            DiagnosticAudioTiming.ADMISSION_CHECK, queue.size.toLong(), retainedInputBytes.toLong())
        val pressure = budget.pressure(packet, track, maxPackets, retainedBytes = retainedInputBytes)
        timingAdmission(packet, pressure)
        if (pressure == null) {
            budget.add(packet)
            queue.addLast(packet)
            if (track == 2) lastAudioAdmissionNs = System.nanoTime()
            if (causalEnabled()) inputProgress.admitted(causalNowNs(), packet.ptsUs)
            packetEvent("admit", packet, queue.size)
            if (statisticsEnabled || diagnosticSession > 0) admittedAt.addLast(System.nanoTime())
            publishQueueStatistics()
            monitor.notifyAll()
        }
        val result = pressure?.let { rejected(it, packet) }
        diagnosticQueue(if (result == null) DiagnosticStage.ADMITTED else DiagnosticStage.REJECTED, packet)
        result
    }

    fun offer(packet: AccessUnit, durationUs: Long = -1) {
        val started = System.nanoTime()
        val deadline = started + TimeUnit.MILLISECONDS.toNanos(250)
        synchronized(monitor) {
            if (causalEnabled()) inputProgress.arrival(causalNowNs(), packet.ptsUs, durationUs)
            while (true) {
                if (!running.get()) throw TrackStoppedException()
                packet.diagnosticAudioTiming?.record(causalNowNs(), diagnosticGeneration, packet.diagnosticPacketId,
                    DiagnosticAudioTiming.ADMISSION_CHECK, queue.size.toLong(), retainedInputBytes.toLong())
                val pressure = budget.pressure(packet, track, maxPackets, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                    retainedInputBytes)
                timingAdmission(packet, pressure)
                if (pressure == null) {
                    budget.add(packet)
                    queue.addLast(packet)
                    if (track == 2) lastAudioAdmissionNs = System.nanoTime()
                    if (causalEnabled()) inputProgress.admitted(causalNowNs(), packet.ptsUs)
                    packetEvent("admit", packet, queue.size)
                    if (statisticsEnabled || diagnosticSession > 0) admittedAt.addLast(System.nanoTime())
                    publishQueueStatistics()
                    monitor.notifyAll()
                    diagnosticQueue(DiagnosticStage.ADMITTED, packet)
                    return
                }
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0 || packet.bytes.size > pressure.maxBytes)
                    throw QueueCongestionException(rejected(pressure, packet))
                TimeUnit.NANOSECONDS.timedWait(monitor, remaining)
            }
        }
    }

    protected fun next(onEmpty: () -> Unit = {}): AccessUnit? = synchronized(monitor) {
        if (queue.isEmpty() && running.get()) {
            // Linearize the empty transition with admission; the callback must not block.
            onEmpty()
            monitor.wait(2)
        }
        if (queue.isEmpty()) null else queue.removeFirst().also {
            it.diagnosticAudioTiming?.record(causalNowNs(), diagnosticGeneration, it.diagnosticPacketId,
                DiagnosticAudioTiming.DEQUEUED, it.ptsUs, it.bytes.size.toLong())
            if (admittedAt.isNotEmpty()) admittedAt.removeFirst()
            budget.remove()
            if (causalEnabled()) {
                val now = causalNowNs()
                inputProgress.dequeued(now, it.ptsUs)
                workerProgress.dequeue(now, it.ptsUs)
            }
            publishQueueStatistics()
            diagnosticQueue(DiagnosticStage.COMPLETE, it)
            packetEvent("dequeue", it, queue.size)
            monitor.notifyAll()
        }
    }

    private fun runTrack() {
        try {
            causalPhase(CausalEvidence.phases.indexOf("THREAD_PREPARE").toLong())
            prepareThread()
            while (running.get()) {
                if (causalEnabled()) workerProgress.begin(causalNowNs())
                TelemetryRegistry.evidence("receiver", diagnosticSession, track, diagnosticGeneration,
                    IncidentEvidence.Stage.WORKER_BEGIN)
                step()
                if (causalEnabled()) workerProgress.end(causalNowNs())
                TelemetryRegistry.evidence("receiver", diagnosticSession, track, diagnosticGeneration,
                    IncidentEvidence.Stage.WORKER_END)
            }
        } catch (e: InterruptedException) {
            if (running.get()) failure("DECODER_FAILED", "Playback interrupted unexpectedly")
        } catch (e: Exception) {
            if (running.get()) failure(if (e is PlaybackException) e.code else "DECODER_FAILED", describeFailure(e))
        }
    }

    protected abstract fun step()
    protected open fun describeFailure(error: Exception): String =
        if (error is AudioClockException) "Audio stage=OUTPUT_CLOCK exception=AudioClockException: ${error.message}"
        else "Playback failed: ${error.message ?: error.javaClass.simpleName}"
    protected abstract fun release()
    protected open fun unblock() {}

    fun halt() {
        if (!running.getAndSet(false)) return
        synchronized(monitor) { monitor.notifyAll() }
        try {
            unblock()
        } finally {
            thread.interrupt()
        }
    }

    final override fun close() {
        try {
            halt()
        } finally {
            try {
                if (Thread.currentThread() !== thread && thread.isAlive) {
                    thread.join(2000)
                    check(!thread.isAlive) { "Decoder did not stop within two seconds" }
                }
                release()
            } finally {
                synchronized(monitor) {
                    queue.clear(); admittedAt.clear(); budget.clear()
                    publishQueueStatistics()
                    monitor.notifyAll()
                }
            }
        }
    }
}

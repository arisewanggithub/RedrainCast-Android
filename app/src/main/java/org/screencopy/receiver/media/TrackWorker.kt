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
    private val prepareThread: () -> Unit = {}
) : AutoCloseable {
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
            metadata = DiagnosticQueue(queue.size, 48, queue.sumOf { it.bytes.size }, maxBytes,
                admittedAt.firstOrNull()?.let { (System.nanoTime() - it).coerceAtLeast(0) },
                if (queue.isEmpty()) 0 else queue.maxOf { it.ptsUs } - queue.minOf { it.ptsUs }, 250_000))
    }
    private val monitor = Object()
    private val budget = PacketBudget(maxBytes, 250_000)
    protected val running = AtomicBoolean(true)
    private val thread = Thread({ runTrack() }, name)

    fun start() {
        causalPhase(CausalEvidence.phases.indexOf("THREAD_START").toLong())
        thread.start()
    }
    protected open fun packetEvent(kind: String, packet: AccessUnit, queued: Int) {}

    internal fun queuedPackets(): Int = synchronized(monitor) { queue.size }
    private data class QueueObservation(val value: EncodedQueueStatistics, val oldestAtNs: Long?)
    @Volatile private var queueObservation = QueueObservation(
        EncodedQueueStatistics(0, 0, 0, null, maxBytes), null)
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
                detail = listOf(queue.size.toLong(), 48, queue.sumOf { it.bytes.size.toLong() },
                    maxBytes.toLong(), admittedAt.firstOrNull()?.let { (now - it).coerceAtLeast(0) / 1_000_000 } ?: -1))
        }
        if (!statisticsEnabled) return
        queueObservation = QueueObservation(EncodedQueueStatistics(queue.size, queue.sumOf { it.bytes.size },
            if (queue.isEmpty()) 0 else queue.maxOf { it.ptsUs } - queue.minOf { it.ptsUs },
            null, maxBytes), admittedAt.firstOrNull())
    }

    fun tryOffer(packet: AccessUnit, durationUs: Long = -1): QueuePressure? = synchronized(monitor) {
        if (!running.get()) throw TrackStoppedException()
        if (causalEnabled()) inputProgress.arrival(causalNowNs(), packet.ptsUs, durationUs)
        val pressure = budget.pressure(packet, track, 48)
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
        diagnosticQueue(if (pressure == null) DiagnosticStage.ADMITTED else DiagnosticStage.REJECTED, packet)
        pressure
    }

    fun offer(packet: AccessUnit, durationUs: Long = -1) {
        val started = System.nanoTime()
        val deadline = started + TimeUnit.MILLISECONDS.toNanos(250)
        synchronized(monitor) {
            if (causalEnabled()) inputProgress.arrival(causalNowNs(), packet.ptsUs, durationUs)
            while (true) {
                if (!running.get()) throw TrackStoppedException()
                val pressure = budget.pressure(packet, track, 48, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
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
                    throw QueueCongestionException(pressure)
                TimeUnit.NANOSECONDS.timedWait(monitor, remaining)
            }
        }
    }

    protected fun next(): AccessUnit? = synchronized(monitor) {
        if (queue.isEmpty() && running.get()) monitor.wait(2)
        if (queue.isEmpty()) null else queue.removeFirst().also {
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

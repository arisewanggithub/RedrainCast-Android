package dev.redrain.cast.diagnostics

import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal object TelemetryTerminalBudget {
    const val FATAL_CLEANUP_MS = 6_000L
    const val FINAL_PREPARATION_MS = 250L
    const val FINAL_WRITE_MS = 750L
    const val RESPONSE_MARGIN_MS = 500L
    const val FATAL_RESPONSE_NS =
        (FATAL_CLEANUP_MS + FINAL_PREPARATION_MS + FINAL_WRITE_MS + RESPONSE_MARGIN_MS) * 1_000_000L
    const val FINAL_TRANSACTION_NS = (FINAL_PREPARATION_MS + FINAL_WRITE_MS) * 1_000_000L
    const val CONTROL_FRAME_NS = 10_000_000_000L
}

private fun telemetryWorker(name: String): ScheduledThreadPoolExecutor =
    ScheduledThreadPoolExecutor(1) { action ->
        Thread(action, name).apply { isDaemon = true }
    }.apply {
        removeOnCancelPolicy = true
        executeExistingDelayedTasksAfterShutdownPolicy = false
    }

/** Receiver-side owner. The supplied offer must be a nonblocking control-writer admission. */
internal class ReceiverTelemetrySession(
    private val offerTelemetry: (ByteArray) -> Boolean,
    intervalMs: Long = 1_000,
    private val clock: () -> Long = System::nanoTime,
    private val failed: (Exception) -> Unit = {},
    private val beforeFinal: () -> Unit = {},
    private val worker: ScheduledExecutorService = telemetryWorker("receiver-telemetry")
) {
    data class FinalBatch(val payload: ByteArray, val sequence: Long, val cleanupSucceeded: Boolean)
    enum class FinalSkipReason { PREPARATION_TIMEOUT, PREPARATION_FAILED, UNAVAILABLE, WRITE_SKIPPED }
    data class FinalResult(val batch: FinalBatch?, val reason: FinalSkipReason?)

    private val collector = SessionTelemetryCollector(clock)
    private val active = AtomicBoolean(true)
    private val producers = AtomicInteger()
    private val quiesced = CountDownLatch(1)
    private val periodic: java.util.concurrent.ScheduledFuture<*>

    init {
        require(intervalMs == 1_000L || intervalMs == 2_000L)
        periodic = worker.scheduleWithFixedDelay({
            if (active.get()) {
                try {
                    val payload = collector.publish()
                    SessionTelemetryProtocol.parse(payload, "receiver")
                    if (!offerTelemetry(payload)) collector.publicationUnsent()
                } catch (error: RuntimeException) {
                    collector.publicationUnsent()
                    failed(error)
                }
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS)
    }

    fun state(slot: Int, row: TelemetryRow) = produce { collector.state(slot, row) }
    fun operation(slot: Int, row: TelemetryRow, unknownGeneration: Boolean) =
        produce { collector.operation(slot, row, unknownGeneration) }

    fun event(row: TelemetryRow, pressure: Boolean = false, failure: Boolean = false) =
        produce { collector.event(row, pressure, failure) }

    /** Also call when the writer replaces a previously accepted pending publication. */
    fun publicationUnsent() = collector.publicationUnsent()

    private inline fun produce(action: () -> Unit) {
        if (!active.get()) return
        producers.incrementAndGet()
        try {
            if (active.get()) action()
        } finally {
            if (producers.decrementAndGet() == 0 && !active.get()) quiesced.countDown()
        }
    }

    /**
     * Called after receiver cleanup. The returned payload is for the terminal transaction,
     * not the periodic slot. A null result means the unstarted final should be skipped.
     * deadlineNs uses the same monotonic clock as this owner.
     */
    fun requestFinal(cleanupSucceeded: Boolean, deadlineNs: Long): FinalBatch? =
        requestFinalResult(cleanupSucceeded, deadlineNs).batch

    fun requestFinalResult(cleanupSucceeded: Boolean, deadlineNs: Long): FinalResult {
        val startedAt = System.nanoTime()
        if (!active.compareAndSet(true, false)) return FinalResult(null, FinalSkipReason.UNAVAILABLE)
        if (producers.get() == 0) quiesced.countDown()
        periodic.cancel(false)
        val now = clock()
        val budget = if (deadlineNs <= now) 0L else minOf(
            TelemetryTerminalBudget.FINAL_PREPARATION_MS * 1_000_000L, deadlineNs - now)
        if (budget == 0L) {
            worker.shutdown()
            return FinalResult(null, FinalSkipReason.PREPARATION_TIMEOUT)
        }
        var skipReason = FinalSkipReason.PREPARATION_TIMEOUT
        val result = try {
            val task = worker.submit<FinalBatch?> {
                if (!quiesced.await(budget, TimeUnit.NANOSECONDS) || clock() >= deadlineNs)
                    return@submit null
                beforeFinal()
                val payload = collector.publish(final = true)
                val batch = SessionTelemetryProtocol.parse(payload, "receiver")
                FinalBatch(payload, batch.sequence, cleanupSucceeded)
            }
            try {
                val remaining = (budget - (System.nanoTime() - startedAt)).coerceAtLeast(0)
                task.get(remaining, TimeUnit.NANOSECONDS)
            } catch (_: TimeoutException) {
                task.cancel(true)
                null
            } catch (error: InterruptedException) {
                task.cancel(true)
                Thread.currentThread().interrupt()
                skipReason = FinalSkipReason.PREPARATION_FAILED
                null
            } catch (error: java.util.concurrent.ExecutionException) {
                failed((error.cause as? Exception) ?: error)
                skipReason = FinalSkipReason.PREPARATION_FAILED
                null
            }
        } catch (error: RejectedExecutionException) {
            failed(error)
            skipReason = FinalSkipReason.PREPARATION_FAILED
            null
        }
        worker.shutdown()
        return FinalResult(result, if (result == null) skipReason else null)
    }
}

/**
 * Sender-side owner. Only the worker touches history and serializes local batches.
 * The control reader validates and stages at most two peer batches.
 */
internal class SenderTelemetrySession(
    private val receiverSupported: Boolean,
    write: (ByteArray) -> Unit,
    private val failed: (Exception) -> Unit = {},
    private val clock: () -> Long = System::nanoTime,
    private val worker: ScheduledExecutorService = telemetryWorker("sender-telemetry"),
    storageExecutor: java.util.concurrent.Executor? = null,
    private val onFinished: () -> Unit = {}
) {
    constructor(
        receiverSupported: Boolean,
        store: IncidentStore,
        localKey: String,
        failed: (Exception) -> Unit = {}
    ) : this(receiverSupported, { store.writeTelemetry(localKey, it) }, failed)

    private data class Inbound(val payload: ByteArray, val arrivalNs: Long)
    private data class Terminal(val finalSequence: Long?, val cleanupSucceeded: Boolean, val atNs: Long,
                                val skipReason: ReceiverTelemetrySession.FinalSkipReason?)

    private val collector = SessionTelemetryCollector(clock)
    private val history = TelemetryHistory(receiverSupported)
    private val revisions = if (storageExecutor == null) TelemetryReportRevisions(write, failed)
        else TelemetryReportRevisions(write, failed, storageExecutor)
    private val active = AtomicBoolean(true)
    private val producers = AtomicInteger()
    private val quiesced = CountDownLatch(1)
    private val gate = Any()
    private val inbound = ArrayDeque<Inbound>(2)
    private val stagingDropped = AtomicLong()
    private var lastPeerSequence = 0L
    private var peerFinalSeen = false
    private var peerTerminal: Terminal? = null
    private var peerTerminalSeen = false
    private var finishing = false
    private var senderCleanupSucceeded = true
    private var scheduled = false
    private var initialSubmitted = false
    private var windowSubmitted = false

    init {
        worker.scheduleWithFixedDelay({
            try {
                if (active.get()) {
                    history.accept(TelemetryHistory.Endpoint.SENDER, collector.publish(), clock())
                    submitRevisions()
                }
            } catch (error: RuntimeException) {
                failed(error)
            }
        }, 1_000, 1_000, TimeUnit.MILLISECONDS)
    }

    fun state(slot: Int, row: TelemetryRow) = produce { collector.state(slot, row) }
    fun operation(slot: Int, row: TelemetryRow, unknownGeneration: Boolean) =
        produce { collector.operation(slot, row, unknownGeneration) }

    fun event(row: TelemetryRow, pressure: Boolean = false, failure: Boolean = false) =
        produce { collector.event(row, pressure, failure) }

    private inline fun produce(action: () -> Unit) {
        if (!active.get()) return
        producers.incrementAndGet()
        try {
            if (active.get()) action()
        } finally {
            if (producers.decrementAndGet() == 0 && !active.get()) quiesced.countDown()
        }
    }

    /** Validates on the control reader, then admits a defensive copy without waiting for storage. */
    fun acceptPeer(payload: ByteArray): Boolean {
        if (!receiverSupported || !active.get()) return false
        if (payload.size > SessionTelemetryProtocol.MAX_BATCH_BYTES)
            SessionTelemetryProtocol.parse(payload, "receiver")
        val copy = payload.copyOf()
        val batch = SessionTelemetryProtocol.parse(copy, "receiver")
        val arrival = clock()
        synchronized(gate) {
            if (finishing || peerTerminalSeen || peerFinalSeen || batch.sequence <= lastPeerSequence)
                return false
            lastPeerSequence = batch.sequence
            peerFinalSeen = batch.final
            if (inbound.size == 2) {
                inbound.removeFirst()
                stagingDropped.incrementAndGet()
            }
            inbound.addLast(Inbound(copy, arrival))
            return scheduleLocked()
        }
    }

    fun peerStagingDropped(): Long = stagingDropped.get()

    /**
     * Call on the receiver terminal after the final STATUS, including when its marker is absent.
     * An optional numeric outcome row is retained in the sender's evidence.
     */
    fun terminal(finalSequence: Long?, cleanupSucceeded: Boolean,
                 outcome: TelemetryRow? = null, failure: Boolean = false,
                 skipReason: ReceiverTelemetrySession.FinalSkipReason? = null): Boolean {
        require(finalSequence == null || finalSequence > 0)
        require(!failure || outcome != null)
        synchronized(gate) {
            if (!receiverSupported || finishing || peerTerminalSeen) return false
            if (outcome != null) collector.event(outcome, failure = failure)
            peerTerminalSeen = true
            peerTerminal = Terminal(finalSequence, cleanupSucceeded, clock(), skipReason)
            return scheduleLocked()
        }
    }

    /** Stops admission and asynchronously drains accepted evidence and queues the final revision. */
    fun finish(cleanupSucceeded: Boolean = true): Boolean {
        synchronized(gate) {
            if (finishing) return false
            finishing = true
            senderCleanupSucceeded = cleanupSucceeded
            active.set(false)
            if (producers.get() == 0) quiesced.countDown()
            return scheduleLocked()
        }
    }

    private fun scheduleLocked(): Boolean {
        if (scheduled) return true
        scheduled = true
        return try {
            worker.execute(::drain)
            true
        } catch (error: RejectedExecutionException) {
            scheduled = false
            failed(error)
            false
        }
    }

    private fun drain() {
        while (true) {
            val items: List<Inbound>
            val terminal: Terminal?
            val finish: Boolean
            val cleanup: Boolean
            synchronized(gate) {
                items = inbound.toList()
                inbound.clear()
                terminal = peerTerminal
                peerTerminal = null
                finish = finishing
                cleanup = senderCleanupSucceeded
                if (items.isEmpty() && terminal == null && !finish) {
                    scheduled = false
                    return
                }
            }
            try {
                items.forEach { history.accept(TelemetryHistory.Endpoint.RECEIVER, it.payload, it.arrivalNs) }
                if (terminal != null) history.terminal(TelemetryHistory.Endpoint.RECEIVER,
                    terminal.finalSequence, terminal.cleanupSucceeded, terminal.atNs, terminal.skipReason)
                if (finish) {
                    val ready = try {
                        quiesced.await(250, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        false
                    }
                    if (!ready)
                        failed(IllegalStateException("Telemetry producer still active at finish"))
                    val now = clock()
                    val payload = collector.publish(final = true)
                    val final = SessionTelemetryProtocol.parse(payload, "sender")
                    history.accept(TelemetryHistory.Endpoint.SENDER, payload, now)
                    history.terminal(TelemetryHistory.Endpoint.SENDER, final.sequence, cleanup, now)
                    history.finish(now)?.let { revisions.submit(TelemetryReportRevisions.Revision.FINAL, it) }
                    worker.shutdown()
                    onFinished()
                    return
                }
                submitRevisions()
            } catch (error: RuntimeException) {
                failed(error)
                if (finish) {
                    worker.shutdown()
                    onFinished()
                    return
                }
            }
        }
    }

    private fun submitRevisions() {
        if (!initialSubmitted) {
            history.initial()?.let {
                initialSubmitted = revisions.submit(TelemetryReportRevisions.Revision.INITIAL, it)
            }
        }
        if (!windowSubmitted) {
            history.closeWindow(clock())?.let {
                windowSubmitted = revisions.submit(TelemetryReportRevisions.Revision.WINDOW_CLOSED, it)
            }
        }
    }
}

package org.screencopy.receiver.net

import dev.redrain.cast.diagnostics.IncidentEvidence
import dev.redrain.cast.diagnostics.TelemetryRegistry
import dev.redrain.cast.diagnostics.TelemetryTerminalBudget
import org.screencopy.receiver.protocol.Message
import org.screencopy.receiver.protocol.Wire
import org.screencopy.receiver.protocol.Type
import org.screencopy.receiver.protocol.boolean
import org.screencopy.receiver.media.QueueCongestionException
import org.screencopy.receiver.media.QueueLimit
import org.screencopy.receiver.media.QueuePressure
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.net.Socket

internal class ControlWriter(
    private val socket: Socket,
    private val written: (Message) -> Unit,
    private val failed: (String) -> Unit,
    private val writeFrame: (Socket, Message) -> Unit = { target, message -> Wire.write(target.outputStream, message) },
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val observed: (Message, Boundary, Int, Int, Long) -> Unit = { _, _, _, _, _ -> }
) : AutoCloseable {
    enum class Boundary { ENQUEUED, WRITING, WRITTEN, CANCELLED, FAILED }
    enum class TelemetryAdmission { ACCEPTED, REPLACED, REJECTED }
    companion object {
        private val deadlines = Executors.newSingleThreadScheduledExecutor {
            Thread(it, "control-terminal-deadline").apply { isDaemon = true }
        }
        fun observeReadyBoundary(message: Message, boundary: Boundary) {
            if (message.type != Type.STREAM_READY) return
            val stage = when (boundary) {
                Boundary.ENQUEUED -> IncidentEvidence.Stage.READY_ENQUEUED
                Boundary.WRITING -> IncidentEvidence.Stage.READY_WRITING
                Boundary.WRITTEN -> IncidentEvidence.Stage.READY_WRITTEN
                Boundary.CANCELLED -> IncidentEvidence.Stage.READY_CANCELLED
                Boundary.FAILED -> IncidentEvidence.Stage.READY_WRITE_FAILED
            }
            TelemetryRegistry.evidence("receiver", message.session, message.track,
                message.generation.toInt(), stage)
        }
    }
    private val queue = ArrayBlockingQueue<Message>(64)
    private var queuedBytes = 0
    private var queuedPackets = 0
    private val admittedAt = java.util.IdentityHashMap<Message, Long>()
    private var terminal: Type? = null
    private var telemetry: Message? = null
    private data class TerminalTransaction(
        val type: Type, val final: Message?, val deadlineMs: Long,
        val terminal: (Boolean) -> Message, val finalResult: (Boolean) -> Unit, val bounded: Boolean,
        val finished: AtomicBoolean, val watchdog: java.util.concurrent.ScheduledFuture<*>?
    ) {
        fun cancelWatchdog() {
            finished.set(true)
            watchdog?.cancel(false)
        }
    }
    private var transaction: TerminalTransaction? = null
    private var terminalReservation: Any? = null
    private val telemetryInFlight = AtomicReference<Message?>()
    private val accepting = AtomicBoolean(true)
    @Volatile private var writingSince = 0L
    private val thread = Thread({ loop() }, "ScreenCopy-control-writer").also { it.start() }

    @Synchronized
    fun offer(message: Message) {
        check(accepting.get()) { "Connection writer has stopped" }
        val fatal = message.type == Type.ERROR && message.json().boolean("fatal")
        val orderly = message.type == Type.STOP || message.type == Type.SESSION_END
        if (terminal == Type.ERROR || terminal != null && !fatal) return
        if (orderly || fatal) {
            if (fatal) {
                terminalReservation = null
                transaction?.let {
                    it.cancelWatchdog()
                    it.finalResult(false)
                }
                transaction = null
            }
            telemetry?.let { observed(it, Boundary.CANCELLED, -1, -1, -1) }
            telemetry = null
            cancelOrdinaryQueue(message.type)
        }
        val size = 40 + message.payload.size
        if (queuedBytes + size > 256 * 1024 || !queue.offer(message)) {
            throw QueueCongestionException(QueuePressure(0,
                if (queuedBytes + size > 256 * 1024) QueueLimit.BYTES else QueueLimit.PACKETS,
                queuedPackets, queuedBytes, queuedPackets + 1, queuedBytes.toLong() + size, 0, 64, 256 * 1024, 0))
        }
        queuedBytes += size
        queuedPackets++
        admittedAt[message] = clock()
        observed(message, Boundary.ENQUEUED, queuedPackets, queuedBytes, admittedAt.values.minOrNull() ?: -1)
        if (orderly || fatal) terminal = message.type
    }

    @Synchronized
    fun offerTelemetry(message: Message): TelemetryAdmission {
        require(message.type == Type.STATUS && message.payload.size <= 8192)
        if (!accepting.get() || terminal != null) return TelemetryAdmission.REJECTED
        val result = if (telemetry == null) TelemetryAdmission.ACCEPTED else TelemetryAdmission.REPLACED
        telemetry = message.copy(payload = message.payload.copyOf())
        return result
    }

    @Synchronized
    fun discardTelemetry(): Boolean {
        val discarded = telemetry ?: return false
        telemetry = null
        observed(discarded, Boundary.CANCELLED, -1, -1, -1)
        return true
    }

    private fun cancelOrdinaryQueue(type: Type) {
        val abandoned = mutableListOf<Message>()
        queue.drainTo(abandoned)
        val retained = if (type == Type.ERROR) emptyList() else abandoned.filter { it.type == Type.ERROR }
        abandoned.filter { it !in retained }.forEach {
            admittedAt.remove(it)
            observed(it, Boundary.CANCELLED, queuedPackets, queuedBytes, -1)
        }
        retained.forEach { check(queue.offer(it)) }
        queuedBytes -= abandoned.sumOf { 40 + it.payload.size } - retained.sumOf { 40 + it.payload.size }
        queuedPackets -= abandoned.size - retained.size
    }

    /**
     * Reserves the terminal slot and reports queued cancellations before preparing the final.
     * Preparation runs outside the writer monitor; true means a pending periodic batch was discarded.
     */
    fun offerTerminalTransaction(type: Type, final: Message?, deadlineMs: Long,
                                 terminalMessage: (Boolean) -> Message,
                                 finalResult: (Boolean) -> Unit = {}, bounded: Boolean = false,
                                 prepareFinal: ((Boolean) -> Message?)? = null): Boolean {
        require(type == Type.STOP || type == Type.SESSION_END || type == Type.ERROR)
        require(final == null || final.type == Type.STATUS && final.payload.size <= 8192)
        require(final == null || prepareFinal == null)
        val reservation = Any()
        val discardedTelemetry = synchronized(this) {
            check(accepting.get()) { "Connection writer has stopped" }
            if (terminal == Type.ERROR || terminal != null && type != Type.ERROR) return false
            transaction?.let { it.cancelWatchdog(); it.finalResult(false) }
            transaction = null
            cancelOrdinaryQueue(type)
            val discarded = discardTelemetry()
            terminal = type
            terminalReservation = reservation
            discarded
        }
        val prepared = if (prepareFinal != null) prepareFinal(discardedTelemetry) else final
        require(prepared == null || prepared.type == Type.STATUS && prepared.payload.size <= 8192)
        return synchronized(this) {
            if (!accepting.get() || terminalReservation !== reservation) return false
            terminalReservation = null
            val finished = AtomicBoolean()
            val watchdog = if (bounded) deadlines.schedule({
                if (finished.compareAndSet(false, true)) socket.close()
            }, minOf(TelemetryTerminalBudget.FINAL_WRITE_MS, (deadlineMs - clock()).coerceAtLeast(0)), TimeUnit.MILLISECONDS) else null
            transaction = TerminalTransaction(type, prepared?.copy(payload = prepared.payload.copyOf()), deadlineMs,
                terminalMessage, finalResult, bounded, finished, watchdog)
            telemetryInFlight.get()?.let { current ->
                val waitMs = minOf(TelemetryTerminalBudget.FINAL_WRITE_MS, (deadlineMs - clock()).coerceAtLeast(0))
                deadlines.schedule({
                    if (telemetryInFlight.compareAndSet(current, null)) socket.close()
                }, waitMs, TimeUnit.MILLISECONDS)
            }
            true
        }
    }

    private fun writeTransaction(item: TerminalTransaction) {
        try {
            writeTransactionWithinDeadline(item)
        } finally {
            item.cancelWatchdog()
        }
    }

    private fun writeTransactionWithinDeadline(item: TerminalTransaction) {
        if (item.bounded && item.finished.get())
            throw java.net.SocketTimeoutException("Terminal transaction deadline expired")
        var sent = false
        val final = item.final
        val writeDeadline = if (item.bounded)
            minOf(item.deadlineMs, clock() + TelemetryTerminalBudget.FINAL_WRITE_MS) else item.deadlineMs
        if (final != null && clock() < writeDeadline && synchronized(this) { terminal == item.type }) {
            val state = AtomicInteger()
            val waitMs = minOf(TelemetryTerminalBudget.FINAL_WRITE_MS, (writeDeadline - clock()).coerceAtLeast(0))
            val timeout = deadlines.schedule({
                if (state.compareAndSet(0, 2)) socket.close()
            }, waitMs, TimeUnit.MILLISECONDS)
            try {
                writingSince = clock()
                observed(final, Boundary.WRITING, -1, -1, -1)
                writeFrame(socket, final)
                if (!state.compareAndSet(0, 1)) throw java.net.SocketTimeoutException("Final telemetry write timed out")
                writingSince = 0
                observed(final, Boundary.WRITTEN, -1, -1, -1)
                written(final)
                sent = true
            } catch (error: java.io.IOException) {
                observed(final, Boundary.FAILED, -1, -1, -1)
                item.finalResult(false)
                throw error
            } catch (error: IllegalArgumentException) {
                observed(final, Boundary.FAILED, -1, -1, -1)
                item.finalResult(false)
                throw error
            } finally { timeout.cancel(false) }
        }
        item.finalResult(sent)
        if (synchronized(this) { terminal != item.type }) return
        val message = item.terminal(sent)
        require(message.type == item.type)
        if (synchronized(this) { terminal != item.type }) return
        val remaining = writeDeadline - clock()
        if (item.bounded && remaining <= 0) {
            socket.close()
            throw java.net.SocketTimeoutException("Terminal write deadline expired")
        }
        val state = AtomicInteger()
        val timeout = if (item.bounded) deadlines.schedule({
            if (state.compareAndSet(0, 2)) socket.close()
        }, remaining, TimeUnit.MILLISECONDS) else null
        writingSince = clock()
        observed(message, Boundary.WRITING, -1, -1, -1)
        try {
            writeFrame(socket, message)
            if (item.bounded && !state.compareAndSet(0, 1))
                throw java.net.SocketTimeoutException("Terminal write timed out")
            if (item.bounded && !item.finished.compareAndSet(false, true))
                throw java.net.SocketTimeoutException("Terminal transaction timed out")
        } catch (error: java.io.IOException) {
            observed(message, Boundary.FAILED, -1, -1, -1)
            throw error
        } catch (error: IllegalArgumentException) {
            observed(message, Boundary.FAILED, -1, -1, -1)
            throw error
        } finally { timeout?.cancel(false) }
        writingSince = 0
        observed(message, Boundary.WRITTEN, -1, -1, -1)
        written(message)
    }

    private fun loop() {
        var inFlight: Message? = null
        try {
            while (true) {
                val queued = queue.poll(100, TimeUnit.MILLISECONDS)
                var isTelemetry = false
                var selectedTransaction: TerminalTransaction? = null
                val message = queued ?: synchronized(this) {
                    queue.poll() ?: if (transaction != null) {
                        selectedTransaction = transaction
                        transaction = null
                        null
                    } else telemetry.also {
                        isTelemetry = it != null
                        telemetry = null
                    }
                }
                if (selectedTransaction != null) {
                    writeTransaction(checkNotNull(selectedTransaction))
                    continue
                }
                if (message == null) {
                    if (!accepting.get()) break
                    continue
                }
                inFlight = message
                if (isTelemetry) {
                    val admitted = synchronized(this) {
                        if (terminal != null) false else {
                            telemetryInFlight.set(message)
                            true
                        }
                    }
                    if (!admitted) {
                        observed(message, Boundary.CANCELLED, -1, -1, -1)
                        inFlight = null
                        continue
                    }
                }
                if (!isTelemetry) {
                    synchronized(this) {
                        queuedBytes -= 40 + message.payload.size; queuedPackets--
                        admittedAt.remove(message)
                        observed(message, Boundary.WRITING, queuedPackets, queuedBytes, admittedAt.values.minOrNull() ?: -1)
                    }
                } else observed(message, Boundary.WRITING, -1, -1, -1)
                writingSince = clock()
                writeFrame(socket, message)
                if (isTelemetry && !telemetryInFlight.compareAndSet(message, null))
                    throw java.net.SocketTimeoutException("Telemetry write exceeded terminal deadline")
                writingSince = 0
                observed(message, Boundary.WRITTEN, -1, -1, -1)
                written(message)
                inFlight = null
            }
        } catch (e: java.io.IOException) {
            inFlight?.let { observed(it, Boundary.FAILED, -1, -1, -1) }
            telemetryInFlight.set(null)
            accepting.set(false)
            synchronized(this) { transaction.also { transaction = null } }?.let {
                it.cancelWatchdog()
                it.finalResult(false)
            }
            failed("Control write failed (${e.javaClass.simpleName})")
        } catch (_: InterruptedException) {
            if (accepting.get()) failed("Control writer interrupted unexpectedly")
        } catch (_: IllegalArgumentException) {
            inFlight?.let { observed(it, Boundary.FAILED, -1, -1, -1) }
            telemetryInFlight.set(null)
            accepting.set(false)
            synchronized(this) { transaction.also { transaction = null } }?.let {
                it.cancelWatchdog()
                it.finalResult(false)
            }
            failed("Internal control frame validation failed")
        }
    }

    fun stalled(now: Long): Boolean = writingSince != 0L && now - writingSince >= 10_000

    override fun close() {
        accepting.set(false)
        synchronized(this) {
            telemetry?.let { observed(it, Boundary.CANCELLED, -1, -1, -1) }
            telemetry = null
        }
        if (thread !== Thread.currentThread()) {
            thread.join(1500)
            if (thread.isAlive) {
                socket.close()
                thread.interrupt()
                thread.join(500)
            }
            check(!thread.isAlive) { "Control writer did not terminate after socket close" }
        }
        val abandoned = mutableListOf<Message>()
        synchronized(this) {
            queue.drainTo(abandoned)
            admittedAt.clear()
            queuedBytes = 0
            queuedPackets = 0
        }
        abandoned.forEach { observed(it, Boundary.CANCELLED, 0, 0, -1) }
    }
}

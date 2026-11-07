package org.screencopy.receiver.media

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class TrackStoppedException : IllegalStateException("Track decoder is stopped")

abstract class TrackWorker(
    name: String,
    private val maxBytes: Int,
    private val failure: (String, String) -> Unit,
    private val track: Int = 0
) : AutoCloseable {
    private val queue = ArrayDeque<AccessUnit>()
    private val admittedAt = ArrayDeque<Long>()
    @Volatile internal var statisticsEnabled = false
    private val monitor = Object()
    private val budget = PacketBudget(maxBytes, 250_000)
    protected val running = AtomicBoolean(true)
    private val thread = Thread({ runTrack() }, name)

    fun start() = thread.start()

    internal fun queuedPackets(): Int = synchronized(monitor) { queue.size }
    internal fun queueStatistics(): EncodedQueueStatistics = synchronized(monitor) {
        EncodedQueueStatistics(queue.size, queue.sumOf { it.bytes.size },
            if (queue.isEmpty()) 0 else queue.maxOf { it.ptsUs } - queue.minOf { it.ptsUs },
            admittedAt.firstOrNull()?.let { (System.nanoTime() - it).coerceAtLeast(0) / 1_000_000 }, maxBytes)
    }

    fun tryOffer(packet: AccessUnit): QueuePressure? = synchronized(monitor) {
        if (!running.get()) throw TrackStoppedException()
        val pressure = budget.pressure(packet, track, 48)
        if (pressure == null) {
            budget.add(packet)
            queue.addLast(packet)
            if (statisticsEnabled) admittedAt.addLast(System.nanoTime())
            monitor.notifyAll()
        }
        pressure
    }

    fun offer(packet: AccessUnit) {
        val started = System.nanoTime()
        val deadline = started + TimeUnit.MILLISECONDS.toNanos(250)
        synchronized(monitor) {
            while (true) {
                if (!running.get()) throw TrackStoppedException()
                val pressure = budget.pressure(packet, track, 48, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                if (pressure == null) {
                    budget.add(packet)
                    queue.addLast(packet)
                    if (statisticsEnabled) admittedAt.addLast(System.nanoTime())
                    monitor.notifyAll()
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
            monitor.notifyAll()
        }
    }

    private fun runTrack() {
        try {
            while (running.get()) step()
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
                synchronized(monitor) { queue.clear(); admittedAt.clear(); budget.clear(); monitor.notifyAll() }
            }
        }
    }
}

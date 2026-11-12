package org.screencopy.receiver.net

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Cooldown never occupies a worker; native operations are serialized only within their track. */
internal class TrackRecoveryScheduler(
    private val nowMs: () -> Long,
    private val cooldownMs: Long = 1000,
    private val executors: List<ScheduledExecutorService> = List(2) {
        ScheduledThreadPoolExecutor(1).apply {
            removeOnCancelPolicy = true
            executeExistingDelayedTasksAfterShutdownPolicy = false
        }
    }
) {
    private class Pending(val current: () -> Boolean, val reset: () -> Unit) {
        var future: ScheduledFuture<*>? = null
    }
    private val lock = Any()
    private val pending = arrayOfNulls<Pending>(2)
    private val lastReset = arrayOfNulls<Long>(2)
    private var stopped = false

    fun schedule(track: Int, current: () -> Boolean, reset: () -> Unit) = synchronized(lock) {
        require(track in 1..2)
        if (stopped) return@synchronized
        val index = track - 1
        pending[index]?.future?.cancel(false)
        Pending(current, reset).also {
            pending[index] = it
            enqueue(index, it)
        }
        Unit
    }

    private fun enqueue(index: Int, task: Pending) {
        val delay = lastReset[index]?.let { (cooldownMs - (nowMs() - it)).coerceAtLeast(0) } ?: 0
        task.future = executors[index].schedule({
            val reset = synchronized(lock) {
                if (stopped || pending[index] !== task) return@schedule
                if (!task.current()) {
                    pending[index] = null
                    return@schedule
                }
                // A preceding native operation may have started after this task was submitted.
                if (lastReset[index]?.let { nowMs() - it < cooldownMs } == true) {
                    enqueue(index, task)
                    return@schedule
                }
                pending[index] = null
                lastReset[index] = nowMs()
                task.reset
            }
            reset()
        }, delay, TimeUnit.MILLISECONDS)
    }

    fun shutdown() {
        synchronized(lock) {
            stopped = true
            pending.forEach { it?.future?.cancel(false) }
            pending.fill(null)
        }
        executors.forEach { it.shutdown() }
    }

    fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean {
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        return executors.all { it.awaitTermination((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS) }
    }
}

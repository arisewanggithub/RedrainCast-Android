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
    },
    private val observed: (Int, Int, State, Long) -> Unit = { _, _, _, _ -> }
) {
    enum class State { QUEUED, RUNNING, CANCELLED }
    private class Pending(val generation: Int, val current: () -> Boolean, val reset: () -> Unit) {
        var future: ScheduledFuture<*>? = null
    }
    private val lock = Any()
    private val pending = arrayOfNulls<Pending>(2)
    private val lastReset = arrayOfNulls<Long>(2)
    private var stopped = false

    fun schedule(track: Int, current: () -> Boolean, reset: () -> Unit) = schedule(track, 0, current, reset)

    fun schedule(track: Int, generation: Int, current: () -> Boolean, reset: () -> Unit) = synchronized(lock) {
        require(track in 1..2)
        if (stopped) {
            observed(track, generation, State.CANCELLED, 0)
            return@synchronized
        }
        val index = track - 1
        pending[index]?.let {
            it.future?.cancel(false)
            observed(track, it.generation, State.CANCELLED, 0)
        }
        Pending(generation, current, reset).also {
            pending[index] = it
            enqueue(index, it)
        }
        Unit
    }

    private fun enqueue(index: Int, task: Pending) {
        val delay = lastReset[index]?.let { (cooldownMs - (nowMs() - it)).coerceAtLeast(0) } ?: 0
        observed(index + 1, task.generation, State.QUEUED, delay)
        task.future = executors[index].schedule({
            val reset = synchronized(lock) {
                if (stopped || pending[index] !== task) return@schedule
                if (!task.current()) {
                    pending[index] = null
                    observed(index + 1, task.generation, State.CANCELLED, 0)
                    return@schedule
                }
                // A preceding native operation may have started after this task was submitted.
                if (lastReset[index]?.let { nowMs() - it < cooldownMs } == true) {
                    enqueue(index, task)
                    return@schedule
                }
                pending[index] = null
                lastReset[index] = nowMs()
                observed(index + 1, task.generation, State.RUNNING, 0)
                task.reset
            }
            reset()
        }, delay, TimeUnit.MILLISECONDS)
    }

    fun shutdown() {
        synchronized(lock) {
            stopped = true
            pending.forEachIndexed { index, task -> task?.let {
                it.future?.cancel(false)
                observed(index + 1, it.generation, State.CANCELLED, 0)
            } }
            pending.fill(null)
        }
        executors.forEach { it.shutdown() }
    }

    fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean {
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        return executors.all { it.awaitTermination((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS) }
    }
}

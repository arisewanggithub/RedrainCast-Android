package org.screencopy.receiver.net

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Delayed
import java.util.concurrent.FutureTask
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

class TrackRecoverySchedulerTest {
    @Test fun cooldownLeavesTheOtherTrackRunnableAndDoesNotRepeatAReset() {
        var now = 0L
        val lanes = List(2) { ManualExecutor() }
        val scheduler = TrackRecoveryScheduler({ now }, executors = lanes)
        val resets = ArrayList<Int>()
        try {
            scheduler.schedule(1, { true }) { resets.add(1) }
            lanes[0].runNext(0)
            now = 20
            scheduler.schedule(1, { true }) { resets.add(1) }
            scheduler.schedule(2, { true }) { resets.add(2) }
            lanes[1].runNext(0)
            assertEquals(listOf(1, 2), resets)
            now = 1000
            lanes[0].runNext(980)
            assertEquals(listOf(1, 2, 1), resets)
            assertTrue(lanes.all { it.tasks.isEmpty() })
        } finally { scheduler.shutdown() }
        assertTrue(scheduler.awaitTermination(1, TimeUnit.SECONDS))
    }

    @Test fun supersededAndRetiredOwnersCannotResetOrConsumeCooldown() {
        var now = 5000L
        val lanes = List(2) { ManualExecutor() }
        val scheduler = TrackRecoveryScheduler({ now }, executors = lanes)
        val resets = ArrayList<Int>()
        try {
            scheduler.schedule(2, { true }) { resets.add(1) }
            scheduler.schedule(2, { true }) { resets.add(2) }
            lanes[1].runNext(0)
            assertTrue(resets.isEmpty())
            lanes[1].runNext(0)
            assertEquals(listOf(2), resets)
            now = 5100
            var current = true
            scheduler.schedule(2, { current }) { resets.add(3) }
            current = false
            now = 6000
            lanes[1].runNext(900)
            scheduler.schedule(2, { true }) { resets.add(4) }
            lanes[1].runNext(0)
            assertEquals(listOf(2, 4), resets)
            assertTrue(lanes.all { it.tasks.isEmpty() })
        } finally { scheduler.shutdown() }
    }

    @Test fun shutdownCancelsDelayedResetAndDeclinesLateWork() {
        var now = 0L
        val lanes = List(2) { ManualExecutor() }
        val scheduler = TrackRecoveryScheduler({ now }, executors = lanes)
        var resets = 0
        scheduler.schedule(1, { true }) { resets++ }
        lanes[0].runNext(0)
        now = 50
        scheduler.schedule(1, { true }) { resets++ }
        scheduler.shutdown()
        lanes[0].runNext(950)
        scheduler.schedule(2, { true }) { resets++ }
        assertEquals(1, resets)
        assertTrue(lanes.all { it.tasks.isEmpty() })
        assertTrue(scheduler.awaitTermination(1, TimeUnit.SECONDS))
    }

    private class ManualExecutor : ScheduledThreadPoolExecutor(1) {
        class Task(command: Runnable, val delayMs: Long) : FutureTask<Unit>(command, Unit), ScheduledFuture<Unit> {
            override fun getDelay(unit: TimeUnit): Long = unit.convert(delayMs, TimeUnit.MILLISECONDS)
            override fun compareTo(other: Delayed): Int = getDelay(TimeUnit.NANOSECONDS).compareTo(other.getDelay(TimeUnit.NANOSECONDS))
        }
        val tasks = ArrayDeque<Task>()
        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
            Task(command, unit.toMillis(delay)).also(tasks::addLast)
        fun runNext(delayMs: Long) {
            val task = tasks.removeFirst()
            assertEquals(delayMs, task.delayMs)
            task.run()
            if (!task.isCancelled) task.get()
        }
    }
}

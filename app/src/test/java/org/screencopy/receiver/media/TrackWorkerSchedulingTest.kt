package org.screencopy.receiver.media

import dev.redrain.cast.MediaThreadDiagnostic
import dev.redrain.cast.MediaThreadRole
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class TrackWorkerSchedulingTest {
    @Test fun setupRunsOnceOnEachWorkerBeforeServicingAndRetirementStillReleases() {
        val creator = Thread.currentThread()
        val workers = mutableSetOf<Thread>()
        repeat(2) {
            val calls = AtomicInteger()
            val setupThread = AtomicReference<Thread>()
            val serviced = CountDownLatch(1)
            val blocked = CountDownLatch(1)
            val released = AtomicInteger()
            val errors = ConcurrentLinkedQueue<String>()
            val worker = object : TrackWorker("scheduled-track-$it", 1024,
                { code, detail -> errors.add("$code: $detail") }, prepareThread = {
                    calls.incrementAndGet()
                    setupThread.set(Thread.currentThread())
                }) {
                override fun step() {
                    assertEquals(1, calls.get())
                    assertSame(setupThread.get(), Thread.currentThread())
                    serviced.countDown()
                    blocked.await()
                }
                override fun release() { released.incrementAndGet() }
            }
            assertEquals(0, calls.get())
            try {
                worker.start()
                assertTrue(serviced.await(2, TimeUnit.SECONDS))
            } finally { worker.close() }
            assertNotSame(creator, setupThread.get())
            assertTrue(workers.add(setupThread.get()))
            assertEquals(1, calls.get())
            assertEquals(1, released.get())
            assertTrue(errors.toString(), errors.isEmpty())
        }
    }

    @Test fun unexpectedSetupFailureUsesExistingFailureChannelAndDoesNotService() {
        val failed = CountDownLatch(1)
        val errors = ConcurrentLinkedQueue<String>()
        val steps = AtomicInteger()
        val releases = AtomicInteger()
        val worker = object : TrackWorker("failed-scheduling", 1024,
            { code, detail -> errors.add("$code: $detail"); failed.countDown() },
            prepareThread = { throw IllegalArgumentException("invalid scheduling request") }) {
            override fun step() { steps.incrementAndGet() }
            override fun release() { releases.incrementAndGet() }
        }
        try {
            worker.start()
            assertTrue(failed.await(2, TimeUnit.SECONDS))
        } finally { worker.close() }
        assertEquals(0, steps.get())
        assertEquals(1, releases.get())
        assertEquals(listOf("DECODER_FAILED: Playback failed: invalid scheduling request"), errors.toList())
    }

    @Test fun priorityReadbackDenialWarnsButDoesNotPreventMediaServicing() {
        val warnings = ConcurrentLinkedQueue<String>()
        val errors = ConcurrentLinkedQueue<String>()
        val serviced = CountDownLatch(1)
        val blocked = CountDownLatch(1)
        val worker = object : TrackWorker("denied-scheduling", 1024,
            { code, detail -> errors.add("$code: $detail") }, prepareThread = {
                MediaThreadDiagnostic.report(MediaThreadRole.AUDIO,
                    { throw SecurityException() }, {}, warnings::add)
            }) {
            override fun step() { serviced.countDown(); blocked.await() }
            override fun release() {}
        }
        try {
            worker.start()
            assertTrue(serviced.await(2, TimeUnit.SECONDS))
        } finally { worker.close() }
        assertEquals(1, warnings.size)
        assertTrue(errors.toString(), errors.isEmpty())
    }

    @Test fun closingAnUnstartedWorkerDoesNotApplyPriorityOnTheClosingThread() {
        val setups = AtomicInteger()
        val releases = AtomicInteger()
        val worker = object : TrackWorker("unstarted-scheduling", 1024,
            { _, _ -> fail("Worker was not started") }, prepareThread = { setups.incrementAndGet() }) {
            override fun step() { fail("Worker was not started") }
            override fun release() { releases.incrementAndGet() }
        }
        worker.close()
        assertEquals(0, setups.get())
        assertEquals(1, releases.get())
    }
}

package org.screencopy.receiver.media

import dev.redrain.cast.MediaThreadDiagnostic
import dev.redrain.cast.MediaThreadRole
import dev.redrain.cast.diagnostics.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class TrackWorkerSchedulingTest {
    @Test fun queueAdmitThenDequeuePublishesEmptyDuringIdleAndFinal() {
        val published = CountDownLatch(1)
        val periodic = AtomicReference<ByteArray>()
        val telemetry = ReceiverTelemetrySession({ bytes -> periodic.set(bytes); published.countDown(); true })
        TelemetryRegistry.beginReceiver(135, telemetry)
        val worker = object : TrackWorker("telemetry-queue", 1024, { _, _ -> fail("not started") }, 2) {
            fun dequeue() = next()
            override fun step() {}
            override fun release() {}
        }.also { it.diagnosticSession = 135; it.diagnosticGeneration = 1 }
        fun assertEmpty(batch: SessionTelemetryProtocol.Batch) {
            val queue = batch.state.single { it.kind == TelemetryRow.EVIDENCE_KIND &&
                it.stage == IncidentEvidence.Stage.QUEUE_STATE.ordinal && it.track == 2 }
            assertEquals(listOf(0L, 0L, 0L, -1L), queue.detail)
            val metrics = batch.state.single { it.kind == DiagnosticKind.entries.size + 1 && it.track == 2 }
            assertEquals(0L, metrics.detail[0])
        }
        try {
            worker.offer(AccessUnit(10_000, ByteArray(100)))
            assertNotNull(worker.dequeue())
            assertTrue(published.await(3, TimeUnit.SECONDS))
            assertEmpty(SessionTelemetryProtocol.parse(periodic.get()))
            worker.close()
            TelemetryRegistry.end("receiver", telemetry)
            assertEmpty(SessionTelemetryProtocol.parse(checkNotNull(
                telemetry.requestFinal(true, System.nanoTime() + 1_000_000_000L)).payload))
        } finally {
            worker.close()
            TelemetryRegistry.end("receiver", telemetry)
            telemetry.requestFinal(false, System.nanoTime() + 1_000_000_000L)
        }
    }

    @Test fun busyWorkersDoNotCrowdOutReadyMilestones() {
        val telemetry = ReceiverTelemetrySession({ true })
        TelemetryRegistry.beginReceiver(134, telemetry)
        val busy = CountDownLatch(2)
        val blocked = CountDownLatch(1)
        val workers = (1..2).map { track ->
            val loops = AtomicInteger()
            object : TrackWorker("telemetry-storm-$track", 1024, { _, _ -> fail("worker failure") }, track) {
                override fun step() {
                    if (loops.incrementAndGet() == 512) {
                        busy.countDown()
                        blocked.await()
                    }
                }
                override fun release() {}
            }.also { it.diagnosticSession = 134; it.diagnosticGeneration = 1 }
        }
        try {
            workers.forEach { it.start() }
            assertTrue(busy.await(2, TimeUnit.SECONDS))
            val ready = org.screencopy.receiver.protocol.Message.json(
                org.screencopy.receiver.protocol.Type.STREAM_READY, org.json.JSONObject(), 134, 1, 1)
            org.screencopy.receiver.net.ControlWriter.observeReadyBoundary(
                ready, org.screencopy.receiver.net.ControlWriter.Boundary.WRITTEN)
            workers.forEach { it.close() }
            TelemetryRegistry.end("receiver", telemetry)
            val batch = SessionTelemetryProtocol.parse(checkNotNull(
                telemetry.requestFinal(true, System.nanoTime() + 1_000_000_000L)).payload)
            assertEquals(0L, batch.dropped)
            assertTrue(batch.events.any { it.kind == TelemetryRow.EVIDENCE_KIND &&
                it.stage == IncidentEvidence.Stage.READY_WRITTEN.ordinal })
            assertEquals(setOf(1, 2), batch.state.filter { it.kind == TelemetryRow.EVIDENCE_KIND &&
                it.stage == IncidentEvidence.Stage.WORKER_END.ordinal }.map { it.track }.toSet())
        } finally {
            blocked.countDown()
            workers.forEach { it.close() }
            TelemetryRegistry.end("receiver", telemetry)
            telemetry.requestFinal(false, System.nanoTime() + 1_000_000_000L)
        }
    }

    @Test fun automaticQueueSamplesBothTracksWithoutStatisticsOrLiveDiagnostics() {
        RemoteDiagnostics.recorder.revoke()
        val telemetry = ReceiverTelemetrySession({ true })
        TelemetryRegistry.beginReceiver(133, telemetry)
        val workers = (1..2).map { track ->
            object : TrackWorker("telemetry-$track", 1024, { _, _ -> fail("not started") }, track) {
                override fun step() {}
                override fun release() {}
            }.also { it.diagnosticSession = 133; it.diagnosticGeneration = 1 }
        }
        try {
            workers.forEachIndexed { index, worker -> worker.offer(AccessUnit(10_000L + index, ByteArray(100))) }
            workers.forEach { it.close() }
            TelemetryRegistry.end("receiver", telemetry)
            val final = checkNotNull(telemetry.requestFinal(true, System.nanoTime() + 1_000_000_000L))
            val state = SessionTelemetryProtocol.parse(final.payload).state
            val rows = state.filter {
                it.kind == TelemetryRow.EVIDENCE_KIND &&
                    it.stage == IncidentEvidence.Stage.QUEUE_STATE.ordinal
            }
            assertEquals(setOf(1, 2), rows.map { it.track }.toSet())
            rows.forEach {
                assertEquals(listOf(0L, 0L, 0L, -1L), it.detail)
            }
            val metrics = state.filter {
                it.kind == DiagnosticKind.entries.size + 1 && it.stage == DiagnosticStage.SAMPLE.ordinal
            }
            assertEquals(setOf(1, 2), metrics.map { it.track }.toSet())
            metrics.forEach {
                assertEquals(listOf(0L, 48L, 0L, 1024L, -1L), it.detail)
            }
            assertFalse(RemoteDiagnostics.recorder.collecting)
        } finally {
            workers.forEach { it.close() }
            TelemetryRegistry.end("receiver", telemetry)
            telemetry.requestFinal(false, System.nanoTime() + 1_000_000_000L)
        }
    }

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

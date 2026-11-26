package dev.redrain.cast.diagnostics

import org.junit.Assert.*
import org.junit.Test
import org.screencopy.receiver.protocol.ProtocolException
import org.screencopy.receiver.protocol.StrictJson
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class SessionTelemetrySessionTest {
    private class ManualWorker : ScheduledThreadPoolExecutor(1, { action ->
        Thread(action, "telemetry-test").apply { isDaemon = true }
    }) {
        private lateinit var periodic: Runnable

        override fun scheduleWithFixedDelay(
            command: Runnable, initialDelay: Long, delay: Long, unit: TimeUnit
        ): ScheduledFuture<*> {
            periodic = command
            return super.scheduleWithFixedDelay(command, 1, 1, TimeUnit.DAYS)
        }

        fun tick() {
            submit(periodic).get(2, TimeUnit.SECONDS)
        }
    }

    private fun row(value: Long) = TelemetryRow(1, DiagnosticStage.PRESSURE.ordinal, 1, 1, 1, value, listOf(0, 0, 0, 0, 1))
    private fun failure(value: Long) = TelemetryRow(1, DiagnosticStage.FAILED.ordinal, 1, 1, 1, value, listOf(0, 0, 0, 0, 1))
    private fun await(latch: CountDownLatch) = assertTrue(latch.await(2, TimeUnit.SECONDS))
    private val direct = Executor { it.run() }

    @Test fun registryPreservesVideoAndAudioTransportSamplesInSameInterval() {
        val finished = CountDownLatch(1)
        val writes = ArrayList<ByteArray>()
        val sender = SenderTelemetrySession(false, { writes.add(it) }, storageExecutor = direct,
            onFinished = finished::countDown)
        TelemetryRegistry.beginSender(811, sender)
        try {
            TelemetryRegistry.video("sender", 811, 1, DiagnosticVideoStage.QUEUE_REMOVED, 100, 10)
            TelemetryRegistry.observe("sender", 811, DiagnosticKind.AUDIO_TRANSPORT, DiagnosticStage.COMPLETE,
                2, 1, 7, 200, 20, -1, DiagnosticReason.NONE, DiagnosticPoint.WRITE)
            TelemetryRegistry.event("sender", 811, DiagnosticKind.RECOVERY.ordinal + 1,
                DiagnosticStage.PRESSURE.ordinal, 1, 1, detail = listOf(0, 0, 0, 0, 1), pressure = true)
            assertTrue(sender.finish())
            await(finished)
            val blocks = StrictJson.decode(writes.last()).getJSONObject("sender").getJSONArray("blocks")
            val batch = SessionTelemetryProtocol.parse(blocks.getJSONArray(blocks.length() - 1)
                .getJSONObject(1).toString().toByteArray())
            assertTrue(batch.state.any { it.kind == 0 &&
                it.stage == DiagnosticVideoStage.QUEUE_REMOVED.ordinal && it.value == 100L })
            assertTrue(batch.state.any { it.kind == DiagnosticKind.AUDIO_TRANSPORT.ordinal + 1 &&
                it.track == 2 && it.value == 200L && it.detail[3] == DiagnosticPoint.WRITE.ordinal.toLong() })
        } finally {
            TelemetryRegistry.end("sender", sender)
            sender.finish()
        }
    }

    @Test fun receiverPublishesBoundedAndAccountsUnsentBeforeFinal() {
        val now = AtomicLong(1)
        val worker = ManualWorker()
        val offered = ArrayList<ByteArray>()
        val receiver = ReceiverTelemetrySession({ bytes ->
            offered.add(bytes)
            offered.size != 1
        }, clock = now::get, worker = worker)
        receiver.state(0, row(5))
        repeat(128) { receiver.event(row(it.toLong())) }
        receiver.event(row(999), pressure = true)
        worker.tick()
        receiver.publicationUnsent()
        worker.tick()
        assertEquals(2, offered.size)
        assertTrue(offered.all { it.size <= SessionTelemetryProtocol.MAX_BATCH_BYTES })
        val periodic = SessionTelemetryProtocol.parse(offered.last())
        assertEquals(2, periodic.unsent)
        assertEquals(999, periodic.firstPressure!!.value)
        assertTrue(periodic.dropped > 0)
        val final = receiver.requestFinal(false, 1_000_000_001)!!
        assertEquals(3, final.sequence)
        assertFalse(final.cleanupSucceeded)
        assertTrue(SessionTelemetryProtocol.parse(final.payload).final)
        assertNull(receiver.requestFinal(true, 1_000_000_001))
        receiver.event(failure(1000), failure = true)
        assertNull(SessionTelemetryProtocol.parse(final.payload).firstFailure)
    }

    @Test fun receiverSkipsUnstartedFinalWhenPreparationDeadlineExpires() {
        val worker = ManualWorker()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val receiver = ReceiverTelemetrySession({ fail("unexpected telemetry"); true }, worker = worker)
        worker.execute {
            entered.countDown()
            release.await()
        }
        try {
            await(entered)
            assertNull(receiver.requestFinal(true, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(20)))
        } finally {
            release.countDown()
            worker.shutdown()
        }
    }

    @Test fun normalAndUnsupportedCastWritesNothingAndRejectsLateInput() {
        val worker = ManualWorker()
        val finished = CountDownLatch(1)
        val writes = ArrayList<ByteArray>()
        val sender = SenderTelemetrySession(false, { writes.add(it) }, clock = { 1 }, worker = worker,
            storageExecutor = direct, onFinished = finished::countDown)
        assertFalse(sender.acceptPeer(SessionTelemetryCollector { 1 }.publish()))
        sender.state(0, row(4))
        worker.tick()
        assertTrue(sender.finish())
        await(finished)
        assertTrue(writes.isEmpty())
        assertFalse(sender.finish())
        assertFalse(sender.terminal(null, true))
    }

    @Test fun stagedOverflowPreservesCumulativeCauseAndFinalWithExplicitGap() {
        val worker = ManualWorker()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val writes = ArrayList<ByteArray>()
        val sender = SenderTelemetrySession(true, { writes.add(it) }, clock = { 1 }, worker = worker,
            storageExecutor = direct, onFinished = finished::countDown)
        worker.execute { entered.countDown(); release.await() }
        try {
            await(entered)
            val peer = SessionTelemetryCollector { 1 }
            peer.event(row(42), pressure = true)
            assertTrue(sender.acceptPeer(peer.publish()))
            assertTrue(sender.acceptPeer(peer.publish()))
            assertTrue(sender.acceptPeer(peer.publish(final = true)))
            assertEquals(1, sender.peerStagingDropped())
            assertFalse(sender.acceptPeer(peer.publish()))
            assertTrue(sender.terminal(3, true))
            assertTrue(sender.finish())
        } finally {
            release.countDown()
        }
        await(finished)
        assertEquals(1, writes.size)
        assertTrue(writes.single().size <= TelemetryReport.MAX_BYTES)
        val report = StrictJson.decode(writes.single())
        assertTrue(report.getBoolean("final"))
        assertTrue(report.getBoolean("receiverComplete"))
        val receiver = report.getJSONObject("receiver")
        assertEquals("1", receiver.getString("missingPublications"))
        val blocks = receiver.getJSONArray("blocks")
        assertEquals("42", blocks.getJSONArray(0).getJSONObject(1)
            .getJSONArray("firstPressure").getString(5))
    }

    @Test fun revisionsCloseWindowAndCleanupFailureIsIncomplete() {
        val now = AtomicLong(1)
        val worker = ManualWorker()
        val finished = CountDownLatch(1)
        val writes = ArrayList<ByteArray>()
        val sender = SenderTelemetrySession(true, { writes.add(it) }, clock = now::get, worker = worker,
            storageExecutor = direct, onFinished = finished::countDown)
        sender.event(row(7), pressure = true)
        worker.tick()
        now.set(10_000_000_001L)
        worker.tick()
        assertEquals(2, writes.size)
        assertFalse(StrictJson.decode(writes[0]).getBoolean("windowClosed"))
        assertTrue(StrictJson.decode(writes[1]).getBoolean("windowClosed"))
        val peer = SessionTelemetryCollector(now::get)
        assertTrue(sender.acceptPeer(peer.publish(final = true)))
        assertTrue(sender.terminal(1, false))
        assertTrue(sender.finish())
        await(finished)
        assertEquals(3, writes.size)
        val report = StrictJson.decode(writes.last())
        assertTrue(report.getBoolean("final"))
        assertFalse(report.getBoolean("receiverComplete"))
        assertFalse(report.getJSONObject("receiver").getBoolean("cleanupSucceeded"))
    }

    @Test fun cleanupFailureAloneTriggersReportAndInvalidPeerIsRejected() {
        val worker = ManualWorker()
        val finished = CountDownLatch(1)
        val writes = ArrayList<ByteArray>()
        val sender = SenderTelemetrySession(true, { writes.add(it) }, clock = { 1 }, worker = worker,
            storageExecutor = direct, onFinished = finished::countDown)
        assertThrows(ProtocolException::class.java) { sender.acceptPeer(ByteArray(8193)) }
        assertThrows(ProtocolException::class.java) {
            sender.acceptPeer(byteArrayOf(0xc3.toByte(), 0x28))
        }
        assertTrue(sender.terminal(null, false))
        assertTrue(sender.finish())
        await(finished)
        assertTrue(writes.size in 1..2)
        val report = StrictJson.decode(writes.last())
        assertTrue(report.getBoolean("final"))
        assertFalse(report.getBoolean("receiverComplete"))
    }

    @Test fun terminalFailureOutcomeIsRetainedWithoutAnEarlierTrigger() {
        val worker = ManualWorker()
        val finished = CountDownLatch(1)
        val writes = ArrayList<ByteArray>()
        val sender = SenderTelemetrySession(true, { writes.add(it) }, clock = { 1 }, worker = worker,
            storageExecutor = direct, onFinished = finished::countDown)
        assertTrue(sender.terminal(null, true, outcome = failure(77), failure = true))
        assertTrue(sender.finish())
        await(finished)
        val report = StrictJson.decode(writes.last())
        val blocks = report.getJSONObject("sender").getJSONArray("blocks")
        val batch = blocks.getJSONArray(blocks.length() - 1).getJSONObject(1)
        assertEquals("77", batch.getJSONArray("firstFailure").getString(5))
        assertFalse(report.getBoolean("receiverComplete"))
    }
}

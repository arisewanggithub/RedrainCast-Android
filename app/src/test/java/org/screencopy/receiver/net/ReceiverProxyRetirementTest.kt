package org.screencopy.receiver.net

import dev.redrain.cast.diagnostics.DiagnosticArmRegistry
import dev.redrain.cast.diagnostics.DiagnosticRecorder
import dev.redrain.cast.diagnostics.DiagnosticDeliveryBudget
import dev.redrain.cast.diagnostics.DiagnosticKind
import dev.redrain.cast.diagnostics.DiagnosticPoint
import dev.redrain.cast.diagnostics.DiagnosticStage
import dev.redrain.cast.diagnostics.DiagnosticTerminalFailure
import dev.redrain.cast.diagnostics.IncidentEvidence
import dev.redrain.cast.diagnostics.RemoteDiagnostics
import dev.screencopy.sender.DiagnosticProxyClient
import dev.screencopy.sender.ReceiverLink
import dev.screencopy.sender.ReceiverTarget
import dev.screencopy.sender.Settings
import dev.screencopy.sender.Wire
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import org.mockito.Mockito
import org.screencopy.receiver.media.VideoPlayer
import org.screencopy.receiver.protocol.Type
import org.screencopy.receiver.ui.ReceiverScreenEvent
import org.screencopy.receiver.ui.FailureOrigin
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class ReceiverProxyRetirementTest {
    private val session = 77L

    @Test(timeout = 15_000) fun operationFailuresCannotConsumeTerminalFailureReservation() {
        val source = DiagnosticRecorder()
        val registry = DiagnosticArmRegistry(DiagnosticRecorder().apply { grantConsent() })
        registry.start("collector", setOf("session"),
            setOf("session", "audio.output", "tail.details"), 10_000, 100)
        val claim = checkNotNull(registry.claim(session))
        val fetchEntered = CountDownLatch(1)
        val releaseFetch = CountDownLatch(1)
        val timerBlocked = CountDownLatch(1)
        val releaseTimer = CountDownLatch(1)
        val terminal = CountDownLatch(1)
        val timer = Executors.newSingleThreadScheduledExecutor()
        val retirementNow = AtomicLong(System.nanoTime())
        val deliveryBudget = AtomicReference<DiagnosticDeliveryBudget>()
        try {
            ReceiverServer.forProtocolTests("Receiver", proxyRecorder = source,
                proxyRetirementNow = retirementNow::get,
                proxyBudgetFactory = {
                    DiagnosticDeliveryBudget().also { deliveryBudget.set(it) }
                }, proxyBeforeFetch = {
                    fetchEntered.countDown()
                    check(releaseFetch.await(5, TimeUnit.SECONDS))
                }).use { server ->
                server.start()
                val target = ReceiverTarget("127.0.0.1", server.listeningPort)
                ReceiverLink(target).use { link ->
                    link.connectArmed(5000, claim) { registry.remainingMs(claim) }
                    val request = Settings().apply { audio = false }.request()
                    link.selectDiagnosticProxy(request, claim)
                    link.send(Wire.control(Wire.SESSION_REQUEST, session, request))
                    val accepted = link.receive()
                    val client = DiagnosticProxyClient(target, registry, claim, timer,
                        onFailure = {}, onTerminal = { terminal.countDown() })
                    try {
                        client.start(link.acceptedDiagnosticProxy(accepted.json(), claim),
                            System.nanoTime() + 5_000_000_000L) { registry.remainingMs(claim) }
                        assertTrue(fetchEntered.await(3, TimeUnit.SECONDS))
                        repeat(2) {
                            val operation = source.operation("receiver", session, DiagnosticKind.AUDIO_OUTPUT)
                            assertTrue(operation > 0)
                            source.observe("receiver", session, DiagnosticKind.AUDIO_OUTPUT, DiagnosticStage.FAILED,
                                track = 2, generation = 1, operation = operation, point = DiagnosticPoint.WRITE)
                        }
                        source.observe("receiver", session, DiagnosticKind.SESSION, DiagnosticStage.FAILED,
                            metadata = DiagnosticTerminalFailure(1, null))
                        val sourcePage = source.fetch(checkNotNull(source.captureIdentity), 0, 32)
                        val sourceRecords = sourcePage.getJSONArray("records")
                        val failures = (0 until sourceRecords.length()).map { sourceRecords.getJSONObject(it) }
                            .filter { it.getJSONObject("data").optString("stage") == "failed" }
                        assertEquals(listOf("AUDIO_OUTPUT", "AUDIO_OUTPUT", "SESSION"),
                            failures.map { it.getString("kind") })
                        val operationSequences = failures.take(2).map { it.getString("seq").toLong() }
                        val failureSequence = failures.last().getString("seq").toLong()

                        val field = ReceiverServer::class.java.getDeclaredField("timer").apply { isAccessible = true }
                        (field.get(server) as ScheduledExecutorService).execute {
                            timerBlocked.countDown()
                            releaseTimer.await()
                        }
                        assertTrue(timerBlocked.await(3, TimeUnit.SECONDS))
                        link.attach(session, accepted, false)
                        link.send(Wire.control(Wire.STOP, session, Wire.obj("reason" to "test")))
                        assertEquals(Wire.SESSION_END, receiveControl(link).type)
                        val budget = checkNotNull(deliveryBudget.get())
                        val now = System.nanoTime()
                        repeat(252) { assertTrue(budget.tryAcquire(now, 1, 4)) }
                        releaseFetch.countDown()
                        assertTrue(terminal.await(3, TimeUnit.SECONDS))
                        val page = registry.receiverFetch(claim, 0, 32)
                        val outcome = page.getJSONObject("terminal")
                        assertTrue(outcome.getBoolean("tailTruncated"))
                        val details = outcome.getJSONObject("tailDetails")
                        assertEquals("budget", details.getString("cause"))
                        assertEquals("delivery", details.getString("stage"))
                        val records = page.getJSONArray("records")
                        val delivered = (0 until records.length()).map { records.getJSONObject(it).getJSONObject("record") }
                        val sequences = delivered.map { it.getString("seq").toLong() }
                        assertTrue(sequences.zipWithNext().all { (left, right) -> left < right })
                        val failure = delivered.single { it.getString("seq").toLong() == failureSequence }
                        assertEquals("terminal_failure", failure.getJSONObject("data")
                            .getJSONObject("metrics").getString("schema"))
                        val omittedOperations = operationSequences.filterNot { it in sequences }
                        assertEquals(2, omittedOperations.size)
                        assertTrue(details.getString("unsentFirstSeq").toLong() <= omittedOperations.first())
                        assertTrue(details.getString("unsentLastSeq").toLong() >= omittedOperations.last())
                        assertTrue(details.getString("observedUnsentCount").toLong() >= omittedOperations.size)
                    } finally {
                        releaseFetch.countDown()
                        releaseTimer.countDown()
                        client.cancel("test_done")
                    }
                }
            }
        } finally {
            releaseFetch.countDown()
            releaseTimer.countDown()
            timer.shutdownNow()
            registry.revoke()
        }
    }

    @Test(timeout = 15_000) fun boundedTailProtectsFailureAfterRoutineBudgetExhaustion() {
        val source = DiagnosticRecorder()
        val registry = DiagnosticArmRegistry(DiagnosticRecorder().apply { grantConsent() })
        registry.start("collector", setOf("session"),
            setOf("session", "audio.output", "tail.details"), 10_000, 100)
        val claim = checkNotNull(registry.claim(session))
        val fetchEntered = CountDownLatch(1)
        val releaseFetch = CountDownLatch(1)
        val terminal = CountDownLatch(1)
        val timer = Executors.newSingleThreadScheduledExecutor()
        val retirementNow = AtomicLong(System.nanoTime())
        try {
            ReceiverServer.forProtocolTests("Receiver", proxyRecorder = source,
                proxyRetirementNow = retirementNow::get,
                proxyBudgetFactory = {
                    DiagnosticDeliveryBudget().apply {
                        val now = System.nanoTime()
                        repeat(254) { assertTrue(tryAcquire(now, 1, 4)) }
                    }
                }, proxyBeforeFetch = {
                    fetchEntered.countDown()
                    check(releaseFetch.await(5, TimeUnit.SECONDS))
                }).use { server ->
                server.start()
                val target = ReceiverTarget("127.0.0.1", server.listeningPort)
                ReceiverLink(target).use { link ->
                    link.connectArmed(5000, claim) { registry.remainingMs(claim) }
                    val request = Settings().apply { audio = false }.request()
                    link.selectDiagnosticProxy(request, claim)
                    link.send(Wire.control(Wire.SESSION_REQUEST, session, request))
                    val accepted = link.receive()
                    val client = DiagnosticProxyClient(target, registry, claim, timer,
                        onFailure = {}, onTerminal = { terminal.countDown() })
                    try {
                        client.start(link.acceptedDiagnosticProxy(accepted.json(), claim),
                            System.nanoTime() + 5_000_000_000L) { registry.remainingMs(claim) }
                        assertTrue(fetchEntered.await(3, TimeUnit.SECONDS))
                        repeat(4) {
                            source.observe("receiver", session, DiagnosticKind.AUDIO_OUTPUT, DiagnosticStage.SAMPLE,
                                metadata = DiagnosticTerminalFailure(it.toLong(), null))
                        }
                        source.observe("receiver", session, DiagnosticKind.SESSION, DiagnosticStage.FAILED,
                            metadata = DiagnosticTerminalFailure(1, null))
                        link.attach(session, accepted, false)
                        link.send(Wire.control(Wire.STOP, session, Wire.obj("reason" to "test")))
                        releaseFetch.countDown()
                        assertTrue(terminal.await(3, TimeUnit.SECONDS))
                        val page = registry.receiverFetch(claim, 0, 32)
                        val details = page.getJSONObject("terminal").getJSONObject("tailDetails")
                        assertEquals("budget", details.getString("cause"))
                        assertEquals("delivery", details.getString("stage"))
                        assertTrue(details.getString("observedUnsentCount").toLong() > 0)
                        val records = page.getJSONArray("records")
                        val delivered = (0 until records.length()).map { records.getJSONObject(it).getJSONObject("record") }
                        val failure = delivered.single { it.getJSONObject("data").optString("stage") == "failed" }
                        val sequences = delivered.map { it.getString("seq").toLong() }
                        assertTrue(sequences.zipWithNext().all { (left, right) -> left < right })
                        assertTrue(details.getString("unsentFirstSeq").toLong() < failure.getString("seq").toLong())
                        val fixture = java.io.File(java.io.File("build"), "diagnostics-contract")
                        fixture.mkdirs()
                        java.io.File(fixture, "receiver-priority-tail-fetch.json").writeText(
                            registry.fetch("collector", claim.pairId, 0, 0, 32).toString())
                    } finally { releaseFetch.countDown(); client.cancel("test_done") }
                }
            }
        } finally { releaseFetch.countDown(); timer.shutdownNow(); registry.revoke() }
    }

    @Test(timeout = 15_000) fun receiverStopPreservesConfirmedRetirementAfterSessionEnd() {
        runReceiverStop(telemetry = true)
    }

    @Test(timeout = 15_000) fun legacyReceiverStopPublishesMediaRetirementBeforeReleasingSessionEnd() {
        runReceiverStop(telemetry = false)
    }

    @Test(timeout = 15_000) fun crossedStopPreservesReceiverRetirement() {
        runReceiverStop(telemetry = true, crossed = true)
    }

    @Test(timeout = 15_000) fun expiredReceiverRetirementDoesNotHoldControlOrOwnership() {
        runReceiverStop(telemetry = true, expire = true)
    }

    @Test(timeout = 15_000) fun failedStopWriteClosesProxyWithoutOrdinaryRetirementGrace() {
        runReceiverStop(telemetry = true, failStopWrite = true)
    }

    @Test(timeout = 15_000) fun failedMediaCleanupStillConfirmsOrdinaryRetirementWithinDeadline() {
        runReceiverStop(telemetry = true, failMediaCleanup = true)
    }

    @Test(timeout = 15_000) fun unblockedStopConfirmsCompleteProxyTerminalWithinDeadline() {
        val registry = DiagnosticArmRegistry(DiagnosticRecorder().apply { grantConsent() })
        registry.start("collector", setOf("session"), setOf("session", "tail.details"), 10_000, 100)
        val claim = checkNotNull(registry.claim(session))
        val confirmed = CountDownLatch(1)
        val terminalWritten = CountDownLatch(1)
        val writtenAtNs = AtomicLong()
        val timer = Executors.newSingleThreadScheduledExecutor()
        try {
            ReceiverServer.forProtocolTests("Receiver", proxyRecorder = DiagnosticRecorder(),
                proxyTerminalWritten = {
                    writtenAtNs.set(it)
                    terminalWritten.countDown()
                }).use { server ->
                server.start()
                val target = ReceiverTarget("127.0.0.1", server.listeningPort)
                ReceiverLink(target).use { link ->
                    link.connectArmed(5000, claim) { registry.remainingMs(claim) }
                    val request = Settings().apply { audio = false }.request()
                    link.selectDiagnosticProxy(request, claim)
                    link.send(Wire.control(Wire.SESSION_REQUEST, session, request))
                    val accepted = link.receive()
                    val client = DiagnosticProxyClient(target, registry, claim, timer,
                        onFailure = {}, onTerminal = { confirmed.countDown() })
                    try {
                        client.start(link.acceptedDiagnosticProxy(accepted.json(), claim),
                            System.nanoTime() + 5_000_000_000L) { registry.remainingMs(claim) }
                        link.attach(session, accepted, false)
                        link.send(Wire.control(Wire.STOP, session, Wire.obj("reason" to "test")))
                        assertTrue("unblocked retirement must deliver ENDED", confirmed.await(3, TimeUnit.SECONDS))
                        assertTrue(terminalWritten.await(3, TimeUnit.SECONDS))
                        assertEquals(Wire.SESSION_END, receiveControl(link).type)
                        val outcome = registry.status("collector", claim.pairId)
                            .getJSONObject("receiverRole").getJSONObject("outcome")
                        assertTrue(outcome.getBoolean("confirmed"))
                        assertEquals("receiver_retired_stop", outcome.getString("reason"))
                        assertFalse(outcome.getBoolean("tailTruncated"))
                        val tailDetails = outcome.getJSONObject("tailDetails")
                        assertTrue(tailDetails.isNull("cause"))
                        assertEquals("0", tailDetails.getString("observedUnsentCount"))
                        val end = outcome.getJSONObject("captureEnd")
                        assertTrue(end.getBoolean("complete"))
                        assertFalse(end.getBoolean("truncated"))
                        val elapsed = writtenAtNs.get() - end.getString("atNs").toLong()
                        assertTrue("terminal write completed outside retirement window: $elapsed ns",
                            elapsed in 0 until 100_000_000L)
                        val page = registry.receiverFetch(claim, 0, 32)
                        assertTrue(page.getBoolean("endConfirmed"))
                        assertFalse(page.getJSONObject("terminal").getBoolean("tailTruncated"))
                    } finally { client.cancel("test_done") }
                }
            }
        } finally { timer.shutdownNow(); registry.revoke() }
    }

    @Test(timeout = 15_000) fun fatalControlRetirementAllowsBoundedProxyTerminalFlush() = runFatal(false)

    @Test(timeout = 15_000) fun fatalControlRetirementPastDeadlineRemainsUnconfirmed() = runFatal(true)

    private fun runFatal(expire: Boolean) {
        val registry = DiagnosticArmRegistry(DiagnosticRecorder().apply { grantConsent() })
        registry.start("collector", setOf("session"), setOf("session"), 10_000, 100)
        val claim = checkNotNull(registry.claim(session))
        val proxyEntered = CountDownLatch(1)
        val releaseProxy = CountDownLatch(1)
        val timerBlocked = CountDownLatch(1)
        val releaseServerTimer = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val retirementNow = AtomicLong(System.nanoTime())
        val timer = Executors.newSingleThreadScheduledExecutor()
        try {
            ReceiverServer.forProtocolTests("Receiver", proxyRecorder = DiagnosticRecorder(),
                proxyBeforeRetirementFlush = {
                    proxyEntered.countDown()
                    check(releaseProxy.await(5, TimeUnit.SECONDS))
                }, proxyAfterFatalError = {
                    if (expire) retirementNow.addAndGet(100_000_000L)
                    releaseProxy.countDown()
                }, proxyRetirementNow = retirementNow::get).use { server ->
                server.start()
                val target = ReceiverTarget("127.0.0.1", server.listeningPort)
                ReceiverLink(target).use { link ->
                    link.connectArmed(5000, claim) { registry.remainingMs(claim) }
                    val request = Settings().apply { audio = false }.request()
                    link.selectSessionTelemetry(request)
                    link.selectDiagnosticProxy(request, claim)
                    link.send(Wire.control(Wire.SESSION_REQUEST, session, request))
                    val accepted = link.receive()
                    val client = DiagnosticProxyClient(target, registry, claim, timer,
                        onFailure = {}, onClosed = { closed.countDown() })
                    try {
                        client.start(link.acceptedDiagnosticProxy(accepted.json(), claim),
                            System.nanoTime() + 5_000_000_000L) { registry.remainingMs(claim) }
                        link.attach(session, accepted, false)
                        val field = ReceiverServer::class.java.getDeclaredField("timer").apply { isAccessible = true }
                        (field.get(server) as ScheduledExecutorService).execute {
                            timerBlocked.countDown()
                            releaseServerTimer.await()
                        }
                        assertTrue(timerBlocked.await(3, TimeUnit.SECONDS))
                        link.send(Wire.control(Wire.ERROR, session, Wire.obj("code" to "AUDIO_FAILED",
                            "message" to "sender failure", "fatal" to true)))
                        assertTrue(proxyEntered.await(3, TimeUnit.SECONDS))
                        var finalSequence: Long? = null
                        var terminal = link.receive()
                        while (terminal.type == Wire.STATUS) {
                            val batch = dev.redrain.cast.diagnostics.SessionTelemetryProtocol.parse(terminal.payload)
                            if (batch.final) finalSequence = batch.sequence
                            terminal = link.receive()
                        }
                        assertEquals(Wire.ERROR, terminal.type)
                        assertEquals("AUDIO_FAILED", terminal.json().getString("code"))
                        assertTrue(terminal.json().getBoolean("cleanupSucceeded"))
                        assertNotNull(finalSequence)
                        assertEquals(finalSequence.toString(), terminal.json().getString("finalSeq"))
                        assertTrue(closed.await(3, TimeUnit.SECONDS))
                        val page = registry.receiverFetch(claim, 0, 32)
                        assertEquals(!expire, page.getBoolean("endConfirmed"))
                        if (!expire)
                            assertEquals("receiver_retired_fatal", page.getJSONObject("terminal").getString("reason"))
                        else assertTrue(page.isNull("terminal"))
                    } finally {
                        releaseProxy.countDown()
                        releaseServerTimer.countDown()
                        client.cancel("test_done")
                    }
                }
            }
        } finally {
            releaseProxy.countDown()
            releaseServerTimer.countDown()
            timer.shutdownNow()
            registry.revoke()
        }
    }

    @Test(timeout = 15_000) fun shutdownDrainsQueuedPreLaneCaptureDiscard() {
        val recorder = DiagnosticRecorder()
        val registry = DiagnosticArmRegistry(DiagnosticRecorder().apply { grantConsent() })
        registry.start("collector", setOf("session"), setOf("session"), 10_000, 100)
        val claim = checkNotNull(registry.claim(session))
        val timerBlocked = CountDownLatch(1)
        val timerInterrupted = CountDownLatch(1)
        val releaseTimer = CountDownLatch(1)
        val ownershipReleased = CountDownLatch(1)
        val closer = Executors.newSingleThreadExecutor()
        val server = ReceiverServer.forProtocolTests(
            "Receiver", proxyRecorder = recorder,
            acquireSession = { AutoCloseable { ownershipReleased.countDown() } }
        )
        try {
            server.start()
            val field = ReceiverServer::class.java.getDeclaredField("timer").apply { isAccessible = true }
            val timer = field.get(server) as ScheduledExecutorService
            timer.execute {
                timerBlocked.countDown()
                try {
                    releaseTimer.await()
                } catch (_: InterruptedException) {
                    timerInterrupted.countDown()
                    releaseTimer.await()
                }
            }
            assertTrue(timerBlocked.await(3, TimeUnit.SECONDS))
            ReceiverLink(ReceiverTarget("127.0.0.1", server.listeningPort)).use { link ->
                link.connectArmed(5000, claim) { registry.remainingMs(claim) }
                val request = Settings().apply { audio = false }.request()
                link.selectDiagnosticProxy(request, claim)
                link.send(Wire.control(Wire.SESSION_REQUEST, session, request))
                assertEquals(Wire.SESSION_ACCEPT, link.receive().type)
                assertNotNull(recorder.captureIdentity)
            }
            assertTrue("control cleanup must queue discard before shutdown",
                ownershipReleased.await(3, TimeUnit.SECONDS))
            val close = closer.submit { server.close() }
            assertTrue(timerInterrupted.await(3, TimeUnit.SECONDS))
            releaseTimer.countDown()
            close.get(5, TimeUnit.SECONDS)
            val next = recorder.startAdmittedReceiver(session + 1, setOf("session"),
                System.nanoTime() + TimeUnit.SECONDS.toNanos(10), 100, AtomicBoolean(true))
            val capture = next.getString("captureId")
            recorder.finishAdmittedReceiver(capture, "test_done")
            recorder.discardAdmittedReceiver(capture)
        } finally {
            releaseTimer.countDown()
            closer.shutdownNow()
            server.close()
            registry.revoke()
        }
    }

    private fun runReceiverStop(
        telemetry: Boolean,
        crossed: Boolean = false,
        expire: Boolean = false,
        failStopWrite: Boolean = false,
        failMediaCleanup: Boolean = false
    ) {
        val registry = DiagnosticArmRegistry(DiagnosticRecorder().apply { grantConsent() })
        registry.start("collector", setOf("session"), setOf("session"), 10_000, 100)
        val claim = checkNotNull(registry.claim(session))
        val proxyEntered = CountDownLatch(1)
        val releaseProxy = CountDownLatch(1)
        val cleanupFence = CountDownLatch(1)
        val ownershipReleased = CountDownLatch(1)
        val cleanupFinished = CountDownLatch(1)
        val failedWrite = CountDownLatch(1)
        val terminal = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val cleanupSawHeldWriter = AtomicBoolean()
        val flushEnteredNs = AtomicLong()
        val cleanupFenceNs = AtomicLong()
        val retirementNow = AtomicLong(System.nanoTime())
        val terminalWrittenAt = AtomicLong()
        val timerBlocked = CountDownLatch(1)
        val releaseServerTimer = CountDownLatch(1)
        val controlledWindow = !expire && !failStopWrite
        val laneFailure = AtomicReference<String>()
        val timer = Executors.newSingleThreadScheduledExecutor()
        val stops = Executors.newSingleThreadExecutor()
        val player = if (failMediaCleanup) Mockito.mock(VideoPlayer::class.java).also {
            Mockito.doThrow(IllegalStateException("media cleanup failed")).`when`(it).close()
        } else null
        val source = if (failMediaCleanup) RemoteDiagnostics.recorder else DiagnosticRecorder()
        if (failMediaCleanup) source.revoke()
        try {
            ReceiverServer.forProtocolTests(
                "Receiver",
                proxyRecorder = source,
                proxyRetirementNow = if (controlledWindow) retirementNow::get else System::nanoTime,
                acquireSession = { AutoCloseable { ownershipReleased.countDown() } },
                videoFactory = if (player != null) { _, _, _, _ -> player } else null,
                proxyBeforeRetirementFlush = {
                    flushEnteredNs.set(System.nanoTime())
                    proxyEntered.countDown()
                    check(releaseProxy.await(5, TimeUnit.SECONDS))
                },
                proxyAfterOrdinaryCleanupFence = {
                    cleanupFenceNs.set(System.nanoTime())
                    cleanupSawHeldWriter.set(releaseProxy.count == 1L)
                    cleanupFence.countDown()
                    if (failMediaCleanup) retirementNow.addAndGet(50_000_000L)
                    if (!expire) releaseProxy.countDown()
                },
                proxyTerminalWritten = { terminalWrittenAt.set(retirementNow.get()) },
                onScreenEvent = {
                    if (it == ReceiverScreenEvent.CleanupFinished && ownershipReleased.count == 0L)
                        cleanupFinished.countDown()
                },
                controlWrite = { socket, message ->
                    if (failStopWrite && message.type == Type.STOP) {
                        check(proxyEntered.await(3, TimeUnit.SECONDS))
                        failedWrite.countDown()
                        throw IOException("STOP write failed")
                    }
                    org.screencopy.receiver.protocol.Wire.write(socket.outputStream, message)
                }
            ).use { server ->
                server.start()
                val target = ReceiverTarget("127.0.0.1", server.listeningPort)
                ReceiverLink(target).use { link ->
                    link.connectArmed(5000, claim) { registry.remainingMs(claim) }
                    val request = Settings().apply { audio = false }.request()
                    if (telemetry) link.selectSessionTelemetry(request)
                    link.selectDiagnosticProxy(request, claim)
                    link.send(Wire.control(Wire.SESSION_REQUEST, session, request))
                    val accepted = link.receive()
                    val client = DiagnosticProxyClient(
                        target, registry, claim, timer,
                        onFailure = { laneFailure.set(it.javaClass.simpleName) },
                        onTerminal = { terminal.countDown() },
                        onClosed = { closed.countDown() }
                    )
                    try {
                        client.start(link.acceptedDiagnosticProxy(accepted.json(), claim),
                            System.nanoTime() + 5_000_000_000L) { registry.remainingMs(claim) }
                        link.attach(session, accepted, false)
                        if (failMediaCleanup) {
                            link.send(Wire.config(session, 1, 1,
                                JSONObject().put("codec", "h264").put("width", 640).put("height", 360)
                                    .put("csd", JSONArray().put("AAAAAWdkACg="))))
                            assertEquals(Wire.STREAM_READY, receiveControl(link).type)
                        }
                        if (controlledWindow) {
                            val field = ReceiverServer::class.java.getDeclaredField("timer").apply { isAccessible = true }
                            (field.get(server) as ScheduledExecutorService).execute {
                                timerBlocked.countDown()
                                releaseServerTimer.await()
                            }
                            assertTrue(timerBlocked.await(3, TimeUnit.SECONDS))
                        }
                        val stop = stops.submit { server.disconnect("receiver_test") }
                        if (failStopWrite) {
                            assertTrue(failedWrite.await(3, TimeUnit.SECONDS))
                        } else {
                            val message = receiveControl(link)
                            assertEquals(Wire.STOP, message.type)
                            assertEquals("receiver_test", message.json().getString("reason"))
                            if (failMediaCleanup) assertFalse(message.json().getBoolean("cleanupSucceeded"))
                            assertTrue(proxyEntered.await(3, TimeUnit.SECONDS))
                            stop.get(3, TimeUnit.SECONDS)
                            assertEquals("disconnect must return while the proxy writer is held", 1L, releaseProxy.count)
                            assertEquals(1L, terminal.count)
                            claim.commitTerminal(System.nanoTime())
                            link.send(Wire.control(if (crossed) Wire.STOP else Wire.SESSION_END,
                                session, Wire.obj("reason" to "receiver_test")))
                        }
                        assertThrows(EOFException::class.java) { receiveControl(link) }
                        stop.get(3, TimeUnit.SECONDS)
                        if (failMediaCleanup) {
                            assertEquals("failed media retirement must retain ownership", 1L, ownershipReleased.count)
                            assertTrue(server.busyForUpdate)
                            Mockito.verify(checkNotNull(player)).close()
                        } else {
                            assertTrue("ownership must retire without a proxy flush",
                                ownershipReleased.await(3, TimeUnit.SECONDS))
                            assertTrue(cleanupFinished.await(3, TimeUnit.SECONDS))
                            assertFalse(server.busyForUpdate)
                        }
                        if (failStopWrite) {
                            assertEquals("failed STOP must not enable ordinary close grace", 1L, cleanupFence.count)
                        } else {
                            assertEquals(0L, cleanupFence.count)
                            assertTrue("control cleanup must reach its fence while the writer is held",
                                cleanupSawHeldWriter.get())
                        }
                        if (expire || failStopWrite) {
                            assertEquals("cleanup and ownership must not release the proxy writer", 1L, releaseProxy.count)
                            assertEquals(1L, terminal.count)
                        }
                        assertTrue("retired lane must close even if its writer remains held",
                            closed.await(3, TimeUnit.SECONDS))
                        val enteredNs = flushEnteredNs.get()
                        val fenceNs = cleanupFenceNs.get()
                        val heldMs = if (enteredNs > 0 && fenceNs >= enteredNs)
                            TimeUnit.NANOSECONDS.toMillis(fenceNs - enteredNs) else null
                        val confirmed = terminal.count == 0L
                        println("proxy held from flush entry to cleanup fence: ${heldMs}ms; " +
                            "confirmed=$confirmed; laneFailure=${laneFailure.get()}")
                        if (expire || failStopWrite) assertFalse(confirmed)
                        else assertTrue("terminal must be confirmed inside the controlled retirement window; " +
                            "held ${heldMs}ms; laneFailure=${laneFailure.get()}", confirmed)
                        val outcome = registry.status("collector", claim.pairId)
                            .getJSONObject("receiverRole").getJSONObject("outcome")
                        assertEquals(confirmed, outcome.getBoolean("confirmed"))
                        if (confirmed) {
                            assertEquals("receiver_retired_stop", outcome.getString("reason"))
                            assertEquals(if (failMediaCleanup) "failed" else "succeeded",
                                outcome.getString("mediaRetirement"))
                            assertFalse(outcome.getBoolean("tailTruncated"))
                            assertTrue(outcome.getJSONObject("captureEnd").getBoolean("complete"))
                            if (failMediaCleanup) {
                                val fence = outcome.getJSONObject("captureEnd").getString("atNs").toLong()
                                val elapsed = terminalWrittenAt.get() - fence
                                assertEquals("controlled writer release must occur halfway through the grace", 50_000_000L, elapsed)
                                assertTrue("terminal must be written before the 100ms deadline", elapsed in 0 until 100_000_000L)
                            }
                        } else {
                            assertTrue(outcome.isNull("reason"))
                            assertTrue(outcome.getBoolean("tailTruncated"))
                            val end = outcome.getJSONObject("captureEnd")
                            assertFalse(end.getBoolean("complete"))
                            assertTrue(end.getBoolean("truncated"))
                        }
                        val page = registry.fetch("collector", claim.pairId, 0, 0, 32)
                            .getJSONObject("receiver")
                        assertEquals(confirmed, page.getBoolean("endConfirmed"))
                        if (failMediaCleanup) {
                            val records = registry.receiverFetch(claim, 0, 32).getJSONArray("records")
                            val failures = (0 until records.length()).map { records.getJSONObject(it).getJSONObject("record") }
                                .filter { it.getString("kind") == "SESSION" && it.getJSONObject("data").getString("stage") == "failed" }
                            val failure = failures.single().getJSONObject("data").getJSONObject("metrics")
                            assertEquals("terminal_failure", failure.getString("schema"))
                            assertEquals(FailureOrigin.CLEANUP.ordinal, failure.getInt("origin"))
                            assertEquals(IncidentEvidence.cause("INTERNAL"), failure.getLong("cause"))
                        }
                    } finally {
                        releaseProxy.countDown()
                        releaseServerTimer.countDown()
                        client.cancel("test_done")
                    }

                }
            }
        } finally {
            releaseProxy.countDown()
            releaseServerTimer.countDown()
            stops.shutdownNow()
            timer.shutdownNow()
            registry.revoke()
            if (failMediaCleanup) source.revoke()
        }
    }

    private fun receiveControl(link: ReceiverLink): Wire.Message {
        var message = link.receive()
        while (message.type == Wire.STATUS) message = link.receive()
        return message
    }
}

package org.screencopy.receiver.net

import dev.redrain.cast.diagnostics.DiagnosticArmRegistry
import dev.redrain.cast.diagnostics.DiagnosticRecorder
import dev.screencopy.sender.DiagnosticProxyClient
import dev.screencopy.sender.ReceiverLink
import dev.screencopy.sender.ReceiverTarget
import dev.screencopy.sender.Settings
import dev.screencopy.sender.Wire
import org.junit.Assert.*
import org.junit.Test
import org.screencopy.receiver.protocol.Type
import org.screencopy.receiver.ui.ReceiverScreenEvent
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ReceiverProxyRetirementTest {
    private val session = 77L

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
        failStopWrite: Boolean = false
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
        val timer = Executors.newSingleThreadScheduledExecutor()
        val stops = Executors.newSingleThreadExecutor()
        try {
            ReceiverServer.forProtocolTests(
                "Receiver",
                proxyRecorder = DiagnosticRecorder(),
                acquireSession = { AutoCloseable { ownershipReleased.countDown() } },
                proxyBeforeRetirementFlush = {
                    proxyEntered.countDown()
                    check(releaseProxy.await(5, TimeUnit.SECONDS))
                },
                proxyAfterOrdinaryCleanupFence = {
                    cleanupSawHeldWriter.set(releaseProxy.count == 1L)
                    cleanupFence.countDown()
                    if (!expire) releaseProxy.countDown()
                },
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
                        onFailure = {},
                        onTerminal = { terminal.countDown() },
                        onClosed = { closed.countDown() }
                    )
                    try {
                        client.start(link.acceptedDiagnosticProxy(accepted.json(), claim),
                            System.nanoTime() + 5_000_000_000L) { registry.remainingMs(claim) }
                        link.attach(session, accepted, false)
                        val stop = stops.submit { server.disconnect("receiver_test") }
                        if (failStopWrite) {
                            assertTrue(failedWrite.await(3, TimeUnit.SECONDS))
                        } else {
                            val message = receiveControl(link)
                            assertEquals(Wire.STOP, message.type)
                            assertEquals("receiver_test", message.json().getString("reason"))
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
                        assertTrue("ownership must retire without a proxy flush",
                            ownershipReleased.await(3, TimeUnit.SECONDS))
                        assertTrue(cleanupFinished.await(3, TimeUnit.SECONDS))
                        assertFalse(server.busyForUpdate)
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
                        val confirmed = !expire && !failStopWrite
                        assertEquals(if (confirmed) 0L else 1L, terminal.count)
                        val outcome = registry.status("collector", claim.pairId)
                            .getJSONObject("receiverRole").getJSONObject("outcome")
                        assertEquals(confirmed, outcome.getBoolean("confirmed"))
                        if (confirmed) {
                            assertEquals("receiver_retired_stop", outcome.getString("reason"))
                            assertEquals("succeeded", outcome.getString("mediaRetirement"))
                        } else {
                            assertTrue(outcome.isNull("reason"))
                            assertTrue(outcome.getBoolean("tailTruncated"))
                        }
                        val page = registry.fetch("collector", claim.pairId, 0, 0, 32)
                            .getJSONObject("receiver")
                        assertEquals(confirmed, page.getBoolean("endConfirmed"))
                    } finally {
                        releaseProxy.countDown()
                        client.cancel("test_done")
                    }

                }
            }
        } finally {
            releaseProxy.countDown()
            stops.shutdownNow()
            timer.shutdownNow()
            registry.revoke()
        }
    }

    private fun receiveControl(link: ReceiverLink): Wire.Message {
        var message = link.receive()
        while (message.type == Wire.STATUS) message = link.receive()
        return message
    }
}

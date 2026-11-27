package dev.screencopy.sender

import dev.redrain.cast.diagnostics.DiagnosticArmRegistry
import dev.redrain.cast.diagnostics.DiagnosticKind
import dev.redrain.cast.diagnostics.DiagnosticProxyWire
import dev.redrain.cast.diagnostics.DiagnosticRecorder
import dev.redrain.cast.diagnostics.DiagnosticStage
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import org.screencopy.receiver.media.VideoPlayer
import org.screencopy.receiver.net.ReceiverServer
import java.lang.management.ManagementFactory
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class DiagnosticProxyIntegrationTest {
    private val session = 77L

    @Test(timeout = 15_000) fun controlTerminalBeforeProxyEndedStillConfirms() = senderRetirement(true)
    @Test(timeout = 15_000) fun senderRetirementDeadlineClosesUnconfirmedLane() = senderRetirement(false)

    private fun senderRetirement(sendEnded: Boolean) {
        val sender = DiagnosticRecorder().apply { grantConsent() }
        val receiver = DiagnosticRecorder()
        val registry = DiagnosticArmRegistry(sender)
        registry.start("collector", setOf("session"), setOf("session"), 10_000, 100)
        val claim = checkNotNull(registry.claim(session))
        val fetchEntered = CountDownLatch(1)
        val releaseFetch = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val retirement = AtomicReference<Runnable>()
        val retirementDelay = AtomicLong()
        val timer = Executors.newSingleThreadScheduledExecutor()
        val retirementTimer = Mockito.mock(ScheduledExecutorService::class.java)
        val future = Mockito.mock(ScheduledFuture::class.java)
        Mockito.doAnswer {
            retirement.set(it.getArgument(0))
            retirementDelay.set(it.getArgument(1))
            future
        }.`when`(retirementTimer).schedule(
            Mockito.any(Runnable::class.java), Mockito.anyLong(), Mockito.eq(TimeUnit.MILLISECONDS))
        try {
            ReceiverServer.forProtocolTests("Receiver", proxyRecorder = receiver, proxyBeforeFetch = {
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
                    val selection = link.acceptedDiagnosticProxy(accepted.json(), claim)
                    val client = DiagnosticProxyClient(target, registry, claim, timer,
                        retirementTimer = retirementTimer, onFailure = {}, onClosed = { closed.countDown() })
                    try {
                        client.start(selection, System.nanoTime() + 5_000_000_000L) { registry.remainingMs(claim) }
                        registry.beginMedia(claim)
                        assertTrue(fetchEntered.await(3, TimeUnit.SECONDS))
                        val owner = checkNotNull((ReceiverServer::class.java.getDeclaredField("active")
                            .apply { isAccessible = true }.get(server) as AtomicReference<*>).get())
                        val control = owner.javaClass.getDeclaredField("socket")
                            .apply { isAccessible = true }.get(owner) as Socket
                        val lane = checkNotNull(owner.javaClass.getDeclaredField("proxyLane")
                            .apply { isAccessible = true }.get(owner))
                        val peer = lane.javaClass.getDeclaredField("socket")
                            .apply { isAccessible = true }.get(lane) as Socket
                        Wire.write(control.outputStream, Wire.control(Wire.SESSION_END, session,
                            Wire.obj("reason" to "test")))
                        assertEquals(Wire.SESSION_END, link.receive().type)
                        claim.commitTerminal(System.nanoTime())
                        finishSenderDiagnostic(sender, registry, claim, session, false, { client.retire() }, { it() })
                        assertEquals("draining", registry.status("collector", claim.pairId).getString("state"))
                        assertEquals(1L, closed.count)
                        assertTrue(retirementDelay.get() in 100..1000)
                        val timeout = checkNotNull(retirement.get())
                        if (sendEnded) {
                            val reason = "receiver_retired_stop"
                            val ended = org.json.JSONObject().put("v", 2).put("type", "ENDED")
                                .put("pairId", claim.pairId).put("session", session.toString())
                                .put("armGeneration", claim.generation.toString()).put("captureId", selection.captureId)
                                .put("clockId", selection.clockId).put("reason", reason).put("endedNs", "1")
                                .put("mediaRetirement", "succeeded").put("ownershipCleanup", "unknown")
                                .put("tailTruncated", false)
                                .put("captureEnd", org.json.JSONObject().put("atNs", "1").put("reason", reason)
                                    .put("complete", true).put("truncated", false))
                                .put("sourceLoss", receiver.fetch(selection.captureId, 0, 32)
                                    .getJSONObject("lossWatermarks"))
                            DiagnosticProxyWire.writeFrame(peer.outputStream, ended)
                        } else timeout.run()
                        assertTrue(closed.await(3, TimeUnit.SECONDS))
                        val status = registry.status("collector", claim.pairId)
                        assertEquals("frozen", status.getString("state"))
                        val outcome = status.getJSONObject("receiverRole").getJSONObject("outcome")
                        assertEquals(sendEnded, outcome.getBoolean("confirmed"))
                        assertEquals(!sendEnded, outcome.getBoolean("tailTruncated"))
                        val page = registry.receiverFetch(claim, 0, 32)
                        assertEquals(sendEnded, page.getBoolean("endConfirmed"))
                        if (sendEnded) assertEquals("receiver_retired_stop",
                            page.getJSONObject("terminal").getString("reason"))
                        else assertTrue(page.isNull("terminal"))
                        Mockito.verify(future).cancel(false)
                    } finally {
                        client.cancel("test_done")
                        releaseFetch.countDown()
                    }
                }
            }
        } finally {
            releaseFetch.countDown()
            timer.shutdownNow()
            registry.revoke()
        }
    }

    @Test(timeout = 15_000) fun registryMonitorCannotPublishCoverageAfterSetupDeadline() {
        val sender = DiagnosticRecorder().apply { grantConsent() }
        val registry = DiagnosticArmRegistry(sender)
        val pair = registry.start("collector", setOf("session"), setOf("session"), 10_000, 100)
        val claim = checkNotNull(registry.claim(session))
        val entered = CountDownLatch(1)
        val commitRelease = CountDownLatch(1)
        val timerBlocked = CountDownLatch(1)
        val timerRelease = CountDownLatch(1)
        val deadlinePassed = CountDownLatch(1)
        val startupThread = AtomicReference<Thread>()
        val bootstrapPublished = AtomicBoolean()
        val mediaStarted = AtomicBoolean()
        val records = AtomicInteger()
        val timer = Executors.newSingleThreadScheduledExecutor()
        val deadlineObserver = Executors.newSingleThreadScheduledExecutor()
        val tasks = Executors.newSingleThreadExecutor()
        timer.execute {
            timerBlocked.countDown()
            check(timerRelease.await(10, TimeUnit.SECONDS))
        }
        try {
            assertTrue(timerBlocked.await(3, TimeUnit.SECONDS))
            ReceiverServer.forProtocolTests("Receiver", proxyRecorder = DiagnosticRecorder()).use { server ->
                server.start()
                val target = ReceiverTarget("127.0.0.1", server.listeningPort)
                ReceiverLink(target).use { link ->
                    link.connectArmed(5000, claim) { registry.remainingMs(claim) }
                    val request = Settings().apply { audio = false }.request()
                    link.selectDiagnosticProxy(request, claim)
                    link.send(Wire.control(Wire.SESSION_REQUEST, session, request))
                    val accepted = link.receive()
                    val client = DiagnosticProxyClient(target, registry, claim, timer,
                        onRecord = { records.incrementAndGet() }, onFailure = {},
                        afterBootstrapPublished = { bootstrapPublished.set(true) })
                    try {
                        val deadline = System.nanoTime() + 1_000_000_000L
                        deadlineObserver.schedule({ deadlinePassed.countDown() },
                            deadline - System.nanoTime(), TimeUnit.NANOSECONDS)
                        val started = tasks.submit {
                            startupThread.set(Thread.currentThread())
                            client.start(link.acceptedDiagnosticProxy(accepted.json(), claim), deadline,
                                commitReady = { commit ->
                                    entered.countDown()
                                    check(commitRelease.await(5, TimeUnit.SECONDS))
                                    commit()
                                    registry.beginMedia(claim)
                                    mediaStarted.set(true)
                                }) { registry.remainingMs(claim) }
                        }
                        assertTrue(entered.await(3, TimeUnit.SECONDS))
                        synchronized(registry) {
                            commitRelease.countDown()
                            assertTrue(deadlinePassed.await(3, TimeUnit.SECONDS))
                            val blocked = checkNotNull(ManagementFactory.getThreadMXBean()
                                .getThreadInfo(checkNotNull(startupThread.get()).id))
                            assertEquals(Thread.State.BLOCKED, blocked.threadState)
                            assertEquals(Thread.currentThread().id, blocked.lockOwnerId)
                            assertEquals(System.identityHashCode(registry), blocked.lockInfo.identityHashCode)
                        }
                        val failure = assertThrows(ExecutionException::class.java) { started.get(3, TimeUnit.SECONDS) }
                        assertTrue(failure.cause is DiagnosticProxySetupException)
                        assertTrue(failure.cause?.cause is java.net.SocketTimeoutException)
                        assertFalse(bootstrapPublished.get())
                        assertFalse(claim.bootstrapAccepted.get())
                        assertFalse(mediaStarted.get())
                        assertEquals(0, records.get())
                        val status = registry.status("collector", pair.getString("pairId"))
                        assertEquals("binding", status.getString("state"))
                        assertTrue(status.isNull("bootstrap"))
                        assertTrue(status.isNull("receiverCaptureId"))
                        assertThrows(dev.redrain.cast.diagnostics.DiagnosticError::class.java) { registry.beginMedia(claim) }
                    } finally { commitRelease.countDown(); client.cancel("test_done") }
                }
            }
        } finally {
            commitRelease.countDown()
            timerRelease.countDown()
            tasks.shutdownNow()
            timer.shutdownNow()
            deadlineObserver.shutdownNow()
            registry.revoke()
        }
    }

    @Test(timeout = 15_000) fun deadlineWinsInsideReadyPublicationEvenWhenTimerIsBlocked() {
        val sender = DiagnosticRecorder().apply { grantConsent() }
        val registry = DiagnosticArmRegistry(sender)
        val pair = registry.start("collector", setOf("session"), setOf("session"), 10_000, 100)
        val claim = checkNotNull(registry.claim(session))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val deadlinePassed = CountDownLatch(1)
        val timer = Executors.newScheduledThreadPool(2)
        val tasks = Executors.newSingleThreadExecutor()
        try {
            ReceiverServer.forProtocolTests("Receiver", proxyRecorder = DiagnosticRecorder()).use { server ->
                server.start()
                val target = ReceiverTarget("127.0.0.1", server.listeningPort)
                ReceiverLink(target).use { link ->
                    link.connectArmed(5000, claim) { registry.remainingMs(claim) }
                    val request = Settings().apply { audio = false }.request()
                    link.selectDiagnosticProxy(request, claim)
                    link.send(Wire.control(Wire.SESSION_REQUEST, session, request))
                    val accepted = link.receive()
                    val client = DiagnosticProxyClient(target, registry, claim, timer, onFailure = {})
                    try {
                        val deadline = System.nanoTime() + 1_000_000_000L
                        timer.schedule({ deadlinePassed.countDown() }, 1, TimeUnit.SECONDS)
                        val started = tasks.submit {
                            client.start(link.acceptedDiagnosticProxy(accepted.json(), claim), deadline,
                                commitReady = { commit ->
                                    entered.countDown()
                                    check(release.await(5, TimeUnit.SECONDS))
                                    commit()
                                    registry.beginMedia(claim)
                                }) { registry.remainingMs(claim) }
                        }
                        assertTrue(entered.await(3, TimeUnit.SECONDS))
                        assertTrue(deadlinePassed.await(3, TimeUnit.SECONDS))
                        release.countDown()
                        val failure = assertThrows(ExecutionException::class.java) { started.get(3, TimeUnit.SECONDS) }
                        assertTrue(failure.cause is DiagnosticProxySetupException)
                        assertTrue(failure.cause?.cause is java.net.SocketTimeoutException)
                        assertFalse(claim.bootstrapAccepted.get())
                        assertEquals("binding", registry.status("collector", pair.getString("pairId")).getString("state"))
                        assertThrows(dev.redrain.cast.diagnostics.DiagnosticError::class.java) { registry.beginMedia(claim) }
                    } finally { release.countDown(); client.cancel("test_done") }
                }
            }
        } finally { release.countDown(); tasks.shutdownNow(); timer.shutdownNow(); registry.revoke() }
    }

    @Test(timeout = 15_000) fun normalStopCleansUpWithoutWaitingForRetirementFlush() {
        runNormalStopRetirement(forceExpiry = false)
        runNormalStopRetirement(forceExpiry = true)
    }

    private fun runNormalStopRetirement(forceExpiry: Boolean) {
        val sender = DiagnosticRecorder().apply { grantConsent() }
        val registry = DiagnosticArmRegistry(sender)
        registry.start("collector", setOf("session"), setOf("session"), 10_000, 100)
        val claim = checkNotNull(registry.claim(session))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ownershipReleased = CountDownLatch(1)
        val cleanupEntered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val timer = Executors.newSingleThreadScheduledExecutor()
        try {
            ReceiverServer.forProtocolTests("Receiver", proxyRecorder = DiagnosticRecorder(),
                acquireSession = { AutoCloseable { ownershipReleased.countDown() } },
                proxyBeforeRetirementFlush = {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }, proxyAfterOrdinaryCleanupFence = { cleanupEntered.countDown() }).use { server ->
                server.start()
                val target = ReceiverTarget("127.0.0.1", server.listeningPort)
                ReceiverLink(target).use { link ->
                    link.connectArmed(5000, claim) { registry.remainingMs(claim) }
                    val request = Settings().apply { audio = false }.request()
                    link.selectDiagnosticProxy(request, claim)
                    link.send(Wire.control(Wire.SESSION_REQUEST, session, request))
                    val accepted = link.receive()
                    val client = DiagnosticProxyClient(target, registry, claim, timer,
                        onFailure = {}, onTerminal = { finished.countDown() }, onClosed = { closed.countDown() })
                    try {
                        client.start(link.acceptedDiagnosticProxy(accepted.json(), claim),
                            System.nanoTime() + 5_000_000_000L) { registry.remainingMs(claim) }
                        link.attach(session, accepted, false)
                        link.send(Wire.control(Wire.STOP, session, Wire.obj("reason" to "test")))
                        assertTrue(entered.await(3, TimeUnit.SECONDS))
                        assertTrue("control cleanup must not wait for the proxy writer",
                            cleanupEntered.await(3, TimeUnit.SECONDS))
                        if (forceExpiry) {
                            assertTrue("expired flush must close the lane", closed.await(3, TimeUnit.SECONDS))
                        } else {
                            release.countDown()
                            assertTrue(finished.await(3, TimeUnit.SECONDS))
                        }
                        assertEquals(Wire.SESSION_END, link.receive().type)
                        assertTrue(ownershipReleased.await(3, TimeUnit.SECONDS))
                        val outcome = registry.status("collector", claim.pairId)
                            .getJSONObject("receiverRole").getJSONObject("outcome")
                        assertEquals(!forceExpiry, outcome.getBoolean("confirmed"))
                        if (forceExpiry) {
                            assertTrue(outcome.isNull("reason"))
                            assertTrue(outcome.getBoolean("tailTruncated"))
                        } else {
                            assertEquals("receiver_retired_stop", outcome.getString("reason"))
                        }
                        val page = registry.fetch("collector", claim.pairId, 0, 0, 32).getJSONObject("receiver")
                        assertEquals(!forceExpiry, page.getBoolean("endConfirmed"))
                    } finally { release.countDown(); client.cancel("test_done") }
                }
            }
        } finally { release.countDown(); timer.shutdownNow(); registry.revoke() }
    }

    @Test(timeout = 15_000) fun laterStopCannotReclassifyAlreadyObservedLaneEof() {
        val sender = DiagnosticRecorder().apply { grantConsent() }
        val registry = DiagnosticArmRegistry(sender)
        val pair = registry.start("collector", setOf("session"), setOf("session"), 10_000, 100)
        val claim = checkNotNull(registry.claim(session))
        val observed = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val timer = Executors.newSingleThreadScheduledExecutor()
        try {
            ReceiverServer.forProtocolTests("Receiver", proxyRecorder = DiagnosticRecorder()).use { server ->
                server.start()
                val target = ReceiverTarget("127.0.0.1", server.listeningPort)
                ReceiverLink(target).use { link ->
                    link.connectArmed(5000, claim) { registry.remainingMs(claim) }
                    val request = Settings().apply { audio = false }.request()
                    link.selectDiagnosticProxy(request, claim)
                    link.send(Wire.control(Wire.SESSION_REQUEST, session, request))
                    val accepted = link.receive()
                    val selection = link.acceptedDiagnosticProxy(accepted.json(), claim)
                    val client = DiagnosticProxyClient(target, registry, claim, timer, onFailure = {
                        assertTrue(it is java.io.EOFException)
                        observed.countDown()
                        check(resume.await(5, TimeUnit.SECONDS))
                    }, onClosed = { closed.countDown() })
                    try {
                        client.start(selection, System.nanoTime() + 5_000_000_000L) { registry.remainingMs(claim) }
                        val owner = checkNotNull((ReceiverServer::class.java.getDeclaredField("active")
                            .apply { isAccessible = true }.get(server) as AtomicReference<*>).get())
                        val lane = checkNotNull(owner.javaClass.getDeclaredField("proxyLane")
                            .apply { isAccessible = true }.get(owner))
                        val peer = lane.javaClass.getDeclaredField("socket").apply { isAccessible = true }
                            .get(lane) as Socket
                        peer.shutdownOutput()
                        assertTrue(observed.await(3, TimeUnit.SECONDS))
                        claim.commitTerminal(System.nanoTime())
                        client.cancel("sender_finished")
                        resume.countDown()
                        assertTrue(closed.await(3, TimeUnit.SECONDS))
                        val status = registry.status("collector", pair.getString("pairId"))
                        assertEquals("frozen", status.getString("state"))
                        assertEquals("coverage_lost", status.getString("endReason"))
                        val outcome = status.getJSONObject("receiverRole").getJSONObject("outcome")
                        assertFalse(outcome.getBoolean("confirmed"))
                        assertTrue(outcome.getBoolean("tailTruncated"))
                        val page = registry.fetch("collector", claim.pairId, 0, 0, 32).getJSONObject("receiver")
                        assertFalse(page.getBoolean("endConfirmed"))
                        assertTrue(page.isNull("terminal"))
                    } finally { resume.countDown(); client.cancel("test_done") }
                }
            }
        } finally { resume.countDown(); timer.shutdownNow() }
    }

    @Test(timeout = 15_000) fun statusShowsFinishingWhileRealTerminalCutoffIsPending() {
        val sender = DiagnosticRecorder().apply { grantConsent() }
        val registry = DiagnosticArmRegistry(sender)
        registry.start("collector", setOf("session"), setOf("session"), 10_000, 100)
        val claim = checkNotNull(registry.claim(session))
        val reserved = CountDownLatch(1)
        val consume = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val controlRelease = CountDownLatch(1)
        val timer = Executors.newSingleThreadScheduledExecutor()
        try {
            ReceiverServer.forProtocolTests("Receiver", proxyRecorder = DiagnosticRecorder(),
                proxyAfterRetirement = { check(controlRelease.await(5, TimeUnit.SECONDS)) }).use { server ->
                server.start()
                val target = ReceiverTarget("127.0.0.1", server.listeningPort)
                ReceiverLink(target).use { link ->
                    link.connectArmed(5000, claim) { registry.remainingMs(claim) }
                    val request = Settings().apply { audio = false }.request()
                    link.selectDiagnosticProxy(request, claim)
                    link.send(Wire.control(Wire.SESSION_REQUEST, session, request))
                    val accepted = link.receive()
                    val client = DiagnosticProxyClient(target, registry, claim, timer,
                        onFailure = {}, onTerminal = { finished.countDown() },
                        afterTerminalReserved = {
                            reserved.countDown()
                            check(consume.await(5, TimeUnit.SECONDS))
                        })
                    try {
                        client.start(link.acceptedDiagnosticProxy(accepted.json(), claim),
                            System.nanoTime() + 5_000_000_000L) { registry.remainingMs(claim) }
                        link.attach(session, accepted, false)
                        link.send(Wire.control(Wire.STOP, session, Wire.obj("reason" to "test")))
                        assertTrue(reserved.await(3, TimeUnit.SECONDS))
                        val role = registry.status("collector", claim.pairId).getJSONObject("receiverRole")
                        assertEquals("finishing", role.getString("state"))
                        assertTrue(role.isNull("outcome"))
                        assertEquals("finishing", registry.receiverFetch(claim, 0, 32).getString("state"))
                        consume.countDown()
                        assertTrue(finished.await(3, TimeUnit.SECONDS))
                        val finalRole = registry.status("collector", claim.pairId).getJSONObject("receiverRole")
                        assertEquals("finished", finalRole.getString("state"))
                        assertEquals("receiver_retired_stop", finalRole.getJSONObject("outcome").getString("reason"))
                        assertTrue(registry.receiverFetch(claim, 0, 32).getBoolean("endConfirmed"))
                    } finally { consume.countDown(); controlRelease.countDown(); client.cancel("test_done") }
                }
            }
        } finally { consume.countDown(); controlRelease.countDown(); timer.shutdownNow() }
    }

    @Test(timeout = 15_000) fun revokedBeforeReadyCannotPublishIntoReplacementArm() {
        val sender = DiagnosticRecorder().apply { grantConsent() }
        val registry = DiagnosticArmRegistry(sender)
        val old = registry.start("collector", setOf("session"), setOf("session"), 10_000, 100)
        val claim = checkNotNull(registry.claim(session))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val timer = Executors.newSingleThreadScheduledExecutor()
        val executor = Executors.newSingleThreadExecutor()
        try {
            ReceiverServer.forProtocolTests("Receiver", proxyRecorder = DiagnosticRecorder()).use { server ->
                server.start()
                val target = ReceiverTarget("127.0.0.1", server.listeningPort)
                ReceiverLink(target).use { link ->
                    link.connectArmed(5000, claim) { registry.remainingMs(claim) }
                    val request = Settings().apply { audio = false }.request()
                    link.selectDiagnosticProxy(request, claim)
                    link.send(Wire.control(Wire.SESSION_REQUEST, session, request))
                    val accepted = link.receive()
                    val client = DiagnosticProxyClient(target, registry, claim, timer,
                        beforeReadyCommit = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) })
                    try {
                        val startup = executor.submit {
                            client.start(link.acceptedDiagnosticProxy(accepted.json(), claim),
                                System.nanoTime() + 5_000_000_000L) { registry.remainingMs(claim) }
                        }
                        assertTrue(entered.await(3, TimeUnit.SECONDS))
                        registry.revoke()
                        sender.grantConsent()
                        val replacement = registry.start("collector", setOf("session"), setOf("session"), 10_000, 100)
                        val next = checkNotNull(registry.claim(session + 1))
                        release.countDown()
                        assertThrows(ExecutionException::class.java) { startup.get(3, TimeUnit.SECONDS) }
                        assertFalse(claim.valid.get())
                        assertFalse(claim.bootstrapAccepted.get())
                        assertThrows(dev.redrain.cast.diagnostics.DiagnosticError::class.java) {
                            registry.beginMedia(claim)
                        }
                        val state = registry.status("collector", replacement.getString("pairId"))
                        assertEquals("binding", state.getString("state"))
                        assertEquals("${session + 1}", state.getString("session"))
                        assertTrue(state.isNull("bootstrap"))
                        assertTrue(state.isNull("receiverCaptureId"))
                        assertNotEquals(old.getString("pairId"), next.pairId)
                        assertTrue(next.valid.get())
                    } finally { release.countDown(); client.cancel("test_done") }
                }
            }
        } finally { release.countDown(); executor.shutdownNow(); timer.shutdownNow(); registry.revoke() }
    }

    @Test(timeout = 30_000) fun realListenerKeeps32OrdinaryCreditsWithProxyInEitherMediaOrder() {
        for (order in listOf(listOf("video", "audio"), listOf("audio", "video"))) {
            val sender = DiagnosticRecorder().apply { grantConsent() }
            val registry = DiagnosticArmRegistry(sender)
            registry.start("collector", setOf("session"), setOf("session"), 20_000, 100)
            val claim = checkNotNull(registry.claim(session))
            val timer = Executors.newSingleThreadScheduledExecutor()
            val idle = ArrayList<Socket>()
            val media = ArrayList<Socket>()
            try {
                ReceiverServer.forProtocolTests("Receiver", proxyRecorder = DiagnosticRecorder()).use { server ->
                    server.start()
                    val pool = ReceiverServer::class.java.getDeclaredField("clients").apply { isAccessible = true }
                        .get(server) as org.screencopy.receiver.net.ConnectionPool<*>
                    try {
                        repeat(29) {
                            val socket = Socket(InetAddress.getLoopbackAddress(), server.listeningPort)
                                .apply { soTimeout = 3000 }
                            idle.add(socket)
                            Wire.write(socket.outputStream, Wire.control(Wire.HELLO, 0,
                                Wire.obj("role" to "sender", "versions" to org.json.JSONArray().put(3),
                                    "name" to "idle")))
                            assertEquals(Wire.HELLO, Wire.read(socket.inputStream).type)
                        }
                        val target = ReceiverTarget("127.0.0.1", server.listeningPort)
                        ReceiverLink(target).use { link ->
                            link.connectArmed(5000, claim) { registry.remainingMs(claim) }
                            assertEquals(30, pool.ordinaryCount())
                            val request = Settings().apply { audio = false }.request()
                            link.selectDiagnosticProxy(request, claim)
                            link.send(Wire.control(Wire.SESSION_REQUEST, session, request))
                            val accepted = link.receive()
                            val client = DiagnosticProxyClient(target, registry, claim, timer, onFailure = {})
                            try {
                                client.start(link.acceptedDiagnosticProxy(accepted.json(), claim),
                                    System.nanoTime() + 5_000_000_000L) { registry.remainingMs(claim) }
                                assertEquals(30, pool.ordinaryCount())
                                val token = accepted.json().getString("mediaToken")
                                order.forEach { channel ->
                                    media.add(org.screencopy.receiver.net.MediaSockets.connect(
                                        server, session, token, channel))
                                }
                                assertEquals(32, pool.ordinaryCount())
                                Socket(InetAddress.getLoopbackAddress(), server.listeningPort).use { extra ->
                                    extra.soTimeout = 3000
                                    assertThrows(java.io.IOException::class.java) { Wire.read(extra.inputStream) }
                                }
                                link.send(Wire.control(Wire.PING, session, Wire.obj("nonce" to 19)))
                                assertEquals(Wire.PONG, link.receive().type)
                                assertEquals(32, pool.ordinaryCount())
                                claim.commitTerminal(System.nanoTime())
                                link.send(Wire.control(Wire.STOP, session, Wire.obj("reason" to "test")))
                                assertEquals(Wire.SESSION_END, link.receive().type)
                            } finally { client.cancel("test_done") }
                        }
                    } finally {
                        media.forEach { it.close() }
                        idle.forEach { it.close() }
                    }
                }
            } finally { timer.shutdownNow(); registry.revoke() }
        }
    }

    @Test(timeout = 45_000) fun retiredRealReaderDoesNotBlockSaturatedUnarmedReplacement() {
        for (order in listOf(listOf("video", "audio"), listOf("audio", "video"))) {
            val sender = DiagnosticRecorder().apply { grantConsent() }
            val registry = DiagnosticArmRegistry(sender)
            registry.start("collector", setOf("session"), setOf("session"), 30_000, 100)
            val claim = checkNotNull(registry.claim(session))
            val readerEntered = CountDownLatch(1)
            val releaseReader = CountDownLatch(1)
            val oldOwnerReleased = CountDownLatch(1)
            val nextOwnerReleased = CountDownLatch(1)
            val armedLaneRejected = CountDownLatch(1)
            val availability = AtomicInteger()
            val readerCalls = AtomicInteger()
            val timer = Executors.newSingleThreadScheduledExecutor()
            val idle = ArrayList<Socket>()
            val media = ArrayList<Socket>()
            val server = ReceiverServer.forProtocolTests("Receiver", proxyRecorder = DiagnosticRecorder(),
                proxyBeforeRead = {
                    if (readerCalls.incrementAndGet() == 1) {
                        readerEntered.countDown()
                        check(releaseReader.await(20, TimeUnit.SECONDS))
                    }
                },
                onScreenEvent = {
                    if (it == org.screencopy.receiver.ui.ReceiverScreenEvent.CleanupFinished) {
                        when (availability.incrementAndGet()) {
                            2 -> oldOwnerReleased.countDown()
                            3 -> nextOwnerReleased.countDown()
                        }
                    }
                }, onDiscoveryDiagnostic = {
                    if (it == "event=DIAGNOSTIC_LANE_FAILED exception=ProtocolException")
                        armedLaneRejected.countDown()
                })
            @Suppress("UNCHECKED_CAST")
            val pool = ReceiverServer::class.java.getDeclaredField("clients").apply { isAccessible = true }
                .get(server) as org.screencopy.receiver.net.ConnectionPool<Any>
            val diagnosticField = pool.javaClass.getDeclaredField("diagnostic").apply { isAccessible = true }
            try {
                server.start()
                repeat(29) {
                    val socket = Socket(InetAddress.getLoopbackAddress(), server.listeningPort)
                        .apply { soTimeout = 3000 }
                    idle.add(socket)
                    Wire.write(socket.outputStream, Wire.control(Wire.HELLO, 0,
                        Wire.obj("role" to "sender", "versions" to org.json.JSONArray().put(3), "name" to "idle")))
                    assertEquals(Wire.HELLO, Wire.read(socket.inputStream).type)
                }
                val target = ReceiverTarget("127.0.0.1", server.listeningPort)
                ReceiverLink(target).use { old ->
                    old.connectArmed(5000, claim) { registry.remainingMs(claim) }
                    val request = Settings().apply { audio = false }.request()
                    old.selectDiagnosticProxy(request, claim)
                    old.send(Wire.control(Wire.SESSION_REQUEST, session, request))
                    val accepted = old.receive()
                    val client = DiagnosticProxyClient(target, registry, claim, timer, onFailure = {})
                    try {
                        client.start(old.acceptedDiagnosticProxy(accepted.json(), claim),
                            System.nanoTime() + 5_000_000_000L) { registry.remainingMs(claim) }
                        assertTrue(readerEntered.await(3, TimeUnit.SECONDS))
                        assertEquals(30, pool.ordinaryCount())
                        claim.commitTerminal(System.nanoTime())
                        old.send(Wire.control(Wire.STOP, session, Wire.obj("reason" to "old")))
                        assertEquals(Wire.SESSION_END, old.receive().type)
                        assertTrue(oldOwnerReleased.await(3, TimeUnit.SECONDS))
                        assertEquals(1L, releaseReader.count)
                        val writer = ReceiverServer::class.java.getDeclaredField("proxyWriter")
                            .apply { isAccessible = true }.get(server) as java.util.concurrent.ThreadPoolExecutor
                        val writerRetired = CountDownLatch(1)
                        assertTrue(writer.queue.offer(Runnable { writerRetired.countDown() }, 3, TimeUnit.SECONDS))
                        assertTrue(writerRetired.await(3, TimeUnit.SECONDS))
                        synchronized(pool) {
                            val reserved = checkNotNull(diagnosticField.get(pool))
                            assertFalse(reserved.javaClass.getDeclaredField("readerExited")
                                .apply { isAccessible = true }.getBoolean(reserved))
                            assertTrue(reserved.javaClass.getDeclaredField("writerExited")
                                .apply { isAccessible = true }.getBoolean(reserved))
                        }
                        ReceiverLink(target).use { next ->
                            next.connect()
                            val nextSession = session + 1
                            next.send(Wire.control(Wire.SESSION_REQUEST, nextSession,
                                Settings().apply { audio = false }.request()))
                            val nextAccepted = next.receive()
                            assertEquals(Wire.SESSION_ACCEPT, nextAccepted.type)
                            val token = nextAccepted.json().getString("mediaToken")
                            order.forEach { channel ->
                                media.add(org.screencopy.receiver.net.MediaSockets.connect(
                                    server, nextSession, token, channel))
                            }
                            assertEquals(32, pool.ordinaryCount())
                            assertEquals(33, pool.snapshot().size)
                            next.send(Wire.control(Wire.PING, nextSession, Wire.obj("nonce" to 21)))
                            assertEquals(Wire.PONG, next.receive().type)
                            next.send(Wire.control(Wire.STOP, nextSession, Wire.obj("reason" to "next")))
                            assertEquals(Wire.SESSION_END, next.receive().type)
                        }
                        assertTrue(nextOwnerReleased.await(3, TimeUnit.SECONDS))
                        media.forEach { it.close() }
                        idle.forEach { it.close() }
                        val replacementRecorder = DiagnosticRecorder().apply { grantConsent() }
                        val replacementRegistry = DiagnosticArmRegistry(replacementRecorder)
                        replacementRegistry.start("collector", setOf("session"), setOf("session"), 10_000, 100)
                        val replacement = checkNotNull(replacementRegistry.claim(session + 2))
                        try {
                            ReceiverLink(target).use { armed ->
                                armed.connectArmed(5000, replacement) { replacementRegistry.remainingMs(replacement) }
                                val armedRequest = Settings().apply { audio = false }.request()
                                armed.selectDiagnosticProxy(armedRequest, replacement)
                                armed.send(Wire.control(Wire.SESSION_REQUEST, replacement.session, armedRequest))
                                val armedAccepted = armed.receive()
                                assertEquals(Wire.SESSION_ACCEPT, armedAccepted.type)
                                assertTrue("ordinary credits must remain available for the diagnostic HELLO",
                                    pool.ordinaryCount() < 32)
                                val denied = DiagnosticProxyClient(target, replacementRegistry, replacement, timer,
                                    onFailure = {})
                                try {
                                    assertThrows(java.io.IOException::class.java) {
                                        denied.start(armed.acceptedDiagnosticProxy(armedAccepted.json(), replacement),
                                            System.nanoTime() + 5_000_000_000L) {
                                            replacementRegistry.remainingMs(replacement)
                                        }
                                        assertTrue(armedLaneRejected.await(3, TimeUnit.SECONDS))
                                    }
                                    assertFalse(replacement.bootstrapAccepted.get())
                                    assertEquals(1, readerCalls.get())
                                    assertEquals(1L, releaseReader.count)
                                    replacement.commitTerminal(System.nanoTime())
                                    armed.send(Wire.control(Wire.STOP, replacement.session, Wire.obj("reason" to "done")))
                                    assertEquals(Wire.SESSION_END, armed.receive().type)
                                } finally { denied.cancel("test_done") }
                            }
                        } finally { replacementRegistry.revoke() }
                    } finally { client.cancel("test_done") }
                }
            } finally {
                media.forEach { it.close() }
                idle.forEach { it.close() }
                releaseReader.countDown()
                server.close()
                timer.shutdownNow()
                registry.revoke()
            }
            synchronized(pool) { assertNull(diagnosticField.get(pool)) }
        }
    }

    @Test(timeout = 15_000) fun setupTimeoutAndReadyPublicationHaveOneWinner() {
        for (timeoutFirst in listOf(true, false)) {
            val sender = DiagnosticRecorder().apply { grantConsent() }
            val registry = DiagnosticArmRegistry(sender)
            val pair = registry.start("collector", setOf("session"), setOf("session"), 10_000, 100)
            val claim = checkNotNull(registry.claim(session))
            val before = CountDownLatch(1)
            val release = CountDownLatch(1)
            val timeoutStarted = CountDownLatch(1)
            val closed = CountDownLatch(1)
            val timer = Executors.newScheduledThreadPool(2)
            val tasks = Executors.newFixedThreadPool(2)
            try {
                ReceiverServer.forProtocolTests("Receiver", proxyRecorder = DiagnosticRecorder()).use { server ->
                    server.start()
                    val target = ReceiverTarget("127.0.0.1", server.listeningPort)
                    ReceiverLink(target).use { link ->
                        link.connectArmed(5000, claim) { registry.remainingMs(claim) }
                        val request = Settings().apply { audio = false }.request()
                        link.selectDiagnosticProxy(request, claim)
                        link.send(Wire.control(Wire.SESSION_REQUEST, session, request))
                        val accepted = link.receive()
                        val selection = link.acceptedDiagnosticProxy(accepted.json(), claim)
                        val waitAtGap = {
                            before.countDown()
                            check(release.await(5, TimeUnit.SECONDS))
                        }
                        val client = DiagnosticProxyClient(target, registry, claim, timer, onFailure = {},
                            onClosed = { closed.countDown() },
                            beforeReadyCommit = if (timeoutFirst) waitAtGap else ({}),
                            afterBootstrapPublished = if (timeoutFirst) ({}) else waitAtGap)
                        try {
                            val started = tasks.submit {
                                client.start(selection, System.nanoTime() + 5_000_000_000L) { registry.remainingMs(claim) }
                            }
                            assertTrue(before.await(3, TimeUnit.SECONDS))
                            val expiry = tasks.submit { timeoutStarted.countDown(); client.expireSetup() }
                            assertTrue(timeoutStarted.await(3, TimeUnit.SECONDS))
                            if (timeoutFirst) expiry.get(3, TimeUnit.SECONDS)
                            release.countDown()
                            if (timeoutFirst) {
                                assertThrows(ExecutionException::class.java) { started.get(3, TimeUnit.SECONDS) }
                                assertFalse(claim.bootstrapAccepted.get())
                                assertEquals("binding", registry.status("collector", pair.getString("pairId")).getString("state"))
                            } else {
                                started.get(3, TimeUnit.SECONDS)
                                expiry.get(3, TimeUnit.SECONDS)
                                assertTrue(claim.bootstrapAccepted.get())
                                assertEquals("covered", registry.status("collector", pair.getString("pairId")).getString("state"))
                                assertEquals(1L, closed.count)
                            }
                        } finally { release.countDown(); client.cancel("test_done") }
                    }
                }
            } finally { release.countDown(); tasks.shutdownNow(); timer.shutdownNow() }
        }
    }

    @Test(timeout = 15_000) fun laneHelloLeaseIsShortenedFromReceiptBeforeDelayedAdmission() {
        val sender = DiagnosticRecorder().apply { grantConsent() }
        val registry = DiagnosticArmRegistry(sender)
        registry.start("collector", setOf("session"), setOf("session"), 10_000, 100)
        val claim = checkNotNull(registry.claim(session))
        val receiverNow = AtomicLong()
        val receipt = AtomicLong()
        val receiver = DiagnosticRecorder {
            receiverNow.get().let { if (it == 0L) System.nanoTime() else it }
        }
        val timer = Executors.newSingleThreadScheduledExecutor()
        try {
            ReceiverServer.forProtocolTests("Receiver", proxyRecorder = receiver, proxyLaneReceived = {
                receipt.set(it)
                receiverNow.set(it + 250_000_000L)
            }).use { server ->
                server.start()
                val target = ReceiverTarget("127.0.0.1", server.listeningPort)
                ReceiverLink(target).use { link ->
                    link.connectArmed(5000, claim) { registry.remainingMs(claim) }
                    val request = Settings().apply { audio = false }.request()
                    link.selectDiagnosticProxy(request, claim)
                    link.send(Wire.control(Wire.SESSION_REQUEST, session, request))
                    val accepted = link.receive()
                    val selection = link.acceptedDiagnosticProxy(accepted.json(), claim)
                    val client = DiagnosticProxyClient(target, registry, claim, timer, onFailure = {})
                    try {
                        client.start(selection, System.nanoTime() + 5_000_000_000L) { 1000 }
                        assertEquals(receipt.get() + 1_000_000_000L,
                            receiver.status().getString("expiresNs").toLong())
                    } finally { client.cancel("test_done") }
                }
            }
        } finally { timer.shutdownNow() }
    }

    @Test(timeout = 20_000) fun maintenanceClosesProxyWhenPeerStopRetirementIsBlocked() {
        val sender = DiagnosticRecorder().apply { grantConsent() }
        val receiver = DiagnosticRecorder()
        val registry = DiagnosticArmRegistry(sender)
        registry.start("collector", setOf("session"), setOf("session"), 10_000, 100)
        val claim = checkNotNull(registry.claim(session))
        val clock = AtomicLong(100_000)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val laneClosed = CountDownLatch(1)
        val firstRecord = CountDownLatch(1)
        val timerBlocked = CountDownLatch(1)
        val releaseTimer = CountDownLatch(1)
        val writerBlocked = CountDownLatch(1)
        val releaseWriter = CountDownLatch(1)
        val fetchCount = AtomicInteger()
        val availableAgain = CountDownLatch(1)
        val availabilityCount = AtomicInteger()
        val timer = Executors.newSingleThreadScheduledExecutor()
        val player = Mockito.mock(VideoPlayer::class.java)
        Mockito.doAnswer {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            null
        }.`when`(player).close()
        try {
            ReceiverServer.forProtocolTests("Receiver", proxyRecorder = receiver,
                timeMilliseconds = clock::get, videoFactory = { _, _, _, _ -> player },
                proxyBeforeFetch = {
                    if (fetchCount.incrementAndGet() == 2) {
                        writerBlocked.countDown()
                        check(releaseWriter.await(8, TimeUnit.SECONDS))
                    }
                },
                onScreenEvent = {
                    if (it == org.screencopy.receiver.ui.ReceiverScreenEvent.CleanupFinished &&
                        availabilityCount.incrementAndGet() == 2) availableAgain.countDown()
                }).use { server ->
                try {
                    server.start()
                    val target = ReceiverTarget("127.0.0.1", server.listeningPort)
                    ReceiverLink(target).use { link ->
                        link.connectArmed(5000, claim) { registry.remainingMs(claim) }
                        val request = Settings().apply { audio = false }.request()
                        link.selectDiagnosticProxy(request, claim)
                        link.send(Wire.control(Wire.SESSION_REQUEST, session, request))
                        val accepted = link.receive()
                        val selection = link.acceptedDiagnosticProxy(accepted.json(), claim)
                        val client = DiagnosticProxyClient(target, registry, claim, timer,
                            onRecord = { firstRecord.countDown() }, onFailure = {},
                            onClosed = { laneClosed.countDown() })
                        try {
                            client.start(selection, System.nanoTime() + 5_000_000_000L) { registry.remainingMs(claim) }
                            registry.beginMedia(claim)
                            link.attach(session, accepted, false)
                            assertTrue(firstRecord.await(3, TimeUnit.SECONDS))
                            assertTrue("diagnostic writer did not block", writerBlocked.await(3, TimeUnit.SECONDS))
                            link.send(Wire.config(session, 1, 1, Wire.obj("codec" to "h264", "width" to 720,
                                "height" to 1280, "csd" to org.json.JSONArray().put("AAAAAQE="))))
                            assertEquals(Wire.STREAM_READY, link.receive().type)
                            val owner = checkNotNull((ReceiverServer::class.java.getDeclaredField("active")
                                .apply { isAccessible = true }.get(server) as AtomicReference<*>).get())
                            val socket = owner.javaClass.getDeclaredField("socket").apply { isAccessible = true }
                                .get(owner) as Socket
                            val tick = owner.javaClass.getDeclaredMethod("tick").apply { isAccessible = true }
                            claim.commitTerminal(System.nanoTime())
                            link.send(Wire.control(Wire.STOP, session, Wire.obj("reason" to "peer stop")))
                            assertTrue("playback retirement did not block", entered.await(3, TimeUnit.SECONDS))
                            val receiverTimer = ReceiverServer::class.java.getDeclaredField("timer")
                                .apply { isAccessible = true }.get(server) as java.util.concurrent.ScheduledExecutorService
                            receiverTimer.execute {
                                timerBlocked.countDown()
                                check(releaseTimer.await(8, TimeUnit.SECONDS))
                            }
                            assertTrue(timerBlocked.await(3, TimeUnit.SECONDS))
                            clock.addAndGet(1999)
                            tick.invoke(owner)
                            assertFalse(socket.isClosed)
                            assertTrue(receiver.enabled("receiver", session, DiagnosticKind.SESSION))
                            clock.incrementAndGet()
                            tick.invoke(owner)
                            assertTrue(socket.isClosed)
                            assertFalse(receiver.enabled("receiver", session, DiagnosticKind.SESSION))
                            assertTrue("lane close waited for the timer or writer",
                                laneClosed.await(3, TimeUnit.SECONDS))
                            assertEquals(1L, releaseWriter.count)
                            releaseTimer.countDown()
                            releaseWriter.countDown()
                            receiver.observe("receiver", session, DiagnosticKind.SESSION, DiagnosticStage.CONFIG)
                            assertEquals(1L, release.count)
                            assertTrue("retirement must continue to own the session", server.busyForUpdate)
                            val page = registry.receiverFetch(claim, 0, 32)
                            assertFalse(page.getBoolean("endConfirmed"))
                            val records = page.getJSONArray("records")
                            assertTrue(records.length() > 0)
                            for (i in 0 until records.length()) {
                                val record = records.getJSONObject(i).getJSONObject("record")
                                assertNotEquals("config", record.optJSONObject("data")?.optString("stage"))
                                if (record.has("role")) assertEquals("$session", record.getString("session"))
                            }
                            val cursor = page.getString("nextAfterSeq").toLong()
                            release.countDown()
                            assertTrue("ownership was not released after retirement",
                                availableAgain.await(4, TimeUnit.SECONDS))
                            ReceiverLink(target).use { later ->
                                later.connect()
                                later.send(Wire.control(Wire.SESSION_REQUEST, session + 1,
                                    Settings().apply { audio = false }.request()))
                                val laterAccepted = later.receive()
                                assertEquals(Wire.SESSION_ACCEPT, laterAccepted.type)
                                later.attach(session + 1, laterAccepted, false)
                                later.send(Wire.control(Wire.STOP, session + 1, Wire.obj("reason" to "later")))
                                assertEquals(Wire.SESSION_END, later.receive().type)
                            }
                            assertEquals(0, registry.receiverFetch(claim, cursor, 32).getJSONArray("records").length())
                        } finally {
                            release.countDown()
                            releaseTimer.countDown()
                            releaseWriter.countDown()
                            client.cancel("test_done")
                        }
                    }
                } finally { release.countDown(); releaseTimer.countDown(); releaseWriter.countDown() }
            }
        } finally {
            release.countDown()
            releaseTimer.countDown()
            releaseWriter.countDown()
            timer.shutdownNow()
        }
    }

    @Test(timeout = 15_000) fun receiverStopDoesNotWaitForStalledDiagnosticReader() = stopWithBlockedReader("remote")
    @Test(timeout = 15_000) fun crossedStopsDoNotWaitForStalledDiagnosticReader() = stopWithBlockedReader("crossed")
    @Test(timeout = 15_000) fun fatalPeerErrorDoesNotWaitForStalledDiagnosticReader() = stopWithBlockedReader("fatal")
    @Test(timeout = 15_000) fun committedStopRetainsSenderCaptureWhenLaneEofPrecedesEnded() = stopWithBlockedReader("eof")

    private fun stopWithBlockedReader(mode: String) {
        val sender = DiagnosticRecorder().apply { grantConsent() }
        val registry = DiagnosticArmRegistry(sender)
        registry.start("collector", setOf("session"), setOf("session"), 10_000, 100)
        val claim = checkNotNull(registry.claim(session))
        val readerEntered = CountDownLatch(1)
        val releaseReader = CountDownLatch(1)
        val laneClosed = CountDownLatch(1)
        val controlWriting = CountDownLatch(1)
        val releaseControl = CountDownLatch(1)
        val availableAgain = CountDownLatch(1)
        val availabilityCount = AtomicInteger()
        val firstRecord = AtomicBoolean()
        val laneFailure = AtomicReference<Exception?>()
        val timer = Executors.newSingleThreadScheduledExecutor()
        try {
            ReceiverServer.forProtocolTests("Receiver", proxyRecorder = DiagnosticRecorder(),
                onScreenEvent = {
                    if (it == org.screencopy.receiver.ui.ReceiverScreenEvent.CleanupFinished &&
                        availabilityCount.incrementAndGet() == 2) availableAgain.countDown()
                },
                controlWrite = { socket, message ->
                    if (mode == "crossed" && message.type == org.screencopy.receiver.protocol.Type.STOP) {
                        controlWriting.countDown()
                        check(releaseControl.await(5, TimeUnit.SECONDS))
                    }
                    org.screencopy.receiver.protocol.Wire.write(socket.outputStream, message)
                }).use { server ->
                server.start()
                val target = ReceiverTarget("127.0.0.1", server.listeningPort)
                ReceiverLink(target).use { link ->
                    link.connectArmed(5000, claim) { registry.remainingMs(claim) }
                    val request = Settings().apply { audio = false }.request()
                    link.selectDiagnosticProxy(request, claim)
                    link.send(Wire.control(Wire.SESSION_REQUEST, session, request))
                    val accepted = link.receive()
                    val selection = link.acceptedDiagnosticProxy(accepted.json(), claim)
                    val client = DiagnosticProxyClient(target, registry, claim, timer,
                        onRecord = {
                            if (firstRecord.compareAndSet(false, true)) {
                                readerEntered.countDown()
                                check(releaseReader.await(5, TimeUnit.SECONDS))
                            }
                        }, onFailure = { laneFailure.set(it) }, onClosed = { laneClosed.countDown() })
                    try {
                        client.start(selection, System.nanoTime() + 5_000_000_000L) { registry.remainingMs(claim) }
                        registry.beginMedia(claim)
                        link.attach(session, accepted, false)
                        assertTrue(readerEntered.await(3, TimeUnit.SECONDS))
                        if (mode == "eof") {
                            claim.commitTerminal(System.nanoTime())
                            val owner = (ReceiverServer::class.java.getDeclaredField("active")
                                .apply { isAccessible = true }.get(server) as AtomicReference<*>).get()
                            val lane = checkNotNull(owner).javaClass.getDeclaredField("proxyLane")
                                .apply { isAccessible = true }.get(owner)
                            val peer = checkNotNull(lane).javaClass.getDeclaredField("socket")
                                .apply { isAccessible = true }.get(lane) as Socket
                            peer.shutdownOutput()
                            releaseReader.countDown()
                            assertTrue(laneClosed.await(3, TimeUnit.SECONDS))
                            assertTrue("Unexpected diagnostic EOF: ${laneFailure.get()}",
                                laneFailure.get() is java.io.EOFException)
                            link.send(Wire.control(Wire.STOP, session, Wire.obj("reason" to "local stop")))
                            assertEquals(Wire.SESSION_END, link.receive().type)
                        } else if (mode == "fatal") {
                            claim.commitTerminal(System.nanoTime())
                            link.send(Wire.control(Wire.ERROR, session,
                                Wire.obj("code" to "TIMEOUT", "message" to "test failure", "fatal" to true)))
                            assertEquals(Wire.ERROR, link.receive().type)
                        } else {
                            val stopping = CompletableFuture.runAsync { server.disconnect("receiver stop") }
                            if (mode == "crossed") {
                                assertTrue(controlWriting.await(3, TimeUnit.SECONDS))
                                claim.commitTerminal(System.nanoTime())
                                link.send(Wire.control(Wire.STOP, session, Wire.obj("reason" to "crossed stop")))
                                releaseControl.countDown()
                            }
                            assertEquals(Wire.STOP, link.receive().type)
                            claim.commitTerminal(System.nanoTime())
                            if (mode == "remote")
                                link.send(Wire.control(Wire.SESSION_END, session, Wire.obj("reason" to "ack")))
                            stopping.get(3, TimeUnit.SECONDS)
                        }
                        assertThrows(java.io.EOFException::class.java) { link.receive() }
                        if (mode != "eof") {
                            assertEquals(1L, laneClosed.count)
                            val transport = DiagnosticProxyClient::class.java.getDeclaredField("transport")
                                .apply { isAccessible = true }.get(client) as AtomicReference<*>
                            (transport.get() as Socket).shutdownInput()
                            releaseReader.countDown()
                            assertTrue(laneClosed.await(3, TimeUnit.SECONDS))
                            assertTrue("Unexpected diagnostic reader result: ${laneFailure.get()}",
                                laneFailure.get() is java.net.SocketException)
                        }
                        val receiverPage = registry.receiverFetch(claim, 0, 32)
                        assertFalse(receiverPage.getBoolean("endConfirmed"))
                        assertTrue(receiverPage.isNull("terminal"))
                        val receiverRecords = receiverPage.getJSONArray("records")
                        assertTrue(receiverRecords.length() > 0)
                        for (i in 0 until receiverRecords.length()) {
                            val item = receiverRecords.getJSONObject(i)
                            assertEquals("receiver", item.getString("role"))
                            val record = item.getJSONObject("record")
                            assertEquals(selection.captureId, record.getString("captureId"))
                            if (record.has("role")) {
                                assertEquals("receiver", record.getString("role"))
                                assertEquals("$session", record.getString("session"))
                                assertEquals("$session", item.getString("session"))
                            } else assertTrue(item.isNull("session"))
                        }
                        val draining = registry.status("collector", claim.pairId)
                        assertEquals("draining", draining.getString("state"))
                        assertEquals("unknown", draining.getJSONObject("receiverRole").getString("ownershipCleanup"))
                        fun assertUnknown(status: org.json.JSONObject) {
                            val outcome = status.getJSONObject("receiverRole").getJSONObject("outcome")
                            assertFalse(outcome.getBoolean("confirmed"))
                            assertTrue(outcome.isNull("reason"))
                            assertTrue(outcome.isNull("endedNs"))
                            assertEquals("unknown", outcome.getString("mediaRetirement"))
                            assertEquals("unknown", outcome.getString("ownershipCleanup"))
                            assertTrue(outcome.getBoolean("tailTruncated"))
                            val end = outcome.getJSONObject("captureEnd")
                            assertTrue(end.isNull("reason"))
                            assertTrue(end.isNull("atNs"))
                            assertFalse(end.getBoolean("complete"))
                            assertTrue(end.getBoolean("truncated"))
                        }
                        assertUnknown(draining)
                        finishSenderDiagnostic(sender, registry, claim, session, mode == "fatal",
                            { client.cancel("sender_finished") }, { it() })
                        val frozen = registry.status("collector", claim.pairId)
                        assertEquals("frozen", frozen.getString("state"))
                        assertUnknown(frozen)
                        val senderPage = sender.fetch(claim.senderCaptureId, 0, 32)
                        val records = senderPage.getJSONArray("records")
                        assertTrue((0 until records.length()).any {
                            records.getJSONObject(it).optJSONObject("data")?.optString("stage") ==
                                if (mode == "fatal") "failed" else "ended"
                        })
                        for (i in 0 until records.length()) {
                            val record = records.getJSONObject(i)
                            if (record.has("role")) {
                                assertEquals("sender", record.getString("role"))
                                assertEquals("$session", record.getString("session"))
                            }
                        }
                        val last = senderPage.getString("nextAfterSeq")
                        sender.sessionStarted("sender", session + 1)
                        sender.observe("sender", session + 1, DiagnosticKind.SESSION, DiagnosticStage.STARTED)
                        sender.sessionEnded("sender", session + 1)
                        assertEquals(0, sender.fetch(claim.senderCaptureId, last.toLong(), 32)
                            .getJSONArray("records").length())
                        if (mode == "eof") {
                            assertTrue("receiver ownership did not retire", availableAgain.await(3, TimeUnit.SECONDS))
                            val receiverCursor = receiverPage.getString("nextAfterSeq").toLong()
                            ReceiverLink(target).use { later ->
                                later.connect()
                                later.send(Wire.control(Wire.SESSION_REQUEST, session + 1,
                                    Settings().apply { audio = false }.request()))
                                val laterAccepted = later.receive()
                                assertEquals(Wire.SESSION_ACCEPT, laterAccepted.type)
                                later.attach(session + 1, laterAccepted, false)
                                later.send(Wire.control(Wire.STOP, session + 1, Wire.obj("reason" to "later")))
                                assertEquals(Wire.SESSION_END, later.receive().type)
                            }
                            assertEquals(0, registry.receiverFetch(claim, receiverCursor, 32)
                                .getJSONArray("records").length())
                        }
                    } finally {
                        releaseControl.countDown()
                        releaseReader.countDown()
                        client.cancel("test_done")
                    }
                }
            }
        } finally {
            releaseControl.countDown()
            releaseReader.countDown()
            timer.shutdownNow()
        }
    }

    @Test(timeout = 15_000) fun realLaneRetainsBootstrapBeforeMediaAndEndAfterReadyDoesNotCancelCast() {
        val sender = DiagnosticRecorder().apply { grantConsent() }
        val registry = DiagnosticArmRegistry(sender)
        val armed = registry.start("collector", setOf("session"), setOf("session"), 10_000, 100)
        val claim = checkNotNull(registry.claim(session))
        val receiver = DiagnosticRecorder()
        val timer = Executors.newSingleThreadScheduledExecutor()
        try {
            ReceiverServer.forProtocolTests("Receiver", proxyRecorder = receiver).use { server ->
                server.start()
                val target = ReceiverTarget("127.0.0.1", server.listeningPort)
                ReceiverLink(target).use { link ->
                    link.connectArmed(5000, claim) { registry.remainingMs(claim) }
                    val request = Settings().apply { audio = false }.request()
                    link.selectDiagnosticProxy(request, claim)
                    link.send(Wire.control(Wire.SESSION_REQUEST, session, request))
                    val accepted = link.receive()
                    assertEquals(Wire.SESSION_ACCEPT, accepted.type)
                    val selection = link.acceptedDiagnosticProxy(accepted.json(), claim)
                    val mediaToken = accepted.json().getString("mediaToken")
                    Socket(InetAddress.getLoopbackAddress(), server.listeningPort).use { early ->
                        early.soTimeout = 3000
                        Wire.write(early.outputStream, Wire.control(Wire.HELLO, session,
                            Wire.obj("role" to "media", "channel" to "video", "token" to mediaToken)))
                        assertEquals(Wire.ERROR, Wire.read(early.inputStream).type)
                    }
                    val record = CountDownLatch(1)
                    val client = DiagnosticProxyClient(target, registry, claim, timer, onRecord = { record.countDown() })
                    try {
                        client.start(selection, System.nanoTime() + 5_000_000_000L) { registry.remainingMs(claim) }
                        val covered = registry.status("collector", armed.getString("pairId"))
                        assertEquals("covered", covered.getString("state"))
                        assertEquals(8, covered.getJSONObject("bootstrap").getJSONArray("stages").length())
                        assertTrue("receiver record was not relayed", record.await(5, TimeUnit.SECONDS))
                        val clock = registry.receiverClock("collector", claim.pairId, "123")
                        assertEquals("123", clock.getString("clientSendNs"))
                        assertEquals(sender.clockId, clock.getString("senderClockId"))
                        assertEquals(selection.clockId, clock.getString("receiverClockId"))
                        assertTrue(clock.getString("sendNs").toLong() >= clock.getString("receiveNs").toLong())
                        assertEquals("NOT_OWNER", assertThrows(dev.redrain.cast.diagnostics.DiagnosticError::class.java) {
                            registry.receiverClock("other", claim.pairId, "123")
                        }.code)
                        val page = registry.receiverFetch(claim, 0, 32)
                        assertEquals("loss_watermarks", page.getJSONObject("sourceLoss").getString("schema"))
                        val item = page.getJSONArray("records").getJSONObject(0)
                        assertEquals("receiver", item.getString("role"))
                        assertEquals(selection.captureId, item.getJSONObject("record").getString("captureId"))
                        val paired = registry.fetch("collector", claim.pairId, 0, 0, 32)
                        val records = paired.getJSONArray("records")
                        val roles = (0 until records.length()).map { records.getJSONObject(it).getString("role") }.toSet()
                        assertEquals(setOf("sender", "receiver"), roles)
                        assertTrue(dev.redrain.cast.diagnostics.DiagnosticProtocol.successBytes("r1", "FETCH", paired) <= 16_384)
                        org.screencopy.receiver.protocol.StrictJson.decode(
                            paired.toString().toByteArray(Charsets.UTF_8), maxDepth = 11, trackSizes = false)
                        assertEquals("frozen", registry.end("collector", claim.pairId).getString("state"))
                        client.cancel("collector_end")
                        registry.beginMedia(claim)
                        link.attach(session, accepted, false)
                        claim.terminalCommitted.set(true)
                        link.send(Wire.control(Wire.STOP, session, Wire.obj("reason" to "done")))
                    } finally {
                        claim.terminalCommitted.set(true)
                        client.cancel("test_done")
                    }
                }
            }
        } finally {
            timer.shutdownNow()
            sender.sessionEnded("sender", session)
            claim.senderFinished.set(true)
            registry.senderEnded(claim)
        }
    }

    @Test(timeout = 15_000) fun cancellingRealLaneBeforeReadyDoesNotPermitMedia() {
        val sender = DiagnosticRecorder().apply { grantConsent() }
        val registry = DiagnosticArmRegistry(sender)
        registry.start("collector", setOf("session"), setOf("session"), 10_000, 100)
        val claim = checkNotNull(registry.claim(session))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val timer = Executors.newSingleThreadScheduledExecutor()
        try {
            ReceiverServer.forProtocolTests("Receiver", proxyRecorder = DiagnosticRecorder(), proxyBeforeReady = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }).use { server ->
                server.start()
                val target = ReceiverTarget("127.0.0.1", server.listeningPort)
                ReceiverLink(target).use { link ->
                    link.connectArmed(5000, claim) { registry.remainingMs(claim) }
                    val request = Settings().apply { audio = false }.request()
                    link.selectDiagnosticProxy(request, claim)
                    link.send(Wire.control(Wire.SESSION_REQUEST, session, request))
                    val accepted = link.receive()
                    val selection = link.acceptedDiagnosticProxy(accepted.json(), claim)
                    val client = DiagnosticProxyClient(target, registry, claim, timer)
                    val startup = CompletableFuture.runAsync {
                        client.start(selection, System.nanoTime() + 5_000_000_000L) { registry.remainingMs(claim) }
                    }
                    try {
                        assertTrue(entered.await(3, TimeUnit.SECONDS))
                        client.cancel("startup_cancelled")
                        assertThrows(ExecutionException::class.java) { startup.get(3, TimeUnit.SECONDS) }
                        assertEquals("binding", registry.status("collector", claim.pairId).getString("state"))
                        Socket(InetAddress.getLoopbackAddress(), server.listeningPort).use { early ->
                            early.soTimeout = 3000
                            Wire.write(early.outputStream, Wire.control(Wire.HELLO, session,
                                Wire.obj("role" to "media", "channel" to "video",
                                    "token" to accepted.json().getString("mediaToken"))))
                            assertEquals(Wire.ERROR, Wire.read(early.inputStream).type)
                        }
                    } finally { release.countDown(); client.cancel("test_done") }
                }
            }
        } finally {
            release.countDown()
            registry.setupFailed(claim, "lane")
            timer.shutdownNow()
            sender.sessionEnded("sender", session)
        }
    }

                @Test(timeout = 15_000) fun receiverRetirementCanPrecedeControlTerminalWithoutEndingSenderCapture() {
                    val sender = DiagnosticRecorder().apply { grantConsent() }
                    val registry = DiagnosticArmRegistry(sender)
                    registry.start("collector", setOf("session"), setOf("session"), 10_000, 100)
                    val claim = checkNotNull(registry.claim(session))
                    val controlEntered = CountDownLatch(1)
                    val releaseControl = CountDownLatch(1)
                    val terminal = CountDownLatch(1)
                    val record = CountDownLatch(1)
                    val laneFailure = java.util.concurrent.atomic.AtomicReference<Exception?>()
                    val receiverEvents = java.util.concurrent.CopyOnWriteArrayList<String>()
                    val timer = Executors.newSingleThreadScheduledExecutor()
                    try {
                        ReceiverServer.forProtocolTests("Receiver", proxyRecorder = DiagnosticRecorder(),
                            onDiscoveryDiagnostic = { receiverEvents.add(it) },
                            proxyAfterRetirement = {
                                controlEntered.countDown()
                                check(releaseControl.await(5, TimeUnit.SECONDS))
                            }).use { server ->
                            server.start()
                            val target = ReceiverTarget("127.0.0.1", server.listeningPort)
                            ReceiverLink(target).use { link ->
                                link.connectArmed(5000, claim) { registry.remainingMs(claim) }
                                val request = Settings().apply { audio = false }.request()
                                link.selectDiagnosticProxy(request, claim)
                                link.send(Wire.control(Wire.SESSION_REQUEST, session, request))
                                val accepted = link.receive()
                                val selection = link.acceptedDiagnosticProxy(accepted.json(), claim)
                                val client = DiagnosticProxyClient(target, registry, claim, timer,
                                    onRecord = { record.countDown() }, onTerminal = { terminal.countDown() },
                                    onFailure = { laneFailure.set(it) })
                                try {
                                    client.start(selection, System.nanoTime() + 5_000_000_000L) { registry.remainingMs(claim) }
                                    registry.beginMedia(claim)
                                    link.attach(session, accepted, false)
                                    assertTrue(record.await(3, TimeUnit.SECONDS))
                                    link.send(Wire.control(Wire.STOP, session, Wire.obj("reason" to "test")))
                                    assertTrue(controlEntered.await(3, TimeUnit.SECONDS))
                                    val receivedTerminal = terminal.await(3, TimeUnit.SECONDS)
                                    assertTrue("receiver terminal was not relayed: ${laneFailure.get()} $receiverEvents",
                                        receivedTerminal)
                                    val status = registry.status("collector", claim.pairId)
                                    assertEquals("draining", status.getString("state"))
                                    assertEquals("active", status.getJSONObject("senderRole").getString("state"))
                                    assertEquals("finished", status.getJSONObject("receiverRole").getString("state"))
                                    assertEquals("succeeded", status.getJSONObject("receiverRole")
                                        .getJSONObject("outcome").getString("mediaRetirement"))
                                    val page = registry.receiverFetch(claim, 0, 32)
                                    assertTrue(page.getBoolean("endConfirmed"))
                                    val ended = page.getJSONObject("terminal")
                                    assertEquals("receiver_retired_stop", ended.getString("reason"))
                                    assertEquals("unknown", status.getJSONObject("receiverRole")
                                        .getJSONObject("outcome").getString("ownershipCleanup"))
                                    assertEquals("collecting", sender.status().getString("state"))
                                    releaseControl.countDown()
                                    assertEquals(Wire.SESSION_END, link.receive().type)
                                    claim.terminalCommitted.set(true)
                                } finally {
                                    releaseControl.countDown()
                                    client.cancel("test_done")
                                }
                            }
                        }
                    } finally {
                        releaseControl.countDown()
                        timer.shutdownNow()
                        sender.sessionEnded("sender", session)
                        claim.senderFinished.set(true)
                        registry.senderEnded(claim)
                    }
                }
}

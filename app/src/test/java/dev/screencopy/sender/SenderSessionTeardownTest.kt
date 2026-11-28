package dev.screencopy.sender

import android.content.Context
import dev.redrain.cast.diagnostics.IncidentEvidence
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import org.mockito.Mockito
import java.io.EOFException
import java.io.FilterInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class SenderSessionTeardownTest {
    @Test fun streamingStatusRequiresBothTracksAndIsPublishedOnlyOnTransitions() {
        val statuses = mutableListOf<String>()
        val sender = SenderSession(Mockito.mock(Context::class.java), Settings(),
            ReceiverTarget("127.0.0.1", 7007), statuses::add, {}, {})
        fun field(name: String) = SenderSession::class.java.getDeclaredField(name).apply { isAccessible = true }
        val diagnostics = SenderSession::class.java.getDeclaredMethod("recoveryDiagnostic", String::class.java)
            .apply { isAccessible = true }
        val written = SenderSession::class.java.getDeclaredMethod("writeCompleted", Wire.Message::class.java)
            .apply { isAccessible = true }
        val clock = AtomicLong(1_000_000_000)
        val mux = field("mux").get(sender) as MediaBudget
        val recovery = StreamRecoverySender(ID, mux, clock::get,
            diagnostic = { diagnostics.invoke(sender, it) })
        field("session").setLong(sender, ID)
        field("recovery").set(sender, recovery)
        fun finish(track: Int) {
            val message = checkNotNull(mux.pollLane(track, 1))
            mux.beginWrite(message)
            written.invoke(sender, message)
            mux.endWrite(message)
        }
        fun video(pts: Long) = Wire.Message(Wire.VIDEO_FRAME, 1, ID, 1, 1, pts,
            byteArrayOf(0, 0, 0, 1, 0x65, 1))
        fun audio(pts: Long) = Wire.Message(Wire.AUDIO_PACKET, 0, ID, 2, 1, pts, ByteArray(3840))
        try {
            recovery.config(1, 1, Wire.obj("codec" to "h264"))
            recovery.config(2, 1, Wire.obj("codec" to "raw", "sampleRate" to 48000, "channels" to 2))
            for (track in 1..2) { finish(track); mux.ready(track, 1) }
            recovery.ready()
            recovery.packet(audio(1_000_000))
            recovery.packet(video(1_000_000))
            finish(1)
            recovery.keyProved(1, 1_000_000)
            recovery.completed(1, 1, 1_000_000)
            assertEquals(0, statuses.count { it == "Streaming" })
            finish(2)
            assertEquals(1, statuses.count { it == "Streaming" })
            recovery.packet(audio(1_020_000)); finish(2)
            recovery.packet(video(1_020_000)); finish(1)
            assertEquals(1, statuses.count { it == "Streaming" })

            recovery.request(1, 1)
            finish(1); mux.ready(1, 2); recovery.ready()
            recovery.packet(video(1_030_000)); finish(1)
            recovery.keyProved(2, 1_030_000)
            recovery.completed(1, 2, 1_030_000)
            assertEquals(2, statuses.count { it == "Streaming" })
            recovery.packet(video(1_040_000)); finish(1)
            assertEquals(2, statuses.count { it == "Streaming" })
        } finally {
            (field("timer").get(sender) as java.util.concurrent.ScheduledExecutorService).shutdownNow()
        }
    }

    @Test(timeout = 30_000) fun stopAndReadyCommitShareStartupOutcomeBoundary() {
        for (mode in listOf("before_ready", "before_commit", "ready_first")) {
            val stopFirst = mode != "ready_first"
            val arms = dev.redrain.cast.diagnostics.DiagnosticAccess.arms
            val recorder = dev.redrain.cast.diagnostics.RemoteDiagnostics.recorder
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val stopStarted = CountDownLatch(1)
            val senderRef = AtomicReference<SenderSession>()
            val tasks = java.util.concurrent.Executors.newFixedThreadPool(2)
            arms.revoke()
            recorder.grantConsent()
            val pair = arms.start("collector", setOf("session"), setOf("session"), 20_000, 100)
            val blocked = {
                entered.countDown()
                check(release.await(6, TimeUnit.SECONDS))
            }
            try {
                org.screencopy.receiver.net.ReceiverServer.forProtocolTests("Receiver",
                    proxyRecorder = dev.redrain.cast.diagnostics.DiagnosticRecorder(),
                    proxyBeforeReady = if (mode == "before_ready") blocked else ({})).use { receiver ->
                    receiver.start()
                    val run = tasks.submit {
                        Mockito.mockConstruction(ProjectionCapture::class.java) { capture, _ ->
                            Mockito.`when`(capture.validate(Mockito.any(Settings::class.java) ?: Settings()))
                                .thenAnswer { it.arguments[0] }
                        }.use { captures ->
                            Mockito.mockStatic(android.util.Log::class.java).use {
                                val sender = SenderSession(Mockito.mock(Context::class.java),
                                    Settings().apply { audio = false },
                                    ReceiverTarget("127.0.0.1", receiver.listeningPort), {}, {}, {},
                                    diagnosticBeforeReadyCommit = if (mode == "before_commit") blocked else ({}),
                                    diagnosticAfterReadyCheck = if (mode == "ready_first") blocked else ({}))
                                sender.projectionOwner = Mockito.mock(ProjectionOwner::class.java)
                                senderRef.set(sender)
                                sender.run()
                                if (stopFirst) Mockito.verify(captures.constructed().single(), Mockito.never())
                                    .start(Mockito.any(Settings::class.java) ?: Settings(), Mockito.anyLong(),
                                        Mockito.any(ScrcpyAdapter.Sink::class.java) ?: sender)
                            }
                        }
                    }
                    assertTrue(entered.await(8, TimeUnit.SECONDS))
                    val stop = tasks.submit { stopStarted.countDown(); checkNotNull(senderRef.get()).stop() }
                    assertTrue(stopStarted.await(3, TimeUnit.SECONDS))
                    if (stopFirst) stop.get(3, TimeUnit.SECONDS)
                    else assertFalse(stop.isDone)
                    release.countDown()
                    stop.get(3, TimeUnit.SECONDS)
                    run.get(7, TimeUnit.SECONDS)
                    val status = arms.status("collector", pair.getString("pairId"))
                    if (stopFirst) {
                        assertEquals("setup_failed", status.getString("endReason"))
                        assertTrue(status.isNull("receiverCaptureId"))
                        assertTrue(status.isNull("bootstrap"))
                        assertTrue(status.toString(), status.isNull("setupFailure"))
                    } else {
                        assertFalse(status.isNull("receiverCaptureId"))
                        assertTrue(status.getJSONObject("bootstrap").getBoolean("complete"))
                        assertNotEquals("setup_failed", status.optString("endReason"))
                    }
                }
            } finally { release.countDown(); tasks.shutdownNow(); arms.revoke() }
        }
    }

    @Test(timeout = 15_000) fun fatalControlBeforeReadyCommitRetainsSetupCauseAndDoesNotStartMedia() {
        val arms = dev.redrain.cast.diagnostics.DiagnosticAccess.arms
        val recorder = dev.redrain.cast.diagnostics.RemoteDiagnostics.recorder
        val ready = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val control = AtomicReference<Socket>()
        val acceptedSession = AtomicLong()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        arms.revoke()
        recorder.grantConsent()
        val id = arms.start("collector", setOf("session"), setOf("session"), 20_000, 100).getString("pairId")
        val secret = "private-control-detail-192.0.2.1"
        try {
            org.screencopy.receiver.net.ReceiverServer.forProtocolTests("Receiver",
                proxyRecorder = dev.redrain.cast.diagnostics.DiagnosticRecorder(), controlWrite = { socket, message ->
                    org.screencopy.receiver.protocol.Wire.write(socket.outputStream, message)
                    if (message.type == org.screencopy.receiver.protocol.Type.SESSION_ACCEPT) {
                        acceptedSession.set(message.session)
                        control.set(socket)
                    }
                }).use { receiver ->
                receiver.start()
                val run = executor.submit {
                    Mockito.mockConstruction(ProjectionCapture::class.java) { capture, _ ->
                        Mockito.`when`(capture.validate(Mockito.any(Settings::class.java) ?: Settings()))
                            .thenAnswer { it.arguments[0] }
                    }.use { captures ->
                      Mockito.mockStatic(android.util.Log::class.java).use {
                        val sender = SenderSession(Mockito.mock(Context::class.java), Settings().apply { audio = false },
                            ReceiverTarget("127.0.0.1", receiver.listeningPort), {}, {}, {
                                if (it.startsWith("sender_session_failure ")) failed.countDown()
                            }, diagnosticBeforeReadyCommit = {
                                ready.countDown()
                                check(release.await(5, TimeUnit.SECONDS))
                            })
                        sender.projectionOwner = Mockito.mock(ProjectionOwner::class.java)
                        sender.run()
                        Mockito.verify(captures.constructed().single(), Mockito.never())
                            .start(Mockito.any(Settings::class.java) ?: Settings(), Mockito.anyLong(),
                                Mockito.any(ScrcpyAdapter.Sink::class.java) ?: sender)
                      }
                    }
                }
                assertTrue(ready.await(7, TimeUnit.SECONDS))
                org.screencopy.receiver.protocol.Wire.write(checkNotNull(control.get()).outputStream,
                    org.screencopy.receiver.protocol.Message.json(org.screencopy.receiver.protocol.Type.ERROR,
                        JSONObject().put("code", "PROTOCOL").put("message", secret).put("fatal", true),
                        acceptedSession.get()))
                assertTrue("control failure was not retained", failed.await(3, TimeUnit.SECONDS))
                release.countDown()
                run.get(5, TimeUnit.SECONDS)
                val status = arms.status("collector", id)
                assertEquals("setup_failed", status.getString("endReason"))
                assertEquals("PROTOCOL", status.getJSONObject("setupFailure").getString("code"))
                assertEquals("receiver_control", status.getJSONObject("setupFailure").getString("provenance"))
                assertTrue(status.isNull("receiverCaptureId"))
                val page = arms.fetch("collector", id, 0, 0, 32)
                assertTrue(page.isNull("receiver"))
                assertFalse(status.toString().contains(secret))
                assertFalse(page.toString().contains(secret))
                assertTrue((0 until page.getJSONArray("records").length()).any {
                    page.getJSONArray("records").getJSONObject(it).getJSONObject("record")
                        .optJSONObject("data")?.optString("stage") == "failed"
                })
            }
        } finally { release.countDown(); executor.shutdownNow(); arms.revoke() }
    }

    @Test(timeout = 15_000) fun armedRequestRejectRetainsSanitizedRemoteFirstCauseAndFailureRecord() {
        requestSetupFailure(remoteReject = true)
    }

    @Test(timeout = 15_000) fun armedRequestTimeoutRetainsLocalCauseAndFailureRecord() {
        requestSetupFailure(remoteReject = false)
    }

    private fun requestSetupFailure(remoteReject: Boolean) {
        val arms = dev.redrain.cast.diagnostics.DiagnosticAccess.arms
        val recorder = dev.redrain.cast.diagnostics.RemoteDiagnostics.recorder
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val statuses = java.util.concurrent.CopyOnWriteArrayList<String>()
        val diagnostics = java.util.concurrent.CopyOnWriteArrayList<String>()
        val secret = "private-token-address-192.0.2.1"
        arms.revoke()
        recorder.grantConsent()
        val pair = arms.start("collector", setOf("session"), setOf("session"), 20_000, 100)
        val id = pair.getString("pairId")
        try {
            org.screencopy.receiver.net.ReceiverServer.forProtocolTests("Receiver",
                proxyRecorder = dev.redrain.cast.diagnostics.DiagnosticRecorder(), validateRequest = {
                entered.countDown()
                if (remoteReject) throw org.screencopy.receiver.protocol.ProtocolException("PROTOCOL", secret)
                check(release.await(8, TimeUnit.SECONDS))
            }).use { receiver ->
                try {
                    receiver.start()
                    Mockito.mockConstruction(ProjectionCapture::class.java) { capture, _ ->
                        Mockito.`when`(capture.validate(Mockito.any(Settings::class.java) ?: Settings()))
                            .thenAnswer { it.arguments[0] }
                        if (remoteReject) Mockito.doThrow(java.io.IOException("TIMEOUT: cleanup $secret"))
                            .`when`(capture).close()
                    }.use {
                        Mockito.mockStatic(android.util.Log::class.java).use {
                            val sender = SenderSession(Mockito.mock(Context::class.java),
                                Settings().apply { audio = false }, ReceiverTarget("127.0.0.1", receiver.listeningPort),
                                statuses::add, {}, diagnostics::add,
                                diagnosticSetupTimeoutNs = if (remoteReject) 5_000_000_000L else 500_000_000L)
                            sender.projectionOwner = Mockito.mock(ProjectionOwner::class.java)
                            sender.run()
                        }
                    }
                    val status = arms.status("collector", id)
                    assertEquals("status=$status states=$statuses diagnostics=$diagnostics", 0L, entered.count)
                    assertEquals("frozen", status.getString("state"))
                    assertEquals("setup_failed", status.getString("endReason"))
                    assertEquals("request", status.getString("setupStage"))
                    val failure = status.getJSONObject("setupFailure")
                    assertEquals(if (remoteReject) "PROTOCOL" else "TIMEOUT", failure.getString("code"))
                    assertEquals(if (remoteReject) "receiver_control" else "sender_local", failure.getString("provenance"))
                    assertEquals(if (remoteReject) "receiver_reported" else "timeout", failure.getString("reason"))
                    assertTrue(status.isNull("receiverCaptureId"))
                    assertEquals("unknown", status.getJSONObject("receiverRole").getString("ownershipCleanup"))
                    val page = arms.fetch("collector", id, 0, 0, 32)
                    assertTrue(page.getBoolean("setupFailureAvailable"))
                    assertFalse(page.has("setupFailure"))
                    assertTrue(page.isNull("receiver"))
                    val records = page.getJSONArray("records")
                    assertTrue((0 until records.length()).any {
                        records.getJSONObject(it).getJSONObject("record").optJSONObject("data")
                            ?.optString("stage") == "failed"
                    })
                    assertFalse(status.toString().contains(secret))
                    assertFalse(page.toString().contains(secret))
                    assertEquals(failure.toString(), arms.status("collector", id).getJSONObject("setupFailure").toString())
                } finally { release.countDown() }
            }
        } finally {
            release.countDown()
            arms.revoke()
        }
    }

    @Test(timeout = 15_000) fun receiverFirstRecoveryDeadlineRetainsTimeoutInSenderReport() {
        val receiverTime = AtomicLong(1_000)
        val receiverRequest = CountDownLatch(1)
        val frames = java.util.concurrent.CopyOnWriteArrayList<org.screencopy.receiver.protocol.Message>()
        val reports = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
        val finished = CountDownLatch(1)
        val player = Mockito.mock(org.screencopy.receiver.media.VideoPlayer::class.java)
        Mockito.doReturn(org.screencopy.receiver.media.QueuePressure(1,
            org.screencopy.receiver.media.QueueLimit.BYTES, 0, 0, 1, 8_388_609, 0, 64, 8_388_608, 0))
            .`when`(player).tryOffer(Mockito.any(org.screencopy.receiver.media.AccessUnit::class.java)
                ?: org.screencopy.receiver.media.AccessUnit(0, byteArrayOf()), Mockito.anyLong())
        Mockito.doAnswer {
            dev.redrain.cast.diagnostics.TelemetryRegistry.event("receiver", ID,
                dev.redrain.cast.diagnostics.DiagnosticKind.SESSION.ordinal + 1,
                dev.redrain.cast.diagnostics.DiagnosticStage.COMPLETE.ordinal, 1, 1, 992)
            null
        }.`when`(player).close()
        org.screencopy.receiver.net.ReceiverServer.forProtocolTests("Receiver",
            timeMilliseconds = receiverTime::get, videoFactory = { _, _, _, _ -> player },
            controlWrite = { socket, message ->
                frames.add(message)
                org.screencopy.receiver.protocol.Wire.write(socket.outputStream, message)
            }).use { receiver ->
            receiver.start()
            ReceiverLink(ReceiverTarget("127.0.0.1", receiver.listeningPort)).use { link ->
                Harness(receiver.listeningPort).use { session ->
                    val telemetry = dev.redrain.cast.diagnostics.SenderTelemetrySession(true, { reports.add(it) },
                        storageExecutor = java.util.concurrent.Executor { it.run() }, onFinished = finished::countDown)
                    try {
                        link.connect()
                        val request = Settings().apply { audio = false }.request()
                        link.selectSessionTelemetry(request)
                        link.send(Wire.control(Wire.SESSION_REQUEST, ID, request))
                        val acceptance = link.receive()
                        assertTrue(link.acceptedSessionTelemetry(acceptance.json()))
                        link.attach(ID, acceptance, false)
                        link.send(Wire.config(ID, 1, 1, Wire.obj("codec" to "h264", "width" to 640,
                            "height" to 360, "csd" to JSONArray().put("AAAAAWdkACg="))))
                        assertEquals(Wire.STREAM_READY, link.receive().type)
                        session.prepare(link)
                        session.useTelemetry(telemetry)
                        session.prepareRecovery(receiverRequest)
                        session.startWorkers(watchMedia = true)
                        val connection = checkNotNull((receiver.javaClass.getDeclaredField("active")
                            .apply { isAccessible = true }.get(receiver) as AtomicReference<*>).get())
                        val receive = connection.javaClass.getDeclaredMethod("receiveMedia",
                            org.screencopy.receiver.protocol.Message::class.java).apply { isAccessible = true }
                        val oversized = ByteArray(8 * 1024 * 1024 + 1).apply { this[3] = 1; this[4] = 0x65 }
                        receive.invoke(connection, org.screencopy.receiver.protocol.Message(
                            org.screencopy.receiver.protocol.Type.VIDEO_FRAME, ID, 1, 1, 0, 1, oversized))
                        assertTrue(receiverRequest.await(3, TimeUnit.SECONDS))
                        receiverTime.set(11_200)
                        connection.javaClass.getDeclaredMethod("tick").apply { isAccessible = true }.invoke(connection)
                        assertTrue(session.receiverReleased.await(3, TimeUnit.SECONDS))
                        session.sender.retireTransport()
                        assertEquals("TIMEOUT", SessionOutcome.errorCode(checkNotNull(session.outcome.failure)))
                        assertTrue(session.outcome.failure is ReceiverReportedFailure)
                        assertTrue(telemetry.finish(session.cleanupSucceeded()))
                        assertTrue(finished.await(3, TimeUnit.SECONDS))
                        val terminal = frames.single { it.type == org.screencopy.receiver.protocol.Type.ERROR }
                        assertEquals("TIMEOUT", terminal.json().getString("code"))
                        val final = dev.redrain.cast.diagnostics.SessionTelemetryProtocol.parse(
                            frames[frames.indexOf(terminal) - 1].payload, "receiver")
                        assertTrue(final.final)
                        assertTrue(final.events.any { it.value == 992L })
                        assertEquals(final.sequence.toString(), terminal.json().getString("finalSeq"))
                        val report = org.screencopy.receiver.protocol.StrictJson.decode(reports.last())
                        assertTrue(report.getBoolean("receiverComplete"))
                        assertEquals(final.sequence.toString(),
                            report.getJSONObject("receiver").getString("terminalSequence"))
                    } finally {
                        dev.redrain.cast.diagnostics.TelemetryRegistry.end("sender", telemetry)
                        telemetry.finish()
                    }
                }
            }
        }
    }

    @Test(timeout = 15_000) fun acceptedFatalPathsSealAfterCleanupAndPreserveTerminalMetadataInSenderReport() {
        for ((peerFatal, cleanupMode) in listOf(true to 0, false to 0, false to 1))
            acceptedFatalRetirement(peerFatal, cleanupMode)
    }

    @Test(timeout = 20_000) fun negotiatedFatalRetirementWaitsForDelayedReceiverCleanupPastTwoSeconds() {
        acceptedFatalRetirement(peerFatal = true, cleanupMode = 3)
        acceptedFatalRetirement(peerFatal = false, cleanupMode = 3)
    }

    @Test(timeout = 20_000) fun negotiatedFatalCleanupTimeoutRemainsReachableAfterQueuedErrorWrite() {
        acceptedFatalRetirement(peerFatal = true, cleanupMode = 2, delayTerminal = true)
    }

    @Test(timeout = 15_000) fun negotiatedRecoveryDeadlineKeepsControlOpenUntilFatalRetirement() {
        acceptedFatalRetirement(peerFatal = true, cleanupMode = 3, recoveryDeadline = true)
    }

    @Test(timeout = 20_000) fun scheduledRecoveryWatchdogClosesSilentFatalControlWhileCaptureCleanupBlocks() {
        runSession { server, session, link ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val errorReceived = CountDownLatch(1)
            val retired = CountDownLatch(1)
            val owner = Mockito.mock(ProjectionOwner::class.java)
            val codec = Mockito.mock(android.media.MediaCodec::class.java)
            val capture = ProjectionCapture(Mockito.mock(Context::class.java), owner, session.sender::captureFailed)
            ProjectionCapture::class.java.getDeclaredField("video").apply { isAccessible = true }.set(capture, codec)
            SenderSession::class.java.getDeclaredField("backend").apply { isAccessible = true }.set(session.sender, capture)
            Mockito.doAnswer {
                entered.countDown()
                check(release.await(15, TimeUnit.SECONDS))
                null
            }.`when`(owner).detach()
            val peer = CompletableFuture.supplyAsync {
                var elapsed = -1L
                attachPeer(server, v2 = true) { control, _, _ ->
                    control.soTimeout = 12_000
                    assertEquals(Wire.ERROR, terminal(control).type)
                    val started = System.nanoTime()
                    errorReceived.countDown()
                    assertEquals(-1, control.inputStream.read())
                    elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                }
                elapsed
            }
            try {
                negotiate(link)
                session.prepare(link)
                SenderSession::class.java.getDeclaredField("telemetryNegotiated").apply { isAccessible = true }
                    .setBoolean(session.sender, true)
                session.startWorkers()
                session.expireRecovery()
                session.sender.startWatchdog()
                assertTrue("Scheduled watchdog did not begin capture cleanup", entered.await(3, TimeUnit.SECONDS))
                val retiring = CompletableFuture.runAsync {
                    try { session.sender.retireTransport() } finally { retired.countDown() }
                }
                assertTrue("Fatal ERROR was not written", errorReceived.await(3, TimeUnit.SECONDS))
                val elapsed = peer.get(9500, TimeUnit.MILLISECONDS)
                assertTrue("Response budget closed too early: $elapsed ms", elapsed >= 7000)
                assertTrue("Response budget was not enforced: $elapsed ms", elapsed < 9000)
                assertEquals(1L, retired.count)
                assertFalse(session.cleanupSucceeded())
                assertEquals("TIMEOUT", SessionOutcome.errorCode(checkNotNull(session.outcome.failure)))
                release.countDown()
                retiring.get(3, TimeUnit.SECONDS)
                Mockito.verify(owner, Mockito.times(1)).detach()
                Mockito.verify(codec, Mockito.times(1)).release()
            } finally {
                release.countDown()
            }
        }
    }

    @Test(timeout = 10_000) fun scheduledProgressWatchdogClosesLegacyControlAndDoesNotBlockTimerOnCaptureFailure() {
        runSession { server, session, link ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val timerAvailable = CountDownLatch(1)
            val retired = CountDownLatch(1)
            val owner = Mockito.mock(ProjectionOwner::class.java)
            val codec = Mockito.mock(android.media.MediaCodec::class.java)
            val capture = ProjectionCapture(Mockito.mock(Context::class.java), owner, session.sender::captureFailed)
            ProjectionCapture::class.java.getDeclaredField("video").apply { isAccessible = true }.set(capture, codec)
            SenderSession::class.java.getDeclaredField("backend").apply { isAccessible = true }.set(session.sender, capture)
            Mockito.doAnswer {
                entered.countDown()
                check(release.await(7, TimeUnit.SECONDS))
                throw IllegalStateException("controlled platform failure")
            }.`when`(owner).detach()
            val peer = CompletableFuture.runAsync {
                attachPeer(server) { control, _, _ ->
                    try {
                        while (true) {
                            val message = Wire.read(control.inputStream)
                            if (message.type == Wire.PING) Wire.write(control.outputStream,
                                Wire.control(Wire.PONG, ID, Wire.obj("nonce" to message.json().getInt("nonce"))))
                        }
                    } catch (_: EOFException) { }
                }
            }
            try {
                negotiate(link)
                session.prepare(link)
                session.startWorkers()
                SenderSession::class.java.getDeclaredField("phaseDeadline").apply { isAccessible = true }
                    .setLong(session.sender, System.nanoTime() - 1)
                session.sender.startWatchdog()
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                peer.get(2, TimeUnit.SECONDS)
                assertTrue(session.linkClosed())
                val timer = SenderSession::class.java.getDeclaredField("timer").apply { isAccessible = true }
                    .get(session.sender) as java.util.concurrent.ScheduledExecutorService
                timer.execute { timerAvailable.countDown() }
                assertTrue("Capture cleanup must not occupy the watchdog executor", timerAvailable.await(1, TimeUnit.SECONDS))
                val retiring = CompletableFuture.runAsync {
                    try { session.sender.retireTransport() } finally { retired.countDown() }
                }
                assertEquals(1L, retired.count)
                release.countDown()
                retiring.get(3, TimeUnit.SECONDS)
                assertFalse(session.cleanupSucceeded())
                assertEquals("TIMEOUT", SessionOutcome.errorCode(checkNotNull(session.outcome.failure)))
                assertTrue(session.diagnostics.any { it.contains("stop_origin=CLEANUP") })
                Mockito.verify(owner, Mockito.times(1)).detach()
                Mockito.verify(codec, Mockito.times(1)).release()
            } finally { release.countDown() }
        }
    }

    private fun acceptedFatalRetirement(peerFatal: Boolean, cleanupMode: Int, delayTerminal: Boolean = false,
                                        recoveryDeadline: Boolean = false) {
            val expectedCode = if (recoveryDeadline) "TIMEOUT" else if (peerFatal) "AUDIO_FAILED" else "UNSUPPORTED_CONFIG"
            val frames = java.util.concurrent.CopyOnWriteArrayList<org.screencopy.receiver.protocol.Message>()
            val receiverStatus = java.util.concurrent.CopyOnWriteArrayList<String>()
            val release = CountDownLatch(1)
            val retired = CountDownLatch(1)
            val cleanupEntered = CountDownLatch(1)
            val terminalEntered = CountDownLatch(1)
            val terminalRelease = CountDownLatch(1)
            val retirementDone = CountDownLatch(1)
            val player = Mockito.mock(org.screencopy.receiver.media.VideoPlayer::class.java)
            Mockito.doAnswer {
                cleanupEntered.countDown()
                if (cleanupMode in 2..3) check(release.await(12, TimeUnit.SECONDS))
                retired.countDown()
                dev.redrain.cast.diagnostics.TelemetryRegistry.event("receiver", ID,
                    dev.redrain.cast.diagnostics.DiagnosticKind.SESSION.ordinal + 1,
                    dev.redrain.cast.diagnostics.DiagnosticStage.COMPLETE.ordinal, 1, 1, 991)
                if (cleanupMode == 1) throw IllegalStateException("controlled cleanup failure")
                null
            }.`when`(player).close()
            org.screencopy.receiver.net.ReceiverServer.forProtocolTests("Receiver",
                onStatus = { state, detail -> receiverStatus.add("$state: $detail") },
                videoFactory = { _, _, _, _ -> player },
                controlWrite = { socket, message ->
                    if (message.type == org.screencopy.receiver.protocol.Type.ERROR)
                        receiverStatus.add("ERROR ${message.json()}")
                    frames.add(message)
                    org.screencopy.receiver.protocol.Wire.write(socket.outputStream, message)
                }).use { receiver ->
                try {
                    receiver.start()
                    ReceiverLink(ReceiverTarget("127.0.0.1", receiver.listeningPort)).use { rawLink ->
                        val link = if (delayTerminal) Mockito.spy(rawLink) else rawLink
                        if (delayTerminal) Mockito.doAnswer { invocation ->
                            val message = invocation.getArgument<Wire.Message>(0)
                            if (message.type == Wire.ERROR) {
                                terminalEntered.countDown()
                                check(terminalRelease.await(5, TimeUnit.SECONDS))
                            }
                            invocation.callRealMethod()
                        }.`when`(link).send(Mockito.any(Wire.Message::class.java)
                            ?: Wire.control(Wire.PING, ID, Wire.obj("nonce" to 0)))
                        Harness(receiver.listeningPort).use { session ->
                            val reports = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
                            val finished = CountDownLatch(1)
                            val telemetry = dev.redrain.cast.diagnostics.SenderTelemetrySession(true, { reports.add(it) },
                                storageExecutor = java.util.concurrent.Executor { it.run() }, onFinished = finished::countDown)
                            try {
                                link.connect()
                                val request = Settings().apply { audio = false }.request()
                                link.selectSessionTelemetry(request)
                                link.send(Wire.control(Wire.SESSION_REQUEST, ID, request))
                                val acceptance = link.receive()
                                assertTrue(link.acceptedSessionTelemetry(acceptance.json()))
                                link.attach(ID, acceptance, false)
                                link.send(Wire.config(ID, 1, 1,
                                    Wire.obj("codec" to "h264", "width" to 640, "height" to 360,
                                        "csd" to JSONArray().put("AAAAAWdkACg="))))
                                assertEquals(Wire.STREAM_READY, link.receive().type)
                                session.prepare(link)
                                session.useTelemetry(telemetry)
                                val mediaFinished = CountDownLatch(2)
                                session.startWorkers(watchMedia = !peerFatal, mediaFinished = mediaFinished)
                                if (recoveryDeadline) {
                                    session.expireRecovery()
                                    session.tickWatchdog()
                                    assertFalse("Recovery timeout must leave healthy negotiated control available",
                                        session.linkClosed())
                                } else if (peerFatal) session.sender.captureFailed(
                                    java.io.IOException("AUDIO_FAILED: controlled sender failure"))
                                else {
                                    link.send(Wire.config(ID, 1, 2, Wire.obj("codec" to "invalid")))
                                    assertTrue(cleanupEntered.await(2, TimeUnit.SECONDS))
                                    assertTrue(mediaFinished.await(2, TimeUnit.SECONDS))
                                }
                                val retiring = CompletableFuture.runAsync {
                                    try { session.sender.retireTransport() }
                                    finally { retirementDone.countDown() }
                                }
                                if (delayTerminal) {
                                    assertTrue(terminalEntered.await(2, TimeUnit.SECONDS))
                                    assertFalse("Queued ERROR must retain its separate frame budget",
                                        retirementDone.await(2200, TimeUnit.MILLISECONDS))
                                    session.tickWatchdog()
                                    assertFalse(session.linkClosed())
                                    terminalRelease.countDown()
                                }
                                assertTrue(cleanupEntered.await(2, TimeUnit.SECONDS))
                                if (cleanupMode == 3) {
                                    if (retiring.isCompletedExceptionally) retiring.get(1, TimeUnit.SECONDS)
                                    assertFalse("Actual retirement must survive receiver cleanup beyond two seconds; " +
                                        "receiver=$receiverStatus; sender=${session.diagnostics}",
                                        retirementDone.await(2200, TimeUnit.MILLISECONDS))
                                    session.tickWatchdog()
                                    assertFalse(session.linkClosed())
                                    release.countDown()
                                }
                                retiring.get(9, TimeUnit.SECONDS)
                                assertEquals(0L, session.receiverReleased.count)
                                assertEquals(expectedCode,
                                    SessionOutcome.errorCode(checkNotNull(session.outcome.failure)))
                                assertTrue("A fatal cast must not invent sender cleanup failure", session.cleanupSucceeded())
                                assertTrue(telemetry.finish(session.cleanupSucceeded()))
                                assertTrue(finished.await(3, TimeUnit.SECONDS))
                                val terminal = frames.last { it.type == org.screencopy.receiver.protocol.Type.ERROR }
                                assertEquals(expectedCode,
                                    terminal.json().getString("code"))
                                val report = org.screencopy.receiver.protocol.StrictJson.decode(reports.last())
                                val remote = report.getJSONObject("receiver")
                                assertEquals(receiverStatus.toString(), cleanupMode == 0 || cleanupMode == 3,
                                    remote.getBoolean("cleanupSucceeded"))
                                assertEquals(cleanupMode == 0 || cleanupMode == 3, report.getBoolean("receiverComplete"))
                                assertTrue(report.getJSONObject("sender").getBoolean("cleanupSucceeded"))
                                if (cleanupMode == 2) {
                                    assertEquals("UNAVAILABLE", remote.getString("finalSkipReason"))
                                    assertTrue(remote.isNull("terminalSequence"))
                                    assertEquals(1L, retired.count)
                                } else {
                                    assertEquals(0L, retired.count)
                                    val index = frames.indexOf(terminal)
                                    val final = dev.redrain.cast.diagnostics.SessionTelemetryProtocol.parse(frames[index - 1].payload)
                                    assertTrue(final.final)
                                    assertTrue(final.events.any { it.value == 991L })
                                    assertEquals(final.sequence.toString(), remote.getString("finalSequence"))
                                    assertEquals(final.sequence.toString(), remote.getString("terminalSequence"))
                                }
                            } finally {
                                terminalRelease.countDown()
                                release.countDown()
                                dev.redrain.cast.diagnostics.TelemetryRegistry.end("sender", telemetry)
                                telemetry.finish()
                            }
                        }
                    }
                } finally { terminalRelease.countDown(); release.countDown() }
            }
    }

    @Test(timeout = 15_000) fun receiverFinalPreparationTimeoutReachesSavedSenderReport() {
        receiverFinalPreparationTimeoutReachesReport(false)
    }

    @Test(timeout = 15_000) fun fatalFinalPreparationTimeoutReachesSavedSenderReport() {
        receiverFinalPreparationTimeoutReachesReport(true)
    }

    private fun receiverFinalPreparationTimeoutReachesReport(fatal: Boolean) {
        org.screencopy.receiver.net.ReceiverServer.forProtocolTests("Receiver").use { receiver ->
            receiver.start()
            ReceiverLink(ReceiverTarget("127.0.0.1", receiver.listeningPort)).use { link ->
                Harness(receiver.listeningPort).use { session ->
                    val reports = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
                    val finished = CountDownLatch(1)
                    val sender = dev.redrain.cast.diagnostics.SenderTelemetrySession(true, { reports.add(it) },
                        storageExecutor = java.util.concurrent.Executor { it.run() }, onFinished = finished::countDown)
                    val entered = CountDownLatch(1)
                    val release = CountDownLatch(1)
                    val worker = ScheduledThreadPoolExecutor(1)
                    worker.execute { entered.countDown(); release.await() }
                    val blocked = dev.redrain.cast.diagnostics.ReceiverTelemetrySession({ true }, worker = worker)
                    val diagnostics = dev.redrain.cast.diagnostics.RemoteDiagnostics.recorder
                    diagnostics.grantConsent()
                    val arms = dev.redrain.cast.diagnostics.DiagnosticArmRegistry(diagnostics)
                    val pair = arms.start("collector", setOf("session"), setOf("session"), 10_000, 100)
                        .getString("pairId")
                    val claim = checkNotNull(arms.claim(ID))
                    claim.bootstrapAccepted.set(true)
                    try {
                        assertTrue(entered.await(2, TimeUnit.SECONDS))
                        link.connect()
                        val request = Settings().apply { audio = false }.request()
                        link.selectSessionTelemetry(request)
                        link.send(Wire.control(Wire.SESSION_REQUEST, ID, request))
                        val acceptance = link.receive()
                        assertTrue(link.acceptedSessionTelemetry(acceptance.json()))
                        link.attach(ID, acceptance, false)
                        val activeField = receiver.javaClass.getDeclaredField("active").apply { isAccessible = true }
                        val connection = (activeField.get(receiver) as AtomicReference<*>).get()
                        val ownerField = checkNotNull(connection).javaClass.getDeclaredField("telemetry").apply { isAccessible = true }
                        val old = ownerField.get(connection) as dev.redrain.cast.diagnostics.ReceiverTelemetrySession
                        dev.redrain.cast.diagnostics.TelemetryRegistry.end("receiver", old)
                        old.requestFinal(false, System.nanoTime())
                        ownerField.set(connection, blocked)
                        dev.redrain.cast.diagnostics.TelemetryRegistry.beginReceiver(ID, blocked)
                        session.prepare(link)
                        session.useTelemetry(sender)
                        dev.redrain.cast.diagnostics.TelemetryRegistry.event("sender", ID,
                            dev.redrain.cast.diagnostics.DiagnosticKind.SESSION.ordinal + 1,
                            dev.redrain.cast.diagnostics.DiagnosticStage.PRESSURE.ordinal, 1, 1,
                            detail = listOf(0, 0, 0, 0, 1), pressure = true)
                        session.startWorkers()
                        arms.receiverEnded(claim, expected = false)
                        assertEquals("draining", arms.status("collector", pair).getString("state"))
                        if (fatal) link.send(Wire.control(Wire.ERROR, ID,
                            Wire.obj("code" to "AUDIO_FAILED", "message" to "controlled failure", "fatal" to true)))
                        else session.sender.stop()
                        session.sender.retireTransport()
                        if (fatal) assertTrue(session.outcome.failure is ReceiverReportedFailure)
                        else assertNull(session.outcome.failure)
                        assertTrue(sender.finish())
                        assertTrue(finished.await(3, TimeUnit.SECONDS))
                        val report = org.screencopy.receiver.protocol.StrictJson.decode(reports.last())
                        assertEquals("PREPARATION_TIMEOUT", report.getJSONObject("receiver").getString("finalSkipReason"))
                        assertTrue(dev.redrain.cast.diagnostics.IncidentSummary.text(report).contains("PREPARATION_TIMEOUT"))
                        assertFalse(report.getBoolean("receiverComplete"))
                        finishSenderDiagnostic(diagnostics, arms, claim, ID, fatal, {}, { it() })
                        val frozen = arms.fetch("collector", pair, 0, 0, 32)
                        assertEquals("frozen", frozen.getString("state"))
                        val records = frozen.getJSONArray("records")
                        val details = (0 until records.length()).map { records.getJSONObject(it) }
                            .map { it.getJSONObject("record") }
                            .mapNotNull { it.getJSONObject("data").optJSONObject("metrics") }
                            .single { it.optString("schema") == "terminal_coverage" }
                            .getJSONObject("preparationTimeout")
                        assertEquals("QUEUED", details.getString("phase"))
                        assertTrue(details.getString("elapsedNs").toLong() > 0)
                        assertEquals(0, details.getInt("activeProducers"))
                    } finally {
                        release.countDown()
                        worker.shutdownNow()
                        dev.redrain.cast.diagnostics.TelemetryRegistry.end("sender", sender)
                        dev.redrain.cast.diagnostics.TelemetryRegistry.end("receiver", blocked)
                        sender.finish()
                        arms.revoke()
                        diagnostics.revoke()
                    }
                }
            }
        }
    }

    @Test(timeout = 10_000) fun stopAfterAcceptanceBeforeReaderStartupDoesNotInventTimeout() {
        runSession { server, session, link ->
            val peer = CompletableFuture.runAsync {
                server.accept().use { control ->
                    controlHandshake(control, v2 = true)
                    while (control.inputStream.read() != -1) { }
                }
            }
            link.connect()
            link.send(Wire.control(Wire.SESSION_REQUEST, ID, Settings().apply { audio = false }.request()))
            val acceptance = link.receive()
            session.prepare(link, playing = false)
            session.sender.stop()
            session.closeTransport()
            assertThrows(java.io.IOException::class.java) { session.sender.attachReceiver(link, acceptance) }
            session.sender.retireTransport()
            assertNull(session.outcome.failure)
            peer.get(3, TimeUnit.SECONDS)
        }
    }

    @Test(timeout = 10_000) fun firstFailureAndLatestCleanupFailureBothReachFinalReport() {
        runSession { server, session, link ->
            val reports = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
            val finished = CountDownLatch(1)
            val owner = dev.redrain.cast.diagnostics.SenderTelemetrySession(false, { reports.add(it) },
                storageExecutor = java.util.concurrent.Executor { it.run() }, onFinished = finished::countDown)
            val peer = CompletableFuture.runAsync {
                attachPeer(server) { control, video, audio ->
                    assertEquals(Wire.ERROR, terminal(control).type)
                    assertEquals(-1, video.inputStream.read())
                    assertEquals(-1, audio.inputStream.read())
                    assertEquals(-1, control.inputStream.read())
                }
            }
            try {
                negotiate(link)
                session.prepare(link)
                session.useTelemetry(owner, negotiated = false)
                session.startWorkers()
                val first = java.io.IOException("CAPTURE_FAILED: original failure")
                session.sender.captureFailed(first)
                val fail = SenderSession::class.java.getDeclaredMethod(
                    "fail", Exception::class.java, SessionOutcome.Origin::class.java).apply { isAccessible = true }
                fail.invoke(session.sender, java.io.IOException("INTERNAL: cleanup failed"), SessionOutcome.Origin.CLEANUP)
                session.sender.retireTransport()
                assertSame(first, session.outcome.failure)
                assertTrue(owner.finish(cleanupSucceeded = false))
                assertTrue(finished.await(3, TimeUnit.SECONDS))
                val blocks = org.screencopy.receiver.protocol.StrictJson.decode(reports.last())
                    .getJSONObject("sender").getJSONArray("blocks")
                val batch = dev.redrain.cast.diagnostics.SessionTelemetryProtocol.parse(
                    blocks.getJSONArray(blocks.length() - 1).getJSONObject(1).toString().toByteArray())
                assertEquals(IncidentEvidence.cause("CAPTURE_FAILED"), batch.firstFailure!!.value)
                val settled = batch.state.single { it.stage == dev.redrain.cast.diagnostics.DiagnosticStage.SETTLED.ordinal }
                assertEquals(IncidentEvidence.cause("CAPTURE_FAILED"), settled.value)
                val cleanup = batch.state.single { it.kind == dev.redrain.cast.diagnostics.DiagnosticKind.SESSION.ordinal + 1 &&
                    it.stage == dev.redrain.cast.diagnostics.DiagnosticStage.FAILED.ordinal &&
                    it.detail.getOrNull(1) == SessionOutcome.Origin.CLEANUP.ordinal.toLong() }
                assertEquals(IncidentEvidence.cause("TIMEOUT"), cleanup.value)
                peer.get(3, TimeUnit.SECONDS)
            } finally {
                dev.redrain.cast.diagnostics.TelemetryRegistry.end("sender", owner)
                owner.finish()
            }
        }
    }

    @Test(timeout = 20000) fun actualReceiverAndSenderReaderCompleteTelemetryOnStop() {
        for (pressure in listOf(false, true)) for (receiverStops in listOf(false, true)) {
            org.screencopy.receiver.net.ReceiverServer.forProtocolTests("Receiver").use { receiver ->
                receiver.start()
                ReceiverLink(ReceiverTarget("127.0.0.1", receiver.listeningPort)).use { link ->
                    Harness(receiver.listeningPort).use { session ->
                        val reports = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
                        val finished = CountDownLatch(1)
                        val owner = dev.redrain.cast.diagnostics.SenderTelemetrySession(true, { reports.add(it) },
                            storageExecutor = java.util.concurrent.Executor { it.run() }, onFinished = finished::countDown)
                        try {
                            link.connect()
                            assertTrue(link.sessionTelemetry)
                            val request = Settings().apply { audio = false }.request()
                            link.selectSessionTelemetry(request)
                            link.send(Wire.control(Wire.SESSION_REQUEST, ID, request))
                            val acceptance = link.receive()
                            assertTrue(link.acceptedSessionTelemetry(acceptance.json()))
                            link.attach(ID, acceptance, false)
                            session.prepare(link)
                            session.useTelemetry(owner)
                            if (pressure) dev.redrain.cast.diagnostics.TelemetryRegistry.event("receiver", ID,
                                dev.redrain.cast.diagnostics.DiagnosticKind.RECOVERY.ordinal + 1,
                                dev.redrain.cast.diagnostics.DiagnosticStage.PRESSURE.ordinal, 1, 1, 42,
                                detail = listOf(0, 0, 0, 0, 1), pressure = true)
                            session.startWorkers()
                            if (receiverStops) {
                                receiver.disconnect("done")
                                assertTrue(session.receiverReleased.await(3, TimeUnit.SECONDS))
                            } else session.sender.stop()
                            session.sender.retireTransport()
                            assertNull(session.outcome.failure)
                            assertTrue(owner.finish())
                            assertTrue(finished.await(3, TimeUnit.SECONDS))
                            if (pressure) {
                                val report = org.screencopy.receiver.protocol.StrictJson.decode(reports.last())
                                assertTrue(report.getBoolean("receiverComplete"))
                                val blocks = report.getJSONObject("receiver").getJSONArray("blocks")
                                val final = blocks.getJSONArray(blocks.length() - 1).getJSONObject(1)
                                assertEquals("42", final.getJSONArray("firstPressure").getString(5))
                            } else assertTrue(reports.isEmpty())
                        } finally {
                            dev.redrain.cast.diagnostics.TelemetryRegistry.end("sender", owner)
                            owner.finish()
                        }
                    }
                }
            }
        }
    }

    @Test(timeout = 15_000) fun malformedNegotiatedTelemetryRetainsProtocolInTerminalAndSenderReport() {
        val malformedTerminal = Wire.obj("reason" to "user", "finalSeq" to "bad", "cleanupSucceeded" to true)
        val cases = listOf(
            Triple(Wire.STATUS, "{", null),
            Triple(Wire.STATUS, Wire.obj("state" to "session-telemetry", "message" to "metadata").toString(),
                "Invalid telemetry batch fields"),
            Triple(Wire.STOP, malformedTerminal.toString(), "Invalid telemetry integer"),
            Triple(Wire.SESSION_END, malformedTerminal.toString(), "Invalid telemetry integer"),
            Triple(Wire.ERROR, Wire.obj("code" to "DECODER_FAILED", "message" to "Receiver output failed",
                "fatal" to true, "finalSeq" to "bad", "cleanupSucceeded" to true).toString(),
                "Invalid telemetry integer"))
        for ((type, payload, causeMessage) in cases) {
            runSession { server, session, link ->
                val reports = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
                val finished = CountDownLatch(1)
                val owner = dev.redrain.cast.diagnostics.SenderTelemetrySession(true, { reports.add(it) },
                    storageExecutor = java.util.concurrent.Executor { it.run() }, onFinished = finished::countDown)
                val peer = CompletableFuture.runAsync {
                    attachPeer(server, telemetry = true) { control, _, _ ->
                        Wire.write(control.outputStream, Wire.Message(type, 0, ID, 0, 0, -1, payload.toByteArray()))
                        val terminal = terminal(control)
                        assertEquals(Wire.ERROR, terminal.type)
                        assertEquals("PROTOCOL", terminal.json().getString("code"))
                        assertEquals(-1, control.inputStream.read())
                    }
                }
                try {
                    negotiate(link, telemetry = true)
                    session.prepare(link)
                    session.useTelemetry(owner)
                    session.startWorkers()
                    assertTrue(session.receiverReleased.await(3, TimeUnit.SECONDS))
                    val failure = checkNotNull(session.outcome.failure)
                    assertEquals("PROTOCOL", SessionOutcome.errorCode(failure))
                    if (causeMessage != null) {
                        assertTrue(failure.cause is org.screencopy.receiver.protocol.ProtocolException)
                        assertEquals("PROTOCOL",
                            (failure.cause as org.screencopy.receiver.protocol.ProtocolException).code)
                        assertEquals(causeMessage, failure.cause!!.message)
                    }
                    assertFalse(session.receiverTerminalSeen())
                    assertFalse(session.receiverStopSeen())
                    session.sender.retireTransport()
                    assertTrue(owner.finish(session.cleanupSucceeded()))
                    assertTrue(finished.await(3, TimeUnit.SECONDS))
                    val report = org.screencopy.receiver.protocol.StrictJson.decode(reports.last())
                    assertFalse(report.getBoolean("receiverComplete"))
                    assertTrue(report.getJSONObject("receiver").isNull("terminalSequence"))
                    val blocks = report.getJSONObject("sender").getJSONArray("blocks")
                    val batch = dev.redrain.cast.diagnostics.SessionTelemetryProtocol.parse(
                        blocks.getJSONArray(blocks.length() - 1).getJSONObject(1).toString().toByteArray())
                    assertEquals(IncidentEvidence.cause("PROTOCOL"), batch.firstFailure!!.value)
                    val settled = batch.state.single {
                        it.stage == dev.redrain.cast.diagnostics.DiagnosticStage.SETTLED.ordinal
                    }
                    assertEquals(IncidentEvidence.cause("PROTOCOL"), settled.value)
                    assertEquals(SessionOutcome.Origin.RECEIVER_READ.ordinal.toLong(), settled.detail[1])
                    assertTrue(dev.redrain.cast.diagnostics.IncidentSummary.text(report).contains("PROTOCOL"))
                    peer.get(3, TimeUnit.SECONDS)
                } finally {
                    dev.redrain.cast.diagnostics.TelemetryRegistry.end("sender", owner)
                    owner.finish()
                }
            }
        }
    }

    @Test(timeout = 15000) fun finalTelemetryIsReadDuringStopAndOnlyPressureWritesAReport() {
        for (pressure in listOf(false, true)) runSession { server, session, link ->
            val reports = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
            val finished = CountDownLatch(1)
            val owner = dev.redrain.cast.diagnostics.SenderTelemetrySession(true, { reports.add(it) },
                storageExecutor = java.util.concurrent.Executor { it.run() }, onFinished = finished::countDown)
            val peer = CompletableFuture.runAsync {
                attachPeer(server, v2 = true) { control, _, _ ->
                    assertEquals(Wire.STOP, terminal(control).type)
                    val collector = dev.redrain.cast.diagnostics.SessionTelemetryCollector()
                    if (pressure) collector.event(dev.redrain.cast.diagnostics.TelemetryRow(
                        dev.redrain.cast.diagnostics.DiagnosticKind.RECOVERY.ordinal + 1,
                        dev.redrain.cast.diagnostics.DiagnosticStage.PRESSURE.ordinal, 1, 1,
                        1, 42, listOf(0, 0, 0, 0, 1)), pressure = true)
                    collector.publish()
                    val final = collector.publish(final = true)
                    Wire.write(control.outputStream, Wire.Message(Wire.STATUS, 0, ID, 0, 0, -1, final))
                    Wire.write(control.outputStream, Wire.control(Wire.SESSION_END, ID,
                        Wire.obj("reason" to "done", "finalSeq" to "2", "cleanupSucceeded" to true)))
                    assertEquals(-1, control.inputStream.read())
                }
            }
            try {
                negotiate(link)
                session.prepare(link)
                session.useTelemetry(owner)
                session.startWorkers()
                session.sender.stop()
                session.sender.retireTransport()
                assertNull(session.outcome.failure)
                assertTrue(owner.finish())
                assertTrue(finished.await(3, TimeUnit.SECONDS))
                if (pressure) {
                    val report = org.screencopy.receiver.protocol.StrictJson.decode(reports.last())
                    assertTrue(report.getBoolean("final"))
                    assertTrue(report.getBoolean("receiverComplete"))
                    assertEquals("1", report.getJSONObject("receiver").getString("missingPublications"))
                } else assertTrue(reports.isEmpty())
                peer.get(3, TimeUnit.SECONDS)
            } finally {
                dev.redrain.cast.diagnostics.TelemetryRegistry.end("sender", owner)
                owner.finish()
            }
        }
    }

    @Test(timeout = 10_000) fun mediaAttachmentFailureOwnsInducedControlReadFailure() {
        attachmentFailure(controlFailure = null)
    }

    @Test(timeout = 10_000) fun mediaAttachmentFailureDoesNotReplaceEarlierGenuineControlFailure() {
        for (fatalPeerError in listOf(false, true)) attachmentFailure(controlFailure = fatalPeerError)
    }

    @Test(timeout = 10_000) fun mediaAttachmentDeadlineOwnsInducedControlReadFailure() {
        attachmentFailure(controlFailure = null, deadlineTimeout = true)
    }

    @Test(timeout = 10_000) fun mediaAttachmentDeadlineDoesNotReplaceEarlierGenuineControlFailure() {
        for (fatalPeerError in listOf(false, true))
            attachmentFailure(controlFailure = fatalPeerError, deadlineTimeout = true)
    }

    private fun attachmentFailure(controlFailure: Boolean?, deadlineTimeout: Boolean = false) {
        runSession { server, session, link ->
            server.soTimeout = 3000
            val readerStarted = CountDownLatch(1)
            val readerFinished = session.receiverReleased
            val deadline = AtomicReference<Runnable?>()
            val peerFailure = AtomicReference<Throwable?>()
            val peer = CompletableFuture.runAsync {
                server.accept().use { control ->
                    controlHandshake(control, v2 = true)
                    server.accept().use { video ->
                        video.soTimeout = 4000
                        assertEquals("video", Wire.read(video.inputStream).json().getString("channel"))
                        assertTrue("Control reads must start before media attachment completes",
                            readerStarted.await(2, TimeUnit.SECONDS))
                        if (controlFailure != null) {
                            if (controlFailure) Wire.write(control.outputStream, Wire.control(Wire.ERROR, ID,
                                Wire.obj("code" to "DECODER_FAILED", "message" to "Output failed", "fatal" to true)))
                            else control.shutdownOutput()
                            assertTrue(readerFinished.await(2, TimeUnit.SECONDS))
                        }
                        if (deadlineTimeout) checkNotNull(deadline.get()).run()
                        else Wire.write(video.outputStream, Wire.control(Wire.HELLO, ID, Wire.obj("channel" to "audio")))
                        assertEquals(-1, video.inputStream.read())
                        assertEquals(-1, control.inputStream.read())
                    }
                }
            }.whenComplete { _, error ->
                if (error != null) {
                    peerFailure.set(error)
                    link.close()
                }
            }
            link.connect()
            link.send(Wire.control(Wire.SESSION_REQUEST, ID, Settings().apply { audio = false }.request()))
            val acceptance = link.receive()
            val sockets = ReceiverLink::class.java.getDeclaredField("sockets").apply { isAccessible = true }
                .get(link) as Array<*>
            val controlSocket = sockets[0] as Socket
            controlSocket.soTimeout = 3000
            val observedInput = object : FilterInputStream(controlSocket.inputStream) {
                override fun read(): Int {
                    readerStarted.countDown()
                    return super.read()
                }
            }
            java.lang.reflect.Array.set(sockets, 0, object : Socket() {
                override fun getInputStream() = observedInput
                override fun getOutputStream() = controlSocket.outputStream
                override fun close() {
                    controlSocket.close()
                    // Force cleanup's read failure to be processed before attach can rethrow.
                    assertTrue(readerFinished.await(2, TimeUnit.SECONDS))
                }
            })
            for (track in 1..2) java.lang.reflect.Array.set(sockets, track, object : Socket() {
                override fun setSoTimeout(timeout: Int) = super.setSoTimeout(timeout.coerceIn(1, 3000))
                override fun getInputStream() = object : FilterInputStream(super.getInputStream()) {
                    override fun read(): Int = try { super.read() } catch (error: java.io.IOException) {
                        if (deadlineTimeout)
                            assertTrue("Control cleanup must finish before attachment resumes",
                                readerFinished.await(2, TimeUnit.SECONDS))
                        throw error
                    }
                }
            })
            if (deadlineTimeout) captureAttachmentDeadline(link, deadline)
            session.prepare(link, playing = false)
            val error = try {
                assertThrows(java.io.IOException::class.java) {
                    session.sender.attachReceiver(link, acceptance)
                }
            } finally {
                peerFailure.get()?.let { throw AssertionError("Attachment peer failed", it) }
            }
            if (deadlineTimeout) {
                assertTrue(error is java.net.SocketTimeoutException)
                assertEquals("TIMEOUT: receiver operation exceeded its deadline", error.message)
            } else assertEquals("PROTOCOL: receiver did not attach video connection", error.message)
            val retained = checkNotNull(session.outcome.failure)
            val origin = if (controlFailure == null) SessionOutcome.Origin.STARTUP else SessionOutcome.Origin.RECEIVER_READ
            when (controlFailure) {
                null -> assertSame(error, retained)
                true -> {
                    assertTrue(retained is ReceiverReportedFailure)
                    assertEquals("DECODER_FAILED", SessionOutcome.errorCode(retained))
                }
                false -> assertTrue("An earlier peer EOF must remain a real failure", retained is EOFException)
            }
            assertTrue(session.diagnostics.toString(),
                session.diagnostics.any { it.contains("stop_origin=$origin") && it.contains("retained=true") })
            assertFalse(session.diagnostics.toString(),
                session.diagnostics.any { it.contains("stop_origin=${if (controlFailure == null) "RECEIVER_READ" else "STARTUP"}") &&
                    it.contains("retained=true") })
            if (controlFailure == null) assertFalse("Attachment failure must only be published once",
                session.diagnostics.any { it.contains("stop_origin=STARTUP") && it.contains("retained=false") })
            peer.get(3, TimeUnit.SECONDS)
        }
    }

    @Test(timeout = 10_000) fun completedMediaAttachmentIgnoresAlreadyDispatchedDeadline() {
        runSession { server, _, link ->
            val peer = CompletableFuture.runAsync {
                attachPeer(server, v2 = true) { control, _, _ ->
                    assertEquals(Wire.PING, Wire.read(control.inputStream).type)
                }
            }
            link.connect()
            link.send(Wire.control(Wire.SESSION_REQUEST, ID, Settings().apply { audio = false }.request()))
            val acceptance = link.receive()
            val deadline = AtomicReference<Runnable?>()
            captureAttachmentDeadline(link, deadline)
            val failures = AtomicInteger()
            link.attach(ID, acceptance, false, onFailure = { failures.incrementAndGet() })
            checkNotNull(deadline.get()).run()
            assertEquals(0, failures.get())
            link.send(Wire.control(Wire.PING, ID, Wire.obj("nonce" to 1)))
            peer.get(3, TimeUnit.SECONDS)
        }
    }

    @Test(timeout = 10_000) fun mediaAttachmentDeadlineClosesTransportWhenFailureCallbackThrows() {
        runSession { server, _, link ->
            server.soTimeout = 3000
            val deadline = AtomicReference<Runnable?>()
            val callbackError = IllegalStateException("Failure callback failed")
            val peer = CompletableFuture.runAsync {
                server.accept().use { control ->
                    controlHandshake(control, v2 = true)
                    server.accept().use { video ->
                        video.soTimeout = 3000
                        assertEquals("video", Wire.read(video.inputStream).json().getString("channel"))
                        assertSame(callbackError, assertThrows(IllegalStateException::class.java) {
                            checkNotNull(deadline.get()).run()
                        })
                        assertEquals(-1, video.inputStream.read())
                        assertEquals(-1, control.inputStream.read())
                    }
                }
            }
            link.connect()
            link.send(Wire.control(Wire.SESSION_REQUEST, ID, Settings().apply { audio = false }.request()))
            val acceptance = link.receive()
            captureAttachmentDeadline(link, deadline)
            val failures = AtomicInteger()
            assertThrows(java.net.SocketTimeoutException::class.java) {
                link.attach(ID, acceptance, false, onFailure = {
                    failures.incrementAndGet()
                    throw callbackError
                })
            }
            peer.get(3, TimeUnit.SECONDS)
            assertEquals(1, failures.get())
        }
    }

    private fun captureAttachmentDeadline(link: ReceiverLink, deadline: AtomicReference<Runnable?>) {
        val field = ReceiverLink::class.java.getDeclaredField("timer").apply { isAccessible = true }
        val previous = field.get(link) as java.util.concurrent.ScheduledExecutorService
        val captured = Mockito.mock(ScheduledFuture::class.java)
        field.set(link, object : ScheduledThreadPoolExecutor(1) {
            override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
                if (deadline.compareAndSet(null, command)) captured else super.schedule(command, delay, unit)
        })
        previous.shutdownNow()
    }

    @Test(timeout = 15_000) fun acceptedCaptureFailureReportsProductionCleanupOutcome() {
        for (cleanupFails in listOf(false, true)) runSession { server, session, link ->
            val saved = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
            val finished = CountDownLatch(1)
            val telemetry = dev.redrain.cast.diagnostics.SenderTelemetrySession(false, { saved.add(it) },
                storageExecutor = java.util.concurrent.Executor { it.run() }, onFinished = finished::countDown)
            val peer = CompletableFuture.runAsync {
                attachPeer(server) { control, video, audio ->
                    val terminal = terminal(control)
                    assertEquals(Wire.ERROR, terminal.type)
                    assertEquals("CAPTURE_FAILED", terminal.json().getString("code"))
                    Wire.write(control.outputStream, Wire.control(Wire.ERROR, ID,
                        Wire.obj("code" to "CAPTURE_FAILED", "message" to "Sender: fixture encoder failure",
                            "fatal" to true)))
                    assertEquals(-1, video.inputStream.read())
                    assertEquals(-1, audio.inputStream.read())
                    assertEquals(-1, control.inputStream.read())
                }
            }
            try {
                negotiate(link)
                session.prepare(link)
                session.useTelemetry(telemetry)
                session.startWorkers()
                val failure = java.io.IOException("CAPTURE_FAILED: fixture encoder failure")
                session.sender.captureFailed(failure)
                if (cleanupFails) {
                    SenderSession::class.java.getDeclaredMethod(
                        "fail", Exception::class.java, SessionOutcome.Origin::class.java).apply { isAccessible = true }
                        .invoke(session.sender, java.io.IOException("TIMEOUT: cleanup failure"), SessionOutcome.Origin.CLEANUP)
                }
                Mockito.mockStatic(android.util.Log::class.java).use { session.sender.run() }
                assertSame(failure, session.outcome.failure)
                assertTrue(finished.await(3, TimeUnit.SECONDS))
                val sender = org.screencopy.receiver.protocol.StrictJson.decode(saved.last()).getJSONObject("sender")
                assertEquals(session.diagnostics.toString(), !cleanupFails, sender.getBoolean("cleanupSucceeded"))
                assertEquals(cleanupFails, session.diagnostics.any { it.contains("stop_origin=CLEANUP") })
                val blocks = sender.getJSONArray("blocks")
                val first = blocks.getJSONArray(blocks.length() - 1).getJSONObject(1).getJSONArray("firstFailure")
                assertEquals("${IncidentEvidence.cause("CAPTURE_FAILED")}", first.getString(5))
                peer.get(3, TimeUnit.SECONDS)
            } finally {
                dev.redrain.cast.diagnostics.TelemetryRegistry.end("sender", telemetry)
                telemetry.finish()
            }
        }
    }

    @Test fun captureFailurePublishesItsAssociatedTerminalCauseInFirstReport() {
        val saved = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
        val finished = CountDownLatch(1)
        val capture = dev.redrain.cast.diagnostics.SenderTelemetrySession(false, { saved.add(it) },
            storageExecutor = java.util.concurrent.Executor { it.run() }, onFinished = finished::countDown)
        Harness(1).use { harness ->
            try {
                SenderSession::class.java.getDeclaredField("session").apply { isAccessible = true }
                    .setLong(harness.sender, ID)
                harness.useTelemetry(capture)
                SenderSession::class.java.getDeclaredMethod("fail", Exception::class.java, SessionOutcome.Origin::class.java)
                    .apply { isAccessible = true }
                    .invoke(harness.sender, java.io.IOException("CAPTURE_FAILED"), SessionOutcome.Origin.CAPTURE)
                assertTrue(capture.finish(false))
                assertTrue(finished.await(3, TimeUnit.SECONDS))
                val report = org.screencopy.receiver.protocol.StrictJson.decode(saved.last())
                val blocks = report.getJSONObject("sender").getJSONArray("blocks")
                val first = blocks.getJSONArray(blocks.length() - 1).getJSONObject(1).getJSONArray("firstFailure")
                assertEquals("${IncidentEvidence.cause("CAPTURE_FAILED")}", first.getString(5))
            } finally {
                dev.redrain.cast.diagnostics.TelemetryRegistry.end("sender", capture)
                capture.finish()
            }
        }
    }

    @Test fun startupStopDoesNotClassifyMediaEofAsOrderlyStopFailure() {
        for ((playing, stopped) in listOf(false to false, false to true, true to true)) {
            Harness(1).use { session ->
                val link = Mockito.mock(ReceiverLink::class.java)
                Mockito.`when`(link.streamRecovery).thenReturn(true)
                session.prepare(link, playing = playing)
                if (stopped) session.outcome.requestStop()
                session.sender.recordMediaFailure(EOFException("media closed"), SessionOutcome.Origin.RECEIVER_READ)
                when {
                    !stopped -> assertTrue(session.outcome.failure is MediaConnectionFailure)
                    !playing -> assertNull(session.outcome.failure)
                    else -> assertTrue(session.outcome.failure?.message.orEmpty()
                        .contains("media transport failed during orderly Stop"))
                }
            }
        }
    }

    @Test(timeout = 15_000) fun mediaEofWaitsForTerminalWriteResultWithoutMaskingWriteFailure() {
        for ((remoteStop, failWrite) in listOf(false to false, true to false, false to true)) {
            runSession { server, session, link ->
                val flushed = CountDownLatch(1)
                val resume = CountDownLatch(1)
                val mediaClosed = CountDownLatch(1)
                val readersCaughtEof = CountDownLatch(2)
                val readersFinished = CountDownLatch(2)
                val peer = CompletableFuture.runAsync {
                    attachPeer(server, v2 = true) { control, video, audio ->
                        if (remoteStop)
                            Wire.write(control.outputStream, Wire.control(Wire.STOP, ID, Wire.obj("reason" to "user")))
                        assertEquals(if (remoteStop) Wire.SESSION_END else Wire.STOP, terminal(control).type)
                        video.shutdownOutput()
                        audio.shutdownOutput()
                        mediaClosed.countDown()
                        if (!remoteStop)
                            Wire.write(control.outputStream, Wire.control(Wire.SESSION_END, ID, Wire.obj("reason" to "user")))
                        assertEquals(-1, control.inputStream.read())
                    }
                }
                negotiate(link)
                val connected = Mockito.mock(ReceiverLink::class.java,
                    Mockito.withSettings().spiedInstance(link).defaultAnswer { invocation ->
                        val result = invocation.callRealMethod()
                        val message = invocation.arguments.firstOrNull() as? Wire.Message
                        if (invocation.method.name == "send" && message?.type in setOf(Wire.STOP, Wire.SESSION_END)) {
                            flushed.countDown()
                            check(resume.await(5, TimeUnit.SECONDS))
                            if (failWrite) throw java.net.SocketTimeoutException("terminal write deadline")
                        }
                        result
                    })
                try {
                    session.prepare(connected)
                    if (!remoteStop) session.sender.stop()
                    session.startWorkers(watchMedia = true, mediaEof = readersCaughtEof, mediaFinished = readersFinished)
                    assertTrue(flushed.await(2, TimeUnit.SECONDS))
                    assertTrue(mediaClosed.await(2, TimeUnit.SECONDS))
                    assertTrue(readersCaughtEof.await(2, TimeUnit.SECONDS))
                    assertFalse("EOF classification must wait for the in-flight terminal write",
                        readersFinished.await(100, TimeUnit.MILLISECONDS))
                    assertNull(session.outcome.failure)
                    resume.countDown()
                    assertTrue(readersFinished.await(2, TimeUnit.SECONDS))
                    session.sender.retireTransport()
                    peer.get(3, TimeUnit.SECONDS)
                    if (failWrite)
                        assertEquals("TIMEOUT", SessionOutcome.errorCode(checkNotNull(session.outcome.failure)))
                    else assertNull(session.outcome.failure)
                } finally { resume.countDown() }
            }
        }
    }

    @Test(timeout = 15_000) fun crossedStopsDrainStartedMediaAndAcknowledgeWithoutDuplicateTerminalWrites() {
        val failures = java.util.concurrent.ConcurrentLinkedQueue<org.screencopy.receiver.ui.FailureDiagnostic>()
        org.screencopy.receiver.net.ReceiverServer.forProtocolTests("Receiver", onFailure = failures::add).use { receiver ->
            receiver.start()
            ReceiverLink(ReceiverTarget("127.0.0.1", receiver.listeningPort)).use { link ->
                Harness(receiver.listeningPort).use { session ->
                    val entered = CountDownLatch(1)
                    val resume = CountDownLatch(1)
                    val blocked = Wire.Message(Wire.VIDEO_FRAME, 0, ID, 1, 1, 0, ByteArray(1024))
                    val connected = Mockito.spy(link)
                    Mockito.doAnswer { invocation ->
                        entered.countDown()
                        check(resume.await(5, TimeUnit.SECONDS))
                        invocation.callRealMethod()
                    }.`when`(connected).send(blocked)
                    try {
                        negotiate(connected)
                        session.prepare(connected)
                        session.mux.offer(Wire.config(ID, 1, 1, Wire.obj("codec" to "h264")))
                        session.mux.endWrite(session.mux.pollLane(1, 1))
                        session.mux.ready(1, 1)
                        session.mux.offer(blocked)
                        session.startWorkers(media = true)
                        assertTrue(entered.await(2, TimeUnit.SECONDS))
                        session.sender.stop()
                        receiver.disconnect("simultaneous receiver Stop")
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                        while (!session.receiverStopSeen() && System.nanoTime() < deadline) Thread.sleep(1)
                        assertTrue(session.receiverStopSeen())
                        val teardown = CompletableFuture.runAsync { session.sender.retireTransport() }
                        try {
                            assertThrows(java.util.concurrent.TimeoutException::class.java) {
                                teardown.get(2200, TimeUnit.MILLISECONDS)
                            }
                            assertNull(session.outcome.failure)
                            assertTrue(failures.toString(), failures.isEmpty())
                        } finally { resume.countDown() }
                        teardown.get(3, TimeUnit.SECONDS)
                        val released = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                        while (receiver.busyForUpdate && System.nanoTime() < released) Thread.sleep(1)
                        assertFalse(receiver.busyForUpdate)
                        assertNull(session.outcome.failure)
                        assertTrue(failures.toString(), failures.isEmpty())
                        val terminals = Mockito.mockingDetails(connected).invocations
                            .filter { it.method.name == "send" }
                            .mapNotNull { it.arguments.firstOrNull() as? Wire.Message }
                            .filter { it.type == Wire.STOP || it.type == Wire.SESSION_END }
                        assertEquals(listOf(Wire.STOP), terminals.map { it.type })
                    } finally { resume.countDown() }
                }
            }
        }
    }

    @Test(timeout = 10_000) fun negotiatedCrossedStopsDeliverFinalBeforeReceiverStop() {
        val failures = java.util.concurrent.ConcurrentLinkedQueue<org.screencopy.receiver.ui.FailureDiagnostic>()
        val frames = java.util.concurrent.CopyOnWriteArrayList<org.screencopy.receiver.protocol.Message>()
        val stopWritten = CountDownLatch(1)
        org.screencopy.receiver.net.ReceiverServer.forProtocolTests("Receiver", onFailure = failures::add,
            controlWrite = { socket, message ->
                org.screencopy.receiver.protocol.Wire.write(socket.outputStream, message)
                frames.add(message)
                if (message.type == org.screencopy.receiver.protocol.Type.STOP) stopWritten.countDown()
            }).use { receiver ->
            receiver.start()
            ReceiverLink(ReceiverTarget("127.0.0.1", receiver.listeningPort)).use { link ->
                Harness(receiver.listeningPort).use { session ->
                    val connected = Mockito.spy(link)
                    val reports = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
                    val finished = CountDownLatch(1)
                    val owner = dev.redrain.cast.diagnostics.SenderTelemetrySession(true, { reports.add(it) },
                        storageExecutor = java.util.concurrent.Executor { it.run() }, onFinished = finished::countDown)
                    try {
                        connected.connect()
                        assertTrue(connected.sessionTelemetry)
                        val request = Settings().apply { audio = false }.request()
                        connected.selectSessionTelemetry(request)
                        connected.send(Wire.control(Wire.SESSION_REQUEST, ID, request))
                        val acceptance = connected.receive()
                        assertTrue(connected.acceptedSessionTelemetry(acceptance.json()))
                        connected.attach(ID, acceptance, false)
                        session.prepare(connected)
                        session.useTelemetry(owner)
                        dev.redrain.cast.diagnostics.TelemetryRegistry.event("receiver", ID,
                            dev.redrain.cast.diagnostics.DiagnosticKind.RECOVERY.ordinal + 1,
                            dev.redrain.cast.diagnostics.DiagnosticStage.PRESSURE.ordinal, 1, 1, 42,
                            detail = listOf(0, 0, 0, 0, 1), pressure = true)

                        // Queue the sender STOP before either sender control worker starts.
                        session.sender.stop()
                        receiver.disconnect("crossed receiver Stop")
                        session.startWorkers()
                        assertTrue(session.receiverReleased.await(3, TimeUnit.SECONDS))
                        session.sender.retireTransport()
                        assertTrue(stopWritten.await(3, TimeUnit.SECONDS))
                        assertNull(session.outcome.failure)
                        assertTrue(failures.toString(), failures.isEmpty())
                        assertTrue(owner.finish())
                        assertTrue(finished.await(3, TimeUnit.SECONDS))

                        val terminalIndex = frames.indexOfFirst { it.type == org.screencopy.receiver.protocol.Type.STOP }
                        assertTrue(terminalIndex > 0)
                        val terminal = frames[terminalIndex]
                        val finalFrame = frames[terminalIndex - 1]
                        assertEquals(org.screencopy.receiver.protocol.Type.STATUS, finalFrame.type)
                        val final = dev.redrain.cast.diagnostics.SessionTelemetryProtocol.parse(finalFrame.payload)
                        assertTrue(final.final)
                        assertEquals(final.sequence.toString(), terminal.json().getString("finalSeq"))
                        assertTrue(terminal.json().getBoolean("cleanupSucceeded"))
                        assertEquals(42L, final.firstPressure!!.value)
                        assertEquals(1, frames.count { it.type == org.screencopy.receiver.protocol.Type.STOP ||
                            it.type == org.screencopy.receiver.protocol.Type.SESSION_END })
                        val sent = Mockito.mockingDetails(connected).invocations
                            .filter { it.method.name == "send" }
                            .mapNotNull { it.arguments.firstOrNull() as? Wire.Message }
                            .filter { it.type == Wire.STOP || it.type == Wire.SESSION_END }
                        assertEquals(listOf(Wire.STOP), sent.map { it.type })

                        val report = org.screencopy.receiver.protocol.StrictJson.decode(reports.last())
                        assertTrue(report.getBoolean("final"))
                        assertTrue(report.getBoolean("receiverComplete"))
                        val receiverReport = report.getJSONObject("receiver")
                        assertTrue(receiverReport.getBoolean("cleanupSucceeded"))
                        assertEquals(final.sequence.toString(), receiverReport.getString("finalSequence"))
                        assertEquals(final.sequence.toString(), receiverReport.getString("terminalSequence"))
                        val senderReport = report.getJSONObject("sender")
                        assertTrue(senderReport.getBoolean("cleanupSucceeded"))
                        assertEquals(senderReport.getString("finalSequence"), senderReport.getString("terminalSequence"))
                    } finally {
                        dev.redrain.cast.diagnostics.TelemetryRegistry.end("sender", owner)
                        owner.finish()
                    }
                }
            }
        }
    }

    @Test(timeout = 10_000) fun v2ReceiverStopDuringMediaAttachmentRetainsFastSetupCancellation() {
        runSession { server, session, link ->
            val peer = CompletableFuture.runAsync {
                server.accept().use { control ->
                    controlHandshake(control, v2 = true)
                    server.accept().use { video ->
                        video.soTimeout = 4000
                        assertEquals(Wire.HELLO, Wire.read(video.inputStream).type)
                        acknowledgeInitialPing(control)
                        Wire.write(control.outputStream, Wire.control(Wire.STOP, ID, Wire.obj("reason" to "user")))
                        assertEquals(Wire.SESSION_END, terminal(control).type)
                        assertEquals(-1, video.inputStream.read())
                        assertEquals(-1, control.inputStream.read())
                    }
                }
            }
            link.connect()
            link.send(Wire.control(Wire.SESSION_REQUEST, ID, Settings().apply { audio = false }.request()))
            val acceptance = link.receive()
            session.prepare(link, playing = false)
            session.startWorkers()
            val attaching = CompletableFuture.runAsync {
                try {
                    link.attach(ID, acceptance, false)
                    fail("Stop must cancel the unfinished media attachment")
                } catch (error: java.io.IOException) {
                    session.outcome.record(error, SessionOutcome.Origin.STARTUP)
                }
            }
            attaching.get(3, TimeUnit.SECONDS)
            session.sender.retireTransport()
            peer.get(3, TimeUnit.SECONDS)
            assertNull(session.outcome.failure)
        }
    }

    @Test(timeout = 10_000) fun v2LocalStopIgnoresLateRecoveryAndReadyButRetainsFatalPeerError() {
        for (fatal in listOf(false, true)) runSession { server, session, link ->
            val peer = CompletableFuture.runAsync {
                attachPeer(server, v2 = true) { control, video, audio ->
                    assertEquals(Wire.STOP, terminal(control).type)
                    Wire.write(control.outputStream, Wire.control(Wire.STATUS, ID,
                        Wire.obj("state" to "stream-recovery-request", "message" to "late", "track" to 1, "generation" to 1)))
                    Wire.write(control.outputStream, Wire.Message(Wire.STREAM_READY, 0, ID, 1, 2, -1,
                        Wire.obj("ok" to true).toString().toByteArray()))
                    Wire.write(control.outputStream, if (fatal) Wire.control(Wire.ERROR, ID,
                        Wire.obj("code" to "DECODER_FAILED", "message" to "Output failed", "fatal" to true))
                    else Wire.control(Wire.SESSION_END, ID, Wire.obj("reason" to "user")))
                    assertEquals(-1, video.inputStream.read())
                    assertEquals(-1, audio.inputStream.read())
                    assertEquals(-1, control.inputStream.read())
                }
            }
            negotiate(link)
            session.prepare(link)
            session.startWorkers()
            session.sender.stop()
            session.sender.retireTransport()
            peer.get(3, TimeUnit.SECONDS)
            if (fatal) {
                assertTrue(session.outcome.failure is ReceiverReportedFailure)
                assertEquals("DECODER_FAILED", SessionOutcome.errorCode(checkNotNull(session.outcome.failure)))
            } else assertNull(session.outcome.failure)
        }
    }

    @Test(timeout = 10_000) fun localStopDuringRecoveryUsesEstablishedOrderlyTeardown() {
        runSession { server, session, link ->
            val peer = CompletableFuture.runAsync {
                attachPeer(server, v2 = true) { control, video, audio ->
                    assertEquals(Wire.STOP, terminal(control).type)
                    Wire.write(control.outputStream, Wire.control(Wire.SESSION_END, ID, Wire.obj("reason" to "user")))
                    assertEquals(-1, video.inputStream.read())
                    assertEquals(-1, audio.inputStream.read())
                    assertEquals(-1, control.inputStream.read())
                }
            }
            negotiate(link)
            session.prepare(link)
            session.setRecovering()
            session.startWorkers()
            session.sender.stop()
            session.sender.retireTransport()
            peer.get(3, TimeUnit.SECONDS)
            assertNull(session.outcome.failure)
            assertFalse(session.diagnostics.any { it.contains("STARTUP_CANCEL") })
        }
    }

    @Test(timeout = 15_000) fun v2ReceiverStopFinishesStartedMediaBeforeSessionEndWithoutTwoSecondAbort() {
        for (playing in listOf(false, true)) runSession { server, session, link ->
            val entered = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val blocked = if (playing) Wire.Message(Wire.VIDEO_FRAME, 0, ID, 1, 1, 0, ByteArray(1024))
                else Wire.config(ID, 1, 1, Wire.obj("codec" to "h264"))
            val peer = CompletableFuture.runAsync {
                attachPeer(server, v2 = true) { control, video, audio ->
                    assertTrue(entered.await(2, TimeUnit.SECONDS))
                    acknowledgeInitialPing(control)
                    Wire.write(control.outputStream, Wire.control(Wire.STOP, ID, Wire.obj("reason" to "user")))
                    val frame = Wire.read(video.inputStream)
                    assertEquals(blocked.type, frame.type)
                    assertArrayEquals(blocked.payload, frame.payload)
                    assertEquals(Wire.SESSION_END, terminal(control).type)
                    assertEquals(-1, video.inputStream.read())
                    assertEquals(-1, audio.inputStream.read())
                    assertEquals(-1, control.inputStream.read())
                }
            }
            val connected = Mockito.spy(link)
            Mockito.doAnswer { invocation ->
                entered.countDown()
                check(resume.await(5, TimeUnit.SECONDS))
                invocation.callRealMethod()
            }.`when`(connected).send(blocked)
            negotiate(connected)
            session.prepare(connected, playing = playing)
            val mux = session.mux
            if (playing) {
                mux.offer(Wire.config(ID, 1, 1, Wire.obj("codec" to "h264")))
                mux.endWrite(mux.pollLane(1, 1))
                mux.ready(1, 1)
            }
            mux.offer(blocked)
            mux.offer(Wire.Message(Wire.VIDEO_FRAME, 0, ID, 1, 1, 1, ByteArray(20)))
            session.startWorkers(media = true)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (!session.outcome.stopping && System.nanoTime() < deadline) Thread.sleep(1)
            assertTrue(session.outcome.stopping)
            val teardown = CompletableFuture.runAsync { session.sender.retireTransport() }
            try {
                assertThrows(java.util.concurrent.TimeoutException::class.java) { teardown.get(2200, TimeUnit.MILLISECONDS) }
                assertNull("Stop must not manufacture a two-second transport failure", session.outcome.failure)
            } finally { resume.countDown() }
            teardown.get(3, TimeUnit.SECONDS)
            peer.get(3, TimeUnit.SECONDS)
            assertNull(session.outcome.failure)
            assertEquals(0, mux.queuedBytes())
        }
    }

    @Test(timeout = 10_000) fun v2ActualMediaWriteTimeoutDuringStopRemainsFatal() {
        runSession { server, session, link ->
            val entered = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val pendingAtClose = java.util.concurrent.atomic.AtomicReference<Boolean?>(null)
            val peer = CompletableFuture.runAsync {
                attachPeer(server, v2 = true) { control, video, audio ->
                    assertTrue(entered.await(2, TimeUnit.SECONDS))
                    acknowledgeInitialPing(control)
                    Wire.write(control.outputStream, Wire.control(Wire.STOP, ID, Wire.obj("reason" to "user")))
                    assertEquals(-1, video.inputStream.read())
                    assertEquals(-1, audio.inputStream.read())
                    assertEquals(-1, control.inputStream.read())
                }
            }
            val connected = Mockito.spy(link)
            Mockito.doAnswer { invocation ->
                pendingAtClose.compareAndSet(null, session.hasPendingMediaWrite())
                invocation.callRealMethod()
            }.`when`(connected).close()
            val blocked = Wire.Message(Wire.VIDEO_FRAME, 0, ID, 1, 1, 0, ByteArray(1024))
            Mockito.doAnswer {
                entered.countDown()
                check(resume.await(5, TimeUnit.SECONDS))
                throw java.net.SocketTimeoutException("Original write deadline expired")
            }.`when`(connected).send(blocked)
            negotiate(connected)
            session.prepare(connected)
            session.mux.offer(Wire.config(ID, 1, 1, Wire.obj("codec" to "h264")))
            session.mux.endWrite(session.mux.pollLane(1, 1)); session.mux.ready(1, 1)
            session.mux.offer(blocked)
            session.startWorkers(media = true)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (!session.outcome.stopping && System.nanoTime() < deadline) Thread.sleep(1)
            assertTrue(session.outcome.stopping)
            resume.countDown()
            peer.get(3, TimeUnit.SECONDS)
            assertTrue("Fatal closure must precede releasing the terminal media fence", pendingAtClose.get() == true)
            assertEquals("TIMEOUT", SessionOutcome.errorCode(checkNotNull(session.outcome.failure)))
            session.sender.retireTransport()
            assertEquals("TIMEOUT", SessionOutcome.errorCode(checkNotNull(session.outcome.failure)))
        }
    }

    @Test(timeout = 10_000) fun intentionalStartupStopDoesNotRequireAnAckAfterLocalTransportAbort() {
        runSession { server, session, link ->
            val closed = CountDownLatch(1)
            val pingRead = CountDownLatch(1)
            val localClosed = CountDownLatch(1)
            val connected = Mockito.spy(link)
            Mockito.doAnswer { invocation ->
                invocation.callRealMethod().also { localClosed.countDown() }
            }.`when`(connected).close()
            val peer = CompletableFuture.runAsync {
                attachPeer(server) { control, video, audio ->
                    assertEquals(Wire.PING, Wire.read(control.inputStream).type)
                    pingRead.countDown()
                    assertTrue(localClosed.await(2, TimeUnit.SECONDS))
                    // This peer deliberately sends no response after the intentional local abort.
                    assertEquals(Wire.STOP, Wire.read(control.inputStream).type)
                    assertEquals(-1, video.inputStream.read())
                    assertEquals(-1, audio.inputStream.read())
                    assertEquals(-1, control.inputStream.read())
                    closed.countDown()
                }
            }
            negotiate(connected)
            session.prepare(connected, playing = false)
            session.startWorkers()
            assertTrue(pingRead.await(2, TimeUnit.SECONDS))
            session.sender.stop()
            assertTrue("Startup cancellation must actively unblock transport", closed.await(2, TimeUnit.SECONDS))
            session.sender.retireTransport()
            assertNull("Intentional startup abort is not a peer protocol failure", session.outcome.failure)
            peer.get(3, TimeUnit.SECONDS)
        }
    }

    @Test(timeout = 10_000) fun nonStartupControlCloseStillRequiresAcknowledgement() {
        runSession { server, session, link ->
            val stopped = CountDownLatch(1)
            val peer = CompletableFuture.runAsync {
                attachPeer(server) { control, video, audio ->
                    assertEquals(Wire.STOP, terminal(control).type)
                    stopped.countDown()
                    assertEquals(-1, video.inputStream.read())
                    assertEquals(-1, audio.inputStream.read())
                    assertEquals(-1, control.inputStream.read())
                }
            }
            negotiate(link)
            session.prepare(link)
            session.startWorkers()
            session.sender.stop()
            assertTrue(stopped.await(1, TimeUnit.SECONDS))
            session.closeTransport()
            session.sender.retireTransport()
            assertEquals("PROTOCOL", SessionOutcome.errorCode(checkNotNull(session.outcome.failure)))
            peer.get(3, TimeUnit.SECONDS)
        }
    }

    @Test(timeout = 10_000) fun normalStopKeepsReadingControlUntilDelayedEndAfterMediaClose() {
        runSession { server, session, link ->
            val peer = CompletableFuture.runAsync {
                attachPeer(server) { control, video, audio ->
                    assertEquals(Wire.STOP, terminal(control).type)
                    assertEquals(-1, video.inputStream.read())
                    assertEquals(-1, audio.inputStream.read())
                    Wire.write(control.outputStream, Wire.control(Wire.PING, ID, Wire.obj("nonce" to 3)))
                    Thread.sleep(350)
                    Wire.write(control.outputStream, Wire.control(Wire.SESSION_END, ID, Wire.obj("reason" to "user")))
                    assertEquals(-1, control.inputStream.read())
                }
            }
            negotiate(link)
            session.prepare(link)
            session.startWorkers()
            session.sender.stop()
            session.sender.retireTransport()
            assertNull(session.outcome.failure)
            peer.get(3, TimeUnit.SECONDS)
        }
    }

    @Test(timeout = 10_000) fun delayedReceiverErrorSurvivesEarlierMediaEofAndCleanupPings() {
        runSession { server, session, link ->
            val reports = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
            val finished = CountDownLatch(1)
            val telemetry = dev.redrain.cast.diagnostics.SenderTelemetrySession(false, { reports.add(it) },
                storageExecutor = java.util.concurrent.Executor { it.run() }, onFinished = finished::countDown)
            val peer = CompletableFuture.runAsync {
                attachPeer(server) { control, video, audio ->
                    video.close()
                    audio.close()
                    assertEquals(Wire.ERROR, terminal(control).type)
                    Wire.write(control.outputStream, Wire.control(Wire.PING, ID, Wire.obj("nonce" to 4)))
                    Thread.sleep(350)
                    Wire.write(control.outputStream, Wire.control(Wire.ERROR, ID,
                        Wire.obj("code" to "DECODER_FAILED", "message" to "Receiver output failed", "fatal" to true)))
                    assertEquals(-1, control.inputStream.read())
                }
            }
            negotiate(link)
            session.prepare(link)
            session.useTelemetry(telemetry)
            session.startWorkers()
            try { link.watchMedia(1); fail("Peer must close video") }
            catch (e: EOFException) { session.sender.recordMediaFailure(e, SessionOutcome.Origin.RECEIVER_READ) }
            assertTrue(session.outcome.failure is MediaConnectionFailure)
            repeat(256) {
                dev.redrain.cast.diagnostics.TelemetryRegistry.event("sender", ID,
                    dev.redrain.cast.diagnostics.DiagnosticKind.SESSION.ordinal + 1,
                    dev.redrain.cast.diagnostics.DiagnosticStage.CONFIG.ordinal, 0, 0)
            }
            session.sender.retireTransport()
            assertTrue(session.outcome.failure is ReceiverReportedFailure)
            assertEquals("DECODER_FAILED", SessionOutcome.errorCode(checkNotNull(session.outcome.failure)))
            assertTrue(session.diagnostics.any { it.startsWith("receiver_session_error ") && it.contains("DECODER_FAILED") })
            assertFalse(session.diagnostics.any { it.contains("Receiver output failed") })
            assertTrue(telemetry.finish())
            assertTrue(finished.await(3, TimeUnit.SECONDS))
            val report = org.screencopy.receiver.protocol.StrictJson.decode(reports.last())
            val blocks = report.getJSONObject("sender").getJSONArray("blocks")
            val batch = dev.redrain.cast.diagnostics.SessionTelemetryProtocol.parse(
                blocks.getJSONArray(blocks.length() - 1).getJSONObject(1).toString().toByteArray())
            assertEquals(IncidentEvidence.cause("PROTOCOL"), batch.firstFailure!!.value)
            val settled = batch.state.single { it.stage == dev.redrain.cast.diagnostics.DiagnosticStage.SETTLED.ordinal }
            assertEquals(IncidentEvidence.cause("DECODER_FAILED"), settled.value)
            assertTrue(batch.dropped > 0)
            assertTrue(dev.redrain.cast.diagnostics.IncidentSummary.text(report).contains("DECODER_FAILED"))
            dev.redrain.cast.diagnostics.TelemetryRegistry.end("sender", telemetry)
            peer.get(3, TimeUnit.SECONDS)
        }
    }

    @Test(timeout = 10_000) fun missingTerminalResponseTimesOutWithoutAnUnboundedControlWait() {
        for (v2 in listOf(false, true)) runSession { server, session, link ->
            val peer = CompletableFuture.runAsync {
                attachPeer(server, v2 = v2) { control, video, audio ->
                    terminal(control)
                    assertEquals(-1, video.inputStream.read())
                    assertEquals(-1, audio.inputStream.read())
                    assertEquals(-1, control.inputStream.read())
                }
            }
            negotiate(link)
            session.prepare(link)
            session.startWorkers()
            session.sender.stop()
            val started = System.nanoTime()
            session.sender.retireTransport()
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue("Teardown deadline must be measured, not skipped: $elapsedMs ms", elapsedMs >= 1500)
            assertTrue("Control teardown exceeded its bound: $elapsedMs ms", elapsedMs < 3000)
            assertEquals("TIMEOUT", SessionOutcome.errorCode(checkNotNull(session.outcome.failure)))
            peer.get(3, TimeUnit.SECONDS)
        }
    }

    private fun runSession(use: (ServerSocket, Harness, ReceiverLink) -> Unit) {
        ServerSocket(0).use { server ->
            ReceiverLink(ReceiverTarget("127.0.0.1", server.localPort)).use { link ->
                Harness(server.localPort).use { use(server, it, link) }
            }
        }
    }

    private class Harness(port: Int) : AutoCloseable {
        val diagnostics = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val sender = SenderSession(Mockito.mock(Context::class.java), Settings().apply { audio = false },
            ReceiverTarget("127.0.0.1", port), {}, {}, diagnostics::add)
        val outcome = field("outcome").get(sender) as SessionOutcome
        val mux = field("mux").get(sender) as MediaBudget
        val receiverReleased = field("receiverReleased").get(sender) as CountDownLatch
        private val workers = mutableListOf<Thread>()

        fun prepare(link: ReceiverLink, playing: Boolean = true) {
            field("session").setLong(sender, ID)
            field("link").set(sender, link)
            for (name in listOf("requestSent", "acceptedSession")) field(name).setBoolean(sender, true)
            field("playing").setBoolean(sender, playing)
            field("established").setBoolean(sender, playing)
        }

        fun setRecovering() = field("playing").setBoolean(sender, false)

        fun useTelemetry(owner: dev.redrain.cast.diagnostics.SenderTelemetrySession, negotiated: Boolean = true) {
            field("telemetry").set(sender, owner)
            field("telemetryNegotiated").setBoolean(sender, negotiated)
            dev.redrain.cast.diagnostics.TelemetryRegistry.beginSender(ID, owner)
        }

        fun closeTransport() {
            SenderSession::class.java.getDeclaredMethod("closeLink").apply { isAccessible = true }.invoke(sender)
        }

        fun hasPendingMediaWrite(): Boolean =
            (MediaBudget::class.java.getDeclaredField("pendingWrites").apply { isAccessible = true }.get(mux) as Array<*>)[1] != null

        fun receiverStopSeen(): Boolean = field("remoteStop").getBoolean(sender)
        fun receiverTerminalSeen(): Boolean =
            (field("receiverTerminal").get(sender) as java.util.concurrent.atomic.AtomicBoolean).get()

        fun tickWatchdog() {
            SenderSession::class.java.getDeclaredMethod("watchdog").apply { isAccessible = true }.invoke(sender)
        }

        fun linkClosed(): Boolean = field("linkClosed").getBoolean(sender)
        fun cleanupSucceeded(): Boolean = field("cleanupSucceeded").getBoolean(sender)

        fun expireRecovery() {
            val now = AtomicLong(1_000_000_000)
            val recovery = StreamRecoverySender(ID, mux, now::get, withAudio = false)
            recovery.config(1, 1, Wire.obj("codec" to "h264"))
            val config = checkNotNull(mux.pollLane(1, 1))
            mux.beginWrite(config); mux.endWrite(config); mux.ready(1, 1)
            recovery.ready()
            recovery.request(1, 1)
            now.addAndGet(10_000_000_000)
            field("recovery").set(sender, recovery)
        }

        fun prepareRecovery(receiverRequest: CountDownLatch) {
            val recovery = StreamRecoverySender(ID, mux, diagnostic = {
                if (it.contains("reason=receiver")) receiverRequest.countDown()
            }, withAudio = false)
            recovery.config(1, 1, Wire.obj("codec" to "h264"))
            val config = checkNotNull(mux.pollLane(1, 1))
            mux.beginWrite(config); mux.endWrite(config); mux.ready(1, 1)
            recovery.ready()
            field("recovery").set(sender, recovery)
        }

        fun startWorkers(media: Boolean = false, watchMedia: Boolean = false,
                         mediaEof: CountDownLatch? = null, mediaFinished: CountDownLatch? = null) {
            val entered = CountDownLatch(1)
            val read = SenderSession::class.java.getDeclaredMethod("readLoop").apply { isAccessible = true }
            val write = SenderSession::class.java.getDeclaredMethod("writeLoop", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
            field("receiverReaderStarted").setBoolean(sender, true)
            workers.add(Thread({ entered.countDown(); read.invoke(sender) }, "fixture-control-reader").also { it.start() })
            workers.add(Thread({ write.invoke(sender, 0) }, "fixture-control-writer").also { it.start() })
            if (media) workers.add(Thread({ write.invoke(sender, 1) }, "fixture-media-writer").also { it.start() })
            if (watchMedia) for (track in 1..2) workers.add(Thread({
                try { checkNotNull(field("link").get(sender) as? ReceiverLink).watchMedia(track) }
                catch (error: java.io.IOException) {
                    if (error is EOFException) mediaEof?.countDown()
                    sender.recordMediaFailure(error, SessionOutcome.Origin.RECEIVER_READ)
                } finally { mediaFinished?.countDown() }
            }, "fixture-media-reader-$track").also { it.start() })
            assertTrue(entered.await(1, TimeUnit.SECONDS))
        }

        override fun close() {
            (field("link").get(sender) as? ReceiverLink)?.close()
            val sessionWorkers = (field("threads").get(sender) as List<*>).filterIsInstance<Thread>()
            (workers + sessionWorkers).forEach { it.interrupt(); it.join(2000); assertFalse(it.isAlive) }
            (field("timer").get(sender) as java.util.concurrent.ScheduledExecutorService).shutdownNow()
        }

        private fun field(name: String) = SenderSession::class.java.getDeclaredField(name).apply { isAccessible = true }
    }

    private fun negotiate(link: ReceiverLink, telemetry: Boolean = false) {
        link.connect()
        val request = Settings().apply { audio = false }.request()
        if (telemetry) {
            assertTrue(link.sessionTelemetry)
            link.selectSessionTelemetry(request)
        }
        link.send(Wire.control(Wire.SESSION_REQUEST, ID, request))
        val acceptance = link.receive()
        if (telemetry) assertTrue(link.acceptedSessionTelemetry(acceptance.json()))
        link.attach(ID, acceptance, false)
    }

    private fun attachPeer(server: ServerSocket, v2: Boolean = false, telemetry: Boolean = false,
                           use: (Socket, Socket, Socket) -> Unit) {
        server.accept().use { control ->
            controlHandshake(control, v2, telemetry)
            fun attach(channel: String): Socket = server.accept().apply {
                soTimeout = 4000
                assertEquals(channel, Wire.read(inputStream).json().getString("channel"))
                Wire.write(outputStream, Wire.control(Wire.HELLO, ID, Wire.obj("channel" to channel)))
            }
            attach("video").use { video -> attach("audio").use { audio -> use(control, video, audio) } }
        }
    }

    private fun controlHandshake(control: Socket, v2: Boolean, telemetry: Boolean = false) {
        control.soTimeout = 4000
        assertEquals(Wire.HELLO, Wire.read(control.inputStream).type)
        Wire.write(control.outputStream, Wire.control(Wire.HELLO, 0,
            Wire.obj("role" to "receiver", "name" to "Fixture", "versions" to JSONArray().put(3), "videoRotation" to true).apply {
                if (v2) put("streamRecovery", true).put("streamRecoveryVersion", 2)
                if (telemetry) put("sessionTelemetry", dev.redrain.cast.diagnostics.SessionTelemetryProtocol.capability())
            }))
        assertEquals(Wire.SESSION_REQUEST, Wire.read(control.inputStream).type)
        Wire.write(control.outputStream, Wire.control(Wire.SESSION_ACCEPT, ID,
            Wire.obj("tracks" to JSONArray().put(1), "mediaToken" to "a".repeat(64)).apply {
                if (telemetry) put("sessionTelemetry", dev.redrain.cast.diagnostics.SessionTelemetryProtocol.capability())
            }))
    }

    private fun acknowledgeInitialPing(control: Socket) {
        val ping = Wire.read(control.inputStream)
        assertEquals(Wire.PING, ping.type)
        Wire.write(control.outputStream, Wire.control(Wire.PONG, ID,
            Wire.obj("nonce" to ping.json().getInt("nonce"))))
    }

    private fun terminal(control: Socket): Wire.Message {
        while (true) {
            val message = Wire.read(control.inputStream)
            if (message.type != Wire.PING) return message
            Wire.write(control.outputStream, Wire.control(Wire.PONG, ID,
                Wire.obj("nonce" to message.json().getInt("nonce"))))
        }
    }

    companion object {
        private const val ID = 71L

        @BeforeClass @JvmStatic fun initializeContextMock() {
            // Class generation is fixture setup, not part of the timed transport regression.
            Mockito.mock(Context::class.java)
        }
    }
}

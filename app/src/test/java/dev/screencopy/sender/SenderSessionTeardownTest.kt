package dev.screencopy.sender

import android.content.Context
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import java.io.EOFException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SenderSessionTeardownTest {
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
            val peer = CompletableFuture.runAsync {
                attachPeer(server) { control, video, audio ->
                    assertEquals(Wire.STOP, terminal(control).type)
                    assertEquals(-1, video.inputStream.read())
                    assertEquals(-1, audio.inputStream.read())
                    assertEquals(-1, control.inputStream.read())
                    closed.countDown()
                }
            }
            negotiate(link)
            session.prepare(link, playing = false)
            session.startWorkers()
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
            session.startWorkers()
            try { link.watchMedia(1); fail("Peer must close video") }
            catch (e: EOFException) { session.sender.recordMediaFailure(e, SessionOutcome.Origin.RECEIVER_READ) }
            assertTrue(session.outcome.failure is MediaConnectionFailure)
            session.sender.retireTransport()
            assertTrue(session.outcome.failure is ReceiverReportedFailure)
            assertEquals("DECODER_FAILED", SessionOutcome.errorCode(checkNotNull(session.outcome.failure)))
            assertTrue(session.diagnostics.any { it.startsWith("receiver_session_error ") && it.contains("DECODER_FAILED") })
            assertFalse(session.diagnostics.any { it.contains("Receiver output failed") })
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
        private val workers = mutableListOf<Thread>()

        fun prepare(link: ReceiverLink, playing: Boolean = true) {
            field("session").setLong(sender, ID)
            field("link").set(sender, link)
            for (name in listOf("requestSent", "acceptedSession")) field(name).setBoolean(sender, true)
            field("playing").setBoolean(sender, playing)
        }

        fun closeTransport() {
            SenderSession::class.java.getDeclaredMethod("closeLink").apply { isAccessible = true }.invoke(sender)
        }

        fun hasPendingMediaWrite(): Boolean =
            (MediaBudget::class.java.getDeclaredField("pendingWrites").apply { isAccessible = true }.get(mux) as Array<*>)[1] != null

        fun receiverStopSeen(): Boolean = field("remoteStop").getBoolean(sender)

        fun startWorkers(media: Boolean = false) {
            val entered = CountDownLatch(1)
            val read = SenderSession::class.java.getDeclaredMethod("readLoop").apply { isAccessible = true }
            val write = SenderSession::class.java.getDeclaredMethod("writeLoop", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
            workers.add(Thread({ entered.countDown(); read.invoke(sender) }, "fixture-control-reader").also { it.start() })
            workers.add(Thread({ write.invoke(sender, 0) }, "fixture-control-writer").also { it.start() })
            if (media) workers.add(Thread({ write.invoke(sender, 1) }, "fixture-media-writer").also { it.start() })
            assertTrue(entered.await(1, TimeUnit.SECONDS))
        }

        override fun close() {
            (field("link").get(sender) as? ReceiverLink)?.close()
            workers.forEach { it.interrupt(); it.join(2000); assertFalse(it.isAlive) }
            (field("timer").get(sender) as java.util.concurrent.ScheduledExecutorService).shutdownNow()
        }

        private fun field(name: String) = SenderSession::class.java.getDeclaredField(name).apply { isAccessible = true }
    }

    private fun negotiate(link: ReceiverLink) {
        link.connect()
        link.send(Wire.control(Wire.SESSION_REQUEST, ID, Settings().apply { audio = false }.request()))
        link.attach(ID, link.receive(), false)
    }

    private fun attachPeer(server: ServerSocket, v2: Boolean = false, use: (Socket, Socket, Socket) -> Unit) {
        server.accept().use { control ->
            controlHandshake(control, v2)
            fun attach(channel: String): Socket = server.accept().apply {
                soTimeout = 4000
                assertEquals(channel, Wire.read(inputStream).json().getString("channel"))
                Wire.write(outputStream, Wire.control(Wire.HELLO, ID, Wire.obj("channel" to channel)))
            }
            attach("video").use { video -> attach("audio").use { audio -> use(control, video, audio) } }
        }
    }

    private fun controlHandshake(control: Socket, v2: Boolean) {
        control.soTimeout = 4000
        assertEquals(Wire.HELLO, Wire.read(control.inputStream).type)
        Wire.write(control.outputStream, Wire.control(Wire.HELLO, 0,
            Wire.obj("role" to "receiver", "name" to "Fixture", "versions" to JSONArray().put(3), "videoRotation" to true).apply {
                if (v2) put("streamRecovery", true).put("streamRecoveryVersion", 2)
            }))
        assertEquals(Wire.SESSION_REQUEST, Wire.read(control.inputStream).type)
        Wire.write(control.outputStream, Wire.control(Wire.SESSION_ACCEPT, ID,
            Wire.obj("tracks" to JSONArray().put(1), "mediaToken" to "a".repeat(64))))
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

    companion object { private const val ID = 71L }
}

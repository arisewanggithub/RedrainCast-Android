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
        runSession { server, session, link ->
            val peer = CompletableFuture.runAsync {
                attachPeer(server) { control, video, audio ->
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

        fun startWorkers() {
            val entered = CountDownLatch(1)
            val read = SenderSession::class.java.getDeclaredMethod("readLoop").apply { isAccessible = true }
            val write = SenderSession::class.java.getDeclaredMethod("writeLoop", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
            workers.add(Thread({ entered.countDown(); read.invoke(sender) }, "fixture-control-reader").also { it.start() })
            workers.add(Thread({ write.invoke(sender, 0) }, "fixture-control-writer").also { it.start() })
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

    private fun attachPeer(server: ServerSocket, use: (Socket, Socket, Socket) -> Unit) {
        server.accept().use { control ->
            control.soTimeout = 4000
            assertEquals(Wire.HELLO, Wire.read(control.inputStream).type)
            Wire.write(control.outputStream, Wire.control(Wire.HELLO, 0,
                Wire.obj("role" to "receiver", "name" to "Fixture", "versions" to JSONArray().put(3), "videoRotation" to true)))
            assertEquals(Wire.SESSION_REQUEST, Wire.read(control.inputStream).type)
            Wire.write(control.outputStream, Wire.control(Wire.SESSION_ACCEPT, ID,
                Wire.obj("tracks" to JSONArray().put(1), "mediaToken" to "a".repeat(64))))
            fun attach(channel: String): Socket = server.accept().apply {
                soTimeout = 4000
                assertEquals(channel, Wire.read(inputStream).json().getString("channel"))
                Wire.write(outputStream, Wire.control(Wire.HELLO, ID, Wire.obj("channel" to channel)))
            }
            attach("video").use { video -> attach("audio").use { audio -> use(control, video, audio) } }
        }
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

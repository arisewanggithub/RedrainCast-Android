package dev.screencopy.sender

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class SenderWriteCompletionTest {
    @Test fun successfulLateAudioWriteDoesNotTurnStopOrSizeSwitchIntoARecoveryTimeout() {
        for (sizeSwitch in listOf(false, true)) Harness().use { h ->
            h.startWrite()
            if (sizeSwitch) assertTrue(h.sender.stopForSizeSwitch()) else h.sender.stop()
            h.expireRecoveryAndReleaseWrite()
            assertNull(h.outcome.failure)
            if (sizeSwitch) assertTrue(h.authorization.requested)
        }
    }

    @Test fun successfulLateAudioWriteDuringCaptureRetirementDoesNotRecheckRecovery() {
        Harness().use { h ->
            h.startWrite()
            val captureCleanup = h.field("captureCleanup").get(h.sender)
            val cleanup = Thread(h.sender::retireTransport, "fixture-retirement")
            (h.field("terminalSent").get(h.sender) as CountDownLatch).countDown()
            (h.field("receiverReleased").get(h.sender) as CountDownLatch).countDown()
            (h.field("receiverTerminal").get(h.sender) as AtomicBoolean).set(true)
            try {
                synchronized(captureCleanup) {
                    cleanup.start()
                    await { h.field("closing").getBoolean(h.sender) }
                    h.expireRecoveryAndReleaseWrite()
                    assertNull(h.outcome.failure)
                }
            } finally {
                h.release.countDown()
                cleanup.join(3000)
            }
            assertFalse(cleanup.isAlive)
            assertNull(h.outcome.failure)
        }
    }

    @Test fun activeLateAudioWriteStillEnforcesTheRecoveryDeadline() {
        Harness().use { h ->
            h.startWrite()
            h.expireRecoveryAndReleaseWrite()
            assertEquals("TIMEOUT", SessionOutcome.errorCode(checkNotNull(h.outcome.failure)))
        }
    }

    @Test fun transportWriteFailureAfterStopIsStillRetained() {
        Harness(failWrite = true).use { h ->
            h.startWrite()
            h.sender.stop()
            h.expireRecoveryAndReleaseWrite()
            assertEquals("CONGESTION", SessionOutcome.errorCode(checkNotNull(h.outcome.failure)))
        }
    }

    private class Harness(failWrite: Boolean = false) : AutoCloseable {
        val now = AtomicLong(1_000_000_000)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sender = SenderSession(Mockito.mock(Context::class.java), Settings(),
            ReceiverTarget("127.0.0.1", 1), {}, {}, {})
        val outcome = field("outcome").get(sender) as SessionOutcome
        val authorization = field("sizeSwitch").get(sender) as SizeSwitchAuthorization
        private val mux = field("mux").get(sender) as MediaBudget
        private val link = ReceiverLink(ReceiverTarget("127.0.0.1", 1))
        private val recovery = StreamRecoverySender(71, mux, now::get)
        private val worker = Thread({
            SenderSession::class.java.getDeclaredMethod("writeLoop", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(sender, 2)
        }, "fixture-audio-writer")

        init {
            val first = AtomicBoolean(true)
            val bytes = ByteArrayOutputStream()
            val output = object : OutputStream() {
                override fun write(value: Int) {
                    if (first.compareAndSet(true, false)) {
                        entered.countDown()
                        check(release.await(3, TimeUnit.SECONDS))
                        if (failWrite) throw IOException("CONGESTION: fixture transport write failed")
                    }
                    bytes.write(value)
                }
            }
            val sockets = ReceiverLink::class.java.getDeclaredField("sockets").apply { isAccessible = true }
                .get(link) as Array<*>
            java.lang.reflect.Array.set(sockets, 2, object : Socket() {
                override fun getOutputStream() = output
            })
            ReceiverLink::class.java.getDeclaredField("attached").apply { isAccessible = true }.setBoolean(link, true)
            ReceiverLink::class.java.getDeclaredField("activeSession").apply { isAccessible = true }.setLong(link, 71)
            field("session").setLong(sender, 71)
            field("link").set(sender, link)
            field("recovery").set(sender, recovery)
            for (name in listOf("requestSent", "acceptedSession", "playing")) field(name).setBoolean(sender, true)
            recovery.config(1, 1, Wire.obj("codec" to "h264"))
            recovery.config(2, 1, Wire.obj("codec" to "raw", "sampleRate" to 48000, "channels" to 2))
            for (track in 1..2) {
                val config = checkNotNull(mux.pollLane(track, 1))
                mux.beginWrite(config); mux.endWrite(config); mux.ready(track, 1)
            }
            recovery.ready()
            recovery.request(2, 1)
            val config = checkNotNull(mux.pollLane(2, 1))
            mux.beginWrite(config); mux.endWrite(config); mux.ready(2, 2)
            recovery.ready()
            recovery.packet(Wire.Message(Wire.AUDIO_PACKET, 0, 71, 2, 1, 1_000_000, ByteArray(3840)))
        }

        fun field(name: String) = SenderSession::class.java.getDeclaredField(name).apply { isAccessible = true }
        fun startWrite() {
            worker.start()
            assertTrue("Audio write did not start", entered.await(2, TimeUnit.SECONDS))
        }
        fun expireRecoveryAndReleaseWrite() {
            now.set(11_000_000_000)
            release.countDown()
            await { mux.writingAudio() == null }
            worker.interrupt()
            worker.join(2000)
            assertFalse(worker.isAlive)
        }
        override fun close() {
            release.countDown()
            worker.interrupt()
            link.close()
            worker.join(3000)
            (field("timer").get(sender) as java.util.concurrent.ScheduledExecutorService).shutdownNow()
            assertFalse(worker.isAlive)
        }
    }

    companion object {
        private fun await(condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!condition() && System.nanoTime() < deadline) Thread.sleep(2)
            assertTrue("Write completion did not finish", condition())
        }
    }
}

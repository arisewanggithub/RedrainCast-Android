package org.screencopy.receiver.net

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.screencopy.receiver.media.*
import org.screencopy.receiver.protocol.*
import org.screencopy.receiver.ui.*
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CongestionDiagnosticsTest {
    @Test fun stopAbandonsUnsentRecoveryButFinishesTheStartedControlFrameFirst() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val written = ConcurrentLinkedQueue<Type>()
        val failures = ConcurrentLinkedQueue<String>()
        val socket = Socket()
        ControlWriter(socket, { written.add(it.type) }, failures::add, writeFrame = { _, message ->
            if (message.type == Type.PING) {
                entered.countDown()
                check(resume.await(3, TimeUnit.SECONDS))
            }
        }).use { writer ->
            try {
                writer.offer(Message.json(Type.PING, JSONObject().put("nonce", 1), 1))
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                repeat(64) {
                    writer.offer(Message.json(Type.STATUS,
                        JSONObject().put("state", "stream-recovery-request").put("message", "obsolete"), 1))
                }
                writer.offer(Message.json(Type.STOP, JSONObject().put("reason", "user"), 1))
                assertTrue(written.isEmpty())
                resume.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (written.size < 2 && System.nanoTime() < deadline) Thread.sleep(1)
                assertEquals(listOf(Type.PING, Type.STOP), written.toList())
                assertTrue(failures.toString(), failures.isEmpty())
            } finally { resume.countDown(); socket.close() }
        }
    }

    @Test fun stopWrittenFenceRequiresSuccessfulWholeFrameFlush() {
        for (failFlush in listOf(false, true)) {
            val flushing = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val written = ConcurrentLinkedQueue<Message>()
            val failed = ConcurrentLinkedQueue<String>()
            val socket = object : Socket() {
                override fun getOutputStream(): OutputStream = object : java.io.ByteArrayOutputStream() {
                    override fun flush() {
                        flushing.countDown()
                        check(resume.await(3, TimeUnit.SECONDS))
                        if (failFlush) throw java.io.IOException("flush failed")
                    }
                }
            }
            ControlWriter(socket, { written.add(it) }, failed::add).use { writer ->
                try {
                    val stop = Message.json(Type.STOP, JSONObject().put("reason", "user"), 1)
                    writer.offer(stop)
                    assertTrue(flushing.await(2, TimeUnit.SECONDS))
                    assertTrue("A fully buffered but unflushed STOP is not written", written.isEmpty())
                    resume.countDown()
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                    while (written.isEmpty() && failed.isEmpty() && System.nanoTime() < deadline) Thread.sleep(1)
                    if (failFlush) {
                        assertTrue(written.isEmpty())
                        assertEquals(1, failed.size)
                    } else {
                        assertEquals(listOf(stop), written.toList())
                        assertTrue(failed.isEmpty())
                    }
                } finally { resume.countDown(); socket.close() }
            }
        }
    }

    @Test fun actualControlWriterRejectReportsControlTrackAndPacketLimit() {
        val writing = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val socket = object : Socket() {
            override fun getOutputStream(): OutputStream = object : OutputStream() {
                override fun write(value: Int) {
                    writing.countDown()
                    check(resume.await(3, TimeUnit.SECONDS))
                }
            }
        }
        val failures = ConcurrentLinkedQueue<String>()
        val writer = ControlWriter(socket, {}, failures::add)
        try {
            val ping = Message.json(Type.PING, JSONObject().put("nonce", 1), 1)
            writer.offer(ping)
            assertTrue(writing.await(3, TimeUnit.SECONDS))
            repeat(64) { writer.offer(ping) }
            val failure = assertThrows(QueueCongestionException::class.java) { writer.offer(ping) }
            assertEquals(0, failure.pressure.track)
            assertEquals(QueueLimit.PACKETS, failure.pressure.limit)
            assertEquals(64, failure.pressure.queuedPackets)
            assertEquals(65, failure.pressure.attemptedPackets)
            assertEquals(64 * (40 + ping.payload.size), failure.pressure.queuedBytes)
        } finally {
            resume.countDown()
            writer.close()
            socket.close()
        }
        assertTrue(failures.toString(), failures.isEmpty())
    }

    @Test fun actualWorkerRejectTravelsThroughReceiverReaderErrorUiAndDiagnosticPath() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val worker = object : TrackWorker("diagnostic-audio-queue", 512 * 1024, { code, detail -> fail("$code: $detail") }, 2) {
            override fun step() {
                if (next() != null) { entered.countDown(); resume.await() }
            }
            override fun unblock() { resume.countDown() }
            override fun release() {}
        }
        val diagnostics = ConcurrentLinkedQueue<FailureDiagnostic>()
        val events = ConcurrentLinkedQueue<ReceiverScreenEvent>()
        try {
            worker.start()
            worker.offer(AccessUnit(0, byteArrayOf(1)))
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            for (index in 1..13) worker.offer(AccessUnit(index * 20_000L, ByteArray(3840)))
            ReceiverServer.forProtocolTests("Receiver", validateRequest = {
                // Exercise the real rejecting offer in the receiver-owned reader, without an Android codec.
                worker.offer(AccessUnit(280_000, ByteArray(3840)))
            }, onScreenEvent = events::add, onFailure = diagnostics::add).use { server ->
                server.start()
                Socket(InetAddress.getLoopbackAddress(), server.listeningPort).use { peer ->
                    peer.soTimeout = 3000
                    Wire.write(peer.outputStream, Message.json(Type.HELLO,
                        JSONObject().put("role", "sender").put("versions", org.json.JSONArray().put(3)).put("name", "Phone")))
                    assertEquals(Type.HELLO, Wire.read(peer.inputStream).type)
                    val settings = StrictJson.decode(
                        """{"video":{"codec":"h264","encoder":"","bitrate":8000000,"maxSize":1920,"maxFps":60,"displayId":0,"orientation":"0","crop":null,"codecOptions":""},"audio":{"enabled":false,"codec":"opus","encoder":"","bitrate":192000,"source":"output","codecOptions":""}}""".toByteArray())
                    Wire.write(peer.outputStream, Message.json(Type.SESSION_REQUEST, settings, 17))
                    val rejected = Wire.read(peer.inputStream)
                    assertEquals("CONGESTION", rejected.json().string("code"))
                    assertEquals(-1, peer.inputStream.read())
                }
                assertEquals(1, diagnostics.size)
                val diagnostic = diagnostics.single()
                assertEquals(FailureOrigin.SESSION, diagnostic.origin)
                assertEquals(FailureReason.QUEUE_PTS, diagnostic.reason)
                val pressure = checkNotNull(diagnostic.pressure)
                assertEquals(2, pressure.track)
                assertEquals(13, pressure.queuedPackets)
                assertEquals(49920, pressure.queuedBytes)
                assertEquals(260_000L, pressure.spanUs)
                assertTrue(pressure.waitedMs >= 240)
                assertTrue(events.filterIsInstance<ReceiverScreenEvent.Problem>().any { it.pressure == pressure })
            }
        } finally { worker.close() }
    }
}

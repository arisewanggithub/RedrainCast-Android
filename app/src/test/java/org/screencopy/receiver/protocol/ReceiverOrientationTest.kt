package org.screencopy.receiver.protocol

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import org.screencopy.receiver.media.VideoPlayer
import org.screencopy.receiver.media.VideoDecoder
import org.screencopy.receiver.media.VideoOutput
import org.screencopy.receiver.net.ReceiverServer
import org.screencopy.receiver.net.MediaSockets
import org.screencopy.receiver.ui.SessionOrientation
import java.net.InetAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ReceiverOrientationTest {
    private fun fixture(): JSONObject = checkNotNull(javaClass.getResourceAsStream("/receiver-orientation.json"))
        .use { StrictJson.decode(it.readBytes()) }

    @Test fun allThirteenSenderOwnedCasesMatchStrictDefaultAndEnumSemantics() {
        val fixture = fixture()
        val cases = fixture.array("cases")
        assertEquals(13, cases.length())
        repeat(cases.length()) { index ->
            val case = cases.getJSONObject(index)
            val json = StrictJson.decode(fixture.obj("baseRequest").toString().toByteArray())
            if (case.boolean("present")) json.put("receiverOrientation", case.get("value"))
            if (case.boolean("valid")) {
                val parsed = Request.parse(json)
                assertEquals(case.string("expected"), parsed.receiverOrientation.wireValue)
                assertEquals("0", json.obj("video").string("orientation"))
                assertEquals("h264", parsed.videoCodec)
            } else {
                assertEquals("UNSUPPORTED_CONFIG",
                    assertThrows(ProtocolException::class.java) { Request.parse(json) }.code)
            }
        }
    }

    @Test fun actualServerRejectsInvalidPolicyAndNeverAppliesItDuringNegotiation() {
        val starts = AtomicInteger()
        val cleanups = AtomicInteger()
        ReceiverServer.forProtocolTests("Receiver", onPresentationStart = {
            starts.incrementAndGet(); AutoCloseable {}
        }, onScreenEvent = { if (it == org.screencopy.receiver.ui.ReceiverScreenEvent.CleanupFinished) cleanups.incrementAndGet() }).use { server ->
            server.start()
            connect(server).use { peer ->
                hello(peer)
                send(peer, Type.SESSION_REQUEST, request().put("receiverOrientation", JSONObject.NULL))
                val rejected = Wire.read(peer.inputStream)
                assertEquals(Type.REJECT, rejected.type)
                assertEquals("UNSUPPORTED_CONFIG", rejected.json().string("code"))
            }
            await { cleanups.get() >= 2 }
            connect(server).use { peer ->
                hello(peer)
                send(peer, Type.SESSION_REQUEST, request().put("receiverOrientation", "landscape"))
                assertEquals(Type.SESSION_ACCEPT, Wire.read(peer.inputStream).type)
                assertEquals(0, starts.get())
                send(peer, Type.STOP, JSONObject().put("reason", "test"))
                assertEquals(Type.SESSION_END, Wire.read(peer.inputStream).type)
                assertEquals(0, starts.get())
            }
        }
    }

    @Test fun failedPlatformOrientationRequestIsAnExplicitPresentationErrorNotACodecClaim() {
        val diagnostics = java.util.concurrent.ConcurrentLinkedQueue<org.screencopy.receiver.ui.FailureDiagnostic>()
        ReceiverServer.forProtocolTests("Receiver", onFailure = diagnostics::add,
            onPresentationStart = { throw org.screencopy.receiver.ui.ReceiverOrientationException(SecurityException("private platform text")) },
            videoFactory = { format, clock, failure, generation -> VideoPlayer(format, clock, failure, IdleDecoder(), generation) })
            .use { server ->
                server.start()
                connect(server).use { peer ->
                    hello(peer)
                    send(peer, Type.SESSION_REQUEST, request().put("receiverOrientation", "landscape"))
                    MediaSockets.attach(server, Wire.read(peer.inputStream)).use { media ->
                    configure(peer, media.video, 1, awaitReady = false)
                    var error = Wire.read(peer.inputStream)
                    if (error.type == Type.STREAM_READY) {
                        assertTrue(error.json().boolean("ok"))
                        error = Wire.read(peer.inputStream)
                    }
                    assertEquals(Type.ERROR, error.type)
                    assertEquals("UNSUPPORTED_CONFIG", error.json().string("code"))
                    await { diagnostics.isNotEmpty() }
                    assertEquals(org.screencopy.receiver.ui.FailureOrigin.PRESENTATION, diagnostics.single().origin)
                    assertEquals(org.screencopy.receiver.ui.FailureReason.RECEIVER_ORIENTATION, diagnostics.single().reason)
                    assertFalse(diagnostics.single().logMessage().contains("private"))
                    }
                }
            }
    }

    @Test fun actualPlayingBoundaryAppliesOnceAcrossGenerationAndRestoresAfterStopOrFailure() {
        for (fail in listOf(false, true)) {
            val orientation = AtomicInteger(-1)
            val writes = AtomicInteger()
            val policy = SessionOrientation(orientation::get) { orientation.set(it); writes.incrementAndGet() }
            val starts = AtomicInteger()
            val closes = AtomicInteger()
            ReceiverServer.forProtocolTests("Receiver", onPresentationStart = { mode ->
                val token = Any()
                policy.begin(token, mode)
                starts.incrementAndGet()
                AutoCloseable { policy.restore(token); closes.incrementAndGet() }
            }, videoFactory = { format, clock, failure, generation ->
                VideoPlayer(format, clock, failure, IdleDecoder(), generation)
            }).use { server ->
                server.start()
                connect(server).use { peer ->
                    hello(peer)
                    send(peer, Type.SESSION_REQUEST, request().put("receiverOrientation", "portrait"))
                    MediaSockets.attach(server, Wire.read(peer.inputStream)).use { media ->
                    assertEquals(-1, orientation.get())
                    configure(peer, media.video, 1)
                    await { starts.get() == 1 }
                    assertEquals(1, orientation.get())
                    configure(peer, media.video, 2)
                    assertEquals(1, starts.get())
                    assertEquals(1, writes.get())
                    if (fail) {
                        send(peer, Type.ERROR, JSONObject().put("code", "CAPTURE_FAILED").put("message", "test").put("fatal", true))
                        assertEquals(Type.ERROR, Wire.read(peer.inputStream).type)
                    } else {
                        send(peer, Type.STOP, JSONObject().put("reason", "test"))
                        assertEquals(Type.SESSION_END, Wire.read(peer.inputStream).type)
                    }
                    await { closes.get() == 1 }
                    assertEquals(-1, orientation.get())
                    assertEquals(2, writes.get())
                    }
                }
            }
        }
    }

    private fun request() = fixture().obj("baseRequest").also { it.obj("audio").put("enabled", false) }
    private fun connect(server: ReceiverServer) = Socket(InetAddress.getLoopbackAddress(), server.listeningPort).apply { soTimeout = 3000 }
    private fun send(peer: Socket, type: Type, json: JSONObject) =
        Wire.write(peer.outputStream, Message.json(type, json, 47))
    private fun hello(peer: Socket) {
        Wire.write(peer.outputStream, Message.json(Type.HELLO,
            JSONObject().put("role", "sender").put("versions", org.json.JSONArray().put(3)).put("name", "Test")))
        assertEquals(Type.HELLO, Wire.read(peer.inputStream).type)
    }
    private fun configure(peer: Socket, video: Socket, generation: Long, awaitReady: Boolean = true) {
        Wire.write(video.outputStream, Message.json(Type.STREAM_CONFIG,
            JSONObject().put("codec", "h264").put("width", 640).put("height", 360)
                .put("csd", org.json.JSONArray().put("AAAAAWdkACg=")), 47, 1, generation))
        if (awaitReady) assertEquals(Type.STREAM_READY, Wire.read(peer.inputStream).type)
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(1)
        assertTrue("Receiver presentation lifecycle made no progress", condition())
    }
    private class IdleDecoder : VideoDecoder {
        override val description = "Orientation lifecycle decoder"
        override fun dequeueInput() = 0
        override fun input(index: Int) = ByteBuffer.allocate(16)
        override fun queueInput(index: Int, size: Int, ptsUs: Long, keyFrame: Boolean) = Unit
        override fun dequeueOutput(): VideoOutput? = null
        override fun releaseOutput(index: Int, renderAtNs: Long?) = Unit
        override fun close() = Unit
    }
}

package org.screencopy.receiver.net

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.screencopy.receiver.media.VideoDecoder
import org.screencopy.receiver.media.VideoOutput
import org.screencopy.receiver.media.VideoPlayer
import org.screencopy.receiver.protocol.*
import java.net.InetAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ThreeSocketReceiverTest {
    @Test fun acceptanceUsesUnpredictableTokensAndRejectsWrongSessionTokenChannelAndDuplicateWithoutStealingOwner() {
        ReceiverServer.forProtocolTests("Receiver").use { server ->
            server.start()
            control(server).use { owner ->
                val accepted = negotiate(owner, 81)
                val token = accepted.json().getString("mediaToken")
                assertTrue(token.matches(Regex("[0-9a-f]{64}")))
                assertEquals(listOf(1), accepted.json().getJSONArray("tracks").let {
                    (0 until it.length()).map(it::getInt)
                })
                for ((session, candidate, channel) in listOf(
                    Triple(82L, token, "video"), Triple(81L, "0".repeat(64), "video"),
                    Triple(81L, token.uppercase(), "video"), Triple(81L, token, "control")
                )) {
                    rejectMedia(server, session, candidate, channel)
                    ping(owner, 81)
                }
                MediaSockets.attach(server, accepted).use { media ->
                    rejectMedia(server, 81, token, "video")
                    rejectMedia(server, 81, token, "audio")
                    ping(owner, 81)
                    stop(owner, 81)
                    assertClosed(media.video)
                    assertClosed(media.audio)
                }
                await { !server.busyForUpdate }
                control(server).use { next ->
                    val nextAccepted = negotiate(next, 81)
                    assertNotEquals(token, nextAccepted.json().getString("mediaToken"))
                    rejectMedia(server, 81, token, "video")
                    ping(next, 81)
                    stop(next, 81)
                }
            }
        }
    }

    @Test fun evenDisabledAudioMustAttachBeforeVideoConfigurationAndReadyReturnsOnlyOnControl() {
        val decoders = AtomicInteger()
        ReceiverServer.forProtocolTests("Receiver", videoFactory = { format, clock, failed, generation ->
            decoders.incrementAndGet()
            VideoPlayer(format, clock, failed, IdleDecoder(), generation)
        }).use { server ->
            server.start()
            control(server).use { owner ->
                val accepted = negotiate(owner, 22)
                val token = accepted.json().getString("mediaToken")
                MediaSockets.connect(server, 22, token, "video").use { video ->
                    configure(video, 22)
                    owner.soTimeout = 150
                    assertThrows(SocketTimeoutException::class.java) { Wire.read(owner.inputStream) }
                    assertEquals(0, decoders.get())
                    owner.soTimeout = 3000
                    ping(owner, 22)
                    MediaSockets.connect(server, 22, token, "audio").use { audio ->
                        val ready = Wire.read(owner.inputStream)
                        assertEquals(Type.STREAM_READY, ready.type)
                        assertEquals(22L, ready.session)
                        assertEquals(1, ready.track)
                        assertEquals(1L, ready.generation)
                        assertEquals(1, decoders.get())
                        video.soTimeout = 150
                        audio.soTimeout = 150
                        assertThrows(SocketTimeoutException::class.java) { Wire.read(video.inputStream) }
                        assertThrows(SocketTimeoutException::class.java) { Wire.read(audio.inputStream) }
                        stop(owner, 22)
                        assertClosed(video)
                        assertClosed(audio)
                    }
                }
            }
        }
    }

    @Test fun mediaAndConfigsOnControlAreNeverAcceptedAsSingleSocketCompatibility() {
        for (type in listOf(Type.STREAM_CONFIG, Type.VIDEO_FRAME, Type.AUDIO_PACKET)) {
            ReceiverServer.forProtocolTests("Receiver").use { server ->
                server.start()
                control(server).use { owner ->
                    MediaSockets.attach(server, negotiate(owner, 52)).use { media ->
                        val packet = when (type) {
                            Type.STREAM_CONFIG -> videoConfig(52)
                            Type.VIDEO_FRAME -> Message(type, 52, 1, 1, 0, payload = byteArrayOf(0, 0, 0, 1, 1))
                            else -> Message(type, 52, 2, 1, 0, payload = byteArrayOf(1))
                        }
                        Wire.write(owner.outputStream, packet)
                        assertProtocolError(owner)
                        assertClosed(media.video)
                        assertClosed(media.audio)
                        await { !server.busyForUpdate }
                    }
                }
            }
        }
    }

    @Test fun wrongTrackOrControlPacketOnMediaAndDisabledAudioPayloadFailEntireSessionOnControl() {
        for (kind in listOf("wrong-track", "control", "disabled-audio", "wrong-session")) {
            ReceiverServer.forProtocolTests("Receiver").use { server ->
                server.start()
                control(server).use { owner ->
                    MediaSockets.attach(server, negotiate(owner, 62)).use { media ->
                        when (kind) {
                            "wrong-track" -> Wire.write(media.audio.outputStream, videoConfig(62))
                            "control" -> send(media.video, Type.PING, JSONObject().put("nonce", 9), 62)
                            "disabled-audio" -> Wire.write(media.audio.outputStream,
                                Message(Type.AUDIO_PACKET, 62, 2, 1, 0, payload = byteArrayOf(1)))
                            else -> Wire.write(media.video.outputStream, videoConfig(63))
                        }
                        assertProtocolError(owner)
                        assertClosed(media.video)
                        assertClosed(media.audio)
                        await { !server.busyForUpdate }
                    }
                }
            }
        }
    }

    @Test fun anyAttachedSocketDisconnectReleasesAllSessionSocketsAndAllowsFreshNegotiation() {
        for (channel in 0..2) {
            ReceiverServer.forProtocolTests("Receiver").use { server ->
                server.start()
                control(server).use { owner ->
                    MediaSockets.attach(server, negotiate(owner, 72)).use { media ->
                        listOf(owner, media.video, media.audio)[channel].close()
                        if (channel != 0) {
                            assertProtocolError(owner)
                            assertClosed(owner)
                        }
                        if (channel != 1) assertClosed(media.video)
                        if (channel != 2) assertClosed(media.audio)
                        await { !server.busyForUpdate }
                        control(server).use { next ->
                            negotiate(next, 73)
                            stop(next, 73)
                        }
                    }
                }
            }
        }
    }

    @Test fun receiverStopAndListenerShutdownCloseBothMediaConnectionsEvenWhenAudioIsIdle() {
        for (shutdown in listOf(false, true)) {
            ReceiverServer.forProtocolTests("Receiver").use { server ->
                server.start()
                control(server).use { owner ->
                    MediaSockets.attach(server, negotiate(owner, 92)).use { media ->
                        if (shutdown) server.close()
                        else {
                            server.disconnect("receiver user")
                            assertEquals(Type.STOP, Wire.read(owner.inputStream).type)
                            send(owner, Type.SESSION_END, JSONObject().put("reason", "receiver user"), 92)
                        }
                        assertClosed(media.video)
                        assertClosed(media.audio)
                        assertClosed(owner)
                        await { !server.busyForUpdate }
                    }
                }
            }
        }
    }

    private fun control(server: ReceiverServer) =
        Socket(InetAddress.getLoopbackAddress(), server.listeningPort).apply { soTimeout = 3000 }

    private fun negotiate(owner: Socket, session: Long): Message {
        send(owner, Type.HELLO, JSONObject().put("role", "sender").put("versions", JSONArray().put(3)).put("name", "Windows"))
        val hello = Wire.read(owner.inputStream)
        assertEquals(Type.HELLO, hello.type)
        assertFalse(hello.json().has("videoRotation"))
        send(owner, Type.SESSION_REQUEST, StrictJson.decode(
            """{"video":{"codec":"h264","encoder":"","bitrate":8000000,"maxSize":1920,"maxFps":60,"displayId":0,"orientation":"0","crop":null,"codecOptions":""},"audio":{"enabled":false,"codec":"opus","encoder":"","bitrate":192000,"source":"output","codecOptions":""}}""".toByteArray()), session)
        return Wire.read(owner.inputStream).also { assertEquals(Type.SESSION_ACCEPT, it.type) }
    }

    private fun rejectMedia(server: ReceiverServer, session: Long, token: String, channel: String) {
        control(server).use { peer ->
            send(peer, Type.HELLO, JSONObject().put("role", "media").put("channel", channel).put("token", token), session)
            assertProtocolError(peer)
            assertClosed(peer)
        }
    }

    private fun ping(owner: Socket, session: Long) {
        send(owner, Type.PING, JSONObject().put("nonce", 123), session)
        assertEquals(Type.PONG, Wire.read(owner.inputStream).type)
    }

    private fun stop(owner: Socket, session: Long) {
        send(owner, Type.STOP, JSONObject().put("reason", "test"), session)
        assertEquals(Type.SESSION_END, Wire.read(owner.inputStream).type)
    }

    private fun send(peer: Socket, type: Type, json: JSONObject, session: Long = 0) =
        Wire.write(peer.outputStream, Message.json(type, json, session))

    private fun videoConfig(session: Long) = Message.json(Type.STREAM_CONFIG,
        JSONObject().put("codec", "h264").put("width", 640).put("height", 360)
            .put("csd", JSONArray().put("AAAAAWdkACg=")), session, 1, 1)

    private fun configure(video: Socket, session: Long) = Wire.write(video.outputStream, videoConfig(session))

    private fun assertProtocolError(peer: Socket) {
        val error = Wire.read(peer.inputStream)
        assertEquals(Type.ERROR, error.type)
        assertEquals("PROTOCOL", error.json().getString("code"))
    }

    private fun assertClosed(peer: Socket) {
        peer.soTimeout = 3000
        try { assertEquals(-1, peer.inputStream.read()) } catch (_: SocketException) { }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(1)
        assertTrue("Receiver did not finish cleanup", condition())
    }

    private class IdleDecoder : VideoDecoder {
        override val description = "Deterministic idle decoder"
        override fun dequeueInput() = -1
        override fun input(index: Int): ByteBuffer? = null
        override fun queueInput(index: Int, size: Int, ptsUs: Long, keyFrame: Boolean) {}
        override fun dequeueOutput(): VideoOutput? = null
        override fun releaseOutput(index: Int, renderAtNs: Long?) {}
        override fun close() {}
    }
}

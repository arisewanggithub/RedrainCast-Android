package dev.screencopy.sender

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class SenderSocketTest {
    @Test fun localCloseRetiresBothMediaSocketsBeforeControl() {
        val link = ReceiverLink(ReceiverTarget("127.0.0.1", 1))
        val sockets = ReceiverLink::class.java.getDeclaredField("sockets").apply { isAccessible = true }
            .get(link) as Array<*>
        val closed = mutableListOf<Int>()
        for (lane in 0..2) java.lang.reflect.Array.set(sockets, lane, object : Socket() {
            override fun close() { closed.add(lane) }
        })
        link.close()
        link.close()
        assertEquals(listOf(1, 2, 0), closed)
    }

    private val session = 71L
    private val token = "aB".repeat(32)
    private fun hello(rotation: Boolean? = true) = Wire.control(Wire.HELLO, 0,
        Wire.obj("role" to "receiver", "versions" to JSONArray().put(3), "name" to "Fixture").apply {
            if (rotation != null) put("videoRotation", rotation)
        })
    private fun accepted(audio: Boolean, value: String = token) = Wire.control(Wire.SESSION_ACCEPT, session,
        Wire.obj("tracks" to JSONArray().put(1).apply { if (audio) put(2) }, "mediaToken" to value))
    private fun ready(track: Int) = Wire.Message(Wire.STREAM_READY, 0, session, track, 1, -1,
        Wire.obj("ok" to true).toString().toByteArray())
    private fun frame(track: Int) = Wire.Message(if (track == 1) Wire.VIDEO_FRAME else Wire.AUDIO_PACKET,
        0, session, track, 1, 123, byteArrayOf(1, 2, 3, 4))

    private fun controlHello(server: ServerSocket): Socket {
        val control = server.accept().apply { soTimeout = 3000 }
        val request = Wire.read(control.inputStream)
        assertEquals(Wire.HELLO, request.type)
        assertEquals(0L, request.session)
        assertEquals("sender", request.json().getString("role"))
        assertEquals("RedrainCast", request.json().getString("name"))
        assertEquals("[3]", request.json().getJSONArray("versions").toString())
        assertTrue(request.json().getBoolean("videoRotation"))
        Wire.write(control.outputStream, hello())
        return control
    }

    private fun mediaHello(server: ServerSocket, channel: String): Socket {
        val media = server.accept().apply { soTimeout = 3000 }
        val request = Wire.read(media.inputStream)
        assertEquals(Wire.HELLO, request.type)
        assertEquals(session, request.session)
        assertEquals("media", request.json().getString("role"))
        assertEquals(channel, request.json().getString("channel"))
        assertEquals(token, request.json().getString("token"))
        Wire.write(media.outputStream, Wire.control(Wire.HELLO, session, Wire.obj("channel" to channel)))
        return media
    }

    private fun negotiate(link: ReceiverLink, audio: Boolean) {
        link.connect()
        link.requireVideoRotation()
        link.send(Wire.control(Wire.SESSION_REQUEST, session, Settings().apply { this.audio = audio }.request()))
        link.attach(session, link.receive(), audio)
    }

    @Test fun attachesBothSocketsAndRoutesConfigsMediaReadinessAndStopToTheirOwnLanes() {
        for (audio in listOf(false, true)) ServerSocket(0).use { server ->
            val peer = CompletableFuture.runAsync {
                controlHello(server).use { control ->
                    val request = Wire.read(control.inputStream)
                    assertEquals(Wire.SESSION_REQUEST, request.type)
                    assertEquals(audio, request.json().getJSONObject("audio").getBoolean("enabled"))
                    Wire.write(control.outputStream, accepted(audio))
                    mediaHello(server, "video").use { video ->
                        mediaHello(server, "audio").use { sound ->
                            val videoConfig = Wire.read(video.inputStream)
                            assertEquals(Wire.STREAM_CONFIG, videoConfig.type)
                            assertEquals(1, videoConfig.track)
                            if (audio) {
                                val audioConfig = Wire.read(sound.inputStream)
                                assertEquals(Wire.STREAM_CONFIG, audioConfig.type)
                                assertEquals(2, audioConfig.track)
                            } else {
                                sound.soTimeout = 150
                                assertThrows(SocketTimeoutException::class.java) { sound.inputStream.read() }
                            }
                            Wire.write(control.outputStream, ready(1))
                            if (audio) Wire.write(control.outputStream, ready(2))
                            assertEquals(Wire.VIDEO_FRAME, Wire.read(video.inputStream).type)
                            if (audio) assertEquals(Wire.AUDIO_PACKET, Wire.read(sound.inputStream).type)
                            assertEquals(Wire.PING, Wire.read(control.inputStream).type)
                            Wire.write(control.outputStream, Wire.control(Wire.PONG, session, Wire.obj("nonce" to 4)))
                            assertEquals(Wire.STOP, Wire.read(control.inputStream).type)
                            Wire.write(control.outputStream, Wire.control(Wire.SESSION_END, session, Wire.obj("reason" to "user")))
                        }
                    }
                }
            }
            ReceiverLink(ReceiverTarget("127.0.0.1", server.localPort)).use { link ->
                negotiate(link, audio)
                val budget = MediaBudget(audio)
                for (track in 1..if (audio) 2 else 1) {
                    budget.offer(Wire.config(session, track, 1, Wire.obj("codec" to if (track == 1) "h264" else "raw")))
                    budget.offer(frame(track))
                    link.send(checkNotNull(budget.pollLane(track, 1)))
                    assertNull(budget.pollLane(track, 1))
                }
                val first = link.receive()
                assertEquals(Wire.STREAM_READY, first.type)
                budget.ready(first.track, first.generation)
                if (audio) {
                    assertFalse(budget.allReady())
                    assertNull(budget.pollLane(1, 1))
                    val second = link.receive()
                    budget.ready(second.track, second.generation)
                }
                assertTrue(budget.allReady())
                link.send(checkNotNull(budget.pollLane(1, 1)))
                if (audio) link.send(checkNotNull(budget.pollLane(2, 1)))
                link.send(Wire.control(Wire.PING, session, Wire.obj("nonce" to 4)))
                assertEquals(Wire.PONG, link.receive().type)
                link.send(Wire.control(Wire.STOP, session, Wire.obj("reason" to "user")))
                assertEquals(Wire.SESSION_END, link.receive().type)
            }
            peer.get(5, TimeUnit.SECONDS)
        }
    }

    @Test fun malformedTokensTracksAndTransportOverridesCloseControlBeforeAnyAttachment() {
        val invalid = listOf(
            accepted(false, ""), accepted(false, "f".repeat(63)), accepted(false, "g".repeat(64)),
            accepted(false, "a".repeat(65)),
            Wire.control(Wire.SESSION_ACCEPT, session, Wire.obj("tracks" to JSONArray().put(1))),
            Wire.control(Wire.SESSION_ACCEPT, session, accepted(false).json().put("tracks", JSONArray().put(1).put(1))),
            Wire.control(Wire.SESSION_ACCEPT, session, accepted(false).json().put("tracks", JSONArray().put("1"))),
            Wire.control(Wire.SESSION_ACCEPT, session, accepted(false).json().put("mediaTransport", "tcp"))
        )
        for (accept in invalid) ServerSocket(0).use { server ->
            val peer = CompletableFuture.runAsync {
                controlHello(server).use { control ->
                    Wire.read(control.inputStream)
                    Wire.write(control.outputStream, accept)
                    assertEquals(-1, control.inputStream.read())
                    server.soTimeout = 100
                    assertThrows(SocketTimeoutException::class.java) { server.accept() }
                }
            }
            ReceiverLink(ReceiverTarget("127.0.0.1", server.localPort)).use { link ->
                assertThrows(Exception::class.java) { negotiate(link, false) }
            }
            peer.get(3, TimeUnit.SECONDS)
        }
    }

    @Test fun rejectedTokenAndWrongAttachmentAcksCloseEveryOwnedSocket() {
        val replies = listOf(
            Wire.control(Wire.REJECT, session, Wire.obj("code" to "PROTOCOL", "message" to "Bad media token")),
            Wire.control(Wire.HELLO, session, Wire.obj("channel" to "audio")),
            Wire.control(Wire.HELLO, session + 1, Wire.obj("channel" to "video"))
        )
        for (reply in replies) ServerSocket(0).use { server ->
            val peer = CompletableFuture.runAsync {
                controlHello(server).use { control ->
                    Wire.read(control.inputStream)
                    Wire.write(control.outputStream, accepted(false))
                    server.accept().use { video ->
                        video.soTimeout = 2000
                        assertEquals(token, Wire.read(video.inputStream).json().getString("token"))
                        Wire.write(video.outputStream, reply)
                        assertEquals(-1, video.inputStream.read())
                        assertEquals(-1, control.inputStream.read())
                    }
                }
            }
            ReceiverLink(ReceiverTarget("127.0.0.1", server.localPort)).use { link ->
                assertThrows(IOException::class.java) { negotiate(link, false) }
            }
            peer.get(3, TimeUnit.SECONDS)
        }
    }

    @Test fun closingDuringSecondAttachmentInterruptsPendingReadAndReleasesBothMediaSockets() {
        ServerSocket(0).use { server ->
            val attaching = CountDownLatch(1)
            val peer = CompletableFuture.runAsync {
                controlHello(server).use { control ->
                    Wire.read(control.inputStream)
                    Wire.write(control.outputStream, accepted(false))
                    mediaHello(server, "video").use { video ->
                        server.accept().use { audio ->
                            audio.soTimeout = 2000
                            assertEquals("audio", Wire.read(audio.inputStream).json().getString("channel"))
                            attaching.countDown()
                            assertEquals(-1, audio.inputStream.read())
                            assertEquals(-1, video.inputStream.read())
                            assertEquals(-1, control.inputStream.read())
                        }

                    }
                }
            }
            ReceiverLink(ReceiverTarget("127.0.0.1", server.localPort)).use { link ->
                val work = CompletableFuture.runAsync { negotiate(link, false) }
                assertTrue(attaching.await(2, TimeUnit.SECONDS))
                link.close()
                assertTrue(assertThrows(ExecutionException::class.java) { work.get(2, TimeUnit.SECONDS) }.cause is IOException)
            }
            peer.get(3, TimeUnit.SECONDS)
        }
    }

    @Test fun rejectedAudioAttachmentAlsoReleasesPreviouslyAttachedVideoAndControl() {
        ServerSocket(0).use { server ->
            val peer = CompletableFuture.runAsync {
                controlHello(server).use { control ->
                    Wire.read(control.inputStream)
                    Wire.write(control.outputStream, accepted(false))
                    mediaHello(server, "video").use { video ->
                        server.accept().use { audio ->
                            audio.soTimeout = 2000
                            assertEquals("audio", Wire.read(audio.inputStream).json().getString("channel"))
                            Wire.write(audio.outputStream, Wire.control(Wire.REJECT, session,
                                Wire.obj("code" to "PROTOCOL", "message" to "Audio token rejected")))
                            assertEquals(-1, audio.inputStream.read())
                            assertEquals(-1, video.inputStream.read())
                            assertEquals(-1, control.inputStream.read())
                        }
                    }
                }
            }
            ReceiverLink(ReceiverTarget("127.0.0.1", server.localPort)).use { link ->
                assertTrue(assertThrows(IOException::class.java) { negotiate(link, false) }
                    .message.orEmpty().contains("Audio token rejected"))
            }
            peer.get(3, TimeUnit.SECONDS)
        }
    }

    @Test fun controlHeartbeatAndStopRemainIndependentOfBlockedVideoWriter() {
        ServerSocket(0).use { server ->
            val videoStarted = CountDownLatch(1)
            val peer = CompletableFuture.runAsync {
                controlHello(server).use { control ->
                    Wire.read(control.inputStream)
                    Wire.write(control.outputStream, accepted(false))
                    mediaHello(server, "video").use { video ->
                        video.receiveBufferSize = 1024
                        mediaHello(server, "audio").use {
                            assertTrue(videoStarted.await(2, TimeUnit.SECONDS))
                            assertEquals(Wire.PING, Wire.read(control.inputStream).type)
                            Wire.write(control.outputStream, Wire.control(Wire.PONG, session, Wire.obj("nonce" to 1)))
                            assertEquals(Wire.STOP, Wire.read(control.inputStream).type)
                            Wire.write(control.outputStream, Wire.control(Wire.SESSION_END, session, Wire.obj("reason" to "user")))
                        }
                    }
                }
            }
            ReceiverLink(ReceiverTarget("127.0.0.1", server.localPort)).use { link ->
                negotiate(link, false)
                val write = CompletableFuture.runAsync {
                    videoStarted.countDown()
                    link.send(frame(1).copy(payload = ByteArray(Wire.MAX_VIDEO)))
                }
                link.send(Wire.control(Wire.PING, session, Wire.obj("nonce" to 1)))
                assertEquals(Wire.PONG, link.receive().type)
                link.send(Wire.control(Wire.STOP, session, Wire.obj("reason" to "user")))
                assertEquals(Wire.SESSION_END, link.receive().type)
                link.close()
                try { write.get(2, TimeUnit.SECONDS) }
                catch (error: ExecutionException) { assertTrue(error.cause is IOException) }
            }
            peer.get(3, TimeUnit.SECONDS)
        }
    }

    @Test fun absentWindowsRotationCapabilityIsAnExplicitUnsupportedConfiguration() {
        ServerSocket(0).use { server ->
            val peer = CompletableFuture.runAsync {
                server.accept().use { socket ->
                    Wire.read(socket.inputStream)
                    Wire.write(socket.outputStream, hello(null))
                    assertEquals(-1, socket.inputStream.read())
                }
            }
            ReceiverLink(ReceiverTarget("127.0.0.1", server.localPort)).use { link ->
                link.connect()
                assertTrue(assertThrows(IOException::class.java) { link.requireVideoRotation() }
                    .message.orEmpty().startsWith("UNSUPPORTED_CONFIG"))
                assertThrows(IOException::class.java) { link.send(frame(1)) }
            }
            peer.get(3, TimeUnit.SECONDS)
        }
    }

    @Test fun absoluteHelloDeadlineCannotBeExtendedByTrickledBytes() {
        ServerSocket(0).use { server ->
            val peer = CompletableFuture.runAsync {
                try {
                    server.accept().use { socket ->
                        Wire.read(socket.inputStream)
                        val bytes = ByteArrayOutputStream().also { Wire.write(it, hello()) }.toByteArray()
                        for (byte in bytes) {
                            socket.outputStream.write(byte.toInt())
                            Thread.sleep(20)
                        }
                    }
                } catch (_: IOException) { }
            }
            ReceiverLink(ReceiverTarget("127.0.0.1", server.localPort)).use { link ->
                assertThrows(SocketTimeoutException::class.java) { link.connect(150) }
            }
            peer.get(3, TimeUnit.SECONDS)
        }
    }
}

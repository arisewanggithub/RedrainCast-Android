package org.screencopy.receiver.net

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import org.screencopy.receiver.media.*
import org.screencopy.receiver.protocol.*
import org.screencopy.receiver.ui.FailureDiagnostic
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

class OrderlyStopReceiverTest {
    @Test(timeout = 15_000) fun stopHaltsHealthyOutputBeforeRecoveryRetirementAndRejectsLateReplacement() {
        for (duringCreation in listOf(false, true)) {
            val entered = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val paused = CountDownLatch(1)
            val active = AtomicBoolean()
            val failures = ConcurrentLinkedQueue<FailureDiagnostic>()
            val players = ConcurrentLinkedQueue<VideoPlayer>()
            val output = Mockito.mock(AudioOutput::class.java)
            Mockito.`when`(output.timing()).thenReturn(
                AudioOutputTiming(48000, 6240, 0, 0, 0, 0, AudioClockSource.TIMESTAMP))
            Mockito.`when`(output.playbackActive).thenAnswer { active.get() }
            Mockito.doAnswer { active.set(true); null }.`when`(output).play()
            Mockito.doAnswer { active.set(false); paused.countDown(); null }.`when`(output).pause()
            ReceiverServer.forProtocolTests("Receiver", onFailure = failures::add,
                videoFactory = { _, _, _, generation ->
                    if (duringCreation && generation == 2L) {
                        entered.countDown()
                        check(resume.await(5, TimeUnit.SECONDS))
                    }
                    Mockito.mock(VideoPlayer::class.java).also { player ->
                        players.add(player)
                        if (!duringCreation && generation == 1L) Mockito.doAnswer {
                            entered.countDown()
                            check(resume.await(5, TimeUnit.SECONDS))
                            null
                        }.`when`(player).close()
                    }
                },
                audioFactory = { format, clock, failed ->
                    testAudioPlayer(format, clock, failed, { output }, opusFactory = {
                        object : OpusPacketDecoder {
                            override fun decode(packet: ByteArray) = ShortArray(1920)
                            override fun close() {}
                        }
                    })
                }).use { server ->
                try {
                    server.start()
                    control(server).use { peer ->
                        MediaSockets.attach(server, negotiate(peer, withAudio = true)).use { media ->
                            fun videoConfig(generation: Long) = Message.json(Type.STREAM_CONFIG,
                                JSONObject().put("codec", "h265").put("width", 720).put("height", 1280)
                                    .put("csd", JSONArray().put("AAAAAQE=")), ID, 1, generation)
                            Wire.write(media.video.outputStream, videoConfig(1))
                            assertEquals(Type.STREAM_READY, Wire.read(peer.inputStream).type)
                            val head = "OpusHead".toByteArray() +
                                byteArrayOf(1, 2, 0x38, 1, 0x80.toByte(), 0xbb.toByte(), 0, 0, 0, 0, 0)
                            Wire.write(media.audio.outputStream, Message.json(Type.STREAM_CONFIG,
                                JSONObject().put("codec", "opus").put("sampleRate", 48000).put("channels", 2)
                                    .put("csd", JSONArray().put(java.util.Base64.getEncoder().encodeToString(head)))
                                    .put("codecDelayNs", 6_500_000).put("seekPrerollNs", 80_000_000), ID, 2, 1))
                            assertEquals(Type.STREAM_READY, Wire.read(peer.inputStream).type)
                            output.play()
                            Wire.write(media.video.outputStream, videoConfig(2))
                            assertTrue(entered.await(2, TimeUnit.SECONDS))
                            assertTrue(active.get())
                            val stopping = java.util.concurrent.CompletableFuture.runAsync { server.disconnect("user") }
                            assertEquals(Type.STOP, Wire.read(peer.inputStream).type)
                            assertTrue("Healthy output must halt before waiting for recovery retirement",
                                paused.await(1, TimeUnit.SECONDS))
                            assertFalse(active.get())
                            assertFalse("Recovery still owns playbackOperations", stopping.isDone)
                            resume.countDown()
                            stopping.get(3, TimeUnit.SECONDS)
                            send(peer, Type.SESSION_END, JSONObject().put("reason", "user"))
                            assertEquals(-1, peer.inputStream.read())
                            await { !server.busyForUpdate }
                            assertEquals(if (duringCreation) 2 else 1, players.size)
                            for (player in players) Mockito.verify(player).close()
                            assertTrue(failures.toString(), failures.isEmpty())
                        }
                    }
                } finally { resume.countDown() }
            }
        }
    }

    @Test(timeout = 10_000) fun crossedStopWaitsForSerializedStopWriteInsteadOfAbortingStartedControl() {
        val clock = AtomicLong(100_000)
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val failures = ConcurrentLinkedQueue<FailureDiagnostic>()
        ReceiverServer.forProtocolTests("Receiver", timeMilliseconds = clock::get, onFailure = failures::add,
            controlWrite = { socket, message ->
                if (message.type == Type.PONG) {
                    entered.countDown()
                    check(resume.await(5, TimeUnit.SECONDS))
                }
                Wire.write(socket.outputStream, message)
            }).use { server ->
            try {
                server.start()
                control(server).use { peer ->
                    MediaSockets.attach(server, negotiate(peer)).use {
                        val owner = owner(server)
                        send(peer, Type.PING, JSONObject().put("nonce", 19))
                        assertTrue(entered.await(2, TimeUnit.SECONDS))
                        server.disconnect("user")
                        send(peer, Type.STOP, JSONObject().put("reason", "crossed user Stop"))
                        clock.addAndGet(3000)
                        tick(owner)
                        assertNull(field(owner, "stopWrittenAt"))
                        assertTrue(server.busyForUpdate)
                        assertTrue(failures.toString(), failures.isEmpty())
                        resume.countDown()
                        assertEquals(Type.PONG, Wire.read(peer.inputStream).type)
                        assertEquals(Type.STOP, Wire.read(peer.inputStream).type)
                        assertEquals(-1, peer.inputStream.read())
                        await { !server.busyForUpdate }
                        assertTrue(failures.toString(), failures.isEmpty())
                    }
                }
            } finally { resume.countDown() }
        }
    }

    @Test(timeout = 10_000) fun controlEofOrFatalPeerErrorDuringStopCannotBecomeCleanRetirement() {
        for (peerError in listOf(false, true)) {
            val failures = ConcurrentLinkedQueue<FailureDiagnostic>()
            ReceiverServer.forProtocolTests("Receiver", onFailure = failures::add).use { server ->
                server.start()
                control(server).use { peer ->
                    MediaSockets.attach(server, negotiate(peer)).use {
                        server.disconnect("user")
                        assertEquals(Type.STOP, Wire.read(peer.inputStream).type)
                        if (peerError) send(peer, Type.ERROR, JSONObject().put("code", "TIMEOUT")
                            .put("message", "Started sender write timed out").put("fatal", true))
                        else peer.shutdownOutput()
                        await { !server.busyForUpdate }
                        assertEquals(if (peerError) "TIMEOUT" else "PROTOCOL", failures.single().code)
                    }
                }
            }
        }
    }

    @Test(timeout = 10_000) fun listenerShutdownDoesNotMisclassifyItsOwnClosedStopWriterAsPeerFailure() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val target = AtomicReference<Socket>()
        val failures = ConcurrentLinkedQueue<FailureDiagnostic>()
        ReceiverServer.forProtocolTests("Receiver", onFailure = failures::add,
            controlWrite = { socket, message ->
                if (message.type == Type.STOP) {
                    target.set(socket)
                    entered.countDown()
                    check(resume.await(5, TimeUnit.SECONDS))
                    throw IOException("local shutdown closed writer")
                }
                Wire.write(socket.outputStream, message)
            }).use { server ->
            try {
                server.start()
                control(server).use { peer ->
                    MediaSockets.attach(server, negotiate(peer)).use {
                        server.disconnect("user")
                        assertTrue(entered.await(2, TimeUnit.SECONDS))
                        val shutdown = java.util.concurrent.CompletableFuture.runAsync { server.close() }
                        await { target.get().isClosed }
                        resume.countDown()
                        shutdown.get(3, TimeUnit.SECONDS)
                        assertTrue(failures.toString(), failures.isEmpty())
                    }
                }
            } finally { resume.countDown() }
        }
    }

    @Test(timeout = 10_000) fun startedControlFrameStillHasTenSecondDeadlineInsideAckWindow() {
        val clock = AtomicLong(100_000)
        val failures = ConcurrentLinkedQueue<FailureDiagnostic>()
        ReceiverServer.forProtocolTests("Receiver", timeMilliseconds = clock::get, onFailure = failures::add).use { server ->
            server.start()
            control(server).use { peer ->
                MediaSockets.attach(server, negotiate(peer)).use {
                    val owner = owner(server)
                    server.disconnect("user")
                    assertEquals(Type.STOP, Wire.read(peer.inputStream).type)
                    peer.outputStream.write(0x53)
                    await { field(owner, "readStartedAt") != null }
                    clock.addAndGet(9_999)
                    tick(owner)
                    assertTrue(failures.toString(), failures.isEmpty())
                    clock.incrementAndGet()
                    tick(owner)
                    await { !server.busyForUpdate }
                    assertEquals("TIMEOUT", failures.single().code)
                }
            }
        }
    }

    @Test(timeout = 10_000) fun ackWindowStartsAtSuccessfulStopWriteNotEnqueueOrEarlierControlWrite() {
        val clock = AtomicLong(100_000)
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val failures = ConcurrentLinkedQueue<FailureDiagnostic>()
        ReceiverServer.forProtocolTests("Receiver", timeMilliseconds = clock::get, onFailure = failures::add,
            controlWrite = { socket, message ->
                if (message.type == Type.PONG) {
                    entered.countDown()
                    check(resume.await(5, TimeUnit.SECONDS))
                }
                Wire.write(socket.outputStream, message)
            }).use { server ->
            try {
                server.start()
                control(server).use { peer ->
                    MediaSockets.attach(server, negotiate(peer)).use {
                        val owner = owner(server)
                        send(peer, Type.PING, JSONObject().put("nonce", 7))
                        assertTrue(entered.await(2, TimeUnit.SECONDS))
                        server.disconnect("user")
                        clock.addAndGet(3_000)
                        tick(owner)
                        assertNull(field(owner, "stopWrittenAt"))
                        assertTrue(server.busyForUpdate)
                        assertTrue(failures.toString(), failures.isEmpty())
                        resume.countDown()
                        assertEquals(Type.PONG, Wire.read(peer.inputStream).type)
                        assertEquals(Type.STOP, Wire.read(peer.inputStream).type)
                        await { field(owner, "stopWrittenAt") != null }
                        assertEquals(clock.get(), field(owner, "stopWrittenAt"))
                        clock.addAndGet(21_999)
                        tick(owner)
                        assertTrue("22 seconds starts after STOP, not the previous PONG or enqueue", server.busyForUpdate)
                        assertTrue(failures.toString(), failures.isEmpty())
                        send(peer, Type.SESSION_END, JSONObject().put("reason", "user"))
                        await { !server.busyForUpdate }
                        clock.incrementAndGet()
                        tick(owner)
                        assertTrue(failures.toString(), failures.isEmpty())
                    }
                }
            } finally { resume.countDown() }
        }
    }

    @Test(timeout = 10_000) fun completeStartedFrameAndLateRecoveryAreDiscardedWithoutAdmissionOrRevival() {
        val clock = AtomicLong(100_000)
        val failures = ConcurrentLinkedQueue<FailureDiagnostic>()
        val creations = AtomicLong()
        ReceiverServer.forProtocolTests("Receiver", timeMilliseconds = clock::get, onFailure = failures::add,
            videoFactory = { _, _, _, _ -> creations.incrementAndGet(); error("Stop must not create a decoder") }).use { server ->
            server.start()
            control(server).use { peer ->
                MediaSockets.attach(server, negotiate(peer)).use { media ->
                    val owner = owner(server)
                    val lane = mediaLane(owner, 0)
                    val bytes = frameBytes()
                    media.video.outputStream.write(bytes, 0, 12)
                    await { field(lane, "readStartedAt") != null }
                    server.disconnect("user")
                    assertEquals(Type.STOP, Wire.read(peer.inputStream).type)
                    clock.addAndGet(2_500)
                    tick(owner); tick(lane)
                    assertTrue(failures.toString(), failures.isEmpty())
                    media.video.outputStream.write(bytes, 12, bytes.size - 12)
                    await { field(lane, "readStartedAt") == null }
                    clock.incrementAndGet()
                    Wire.write(media.video.outputStream, Message.json(Type.STREAM_CONFIG,
                        JSONObject().put("codec", "h264").put("recoveryPtsUs", 0), ID, 1, 2))
                    await { field(lane, "lastRead") == clock.get() && field(lane, "readStartedAt") == null }
                    clock.incrementAndGet()
                    Wire.write(media.video.outputStream, Message(Type.VIDEO_FRAME, ID, 1, 2, 1,
                        payload = byteArrayOf(1)))
                    await { field(lane, "lastRead") == clock.get() && field(lane, "readStartedAt") == null }
                    assertEquals(0L, creations.get())
                    assertNull(field(owner, "video"))
                    assertFalse(field(owner, "tracksReady") as Boolean)
                    clock.addAndGet(10_001)
                    tick(owner)
                    assertTrue("Control idle wait must not retain its old 10-second Stop cutoff", server.busyForUpdate)
                    send(peer, Type.SESSION_END, JSONObject().put("reason", "drained"))
                    await { !server.busyForUpdate }
                    assertTrue(failures.toString(), failures.isEmpty())
                }
            }
        }
    }

    @Test(timeout = 10_000) fun startedMediaFrameRetainsOriginalAbsoluteTenSecondDeadlineDuringStop() {
        val clock = AtomicLong(100_000)
        val failures = ConcurrentLinkedQueue<FailureDiagnostic>()
        ReceiverServer.forProtocolTests("Receiver", timeMilliseconds = clock::get, onFailure = failures::add).use { server ->
            server.start()
            control(server).use { peer ->
                MediaSockets.attach(server, negotiate(peer)).use { media ->
                    val owner = owner(server)
                    val lane = mediaLane(owner, 0)
                    media.video.outputStream.write(frameBytes(), 0, 12)
                    await { field(lane, "readStartedAt") != null }
                    clock.addAndGet(3_000)
                    server.disconnect("user")
                    assertEquals(Type.STOP, Wire.read(peer.inputStream).type)
                    clock.addAndGet(6_999)
                    tick(lane)
                    assertTrue(failures.toString(), failures.isEmpty())
                    clock.incrementAndGet()
                    tick(lane)
                    await { !server.busyForUpdate }
                    assertEquals("TIMEOUT", failures.single().code)
                }
            }
        }
    }

    @Test(timeout = 10_000) fun truncatedStartedMediaFrameIsFatalDuringStopButIdleMediaEofIsClean() {
        for (partial in listOf(false, true)) {
            val failures = ConcurrentLinkedQueue<FailureDiagnostic>()
            ReceiverServer.forProtocolTests("Receiver", onFailure = failures::add).use { server ->
                server.start()
                control(server).use { peer ->
                    MediaSockets.attach(server, negotiate(peer)).use { media ->
                        val owner = owner(server)
                        val lane = mediaLane(owner, 0)
                        if (partial) {
                            media.video.outputStream.write(frameBytes(), 0, 12)
                            await { field(lane, "readStartedAt") != null }
                        }
                        server.disconnect("user")
                        assertEquals(Type.STOP, Wire.read(peer.inputStream).type)
                        media.video.shutdownOutput()
                        if (!partial) send(peer, Type.SESSION_END, JSONObject().put("reason", "drained"))
                        await { !server.busyForUpdate }
                        if (partial) assertEquals("PROTOCOL", failures.single().code)
                        else assertTrue(failures.toString(), failures.isEmpty())
                    }
                }
            }
        }
    }

    @Test(timeout = 10_000) fun blockedStopWriteKeepsTenSecondWriteDeadlineAndDoesNotStartAckTimer() {
        val clock = AtomicLong(100_000)
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val failures = ConcurrentLinkedQueue<FailureDiagnostic>()
        ReceiverServer.forProtocolTests("Receiver", timeMilliseconds = clock::get, onFailure = failures::add,
            controlWrite = { socket, message ->
                if (message.type == Type.STOP) {
                    entered.countDown()
                    check(resume.await(5, TimeUnit.SECONDS))
                    throw IOException("failed started STOP")
                }
                Wire.write(socket.outputStream, message)
            }).use { server ->
            try {
                server.start()
                control(server).use { peer ->
                    MediaSockets.attach(server, negotiate(peer)).use {
                        val owner = owner(server)
                        server.disconnect("user")
                        assertTrue(entered.await(2, TimeUnit.SECONDS))
                        clock.addAndGet(2_001)
                        tick(owner)
                        assertNull(field(owner, "stopWrittenAt"))
                        assertTrue(failures.toString(), failures.isEmpty())
                        clock.addAndGet(7_999)
                        tick(owner)
                        assertEquals("TIMEOUT", failures.single().code)
                        resume.countDown()
                        await { !server.busyForUpdate }
                        assertNull(field(owner, "stopWrittenAt"))
                    }
                }
            } finally { resume.countDown() }
        }
    }

    @Test(timeout = 10_000) fun failedStopWriteIsFatalAndNeverPublishesSuccessfulWriteFence() {
        val failures = ConcurrentLinkedQueue<FailureDiagnostic>()
        ReceiverServer.forProtocolTests("Receiver", onFailure = failures::add,
            controlWrite = { socket, message ->
                if (message.type == Type.STOP) throw IOException("broken pipe")
                Wire.write(socket.outputStream, message)
            }).use { server ->
            server.start()
            control(server).use { peer ->
                MediaSockets.attach(server, negotiate(peer)).use {
                    val owner = owner(server)
                    server.disconnect("user")
                    await { !server.busyForUpdate }
                    assertNull(field(owner, "stopWrittenAt"))
                    assertEquals("PROTOCOL", failures.single().code)
                }
            }
        }
    }

    @Test(timeout = 10_000) fun missingAckFailsAtExactlyTwentyTwoSecondsAndLegacyStillClosesAtTwo() {
        for (v2 in listOf(false, true)) {
            val clock = AtomicLong(100_000)
            val failures = ConcurrentLinkedQueue<FailureDiagnostic>()
            ReceiverServer.forProtocolTests("Receiver", timeMilliseconds = clock::get, onFailure = failures::add).use { server ->
                server.start()
                control(server).use { peer ->
                    MediaSockets.attach(server, negotiate(peer, v2)).use {
                        val owner = owner(server)
                        server.disconnect("user")
                        assertEquals(Type.STOP, Wire.read(peer.inputStream).type)
                        await { field(owner, "stopWrittenAt") != null }
                        val deadline = if (v2) 22_000L else 2_000L
                        clock.addAndGet(deadline - 1)
                        tick(owner)
                        peer.soTimeout = 100
                        assertThrows(SocketTimeoutException::class.java) { peer.inputStream.read() }
                        clock.incrementAndGet()
                        tick(owner)
                        await { !server.busyForUpdate }
                        if (v2) assertEquals("TIMEOUT", failures.single().code)
                    }
                }
            }
        }
    }

    private fun control(server: ReceiverServer) = Socket("127.0.0.1", server.listeningPort).apply { soTimeout = 3000 }
    private fun negotiate(peer: Socket, v2: Boolean = true, withAudio: Boolean = false): Message {
        Wire.write(peer.outputStream, Message.json(Type.HELLO,
            JSONObject().put("role", "sender").put("name", "Fixture").put("versions", JSONArray().put(3)).apply {
                if (v2) put("streamRecovery", true).put("streamRecoveryVersion", StreamRecoveryProtocol.VERSION)
            }))
        assertEquals(Type.HELLO, Wire.read(peer.inputStream).type)
        send(peer, Type.SESSION_REQUEST, dev.screencopy.sender.Settings().apply {
            audio = withAudio
            if (withAudio) { videoCodec = "h265"; audioCodec = "opus" }
        }.request())
        return Wire.read(peer.inputStream).also { assertEquals(Type.SESSION_ACCEPT, it.type) }
    }

    private fun send(peer: Socket, type: Type, json: JSONObject) = Wire.write(peer.outputStream, Message.json(type, json, ID))
    private fun frameBytes() = ByteArrayOutputStream().apply {
        Wire.write(this, Message(Type.VIDEO_FRAME, ID, 1, 1, 0, payload = ByteArray(1024)))
    }.toByteArray()
    private fun owner(server: ReceiverServer): Any =
        (ReceiverServer::class.java.getDeclaredField("active").apply { isAccessible = true }.get(server) as AtomicReference<*>).get()!!
    private fun field(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    private fun mediaLane(owner: Any, index: Int): Any = (field(owner, "mediaChannels") as Array<*>)[index]!!
    private fun tick(target: Any) { target.javaClass.getDeclaredMethod("tick").apply { isAccessible = true }.invoke(target) }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(1)
        assertTrue("Condition did not become true", condition())
    }
    companion object { private const val ID = 81L }
}

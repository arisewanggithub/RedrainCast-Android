package org.screencopy.receiver.media

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.screencopy.receiver.net.MediaSockets
import org.screencopy.receiver.net.ReceiverServer
import org.screencopy.receiver.protocol.*
import java.net.InetAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import dev.screencopy.sender.MediaBudget
import dev.screencopy.sender.ReceiverLink
import dev.screencopy.sender.ReceiverTarget
import dev.screencopy.sender.StreamRecoverySender
import dev.screencopy.sender.Wire as SenderWire

class StreamRecoveryPipelineTest {
    @Test(timeout = 15_000) fun recoveryAfterLongPlaybackUsesItsTrackDeadlineNotTheSetupTimestamp() {
        for (track in 1..2) recoveryDeadline(track, expireControl = false)
    }

    @Test(timeout = 10_000) fun freshRecoveryDoesNotDisableControlReadLiveness() =
        recoveryDeadline(1, expireControl = true)

    private fun recoveryDeadline(track: Int, expireControl: Boolean) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val audioCreations = AtomicInteger()
        ReceiverServer.forProtocolTests("Receiver",
            videoFactory = { format, clock, failed, generation ->
                if (track == 1 && generation == 2L) {
                    entered.countDown()
                    assertTrue(release.await(3, TimeUnit.SECONDS))
                }
                VideoPlayer(format, clock, failed, Decoder(false), generation)
            },
            audioFactory = { format, clock, failed ->
                if (track == 2 && audioCreations.incrementAndGet() == 2) {
                    entered.countDown()
                    assertTrue(release.await(3, TimeUnit.SECONDS))
                }
                testAudioPlayer(format, clock, failed, { Output() }, opusFactory = {
                    object : OpusPacketDecoder {
                        override fun decode(packet: ByteArray) = ShortArray(1920)
                        override fun close() {}
                    }
                })
            }).use { server ->
            try {
                session(server) { peer, media ->
                    Wire.write(media.video.outputStream, frame(1_000_000, key = true))
                    assertEquals(StreamRecoveryProtocol.KEY, expect(peer, Type.STATUS).json().getString("state"))
                    assertEquals(StreamRecoveryProtocol.COMPLETE, expect(peer, Type.STATUS).json().getString("state"))
                    val active = ReceiverServer::class.java.getDeclaredField("active").apply { isAccessible = true }
                        .get(server) as java.util.concurrent.atomic.AtomicReference<*>
                    val connection = checkNotNull(active.get())
                    fun field(name: String) = connection.javaClass.getDeclaredField(name).apply { isAccessible = true }
                    assertEquals("Playing", field("phase").get(connection))
                    val timer = ReceiverServer::class.java.getDeclaredField("timer").apply { isAccessible = true }
                        .get(server) as java.util.concurrent.ScheduledExecutorService
                    timer.shutdown()
                    assertTrue(timer.awaitTermination(3, TimeUnit.SECONDS))
                    val oldSetup = System.nanoTime() / 1_000_000 - 30_000
                    field("phaseAt").setLong(connection, oldSetup)
                    val socket = field("socket").get(connection) as Socket
                    val config = if (track == 1) videoConfig(2) else audioConfig(2, 1_080_000)
                    Wire.write((if (track == 1) media.video else media.audio).outputStream, config)
                    assertTrue(entered.await(2, TimeUnit.SECONDS))
                    assertEquals("Recovering", field("phase").get(connection))
                    sendControl(peer, Type.PING, JSONObject().put("nonce", 741))
                    assertEquals(741, expect(peer, Type.PONG).json().getInt("nonce"))
                    val recoveryAt = field(if (track == 1) "videoRecoveryAt" else "audioRecoveryAt")
                    val originalRecovery = recoveryAt.getLong(connection)
                    assertTrue(originalRecovery > 0)
                    val tick = connection.javaClass.getDeclaredMethod("tick").apply { isAccessible = true }
                    if (expireControl) field("lastRead").setLong(connection, System.nanoTime() / 1_000_000 - 10_000)
                    tick.invoke(connection)
                    if (expireControl) {
                        assertTrue("Control read liveness must still close the socket", socket.isClosed)
                    } else {
                        assertFalse("A stale setup deadline must not close fresh recovery", socket.isClosed)
                        assertEquals(oldSetup, field("phaseAt").getLong(connection))
                        assertEquals(originalRecovery, recoveryAt.getLong(connection))
                        recoveryAt.setLong(connection, System.nanoTime() / 1_000_000 - 9_000)
                        tick.invoke(connection)
                        assertFalse("Track recovery must retain its full ten-second budget", socket.isClosed)
                        recoveryAt.setLong(connection, System.nanoTime() / 1_000_000 - 10_000)
                        tick.invoke(connection)
                        assertTrue("The original track deadline must still expire", socket.isClosed)
                    }
                    release.countDown()
                }
            } finally { release.countDown() }
        }
    }

    @Test(timeout = 10_000) fun fatalErrorIsDeliveredWhileControlWaitsForPlaybackRetirement() {
        val retiring = CountDownLatch(1)
        val release = CountDownLatch(1)
        ReceiverServer.forProtocolTests("Receiver",
            onPresentationStart = { AutoCloseable { retiring.countDown(); check(release.await(3, TimeUnit.SECONDS)) } },
            videoFactory = { format, clock, failed, generation ->
                val decoder = Decoder(false)
                VideoPlayer(format, clock, failed, object : VideoDecoder by decoder {
                    override fun queueInput(index: Int, size: Int, ptsUs: Long, keyFrame: Boolean) {
                        throw PlaybackException("Synthetic decoder output failure")
                    }
                }, generation)
            },
            audioFactory = { format, clock, failed ->
                testAudioPlayer(format, clock, failed, { Output() }, opusFactory = {
                    object : OpusPacketDecoder {
                        override fun decode(packet: ByteArray) = ShortArray(1920)
                        override fun close() {}
                    }
                })
            }).use { server ->
            try {
                session(server) { peer, media ->
                    Wire.write(media.video.outputStream, frame(1_000_000, key = true))
                    val error = expect(peer, Type.ERROR)
                    assertEquals("DECODER_FAILED", error.json().getString("code"))
                    val retirementStarted = retiring.await(1, TimeUnit.SECONDS)
                    assertTrue("Playback retirement did not start: " + if (retirementStarted) "" else
                        Thread.getAllStackTraces().entries.filter { (thread, stack) ->
                            thread.name.startsWith("ScreenCopy") || stack.any { it.className.contains("ReceiverServer") }
                        }.joinToString("\n") { (thread, stack) ->
                            "${thread.name}: ${stack.joinToString(" <- ") { "${it.className}.${it.methodName}:${it.lineNumber}" }}"
                        }, retirementStarted)
                    assertEquals(-1, media.video.inputStream.read())
                    assertEquals(-1, media.audio.inputStream.read())
                    peer.soTimeout = 100
                    assertThrows(java.net.SocketTimeoutException::class.java) { peer.inputStream.read() }
                    release.countDown()
                    peer.soTimeout = 3000
                    assertEquals(-1, peer.inputStream.read())
                }
            } finally { release.countDown() }
        }
    }

    @Test(timeout = 10_000) fun delayedVideoProofCompletesWithoutWaitingForSourceAudio() {
        for (delayMs in listOf(350L, 800L)) {
            val failures = ConcurrentLinkedQueue<String>()
            val pressure = AtomicInteger()
            ReceiverServer.forProtocolTests("Receiver", onFailure = { failures.add(it.logMessage()) },
                videoFactory = { format, clock, failed, generation ->
                    val decoder = Decoder(false)
                    val delayed = AtomicBoolean()
                    VideoPlayer(format, clock, failed, object : VideoDecoder by decoder {
                        override fun dequeueOutput(): VideoOutput? {
                            val output = decoder.dequeueOutput() ?: return null
                            if (delayed.compareAndSet(false, true)) Thread.sleep(delayMs)
                            return output
                        }
                    }, generation)
                },
                audioFactory = { format, clock, failed ->
                    testAudioPlayer(format, clock, failed, { Output() }, opusFactory = {
                        object : OpusPacketDecoder {
                            override fun decode(packet: ByteArray) = ShortArray(1920)
                            override fun close() {}
                        }
                    })
                }).use { server ->
                session(server) { peer, media ->
                    Wire.write(media.video.outputStream, frame(1_000_000, key = true))
                    val key = expect(peer, Type.STATUS).json()
                    assertEquals(StreamRecoveryProtocol.KEY, key.getString("state"))
                    assertEquals(1, key.getInt("generation"))
                    assertEquals("1000000", key.getString("keyPtsUs"))
                    val complete = expect(peer, Type.STATUS).json()
                    assertEquals(StreamRecoveryProtocol.COMPLETE, complete.getString("state"))
                    assertEquals(1, complete.getInt("track"))
                    assertFalse(complete.has("audioPtsUs"))
                    Thread.sleep(delayMs)
                    sendControl(peer, Type.PING, JSONObject().put("nonce", 902))
                    assertEquals(902, expect(peer, Type.PONG).json().getInt("nonce"))
                    Wire.write(media.audio.outputStream,
                        Message(Type.AUDIO_PACKET, 41, 2, 1, 1_000_000, 0, byteArrayOf(8, 1)))
                    sendControl(peer, Type.STOP, JSONObject().put("reason", "delayed recovery complete"))
                    expect(peer, Type.SESSION_END)
                }
            }
            assertEquals(0, pressure.get())
            assertTrue(failures.toString(), failures.isEmpty())
        }
    }

    @Test(timeout = 10_000) fun senderStopDeadlineStartsAtStopWhilePresentationRetires() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        ReceiverServer.forProtocolTests("Receiver",
            onPresentationStart = { AutoCloseable { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) } },
            videoFactory = { format, clock, failed, generation ->
                VideoPlayer(format, clock, failed, Decoder(false), generation)
            },
            audioFactory = { format, clock, failed ->
                testAudioPlayer(format, clock, failed, { Output() }, opusFactory = {
                    object : OpusPacketDecoder {
                        override fun decode(packet: ByteArray) = ShortArray(1920)
                        override fun close() {}
                    }
                })
            }).use { server ->
            session(server) { peer, _ ->
                try {
                    sendControl(peer, Type.STOP, JSONObject().put("reason", "bounded retirement"))
                    assertTrue(entered.await(2, TimeUnit.SECONDS))
                    val active = ReceiverServer::class.java.getDeclaredField("active").apply { isAccessible = true }
                        .get(server) as java.util.concurrent.atomic.AtomicReference<*>
                    val connection = checkNotNull(active.get())
                    connection.javaClass.getDeclaredMethod("tick").apply { isAccessible = true }.invoke(connection)
                } finally { release.countDown() }
                expect(peer, Type.SESSION_END)
            }
        }
    }

    @Test(timeout = 10_000) fun audioConfigurationCannotOvertakeItsOwnPendingAdmission() {
        val player = java.util.concurrent.atomic.AtomicReference<AudioPlayer>()
        val failures = ConcurrentLinkedQueue<String>()
        ReceiverServer.forProtocolTests("Receiver", onFailure = { failures.add(it.logMessage()) },
            videoFactory = { format, clock, failed, generation ->
                VideoPlayer(format, clock, failed, Decoder(false), generation)
            },
            audioFactory = { format, clock, failed ->
                testAudioPlayer(format, clock, failed, { Output() }, opusFactory = {
                    object : OpusPacketDecoder {
                        override fun decode(packet: ByteArray) = ShortArray(1920)
                        override fun close() {}
                    }
                }).also(player::set)
            }).use { server ->
            session(server) { peer, media ->
                val old = checkNotNull(player.get())
                Wire.write(media.video.outputStream, frame(1_000_000, key = true))
                assertEquals(StreamRecoveryProtocol.KEY, expect(peer, Type.STATUS).json().getString("state"))
                val monitor = checkNotNull(TrackWorker::class.java.getDeclaredField("monitor").apply { isAccessible = true }.get(old))
                val running = checkNotNull(AtomicBoolean::class.java.cast(
                    TrackWorker::class.java.getDeclaredField("running").apply { isAccessible = true }.get(old)))
                synchronized(monitor) {
                    Wire.write(media.audio.outputStream, Message(Type.AUDIO_PACKET, 41, 2, 1, 1_000_000, 0, byteArrayOf(8, 1)))
                    await {
                        Thread.getAllStackTraces().values.any { stack ->
                            stack.any { it.className == TrackWorker::class.java.name && it.methodName == "tryOffer" }
                        }
                    }
                    Wire.write(media.audio.outputStream, audioConfig(2, 2_000_000))
                    sendControl(peer, Type.PING, JSONObject().put("nonce", 900))
                    assertEquals(900, expect(peer, Type.PONG).json().getInt("nonce"))
                    assertTrue("Same-lane configuration must remain behind pending admission", running.get())
                }
                assertEquals(2L, expect(peer, Type.STREAM_READY).generation)
                sendControl(peer, Type.PING, JSONObject().put("nonce", 901))
                assertEquals(901, expect(peer, Type.PONG).json().getInt("nonce"))
                assertNotSame(old, player.get())
                assertTrue(failures.toString(), failures.isEmpty())
                sendControl(peer, Type.STOP, JSONObject().put("reason", "race test complete"))
                expect(peer, Type.SESSION_END)
            }
        }
    }

    private fun session(server: ReceiverServer, use: (Socket, MediaSockets) -> Unit) {
        server.start()
        Socket(InetAddress.getLoopbackAddress(), server.listeningPort).use { peer ->
            peer.soTimeout = 4000
            sendControl(peer, Type.HELLO, JSONObject().put("role", "sender").put("name", "Phone")
                .put("versions", JSONArray().put(3)).put("streamRecovery", true).put("streamRecoveryVersion", 2), 0)
            assertTrue(expect(peer, Type.HELLO).json().getBoolean("streamRecovery"))
            sendControl(peer, Type.SESSION_REQUEST, request())
            MediaSockets.attach(server, expect(peer, Type.SESSION_ACCEPT)).use { media ->
                Wire.write(media.video.outputStream, videoConfig(1)); expect(peer, Type.STREAM_READY)
                Wire.write(media.audio.outputStream, audioConfig()); expect(peer, Type.STREAM_READY)
                use(peer, media)
            }
        }
    }

    @Test(timeout = 10_000) fun genuineRecoveryDecoderFailureIsFatalNotPretendReadiness() {
        val failures = ConcurrentLinkedQueue<String>()
        ReceiverServer.forProtocolTests("Receiver", onFailure = { failures.add(it.logMessage()) },
            videoFactory = { format, clock, failed, generation ->
                if (generation == 2L) throw PlaybackException("fixture decoder cannot reopen")
                VideoPlayer(format, clock, failed, Decoder(false), generation)
            },
            audioFactory = { format, clock, failed ->
                testAudioPlayer(format, clock, failed, { Output() }, opusFactory = {
                    object : OpusPacketDecoder {
                        override fun decode(packet: ByteArray) = ShortArray(1920)
                        override fun close() {}
                    }
                })
            }).use { server ->
            session(server) { peer, media ->
                Wire.write(media.video.outputStream, videoConfig(2))
                val error = expect(peer, Type.ERROR)
                assertTrue(error.json().getBoolean("fatal"))
                assertEquals("DECODER_FAILED", error.json().getString("code"))
                await { failures.isNotEmpty() }
            }
        }
    }

    @Test(timeout = 10_000) fun stopDuringRecoveryConstructionClosesResourcesWithoutLateReadinessOrAudioStart() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val decoders = ConcurrentLinkedQueue<Decoder>()
        val audioStarts = AtomicInteger()
        ReceiverServer.forProtocolTests("Receiver",
            videoFactory = { format, clock, failed, generation ->
                if (generation == 2L) {
                    entered.countDown()
                    assertTrue(release.await(3, TimeUnit.SECONDS))
                }
                VideoPlayer(format, clock, failed, Decoder(false).also(decoders::add), generation)
            },
            audioFactory = { format, clock, failed ->
                audioStarts.incrementAndGet()
                testAudioPlayer(format, clock, failed, { Output() }, opusFactory = {
                    object : OpusPacketDecoder {
                        override fun decode(packet: ByteArray) = ShortArray(1920)
                        override fun close() {}
                    }
                })
            }).use { server ->
            try {
                session(server) { peer, media ->
                    Wire.write(media.video.outputStream, videoConfig(2))
                    assertTrue(entered.await(2, TimeUnit.SECONDS))
                    sendControl(peer, Type.PING, JSONObject().put("nonce", 881))
                    assertEquals(881, expect(peer, Type.PONG).json().getInt("nonce"))
                    sendControl(peer, Type.STOP, JSONObject().put("reason", "stop during reset"))
                    await {
                        Thread.getAllStackTraces().values.any { stack ->
                            stack.any { it.className.contains("ReceiverServer") && it.methodName == "stopPlayback" }
                        }
                    }
                    release.countDown()
                    expect(peer, Type.SESSION_END)
                }
            } finally { release.countDown() }
        }
        assertEquals(1, audioStarts.get())
        assertTrue(decoders.all { it.closed })
    }

    private class Decoder(private val stalled: Boolean) : VideoDecoder {
        override val description = "Recovery fixture decoder"
        val pts = ConcurrentLinkedQueue<Long>()
        val keys = ConcurrentLinkedQueue<Boolean>()
        val rendered = AtomicInteger()
        private val outputs = ConcurrentLinkedQueue<VideoOutput>()
        private var sequence = 0
        @Volatile var closed = false
        override fun dequeueInput() = if (stalled) -1 else 0
        override fun input(index: Int) = ByteBuffer.allocate(64)
        override fun queueInput(index: Int, size: Int, ptsUs: Long, keyFrame: Boolean) {
            pts.add(ptsUs)
            keys.add(keyFrame)
            outputs.add(VideoOutput(++sequence, ptsUs, size, 0))
        }
        override fun dequeueOutput(): VideoOutput? = outputs.poll()
        override fun releaseOutput(index: Int, renderAtNs: Long?) {
            if (renderAtNs != null) rendered.incrementAndGet()
        }
        override fun close() { closed = true }
    }

    private class Output : AudioOutput {
        @Volatile var closed = false
        val writtenFrames = AtomicInteger()
        override val underrunCount = 0
        private var submitted = 0L
        private var consumed = 0.0
        private var previous = System.nanoTime()
        private var playing = false
        private var speed = 1f
        override val playbackActive: Boolean get() = playing
        private fun advance() {
            val now = System.nanoTime()
            if (playing) consumed = minOf(submitted.toDouble(), consumed + (now - previous) * 48000.0 * speed / 1_000_000_000)
            previous = now
        }
        @Synchronized override fun play() { previous = System.nanoTime(); playing = true }
        @Synchronized override fun pause() { advance(); playing = false }
        @Synchronized override fun write(samples: ShortArray, offset: Int, count: Int): Int {
            advance()
            val frames = minOf(count / 2, (6240 - submitted + consumed.toLong()).toInt().coerceAtLeast(0))
            submitted += frames
            writtenFrames.addAndGet(frames)
            return frames * 2
        }
        @Synchronized override fun timing(): AudioOutputTiming {
            advance()
            return AudioOutputTiming(48000, 6240, submitted, consumed.toLong(), consumed.toLong(), 0, AudioClockSource.TIMESTAMP)
        }
        @Synchronized override fun setSpeed(speed: Float) { advance(); this.speed = speed }
        override fun close() { closed = true }
    }

    private fun request() = StrictJson.decode(
        """{"video":{"codec":"h265","encoder":"","bitrate":20000000,"maxSize":1920,"maxFps":60,"displayId":0,"orientation":"0","crop":null,"codecOptions":""},"audio":{"enabled":true,"codec":"opus","encoder":"","bitrate":192000,"source":"playback","codecOptions":""}}""".toByteArray())

    private fun videoConfig(generation: Long) = Message.json(Type.STREAM_CONFIG,
        JSONObject().put("codec", "h265").put("width", 720).put("height", 1280)
            .put("csd", JSONArray().put("AAAAAQE=")), 41, 1, generation)

    private fun audioConfig(generation: Long = 1, cutoff: Long? = null): Message {
        val head = "OpusHead".toByteArray() + byteArrayOf(1, 2, 0x38, 1, 0x80.toByte(), 0xbb.toByte(), 0, 0, 0, 0, 0)
        return Message.json(Type.STREAM_CONFIG, JSONObject().put("codec", "opus").put("sampleRate", 48000)
            .put("channels", 2).put("csd", JSONArray().put(Base64.getEncoder().encodeToString(head)))
            .put("codecDelayNs", 6_500_000).put("seekPrerollNs", 80_000_000).apply {
                if (cutoff != null) put("recoveryPtsUs", cutoff.toString())
            }, 41, 2, generation)
    }

    private fun frame(pts: Long, generation: Long = 1, key: Boolean = false, rotation: Int = 0) =
        Message(Type.VIDEO_FRAME, 41, 1, generation, pts, (if (key) 1 else 0) or (rotation / 90 shl 1),
            byteArrayOf(0, 0, 0, 1, 0x26, 1))

    private fun sendControl(peer: Socket, type: Type, json: JSONObject, session: Long = 41) =
        Wire.write(peer.outputStream, Message.json(type, json, session))

    private fun expect(peer: Socket, type: Type): Message {
        while (true) {
            val message = Wire.read(peer.inputStream)
            if (message.type == Type.PING) {
                sendControl(peer, Type.PONG, JSONObject().put("nonce", message.json().getInt("nonce")))
                continue
            }
            if (type != Type.STATUS && message.type == Type.STATUS &&
                message.json().getString("state") in setOf(StreamRecoveryProtocol.KEY, StreamRecoveryProtocol.COMPLETE)) continue
            assertEquals("Unexpected peer reply: ${message.type} ${message.payload.toString(Charsets.UTF_8)}", type, message.type)
            return message
        }
    }

    private fun await(condition: () -> Boolean) {
        val start = System.nanoTime()
        while (!condition() && System.nanoTime() - start < 3_000_000_000L) Thread.sleep(2)
        assertTrue("Recovery pipeline did not reach expected state", condition())
    }

    @Test(timeout = 10_000) fun slowVideoConstructionDoesNotPauseOrRecreateHealthyAudio() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val outputs = ConcurrentLinkedQueue<Output>()
        val players = ConcurrentLinkedQueue<AudioPlayer>()
        val failures = ConcurrentLinkedQueue<String>()
        ReceiverServer.forProtocolTests("Receiver", onFailure = { failures.add(it.logMessage()) },
            videoFactory = { format, clock, failed, generation ->
                if (generation == 2L) { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
                VideoPlayer(format, clock, failed, Decoder(false), generation)
            },
            audioFactory = { format, clock, failed ->
                testAudioPlayer(format, clock, failed, { Output().also(outputs::add) }, opusFactory = {
                    object : OpusPacketDecoder {
                        override fun decode(packet: ByteArray) = ShortArray(1920)
                        override fun close() {}
                    }
                }).also(players::add)
            }).use { server ->
            try {
                session(server) { peer, media ->
                    Wire.write(media.video.outputStream, frame(1_000_000, key = true))
                    expect(peer, Type.STATUS); expect(peer, Type.STATUS)
                    Wire.write(media.audio.outputStream, Message(Type.AUDIO_PACKET, 41, 2, 1, 1_000_000, 0, byteArrayOf(8, 1)))
                    await { players.single().sourceSubmittedFrames > 0 }
                    val before = players.single().sourceSubmittedFrames
                    Wire.write(media.video.outputStream, videoConfig(2))
                    assertTrue(entered.await(2, TimeUnit.SECONDS))
                    repeat(4) { index ->
                        Wire.write(media.audio.outputStream, Message(Type.AUDIO_PACKET, 41, 2, 1,
                            1_020_000 + index * 20_000L, 0, byteArrayOf(8, 1)))
                    }
                    await { players.single().sourceSubmittedFrames > before }
                    assertFalse(outputs.single().closed)
                    assertTrue(failures.toString(), failures.isEmpty())
                    release.countDown()
                    assertEquals(1, expect(peer, Type.STREAM_READY).track)
                    sendControl(peer, Type.STOP, JSONObject().put("reason", "audio continuity verified"))
                    expect(peer, Type.SESSION_END)
                }
            } finally { release.countDown() }
        }
        assertEquals(1, outputs.size)
    }

    @Test(timeout = 10_000) fun slowAudioConstructionKeepsVideoDecoderAndSourceClockContinuous() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val decoders = ConcurrentLinkedQueue<Decoder>()
        val outputs = ConcurrentLinkedQueue<Output>()
        val formats = ConcurrentLinkedQueue<AudioFormat>()
        val clocks = ConcurrentLinkedQueue<PlaybackClock>()
        val failures = ConcurrentLinkedQueue<String>()
        ReceiverServer.forProtocolTests("Receiver", onFailure = { failures.add(it.logMessage()) },
            clockFactory = { PlaybackClock().also(clocks::add) },
            videoFactory = { format, clock, failed, generation ->
                VideoPlayer(format, clock, failed, Decoder(false).also(decoders::add), generation)
            },
            audioFactory = { format, clock, failed ->
                formats.add(format)
                if (formats.size == 2) { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
                testAudioPlayer(format, clock, failed, { Output().also(outputs::add) }, opusFactory = {
                    object : OpusPacketDecoder {
                        override fun decode(packet: ByteArray) = ShortArray(1920)
                        override fun close() {}
                    }
                })
            }).use { server ->
            try {
                session(server) { peer, media ->
                    Wire.write(media.video.outputStream, frame(1_000_000, key = true))
                    expect(peer, Type.STATUS); expect(peer, Type.STATUS)
                    Wire.write(media.audio.outputStream, Message(Type.AUDIO_PACKET, 41, 2, 1, 1_000_000, 0, byteArrayOf(8, 1)))
                    await { outputs.single().writtenFrames.get() > 0 }
                    val sourceBefore = checkNotNull(clocks.single().currentUs())
                    val renderedBefore = decoders.single().rendered.get()
                    Wire.write(media.audio.outputStream, audioConfig(2, 1_200_000))
                    assertTrue(entered.await(2, TimeUnit.SECONDS))
                    assertTrue(outputs.first().closed)
                    Wire.write(media.video.outputStream, frame(1_020_000))
                    Wire.write(media.video.outputStream, frame(1_040_000))
                    await { decoders.single().pts.contains(1_040_000) }
                    await { decoders.single().rendered.get() > renderedBefore }
                    assertFalse(decoders.single().closed)
                    assertEquals(1, clocks.size)
                    assertTrue(checkNotNull(clocks.single().currentUs()) >= sourceBefore)
                    release.countDown()
                    val ready = expect(peer, Type.STREAM_READY)
                    assertEquals(2, ready.track)
                    assertEquals(2L, ready.generation)
                    repeat(5) { index ->
                        Wire.write(media.audio.outputStream, Message(Type.AUDIO_PACKET, 41, 2, 2,
                            1_120_000 + index * 20_000L, 0, byteArrayOf(8, 1)))
                    }
                    val complete = expect(peer, Type.STATUS).json()
                    assertEquals(StreamRecoveryProtocol.COMPLETE, complete.getString("state"))
                    assertEquals(2, complete.getInt("track"))
                    assertFalse(complete.has("keyPtsUs"))
                    assertTrue(StreamRecoveryProtocol.timestamp(complete, "audioPtsUs") >= 1_200_000)
                    assertEquals(listOf(312, 0), formats.map { it.preSkip })
                    assertEquals(1, decoders.size)
                    assertTrue(failures.toString(), failures.isEmpty())
                    sendControl(peer, Type.STOP, JSONObject().put("reason", "video continuity verified"))
                    expect(peer, Type.SESSION_END)
                }
            } finally { release.countDown() }
        }
    }

    @Test(timeout = 10_000) fun audioRecoveryBeforeFirstPlaybackSurvivesTheInitialStartupDeadline() {
        val time = java.util.concurrent.atomic.AtomicLong(1_000_000_000)
        val decoding = CountDownLatch(1)
        val releaseDecode = CountDownLatch(1)
        val constructing = CountDownLatch(1)
        val releaseConstruction = CountDownLatch(1)
        val constructions = AtomicInteger()
        val decoders = ConcurrentLinkedQueue<Decoder>()
        val failures = ConcurrentLinkedQueue<String>()
        ReceiverServer.forProtocolTests("Receiver", onFailure = { failures.add(it.logMessage()) },
            clockFactory = { PlaybackClock(time::get) { time.addAndGet(it * 1_000_000) } },
            videoFactory = { format, clock, failed, generation ->
                VideoPlayer(format, clock, failed, Decoder(false).also(decoders::add), generation)
            },
            audioFactory = { format, clock, failed ->
                val first = constructions.incrementAndGet() == 1
                if (!first) { constructing.countDown(); check(releaseConstruction.await(3, TimeUnit.SECONDS)) }
                testAudioPlayer(format, clock, failed, { Output() }, opusFactory = {
                    object : OpusPacketDecoder {
                        override fun decode(packet: ByteArray): ShortArray {
                            if (first) { decoding.countDown(); releaseDecode.await() }
                            return ShortArray(1920)
                        }
                        override fun close() {}
                    }
                })
            }).use { server ->
            try {
                session(server) { peer, media ->
                    Wire.write(media.video.outputStream, frame(1_000_000, key = true))
                    expect(peer, Type.STATUS); expect(peer, Type.STATUS)
                    Wire.write(media.audio.outputStream, Message(Type.AUDIO_PACKET, 41, 2, 1, 1_000_000, 0, byteArrayOf(8, 1)))
                    assertTrue(decoding.await(2, TimeUnit.SECONDS))
                    repeat(14) { index ->
                        Wire.write(media.audio.outputStream, Message(Type.AUDIO_PACKET, 41, 2, 1,
                            1_020_000 + index * 20_000L, 0, byteArrayOf(8, 1)))
                    }
                    assertEquals(2, expect(peer, Type.STATUS).json().getInt("track"))
                    Wire.write(media.audio.outputStream, audioConfig(2, 5_000_000))
                    assertTrue(constructing.await(2, TimeUnit.SECONDS))
                    val before = decoders.single().rendered.get()
                    time.set(5_000_000_000)
                    Wire.write(media.video.outputStream, frame(5_000_000))
                    sendControl(peer, Type.PING, JSONObject().put("nonce", 993))
                    assertEquals(993, expect(peer, Type.PONG).json().getInt("nonce"))
                    await { decoders.single().rendered.get() > before }
                    releaseConstruction.countDown()
                    assertEquals(2, expect(peer, Type.STREAM_READY).track)
                    repeat(5) { index ->
                        Wire.write(media.audio.outputStream, Message(Type.AUDIO_PACKET, 41, 2, 2,
                            4_920_000 + index * 20_000L, 0, byteArrayOf(8, 1)))
                    }
                    val complete = expect(peer, Type.STATUS).json()
                    assertEquals(2, complete.getInt("track"))
                    assertEquals(StreamRecoveryProtocol.COMPLETE, complete.getString("state"))
                    assertTrue(StreamRecoveryProtocol.timestamp(complete, "audioPtsUs") >= 5_000_000)
                    assertTrue(failures.toString(), failures.isEmpty())
                    sendControl(peer, Type.STOP, JSONObject().put("reason", "delayed initial audio recovered"))
                    expect(peer, Type.SESSION_END)
                }
            } finally { releaseDecode.countDown(); releaseConstruction.countDown() }
        }
    }

    @Test(timeout = 10_000) fun audioQueuePressureRequestsOnlyAudioRecoveryAndVideoStillDecodes() {
        val decoding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val decoders = ConcurrentLinkedQueue<Decoder>()
        val outputs = ConcurrentLinkedQueue<Output>()
        val failures = ConcurrentLinkedQueue<String>()
        ReceiverServer.forProtocolTests("Receiver", onFailure = { failures.add(it.logMessage()) },
            videoFactory = { format, clock, failed, generation ->
                VideoPlayer(format, clock, failed, Decoder(false).also(decoders::add), generation)
            },
            audioFactory = { format, clock, failed ->
                val stall = outputs.isEmpty()
                testAudioPlayer(format, clock, failed, { Output().also(outputs::add) }, opusFactory = {
                    object : OpusPacketDecoder {
                        override fun decode(packet: ByteArray): ShortArray {
                            if (stall) { decoding.countDown(); release.await() }
                            return ShortArray(1920)
                        }
                        override fun close() {}
                    }
                })
            }).use { server ->
            try {
                session(server) { peer, media ->
                    Wire.write(media.video.outputStream, frame(1_000_000, key = true))
                    expect(peer, Type.STATUS); expect(peer, Type.STATUS)
                    Wire.write(media.audio.outputStream, Message(Type.AUDIO_PACKET, 41, 2, 1, 1_000_000, 0, byteArrayOf(8, 1)))
                    assertTrue(decoding.await(2, TimeUnit.SECONDS))
                    repeat(14) { index ->
                        Wire.write(media.audio.outputStream, Message(Type.AUDIO_PACKET, 41, 2, 1,
                            1_020_000 + index * 20_000L, 0, byteArrayOf(8, 1)))
                    }
                    val request = expect(peer, Type.STATUS).json()
                    assertEquals(StreamRecoveryProtocol.REQUEST, request.getString("state"))
                    assertEquals(2, request.getInt("track"))
                    assertEquals(1, request.getInt("generation"))
                    Wire.write(media.video.outputStream, frame(1_300_000))
                    await { decoders.single().pts.contains(1_300_000) }
                    Wire.write(media.audio.outputStream, audioConfig(2, 1_400_000))
                    assertEquals(2, expect(peer, Type.STREAM_READY).track)
                    assertFalse(decoders.single().closed)
                    assertTrue(outputs.first().closed)
                    repeat(5) { index ->
                        Wire.write(media.audio.outputStream, Message(Type.AUDIO_PACKET, 41, 2, 2,
                            1_320_000 + index * 20_000L, 0, byteArrayOf(8, 1)))
                    }
                    val complete = expect(peer, Type.STATUS).json()
                    assertEquals(2, complete.getInt("track"))
                    assertEquals(StreamRecoveryProtocol.COMPLETE, complete.getString("state"))
                    assertEquals(1, decoders.size)
                    assertTrue(failures.toString(), failures.isEmpty())
                    sendControl(peer, Type.STOP, JSONObject().put("reason", "audio pressure verified"))
                    expect(peer, Type.SESSION_END)
                }
            } finally { release.countDown() }
        }
    }

    @Test(timeout = 15_000) fun videoPressureResetsOnlyVideoAndKeepsAudioAndClockAlive() {
        val decoders = ConcurrentLinkedQueue<Decoder>()
        val outputs = ConcurrentLinkedQueue<Output>()
        val formats = ConcurrentLinkedQueue<AudioFormat>()
        val rotations = ConcurrentLinkedQueue<Int>()
        val clocks = ConcurrentLinkedQueue<PlaybackClock>()
        val failures = ConcurrentLinkedQueue<String>()
        val audioDecoded = AtomicInteger()
        val freshAudioDecoded = AtomicInteger()
        ReceiverServer.forProtocolTests("Receiver", onFailure = { failures.add(it.logMessage()) },
            clockFactory = { PlaybackClock().also(clocks::add) },
            videoFactory = { format, clock, failed, generation ->
                val decoder = Decoder(generation == 1L).also(decoders::add)
                VideoPlayer(format, clock, failed, decoder, generation, onRotation = rotations::add)
            },
            audioFactory = { format, clock, failed ->
                formats.add(format)
                val output = Output().also(outputs::add)
                testAudioPlayer(format, clock, failed, { output }, opusFactory = {
                    object : OpusPacketDecoder {
                        override fun decode(packet: ByteArray): ShortArray {
                            audioDecoded.incrementAndGet()
                            if (packet.last().toInt() == 2) freshAudioDecoded.incrementAndGet()
                            return ShortArray(1920)
                        }
                        override fun close() {}
                    }
                })
            }).use { server ->
            server.start()
            Socket(InetAddress.getLoopbackAddress(), server.listeningPort).use { peer ->
                peer.soTimeout = 4000
                sendControl(peer, Type.HELLO, JSONObject().put("role", "sender").put("name", "Phone")
                    .put("versions", JSONArray().put(3)).put("videoRotation", true).put("streamRecovery", true)
                    .put("streamRecoveryVersion", 2), 0)
                assertTrue(expect(peer, Type.HELLO).json().getBoolean("streamRecovery"))
                sendControl(peer, Type.SESSION_REQUEST, request())
                MediaSockets.attach(server, expect(peer, Type.SESSION_ACCEPT)).use { media ->
                    Wire.write(media.video.outputStream, videoConfig(1)); expect(peer, Type.STREAM_READY)
                    Wire.write(media.audio.outputStream, audioConfig()); expect(peer, Type.STREAM_READY)
                    repeat(40) { index -> Wire.write(media.video.outputStream, frame(1_000_000L + index * 20_000, key = index == 0)) }
                    val recovery = expect(peer, Type.STATUS)
                    assertEquals("stream-recovery-request", recovery.json().getString("state"))
                    assertEquals(1, recovery.json().getInt("generation"))
                    assertEquals(1, recovery.json().getInt("track"))
                    Wire.write(media.audio.outputStream, Message(Type.AUDIO_PACKET, 41, 2, 1,
                        2_000_000, 0, byteArrayOf(8, 1)))
                    await { audioDecoded.get() > 0 }
                    repeat(200) { index ->
                        Wire.write(media.video.outputStream, frame(2_000_000L + index * 20_000))
                    }
                    sendControl(peer, Type.PING, JSONObject().put("nonce", 771))
                    assertEquals(771, expect(peer, Type.PONG).json().getInt("nonce"))
                    Wire.write(media.video.outputStream, videoConfig(2))
                    val ready = expect(peer, Type.STREAM_READY)
                    assertEquals(2L, ready.generation)
                    assertEquals(2, decoders.size)
                    assertEquals(1, outputs.size)
                    assertTrue(decoders.first().closed)
                    assertFalse(outputs.first().closed)
                    assertEquals(listOf(312), formats.map { it.preSkip })
                    assertEquals(1, clocks.size)
                    Wire.write(media.video.outputStream, frame(8_000_000, 2))
                    sendControl(peer, Type.PING, JSONObject().put("nonce", 772))
                    expect(peer, Type.PONG)
                    assertTrue(decoders.last().pts.isEmpty())
                    val before = audioDecoded.get()
                    Wire.write(media.video.outputStream, frame(8_020_000, 2, key = true, rotation = 270))
                    await { decoders.last().pts.contains(8_020_000) }
                    assertEquals(StreamRecoveryProtocol.KEY, expect(peer, Type.STATUS).json().getString("state"))
                    assertEquals(StreamRecoveryProtocol.COMPLETE, expect(peer, Type.STATUS).json().getString("state"))
                    Wire.write(media.audio.outputStream, Message(Type.AUDIO_PACKET, 41, 2, 1, 8_040_000, 0, byteArrayOf(8, 2)))
                    await { audioDecoded.get() > before && freshAudioDecoded.get() == 1 }
                    await { rotations.contains(270) }
                    assertEquals(listOf(8_020_000L), decoders.last().pts.toList())
                    assertTrue(failures.toString(), failures.isEmpty())
                    sendControl(peer, Type.STOP, JSONObject().put("reason", "test complete"))
                    expect(peer, Type.SESSION_END)
                }
            }
        }
        assertTrue(decoders.all { it.closed })
        assertTrue(outputs.all { it.closed })
    }

    @Test fun malformedRecoveryCapabilitiesAreRejectedRatherThanCoerced() {
        ReceiverServer.forProtocolTests("Receiver").use { server ->
            server.start()
            for (capability in listOf("true", 1, JSONObject.NULL)) {
                Socket(InetAddress.getLoopbackAddress(), server.listeningPort).use { peer ->
                    peer.soTimeout = 3000
                    sendControl(peer, Type.HELLO, JSONObject().put("role", "sender").put("name", "Phone")
                        .put("versions", JSONArray().put(3)).put("streamRecovery", capability).put("streamRecoveryVersion", 2), 0)
                    assertEquals("PROTOCOL", expect(peer, Type.ERROR).json().getString("code"))
                }
            }

        }
    }

    @Test fun recoveryRequiresAnAcknowledgedMatchingRevisionNotThePrototypeBooleanAlone() {
        ReceiverServer.forProtocolTests("Receiver").use { server ->
            server.start()
            for (version in listOf(null, 1, 2, 3)) {
                Socket(InetAddress.getLoopbackAddress(), server.listeningPort).use { peer ->
                    peer.soTimeout = 3000
                    val hello = JSONObject().put("role", "sender").put("name", "Phone")
                        .put("versions", JSONArray().put(3)).put("streamRecovery", true)
                    if (version != null) hello.put("streamRecoveryVersion", version)
                    sendControl(peer, Type.HELLO, hello, 0)
                    val reply = expect(peer, Type.HELLO).json()
                    assertEquals(version == 2, reply.opt("streamRecovery") == true)
                    if (version == 2) assertEquals(2, reply.getInt("streamRecoveryVersion"))
                }
            }
            for (version in listOf("1", 0, JSONObject.NULL)) {
                Socket(InetAddress.getLoopbackAddress(), server.listeningPort).use { peer ->
                    peer.soTimeout = 3000
                    sendControl(peer, Type.HELLO, JSONObject().put("role", "sender").put("name", "Phone")
                        .put("versions", JSONArray().put(3)).put("streamRecovery", true).put("streamRecoveryVersion", version), 0)
                    assertEquals("PROTOCOL", expect(peer, Type.ERROR).json().getString("code"))
                }
            }
        }
    }

    @Test(timeout = 15_000) fun realSenderAndReceiverRecoverOnThreeSocketsWithoutEndingTheSession() {
        val decoders = ConcurrentLinkedQueue<Decoder>()
        val audioPlayers = ConcurrentLinkedQueue<AudioPlayer>()
        val failures = ConcurrentLinkedQueue<String>()
        val leases = AtomicInteger()
        val releases = AtomicInteger()
        val presentations = AtomicInteger()
        val presentationEnds = AtomicInteger()
        ReceiverServer.forProtocolTests("Receiver", onFailure = { failures.add(it.logMessage()) },
            acquireSession = { leases.incrementAndGet(); AutoCloseable { releases.incrementAndGet() } },
            onPresentationStart = { presentations.incrementAndGet(); AutoCloseable { presentationEnds.incrementAndGet() } },
            videoFactory = { format, clock, failed, generation ->
                VideoPlayer(format, clock, failed, Decoder(generation == 1L).also(decoders::add), generation)
            },
            audioFactory = { format, clock, failed ->
                testAudioPlayer(format, clock, failed, { Output() }).also(audioPlayers::add)
            }
        ).use { server ->
            server.start()
            ReceiverLink(ReceiverTarget("127.0.0.1", server.listeningPort)).use { link ->
                link.connect()
                assertTrue(link.streamRecovery)
                val settings = request().apply { getJSONObject("audio").put("codec", "raw") }
                link.send(SenderWire.control(SenderWire.SESSION_REQUEST, 41, settings))
                link.attach(41, link.receive(), true)
                val mux = MediaBudget(true)
                val sender = StreamRecoverySender(41, mux)
                val keyRequested = AtomicBoolean()
                val audioReady = CountDownLatch(1)
                val keyRequests = AtomicInteger()
                val completions = AtomicInteger()
                val ended = CountDownLatch(1)
                val done = AtomicBoolean()
                val closing = AtomicBoolean()
                val executor = Executors.newFixedThreadPool(4)
                try {
                    for (lane in 0..2) executor.submit {
                        try {
                            while (!done.get() && (!closing.get() || lane == 0)) {
                                val packet = mux.pollLane(lane, 10) ?: continue
                                mux.beginWrite(packet)
                                try {
                                    link.send(packet)
                                    sender.written(packet)
                                } finally { mux.endWrite(packet) }
                                if (packet.type == SenderWire.STOP) return@submit
                            }
                        } catch (error: Exception) {
                            if (!closing.get()) failures.add("writer: ${error.message}")
                        }
                    }
                    executor.submit {
                        try {
                            while (!done.get()) {
                                val message = link.receive()
                                val json = message.json()
                                when (message.type) {
                                    SenderWire.STREAM_READY -> {
                                        mux.ready(message.track, message.generation)
                                        if (sender.ready()) { keyRequests.incrementAndGet(); keyRequested.set(true) }
                                        if (message.track == 2 && message.generation == 1) audioReady.countDown()
                                    }
                                    SenderWire.STATUS -> when (json.getString("state")) {
                                        StreamRecoveryProtocol.REQUEST -> sender.request(json.getInt("track"), json.getInt("generation"))
                                        StreamRecoveryProtocol.KEY -> sender.keyProved(json.getInt("generation"),
                                            StreamRecoveryProtocol.timestamp(json, "keyPtsUs"))
                                        StreamRecoveryProtocol.COMPLETE -> {
                                            val track = json.getInt("track")
                                            sender.completed(track, json.getInt("generation"),
                                                StreamRecoveryProtocol.timestamp(json, if (track == 1) "keyPtsUs" else "audioPtsUs"))
                                            completions.incrementAndGet()
                                        }
                                    }
                                    SenderWire.PING -> mux.offer(SenderWire.control(SenderWire.PONG, 41,
                                        JSONObject().put("nonce", json.getInt("nonce"))))
                                    SenderWire.SESSION_END -> { ended.countDown(); return@submit }
                                    SenderWire.ERROR -> { failures.add(json.toString()); return@submit }
                                }
                            }
                        } catch (error: Exception) {
                            if (!closing.get()) failures.add("reader: ${error.message}")
                        }
                    }
                    sender.config(2, 1, JSONObject().put("codec", "raw").put("sampleRate", 48000)
                        .put("channels", 2).put("sampleFormat", "s16le").put("csd", JSONArray()))
                    assertTrue(audioReady.await(2, TimeUnit.SECONDS))
                    val warmup = System.nanoTime()
                    while (System.nanoTime() - warmup < 750_000_000L) {
                        sender.checkDeadline()
                        sender.packet(SenderWire.Message(SenderWire.AUDIO_PACKET, 0, 41, 2, 1,
                            System.nanoTime() / 1000, ByteArray(3840)))
                        assertTrue(failures.toString(), failures.isEmpty())
                        Thread.sleep(20)
                    }
                    assertTrue(failures.toString(), failures.isEmpty())
                    assertEquals(0, decoders.size)
                    assertEquals(1, audioPlayers.size)
                    assertFalse(keyRequested.get())
                    sender.config(1, 1, videoConfig(1).json())
                    val start = System.nanoTime()
                    while (decoders.size < 2 || decoders.last().pts.size < 3 || completions.get() == 0) {
                        sender.checkDeadline()
                        if (sender.retryKeyFrame()) { keyRequests.incrementAndGet(); keyRequested.set(true) }
                        assertTrue("Three-socket recovery exceeded eight seconds: $failures",
                            System.nanoTime() - start < 8_000_000_000L)
                        assertTrue(failures.toString(), failures.isEmpty())
                        val pts = System.nanoTime() / 1000
                        sender.packet(SenderWire.Message(SenderWire.VIDEO_FRAME, if (keyRequested.getAndSet(false)) 1 else 0,
                            41, 1, 1, pts, byteArrayOf(0, 0, 0, 1, 0x26, 1)))
                        sender.packet(SenderWire.Message(SenderWire.AUDIO_PACKET, 0, 41, 2, 1, pts, ByteArray(3840)))
                        Thread.sleep(20)
                    }
                    assertEquals(2, keyRequests.get())
                    assertTrue(decoders.first().closed)
                    assertTrue(decoders.last().keys.first())
                    assertEquals(1, audioPlayers.size)
                    assertTrue(audioPlayers.single().sourceSubmittedFrames > 0)
                    assertEquals(1, leases.get())
                    assertEquals(0, releases.get())
                    assertEquals(1, presentations.get())
                    assertTrue(server.busyForUpdate)
                    closing.set(true)
                    mux.finish(SenderWire.control(SenderWire.STOP, 41, JSONObject().put("reason", "test complete")))
                    assertTrue(ended.await(3, TimeUnit.SECONDS))
                } finally {
                    closing.set(true); done.set(true)
                    link.close()
                    executor.shutdownNow()
                    assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
                }
            }
        }
        assertEquals(1, releases.get())
        assertEquals(1, presentationEnds.get())
        assertTrue(failures.toString(), failures.isEmpty())
    }
}

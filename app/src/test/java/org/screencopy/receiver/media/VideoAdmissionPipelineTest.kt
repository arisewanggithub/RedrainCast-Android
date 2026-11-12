package org.screencopy.receiver.media

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.screencopy.receiver.net.ReceiverServer
import org.screencopy.receiver.net.MediaSockets
import org.screencopy.receiver.protocol.*
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class VideoAdmissionPipelineTest {
    @Test(timeout = 10_000) fun blockedVideoAdmissionDoesNotBlockAudioOnItsSeparateConnection() =
        admissionRemainsIndependent(1)

    @Test(timeout = 10_000) fun blockedAudioAdmissionDoesNotBlockVideoOnItsSeparateConnection() =
        admissionRemainsIndependent(2)

    @Test(timeout = 10_000) fun reconfigurationRetiresDecoderWithoutHoldingItsOutputCallbackLock() {
        val firstPlayer = AtomicReference<VideoPlayer>()
        val decoders = ConcurrentLinkedQueue<BoundedDecoder>()
        val errors = ConcurrentLinkedQueue<String>()
        val callbackEntered = CountDownLatch(1)
        val callbackInterrupted = CountDownLatch(1)
        val callbackGate = CountDownLatch(1)
        try {
            ReceiverServer.forProtocolTests("Receiver", onFailure = { errors.add(it.logMessage()) },
                videoFactory = { format, clock, failed, generation ->
                    val decoder = BoundedDecoder(System::nanoTime).also(decoders::add)
                    VideoPlayer(format, clock, failed, decoder, generation).also {
                        if (generation == 1L) firstPlayer.set(it)
                    }
                }).use { server ->
                server.start()
                Socket(InetAddress.getLoopbackAddress(), server.listeningPort).use { peer ->
                    peer.soTimeout = 4000
                    fun control(type: Type, json: JSONObject) =
                        Wire.write(peer.outputStream, Message.json(type, json, if (type == Type.HELLO) 0 else 41))
                    fun expect(type: Type) = Wire.read(peer.inputStream).also {
                        assertEquals("Unexpected peer reply: ${it.type}; $errors", type, it.type)
                    }
                    control(Type.HELLO, JSONObject().put("role", "sender").put("versions", JSONArray().put(3))
                        .put("name", "Phone"))
                    expect(Type.HELLO)
                    control(Type.SESSION_REQUEST, StrictJson.decode(
                        """{"video":{"codec":"h265","encoder":"","bitrate":8000000,"maxSize":1920,"maxFps":60,"displayId":0,"orientation":"0","crop":null,"codecOptions":""},"audio":{"enabled":false,"codec":"opus","encoder":"","bitrate":192000,"source":"playback","codecOptions":""}}""".toByteArray()))
                    MediaSockets.attach(server, expect(Type.SESSION_ACCEPT)).use { media ->
                        fun configure(generation: Long) {
                            Wire.write(media.video.outputStream, Message.json(Type.STREAM_CONFIG,
                                JSONObject().put("codec", "h265").put("width", 1080).put("height", 1920)
                                    .put("csd", JSONArray().put("AAAAAQE=")), 41, 1, generation))
                            expect(Type.STREAM_READY)
                        }
                        configure(1)
                        val player = checkNotNull(firstPlayer.get())
                        val original = player.onDecoded
                        player.onDecoded = { pts ->
                            callbackEntered.countDown()
                            try {
                                check(callbackGate.await(5, TimeUnit.SECONDS)) { "Decoder retirement never interrupted output" }
                            } catch (_: InterruptedException) {
                                callbackInterrupted.countDown()
                            }
                            original(pts)
                        }
                        Wire.write(media.video.outputStream, Message(Type.VIDEO_FRAME, 41, 1, 1, 1_000_000,
                            1, byteArrayOf(0, 0, 0, 1, 0x26, 1)))
                        assertTrue(callbackEntered.await(2, TimeUnit.SECONDS))
                        configure(2)
                        assertTrue("Retirement must overlap the old output callback",
                            callbackInterrupted.await(1, TimeUnit.SECONDS))
                        assertTrue(decoders.first().closed)
                        Wire.write(media.video.outputStream, Message(Type.VIDEO_FRAME, 41, 1, 2, 1_020_000,
                            1, byteArrayOf(0, 0, 0, 1, 0x26, 1)))
                        await { decoders.last().inputs.get() == 1 || errors.isNotEmpty() }
                        assertTrue(errors.toString(), errors.isEmpty())
                        control(Type.STOP, JSONObject().put("reason", "done"))
                        expect(Type.SESSION_END)
                    }
                }
            }
            assertEquals(2, decoders.size)
            assertTrue(decoders.all { it.closed })
            assertTrue(errors.toString(), errors.isEmpty())
        } finally { callbackGate.countDown() }
    }

    private fun admissionRemainsIndependent(blockedTrack: Int) {
        val now = System::nanoTime
        val video = AtomicReference<VideoPlayer>()
        val audio = AtomicReference<AudioPlayer>()
        val decodedVideo = CountDownLatch(1)
        val decodedAudio = CountDownLatch(1)
        val errors = ConcurrentLinkedQueue<String>()
        ReceiverServer.forProtocolTests("Receiver", onFailure = { errors.add(it.logMessage()) },
            videoFactory = { format, clock, failed, generation ->
                val decoder = BoundedDecoder(now)
                VideoPlayer(format, clock, failed, object : VideoDecoder by decoder {
                    override fun queueInput(index: Int, size: Int, ptsUs: Long, keyFrame: Boolean) {
                        decoder.queueInput(index, size, ptsUs, keyFrame)
                        decodedVideo.countDown()
                    }
                }, generation).also(video::set)
            },
            audioFactory = { format, clock, failed ->
                testAudioPlayer(format, clock, failed, { TransitOutput(now, 0) },
                    opusFactory = { object : OpusPacketDecoder {
                        override fun decode(packet: ByteArray): ShortArray {
                            decodedAudio.countDown()
                            return ShortArray(1920)
                        }
                        override fun close() {}
                    } }).also(audio::set)
            }).use { server ->
            server.start()
            Socket(InetAddress.getLoopbackAddress(), server.listeningPort).use { peer ->
                peer.soTimeout = 3000
                fun control(type: Type, json: JSONObject) =
                    Wire.write(peer.outputStream, Message.json(type, json, if (type == Type.HELLO) 0 else 41))
                fun expect(type: Type): Message = Wire.read(peer.inputStream).also {
                    assertEquals("Unexpected peer reply: ${it.type}; $errors", type, it.type)
                }
                control(Type.HELLO, JSONObject().put("role", "sender").put("versions", JSONArray().put(3)).put("name", "Phone"))
                expect(Type.HELLO)
                control(Type.SESSION_REQUEST, StrictJson.decode(
                    """{"video":{"codec":"h265","encoder":"","bitrate":20000000,"maxSize":1920,"maxFps":60,"displayId":0,"orientation":"0","crop":null,"codecOptions":""},"audio":{"enabled":true,"codec":"opus","encoder":"","bitrate":192000,"source":"playback","codecOptions":""}}""".toByteArray()))
                MediaSockets.attach(server, expect(Type.SESSION_ACCEPT)).use { media ->
                    Wire.write(media.video.outputStream, Message.json(Type.STREAM_CONFIG,
                        JSONObject().put("codec", "h265").put("width", 1080).put("height", 1920)
                            .put("csd", JSONArray().put("AAAAAQE=")), 41, 1, 1))
                    expect(Type.STREAM_READY)
                    val head = "OpusHead".toByteArray() + byteArrayOf(1, 2, 0, 0, 0x80.toByte(), 0xbb.toByte(), 0, 0, 0, 0, 0)
                    Wire.write(media.audio.outputStream, Message.json(Type.STREAM_CONFIG,
                        JSONObject().put("codec", "opus").put("sampleRate", 48000).put("channels", 2)
                            .put("csd", JSONArray().put(Base64.getEncoder().encodeToString(head)))
                            .put("codecDelayNs", 0).put("seekPrerollNs", 80_000_000), 41, 2, 1))
                    expect(Type.STREAM_READY)
                    val blockedPlayer: TrackWorker = if (blockedTrack == 1) checkNotNull(video.get()) else checkNotNull(audio.get())
                    val monitor = checkNotNull(TrackWorker::class.java.getDeclaredField("monitor").apply { isAccessible = true }
                        .get(blockedPlayer))
                    val videoPacket = Message(Type.VIDEO_FRAME, 41, 1, 1, 1_000_000,
                        1, byteArrayOf(0, 0, 0, 1, 0x26, 1))
                    val audioPacket = Message(Type.AUDIO_PACKET, 41, 2, 1, 1_000_000, 0, byteArrayOf(1))
                    synchronized(monitor) {
                        Wire.write(media.output(blockedTrack), if (blockedTrack == 1) videoPacket else audioPacket)
                        await {
                            Thread.getAllStackTraces().values.any { stack ->
                                stack.any { it.className == TrackWorker::class.java.name && it.methodName == "offer" }
                            }
                        }
                        Wire.write(media.output(3 - blockedTrack), if (blockedTrack == 1) audioPacket else videoPacket)
                        val otherTrackProgress = if (blockedTrack == 1) decodedAudio else decodedVideo
                        assertTrue("Track ${3 - blockedTrack} must progress while track $blockedTrack admission is blocked",
                            otherTrackProgress.await(1, TimeUnit.SECONDS))
                    }
                    control(Type.STOP, JSONObject().put("reason", "test complete"))
                    expect(Type.SESSION_END)
                }
            }
        }
        assertTrue(errors.toString(), errors.isEmpty())
    }

    @Test fun negotiatedRotationCrossesRealTcpAndChangesPresentationWithoutANewDecoderGeneration() {
        val errors = ConcurrentLinkedQueue<String>()
        val rotations = ConcurrentLinkedQueue<Int>()
        val decoders = mutableListOf<BoundedDecoder>()
        val sizes = AtomicInteger()
        ReceiverServer.forProtocolTests("Receiver", onFailure = { errors.add(it.logMessage()) },
            onVideoSize = { width, height -> assertEquals(1080, width); assertEquals(1920, height); sizes.incrementAndGet() },
            videoFactory = { format, clock, failed, generation ->
                assertEquals(1L, generation)
                val decoder = BoundedDecoder(System::nanoTime).also(decoders::add)
                VideoPlayer(format, clock, failed, decoder, generation, onRotation = rotations::add)
            }).use { server ->
            server.start()
            Socket(InetAddress.getLoopbackAddress(), server.listeningPort).use { peer ->
                peer.soTimeout = 4000
                var media: MediaSockets? = null
                fun control(type: Type, json: JSONObject, track: Int = 0, generation: Long = 0) =
                    Wire.write(if (track == 0) peer.outputStream else checkNotNull(media).output(track),
                        Message.json(type, json, if (type == Type.HELLO) 0 else 41, track, generation))
                fun expect(type: Type): Message {
                    val response = Wire.read(peer.inputStream)
                    assertEquals("Unexpected peer reply: ${response.type}; $errors", type, response.type)
                    return response
                }
                control(Type.HELLO, JSONObject().put("role", "sender").put("versions", JSONArray().put(3))
                    .put("name", "Phone").put("videoRotation", true))
                assertTrue(expect(Type.HELLO).json().getBoolean("videoRotation"))
                control(Type.SESSION_REQUEST, StrictJson.decode(
                    """{"video":{"codec":"h265","encoder":"","bitrate":20000000,"maxSize":1920,"maxFps":60,"displayId":0,"orientation":"0","crop":null,"codecOptions":""},"audio":{"enabled":false,"codec":"opus","encoder":"","bitrate":192000,"source":"output","codecOptions":""}}""".toByteArray()))
                media = MediaSockets.attach(server, expect(Type.SESSION_ACCEPT))
                media.use {
                control(Type.STREAM_CONFIG, JSONObject().put("codec", "h265").put("width", 1080).put("height", 1920)
                    .put("csd", JSONArray().put("AAAAAQE=")), 1, 1)
                expect(Type.STREAM_READY)
                val expectedPts = mutableListOf<Long>()
                for ((index, turns) in listOf(1, 2, 3, 0, 1).withIndex()) {
                    val pts = 8_000_000_000 + index * 20_000L
                    expectedPts += pts
                    Wire.write(checkNotNull(media).video.outputStream, Message(Type.VIDEO_FRAME, 41, 1, 1, pts,
                        flags = (turns shl 1) or if (index == 0) 1 else 0, payload = byteArrayOf(0, 0, 0, 1, 1)))
                    await { rotations.size > index || errors.isNotEmpty() }
                    assertTrue(errors.toString(), errors.isEmpty())
                }
                assertEquals(listOf(90, 180, 270, 0, 90), rotations.toList())
                assertEquals(1, sizes.get())
                assertEquals(1, decoders.size)
                assertEquals(expectedPts, decoders.single().inputPts.toList())
                assertFalse(decoders.single().closed)
                control(Type.STOP, JSONObject().put("reason", "rotation test"))
                expect(Type.SESSION_END)
                }
            }
        }
        assertTrue(decoders.single().closed)
    }

    @Test fun reportedProjectorCounterIsSixtyFpsPtsPressureNotByteOrPacketCapacity() {
        val budget = PacketBudget(8 * 1024 * 1024, 250_000)
        repeat(15) { index ->
            budget.add(AccessUnit(index * 16_680L, ByteArray(if (index == 14) 38_495 else 38_489)))
        }
        val pressure = checkNotNull(budget.pressure(AccessUnit(266_887, ByteArray(14_252)), 1, 48, 250))
        assertEquals("track=1 queue_limit=PTS queued_packets=15 queued_bytes=577341" +
            " attempted_packets=16 attempted_bytes=591593 relative_pts_span_us=266887" +
            " max_packets=48 max_bytes=8388608 max_span_us=250000 wait_ms=250", pressure.logMessage())
    }

    @Test(timeout = 45_000) fun playing60fpsVideoKeepsFeedingThroughHardwareTransitAndRefreshedOpusSourcePhase() = stream(60, 200_000)

    @Test(timeout = 45_000) fun playing30fpsVideoKeepsFeedingThrough300msHardwareTransit() = stream(30, 300_000)

    @Test fun capturePacingRejectsAFrozenClockInsteadOfSleepingIndefinitely() {
        var sleeps = 0
        assertThrows(AssertionError::class.java) {
            paceCapture(100, 1, 60, { 100 }, { sleeps++ })
        }
        assertEquals(256, sleeps)
    }

    @Test fun capturePacingUsesRelativeTimeAndCapsEverySleep() {
        var now = Long.MAX_VALUE - 10_000_000
        val start = now
        paceCapture(start, 1, 60, { now }, { delay ->
            assertTrue(delay in 1L..5_000_000L)
            now += delay
        })
        assertTrue(now - start >= 1_000_000_000L / 60)
    }

    @Test fun transportDiagnosticsPreserveCauseAndObserveTheFailureQueueAtFailureTime() {
        val cause = IOException("original transport failure")
        val errors = ConcurrentLinkedQueue<String>()
        val failure = assertThrows(AssertionError::class.java) {
            withTransportDiagnostics({ "capture_index=330 generation=2 receiver_errors=$errors" }) {
                errors.add("DECODER_FAILED original receiver diagnostic")
                throw cause
            }
        }
        assertSame(cause, failure.cause)
        assertTrue(failure.message.orEmpty().contains("capture_index=330 generation=2"))
        assertTrue(failure.message.orEmpty().contains("DECODER_FAILED original receiver diagnostic"))
    }

    @Test fun transportDiagnosticsDoNotCatchAssertionsOrEvaluateContextOnSuccess() {
        var evaluated = false
        val context = { evaluated = true; "unused" }
        withTransportDiagnostics(context) {}
        assertFalse(evaluated)
        val original = AssertionError("original playback assertion")
        assertSame(original, assertThrows(AssertionError::class.java) {
            withTransportDiagnostics(context) { throw original }
        })
        assertFalse(evaluated)
    }

    private inline fun withTransportDiagnostics(context: () -> String, action: () -> Unit) {
        try {
            action()
        } catch (failure: IOException) {
            throw AssertionError("Transport failure: ${context()}", failure)
        }
    }

    private fun stream(fps: Int, transitUs: Long) {
        val now = System::nanoTime
        val base = 8_000_000_000L
        val clock = PlaybackClock(now)
        val output = TransitOutput(now, transitUs)
        val decoders = CopyOnWriteArrayList<BoundedDecoder>()
        val errors = ConcurrentLinkedQueue<String>()
        val corrections = ConcurrentLinkedQueue<AudioClockCorrection>()
        val expectedPts = mutableListOf<Long>()
        val pcmFrames = 48000 / fps
        var sourceFrames = 0L
        var generation = 1L
        ReceiverServer.forProtocolTests("Receiver", onFailure = { errors.add(it.logMessage()) },
            clockFactory = { clock },
            videoFactory = { format, shared, failed, epoch ->
                assertEquals(1080, format.width)
                assertEquals(1920, format.height)
                assertEquals("video/hevc", format.mime)
                val decoder = BoundedDecoder(now).also(decoders::add)
                VideoPlayer(format, shared, failed, decoder, epoch)
            },
            audioFactory = { format, shared, failed ->
                testAudioPlayer(format, shared, failed, { output }, onClockCorrection = corrections::add,
                    opusFactory = { object : OpusPacketDecoder {
                        override fun decode(packet: ByteArray) = ShortArray(pcmFrames * 2)
                        override fun close() {}
                    } })
            }).use { server ->
            server.start()
            Socket(InetAddress.getLoopbackAddress(), server.listeningPort).use { peer ->
                peer.soTimeout = 4000
                peer.tcpNoDelay = true
                var media: MediaSockets? = null
                fun control(type: Type, json: JSONObject, track: Int = 0, epoch: Long = 0) =
                    Wire.write(if (track == 0) peer.outputStream else checkNotNull(media).output(track),
                        Message.json(type, json, if (type == Type.HELLO) 0 else 41, track, epoch))
                fun expect(type: Type): Message {
                    val result = Wire.read(peer.inputStream)
                    assertEquals("Unexpected peer response: ${if (result.type == Type.ERROR) result.json() else result.type}; $errors", type, result.type)
                    return result
                }
                control(Type.HELLO, JSONObject().put("role", "sender").put("versions", JSONArray().put(3)).put("name", "Phone"))
                expect(Type.HELLO)
                val settings = StrictJson.decode(
                    """{"video":{"codec":"h265","encoder":"","bitrate":20000000,"maxSize":0,"maxFps":$fps,"displayId":0,"orientation":"0","crop":{"width":1080,"height":1920,"x":0,"y":228},"codecOptions":""},"audio":{"enabled":true,"codec":"opus","encoder":"","bitrate":192000,"source":"output","codecOptions":""},"receiverOrientation":"landscape"}""".toByteArray())
                control(Type.SESSION_REQUEST, settings)
                media = MediaSockets.attach(server, expect(Type.SESSION_ACCEPT))
                media.use {
                fun configureVideo() {
                    control(Type.STREAM_CONFIG, JSONObject().put("codec", "h265").put("width", 1080).put("height", 1920)
                        .put("csd", JSONArray().put("AAAAAQE=")), 1, generation)
                    expect(Type.STREAM_READY)
                }
                configureVideo()
                val head = "OpusHead".toByteArray() + byteArrayOf(1, 2, 0, 0, 0x80.toByte(), 0xbb.toByte(), 0, 0, 0, 0, 0)
                control(Type.STREAM_CONFIG, JSONObject().put("codec", "opus").put("sampleRate", 48000).put("channels", 2)
                    .put("csd", JSONArray().put(Base64.getEncoder().encodeToString(head)))
                    .put("codecDelayNs", 0).put("seekPrerollNs", 80_000_000), 2, 1)
                expect(Type.STREAM_READY)
                var start = 0L
                val scenarioStart = now()
                var previousClock = Long.MIN_VALUE
                repeat(fps * 14) { index ->
                    withTransportDiagnostics({
                        "fps=$fps transit_us=$transitUs capture_index=$index generation=$generation" +
                            " elapsed_ns=${now() - scenarioStart} capture_elapsed_ns=${if (start == 0L) null else now() - start}" +
                            " source_frames=$sourceFrames written_frames=${output.written.get()}" +
                            " decoder_inputs=${decoders.map { it.inputs.get() }}" +
                            " decoder_rendered=${decoders.map { it.rendered.get() }}" +
                            " decoder_closed=${decoders.map { it.closed }} receiver_errors=$errors"
                    }) {
                        assertTrue("Real-time stream scenario exceeded its 30-second budget",
                            now() - scenarioStart < TimeUnit.SECONDS.toNanos(30))
                        if (index > 0) {
                            paceCapture(start, index, fps, now) { TimeUnit.NANOSECONDS.sleep(it) }
                        }
                        if (index == fps * 11) {
                            assertTrue("Must already be presenting before generation change", decoders.sumOf { it.rendered.get() } > fps)
                            val before = clock.currentUs()
                            generation++
                            configureVideo()
                            assertTrue(checkNotNull(clock.currentUs()) >= checkNotNull(before))
                        }
                        val pts = base + index * 1_000_000L / fps
                        // A finite codec-output pause after playback has started, not a slow decoder.
                        if (index == fps * 2) {
                            assertTrue(output.written.get() > 48000)
                            assertTrue("Failure must not be a startup-only scenario", decoders.single().rendered.get() > fps / 2)
                            decoders.single().outputReadyAt.set(now() + 100_000_000)
                        }
                        val payload = ByteArray(20_000_000 / 8 / fps).apply { this[3] = 1 }
                        // A static screen before rotation lets the prior generation finish naturally.
                        if (index < fps * 10 || index in fps * 11 until fps * 13) {
                            Wire.write(checkNotNull(media).video.outputStream, Message(Type.VIDEO_FRAME, 41, 1, generation, pts,
                                flags = if (index == 0 || index == fps * 11) 1 else 0, payload = payload))
                            expectedPts.add(pts)
                        }
                        // Opus refreshes source phase independently of the continuous decoded sample timeline.
                        val phase = if (index < fps * 3) 0 else -149_651
                        Wire.write(checkNotNull(media).audio.outputStream, Message(Type.AUDIO_PACKET, 41, 2, 1, pts + phase, payload = byteArrayOf(1)))
                        control(Type.PING, JSONObject().put("nonce", index))
                        expect(Type.PONG)
                        sourceFrames += pcmFrames
                        await { output.written.get() >= sourceFrames || errors.isNotEmpty() }
                        assertTrue(errors.toString(), errors.isEmpty())
                        if (index == 0) start = now()
                        val current = checkNotNull(clock.currentUs())
                        assertTrue("Presentation clock must not rewind", current >= previousClock)
                        previousClock = current
                    }
                }
                assertTrue(corrections.any { it.afterOffsetUs == -149_651L })
                assertTrue(errors.toString(), errors.isEmpty())
                await { decoders.sumOf { it.inputs.get() } == fps * 12 }
                assertEquals(fps * 12, decoders.sumOf { it.inputs.get() })
                assertEquals("Every compressed access unit must reach the decoder in order", expectedPts,
                    decoders.flatMap { it.inputPts.toList() })
                assertTrue(decoders.all { it.maxOutstanding.get() <= 2 })
                assertTrue(decoders.all { it.maximumBuffered.get() <= 8 })
                assertTrue(output.maxQueued.get() <= 6240)
                control(Type.STOP, JSONObject().put("reason", "test complete"))
                expect(Type.SESSION_END)
                }
            }
        }
        assertTrue(decoders.all { it.closed })
        assertTrue(output.closed)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(1)
        assertTrue("Production playback stopped progressing", condition())
    }

    private fun paceCapture(start: Long, index: Int, fps: Int, now: () -> Long, sleep: (Long) -> Unit) {
        val targetElapsed = index * 1_000_000_000L / fps
        repeat(256) {
            val elapsed = now() - start
            assertTrue("Monotonic capture clock moved backwards", elapsed >= 0)
            if (elapsed >= targetElapsed) return
            val remaining = targetElapsed - elapsed
            assertTrue("Capture pacing is more than one second ahead of the clock",
                remaining <= TimeUnit.SECONDS.toNanos(1))
            sleep(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(5)))
        }
        fail("Capture clock did not reach the next frame deadline after 256 bounded sleeps")
    }

    private class BoundedDecoder(private val now: () -> Long) : VideoDecoder {
        override val description = "Bounded deterministic decoder"
        private val pictures = ConcurrentLinkedQueue<VideoOutput>()
        val inputs = AtomicInteger()
        val inputPts = ConcurrentLinkedQueue<Long>()
        private val released = AtomicInteger()
        private val outstanding = AtomicInteger()
        val maxOutstanding = AtomicInteger()
        val maximumBuffered = AtomicInteger()
        val rendered = AtomicInteger()
        val outputReadyAt = AtomicLong()
        @Volatile var closed = false
        override fun dequeueInput() = if (inputs.get() - released.get() < 8) 0 else -1
        override fun input(index: Int) = ByteBuffer.allocate(100_000)
        override fun queueInput(index: Int, size: Int, ptsUs: Long, keyFrame: Boolean) {
            val sequence = inputs.incrementAndGet()
            inputPts.add(ptsUs)
            maximumBuffered.updateAndGet { maxOf(it, inputs.get() - released.get()) }
            pictures.add(VideoOutput(sequence, ptsUs, size, 0))
        }
        override fun dequeueOutput(): VideoOutput? {
            if (now() < outputReadyAt.get()) return null
            return pictures.poll()?.also {
                val held = outstanding.incrementAndGet()
                maxOutstanding.updateAndGet { maxOf(it, held) }
            }
        }
        override fun releaseOutput(index: Int, renderAtNs: Long?) {
            if (renderAtNs != null) {
                assertTrue("A future frame must retain its presentation deadline", renderAtNs >= now() - 10_000_000)
                rendered.incrementAndGet()
            }
            outstanding.decrementAndGet()
            released.incrementAndGet()
        }
        override fun close() { closed = true }
    }

    private class TransitOutput(private val now: () -> Long, private val transitUs: Long) : AudioOutput {
        val written = AtomicLong()
        val maxQueued = AtomicLong()
        private var previousNs = 0L
        private var consumed = 0.0
        private var speed = 1f
        private var playing = false
        @Volatile var closed = false
        override val underrunCount = 0
        override val playbackActive get() = playing
        @Synchronized override fun play() { playing = true; previousNs = now() }
        @Synchronized override fun pause() { advance(); playing = false }
        private fun advance() {
            val current = now()
            if (playing) consumed = minOf(written.get().toDouble(),
                consumed + (current - previousNs).coerceAtLeast(0) * 48000.0 * speed / 1_000_000_000)
            previousNs = current
        }
        @Synchronized override fun write(samples: ShortArray, offset: Int, count: Int): Int {
            advance()
            val frames = minOf(count / 2, (6240 - written.get() + consumed.toLong()).coerceAtLeast(0).toInt())
            written.addAndGet(frames.toLong())
            maxQueued.updateAndGet { maxOf(it, written.get() - consumed.toLong()) }
            return frames * 2
        }
        @Synchronized override fun timing(): AudioOutputTiming {
            advance()
            return AudioOutputTiming(48000, 6240, written.get(), consumed.toLong(),
                (consumed.toLong() - transitUs * 48000 / 1_000_000).coerceAtLeast(0), 0, AudioClockSource.TIMESTAMP)
        }
        @Synchronized override fun setSpeed(speed: Float) { advance(); this.speed = speed }
        override fun close() { closed = true }
    }
}

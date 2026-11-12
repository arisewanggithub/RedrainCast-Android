package org.screencopy.receiver.media

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.screencopy.receiver.lifecycle.ResourceSlot
import org.screencopy.receiver.net.MediaSockets
import org.screencopy.receiver.net.ReceiverServer
import org.screencopy.receiver.protocol.*
import java.net.InetAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class VideoConfigurationConcurrencyTest {
    @Test(timeout = 15_000) fun blockedAudioTimingLeavesVideoControlAndStopWriteIndependent() =
        blockedAudioTiming(stopWhileBlocked = true)

    @Test(timeout = 15_000) fun resumedAudioObservationRetriesTheDecodedProofWithoutAnotherVideoPicture() =
        blockedAudioTiming(stopWhileBlocked = false)

    private fun blockedAudioTiming(stopWhileBlocked: Boolean) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val submitted = CountDownLatch(1)
        val rendered = CountDownLatch(1)
        val block = AtomicBoolean()
        val errors = ConcurrentLinkedQueue<String>()
        val decoders = ConcurrentLinkedQueue<Decoder>()
        val output = Output().apply {
            onSourceWrite = { submitted.countDown() }
            onTiming = {
                if (block.get()) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }
        }
        val now = AtomicLong(1_000_000_000)
        val stopper = Executors.newSingleThreadExecutor()
        ReceiverServer.forProtocolTests("Receiver", onFailure = { errors.add(it.logMessage()) },
            clockFactory = { PlaybackClock(now::get) { now.addAndGet(it * 1_000_000) } },
            videoFactory = { format, clock, failed, generation ->
                VideoPlayer(format, clock, failed, Decoder().apply {
                    if (generation == 2L) onRender = { rendered.countDown() }
                    decoders.add(this)
                }, generation)
            },
            audioFactory = { format, clock, failed -> testAudioPlayer(format, clock, failed, { output }) }
        ).use { server ->
            try {
                session(server, audio = true, recovery = true) { peer, media ->
                    configure(peer, media, 1)
                    configure(peer, media, 2)
                    Wire.write(media.video.outputStream, frame(1_000_000))
                    assertEquals(StreamRecoveryProtocol.KEY, expect(peer, Type.STATUS).json().getString("state"))
                    assertEquals(StreamRecoveryProtocol.COMPLETE, expect(peer, Type.STATUS).json().getString("state"))
                    sendAudio(media, 1_000_000)
                    assertTrue(submitted.await(2, TimeUnit.SECONDS))
                    block.set(true)
                    assertTrue(entered.await(2, TimeUnit.SECONDS))
                    Wire.write(media.video.outputStream, config(1, 2))
                    assertEquals(2L, expect(peer, Type.STREAM_READY).generation)
                    Wire.write(media.video.outputStream,
                        Message(Type.VIDEO_FRAME, 41, 1, 2, 1_020_000, 1, byteArrayOf(0, 0, 0, 1, 0x26, 1)))
                    assertTrue("Real video decoded/render callbacks must not poll blocked native audio",
                        rendered.await(2, TimeUnit.SECONDS))
                    // A queued stale KEY/COMPLETE instead of PONG would fail this assertion.
                    ping(peer)
                    if (!stopWhileBlocked) {
                        block.set(false)
                        release.countDown()
                        val key = expect(peer, Type.STATUS)
                        assertEquals(StreamRecoveryProtocol.KEY, key.json().getString("state"))
                        assertEquals(2L, key.json().getLong("generation"))
                        assertEquals(StreamRecoveryProtocol.COMPLETE, expect(peer, Type.STATUS).json().getString("state"))
                    }
                    val stopping = stopper.submit { server.disconnect("blocked audio timing") }
                    expect(peer, Type.STOP)
                    if (stopWhileBlocked)
                        assertFalse("STOP write is not proof of completed native worker cleanup", output.closed)
                    block.set(false)
                    release.countDown()
                    stopping.get(3, TimeUnit.SECONDS)
                    Wire.write(peer.outputStream,
                        Message.json(Type.SESSION_END, JSONObject().put("reason", "acknowledged"), 41))
                }
            } finally {
                block.set(false)
                release.countDown()
                stopper.shutdownNow()
                assertTrue(stopper.awaitTermination(3, TimeUnit.SECONDS))
            }
        }
        assertTrue(errors.toString(), errors.isEmpty())
        assertTrue(output.closed)
        assertTrue(decoders.all { it.closes.get() == 1 })
    }

    @Test(timeout = 15_000) fun blockedRecoveryDiagnosticDoesNotHoldPlaybackTransactions() {
        val diagnostic = CountDownLatch(1)
        val release = CountDownLatch(1)
        val audioSubmitted = CountDownLatch(1)
        val errors = ConcurrentLinkedQueue<String>()
        val now = AtomicLong(1_000_000_000)
        ReceiverServer.forProtocolTests("Receiver", onFailure = { errors.add(it.logMessage()) },
            clockFactory = { PlaybackClock(now::get) { now.addAndGet(it * 1_000_000) } },
            onDiscoveryDiagnostic = {
                if (it.startsWith("event=STREAM_RECOVERY_KEY ")) {
                    diagnostic.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            },
            videoFactory = { format, clock, failed, generation ->
                VideoPlayer(format, clock, failed, Decoder(), generation)
            },
            audioFactory = { format, clock, failed ->
                testAudioPlayer(format, clock, failed, { Output().apply {
                    onSourceWrite = { audioSubmitted.countDown() }
                } })
            }
        ).use { server ->
            try {
                session(server, audio = true, recovery = true) { peer, media ->
                    configure(peer, media, 1)
                    configure(peer, media, 2)
                    Wire.write(media.video.outputStream, frame(1_000_000))
                    assertTrue(diagnostic.await(2, TimeUnit.SECONDS))
                    sendAudio(media, 1_000_000)
                    assertTrue("A diagnostic callback cannot gate the other media lane",
                        audioSubmitted.await(2, TimeUnit.SECONDS))
                    ping(peer)
                    release.countDown()
                    expect(peer, Type.STATUS)
                    expect(peer, Type.STATUS)
                    stop(peer)
                }
            } finally { release.countDown() }
        }
        assertTrue(errors.toString(), errors.isEmpty())
    }

    @Test(timeout = 20_000) fun closureBetweenCurrentCheckAndConstructionAdmissionRetiresInitialLegacyAndRecovery() {
        for (mode in 0..2) {
            val slot = ResourceSlot<VideoPlayer>()
            val retired = CountDownLatch(1)
            val errors = ConcurrentLinkedQueue<String>()
            val creations = AtomicInteger()
            val decoder = Decoder()
            val generation = if (mode == 0) 1L else 2L
            ReceiverServer.forProtocolTests("Receiver", videoOutput = slot,
                onFailure = { errors.add(it.logMessage()) },
                onDiscoveryDiagnostic = {
                    if (it == "event=VIDEO_CONFIGURATION_ADMISSION generation=$generation") slot.close()
                    if (it.startsWith("event=RETIRED_TRACK_CONFIGURATION")) retired.countDown()
                },
                videoFactory = { format, clock, failed, epoch ->
                    creations.incrementAndGet()
                    VideoPlayer(format, clock, failed, decoder, epoch)
                }).use { server ->
                session(server, audio = false, recovery = mode == 2) { peer, media ->
                    if (mode != 0) configure(peer, media, 1)
                    Wire.write(media.video.outputStream, config(1, generation))
                    assertTrue(retired.await(3, TimeUnit.SECONDS))
                    ping(peer)
                    assertEquals(if (mode == 0) 0 else 1, creations.get())
                    stop(peer)
                }
            }
            assertTrue(errors.toString(), errors.isEmpty())
            assertEquals(if (mode == 0) 0 else 1, decoder.closes.get())
        }
    }

    @Test(timeout = 15_000) fun alreadyClosingSurfaceRetiresInitialAndLegacyVideoConfigurationWithoutError() {
        for (legacy in listOf(false, true)) {
            val slot = ResourceSlot<VideoPlayer>()
            val retired = CountDownLatch(1)
            val errors = ConcurrentLinkedQueue<String>()
            val creations = AtomicInteger()
            val ready = AtomicInteger()
            val decoder = Decoder()
            val output = Output()
            ReceiverServer.forProtocolTests("Receiver", videoOutput = slot,
                onFailure = { errors.add(it.logMessage()) },
                onStatus = { state, detail ->
                    if (state.contains("error", true) || state.contains("failed", true)) errors.add("$state: $detail")
                },
                onDiscoveryDiagnostic = {
                    if (it.startsWith("event=RETIRED_TRACK_CONFIGURATION")) retired.countDown()
                },
                controlWrite = { socket, message ->
                    if (message.type == Type.STREAM_READY) ready.incrementAndGet()
                    Wire.write(socket.outputStream, message)
                },
                videoFactory = { format, clock, failed, generation ->
                    creations.incrementAndGet()
                    VideoPlayer(format, clock, failed, decoder, generation)
                },
                audioFactory = { format, clock, failed ->
                    creations.incrementAndGet()
                    testAudioPlayer(format, clock, failed, { output })
                }).use { server ->
                session(server, audio = true) { peer, media ->
                    if (legacy) {
                        configure(peer, media, 1)
                        configure(peer, media, 2)
                    }
                    slot.close()
                    Wire.write(media.video.outputStream, config(1, if (legacy) 2 else 1))
                    assertTrue("Closed Surface configuration must retire: legacy=$legacy",
                        retired.await(2, TimeUnit.SECONDS))
                    ping(peer)
                    assertEquals(if (legacy) 2 else 0, creations.get())
                    assertEquals(if (legacy) 2 else 0, ready.get())
                    stop(peer)
                }
            }
            assertTrue(errors.toString(), errors.isEmpty())
            assertEquals(if (legacy) 1 else 0, decoder.closes.get())
            assertEquals(legacy, output.closed)
        }
    }

    @Test(timeout = 15_000) fun closedVideoSurfaceDoesNotGateInitialAudioReady() {
        val slot = ResourceSlot<VideoPlayer>()
        val errors = ConcurrentLinkedQueue<String>()
        val creations = AtomicInteger()
        val ready = AtomicInteger()
        val output = Output()
        ReceiverServer.forProtocolTests("Receiver", videoOutput = slot,
            onFailure = { errors.add(it.logMessage()) },
            controlWrite = { socket, message ->
                if (message.type == Type.STREAM_READY) ready.incrementAndGet()
                Wire.write(socket.outputStream, message)
            },
            audioFactory = { format, clock, failed ->
                creations.incrementAndGet()
                testAudioPlayer(format, clock, failed, { output })
            }).use { server ->
            session(server, audio = true) { peer, media ->
                slot.close()
                Wire.write(media.audio.outputStream, config(2))
                assertEquals(2, expect(peer, Type.STREAM_READY).track)
                assertEquals(1, creations.get())
                assertEquals(1, ready.get())
                assertFalse(output.closed)
                stop(peer)
            }
        }
        assertTrue(errors.toString(), errors.isEmpty())
        assertTrue(output.closed)
    }

    @Test(timeout = 15_000) fun closedVideoSurfaceDoesNotGateAudioRecovery() {
        val slot = ResourceSlot<VideoPlayer>()
        val errors = ConcurrentLinkedQueue<String>()
        val audioCreations = AtomicInteger()
        val decoder = Decoder()
        val submitted = CountDownLatch(1)
        val outputs = ConcurrentLinkedQueue<Output>()
        val now = AtomicLong(1_000_000_000)
        ReceiverServer.forProtocolTests("Receiver", videoOutput = slot,
            onFailure = { errors.add(it.logMessage()) },
            clockFactory = { PlaybackClock(now::get) { now.addAndGet(it * 1_000_000) } },
            videoFactory = { format, clock, failed, generation ->
                VideoPlayer(format, clock, failed, decoder, generation)
            },
            audioFactory = { format, clock, failed ->
                audioCreations.incrementAndGet()
                testAudioPlayer(format, clock, failed, {
                    Output().apply { onSourceWrite = { submitted.countDown() } }.also(outputs::add)
                })
            }).use { server ->
            session(server, audio = true, recovery = true) { peer, media ->
                configure(peer, media, 1)
                configure(peer, media, 2)
                slot.close()
                val recovery = JSONObject().put("codec", "raw").put("sampleRate", 48000)
                    .put("channels", 2).put("sampleFormat", "s16le").put("csd", JSONArray())
                    .put("recoveryPtsUs", "1000000")
                Wire.write(media.audio.outputStream, Message.json(Type.STREAM_CONFIG, recovery, 41, 2, 2))
                val ready = expect(peer, Type.STREAM_READY)
                assertEquals(2, ready.track)
                assertEquals(2L, ready.generation)
                assertEquals(2, audioCreations.get())
                Wire.write(media.audio.outputStream, Message(Type.AUDIO_PACKET, 41, 2, 2,
                    1_000_000, payload = ByteArray(3840) { 1 }))
                assertTrue("Recovered audio must reach output without a live Surface or first video packet",
                    submitted.await(2, TimeUnit.SECONDS))
                val complete = expect(peer, Type.STATUS).json()
                assertEquals(StreamRecoveryProtocol.COMPLETE, complete.getString("state"))
                assertEquals(2, complete.getInt("track"))
                assertEquals(2, complete.getInt("generation"))
                assertTrue(StreamRecoveryProtocol.timestamp(complete, "audioPtsUs") >= 1_000_000)
                assertTrue(outputs.first().closed)
                assertTrue(outputs.last().sourceFrames.get() > 0)
                assertFalse(outputs.last().closed)
                stop(peer)
            }
        }
        assertTrue(errors.toString(), errors.isEmpty())
        assertEquals(1, decoder.closes.get())
    }

    @Test(timeout = 10_000) fun openSurfaceCodecSetupFailureRemainsFatal() {
        val errors = ConcurrentLinkedQueue<String>()
        ReceiverServer.forProtocolTests("Receiver", onFailure = { errors.add(it.code) },
            videoFactory = { _, _, _, _ -> throw IllegalStateException("Fixture codec setup failed") }
        ).use { server ->
            session(server, audio = false) { peer, media ->
                Wire.write(media.video.outputStream, config(1))
                val error = expect(peer, Type.ERROR).json()
                assertEquals("INTERNAL", error.getString("code"))
                assertTrue(error.getBoolean("fatal"))
            }
        }
        assertEquals(listOf("INTERNAL"), errors.toList())
    }

    @Test(timeout = 15_000) fun slowLegacyVideoConstructionKeepsHealthyAudioAdmissionAndSubmissionIndependent() {
        val constructing = CountDownLatch(1)
        val finishConstruction = CountDownLatch(1)
        val firstSubmitted = CountDownLatch(1)
        val nextSubmitted = CountDownLatch(1)
        val output = Output().apply {
            onSourceWrite = { sample ->
                if (sample == 257.toShort()) firstSubmitted.countDown()
                if (sample == 514.toShort()) nextSubmitted.countDown()
            }
        }
        val players = ConcurrentLinkedQueue<AudioPlayer>()
        val decoders = ConcurrentLinkedQueue<Decoder>()
        val errors = ConcurrentLinkedQueue<String>()
        val now = AtomicLong(1_000_000_000)
        ReceiverServer.forProtocolTests("Receiver", onFailure = { errors.add(it.logMessage()) },
            clockFactory = { PlaybackClock(now::get) { now.addAndGet(it * 1_000_000) } },
            videoFactory = { format, clock, failed, generation ->
                if (generation == 2L) {
                    constructing.countDown()
                    check(finishConstruction.await(5, TimeUnit.SECONDS))
                }
                VideoPlayer(format, clock, failed, Decoder().also(decoders::add), generation)
            },
            audioFactory = { format, clock, failed ->
                testAudioPlayer(format, clock, failed, { output }).also(players::add)
            }).use { server ->
            try {
                session(server, audio = true) { peer, media ->
                    configure(peer, media, 1)
                    configure(peer, media, 2)
                    Wire.write(media.video.outputStream, frame(1_000_000))
                    sendAudio(media, 1_000_000)
                    assertTrue(firstSubmitted.await(2, TimeUnit.SECONDS))
                    val submitted = output.sourceFrames.get()
                    Wire.write(media.video.outputStream, config(1, 2))
                    assertTrue(constructing.await(2, TimeUnit.SECONDS))
                    sendAudio(media, 1_020_000, 2)
                    assertTrue("Healthy audio must reach native output while video construction is gated",
                        nextSubmitted.await(2, TimeUnit.SECONDS))
                    assertTrue(output.sourceFrames.get() > submitted)
                    assertEquals(1, players.size)
                    assertFalse(output.closed)
                    assertEquals(1, decoders.first().closes.get())
                    ping(peer)
                    finishConstruction.countDown()
                    assertEquals(2L, expect(peer, Type.STREAM_READY).generation)
                    stop(peer)
                }
            } finally { finishConstruction.countDown() }
        }
        assertTrue(errors.toString(), errors.isEmpty())
        assertTrue(decoders.all { it.closes.get() == 1 })
    }

    @Test(timeout = 15_000) fun slowLegacyAudioConstructionKeepsExistingVideoOutputCallbacksIndependent() {
        val constructing = CountDownLatch(1)
        val finishConstruction = CountDownLatch(1)
        val rendered = CountDownLatch(1)
        val decoder = Decoder()
        val audioCreations = AtomicInteger()
        val errors = ConcurrentLinkedQueue<String>()
        ReceiverServer.forProtocolTests("Receiver", onFailure = { errors.add(it.logMessage()) },
            videoFactory = { format, clock, failed, generation ->
                VideoPlayer(format, clock, failed, decoder, generation)
            },
            audioFactory = { format, clock, failed ->
                if (audioCreations.incrementAndGet() == 2) {
                    constructing.countDown()
                    check(finishConstruction.await(5, TimeUnit.SECONDS))
                }
                testAudioPlayer(format, clock, failed, { Output() })
            }).use { server ->
            try {
                session(server, audio = true) { peer, media ->
                    configure(peer, media, 1)
                    configure(peer, media, 2)
                    Wire.write(media.audio.outputStream, config(2, 2))
                    assertTrue(constructing.await(2, TimeUnit.SECONDS))
                    decoder.onRender = { rendered.countDown() }
                    Wire.write(media.video.outputStream, frame(1_000_000))
                    assertTrue("The existing video worker must finish its production decoded callback and render",
                        rendered.await(2, TimeUnit.SECONDS))
                    assertEquals(0, decoder.closes.get())
                    ping(peer)
                    finishConstruction.countDown()
                    assertEquals(2, expect(peer, Type.STREAM_READY).track)
                    stop(peer)
                }
            } finally { finishConstruction.countDown() }
        }
        assertTrue(errors.toString(), errors.isEmpty())
        assertEquals(1, decoder.closes.get())
    }

    @Test(timeout = 15_000) fun blockedVideoPresentationStartupDoesNotBlockAudioConfiguration() {
        val starting = CountDownLatch(1)
        val finishPresentation = CountDownLatch(1)
        val readyTracks = ConcurrentLinkedQueue<Int>()
        val errors = ConcurrentLinkedQueue<String>()
        val decoder = Decoder()
        ReceiverServer.forProtocolTests("Receiver", onFailure = { errors.add(it.logMessage()) },
            onPresentationStart = {
                starting.countDown()
                check(finishPresentation.await(5, TimeUnit.SECONDS))
                AutoCloseable {}
            },
            controlWrite = { socket, message ->
                if (message.type == Type.STREAM_READY) readyTracks.add(message.track)
                Wire.write(socket.outputStream, message)
            },
            videoFactory = { format, clock, failed, generation ->
                VideoPlayer(format, clock, failed, decoder, generation)
            },
            audioFactory = { format, clock, failed -> testAudioPlayer(format, clock, failed, { Output() }) }
        ).use { server ->
            try {
                session(server, audio = true) { peer, media ->
                    Wire.write(media.video.outputStream, config(1))
                    assertTrue(starting.await(2, TimeUnit.SECONDS))
                    Wire.write(media.audio.outputStream, config(2))
                    assertEquals(2, expect(peer, Type.STREAM_READY).track)
                    assertEquals(listOf(2), readyTracks.toList())
                    ping(peer)
                    finishPresentation.countDown()
                    assertEquals(1, expect(peer, Type.STREAM_READY).track)
                    assertEquals(listOf(2, 1), readyTracks.toList())
                    stop(peer)
                }
            } finally { finishPresentation.countDown() }
        }
        assertTrue(errors.toString(), errors.isEmpty())
        assertEquals(1, decoder.closes.get())
    }

    @Test(timeout = 15_000) fun stopDuringConstructionRejectsTheUninstalledDecoderAndReady() {
        val constructing = CountDownLatch(1)
        val finishConstruction = CountDownLatch(1)
        val ready = AtomicInteger()
        val presentations = AtomicInteger()
        val decoder = Decoder()
        val errors = ConcurrentLinkedQueue<String>()
        val stopper = Executors.newSingleThreadExecutor()
        ReceiverServer.forProtocolTests("Receiver", onFailure = { errors.add(it.logMessage()) },
            onPresentationStart = { presentations.incrementAndGet(); AutoCloseable {} },
            controlWrite = { socket, message ->
                if (message.type == Type.STREAM_READY) ready.incrementAndGet()
                Wire.write(socket.outputStream, message)
            },
            videoFactory = { format, clock, failed, generation ->
                constructing.countDown()
                check(finishConstruction.await(5, TimeUnit.SECONDS))
                VideoPlayer(format, clock, failed, decoder, generation)
            }).use { server ->
            try {
                session(server, audio = false) { peer, media ->
                    Wire.write(media.video.outputStream, config(1))
                    assertTrue(constructing.await(2, TimeUnit.SECONDS))
                    val stopping = stopper.submit { server.disconnect("construction race") }
                    expect(peer, Type.STOP)
                    finishConstruction.countDown()
                    stopping.get(3, TimeUnit.SECONDS)
                    assertEquals(1, decoder.closes.get())
                    assertEquals(0, ready.get())
                    assertEquals(0, presentations.get())
                }
            } finally {
                finishConstruction.countDown()
                stopper.shutdownNow()
                assertTrue(stopper.awaitTermination(3, TimeUnit.SECONDS))
            }
        }
        assertTrue(errors.toString(), errors.isEmpty())
    }

    @Test(timeout = 15_000) fun surfaceRetirementDuringPresentationStartupRejectsInstallAndReady() {
        val starting = CountDownLatch(1)
        val finishPresentation = CountDownLatch(1)
        val presentationClosed = CountDownLatch(1)
        val slot = ResourceSlot<VideoPlayer>()
        val decoder = Decoder()
        val errors = ConcurrentLinkedQueue<String>()
        ReceiverServer.forProtocolTests("Receiver", videoOutput = slot,
            onFailure = { errors.add(it.logMessage()) },
            onPresentationStart = {
                starting.countDown()
                check(finishPresentation.await(5, TimeUnit.SECONDS))
                AutoCloseable { presentationClosed.countDown() }
            },
            videoFactory = { format, clock, failed, generation ->
                VideoPlayer(format, clock, failed, decoder, generation)
            }).use { server ->
            try {
                session(server, audio = false) { peer, media ->
                    Wire.write(media.video.outputStream, config(1))
                    assertTrue(starting.await(2, TimeUnit.SECONDS))
                    slot.close()
                    assertEquals(1, decoder.closes.get())
                    finishPresentation.countDown()
                    assertTrue(presentationClosed.await(2, TimeUnit.SECONDS))
                    ping(peer)
                    stop(peer)
                    assertEquals(1, decoder.closes.get())
                }
            } finally { finishPresentation.countDown(); slot.close() }
        }
        assertTrue(errors.toString(), errors.isEmpty())
    }

    @Test(timeout = 15_000) fun initialMediaBarrierRemainsClosedWhilePresentationStartupIsGated() {
        val starting = CountDownLatch(1)
        val finishPresentation = CountDownLatch(1)
        val presentationClosed = CountDownLatch(1)
        val errors = ConcurrentLinkedQueue<String>()
        val decoder = Decoder()
        ReceiverServer.forProtocolTests("Receiver", onFailure = { errors.add(it.code) },
            onPresentationStart = {
                starting.countDown()
                check(finishPresentation.await(5, TimeUnit.SECONDS))
                AutoCloseable { presentationClosed.countDown() }
            },
            videoFactory = { format, clock, failed, generation ->
                VideoPlayer(format, clock, failed, decoder, generation)
            },
            audioFactory = { format, clock, failed -> testAudioPlayer(format, clock, failed, { Output() }) }
        ).use { server ->
            try {
                session(server, audio = true) { peer, media ->
                    Wire.write(media.video.outputStream, config(1))
                    assertTrue(starting.await(2, TimeUnit.SECONDS))
                    Wire.write(media.audio.outputStream, config(2))
                    assertEquals(2, expect(peer, Type.STREAM_READY).track)
                    sendAudio(media, 1_000_000)
                    assertEquals("PROTOCOL", expect(peer, Type.ERROR).json().getString("code"))
                    finishPresentation.countDown()
                    assertTrue(presentationClosed.await(2, TimeUnit.SECONDS))
                }
            } finally { finishPresentation.countDown() }
        }
        assertEquals(listOf("PROTOCOL"), errors.toList())
        assertEquals(0, decoder.inputs.get())
        assertEquals(1, decoder.closes.get())
    }

    private fun session(server: ReceiverServer, audio: Boolean, recovery: Boolean = false, block: (Socket, MediaSockets) -> Unit) {
        server.start()
        Socket(InetAddress.getLoopbackAddress(), server.listeningPort).use { peer ->
            peer.soTimeout = 3000
            Wire.write(peer.outputStream, Message.json(Type.HELLO,
                JSONObject().put("role", "sender").put("versions", JSONArray().put(3)).put("name", "Phone")
                    .apply { if (recovery) put("streamRecovery", true).put("streamRecoveryVersion", 2) }))
            expect(peer, Type.HELLO)
            val settings = StrictJson.decode(
                """{"video":{"codec":"h265","encoder":"","bitrate":8000000,"maxSize":1920,"maxFps":60,"displayId":0,"orientation":"0","crop":null,"codecOptions":""},"audio":{"enabled":$audio,"codec":"raw","encoder":"","bitrate":192000,"source":"playback","codecOptions":""}}""".toByteArray())
            Wire.write(peer.outputStream, Message.json(Type.SESSION_REQUEST, settings, 41))
            MediaSockets.attach(server, expect(peer, Type.SESSION_ACCEPT)).use { block(peer, it) }
        }
    }

    private fun configure(peer: Socket, media: MediaSockets, track: Int) {
        Wire.write(media.output(track), config(track))
        assertEquals(track, expect(peer, Type.STREAM_READY).track)
    }

    private fun config(track: Int, generation: Long = 1): Message {
        val json = if (track == 1)
            JSONObject().put("codec", "h265").put("width", 640).put("height", 360)
                .put("csd", JSONArray().put("AAAAAQE="))
        else JSONObject().put("codec", "raw").put("sampleRate", 48000).put("channels", 2)
            .put("sampleFormat", "s16le").put("csd", JSONArray())
        return Message.json(Type.STREAM_CONFIG, json, 41, track, generation)
    }

    private fun frame(pts: Long) =
        Message(Type.VIDEO_FRAME, 41, 1, 1, pts, 1, byteArrayOf(0, 0, 0, 1, 0x26, 1))

    private fun sendAudio(media: MediaSockets, pts: Long, value: Byte = 1) =
        Wire.write(media.audio.outputStream, Message(Type.AUDIO_PACKET, 41, 2, 1, pts, payload = ByteArray(3840) { value }))

    private fun ping(peer: Socket) {
        Wire.write(peer.outputStream, Message.json(Type.PING, JSONObject().put("nonce", 1), 41))
        expect(peer, Type.PONG)
    }

    private fun stop(peer: Socket) {
        Wire.write(peer.outputStream, Message.json(Type.STOP, JSONObject().put("reason", "test complete"), 41))
        expect(peer, Type.SESSION_END)
    }

    private fun expect(peer: Socket, type: Type) = Wire.read(peer.inputStream).also { assertEquals(type, it.type) }

    private class Decoder : VideoDecoder {
        override val description = "Configuration concurrency fixture"
        private val outputs = ConcurrentLinkedQueue<VideoOutput>()
        val inputs = AtomicInteger()
        val closes = AtomicInteger()
        @Volatile var onRender: () -> Unit = {}
        override fun dequeueInput() = 0
        override fun input(index: Int) = ByteBuffer.allocate(64)
        override fun queueInput(index: Int, size: Int, ptsUs: Long, keyFrame: Boolean) {
            outputs.add(VideoOutput(inputs.incrementAndGet(), ptsUs, size, 0))
        }
        override fun dequeueOutput(): VideoOutput? = outputs.poll()
        override fun releaseOutput(index: Int, renderAtNs: Long?) { if (renderAtNs != null) onRender() }
        override fun close() { closes.incrementAndGet() }
    }

    private class Output : AudioOutput {
        private val submitted = AtomicLong()
        val sourceFrames = AtomicLong()
        @Volatile var onSourceWrite: (Short) -> Unit = {}
        @Volatile var onTiming: () -> Unit = {}
        @Volatile var closed = false
        override val underrunCount = 0
        override fun play() {}
        override fun pause() {}
        override fun setSpeed(speed: Float) {}
        override fun write(samples: ShortArray, offset: Int, count: Int): Int {
            submitted.addAndGet(count / 2L)
            if (samples[offset] != 0.toShort()) {
                sourceFrames.addAndGet(count / 2L)
                onSourceWrite(samples[offset])
            }
            return count
        }
        override fun timing(): AudioOutputTiming {
            onTiming()
            return AudioOutputTiming(48000, 6240, submitted.get(), 0, 0, 0, AudioClockSource.TIMESTAMP)
        }
        override fun close() { closed = true }
    }
}

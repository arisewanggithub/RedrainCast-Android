package org.screencopy.receiver.net

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import org.screencopy.receiver.media.AudioPlayer
import org.screencopy.receiver.media.AccessUnit
import org.screencopy.receiver.media.PlaybackClock
import org.screencopy.receiver.media.QueueLimit
import org.screencopy.receiver.media.QueuePressure
import org.screencopy.receiver.media.AudioOutput
import org.screencopy.receiver.media.AudioOutputTiming
import org.screencopy.receiver.media.AudioClockSource
import org.screencopy.receiver.media.IdentityPcmResampler
import org.screencopy.receiver.media.testAudioPlayer
import org.screencopy.receiver.media.VideoDecoder
import org.screencopy.receiver.media.VideoOutput
import org.screencopy.receiver.media.VideoPlayer
import org.screencopy.receiver.protocol.*
import org.screencopy.receiver.ui.ReceiverScreenEvent
import java.net.InetAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import dev.redrain.cast.diagnostics.DiagnosticKind
import dev.redrain.cast.diagnostics.DiagnosticPoint
import dev.redrain.cast.diagnostics.DiagnosticStage
import dev.redrain.cast.diagnostics.IncidentEvidence
import dev.redrain.cast.diagnostics.ReceiverTelemetrySession
import dev.redrain.cast.diagnostics.TelemetryRegistry
import dev.redrain.cast.diagnostics.CausalEvidence
import dev.redrain.cast.diagnostics.SessionTelemetryProtocol
import dev.redrain.cast.diagnostics.TelemetryHistory
import dev.redrain.cast.diagnostics.TelemetryReport

class ThreeSocketReceiverTest {
    @Test(timeout = 15_000) fun audioOnlyNegotiationAttachesBothLanesAndReadiesOnlyAudioWithoutVideoDecoder() {
        val videoCreated = AtomicInteger()
        val audioCreated = AtomicInteger()
        ReceiverServer.forProtocolTests("Receiver",
            videoFactory = { _, _, _, _ ->
                videoCreated.incrementAndGet()
                error("Audio-only must not create a video decoder")
            },
            audioFactory = { _, _, _ ->
                audioCreated.incrementAndGet()
                Mockito.mock(AudioPlayer::class.java).also {
                    Mockito.`when`(it.decoderDescription).thenReturn("test audio decoder")
                }
            }).use { server ->
            server.start()
                fun beginAudioOnly(owner: Socket, session: Long): MediaSockets {
                    send(owner, Type.HELLO, JSONObject().put("role", "sender")
                        .put("versions", JSONArray().put(3)).put("name", "test").put("audioOnlyVersion", 1))
                    val hello = Wire.read(owner.inputStream)
                    assertEquals(Type.HELLO, hello.type)
                    assertEquals(1, hello.json().getInt("audioOnlyVersion"))
                    val request = JSONObject().put("video", JSONObject()
                        .put("enabled", false).put("codec", "h264").put("encoder", "")
                        .put("bitrate", 8_000_000).put("maxSize", 1920).put("maxFps", 0)
                        .put("displayId", 0).put("orientation", "0").put("crop", JSONObject.NULL)
                        .put("codecOptions", ""))
                        .put("audio", JSONObject().put("enabled", true).put("codec", "raw")
                            .put("encoder", "").put("bitrate", 192_000).put("source", "playback")
                            .put("codecOptions", ""))
                    send(owner, Type.SESSION_REQUEST, request, session)
                    val accepted = Wire.read(owner.inputStream)
                    assertEquals(Type.SESSION_ACCEPT, accepted.type)
                    val tracks = accepted.json().getJSONArray("tracks")
                    assertEquals(1, tracks.length())
                    assertEquals(2, tracks.getInt(0))
                    return MediaSockets.attach(server, accepted)
                }
                fun configureAudio(owner: Socket, media: MediaSockets, session: Long) {
                    Wire.write(media.audio.outputStream, Message.json(Type.STREAM_CONFIG,
                        JSONObject().put("codec", "raw").put("sampleRate", 48000).put("channels", 2)
                            .put("sampleFormat", "s16le").put("csd", JSONArray()), session, 2, 1))
                    var ready: Message
                    do { ready = Wire.read(owner.inputStream) }
                    while (ready.type == Type.STATUS || ready.type == Type.PING)
                    assertEquals(Type.STREAM_READY, ready.type)
                    assertEquals(2, ready.track)
                }
                fun currentTicket(session: Long): Triple<Long, Long, Long> {
                    val active = checkNotNull((server.javaClass.getDeclaredField("active")
                        .apply { isAccessible = true }.get(server) as AtomicReference<*>).get())
                    fun longField(name: String) = active.javaClass.getDeclaredField(name)
                        .apply { isAccessible = true }.getLong(active)
                    return Triple(longField("connectionId"), session, longField("audioEpoch"))
                }
                var firstTicket: Triple<Long, Long, Long>? = null
            control(server).use { owner ->
                    val media = beginAudioOnly(owner, 301)
                    media.use {
                    assertTrue(media.video.isConnected)
                    assertTrue(media.audio.isConnected)
                    owner.soTimeout = 150
                        try {
                            while (true) {
                                val pending = Wire.read(owner.inputStream)
                                assertEquals("Only non-readiness status is allowed before audio config",
                                    Type.STATUS, pending.type)
                            }
                        } catch (_: java.net.SocketTimeoutException) {
                    }
                    owner.soTimeout = 3000
                        configureAudio(owner, media, 301)
                    assertEquals(1, audioCreated.get())
                    assertEquals(0, videoCreated.get())
                        firstTicket = currentTicket(301)
                        assertTrue(server.audioSubmissionCurrent(firstTicket!!.first, firstTicket!!.second, firstTicket!!.third))
                    send(owner, Type.STOP, JSONObject().put("reason", "test"), 301)
                    var terminal: Message
                    do { terminal = Wire.read(owner.inputStream) }
                    while (terminal.type == Type.STATUS || terminal.type == Type.PING)
                    assertEquals(Type.SESSION_END, terminal.type)
                }
            }
                await { !server.busyForUpdate }
                assertFalse(server.audioSubmissionCurrent(firstTicket!!.first, firstTicket!!.second, firstTicket!!.third))
                control(server).use { replacement ->
                    val media = beginAudioOnly(replacement, 301)
                    media.use {
                        configureAudio(replacement, media, 301)
                        val freshTicket = currentTicket(301)
                        assertNotEquals(firstTicket!!.first, freshTicket.first)
                        assertFalse(server.audioSubmissionCurrent(firstTicket!!.first, firstTicket!!.second, firstTicket!!.third))
                        assertTrue(server.audioSubmissionCurrent(freshTicket.first, freshTicket.second, freshTicket.third))
                        send(replacement, Type.STOP, JSONObject().put("reason", "test"), 301)
                        var terminal: Message
                        do { terminal = Wire.read(replacement.inputStream) }
                        while (terminal.type == Type.STATUS || terminal.type == Type.PING)
                        assertEquals(Type.SESSION_END, terminal.type)
                }
                }
        }
    }

    @Test(timeout = 15_000) fun obsoleteValidationFailureDoesNotTerminateRecovery() {
        val blockNext = AtomicBoolean(false)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val initialAdmitted = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val player = Mockito.mock(VideoPlayer::class.java)
        Mockito.doAnswer { initialAdmitted.countDown(); null }.`when`(player).tryOffer(
            Mockito.any(AccessUnit::class.java) ?: AccessUnit(0, byteArrayOf()), Mockito.anyLong())
        try {
            ReceiverServer.forProtocolTests("Receiver", videoFactory = { _, _, _, _ -> player },
                beforeMediaValidation = {
                    if (it == 1 && blockNext.compareAndSet(true, false)) {
                        entered.countDown()
                        release.await()
                    }
                }, afterMediaAdmission = { if (it == 1) finished.countDown() }).use { server ->
                server.start()
                control(server).use { owner ->
                    MediaSockets.attach(server, negotiate(owner, 220, recovery = true)).use { media ->
                        configure(media.video, 220)
                        assertEquals(Type.STREAM_READY, Wire.read(owner.inputStream).type)
                        Wire.write(media.video.outputStream, Message(Type.VIDEO_FRAME, 220, 1, 1, 0,
                            flags = 1, payload = byteArrayOf(0, 0, 0, 1, 0x65)))
                        assertTrue(initialAdmitted.await(2, TimeUnit.SECONDS))
                        blockNext.set(true)
                        Wire.write(media.video.outputStream, Message(Type.VIDEO_FRAME, 220, 1, 1, 1,
                            payload = byteArrayOf(1)))
                        assertTrue(entered.await(2, TimeUnit.SECONDS))
                        val connection = checkNotNull((server.javaClass.getDeclaredField("active")
                            .apply { isAccessible = true }.get(server) as AtomicReference<*>).get())
                        connection.javaClass.getDeclaredMethod("requestRecovery",
                            QueuePressure::class.java, java.lang.Long::class.java).apply { isAccessible = true }
                            .invoke(connection, QueuePressure(1, QueueLimit.PACKETS, 1, 4, 2, 8, 0, 1, 4, 1000), null)
                        assertEquals(Type.STATUS, Wire.read(owner.inputStream).type)
                        release.countDown()
                        assertTrue(finished.await(2, TimeUnit.SECONDS))
                        ping(owner, 220)
                        stop(owner, 220)
                    }
                }
            }
        } finally { release.countDown() }
    }

    @Test(timeout = 15_000) fun retiredAdmissionPressureCannotRecoverReplacementVideo() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val events = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val old = Mockito.mock(VideoPlayer::class.java)
        val replacement = Mockito.mock(VideoPlayer::class.java)
        Mockito.doAnswer {
            entered.countDown()
            release.await()
            QueuePressure(1, QueueLimit.PACKETS, 1, 4, 2, 8, 0, 1, 4, 1000)
        }.`when`(old).tryOffer(
            Mockito.any(AccessUnit::class.java) ?: AccessUnit(0, byteArrayOf()), Mockito.anyLong())
        try {
            ReceiverServer.forProtocolTests("Receiver",
                videoFactory = { _, _, _, generation -> if (generation == 1L) old else replacement },
                onDiscoveryDiagnostic = events::add,
                afterMediaAdmission = { if (it == 1) finished.countDown() }).use { server ->
                server.start()
                control(server).use { owner ->
                    MediaSockets.attach(server, negotiate(owner, 219, recovery = true)).use { media ->
                        configure(media.video, 219)
                        assertEquals(Type.STREAM_READY, Wire.read(owner.inputStream).type)
                        val frame = Message(Type.VIDEO_FRAME, session = 219, track = 1, generation = 1,
                            ptsUs = 0, flags = 1, payload = byteArrayOf(0, 0, 0, 1, 0x65))
                        Wire.write(media.video.outputStream, frame)
                        assertTrue(entered.await(2, TimeUnit.SECONDS))
                        val connection = checkNotNull((server.javaClass.getDeclaredField("active")
                            .apply { isAccessible = true }.get(server) as AtomicReference<*>).get())
                        connection.javaClass.getDeclaredMethod("configure", Message::class.java)
                            .apply { isAccessible = true }.invoke(connection, videoConfig(219).copy(generation = 2))
                        assertEquals(Type.STREAM_READY, Wire.read(owner.inputStream).type)
                        connection.javaClass.getDeclaredMethod("receiveMedia", Message::class.java)
                            .apply { isAccessible = true }.invoke(connection, frame.copy(generation = 2))
                        release.countDown()
                        assertTrue(finished.await(2, TimeUnit.SECONDS))
                        ping(owner, 219)
                        assertFalse(events.any { it.startsWith("event=STREAM_RECOVERY_REQUEST track=1") })
                        stop(owner, 219)
                    }
                }
            }
        } finally { release.countDown() }
    }

    @Test(timeout = 30_000) fun slowVideoValidationDoesNotBlockAudioOrRouteAfterStop() {
        for (recovery in listOf(false, true)) slowVideoValidationDoesNotBlockAudioOrRouteAfterStop(recovery)
    }

    private fun slowVideoValidationDoesNotBlockAudioOrRouteAfterStop(recovery: Boolean) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val audioOffered = CountDownLatch(1)
        val videoFinished = CountDownLatch(1)
        val videoPlayer = Mockito.mock(VideoPlayer::class.java)
        val audioPlayer = Mockito.mock(AudioPlayer::class.java)
        Mockito.`when`(audioPlayer.decoderDescription).thenReturn("fixture")
        Mockito.doAnswer { audioOffered.countDown(); null }.`when`(audioPlayer).offer(
            Mockito.any(AccessUnit::class.java) ?: AccessUnit(0, byteArrayOf()), Mockito.anyLong())
        Mockito.doAnswer { audioOffered.countDown(); null }.`when`(audioPlayer).tryOffer(
            Mockito.any(AccessUnit::class.java) ?: AccessUnit(0, byteArrayOf()), Mockito.anyLong())
        try {
            ReceiverServer.forProtocolTests("Receiver",
                videoFactory = { _, _, _, _ -> videoPlayer },
                audioFactory = { _, _, _ -> audioPlayer },
                beforeMediaValidation = { track ->
                    if (track == 1) { entered.countDown(); release.await() }
                }, afterMediaAdmission = { track -> if (track == 1) videoFinished.countDown() }).use { server ->
                server.start()
                control(server).use { owner ->
                    MediaSockets.attach(server, negotiate(owner, 215, recovery = recovery, audio = true)).use { media ->
                        configure(media.video, 215)
                        assertEquals(Type.STREAM_READY, Wire.read(owner.inputStream).type)
                        Wire.write(media.audio.outputStream, Message.json(Type.STREAM_CONFIG,
                            JSONObject().put("codec", "raw").put("sampleRate", 48000).put("channels", 2)
                                .put("sampleFormat", "s16le").put("csd", JSONArray()), 215, 2, 1))
                        assertEquals(Type.STREAM_READY, Wire.read(owner.inputStream).type)
                        Wire.write(media.video.outputStream, Message(Type.VIDEO_FRAME, session = 215, track = 1,
                            generation = 1, ptsUs = 0, flags = 1, payload = byteArrayOf(0, 0, 0, 1, 0x65)))
                        assertTrue(entered.await(2, TimeUnit.SECONDS))
                        Wire.write(media.audio.outputStream, Message(Type.AUDIO_PACKET, session = 215, track = 2,
                            generation = 1, ptsUs = 0, payload = ByteArray(4)))
                        assertTrue("audio admission blocked by video validation", audioOffered.await(2, TimeUnit.SECONDS))
                        stop(owner, 215)
                        release.countDown()
                        assertTrue(videoFinished.await(2, TimeUnit.SECONDS))
                        Mockito.verify(videoPlayer, Mockito.never()).offer(
                            Mockito.any(AccessUnit::class.java) ?: AccessUnit(0, byteArrayOf()), Mockito.anyLong())
                        Mockito.verify(videoPlayer, Mockito.never()).tryOffer(
                            Mockito.any(AccessUnit::class.java) ?: AccessUnit(0, byteArrayOf()), Mockito.anyLong())
                    }
                }
            }
        } finally { release.countDown() }
    }

    @Test(timeout = 15_000) fun slowAudioClockObservationDoesNotBlockVideoRecoveryKey() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val videoOffered = CountDownLatch(1)
        val audioOffered = CountDownLatch(1)
        val clock = PlaybackClock(nanoTime = { 0L })
        val video = Mockito.mock(VideoPlayer::class.java)
        Mockito.doAnswer { videoOffered.countDown(); null }.`when`(video).tryOffer(
            Mockito.any(AccessUnit::class.java) ?: AccessUnit(0, byteArrayOf()), Mockito.anyLong())
        try {
            ReceiverServer.forProtocolTests("Receiver", clockFactory = { clock },
                videoFactory = { _, _, _, _ -> video },
                audioFactory = { _, _, _ ->
                    Mockito.mock(AudioPlayer::class.java).also {
                        Mockito.`when`(it.decoderDescription).thenReturn("fixture")
                        Mockito.doAnswer { audioOffered.countDown(); null }.`when`(it).tryOffer(
                            Mockito.any(AccessUnit::class.java) ?: AccessUnit(0, byteArrayOf()), Mockito.anyLong())
                    }
                }).use { server ->
                server.start()
                control(server).use { owner ->
                    MediaSockets.attach(server, negotiate(owner, 217, recovery = true, audio = true)).use { media ->
                        configure(media.video, 217)
                        assertEquals(Type.STREAM_READY, Wire.read(owner.inputStream).type)
                        val config = Message.json(Type.STREAM_CONFIG,
                            JSONObject().put("codec", "raw").put("sampleRate", 48000).put("channels", 2)
                                .put("sampleFormat", "s16le").put("csd", JSONArray()), 217, 2, 1)
                        Wire.write(media.audio.outputStream, config)
                        assertEquals(Type.STREAM_READY, Wire.read(owner.inputStream).type)
                        clock.anchorAudio(0) { entered.countDown(); release.await(); 0 }
                        Wire.write(media.audio.outputStream, config.copy(generation = 2,
                            payload = config.json().put("recoveryPtsUs", "0").toString().toByteArray()))
                        assertEquals(Type.STREAM_READY, Wire.read(owner.inputStream).type)
                        Wire.write(media.audio.outputStream, Message(Type.AUDIO_PACKET, session = 217, track = 2,
                            generation = 2, ptsUs = 1_000_000, payload = ByteArray(4)))
                        assertTrue(entered.await(2, TimeUnit.SECONDS))
                        Wire.write(media.video.outputStream, Message(Type.VIDEO_FRAME, session = 217, track = 1,
                            generation = 1, ptsUs = 0, flags = 1, payload = byteArrayOf(0, 0, 0, 1, 0x65)))
                        assertTrue("video key blocked by audio clock callback", videoOffered.await(2, TimeUnit.SECONDS))
                        release.countDown()
                        assertTrue(audioOffered.await(2, TimeUnit.SECONDS))
                        stop(owner, 217)
                    }
                }
            }
        } finally { release.countDown() }
    }

    @Test(timeout = 15_000) fun delayedRecoveryRequestDoesNotPublishAfterNewGenerationReady() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            ReceiverServer.forProtocolTests("Receiver",
                videoFactory = { _, _, _, _ -> Mockito.mock(VideoPlayer::class.java) },
                audioFactory = { _, _, _ ->
                    Mockito.mock(AudioPlayer::class.java).also {
                        Mockito.`when`(it.decoderDescription).thenReturn("fixture")
                    }
                }, onDiscoveryDiagnostic = {
                    if (it.startsWith("event=STREAM_RECOVERY_REQUEST track=2")) {
                        entered.countDown()
                        release.await()
                    }
                }).use { server ->
                server.start()
                control(server).use { owner ->
                    MediaSockets.attach(server, negotiate(owner, 218, recovery = true, audio = true)).use { media ->
                        configure(media.video, 218)
                        assertEquals(Type.STREAM_READY, Wire.read(owner.inputStream).type)
                        val config = Message.json(Type.STREAM_CONFIG,
                            JSONObject().put("codec", "raw").put("sampleRate", 48000).put("channels", 2)
                                .put("sampleFormat", "s16le").put("csd", JSONArray()), 218, 2, 1)
                        Wire.write(media.audio.outputStream, config)
                        assertEquals(Type.STREAM_READY, Wire.read(owner.inputStream).type)
                        val connection = checkNotNull((server.javaClass.getDeclaredField("active")
                            .apply { isAccessible = true }.get(server) as AtomicReference<*>).get())
                        val recovery = connection.javaClass.getDeclaredMethod("requestRecovery",
                            QueuePressure::class.java, java.lang.Long::class.java).apply { isAccessible = true }
                        val delayed = CompletableFuture.runAsync {
                            recovery.invoke(connection, QueuePressure(2, QueueLimit.PACKETS, 1, 4,
                                2, 8, 0, 1, 4, 1000), null)
                        }
                        assertTrue(entered.await(2, TimeUnit.SECONDS))
                        Wire.write(media.audio.outputStream, config.copy(generation = 2,
                            payload = config.json().put("recoveryPtsUs", "0").toString().toByteArray()))
                        assertEquals(Type.STREAM_READY, Wire.read(owner.inputStream).type)
                        release.countDown()
                        delayed.get(2, TimeUnit.SECONDS)
                        ping(owner, 218)
                        stop(owner, 218)
                    }
                }
            }
        } finally { release.countDown() }
    }

    @Test(timeout = 30_000) fun mediaMessageFailureCapturesMessageTrackAndGenerationAfterAudioConfiguration() {
        for (case in 0..3) {
            val failures = java.util.concurrent.ConcurrentLinkedQueue<org.screencopy.receiver.ui.FailureDiagnostic>()
            val session = 280L + case
            ReceiverServer.forProtocolTests("Receiver", onFailure = { failures.add(it) },
                videoFactory = { _, _, _, generation ->
                    if (case == 1 && generation == 2L) throw IllegalStateException("fixture")
                    Mockito.mock(VideoPlayer::class.java)
                }, audioFactory = { _, _, _ ->
                    Mockito.mock(AudioPlayer::class.java).also {
                        Mockito.`when`(it.decoderDescription).thenReturn("fixture")
                    }
                }).use { server ->
                server.start()
                control(server).use { owner ->
                    MediaSockets.attach(server, negotiate(owner, session, telemetry = true, audio = true)).use { media ->
                        fun ready() {
                            var message: Message
                            do { message = Wire.read(owner.inputStream) } while (message.type == Type.STATUS)
                            assertEquals(Type.STREAM_READY, message.type)
                        }
                        configure(media.video, session)
                        ready()
                        if (case != 1) {
                            Wire.write(media.video.outputStream, videoConfig(session).copy(generation = 2))
                            ready()
                        }
                        Wire.write(media.audio.outputStream, Message.json(Type.STREAM_CONFIG,
                            JSONObject().put("codec", "raw").put("sampleRate", 48000).put("channels", 2)
                                .put("sampleFormat", "s16le").put("csd", JSONArray()), session, 2, 1))
                        ready()
                        if (case != 1) {
                            val connection = checkNotNull((server.javaClass.getDeclaredField("active")
                                .apply { isAccessible = true }.get(server) as AtomicReference<*>).get())
                            val terminate = connection.javaClass.getDeclaredMethod("terminateMediaFailure",
                                Exception::class.java, String::class.java, Int::class.javaPrimitiveType,
                                java.lang.Long::class.java, java.lang.Long::class.java).apply { isAccessible = true }
                            assertEquals(false, terminate.invoke(connection, IllegalStateException("retired"),
                                null, 1, 1L, 1L))
                            assertTrue(failures.isEmpty())
                        }
                        val generation = if (case == 2) 3L else 2L
                        if (case == 3) {
                            val connection = checkNotNull((server.javaClass.getDeclaredField("active")
                                .apply { isAccessible = true }.get(server) as AtomicReference<*>).get())
                            val terminate = connection.javaClass.getDeclaredMethod("terminateMediaFailure",
                                Exception::class.java, String::class.java, Int::class.javaPrimitiveType,
                                java.lang.Long::class.java, java.lang.Long::class.java).apply { isAccessible = true }
                            assertEquals(true, terminate.invoke(connection,
                                org.screencopy.receiver.ui.ReceiverOrientationException(), null, 1, generation, generation))
                        } else if (case == 1) Wire.write(media.video.outputStream, videoConfig(session).copy(generation = generation))
                        else Wire.write(media.video.outputStream, Message(Type.VIDEO_FRAME, session = session,
                            track = 1, generation = generation, flags = if (case == 2) 0 else 2,
                            ptsUs = 0, payload = byteArrayOf(1)))
                        var final: SessionTelemetryProtocol.Batch? = null
                        var message: Message
                        do {
                            message = Wire.read(owner.inputStream)
                            if (message.type == Type.STATUS && message.json().optString("state") == "session-telemetry") {
                                val batch = SessionTelemetryProtocol.parse(message.payload, "receiver")
                                if (batch.final) final = batch
                            }
                        } while (message.type == Type.STATUS)
                        assertEquals(Type.ERROR, message.type)
                        val batch = checkNotNull(final)
                        assertEquals(1, checkNotNull(batch.firstFailure).track)
                        val capture = batch.events.single {
                            it.kind == CausalEvidence.KIND && it.stage == CausalEvidence.Stage.CAPTURE.ordinal
                        }
                        assertEquals(generation.toInt(), capture.generation)
                        assertEquals(1L, capture.detail[4])
                        if (case == 3) {
                            assertEquals(org.screencopy.receiver.ui.FailureOrigin.PRESENTATION, failures.single().origin)
                            assertEquals(org.screencopy.receiver.ui.FailureReason.RECEIVER_ORIENTATION, failures.single().reason)
                        }
                    }
                }
            }
        }
    }

    @Test(timeout = 15_000) fun receiverInitializationFailureCapturesFailingTrackGeneration() {
        for (track in 1..2) {
            val session = 290L + track
            ReceiverServer.forProtocolTests("Receiver",
                videoFactory = { _, _, _, _ ->
                    if (track == 1) throw IllegalStateException("fixture")
                    Mockito.mock(VideoPlayer::class.java)
                }, audioFactory = { _, _, _ -> throw IllegalStateException("fixture") }).use { server ->
                server.start()
                control(server).use { owner ->
                    MediaSockets.attach(server, negotiate(owner, session, telemetry = true, audio = track == 2)).use { media ->
                        configure(media.video, session)
                        if (track == 2) {
                            var ready: Message
                            do { ready = Wire.read(owner.inputStream) } while (ready.type == Type.STATUS)
                            assertEquals(Type.STREAM_READY, ready.type)
                            Wire.write(media.audio.outputStream, Message.json(Type.STREAM_CONFIG,
                                JSONObject().put("codec", "raw").put("sampleRate", 48000).put("channels", 2)
                                    .put("sampleFormat", "s16le").put("csd", JSONArray()), session, 2, 1))
                        }
                        var final: SessionTelemetryProtocol.Batch? = null
                        var message: Message
                        do {
                            message = Wire.read(owner.inputStream)
                            if (message.type == Type.STATUS && message.json().optString("state") == "session-telemetry") {
                                val batch = SessionTelemetryProtocol.parse(message.payload, "receiver")
                                if (batch.final) final = batch
                            }
                        } while (message.type == Type.STATUS)
                        assertEquals(Type.ERROR, message.type)
                        val batch = checkNotNull(final)
                        assertEquals(0, checkNotNull(batch.firstFailure).generation)
                        val capture = batch.events.single {
                            it.kind == CausalEvidence.KIND && it.stage == CausalEvidence.Stage.CAPTURE.ordinal
                        }
                        assertEquals(1, capture.generation)
                        assertEquals(track.toLong(), capture.detail[4])
                    }
                }
            }
        }
    }

    @Test(timeout = 30_000) fun receiverFailureCaptureUsesOriginatingTrackGenerationAndRejectsRetiredCallbacks() {
        for (track in 1..2) for (generation in 1..2) {
            val callbacks = java.util.concurrent.ConcurrentHashMap<Int, (String, String) -> Unit>()
            val audioCreations = AtomicInteger()
            val failures = java.util.concurrent.ConcurrentLinkedQueue<String>()
            val session = 300L + track * 10 + generation
            ReceiverServer.forProtocolTests("Receiver", onFailure = { failures.add(it.code) },
                videoFactory = { _, _, failed, videoGeneration ->
                    if (track == 1) callbacks[videoGeneration.toInt()] = failed
                    Mockito.mock(VideoPlayer::class.java)
                }, audioFactory = { _, _, failed ->
                    if (track == 2) callbacks[audioCreations.incrementAndGet()] = failed
                    Mockito.mock(AudioPlayer::class.java).also {
                        Mockito.`when`(it.decoderDescription).thenReturn("fixture")
                    }
                }).use { server ->
                server.start()
                control(server).use { owner ->
                    MediaSockets.attach(server, negotiate(owner, session, telemetry = true,
                        recovery = track == 1, audio = track == 2)).use { media ->
                        fun ready(expectedGeneration: Long) {
                            var message: Message
                            do { message = Wire.read(owner.inputStream) } while (message.type == Type.STATUS)
                            assertEquals(Type.STREAM_READY, message.type)
                            assertEquals(expectedGeneration, message.generation)
                        }
                        configure(media.video, session)
                        ready(1)
                        val audioConfig = Message.json(Type.STREAM_CONFIG,
                            JSONObject().put("codec", "raw").put("sampleRate", 48000).put("channels", 2)
                                .put("sampleFormat", "s16le").put("csd", JSONArray()), session, 2, 1)
                        if (track == 2) {
                            Wire.write(media.audio.outputStream, audioConfig)
                            ready(1)
                        }
                        if (generation == 2) {
                            val replacement = if (track == 1) videoConfig(session).copy(generation = 2)
                                else audioConfig.copy(generation = 2)
                            Wire.write(if (track == 1) media.video.outputStream else media.audio.outputStream, replacement)
                            ready(2)
                            checkNotNull(callbacks[1])("DECODER_FAILED", "retired callback")
                            val connection = checkNotNull((server.javaClass.getDeclaredField("active")
                                .apply { isAccessible = true }.get(server) as AtomicReference<*>).get())
                            connection.javaClass.getDeclaredMethod("playbackFailed", String::class.java, String::class.java,
                                org.screencopy.receiver.ui.FailureOrigin::class.java, java.lang.Long.TYPE,
                                java.lang.Long::class.java).apply { isAccessible = true }.invoke(
                                connection, "DECODER_FAILED", "retired after callback check",
                                if (track == 1) org.screencopy.receiver.ui.FailureOrigin.VIDEO
                                else org.screencopy.receiver.ui.FailureOrigin.AUDIO, 1L,
                                if (track == 2) 1L else null)
                            assertTrue(failures.toString(), failures.isEmpty())
                        }
                        val code = if (track == 1) "DECODER_FAILED" else "AUDIO_FAILED"
                        checkNotNull(callbacks[generation])(code, "current callback")
                        var final: SessionTelemetryProtocol.Batch? = null
                        var message: Message
                        do {
                            message = Wire.read(owner.inputStream)
                            if (message.type == Type.STATUS && message.json().optString("state") == "session-telemetry") {
                                val batch = SessionTelemetryProtocol.parse(message.payload, "receiver")
                                if (batch.final) final = batch
                            }
                        } while (message.type == Type.STATUS)
                        assertEquals(Type.ERROR, message.type)
                        assertEquals(listOf(code), failures.toList())
                        val batch = checkNotNull(final)
                        assertEquals(0, checkNotNull(batch.firstFailure).generation)
                        assertEquals(track, checkNotNull(batch.firstFailure).track)
                        val capture = batch.events.single {
                            it.kind == CausalEvidence.KIND && it.stage == CausalEvidence.Stage.CAPTURE.ordinal
                        }
                        assertEquals(generation, capture.generation)
                        assertEquals(track.toLong(), capture.detail[4])
                    }
                }
            }
        }
    }

    @Test(timeout = 15_000) fun nonRecoveryAudioRetainsDurationGapAndBurstWithoutRejectingLegacyLongPcm() {
        val now = AtomicLong(1_000_000_000)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val offered = CountDownLatch(4)
        val arrivals = AtomicInteger()
        val failures = java.util.concurrent.ConcurrentLinkedQueue<String>()
        try {
            ReceiverServer.forProtocolTests("Receiver",
                onFailure = { failures.add(it.logMessage()) },
                videoFactory = { _, _, _, _ -> Mockito.mock(VideoPlayer::class.java) },
                clockFactory = { PlaybackClock(now::get) },
                audioFactory = { format, clock, failed ->
                    val actual = AudioPlayer(format, clock, failed,
                        { Mockito.mock(AudioOutput::class.java) },
                        resamplerFactory = { _, channels -> IdentityPcmResampler(channels) }, threadCpuNs = { 0 },
                        prepareThread = { entered.countDown(); release.await() })
                    val player = Mockito.mock(AudioPlayer::class.java, org.mockito.AdditionalAnswers.delegatesTo<Any>(actual))
                    Mockito.doAnswer { invocation ->
                        now.addAndGet(when (arrivals.incrementAndGet()) {
                            1 -> 0L
                            2 -> 20_000_000L
                            3 -> 1_000_000L
                            else -> 300_000_000L
                        })
                        actual.offer(invocation.getArgument(0), invocation.getArgument(1))
                        offered.countDown()
                        null
                    }.`when`(player).offer(
                        Mockito.any(AccessUnit::class.java) ?: AccessUnit(0, byteArrayOf()), Mockito.anyLong())
                    player
                }
            ).use { server ->
                server.start()
                control(server).use { owner ->
                    val acceptance = negotiate(owner, 216, telemetry = true, audio = true)
                    assertFalse(acceptance.json().has("streamRecovery"))
                    MediaSockets.attach(server, acceptance).use { media ->
                        configure(media.video, 216)
                        var response: Message
                        do { response = Wire.read(owner.inputStream) } while (response.type == Type.STATUS)
                        assertEquals("failures=$failures response=${response.json()}", Type.STREAM_READY, response.type)
                        Wire.write(media.audio.outputStream, Message.json(Type.STREAM_CONFIG,
                            JSONObject().put("codec", "raw").put("sampleRate", 48000).put("channels", 2)
                                .put("sampleFormat", "s16le").put("csd", JSONArray()), 216, 2, 1))
                        do { response = Wire.read(owner.inputStream) } while (response.type == Type.STATUS)
                        assertEquals("failures=$failures response=${response.json()}", Type.STREAM_READY, response.type)
                        assertTrue(entered.await(2, TimeUnit.SECONDS))
                        for ((pts, bytes) in listOf(1_000_000L to 3840, 1_020_000L to 3840,
                            1_200_000L to 3840, 1_220_000L to 48000)) {
                            Wire.write(media.audio.outputStream,
                                Message(Type.AUDIO_PACKET, session = 216, track = 2, generation = 1,
                                    ptsUs = pts, payload = ByteArray(bytes)))
                        }
                        assertTrue(offered.await(2, TimeUnit.SECONDS))
                        assertTrue(failures.toString(), failures.isEmpty())
                        send(owner, Type.ERROR, JSONObject().put("code", "AUDIO_FAILED")
                            .put("message", "test sender failure").put("fatal", true), 216)
                        var final: ByteArray? = null
                        do {
                            response = Wire.read(owner.inputStream)
                            if (response.type == Type.STATUS && SessionTelemetryProtocol.parse(response.payload).final)
                                final = response.payload
                        } while (response.type == Type.STATUS)
                        assertEquals(Type.ERROR, response.type)
                        assertNotNull("terminal=${response.json()} failures=$failures", final)
                        val payload = checkNotNull(final)
                        val batch = SessionTelemetryProtocol.parse(payload, "receiver")
                        val history = TelemetryHistory(true)
                        history.accept(TelemetryHistory.Endpoint.RECEIVER, payload, 1)
                        history.terminal(TelemetryHistory.Endpoint.RECEIVER, batch.sequence, true, 1)
                        val report = StrictJson.decode(TelemetryReport.encode(checkNotNull(history.finish(1))))
                        assertTrue(TelemetryReport.validate(report))
                        val stored = report.getJSONObject("receiver").getJSONArray("blocks")
                            .getJSONArray(0).getJSONObject(1).toString().toByteArray()
                        val input = SessionTelemetryProtocol.parse(stored, "receiver").events.single {
                            it.kind == CausalEvidence.KIND && it.stage == CausalEvidence.Stage.INPUT.ordinal &&
                                it.value == 1L
                        }
                        assertEquals(250_000L, input.detail[4])
                        assertTrue(input.detail[5] > 0)
                        assertEquals(180_000L, input.detail[6])
                        assertEquals(1L, input.detail[9])
                        assertEquals(4L, input.detail[10])
                        assertEquals(1_220_000L, input.detail[12])
                    }
                }
            }
        } finally { release.countDown() }
    }

    @Test(timeout = 15_000) fun releaseFaultsStillCloseLeaseAndClearOwnership() {
        fatalReleaseHardening(fault = true)
    }

    @Test(timeout = 15_000) fun lateResourceFailureVetoesAvailabilityButNotOwnershipRelease() {
        fatalReleaseHardening(fault = false)
    }

    private fun fatalReleaseHardening(fault: Boolean) {
        val playerClosing = CountDownLatch(1)
        val releasePlayer = CountDownLatch(1)
        val controlEnding = CountDownLatch(1)
        val releaseControl = CountDownLatch(1)
        val leaseClosed = CountDownLatch(1)
        val releaseException = AtomicReference<Throwable>()
        val controlThread = AtomicReference<Thread>()
        val leaseCloses = AtomicInteger()
        val available = AtomicInteger()
        val admitted = AtomicBoolean()
        val status = AtomicReference<String>()
        val cleanupLog = AtomicReference<String>()
        val connectionRef = AtomicReference<Any>()
        val player = Mockito.mock(VideoPlayer::class.java)
        Mockito.doAnswer {
            playerClosing.countDown()
            check(releasePlayer.await(5, TimeUnit.SECONDS))
            null
        }.`when`(player).close()
        val access = object : DiagnosticSessionAccess {
            override fun admitted(session: Long, requested: Boolean, ownerActive: () -> Boolean, report: (String) -> Unit) {}
            override fun revoked(session: Long) {}
            override fun ended(session: Long) {
                if (fault) {
                    val thread = Thread.currentThread()
                    controlThread.set(thread)
                    thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error ->
                        releaseException.set(error)
                    }
                }
                controlEnding.countDown()
                check(releaseControl.await(5, TimeUnit.SECONDS))
            }
        }
        try {
            ReceiverServer.forProtocolTests("Receiver",
                acquireSession = {
                    AutoCloseable {
                        leaseCloses.incrementAndGet()
                        val connection = checkNotNull(connectionRef.get())
                        if (fault) {
                            connection.javaClass.getDeclaredMethod("finishFatalCleanup",
                                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                                Boolean::class.javaPrimitiveType).apply { isAccessible = true }
                                .invoke(connection, true, false, false)
                            leaseClosed.countDown()
                            throw IllegalStateException("lease failed")
                        } else {
                            (connection.javaClass.getDeclaredField("resourceRetirementFailed")
                                .apply { isAccessible = true }.get(connection) as AtomicBoolean).set(true)
                            leaseClosed.countDown()
                        }
                    }
                },
                onStatus = { title, detail ->
                    if (title == "Session cleanup failed") {
                        status.set(detail)
                        if (fault) throw IllegalStateException("notification failed")
                    }
                },
                onScreenEvent = {
                    if (admitted.get() && it == ReceiverScreenEvent.CleanupFinished) available.incrementAndGet()
                },
                onDiscoveryDiagnostic = {
                    if (it.startsWith("event=SESSION_CLEANUP_FAILED")) cleanupLog.set(it)
                },
                diagnosticSessionAccess = access,
                videoFactory = { _, _, _, _ -> player }).use { server ->
                try {
                server.start()
                control(server).use { owner ->
                    MediaSockets.attach(server, negotiate(owner, 214, telemetry = true)).use { media ->
                        admitted.set(true)
                        configure(media.video, 214)
                        var message: Message
                        do { message = Wire.read(owner.inputStream) } while (message.type == Type.STATUS)
                        assertEquals(Type.STREAM_READY, message.type)
                        val connection = checkNotNull((server.javaClass.getDeclaredField("active")
                            .apply { isAccessible = true }.get(server) as AtomicReference<*>).get())
                        connectionRef.set(connection)
                        fun field(name: String) = connection.javaClass.getDeclaredField(name).apply { isAccessible = true }
                        Wire.write(media.video.outputStream, videoConfig(214).copy(generation = 2,
                            payload = videoConfig(214).json().put("codec", "invalid").toString().toByteArray()))
                        assertTrue(playerClosing.await(3, TimeUnit.SECONDS))
                        val workerFinished = CountDownLatch(1)
                        (field("recoveryExecutor").get(connection) as ExecutorService).execute { workerFinished.countDown() }
                        owner.shutdownOutput()
                        assertTrue(controlEnding.await(3, TimeUnit.SECONDS))
                        releasePlayer.countDown()
                        assertTrue(workerFinished.await(4, TimeUnit.SECONDS))
                        assertEquals("RETIRED", (field("fatalWork").get(connection) as AtomicReference<*>).get().toString())
                        assertFalse((field("resourceRetirementFailed").get(connection) as AtomicBoolean).get())
                        if (fault) {
                            val oldTelemetry = field("telemetry").get(connection) as ReceiverTelemetrySession
                            TelemetryRegistry.end("receiver", oldTelemetry)
                            val failingTelemetry = Mockito.mock(ReceiverTelemetrySession::class.java) { invocation ->
                                if (invocation.method.name == "requestFinal") throw IllegalStateException("telemetry failed")
                                Mockito.RETURNS_DEFAULTS.answer(invocation)
                            }
                            field("telemetry").set(connection, failingTelemetry)
                            TelemetryRegistry.beginReceiver(214, failingTelemetry)
                            assertTrue(TelemetryRegistry.has("receiver", 214))
                        }
                        val finish = connection.javaClass.getDeclaredMethod("finishFatalCleanup",
                            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                            Boolean::class.javaPrimitiveType).apply { isAccessible = true }
                        releaseControl.countDown()
                        assertTrue(leaseClosed.await(3, TimeUnit.SECONDS))
                        if (fault) {
                            synchronized(checkNotNull(field("retirementLock").get(connection))) { }
                            assertEquals("telemetry failed", status.get())
                            val log = checkNotNull(cleanupLog.get())
                            assertTrue(log.contains("telemetry failed"))
                            assertTrue(log.contains("lease failed"))
                            assertTrue(log.contains("notification failed"))
                        }
                        synchronized(checkNotNull(field("retirementLock").get(connection))) { }
                        assertEquals(1, leaseCloses.get())
                        assertNull(field("sessionLease").get(connection))
                        assertFalse(server.busyForUpdate)
                        assertFalse(TelemetryRegistry.has("receiver", 214))
                        assertEquals(0, available.get())
                        assertTrue(field("ownershipReleased").getBoolean(connection))
                        finish.invoke(connection, true, false, false)
                        assertEquals(1, leaseCloses.get())
                    }
                }
                } finally {
                    releasePlayer.countDown()
                    releaseControl.countDown()
                }
            }
            if (fault) {
                val thread = checkNotNull(controlThread.get())
                thread.join(3_000)
                assertFalse(thread.isAlive)
                assertNull("Routine release exceptions must not escape", releaseException.get())
            }
        } finally {
            releasePlayer.countDown()
            releaseControl.countDown()
        }
    }

    @Test(timeout = 15_000) fun fatalRetirementWaitsForControlCleanupBeforeReleasingOwnership() {
        fatalOwnershipAccounting(writerFails = false, duplicateCompletion = false)
    }

    @Test(timeout = 15_000) fun fatalRetirementSurvivesLaterTerminalWriterFailureWithoutHealthyAvailability() {
        fatalOwnershipAccounting(writerFails = true, duplicateCompletion = false)
    }

    @Test(timeout = 15_000) fun duplicateFatalCompletionReleasesAndNotifiesAtMostOnce() {
        fatalOwnershipAccounting(writerFails = false, duplicateCompletion = true)
    }

    private fun fatalOwnershipAccounting(writerFails: Boolean, duplicateCompletion: Boolean) {
        val playerClosing = CountDownLatch(1)
        val releasePlayer = CountDownLatch(1)
        val controlEnding = CountDownLatch(1)
        val releaseControl = CountDownLatch(1)
        val leaseClosed = CountDownLatch(1)
        val leases = AtomicInteger()
        val available = AtomicInteger()
        val availableSignal = CountDownLatch(1)
        val admitted = AtomicBoolean()
        val player = Mockito.mock(VideoPlayer::class.java)
        Mockito.doAnswer {
            playerClosing.countDown()
            check(releasePlayer.await(5, TimeUnit.SECONDS))
            null
        }.`when`(player).close()
        val access = object : DiagnosticSessionAccess {
            override fun admitted(session: Long, requested: Boolean, ownerActive: () -> Boolean, report: (String) -> Unit) {}
            override fun revoked(session: Long) {}
            override fun ended(session: Long) {
                controlEnding.countDown()
                check(releaseControl.await(5, TimeUnit.SECONDS))
            }
        }
        try {
            ReceiverServer.forProtocolTests("Receiver",
                acquireSession = { AutoCloseable { leases.incrementAndGet(); leaseClosed.countDown() } },
                onScreenEvent = {
                    if (admitted.get() && it == ReceiverScreenEvent.CleanupFinished) {
                        available.incrementAndGet()
                        availableSignal.countDown()
                    }
                },
                diagnosticSessionAccess = access,
                videoFactory = { _, _, _, _ -> player }).use { server ->
                server.start()
                control(server).use { owner ->
                    MediaSockets.attach(server, negotiate(owner, 212, telemetry = true)).use { media ->
                        admitted.set(true)
                        configure(media.video, 212)
                        var message: Message
                        do { message = Wire.read(owner.inputStream) } while (message.type == Type.STATUS)
                        assertEquals(Type.STREAM_READY, message.type)
                        val connection = checkNotNull((server.javaClass.getDeclaredField("active")
                            .apply { isAccessible = true }.get(server) as AtomicReference<*>).get())
                        fun field(name: String) = connection.javaClass.getDeclaredField(name).apply { isAccessible = true }
                        Wire.write(media.video.outputStream, videoConfig(212).copy(generation = 2,
                            payload = videoConfig(212).json().put("codec", "invalid").toString().toByteArray()))
                        assertTrue(playerClosing.await(3, TimeUnit.SECONDS))
                        val workerFinished = CountDownLatch(1)
                        (field("recoveryExecutor").get(connection) as ExecutorService).execute { workerFinished.countDown() }
                        owner.shutdownOutput()
                        assertTrue(controlEnding.await(3, TimeUnit.SECONDS))
                        if (writerFails) (field("writer").get(connection) as AutoCloseable).close()
                        releasePlayer.countDown()
                        assertTrue(workerFinished.await(4, TimeUnit.SECONDS))
                        val fatalWork = field("fatalWork").get(connection) as AtomicReference<*>
                        assertEquals("RETIRED", fatalWork.get().toString())
                        assertEquals(writerFails, field("retirementFailed").getBoolean(connection))
                        val finish = connection.javaClass.getDeclaredMethod("finishFatalCleanup",
                            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                            Boolean::class.javaPrimitiveType).apply { isAccessible = true }
                        if (duplicateCompletion) finish.invoke(connection, false, true, false)
                        assertEquals("RETIRED", fatalWork.get().toString())
                        assertTrue(server.busyForUpdate)
                        assertEquals(0, leases.get())
                        control(server).use { peer ->
                            assertEquals("BUSY", negotiate(peer, 213, expected = Type.REJECT).json().getString("code"))
                        }
                        releaseControl.countDown()
                        assertTrue(leaseClosed.await(3, TimeUnit.SECONDS))
                        synchronized(checkNotNull(field("retirementLock").get(connection))) { }
                        assertFalse(server.busyForUpdate)
                        if (!writerFails) assertTrue(availableSignal.await(3, TimeUnit.SECONDS))
                        if (duplicateCompletion) {
                            finish.invoke(connection, false, true, false)
                            finish.invoke(connection, true, false, false)
                        }
                        assertEquals(1, leases.get())
                        assertEquals(if (writerFails) 0 else 1, available.get())
                        assertEquals("RETIRED", fatalWork.get().toString())
                    }
                }
            }
        } finally {
            releasePlayer.countDown()
            releaseControl.countDown()
        }
    }

    @Test(timeout = 15_000) fun localStopFinalIncludesQueuedReadyCancellationBeforeTerminal() {
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val session = 209L
        try {
            ReceiverServer.forProtocolTests("Receiver",
                videoFactory = { _, _, _, _ -> Mockito.mock(VideoPlayer::class.java) },
                controlWrite = { socket, message ->
                    if (message.type == Type.PONG) {
                        writing.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                    Wire.write(socket.outputStream, message)
                }).use { server ->
                server.start()
                control(server).use { owner ->
                    MediaSockets.attach(server, negotiate(owner, session, telemetry = true)).use {
                        send(owner, Type.PING, JSONObject().put("nonce", 1), session)
                        assertTrue(writing.await(3, TimeUnit.SECONDS))
                        val connection = checkNotNull((server.javaClass.getDeclaredField("active")
                            .apply { isAccessible = true }.get(server) as AtomicReference<*>).get())
                        connection.javaClass.getDeclaredMethod("configure", Message::class.java)
                            .apply { isAccessible = true }.invoke(connection, videoConfig(session))
                        server.disconnect("receiver stop")
                        release.countDown()
                        assertEquals(Type.PONG, Wire.read(owner.inputStream).type)
                        val status = Wire.read(owner.inputStream)
                        assertEquals(Type.STATUS, status.type)
                        val batch = dev.redrain.cast.diagnostics.SessionTelemetryProtocol.parse(status.payload, "receiver")
                        assertTrue(batch.final)
                        val ready = batch.events.filter {
                            it.kind == dev.redrain.cast.diagnostics.TelemetryRow.EVIDENCE_KIND &&
                                it.stage in setOf(IncidentEvidence.Stage.READY_ENQUEUED.ordinal,
                                    IncidentEvidence.Stage.READY_CANCELLED.ordinal,
                                    IncidentEvidence.Stage.READY_WRITING.ordinal,
                                    IncidentEvidence.Stage.READY_WRITTEN.ordinal)
                        }
                        assertEquals(listOf(IncidentEvidence.Stage.READY_ENQUEUED.ordinal,
                            IncidentEvidence.Stage.READY_CANCELLED.ordinal), ready.map { it.stage })
                        assertTrue(ready.all { it.track == 1 && it.generation == 1 })
                        assertTrue(ready[1].atNs >= ready[0].atNs)
                        val terminal = Wire.read(owner.inputStream)
                        assertEquals(Type.STOP, terminal.type)
                        assertEquals(batch.sequence.toString(), terminal.json().getString("finalSeq"))
                        assertTrue(terminal.json().getBoolean("cleanupSucceeded"))
                    }
                }
            }
        } finally { release.countDown() }
    }

    @Test(timeout = 15_000) fun admittedControlFatalFailedPauseRetainsOwnershipUntilDestruction() {
        val leaseClosed = CountDownLatch(1)
        val audioPlayer = AtomicReference<AudioPlayer>()
        val output = Mockito.mock(AudioOutput::class.java)
        Mockito.`when`(output.timing()).thenReturn(
            AudioOutputTiming(48000, 6240, 0, 0, 0, 0, AudioClockSource.TIMESTAMP))
        Mockito.doThrow(IllegalStateException("pause failed")).`when`(output).pause()
        ReceiverServer.forProtocolTests("Receiver",
            acquireSession = { AutoCloseable { leaseClosed.countDown() } },
            videoFactory = { _, _, _, _ -> Mockito.mock(VideoPlayer::class.java) },
            audioFactory = { format, clock, failed ->
                testAudioPlayer(format, clock, failed, { output }).also { audioPlayer.set(it) }
            }
        ).use { server ->
            server.start()
            control(server).use { owner ->
                MediaSockets.attach(server, negotiate(owner, 201, telemetry = true, audio = true)).use { media ->
                    configure(media.video, 201)
                    var message: Message
                    do { message = Wire.read(owner.inputStream) } while (message.type == Type.STATUS)
                    assertEquals(Type.STREAM_READY, message.type)
                    Wire.write(media.audio.outputStream, Message.json(Type.STREAM_CONFIG,
                        JSONObject().put("codec", "raw").put("sampleRate", 48000).put("channels", 2)
                            .put("sampleFormat", "s16le").put("csd", JSONArray()), 201, 2, 1))
                    do { message = Wire.read(owner.inputStream) } while (message.type == Type.STATUS)
                    assertEquals(Type.STREAM_READY, message.type)
                    send(owner, Type.ERROR, JSONObject().put("code", "AUDIO_FAILED")
                        .put("message", "sender failure").put("fatal", true), 201)
                    do { message = Wire.read(owner.inputStream) } while (message.type == Type.STATUS)
                    assertEquals(Type.ERROR, message.type)
                    assertFalse(message.json().getBoolean("cleanupSucceeded"))
                    assertTrue(server.busyForUpdate)
                    assertEquals(1L, leaseClosed.count)
                    Mockito.verify(output, Mockito.never()).close()
                    control(server).use { peer ->
                        assertEquals("BUSY", negotiate(peer, 202, expected = Type.REJECT).json().getString("code"))
                    }
                }
            }
        }
        assertTrue(leaseClosed.await(2, TimeUnit.SECONDS))
        audioPlayer.get()?.close()
    }

    @Test(timeout = 18_000) fun halfClosedControlWaitsForFatalDeadlineWhileRetirementIsBlocked() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val leaseClosed = CountDownLatch(1)
        val player = Mockito.mock(VideoPlayer::class.java)
        Mockito.doAnswer {
            entered.countDown()
            check(release.await(12, TimeUnit.SECONDS))
            null
        }.`when`(player).close()
        try {
            ReceiverServer.forProtocolTests("Receiver",
                acquireSession = { AutoCloseable { leaseClosed.countDown() } },
                videoFactory = { _, _, _, _ -> player }).use { server ->
                server.start()
                control(server).use { owner ->
                    owner.soTimeout = 10_000
                    MediaSockets.attach(server, negotiate(owner, 203, telemetry = true)).use { media ->
                        configure(media.video, 203)
                        var message: Message
                        do { message = Wire.read(owner.inputStream) } while (message.type == Type.STATUS)
                        assertEquals(Type.STREAM_READY, message.type)
                        val connection = checkNotNull((server.javaClass.getDeclaredField("active")
                            .apply { isAccessible = true }.get(server) as AtomicReference<*>).get())
                        Wire.write(media.video.outputStream, videoConfig(203).copy(generation = 2,
                            payload = videoConfig(203).json().put("codec", "invalid").toString().toByteArray()))
                        assertTrue(entered.await(3, TimeUnit.SECONDS))
                        owner.shutdownOutput()
                        val finished = connection.javaClass.getDeclaredField("controlCleanupFinished")
                            .apply { isAccessible = true }.get(connection) as CountDownLatch
                        assertTrue(finished.await(3, TimeUnit.SECONDS))
                        do {
                            message = Wire.read(owner.inputStream)
                            if (message.type == Type.STATUS && message.json().optString("state") == "session-telemetry") {
                                assertFalse(dev.redrain.cast.diagnostics.SessionTelemetryProtocol.parse(message.payload, "receiver").final)
                            }
                        } while (message.type != Type.ERROR)
                        assertFalse(message.json().getBoolean("cleanupSucceeded"))
                        assertTrue(message.json().isNull("finalSeq"))
                        assertEquals("UNAVAILABLE", message.json().getString("finalSkipReason"))
                        assertTrue(server.busyForUpdate)
                        assertEquals(1L, leaseClosed.count)
                        release.countDown()
                        assertTrue(leaseClosed.await(4, TimeUnit.SECONDS))
                    }
                }
            }
        } finally { release.countDown() }
    }

    @Test(timeout = 20_000) fun recoveryRetirementFailureFencesOwnershipButConstructionFailureDoesNot() {
        for (retirementFails in listOf(true, false)) {
            val leaseClosed = CountDownLatch(1)
            val player = Mockito.mock(VideoPlayer::class.java)
            if (retirementFails) Mockito.doThrow(IllegalStateException("detached close failed"))
                .`when`(player).close()
            ReceiverServer.forProtocolTests("Receiver",
                acquireSession = { AutoCloseable { leaseClosed.countDown() } },
                videoFactory = { _, _, _, generation ->
                    if (generation == 2L) throw IllegalStateException("construction failed")
                    player
                }).use { server ->
                server.start()
                control(server).use { owner ->
                    owner.soTimeout = 8_000
                    MediaSockets.attach(server, negotiate(owner, 204, telemetry = true, recovery = true)).use { media ->
                        configure(media.video, 204)
                        var message: Message
                        do { message = Wire.read(owner.inputStream) } while (message.type == Type.STATUS)
                        assertEquals(Type.STREAM_READY, message.type)
                        Wire.write(media.video.outputStream, videoConfig(204).copy(generation = 2))
                        do { message = Wire.read(owner.inputStream) } while (message.type == Type.STATUS)
                        assertEquals(Type.ERROR, message.type)
                        assertEquals(!retirementFails, message.json().getBoolean("cleanupSucceeded"))
                        if (retirementFails) {
                            assertTrue(server.busyForUpdate)
                            assertEquals(1L, leaseClosed.count)
                            control(server).use { peer ->
                                assertEquals("BUSY", negotiate(peer, 205, expected = Type.REJECT).json().getString("code"))
                            }
                        } else {
                            assertTrue(leaseClosed.await(3, TimeUnit.SECONDS))
                        }
                    }
                }
            }
            assertTrue(leaseClosed.await(2, TimeUnit.SECONDS))
            Mockito.verify(player, Mockito.times(1)).close()
        }
    }

    @Test(timeout = 25_000) fun stopRacingRealRecoveryJoinFailureRetainsOwnershipUntilDestruction() {
        for (receiverInitiated in listOf(false, true)) {
            val workerEntered = CountDownLatch(1)
            val workerRelease = CompletableFuture<Unit>()
            val recoveryCloseStarted = CountDownLatch(1)
            val stopHaltedVideo = CountDownLatch(1)
            val leaseClosed = CountDownLatch(1)
            val player = AtomicReference<AudioPlayer>()
            val output = Mockito.mock(AudioOutput::class.java)
            Mockito.doAnswer { recoveryCloseStarted.countDown(); null }.`when`(output).pause()
            val video = Mockito.mock(VideoPlayer::class.java)
            Mockito.doAnswer { stopHaltedVideo.countDown(); null }.`when`(video).halt()
            try {
                ReceiverServer.forProtocolTests("Receiver",
                    acquireSession = { AutoCloseable { leaseClosed.countDown() } },
                    videoFactory = { _, _, _, _ -> video },
                    audioFactory = { format, clock, failed ->
                        AudioPlayer(format, clock, failed, { output },
                            resamplerFactory = { _, channels -> IdentityPcmResampler(channels) },
                            threadCpuNs = { 0 }, prepareThread = {
                                workerEntered.countDown()
                                workerRelease.join()
                            }).also { player.set(it) }
                    }).use { server ->
                    server.start()
                    control(server).use { owner ->
                        owner.soTimeout = 8_000
                        MediaSockets.attach(server, negotiate(owner, 206, telemetry = true, recovery = true, audio = true)).use { media ->
                            configure(media.video, 206)
                            var message: Message
                            do { message = Wire.read(owner.inputStream) } while (message.type == Type.STATUS)
                            assertEquals(Type.STREAM_READY, message.type)
                            val audioConfig = Message.json(Type.STREAM_CONFIG,
                                JSONObject().put("codec", "raw").put("sampleRate", 48000).put("channels", 2)
                                    .put("sampleFormat", "s16le").put("csd", JSONArray()), 206, 2, 1)
                            Wire.write(media.audio.outputStream, audioConfig)
                            do { message = Wire.read(owner.inputStream) } while (message.type == Type.STATUS)
                            assertEquals(Type.STREAM_READY, message.type)
                            assertTrue(workerEntered.await(2, TimeUnit.SECONDS))
                            Wire.write(media.audio.outputStream, audioConfig.copy(generation = 2,
                                payload = audioConfig.json().put("recoveryPtsUs", "0").toString().toByteArray()))
                            assertTrue(recoveryCloseStarted.await(2, TimeUnit.SECONDS))
                            val stop = if (receiverInitiated)
                                CompletableFuture.runAsync { server.disconnect("receiver stop") }
                            else {
                                send(owner, Type.STOP, JSONObject().put("reason", "sender stop"), 206)
                                null
                            }
                            assertTrue(stopHaltedVideo.await(2, TimeUnit.SECONDS))
                            do {
                                message = Wire.read(owner.inputStream)
                                if (message.type == Type.PING)
                                    send(owner, Type.PONG, JSONObject().put("nonce", message.json().getInt("nonce")), 206)
                            } while (message.type == Type.STATUS || message.type == Type.PING)
                            assertEquals(if (receiverInitiated) Type.STOP else Type.SESSION_END, message.type)
                            assertFalse(message.json().getBoolean("cleanupSucceeded"))
                            stop?.get(3, TimeUnit.SECONDS)
                            val connection = checkNotNull((server.javaClass.getDeclaredField("active")
                                .apply { isAccessible = true }.get(server) as AtomicReference<*>).get())
                            if (receiverInitiated)
                                send(owner, Type.SESSION_END, JSONObject().put("reason", "receiver stop"), 206)
                            val finished = connection.javaClass.getDeclaredField("controlCleanupFinished")
                                .apply { isAccessible = true }.get(connection) as CountDownLatch
                            assertTrue(finished.await(3, TimeUnit.SECONDS))
                            assertTrue(server.busyForUpdate)
                            assertEquals(1L, leaseClosed.count)
                            control(server).use { peer ->
                                assertEquals("BUSY", negotiate(peer, 207, expected = Type.REJECT).json().getString("code"))
                            }
                        }
                    }
                }
                assertTrue(leaseClosed.await(2, TimeUnit.SECONDS))
            } finally {
                workerRelease.complete(Unit)
                player.get()?.close()
            }
        }
    }

    @Test(timeout = 15_000) fun ordinaryStopInstalledPlayerCloseFailureRetainsOwnership() {
        val leaseClosed = CountDownLatch(1)
        val player = Mockito.mock(VideoPlayer::class.java)
        Mockito.doThrow(IllegalStateException("close failed")).`when`(player).close()
        ReceiverServer.forProtocolTests("Receiver",
            acquireSession = { AutoCloseable { leaseClosed.countDown() } },
            videoFactory = { _, _, _, _ -> player }).use { server ->
            server.start()
            control(server).use { owner ->
                MediaSockets.attach(server, negotiate(owner, 208, telemetry = true)).use { media ->
                    configure(media.video, 208)
                    var message: Message
                    do { message = Wire.read(owner.inputStream) } while (message.type == Type.STATUS)
                    assertEquals(Type.STREAM_READY, message.type)
                    send(owner, Type.STOP, JSONObject().put("reason", "sender stop"), 208)
                    do { message = Wire.read(owner.inputStream) } while (message.type == Type.STATUS)
                    assertEquals(Type.SESSION_END, message.type)
                    assertFalse(message.json().getBoolean("cleanupSucceeded"))
                    assertEquals(-1, owner.inputStream.read())
                    assertTrue(server.busyForUpdate)
                    assertEquals(1L, leaseClosed.count)
                    control(server).use { peer ->
                        assertEquals("BUSY", negotiate(peer, 209, expected = Type.REJECT).json().getString("code"))
                    }
                }
            }
        }
        assertTrue(leaseClosed.await(2, TimeUnit.SECONDS))
    }

    @Test(timeout = 10_000) fun legacyReceiverStopKeepsWireBehaviorButFencesFailedClose() {
        val leaseClosed = CountDownLatch(1)
        val stopWritten = CountDownLatch(1)
        val player = Mockito.mock(VideoPlayer::class.java)
        Mockito.doAnswer {
            check(stopWritten.await(3, TimeUnit.SECONDS))
            throw IllegalStateException("close failed")
        }.`when`(player).close()
        ReceiverServer.forProtocolTests("Receiver",
            acquireSession = { AutoCloseable { leaseClosed.countDown() } },
            videoFactory = { _, _, _, _ -> player },
            controlWrite = { socket, message ->
                Wire.write(socket.outputStream, message)
                if (message.type == Type.STOP) stopWritten.countDown()
            }).use { server ->
            server.start()
            control(server).use { owner ->
                MediaSockets.attach(server, negotiate(owner, 210, recovery = true)).use { media ->
                    configure(media.video, 210)
                    var message: Message
                    do { message = Wire.read(owner.inputStream) } while (message.type == Type.STATUS)
                    assertEquals(Type.STREAM_READY, message.type)
                    server.disconnect("receiver stop")
                    do { message = Wire.read(owner.inputStream) } while (message.type == Type.STATUS)
                    assertEquals(Type.STOP, message.type)
                    assertFalse(message.json().has("cleanupSucceeded"))
                    assertEquals(-1, owner.inputStream.read())
                    assertTrue(server.busyForUpdate)
                    assertEquals(1L, leaseClosed.count)
                    control(server).use { peer ->
                        assertEquals("BUSY", negotiate(peer, 211, expected = Type.REJECT).json().getString("code"))
                    }
                }
            }
        }
        assertTrue(leaseClosed.await(2, TimeUnit.SECONDS))
    }

    @Test(timeout = 25_000) fun fatalBlockedAudioPauseRetainsOwnershipUntilUninterruptibleRetirementFinishes() {
        val pauseEntered = CountDownLatch(1)
        val pauseRelease = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val worker = AtomicReference<Thread>()
        val leaseClosed = CountDownLatch(1)
        val available = CountDownLatch(1)
        val admitted = AtomicBoolean()
        val output = Mockito.mock(AudioOutput::class.java)
        Mockito.`when`(output.timing()).thenReturn(
            AudioOutputTiming(48000, 6240, 0, 0, 0, 0, AudioClockSource.TIMESTAMP))
        Mockito.doAnswer {
            worker.set(Thread.currentThread())
            pauseEntered.countDown()
            while (true) {
                try {
                    pauseRelease.await()
                    break
                } catch (_: InterruptedException) {
                    interrupted.countDown()
                }
            }
            null
        }.`when`(output).pause()
        try {
            ReceiverServer.forProtocolTests("Receiver",
                acquireSession = { AutoCloseable { leaseClosed.countDown() } },
                onScreenEvent = { if (admitted.get() && it == ReceiverScreenEvent.CleanupFinished) available.countDown() },
                videoFactory = { _, _, _, _ -> Mockito.mock(VideoPlayer::class.java) },
                audioFactory = { format, clock, failed -> testAudioPlayer(format, clock, failed, { output }) }
            ).use { server ->
                server.start()
                control(server).use { owner ->
                    owner.soTimeout = 12_000
                    MediaSockets.attach(server, negotiate(owner, 77, telemetry = true, audio = true)).use { media ->
                        admitted.set(true)
                        fun ready() {
                            var message: Message
                            do { message = Wire.read(owner.inputStream) } while (message.type == Type.STATUS)
                            assertEquals(Type.STREAM_READY, message.type)
                        }
                        configure(media.video, 77)
                        ready()
                        Wire.write(media.audio.outputStream, Message.json(Type.STREAM_CONFIG,
                            JSONObject().put("codec", "raw").put("sampleRate", 48000).put("channels", 2)
                                .put("sampleFormat", "s16le").put("csd", JSONArray()), 77, 2, 1))
                        ready()
                        val connection = checkNotNull((server.javaClass.getDeclaredField("active")
                            .apply { isAccessible = true }.get(server) as AtomicReference<*>).get())
                        Wire.write(media.video.outputStream, videoConfig(77).copy(generation = 2,
                            payload = videoConfig(77).json().put("codec", "invalid").toString().toByteArray()))
                        assertTrue(pauseEntered.await(3, TimeUnit.SECONDS))
                        checkNotNull(worker.get()).interrupt()
                        assertTrue(interrupted.await(3, TimeUnit.SECONDS))
                        var message: Message
                        do { message = Wire.read(owner.inputStream) } while (message.type == Type.STATUS)
                        assertEquals(Type.ERROR, message.type)
                        assertEquals("UNSUPPORTED_CONFIG", message.json().getString("code"))
                        assertFalse(message.json().getBoolean("cleanupSucceeded"))
                        assertTrue(message.json().isNull("finalSeq"))
                        assertEquals("UNAVAILABLE", message.json().getString("finalSkipReason"))
                        val controlFinished = connection.javaClass.getDeclaredField("controlCleanupFinished")
                            .apply { isAccessible = true }.get(connection) as CountDownLatch
                        assertTrue(controlFinished.await(5, TimeUnit.SECONDS))
                        assertTrue(server.busyForUpdate)
                        assertEquals("busy", StrictJson.decode(server.advertisement()).string("state"))
                        assertEquals(1L, leaseClosed.count)
                        Mockito.verify(output, Mockito.never()).close()
                        control(server).use { peer ->
                            val rejected = negotiate(peer, 78, expected = Type.REJECT)
                            assertEquals("BUSY", rejected.json().getString("code"))
                        }
                        pauseRelease.countDown()
                        assertTrue(leaseClosed.await(5, TimeUnit.SECONDS))
                        assertTrue(available.await(5, TimeUnit.SECONDS))
                        assertFalse(server.busyForUpdate)
                        Mockito.verify(output, Mockito.times(1)).pause()
                        Mockito.verify(output, Mockito.times(1)).close()
                        control(server).use { successor ->
                            negotiate(successor, 79)
                            stop(successor, 79)
                        }
                    }
                }
            }
        } finally { pauseRelease.countDown() }
    }

    @Test(timeout = 15_000) fun failedFatalResourceRetirementStaysBusyUntilReceiverDestroyed() {
        val leaseClosed = CountDownLatch(1)
        val player = Mockito.mock(VideoPlayer::class.java)
        Mockito.doThrow(IllegalStateException("retirement failed")).`when`(player).close()
        ReceiverServer.forProtocolTests("Receiver",
            acquireSession = { AutoCloseable { leaseClosed.countDown() } },
            videoFactory = { _, _, _, _ -> player }).use { server ->
            server.start()
            control(server).use { owner ->
                MediaSockets.attach(server, negotiate(owner, 80, telemetry = true)).use { media ->
                    configure(media.video, 80)
                    var ready: Message
                    do { ready = Wire.read(owner.inputStream) } while (ready.type == Type.STATUS)
                    assertEquals(Type.STREAM_READY, ready.type)
                    val connection = checkNotNull((server.javaClass.getDeclaredField("active")
                        .apply { isAccessible = true }.get(server) as AtomicReference<*>).get())
                    Wire.write(media.video.outputStream, videoConfig(80).copy(generation = 2,
                        payload = videoConfig(80).json().put("codec", "invalid").toString().toByteArray()))
                    var message: Message
                    do { message = Wire.read(owner.inputStream) } while (message.type == Type.STATUS)
                    assertEquals(Type.ERROR, message.type)
                    assertEquals("UNSUPPORTED_CONFIG", message.json().getString("code"))
                    assertFalse(message.json().getBoolean("cleanupSucceeded"))
                    val controlFinished = connection.javaClass.getDeclaredField("controlCleanupFinished")
                        .apply { isAccessible = true }.get(connection) as CountDownLatch
                    assertTrue(controlFinished.await(5, TimeUnit.SECONDS))
                    assertTrue(server.busyForUpdate)
                    assertEquals(1L, leaseClosed.count)
                    control(server).use { peer ->
                        assertEquals("BUSY", negotiate(peer, 81, expected = Type.REJECT).json().getString("code"))
                    }
                }
            }
        }
        assertTrue(leaseClosed.await(5, TimeUnit.SECONDS))
        Mockito.verify(player, Mockito.times(1)).close()
    }

    @Test(timeout = 15_000) fun fatalDeadlineAdmissionWinsBeforeCleanupClosesWriter() {
        val cleanupEntered = CountDownLatch(1)
        val cleanupRelease = CountDownLatch(1)
        val cleanupReturned = CountDownLatch(1)
        val player = Mockito.mock(VideoPlayer::class.java)
        Mockito.doAnswer {
            cleanupEntered.countDown()
            check(cleanupRelease.await(5, TimeUnit.SECONDS))
            cleanupReturned.countDown()
            null
        }.`when`(player).close()
        try {
            ReceiverServer.forProtocolTests("Receiver", videoFactory = { _, _, _, _ -> player }).use { server ->
                server.start()
                control(server).use { owner ->
                    MediaSockets.attach(server, negotiate(owner, 77, telemetry = true)).use { media ->
                        configure(media.video, 77)
                        assertEquals(Type.STREAM_READY, Wire.read(owner.inputStream).type)
                        val connection = checkNotNull((server.javaClass.getDeclaredField("active")
                            .apply { isAccessible = true }.get(server) as AtomicReference<*>).get())
                        fun field(name: String) = connection.javaClass.getDeclaredField(name).apply { isAccessible = true }
                        val writer = checkNotNull(field("writer").get(connection))
                        Wire.write(media.video.outputStream, videoConfig(77).copy(generation = 2,
                            payload = videoConfig(77).json().put("codec", "invalid").toString().toByteArray()))
                        assertTrue(cleanupEntered.await(3, TimeUnit.SECONDS))
                        val recovery = field("recoveryExecutor").get(connection) as ExecutorService
                        val cleanupFinished = CountDownLatch(1)
                        recovery.execute { cleanupFinished.countDown() }
                        val complete = connection.javaClass.getDeclaredMethod("completeFatalRetirement",
                            Boolean::class.javaPrimitiveType,
                            dev.redrain.cast.diagnostics.ReceiverTelemetrySession.FinalSkipReason::class.java)
                            .apply { isAccessible = true }
                        val claimed = field("fatalTerminalClaimed").get(connection) as AtomicBoolean
                        val accepting = writer.javaClass.getDeclaredField("accepting").apply { isAccessible = true }
                            .get(writer) as AtomicBoolean
                        val winner: java.util.concurrent.CompletableFuture<Void>
                        synchronized(writer) {
                            winner = java.util.concurrent.CompletableFuture.runAsync {
                                complete.invoke(connection, false,
                                    dev.redrain.cast.diagnostics.ReceiverTelemetrySession.FinalSkipReason.UNAVAILABLE)
                            }
                            await { claimed.get() }
                            cleanupRelease.countDown()
                            assertTrue(cleanupReturned.await(2, TimeUnit.SECONDS))
                            assertFalse("Cleanup must not finish before terminal admission",
                                cleanupFinished.await(150, TimeUnit.MILLISECONDS))
                            assertTrue("Writer must remain accepting until admission", accepting.get())
                        }
                        winner.get(3, TimeUnit.SECONDS)
                        var message = Wire.read(owner.inputStream)
                        while (message.type == Type.STATUS) message = Wire.read(owner.inputStream)
                        assertEquals(Type.ERROR, message.type)
                        assertEquals("UNSUPPORTED_CONFIG", message.json().getString("code"))
                        assertFalse(message.json().getBoolean("cleanupSucceeded"))
                        assertTrue(message.json().isNull("finalSeq"))
                        assertEquals("UNAVAILABLE", message.json().getString("finalSkipReason"))
                        assertClosed(owner)
                    }
                }
            }
        } finally { cleanupRelease.countDown() }
    }

    @Test(timeout = 15_000) fun receiverRecoveryDeadlineSendsFinalBeforeTimeoutError() {
        val time = java.util.concurrent.atomic.AtomicLong(1_000)
        ReceiverServer.forProtocolTests("Receiver", timeMilliseconds = time::get,
            videoFactory = { format, clock, failed, generation ->
                VideoPlayer(format, clock, failed, IdleDecoder(), generation)
            }).use { server ->
            server.start()
            control(server).use { owner ->
                MediaSockets.attach(server, negotiate(owner, 78, telemetry = true, recovery = true)).use { media ->
                    configure(media.video, 78)
                    assertEquals(Type.STREAM_READY, Wire.read(owner.inputStream).type)
                    val connection = checkNotNull((server.javaClass.getDeclaredField("active")
                        .apply { isAccessible = true }.get(server) as AtomicReference<*>).get())
                    val receive = connection.javaClass.getDeclaredMethod("receiveMedia", Message::class.java)
                        .apply { isAccessible = true }
                    val oversized = ByteArray(8 * 1024 * 1024 + 1).apply { this[3] = 1; this[4] = 0x65 }
                    receive.invoke(connection, Message(Type.VIDEO_FRAME, 78, 1, 1, 0, 1, oversized))
                    var request: Message
                    do { request = Wire.read(owner.inputStream) }
                    while (request.type == Type.STATUS && request.json().optString("state") == "session-telemetry")
                    assertEquals("stream-recovery-request", request.json().getString("state"))
                    val tick = connection.javaClass.getDeclaredMethod("tick").apply { isAccessible = true }
                    time.set(10_999)
                    tick.invoke(connection)
                    assertFalse((connection.javaClass.getDeclaredField("stopping").apply { isAccessible = true }
                        .get(connection) as AtomicBoolean).get())
                    time.set(11_200)
                    tick.invoke(connection)
                    var final: dev.redrain.cast.diagnostics.SessionTelemetryProtocol.Batch? = null
                    var message: Message
                    do {
                        message = Wire.read(owner.inputStream)
                        if (message.type == Type.STATUS && message.json().optString("state") == "session-telemetry") {
                            val batch = dev.redrain.cast.diagnostics.SessionTelemetryProtocol.parse(message.payload, "receiver")
                            if (batch.final) final = batch
                        }
                    } while (message.type != Type.ERROR)
                    assertEquals("TIMEOUT", message.json().getString("code"))
                    assertTrue(message.json().getString("message").contains("Track 1 recovery"))
                    assertTrue(message.json().getBoolean("cleanupSucceeded"))
                    assertEquals(checkNotNull(final).sequence.toString(), message.json().getString("finalSeq"))
                    assertClosed(owner)
                }
            }
        }
    }

    @Test(timeout = 40_000) fun blockedRecoveryRetirementAndConstructionPublishAndClearBegins() {
        for (track in 1..2) for (blockedPoint in listOf(DiagnosticPoint.RECOVERY_RETIRE, DiagnosticPoint.RECOVERY_CONSTRUCT)) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val enteredAt = java.util.concurrent.atomic.AtomicLong()
            val session = 70L + track * 2 + blockedPoint.ordinal
            val audioCreations = AtomicInteger()
            try {
                ReceiverServer.forProtocolTests("Receiver", videoFactory = { _, _, _, generation ->
                    if (track == 1 && generation == 2L && blockedPoint == DiagnosticPoint.RECOVERY_CONSTRUCT) {
                        enteredAt.set(System.nanoTime())
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                    }
                    Mockito.mock(VideoPlayer::class.java).also { player ->
                        if (track == 1 && generation == 1L && blockedPoint == DiagnosticPoint.RECOVERY_RETIRE)
                            Mockito.doAnswer {
                                enteredAt.set(System.nanoTime())
                                entered.countDown()
                                check(release.await(10, TimeUnit.SECONDS))
                                null
                            }.`when`(player).close()
                    }
                }, audioFactory = { _, _, _ ->
                    val generation = audioCreations.incrementAndGet()
                    if (track == 2 && generation == 2 && blockedPoint == DiagnosticPoint.RECOVERY_CONSTRUCT) {
                        enteredAt.set(System.nanoTime())
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                    }
                    Mockito.mock(AudioPlayer::class.java).also { player ->
                        Mockito.`when`(player.decoderDescription).thenReturn("fixture")
                        if (track == 2 && generation == 1 && blockedPoint == DiagnosticPoint.RECOVERY_RETIRE)
                            Mockito.doAnswer {
                                enteredAt.set(System.nanoTime())
                                entered.countDown()
                                check(release.await(10, TimeUnit.SECONDS))
                                null
                            }.`when`(player).close()
                    }
                }).use { server ->
                    server.start()
                    control(server).use { owner ->
                        MediaSockets.attach(server, negotiate(owner, session, telemetry = true, recovery = true,
                            audio = track == 2)).use { media ->
                            configure(media.video, session)
                            fun ready(): Message {
                                var message: Message
                                do { message = Wire.read(owner.inputStream) } while (message.type == Type.STATUS)
                                assertEquals(Type.STREAM_READY, message.type)
                                return message
                            }
                            ready()
                            if (track == 2) {
                                val audioConfig = Message.json(Type.STREAM_CONFIG,
                                    JSONObject().put("codec", "raw").put("sampleRate", 48000).put("channels", 2)
                                        .put("sampleFormat", "s16le").put("csd", JSONArray()), session, 2, 1)
                                Wire.write(media.audio.outputStream, audioConfig)
                                ready()
                                Wire.write(media.audio.outputStream, audioConfig.copy(generation = 2,
                                    payload = audioConfig.json().put("recoveryPtsUs", "0").toString().toByteArray()))
                            } else Wire.write(media.video.outputStream, videoConfig(session).copy(generation = 2))
                            assertTrue(entered.await(3, TimeUnit.SECONDS))
                            var batch: dev.redrain.cast.diagnostics.SessionTelemetryProtocol.Batch
                            do {
                                val status = Wire.read(owner.inputStream)
                                assertEquals(Type.STATUS, status.type)
                                assertTrue(status.payload.size <= 8192)
                                batch = dev.redrain.cast.diagnostics.SessionTelemetryProtocol.parse(status.payload, "receiver")
                            } while (batch.atNs < enteredAt.get())
                            assertTrue(batch.state.size <= 64)
                            assertTrue(batch.state.count { it.stage == DiagnosticStage.BEGIN.ordinal &&
                                it.kind in 1 until dev.redrain.cast.diagnostics.TelemetryRow.EVIDENCE_KIND } <= 16)
                            val begin = batch.state.single {
                                it.kind == DiagnosticKind.RECOVERY.ordinal + 1 &&
                                    it.stage == DiagnosticStage.BEGIN.ordinal && it.track == track &&
                                    it.detail.getOrNull(3) == blockedPoint.ordinal.toLong()
                            }
                            assertEquals(2, begin.generation)
                            assertTrue(begin.atNs > 0)
                            assertTrue(begin.detail[4] > 0)
                            release.countDown()
                            assertEquals(2L, ready().generation)
                            val completedAt = System.nanoTime()
                            var after: dev.redrain.cast.diagnostics.SessionTelemetryProtocol.Batch
                            do {
                                val message = Wire.read(owner.inputStream)
                                assertEquals(Type.STATUS, message.type)
                                after = dev.redrain.cast.diagnostics.SessionTelemetryProtocol.parse(message.payload, "receiver")
                            } while (after.atNs <= completedAt)
                            assertFalse(after.state.any {
                                it.kind == DiagnosticKind.RECOVERY.ordinal + 1 &&
                                    it.stage == DiagnosticStage.BEGIN.ordinal && it.detail.getOrNull(4) == begin.detail[4]
                            })
                        }
                    }
                }
            } finally { release.countDown() }
        }
    }

    @Test(timeout = 10_000) fun completedMediaReadDoesNotRemainInFlightWhileConfigureBlocks() {
        val configuring = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            ReceiverServer.forProtocolTests("Receiver", videoFactory = { format, clock, failed, generation ->
                configuring.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                VideoPlayer(format, clock, failed, IdleDecoder(), generation)
            }).use { server ->
                server.start()
                control(server).use { owner ->
                    MediaSockets.attach(server, negotiate(owner, 78, telemetry = true)).use { media ->
                        try {
                            configure(media.video, 78)
                            assertTrue(configuring.await(3, TimeUnit.SECONDS))
                            val status = Wire.read(owner.inputStream)
                            assertEquals(Type.STATUS, status.type)
                            val batch = dev.redrain.cast.diagnostics.SessionTelemetryProtocol.parse(status.payload, "receiver")
                            val reads = batch.state.filter {
                                it.kind == DiagnosticKind.TRANSPORT_HEALTH.ordinal + 1 && it.track == 1 &&
                                    it.detail.size == 5 && it.detail[3] == DiagnosticPoint.READ.ordinal.toLong()
                            }
                            assertTrue(reads.any { it.stage == DiagnosticStage.COMPLETE.ordinal && it.generation == 1 })
                            assertFalse(reads.any { it.stage == DiagnosticStage.BEGIN.ordinal })
                        } finally {
                            release.countDown()
                        }
                    }
                }
            }
        } finally {
            release.countDown()
        }
    }

    @Test(timeout = 15_000) fun admissionEvidenceDistinguishesAcceptedPressureAndExceptions() {
        for ((index, scenario) in listOf("accepted", "rotation", "congestion", "recovery", "retired").withIndex()) {
            val session = 26L + index
            ReceiverServer.forProtocolTests("Receiver", videoFactory = { format, clock, failed, generation ->
                VideoPlayer(format, clock, failed, IdleDecoder(), generation)
            }).use { server ->
                server.start()
                control(server).use { owner ->
                    MediaSockets.attach(server, negotiate(owner, session, telemetry = true)).use { media ->
                        configure(media.video, session)
                        var final: dev.redrain.cast.diagnostics.SessionTelemetryProtocol.Batch? = null
                        fun expect(type: Type): Message {
                            while (true) {
                                val message = Wire.read(owner.inputStream)
                                if (message.type == Type.STATUS) {
                                    if (message.json().optString("state") == "session-telemetry") {
                                        val batch = dev.redrain.cast.diagnostics.SessionTelemetryProtocol.parse(message.payload)
                                        if (batch.final) final = batch
                                    }
                                } else {
                                    assertEquals(type, message.type)
                                    return message
                                }
                            }
                        }
                        expect(Type.STREAM_READY)
                        assertTrue(dev.redrain.cast.diagnostics.TelemetryRegistry.has("receiver", session))
                        val connection = checkNotNull((server.javaClass.getDeclaredField("active")
                            .apply { isAccessible = true }.get(server) as AtomicReference<*>).get())
                        val receive = connection.javaClass.getDeclaredMethod("receiveMedia", Message::class.java)
                            .apply { isAccessible = true }
                        val bytes = if (scenario in setOf("congestion", "recovery"))
                            ByteArray(8 * 1024 * 1024 + 1).apply { this[3] = 1; this[4] = 0x65 }
                        else byteArrayOf(0, 0, 0, 1, 0x65)
                        val message = Message(Type.VIDEO_FRAME, session, 1, 1, 0,
                            if (scenario == "rotation") 4 else 1, bytes)
                        if (scenario == "recovery") connection.javaClass.getDeclaredField("streamRecovery")
                            .apply { isAccessible = true }.setBoolean(connection, true)
                        if (scenario == "retired") {
                            val worker = connection.javaClass.getDeclaredField("video").apply { isAccessible = true }
                                .get(connection) as VideoPlayer
                            worker.halt()
                            connection.javaClass.getDeclaredField("retiredVideoThrough").apply { isAccessible = true }
                                .setLong(connection, 1)
                        }
                        when (scenario) {
                            "rotation", "congestion" -> {
                                val error = assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
                                    receive.invoke(connection, message)
                                }.cause
                                if (scenario == "rotation") assertTrue(error is ProtocolException)
                                else assertTrue(error is org.screencopy.receiver.media.QueueCongestionException)
                            }
                            else -> receive.invoke(connection, message)
                        }
                        send(owner, Type.STOP, JSONObject().put("reason", "test"), session)
                        val terminal = expect(Type.SESSION_END)
                        val batch = checkNotNull(final)
                        assertEquals(batch.sequence.toString(), terminal.json().getString("finalSeq"))
                        val evidence = batch.state.filter {
                            it.kind == dev.redrain.cast.diagnostics.TelemetryRow.EVIDENCE_KIND && it.track == 1
                        }
                        val begin = evidence.single { it.stage == IncidentEvidence.Stage.ADMISSION_BEGIN.ordinal }
                        assertEquals(listOf(0L, bytes.size.toLong()), begin.detail)
                        val end = evidence.single { it.stage == IncidentEvidence.Stage.ADMISSION_END.ordinal }
                        assertEquals(if (scenario == "accepted") emptyList<Long>() else listOf(0L), end.detail)
                        if (scenario == "recovery") assertNotNull(batch.firstPressure)
                    }
                }
            }
        }
    }

    @Test(timeout = 15_000) fun senderStopCancelsQueuedReadyAfterMediaClosure() =
        senderStopRetainsWriterEvidence(queued = true, failWrite = false)

    @Test(timeout = 15_000) fun senderStopDrainsInflightReadyAfterMediaClosure() =
        senderStopRetainsWriterEvidence(queued = false, failWrite = false)

    @Test(timeout = 15_000) fun senderStopClosesAfterInflightReadyFailure() =
        senderStopRetainsWriterEvidence(queued = false, failWrite = true)

    private fun senderStopRetainsWriterEvidence(queued: Boolean, failWrite: Boolean) {
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cleaned = CountDownLatch(1)
        val armed = AtomicBoolean()
        val session = 25L
        ReceiverServer.forProtocolTests("Receiver", onScreenEvent = {
            if (armed.get() && it == ReceiverScreenEvent.CleanupFinished)
                cleaned.countDown()
        }, controlWrite = { socket, message ->
            if (message.type == (if (queued) Type.PONG else Type.STREAM_READY)) {
                writing.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                if (failWrite) throw java.io.IOException("ready write failed after sender Stop")
            }
            Wire.write(socket.outputStream, message)
        }).use { server ->
            server.start()
            try {
                control(server).use { owner ->
                    MediaSockets.attach(server, negotiate(owner, session)).use { media ->
                        armed.set(true)
                        val connection = checkNotNull((server.javaClass.getDeclaredField("active")
                            .apply { isAccessible = true }.get(server) as AtomicReference<*>).get())
                        fun field(name: String): Any = connection.javaClass.getDeclaredField(name)
                            .apply { isAccessible = true }.get(connection)
                        val writer = field("writer") as ControlWriter
                        val recovery = field("recoveryExecutor") as ExecutorService
                        if (queued) {
                            send(owner, Type.PING, JSONObject().put("nonce", 1), session)
                            assertTrue(writing.await(5, TimeUnit.SECONDS))
                        }
                        writer.offer(Message.json(Type.STREAM_READY, JSONObject().put("ok", true), session, 1, 1))
                        if (!queued) assertTrue(writing.await(5, TimeUnit.SECONDS))
                        send(owner, Type.STOP, JSONObject().put("reason", "sender"), session)
                        assertClosed(media.video)
                        assertClosed(media.audio)
                        assertTrue(recovery.awaitTermination(1, TimeUnit.SECONDS))
                        assertFalse(connection.javaClass.getDeclaredField("accepted")
                            .apply { isAccessible = true }.getBoolean(connection))
                        release.countDown()
                        if (failWrite) assertClosed(owner) else {
                            assertEquals(if (queued) Type.PONG else Type.STREAM_READY, Wire.read(owner.inputStream).type)
                            assertEquals(Type.SESSION_END, Wire.read(owner.inputStream).type)
                        }
                        assertTrue(cleaned.await(5, TimeUnit.SECONDS))
                    }
                }
            } finally { release.countDown() }
        }
    }

    @Test(timeout = 15_000) fun telemetryCaptureIsRegisteredBeforeControlAcceptanceOrMediaAdmission() {
        val captureAtAcceptance = AtomicBoolean()
        val receiver = ReceiverServer.forProtocolTests("Receiver", videoFactory = { format, clock, failed, generation ->
            VideoPlayer(format, clock, failed, IdleDecoder(), generation)
        }, controlWrite = { socket, message ->
            if (message.type == Type.SESSION_ACCEPT)
                captureAtAcceptance.set(dev.redrain.cast.diagnostics.TelemetryRegistry.has("receiver", message.session))
            Wire.write(socket.outputStream, message)
        })
        receiver.use { server ->
            server.start()
            control(server).use { owner ->
                val accepted = negotiate(owner, 24, telemetry = true)
                assertTrue(captureAtAcceptance.get())
                MediaSockets.attach(server, accepted).use { media ->
                    val rows = mutableListOf<dev.redrain.cast.diagnostics.TelemetryRow>()
                    var finalSeen = false
                    fun next(type: Type): Message {
                        while (true) {
                            val message = Wire.read(owner.inputStream)
                            if (message.type == Type.STATUS) {
                                val batch = dev.redrain.cast.diagnostics.SessionTelemetryProtocol.parse(message.payload)
                                rows.addAll(batch.events)
                                finalSeen = finalSeen || batch.final
                            } else {
                                assertEquals(type, message.type)
                                return message
                            }
                        }
                    }
                    configure(media.video, 24)
                    next(Type.STREAM_READY)
                    send(owner, Type.STOP, JSONObject().put("reason", "test"), 24)
                    val terminal = next(Type.SESSION_END)
                    if (finalSeen) {
                        val stages = rows.filter { it.kind == dev.redrain.cast.diagnostics.TelemetryRow.EVIDENCE_KIND }.map { it.stage }
                        assertTrue("$stages", dev.redrain.cast.diagnostics.IncidentEvidence.Stage.CONFIG_RECEIVED.ordinal in stages)
                        assertTrue("$stages", dev.redrain.cast.diagnostics.IncidentEvidence.Stage.READY_ENQUEUED.ordinal in stages)
                        assertTrue("$stages", dev.redrain.cast.diagnostics.IncidentEvidence.Stage.READY_WRITING.ordinal in stages)
                    } else assertTrue(terminal.json().isNull("finalSeq"))
                }
            }
        }
    }

    @Test(timeout = 15_000) fun videoQueueFailureDoesNotWriteLegacyReceiverReport() {
        val saved = AtomicBoolean()
        ReceiverServer.forProtocolTests("Receiver", videoFactory = { format, clock, failed, generation ->
            VideoPlayer(format, clock, failed, IdleDecoder(), generation)
        }, incidentSave = { { _, _ -> saved.set(true) } }).use { server ->
            server.start()
            control(server).use { owner ->
                val accepted = negotiate(owner, 23)
                MediaSockets.attach(server, accepted).use { media ->
                    configure(media.video, 23)
                    assertEquals(Type.STREAM_READY, Wire.read(owner.inputStream).type)
                    try {
                        repeat(20) { index ->
                            Wire.write(media.video.outputStream, Message(Type.VIDEO_FRAME, 23, 1, 1,
                                index * 20_000L, if (index == 0) 1 else 0, byteArrayOf(0, 0, 0, 1, 0x65)))
                        }
                    } catch (_: java.io.IOException) { }
                    val error = Wire.read(owner.inputStream)
                    assertEquals(Type.ERROR, error.type)
                    assertEquals("CONGESTION", error.json().getString("code"))
                    assertFalse(saved.get())
                }
            }
        }
    }

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

    private fun negotiate(owner: Socket, session: Long, telemetry: Boolean = false, recovery: Boolean = false,
                          audio: Boolean = false, expected: Type = Type.SESSION_ACCEPT): Message {
        val capability = dev.redrain.cast.diagnostics.SessionTelemetryProtocol.capability()
        send(owner, Type.HELLO, JSONObject().put("role", "sender").put("versions", JSONArray().put(3)).put("name", "Windows")
            .apply {
                if (telemetry) put("sessionTelemetry", capability)
                if (recovery) put("streamRecovery", true).put("streamRecoveryVersion", StreamRecoveryProtocol.VERSION)
            })
        val hello = Wire.read(owner.inputStream)
        assertEquals(Type.HELLO, hello.type)
        assertFalse(hello.json().has("videoRotation"))
        if (telemetry) assertTrue(dev.redrain.cast.diagnostics.SessionTelemetryProtocol.supported(hello.json()))
        send(owner, Type.SESSION_REQUEST, StrictJson.decode(
            """{"video":{"codec":"h264","encoder":"","bitrate":8000000,"maxSize":1920,"maxFps":60,"displayId":0,"orientation":"0","crop":null,"codecOptions":""},"audio":{"enabled":false,"codec":"opus","encoder":"","bitrate":192000,"source":"output","codecOptions":""}}""".toByteArray())
            .apply {
                if (telemetry) put("sessionTelemetry", capability)
                if (audio) getJSONObject("audio").put("enabled", true).put("codec", "raw")
            }, session)
        return Wire.read(owner.inputStream).also {
            assertEquals(expected, it.type)
            if (telemetry && expected == Type.SESSION_ACCEPT)
                assertTrue(dev.redrain.cast.diagnostics.SessionTelemetryProtocol.supported(it.json()))
        }
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

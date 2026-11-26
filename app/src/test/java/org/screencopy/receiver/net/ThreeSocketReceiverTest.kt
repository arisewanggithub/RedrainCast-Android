package org.screencopy.receiver.net

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import org.screencopy.receiver.media.AudioPlayer
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
import java.util.concurrent.atomic.AtomicReference
import dev.redrain.cast.diagnostics.DiagnosticKind
import dev.redrain.cast.diagnostics.DiagnosticPoint
import dev.redrain.cast.diagnostics.DiagnosticStage
import dev.redrain.cast.diagnostics.IncidentEvidence
import dev.redrain.cast.diagnostics.ReceiverTelemetrySession
import dev.redrain.cast.diagnostics.TelemetryRegistry

class ThreeSocketReceiverTest {
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
                            do { message = Wire.read(owner.inputStream) } while (message.type == Type.STATUS)
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

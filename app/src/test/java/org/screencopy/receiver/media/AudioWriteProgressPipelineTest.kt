package org.screencopy.receiver.media

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import dev.redrain.cast.diagnostics.*

class AudioWriteProgressPipelineTest {
    @Test fun causalStartGateResetsForPendingGenerationSessionAndCapture() {
        val gate = AudioCausalStartGate()
        val a = AudioStartDecision(1, 1, 100, null, null, null, null, 0, 0, 0)
        val b = a.copy(revision = 1)
        assertTrue(gate.take(1, 1, null, a))
        assertTrue(gate.take(1, 1, null, b))
        assertFalse(gate.take(1, 1, null, b))
        assertTrue(gate.take(1, 1, "capture", b))
        assertTrue(gate.take(1, 2, "capture", b))
        assertTrue(gate.take(2, 2, "capture", b))
        gate.reset()
        assertTrue(gate.take(2, 2, "capture", b))
    }

    @Test(timeout = 15_000) fun recoveryWaitRetriesDoNotOverwriteLastCausalTransition() {
        val now = AtomicLong()
        val entered = Semaphore(0)
        val proceed = Semaphore(0)
        val errors = ConcurrentLinkedQueue<String>()
        val clock = PlaybackClock(now::get) {
            entered.release()
            check(proceed.tryAcquire(3, TimeUnit.SECONDS))
        }.apply { anchorVideo(1_000_000); beginAudioRecovery() }
        val output = object : AudioOutput {
            override val underrunCount = 0
            override fun play() {}
            override fun pause() {}
            override fun setSpeed(speed: Float) {}
            override fun timing() = AudioOutputTiming(48000, 5760, 0, 0, 0, null,
                AudioClockSource.HEAD_UNAVAILABLE)
            override fun write(samples: ShortArray, offset: Int, count: Int) = count
            override fun close() {}
        }
        val player = testAudioPlayer(AudioFormat("pcm", 48000, 2, byteArrayOf()), clock,
            { code, detail -> errors.add("$code: $detail") }, { output }).apply {
            recoveryCutoffUs = 4_000_000
            diagnosticSession = 505
            diagnosticGeneration = 1
        }
        val telemetry = ReceiverTelemetrySession({ true })
        TelemetryRegistry.beginReceiver(505, telemetry)
        try {
            player.start()
            assertNull(player.tryOffer(AccessUnit(4_000_000, ByteArray(960 * 4))))
            assertTrue(entered.tryAcquire(3, TimeUnit.SECONDS))
            TelemetryRegistry.freezeReceiver(505, 1)
            clock.videoPresented(2_000_000, now.get())
            proceed.release()
            assertTrue(entered.tryAcquire(3, TimeUnit.SECONDS))
            proceed.release()
            assertTrue(entered.tryAcquire(3, TimeUnit.SECONDS))
            val final = SessionTelemetryProtocol.parse(checkNotNull(
                telemetry.requestFinal(true, System.nanoTime() + 1_000_000_000L)).payload)
            val starts = final.events.filter { it.kind == CausalEvidence.KIND &&
                it.stage == CausalEvidence.Stage.START.ordinal }
            val first = starts.single { it.value == 1L }
            val later = starts.filter { it.value == 2L }
            assertEquals(1, later.size)
            assertEquals(1L, first.detail[1])
            assertEquals(1L, later.single().detail[1])
            assertTrue(later.single().detail[6] > first.detail[6])
            val capture = final.events.single { it.kind == CausalEvidence.KIND &&
                it.stage == CausalEvidence.Stage.CAPTURE.ordinal }
            assertEquals(0L, capture.detail[2])
            assertEquals(1L, capture.detail[3])
            assertTrue(errors.toString(), errors.isEmpty())
        } finally {
            proceed.release()
            player.close()
            TelemetryRegistry.end("receiver", telemetry)
            telemetry.requestFinal(false, System.nanoTime())
        }
    }

    @Test fun startupEvidencePreservesTransitionsAndAccountsForSuppressedRepeats() {
        val gate = AudioStartEvidenceGate()
        val waiting = AudioStartDecision(1, 1, 100, null, null, null, null, 0, 0, 0)
        assertEquals(0L, gate.take(1, waiting, 0))
        assertNull(gate.take(1, waiting, 1))
        assertNull(gate.take(1, waiting, 2))
        assertEquals(2L, gate.take(1, waiting.copy(reason = 4), 3))
        assertEquals(0L, gate.take(1, waiting.copy(reason = 5), 4))
        assertEquals(0L, gate.take(2, waiting, 5))
        assertNull(gate.take(2, waiting, 6))
        assertEquals(1L, gate.take(2, waiting, 250_000_005))
        assertEquals(0L, gate.take(2, waiting, 250_000_006, "new-capture"))
        assertNull(gate.take(2, waiting, 250_000_007, "new-capture"))
        assertEquals(1L, gate.take(2, waiting.copy(revision = 1), 250_000_008, "new-capture"))
    }

    @Test fun startupStepCpuIsCapturedBeforeAudioOutputStarts() {
        val cpu = AtomicLong()
        val fixture = Fixture(threadCpuNs = { cpu.addAndGet(1_000_000) }) { _, count, _ -> count }
        val telemetry = ReceiverTelemetrySession({ true })
        TelemetryRegistry.beginReceiver(504, telemetry)
        fixture.player.diagnosticSession = 504
        fixture.player.diagnosticGeneration = 1
        try {
            fixture.start()
            await { fixture.player.sourceSubmittedFrames > 0 || fixture.errors.isNotEmpty() }
            assertTrue(fixture.errors.toString(), fixture.errors.isEmpty())
            TelemetryRegistry.freezeReceiver(504, 1)
            fixture.close()
            TelemetryRegistry.end("receiver", telemetry)
            val final = SessionTelemetryProtocol.parse(checkNotNull(
                telemetry.requestFinal(true, System.nanoTime() + 1_000_000_000L)).payload)
            val worker = final.events.single { it.kind == CausalEvidence.KIND &&
                it.stage == CausalEvidence.Stage.WORKER.ordinal }
            assertTrue(worker.detail.toString(), worker.detail[8] >= 5_000_000)
            assertTrue(worker.detail.toString(), worker.detail[9] > 0)
        } finally {
            fixture.close()
            TelemetryRegistry.end("receiver", telemetry)
            telemetry.requestFinal(false, System.nanoTime())
        }
    }

    @Test fun audioStartDecisionIsPublishedDuringControlledWaitAndResetForNewGeneration() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockedPublished = CountDownLatch(1)
        val readyPublished = CountDownLatch(1)
        val blocked = java.util.concurrent.atomic.AtomicReference<TelemetryRow>()
        val ready = java.util.concurrent.atomic.AtomicReference<SessionTelemetryProtocol.Batch>()
        val fixture = Fixture(waitTick = { now, ms ->
            if (entered.count > 0) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            now.addAndGet(ms * 1_000_000)
        }) { _, count, _ -> count }
        val telemetry = ReceiverTelemetrySession({ bytes ->
            val batch = SessionTelemetryProtocol.parse(bytes)
            batch.state.firstOrNull { it.kind == DiagnosticKind.AUDIO_OUTPUT.ordinal + 1 &&
                it.stage == DiagnosticStage.SAMPLE.ordinal && it.detail.size == 20 }?.let {
                if (it.detail[2] == 1L) { blocked.set(it); blockedPublished.countDown() }
                if (it.detail[2] == 4L) { ready.set(batch); readyPublished.countDown() }
            }
            true
        })
        TelemetryRegistry.beginReceiver(503, telemetry)
        fixture.player.diagnosticSession = 503
        fixture.player.diagnosticGeneration = 3
        try {
            fixture.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(blockedPublished.await(3, TimeUnit.SECONDS))
            val row = blocked.get()
            assertEquals(3, row.generation)
            assertEquals(1_000_000L, row.value)
            assertEquals(950_000L, row.detail[4])
            assertEquals(50_000L, row.detail[5])
            assertTrue(row.detail[1] > 0)
            assertEquals(-1L, row.detail[11])
            assertEquals("Waiting must not query an output that has not started", 0, fixture.track.timingCalls)
            TelemetryRegistry.freezeReceiver(503, 3)
            release.countDown()
            assertTrue(readyPublished.await(3, TimeUnit.SECONDS))
            val progressed = ready.get()
            val state = progressed.state.single { it.kind == TelemetryRow.EVIDENCE_KIND &&
                it.stage == IncidentEvidence.Stage.AUDIO_STATE.ordinal }
            assertEquals(3, state.generation)
            assertTrue("The normal output worker supplies real cached timing", state.detail[2] > 0)
            assertTrue(state.detail[3] <= state.detail[2])
            assertTrue(fixture.track.timingCalls > 0)
            assertFalse(progressed.state.any { it.stage == DiagnosticStage.BEGIN.ordinal &&
                it.kind == DiagnosticKind.AUDIO_OUTPUT.ordinal + 1 &&
                it.detail[3] == DiagnosticPoint.AUDIO_START_WAIT.ordinal.toLong() })
            fixture.close()
            fixture.player.diagnosticGeneration = 4
            fixture.player.resetAudioStartEvidence()
            TelemetryRegistry.end("receiver", telemetry)
            val final = SessionTelemetryProtocol.parse(checkNotNull(
                telemetry.requestFinal(true, System.nanoTime() + 1_000_000_000L)).payload)
            val reset = final.state.single { it.kind == DiagnosticKind.AUDIO_OUTPUT.ordinal + 1 &&
                it.stage == DiagnosticStage.SAMPLE.ordinal && it.detail.size == 20 }
            assertEquals(4, reset.generation)
            assertEquals(0L, reset.detail[2])
            assertEquals(-1L, reset.detail[11])
            val retentionReset = final.state.single { it.kind == DiagnosticKind.AUDIO_OUTPUT.ordinal + 1 &&
                it.stage == DiagnosticStage.SAMPLE.ordinal && it.detail.size == 17 }
            assertEquals(4, retentionReset.generation)
            assertEquals(listOf(-1L, 0L, 0L, 0L, 0L), retentionReset.detail.subList(2, 7))
            assertEquals(-1L, retentionReset.detail[9])
            val frozenWait = final.events.single { it.kind == CausalEvidence.KIND &&
                it.stage == CausalEvidence.Stage.START.ordinal && it.value == 1L }
            assertEquals(1L, frozenWait.detail[1])
            assertEquals(1_000_000L, frozenWait.detail[3])
            assertTrue(final.events.any { it.kind == CausalEvidence.KIND &&
                it.stage == CausalEvidence.Stage.START.ordinal && it.value == 2L && it.detail[1] == 4L })
        } finally {
            release.countDown()
            fixture.close()
            TelemetryRegistry.end("receiver", telemetry)
            telemetry.requestFinal(false, System.nanoTime())
        }
    }

    @Test fun blockedWriteAppearsInPeriodicTelemetryAndClearsAfterCompletion() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val published = CountDownLatch(1)
        val periodic = java.util.concurrent.atomic.AtomicReference<ByteArray>()
        val fixture = Fixture { _, count, _ -> entered.countDown(); release.await(); count }
        val telemetry = ReceiverTelemetrySession({ bytes -> periodic.set(bytes); published.countDown(); true })
        TelemetryRegistry.beginReceiver(502, telemetry)
        fixture.player.diagnosticSession = 502
        fixture.player.diagnosticGeneration = 3
        try {
            fixture.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(published.await(3, TimeUnit.SECONDS))
            val blocked = SessionTelemetryProtocol.parse(periodic.get()).state.single {
                it.kind == DiagnosticKind.AUDIO_OUTPUT.ordinal + 1 &&
                    it.stage == DiagnosticStage.BEGIN.ordinal &&
                    it.detail[3] == DiagnosticPoint.WRITE.ordinal.toLong()
            }
            assertEquals(3, blocked.generation)
            assertTrue(blocked.atNs > 0)
            assertTrue(blocked.detail[4] > 0)
            release.countDown()
            await { fixture.player.sourceSubmittedFrames > 0 || fixture.errors.isNotEmpty() }
            assertTrue(fixture.errors.toString(), fixture.errors.isEmpty())
            fixture.close()
            TelemetryRegistry.end("receiver", telemetry)
            val final = SessionTelemetryProtocol.parse(checkNotNull(
                telemetry.requestFinal(true, System.nanoTime() + 1_000_000_000L)).payload)
            assertFalse(final.state.any { it.kind == DiagnosticKind.AUDIO_OUTPUT.ordinal + 1 &&
                it.stage == DiagnosticStage.BEGIN.ordinal &&
                it.detail[3] == DiagnosticPoint.WRITE.ordinal.toLong() })
        } finally {
            release.countDown()
            fixture.close()
            TelemetryRegistry.end("receiver", telemetry)
            telemetry.requestFinal(false, System.nanoTime() + 1_000_000_000L)
        }
    }

    @Test fun realAudioWriteReportsPointPtsAndAcceptedPcmBytes() {
        val written = CountDownLatch(1)
        val fixture = Fixture { _, count, _ -> written.countDown(); count }
        val telemetry = ReceiverTelemetrySession({ true })
        TelemetryRegistry.beginReceiver(501, telemetry)
        fixture.player.diagnosticSession = 501
        fixture.player.diagnosticGeneration = 1
        var closed = false
        try {
            fixture.start()
            assertTrue(written.await(2, TimeUnit.SECONDS))
            await { fixture.player.sourceSubmittedFrames > 0 || fixture.errors.isNotEmpty() }
            assertTrue(fixture.errors.toString(), fixture.errors.isEmpty())
            fixture.close()
            closed = true
            TelemetryRegistry.end("receiver", telemetry)
            val final = checkNotNull(telemetry.requestFinal(true, System.nanoTime() + 1_000_000_000L))
            val batch = SessionTelemetryProtocol.parse(final.payload)
            val worker = batch.events.single { it.kind == CausalEvidence.KIND &&
                it.stage == CausalEvidence.Stage.WORKER.ordinal }
            assertEquals(1_000_000L, worker.detail[5])
            assertTrue(worker.detail[3] >= worker.detail[4])
            assertTrue(worker.atNs <= fixture.now.get())
            assertTrue(batch.state.toString(), batch.state.any {
                it.kind == dev.redrain.cast.diagnostics.DiagnosticKind.AUDIO_OUTPUT.ordinal + 1 &&
                    it.stage == dev.redrain.cast.diagnostics.DiagnosticStage.COMPLETE.ordinal &&
                    it.detail[3] == dev.redrain.cast.diagnostics.DiagnosticPoint.WRITE.ordinal.toLong() &&
                    it.atNs >= 0 && it.value >= 1_000_000L && it.detail[0] > 0
            })
            assertTrue(batch.state.toString(), batch.state.any {
                it.kind == dev.redrain.cast.diagnostics.DiagnosticKind.AUDIO_OUTPUT.ordinal + 1 &&
                    it.detail[3] == dev.redrain.cast.diagnostics.DiagnosticPoint.TIMING.ordinal.toLong()
            })
        } finally {
            if (!closed) fixture.close()
            TelemetryRegistry.end("receiver", telemetry)
            telemetry.requestFinal(false, System.nanoTime() + 1_000_000_000L)
        }
    }

    @Test fun audioStartWaitHasItsOwnTypedOperationSummaries() {
        val diagnostics = RemoteDiagnostics.recorder
        diagnostics.grantConsent()
        diagnostics.sessionStarted("receiver", 73)
        val id = diagnostics.start("receiver", 73, setOf("audio.output"), 10000, 1000).getString("captureId")
        try {
            Fixture { _, count, _ -> count }.use { fixture ->
                fixture.player.diagnosticSession = 73
                fixture.player.diagnosticGeneration = 2
                fixture.start()
                await { fixture.player.sourceSubmittedFrames > 0 || fixture.errors.isNotEmpty() }
                assertTrue(fixture.errors.toString(), fixture.errors.isEmpty())
                diagnostics.end(id)
                val wait = capturedRecords(id).filter {
                    it.getString("kind") == "AUDIO_OUTPUT" &&
                        it.getJSONObject("data").optString("point") == "audio_start_wait" &&
                        it.getJSONObject("data").optString("stage") == "sample" &&
                        it.getJSONObject("data").optJSONObject("metrics")?.optString("schema") == "operation_summary"
                }
                assertTrue(wait.isNotEmpty())
                val metrics = wait.map { it.getJSONObject("data").getJSONObject("metrics") }
                assertTrue(metrics.all { it.getString("schema") == "operation_summary" })
                assertEquals(1L, metrics.sumOf { it.getString("started").toLong() })
                assertEquals(1L, metrics.sumOf { it.getString("completed").toLong() })
                assertEquals(1L, metrics.sumOf { summary ->
                    val durations = summary.getJSONObject("durationBuckets")
                    listOf("under1Ms", "1To5Ms", "5To20Ms", "over20Ms").sumOf { durations.getString(it).toLong() }
                })
            }
        } finally {
            diagnostics.revoke()
            diagnostics.sessionEnded("receiver", 73)
        }
    }

    @Test fun resumedRawOutputTrimsToPresentedVideoWithoutWaitingOrRetimingSamples() = listOf(false, true).forEach { freezeBefore ->
        val telemetry = ReceiverTelemetrySession({ true })
        TelemetryRegistry.beginReceiver(9703, telemetry)
        try { Fixture { _, count, _ -> count }.use { fixture ->
            val clock = fixture.clock
            fixture.player.diagnosticSession = 9703
            fixture.player.diagnosticGeneration = 2
            clock.prepareAudio(TrimmedAudio(0, 960, 1_000_000))
            assertTrue(clock.awaitAudioStart(1_000_000) { true })
            clock.anchorAudio(1_000_000) { 20_000 }
            clock.audioWritten(40_000)
            clock.beginAudioRecovery()
            clock.detachAudio()
            clock.videoPresented(1_990_000)
            fixture.player.recoveryCutoffUs = 1_000_000
            val submitted = AtomicLong(-1)
            fixture.player.onFirstSubmitted = submitted::set
            val before = fixture.now.get()
            val bytes = ByteBuffer.allocate(1920 * 2).order(ByteOrder.LITTLE_ENDIAN)
            repeat(1920) { bytes.putShort(it.toShort()) }
            if (freezeBefore) TelemetryRegistry.freezeReceiver(9703, 1)
            fixture.player.start()
            fixture.player.offer(AccessUnit(1_980_000, bytes.array()))
            await { fixture.player.sourceSubmittedFrames == 480L || fixture.errors.isNotEmpty() }
            assertTrue(fixture.errors.toString(), fixture.errors.isEmpty())
            assertEquals(1_990_000L, submitted.get())
            assertEquals(before, fixture.now.get())
            assertEquals((960 until 1920).map(Int::toShort), fixture.track.accepted.toList().take(960))
            assertEquals(48000, fixture.track.sampleRate)
            assertEquals(1f, fixture.track.speed)
            assertTrue(clock.currentUs()!! >= 1_990_000)
            clock.endAudioRecovery()
            clock.checkStartup()
            if (!freezeBefore) TelemetryRegistry.freezeReceiver(9703, 2)
            val final = checkNotNull(telemetry.requestFinal(true, System.nanoTime() + 1_000_000_000))
            val rows = SessionTelemetryProtocol.parse(final.payload).events
            if (!freezeBefore) {
                val trim = rows.single {
                    it.kind == CausalEvidence.KIND && it.stage == CausalEvidence.Stage.TRIM.ordinal && it.value == 1L
                }
                assertEquals(listOf(1L, 1_980_000L, 1_990_000L, 1_000_000L, 1_990_000L, 960L, 480L), trim.detail)
            }
            val gate = rows.single { it.kind == CausalEvidence.KIND &&
                it.stage == CausalEvidence.Stage.START.ordinal && it.value == if (freezeBefore) 2L else 1L }
            assertEquals(1_990_000L, gate.detail[3])
            assertEquals(listOf(1_980_000L, 1_990_000L, 1L), gate.detail.takeLast(3))
        } } finally {
            TelemetryRegistry.end("receiver", telemetry)
            telemetry.requestFinal(false, System.nanoTime())
        }
    }

    @Test fun remoteEndCannotWaitForBlockedNativeOutputAndPreservesUnfinishedOperation() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fixture = Fixture { _, _, _ -> entered.countDown(); release.await(); 0 }
        val diagnostics = RemoteDiagnostics.recorder
        diagnostics.grantConsent()
        diagnostics.sessionStarted("receiver", 73)
        val id = diagnostics.start("receiver", 73, setOf("audio.output", "audio.queue"), 10000, 100).getString("captureId")
        fixture.player.diagnosticSession = 73
        fixture.player.diagnosticGeneration = 1
        try {
            fixture.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val started = System.nanoTime()
            diagnostics.end(id)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 500)
            val terminal = capturedRecords(id).single { it.getString("kind") == "CAPTURE_ENDED" }.getJSONObject("data")
            assertTrue(terminal.getInt("inFlightCount") > 0)
            val inFlight = terminal.getJSONArray("inFlight")
            val write = (0 until inFlight.length()).map { inFlight.getJSONObject(it) }.single {
                it.getString("kind") == "AUDIO_OUTPUT" && it.getString("point") == "write"
            }
            assertTrue(write.getString("operationId").toLong() > 0)
            assertTrue(write.getString("ageNs").toLong() >= 0)
            assertEquals(2, write.getInt("track"))
            assertEquals(1, write.getInt("generation"))
            assertEquals("1000000", write.getString("ptsUs"))
            assertTrue(write.getString("count").toLong() > 0)
            assertFalse(diagnostics.collecting)
        } finally {
            release.countDown()
            fixture.close()
            diagnostics.revoke()
            diagnostics.sessionEnded("receiver", 73)
        }
    }
    @Test fun partialProgressAcrossLongPacketDoesNotBecomeAWriteStall() {
        val fixture = Fixture { track, count, call ->
            if (call % 2 == 0) {
                track.now.addAndGet(100_000_000)
                minOf(192, count)
            } else {
                track.now.addAndGet(20_000_000)
                0
            }
        }
        fixture.use {
            it.start()
            await { it.player.sourceSubmittedFrames == 960L || it.errors.isNotEmpty() }
            assertTrue(it.errors.toString(), it.errors.isEmpty())
            assertEquals((0 until 1920).map(Int::toShort), it.track.accepted.toList().take(1920))
            assertTrue("Packet elapsed exceeds the unchanged no-progress budget", it.track.elapsedNs > 250_000_000)
            assertTrue(it.track.maximumQueued <= it.track.bufferFrames)
            assertEquals(960L, it.player.sourceSubmittedFrames)
            await { it.clock.currentUs() == 1_020_000L }
            assertEquals(it.player.sourcePositionUs() + 1_000_000, it.clock.currentUs())
        }
    }

    @Test fun progressAfterLongWriteCallStartsANewNoProgressWindow() {
        val fixture = Fixture { track, count, call ->
            when (call) {
                0 -> { track.now.addAndGet(400_000_000); 192 }
                1 -> { track.now.addAndGet(1_000_000); 0 }
                else -> count
            }
        }
        fixture.use {
            it.start()
            await { it.player.sourceSubmittedFrames == 960L || it.errors.isNotEmpty() }
            assertTrue(it.errors.toString(), it.errors.isEmpty())
            assertEquals((0 until 1920).map(Int::toShort), it.track.accepted.toList().take(1920))
        }
    }

    @Test fun continuousZeroWritesStillFailAtTheOriginalNoProgressLimit() {
        val fixture = Fixture { track, _, _ -> track.now.addAndGet(50_000_000); 0 }
        fixture.use {
            it.start()
            await { it.errors.isNotEmpty() }
            assertEquals(1, it.errors.size)
            val error = it.errors.single()
            assertTrue(error, error.startsWith("AUDIO_FAILED: Audio stage=OUTPUT_WRITE"))
            assertTrue(error, error.contains("Audio output stalled for 250 ms"))
            assertTrue(error, error.contains("event=WRITE_STALL"))
            assertTrue(error, error.contains("recent_progress_ms=300"))
            assertTrue(error, error.contains("queued_decoded_frames=960"))
            assertTrue(error, error.contains("source_frames=0"))
            assertTrue(error, error.contains("playing_state=true"))
            assertTrue(error, error.contains("submitted_frames=0"))
            assertTrue(error, error.contains("consumed_frames=0"))
            assertEquals(6, it.track.calls)
            assertTrue(it.track.accepted.isEmpty())
        }
    }

    @Test fun aTrueStallAfterProgressReportsOnlyTimeSinceLastAcceptedSamples() {
        val fixture = Fixture { track, _, call ->
            track.now.addAndGet(100_000_000)
            if (call == 0) 192 else 0
        }
        fixture.use {
            it.start()
            await { it.errors.isNotEmpty() }
            val error = it.errors.single()
            assertTrue(error, error.contains("recent_progress_ms=300"))
            assertTrue(error, error.contains("queued_decoded_frames=864"))
            assertTrue(error, error.contains("source_frames=96"))
            assertEquals(192, it.track.accepted.size)
        }
    }

    @Test fun negativeNativeWriteRemainsFatal() = invalidWrite(-6, "AudioTrack write failed (-6)")
    @Test fun excessiveNativeWriteRemainsFatal() = invalidWrite(1922, "AudioTrack returned an invalid sample count")
    @Test fun unalignedNativeWriteRemainsFatal() = invalidWrite(1, "AudioTrack returned an invalid sample count")

    private fun invalidWrite(result: Int, expected: String) {
        val fixture = Fixture { _, _, _ -> result }
        fixture.use {
            it.start()
            await { it.errors.isNotEmpty() }
            assertEquals(1, it.errors.size)
            assertTrue(it.errors.single(), it.errors.single().contains(expected))
        }
    }

    @Test fun cancellationInterruptsOutstandingZeroWriteLoopAndReleasesOutput() {
        val entered = CountDownLatch(1)
        val fixture = Fixture { _, _, _ -> entered.countDown(); 0 }
        var started = 0L
        try {
            fixture.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
        } finally {
            started = System.nanoTime()
            fixture.close()
        }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 500)
        assertTrue(fixture.errors.toString(), fixture.errors.isEmpty())
        assertFalse(fixture.track.isPlaying)
        assertTrue(fixture.track.closed)
        assertEquals(0, fixture.player.queuedPackets())
    }

    @Test fun stalledOutputStillHonorsProducerQueueAndAdmissionTimeout() {
        val entered = CountDownLatch(1)
        val wait = CountDownLatch(1)
        val fixture = Fixture { _, _, _ -> entered.countDown(); wait.await(); 0 }
        fixture.use {
            it.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            for (index in 1..51) it.player.offer(AccessUnit(1_000_000 + index * 20_000L, ByteArray(3840)))
            val started = System.nanoTime()
            try {
                it.player.offer(AccessUnit(2_040_000, ByteArray(3840)))
                fail("An output retry must not bypass the producer budget")
            } catch (error: QueueCongestionException) {
                assertEquals(QueueLimit.PTS, error.pressure.limit)
                assertEquals(DEFAULT_AUDIO_QUEUE_SPAN_US, error.pressure.maxSpanUs)
                assertEquals(512 * 1024, error.pressure.maxBytes)
                assertEquals(256, error.pressure.maxPackets)
            }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) in 240..1000)
            assertEquals(51, it.player.queuedPackets())
            assertTrue(it.errors.toString(), it.errors.isEmpty())
        }
    }

    private class Fixture(waitTick: ((AtomicLong, Long) -> Unit)? = null,
                          threadCpuNs: () -> Long = { 0 },
                          write: (TraceTrack, Int, Int) -> Int) : AutoCloseable {
        val now = AtomicLong(1_000_000_000)
        val errors = ConcurrentLinkedQueue<String>()
        val format = AudioFormat("pcm", 48000, 2, byteArrayOf())
        val track = TraceTrack(now, write)
        val output = PlatformAudioOutput(format, { code, detail -> errors.add("$code: $detail") },
            object : AudioOutputPlatform {
                override fun minimumBytes(format: AudioFormat) = 5760 * 4
                override fun create(format: AudioFormat, bytes: Int, failure: (String, String) -> Unit): RateAudioTrack {
                    assertEquals(AudioOutputPreparation.plan(format, 5760 * 4).bytes, bytes)
                    return track
                }
            })
        val clock = PlaybackClock(now::get) {
            if (waitTick == null) now.addAndGet(it * 1_000_000) else waitTick(now, it)
        }
            .apply { audioExpected = true; anchorVideo(1_000_000) }
        val player = testAudioPlayer(format, clock,
            { code, detail -> errors.add("$code: $detail") }, { output }, threadCpuNs = threadCpuNs)
        fun start() {
            player.start()
            val bytes = ByteBuffer.allocate(1920 * 2).order(ByteOrder.LITTLE_ENDIAN)
            repeat(1920) { bytes.putShort(it.toShort()) }
            player.offer(AccessUnit(1_000_000, bytes.array()))
        }
        override fun close() = player.close()
    }

    private class TraceTrack(val now: AtomicLong, private val write: (TraceTrack, Int, Int) -> Int) : RateAudioTrack {
        override val initialized = true
        override val sampleRate = 48000
        override val channels = 2
        override val pcm16 = true
        override val bufferFrames = 6240
        override val capacityFrames = 6240
        override val pitch = 1f
        override val failOnUnsupported = true
        override var isPlaying = false
        private var actualSpeed = 1f
        override val speed get() = actualSpeed
        override val underrunCount = 0
        val accepted = ConcurrentLinkedQueue<Short>()
        private val submitted = AtomicLong()
        @Volatile var calls = 0
        @Volatile var timingCalls = 0
        @Volatile var closed = false
        var maximumQueued = 0L
        private var startNs = 0L
        val elapsedNs get() = now.get() - startNs
        private fun consumed() = minOf(submitted.get(), elapsedNs * sampleRate / 1_000_000_000)
        private val position = AudioPosition(sampleRate, bufferFrames, now::get, { null }, { consumed().toInt() })
        override fun play() { startNs = now.get(); isPlaying = true }
        override fun pause() { isPlaying = false }
        override fun write(samples: ShortArray, offset: Int, count: Int): Int {
            check(!closed)
            val result = write(this, count, calls++)
            if (result > 0 && result <= count && result % channels == 0) {
                position.written(result / channels)
                submitted.addAndGet(result.toLong() / channels)
                maximumQueued = maxOf(maximumQueued, submitted.get() - consumed())
                for (index in offset until offset + result) accepted.add(samples[index])
            }
            return result
        }
        override fun positionUs() = position.current().presentedUs
        override fun timing() = position.current().also { timingCalls++ }
        override fun setSpeed(speed: Float) { actualSpeed = speed }
        override fun close() { closed = true }
    }

    companion object {
        private fun capturedRecords(id: String): List<org.json.JSONObject> {
            val records = ArrayList<org.json.JSONObject>()
            var cursor = 0L
            repeat(32) {
                val page = RemoteDiagnostics.recorder.fetch(id, cursor, 32)
                val batch = page.getJSONArray("records")
                if (batch.length() == 0) return records
                for (i in 0 until batch.length()) records.add(batch.getJSONObject(i))
                cursor = page.getString("nextAfterSeq").toLong()
            }
            fail("Capture pagination did not terminate")
            return records
        }

        private fun await(condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!condition() && System.nanoTime() < deadline) Thread.sleep(1)
            assertTrue("Production audio worker made no progress", condition())
        }
    }
}

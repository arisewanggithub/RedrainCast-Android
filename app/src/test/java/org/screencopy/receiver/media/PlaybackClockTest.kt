package org.screencopy.receiver.media

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import dev.redrain.cast.diagnostics.*

class PlaybackClockTest {
    @Test fun automaticFinalPreservesPreRecoveryMappingAndFirstAndLatestRecoveryTransitions() {
        val now = java.util.concurrent.atomic.AtomicLong(1_000_000_000)
        val telemetry = ReceiverTelemetrySession({ true })
        TelemetryRegistry.beginReceiver(9702, telemetry)
        val transitions = ArrayList<LongArray>()
        val clock = PlaybackClock(now::get) { now.addAndGet(it * 1_000_000) }
        clock.diagnosticSession = 9702
        clock.diagnosticGeneration = 1
        clock.mappingObserved = { values, at, generation ->
            assertFalse("Publishing cannot run under the clock monitor", Thread.holdsLock(clock))
            transitions.add(values)
            TelemetryRegistry.causal(9702, CausalEvidence.Stage.MAPPING, generation, *values, atNs = at)
        }
        try {
            clock.audioExpected = true
            clock.anchorVideo(1_000_000)
            clock.prepareAudio(TrimmedAudio(0, 960, 1_000_000), 48000)
            now.addAndGet(50_000_000)
            val source = clock.anchorObservedAudio(1_000_000, 48000)
            clock.audioWritten(40_000)
            clock.observeAudio(source, 20_000, now.get())
            val before = transitions.last().toList()
            TelemetryRegistry.freezeReceiver(9702, 1)
            clock.beginAudioRecovery()
            now.incrementAndGet()
            clock.detachObservedAudio(source)
            val detached = transitions.last()
            assertEquals(CausalEvidence.Mapping.DETACH.ordinal.toLong(), detached[0])
            assertEquals(4L, detached[7])
            assertEquals(1L, detached[8])
            assertEquals(1_000_000L, detached[3])
            assertEquals(1_020_000L, detached[5])
            assertEquals(now.get(), detached[6])
            assertEquals(1_020_000L, detached[15])
            now.incrementAndGet()
            clock.videoPresented(1_990_000)
            var calls = 0
            assertFalse(clock.awaitAudioStartObserved(2_100_000, { decision ->
                TelemetryRegistry.causal(9702, CausalEvidence.Stage.START, 1,
                    55, decision.reason.toLong(), decision.mode.toLong(), decision.targetUs,
                    decision.positionUs ?: -1, decision.delayUs ?: Long.MIN_VALUE, decision.revision,
                    decision.flags, decision.waitedUs, decision.releasedVideoUs ?: -1,
                    2_100_000, -1, -1, atNs = now.get())
            }) { calls++ == 0 })
            telemetry.event(TelemetryRow(DiagnosticKind.RECOVERY.ordinal + 1, DiagnosticStage.PRESSURE.ordinal,
                2, 1, now.get(), 260000, listOf(2, 13, 49920, 14, 53760, 48, 524288, 250000, 0)), pressure = true)
            val final = checkNotNull(telemetry.requestFinal(true, System.nanoTime() + 1_000_000_000))
            val history = TelemetryHistory(true)
            history.accept(TelemetryHistory.Endpoint.RECEIVER, final.payload, 1)
            history.terminal(TelemetryHistory.Endpoint.RECEIVER, final.sequence, true, 1)
            val report = org.screencopy.receiver.protocol.StrictJson.decode(
                TelemetryReport.encode(checkNotNull(history.finish(1))))
            assertTrue(TelemetryReport.validate(report))
            val rows = SessionTelemetryProtocol.parse(report.getJSONObject("receiver").getJSONArray("blocks")
                .getJSONArray(0).getJSONObject(1).toString().toByteArray(), "receiver").events
            assertEquals(before, rows.single { it.kind == CausalEvidence.KIND &&
                it.stage == CausalEvidence.Stage.MAPPING.ordinal && it.value == 1L }.detail)
            val later = rows.filter { it.kind == CausalEvidence.KIND && it.value == 2L }
            assertEquals(listOf(CausalEvidence.Mapping.RECOVERY_BEGIN.ordinal.toLong(),
                CausalEvidence.Mapping.DETACH.ordinal.toLong(), CausalEvidence.Mapping.VIDEO_RELEASE.ordinal.toLong()),
                later.filter { it.stage == CausalEvidence.Stage.MAPPING.ordinal }.map { it.detail[0] })
            assertEquals(detached.toList(), later.single { it.stage == CausalEvidence.Stage.MAPPING.ordinal &&
                it.detail[0] == CausalEvidence.Mapping.DETACH.ordinal.toLong() }.detail)
            assertEquals(listOf(1L, 5L), later.filter { it.stage == CausalEvidence.Stage.START.ordinal }.map { it.detail[1] })
            assertTrue(later.filter { it.stage == CausalEvidence.Stage.START.ordinal }.all {
                it.detail[3] == 2_100_000L && it.detail[9] == 1_990_000L
            })
        } finally {
            TelemetryRegistry.end("receiver", telemetry)
            telemetry.requestFinal(false, System.nanoTime())
        }
    }

    @Test fun audioStartDecisionSamplesPinnedClockWhileBlockedAndTransitionsOnSourceProgress() {
        val now = java.util.concurrent.atomic.AtomicLong(1_000_000_000)
        val decisions = ArrayList<AudioStartDecision>()
        lateinit var clock: PlaybackClock
        lateinit var source: AudioSourceClock
        var sleeps = 0
        clock = PlaybackClock(now::get) {
            now.addAndGet(1_000_000_000)
            if (++sleeps == 2) clock.observeAudio(source, 200_000, now.get())
        }
        source = clock.anchorObservedAudio(1_000_000, 48000)
        clock.audioWritten(400_000)
        clock.beginAudioRecovery()
        assertTrue(clock.awaitAudioStartObserved(1_200_000, {
            assertFalse("Observers must not run under the clock monitor", Thread.holdsLock(clock))
            decisions.add(it)
        }) { true })
        assertEquals(listOf(1, 1, 4), decisions.map { it.reason })
        assertEquals(listOf(2, 2, 4), decisions.map { it.mode })
        assertEquals(listOf(0L, 1_000_000L, 2_000_000L), decisions.map { it.waitedUs })
        assertEquals(listOf(1_000_000L, 1_000_000L, 1_200_000L), decisions.map { it.positionUs })
        assertEquals(listOf(200_000L, 200_000L, 0L), decisions.map { it.delayUs })
        assertTrue(decisions.all { it.flags and 12L == 12L })
    }

    @Test fun audioStartDecisionDistinguishesPrerollStaleRecoveryAndCancellation() {
        val now = java.util.concurrent.atomic.AtomicLong(1_000_000_000)
        val clock = PlaybackClock(now::get) { now.addAndGet(2_000_000) }
        clock.audioExpected = true
        val decisions = ArrayList<AudioStartDecision>()
        assertFalse(clock.awaitAudioStartObserved(1_000_000, decisions::add) { true })
        assertEquals(2, decisions.last().reason)
        assertNull(decisions.last().positionUs)
        clock.anchorVideo(1_000_000)
        clock.beginAudioRecovery()
        clock.videoPresented(2_000_000)
        assertFalse(clock.awaitAudioStartObserved(1_000_000, decisions::add) { true })
        assertEquals(3, decisions.last().reason)
        assertEquals(2_000_000L, decisions.last().releasedVideoUs)
        assertEquals(-1_000_000L, decisions.last().delayUs)
        var calls = 0
        assertFalse(clock.awaitAudioStartObserved(2_100_000, decisions::add) { calls++ == 0 })
        assertEquals(listOf(1, 5), decisions.takeLast(2).map { it.reason })
        assertTrue(decisions.last().revision > decisions.first().revision)
    }

    @Test fun qualifiedTimestampBehindFallbackFloorDoesNotProjectTheFloorWhileCatchingUp() {
        val time = FakeTime()
        val clock = time.clock()
        val source = clock.anchorObservedAudio(1_000_000, 48000)
        val timeline = AudioPresentationTimeline(48000)
        timeline.submitted(9600, true)
        clock.audioWritten(200_000)
        var head = 5280
        var stamp: FrameTimestamp? = null
        val position = AudioPosition(48000, 9600, { time.ns }, { stamp }, { head })
        position.written(9600)
        fun publish(): AudioOutputTiming = position.current().also {
            clock.observeAudio(source, timeline.presentedUs(it.presentedFrames), time.ns, it,
                timeline.projection())
        }
        assertEquals(AudioClockSource.HEAD_UNAVAILABLE, publish().clockSource)
        assertEquals(1_110_000L, clock.currentUs())
        stamp = FrameTimestamp(480, time.ns)
        assertEquals(AudioClockSource.HEAD_WARMUP, publish().clockSource)
        time.advanceUs(10_000)
        head = 5760
        stamp = FrameTimestamp(960, time.ns)
        val qualified = publish()
        assertEquals(AudioClockSource.TIMESTAMP, qualified.clockSource)
        assertEquals(5280L, qualified.presentedFrames)
        val observed = clock.cachedPlayedAudioObservation()
        time.advanceUs(5000)
        val early = clock.videoSchedulingClock(1_200_000)
        assertEquals("The 960-frame DAC estimate must catch the 5280-frame floor before video advances",
            1_110_000L, early.positionUs)
        assertSame(observed, clock.cachedPlayedAudioObservation())
        assertEquals(5280L, publish().presentedFrames)
        time.advanceUs(85_000)
        assertEquals("At 90 ms the DAC estimate reaches, but cannot advance, the floor",
            1_110_000L, clock.videoSchedulingClock(1_200_000).positionUs)
        time.advanceUs(5000)
        assertEquals("After catch-up, project the DAC estimate, not floor plus elapsed",
            1_115_000L, clock.videoSchedulingClock(1_200_000).positionUs)
        assertEquals(1_110_000L, clock.currentUs())
        time.advanceUs(5000)
        assertEquals("Projection stops at the observed 5760 consumed frames",
            1_120_000L, clock.videoSchedulingClock(1_200_000).positionUs)
        time.advanceUs(1)
        assertEquals("Expiry cannot advance beyond previously guarded progress",
            1_120_000L, clock.videoSchedulingClock(1_200_000).positionUs)
        assertEquals("Video prediction never publishes actual audible progress", 1_110_000L,
            clock.cachedPlayedAudioSource())
        stamp = FrameTimestamp(5760, time.ns)
        assertEquals(5760L, publish().presentedFrames)
        assertEquals(1_120_000L, clock.currentUs())
    }

    @Test fun variableObservationAgeDoesNotBecomeVideoDeadlineCadenceJitter() {
        for ((firstAge, secondAge) in listOf(10_681_666L to 280_083L, 388_333L to 11_329_333L)) {
            val time = FakeTime()
            val clock = time.clock()
            val source = clock.anchorObservedAudio(1_000_000, 48000)
            clock.audioWritten(500_000)
            val deadlines = ArrayList<Long>()
            val frozenDeadlines = ArrayList<Long>()
            for ((index, age) in listOf(firstAge, secondAge).withIndex()) {
                val scheduleNs = 100_000_000L + index * 20_000_000L
                val observationNs = scheduleNs - age
                val frames = observationNs * 48000 / 1_000_000_000
                val sampleUs = frames * 1_000_000 / 48000
                val timing = AudioOutputTiming(48000, 4800, frames + 4800, frames + 2400,
                    frames, 10_000, AudioClockSource.TIMESTAMP, observationNs,
                    timestampAtNs = observationNs - 10_000_000, timestampEstimateFrames = frames)
                clock.observeAudio(source, sampleUs, observationNs, timing) { it * 1_000_000 / 48000 }
                time.ns = scheduleNs
                val pts = 1_300_000L + index * 20_000
                val decision = clock.videoSchedulingClock(pts)
                deadlines.add(decision.readAtNs + checkNotNull(decision.delayUs) * 1000)
                frozenDeadlines.add(scheduleNs + (pts - checkNotNull(clock.currentUs())) * 1000)
                assertEquals("Prediction cannot publish fake audible progress", 1_000_000 + sampleUs,
                    clock.cachedPlayedAudioSource())
                assertEquals(scheduleNs, decision.readAtNs)
            }
            val residual = deadlines[1] - deadlines[0] - 20_000_000
            assertTrue("One output-frame rounding bound, not observation-age jitter: $residual",
                kotlin.math.abs(residual) <= (1_000_000_000L + 47999) / 48000)
            assertTrue("Frozen-clock baseline reproduces >10 ms cadence error",
                kotlin.math.abs(frozenDeadlines[1] - frozenDeadlines[0] - 20_000_000) > 10_000_000)
        }
    }

    @Test fun predictionMapsActualResampledSpansSilenceAndSlewWithoutConsumingAudioHistory() {
        val time = FakeTime()
        val clock = time.clock()
        val source = clock.anchorObservedAudio(1_000_000, 48000)
        val timeline = AudioPresentationTimeline(48000)
        timeline.write(true, 1, 960, sourceUnits = { it * 1_040_000L }) { 960 }
        timeline.submitted(480, false)
        timeline.submitted(960, true)
        clock.audioPacket(0, 1_000_000)
        clock.audioPacket(10_000, 1_011_000)
        clock.audioWritten(40_800)
        clock.observeAudio(source, timeline.presentedUs(0), time.ns,
            AudioOutputTiming(48000, 2400, 2400, 2400, 0, 0, AudioClockSource.TIMESTAMP, time.ns,
                timestampAtNs = time.ns, timestampEstimateFrames = 0),
            timeline.projection())
        val anchors = source.pendingAnchors()
        time.advanceUs(20_000)
        assertEquals(1_021_016L, clock.videoSchedulingClock(1_100_000).sourcePositionUs)
        time.advanceUs(5_000)
        assertEquals("Underrun silence does not count as source audio", 1_021_016L,
            clock.videoSchedulingClock(1_100_000).sourcePositionUs)
        assertEquals("Only actual audio may apply phase slew", 0L, source.appliedOffsetUs())
        assertEquals(anchors, source.pendingAnchors())
        assertEquals(1_000_000L, clock.currentUs())
        time.advanceUs(15_000)
        assertEquals(1_031_216L, clock.videoSchedulingClock(1_100_000).sourcePositionUs)
        clock.observeAudio(source, timeline.presentedUs(1920), time.ns)
        assertEquals("Actual presentation agrees with the earlier prediction", 1_031_216L, clock.currentUs())
    }

    @Test fun predictionStopsAtKnownConsumptionTimestampValidityAndRecoveryBoundaries() {
        val time = FakeTime()
        val clock = time.clock()
        val source = clock.anchorObservedAudio(1_000_000, 48000)
        clock.audioWritten(500_000)
        fun observe(frames: Long, consumed: Long, ageUs: Long = 0,
                    kind: AudioClockSource = AudioClockSource.TIMESTAMP) {
            clock.observeAudio(source, frames * 1_000_000 / 48000, time.ns,
                AudioOutputTiming(48000, 4800, consumed + 4800, consumed, frames, ageUs, kind, time.ns,
                    timestampAtNs = time.ns - ageUs * 1000, timestampEstimateFrames = frames)
            ) { it * 1_000_000 / 48000 }
        }
        observe(0, 960)
        time.advanceUs(30_000)
        assertEquals("Never predict queued but not yet consumed frames", 1_020_000L,
            clock.videoSchedulingClock(1_200_000).positionUs)
        observe(960, 9600, 90_000)
        time.advanceUs(10_000)
        assertEquals("Remaining native timestamp validity is ten ms", 1_030_000L,
            clock.videoSchedulingClock(1_200_000).positionUs)
        time.advanceUs(10_000)
        assertEquals("Expiry retains only previously guarded progress", 1_030_000L,
            clock.videoSchedulingClock(1_200_000).positionUs)
        time.advanceUs(250_000)
        assertEquals(1_030_000L, clock.videoSchedulingClock(1_200_000).positionUs)
        observe(960, 9600, kind = AudioClockSource.HEAD_STALE)
        assertEquals("An unqualified new observation cannot rewind guarded video", 1_030_000L,
            clock.videoSchedulingClock(1_200_000).positionUs)
        observe(2400, 4800)
        clock.beginAudioRecovery()
        time.advanceUs(10_000)
        assertEquals("Recovery invalidates the old projection", 1_050_000L,
            clock.videoSchedulingClock(1_200_000).positionUs)
        clock.detachObservedAudio(source)
        val replacement = clock.anchorObservedAudio(1_050_000, 48000)
        clock.audioWritten(100_000)
        clock.endAudioRecovery()
        clock.observeAudio(source, 400_000, time.ns + 1)
        time.advanceUs(10_000)
        assertEquals("Retired epochs cannot qualify a new output", 1_050_000L,
            clock.videoSchedulingClock(1_200_000).positionUs)
        clock.observeAudio(replacement, 20_000, time.ns)
        assertEquals(1_070_000L, clock.videoSchedulingClock(1_200_000).positionUs)
    }

    @Test fun unqualifiedOrFutureEvidenceCannotStartOrRefreshPrediction() {
        for (kind in AudioClockSource.entries) {
            val time = FakeTime()
            val clock = time.clock()
            val source = clock.anchorObservedAudio(1_000_000, 48000)
            clock.audioWritten(100_000)
            val timing = AudioOutputTiming(48000, 4800, 4800, 4800, 0, 0, kind,
                positionAtNs = time.ns, timestampAtNs = time.ns, timestampEstimateFrames = 0)
            clock.observeAudio(source, 0, time.ns,
                if (kind == AudioClockSource.TIMESTAMP) timing.copy(timestampAtNs = null) else timing
            ) { it * 1_000_000 / 48000 }
            time.advanceUs(20_000)
            assertEquals("Missing native evidence and fallback heads do not establish a source rate",
                1_000_000L, clock.videoSchedulingClock(1_200_000).positionUs)
            clock.observeAudio(source, 0, time.ns, timing.copy(positionAtNs = time.ns,
                clockSource = AudioClockSource.TIMESTAMP, timestampAtNs = time.ns + 1)
            ) { it * 1_000_000 / 48000 }
            time.advanceUs(20_000)
            assertEquals(1_000_000L, clock.videoSchedulingClock(1_200_000).positionUs)
            val observation = clock.cachedPlayedAudioObservation()
            clock.observeAudio(source, 50_000, time.ns, timing.copy(positionAtNs = 0,
                clockSource = AudioClockSource.TIMESTAMP, timestampAtNs = 0)
            ) { it * 1_000_000 / 48000 }
            assertSame("Old native observation times cannot refresh the prediction",
                observation, clock.cachedPlayedAudioObservation())
        }
        val time = FakeTime()
        val clock = time.clock()
        val source = clock.anchorObservedAudio(1_000_000, 48000)
        clock.audioWritten(100_000)
        clock.observeAudio(source, 0, time.ns,
            AudioOutputTiming(48000, 4800, 4800, 4800, 0, 0, AudioClockSource.TIMESTAMP,
                positionAtNs = time.ns, timestampAtNs = time.ns, timestampEstimateFrames = 0)
        ) { it * 1_000_000 / 48000 }
        time.advanceUs(100_001)
        assertEquals("A first read after expiry must not invent past progress up to the validity boundary",
            1_000_000L, clock.videoSchedulingClock(1_200_000).positionUs)
    }

    @Test fun schedulingContextUsesCachedObservationAndKeepsPacketCorrectionSeparateFromAppliedSlew() {
        val time = FakeTime()
        val clock = time.clock()
        val source = clock.anchorObservedAudio(1_000_000, 48000)
        val initial = clock.videoSchedulingClock(1_010_000)
        assertEquals("AUDIO_INITIAL", initial.mode)
        assertNull(initial.audioObservationSequence)
        clock.audioPacket(0, 1_000_000)
        clock.audioWritten(100_000)
        val output = AudioOutputTiming(48000, 4800, 4800, 1000, 960, 12, AudioClockSource.TIMESTAMP)
        clock.observeAudio(source, 20_000, time.ns, output)
        val fresh = clock.videoSchedulingClock(1_030_000)
        assertEquals("CACHED_AUDIO", fresh.mode)
        assertEquals(10_000L, fresh.delayUs)
        assertEquals(AudioClockSource.TIMESTAMP, fresh.audioOutputSource)
        assertEquals(12L, fresh.audioTimestampAgeUs)
        clock.audioPacket(50_000, 1_051_000)
        time.advanceUs(250_000)
        val stale = clock.videoSchedulingClock(1_030_000)
        assertEquals(fresh.audioObservationSequence, stale.audioObservationSequence)
        assertEquals(fresh.audioObservationAtNs, stale.audioObservationAtNs)
        assertEquals(fresh.positionUs, stale.positionUs)
        assertEquals(250_000_000L, stale.readAtNs - checkNotNull(stale.audioObservationAtNs))
        assertEquals(0L, stale.observedAppliedOffsetUs)
        assertEquals(1000L, stale.lastPacketCorrection?.deltaUs)
        assertTrue(stale.mappingRevision > fresh.mappingRevision)
        clock.observeAudio(source, 80_000, time.ns, output.copy(clockSource = AudioClockSource.HEAD_STALE))
        val next = clock.videoSchedulingClock(1_090_000)
        assertEquals(600L, next.observedAppliedOffsetUs)
        assertEquals(AudioClockSource.HEAD_STALE, next.audioOutputSource)
        assertNotEquals(stale.audioObservationSequence, next.audioObservationSequence)
        clock.observeAudio(source, 90_000, time.ns - 1)
        assertEquals(next, clock.videoSchedulingClock(1_090_000))
    }

    @Test fun schedulingContextLabelsUnavailableFallbackAndMonotonicGuardWithoutInventingAudio() {
        val time = FakeTime()
        val clock = time.clock()
        assertEquals("UNAVAILABLE", clock.videoSchedulingClock(1).mode)
        assertNull(clock.videoSchedulingClock(1).positionUs)
        clock.anchorVideo(1_000_000)
        val monotonic = clock.videoSchedulingClock(1_000_000)
        assertEquals("MONOTONIC", monotonic.mode)
        assertNull(monotonic.audioOutputSource)
        time.advanceUs(20_000)
        clock.currentUs()
        val source = clock.anchorObservedAudio(1_019_000, 48000)
        val guarded = clock.videoSchedulingClock(1_030_000)
        assertEquals(1_019_000L, guarded.sourcePositionUs)
        assertEquals(1_020_000L, guarded.positionUs)
        clock.beginAudioRecovery()
        clock.detachObservedAudio(source)
        val detached = clock.videoSchedulingClock(1_030_000)
        assertEquals("MONOTONIC", detached.mode)
        assertTrue(detached.recoveringAudio)
        assertNull(detached.audioObservationSequence)
        assertNull(detached.observedAppliedOffsetUs)
        assertTrue(detached.mappingRevision > guarded.mappingRevision)
        clock.anchorAudio(1_020_000) { 0 }
        assertEquals("CALLBACK_AUDIO", clock.videoSchedulingClock(1_030_000).mode)
        assertNull(clock.videoSchedulingClock(1_030_000).audioOutputSource)
    }

    @Test fun publishedAudioKeepsItsSourceMappingAndObservationTimeWithoutExtrapolation() {
        val time = FakeTime()
        val clock = time.clock()
        val source = clock.anchorObservedAudio(1_000_000, 48000)
        assertFalse("An unobserved audio clock cannot prove video freshness", clock.canProveVideo(1_000_000, null))
        clock.audioPacket(0, 1_000_000)
        clock.audioWritten(100_000)
        clock.observeAudio(source, 20_000, time.ns)
        assertEquals(1_020_000L, clock.currentUs())
        clock.audioPacket(50_000, 1_051_000)
        time.advanceUs(60_000)
        clock.observeAudio(source, 80_000, time.ns)
        assertEquals(1_080_600L, clock.currentUs())
        val observed = checkNotNull(clock.cachedPlayedAudioObservation())
        clock.beginAudioRecovery()
        time.advanceUs(250_001)
        clock.videoPresented(2_000_000)
        repeat(10) {
            assertEquals("Attached audio remains sample-master even when its observation is stale",
                1_080_600L, clock.currentUs())
            assertSame(observed, clock.cachedPlayedAudioObservation())
            assertFalse("Unchanged audio observations must not prove a recovery key fresh",
                clock.canProveVideo(1_080_600, observed))
        }
        clock.observeAudio(source, 90_000, observed.observedAtNs - 1)
        assertSame("Out-of-order observations cannot refresh or rewind the clock",
            observed, clock.cachedPlayedAudioObservation())
        clock.observeAudio(source, 90_000, time.ns)
        assertEquals(1_090_800L, clock.currentUs())
        assertTrue(clock.canProveVideo(1_090_800, observed))
        clock.detachObservedAudio(source)
        time.advanceUs(20_000)
        assertEquals(1_110_800L, clock.currentUs())
        val replacement = clock.anchorObservedAudio(1_110_800, 48000)
        clock.audioWritten(20_000)
        clock.observeAudio(replacement, 10_000, time.ns)
        val replacementObservation = clock.cachedPlayedAudioObservation()
        clock.observeAudio(source, 100_000, time.ns + 1)
        clock.detachObservedAudio(source)
        assertSame(replacementObservation, clock.cachedPlayedAudioObservation())
        assertEquals(1_120_800L, clock.currentUs())
    }

    @Test(timeout = 5000) fun blockedCallbackDoesNotOwnClockMonitorOrPublishIntoReplacement() {
        val time = FakeTime()
        val clock = time.clock()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        clock.anchorAudio(1_000_000) {
            if (calls.incrementAndGet() == 1) {
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
                50_000
            } else 10_000
        }
        clock.audioWritten(100_000)
        val workers = Executors.newFixedThreadPool(2)
        val reading = workers.submit<Long?> { clock.currentUs() }
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            workers.submit {
                clock.beginAudioRecovery()
                clock.anchorVideo(1_000_000)
                clock.detachAudio()
            }.get(1, TimeUnit.SECONDS)
            val replacement = clock.anchorObservedAudio(1_010_000, 48000)
            clock.audioWritten(20_000)
            clock.observeAudio(replacement, 5000, time.ns)
            val observed = clock.cachedPlayedAudioObservation()
            release.countDown()
            assertEquals(1_015_000L, reading.get(1, TimeUnit.SECONDS))
            assertSame("The blocked retired callback cannot publish into the replacement",
                observed, clock.cachedPlayedAudioObservation())
        } finally {
            release.countDown()
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(1, TimeUnit.SECONDS))
        }
    }

    @Test fun previouslyAnchoredAudioFollowsPresentedVideoOnlyWhileActuallyDetached() {
        val time = FakeTime()
        val clock = time.clock().apply { audioExpected = true }
        clock.anchorVideo(1_000_000)
        clock.prepareAudio(TrimmedAudio(0, 960, 1_000_000), 48000)
        assertTrue(clock.awaitAudioStart(1_000_000) { true })
        clock.anchorAudio(1_000_000) { 20_000 }
        clock.audioWritten(40_000)
        clock.beginAudioRecovery()
        clock.videoPresented(2_000_000)
        assertEquals("Attached audio remains master even during recovery", 1_020_000L, clock.currentUs())
        clock.detachAudio()
        val before = time.ns
        clock.videoPresented(2_000_000)
        assertEquals(2_000_000L, clock.currentUs())
        clock.videoPresented(1_500_000)
        assertEquals("Older video cannot rewind the detached clock", 2_000_000L, clock.currentUs())
        val slice = TrimmedAudio(0, 960, 2_000_000)
        assertEquals(slice, clock.prepareAudio(slice, 48000))
        assertTrue(clock.awaitAudioStart(slice.audiblePtsUs) { true })
        assertEquals("Fresh source audio must not wait behind the retired audio clock", before, time.ns)
        clock.anchorAudio(slice.audiblePtsUs) { 0 }
        clock.videoPresented(3_000_000)
        assertEquals("New audio is master before completion too", 2_000_000L, clock.currentUs())
        clock.endAudioRecovery()
        clock.checkStartup()
    }

    @Test fun videoFirstPlaysBeforeAudioWithThreeHundredMillisecondSourceOffset() {
        val time = FakeTime()
        val clock = time.clock()
        val first = 8_000_000_000L
        clock.anchorVideo(first)
        assertEquals(0L, clock.delayUs(first))
        assertEquals(300_000L, clock.audioStartDelayUs(first + 300_000))
        time.advanceUs(150_000)
        assertEquals(0L, clock.delayUs(first + 150_000))
        assertEquals(150_000L, clock.audioStartDelayUs(first + 300_000))
        time.advanceUs(150_000)
        val beforeHandover = clock.currentUs()
        var playedUs = 0L
        clock.anchorAudio(first + 300_000) { playedUs }
        clock.audioWritten(40_000)
        assertEquals(beforeHandover, clock.currentUs())
        playedUs = 20_000
        assertEquals(first + 320_000, clock.currentUs())
    }

    @Test fun delayedOpusAudioIsScheduledAtItsFirstAudibleTimestampIncludingPreskip() {
        val time = FakeTime()
        val clock = time.clock()
        clock.anchorVideo(1_000_000)
        val audio = StartupTrim(312, 48000).apply(960, 1_300_000)
        assertEquals(1_306_500L, audio.audiblePtsUs)
        assertEquals(306_500L, clock.audioStartDelayUs(audio.audiblePtsUs))
        time.advanceUs(306_500)
        val beforeHandover = clock.currentUs()
        clock.anchorAudio(audio.audiblePtsUs) { 0 }
        assertEquals(beforeHandover, clock.currentUs())
    }

    @Test fun earlierVideoDoesNotRewindAnAlreadyRunningMonotonicAnchor() {
        val time = FakeTime()
        val clock = time.clock()
        assertEquals(0L, clock.audioStartDelayUs(1_300_000))
        time.advanceUs(20_000)
        clock.anchorVideo(1_000_000)
        assertEquals(-20_000L, clock.audioStartDelayUs(1_300_000))
        assertEquals(1_320_000L, clock.currentUs())
        assertEquals(0L, clock.videoDelayUs(1_000_000))
    }

    @Test fun startupWaitIsCancellableAndBoundedWithoutDroppingVideo() {
        val time = FakeTime()
        val clock = time.clock()
        clock.anchorVideo(1_000_000)
        assertTrue(clock.awaitAudioStart(1_300_000) { true })
        assertEquals(1_300_000L, clock.currentUs())
        assertEquals(300_000_000L, time.ns)
        assertFalse(clock.awaitAudioStart(1_400_000) { false })
        assertEquals(300_000_000L, time.ns)
        assertThrows(PlaybackException::class.java) { clock.audioStartDelayUs(5_000_000) }
    }

    @Test fun audioCannotBecomeMasterBeforeItsPresentationTime() {
        val time = FakeTime()
        val clock = time.clock()
        clock.anchorVideo(1_000_000)
        assertThrows(IllegalStateException::class.java) { clock.anchorAudio(1_300_000) { 0 } }
        assertEquals(1_000_000L, clock.currentUs())
    }

    @Test fun unchangedAudioClockSurvivesVideoGenerationAndAudioDetachStaysContinuous() {
        val time = FakeTime()
        val clock = time.clock()
        assertTrue(clock.awaitAudioStart(1_000_000) { true })
        var audioUs = 20_000L
        clock.anchorAudio(1_000_000) { audioUs }
        clock.audioWritten(100_000)
        clock.anchorVideo(1_200_000)
        assertEquals(1_020_000L, clock.currentUs())
        audioUs = 40_000
        clock.detachAudio()
        assertEquals(1_040_000L, clock.currentUs())
        time.advanceUs(20_000)
        assertEquals(1_060_000L, clock.currentUs())
    }

    @Test fun videoOnlyStillUsesItsMonotonicAnchorAndLateAudioFailsExplicitly() {
        val time = FakeTime()
        val clock = time.clock()
        assertNull(clock.currentUs())
        clock.anchorVideo(1_000_000)
        time.advanceUs(580_000)
        assertEquals(1_580_000L, clock.currentUs())
        assertThrows(PlaybackException::class.java) { clock.audioStartDelayUs(1_000_000) }
    }

    @Test fun recoveringAudioKeepsTheSourceTimelineAndTemporaryOffsetIsNotFatal() {
        val time = FakeTime()
        val clock = time.clock()
        clock.anchorVideo(1_000_000)
        clock.anchorAudio(1_000_000) { 20_000 }
        clock.audioWritten(40_000)
        clock.detachAudio()
        assertEquals(1_020_000L, clock.currentUs())
        time.advanceUs(500_000)
        assertFalse("Stale samples must be retrimmed, not fail or rewind the source clock",
            clock.awaitAudioStart(1_000_000) { true })
        assertEquals(1_520_000L, clock.currentUs())
        assertTrue(clock.awaitAudioStart(5_020_000) { true })
        assertEquals(5_020_000L, clock.currentUs())
        clock.anchorAudio(5_020_000) { 0 }
        assertEquals(5_020_000L, clock.currentUs())
    }

    @Test fun resumedOutputCannotRewindTheClockWhenStartupSleepOvershootsItsSamplePts() {
        val time = FakeTime()
        val clock = time.clock()
        clock.anchorVideo(1_000_000)
        clock.anchorAudio(1_000_000) { 20_000 }
        clock.audioWritten(40_000)
        assertEquals(1_020_000L, clock.currentUs())
        clock.detachAudio()
        time.advanceUs(500_000)
        assertEquals(1_520_000L, clock.currentUs())
        var presented = 0L
        clock.anchorAudio(1_519_000) { presented }
        assertEquals(1_520_000L, clock.currentUs())
        clock.audioWritten(20_000)
        presented = 2_000
        assertEquals(1_521_000L, clock.currentUs())
    }

    @Test fun defaultVideoNeverWaitsForTheAudioSourceClockEvenAfterPreroll() {
        val time = FakeTime()
        val clock = time.clock().apply { audioExpected = true }
        clock.anchorVideo(1_000_000)
        val slice = checkNotNull(clock.prepareAudio(TrimmedAudio(0, 960, 1_300_000), 48000))
        time.advanceUs(80_000)
        var playedUs = 0L
        clock.anchorAudio(slice.audiblePtsUs) { playedUs }
        clock.audioWritten(3_000_000)
        assertEquals(0L, clock.videoDelayUs(1_000_000))
        time.advanceUs(3_000_000)
        playedUs = 3_000_000
        assertEquals(0L, clock.videoDelayUs(1_000_000))
        assertEquals(0L, clock.videoDelayUs(9_000_000))
    }

    @Test fun recoveringBeforeFirstAudioUsesItsOwnStartupStateWithoutRebasingVideo() {
        val time = FakeTime()
        val clock = time.clock().apply { audioExpected = true; recoveryStartup = true }
        clock.anchorVideo(1_000_000)
        time.advanceUs(2_000_000)
        clock.beginAudioRecovery()
        clock.recoveryStartup = false
        clock.detachAudio()
        time.advanceUs(2_000_000)
        clock.checkStartup()
        assertEquals(5_000_000L, clock.currentUs())
        val slice = TrimmedAudio(0, 960, 5_000_000)
        assertEquals(slice, clock.prepareAudio(slice, 48000))
        assertEquals(5_000_000L, clock.currentUs())
        assertTrue(clock.awaitAudioStart(slice.audiblePtsUs) { true })
        clock.anchorAudio(slice.audiblePtsUs) { 0 }
        clock.endAudioRecovery()
        clock.checkStartup()
        assertEquals(5_000_000L, clock.currentUs())
    }

    @Test fun recoveringAudioCanInitializeAnUnanchoredClockWithoutWaitingForVideo() {
        val time = FakeTime()
        val clock = time.clock().apply { audioExpected = true }
        clock.beginAudioRecovery()
        assertTrue(clock.canPrepareAudio())
        val slice = TrimmedAudio(0, 960, 9_000_000)
        assertEquals(slice, clock.prepareAudio(slice, 48000))
        assertTrue(clock.awaitAudioStart(slice.audiblePtsUs) { true })
        clock.anchorAudio(slice.audiblePtsUs) { 0 }
        clock.endAudioRecovery()
        clock.anchorVideo(7_000_000)
        assertEquals(9_000_000L, clock.currentUs())
    }

    @Test fun ordinaryInitialStartupRetainsTheThreeSecondDeadline() {
        val time = FakeTime()
        val clock = time.clock().apply { audioExpected = true }
        clock.anchorVideo(1_000_000)
        time.advanceUs(2_999_999)
        clock.checkStartup()
        time.advanceUs(1)
        assertThrows(PlaybackException::class.java) { clock.checkStartup() }
    }

    @Test fun freshVideoPresentationBootstrapsRecoveringAudioWithoutPreservingAnOldArrivalDelay() {
        val time = FakeTime()
        val clock = time.clock().apply { audioExpected = true }
        time.advanceUs(300_000)
        clock.anchorVideo(1_000_000)
        time.advanceUs(300_000)
        clock.beginAudioRecovery()
        assertEquals(1_300_000L, clock.currentUs())
        clock.videoPresented(1_600_000)
        assertEquals(1_600_000L, clock.currentUs())
        val slice = TrimmedAudio(0, 960, 1_600_000)
        assertEquals(slice, clock.prepareAudio(slice, 48000))
        assertTrue(clock.awaitAudioStart(slice.audiblePtsUs) { true })
        assertEquals(600_000_000L, time.ns)
        clock.anchorAudio(slice.audiblePtsUs) { 0 }
        clock.endAudioRecovery()
        clock.videoPresented(1_900_000)
        assertEquals(1_600_000L, clock.currentUs())
    }

    @Test fun scheduledVideoPresentationKeepsRecoveryAudioAlignedUntilItsDeadline() {
        val time = FakeTime()
        val clock = time.clock().apply { audioExpected = true }
        time.advanceUs(300_000)
        clock.anchorVideo(1_000_000)
        time.advanceUs(300_000)
        clock.beginAudioRecovery()
        val deadlineNs = time.ns + 300_000_000
        clock.videoPresented(1_600_000, deadlineNs)
        assertEquals(1_300_000L, clock.currentUs())
        assertEquals(300_000L, clock.audioStartDelayUs(1_600_000))
        time.advanceUs(300_000)
        assertEquals(1_600_000L, clock.currentUs())
        assertEquals(0L, clock.audioStartDelayUs(1_600_000))
    }

    private class FakeTime {
        var ns = 0L
        fun advanceUs(us: Long) { ns += us * 1000 }
        fun clock() = PlaybackClock({ ns }, { ms -> ns += ms * 1_000_000 })
    }
}

package org.screencopy.receiver.media

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PlaybackClockTest {
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

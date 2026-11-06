package org.screencopy.receiver.media

import org.junit.Assert.*
import org.junit.Test

class AudioPositionTest {
    @Test fun clientConsumptionExcludes129msHardwareTransitFromBoundedOccupancy() {
        val trace = Trace()
        trace.write(6240)
        trace.head = 480
        trace.stamp = FrameTimestamp(0, trace.now)
        trace.position.current()
        trace.write(480)
        trace.now += 140_000_000
        trace.head = 6720
        trace.stamp = FrameTimestamp(528, trace.now)
        val reading = trace.position.current()
        assertEquals(0L, reading.bufferedUs)
        assertEquals(528L, reading.presentedFrames)
        assertEquals(6192L, reading.consumedFrames - reading.presentedFrames)
        assertEquals(AudioClockSource.TIMESTAMP, reading.clockSource)
    }

    @Test fun staleTrueTimestampUsesAdvancingConsumptionAndPreservesLearnedHardwareTransit() {
        val trace = qualified()
        val previous = trace.position.current()
        trace.now += 200_000_000
        trace.write(6240)
        trace.head += 6240
        val reading = trace.position.current()
        assertEquals(AudioClockSource.HEAD_STALE, reading.clockSource)
        assertEquals(200_000L, reading.timestampAgeUs)
        assertEquals(0L, reading.bufferedUs)
        assertEquals(previous.presentedFrames + 6240, reading.presentedFrames)
        assertEquals(6192L, reading.consumedFrames - reading.presentedFrames)
    }

    @Test fun timestampLossAndRecoveryDoNotJumpFromDacToClientClock() {
        val trace = qualified()
        val before = trace.position.current()
        trace.write(480)
        trace.head += 480
        trace.now += 10_000_000
        trace.stamp = null
        val absent = trace.position.current()
        assertEquals(AudioClockSource.HEAD_UNAVAILABLE, absent.clockSource)
        assertEquals(before.presentedFrames + 480, absent.presentedFrames)
        trace.write(480)
        trace.head += 480
        trace.now += 10_000_000
        trace.stamp = FrameTimestamp(trace.head - 6192, trace.now)
        val recovered = trace.position.current()
        assertEquals(AudioClockSource.TIMESTAMP, recovered.clockSource)
        assertEquals(absent.presentedFrames + 480, recovered.presentedFrames)
    }

    @Test fun futureNegativeAndRegressingTimestampReportsCannotRewindOrAdvanceBeyondConsumption() {
        val trace = qualified()
        var previous = trace.position.current().presentedFrames
        for (invalid in listOf(FrameTimestamp(-1, trace.now), FrameTimestamp(6000, trace.now + 1_000_000),
            FrameTimestamp(100, trace.now - 1_000_000), FrameTimestamp(100_000, trace.now))) {
            trace.stamp = invalid
            val reading = trace.position.current()
            assertEquals(AudioClockSource.HEAD_INVALID, reading.clockSource)
            assertTrue(reading.presentedFrames >= previous)
            assertTrue(reading.presentedFrames <= reading.consumedFrames)
            previous = reading.presentedFrames
        }
    }

    @Test fun advancingTimesWithUnchangedFramesAreWarmupNotAQualifiedPresentationClock() {
        val trace = Trace()
        trace.write(6240)
        trace.head = 100
        repeat(5) {
            trace.stamp = FrameTimestamp(0, trace.now)
            val reading = trace.position.current()
            assertEquals(AudioClockSource.HEAD_WARMUP, reading.clockSource)
            assertEquals(100L, reading.presentedFrames)
            trace.now += 10_000_000
        }
    }

    @Test fun playbackHeadRegressionOrUnexpectedResetIsFatalNotA32bitWrap() {
        for (next in listOf(0, 479)) {
            val trace = Trace()
            trace.write(6240)
            trace.head = 480
            trace.position.current()
            trace.head = next.toLong()
            val error = assertThrows(AudioClockException::class.java) { trace.position.current() }
            assertEquals(AudioClockFault.HEAD_REGRESSION, error.diagnostic.fault)
            assertEquals(6240L, error.diagnostic.timing.submittedFrames)
            assertEquals(next - 480L, error.diagnostic.headDeltaFrames)
        }
    }

    @Test fun unsignedPlaybackHeadAndTimestampWrapKeepContinuousFrameDomains() {
        val trace = Trace()
        // Poll at sub-half-range intervals, as in a long-lived stream, without allocating media.
        repeat(4) {
            trace.write(1_073_741_824)
            trace.head += 1_073_741_824
            trace.stamp = FrameTimestamp((trace.head - 480) and 0xffffffffL, trace.now)
            trace.position.current()
            trace.now += 1_000_000
        }
        trace.write(480)
        trace.head += 480
        trace.stamp = FrameTimestamp((trace.head - 480) and 0xffffffffL, trace.now)
        val reading = trace.position.current()
        assertEquals(4_294_967_776L, reading.consumedFrames)
        assertEquals(4_294_967_296L, reading.presentedFrames)
        assertEquals(0L, reading.bufferedUs)
        assertEquals(AudioClockSource.TIMESTAMP, reading.clockSource)
    }

    @Test fun impossibleConsumptionSubmissionAndClientOverflowRemainExplicitFailures() {
        val ahead = Trace()
        ahead.head = 1
        assertEquals(AudioClockFault.HEAD_AHEAD,
            assertThrows(AudioClockException::class.java) { ahead.position.current() }.diagnostic.fault)
        val full = Trace()
        full.write(6241)
        val error = assertThrows(AudioClockException::class.java) { full.position.current() }
        assertEquals(AudioClockFault.CLIENT_BUFFER, error.diagnostic.fault)
        assertEquals(130020L, error.diagnostic.timing.bufferedUs)
        assertEquals(AudioClockFault.SUBMISSION,
            assertThrows(AudioClockException::class.java) { Trace().write(-1) }.diagnostic.fault)
    }

    @Test fun shortTimestampExtrapolationUsesPreparedSpeedAndNeverPassesConsumedFrames() {
        val trace = qualified()
        trace.position.speed(1.005f)
        trace.now += 10_000_000
        val reading = trace.position.current()
        assertEquals(528L + 482, reading.presentedFrames)
        trace.now += 90_000_000
        assertTrue(trace.position.current().presentedFrames <= trace.head)
    }

    private fun qualified(): Trace = Trace().apply {
        write(6240)
        head = 480
        stamp = FrameTimestamp(0, now)
        position.current()
        write(480)
        head = 6720
        now += 140_000_000
        stamp = FrameTimestamp(528, now)
        position.current()
    }

    private class Trace {
        var now = 1_000_000_000L
        var head = 0L
        var stamp: FrameTimestamp? = null
        val position = AudioPosition(48000, 6240, { now }, { stamp }, { head.toInt() })
        fun write(frames: Int) = position.written(frames)
    }
}

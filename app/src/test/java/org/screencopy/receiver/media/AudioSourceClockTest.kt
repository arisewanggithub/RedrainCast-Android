package org.screencopy.receiver.media

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class AudioSourceClockTest {
    @Test fun exact64112CorrectionIsAppliedAtPlayedSamplesWithTwoPercentMonotonicSlew() {
        val source = AudioSourceClock(8_000_000_000L, 48000)
        source.packet(0, 8_000_000_000L)
        source.written(8_000_000)
        source.packet(200_000, 8_000_264_112L)
        assertEquals(8_000_100_000L, source.current(100_000))
        assertEquals(8_000_200_000L, source.current(200_000))
        assertEquals(8_000_710_000L, source.current(700_000))
        var previous = source.current(700_000)
        for (sample in 720_000L..8_000_000L step 20_000) {
            val current = source.current(sample)
            assertTrue(current - previous in 19_600..20_400)
            previous = current
        }
        assertEquals(8_008_064_112L, previous)
        assertEquals(1, source.pendingAnchors())
    }

    @Test fun overlappingAndMultipleNegativeRefreshesNeverMakeThePresentationClockGoBackwards() {
        val source = AudioSourceClock(1_000_000, 48000)
        source.packet(0, 1_000_000)
        source.written(8_000_000)
        for ((position, offset) in listOf(20_000L to -16_000L, 40_000L to -32_000L,
            60_000L to -48_000L, 80_000L to -64_112L)) {
            source.packet(position, 1_000_000 + position + offset)
        }
        var previous = 1_000_000L
        for (position in 20_000L..8_000_000L step 20_000) {
            val current = source.current(position)
            assertTrue(current - previous in 19_600..20_400)
            previous = current
        }
        assertEquals(8_935_888L, previous)
    }

    @Test fun submittedSamplesBoundExtrapolationAndPresentedCounterCorrectionsCannotRewind() {
        val source = AudioSourceClock(1_000_000, 48000)
        assertEquals(1_000_000L, source.current(80_000))
        source.packet(0, 1_000_000)
        source.written(120_000)
        assertEquals(1_120_000L, source.current(500_000))
        assertEquals(1_120_000L, source.current(80_000))
        assertEquals(0L, source.bufferedUs())
        assertThrows(IllegalArgumentException::class.java) { source.current(-1) }
        assertThrows(PlaybackException::class.java) { source.packet(100_000, 1_100_000) }
    }

    @Test fun gradualClockRateDifferenceIsFollowedWithoutAnAbsolutePhaseThreshold() {
        val source = AudioSourceClock(1_000_000, 48000)
        source.packet(0, 1_000_000)
        for (index in 1..1000) {
            val position = index * 20_000L
            source.written(position)
            source.packet(position, 1_000_000 + position + position / 500)
            val mapped = source.current(position)
            assertTrue(abs(mapped - (1_000_000 + position + position / 500)) <= 400)
            assertTrue(source.pendingAnchors() <= 2)
        }
    }

    @Test fun correctionDiagnosticsContainOnlyRelativeEpochValues() {
        val source = AudioSourceClock(8_000_000_000L, 48000)
        source.packet(0, 8_000_000_000L)
        val correction = checkNotNull(source.packet(200_000, 8_000_264_112L))
        assertEquals(64_112L, correction.deltaUs)
        assertEquals(0L, correction.beforeOffsetUs)
        assertEquals(64_112L, correction.afterOffsetUs)
        assertFalse(correction.logMessage().contains("8000000000"))
        assertTrue(correction.logMessage().contains("sequence=2"))
    }

    @Test fun outputHistoryIsBoundedAndInvalidSampleAccountingStillFails() {
        val source = AudioSourceClock(1_000_000, 48000)
        source.packet(0, 1_000_000)
        for (index in 1..12001) source.packet(index * 20L, 1_000_000 + index * 21L)
        assertThrows(PlaybackException::class.java) { source.packet(240_040, 1_252_042) }
        assertThrows(PlaybackException::class.java) { source.packet(0, 1_500_000) }
        source.written(100)
        assertThrows(IllegalArgumentException::class.java) { source.written(99) }
    }
}

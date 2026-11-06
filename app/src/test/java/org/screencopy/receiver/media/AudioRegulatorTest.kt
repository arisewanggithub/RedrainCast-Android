package org.screencopy.receiver.media

import org.junit.Assert.*
import org.junit.Test

class AudioRegulatorTest {
    private val rate = 48000
    private fun packet(fifo: AudioRegulator, frames: Int, pts: Long = 0): AudioCompensation? {
        fifo.offer(ShortArray(frames * 2) { 7 }, TrimmedAudio(0, frames, pts), observe = false)
        return fifo.converted(frames, frames)
    }

    @Test fun fiftyMsTargetAndStartupOverflowKeepNewestDecodedSamplesAndPts() {
        val fifo = AudioRegulator(rate, 2)
        packet(fifo, 960, 1_000_000)
        assertNull(fifo.peek(240))
        packet(fifo, 4800, 1_020_000)
        assertTrue(fifo.started)
        assertEquals(2880, fifo.bufferedFrames)
        val first = checkNotNull(fifo.peek(240))
        assertEquals(1_060_000L, first.ptsUs)
        assertEquals(2880L, fifo.diagnostic().droppedFrames)
    }

    @Test fun recurring200To250msGapsRecoverWithin115msWithoutDiscardOnUnderrun() {
        for (gapMs in listOf(200, 219, 225, 250)) {
            val fifo = AudioRegulator(rate, 2)
            packet(fifo, 2400)
            repeat(20) { cycle ->
                while (fifo.bufferedFrames > 0) fifo.consumed(minOf(240, fifo.bufferedFrames))
                val previousDrops = fifo.diagnostic().droppedFrames
                repeat(gapMs / 5) {
                    fifo.insertedSilence(240)
                }
                assertEquals("Underrun is not an instruction to discard delayed audio", previousDrops, fifo.diagnostic().droppedFrames)
                repeat((gapMs + 19) / 20) { index ->
                    packet(fifo, 960, (cycle * 300_000L) + index * 20_000)
                    assertTrue(fifo.bufferedFrames <= 5520)
                }
                repeat(20) {
                    fifo.consumed(minOf(240, fifo.bufferedFrames))
                    packet(fifo, 240)?.let {
                        assertTrue(kotlin.math.abs(it.samples) <= rate * 4 / 50)
                    }
                }
                assertTrue(fifo.bufferedFrames <= 5520)
            }
            val result = fifo.diagnostic()
            assertTrue(result.recoveries >= 20)
            assertTrue(result.silenceFrames >= rate * gapMs / 1000L * 19)
            assertTrue(result.droppedFrames > 0)
            fifo.clear()
            assertEquals(0, fifo.bufferedFrames)
        }
    }

    @Test fun smoothedFourSecondCompensationIsBoundedAndNeverAcceleratesBelowTarget() {
        val fifo = AudioRegulator(rate, 2)
        packet(fifo, 2400)
        fifo.insertedSilence(48000)
        var correction: AudioCompensation? = null
        repeat(50) {
            fifo.consumed(960)
            packet(fifo, 960)?.let { correction = it }
        }
        assertEquals(AudioCompensation(-3840, 192000), correction)
        fifo.consumed(2160)
        repeat(50) { fifo.converted(960, 960)?.let { correction = it } }
        assertEquals(AudioCompensation(0, 0), correction)
        assertEquals(240, fifo.bufferedFrames)
    }

    @Test fun compensationUsesDecodedOccupancyNotCaptureTimestampPhase() {
        val first = AudioRegulator(rate, 2)
        val second = AudioRegulator(rate, 2)
        packet(first, 2400, 1_000_000)
        packet(second, 2400, 2_000_000)
        repeat(100) {
            first.consumed(240); second.consumed(240)
            assertEquals(packet(first, 240, 1_000_000 + it * 5000L),
                packet(second, 240, 2_000_000 - it * 1000L))
        }
    }

    @Test fun occupancyAverageFollowsDecodedProductionNotEmptyOutputPolling() {
        val fifo = AudioRegulator(rate, 2)
        packet(fifo, 2400)
        fifo.consumed(2400)
        val before = fifo.diagnostic().averageFrames
        repeat(200) { fifo.diagnostic() }
        assertEquals("Consumer polling must not repeatedly bias the average toward empty",
            before, fifo.diagnostic().averageFrames, 0.0)
        packet(fifo, 960)
        assertEquals(before + (960 - before) / 128, fifo.diagnostic().averageFrames, 0.0)
    }

    @Test fun insertedSilenceNeverAdvancesPresentedSourceSamples() {
        val timeline = AudioPresentationTimeline(rate)
        timeline.submitted(480, true)
        timeline.submitted(10560, false)
        timeline.submitted(480, true)
        assertEquals(10_000L, timeline.presentedUs(480))
        assertEquals(10_000L, timeline.presentedUs(480 + 5000))
        assertEquals(10_000L, timeline.presentedUs(11040))
        assertEquals(20_000L, timeline.presentedUs(11520))
        timeline.clear()
    }
}

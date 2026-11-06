package org.screencopy.receiver.media

import org.junit.Assert.*
import org.junit.Test

class ResampledAudioTest {
    @Test fun filterLookaheadRetainsTheOriginalPacketBoundariesAndPts() {
        val mapping = ResampledAudioTimeline(48000)
        mapping.offer(960, 1_000_000)
        val first = mapping.output(943, 17)
        assertEquals(listOf(MappedPcm(0, 943, 1_000_000, 943_000_000)), first)
        mapping.offer(960, 1_020_000)
        val next = mapping.output(960, 17)
        assertEquals(listOf(MappedPcm(0, 17, 1_019_645, 17_000_000),
            MappedPcm(17, 943, 1_020_000, 943_000_000)), next)
    }

    @Test fun zeroOutputDoesNotLoseInputOrResamplerHistory() {
        val mapping = ResampledAudioTimeline(48000)
        mapping.offer(1, 1_000_000)
        assertTrue(mapping.output(0, 1).isEmpty())
        mapping.offer(960, 1_000_020)
        val result = mapping.output(944, 17)
        assertEquals(944, result.sumOf { it.frames })
        assertEquals(944_000_000L, result.sumOf { it.sourceUnits })
        assertEquals(1_000_000L, result.first().ptsUs)
    }

    @Test fun convertedFrameCountDoesNotChangeSourceDurationOrPresentationMapping() {
        val mapping = ResampledAudioTimeline(48000)
        mapping.offer(960, 1_000_000)
        val block = mapping.output(980, 0).single()
        assertEquals(960_000_000L, block.sourceUnits)
        val fifo = AudioRegulator(48000, 2)
        fifo.offer(ShortArray(1960), TrimmedAudio(0, 980, block.ptsUs), block.sourceUnits)
        fifo.startAfterPreroll()
        val presentation = AudioPresentationTimeline(48000)
        repeat(4) {
            presentation.write(true, 2, 245, fifo::sourceUnits) { 490 }
            fifo.consumed(245)
        }
        assertEquals(10_000L, presentation.presentedUs(490))
        assertEquals(20_000L, presentation.presentedUs(980))
        presentation.submitted(240, false)
        assertEquals(20_000L, presentation.presentedUs(1220))
    }

    @Test fun partialWritesKeepFractionalSourceAccountingRatherThanRoundingEachChunk() {
        val fifo = AudioRegulator(48000, 2)
        fifo.offer(ShortArray(1960), TrimmedAudio(0, 980, 1_000_000), 960_000_000)
        fifo.startAfterPreroll()
        var sourceUnits = 0L
        repeat(980) {
            sourceUnits += fifo.sourceUnits(1)
            fifo.consumed(1)
        }
        assertEquals(960_000_000L, sourceUnits)
        assertEquals(0, fifo.bufferedFrames)
    }

    @Test fun invalidFilterDelayCannotInventSourceSamples() {
        val mapping = ResampledAudioTimeline(48000)
        mapping.offer(960, 1_000_000)
        assertThrows(PlaybackException::class.java) { mapping.output(960, 960) }
        assertThrows(PlaybackException::class.java) { mapping.output(1, 5761) }
    }

    @Test fun downsamplingATinyBoundarySpanStillAccountsForEverySourceFrame() {
        val mapping = ResampledAudioTimeline(48000)
        mapping.offer(1, 1_000_000)
        mapping.offer(99, 1_000_020)
        val result = mapping.output(98, 0)
        assertEquals(98, result.sumOf { it.frames })
        assertEquals(100_000_000L, result.sumOf { it.sourceUnits })
        assertEquals(1_000_000L, result.first().ptsUs)
    }
}

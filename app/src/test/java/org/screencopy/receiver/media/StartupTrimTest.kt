package org.screencopy.receiver.media

import org.junit.Assert.assertEquals
import org.junit.Test

class StartupTrimTest {
    @Test fun canonicalOpusPreskipKeepsSharedCaptureClock() {
        val trim = StartupTrim(312, 48000)
        val first = trim.apply(960, 8_000_000_000)
        assertEquals(312, first.skipFrames)
        assertEquals(648, first.frames)
        assertEquals(8_000_006_500, first.audiblePtsUs)
        val second = trim.apply(960, 8_000_020_000)
        assertEquals(0, second.skipFrames)
        assertEquals(960, second.frames)
        assertEquals(8_000_020_000, second.audiblePtsUs)
        assertEquals(second.audiblePtsUs, first.audiblePtsUs + first.frames * 1_000_000L / 48000)
    }

    @Test fun preskipCanSpanPacketsAndResetsOnlyWithNewAudioGeneration() {
        val trim = StartupTrim(2000, 48000)
        assertEquals(0, trim.apply(960, 0).frames)
        assertEquals(0, trim.apply(960, 20_000).frames)
        val third = trim.apply(960, 40_000)
        assertEquals(80, third.skipFrames)
        assertEquals(880, third.frames)
        assertEquals(41_666, third.audiblePtsUs)
        assertEquals(960, trim.apply(960, 60_000).frames)
        assertEquals(648, StartupTrim(312, 48000).apply(960, 1_000_000).frames)
    }

    @Test fun pcmAndAacDoNotReceiveOpusTrimming() {
        assertEquals(TrimmedAudio(0, 1024, 5_000_000), StartupTrim(0, 48000).apply(1024, 5_000_000))
    }

    @Test fun reversedSourceObservationsDoNotRepeatPreskipOrDiscardPostPrimingSamples() {
        val trim = StartupTrim(2000, 48000)
        val slices = listOf(1_000_000L, 999_551L, 700_000L, 710_000L).map { trim.apply(960, it) }
        assertEquals(2000, slices.sumOf { it.skipFrames })
        assertEquals(4 * 960 - 2000, slices.sumOf { it.frames })
        assertEquals(80, slices[2].skipFrames)
        assertEquals(701_666L, slices[2].audiblePtsUs)
        assertEquals(710_000L, slices[3].audiblePtsUs)
        assertEquals(0, slices[3].skipFrames)
    }
}

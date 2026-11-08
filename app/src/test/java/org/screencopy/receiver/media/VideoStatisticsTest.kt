package org.screencopy.receiver.media

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class VideoStatisticsTest {
    @Test fun scheduledReleaseIsNotAnActualPresentationAndRetiredCallbacksDoNotCount() {
        val decoder = Decoder()
        val player = VideoPlayer(VideoFormat("video/avc", 640, 360, listOf(byteArrayOf(1)), 60),
            PlaybackClock(), { code, detail -> fail("$code $detail") }, decoder).apply { statisticsEnabled = true }
        val step = VideoPlayer::class.java.getDeclaredMethod("step").apply { isAccessible = true }
        try {
            player.offer(AccessUnit(1_000_000, byteArrayOf(1), true))
            step.invoke(player)
            step.invoke(player)
            val scheduled = player.videoStatistics()
            assertEquals(1L, scheduled.decoded)
            assertEquals(1L, scheduled.scheduled)
            assertEquals(0L, scheduled.rendered)
            assertNull(scheduled.renderedPtsUs)
            assertNull(player.videoPathStatistics().presentation)
            decoder.rendered(1_000_000, 2_000_000_000)
            assertEquals(1L, player.videoStatistics().rendered)
            assertEquals(1_000_000L, player.videoStatistics().renderedPtsUs)
            assertEquals(2_000_000_000L, player.videoStatistics().renderedAtNs)
            assertEquals(VideoPresentationOutput.Sdr, player.videoPathStatistics().presentation!!.output)
        } finally { player.close() }
        decoder.rendered(1_020_000, 2_020_000_000)
        assertEquals(1L, player.videoStatistics().rendered)
        assertEquals(2_000_000_000L, player.videoStatistics().renderedAtNs)
        assertEquals(VideoPathStatistics(null, null), player.videoPathStatistics())
    }

    private class Decoder : VideoDecoder {
        override val description = "statistics fixture"
        override val supportsRenderedStatistics = true
        var rendered: (Long, Long) -> Unit = { _, _ -> }
        private var output: VideoOutput? = null
        override fun renderedStatistics(listener: (Long, Long) -> Unit) { rendered = listener }
        override fun dequeueInput() = 0
        override fun input(index: Int) = ByteBuffer.allocate(16)
        override fun queueInput(index: Int, size: Int, ptsUs: Long, keyFrame: Boolean) {
            output = VideoOutput(0, ptsUs, size, 0, VideoPathStatistics(
                DecodedVideoStatistics(VideoStreamFormat.Sdr, VideoTransfer.Bt709, "Main", 8),
                VideoPresentationStatistics(VideoPresentationOutput.Sdr)))
        }
        override fun dequeueOutput(): VideoOutput? = output.also { output = null }
        override fun releaseOutput(index: Int, renderAtNs: Long?) {}
        override fun close() {}
    }
}

package org.screencopy.receiver.media

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.screencopy.receiver.protocol.Configuration
import org.screencopy.receiver.protocol.Request
import org.screencopy.receiver.protocol.StrictJson
import java.util.concurrent.ConcurrentLinkedQueue

@RunWith(AndroidJUnit4::class)
class OpusPipelineTest {
    @Test fun continuingOpusPacketsWith300msOffsetUseRealDecoderQueueAndAudioTrack() = stream(300_000)
    @Test fun continuingOpusPacketsWithTwoSecondOffsetStayBounded() = stream(2_000_000)
    @Test fun monotonicCaptureTimestampRefreshUsesTheRealOpusOutputPath() =
        stream(0, { index -> if (index >= 10) 6_000 else 0 })
    @Test fun exact64112usRefreshUsesRealOpusPreskipAndOutputWithoutFatalPhaseGuard() =
        stream(0, { index -> if (index >= 10) 64_112 else 0 })

    private fun stream(offsetUs: Long, correctionUs: (Int) -> Long = { 0 }) {
        val format = Configuration.audio(
            StrictJson.decode("""{"codec":"opus","sampleRate":48000,"channels":2,"csd":["T3B1c0hlYWQBAjgBgLsAAAAAAA=="],"codecDelayNs":6500000,"seekPrerollNs":80000000}""".toByteArray()),
            Request("h264", "opus", 60, "")
        )
        val clock = PlaybackClock().apply { audioExpected = true; anchorVideo(1_000_000) }
        val failures = ConcurrentLinkedQueue<String>()
        val player = AudioPlayer(format, clock, { code, detail -> failures.add("$code: $detail") })
        try {
            player.start()
            repeat(60) { index ->
                assertTrue(failures.toString(), failures.isEmpty())
                player.offer(AccessUnit(1_000_000 + offsetUs + index * 20_000 + correctionUs(index),
                    byteArrayOf(0xf8.toByte(), 0xff.toByte(), 0xfe.toByte())))
                Thread.sleep(20)
            }
            assertTrue(failures.toString(), failures.isEmpty())
            assertNotNull(clock.currentUs())
            assertTrue(checkNotNull(clock.currentUs()) >= 1_000_000 + offsetUs + 6500)
        } finally { player.close() }
    }
}

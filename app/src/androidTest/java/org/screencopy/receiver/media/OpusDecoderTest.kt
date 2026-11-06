package org.screencopy.receiver.media

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpusDecoderTest {
    @Test fun pinnedOpusDecodesSilencePacket() {
        OpusDecoder(2).use { decoder ->
            val pcm = decoder.decode(byteArrayOf(0xf8.toByte(), 0xff.toByte(), 0xfe.toByte()))
            assertEquals(1920, pcm.size)
            assertTrue(pcm.all { it.toInt() == 0 })
        }
    }

    @Test fun malformedOpusIsExplicit() {
        OpusDecoder(1).use { decoder ->
            try {
                decoder.decode(byteArrayOf())
                fail("Empty packet must not become packet-loss concealment")
            } catch (_: IllegalArgumentException) {
                // Empty network packets are invalid, not a PLC request.
            }
        }
    }
}

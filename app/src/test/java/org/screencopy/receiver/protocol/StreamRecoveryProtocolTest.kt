package org.screencopy.receiver.protocol

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.screencopy.receiver.media.AudioPlayer
import org.screencopy.receiver.media.TrimmedAudio

class StreamRecoveryProtocolTest {
    @Test fun proofTimestampsAreCanonicalInt64StringsNeverJsonNumbers() {
        val proof = StreamRecoveryProtocol.status(StreamRecoveryProtocol.COMPLETE, Int.MAX_VALUE.toLong(),
            Long.MAX_VALUE - 1, Long.MAX_VALUE)
        val decoded = StrictJson.decode(proof.toString().toByteArray())
        assertEquals(Long.MAX_VALUE - 1, StreamRecoveryProtocol.timestamp(decoded, "keyPtsUs"))
        assertEquals(Long.MAX_VALUE, StreamRecoveryProtocol.timestamp(decoded, "audioPtsUs"))
        for (value in listOf<Any>("01", "-1", "+1", "1.0", "9223372036854775808", "", 123, JSONObject.NULL)) {
            assertThrows(ProtocolException::class.java) {
                StreamRecoveryProtocol.timestamp(JSONObject().put("keyPtsUs", value), "keyPtsUs")
            }
        }
        assertThrows(ProtocolException::class.java) {
            StreamRecoveryProtocol.status(StreamRecoveryProtocol.KEY, Int.MAX_VALUE.toLong() + 1, 0)
        }
    }

    @Test fun opusDurationUsesTheTocIncludingLongAndMultiFramePackets() {
        val opus = AudioPacketTiming("opus")
        for ((toc, duration) in listOf(0 to 10_000L, 8 to 20_000L, 16 to 40_000L, 24 to 60_000L,
            128 to 2_500L, 136 to 5_000L, 144 to 10_000L, 152 to 20_000L)) {
            assertEquals(1_000_000 + duration, opus.endPts(1_000_000, byteArrayOf(toc.toByte(), 1)))
        }
        assertEquals(1_120_000, opus.endPts(1_000_000, byteArrayOf(27, 2, 1, 1)))
        assertEquals(920_000, opus.windowStart(1_000_000))
        assertThrows(ProtocolException::class.java) { opus.endPts(0, byteArrayOf(27, 3)) }
        assertThrows(ProtocolException::class.java) { opus.endPts(0, byteArrayOf(3)) }
    }

    @Test fun pcmAndAacDurationAndOverflowAreValidated() {
        val pcm = AudioPacketTiming("raw")
        assertEquals(1_020_000, pcm.endPts(1_000_000, ByteArray(3840)))
        assertEquals(1_000_000, pcm.windowStart(1_000_000))
        assertThrows(ProtocolException::class.java) { pcm.endPts(0, ByteArray(3)) }
        assertThrows(ProtocolException::class.java) { pcm.endPts(Long.MAX_VALUE, ByteArray(4)) }
        assertThrows(ProtocolException::class.java) { pcm.endPts(0, ByteArray(48000)) }
        for ((asc, frames) in listOf(byteArrayOf(0x11, 0x90.toByte()) to 1024,
            byteArrayOf(0x11, 0x94.toByte()) to 960)) {
            val timing = AudioPacketTiming.fromConfig(JSONObject().put("codec", "aac").put("sampleRate", 48000)
                .put("channels", 2).put("csd", org.json.JSONArray().put(java.util.Base64.getEncoder().encodeToString(asc))))
            assertEquals(1_000_000 + frames * 1_000_000L / 48000, timing.endPts(1_000_000, byteArrayOf(1)))
            assertEquals((frames * 1_000_000L + 47999) / 48000, timing.prerollUs)
        }
    }

    @Test fun overlappingDecodedSamplesAreTrimmedOnFrameBoundariesWithInitialSkipPreserved() {
        val original = TrimmedAudio(312, 960, 1_000_000)
        val trimmed = AudioPlayer.trimRecoveryAudio(original, 1_010_001, 48000)
        assertEquals(793, trimmed.skipFrames)
        assertEquals(479, trimmed.frames)
        assertEquals(1_010_020, trimmed.audiblePtsUs)
        assertEquals(original, AudioPlayer.trimRecoveryAudio(original, 999_999, 48000))
        assertEquals(0, AudioPlayer.trimRecoveryAudio(original, Long.MAX_VALUE, 48000).frames)
    }

    @Test fun randomAccessRequiresRealNalTypeAndMatchingParameterSetsIgnoringAnnexBPadding() {
        val sps = byteArrayOf(0, 0, 0, 1, 0x67, 2, 0, 0)
        val pps = byteArrayOf(0, 0, 1, 0x68, 3)
        val key = byteArrayOf(0, 0, 1, 0x67, 2, 0, 0, 0, 1, 0x68, 3, 0, 0, 1, 0x65, 1)
        RandomAccessPoint.verify("h264", key, listOf(sps, pps))
        assertThrows(ProtocolException::class.java) { RandomAccessPoint.verify("h264", byteArrayOf(0, 0, 1, 0x41, 1), listOf(sps)) }
        assertThrows(ProtocolException::class.java) { RandomAccessPoint.verify("h264", key, listOf(sps)) }
        for (type in 16..21) RandomAccessPoint.verify("h265", byteArrayOf(0, 0, 1, (type shl 1).toByte(), 1), emptyList())
        assertThrows(ProtocolException::class.java) { RandomAccessPoint.verify("h265", byteArrayOf(0, 0, 1, 0x26, 0), emptyList()) }
        assertThrows(ProtocolException::class.java) { RandomAccessPoint.verify("h265", byteArrayOf(0, 0, 1, 2, 1), emptyList()) }
    }
}

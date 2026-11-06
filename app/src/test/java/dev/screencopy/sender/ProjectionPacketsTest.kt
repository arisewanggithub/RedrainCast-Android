package dev.screencopy.sender

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProjectionPacketsTest {
    private val head = WireTest.unhex("4f707573486561640102380180bb0000000000")
    private fun section(name: String, bytes: ByteArray) = name.toByteArray(Charsets.US_ASCII) +
        ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(bytes.size.toLong()).array() + bytes

    @Test fun androidOpusInitializationPreservesTheActualHeadAndDropsContainerSections() {
        assertArrayEquals(head, ProjectionPackets.opusHead(head))
        val android = section("AOPUSHDR", head) + section("AOPUSDLY", ByteArray(8)) + section("AOPUSPRL", ByteArray(8))
        assertArrayEquals(head, ProjectionPackets.opusHead(android))
        assertEquals(6_500_000L, CodecConfig.audio("opus", ProjectionPackets.opusHead(android)).getLong("codecDelayNs"))
        for (invalid in listOf(byteArrayOf(), head.copyOf(18), section("AOPUSHDR", ByteArray(19)),
            "AOPUSHDR".toByteArray() + ByteArray(8) { 0x7f }))
            assertThrows(IOException::class.java) { ProjectionPackets.opusHead(invalid) }
    }

    private class Sink : ScrcpyAdapter.Sink {
        val configs = mutableListOf<Triple<Int, Int, JSONObject>>()
        val packets = mutableListOf<Wire.Message>()
        override fun config(track: Int, generation: Int, config: JSONObject) { configs += Triple(track, generation, config) }
        override fun packet(packet: Wire.Message) { packets += packet }
    }

    @Test fun realVideoConfigAndAccessUnitsKeepPtsKeysAndGenerationsForCustomTcp() {
        val sink = Sink()
        val output = EncodedOutput(1, "h264", 123, sink)
        val first = BackendTest().sps(640, 480)
        output.configure(first); output.configure(first)
        val frame = WireTest.unhex("000000016588")
        output.packet(frame, 1_234_567, true)
        output.configure(BackendTest().sps(480, 640))
        output.packet(frame, 1_250_000, true)
        assertEquals(listOf(1, 2), sink.configs.map { it.second })
        assertEquals(listOf(640, 480), sink.configs.map { it.third.getInt("width") })
        assertEquals(listOf(1_234_567L, 1_250_000L), sink.packets.map { it.pts })
        assertTrue(sink.packets.all { it.flags == 1 && it.session == 123L && it.track == 1 })
        assertEquals(listOf(1, 2), sink.packets.map { it.generation })
        assertSame(frame, sink.packets.first().payload)
        assertThrows(IOException::class.java) { output.packet(byteArrayOf(1, 2, 3), 10, true) }
    }

    @Test fun rawAudioUsesExplicitPcmMetadataAndNeverInventsAnEncoderOrDropsAlignmentErrors() {
        val sink = Sink()
        val output = EncodedOutput(2, "raw", 12, sink)
        assertThrows(IOException::class.java) { output.packet(ByteArray(4), 0, false) }
        output.configure(byteArrayOf())
        val data = ByteArray(3840)
        output.packet(data, 7_000_000, false)
        assertEquals("s16le", sink.configs.single().third.getString("sampleFormat"))
        assertEquals(48000, sink.configs.single().third.getInt("sampleRate"))
        assertSame(data, sink.packets.single().payload)
        assertEquals(7_000_000L, sink.packets.single().pts)
        for (bytes in listOf(byteArrayOf(), ByteArray(3), ByteArray(Wire.MAX_AUDIO + 4)))
            assertThrows(IOException::class.java) { output.packet(bytes, 8_000_000, false) }
        assertThrows(IOException::class.java) { output.packet(data, -1, false) }
    }

    @Test fun restartingVideoEncoderAnnouncesANewGenerationEvenWhenCsdIsIdentical() {
        val sink = Sink()
        val output = EncodedOutput(1, "h264", 123, sink)
        val csd = BackendTest().sps(640, 480)
        val frame = WireTest.unhex("000000016588")
        output.configure(csd)
        output.packet(frame, 1_000_000, true)
        output.restart()
        output.configure(csd)
        output.configure(csd)
        output.packet(frame, 2_000_000, true)
        output.restart()
        output.configure(BackendTest().sps(480, 640))
        output.packet(frame, 3_000_000, true)
        assertEquals(listOf(1, 2, 3), sink.configs.map { it.second })
        assertEquals(listOf(1, 2, 3), sink.packets.map { it.generation })
        assertEquals(listOf(1_000_000L, 2_000_000L, 3_000_000L), sink.packets.map { it.pts })
    }

    @Test fun encodedAudioUsesCanonicalMetadataAndRejectsAdtsAndUnsupportedProfiles() {
        val sink = Sink()
        val opus = EncodedOutput(2, "opus", 1, sink)
        opus.configure(section("AOPUSHDR", head))
        assertEquals("opus", sink.configs.single().third.getString("codec"))
        val aac = EncodedOutput(2, "aac", 1, Sink())
        aac.configure(WireTest.unhex("1190"))
        aac.packet(byteArrayOf(1, 2, 3), 1_000_000, false)
        assertThrows(IOException::class.java) { aac.packet(WireTest.unhex("fff150"), 2_000_000, false) }
        assertThrows(IOException::class.java) { aac.configure(WireTest.unhex("2990")) }
    }
}

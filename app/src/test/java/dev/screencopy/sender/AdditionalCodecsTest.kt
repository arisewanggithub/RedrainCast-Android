package dev.screencopy.sender

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException

class AdditionalCodecsTest {
    // AndroidX Media3 Apache-2.0 AV1 sequence-header test vector, 854x480.
    private val sequence = WireTest.unhex("0a0e00000024c6abdf3efe2404040410")

    @Test fun aacActualAscRetainsRateChannelsAndFrameLength() {
        val asc = WireTest.unhex("1190")
        val actual = AacConfiguration.parse(asc)
        assertEquals(AacConfiguration(48000, 2, 1024), actual)
        assertEquals(960, AacConfiguration.parse(WireTest.unhex("1194")).samplesPerFrame)
        val config = CodecConfig.audio("aac", asc)
        assertEquals(48000, config.getInt("sampleRate"))
        assertEquals(2, config.getInt("channels"))
    }

    @Test fun malformedOrUnsupportedAacNeverFallsBackOrAcceptsAdts() {
        for (bytes in listOf(byteArrayOf(), byteArrayOf(0x11), WireTest.unhex("2990"),
            WireTest.unhex("1192"), WireTest.unhex("1191"), WireTest.unhex("119001")))
            assertThrows(IOException::class.java) { AacConfiguration.parse(bytes) }
        assertThrows(IOException::class.java) { AacConfiguration.accessUnit(WireTest.unhex("fff150")) }
    }

    @Test fun av1AdapterRetainsRealObusDimensionsKeysAndRawPtsWithoutAvcParsing() {
        assertEquals(854 to 480, Av1Configuration.dimensions(sequence))
        val config = CodecConfig.video("av1", sequence)
        assertEquals(854, config.getInt("width")); assertEquals(480, config.getInt("height"))
        val frames = listOf(sequence + WireTest.unhex("320100"), WireTest.unhex("320110"))
        val received = mutableListOf<ByteArray>()
        val keys = mutableListOf<Boolean>()
        val timestamps = mutableListOf<Long>()
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(0x00617631); output.writeInt(854); output.writeInt(480)
            output.writeLong(Long.MIN_VALUE); output.writeInt(sequence.size); output.write(sequence)
            frames.forEachIndexed { i, frame ->
                output.writeLong(1_000_000 + i * 16_667L or (if (i == 0) 1L shl 62 else 0))
                output.writeInt(frame.size); output.write(frame)
            }
        }
        assertThrows(EOFException::class.java) {
            ScrcpyAdapter.read(ByteArrayInputStream(bytes.toByteArray()), 1,
                Settings().apply { videoCodec = "av1" }, 1,
                object : ScrcpyAdapter.Sink {
                    override fun config(track: Int, generation: Int, config: org.json.JSONObject) {
                        assertEquals(854, config.getInt("width")); assertEquals(480, config.getInt("height"))
                        assertArrayEquals(sequence, java.util.Base64.getDecoder().decode(config.getJSONArray("csd").getString(0)))
                    }
                    override fun packet(packet: Wire.Message) {
                        received += packet.payload; keys += packet.flags and 1 != 0; timestamps += packet.pts
                    }
                })
        }
        assertEquals(listOf(true, false), keys)
        frames.forEachIndexed { i, frame -> assertArrayEquals(frame, received[i]) }
        assertEquals(listOf(1_000_000L, 1_016_667L), timestamps)
        assertThrows(IOException::class.java) { Av1Configuration.dimensions(WireTest.unhex("320100")) }
        assertThrows(IOException::class.java) { Av1Configuration.dimensions(sequence.copyOf(4)) }
        assertThrows(IOException::class.java) { Av1Configuration.obus(sequence + WireTest.unhex("3205ff")) }
    }
}

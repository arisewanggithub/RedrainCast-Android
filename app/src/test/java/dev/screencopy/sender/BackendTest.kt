package dev.screencopy.sender

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.nio.file.Path

class BackendTest {
    @Test fun defaultsPersistExactlyAndInvalidCodecOptionsFail() {
        val settings = Settings()
        assertTrue(settings.audio); assertEquals("raw", settings.audioCodec)
        assertEquals(192000, settings.audioBitrate); assertEquals("h264", settings.videoCodec)
        assertEquals(0, settings.maxFps)
        assertEquals(1920, settings.maxSize)
        settings.cropWidth = 400; settings.cropHeight = 600; settings.cropX = 10; settings.cropY = 20
        settings.orientation = "@90"; settings.displayId = 2
        settings.videoEncoder = "c2.android.avc.encoder"; settings.videoOptions = "profile=1,level:int=4"
        val restored = Settings.from(settings.request())
        assertEquals(settings.request().toString(), restored.request().toString())
        for (invalid in listOf("profile=1;id", "max-bframes=1", "profile:float=1.5,profile:int=2")) {
            settings.videoOptions = invalid
            assertThrows(IllegalArgumentException::class.java) { settings.validate() }
        }
    }
    @Test fun canonicalOpusMetadataAndRawConfiguration() {
        val head = WireTest.unhex(readUtf8Strict(fixturePath("opus-head.hex")).trim())
        val config = CodecConfig.audio("opus", head)
        assertEquals(48000, config.getInt("sampleRate")); assertEquals(2, config.getInt("channels"))
        assertEquals(6_500_000L, config.getLong("codecDelayNs")); assertEquals(80_000_000L, config.getLong("seekPrerollNs"))
        assertThrows(IOException::class.java) { CodecConfig.audio("opus", ByteArray(16)) }
        assertEquals("s16le", CodecConfig.audio("raw", byteArrayOf()).getString("sampleFormat"))
        assertEquals("aac", CodecConfig.audio("aac", byteArrayOf(0x11, 0x90.toByte())).getString("codec"))
        assertThrows(IOException::class.java) { CodecConfig.audio("aac", byteArrayOf(0x12, 0x10)) }
    }
    @Test fun scrcpyOpusHeaderConfigPacketAndAbsolutePts() {
        val head = WireTest.unhex("4f707573486561640102380180bb0000000000")
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply {
            writeInt(0x6f707573); writeLong(Long.MIN_VALUE); writeInt(head.size); write(head)
            writeLong(123456789); writeInt(3); write(byteArrayOf(0xf8.toByte(), 0xff.toByte(), 0xfe.toByte()))
        }
        val configs = mutableListOf<JSONObject>()
        val packets = mutableListOf<Wire.Message>()
        assertThrows(EOFException::class.java) {
            ScrcpyAdapter.read(ByteArrayInputStream(bytes.toByteArray()), 2, Settings().apply { audioCodec = "opus" }, 1, object : ScrcpyAdapter.Sink {
                override fun config(track: Int, generation: Int, config: JSONObject) { configs.add(config) }
                override fun packet(packet: Wire.Message) { packets.add(packet) }
            })
        }
        assertEquals(1, configs.size); assertEquals(1, packets.size)
        assertEquals(123456789L, packets[0].pts); assertEquals(1, packets[0].generation)
    }
    @Test fun enabledAudioDisableSignalIsFatalNotVideoOnly() {
        val sink = object : ScrcpyAdapter.Sink {
            override fun config(track: Int, generation: Int, config: JSONObject) { fail() }
            override fun packet(packet: Wire.Message) { fail() }
        }
        val error = assertThrows(IOException::class.java) {
            ScrcpyAdapter.read(ByteArrayInputStream(ByteArray(4)), 2, Settings(), 1, sink)
        }
        assertTrue(error.message.orEmpty().contains("AUDIO_FAILED"))
    }
    @Test fun h264SpsDimensionsAndRotation() {
        for ((width, height) in listOf(640 to 480, 480 to 640)) {
            val config = CodecConfig.video("h264", sps(width, height))
            assertEquals(width, config.getInt("width")); assertEquals(height, config.getInt("height"))
            assertEquals(2, config.getJSONArray("csd").length())
        }
    }
    @Test fun h265SpsActualDimensions() {
        val config = CodecConfig.video("h265", h265Config(1920, 1080))
        assertEquals(1920, config.getInt("width")); assertEquals(1080, config.getInt("height"))
        assertEquals(1, config.getJSONArray("csd").length())
    }
    internal fun h265Config(width: Int, height: Int): ByteArray {
        val bits = StringBuilder("00000001" + "0".repeat(96))
        ue(bits, 0); ue(bits, 1); ue(bits, width); ue(bits, height); bits.append("01")
        while (bits.length % 8 != 0) bits.append('0')
        val bytes = ByteArrayOutputStream()
        bytes.write(byteArrayOf(0, 0, 0, 1, 0x40, 1, 0x80.toByte(), 0, 0, 0, 1, 0x42, 1))
        var zeros = 0
        for (index in bits.indices step 8) {
            val value = bits.substring(index, index + 8).toInt(2)
            if (zeros >= 2 && value <= 3) { bytes.write(3); zeros = 0 }
            bytes.write(value); zeros = if (value == 0) zeros + 1 else 0
        }
        bytes.write(byteArrayOf(0, 0, 0, 1, 0x44, 1, 0x80.toByte()))
        return bytes.toByteArray()
    }
    internal fun sps(width: Int, height: Int): ByteArray {
        val bits = StringBuilder("010000100000000000011110")
        for (value in listOf(0, 0, 0, 0, 1)) ue(bits, value)
        bits.append('0'); ue(bits, width / 16 - 1); ue(bits, height / 16 - 1); bits.append("1101")
        while (bits.length % 8 != 0) bits.append('0')
        return ByteArrayOutputStream().apply {
            write(byteArrayOf(0, 0, 0, 1, 0x67))
            for (index in bits.indices step 8) write(bits.substring(index, index + 8).toInt(2))
            write(byteArrayOf(0, 0, 0, 1, 0x68, 0x80.toByte()))
        }.toByteArray()
    }
    private fun ue(bits: StringBuilder, number: Int) {
        val value = Integer.toBinaryString(number + 1)
        bits.append("0".repeat(value.length - 1)).append(value)
    }
}

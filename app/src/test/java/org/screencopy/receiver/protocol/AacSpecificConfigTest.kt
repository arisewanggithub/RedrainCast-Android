package org.screencopy.receiver.protocol

import org.junit.Assert.*
import org.junit.Test

class AacSpecificConfigTest {
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    @Test fun actualFrameLengthRateAndChannelsAreParsedWithoutDefaults() {
        assertEquals(AacSpecificConfig(48000, 2, 1024), AacSpecificConfig.parse(hex("1190")))
        assertEquals(AacSpecificConfig(48000, 2, 960), AacSpecificConfig.parse(hex("1194")))
        assertEquals(AacSpecificConfig(44100, 2, 1024), AacSpecificConfig.parse(hex("1210")))
    }
    @Test fun truncatedHeAacAndUnsupportedExtensionsFailExplicitly() {
        for (bytes in listOf("", "11", "2990", "1191", "1192", "119001")) {
            assertEquals("UNSUPPORTED_CONFIG", assertThrows(ProtocolException::class.java) {
                AacSpecificConfig.parse(hex(bytes))
            }.code)
        }
        val request = Request("h264", "aac", 30, "")
        val mismatched = org.json.JSONObject("""{"codec":"aac","sampleRate":48000,"channels":2,"csd":["EhA="]}""")
        assertEquals("UNSUPPORTED_CONFIG", assertThrows(ProtocolException::class.java) {
            Configuration.audio(mismatched, request)
        }.code)
    }
}

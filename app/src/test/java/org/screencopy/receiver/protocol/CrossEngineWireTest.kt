package org.screencopy.receiver.protocol

import dev.screencopy.sender.Wire as SenderWire
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer

class CrossEngineWireTest {
    @Test fun actualWindowsWriterGoldenPacketsDecodeAndReencodeExactlyInBothAndroidEngines() {
        data class Expected(val type: Int, val session: Long = 73, val track: Int = 0,
            val generation: Int = 0, val pts: Long = -1, val flags: Int = 0)
        val fixtures = mutableMapOf(
            "hello" to Expected(1, session = 0),
            "accept" to Expected(4),
            "attach" to Expected(1),
            "config" to Expected(6, track = 1, generation = 1),
            "ready" to Expected(7, track = 1, generation = 1),
            "frame" to Expected(8, track = 1, generation = 1, pts = 123456789, flags = 1),
            "pcm" to Expected(9, track = 2, generation = 1, pts = 123456789),
            "stop" to Expected(14),
            "rotation-sender-hello" to Expected(1, session = 0),
            "rotation-receiver-hello" to Expected(1, session = 0)
        )
        for (flags in 0..7) fixtures["rotation-frame-$flags"] =
            Expected(8, track = 1, generation = 1, pts = 123456789, flags = flags)
        assertEquals(18, fixtures.size)
        for ((name, expected) in fixtures) {
            val bytes = windowsFixture(name)
            val header = ByteBuffer.wrap(bytes)
            assertEquals(name, 0x53435059, header.int)
            assertEquals(name, 3, header.short.toInt())
            assertEquals(name, expected.type, header.short.toInt())
            assertEquals(name, bytes.size - 40, header.int)
            assertEquals(name, expected.flags, header.int)
            assertEquals(name, expected.session, header.long)
            assertEquals(name, expected.track, header.int)
            assertEquals(name, expected.generation, header.int)
            assertEquals(name, expected.pts, header.long)
            assertEquals(name, 40, header.position())
            val receiverInput = ByteArrayInputStream(bytes)
            val received = Wire.read(receiverInput)
            assertEquals(name, -1, receiverInput.read())
            assertEquals(name, expected.type, received.type.number)
            assertEquals(name, expected.session, received.session)
            assertEquals(name, expected.track, received.track)
            assertEquals(name, expected.generation.toLong(), received.generation)
            assertEquals(name, expected.pts, received.ptsUs)
            assertEquals(name, expected.flags, received.flags)
            assertArrayEquals(name, bytes, ByteArrayOutputStream().also { Wire.write(it, received) }.toByteArray())
            val senderInput = ByteArrayInputStream(bytes)
            val sent = SenderWire.read(senderInput)
            assertEquals(name, -1, senderInput.read())
            assertEquals(name, expected.type, sent.type)
            assertEquals(name, expected.session, sent.session)
            assertEquals(name, expected.track, sent.track)
            assertEquals(name, expected.generation, sent.generation)
            assertEquals(name, expected.pts, sent.pts)
            assertEquals(name, expected.flags, sent.flags)
            assertArrayEquals(name, received.payload, sent.payload)
            assertArrayEquals(name, bytes, ByteArrayOutputStream().also { SenderWire.write(it, sent) }.toByteArray())
            if (name.startsWith("rotation-frame-")) {
                assertEquals(name, expected.flags / 2 * 90, (received.flags ushr 1) * 90)
                assertEquals(name, expected.flags and 1, sent.flags and 1)
            }
            if (name == "rotation-sender-hello" || name == "rotation-receiver-hello") {
                assertTrue(name, received.json().getBoolean("videoRotation"))
                assertTrue(name, sent.json().getBoolean("videoRotation"))
                assertEquals(name, if (name == "rotation-sender-hello") "sender" else "receiver",
                    received.json().getString("role"))
            }
        }
    }

    @Test fun reservedVideoFlagsAndAllNonvideoFlagsAreRejectedByBothReadersAndWriters() {
        var cases = 0
        for (type in Type.entries) {
            val fixture = when (type) {
                Type.VIDEO_FRAME -> "rotation-frame-0"
                Type.AUDIO_PACKET -> "pcm"
                Type.STREAM_CONFIG -> "config"
                Type.STREAM_READY -> "ready"
                else -> "stop"
            }
            val bytes = windowsFixture(fixture).also {
                ByteBuffer.wrap(it).putShort(6, type.number.toShort())
            }
            val receiver = Wire.read(ByteArrayInputStream(bytes))
            val sender = SenderWire.read(ByteArrayInputStream(bytes))
            val invalidFlags = if (type == Type.VIDEO_FRAME) listOf(8, Int.MIN_VALUE, -1)
                else listOf(1, 2, 7, 8, Int.MIN_VALUE, -1)
            for (flags in invalidFlags) {
                val malformed = bytes.copyOf().also { ByteBuffer.wrap(it).putInt(12, flags) }
                assertEquals("PROTOCOL", assertThrows(ProtocolException::class.java) {
                    Wire.read(ByteArrayInputStream(malformed))
                }.code)
                assertTrue(assertThrows(IOException::class.java) {
                    SenderWire.read(ByteArrayInputStream(malformed))
                }.message!!.startsWith("PROTOCOL:"))
                val receiverOutput = ByteArrayOutputStream()
                assertEquals("PROTOCOL", assertThrows(ProtocolException::class.java) {
                    Wire.write(receiverOutput, receiver.copy(flags = flags))
                }.code)
                assertEquals(0, receiverOutput.size())
                val senderOutput = ByteArrayOutputStream()
                assertTrue(assertThrows(IOException::class.java) {
                    SenderWire.write(senderOutput, sender.copy(flags = flags))
                }.message!!.startsWith("PROTOCOL:"))
                assertEquals(0, senderOutput.size())
                cases++
            }
        }
        assertEquals(81, cases)
    }

    private fun windowsFixture(name: String): ByteArray {
        val hex = checkNotNull(javaClass.getResourceAsStream("/windows-v3-$name.hex"))
            .use { it.readBytes().toString(Charsets.US_ASCII).trim() }
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    @Test fun sharedHelloMediaConfigurationReadinessAndRotationStatusBytesCrossBothRealEngines() {
        val fixture = checkNotNull(javaClass.getResourceAsStream("/cross-engine.json"))
            .use { StrictJson.decode(it.readBytes()) }
        val session = fixture.getLong("session")
        assertTrue(session > 0)
        val packets = fixture.getJSONArray("packets")
        repeat(packets.length()) { index ->
            val case = packets.getJSONObject(index)
            val name = case.getString("name")
            val type = case.getInt("type")
            val caseSession = case.optLong("session", session)
            val track = case.optInt("track", 0)
            val generation = case.optInt("generation", 0)
            val pts = case.optString("ptsUs", "-1").toLong()
            val flags = case.optInt("flags", 0)
            val payload = if (case.has("hex")) case.getString("hex").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                else case.getJSONObject("body").toString().toByteArray(Charsets.UTF_8)

            val senderMessage = SenderWire.Message(type, flags, caseSession, track, generation, pts, payload)
            val senderBytes = ByteArrayOutputStream().also { SenderWire.write(it, senderMessage) }.toByteArray()
            val receiverMessage = Wire.read(ByteArrayInputStream(senderBytes))
            assertEquals(name, type, receiverMessage.type.number)
            assertEquals(name, caseSession, receiverMessage.session)
            assertEquals(name, track, receiverMessage.track)
            assertEquals(name, generation.toLong(), receiverMessage.generation)
            assertEquals(name, pts, receiverMessage.ptsUs)
            assertEquals(name, flags, receiverMessage.flags)
            assertArrayEquals(name, payload, receiverMessage.payload)

            val receiverBytes = ByteArrayOutputStream().also { Wire.write(it, receiverMessage) }.toByteArray()
            assertArrayEquals(name, senderBytes, receiverBytes)
            val senderRead = SenderWire.read(ByteArrayInputStream(receiverBytes))
            assertEquals(name, senderMessage.type, senderRead.type)
            assertEquals(name, senderMessage.session, senderRead.session)
            assertEquals(name, senderMessage.track, senderRead.track)
            assertEquals(name, senderMessage.generation, senderRead.generation)
            assertEquals(name, senderMessage.pts, senderRead.pts)
            assertEquals(name, senderMessage.flags, senderRead.flags)
            assertArrayEquals(name, payload, senderRead.payload)

            if (name == "rotation-video") {
                assertEquals(270, (receiverMessage.flags ushr 1) * 90)
                assertTrue(receiverMessage.flags and 1 != 0)
                assertEquals(8_000_000_123_456L, senderRead.pts)
            }
            if (name.endsWith("-hello")) assertTrue(receiverMessage.json().getBoolean("videoRotation"))
            if (name == "rotation-status-envelope") {
                assertEquals(0, senderRead.track)
                assertEquals(-1L, senderRead.pts)
                assertEquals("Capturing", senderRead.json().getString("state"))
                assertTrue(senderRead.json().getString("message").contains("270"))
            }
        }
    }
}

package dev.screencopy.sender

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Path

class WireTest {
    @Test fun smallMessagesUseOneBulkSocketWriteAndLargeFramesUseABoundedPrefix() {
        for (size in listOf(1, 192, 3840, 16384, 16385, Wire.MAX_VIDEO)) {
            val payload = ByteArray(size) { (it * 31).toByte() }
            val message = Wire.Message(Wire.VIDEO_FRAME, 1, 77, 1, 2, 1_234_567, payload)
            val calls = mutableListOf<Triple<ByteArray, Int, Int>>()
            val bytes = ByteArrayOutputStream()
            var flushes = 0
            val socket = object : OutputStream() {
                override fun write(value: Int) { fail("Framing must not issue per-byte socket writes") }
                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    calls += Triple(buffer, offset, length)
                    bytes.write(buffer, offset, length)
                }
                override fun flush() { flushes++ }
            }
            Wire.write(socket, message)
            assertEquals(if (size <= 16384) 1 else 2, calls.size)
            assertEquals(40 + minOf(size, 16384), calls.first().third)
            if (size > 16384) {
                assertSame("Large payload must not be copied in its entirety", payload, calls[1].first)
                assertEquals(16384, calls[1].second)
                assertEquals(size - 16384, calls[1].third)
            }
            assertEquals(1, flushes)
            val decoded = Wire.read(ByteArrayInputStream(bytes.toByteArray()))
            assertEquals(message.copy(payload = decoded.payload), decoded)
            assertArrayEquals(payload, decoded.payload)
        }
    }

    @Test fun bulkWriteFailuresPropagateWithoutRetryOrSuccessShapedFlush() {
        val failure = IOException("Disconnected")
        var flushes = 0
        val socket = object : OutputStream() {
            override fun write(value: Int) { fail("Unexpected per-byte write") }
            override fun write(buffer: ByteArray, offset: Int, length: Int) { throw failure }
            override fun flush() { flushes++ }
        }
        val observed = assertThrows(IOException::class.java) {
            Wire.write(socket, Wire.control(Wire.PING, 1, Wire.obj("nonce" to 3)))
        }
        assertSame(failure, observed)
        assertEquals(0, flushes)
    }

    @Test fun everyTrackMessageStillRequiresPositiveInternalCastSession() {
        for (type in listOf(Wire.STREAM_CONFIG, Wire.STREAM_READY, Wire.VIDEO_FRAME, Wire.AUDIO_PACKET)) {
            val track = if (type == Wire.AUDIO_PACKET) 2 else 1
            val pts = if (type == Wire.VIDEO_FRAME || type == Wire.AUDIO_PACKET) 0L else -1L
            assertThrows(IOException::class.java) { Wire.validate(type, 0, 0, track, 1, pts, 1) }
            Wire.validate(type, 0, 1, track, 1, pts, 1)
        }
    }

    @Test fun sharedFixturesFragmentAndRoundTrip() {
        val cases = JSONObject(readUtf8Strict(fixturePath("v3.json"))).getJSONArray("cases")
        for (index in 0 until cases.length()) {
            val item = cases.getJSONObject(index)
            val data = unhex(item.getString("hex"))
            try {
                val input = object : FilterInputStream(ByteArrayInputStream(data)) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, minOf(1, length))
                }
                val message = Wire.read(input)
                assertTrue(item.getString("name"), item.getBoolean("valid"))
                val out = ByteArrayOutputStream()
                Wire.write(out, message)
                assertArrayEquals(data, out.toByteArray())
            } catch (e: IOException) {
                assertFalse("${item.getString("name")}: $e", item.getBoolean("valid"))
            }
        }
    }
    @Test fun coalescedMessagesRemainSeparate() {
        val out = ByteArrayOutputStream()
        Wire.write(out, Wire.control(Wire.PING, 7, Wire.obj("nonce" to 1)))
        Wire.write(out, Wire.control(Wire.PONG, 7, Wire.obj("nonce" to 1)))
        val input = ByteArrayInputStream(out.toByteArray())
        assertEquals(Wire.PING, Wire.read(input).type); assertEquals(Wire.PONG, Wire.read(input).type)
        assertEquals(0, input.available())
    }
    @Test fun strictJsonRejectsAmbiguousAndMalformedValues() {
        for (invalid in listOf(
            """{"x":1,"x":2}""", """{"x":1,"\u0078":2}""", """{x:1}""", """{"x":NaN}""",
            """{"x":1,}""", """{"x":[1,]}""", "{} trailing", """{"x":01}""", """{"x":1e9999}""",
            """{"x":/*comment*/1}""", """{"x":"\ud800"}""", """{"x":2147483648}"""
        )) assertThrows(invalid, IOException::class.java) { Wire.parseJson(invalid.toByteArray(Charsets.UTF_8)) }
        assertThrows(IOException::class.java) { Wire.parseJson(byteArrayOf(123, 34, 0xff.toByte(), 34, 58, 49, 125)) }
        val value = Wire.parseJson("""{"x":"1","ok":"true"}""".toByteArray())
        assertThrows(JSONException::class.java) { Json.integer(value, "x") }
        assertThrows(JSONException::class.java) { Json.bool(value, "ok") }
    }
    @Test fun helloFixtureIsCodeFreeButInternalCastSessionIdIsPreserved() {
        val text = readUtf8Strict(fixturePath("session.json"))
        val hello = Wire.parseJson(text.toByteArray()).getJSONObject("senderHello")
        assertFalse(hello.has("code")); assertFalse(hello.has("sessionCode"))
        assertEquals(3, hello.getJSONArray("versions").getInt(0))
        assertEquals(19, Wire.parseJson(text.toByteArray()).getInt("requestSessionId"))
        assertFalse(text.contains("credential")); assertFalse(text.contains("fingerprint"))
        assertThrows(IOException::class.java) { Wire.validate(2, 0, 0, 0, 0, -1, 0) }
    }
    @Test fun boundaryLengthsCheckedWithoutAllocating() {
        Wire.validate(Wire.VIDEO_FRAME, 1, 1, 1, 1, 0, Wire.MAX_VIDEO)
        Wire.validate(Wire.AUDIO_PACKET, 0, 1, 2, 1, 0, Wire.MAX_AUDIO)
        assertThrows(IOException::class.java) { Wire.validate(Wire.VIDEO_FRAME, 0, 1, 1, 1, 0, Wire.MAX_VIDEO + 1) }
        assertThrows(IOException::class.java) { Wire.validate(Wire.AUDIO_PACKET, 0, 1, 2, 1, 0, 0) }
        assertThrows(IOException::class.java) { Wire.validate(Wire.PING, 0, 1, 0, 0, 0, 4) }
    }
    @Test fun oldVersionTlsAndAuthFailBeforePayloadAllocationOrLongReads() {
        val old = unhex("534350590002")
        val tls = byteArrayOf(0x16, 0x03, 0x01, 0x00)
        assertTrue(assertThrows(IOException::class.java) { Wire.read(ByteArrayInputStream(old)) }.message.orEmpty().startsWith("VERSION"))
        assertTrue(assertThrows(IOException::class.java) { Wire.read(ByteArrayInputStream(tls)) }.message.orEmpty().contains("TLS"))
        val auth = ByteArrayOutputStream().also { out ->
            java.io.DataOutputStream(out).apply {
                writeInt(0x53435059); writeShort(3); writeShort(2); writeInt(Int.MAX_VALUE)
                writeInt(0); writeLong(0); writeInt(0); writeInt(0); writeLong(-1)
            }
        }.toByteArray()
        val error = assertThrows(IOException::class.java) { Wire.read(ByteArrayInputStream(auth)) }
        assertTrue(error.message.orEmpty().contains("reserved"))
    }
    companion object {
        fun unhex(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}

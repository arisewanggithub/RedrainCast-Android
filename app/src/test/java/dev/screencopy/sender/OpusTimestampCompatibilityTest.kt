package dev.screencopy.sender

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class OpusTimestampCompatibilityTest {
    private fun fixture() = Wire.parseJson(Files.readAllBytes(
        fixturePath("opus-pts-order.json")))

    private fun packet(pts: Long, generation: Int = 1, track: Int = 2) =
        Wire.Message(if (track == 1) Wire.VIDEO_FRAME else Wire.AUDIO_PACKET,
            0, 1, track, generation, pts, byteArrayOf(0xf8.toByte(), 0xff.toByte(), 0xfe.toByte()))

    private fun configured(codec: String = "opus"): MediaBudget =
        MediaBudget(true, System::nanoTime, 1L).apply {
            offer(Wire.config(1, 1, 1, Wire.obj("codec" to "h264")))
            offer(Wire.config(1, 2, 1, Wire.obj("codec" to codec)))
        }

    private fun ready(mux: MediaBudget) {
        val configs = listOf(checkNotNull(mux.poll(1)), checkNotNull(mux.poll(1)))
        assertEquals(setOf(1, 2), configs.map { it.track }.toSet())
        assertTrue(configs.all { it.type == Wire.STREAM_CONFIG })
        mux.ready(1, 1)
        assertNull(mux.poll(1))
        mux.ready(2, 1)
    }

    @Test fun sharedFixtureLimitsSourcePtsExceptionToNonnegativeOpus() {
        val fixture = fixture()
        assertEquals(Wire.VERSION, fixture.getInt("wireVersion"))
        assertEquals(1L, fixture.getLong("session"))
        assertEquals(1, fixture.getInt("generation"))
        val cases = fixture.getJSONArray("cases")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val mux = configured(case.getString("codec"))
            val pts = case.getJSONArray("ptsUs")
            val track = case.getInt("track")
            val accepted = mutableListOf<Wire.Message>()
            if (case.getBoolean("accepted")) {
                for (n in 0 until pts.length()) packet(pts.getLong(n), track = track).also {
                    mux.offer(it)
                    accepted.add(it)
                }
                assertEquals(accepted.size * 3, mux.queuedBytes())
                ready(mux)
                for (message in accepted) assertSame(message, mux.poll(1))
                assertEquals(0, mux.queuedBytes())
                assertNull(mux.poll(1))
            } else {
                for (n in 0 until pts.length() - 1) mux.offer(packet(pts.getLong(n), track = track))
                val error = assertThrows(case.getString("name"), SenderProtocolException::class.java) {
                    mux.offer(packet(pts.getLong(pts.length() - 1), track = track))
                }
                assertEquals(CaptureProtocolReason.PTS_DECREASE, error.evidence.reason)
            }
        }
    }

    @Test fun actualAdapterMuxAndWirePreserveEveryOpusPacketAndSourcePts() {
        val fixture = fixture()
        val cases = fixture.getJSONArray("cases")
        val config = fixture.getJSONObject("opusConfig")
        val csd = java.util.Base64.getDecoder().decode(config.getJSONArray("csd").getString(0))
        val payload = WireTest.unhex(fixture.getString("opusPacketHex"))
        assertEquals(3, payload.size)
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            if (case.getString("codec") != "opus" || !case.getBoolean("accepted")) continue
            val timestamps = case.getJSONArray("ptsUs")
            val source = ByteArrayOutputStream()
            DataOutputStream(source).apply {
                writeInt(0x6f707573)
                writeLong(Long.MIN_VALUE); writeInt(csd.size); write(csd)
                for (n in 0 until timestamps.length()) {
                    writeLong(timestamps.getLong(n)); writeInt(payload.size); write(payload)
                }
            }
            val mux = MediaBudget(true)
            mux.offer(Wire.config(1, 1, 1, Wire.obj("codec" to "h264")))
            val admitted = mutableListOf<Wire.Message>()
            var configs = 0
            assertThrows(EOFException::class.java) {
                ScrcpyAdapter.read(ByteArrayInputStream(source.toByteArray()), 2,
                    Settings().apply { audioCodec = "opus" }, 1, object : ScrcpyAdapter.Sink {
                        override fun config(track: Int, generation: Int, config: JSONObject) {
                            configs++
                            assertEquals("opus", config.getString("codec"))
                            mux.offer(Wire.config(1, track, generation, config))
                        }
                        override fun packet(packet: Wire.Message) {
                            mux.offer(packet)
                            admitted.add(packet)
                        }
                    })
            }
            assertEquals(1, configs)
            assertEquals(timestamps.length(), admitted.size)
            assertEquals(timestamps.length() * payload.size, mux.queuedBytes())
            val wire = ByteArrayOutputStream()
            repeat(2) { Wire.write(wire, checkNotNull(mux.poll(1))) }
            assertNull(mux.poll(1))
            mux.ready(1, 1)
            assertNull(mux.poll(1))
            mux.ready(2, 1)
            for (message in admitted) {
                val output = checkNotNull(mux.poll(1))
                assertSame(message, output)
                Wire.write(wire, output)
            }
            assertNull(mux.poll(1))
            assertEquals(0, mux.queuedBytes())
            val input = ByteArrayInputStream(wire.toByteArray())
            repeat(2) { assertEquals(Wire.STREAM_CONFIG, Wire.read(input).type) }
            for (n in 0 until timestamps.length()) {
                val output = Wire.read(input)
                assertEquals(Wire.AUDIO_PACKET, output.type)
                assertEquals(2, output.track)
                assertEquals(1, output.generation)
                assertEquals(timestamps.getLong(n), output.pts)
                assertArrayEquals(payload, output.payload)
            }
            assertEquals(0, input.available())
        }
    }

    @Test fun sourceSpanUsesAllExtremaAndKeepsSignedPreviousPacketDiagnostics() {
        val mux = configured()
        ready(mux)
        listOf(1_000_000L, 1_400_000L, 1_200_000L, 900_000L).forEach { mux.offer(packet(it)) }
        assertEquals(500_000L, mux.queuedSpanUs(2))
        val error = assertThrows(SenderCongestionException::class.java) { mux.offer(packet(899_999)) }
        assertEquals(SenderLimit.PTS_SPAN, error.snapshot.limit)
        assertEquals(500_000L, error.snapshot.queuedSpanUs)
        assertEquals(500_001L, error.snapshot.attemptedSpanUs)
        assertEquals(-1L, error.snapshot.sourceStepUs)
        assertEquals(12, mux.queuedBytes())
        assertEquals(1_000_000L, mux.poll(1)?.pts)
        assertEquals(500_000L, mux.queuedSpanUs(2))
        assertEquals(1_400_000L, mux.poll(1)?.pts)
        assertEquals(300_000L, mux.queuedSpanUs(2))
        mux.offer(packet(700_000))
        assertEquals(500_000L, mux.queuedSpanUs(2))
        assertThrows(SenderCongestionException::class.java) { mux.offer(packet(699_999)) }
    }

    @Test fun backwardsSpanBoundaryAndConfigurationPtsDoNotEscapeBudget() {
        val mux = configured()
        mux.offer(packet(1_000_000)); mux.offer(packet(500_000))
        assertEquals(500_000L, mux.queuedSpanUs(2))
        val error = assertThrows(SenderCongestionException::class.java) { mux.offer(packet(499_999)) }
        assertEquals(500_001L, error.snapshot.attemptedSpanUs)
        assertEquals(6, error.snapshot.trackBytes)
        assertEquals(6, mux.queuedBytes())
    }

    @Test fun onlyExactAudioOpusConfigurationEnablesCompatibility() {
        for (codec in listOf<Any?>(null, JSONObject.NULL, true, 1, "OPUS", "opus ", "aac", "raw",
            Wire.obj("codec" to "opus"))) {
            val mux = configured()
            mux.offer(Wire.config(1, 2, 2, if (codec == null) JSONObject() else Wire.obj("codec" to codec)))
            mux.offer(packet(1_000_000, 2))
            assertThrows(SenderProtocolException::class.java) { mux.offer(packet(999_551, 2)) }
        }
        val video = configured()
        video.offer(Wire.config(1, 1, 2, Wire.obj("codec" to "opus")))
        video.offer(packet(1_000_000, 2, 1))
        assertThrows(SenderProtocolException::class.java) { video.offer(packet(999_551, 2, 1)) }
        val malformed = configured()
        assertThrows(IOException::class.java) {
            malformed.offer(Wire.config(1, 2, 2, Wire.obj("codec" to "opus"))
                .copy(payload = """{"codec":"opus","codec":"aac"}""".toByteArray()))
        }
    }

    @Test fun codecPolicyFollowsAcceptedGenerationWithoutWeakeningGenerationOrReadyRules() {
        val mux = configured("aac")
        mux.offer(packet(1_000_000))
        mux.offer(Wire.config(1, 2, 2, Wire.obj("codec" to "opus")))
        mux.offer(packet(1_000_000, 2)); mux.offer(packet(999_551, 2))
        val stale = assertThrows(SenderProtocolException::class.java) { mux.offer(packet(999_550, 1)) }
        assertEquals(CaptureProtocolReason.MEDIA_GENERATION, stale.evidence.reason)
        assertEquals(-1L, stale.evidence.ptsDeltaUs)
        val skipped = assertThrows(SenderProtocolException::class.java) {
            mux.offer(Wire.config(1, 2, 4, Wire.obj("codec" to "opus")))
        }
        assertEquals(CaptureProtocolReason.CONFIG_SEQUENCE, skipped.evidence.reason)
        val unannounced = assertThrows(SenderProtocolException::class.java) { mux.ready(2, 2) }
        assertEquals(CaptureProtocolReason.READY_MISMATCH, unannounced.evidence.reason)
        mux.offer(Wire.config(1, 2, 3, Wire.obj("codec" to "aac")))
        mux.offer(packet(1_000_000, 3))
        assertThrows(SenderProtocolException::class.java) { mux.offer(packet(999_999, 3)) }
    }

    @Test fun negativePtsAndReservedAudioFlagsRemainInvalidOnWire() {
        val mux = configured()
        assertThrows(SenderProtocolException::class.java) { mux.offer(packet(-1)) }
        for (invalid in listOf(packet(-1), packet(1).copy(flags = 1),
            packet(1, generation = 0), packet(1).copy(track = 1), packet(1, track = 1).copy(flags = 8))) {
            assertThrows(IOException::class.java) { Wire.write(ByteArrayOutputStream(), invalid) }
        }
    }
}

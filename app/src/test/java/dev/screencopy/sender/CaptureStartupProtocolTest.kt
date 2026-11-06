package dev.screencopy.sender

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CaptureStartupProtocolTest {
    private class Tail : InputStream() {
        private val ended = CountDownLatch(1)
        override fun read(): Int { ended.await(); return -1 }
        override fun close() { ended.countDown() }
    }
    private fun packet(out: DataOutputStream, pts: Long, data: ByteArray, config: Boolean = false, key: Boolean = false) {
        out.writeLong(if (config) Long.MIN_VALUE else pts or if (key) 1L shl 62 else 0)
        out.writeInt(data.size); out.write(data)
    }
    private fun video(before: Boolean, descending: Boolean = false): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(0x68323635); out.writeInt(872); out.writeInt(1920)
            val csd = BackendTest().h265Config(872, 1920)
            packet(out, 0, csd, config = true)
            if (before) packet(out, 0, csd, config = true)
            val frame = byteArrayOf(0, 0, 0, 1, 0x26, 1, 0x80.toByte())
            packet(out, 1_000_000, frame, key = true)
            if (!before) packet(out, 0, csd, config = true)
            packet(out, if (descending) 999_999 else 1_020_000, frame, key = true)
            packet(out, 0, BackendTest().h265Config(1920, 872), config = true)
            packet(out, 1_040_000, frame, key = true)
            packet(out, 1_060_000, frame)
        }
    }.toByteArray()
    private fun audio(codec: String, descending: Boolean = false): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(when (codec) { "opus" -> 0x6f707573; "aac" -> 0x00616163; else -> 0x00726177 })
            if (codec != "raw") {
                val csd = if (codec == "opus") WireTest.unhex("4f707573486561640102380180bb0000000000")
                    else byteArrayOf(0x11, 0x90.toByte())
                packet(out, 0, csd, config = true); packet(out, 0, csd, config = true)
            }
            repeat(4) { index ->
                packet(out, if (descending && index == 1) 999_999 else 1_000_000L + index * 20_000,
                    if (codec == "raw") ByteArray(3840) else byteArrayOf(0xf8.toByte(), 0xff.toByte(), 0xfe.toByte()))
            }
        }
    }.toByteArray()
    @Test fun exactMuxProtocolReasonsRemainStrictWithoutGuessingCodec() {
        fun mux() = MediaBudget(false).apply { offer(Wire.config(1, 1, 1, Wire.obj("codec" to "h265"))) }
        val sequence = assertThrows(SenderProtocolException::class.java) {
            mux().offer(Wire.config(1, 1, 3, Wire.obj("codec" to "h265")))
        }
        assertEquals(CaptureProtocolReason.CONFIG_SEQUENCE, sequence.evidence.reason)
        val generation = assertThrows(SenderProtocolException::class.java) {
            mux().offer(Wire.Message(Wire.VIDEO_FRAME, 1, 1, 1, 2, 100, byteArrayOf(1)))
        }
        assertEquals(CaptureProtocolReason.MEDIA_GENERATION, generation.evidence.reason)
        val ready = assertThrows(SenderProtocolException::class.java) { mux().ready(1, 2) }
        assertEquals(CaptureProtocolReason.READY_MISMATCH, ready.evidence.reason)
    }
    @Test fun actualAdapterPtsDecreaseRetainsTrackFormatGenerationPacketContextAndNoAbsolutePts() {
        for (codec in listOf("aac", "raw")) {
            val settings = Settings().apply { videoCodec = "h265"; audioCodec = codec }
            val mux = MediaBudget(true)
            val error = assertThrows(SenderProtocolException::class.java) {
                ScrcpyAdapter.read(ByteArrayInputStream(audio(codec, true)), 2, settings, 1, object : ScrcpyAdapter.Sink {
                    override fun config(track: Int, generation: Int, config: JSONObject) { mux.offer(Wire.config(1, track, generation, config)) }
                    override fun packet(packet: Wire.Message) { mux.offer(packet) }
                })
            }
            assertEquals(CaptureProtocolReason.PTS_DECREASE, error.evidence.reason)
            assertEquals(CaptureTrack.AUDIO, error.evidence.track)
            assertEquals(-1L, error.evidence.ptsDeltaUs)
            assertEquals(2L, error.evidence.adapter?.mediaPackets)
            val logs = mutableListOf<String>()
            SessionStopTrace(logs::add).captureProtocol(error.evidence, captureFormat(codec),
                SessionStopTrace.Phase.CAPTURE_START, SessionOutcome.Origin.CAPTURE)
            val diagnostic = logs.single { it.startsWith("sender_capture_failure ") }
            assertTrue(diagnostic.contains("reason=PTS_DECREASE"))
            assertTrue(diagnostic.contains("format=${when (codec) { "opus" -> "OPUS"; "aac" -> "AAC"; else -> "PCM" }}"))
            assertFalse(diagnostic.contains("1000000")); assertFalse(diagnostic.contains("999999"))
        }
    }
    @Test fun genericCaptureProtocolFailurePersistsOnlyUnclassifiedContextNotOriginalPrivateText() {
        val logs = mutableListOf<String>()
        val error = SenderProtocolException(CaptureProtocolFailure.unknown(
            IOException("PROTOCOL: vendor-private payload CSD address secret")))
        SessionStopTrace(logs::add).captureProtocol(error.evidence, CaptureFormat.UNKNOWN,
            SessionStopTrace.Phase.CAPTURE_START, SessionOutcome.Origin.CAPTURE)
        val outcome = SessionOutcome()
        outcome.record(error, SessionOutcome.Origin.CAPTURE)
        val diagnostic = logs.single { it.startsWith("sender_capture_failure ") }
        assertTrue(diagnostic.contains("reason=UNCLASSIFIED"))
        val peerMessage = outcome.terminal(1, false).json().getString("message")
        for (privateText in listOf("vendor-private", "payload", "CSD", "address", "secret")) {
            assertFalse(diagnostic.contains(privateText)); assertFalse(peerMessage.contains(privateText))
        }
    }
    @Test fun videoPtsDecreaseWithRepeatedConfigurationHasExactGenerationAndRepeatContext() {
        val mux = MediaBudget(false)
        val error = assertThrows(SenderProtocolException::class.java) {
            ScrcpyAdapter.read(ByteArrayInputStream(video(true, true)), 1, Settings().apply { videoCodec = "h265" }, 1,
                object : ScrcpyAdapter.Sink {
                    override fun config(track: Int, generation: Int, config: JSONObject) { mux.offer(Wire.config(1, track, generation, config)) }
                    override fun packet(packet: Wire.Message) { mux.offer(packet) }
                })
        }
        assertEquals(CaptureProtocolReason.PTS_DECREASE, error.evidence.reason)
        assertEquals(CaptureTrack.VIDEO, error.evidence.track)
        assertEquals(2, error.evidence.generation)
        assertEquals(true, error.evidence.adapter?.repeatedConfig)
        assertEquals(2L, error.evidence.adapter?.configPackets)
        assertEquals(CapturePacketKind.KEY_FRAME, error.evidence.adapter?.kind)
    }
    @Test fun malformedLengthAndMediaBeforeConfigStillFailInsteadOfBeingIgnored() {
        for (length in listOf(0, Wire.MAX_VIDEO + 1)) {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).apply {
                writeInt(0x68323635); writeInt(872); writeInt(1920); writeLong(1_000_000); writeInt(length)
            }
            assertThrows(IOException::class.java) {
                ScrcpyAdapter.read(ByteArrayInputStream(bytes.toByteArray()), 1, Settings().apply { videoCodec = "h265" }, 1,
                    object : ScrcpyAdapter.Sink {
                        override fun config(track: Int, generation: Int, config: JSONObject) { fail("Unexpected config") }
                        override fun packet(packet: Wire.Message) { fail("Unexpected packet") }
                    })
            }
        }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply {
            writeInt(0x68323635); writeInt(872); writeInt(1920)
            packet(this, 1_000_000, byteArrayOf(0, 0, 0, 1, 0x26, 1))
        }
        val error = assertThrows(IOException::class.java) {
            ScrcpyAdapter.read(ByteArrayInputStream(bytes.toByteArray()), 1, Settings().apply { videoCodec = "h265" }, 1,
                object : ScrcpyAdapter.Sink {
                    override fun config(track: Int, generation: Int, config: JSONObject) = Unit
                    override fun packet(packet: Wire.Message) { fail("Media without config") }
                })
        }
        assertTrue(error.message!!.contains("before codec initialization"))
    }
}

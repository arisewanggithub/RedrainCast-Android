package dev.redrain.cast.diagnostics

import org.json.JSONObject
import org.json.JSONArray
import org.screencopy.receiver.protocol.StrictJson
import org.screencopy.receiver.protocol.protocolRequire
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/** Framing and source identity checks shared by the diagnostic proxy endpoints. */
internal object DiagnosticProxyWire {
    const val FRAME_BYTES = 16_384
    const val SOURCE_DEPTH = 7
    const val LANE_DEPTH = 8
    const val FETCH_DEPTH = 11

    data class Binding(val pairId: String, val session: Long, val generation: Long, val captureId: String)
    data class ReceivedFrame(val value: JSONObject, val bytes: Int)

    fun validateIdentity(frame: JSONObject, binding: Binding) {
        validateFrame(frame)
        protocolRequire(frame.opt("pairId") == binding.pairId &&
            frame.opt("captureId") == binding.captureId &&
            decimal(frame.opt("session")) == binding.session &&
            decimal(frame.opt("armGeneration")) == binding.generation,
            "Diagnostic lane identity mismatch")
    }

    fun readFrame(input: InputStream): JSONObject? = readFrameWithSize(input)?.value

    fun readFrameWithSize(input: InputStream): ReceivedFrame? {
        val first = input.read()
        if (first < 0) return null
        val header = ByteArray(4)
        header[0] = first.toByte()
        readFully(input, header, 1, 3)
        val length = ((header[0].toLong() and 255) shl 24) or
            ((header[1].toLong() and 255) shl 16) or
            ((header[2].toLong() and 255) shl 8) or (header[3].toLong() and 255)
        protocolRequire(length in 1..FRAME_BYTES.toLong(), "Invalid diagnostic frame length")
        val payload = ByteArray(length.toInt())
        readFully(input, payload, 0, payload.size)
        return ReceivedFrame(validateFrame(StrictJson.decode(payload, maxDepth = LANE_DEPTH, trackSizes = false)),
            payload.size + 4)
    }

    fun writeFrame(output: OutputStream, frame: JSONObject) {
        validateFrame(frame)
        val bytes = frame.toString().toByteArray(Charsets.UTF_8)
        protocolRequire(bytes.size in 1..FRAME_BYTES, "Invalid diagnostic frame length")
        validateFrame(StrictJson.decode(bytes, maxDepth = LANE_DEPTH, trackSizes = false))
        val length = bytes.size
        output.write(byteArrayOf((length ushr 24).toByte(), (length ushr 16).toByte(),
            (length ushr 8).toByte(), length.toByte()))
        output.write(bytes)
        output.flush()
    }

    private fun validateFrame(frame: JSONObject): JSONObject {
        protocolRequire(frame.opt("v") is Int && frame.opt("v") == 2, "Unsupported diagnostic lane version")
        protocolRequire(frame.opt("type") in setOf("RECORD", "LOSS", "CLOCK_REQUEST", "CLOCK_RESPONSE", "END", "ENDED",
            "AUDIO_PRESSURE"),
            "Invalid diagnostic lane message type")
        return frame
    }

    private fun readFully(input: InputStream, bytes: ByteArray, offset: Int, length: Int) {
        var position = offset
        val end = offset + length
        while (position < end) {
            val count = input.read(bytes, position, end - position)
            if (count < 0) throw EOFException("Truncated diagnostic frame")
            if (count == 0) {
                val value = input.read()
                if (value < 0) throw EOFException("Truncated diagnostic frame")
                bytes[position++] = value.toByte()
            } else position += count
        }
    }

    fun decimal(value: Any?): Long {
        val raw = value as? String
            ?: throw org.screencopy.receiver.protocol.ProtocolException("PROTOCOL", "Invalid diagnostic int64")
        protocolRequire(raw.matches(Regex("0|[1-9][0-9]{0,18}")), "Invalid diagnostic int64")
        return raw.toLongOrNull()
            ?: throw org.screencopy.receiver.protocol.ProtocolException("PROTOCOL", "Diagnostic int64 overflow")
    }

    /**
     * Validates without changing the source record. A staged pre-bind record
     * remains unbound even if the pair has bound since it was produced.
     */
    fun validateSource(record: JSONObject, role: String, session: String?, captureId: String,
                       videoTiming: Boolean, audioTiming: Boolean = false,
                       audioWork: Boolean = false, audioDetail: Boolean = false, audioOwner: Boolean = false): String? {
        val encoded = record.toString().toByteArray(Charsets.UTF_8)
        StrictJson.decode(encoded, maxDepth = SOURCE_DEPTH, trackSizes = false)
        val timing = videoTiming && record.opt("kind") == "VIDEO_PROGRESS" &&
            record.optJSONObject("data")?.optJSONObject("metrics")?.opt("schema") == "video_frame_timing"
        protocolRequire(encoded.size <= if (timing) DiagnosticRecorder.MAX_VIDEO_TIMING_RECORD_BYTES
            else DiagnosticRecorder.MAX_RECORD_BYTES, "Oversized diagnostic record")
        val keys = record.keys().asSequence().toSet()
        protocolRequire(keys.containsAll(setOf("captureId", "seq", "monoNs", "kind", "data")) &&
            keys.all { it in setOf("captureId", "seq", "monoNs", "kind", "data", "role", "session", "track", "generation") },
            "Invalid diagnostic record fields")
        protocolRequire(record.opt("captureId") == captureId, "Diagnostic capture mismatch")
        protocolRequire(decimal(record.opt("seq")) > 0, "Invalid diagnostic sequence")
        decimal(record.opt("monoNs"))
        protocolRequire((record.opt("kind") as? String)?.isNotEmpty() == true &&
            record.opt("data") is JSONObject, "Invalid diagnostic record")
        val data = record.optJSONObject("data")
            ?: throw org.screencopy.receiver.protocol.ProtocolException("PROTOCOL", "Missing diagnostic data")
        if (record.opt("kind") == "AUDIO_TIMING") {
            protocolRequire(role == "receiver" && audioTiming &&
                data.keys().asSequence().toSet() ==
                    setOf("schema", "events", "attempted", "dropped", "droppedBeforeSequence", "batchDropped") &&
                data.opt("schema") in setOf("audio_timing_v1", "audio_timing_v2", "audio_timing_v3", "audio_timing_v4"), "Invalid audio timing record")
            val ownerSchema = data.opt("schema") == "audio_timing_v4"
            val detailSchema = data.opt("schema") == "audio_timing_v3" || ownerSchema
            val workSchema = data.opt("schema") != "audio_timing_v1"
            protocolRequire(!workSchema || audioWork, "Unsolicited audio timing work")
            protocolRequire(!detailSchema || audioDetail, "Unsolicited audio timing detail")
            protocolRequire(!ownerSchema || audioOwner, "Unsolicited audio timing owner")
            val maxType = if (ownerSchema) 31 else if (detailSchema) 29 else if (workSchema) 24 else 21
            val attempted = decimal(data.opt("attempted"))
            protocolRequire(decimal(data.opt("dropped")) <= attempted &&
                decimal(data.opt("batchDropped")) <= attempted, "Invalid audio timing loss")
            decimal(data.opt("droppedBeforeSequence"))
            val events = data.opt("events") as? JSONArray
                ?: throw org.screencopy.receiver.protocol.ProtocolException("PROTOCOL", "Missing audio timing events")
            protocolRequire(events.length() in 1..8, "Invalid audio timing event count")
            val sequences = HashSet<Long>()
            for (index in 0 until events.length()) {
                val event = events.opt(index) as? JSONArray
                    ?: throw org.screencopy.receiver.protocol.ProtocolException("PROTOCOL", "Invalid audio timing event")
                protocolRequire(event.length() == 7, "Invalid audio timing event")
                val sequence = decimal(event.opt(0))
                protocolRequire(sequence in 1..attempted && sequences.add(sequence), "Invalid audio timing event sequence")
                decimal(event.opt(1))
                protocolRequire(event.opt(2) is Int && event.getInt(2) > 0, "Invalid audio timing generation")
                decimal(event.opt(3))
                protocolRequire(event.opt(4) is Int && event.getInt(4) in 1..maxType,
                    "Invalid audio timing event type")
                for (field in 5..6) {
                    val raw = event.opt(field) as? String
                    protocolRequire(raw != null && raw.matches(Regex("0|-?[1-9][0-9]{0,18}")) &&
                        raw.toLongOrNull() != null, "Invalid audio timing value")
                }
                if (event.getInt(4) in 22..24) {
                    protocolRequire(event.getString(5).toLong() in 1..(if (detailSchema) 14 else 12) &&
                        event.getString(6).toLong() >= -1, "Invalid audio timing work boundary")
                }
                if (event.getInt(4) in 30..31) {
                    protocolRequire(event.getString(5).toLong() > 0 && event.getString(6).toLong() > 0,
                        "Invalid audio timing owner boundary")
                }
            }
        }
        if (record.opt("kind") == "CAPTURE_ENDED") {
            val summary = data.opt("audioTiming")
            protocolRequire((summary != null) == (role == "receiver" && audioTiming),
                "Invalid audio timing terminal selection")
            if (summary != null) {
                protocolRequire(summary is JSONObject && summary.keys().asSequence().toSet() ==
                    setOf("attempted", "dropped", "droppedBeforeSequence", "batchDropped",
                        "producersAtClose", "pendingProducers"), "Invalid audio timing terminal")
                summary as JSONObject
                val attempted = decimal(summary.opt("attempted"))
                protocolRequire(decimal(summary.opt("dropped")) <= attempted &&
                    decimal(summary.opt("batchDropped")) <= attempted, "Invalid audio timing terminal loss")
                decimal(summary.opt("droppedBeforeSequence"))
                decimal(summary.opt("producersAtClose"))
                decimal(summary.opt("pendingProducers"))
            }
        }
        if (data.has("operationId")) decimal(data.opt("operationId"))
        if (data.has("inFlight")) {
            val inFlight = data.opt("inFlight") as? JSONArray
                ?: throw org.screencopy.receiver.protocol.ProtocolException("PROTOCOL", "Invalid in-flight operations")
            for (index in 0 until inFlight.length()) {
                val operation = inFlight.opt(index) as? JSONObject
                    ?: throw org.screencopy.receiver.protocol.ProtocolException("PROTOCOL", "Invalid in-flight operation")
                decimal(operation.opt("operationId"))
            }
        }
        if (data.has("metrics"))
            protocolRequire(data.opt("metrics") is JSONObject, "Invalid diagnostic metrics")
        data.optJSONObject("metrics")?.let { metrics ->
            if (metrics.has("slowExampleOperationId") && !metrics.isNull("slowExampleOperationId"))
                decimal(metrics.opt("slowExampleOperationId"))
        }
        val bound = record.has("role") && record.has("session")
        protocolRequire(record.opt("kind") != "AUDIO_TIMING" || bound, "Unbound audio timing")
        protocolRequire(record.has("role") == record.has("session"), "Incomplete source identity")
        if (bound) {
            protocolRequire(record.opt("role") == role && session != null && record.opt("session") == session,
                "Diagnostic source identity mismatch")
            decimal(record.opt("session"))
        }
        protocolRequire(record.opt("kind") != "SESSION_BOUND" || bound, "Unbound SESSION_BOUND")
        protocolRequire(record.has("track") == record.has("generation"), "Incomplete track identity")
        if (record.has("track")) {
            protocolRequire(record.opt("track") is Int && record.opt("generation") is Int,
                "Invalid track identity")
        }
        return if (bound) session else null
    }
}

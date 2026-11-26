package dev.redrain.cast.diagnostics

import org.json.JSONArray
import org.json.JSONObject
import org.screencopy.receiver.protocol.StrictJson
import org.screencopy.receiver.protocol.protocolRequire

/** Bounded decoder for saved v1 reports only; this format is never negotiated or sent on the wire. */
internal object IncidentProtocol {
    const val VERSION = 1
    const val MAX_BYTES = 28 * 1024
    const val MAX_FRAMES = IncidentHistory.HISTORY_SECONDS + 12
    private val idPattern = Regex("[0-9a-f]{32}")

    fun validId(value: Any?): Boolean = value is String && idPattern.matches(value)

    fun snapshot(id: String, data: JSONObject): JSONObject {
        require(idPattern.matches(id))
        return JSONObject().put("version", VERSION).put("id", id)
            .put("action", "snapshot").put("snapshot", data)
    }

    fun parse(value: Any?, expectedRole: String, evidence: Boolean = false): JSONObject {
        protocolRequire(value is JSONObject, "Invalid legacy incident report")
        val envelope = value as JSONObject
        protocolRequire(StrictJson.encodedSize(envelope) <= MAX_BYTES &&
            envelope.opt("version") == VERSION && validId(envelope.opt("id")) &&
            envelope.opt("action") == "snapshot", "Invalid legacy incident envelope")
        keys(envelope, setOf("version", "id", "action", "snapshot"))
        return validateSnapshot(envelope.opt("snapshot"), expectedRole, evidence)
    }

    private fun validateSnapshot(value: Any?, role: String, evidence: Boolean): JSONObject {
        protocolRequire(value is JSONObject, "Invalid incident snapshot")
        val input = value as JSONObject
        keys(input, setOf("role", "final", "frames", "configuration", "droppedEvents", "omittedFrames", "omittedRecords") +
            if (evidence) setOf("evidence", "omittedFrameRanges") else emptySet())
        protocolRequire(input.opt("role") == role, "Incident role mismatch")
        protocolRequire(input.opt("final") is Boolean, "Invalid incident final flag")
        val frames = input.opt("frames")
        protocolRequire(frames is JSONArray && frames.length() <= MAX_FRAMES,
            "Invalid incident frames")
        val configuration = input.opt("configuration")
        protocolRequire(configuration is JSONArray && configuration.length() <= 3,
            "Invalid incident configuration")
        val configCopy = JSONArray()
        val configTracks = mutableSetOf<Int>()
        for (i in 0 until (configuration as JSONArray).length()) {
            val config = record(configuration.opt(i), 9, true, role)
            protocolRequire(config.getInt(1) == DiagnosticKind.SESSION.ordinal + 1 &&
                config.getInt(2) == DiagnosticStage.CONFIG.ordinal &&
                configTracks.add(config.getInt(3)), "Invalid incident configuration")
            configCopy.put(config)
        }
        val copy = JSONArray()
        for (i in 0 until (frames as JSONArray).length()) {
            val frame = frames.opt(i)
            protocolRequire(frame is JSONArray && frame.length() == 3, "Invalid incident frame")
            frame as JSONArray
            val samples = frame.opt(1)
            val events = frame.opt(2)
            protocolRequire(samples is JSONArray &&
                samples.length() <= DiagnosticVideoStage.entries.size + DiagnosticKind.entries.size * 6 &&
                events is JSONArray && events.length() <= IncidentHistory.EVENT_CAPACITY,
                "Invalid incident records")
            val sampleCopy = JSONArray()
            val eventCopy = JSONArray()
            for (j in 0 until (samples as JSONArray).length())
                sampleCopy.put(record(samples.opt(j), 8, false, role))
            for (j in 0 until (events as JSONArray).length())
                eventCopy.put(record(events.opt(j), 9, true, role))
            val at = longValue(frame.opt(0))
            protocolRequire(at.toLong() >= 0, "Invalid incident timestamp")
            copy.put(JSONArray().put(at).put(sampleCopy).put(eventCopy))
        }
        val dropped = longValue(input.opt("droppedEvents"))
        protocolRequire(dropped.toLong() >= 0, "Invalid incident loss count")
        val result = JSONObject().put("role", role).put("final", input.getBoolean("final"))
            .put("frames", copy).put("configuration", configCopy).put("droppedEvents", dropped)
            .put("omittedFrames", integer(input.opt("omittedFrames"), 0, IncidentHistory.HISTORY_SECONDS * 2 + 9))
            .put("omittedRecords", integer(input.opt("omittedRecords"), 0,
                IncidentHistory.EVENT_CAPACITY + DiagnosticVideoStage.entries.size + DiagnosticKind.entries.size * 6))
        if (input.has("evidence")) result.put("evidence", IncidentEvidence.validate(input.opt("evidence")))
        if (input.has("omittedFrameRanges")) {
            val ranges = input.optJSONArray("omittedFrameRanges")
            protocolRequire(ranges != null && ranges.length() <= MAX_FRAMES + 6, "Invalid omitted ranges")
            for (i in 0 until checkNotNull(ranges).length()) {
                val range = ranges.optJSONArray(i)
                protocolRequire(range != null && range.length() == 3, "Invalid omitted range")
                protocolRequire(longValue(range!!.opt(0)).toLong() >= 0 &&
                    longValue(range.opt(1)).toLong() >= range.getString(0).toLong(),
                    "Invalid omitted range bounds")
                integer(range.opt(2), 1, 49)
            }
            result.put("omittedFrameRanges", JSONArray(ranges.toString()))
        }
        return result
    }

    private fun record(value: Any?, size: Int, detail: Boolean, role: String): JSONArray {
        protocolRequire(value is JSONArray && value.length() == size, "Invalid incident record")
        value as JSONArray
        val output = JSONArray()
        for (i in 0 until size) {
            val item = value.opt(i)
            when {
                detail && i == size - 1 -> {
                    protocolRequire(item is JSONArray && item.length() <= 20, "Invalid incident details")
                    val array = JSONArray()
                    for (j in 0 until (item as JSONArray).length()) {
                        val number = longValue(item.opt(j))
                        protocolRequire(number.toLong() in -1_000_000_000_000_000L..1_000_000_000_000_000_000L,
                            "Incident detail outside bounds")
                        array.put(number)
                    }
                    output.put(array)
                }
                i == 1 -> output.put(integer(item, 0, DiagnosticKind.entries.size + 1))
                i == 2 -> output.put(integer(item, 0,
                    maxOf(DiagnosticStage.entries.size, DiagnosticVideoStage.entries.size) - 1))
                i == 3 -> output.put(integer(item, 0, 2))
                i == 4 -> output.put(integer(item, 0, Int.MAX_VALUE))
                detail && i == 7 -> output.put(integer(item, 0, 64))
                else -> output.put(longValue(item))
            }
        }
        protocolRequire(output.getString(0).toLong() >= 0, "Invalid incident timestamp")
        if (detail) validateEvent(output, role)
        return output
    }

    private fun validateEvent(record: JSONArray, role: String) {
        val kind = record.getInt(1)
        val stage = record.getInt(2)
        val track = record.getInt(3)
        val reason = record.getInt(7)
        val count = record.getJSONArray(8).length()
        val session = DiagnosticKind.SESSION.ordinal + 1
        val recovery = DiagnosticKind.RECOVERY.ordinal + 1
        val queue = DiagnosticKind.entries.size + 1
        val values = record.getJSONArray(8)
        fun number(index: Int) = values.getString(index).toLong()
        val valid = when {
            kind == session && stage == DiagnosticStage.CONFIG.ordinal -> count == 5 && reason == 0 &&
                number(0) in 0..6 && if (track == 0) number(1) in 0..6 && number(2) in 0..240 &&
                    number(3) in 0..Int.MAX_VALUE.toLong() && number(4) in -1..Int.MAX_VALUE.toLong()
                else track in 1..2 && number(1) in 0..16384 && number(2) in 0..16384 &&
                    number(3) in 0..384000 && number(4) in 0..32
            kind == queue && stage == DiagnosticStage.SAMPLE.ordinal -> track in 1..2 &&
                (role == "sender" && count == 10 || role == "receiver" && count == 5) && reason == 0
            kind == recovery && stage == DiagnosticStage.PRESSURE.ordinal ->
                count == 1 && reason < DiagnosticReason.entries.size ||
                    role == "sender" && count == 17 && reason <= 4 ||
                    role == "receiver" && count == 8 && reason <= 2
            kind == recovery && stage == DiagnosticStage.FAILED.ordinal ->
                count == 1 && reason < DiagnosticReason.entries.size ||
                    count == 2 && reason < DiagnosticPoint.entries.size ||
                    role == "sender" && count == 8 && reason == 0 ||
                    role == "receiver" && count == 11 && reason == 0
            kind == recovery && stage in setOf(DiagnosticStage.RESET.ordinal, DiagnosticStage.READY.ordinal,
                DiagnosticStage.COMPLETE.ordinal) && count == 1 -> reason < DiagnosticReason.entries.size
            kind in 1..DiagnosticKind.entries.size &&
                stage in setOf(DiagnosticStage.BEGIN.ordinal, DiagnosticStage.COMPLETE.ordinal,
                    DiagnosticStage.FAILED.ordinal) && count == 2 -> reason < DiagnosticPoint.entries.size
            kind == session && stage == DiagnosticStage.FAILED.ordinal ->
                count == 0 && reason < DiagnosticReason.entries.size
            else -> false
        }
        protocolRequire(valid, "Invalid incident event vocabulary")
    }

    private fun longValue(value: Any?): String {
        protocolRequire(value is String && value.length <= 20 &&
            value.matches(Regex("-?(0|[1-9][0-9]*)")) && value.toLongOrNull() != null,
            "Invalid incident integer")
        return value as String
    }

    private fun integer(value: Any?, min: Int, max: Int): Int {
        protocolRequire(value is Int && value in min..max, "Invalid incident integer")
        return value as Int
    }

    private fun keys(value: JSONObject, allowed: Set<String>) {
        val it = value.keys()
        while (it.hasNext()) protocolRequire(it.next() in allowed, "Unexpected incident field")
    }
}

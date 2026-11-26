package dev.redrain.cast.diagnostics

import org.json.JSONArray
import org.json.JSONObject
import org.screencopy.receiver.protocol.protocolRequire

/** Fixed v1 lifecycle vocabulary shared with session telemetry and legacy-report validation. */
internal object IncidentEvidence {
    enum class Stage {
        REQUEST, CONFIG_ENQUEUED, CONFIG_WRITING, CONFIG_WRITTEN, CONFIG_RECEIVED,
        RECOVERY_QUEUED, RECOVERY_RUNNING, RECOVERY_CANCELLED,
        RETIRE_BEGIN, RETIRE_END, CONSTRUCT_BEGIN, CONSTRUCT_END,
        READY_ENQUEUED, READY_WRITING, READY_WRITTEN, READY_RECEIVED, READY_PROCESSED,
        FIRST_OUTPUT, DEADLINE, TERMINAL, AUDIO_STATE, QUEUE_STATE, ADMISSION_BEGIN, ADMISSION_END,
        DEADLINE_EXPIRED, WORKER_BEGIN, WORKER_END, FORMAT_OBSERVED, READY_WRITE_FAILED, TERMINAL_CAUSE,
        RECOVERY_COMPLETE, READY_CANCELLED
    }

    enum class Cause {
        UNKNOWN, VERSION, BUSY, UNSUPPORTED_CONFIG, PROTOCOL, CAPTURE_FAILED,
        AUDIO_FAILED, DECODER_FAILED, TIMEOUT, CONGESTION, INTERNAL
    }

    fun cause(code: String): Long = Cause.entries.firstOrNull { it.name == code }?.ordinal?.toLong() ?: 0

    fun validate(value: Any?): JSONObject {
        protocolRequire(value is JSONObject, "Invalid incident evidence")
        value as JSONObject
        val allowed = setOf("version", "first", "terminal", "milestones", "latest", "cached", "omitted",
            "omittedRangeNs", "overwrittenRangeUnknown", "droppedFields", "historyOmittedRecords",
            "operations", "sampledAtNs", "journalStartSequence", "journalEndSequence")
        protocolRequire(value.keys().asSequence().all { it in allowed } &&
            value.opt("version") == 1 && value.toString().toByteArray().size <= 9 * 1024,
            "Invalid incident evidence bounds")
        for (name in listOf("first", "terminal", "milestones", "latest", "cached")) {
            val records = value.optJSONArray(name)
            protocolRequire(records != null && records.length() <= Stage.entries.size * 3,
                "Invalid evidence records")
            for (i in 0 until checkNotNull(records).length()) {
                val entry = records.optJSONArray(i)
                protocolRequire(entry != null && entry.length() == 5, "Invalid evidence record")
                entry!!
                protocolRequire(number(entry.opt(0)) >= 0 &&
                    entry.opt(1) is Int && entry.getInt(1) in 0..2 &&
                    entry.opt(2) is Int && entry.getInt(2) >= 0 &&
                    entry.opt(3) is Int && entry.getInt(3) in Stage.entries.indices,
                    "Invalid evidence vocabulary")
                val values = entry.optJSONArray(4)
                protocolRequire(values != null && values.length() <= 12, "Invalid evidence values")
                for (j in 0 until checkNotNull(values).length()) number(values.opt(j))
            }
        }
        protocolRequire(number(value.opt("omitted")) >= 0 &&
            value.opt("overwrittenRangeUnknown") is Boolean &&
            number(value.opt("droppedFields")) >= 0, "Invalid evidence losses")
        if (value.has("historyOmittedRecords"))
            protocolRequire(number(value.opt("historyOmittedRecords")) >= 0, "Invalid history losses")
        if (value.has("journalStartSequence") || value.has("journalEndSequence"))
            protocolRequire(number(value.opt("journalStartSequence")) >= 0 &&
                number(value.opt("journalEndSequence")) >= number(value.opt("journalStartSequence")),
                "Invalid evidence journal window")
        protocolRequire(number(value.opt("sampledAtNs")) >= 0, "Invalid evidence sampling time")
        if (value.has("operations")) {
            val operations = value.optJSONObject("operations")
            protocolRequire(operations != null &&
                operations.keys().asSequence().all { it in setOf("records", "omitted") } &&
                operations.opt("omitted") is Int && operations.getInt("omitted") >= 0,
                "Invalid operation cache")
            val records = operations!!.optJSONArray("records")
            protocolRequire(records != null && records.length() <= 32, "Invalid operation records")
            for (i in 0 until records!!.length()) {
                val record = records.optJSONArray(i)
                protocolRequire(record != null && record.length() == 9, "Invalid operation state")
                protocolRequire(record!!.opt(0) is Int && record.getInt(0) in DiagnosticKind.entries.indices &&
                    record.opt(1) is Int && record.getInt(1) in 0..2 &&
                    record.opt(2) is Int && record.getInt(2) in DiagnosticPoint.entries.indices,
                    "Invalid operation vocabulary")
                for (j in 3..8) number(record.opt(j))
            }
        }
        val range = value.optJSONArray("omittedRangeNs")
        protocolRequire(range != null && range.length() == 2, "Invalid evidence range")
        for (i in 0..1) if (range!!.opt(i) != JSONObject.NULL)
            protocolRequire(number(range.opt(i)) >= 0, "Invalid evidence range")
        return JSONObject(value.toString())
    }

    private fun number(value: Any?): Long {
        protocolRequire(value is String && value.length <= 20 &&
            value.matches(Regex("-?(0|[1-9][0-9]*)")) && value.toLongOrNull() != null,
            "Invalid evidence number")
        return (value as String).toLong()
    }
}

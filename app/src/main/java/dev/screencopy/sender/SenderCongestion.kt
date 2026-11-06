package dev.screencopy.sender

import android.content.Context
import java.io.IOException

internal enum class SenderQueue { CONTROL, VIDEO, AUDIO }
internal enum class SenderLimit { CONTROL_COUNT, CONTROL_BYTES, TRACK_COUNT, MEDIA_BYTES, PTS_SPAN }
internal data class SenderQueueFailure(
    val queue: SenderQueue, val limit: SenderLimit, val count: Int, val countCap: Int,
    val bytes: Int, val byteCap: Int, val incomingBytes: Int, val trackCount: Int, val trackBytes: Int,
    val queuedSpanUs: Long, val attemptedSpanUs: Long, val sourceStepUs: Long, val ageMs: Long,
    val waitMs: Long, val ready: Boolean, val writerQueue: SenderQueue?, val writerBytes: Int,
    val writerMs: Long, val writerAdmittedAgeMs: Long, val lastWriteMs: Long
) {
    fun fields() = "queue=$queue limit=$limit count=$count count_cap=$countCap bytes=$bytes byte_cap=$byteCap" +
        " incoming_bytes=$incomingBytes track_count=$trackCount track_bytes=$trackBytes" +
        " queued_span_us=$queuedSpanUs attempted_span_us=$attemptedSpanUs span_cap_us=500000" +
        " source_step_us=$sourceStepUs age_ms=$ageMs admission_wait_ms=$waitMs ready=$ready" +
        " inflight=${writerQueue ?: "NONE"} inflight_bytes=$writerBytes writer_elapsed_ms=$writerMs" +
        " inflight_admitted_age_ms=$writerAdmittedAgeMs last_write_ms=$lastWriteMs"
}
internal class SenderCongestionException(val snapshot: SenderQueueFailure) : IOException(
    "CONGESTION: ${snapshot.queue} ${snapshot.limit} admission timed out after ${snapshot.waitMs} ms; " +
        "queued ${snapshot.count}/${snapshot.countCap}, bytes ${snapshot.bytes}/${snapshot.byteCap}, " +
        "attempted span ${snapshot.attemptedSpanUs}/500000 us, age ${snapshot.ageMs} ms, writer ${snapshot.writerMs} ms."
)

/** Only controlled failure fields are persisted, never arbitrary exception/peer/media text. */
internal object SenderFailureStore {
    private const val PREFIX = "sender_queue_failure "
    private const val CAPTURE = "sender_capture_failure "
    private const val STARTUP = "sender_startup_failure "
    private const val SESSION = "sender_session_failure "
    private const val STARTED = "sender_session_started "
    // Android JSONObject.getLong coerces decimal strings through Double and rounds session IDs.
    internal fun exactLong(json: org.json.JSONObject, key: String): Long = when (val value = json.opt(key)) {
        is String -> value.takeIf { it.matches(Regex("-?(0|[1-9][0-9]*)")) }?.toLongOrNull()
            ?: throw IOException("Invalid diagnostic integer")
        is Int -> value.toLong()
        else -> throw IOException("Invalid diagnostic integer")
    }
    @Synchronized
    fun recordDiagnostic(context: Context, line: String) {
        if (listOf(PREFIX, CAPTURE, STARTUP, SESSION, STARTED).none { line.startsWith(it) }) return
        val prefs = context.getSharedPreferences("sender-diagnostics", Context.MODE_PRIVATE)
        if (line.startsWith(STARTED)) {
            val fields = Wire.parseJson(line.removePrefix(STARTED).toByteArray(Charsets.UTF_8))
            if (!prefs.edit().putLong("currentSessionId", exactLong(fields, "session"))
                    .putLong("currentSessionStartedAt", exactLong(fields, "timestamp_ms")).commit())
                android.util.Log.w("ScreenCopySender", "diagnostic_store=WRITE_FAILED")
            return
        }
        if (line.startsWith(SESSION)) {
            val text = line.removePrefix(SESSION)
            val fields = Wire.parseJson(text.toByteArray(Charsets.UTF_8))
            val session = exactLong(fields, "session")
            val current = prefs.getLong("currentSessionId", -1)
            if (current != -1L && current != session) return
            if (prefs.getLong("lastSessionFailureId", -1) == session) return
            if (!prefs.edit().putLong("lastSessionFailureId", session).putString("lastSessionFailure", text)
                    .putString("lastFailure", "session $text").commit())
                android.util.Log.w("ScreenCopySender", "diagnostic_store=WRITE_FAILED")
            return
        }
        val kind = when { line.startsWith(PREFIX) -> "queue"; line.startsWith(CAPTURE) -> "capture"
            line.startsWith(STARTUP) -> "startup"; else -> return }
        val fields = line.removePrefix(when (kind) { "queue" -> PREFIX; "capture" -> CAPTURE; else -> STARTUP })
        val editor = prefs.edit()
        editor.putString(when (kind) { "queue" -> "lastQueueFailure"; "capture" -> "lastCaptureFailure"; else -> "lastStartupFailure" }, fields)
        if (!prefs.contains("lastSessionFailureId") ||
            prefs.getLong("lastSessionFailureId", -1) != prefs.getLong("currentSessionId", -2))
            editor.putString("lastFailure", "$kind $fields")
        if (!editor.commit()) android.util.Log.w("ScreenCopySender", "diagnostic_store=WRITE_FAILED")
    }
    fun read(context: Context): String? {
        val prefs = context.getSharedPreferences("sender-diagnostics", Context.MODE_PRIVATE)
        val latest = prefs.getString("lastFailure", null)
        if (latest?.startsWith("session ") == true) {
            val fields = Wire.parseJson(latest.removePrefix("session ").toByteArray(Charsets.UTF_8))
            val label = if (exactLong(fields, "session") == prefs.getLong("currentSessionId", -1))
                "Last sender session failure" else "Previous sender session failure"
            val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT)
                .format(java.util.Date(exactLong(fields, "timestamp_ms")))
            return "$label at $time: phase=${fields.getString("phase")} ${fields.getString("summary")}"
        }
        if (latest != null) return "Historical sender ${latest.substringBefore(' ')} failure (timestamp unavailable): ${latest.substringAfter(' ')}"
        return prefs.getString("lastQueueFailure", null)?.let { "Historical sender queue failure (timestamp unavailable): $it" }
    }
}

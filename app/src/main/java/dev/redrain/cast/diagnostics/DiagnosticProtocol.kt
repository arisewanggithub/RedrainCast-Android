package dev.redrain.cast.diagnostics

import org.json.JSONArray
import org.json.JSONObject
import org.screencopy.receiver.protocol.StrictJson
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID

/** Unauthenticated LAN metadata protocol; called only by its diagnostic I/O lane. */
class DiagnosticProtocol(
    private val recorder: DiagnosticRecorder,
    private val clock: () -> Long = System::nanoTime,
    private val acquire: () -> Boolean = { true },
    private val connections: () -> JSONObject = { JSONObject().put("version", 1) }
) {
    private val ids = HashSet<String>()
    private val connectionId = UUID.randomUUID().toString()
    private var tokens = 20.0
    private var budgetAt = clock()
    private data class Delivery(val at: Long, val records: Int, val bytes: Int)
    private val deliveries = java.util.ArrayDeque<Delivery>()
    private var outputRecords = 0
    private var outputBytes = 0
    var ready = false
        private set
    var closing = false
        private set

    fun handle(bytes: ByteArray): JSONObject {
        require(bytes.size <= 16384) { "Oversized diagnostic frame" }
        val request = StrictJson.decode(bytes, maxDepth = 8, trackSizes = false)
        // A syntactically valid request with bad typed fields receives a controlled error.
        val id = (request.opt("id") as? String)?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) }
        val op = (request.opt("op") as? String)?.takeIf { it.length in 1..64 }
        val response = JSONObject().put("v", 1).put("id", id ?: JSONObject.NULL).put("op", op ?: JSONObject.NULL)
        val received = clock()
        try {
            fields(request, "v", "id", "op", "args")
            if (request.opt("v") !is Int) invalid()
            if (request.opt("v") != 1) throw DiagnosticError("UNSUPPORTED_VERSION")
            if (id == null || op == null) invalid()
            val args = request.opt("args") as? JSONObject ?: invalid()
            if (id in ids) throw DiagnosticError("DUPLICATE_ID")
            if (ids.size >= 4096) { closing = true; throw DiagnosticError("RATE_LIMITED") }
            ids.add(id)
            val now = clock()
            tokens = (tokens + (now - budgetAt).coerceAtLeast(0) * 20.0 / 1e9).coerceAtMost(20.0)
            budgetAt = now
            if (tokens < 1) throw DiagnosticError("RATE_LIMITED")
            tokens--
            if (op == "AUTH") throw DiagnosticError("UNKNOWN_OPERATION")
            if (!ready && op != "HELLO") {
                closing = true
                throw DiagnosticError("HELLO_REQUIRED")
            }
            val result = when (op) {
                "HELLO" -> {
                    fields(args)
                    if (ready) invalid()
                    if (!acquire()) {
                        closing = true
                        throw DiagnosticError("BUSY")
                    }
                    ready = true
                    val caps = JSONObject()
                    val scope = recorder.scope()
                    DiagnosticRecorder.capabilities.forEach { (role, flags) ->
                        caps.put(role, JSONArray(if (scope != null && role != "receiver") emptyList() else flags.toList()))
                    }
                    JSONObject().put("access", "lan").put("clockId", recorder.clockId).put("capabilities", caps)
                        .put("roles", JSONArray(listOf("sender", "receiver"))).put("connectionId", connectionId)
                        .put("limits", DiagnosticRecorder.limits()).put("delivery", "fetch")
                        .put("scope", scope ?: JSONObject.NULL)
                        .put("connections", connections())
                        .put("metricAvailability", JSONObject().put("tcpKernel", false)
                            .put("transportProvenance", "application_socket_calls")
                            .put("videoPresentation", "codec_release_and_optional_render_callback")
                            .put("physicalDisplay", false))
                }
                "STATUS" -> { fields(args); recorder.status().put("connections", connections()) }
                "CONNECTIONS" -> { fields(args); connections() }
                "CLOCK" -> {
                    fields(args, "clientSendNs")
                    val sent = decimal(args, "clientSendNs")
                    JSONObject().put("clientSendNs", "$sent").put("receiveNs", "$received")
                        .put("sendNs", "${clock()}").put("clockId", recorder.clockId)
                }
                "START" -> {
                    fields(args, "target", "flags", "durationMs", "samplePeriodMs")
                    val target = args.opt("target") as? JSONObject ?: invalid()
                    val role = string(target, "role", 16)
                    if (role !in DiagnosticRecorder.capabilities) invalid()
                    val session = if (target.has("session")) {
                        fields(target, "role", "session"); decimal(target, "session")
                    } else {
                        fields(target, "role", "nextSession")
                        if (target.opt("nextSession") != true) invalid()
                        null
                    }
                    val values = args.opt("flags") as? JSONArray ?: invalid()
                    if (values.length() !in 1..16) invalid()
                    val flags = HashSet<String>()
                    for (i in 0 until values.length()) {
                        val flag = values.opt(i) as? String ?: invalid()
                        if (!flags.add(flag)) invalid()
                    }
                    recorder.start(role, session, flags, integer(args, "durationMs"), integer(args, "samplePeriodMs"))
                }
                "END" -> {
                    fields(args, "captureId"); recorder.end(string(args, "captureId", 64))
                }
                "CLEAR" -> {
                    fields(args, "captureId"); recorder.clear(string(args, "captureId", 64))
                }
                "FETCH" -> {
                    fields(args, "captureId", "afterSeq", "limit")
                    val limit = integer(args, "limit")
                    if (limit !in 1..32) invalid()
                    val result = recorder.fetch(string(args, "captureId", 64), decimal(args, "afterSeq"), limit)
                    val records = result.getJSONArray("records").length()
                    val size = result.toString().toByteArray(Charsets.UTF_8).size + 160
                    val at = clock()
                    while (deliveries.isNotEmpty() && at - deliveries.first.at >= 1_000_000_000L) {
                        val old = deliveries.removeFirst()
                        outputRecords -= old.records; outputBytes -= old.bytes
                    }
                    if (outputRecords + records > 256 || outputBytes + size > 131072) throw DiagnosticError("RATE_LIMITED")
                    deliveries.addLast(Delivery(at, records, size))
                    outputRecords += records; outputBytes += size
                    result
                }
                "PING" -> { fields(args); JSONObject().put("alive", true) }
                else -> throw DiagnosticError("UNKNOWN_OPERATION")
            }
            response.put("ok", true).put("result", result)
        } catch (e: DiagnosticError) {
            response.put("ok", false).put("error", JSONObject().put("code", e.code).put("message", e.code))
        } catch (e: Exception) {
            android.util.Log.e("RemoteDiagnostics", "Internal diagnostic protocol failure", e)
            recorder.disconnect("internal_error")
            closing = true
            response.put("ok", false).put("error", JSONObject().put("code", "INTERNAL").put("message", "INTERNAL"))
        }
        return response
    }

    companion object {
        fun readLine(input: InputStream): ByteArray? {
            val buffer = ByteArrayOutputStream()
            while (true) {
                val value = input.read()
                if (value == -1) {
                    require(buffer.size() == 0) { "Truncated diagnostic frame" }
                    return null
                }
                if (value == 10) return buffer.toByteArray()
                require(buffer.size() < 16384) { "Oversized diagnostic frame" }
                buffer.write(value)
            }
        }
        private fun invalid(): Nothing = throw DiagnosticError("INVALID_ARGUMENT")
        private fun fields(value: JSONObject, vararg names: String) {
            val keys = value.keys().asSequence().toSet()
            if (keys != names.toSet()) invalid()
        }
        private fun string(value: JSONObject, name: String, max: Int): String =
            (value.opt(name) as? String)?.takeIf { it.isNotEmpty() && it.length <= max } ?: invalid()
        private fun integer(value: JSONObject, name: String): Int = value.opt(name) as? Int ?: invalid()
        private fun decimal(value: JSONObject, name: String): Long {
            val raw = string(value, name, 19)
            if (!raw.matches(Regex("0|[1-9][0-9]*"))) invalid()
            return raw.toLongOrNull() ?: invalid()
        }
    }
}

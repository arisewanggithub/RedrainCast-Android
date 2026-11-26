package dev.redrain.cast.diagnostics

import org.json.JSONArray
import org.json.JSONObject
import org.screencopy.receiver.protocol.StrictJson
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID

internal class DiagnosticDeliveryExhausted : Exception()

/** Unauthenticated paired collector protocol, confined to its diagnostic I/O worker. */
internal class DiagnosticProtocol(
    private val recorder: DiagnosticRecorder,
    private val clock: () -> Long = System::nanoTime,
    private val acquire: () -> Boolean = { true },
    private val registry: DiagnosticArmRegistry = DiagnosticArmRegistry(recorder, clock)
) {
    data class Ownership(val pairId: String, val generation: Long)

    private val ids = HashSet<String>()
    private val connectionId = UUID.randomUUID().toString()
    private val delivery = DiagnosticDeliveryBudget()
    private var tokens = 20.0
    private var budgetAt = clock()
    private var ownership: Ownership? = null
    var ready = false
        private set
    var closing = false
        private set

    fun ownership(): Ownership? = ownership

    fun disconnected(snapshot: Ownership?, reason: String) {
        if (snapshot != null)
            registry.disconnected(connectionId, snapshot.pairId, snapshot.generation, reason)
    }

    fun handle(bytes: ByteArray): JSONObject {
        require(bytes.size <= 16_384) { "Oversized diagnostic frame" }
        val request = StrictJson.decode(bytes, maxDepth = 8, trackSizes = false)
        val id = (request.opt("id") as? String)?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) }
        val op = (request.opt("op") as? String)?.takeIf { it.length in 1..64 }
        val responseOp = op?.takeIf { it in setOf("HELLO", "START", "STATUS", "CONNECTIONS", "FETCH",
            "END", "CLEAR", "CLOCK", "PING") }
        val response = JSONObject().put("v", 2).put("id", id ?: JSONObject.NULL).put("op", responseOp ?: JSONObject.NULL)
        val received = clock()
        var reserved = false
        try {
            if (request.opt("v") !is Int) invalid()
            if (request.opt("v") != 2) {
                closing = true
                throw DiagnosticError("UNSUPPORTED_VERSION")
            }
            fields(request, "v", "id", "op", "args")
            if (id == null || op == null) invalid()
            val args = request.opt("args") as? JSONObject ?: invalid()
            val now = clock()
            tokens = (tokens + (now - budgetAt).coerceAtLeast(0) * 20.0 / 1e9).coerceAtMost(20.0)
            budgetAt = now
            if (tokens < 1) throw DiagnosticError("RATE_LIMITED")
            tokens--
            if (id in ids) throw DiagnosticError("DUPLICATE_ID")
            if (ids.size >= 4096) { closing = true; throw DiagnosticError("RATE_LIMITED") }
            ids.add(id)
            if (!ready && op != "HELLO") {
                closing = true
                throw DiagnosticError("HELLO_REQUIRED")
            }
            if (op in setOf("START", "END", "CLEAR")) {
                if (!delivery.tryAcquire(clock(), 0, 8192, reserveBytes = 256, reserveSlots = 1))
                    throw DiagnosticError("RATE_LIMITED")
                reserved = true
            }
            val result = when (op) {
                "HELLO" -> {
                    fields(args)
                    if (ready) invalid()
                    if (!acquire()) { closing = true; throw DiagnosticError("BUSY") }
                    ready = true
                    JSONObject().put("access", "lan").put("clockId", recorder.clockId)
                        .put("connectionId", connectionId).put("pairedNextCast", 1)
                        .put("senderFlags", JSONArray(DiagnosticRecorder.capabilities.getValue("sender").sorted()))
                        .put("receiverFlags", JSONArray(DiagnosticRecorder.capabilities.getValue("receiver").sorted()))
                        .put("limits", DiagnosticRecorder.limits()).put("delivery", "fetch")
                        .put("metricAvailability", JSONObject().put("tcpKernel", false)
                            .put("transportProvenance", "application_socket_calls")
                            .put("videoPresentation", "codec_release_and_optional_render_callback")
                            .put("physicalDisplay", false))
                }
                "START" -> {
                    fields(args, "target", "senderFlags", "receiverFlags", "durationMs", "samplePeriodMs")
                    val target = args.opt("target") as? JSONObject ?: invalid()
                    fields(target, "nextSession")
                    if (target.opt("nextSession") != true) invalid()
                    val result = registry.start(connectionId, flags(args, "senderFlags"), flags(args, "receiverFlags"),
                        integer(args, "durationMs"), integer(args, "samplePeriodMs"))
                    ownership = Ownership(result.getString("pairId"), decimal(result, "armGeneration"))
                    result
                }
                "STATUS" -> {
                    if (args.length() == 0) registry.statusCurrent(connectionId)
                    else { fields(args, "pairId"); registry.status(connectionId, string(args, "pairId", 64)) }
                }
                "CONNECTIONS" -> { fields(args); registry.connections(connectionId) }
                "FETCH" -> {
                    fields(args, "pairId", "afterSenderSeq", "afterReceiverSeq", "limit")
                    registry.fetch(connectionId, string(args, "pairId", 64), decimal(args, "afterSenderSeq"),
                        decimal(args, "afterReceiverSeq"), integer(args, "limit"), id)
                }
                "END" -> { fields(args, "pairId"); registry.end(connectionId, string(args, "pairId", 64)) }
                "CLEAR" -> { fields(args, "pairId"); registry.clear(connectionId, string(args, "pairId", 64)) }
                "CLOCK" -> {
                    val role = string(args, "role", 16)
                    val sent = decimal(args, "clientSendNs")
                    when (role) {
                        "sender" -> {
                            fields(args, "role", "clientSendNs")
                            JSONObject().put("clientSendNs", sent.toString()).put("receiveNs", received.toString())
                                .put("sendNs", clock().toString()).put("clockId", recorder.clockId)
                        }
                        "receiver" -> {
                            fields(args, "role", "pairId", "clientSendNs")
                            registry.receiverClock(connectionId, string(args, "pairId", 64), sent.toString())
                        }
                        else -> invalid()
                    }
                }
                "PING" -> { fields(args); JSONObject().put("alive", true) }
                else -> throw DiagnosticError("UNKNOWN_OPERATION")
            }
            response.put("ok", true).put("result", result)
            val output = response.toString().toByteArray(Charsets.UTF_8)
            if (output.size + 1 > DiagnosticProxyWire.FRAME_BYTES) throw DiagnosticError("RECORD_TOO_LARGE")
            StrictJson.decode(output, maxDepth = DiagnosticProxyWire.FETCH_DEPTH, trackSizes = false)
            val records = if (op == "FETCH") result.getJSONArray("records").length() else 0
            if (reserved && output.size + 1 > 8192 &&
                !delivery.tryAcquire(clock(), 0, output.size + 1 - 8192, reserveBytes = 256, reserveSlots = 1))
                throw DiagnosticError("RATE_LIMITED")
            if (!reserved && !delivery.tryAcquire(clock(), records, output.size + 1,
                    reserveBytes = 256, reserveSlots = 1))
                throw DiagnosticError("RATE_LIMITED")
        } catch (e: DiagnosticError) {
            response.remove("result")
            response.put("ok", false).put("error", JSONObject().put("code", e.code).put("message", e.code))
        } catch (e: Exception) {
            android.util.Log.e("RemoteDiagnostics", "Internal diagnostic protocol failure", e)
            disconnected(ownership, "internal_error")
            closing = true
            response.remove("result")
            response.put("ok", false).put("error", JSONObject().put("code", "INTERNAL").put("message", "INTERNAL"))
        }
        if (!response.getBoolean("ok") && !reserved) {
            val bytesOut = response.toString().toByteArray(Charsets.UTF_8).size + 1
            if (!delivery.tryAcquire(clock(), 0, bytesOut)) {
                closing = true
                throw DiagnosticDeliveryExhausted()
            }
        }
        return response
    }

    companion object {
        fun successBytes(id: String, op: String, result: JSONObject): Int =
            JSONObject().put("v", 2).put("id", id).put("op", op).put("ok", true).put("result", result)
                .toString().toByteArray(Charsets.UTF_8).size + 1

        fun readLine(input: InputStream): ByteArray? {
            val buffer = ByteArrayOutputStream()
            while (true) {
                val value = input.read()
                if (value == -1) {
                    require(buffer.size() == 0) { "Truncated diagnostic frame" }
                    return null
                }
                if (value == 10) return buffer.toByteArray()
                require(buffer.size() < 16_384) { "Oversized diagnostic frame" }
                buffer.write(value)
            }
        }
        private fun invalid(): Nothing = throw DiagnosticError("INVALID_ARGUMENT")
        private fun fields(value: JSONObject, vararg names: String) {
            if (value.keys().asSequence().toSet() != names.toSet()) invalid()
        }
        private fun string(value: JSONObject, name: String, max: Int): String =
            (value.opt(name) as? String)?.takeIf { it.isNotEmpty() && it.length <= max } ?: invalid()
        private fun integer(value: JSONObject, name: String): Int = value.opt(name) as? Int ?: invalid()
        private fun decimal(value: JSONObject, name: String): Long {
            val raw = string(value, name, 19)
            if (!raw.matches(Regex("0|[1-9][0-9]*"))) invalid()
            return raw.toLongOrNull() ?: invalid()
        }
        private fun flags(value: JSONObject, name: String): Set<String> {
            val array = value.opt(name) as? JSONArray ?: invalid()
            if (array.length() !in 1..16) invalid()
            val flags = HashSet<String>()
            repeat(array.length()) {
                val flag = array.opt(it) as? String ?: invalid()
                if (flag.isEmpty() || flag.length > 64 || !flags.add(flag)) invalid()
            }
            return flags
        }
    }
}

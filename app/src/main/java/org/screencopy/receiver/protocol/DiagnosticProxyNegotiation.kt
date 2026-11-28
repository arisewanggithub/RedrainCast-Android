package org.screencopy.receiver.protocol

import dev.redrain.cast.diagnostics.DiagnosticProxyWire
import dev.redrain.cast.diagnostics.DiagnosticRecorder
import org.json.JSONArray
import org.json.JSONObject

/** SCPY proxy revision is independent of SCPY and collector protocol versions. */
internal object DiagnosticProxyNegotiation {
    const val VERSION = 2
    const val MAX_BOOTSTRAP_BYTES = 4096
    val stages = listOf("socket_accepted", "hello_received", "hello_validated", "hello_reply_queued",
        "request_received", "owner_admitted", "capture_started", "lane_admitted")

    data class Offer(
        val session: Long, val pairId: String, val generation: Long, val flags: Set<String>,
        val expiresNs: Long, val samplePeriodMs: Int
    )
    data class Accepted(val token: String, val captureId: String, val clockId: String)

    private fun fields(value: JSONObject, vararg names: String) {
        protocolRequire(value.keys().asSequence().toSet() == names.toSet(), "Invalid proxy fields")
    }

    private fun opaque(value: JSONObject, name: String): String =
        value.string(name, 64).also { protocolRequire(it.isNotEmpty(), "Empty proxy identity") }

    private fun decimal(value: JSONObject, name: String): Long = DiagnosticProxyWire.decimal(value.opt(name))

    private fun flags(value: Any?): Set<String> {
        val array = value as? JSONArray ?: throw ProtocolException("PROTOCOL", "Missing receiver flags")
        protocolRequire(array.length() in 1..16, "Invalid receiver flag count")
        val result = LinkedHashSet<String>()
        repeat(array.length()) { index ->
            val flag = array.opt(index) as? String ?: throw ProtocolException("PROTOCOL", "Invalid receiver flag")
            protocolRequire(result.add(flag), "Duplicate receiver flag")
        }
        protocolRequire(result.all { it in DiagnosticRecorder.capabilities.getValue("receiver") },
            "Unsupported receiver flag", "UNSUPPORTED_CONFIG")
        protocolRequire("video.timing" !in result || "video.progress" in result,
            "video.timing requires video.progress")
        protocolRequire("audio.timing.work" !in result || "audio.timing" in result,
            "audio.timing.work requires audio.timing")
        protocolRequire("audio.timing.detail" !in result || "audio.timing.work" in result,
            "audio.timing.detail requires audio.timing.work")
        protocolRequire("audio.timing.owner" !in result || "audio.timing.detail" in result,
            "audio.timing.owner requires audio.timing.detail")
        return result
    }

    fun capability() = JSONObject().put("version", VERSION)

    fun receiverCapability() = capability().put("receiverFlags",
        JSONArray(DiagnosticRecorder.capabilities.getValue("receiver").sorted()))

    fun supportsReceiver(hello: JSONObject, requested: Set<String>): Boolean {
        if (!hello.has("diagProxy")) return false
        val proxy = hello.obj("diagProxy")
        val version = proxy.integer("version")
        protocolRequire(version > 0, "Invalid proxy version")
        if (version != VERSION) return false
        fields(proxy, "version", "receiverFlags")
        val array = proxy.array("receiverFlags")
        protocolRequire(array.length() in 1..64, "Invalid advertised flag count")
        val supported = HashSet<String>()
        repeat(array.length()) {
            val flag = array.opt(it) as? String
                ?: throw ProtocolException("PROTOCOL", "Invalid advertised receiver flag")
            protocolRequire(flag.isNotEmpty() && flag.length <= 64 && supported.add(flag),
                "Invalid advertised receiver flag")
        }
        return supported.containsAll(requested)
    }

    /** Receipt establishes the receiver-local lease; admission must not renew it. */
    fun offer(hello: JSONObject, receivedNs: Long): Offer? {
        if (!hello.has("diagProxy")) return null
        val proxy = hello.obj("diagProxy")
        val version = proxy.integer("version")
        protocolRequire(version > 0, "Invalid proxy version")
        if (version != VERSION) {
            protocolRequire(!proxy.has("offer"), "Unsupported armed proxy revision", "UNSUPPORTED_CONFIG")
            return null
        }
        if (!proxy.has("offer")) {
            fields(proxy, "version")
            return null
        }
        fields(proxy, "version", "offer")
        val value = proxy.obj("offer")
        fields(value, "session", "pairId", "armGeneration", "receiverFlags", "durationMs", "samplePeriodMs")
        val session = decimal(value, "session")
        val generation = decimal(value, "armGeneration")
        protocolRequire(session > 0 && generation > 0, "Invalid proxy identity")
        val duration = value.integer("durationMs")
        val period = value.integer("samplePeriodMs")
        protocolRequire(duration in 1..180_000 && period in 100..5000, "Invalid proxy timing")
        val expiry = try { Math.addExact(receivedNs, duration * 1_000_000L) }
            catch (_: ArithmeticException) { throw ProtocolException("PROTOCOL", "Invalid proxy deadline") }
        return Offer(session, opaque(value, "pairId"), generation, flags(value.opt("receiverFlags")), expiry, period)
    }

    fun requestMatches(request: JSONObject, session: Long, offer: Offer?) {
        if (offer == null) {
            protocolRequire(!request.has("diagProxy"), "Unsolicited proxy request")
            return
        }
        val proxy = request.obj("diagProxy")
        fields(proxy, "version", "pairId", "armGeneration")
        protocolRequire(proxy.integer("version") == VERSION && session == offer.session &&
            opaque(proxy, "pairId") == offer.pairId &&
            decimal(proxy, "armGeneration") == offer.generation, "Proxy request identity mismatch")
    }

    fun accepted(value: JSONObject, offer: Offer): Accepted {
        val proxy = value.obj("diagProxy")
        fields(proxy, "version", "pairId", "armGeneration", "laneToken", "captureId", "clockId")
        protocolRequire(proxy.integer("version") == VERSION && opaque(proxy, "pairId") == offer.pairId &&
            decimal(proxy, "armGeneration") == offer.generation, "Invalid proxy acceptance")
        val token = opaque(proxy, "laneToken")
        protocolRequire(token.matches(Regex("[0-9a-fA-F]{64}")), "Invalid diagnostic lane token")
        return Accepted(token, opaque(proxy, "captureId"), opaque(proxy, "clockId"))
    }

    fun laneHello(value: JSONObject, session: Long, offer: Offer, token: String): Int {
        fields(value, "role", "version", "pairId", "armGeneration", "token", "remainingMs")
        protocolRequire(value.string("role", 16) == "diagnostic" && value.integer("version") == VERSION &&
            session == offer.session && opaque(value, "pairId") == offer.pairId &&
            decimal(value, "armGeneration") == offer.generation, "Invalid diagnostic lane owner")
        val supplied = opaque(value, "token")
        protocolRequire(supplied.matches(Regex("[0-9a-fA-F]{64}")) &&
            java.security.MessageDigest.isEqual(supplied.toByteArray(Charsets.UTF_8), token.toByteArray(Charsets.UTF_8)),
            "Invalid diagnostic lane token")
        val remaining = value.integer("remainingMs")
        protocolRequire(remaining in 1..180_000, "Invalid diagnostic lane lease")
        return remaining
    }

    fun ready(value: JSONObject, offer: Offer, accepted: Accepted): JSONObject {
        fields(value, "version", "state", "pairId", "armGeneration", "captureId", "clockId", "bootstrap")
        protocolRequire(value.integer("version") == VERSION && value.string("state", 16) == "ready" &&
            opaque(value, "pairId") == offer.pairId && decimal(value, "armGeneration") == offer.generation &&
            opaque(value, "captureId") == accepted.captureId && opaque(value, "clockId") == accepted.clockId,
            "Invalid diagnostic READY identity")
        val bootstrap = value.obj("bootstrap")
        validateBootstrap(bootstrap, offer.session, offer.pairId, accepted.captureId, accepted.clockId)
        return bootstrap
    }

    fun validateBootstrap(value: JSONObject, session: Long, pairId: String, captureId: String, clockId: String) {
        protocolRequire(value.toString().toByteArray(Charsets.UTF_8).size <= MAX_BOOTSTRAP_BYTES,
            "Oversized proxy bootstrap")
        fields(value, "session", "pairId", "captureId", "clockId", "complete", "loss", "stages")
        protocolRequire(decimal(value, "session") == session && opaque(value, "pairId") == pairId &&
            opaque(value, "captureId") == captureId && opaque(value, "clockId") == clockId &&
            value.boolean("complete") && !value.boolean("loss"), "Invalid proxy bootstrap identity or completeness")
        val entries = value.array("stages")
        protocolRequire(entries.length() == stages.size, "Invalid proxy bootstrap stage count")
        var previous = -1L
        stages.forEachIndexed { index, expected ->
            val entry = entries.opt(index) as? JSONObject
                ?: throw ProtocolException("PROTOCOL", "Invalid proxy bootstrap stage")
            fields(entry, "stage", "monoNs")
            val timestamp = decimal(entry, "monoNs")
            protocolRequire(entry.string("stage", 64) == expected && timestamp >= previous,
                "Invalid proxy bootstrap stage order")
            previous = timestamp
        }
    }
}

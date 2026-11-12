package dev.redrain.cast.diagnostics

import org.json.JSONObject
import org.screencopy.receiver.protocol.DiagnosticDelegation

/** Process-only hint, bound to the owning cast and its connected control peer. */
class DiagnosticConnections(private val clock: () -> Long = System::nanoTime) {
    private var session: Long? = null
    private var host: String? = null
    private var state = "unavailable"
    private var reason = "no_session"
    private var port: Int? = null
    private var deadline: Long? = null
    private var active = false

    @Synchronized fun begin(session: Long, connectedHost: String, requested: Boolean, supported: Boolean) {
        this.session = session
        host = connectedHost
        port = null
        deadline = null
        active = requested && supported
        state = if (!requested) "disabled" else if (!supported) "unsupported" else "starting"
        reason = if (!requested) "not_requested" else if (!supported) "unsupported" else "awaiting_endpoint"
    }

    @Synchronized fun update(owner: Long, json: JSONObject) {
        if (owner != session || !active || deadline != null || state == "disabled") return
        try {
            val result = DiagnosticDelegation.connection(json, owner)
            state = result?.state ?: "unsupported"
            port = result?.endpoint?.port
            if (state == "frozen") {
                if (port != null) deadline = clock() + 120_000_000_000L
                active = false
            }
            reason = when {
                port != null -> "available"
                result == null || state == "unsupported" -> "unsupported"
                state == "starting" -> "awaiting_endpoint"
                state == "disabled" -> "revoked"
                else -> "endpoint_unavailable"
            }
        } catch (_: IllegalArgumentException) {
            port = null
            state = "unavailable"
            reason = "malformed"
        }
    }

    @Synchronized fun ended(owner: Long) {
        if (session != owner || !active) return
        active = false
        if (port != null && state in setOf("enabled", "frozen")) {
            state = "frozen"
            deadline = clock() + 120_000_000_000L
        } else {
            port = null
            reason = "cast_ended"
        }
    }

    @Synchronized fun revoke(owner: Long? = null) {
        if (owner != null && owner != session) return
        session = null
        host = null
        port = null
        deadline = null
        active = false
        state = "disabled"
        reason = "revoked"
    }

    @Synchronized fun snapshot(): JSONObject {
        if (deadline?.let { clock() >= it } == true) {
            port = null
            state = "unavailable"
            reason = "grace_expired"
        }
        return JSONObject().put("state", state).put("reason", reason)
            .put("session", session?.toString() ?: JSONObject.NULL)
            .put("available", port != null).apply {
                port?.let {
                    put("host", host).put("port", it).put("version", 1).put("access", "lan")
                }
            }
    }
}

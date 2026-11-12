package org.screencopy.receiver.protocol

import org.json.JSONObject

/** Additive SCPY negotiation, not a discovery-side authority. */
object DiagnosticDelegation {
    fun capability() = JSONObject().put("version", 1).put("sessionDelegation", true)
    fun supported(hello: JSONObject): Boolean {
        if (!hello.has("diagnostics")) return false
        val value = hello.obj("diagnostics")
        val version = value.integer("version")
        protocolRequire(version > 0, "Invalid diagnostics capability version")
        val enabled = value.boolean("sessionDelegation")
        return version == 1 && enabled
    }
    fun request(enabled: Boolean) = JSONObject().put("version", 1).put("enabled", enabled)
    fun requested(json: JSONObject): Boolean {
        if (!json.has("diagnostic")) return false
        val value = json.obj("diagnostic")
        protocolRequire(value.keys().asSequence().toSet() == setOf("version", "enabled"),
            "Invalid diagnostic request fields")
        protocolRequire(value.integer("version") == 1, "Unsupported diagnostic request version")
        return value.boolean("enabled")
    }
    val states = setOf("starting", "enabled", "frozen", "unavailable", "unsupported", "local", "disabled")
    fun result(state: String) = JSONObject().put("version", 1).put("state", state)
    fun result(state: String, session: Long, endpoint: DiagnosticDiscovery?): JSONObject =
        result(state).put("session", session.toString()).apply {
            if (state in setOf("enabled", "local", "frozen")) endpoint?.let {
                put("port", it.port).put("access", "lan")
            }
        }
    data class Connection(val state: String, val endpoint: DiagnosticDiscovery?)
    fun connection(json: JSONObject, owner: Long): Connection? {
        val state = state(json) ?: return null
        val value = json.obj("diagnostic")
        // Legacy state-only results remain usable as status, never as endpoints.
        if (!value.has("session")) {
            protocolRequire(!value.has("port") && !value.has("access"), "Missing diagnostic owner")
            return Connection(state, null)
        }
        protocolRequire(value.opt("session") is String && value.opt("session") == owner.toString(),
            "Diagnostic owner mismatch")
        val endpoint = if (value.has("port") || value.has("access")) {
            protocolRequire(state in setOf("enabled", "local", "frozen"), "Inactive diagnostic endpoint")
            DiagnosticDiscovery.parse(value)
        } else null
        return Connection(state, endpoint)
    }
    fun state(json: JSONObject): String? {
        if (!json.has("diagnostic")) return null
        val value = json.obj("diagnostic")
        protocolRequire(value.integer("version") == 1, "Invalid diagnostic result version")
        val state = value.string("state", 32)
        protocolRequire(state in states, "Invalid diagnostic result state")
        return state
    }
}

interface DiagnosticSessionAccess {
    fun admitted(session: Long, requested: Boolean, ownerActive: () -> Boolean, report: (String) -> Unit)
    fun ended(session: Long)
    fun revoked(session: Long)
}

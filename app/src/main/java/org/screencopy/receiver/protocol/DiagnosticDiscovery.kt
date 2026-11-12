package org.screencopy.receiver.protocol

import org.json.JSONObject

/** Untrusted endpoint hint only; never carries certificate or authentication material. */
data class DiagnosticDiscovery(val port: Int) {
    init { require(port in 1..65535) }
    fun json(): JSONObject = JSONObject().put("version", 1).put("port", port).put("access", "lan")

    companion object {
        fun parse(value: Any?): DiagnosticDiscovery? {
            protocolRequire(value is JSONObject, "Invalid diagnostic discovery envelope")
            val envelope = value as JSONObject
            val version = envelope.integer("version")
            val port = envelope.integer("port")
            protocolRequire(version > 0 && port in 1..65535, "Invalid diagnostic discovery version or port")
            if (version != 1) return null
            protocolRequire(envelope.opt("access") == "lan", "Unsupported diagnostic access mode")
            return DiagnosticDiscovery(port)
        }
    }
}

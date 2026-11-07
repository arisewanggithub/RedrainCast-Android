package org.screencopy.receiver.protocol

import org.json.JSONArray
import org.json.JSONObject

data class ReceiverOptions(val playbackStats: Boolean = false)

data class ReceiverOptionCapabilities(val playbackStats: Boolean = false) {
    fun intersect(other: ReceiverOptionCapabilities) = ReceiverOptionCapabilities(playbackStats && other.playbackStats)
    fun select(enabled: Boolean) = ReceiverOptions(playbackStats && enabled)
    fun wireValue(): JSONObject = JSONObject().put("version", 1)
        .put("supported", JSONArray().apply { if (playbackStats) put("playbackStats") })
}

object ReceiverOptionProtocol {
    const val MAXIMUM_BYTES = 2048
    val IMPLEMENTED = ReceiverOptionCapabilities(true)

    private fun envelope(root: JSONObject): JSONObject? {
        if (!root.has("receiverOptions")) return null
        val value = root.obj("receiverOptions")
        protocolRequire(StrictJson.encodedSize(value) <= MAXIMUM_BYTES, "receiverOptions exceeds 2048 UTF-8 bytes")
        val keys = value.keys().asSequence().toList()
        protocolRequire(keys.size <= 16 && keys.all { it.length in 1..64 && it.none(Char::isISOControl) },
            "Invalid receiverOptions members")
        return value
    }

    private fun revision(value: JSONObject): Int = value.integer("version").also {
        protocolRequire(it > 0, "receiverOptions.version must be positive")
    }

    fun capabilities(root: JSONObject): ReceiverOptionCapabilities {
        val value = envelope(root) ?: return ReceiverOptionCapabilities()
        val version = revision(value)
        val supported = value.array("supported")
        protocolRequire(supported.length() <= 16, "Too many receiver option names")
        val names = HashSet<String>()
        repeat(supported.length()) {
            val name = supported.opt(it)
            protocolRequire(name is String && name.length in 1..64 && name.none(Char::isISOControl) &&
                names.add(name), "Invalid or duplicate receiver option name")
        }
        return ReceiverOptionCapabilities(version == 1 && "playbackStats" in names)
    }

    fun selection(root: JSONObject, negotiated: ReceiverOptionCapabilities): ReceiverOptions {
        val value = envelope(root) ?: return ReceiverOptions()
        val version = revision(value)
        val enabled = if (value.has("playbackStats")) value.boolean("playbackStats") else false
        return ReceiverOptions(version == 1 && negotiated.playbackStats && enabled)
    }

    fun apply(root: JSONObject, options: ReceiverOptions): JSONObject = root.apply {
        if (options.playbackStats) put("receiverOptions", JSONObject().put("version", 1).put("playbackStats", true))
    }
}

package org.screencopy.receiver.protocol

import org.json.JSONObject

internal object StreamRecoveryProtocol {
    const val VERSION = 2
    const val REQUEST = "stream-recovery-request"
    const val KEY = "stream-recovery-key"
    const val COMPLETE = "stream-recovery-complete"

    fun timestamp(json: JSONObject, name: String): Long {
        val text = json.string(name, 20)
        protocolRequire(text.matches(Regex("0|[1-9][0-9]*")), "Invalid canonical $name")
        return text.toLongOrNull() ?: throw ProtocolException("PROTOCOL", "$name exceeds int64")
    }

    fun track(json: JSONObject): Int = json.integer("track").also {
        protocolRequire(it in 1..2, "Invalid recovery track")
    }

    fun status(state: String, track: Int, generation: Long, pts: Long): JSONObject {
        protocolRequire(state in setOf(KEY, COMPLETE) && track in 1..2 && (state != KEY || track == 1) &&
            generation in 1..Int.MAX_VALUE.toLong() && pts >= 0,
            "Invalid recovery proof fields")
        return JSONObject().put("state", state).put("message", "Track $track recovery $state")
            .put("track", track).put("generation", generation.toInt())
            .put(if (track == 1) "keyPtsUs" else "audioPtsUs", pts.toString())
    }
}

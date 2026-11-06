package org.screencopy.receiver.protocol

import org.json.JSONObject

internal object StreamRecoveryProtocol {
    const val VERSION = 1
    const val REQUEST = "stream-recovery-request"
    const val KEY = "stream-recovery-key"
    const val COMPLETE = "stream-recovery-complete"

    fun timestamp(json: JSONObject, name: String): Long {
        val text = json.string(name, 20)
        protocolRequire(text.matches(Regex("0|[1-9][0-9]*")), "Invalid canonical $name")
        return text.toLongOrNull() ?: throw ProtocolException("PROTOCOL", "$name exceeds int64")
    }

    fun status(state: String, generation: Long, keyPts: Long, audioPts: Long? = null): JSONObject {
        protocolRequire(generation in 1..Int.MAX_VALUE.toLong() && keyPts >= 0 && (audioPts == null || audioPts >= 0),
            "Invalid recovery proof fields")
        return JSONObject().put("state", state).put("message", "Stream recovery $state")
            .put("generation", generation.toInt()).put("keyPtsUs", keyPts.toString()).apply {
                if (audioPts != null) put("audioPtsUs", audioPts.toString())
            }
    }
}

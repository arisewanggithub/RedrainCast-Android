package dev.screencopy.sender

import java.io.IOException

internal enum class CaptureProtocolReason { CONFIG_SEQUENCE, MEDIA_GENERATION, PTS_DECREASE, READY_MISMATCH, INVALID_TRACK, UNCLASSIFIED }
internal enum class CaptureProtocolStage { MUX_CONFIG, MUX_MEDIA, MUX_READY, CAPTURE_CALLBACK }
internal enum class CapturePacketKind { CONFIG, KEY_FRAME, MEDIA, READY, UNKNOWN }
internal enum class CaptureTrack { VIDEO, AUDIO, UNKNOWN }
internal enum class CaptureFormat { H264, H265, AV1, OPUS, AAC, PCM, UNKNOWN }
internal data class AdapterProtocolContext(
    val kind: CapturePacketKind, val configPackets: Long, val mediaPackets: Long,
    val repeatedConfig: Boolean, val packetBytes: Int, val sourceStepUs: Long
)
internal data class CaptureProtocolFailure(
    val reason: CaptureProtocolReason, val stage: CaptureProtocolStage, val track: CaptureTrack,
    val generation: Int, val expected: Int, val current: Int, val announced: Int, val ready: Int,
    val ptsDeltaUs: Long, val adapter: AdapterProtocolContext? = null,
    val originalException: String = "IOException"
) {
    fun fields(format: CaptureFormat) = "code=PROTOCOL reason=$reason stage=$stage track=$track format=$format" +
        " generation=$generation expected_generation=$expected current_generation=$current announced_generation=$announced" +
        " ready_generation=$ready pts_delta_us=$ptsDeltaUs original_exception=$originalException" +
        " packet=${adapter?.kind ?: CapturePacketKind.UNKNOWN} config_packets=${adapter?.configPackets ?: 0}" +
        " media_packets=${adapter?.mediaPackets ?: 0} repeated_config=${adapter?.repeatedConfig ?: false}" +
        " packet_bytes=${adapter?.packetBytes ?: 0} source_step_us=${adapter?.sourceStepUs ?: 0}"
    companion object {
        fun unknown(error: Exception) = CaptureProtocolFailure(CaptureProtocolReason.UNCLASSIFIED,
            CaptureProtocolStage.CAPTURE_CALLBACK, CaptureTrack.UNKNOWN, 0, 0, 0, 0, 0, 0,
            originalException = when (error) {
                is IOException -> "IOException"
                is IllegalStateException -> "IllegalStateException"
                is org.json.JSONException -> "JSONException"
                else -> "Exception"
            })
    }
}
internal class SenderProtocolException(val evidence: CaptureProtocolFailure, cause: Exception? = null) : IOException(
    "PROTOCOL: ${evidence.reason} ${evidence.track}; generation ${evidence.generation}, expected ${evidence.expected}, " +
        "relative PTS delta ${evidence.ptsDeltaUs} us.", cause
) {
    fun withAdapter(context: AdapterProtocolContext) = SenderProtocolException(evidence.copy(adapter = context), this)
}

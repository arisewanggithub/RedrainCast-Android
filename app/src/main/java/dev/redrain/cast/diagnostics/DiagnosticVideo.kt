package dev.redrain.cast.diagnostics

import org.json.JSONObject
import org.screencopy.receiver.media.VideoStage
import org.screencopy.receiver.media.VideoFault

/** Separate fixed slots prevent high-rate pictures from occupying the staging queue. */
enum class DiagnosticVideoStage {
    ENCODED, SOURCE_DROPPED, QUEUED, QUEUE_REMOVED, QUEUE_DROPPED, WRITE_STARTED, WRITTEN, WRITE_FAILED,
    RECEIVED, ADMITTED, REJECTED, DECODER_INPUT, DECODED, SKIPPED, RELEASED, RENDERED
}

internal data class DiagnosticVideoProgress(
    val generation: Int,
    val videoStage: DiagnosticVideoStage,
    val count: Long,
    val bytes: Long,
    val keyFrames: Long,
    val firstPtsUs: Long,
    val lastPtsUs: Long,
    val firstAtNs: Long,
    val lastAtNs: Long,
    val sourceAtNs: Long?,
    val maxPtsGapUs: Long,
    val maxObservationGapNs: Long,
    val reason: DiagnosticReason,
    val stale: Long,
    val capacity: Long,
    val pressure: Long,
    val sourcePending: Long,
    val stopped: Long,
    val replaced: Long,
    val retired: Boolean = false
) : DiagnosticMetadata {
    override fun json(): JSONObject = JSONObject().put("schema", "video_progress")
        .put("videoStage", videoStage.name.lowercase()).put("count", "$count").put("bytes", nullable(bytes))
        .put("keyFrames", nullable(keyFrames)).put("firstPtsUs", nullable(firstPtsUs))
        .put("lastPtsUs", nullable(lastPtsUs)).put("firstAtNs", "$firstAtNs").put("lastAtNs", "$lastAtNs")
        .put("sourceAtNs", sourceAtNs?.toString() ?: JSONObject.NULL)
        .put("maxPtsGapUs", "$maxPtsGapUs").put("maxObservationGapNs", "$maxObservationGapNs")
        .put("lastReason", reason.name.lowercase())
        .put("retired", retired)
        .put("reasons", JSONObject().put("stale", "$stale").put("capacity", "$capacity")
            .put("pressure", "$pressure").put("sourcePending", "$sourcePending").put("stopped", "$stopped")
            .put("replaced", "$replaced"))

    private fun nullable(value: Long): Any = if (value >= 0) "$value" else JSONObject.NULL
}

data class DiagnosticVideoConfiguration(
    val codec: DiagnosticCodec, val width: Int, val height: Int, val frameRate: Int,
    val renderedCallback: Boolean?
) : DiagnosticMetadata {
    override fun json(): JSONObject = JSONObject().put("schema", "video_configuration")
        .put("codec", codec.name.lowercase()).put("width", width).put("height", height)
        .put("frameRate", frameRate).put("renderedCallback", renderedCallback ?: JSONObject.NULL)
        .put("presentationEvidence", "codec_release_or_render_callback_not_physical_display")
}

data class DiagnosticVideoFailure(val stage: VideoStage, val fault: VideoFault) : DiagnosticMetadata {
    override fun json(): JSONObject = JSONObject().put("schema", "video_failure")
        .put("videoStage", stage.name.lowercase()).put("fault", fault.name.lowercase())
}

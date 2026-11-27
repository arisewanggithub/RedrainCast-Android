package dev.redrain.cast.diagnostics

import org.json.JSONObject
import org.screencopy.receiver.media.AudioClockSource
import org.screencopy.receiver.media.AudioStartDecision

enum class DiagnosticCodec {
    PCM, OPUS, AAC, H264, H265, AV1, UNKNOWN;
    companion object {
        fun from(value: String): DiagnosticCodec = when (value) {
            "raw", "pcm" -> PCM; "opus" -> OPUS; "aac" -> AAC
            "h264", "video/avc" -> H264; "h265", "video/hevc" -> H265; "av1", "video/av01" -> AV1
            else -> UNKNOWN
        }
    }
}

/** Only these immutable metadata schemas can cross from media hooks to diagnostic JSON. */
sealed interface DiagnosticMetadata {
    fun json(): JSONObject
}

internal data class DiagnosticTerminalFailure(val cause: Long, val origin: Int?) : DiagnosticMetadata {
    override fun json(): JSONObject = JSONObject().put("schema", "terminal_failure")
        .put("cause", cause).put("origin", origin ?: JSONObject.NULL)
}

internal data class DiagnosticTerminalCoverage(
    val finalSequence: Long?, val cleanupSucceeded: Boolean?,
    val skip: ReceiverTelemetrySession.FinalSkipReason?,
    val preparation: DiagnosticPreparationTimeout? = null
) : DiagnosticMetadata {
    override fun json(): JSONObject = JSONObject().put("schema", "terminal_coverage")
        .put("finalSeq", finalSequence?.toString() ?: JSONObject.NULL)
        .put("cleanupSucceeded", cleanupSucceeded ?: JSONObject.NULL)
        .put("finalSkipReason", skip?.name ?: JSONObject.NULL)
        .apply { preparation?.let { put("preparationTimeout", it.json()) } }
}

internal data class DiagnosticPreparationTimeout(
    val phase: ReceiverTelemetrySession.PreparationPhase, val elapsedNs: Long, val activeProducers: Int
) {
    fun json() = JSONObject().put("phase", phase.name).put("elapsedNs", "$elapsedNs")
        .put("activeProducers", activeProducers)
}

internal data class DiagnosticAudioStartDecision(val decision: AudioStartDecision) : DiagnosticMetadata {
    override fun json(): JSONObject = JSONObject().put("schema", "audio_start_decision")
        .put("decision", decision.reason).put("mode", decision.mode)
        .put("positionUs", decision.positionUs?.toString() ?: JSONObject.NULL)
        .put("delayUs", decision.delayUs?.toString() ?: JSONObject.NULL)
        .put("firstVideoUs", decision.firstVideoUs?.toString() ?: JSONObject.NULL)
        .put("releasedVideoUs", decision.releasedVideoUs?.toString() ?: JSONObject.NULL)
        .put("waitedUs", "${decision.waitedUs}").put("revision", "${decision.revision}")
        .put("flags", "${decision.flags}")
        .put("observationAgeUs", decision.observationAgeUs?.toString() ?: JSONObject.NULL)
        .put("retainedDecodedBytes", decision.retainedDecodedBytes)
        .put("retainedDecodedPackets", decision.retainedDecodedPackets)
        .put("encodedQueueBytes", decision.encodedQueueBytes)
        .put("encodedQueuePackets", decision.encodedQueuePackets)
        .put("sampledAtNs", decision.sampledAtNs?.toString() ?: JSONObject.NULL)
        .put("queueSampledAtNs", decision.queueSampledAtNs?.toString() ?: JSONObject.NULL)
        .put("observationSequence", decision.observationSequence?.toString() ?: JSONObject.NULL)
        .put("observationAtNs", decision.observationAtNs?.toString() ?: JSONObject.NULL)
        .put("observationPtsUs", decision.observationPtsUs?.toString() ?: JSONObject.NULL)
        .put("outputSource", decision.outputSource?.name?.lowercase() ?: JSONObject.NULL)
        .put("nativeTimestampAtNs", decision.nativeTimestampAtNs?.toString() ?: JSONObject.NULL)
        .put("nativeTimestampFrame", decision.nativeTimestampFrame?.toString() ?: JSONObject.NULL)
        .put("appliedOffsetUs", decision.appliedOffsetUs?.toString() ?: JSONObject.NULL)
        .put("correctionSequence", decision.correctionSequence?.toString() ?: JSONObject.NULL)
        .put("omittedRepeats", "${decision.omittedRepeats}")
}

internal data class DiagnosticAudioClockObservation(
    val observation: org.screencopy.receiver.media.AudioClockObservation
) : DiagnosticMetadata {
    override fun json(): JSONObject = audioClockObservationJson(observation).put("schema", "audio_clock_observation")
}

internal fun audioClockObservationJson(value: org.screencopy.receiver.media.AudioClockObservation): JSONObject {
    val timing = value.timing
    fun number(value: Long?): Any = value?.toString() ?: JSONObject.NULL
    return JSONObject().put("sequence", "${value.sequence}").put("atNs", "${value.atNs}")
        .put("sampleUs", "${value.sampleUs}").put("sourcePtsUs", "${value.sourcePtsUs}")
        .put("appliedOffsetUs", "${value.appliedOffsetUs}")
        .put("submittedFrames", number(timing?.submittedFrames))
        .put("consumedFrames", number(timing?.consumedFrames))
        .put("presentedFrames", number(timing?.presentedFrames))
        .put("nativeTimestampNs", number(timing?.timestampAtNs))
        .put("nativeTimestampFrame", number(timing?.timestampFramePosition))
        .put("clockSource", timing?.clockSource?.name ?: JSONObject.NULL)
}

data class DiagnosticConfiguration(
    val codec: DiagnosticCodec, val sampleRate: Int, val channels: Int, val prerollUs: Long?
) : DiagnosticMetadata {
    override fun json(): JSONObject = JSONObject().put("schema", "configuration")
        .put("codec", codec.name.lowercase()).put("sampleRate", sampleRate).put("channels", channels)
        .put("prerollUs", prerollUs?.toString() ?: JSONObject.NULL)
}

data class DiagnosticQueue(
    val packets: Int, val maxPackets: Int, val bytes: Int, val maxBytes: Int,
    val oldestAgeNs: Long?, val spanUs: Long, val maxSpanUs: Long
) : DiagnosticMetadata {
    override fun json(): JSONObject = JSONObject().put("schema", "queue").put("packets", packets)
        .put("maxPackets", maxPackets).put("bytes", bytes).put("maxBytes", maxBytes)
        .put("oldestAgeNs", oldestAgeNs?.toString() ?: JSONObject.NULL)
        .put("spanUs", "$spanUs").put("maxSpanUs", "$maxSpanUs")
}

data class DiagnosticOutput(
    val submittedFrames: Long, val consumedFrames: Long, val presentedFrames: Long,
    val timestampAgeUs: Long?, val clockSource: AudioClockSource, val underruns: Int? = null
) : DiagnosticMetadata {
    override fun json(): JSONObject = JSONObject().put("schema", "output")
        .put("submittedFrames", "$submittedFrames").put("consumedFrames", "$consumedFrames")
        .put("presentedFrames", "$presentedFrames").put("timestampAgeUs", timestampAgeUs?.toString() ?: JSONObject.NULL)
        .put("clockSource", clockSource.name.lowercase()).put("underruns", underruns ?: JSONObject.NULL)
}

data class DiagnosticCaptureTimestamp(
    val available: Boolean, val framePosition: Long, val monoNs: Long
) : DiagnosticMetadata {
    override fun json(): JSONObject = JSONObject().put("schema", "capture_timestamp").put("available", available)
        .put("framePosition", if (available) "$framePosition" else JSONObject.NULL)
        .put("monoNs", if (available) "$monoNs" else JSONObject.NULL)
}

data class DiagnosticWindow(
    val codec: DiagnosticCodec, val prerollUs: Long, val windowStartUs: Long
) : DiagnosticMetadata {
    override fun json(): JSONObject = JSONObject().put("schema", "window")
        .put("codec", codec.name.lowercase()).put("prerollUs", "$prerollUs").put("windowStartUs", "$windowStartUs")
}

data class DiagnosticOperationSummary(
    val point: DiagnosticPoint, val windowStartNs: Long, val windowEndNs: Long,
    val started: Long, val completed: Long, val zeroOperationEvents: Long, val bytes: Long,
    val units: Long,
    val durationsUnder1Ms: Long, val durations1To5Ms: Long,
    val durations5To20Ms: Long, val durationsOver20Ms: Long,
    val slow: Long, val maxDurationNs: Long,
    val slowExampleOperationId: Long?, val slowExampleDurationNs: Long?,
    val slowExampleTrack: Int?, val slowExampleGeneration: Int?,
    val slowExamplePtsUs: Long?, val slowExampleEndPtsUs: Long?,
    val slowExampleCount: Long?, val slowExampleBytes: Long?
) : DiagnosticMetadata {
    override fun json(): JSONObject = JSONObject().put("schema", "operation_summary")
        .put("point", point.name.lowercase()).put("windowStartNs", "$windowStartNs")
        .put("windowEndNs", "$windowEndNs").put("started", "$started").put("completed", "$completed")
        .put("zeroOperationEvents", "$zeroOperationEvents").put("bytes", "$bytes")
        .put("units", "$units")
        .put("durationBuckets", JSONObject().put("under1Ms", "$durationsUnder1Ms")
            .put("1To5Ms", "$durations1To5Ms").put("5To20Ms", "$durations5To20Ms")
            .put("over20Ms", "$durationsOver20Ms"))
        .put("slow", "$slow").put("maxDurationNs", "$maxDurationNs")
        .put("slowExampleOperationId", slowExampleOperationId?.toString() ?: JSONObject.NULL)
        .put("slowExampleDurationNs", slowExampleDurationNs?.toString() ?: JSONObject.NULL)
        .put("slowExampleTrack", slowExampleTrack ?: JSONObject.NULL)
        .put("slowExampleGeneration", slowExampleGeneration ?: JSONObject.NULL)
        .put("slowExamplePtsUs", slowExamplePtsUs?.toString() ?: JSONObject.NULL)
        .put("slowExampleEndPtsUs", slowExampleEndPtsUs?.toString() ?: JSONObject.NULL)
        .put("slowExampleCount", slowExampleCount?.toString() ?: JSONObject.NULL)
        .put("slowExampleBytes", slowExampleBytes?.toString() ?: JSONObject.NULL)
}

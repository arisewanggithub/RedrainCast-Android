package org.screencopy.receiver.ui

import org.screencopy.receiver.media.QueueLimit
import org.screencopy.receiver.media.QueuePressure
import org.screencopy.receiver.media.OutputPreparationDiagnostic
import org.screencopy.receiver.media.OutputPreparationReason
import org.screencopy.receiver.media.AudioClockDiagnostic
import org.screencopy.receiver.media.VideoDiagnostic
import org.screencopy.receiver.media.VideoFault
import org.screencopy.receiver.media.VideoStage

enum class FailureOrigin { AUDIO, VIDEO, SESSION, PEER, CLEANUP, PRESENTATION }
enum class FailureReason { SOURCE_TIMING, AUDIO_CLOCK, AUDIO_UNDERRUN, AUDIO_ROUTE, AUDIO_WRITE, AUDIO_STALL, AUDIO_INITIALIZATION, RATE_UNSUPPORTED, OUTPUT_PREPARE, PEER_REPORTED, QUEUE_PACKETS, QUEUE_BYTES, QUEUE_PTS, VIDEO_KEYFRAME, VIDEO_INPUT_CAPACITY, VIDEO_INPUT, VIDEO_TIMESTAMPS, VIDEO_CLOCK_WAIT, VIDEO_STALL, VIDEO_RESOURCE, VIDEO_DECODER, VIDEO_INITIALIZATION, VIDEO_UNSUPPORTED, VIDEO_STREAM_END, VIDEO_OUTPUT, RECEIVER_ORIENTATION, CLEANUP, OTHER }

data class FailureDiagnostic(
    val code: String,
    val origin: FailureOrigin,
    val reason: FailureReason,
    val phase: String,
    val offsetUs: Long? = null,
    val stage: String? = null,
    val exceptionType: String? = null,
    val pressure: QueuePressure? = null,
    val outputPreparation: OutputPreparationDiagnostic? = null,
    val audioClock: AudioClockDiagnostic? = null,
    val video: VideoDiagnostic? = null
) {
    // Never log arbitrary peer text, codec payloads, names, endpoints or absolute source timestamps.
    fun logMessage(): String = "code=$code origin=$origin reason=$reason phase=$phase" +
        (stage?.let { " stage=$it" } ?: "") +
        (exceptionType?.let { " exception=$it" } ?: "") +
        (offsetUs?.let { " offset_us=$it" } ?: "") +
        (pressure?.let { " ${it.logMessage()}" } ?: "") +
        (outputPreparation?.let { " ${it.logMessage()}" } ?: "") +
        (audioClock?.let { " ${it.logMessage()}" } ?: "") +
        (video?.let { " ${it.logMessage()}" } ?: "")

    companion object {
        fun classify(code: String, origin: FailureOrigin, phase: String, detail: String, pressure: QueuePressure? = null,
            outputPreparation: OutputPreparationDiagnostic? = null, videoDiagnostic: VideoDiagnostic? = null): FailureDiagnostic {
            val context = when (origin) {
                FailureOrigin.AUDIO -> Regex("^Audio stage=([A-Z_]{1,32}) exception=([A-Za-z0-9_$]{1,64}):").find(detail)
                FailureOrigin.VIDEO -> Regex("^Video stage=([A-Z_]{1,32}) exception=([A-Za-z0-9_$]{1,64}):").find(detail)
                else -> null
            }
            val preparation = outputPreparation ?: if (context?.groupValues?.get(2) == "AudioOutputPreparationException")
                OutputPreparationDiagnostic.fromLocalDetail(detail) else null
            val audioClock = if (origin == FailureOrigin.AUDIO && context?.groupValues?.get(2) == "AudioClockException")
                AudioClockDiagnostic.fromLocalDetail(detail) else null
            val video = if (origin == FailureOrigin.PEER) null else videoDiagnostic ?:
                if (origin == FailureOrigin.VIDEO) VideoDiagnostic.fromLocalDetail(detail) else null
            val reason = when {
                origin == FailureOrigin.PEER -> FailureReason.PEER_REPORTED
                origin == FailureOrigin.PRESENTATION -> FailureReason.RECEIVER_ORIENTATION
                video != null -> when (video.fault) {
                    VideoFault.UNSUPPORTED -> FailureReason.VIDEO_UNSUPPORTED
                    VideoFault.FIRST_KEYFRAME -> FailureReason.VIDEO_KEYFRAME
                    VideoFault.INPUT_CAPACITY -> FailureReason.VIDEO_INPUT_CAPACITY
                    VideoFault.INPUT_BUFFER -> FailureReason.VIDEO_INPUT
                    VideoFault.INPUT_PTS, VideoFault.OUTPUT_PTS -> FailureReason.VIDEO_TIMESTAMPS
                    VideoFault.CLOCK_WAIT, VideoFault.CLOCK_FAILURE -> FailureReason.VIDEO_CLOCK_WAIT
                    VideoFault.INPUT_STALL, VideoFault.OUTPUT_STALL -> FailureReason.VIDEO_STALL
                    VideoFault.CODEC_RESOURCE, VideoFault.CODEC_RECLAIMED -> FailureReason.VIDEO_RESOURCE
                    VideoFault.END_OF_STREAM -> FailureReason.VIDEO_STREAM_END
                    VideoFault.OUTPUT_METADATA -> FailureReason.VIDEO_OUTPUT
                    else -> if (video.stage in setOf(VideoStage.SELECT, VideoStage.CREATE, VideoStage.CONFIGURE, VideoStage.START))
                        FailureReason.VIDEO_INITIALIZATION else FailureReason.VIDEO_DECODER
                }
                preparation?.reason in setOf(OutputPreparationReason.RATE_UNSUPPORTED, OutputPreparationReason.PARAMS_MISMATCH) -> FailureReason.RATE_UNSUPPORTED
                preparation != null -> FailureReason.OUTPUT_PREPARE
                pressure?.limit == QueueLimit.PACKETS -> FailureReason.QUEUE_PACKETS
                pressure?.limit == QueueLimit.BYTES -> FailureReason.QUEUE_BYTES
                pressure?.limit == QueueLimit.PTS -> FailureReason.QUEUE_PTS
                origin == FailureOrigin.CLEANUP -> FailureReason.CLEANUP
                origin == FailureOrigin.VIDEO -> if (phase == "Initializing tracks")
                    FailureReason.VIDEO_INITIALIZATION else FailureReason.VIDEO_DECODER
                origin != FailureOrigin.AUDIO -> FailureReason.OTHER
                audioClock != null -> FailureReason.AUDIO_CLOCK
                detail.contains("source timestamp offset") || detail.contains("timestamps are discontinuous") || detail.contains("Decoded audio PTS") -> FailureReason.SOURCE_TIMING
                detail.contains("Audio clock lead") || detail.contains("Audio clock drift") || detail.contains("Audio buffered PCM") ||
                    detail.contains("Audio presented sample position") || detail.contains("Audio clock history") -> FailureReason.AUDIO_CLOCK
                detail.contains("Repeated audio underruns") -> FailureReason.AUDIO_UNDERRUN
                detail.contains("Audio output device changed") -> FailureReason.AUDIO_ROUTE
                detail.contains("AudioTrack write failed") -> FailureReason.AUDIO_WRITE
                detail.contains("Audio output stalled") -> FailureReason.AUDIO_STALL
                phase == "Initializing tracks" && (detail.contains("AudioTrack") || detail.contains("Device minimum audio buffer")) -> FailureReason.AUDIO_INITIALIZATION
                else -> FailureReason.OTHER
            }
            val offset = if (reason in setOf(FailureReason.SOURCE_TIMING, FailureReason.AUDIO_CLOCK))
                Regex("(?:offset is |discontinuous by |lead is |buffered PCM is )(-?\\d+) us").find(detail)?.groupValues?.get(1)?.toLongOrNull() else null
            val localPreparation = if (origin == FailureOrigin.PEER) null else preparation
            return FailureDiagnostic(code, origin, reason, phase, offset,
                context?.groupValues?.get(1) ?: video?.stage?.name ?: when {
                    origin == FailureOrigin.PRESENTATION -> "ORIENTATION_APPLY"
                    localPreparation != null -> "OUTPUT_PREPARE"
                    else -> null
                },
                video?.exceptionType ?: if (localPreparation != null) "AudioOutputPreparationException" else context?.groupValues?.get(2),
                if (origin == FailureOrigin.PEER) null else pressure, localPreparation, audioClock, video)
        }
    }
}

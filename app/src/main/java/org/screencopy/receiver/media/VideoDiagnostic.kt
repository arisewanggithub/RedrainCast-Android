package org.screencopy.receiver.media

enum class VideoStage { SELECT, CREATE, CONFIGURE, START, CLOCK, INPUT_KEYFRAME, INPUT_DEQUEUE, INPUT_BUFFER, INPUT_QUEUE, OUTPUT_DEQUEUE, OUTPUT_VALIDATE, OUTPUT_RELEASE, CLOSE }
enum class VideoFault { NONE, UNSUPPORTED, FIRST_KEYFRAME, INPUT_BUFFER, INPUT_CAPACITY, INPUT_PTS, OUTPUT_METADATA, OUTPUT_PTS, END_OF_STREAM, CLOCK_WAIT, CLOCK_FAILURE, INPUT_STALL, OUTPUT_STALL, CODEC_RESOURCE, CODEC_RECLAIMED, CODEC_API }

data class VideoDiagnostic(
    val stage: VideoStage,
    val fault: VideoFault,
    val exceptionType: String,
    val generation: Long,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val frameRate: Int,
    val delayUs: Long?,
    val heldUs: Long,
    val inputBytes: Int,
    val inputCapacity: Int,
    val inputKeyFrame: Boolean,
    val outputBytes: Int,
    val outputFlags: Int,
    val queuedFrames: Long,
    val decodedFrames: Long,
    val renderSubmitted: Long,
    val decodedSkipped: Long,
    val lateRendered: Long,
    val codecError: Int? = null,
    val codecRecoverable: Boolean = false,
    val codecTransient: Boolean = false,
    val codec: String = "UNKNOWN",
    val inputWaitUs: Long = 0,
    val outputWaitUs: Long = 0
) {
    fun logMessage() = "video_stage=$stage video_fault=$fault video_exception=$exceptionType video_generation=$generation" +
        " reconfigured=${generation > 1} video_codec=$codec width=$width height=$height rotation=$rotation frame_rate=$frameRate" +
        " delay_available=${delayUs != null} frame_delay_us=${delayUs ?: 0} held_us=$heldUs" +
        " input_wait_us=$inputWaitUs output_wait_us=$outputWaitUs" +
        " input_bytes=$inputBytes input_capacity=$inputCapacity input_keyframe=$inputKeyFrame" +
        " output_bytes=$outputBytes output_flags=$outputFlags queued_frames=$queuedFrames decoded_frames=$decodedFrames" +
        " render_submitted=$renderSubmitted decoded_skipped=$decodedSkipped late_rendered=$lateRendered" +
        " codec_error_available=${codecError != null} codec_error=${codecError ?: 0}" +
        " codec_recoverable=$codecRecoverable codec_transient=$codecTransient"

    companion object {
        internal fun fromLocalDetail(detail: String): VideoDiagnostic? {
            val fields = detail.substringAfter("Video failure: ", "").split(' ').mapNotNull {
                val pair = it.split('=', limit = 2)
                if (pair.size == 2) pair[0] to pair[1] else null
            }.toMap()
            val stage = VideoStage.entries.firstOrNull { it.name == fields["video_stage"] } ?: return null
            val fault = VideoFault.entries.firstOrNull { it.name == fields["video_fault"] } ?: return null
            val exception = fields["video_exception"]?.takeIf { it.matches(Regex("[A-Za-z0-9_$]{1,64}")) } ?: return null
            fun integer(key: String) = fields[key]?.toIntOrNull()
            fun long(key: String) = fields[key]?.toLongOrNull()
            return VideoDiagnostic(stage, fault, exception, long("video_generation") ?: return null,
                integer("width") ?: return null, integer("height") ?: return null, integer("rotation") ?: return null,
                integer("frame_rate") ?: return null,
                if (fields["delay_available"] == "true") long("frame_delay_us") ?: return null else null,
                long("held_us") ?: return null, integer("input_bytes") ?: return null, integer("input_capacity") ?: return null,
                fields["input_keyframe"] == "true", integer("output_bytes") ?: return null, integer("output_flags") ?: return null,
                long("queued_frames") ?: return null, long("decoded_frames") ?: return null, long("render_submitted") ?: return null,
                long("decoded_skipped") ?: return null, long("late_rendered") ?: return null,
                if (fields["codec_error_available"] == "true") integer("codec_error") ?: return null else null,
                fields["codec_recoverable"] == "true", fields["codec_transient"] == "true",
                fields["video_codec"]?.takeIf { it in setOf("H264", "H265", "AV1", "UNKNOWN") } ?: return null,
                long("input_wait_us") ?: return null, long("output_wait_us") ?: return null)
        }
    }
}

class VideoPlaybackException(val diagnostic: VideoDiagnostic, cause: Exception? = null) :
    PlaybackException("Video failure: ${diagnostic.logMessage()}",
        if (diagnostic.fault == VideoFault.UNSUPPORTED) "UNSUPPORTED_CONFIG" else "DECODER_FAILED") {
    init { if (cause != null) initCause(cause) }
}

internal class VideoInitializationFailure(val stage: VideoStage, val failure: Exception) :
    IllegalStateException("Video initialization failed", failure)

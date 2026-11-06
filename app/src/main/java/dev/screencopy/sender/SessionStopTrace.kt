package dev.screencopy.sender

internal class SessionStopTrace(private val log: (String) -> Unit, private val clock: () -> Long = System::nanoTime) {
    enum class Stage {
        REQUESTED, CLOSING, TERMINAL_QUEUED, TERMINAL_SENT, STARTUP_CANCEL, STARTUP_BRIDGE_CLOSE_BEGIN,
        STARTUP_BRIDGE_CLOSE_END, BRIDGE_CLOSE_BEGIN,
        BRIDGE_CLOSE_END, CAPTURE_JOIN_BEGIN, CAPTURE_JOIN_END, SHELL_CLEANUP_BEGIN, SHELL_CLEANUP_END,
        RECEIVER_DRAIN_DONE, LINK_CLOSED, READERS_JOINED, FINISHED
    }
    enum class Phase { ACCESS, DEPLOYING, CAPABILITIES, CONNECTING, NEGOTIATING, CAPTURE_START, STREAMING, CLEANUP }
    @Volatile private var phase = Phase.ACCESS
    fun phase(value: Phase) { phase = value }
    fun currentPhase() = phase
    fun sessionStarted(session: Long) {
        log("sender_session_started ${Wire.obj("session" to session.toString(), "timestamp_ms" to System.currentTimeMillis().toString())}")
    }
    fun firstFailure(error: Exception, atFailure: Phase, origin: SessionOutcome.Origin, session: Long, settings: Settings,
                     timestampMs: Long = System.currentTimeMillis(), lastReceiveAgeMs: Long? = null, writeElapsedMs: Long? = null) {
        val original = if (error is java.io.IOException) error
            else java.io.IOException("${SessionOutcome.errorCode(error)}: session failed", error)
        val safe = SenderFailureDiagnostics.sanitize(original)
        val chain = SenderFailureDiagnostics.chain(original)
        val root = chain.last()
        val causes = SenderFailureDiagnostics.causes(chain)
        val stack = SenderFailureDiagnostics.stack(chain)
        fun codec(value: String) = value.takeIf { it in setOf("h264", "h265", "av1", "opus", "aac", "raw") } ?: "UNKNOWN"
        // Wire JSON numbers are int32; local diagnostic identifiers/times are decimal int64 strings.
        log("sender_session_failure ${Wire.obj(
            "session" to session.toString(), "timestamp_ms" to timestampMs.toString(), "phase" to atFailure.name, "origin" to origin.name,
            "summary" to safe.message, "causes" to org.json.JSONArray(causes), "stack" to org.json.JSONArray(stack),
            "failure_category" to SenderFailureDiagnostics.type(root), "root_exception" to SenderFailureDiagnostics.type(root),
            "cause_chain_truncated" to (root.cause != null),
            "last_control_receive_age_ms" to lastReceiveAgeMs?.toString(), "control_write_elapsed_ms" to writeElapsedMs?.toString(),
            "video_codec" to codec(settings.videoCodec), "audio_codec" to codec(settings.audioCodec),
            "audio_enabled" to settings.audio, "max_size" to settings.maxSize, "max_fps" to settings.maxFps,
            "crop_width" to settings.cropWidth, "crop_height" to settings.cropHeight,
            "track" to (error as? SenderProtocolException)?.evidence?.track?.name,
            "generation" to (error as? SenderProtocolException)?.evidence?.generation
        )}")
    }
    fun congestion(snapshot: SenderQueueFailure, atFailure: Phase) { log("sender_queue_failure phase=$atFailure ${snapshot.fields()}") }
    fun captureProtocol(evidence: CaptureProtocolFailure, format: CaptureFormat, atFailure: Phase, origin: SessionOutcome.Origin) {
        log("sender_capture_failure phase=$atFailure origin=$origin ${evidence.fields(format)}")
    }
    fun startup(error: CaptureStreamEof, atFailure: Phase, video: CaptureFormat, audio: CaptureFormat, audioEnabled: Boolean,
                requested: Settings? = null) {
        val orientation = requested?.orientation?.takeIf { it in setOf("0", "90", "180", "270", "@0", "@90", "@180", "@270") } ?: "UNKNOWN"
        log("sender_startup_failure code=${SessionOutcome.errorCode(error)} phase=$atFailure selected_video_format=$video" +
            " selected_audio_format=$audio audio_enabled=$audioEnabled requested_crop_width=${requested?.cropWidth ?: 0}" +
            " requested_crop_height=${requested?.cropHeight ?: 0} max_size=${requested?.maxSize ?: 0} max_fps=${requested?.maxFps ?: 0}" +
            " selected_orientation=$orientation ${error.evidence.fields()}")
    }
    fun geometry(error: VideoGeometryException, format: CaptureFormat, atFailure: Phase) {
        val shape = error.geometry
        log("sender_startup_failure code=UNSUPPORTED_CONFIG reason=${error.reason} stage=VIDEO_PREFLIGHT format=$format phase=$atFailure" +
            " requested_width=${shape.requested.width} requested_height=${shape.requested.height}" +
            " output_width=${shape.output.width} output_height=${shape.output.height} max_size=${shape.maxSize}" +
            " normalized_max_size=${shape.normalizedMaxSize} fps=${shape.fps} checked_encoders=${error.checkedEncoders}" +
            error.limits.mapIndexed { index, limits -> " limit${index}_width=${limits.minWidth}..${limits.maxWidth}" +
                " limit${index}_height=${limits.minHeight}..${limits.maxHeight} limit${index}_alignment=${limits.widthAlignment}x${limits.heightAlignment}" +
                " limit${index}_fps=${limits.minFps}..${limits.maxFps}" }.joinToString("") +
            " suggested_width=${error.suggestion?.first?.width ?: 0} suggested_height=${error.suggestion?.first?.height ?: 0}" +
            " suggested_fps=${error.suggestion?.second ?: 0}")
    }
    private val lock = Any()
    private var begin: Long? = null
    private val stages = mutableSetOf<Stage>()
    private val causes = mutableSetOf<Pair<SessionOutcome.Origin, Boolean>>()
    private fun elapsed() = ((clock() - checkNotNull(begin)) / 1_000_000).coerceAtLeast(0)
    fun mark(stage: Stage) {
        val text = synchronized(lock) {
            if (!stages.add(stage)) return
            if (begin == null) begin = clock()
            "stop_stage=$stage phase=$phase elapsed_ms=${elapsed()}"
        }
        log(text)
    }
    fun cause(origin: SessionOutcome.Origin, error: Exception, retained: Boolean, atFailure: Phase = phase) {
        val text = synchronized(lock) {
            if (!causes.add(origin to retained)) return
            if (begin == null) begin = clock()
            var root: Throwable = error
            repeat(8) { root.cause?.let { root = it } }
            val cleanup = cleanupCode(error)
            val waitBudget = when (cleanup) {
                "CLEANUP_GUARDIAN_TIMEOUT" -> 6000
                "CLEANUP_CANCEL_TIMEOUT", "CLEANUP_DEPLOY_TIMEOUT" -> 2000
                "CLEANUP_COMMAND_TIMEOUT" -> 10000
                else -> 0
            }
            "stop_origin=$origin exception=${error.javaClass.simpleName} cause=${root.javaClass.simpleName}" +
                " retained=$retained cleanup_code=$cleanup wait_budget_ms=$waitBudget phase=$atFailure elapsed_ms=${elapsed()}"
        }
        log(text)
    }
    private fun cleanupCode(error: Exception): String {
        var cause: Throwable? = error
        val codes = listOf("CLEANUP_GUARDIAN_TIMEOUT", "CLEANUP_GUARDIAN_EXIT", "CLEANUP_GUARDIAN_INTERRUPTED", "CLEANUP_CANCEL_TIMEOUT",
            "CLEANUP_DEPLOY_TIMEOUT", "CLEANUP_COMMAND_TIMEOUT", "CLEANUP_COMMAND_EXIT", "CLEANUP_ARTIFACT_REMOVE", "CLEANUP_NOT_CONFIRMED")
        repeat(8) {
            val message = cause?.message.orEmpty().removePrefix("SHELL_IO: ")
            codes.firstOrNull { message.startsWith(it) }?.let { return it }
            cause = cause?.cause
        }
        return "NONE"
    }
}

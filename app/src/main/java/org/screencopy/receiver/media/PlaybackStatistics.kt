package org.screencopy.receiver.media

import java.util.Locale

data class EncodedQueueStatistics(val entries: Int, val bytes: Int, val spanUs: Long, val oldestAgeMs: Long?,
    val maximumBytes: Int, val maximumEntries: Int = 48, val maximumSpanUs: Long = 250_000)

data class VideoStatistics(val decoded: Long, val scheduled: Long, val skipped: Long,
    val rendered: Long?, val renderedPtsUs: Long?, val renderedAtNs: Long? = null)

data class AudioStatistics(val bufferedMs: Double, val targetMs: Double, val paddingMs: Double,
    val silenceMs: Double, val discardedMs: Double, val playedSourcePtsUs: Long?, val devicePlayedMs: Double,
    val playedSourceObservedAtNs: Long? = null)

data class SourceTimestampObservation(val ptsUs: Long, val observedAtNs: Long)

data class PlaybackStatisticsInput(
    val session: Long, val generation: Long, val audioEpoch: Long, val state: String,
    val videoCodec: String, val audioCodec: String?, val encodedWidth: Int?, val encodedHeight: Int?,
    val requestedFps: Int, val configuredVideoBps: Int, val configuredAudioBps: Int?,
    val rotation: Int, val orientation: String, val receivedVideo: Long, val receivedAudio: Long,
    val videoBytes: Long, val audioBytes: Long, val video: VideoStatistics?, val audio: AudioStatistics?,
    val videoQueue: EncodedQueueStatistics?, val audioQueue: EncodedQueueStatistics?,
    val rttMs: Long?, val controlAgeMs: Long,
    val videoLastPacketAgeMs: Long? = null, val audioLastPacketAgeMs: Long? = null,
    val playbackEpoch: Long? = null, val occluded: Boolean? = null,
    val decodedVideo: DecodedVideoStatistics? = null,
    val presentation: VideoPresentationStatistics? = null
)

data class PlaybackStatisticsSnapshot(val input: PlaybackStatisticsInput, val rxFps: Double?,
    val decodedFps: Double?, val presentedFps: Double?, val videoMbps: Double?, val audioMbps: Double?,
    val recentSkippedPercent: Double?, val recentSilenceMs: Double?, val recentDiscardedMs: Double?,
    val syncMs: Double?, val recentSyncPeakMs: Double?, val generationSyncPeakMs: Double?,
    val sampledAtMs: Long? = null) {
    fun forDisplay(nowMs: Long): PlaybackStatisticsSnapshot {
        val sampled = sampledAtMs ?: return this
        val age = (nowMs - sampled).coerceAtLeast(0)
        if (age <= 2500) return this
        return copy(input = input.copy(state = "Statistics stale (${age} ms)",
            audio = null, videoQueue = null, audioQueue = null, rttMs = null,
            controlAgeMs = input.controlAgeMs + age,
            videoLastPacketAgeMs = input.videoLastPacketAgeMs?.plus(age),
            audioLastPacketAgeMs = input.audioLastPacketAgeMs?.plus(age)),
            rxFps = null, decodedFps = null, presentedFps = null, videoMbps = null, audioMbps = null,
            recentSkippedPercent = null, recentSilenceMs = null, recentDiscardedMs = null,
            syncMs = null, recentSyncPeakMs = null)
    }
}

/** One consumer samples typed counters at 1 Hz; native output is never queried by the UI. */
class PlaybackStatisticsSampler {
    private var previous: Pair<Long, PlaybackStatisticsInput>? = null
    private var peak: Double? = null
    @Synchronized fun reset() { previous = null; peak = null }
    @Synchronized fun sample(nowMs: Long, input: PlaybackStatisticsInput): PlaybackStatisticsSnapshot {
        val old = previous
        val same = old != null && old.second.session == input.session &&
            old.second.generation == input.generation && old.second.audioEpoch == input.audioEpoch &&
            old.second.state == input.state && nowMs > old.first &&
            input.receivedVideo >= old.second.receivedVideo && input.videoBytes >= old.second.videoBytes &&
            input.audioBytes >= old.second.audioBytes &&
            input.receivedAudio >= old.second.receivedAudio &&
            (input.video?.decoded ?: 0) >= (old.second.video?.decoded ?: 0) &&
            (input.video?.rendered ?: 0) >= (old.second.video?.rendered ?: 0) &&
            (input.video?.scheduled ?: 0) >= (old.second.video?.scheduled ?: 0) &&
            (input.video?.skipped ?: 0) >= (old.second.video?.skipped ?: 0) &&
            (input.audio?.silenceMs ?: 0.0) >= (old.second.audio?.silenceMs ?: 0.0) &&
            (input.audio?.discardedMs ?: 0.0) >= (old.second.audio?.discardedMs ?: 0.0) &&
            (input.audio?.devicePlayedMs ?: 0.0) >= (old.second.audio?.devicePlayedMs ?: 0.0)
        if (!same) peak = null
        val prior = if (same) old?.second else null
        val seconds = if (same) (nowMs - checkNotNull(old).first) / 1000.0 else null
        fun rate(value: Long, before: Long?): Double? =
            if (seconds != null && before != null && value >= before) (value - before) / seconds else null
        val sync = comparableSkew(nowMs, input.video, input.audio)
        val recentPeak = sync?.let { kotlin.math.abs(it) }
        if (sync != null) peak = maxOf(peak ?: 0.0, kotlin.math.abs(sync))
        previous = nowMs to input
        val skipped = input.video?.let { video -> prior?.video?.let { before ->
            val rendered = video.rendered
            val previousRendered = before.rendered
            val discarded = video.skipped - before.skipped
            val total = if (rendered != null && previousRendered != null)
                rendered - previousRendered + discarded else 0
            if (total > 0) discarded * 100.0 / total else null
        } }
        return PlaybackStatisticsSnapshot(input, rate(input.receivedVideo, prior?.receivedVideo),
            input.video?.let { rate(it.decoded, prior?.video?.decoded) },
            input.video?.rendered?.let { rate(it, prior?.video?.rendered) },
            rate(input.videoBytes, prior?.videoBytes)?.times(8.0 / 1_000_000),
            if (input.audioCodec == null) null else rate(input.audioBytes, prior?.audioBytes)?.times(8.0 / 1_000_000),
            skipped, input.audio?.let { a -> prior?.audio?.let { (a.silenceMs - it.silenceMs).coerceAtLeast(0.0) } },
            input.audio?.let { a -> prior?.audio?.let { (a.discardedMs - it.discardedMs).coerceAtLeast(0.0) } },
            sync, recentPeak, peak, nowMs)
    }

    private fun comparableSkew(nowMs: Long, video: VideoStatistics?, audio: AudioStatistics?): Double? {
        val videoPts = video?.renderedPtsUs ?: return null
        val audioPts = audio?.playedSourcePtsUs ?: return null
        val videoAt = video.renderedAtNs ?: return null
        val audioAt = audio.playedSourceObservedAtNs ?: return null
        val videoMs = Math.floorDiv(videoAt, 1_000_000L)
        val audioMs = Math.floorDiv(audioAt, 1_000_000L)
        // Compare observations, never extrapolate source PTS across gaps or clock corrections.
        if (videoMs > nowMs || audioMs > nowMs || nowMs - videoMs > 250 ||
            nowMs - audioMs > 250) return null
        if (kotlin.math.abs(videoAt - audioAt) > 10_000_000L) return null
        return (videoPts - audioPts) / 1000.0
    }
}

data class StatisticsField(val id: String, val label: String, val value: String)
data class StatisticsConnection(val id: String, val channel: String, val queued: String,
    val span: String, val lastActivity: String, val rtt: String)
data class PixelDimensions(val width: Int, val height: Int) {
    init { require(width > 0 && height > 0) }
    fun format() = "$width × $height px"
}
data class PlaybackDisplayMeasurements(
    val renderTarget: PixelDimensions? = null,
    val displayOutput: PixelDimensions? = null
)
data class PlaybackStatisticsDisplay(
    val header: String, val kpis: List<StatisticsField>, val totals: List<StatisticsField>,
    val video: List<StatisticsField>, val audio: List<StatisticsField>,
    val connections: List<StatisticsConnection>, val footer: List<StatisticsField>,
    val details: List<StatisticsField>, val diagnostics: List<StatisticsField> = emptyList()
) {
    val connectionHeaders = listOf("Channel", "Queued", "Span", "Last activity", "RTT")
    val notes = listOf("Silence/discard: total (+recent)", "Positive skew = video ahead",
        "Source timestamps · not end-to-end latency")
    val queueCaption = "Encoded queues: waiting packets"
    val peakCaption = "Skew sampled 1 Hz"
    val totalsCaption = "Current generation totals · renderer skips, not network loss"
}

object PlaybackStatisticsText {
    fun streamRange(video: DecodedVideoStatistics?): String {
        val range = when (video?.streamFormat) {
            VideoStreamFormat.Hdr10 -> "HDR10"
            VideoStreamFormat.Sdr -> "SDR"
            null -> return "N/A"
        }
        val transfer = when (video.transfer) {
            VideoTransfer.Pq -> "PQ"
            VideoTransfer.Bt709 -> "BT.709"
            null -> return range
        }
        return "$range · $transfer"
    }

    fun decodeFormat(video: DecodedVideoStatistics?, bitText: (Int) -> String = { "$it-bit" }): String =
        if (video?.codecProfile == null && video?.bitDepth == null) "N/A"
        else "${video?.codecProfile ?: "N/A"} · ${video?.bitDepth?.let(bitText) ?: "N/A"}"

    fun presentationOutput(presentation: VideoPresentationStatistics?): String = when (presentation?.output) {
        VideoPresentationOutput.Hdr -> "HDR output"
        VideoPresentationOutput.Sdr -> "SDR output"
        VideoPresentationOutput.SdrToneMapped -> "SDR (tone-mapped)"
        null -> "N/A"
    }

    private fun number(value: Double?, suffix: String = "") =
        value?.let { String.format(Locale.ROOT, "%.1f%s", it, suffix) } ?: "N/A"
    private fun age(value: Long?) = value?.let { "$it ms" } ?: "N/A"
    private fun codec(value: String?) = when (value) {
        "h264" -> "H.264"
        "h265" -> "H.265 (HEVC)"
        "av1" -> "AV1"
        "raw", "pcm" -> "PCM"
        "opus" -> "Opus"
        "aac" -> "AAC"
        null -> "Off"
        else -> value
    }

    fun display(snapshot: PlaybackStatisticsSnapshot, canvasWidth: Int, canvasHeight: Int,
        measurements: PlaybackDisplayMeasurements = PlaybackDisplayMeasurements()): PlaybackStatisticsDisplay {
        val s = snapshot.input
        val video = s.video
        val audio = s.audio
        fun field(id: String, label: String, value: String) = StatisticsField(id, label, value)
        fun total(value: Long?) = value?.toString() ?: "N/A"
        fun change(value: Double?, recent: Double?): String =
            if (value == null) "N/A" else "${number(value)} (+${number(recent)}) ms"
        fun connection(id: String, label: String, queue: EncodedQueueStatistics?, activity: Long?) =
            StatisticsConnection(id, label,
                queue?.let { "${it.entries} packets · ${it.bytes} B" } ?: "N/A",
                number(queue?.spanUs?.div(1000.0), " ms"), age(activity), "—")
        val orientation = s.orientation.replaceFirstChar { it.uppercase() }
        val skew = snapshot.syncMs?.let { String.format(Locale.ROOT, "%+.1f ms", it) } ?: "N/A"
        return PlaybackStatisticsDisplay("Playback statistics",
            listOf(
                field("receivedFps", "Received FPS", number(snapshot.rxFps)),
                field("decodedFps", "Decoded FPS", number(snapshot.decodedFps)),
                field("presentedFps", "Presented FPS", number(snapshot.presentedFps)),
                field("skippedRecent", "Skipped · recent", number(snapshot.recentSkippedPercent, "%"))),
            listOf(field("decoded", "Decoded", total(video?.decoded)),
                field("presented", "Presented", total(video?.rendered)),
                field("skipped", "Skipped", total(video?.skipped))),
            listOf(
                field("videoCodec", "Codec", codec(s.videoCodec)),
                field("videoDynamicRange", "Stream range", streamRange(s.decodedVideo)),
                field("videoDecodeFormat", "Profile / depth", decodeFormat(s.decodedVideo)),
                field("presentationOutput", "Output", presentationOutput(s.presentation)),
                field("encodedSize", "Encoded size", if (s.encodedWidth != null && s.encodedHeight != null)
                    "${s.encodedWidth} × ${s.encodedHeight}" else "N/A"),
                field("captureLimit", "Capture limit", if (s.requestedFps == 0) "Uncapped" else "${s.requestedFps} FPS"),
                field("displayOutput", "Display mode", measurements.displayOutput?.format() ?: "N/A"),
                field("rotation", "Rotation", "${s.rotation}° · $orientation"),
                field("videoBitrate", "Video bitrate", number(snapshot.videoMbps, " Mbps")),
                field("configuredBitrate", "Configured bitrate",
                    number(s.configuredVideoBps.takeIf { it > 0 }?.div(1_000_000.0), " Mbps")),
                field("networkLoss", "Network loss", "N/A")),
            listOf(
                field("audioFormat", "Audio format", codec(s.audioCodec)),
                field("audioBitrate", "Audio bitrate", number(snapshot.audioMbps, " Mbps")),
                field("pcmBufferTarget", "PCM buffer / target", if (audio == null) "N/A"
                    else "${number(audio.bufferedMs)} / ${number(audio.targetMs)} ms"),
                field("devicePadding", "Device padding", number(audio?.paddingMs, " ms")),
                field("insertedSilence", "Inserted silence", change(audio?.silenceMs, snapshot.recentSilenceMs)),
                field("discardedPcm", "Discarded PCM", change(audio?.discardedMs, snapshot.recentDiscardedMs)),
                field("avSkew", "A/V skew", skew),
                field("peakObservedSkew", "Peak observed skew", number(snapshot.generationSyncPeakMs, " ms"))),
            listOf(connection("video", "Video", s.videoQueue, s.videoLastPacketAgeMs),
                connection("audio", "Audio", s.audioQueue, s.audioLastPacketAgeMs),
                StatisticsConnection("control", "Control", "—", "—", age(s.controlAgeMs), age(s.rttMs))),
            listOf(field("state", "State", s.state), field("generation", "Generation", s.generation.toString()),
                field("playbackEpoch", "Playback epoch", total(s.playbackEpoch)),
                field("occluded", "Occluded", s.occluded?.let { if (it) "Yes" else "No" } ?: "N/A")),
            listOf(field("scheduledFrames", "Scheduled frames", total(video?.scheduled)),
                field("audioEpoch", "Audio epoch", s.audioEpoch.takeIf { it > 0 }?.toString() ?: "N/A"),
                field("devicePlayed", "Device played", number(audio?.devicePlayedMs, " ms"))),
            listOf(
                field("receiverCanvas", "App view size", if (canvasWidth > 0 && canvasHeight > 0)
                    "$canvasWidth × $canvasHeight px" else "N/A"),
                field("renderTarget", "Render target", measurements.renderTarget?.format() ?: "N/A"),
                field("renderTargetMeasurement", "Render target measurement",
                    if (measurements.renderTarget == null) "Unavailable; view size is not a buffer measurement" else "Measured compositor target"),
                field("displayOutputMeasurement", "Display mode source",
                    if (measurements.displayOutput == null) "Unavailable" else "Android active display mode; not scanout proof")))
    }

    fun format(snapshot: PlaybackStatisticsSnapshot, canvasWidth: Int, canvasHeight: Int,
        measurements: PlaybackDisplayMeasurements = PlaybackDisplayMeasurements()): String {
        val model = display(snapshot, canvasWidth, canvasHeight, measurements)
        fun fields(values: List<StatisticsField>) = values.joinToString("\n") { "${it.label}: ${it.value}" }
        return listOf(model.header, fields(model.kpis), model.totalsCaption, fields(model.totals),
            "VIDEO STREAM", fields(model.video), "AUDIO & SYNC", fields(model.audio),
            model.peakCaption, model.notes.joinToString("\n"), "CONNECTIONS & QUEUES",
            model.connectionHeaders.joinToString(" | "), model.connections.joinToString("\n") {
                "${it.channel} | ${it.queued} | ${it.span} | ${it.lastActivity} | ${it.rtt}"
            }, model.queueCaption, fields(model.footer), "Android details", fields(model.details),
            "Display diagnostics", fields(model.diagnostics)).joinToString("\n")
    }
}

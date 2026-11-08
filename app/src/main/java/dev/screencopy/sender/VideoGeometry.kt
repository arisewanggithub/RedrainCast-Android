package dev.screencopy.sender

import java.io.IOException

internal data class VideoSize(val width: Int, val height: Int) {
    fun rotated() = VideoSize(height, width)
    override fun toString() = "${width}x$height"
}
internal data class VideoGeometry(val requested: VideoSize, val output: VideoSize, val maxSize: Int, val normalizedMaxSize: Int, val fps: Int) {
    fun description(): String {
        val rate = if (fps == 0) "with uncapped capture FPS" else "at up to $fps fps"
        return "Requested capture $requested, max size $maxSize (scrcpy $normalizedMaxSize); encoded output $output $rate."
    }
}
internal object ScrcpyVideoSizing {
    fun calculate(settings: Settings, inventory: DisplayInventory): VideoGeometry {
        settings.validate(); inventory.validate(settings)
        val display = inventory.displays.single { it.id == settings.displayId }
        if (display.rotation == null) throw IOException("UNSUPPORTED_CONFIG: Refresh display rotation metadata before checking video size.")
        var size = VideoSize(display.naturalWidth, display.naturalHeight)
        if (settings.cropWidth > 0) {
            size = VideoSize(settings.cropWidth, settings.cropHeight)
        }
        val requested = size
        val max = settings.maxSize and -8
        if (settings.maxSize in 1..7) throw IOException("UNSUPPORTED_CONFIG: Max size ${settings.maxSize} becomes 0 (unlimited) in scrcpy. Choose 0 explicitly or at least 8.")
        val portrait = size.height > size.width
        var major = maxOf(size.width, size.height)
        var minor = minOf(size.width, size.height)
        if (max > 0 && major > max) { minor = (max.toLong() * minor / major).toInt(); major = max }
        major = major and -8
        minor = (minor + 4) and -8
        if (minor > major) minor = major
        size = if (portrait) VideoSize(minor, major) else VideoSize(major, minor)
        return VideoGeometry(requested, size, settings.maxSize, max, settings.maxFps)
    }
}
internal data class VideoLimits(val minWidth: Int, val maxWidth: Int, val minHeight: Int, val maxHeight: Int,
                                val widthAlignment: Int, val heightAlignment: Int, val minFps: Double, val maxFps: Double) {
    init {
        require(minWidth >= 0 && maxWidth >= minWidth && minHeight >= 0 && maxHeight >= minHeight &&
            widthAlignment > 0 && heightAlignment > 0 && minFps.isFinite() && maxFps.isFinite() && maxFps >= minFps)
    }
    fun describe() = "width $minWidth..$maxWidth aligned $widthAlignment; height $minHeight..$maxHeight aligned $heightAlignment; fps $minFps..$maxFps"
}
internal data class EncoderGeometryCheck(val limits: VideoLimits?, val sizeSupported: Boolean?, val rateSupported: Boolean?,
                                        val suggestion: Pair<VideoSize, Int>? = null)
internal enum class VideoGeometryReason { ZERO_OUTPUT, SIZE_OR_ALIGNMENT, FRAME_RATE, AUTO_NOT_FIXED, METADATA_UNAVAILABLE }
internal class VideoGeometryException(val geometry: VideoGeometry, val reason: VideoGeometryReason, val details: String,
    val limits: List<VideoLimits> = emptyList(), val suggestion: Pair<VideoSize, Int>? = null, val checkedEncoders: Int = 0) :
    IOException("UNSUPPORTED_CONFIG: ${geometry.description()} $details")

internal object VideoPreflightReport {
    fun encode(geometry: VideoGeometry, failure: VideoGeometryException? = null): String = Wire.obj(
        "ok" to (failure == null), "requestedWidth" to geometry.requested.width, "requestedHeight" to geometry.requested.height,
        "width" to geometry.output.width, "height" to geometry.output.height, "maxSize" to geometry.maxSize,
        "normalizedMaxSize" to geometry.normalizedMaxSize, "fps" to geometry.fps,
        "reason" to (failure?.reason?.name ?: ""), "details" to (failure?.details ?: ""),
        "checkedEncoders" to (failure?.checkedEncoders ?: 0),
        "limits" to org.json.JSONArray().apply {
            failure?.limits?.forEach { put(Wire.obj("minWidth" to it.minWidth, "maxWidth" to it.maxWidth,
                "minHeight" to it.minHeight, "maxHeight" to it.maxHeight, "widthAlignment" to it.widthAlignment,
                "heightAlignment" to it.heightAlignment, "minFps" to it.minFps, "maxFps" to it.maxFps)) }
        },
        "suggestedWidth" to (failure?.suggestion?.first?.width ?: 0), "suggestedHeight" to (failure?.suggestion?.first?.height ?: 0),
        "suggestedFps" to (failure?.suggestion?.second ?: 0)
    ).toString()
    fun decode(report: String): VideoGeometry {
        val value = Wire.parseJson(report.toByteArray())
        val geometry = VideoGeometry(VideoSize(Json.integer(value, "requestedWidth"), Json.integer(value, "requestedHeight")),
            VideoSize(Json.integer(value, "width"), Json.integer(value, "height")), Json.integer(value, "maxSize"),
            Json.integer(value, "normalizedMaxSize"), Json.integer(value, "fps"))
        if (!Json.bool(value, "ok")) {
            val array = value.getJSONArray("limits")
            require(array.length() <= 4) { "Video limits report exceeds bound" }
            val limits = (0 until array.length()).map {
                val info = array.getJSONObject(it)
                VideoLimits(Json.integer(info, "minWidth"), Json.integer(info, "maxWidth"), Json.integer(info, "minHeight"),
                    Json.integer(info, "maxHeight"), Json.integer(info, "widthAlignment"), Json.integer(info, "heightAlignment"),
                    info.getDouble("minFps"), info.getDouble("maxFps"))
            }
            val suggestion = if (Json.integer(value, "suggestedWidth") > 0) VideoSize(Json.integer(value, "suggestedWidth"),
                Json.integer(value, "suggestedHeight")) to Json.integer(value, "suggestedFps") else null
            throw VideoGeometryException(geometry, VideoGeometryReason.valueOf(Json.string(value, "reason")),
                Json.string(value, "details"), limits, suggestion, Json.integer(value, "checkedEncoders"))
        }
        return geometry
    }
}

internal object AutomaticVideoEncoderOrder {
    fun firstAttempts(canonicalNames: List<String>, softwarePreferred: Boolean?): List<Int> {
        if (canonicalNames.isEmpty()) return emptyList()
        if (softwarePreferred == false) return listOf(0)
        fun priority(name: String): Int {
            val c2 = name.startsWith("c2.", true)
            val omx = name.startsWith("OMX.", true)
            val software = name.startsWith("OMX.google.", true) || name.startsWith("c2.android.", true) || (!c2 && !omx)
            return (if (software) 0 else 3) + (if (c2) 0 else if (omx) 1 else 2)
        }
        val priorities = canonicalNames.map(::priority)
        val minimum = priorities.min()
        val first = canonicalNames.indices.filter { priorities[it] == minimum }
        // Native sorting does not promise a stable order within an equal-priority tier.
        return if (softwarePreferred == true) first else (listOf(0) + first).distinct()
    }
}

internal object VideoGeometryValidation {
    fun validate(geometry: VideoGeometry, checks: List<EncoderGeometryCheck>, automatic: Boolean,
                 automaticFirstAttempts: List<Int> = checks.indices.toList()) {
        if (geometry.output.width <= 0 || geometry.output.height <= 0)
            throw VideoGeometryException(geometry, VideoGeometryReason.ZERO_OUTPUT, "Crop/size rounds to zero. Choose larger dimensions.")
        val considered = if (automatic) automaticFirstAttempts.map { checks[it] } else checks
        if (considered.isEmpty() || considered.any { it.limits == null || it.sizeSupported == null || it.rateSupported == null })
            throw VideoGeometryException(geometry, VideoGeometryReason.METADATA_UNAVAILABLE,
                "Video size/rate metadata unavailable. Refresh capture capabilities or explicitly choose an encoder with available metadata.")
        val supported = considered.count { it.sizeSupported == true && it.rateSupported == true }
        if (supported == considered.size) return
        val reason = if (automatic && supported > 0) VideoGeometryReason.AUTO_NOT_FIXED
            else if (considered.any { it.sizeSupported == false }) VideoGeometryReason.SIZE_OR_ALIGNMENT else VideoGeometryReason.FRAME_RATE
        val limits = considered.mapNotNull { it.limits?.describe() }.distinct().take(4).joinToString(" | ")
        val suggestion = considered.firstNotNullOfOrNull { it.suggestion }
        val example = suggestion?.let {
            " Verified encoded example: ${it.first} " +
                (if (it.second == 0) "with uncapped capture FPS" else "at ${it.second} fps") +
                "; adjust crop/size/FPS explicitly within display bounds."
        }.orEmpty()
        val selection = if (automatic && supported > 0)
            "Automatic first-attempt ordering is uncertain and candidate metadata differs. Choose a compatible encoder explicitly; no candidate is substituted."
        else if (automatic)
            "The ranked automatic first-attempt encoder metadata does not support this size/rate. Adjust dimensions/FPS or choose a compatible encoder explicitly; allocation fallback is not assumed."
        else "Selected video size/rate is not supported by the checked encoder metadata."
        throw VideoGeometryException(geometry, reason, "$selection Reported limits: $limits.$example",
            considered.mapNotNull { it.limits }.distinct().take(4), suggestion, considered.size)
    }
}

package dev.screencopy.sender

import org.json.JSONObject
import org.json.JSONException

class Settings {
    var videoCodec = "h264"
    var videoEncoder = ""
    var video = true
    var fullscreen = false
    var orientation = "0"
    var receiverOrientation = "auto"
    var videoOptions = ""
    var videoBitrate = 8_000_000
    var maxSize = 1920
    var maxFps = 0
    var displayId = 0
    var cropWidth = 0
    var cropHeight = 0
    var cropX = 0
    var cropY = 0
    var audio = true
    var audioCodec = "raw"
    var audioEncoder = ""
    var audioSource = "playback"
    var audioOptions = ""
    var audioBitrate = 192_000
    var showPlaybackStatistics = false
    internal var invalidSavedError: String? = null

    fun saved(): JSONObject = Wire.obj(
        "receiverOrientation" to receiverOrientation,
        "video" to Wire.obj(
            "codec" to videoCodec, "encoder" to videoEncoder, "bitrate" to videoBitrate,
            "maxSize" to maxSize, "maxFps" to maxFps, "displayId" to displayId,
            "orientation" to orientation, "codecOptions" to videoOptions,
            "enabled" to video, "fullscreen" to fullscreen,
            "crop" to if (cropWidth == 0 && cropHeight == 0 && cropX == 0 && cropY == 0) JSONObject.NULL
                else Wire.obj("width" to cropWidth, "height" to cropHeight, "x" to cropX, "y" to cropY)
        ),
        "audio" to Wire.obj(
            "enabled" to audio, "codec" to audioCodec, "encoder" to audioEncoder,
            "bitrate" to audioBitrate, "source" to audioSource, "codecOptions" to audioOptions
        ),
        "ShowPlaybackStatistics" to showPlaybackStatistics
    )

    fun validate() {
        require(invalidSavedError == null, invalidSavedError ?: "Saved settings are invalid")
        require(video || audio, "Enable video, audio, or both")
        if (video) {
            require(videoCodec in setOf("h264", "h265", "av1"), "Unknown video codec")
            range(videoBitrate, 100_000, 200_000_000, "Video bitrate")
            range(maxSize, 0, 16384, "Maximum size")
            range(maxFps, 0, 240, "Maximum capture FPS")
            range(displayId, 0, Int.MAX_VALUE, "Display")
            require(orientation in setOf("0", "90", "180", "270", "@0", "@90", "@180", "@270"),
                "Invalid capture orientation")
            require(receiverOrientation in setOf("auto", "landscape", "portrait"), "Invalid receiver orientation")
            require(videoEncoder.isEmpty() || name.matches(videoEncoder), "Invalid video encoder name")
            validateOptions(videoOptions, true)
            if (!fullscreen) require(
                cropWidth == 0 && cropHeight == 0 && cropX == 0 && cropY == 0 ||
                    cropWidth in 1..16384 && cropHeight in 1..16384 && cropX >= 0 && cropY >= 0,
                "Crop must be disabled (all zero) or a positive rectangle"
            )
        }
        if (audio) {
            require(audioCodec in audioCodecs(), "Unknown audio codec")
            require(audioSource in setOf("output", "playback", "mic"), "Unknown audio source")
            if (audioCodec != "raw") range(audioBitrate, 16_000, 512_000, "Audio bitrate")
            else require(audioBitrate > 0,
                "Raw audio bitrate placeholder must remain positive; actual PCM rate is not selectable")
            require(audioEncoder.isEmpty() || name.matches(audioEncoder), "Invalid audio encoder name")
            validateOptions(audioOptions, false)
        }
    }

    fun request(): JSONObject {
        val crop = if (!video || fullscreen || cropWidth == 0) JSONObject.NULL else Wire.obj(
            "width" to cropWidth, "height" to cropHeight, "x" to cropX, "y" to cropY
        )
        val requestVideo = video
        val requestAudio = audio
        return Wire.obj(
            "receiverOrientation" to if (requestVideo) receiverOrientation else "auto",
            "video" to Wire.obj(
                "codec" to if (requestVideo) videoCodec else "h264",
                "encoder" to if (requestVideo) videoEncoder else "",
                "bitrate" to if (requestVideo) videoBitrate else 8_000_000,
                "maxSize" to if (requestVideo) maxSize else 1920,
                "maxFps" to if (requestVideo) maxFps else 0,
                "displayId" to if (requestVideo) displayId else 0,
                "orientation" to if (requestVideo) orientation else "0",
                "crop" to crop,
                "codecOptions" to if (requestVideo) videoOptions else ""
            ).apply { if (!requestVideo) put("enabled", false) },
            "audio" to Wire.obj(
                "enabled" to requestAudio,
                "codec" to if (requestAudio) audioCodec else "raw",
                "encoder" to if (requestAudio) audioEncoder else "",
                "bitrate" to if (requestAudio) audioBitrate else 192_000,
                "source" to if (requestAudio) audioSource else "playback",
                "codecOptions" to if (requestAudio) audioOptions else ""
            )
        )
    }

    companion object {
        fun audioCodecs(): List<String> = listOf("raw", "opus", "aac")

        private val name = Regex("[A-Za-z0-9._-]{1,160}")
        fun require(valid: Boolean, message: String) { if (!valid) throw IllegalArgumentException(message) }
        fun range(value: Int, min: Int, max: Int, name: String) = require(value in min..max, "$name must be $min..$max")

        fun validateOptions(options: String, video: Boolean) {
            require(options.length <= 1024, "Codec options too long")
            if (options.isEmpty()) return
            val allowed = if (video) setOf("i-frame-interval", "profile", "level", "bitrate-mode", "max-bframes", "repeat-previous-frame-after")
                else setOf("complexity", "bitrate-mode", "aac-profile", "flac-compression-level")
            val seen = mutableSetOf<String>()
            for (part in options.split(',')) {
                val kv = part.split('=')
                require(kv.size == 2 && kv[0].matches(Regex("[a-z][a-z0-9-]*(:(int|long|float))?")) &&
                    kv[1].matches(Regex("-?[0-9]{1,10}(\\.[0-9]{1,6})?")), "Codec options must be typed numeric key=value entries")
                val key = kv[0].split(':')
                require(key[0] in allowed && seen.add(key[0]), "Unsupported/duplicate codec option: ${key[0]}")
                val type = key.getOrElse(1) { "int" }
                try {
                    when (type) {
                        "float" -> require(kv[1].toFloat().isFinite(), "Nonfinite option")
                        "long" -> kv[1].toLong()
                        else -> kv[1].toInt()
                    }
                } catch (e: NumberFormatException) { throw IllegalArgumentException("Invalid $type codec option", e) }
                if (key[0] == "max-bframes") require(type == "int" && kv[1] == "0", "Protocol requires integer max-bframes=0")
            }
        }

        fun from(root: JSONObject, validate: Boolean = true): Settings = try {
            Settings().apply {
                if (root.has("ShowPlaybackStatistics")) showPlaybackStatistics = Json.bool(root, "ShowPlaybackStatistics")
                require(!root.has("mediaTransportMode"), "Legacy media transport settings are not supported")
                if (root.has("receiverOrientation")) receiverOrientation = Json.string(root, "receiverOrientation")
                val v = root.getJSONObject("video")
                val a = root.getJSONObject("audio")
                videoCodec = Json.string(v, "codec")
                videoEncoder = Json.string(v, "encoder")
                if (v.has("enabled")) video = Json.bool(v, "enabled")
                if (v.has("fullscreen")) fullscreen = Json.bool(v, "fullscreen")
                videoBitrate = Json.integer(v, "bitrate")
                maxSize = Json.integer(v, "maxSize")
                maxFps = Json.integer(v, "maxFps")
                displayId = Json.integer(v, "displayId")
                orientation = Json.string(v, "orientation")
                videoOptions = Json.string(v, "codecOptions")
                require(v.has("crop"), "Missing video.crop")
                val cropValue = v.get("crop")
                require(cropValue === JSONObject.NULL || cropValue is JSONObject, "video.crop must be an object or null")
                if (cropValue is JSONObject) {
                    require(cropValue.has("width") && cropValue.has("height") &&
                        cropValue.has("x") && cropValue.has("y"), "video.crop requires width, height, x, and y")
                    cropWidth = Json.integer(cropValue, "width")
                    cropHeight = Json.integer(cropValue, "height")
                    cropX = Json.integer(cropValue, "x")
                    cropY = Json.integer(cropValue, "y")
                }
                audio = Json.bool(a, "enabled")
                audioCodec = Json.string(a, "codec")
                audioEncoder = Json.string(a, "encoder")
                audioBitrate = Json.integer(a, "bitrate")
                audioSource = Json.string(a, "source")
                audioOptions = Json.string(a, "codecOptions")
                if (validate) validate()
            }
        } catch (error: JSONException) {
            throw IllegalArgumentException("Invalid saved settings: ${error.message}", error)
        }
    }
}

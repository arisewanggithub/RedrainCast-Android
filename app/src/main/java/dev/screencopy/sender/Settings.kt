package dev.screencopy.sender

import org.json.JSONObject

class Settings {
    var videoCodec = "h264"
    var videoEncoder = ""
    var orientation = "0"
    var receiverOrientation = "auto"
    var videoOptions = ""
    var videoBitrate = 8_000_000
    var maxSize = 1920
    var maxFps = 60
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

    fun validate() {
        require(videoCodec in setOf("h264", "h265", "av1"), "Unknown video codec")
        require(audioCodec in audioCodecs(), "Unknown audio codec")
        require(audioSource in setOf("output", "playback", "mic"), "Unknown audio source")
        require(orientation in setOf("0", "90", "180", "270", "@0", "@90", "@180", "@270"), "Invalid capture orientation")
        require(receiverOrientation in setOf("auto", "landscape", "portrait"), "Invalid receiver orientation")
        range(videoBitrate, 100_000, 200_000_000, "Video bitrate")
        if (audioCodec != "raw") range(audioBitrate, 16_000, 512_000, "Audio bitrate")
        else require(audioBitrate > 0, "Raw audio bitrate placeholder must remain positive; actual PCM rate is not selectable")
        range(maxSize, 0, 16384, "Maximum size"); range(maxFps, 1, 240, "Maximum FPS")
        range(displayId, 0, Int.MAX_VALUE, "Display")
        require(videoEncoder.isEmpty() || name.matches(videoEncoder), "Invalid video encoder name")
        require(audioEncoder.isEmpty() || name.matches(audioEncoder), "Invalid audio encoder name")
        require(
            cropWidth == 0 && cropHeight == 0 && cropX == 0 && cropY == 0 ||
                cropWidth in 1..16384 && cropHeight in 1..16384 && cropX >= 0 && cropY >= 0,
            "Crop must be disabled (all zero) or a positive rectangle"
        )
        validateOptions(videoOptions, true); validateOptions(audioOptions, false)
    }

    fun request(): JSONObject {
        val crop = if (cropWidth == 0) JSONObject.NULL else Wire.obj(
            "width" to cropWidth, "height" to cropHeight, "x" to cropX, "y" to cropY
        )
        return Wire.obj(
            "receiverOrientation" to receiverOrientation,
            "video" to Wire.obj(
                "codec" to videoCodec, "encoder" to videoEncoder, "bitrate" to videoBitrate,
                "maxSize" to maxSize, "maxFps" to maxFps, "displayId" to displayId,
                "orientation" to orientation, "crop" to crop, "codecOptions" to videoOptions
            ),
            "audio" to Wire.obj(
                "enabled" to audio, "codec" to audioCodec, "encoder" to audioEncoder,
                "bitrate" to audioBitrate, "source" to audioSource, "codecOptions" to audioOptions
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

        fun from(root: JSONObject, validate: Boolean = true): Settings = Settings().apply {
            require(!root.has("mediaTransportMode"), "Legacy media transport settings are not supported")
            if (root.has("receiverOrientation")) receiverOrientation = Json.string(root, "receiverOrientation")
            val v = root.getJSONObject("video")
            val a = root.getJSONObject("audio")
            videoCodec = v.getString("codec"); videoEncoder = v.getString("encoder")
            videoBitrate = v.getInt("bitrate"); maxSize = Json.integer(v, "maxSize"); maxFps = Json.integer(v, "maxFps")
            displayId = v.getInt("displayId"); orientation = v.getString("orientation"); videoOptions = v.getString("codecOptions")
            if (!v.isNull("crop")) {
                val c = v.getJSONObject("crop")
                cropWidth = c.getInt("width"); cropHeight = c.getInt("height"); cropX = c.getInt("x"); cropY = c.getInt("y")
            }
            audio = a.getBoolean("enabled"); audioCodec = a.getString("codec"); audioEncoder = a.getString("encoder")
            audioBitrate = a.getInt("bitrate"); audioSource = a.getString("source"); audioOptions = a.getString("codecOptions")
            if (validate) validate()
        }
    }
}

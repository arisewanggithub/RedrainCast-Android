package dev.screencopy.sender

import org.json.JSONArray
import java.math.BigDecimal

internal data class EncoderBitrate(val codec: String, val encoder: String, val min: Int?, val max: Int?,
                                   val unavailable: String = "") {
    init {
        require((min == null && max == null && unavailable.isNotEmpty()) ||
            (min != null && max != null && min >= 0 && max >= min)) { "Invalid encoder bitrate metadata" }
    }
    fun permits(bps: Int) = min == null || bps in min..checkNotNull(max)
}

/** Internal privileged capability report, not a change to the LAN protocol. */
internal class CodecBitrates(val encoders: List<EncoderBitrate> = emptyList()) {
    fun candidates(codec: String, encoder: String) = encoders.filter {
        it.codec == codec && (encoder.isEmpty() || it.encoder == encoder)
    }.let { if (encoder.isEmpty()) it.take(1) else it }
    fun permits(codec: String, encoder: String, bps: Int): Boolean {
        val candidates = candidates(codec, encoder)
        // Missing metadata is unproven, not a promise of hardware support.
        return candidates.isEmpty() || candidates.any { it.permits(bps) }
    }
    fun describe(codec: String, encoder: String): String {
        val candidates = candidates(codec, encoder)
        val prefix = if (encoder.isEmpty())
            "Automatic resolves to the first advertised ${if (HardwareEncoderPolicy.requiresHardware(codec)) "hardware" else "compatible"} encoder before startup. "
            else ""
        if (candidates.isEmpty()) return prefix + "Bitrate range unavailable; encoder may refuse this rate."
        return prefix + candidates.joinToString("; ") {
            "${it.encoder}: " + if (it.min == null) "range unavailable (${it.unavailable})"
            else "${it.min}..${it.max} bits/s"
        } + ". Ranges do not guarantee this complete encoding configuration."
    }
    fun report(): String = PREFIX + JSONArray().apply {
        encoders.forEach { put(Wire.obj("codec" to it.codec, "encoder" to it.encoder,
            "min" to (it.min ?: org.json.JSONObject.NULL), "max" to (it.max ?: org.json.JSONObject.NULL),
            "unavailable" to it.unavailable)) }
    }.toString()
    companion object {
        private const val PREFIX = "SCREENCOPY_BITRATE_RANGES:"
        fun fromReport(report: String): CodecBitrates {
            val lines = report.lineSequence().filter { it.startsWith(PREFIX) }.toList()
            check(lines.size <= 1) { "Duplicate privileged bitrate report" }
            if (lines.isEmpty()) return CodecBitrates()
            val array = JSONArray(lines.single().removePrefix(PREFIX))
            check(array.length() <= 256) { "Privileged bitrate report exceeds encoder limit" }
            return CodecBitrates((0 until array.length()).map {
                val value = array.getJSONObject(it)
                EncoderBitrate(value.getString("codec"), value.getString("encoder"),
                    if (value.isNull("min")) null else value.getInt("min"),
                    if (value.isNull("max")) null else value.getInt("max"), value.getString("unavailable"))
            })
        }
    }
}

internal object Bitrates {
    private val million = BigDecimal(1_000_000)
    fun videoMbps(bps: Int): String = BigDecimal(bps).divide(million).stripTrailingZeros().toPlainString()
    fun videoBps(input: String): Int {
        val text = input.trim()
        require(text.length <= 40 && text.matches(Regex("([0-9]+([.,][0-9]*)?|[.,][0-9]+)"))) {
            "Video bitrate requires decimal Mbps (for example 8 or 2.5), without grouping or exponent."
        }
        val bps = try { BigDecimal(text.replace(',', '.')).multiply(million).intValueExact() }
        catch (e: ArithmeticException) {
            throw IllegalArgumentException("Video Mbps must convert to whole bits/s without rounding or overflow.", e)
        }
        Settings.range(bps, 100_000, 200_000_000, "Video bitrate (bits/s; 0.1..200 Mbps)")
        return bps
    }
    data class AudioChoice(val bps: Int?, val label: String, val valid: Boolean) {
        override fun toString() = label
    }
    fun audioPresets(codec: String): List<Int> = when (codec) {
        "opus" -> listOf(16, 24, 32, 48, 64, 96, 128, 160, 192, 256, 320, 384, 512)
        "aac" -> listOf(64, 96, 128, 160, 192, 256, 320, 384, 512)
        "raw" -> emptyList()
        else -> throw IllegalArgumentException("Unsupported audio codec")
    }.map { it * 1000 }
    fun audioChoices(codec: String, encoder: String, existing: Int, ranges: CodecBitrates): List<AudioChoice> {
        if (codec == "raw") return listOf(AudioChoice(null, "N/A - PCM rate = sample rate x channels x bits/sample", true))
        val presets = audioPresets(codec).filter { ranges.permits(codec, encoder, it) }
        val recommended = if (codec == "aac") 128_000 else 192_000
        val choices = presets.map {
            AudioChoice(it, "${it / 1000} kbps" + if (it == recommended) " (recommended)" else "", true)
        }.toMutableList()
        if (existing !in presets) {
            val valid = existing in 16_000..512_000 && ranges.permits(codec, encoder, existing)
            val rate = BigDecimal(existing).divide(BigDecimal(1000)).stripTrailingZeros().toPlainString()
            choices.add(0, AudioChoice(existing, "$rate kbps (existing; " +
                if (valid) "not a preset)" else "invalid for selection - choose another rate)", valid))
        }
        return choices
    }
    fun validate(settings: Settings, ranges: CodecBitrates) {
        settings.validate()
        require(ranges.permits(settings.videoCodec, settings.videoEncoder, settings.videoBitrate)) {
            "Video bitrate is outside the advertised encoder range. " + ranges.describe(settings.videoCodec, settings.videoEncoder)
        }
        if (settings.audio && settings.audioCodec != "raw")
            require(ranges.permits(settings.audioCodec, settings.audioEncoder, settings.audioBitrate)) {
                "Audio bitrate is outside the advertised encoder range; choose a rate explicitly. " +
                    ranges.describe(settings.audioCodec, settings.audioEncoder)
            }
    }
}

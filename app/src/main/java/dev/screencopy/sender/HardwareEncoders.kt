package dev.screencopy.sender

import org.json.JSONArray
import java.io.IOException

internal data class EncoderMetadata(
    val name: String, val canonicalName: String, val supportedTypes: List<String>,
    val encoder: Boolean, val hardwareAccelerated: Boolean, val softwareOnly: Boolean,
    val alias: Boolean = false
)

internal object HardwareEncoderPolicy {
    val mimeTypes = linkedMapOf(
        "h264" to "video/avc", "h265" to "video/hevc", "av1" to "video/av01",
        "opus" to "audio/opus", "aac" to "audio/mp4a-latm"
    )

    fun requiresHardware(codec: String): Boolean = mimeTypes[codec]?.startsWith("video/") == true

    fun eligible(metadata: EncoderMetadata, codec: String): Boolean =
        metadata.encoder &&
            (!requiresHardware(codec) || metadata.hardwareAccelerated && !metadata.softwareOnly) &&
            mimeTypes[codec]?.let { mime -> metadata.supportedTypes.any { it.equals(mime, true) } } == true
}

internal class HardwareEncoders(private val metadata: List<EncoderMetadata>) {
    fun candidates(codec: String) = metadata.filter { HardwareEncoderPolicy.eligible(it, codec) }
    fun names(codec: String) = candidates(codec).map { it.name }.distinct()

    fun resolve(codec: String, selected: String): String {
        val candidates = candidates(codec)
        val hardware = HardwareEncoderPolicy.requiresHardware(codec)
        if (selected.isNotEmpty()) {
            if (candidates.none { it.name == selected })
                throw IOException("UNSUPPORTED_CONFIG: selected $codec encoder '$selected' is unavailable" +
                    (if (hardware) " or not hardware-accelerated. Choose a hardware encoder" else ". Choose a compatible encoder") +
                    " or automatic explicitly.")
            return selected
        }
        return candidates.distinctBy { it.canonicalName }.firstOrNull()?.name
            ?: throw IOException("UNSUPPORTED_CONFIG: No ${if (hardware) "hardware" else "compatible"} $codec encoder. Choose another codec" +
                (if (codec in setOf("opus", "aac")) " or explicitly disable audio" else "") +
                (if (hardware) "; no software/codec fallback." else "; no codec fallback."))
    }

    fun resolve(settings: Settings): Settings = Settings.from(settings.request()).apply {
        videoEncoder = resolve(videoCodec, videoEncoder)
        if (audio && audioCodec != "raw") audioEncoder = resolve(audioCodec, audioEncoder)
    }

    fun listedBy(backend: String): HardwareEncoders {
        val lines = backend.lineSequence().toList()
        return HardwareEncoders(lines.asSequence().flatMap { line ->
            HardwareEncoderPolicy.mimeTypes.keys.asSequence().flatMap { codec ->
                candidates(codec).asSequence().filter { listed(line, codec, it.name) }
            }
        }.distinctBy { it.name }.map { value ->
            value.copy(supportedTypes = value.supportedTypes.filter { type ->
                HardwareEncoderPolicy.mimeTypes.any { (codec, mime) ->
                    type.equals(mime, true) && HardwareEncoderPolicy.eligible(value, codec) &&
                        lines.any { listed(it, codec, value.name) }
                }
            })
        }.toList())
    }

    fun filterBackendReport(backend: String): String = backend.lineSequence().filter { line ->
        !line.contains("--video-encoder=") && !line.contains("--audio-encoder=") ||
            HardwareEncoderPolicy.mimeTypes.keys.any { codec -> names(codec).any { listed(line, codec, it) } }
    }.joinToString("\n")

    fun report(): String = PREFIX + JSONArray().apply {
        metadata.forEach { value ->
            if (HardwareEncoderPolicy.mimeTypes.keys.any { HardwareEncoderPolicy.eligible(value, it) })
                put(Wire.obj("name" to value.name, "canonicalName" to value.canonicalName,
                    "types" to JSONArray(value.supportedTypes), "alias" to value.alias,
                    "encoder" to value.encoder, "hardwareAccelerated" to value.hardwareAccelerated,
                    "softwareOnly" to value.softwareOnly))
        }
    }.toString()

    companion object {
        private const val PREFIX = "SCREENCOPY_HARDWARE_ENCODERS:"
        private fun listed(line: String, codec: String, name: String): Boolean {
            val track = if (HardwareEncoderPolicy.requiresHardware(codec)) "video" else "audio"
            fun option(key: String, value: String) =
                Regex("""(?:^|\s)--$track-$key=['"]?${Regex.escape(value)}['"]?(?=\s|$)""").containsMatchIn(line)
            return option("codec", codec) && option("encoder", name)
        }
        fun fromReport(report: String): HardwareEncoders {
            val lines = report.lineSequence().filter { it.startsWith(PREFIX) }.toList()
            check(lines.size == 1) { "Verified encoder inventory unavailable. Refresh capture capabilities." }
            val array = JSONArray(lines.single().removePrefix(PREFIX))
            check(array.length() <= 256) { "Encoder inventory exceeds limit" }
            return HardwareEncoders((0 until array.length()).map {
                val value = array.getJSONObject(it)
                val types = value.getJSONArray("types")
                check(types.length() <= 32) { "Encoder MIME inventory exceeds limit" }
                EncoderMetadata(value.getString("name"), value.getString("canonicalName"),
                    (0 until types.length()).map(types::getString), value.getBoolean("encoder"),
                    value.getBoolean("hardwareAccelerated"), value.getBoolean("softwareOnly"),
                    value.getBoolean("alias"))
            })
        }
    }
}

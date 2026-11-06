package org.screencopy.receiver.protocol

import org.json.JSONObject
import org.screencopy.receiver.media.AudioFormat
import java.util.Base64

/** Packet duration is codec-defined; start PTS alone cannot identify an overlapping packet. */
internal class AudioPacketTiming(
    val codec: String,
    val rate: Int = 48000,
    private val channels: Int = 2,
    private val aacFrames: Int = 1024
) {
    init { require(rate == 48000 && channels in 1..2) { "Recovery audio must be 48 kHz mono/stereo" } }
    val prerollUs: Long = when (codec) {
        "opus" -> 80_000
        "aac" -> (aacFrames * 1_000_000L + rate - 1) / rate
        else -> 0
    }

    fun endPts(pts: Long, bytes: ByteArray): Long {
        protocolRequire(pts >= 0 && bytes.isNotEmpty(), "Invalid audio packet timing")
        val frames = when (codec) {
            "raw", "pcm" -> {
                protocolRequire(bytes.size % (channels * 2) == 0, "PCM packet is not sample aligned")
                bytes.size / (channels * 2)
            }
            "aac" -> aacFrames
            "opus" -> opusFrames(bytes)
            else -> throw ProtocolException("UNSUPPORTED_CONFIG", "Unsupported recovery audio codec")
        }
        protocolRequire(frames in 1..rate * 120 / 1000, "Audio packet exceeds the 120 ms budget")
        return try { Math.addExact(pts, frames * 1_000_000L / rate) }
        catch (_: ArithmeticException) { throw ProtocolException("PROTOCOL", "Audio packet timestamp overflow") }
    }

    fun windowStart(cutoff: Long) = (cutoff - prerollUs).coerceAtLeast(0)

    private fun opusFrames(bytes: ByteArray): Int {
        val toc = bytes[0].toInt() and 255
        val config = toc ushr 3
        val samples = when {
            config >= 16 -> 120 shl (config and 3)
            config >= 12 -> 480 shl (config and 1)
            else -> intArrayOf(480, 960, 1920, 2880)[config and 3]
        }
        val count = when (toc and 3) {
            0 -> 1
            1 -> { protocolRequire((bytes.size - 1) % 2 == 0, "Malformed Opus CBR packet"); 2 }
            2 -> { protocolRequire(bytes.size >= 2, "Truncated Opus VBR packet"); 2 }
            else -> {
                protocolRequire(bytes.size >= 2, "Truncated Opus frame count")
                bytes[1].toInt() and 63
            }
        }
        protocolRequire(count in 1..48 && samples * count <= 5760, "Invalid Opus packet duration")
        return samples * count
    }

    companion object {
        fun fromConfig(json: JSONObject): AudioPacketTiming {
            val codec = json.string("codec")
            val rate = json.integer("sampleRate")
            val channels = json.integer("channels")
            val frames = if (codec == "aac")
                AacSpecificConfig.parse(Base64.getDecoder().decode(json.array("csd").getString(0))).samplesPerFrame else 1024
            return AudioPacketTiming(codec, rate, channels, frames)
        }
        fun from(format: AudioFormat) = AudioPacketTiming(format.codec, format.sampleRate, format.channels,
            if (format.codec == "aac") AacSpecificConfig.parse(format.configuration).samplesPerFrame else 1024)
    }
}

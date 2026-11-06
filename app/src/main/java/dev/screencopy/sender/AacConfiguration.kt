package dev.screencopy.sender

import java.io.IOException

/** Strict scrcpy AAC-LC access-unit contract; no ADTS, HE-AAC or implicit format substitutions. */
internal data class AacConfiguration(val sampleRate: Int, val channels: Int, val samplesPerFrame: Int) {
    companion object {
        fun parse(bytes: ByteArray): AacConfiguration {
            val bits = CodecBits(bytes)
            if (bits.read(5) != 2) throw IOException("UNSUPPORTED_CONFIG: AAC requires AAC-LC")
            val index = bits.read(4)
            val rates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
            val rate = if (index == 15) bits.read(24) else rates.getOrNull(index)
                ?: throw IOException("AUDIO_FAILED: invalid AAC sampling frequency")
            val channels = bits.read(4)
            val frames = if (bits.read(1) == 0) 1024 else 960
            if (rate <= 0 || channels !in 1..2 || bits.read(1) != 0 || bits.read(1) != 0)
                throw IOException("UNSUPPORTED_CONFIG: unsupported AAC-LC channel/core/extension configuration")
            // Remaining zero padding only: explicit extensions must not masquerade as LC.
            while (bits.remaining > 0) if (bits.read(1) != 0)
                throw IOException("UNSUPPORTED_CONFIG: AAC extensions are unsupported")
            return AacConfiguration(rate, channels, frames)
        }
        fun accessUnit(bytes: ByteArray) {
            if (bytes.isEmpty() || bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1].toInt() and 0xf6 == 0xf0)
                throw IOException("AUDIO_FAILED: AAC requires raw access units, not ADTS")
        }
    }
}

internal class CodecBits(private val bytes: ByteArray) {
    private var position = 0
    val remaining: Int get() = bytes.size * 8 - position
    fun read(count: Int): Int {
        if (count !in 0..31 || count > remaining) throw IOException("CAPTURE_FAILED: truncated codec configuration")
        var value = 0
        repeat(count) {
            value = (value shl 1) or ((bytes[position / 8].toInt() ushr (7 - position % 8)) and 1)
            position++
        }
        return value
    }
    fun skip(count: Int) { repeat(count) { read(1) } }
    fun uv(): Int {
        var zeros = 0
        while (read(1) == 0) if (++zeros > 30) throw IOException("CAPTURE_FAILED: invalid codec variable integer")
        return (1 shl zeros) - 1 + read(zeros)
    }
}

package org.screencopy.receiver.protocol

internal data class AacSpecificConfig(val sampleRate: Int, val channels: Int, val samplesPerFrame: Int) {
    companion object {
        fun parse(bytes: ByteArray): AacSpecificConfig {
            var position = 0
            fun read(count: Int): Int {
                protocolRequire(position + count <= bytes.size * 8, "Truncated AAC AudioSpecificConfig", "UNSUPPORTED_CONFIG")
                var value = 0
                repeat(count) {
                    value = (value shl 1) or ((bytes[position / 8].toInt() ushr (7 - position % 8)) and 1)
                    position++
                }
                return value
            }
            protocolRequire(read(5) == 2, "AAC-LC required", "UNSUPPORTED_CONFIG")
            val index = read(4)
            val rates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
            val rate = if (index == 15) read(24) else rates.getOrNull(index)
                ?: throw ProtocolException("UNSUPPORTED_CONFIG", "Invalid AAC sampling frequency")
            val channels = read(4)
            val samples = if (read(1) == 0) 1024 else 960
            protocolRequire(rate > 0 && channels in 1..2 && read(1) == 0 && read(1) == 0,
                "Unsupported AAC core/channel/extension configuration", "UNSUPPORTED_CONFIG")
            while (position < bytes.size * 8) protocolRequire(read(1) == 0, "AAC extensions unsupported", "UNSUPPORTED_CONFIG")
            return AacSpecificConfig(rate, channels, samples)
        }
    }
}

package dev.screencopy.sender

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

object CodecConfig {
    fun video(codec: String, config: ByteArray): JSONObject {
        if (codec == "av1") {
            val dimensions = Av1Configuration.dimensions(config)
            return Wire.obj("codec" to codec, "width" to dimensions.first, "height" to dimensions.second,
                "csd" to JSONArray().put(Base64.getEncoder().encodeToString(config)))
        }
        val nals = annexB(config)
        var dimensions: Pair<Int, Int>? = null
        val csd = JSONArray()
        var parameters = false
        for (nal in nals) {
            val type = if (codec == "h264") nal[0].toInt() and 31 else (nal[0].toInt() ushr 1) and 63
            if (codec == "h264" && type == 7 || codec == "h265" && type == 33)
                dimensions = if (codec == "h264") h264(nal) else h265(nal)
            if (codec == "h264" && type in setOf(7, 8)) {
                csd.put(Base64.getEncoder().encodeToString(byteArrayOf(0, 0, 0, 1) + nal))
                if (type == 8) parameters = true
            }
            if (codec == "h265" && type == 34) parameters = true
        }
        val (width, height) = dimensions ?: throw IOException("CAPTURE_FAILED: codec config lacks SPS")
        if (!parameters) throw IOException("CAPTURE_FAILED: codec config lacks PPS")
        if (codec == "h265") csd.put(Base64.getEncoder().encodeToString(config))
        if (width !in 1..16384 || height !in 1..16384) throw IOException("CAPTURE_FAILED: invalid coded dimensions")
        return Wire.obj("codec" to codec, "width" to width, "height" to height, "csd" to csd)
    }

    fun audio(codec: String, config: ByteArray): JSONObject {
        val csd = JSONArray()
        if (codec == "raw") return Wire.obj("codec" to "raw", "sampleRate" to 48000, "channels" to 2,
            "csd" to csd, "sampleFormat" to "s16le")
        csd.put(Base64.getEncoder().encodeToString(config))
        if (codec == "opus") {
            if (config.size != 19 || String(config, 0, 8, Charsets.US_ASCII) != "OpusHead" ||
                config[8] != 1.toByte() || config[9].toInt() !in 1..2 || config[18] != 0.toByte())
                throw IOException("AUDIO_FAILED: invalid/unsupported canonical OpusHead")
            val skip = ByteBuffer.wrap(config, 10, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 65535
            return Wire.obj("codec" to "opus", "sampleRate" to 48000, "channels" to config[9].toInt(),
                "csd" to csd, "codecDelayNs" to skip * 1_000_000_000L / 48000, "seekPrerollNs" to 80_000_000)
        }
        if (codec != "aac") throw IOException("AUDIO_FAILED: unsupported audio codec")
        val actual = AacConfiguration.parse(config)
        if (actual.sampleRate != 48000 || actual.channels != 2)
            throw IOException("AUDIO_FAILED: scrcpy AAC requires actual AAC-LC stereo 48 kHz; selected encoder/options differ")
        return Wire.obj("codec" to "aac", "sampleRate" to actual.sampleRate, "channels" to actual.channels, "csd" to csd)
    }

    internal fun annexB(bytes: ByteArray): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        var start = -1
        var index = 0
        while (index < bytes.size - 2) {
            val prefix = if (bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte()) {
                when {
                    bytes[index + 2] == 1.toByte() -> 3
                    index + 3 < bytes.size && bytes[index + 2] == 0.toByte() && bytes[index + 3] == 1.toByte() -> 4
                    else -> 0
                }
            } else 0
            if (prefix > 0) {
                if (start >= 0 && index > start) result.add(bytes.copyOfRange(start, index))
                else if (start < 0 && index != 0) throw IOException("CAPTURE_FAILED: not Annex B")
                start = index + prefix; index = start
            } else index++
        }
        if (start < 0 || start >= bytes.size) throw IOException("CAPTURE_FAILED: empty Annex B config")
        result.add(bytes.copyOfRange(start, bytes.size))
        return result
    }

    private fun h264(nal: ByteArray): Pair<Int, Int> {
        val bits = Bits(rbsp(nal), 1)
        val profile = bits.read(8)
        bits.skip(16); bits.ue()
        var chroma = 1
        var separate = 0
        if (profile in setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
            chroma = bits.ue()
            if (chroma > 3) throw IOException("Invalid SPS chroma format")
            if (chroma == 3) separate = bits.read(1)
            bits.ue(); bits.ue(); bits.skip(1)
            if (bits.read(1) != 0) {
                repeat(if (chroma == 3) 12 else 8) { index ->
                    if (bits.read(1) != 0) {
                        var last = 8
                        var next = 8
                        repeat(if (index < 6) 16 else 64) {
                            if (next != 0) next = (last + bits.se() + 256) % 256
                            last = if (next == 0) last else next
                        }
                    }
                }
            }
        }
        bits.ue()
        when (bits.ue()) {
            0 -> bits.ue()
            1 -> {
                bits.skip(1); bits.se(); bits.se()
                val count = bits.ue()
                if (count > 256) throw IOException("Invalid SPS POC")
                repeat(count) { bits.se() }
            }
            2 -> Unit
            else -> throw IOException("Invalid SPS POC type")
        }
        bits.ue(); bits.skip(1)
        val width = (bits.ue() + 1) * 16
        val mapHeight = bits.ue() + 1
        val frame = bits.read(1)
        if (frame == 0) bits.skip(1)
        bits.skip(1)
        var left = 0; var right = 0; var top = 0; var bottom = 0
        if (bits.read(1) != 0) { left = bits.ue(); right = bits.ue(); top = bits.ue(); bottom = bits.ue() }
        val array = if (separate == 1) 0 else chroma
        val unitX = if (array in 1..2) 2 else 1
        val unitY = (if (array == 1) 2 else 1) * (2 - frame)
        return width - (left + right) * unitX to (2 - frame) * mapHeight * 16 - (top + bottom) * unitY
    }

    private fun h265(nal: ByteArray): Pair<Int, Int> {
        val bits = Bits(rbsp(nal), 2)
        bits.skip(4)
        val sub = bits.read(3)
        bits.skip(1); bits.skip(96)
        val profile = BooleanArray(8)
        val level = BooleanArray(8)
        repeat(sub) { profile[it] = bits.read(1) != 0; level[it] = bits.read(1) != 0 }
        if (sub > 0) bits.skip((8 - sub) * 2)
        repeat(sub) { if (profile[it]) bits.skip(88); if (level[it]) bits.skip(8) }
        bits.ue()
        var chroma = bits.ue()
        if (chroma > 3) throw IOException("Invalid HEVC chroma format")
        if (chroma == 3 && bits.read(1) != 0) chroma = 0
        var width = bits.ue()
        var height = bits.ue()
        if (bits.read(1) != 0) {
            val x = if (chroma in 1..2) 2 else 1
            val y = if (chroma == 1) 2 else 1
            val left = bits.ue(); val right = bits.ue(); val top = bits.ue(); val bottom = bits.ue()
            width -= (left + right) * x; height -= (top + bottom) * y
        }
        return width to height
    }
    internal fun rbsp(nal: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(nal.size)
        var zeros = 0
        for (value in nal) {
            if (zeros >= 2 && value == 3.toByte()) { zeros = 0; continue }
            out.write(value.toInt()); zeros = if (value == 0.toByte()) zeros + 1 else 0
        }
        return out.toByteArray()
    }
    private class Bits(private val data: ByteArray, skipBytes: Int) {
        private var offset = skipBytes * 8
        fun read(count: Int): Int {
            if (count !in 0..31 || offset + count > data.size * 8) throw IOException("Truncated codec config")
            var value = 0
            repeat(count) { value = (value shl 1) or ((data[offset / 8].toInt() ushr (7 - offset % 8)) and 1); offset++ }
            return value
        }
        fun skip(count: Int) {
            var remaining = count
            while (remaining > 0) { val part = minOf(31, remaining); read(part); remaining -= part }
        }
        fun ue(): Int {
            var zeros = 0
            while (read(1) == 0) if (++zeros > 20) throw IOException("Codec Exp-Golomb exceeds dimension budget")
            return (1 shl zeros) - 1 + read(zeros)
        }
        fun se(): Int { val value = ue(); return if (value and 1 == 0) -value / 2 else (value + 1) / 2 }
    }
}

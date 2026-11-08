package org.screencopy.receiver.media

import org.screencopy.receiver.protocol.HdrStaticMetadata
import org.screencopy.receiver.protocol.ProtocolException
import org.screencopy.receiver.protocol.protocolRequire
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object HdrBitstream {
    fun validateConfiguration(format: VideoFormat) {
        val units = format.configuration.flatMap(::metadataUnits)
        protocolRequire(units.any { type(it) == 32 } && units.any { type(it) == 34 },
            "HDR HEVC requires VPS and PPS", "UNSUPPORTED_CONFIG")
        val sequence = units.filter { type(it) == 33 }
        protocolRequire(sequence.size == 1, "HDR HEVC requires one SPS", "UNSUPPORTED_CONFIG")
        try { validateSps(sequence.single(), format) }
        catch (error: java.io.IOException) { throw ProtocolException("PROTOCOL", "Malformed HDR HEVC SPS: ${error.message}") }
        units.filter { type(it) in 39..40 }.forEach { validateSei(it, format.videoColor?.staticMetadata) }
    }

    // Walk only the SPS syntax needed for HDR10. Bound data-derived loops before allocating or iterating.
    private fun validateSps(nal: ByteArray, format: VideoFormat) {
        val raw = dev.screencopy.sender.CodecConfig.rbsp(nal)
        val bits = dev.screencopy.sender.CodecBits(raw.copyOfRange(2, raw.size))
        fun flag() = bits.read(1) != 0
        fun ue(maximum: Int): Int = bits.uv().also {
            protocolRequire(it in 0..maximum, "HDR HEVC SPS value exceeds bound")
        }
        bits.skip(4)
        val layers = bits.read(3)
        protocolRequire(layers <= 6, "Invalid HDR HEVC sub-layer count")
        bits.skip(1)
        val profileSpace = bits.read(2)
        bits.skip(1)
        protocolRequire(profileSpace == 0 && bits.read(5) == 2, "HDR HEVC requires Main10 profile", "UNSUPPORTED_CONFIG")
        bits.skip(88)
        val subProfile = BooleanArray(layers)
        val subLevel = BooleanArray(layers)
        repeat(layers) { subProfile[it] = flag(); subLevel[it] = flag() }
        if (layers > 0) bits.skip((8 - layers) * 2)
        repeat(layers) { if (subProfile[it]) bits.skip(88); if (subLevel[it]) bits.skip(8) }
        ue(15)
        protocolRequire(ue(3) == 1, "HDR HEVC requires 4:2:0", "UNSUPPORTED_CONFIG")
        var width = ue(16384)
        var height = ue(16384)
        if (flag()) {
            val left = ue(16384); val right = ue(16384); val top = ue(16384); val bottom = ue(16384)
            width -= (left + right) * 2
            height -= (top + bottom) * 2
        }
        protocolRequire(width == format.width && height == format.height,
            "HDR HEVC SPS dimensions differ from configuration", "UNSUPPORTED_CONFIG")
        protocolRequire(ue(8) == 2 && ue(8) == 2, "HDR HEVC requires 10-bit samples", "UNSUPPORTED_CONFIG")
        val pocBits = ue(12) + 4
        val orderingAllLayers = flag()
        for (layer in (if (orderingAllLayers) 0 else layers)..layers) {
            ue(16); ue(16); ue(Int.MAX_VALUE)
        }
        repeat(4) { ue(3) }
        repeat(2) { ue(4) }
        if (flag() && flag()) {
            for (size in 0..3) {
                for (matrix in 0 until 6 step (if (size == 3) 3 else 1)) {
                    if (!flag()) ue(matrix)
                    else {
                        if (size > 1) ue(512)
                        repeat(minOf(64, 1 shl (4 + size * 2))) { ue(512) }
                    }
                }
            }
        }
        bits.skip(2)
        if (flag()) { bits.skip(8); ue(3); ue(3); bits.skip(1) }
        var previousDeltaPictures = 0
        repeat(ue(64)) { index ->
            if (index > 0 && flag()) {
                bits.skip(1); ue(Int.MAX_VALUE)
                var deltaPictures = 0
                repeat(previousDeltaPictures + 1) {
                    val used = flag()
                    if (used || flag()) deltaPictures++
                }
                previousDeltaPictures = deltaPictures
            } else {
                val negative = ue(32)
                val positive = ue(32)
                protocolRequire(negative + positive <= 32, "Too many HDR HEVC reference pictures")
                repeat(negative + positive) { ue(Int.MAX_VALUE); bits.skip(1) }
                previousDeltaPictures = negative + positive
            }
            protocolRequire(previousDeltaPictures <= 32, "Too many HDR HEVC predicted reference pictures")
        }
        if (flag()) repeat(ue(32)) { bits.skip(pocBits + 1) }
        bits.skip(2)
        protocolRequire(flag(), "HDR HEVC requires VUI color information", "UNSUPPORTED_CONFIG")
        if (flag() && bits.read(8) == 255) bits.skip(32)
        if (flag()) bits.skip(1)
        protocolRequire(flag(), "HDR HEVC requires video signal information", "UNSUPPORTED_CONFIG")
        bits.skip(3)
        protocolRequire(!flag() && flag(), "HDR HEVC requires limited-range color description", "UNSUPPORTED_CONFIG")
        protocolRequire(bits.read(8) == 9 && bits.read(8) == 16 && bits.read(8) == 9,
            "HDR HEVC SPS must signal BT2020/PQ/BT2020nc", "UNSUPPORTED_CONFIG")
    }

    class Admission(format: VideoFormat) {
        private val configuration = format.configuration.flatMap(::metadataUnits).filter { type(it) in 32..34 }
        private val metadata = format.videoColor?.staticMetadata
        fun validate(bytes: ByteArray) {
            metadataUnits(bytes).forEach { unit ->
                if (type(unit) in 32..34)
                    protocolRequire(configuration.any { it.contentEquals(unit) }, "Unannounced HDR HEVC CSD change", "UNSUPPORTED_CONFIG")
                else validateSei(unit, metadata)
            }
        }
    }

    // Scan encoded slices without copying them; only small configuration/SEI units are materialized.
    private fun metadataUnits(bytes: ByteArray): List<ByteArray> {
        fun prefix(index: Int): Int = when {
            index + 3 <= bytes.size && bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte() &&
                bytes[index + 2] == 1.toByte() -> 3
            index + 4 <= bytes.size && bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte() &&
                bytes[index + 2] == 0.toByte() && bytes[index + 3] == 1.toByte() -> 4
            else -> 0
        }
        protocolRequire(prefix(0) > 0, "HDR HEVC requires Annex B")
        val result = mutableListOf<ByteArray>()
        var count = 0
        fun add(start: Int, end: Int) {
            protocolRequire(++count <= 4096 && end - start >= 2, "Invalid HDR HEVC NAL units")
            protocolRequire(bytes[start].toInt() and 0x80 == 0 && bytes[start + 1].toInt() and 7 != 0, "Invalid HDR HEVC NAL header")
            val kind = (bytes[start].toInt() ushr 1) and 63
            if (kind in 32..34 || kind in 39..40) {
                protocolRequire(end - start <= 65536, "HDR HEVC metadata NAL exceeds bound")
                var last = end
                while (last > start + 2 && bytes[last - 1] == 0.toByte()) last--
                result.add(bytes.copyOfRange(start, last))
            }
        }
        var start = prefix(0)
        var index = start
        while (index < bytes.size) {
            val length = prefix(index)
            if (length == 0) { index++; continue }
            add(start, index)
            start = index + length
            index = start
        }
        add(start, bytes.size)
        return result
    }

    private fun type(unit: ByteArray) = (unit[0].toInt() ushr 1) and 63

    private fun masteringPrimariesMatch(actual: List<Int>, expected: List<Int>): Boolean {
        fun normalized(values: List<Int>) = values.size == 8 && values.all { it in 0..50000 } &&
            values.chunked(2).all { it[0] + it[1] <= 50000 }
        // MF float primaries can lose one fixed 1/50000 unit when hardware writes the SEI.
        return normalized(actual) && normalized(expected) &&
            actual.indices.all { kotlin.math.abs(actual[it].toLong() - expected[it].toLong()) <= 1L }
    }

    private fun validateSei(unit: ByteArray, expected: HdrStaticMetadata?) {
        val unescaped = dev.screencopy.sender.CodecConfig.rbsp(unit)
        val rbsp = unescaped.copyOfRange(2, unescaped.size)
        var offset = 0
        fun value(): Int {
            var result = 0
            while (true) {
                protocolRequire(offset < rbsp.size, "Truncated HDR SEI header")
                val part = rbsp[offset++].toInt() and 255
                result += part
                protocolRequire(result <= 65536, "HDR SEI field exceeds bound")
                if (part != 255) return result
            }
        }
        while (offset < rbsp.size && !(rbsp[offset] == 0x80.toByte() && offset == rbsp.lastIndex)) {
            val kind = value()
            val size = value()
            protocolRequire(size <= rbsp.size - offset, "Truncated HDR SEI payload")
            if (kind == 137 || kind == 144) {
                val required = if (kind == 137) 24 else 4
                // Some hardware emits an additional payload trailing-bits byte. Validate the defined fields.
                protocolRequire(size == required || size == required + 1 && rbsp[offset + required] == 0x80.toByte(),
                    "Invalid HDR static SEI size")
                if (expected != null) {
                    val data = ByteBuffer.wrap(rbsp, offset, required).order(ByteOrder.BIG_ENDIAN)
                    if (kind == 137) {
                        val gbRed = List(6) { data.short.toInt() and 65535 }
                        val primaries = gbRed.drop(4) + gbRed.take(4) + List(2) { data.short.toInt() and 65535 }
                        val maximum = data.int.toLong() and 0xffffffffL
                        val minimum = data.int.toLong() and 0xffffffffL
                        protocolRequire(masteringPrimariesMatch(primaries, expected.masteringPrimaries) &&
                            maximum == expected.maxMasteringNits * 10000L &&
                            minimum == expected.minMasteringTenThousandths.toLong(),
                            "HDR mastering SEI differs from negotiated metadata: " +
                                "expected_primaries=${expected.masteringPrimaries} actual_primaries=$primaries " +
                                "chromaticity_denominator=50000 " +
                                "expected_max_luminance=${expected.maxMasteringNits * 10000L} actual_max_luminance=$maximum " +
                                "expected_min_luminance=${expected.minMasteringTenThousandths} actual_min_luminance=$minimum " +
                                "luminance_denominator=10000", "UNSUPPORTED_CONFIG")
                    } else {
                        protocolRequire((data.short.toInt() and 65535) == expected.maxContentLightNits &&
                            (data.short.toInt() and 65535) == expected.maxFrameAverageLightNits,
                            "HDR light-level SEI differs from negotiated metadata", "UNSUPPORTED_CONFIG")
                    }
                }
            }
            offset += size
        }
    }
}

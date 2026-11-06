package org.screencopy.receiver.protocol

import androidx.media3.container.ObuParser
import dev.screencopy.sender.Av1Configuration

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object RandomAccessPoint {
    fun verify(codec: String, bytes: ByteArray, csd: List<ByteArray>) {
        val randomAccess = when (codec) {
            "h264", "h265" -> {
                val units = nals(bytes)
                val configuration = csd.flatMap(::nals)
                val configTypes = if (codec == "h264") setOf(7, 8) else setOf(32, 33, 34)
                fun type(unit: ByteArray): Int = if (codec == "h264") unit[0].toInt() and 31
                    else (unit[0].toInt() ushr 1) and 63
                protocolRequire(units.all { it[0].toInt() and 0x80 == 0 &&
                    (codec == "h264" || it.size >= 2 && it[1].toInt() and 7 != 0) }, "Invalid recovery NAL header")
                for (unit in units.filter { type(it) in configTypes })
                    protocolRequire(configuration.any { it.contentEquals(unit) }, "Unannounced keyframe CSD change")
                units.any { if (codec == "h264") type(it) == 5 else type(it) in 16..21 }
            }
            "av1" -> {
                val configured = csd.flatMap(Av1Configuration::obus).filter { it.type == ObuParser.OBU_SEQUENCE_HEADER }
                protocolRequire(configured.size == 1, "Recovery requires one AV1 sequence header")
                val sample = Av1Configuration.obus(bytes)
                for (sequence in sample.filter { it.type == ObuParser.OBU_SEQUENCE_HEADER }) {
                    protocolRequire(sequence.payload.duplicate() == configured.single().payload.duplicate(),
                        "Unannounced AV1 sequence-header change")
                }
                val sequence = ObuParser.SequenceHeader.parse(configured.single())
                    ?: throw ProtocolException("UNSUPPORTED_CONFIG", "Unsupported AV1 recovery sequence")
                val frames = sample.filter { it.type == ObuParser.OBU_FRAME || it.type == ObuParser.OBU_FRAME_HEADER }
                protocolRequire(frames.size == 1 && frames[0].payload.hasRemaining(), "Invalid AV1 recovery frame")
                sequence.reducedStillPictureHeader || frames[0].payload.get(frames[0].payload.position()).toInt() and 0xf0 == 0x10
            }
            else -> throw ProtocolException("UNSUPPORTED_CONFIG", "Unsupported recovery video codec")
        }
        protocolRequire(randomAccess, "Keyframe flag does not identify an independently decodable picture")
    }

    private fun nals(bytes: ByteArray): List<ByteArray> {
        fun prefix(index: Int): Int = when {
            index + 3 <= bytes.size && bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte() && bytes[index + 2] == 1.toByte() -> 3
            index + 4 <= bytes.size && bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte() &&
                bytes[index + 2] == 0.toByte() && bytes[index + 3] == 1.toByte() -> 4
            else -> 0
        }
        protocolRequire(prefix(0) > 0, "Expected Annex B recovery picture")
        val units = mutableListOf<ByteArray>()
        fun addUnit(start: Int, end: Int) {
            var last = end
            while (last > start && bytes[last - 1] == 0.toByte()) last--
            protocolRequire(last > start, "Empty recovery NAL unit")
            units.add(bytes.copyOfRange(start, last))
        }
        var start = prefix(0)
        var index = start
        while (index < bytes.size) {
            val size = prefix(index)
            if (size == 0) { index++; continue }
            protocolRequire(index > start, "Empty recovery NAL unit")
            addUnit(start, index)
            start = index + size
            index = start
        }
        protocolRequire(start < bytes.size, "Empty recovery NAL unit")
        addUnit(start, bytes.size)
        return units
    }
}

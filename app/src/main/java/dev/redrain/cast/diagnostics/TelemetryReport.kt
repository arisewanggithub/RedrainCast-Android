package dev.redrain.cast.diagnostics

import org.json.JSONObject
import org.json.JSONArray
import org.screencopy.receiver.protocol.protocolRequire
import java.io.ByteArrayOutputStream

/** Serializes retained blocks directly; no expanded JSON trees are retained between revisions. */
internal object TelemetryReport {
    const val MAX_BYTES = 2 * 1024 * 1024
    private const val METADATA_RESERVE = 16 * 1024

    fun encode(snapshot: TelemetryHistory.Snapshot): ByteArray {
        val selected = arrayOf(mutableListOf<TelemetryHistory.Block>(), mutableListOf())
        val sources = arrayOf(snapshot.sender, snapshot.receiver)
        val positions = intArrayOf(sources[0].blocks.lastIndex, sources[1].blocks.lastIndex)
        var available = MAX_BYTES - METADATA_RESERVE
        fun encodedSize(block: TelemetryHistory.Block) =
            block.size + block.arrivalNs.toString().length + 6
        // Reserve final payloads before selecting the newest remaining blocks.
        if (snapshot.final) {
            for (i in 0..1) {
                val finalBlock = sources[i].blocks.find { it.sequence == sources[i].finalSequence }
                if (finalBlock != null) {
                    selected[i].add(finalBlock)
                    available -= encodedSize(finalBlock)
                }
            }
        }
        while (positions.any { it >= 0 }) {
            for (i in 0..1) {
                if (positions[i] < 0) continue
                val block = sources[i].blocks[positions[i]--]
                if (selected[i].contains(block)) continue
                val size = encodedSize(block)
                if (size <= available) {
                    selected[i].add(block)
                    available -= size
                }
            }
        }
        val output = ByteArrayOutputStream()
        fun text(value: String) = output.write(value.toByteArray(Charsets.UTF_8))
        fun source(index: Int) {
            val source = sources[index]
            val blocks = selected[index].sortedBy { it.sequence }
            val metadata = JSONObject()
                .put("missingPublications", "${source.missingPublications}")
                .put("truncated", "${source.truncated}")
                .put("reportOmitted", "${source.blocks.size - blocks.size}")
                .put("firstSequence", blocks.firstOrNull()?.sequence?.toString() ?: JSONObject.NULL)
                .put("lastSequence", blocks.lastOrNull()?.sequence?.toString() ?: JSONObject.NULL)
                .put("finalSequence", source.finalSequence?.toString() ?: JSONObject.NULL)
                .put("terminalSequence", source.terminalSequence?.toString() ?: JSONObject.NULL)
                .put("cleanupSucceeded", source.cleanupSucceeded ?: JSONObject.NULL)
                .put("finalSkipReason", source.finalSkipReason?.name ?: JSONObject.NULL)
                .put("gaps", JSONArray().apply {
                    source.gaps.forEach { put(JSONArray().put("${it.first}").put("${it.last}")) }
                })
                .put("omittedGapRanges", "${source.omittedGapRanges}")
            val prefix = metadata.toString()
            text(prefix.substring(0, prefix.length - 1))
            text(",\"blocks\":[")
            blocks.forEachIndexed { at, block ->
                if (at > 0) text(",")
                text("[\"${block.arrivalNs}\",")
                block.writeTo(output)
                text("]")
            }
            text("]}")
        }
        text("{\"version\":2,\"receiverSupported\":${snapshot.receiverSupported}," +
            "\"receiverComplete\":${snapshot.receiverComplete},\"windowClosed\":${snapshot.windowClosed}," +
            "\"final\":${snapshot.final},\"sender\":")
        source(0)
        text(",\"receiver\":")
        source(1)
        text("}")
        return output.toByteArray().also { check(it.size <= MAX_BYTES) }
    }

    fun validate(root: JSONObject): Boolean {
        protocolRequire(root.keys().asSequence().toSet() == setOf("version", "receiverSupported",
            "receiverComplete", "windowClosed", "final", "sender", "receiver") &&
            root.opt("version") == 2, "Invalid telemetry report")
        for (key in listOf("receiverSupported", "receiverComplete", "windowClosed", "final"))
            protocolRequire(root.opt(key) is Boolean, "Invalid telemetry report $key")
        data class Terminal(val final: Long?, val terminal: Long?, val clean: Boolean?)
        fun source(key: String): Terminal {
            val source = root.opt(key)
            protocolRequire(source is JSONObject, "Invalid telemetry report source")
            source as JSONObject
            val fields = source.keys().asSequence().toSet()
            val legacyFields = setOf("missingPublications", "truncated",
                "reportOmitted", "firstSequence", "lastSequence", "finalSequence", "terminalSequence",
                "cleanupSucceeded", "gaps", "omittedGapRanges", "blocks")
            protocolRequire(fields == legacyFields || fields == legacyFields + "finalSkipReason",
                "Invalid telemetry report source fields")
            fun number(name: String): Long = SessionTelemetryProtocol.number(source.opt(name)).also {
                protocolRequire(it >= 0, "Invalid telemetry report $name")
            }
            fun optional(name: String): Long? =
                if (source.opt(name) === JSONObject.NULL) null else number(name)
            number("missingPublications"); number("truncated"); number("reportOmitted")
            number("omittedGapRanges")
            val gaps = source.opt("gaps")
            protocolRequire(gaps is JSONArray && gaps.length() <= 64, "Invalid telemetry gaps")
            var lastGap = 0L
            for (i in 0 until (gaps as JSONArray).length()) {
                val gap = gaps.opt(i)
                protocolRequire(gap is JSONArray && gap.length() == 2, "Invalid telemetry gap")
                gap as JSONArray
                val start = SessionTelemetryProtocol.number(gap.opt(0))
                val end = SessionTelemetryProtocol.number(gap.opt(1))
                protocolRequire(start > lastGap && end >= start, "Invalid telemetry gap range")
                lastGap = end
            }
            val first = optional("firstSequence")
            val last = optional("lastSequence")
            val final = optional("finalSequence")
            val terminal = optional("terminalSequence")
            protocolRequire(listOfNotNull(first, last, final, terminal).all { it > 0 },
                "Invalid telemetry report sequence")
            val cleanValue = source.opt("cleanupSucceeded")
            protocolRequire(cleanValue === JSONObject.NULL || cleanValue is Boolean, "Invalid cleanup outcome")
            val skip = source.opt("finalSkipReason")
            protocolRequire(skip == null || skip === JSONObject.NULL ||
                skip is String && ReceiverTelemetrySession.FinalSkipReason.entries.any { it.name == skip } &&
                terminal == null, "Invalid telemetry final skip reason")
            val blocks = source.opt("blocks")
            protocolRequire(blocks is JSONArray && blocks.length() <= 16_384, "Invalid telemetry blocks")
            var previous = 0L
            var actualFirst: Long? = null
            for (i in 0 until (blocks as JSONArray).length()) {
                val record = blocks.opt(i)
                protocolRequire(record is JSONArray && record.length() == 2, "Invalid telemetry block")
                record as JSONArray
                protocolRequire(SessionTelemetryProtocol.number(record.opt(0)) >= 0, "Invalid arrival time")
                val batch = record.opt(1)
                protocolRequire(batch is JSONObject, "Invalid telemetry batch")
                val parsed = SessionTelemetryProtocol.parse(batch.toString().toByteArray(Charsets.UTF_8), key)
                protocolRequire(parsed.sequence > previous, "Telemetry blocks out of order")
                if (actualFirst == null) actualFirst = parsed.sequence
                previous = parsed.sequence
            }
            protocolRequire(first == actualFirst && last == if (actualFirst == null) null else previous,
                "Invalid telemetry report sequence range")
            return Terminal(final, terminal, cleanValue as? Boolean)
        }
        source("sender")
        val receiver = source("receiver")
        val complete = root.getBoolean("receiverSupported") && receiver.final != null &&
            receiver.final == receiver.terminal && receiver.clean == true
        protocolRequire(root.getBoolean("receiverComplete") == complete, "Invalid receiver completeness")
        return true
    }
}

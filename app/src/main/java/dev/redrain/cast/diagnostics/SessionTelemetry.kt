package dev.redrain.cast.diagnostics

import org.json.JSONArray
import org.json.JSONObject
import org.screencopy.receiver.protocol.StrictJson
import org.screencopy.receiver.protocol.protocolRequire
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicReferenceArray

/** Numeric observations only; decimal strings preserve signed 64-bit values through StrictJson. */
internal data class TelemetryRow(
    val kind: Int, val stage: Int, val track: Int, val generation: Int,
    val atNs: Long, val value: Long, val detail: List<Long>
) {
    companion object {
        val EVIDENCE_KIND = DiagnosticKind.entries.size + 2
    }

    init {
        val stageCount = when (kind) {
            0 -> DiagnosticVideoStage.entries.size
            EVIDENCE_KIND -> IncidentEvidence.Stage.entries.size
            EVIDENCE_KIND + 1 -> CausalEvidence.Stage.entries.size
            in 1 until EVIDENCE_KIND -> DiagnosticStage.entries.size
            else -> 0
        }
        require(stage in 0 until stageCount && track in 0..2 && generation >= 0 && atNs >= 0)
        require(detail.size <= 20)
        require(encodedBytes() <= 512)
    }

    private fun encodedBytes(): Int =
        16 + kind.toString().length + stage.toString().length + track.toString().length +
            generation.toString().length + atNs.toString().length + value.toString().length +
            detail.sumOf { it.toString().length + 3 }

    internal fun json(): JSONArray = JSONArray().put(kind).put(stage).put(track).put(generation)
        .put("$atNs").put("$value").put(JSONArray().apply { detail.forEach { put("$it") } })
}

internal object SessionTelemetryProtocol {
    const val VERSION = 2
    const val INTERVAL_MS = 1000
    const val MAX_BATCH_BYTES = 8192
    private val decimal = Regex("-?(0|[1-9][0-9]{0,18})")

    fun capability() = JSONObject().put("version", VERSION).put("intervalMs", INTERVAL_MS)
        .put("maxBatchBytes", MAX_BATCH_BYTES)

    fun supported(root: JSONObject): Boolean {
        if (!root.has("sessionTelemetry")) return false
        val raw = root.opt("sessionTelemetry")
        protocolRequire(raw is JSONObject, "Invalid telemetry capability")
        val obj = raw as JSONObject
        val version = obj.opt("version")
        protocolRequire(version is Int && version > 0, "Invalid telemetry capability values")
        if (version != VERSION) return false
        protocolRequire(obj.keys().asSequence().toSet() == setOf("version", "intervalMs", "maxBatchBytes"),
            "Invalid telemetry capability fields")
        val interval = obj.opt("intervalMs")
        val bytes = obj.opt("maxBatchBytes")
        protocolRequire(interval is Int && interval > 0 && bytes is Int && bytes > 0,
            "Invalid telemetry capability values")
        return interval == INTERVAL_MS && bytes == MAX_BATCH_BYTES
    }

    internal fun number(value: Any?): Long {
        protocolRequire(value is String && decimal.matches(value) && value != "-0",
            "Invalid telemetry integer")
        return (value as String).toLongOrNull()
            ?: throw org.screencopy.receiver.protocol.ProtocolException("PROTOCOL", "Telemetry integer out of range")
    }

    private fun row(value: Any?): TelemetryRow {
        protocolRequire(value is JSONArray && value.length() == 7, "Invalid telemetry row")
        val array = value as JSONArray
        protocolRequire(StrictJson.encodedSize(array) <= 512, "Telemetry row exceeds limit")
        fun int(index: Int): Int {
            val v = array.opt(index)
            protocolRequire(v is Int, "Invalid telemetry row integer")
            return v as Int
        }
        val detail = array.opt(6)
        protocolRequire(detail is JSONArray && detail.length() <= 20, "Invalid telemetry details")
        val values = (0 until (detail as JSONArray).length()).map { number(detail.opt(it)) }
        try {
            return TelemetryRow(int(0), int(1), int(2), int(3),
                number(array.opt(4)), number(array.opt(5)), values)
        } catch (_: IllegalArgumentException) {
            throw org.screencopy.receiver.protocol.ProtocolException("PROTOCOL", "Invalid telemetry row values")
        }
    }

    data class Batch(
        val sequence: Long, val atNs: Long, val final: Boolean,
        val state: List<TelemetryRow>, val events: List<TelemetryRow>,
        val firstPressure: TelemetryRow?, val firstFailure: TelemetryRow?,
        val dropped: Long, val coalesced: Long, val omitted: Long, val unsent: Long
    )

    fun parse(bytes: ByteArray, role: String? = null): Batch {
        protocolRequire(bytes.size <= MAX_BATCH_BYTES, "Telemetry batch exceeds limit")
        val root = StrictJson.decode(bytes, trackSizes = false, trackArraySizes = true)
        protocolRequire(root.keys().asSequence().toSet() == setOf("state", "message", "version", "seq", "atNs", "final", "current",
            "events", "firstPressure", "firstFailure", "dropped", "coalesced", "omitted", "unsent"),
            "Invalid telemetry batch fields")
        protocolRequire(root.opt("state") == "session-telemetry" && root.opt("message") == "metadata" &&
            root.opt("version") is Int && root.opt("version") in listOf(1, VERSION) && root.opt("final") is Boolean,
            "Invalid telemetry batch version or final")
        fun nonnegative(key: String): Long = number(root.opt(key)).also {
            protocolRequire(it >= 0, "Invalid telemetry $key")
        }
        fun rows(key: String, max: Int): List<TelemetryRow> {
            val value = root.opt(key)
            protocolRequire(value is JSONArray && value.length() <= max, "Invalid telemetry $key")
            return (0 until (value as JSONArray).length()).map {
                row(value.opt(it)).also { row ->
                    if (row.kind == CausalEvidence.KIND) {
                        protocolRequire(root.opt("version") == VERSION && key == "events" &&
                            role != "sender" && row.track == 2 && row.value in 0..4 &&
                            (row.value != 4L || row.stage == CausalEvidence.Stage.START.ordinal) &&
                            (row.value != 2L || row.stage in listOf(CausalEvidence.Stage.MAPPING.ordinal,
                                CausalEvidence.Stage.START.ordinal)) &&
                            (row.value == 3L) == (row.stage == CausalEvidence.Stage.CAPTURE.ordinal),
                            "Invalid causal telemetry provenance")
                        protocolRequire(row.detail.size == CausalEvidence.fields(row.stage).size,
                            "Invalid causal telemetry layout")
                        if (row.stage == CausalEvidence.Stage.BUILD.ordinal)
                            protocolRequire(row.detail[2] == CausalEvidence.LAYOUT, "Unsupported causal layout")
                        if (row.stage == CausalEvidence.Stage.CAPTURE.ordinal)
                            protocolRequire(row.detail[0] == CausalEvidence.LAYOUT, "Unsupported causal layout")
                    }
                }
            }
        }
        fun anchor(key: String): TelemetryRow? {
            if (root.opt(key) === JSONObject.NULL) return null
            val result = row(root.opt(key))
            val pressure = key == "firstPressure"
            val session = DiagnosticKind.SESSION.ordinal + 1
            val recovery = DiagnosticKind.RECOVERY.ordinal + 1
            val genericKind = DiagnosticKind.entries.getOrNull(result.kind - 1)
            val allowedRole = role == null || genericKind != null &&
                genericKind.group in DiagnosticRecorder.capabilities[role].orEmpty()
            val allowedTrack = when (genericKind) {
                DiagnosticKind.AUDIO_CAPTURE, DiagnosticKind.AUDIO_TRANSPORT, DiagnosticKind.AUDIO_QUEUE,
                DiagnosticKind.AUDIO_OUTPUT -> result.track == 2
                DiagnosticKind.VIDEO_PROGRESS -> result.track == 1
                else -> true
            }
            val generic = genericKind != null && allowedRole && allowedTrack && result.detail.size == 5 &&
                result.detail[2] in 0 until DiagnosticReason.entries.size.toLong() &&
                result.detail[3] in 0 until DiagnosticPoint.entries.size.toLong()
            val structured = if (pressure) result.kind == recovery && result.track in 1..2 &&
                ((role != "receiver" && result.detail.size == 19 &&
                    result.detail[0] in 0 until dev.screencopy.sender.SenderLimit.entries.size.toLong()) ||
                    (role != "sender" && result.detail.size == 9 &&
                        result.detail[0] in 0 until org.screencopy.receiver.media.QueueLimit.entries.size.toLong()))
            else when (result.kind) {
                session -> result.value in 0 until IncidentEvidence.Cause.entries.size.toLong() &&
                    ((role != "receiver" && result.detail.size == 2 &&
                    result.detail[0] in 0 until dev.screencopy.sender.SessionStopTrace.Phase.entries.size.toLong() &&
                    result.detail[1] in 0 until dev.screencopy.sender.SessionOutcome.Origin.entries.size.toLong()) ||
                    (role != "sender" && result.detail.size == 1 &&
                        result.detail[0] in 0 until org.screencopy.receiver.ui.FailureOrigin.entries.size.toLong()))
                recovery -> result.track in 1..2 &&
                    ((role != "receiver" && result.detail.size == 9) ||
                        (role != "sender" && result.detail.size == 12))
                else -> false
            }
            protocolRequire(result.stage == if (pressure) DiagnosticStage.PRESSURE.ordinal else DiagnosticStage.FAILED.ordinal,
                "Invalid telemetry anchor stage")
            protocolRequire(structured || generic && (pressure || result.kind == session || result.kind == recovery),
                "Invalid telemetry anchor shape")
            return result
        }
        val sequence = nonnegative("seq")
        protocolRequire(sequence > 0, "Invalid telemetry sequence")
        return Batch(sequence, nonnegative("atNs"), root.getBoolean("final"), rows("current", 64),
            rows("events", 64), anchor("firstPressure"), anchor("firstFailure"),
            nonnegative("dropped"), nonnegative("coalesced"), nonnegative("omitted"), nonnegative("unsent"))
    }
}

/**
 * Producers use bounded atomic publication; only fault capture and anchor selection share a lock.
 * The single consumer owns pending rows and encoding.
 * The caller schedules publication without catch-up and accounts rejected/replaced publications.
 */
internal class SessionTelemetryCollector(private val clock: () -> Long = System::nanoTime) {
    val causal = CausalEvidence.Cache()
    private data class Operation(val row: TelemetryRow, val unknownGeneration: Boolean)
    private val states = AtomicReferenceArray<TelemetryRow?>(66)
    private val operations = AtomicReferenceArray<Operation?>(16)
    private val ingress = AtomicReferenceArray<TelemetryRow?>(128)
    private val next = AtomicLong()
    private val dropped = AtomicLong()
    private val coalesced = AtomicLong()
    private val unsent = AtomicLong()
    private val firstPressure = AtomicReference<TelemetryRow?>()
    private val firstFailure = AtomicReference<TelemetryRow?>()
    private val firstFaultLock = Any()
    internal var afterFaultFreezeForTest: (() -> Unit)? = null
    private val pending = ArrayDeque<TelemetryRow>()
    private var sequence = 0L
    private var omitted = 0L
    private var stateOffset = 0

    fun state(slot: Int, row: TelemetryRow) {
        require(slot in 0 until states.length())
        val previous = states.getAndSet(slot, row.copy(detail = row.detail.toList()))
        if (previous != null) coalesced.incrementAndGet()
    }

    fun firstRejection(row: TelemetryRow) {
        states.compareAndSet(65, null, row.copy(detail = row.detail.toList()))
    }

    fun operation(slot: Int, row: TelemetryRow, unknownGeneration: Boolean = false) {
        require(slot in 0 until operations.length())
        val id = row.detail[4]
        while (true) {
            val previous = operations.get(slot)
            if (row.stage == DiagnosticStage.BEGIN.ordinal) {
                if (previous != null && previous.row.detail[4] >= id) return
                val value = Operation(row.copy(detail = row.detail.toList()), unknownGeneration)
                if (operations.compareAndSet(slot, previous, value)) return
            } else {
                if (previous == null || previous.row.detail[4] != id ||
                    (!previous.unknownGeneration && previous.row.generation != row.generation)) return
                if (operations.compareAndSet(slot, previous, null)) return
            }
        }
    }

    fun event(row: TelemetryRow, pressure: Boolean = false, failure: Boolean = false,
              causalGeneration: Int? = null) {
        val immutable = row.copy(detail = row.detail.toList())
        if (pressure || failure) synchronized(firstFaultLock) {
            causal.freeze(causalGeneration ?: row.generation, row.atNs, row.track)
            afterFaultFreezeForTest?.invoke()
            if (pressure) firstPressure.compareAndSet(null, immutable)
            if (failure) firstFailure.compareAndSet(null, immutable)
        }
        val index = (next.getAndIncrement() and Long.MAX_VALUE).rem(ingress.length()).toInt()
        if (!ingress.compareAndSet(index, null, immutable)) dropped.incrementAndGet()
    }

    fun publicationUnsent() { unsent.incrementAndGet() }

    fun publish(final: Boolean = false): ByteArray {
        for (i in 0 until ingress.length()) {
            val row = ingress.getAndSet(i, null) ?: continue
            if (pending.size < 64) pending.addLast(row) else dropped.incrementAndGet()
        }
        val stateRows = JSONArray()
        val eventRows = JSONArray()
        val root = JSONObject().put("state", "session-telemetry").put("message", "metadata")
            .put("version", SessionTelemetryProtocol.VERSION)
            .put("seq", "${++sequence}").put("atNs", "${clock()}").put("final", final)
            .put("current", stateRows).put("events", eventRows)
            .put("firstPressure", firstPressure.get()?.json() ?: JSONObject.NULL)
            .put("firstFailure", firstFailure.get()?.json() ?: JSONObject.NULL)
            .put("dropped", "${dropped.get()}").put("coalesced", "${coalesced.get()}")
            .put("omitted", "$omitted").put("unsent", "${unsent.get()}")
        // This small reserved bundle is repeated, including in the terminal batch. Ordinary
        // event saturation and replacement of a periodic STATUS cannot erase the first fault.
        causal.rows().forEach { eventRows.put(it.json()) }
        var encodedSize = root.toString().toByteArray(Charsets.UTF_8).size
        val limit = SessionTelemetryProtocol.MAX_BATCH_BYTES - 32
        check(encodedSize <= limit)
        val stateBudget = minOf(SessionTelemetryProtocol.MAX_BATCH_BYTES / 2,
            (limit - encodedSize).coerceAtLeast(0))
        var stateSize = 0
        fun append(array: JSONArray, row: TelemetryRow, state: Boolean): Boolean {
            val json = row.json()
            val added = json.toString().toByteArray(Charsets.UTF_8).size +
                if (array.length() == 0) 0 else 1
            if (encodedSize + added > limit || state && stateSize + added > stateBudget) return false
            array.put(json)
            encodedSize += added
            if (state) stateSize += added
            return true
        }
        // The first rejection and settled outcomes must survive state truncation.
        states.get(65)?.let { if (!append(stateRows, it, true)) omitted++ }
        for (i in 0 until operations.length()) {
            val row = operations.get(i)?.row ?: continue
            if (!append(stateRows, row, true)) omitted++
        }
        val ordered = listOf(60, 63, 64) + (0 until 62).map {
            val index = (stateOffset + it) % 62
            if (index >= 60) index + 1 else index
        }
        for (i in ordered) {
            val row = states.get(i) ?: continue
            if (stateRows.length() >= 64) { omitted++; continue }
            if (!append(stateRows, row, true)) omitted++
        }
        stateOffset = (stateOffset + 1) % 62
        while (pending.isNotEmpty() && eventRows.length() < 64) {
            if (!append(eventRows, pending.first(), false)) break
            pending.removeFirst()
        }
        if (final) {
            omitted += pending.size
            pending.clear()
        }
        root.put("omitted", "$omitted")
        return root.toString().toByteArray(Charsets.UTF_8).also {
            check(it.size <= SessionTelemetryProtocol.MAX_BATCH_BYTES)
        }
    }
}

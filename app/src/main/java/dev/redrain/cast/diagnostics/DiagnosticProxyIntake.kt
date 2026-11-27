package dev.redrain.cast.diagnostics

import org.json.JSONArray
import org.json.JSONObject
import org.screencopy.receiver.protocol.DiagnosticProxyNegotiation
import org.screencopy.receiver.protocol.StrictJson
import org.screencopy.receiver.protocol.protocolRequire
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/** Sender-local receiver-source staging and ordered terminal cutoff. */
internal class DiagnosticProxyIntake(
    private val session: String,
    private val pairId: String,
    private val captureId: String,
    private val clockId: String,
    private val videoTiming: Boolean,
    bootstrap: JSONObject
) {
    companion object {
        const val QUEUE_RECORDS = 64
        const val QUEUE_BYTES = 256 * 1024
        const val TERMINAL_BYTES = DiagnosticProxyWire.FRAME_BYTES + 4
        const val BOOTSTRAP_BYTES = 4096
        const val SOURCE_LOSS_BYTES = 4096
        private const val CRITICAL_RECORDS = 8
        private const val CRITICAL_BYTES = 32 * 1024
    }

    private data class Item(
        val ordinal: Long, val sequence: Long, val sourceSession: String?,
        val record: String, val stagedBytes: Int, val retainedBytes: Int
    )
    private data class Terminal(val ordinal: Long, val value: String?, val confirmed: Boolean, val outcome: String?)

    private val queue = ArrayDeque<Item>()
    private val retained = ArrayDeque<Item>()
    private val queueLoss = DiagnosticRecorder.SequenceLoss()
    private val forcedLoss = DiagnosticRecorder.SequenceLoss()
    private val retentionLoss = DiagnosticRecorder.SequenceLoss()
    private var queuedBytes = 0
    private var retainedBytes = 0
    private var ordinal = 0L
    private var lastSequence = 0L
    private var committedSequence = 0L
    private var terminal: Terminal? = null
    private var finished = false
    @Volatile private var committedOutcome: String? = null
    @Volatile private var validity: AtomicBoolean? = null
    private var sourceLoss: String? = null
    private val bootstrap: String

    init {
        val boundSession = DiagnosticProxyWire.decimal(session)
        DiagnosticProxyNegotiation.validateBootstrap(bootstrap, boundSession, pairId, captureId, clockId)
        this.bootstrap = bootstrap.toString()
    }

    fun bootstrap(): JSONObject = StrictJson.decode(bootstrap.toByteArray(Charsets.UTF_8),
        maxDepth = DiagnosticProxyWire.LANE_DEPTH, trackSizes = false)

    fun bindValidity(valid: AtomicBoolean) {
        check(validity == null)
        validity = valid
    }

    /**
     * The caller supplies the complete lane frame length including its prefix.
     * Source validation/serialization occurs before taking the intake lock.
     */
    fun record(record: JSONObject, frameBytes: Int): Boolean {
        val sourceSession = DiagnosticProxyWire.validateSource(record, "receiver", session, captureId, videoTiming)
        val source = record.toString()
        val sourceBytes = source.toByteArray(Charsets.UTF_8).size
        protocolRequire(frameBytes in 5..TERMINAL_BYTES, "Invalid source frame accounting")
        val sequence = DiagnosticProxyWire.decimal(record.opt("seq"))
        val kind = record.optString("kind")
        val stage = record.optJSONObject("data")?.optString("stage")
        val critical = kind == "SESSION_BOUND" || kind == "CAPTURE_STARTED" || kind == "CAPTURE_ENDED" ||
            kind == "SESSION" || kind == "RECOVERY" ||
            stage == "failed" || stage == "rejected" || stage == "pressure" || stage == "evicted"
        val envelope = JSONObject().put("role", "receiver").put("session", sourceSession ?: JSONObject.NULL)
            .put("captureId", captureId).put("clockId", clockId).put("record", record)
        val retainedSize = envelope.toString().toByteArray(Charsets.UTF_8).size
        protocolRequire(retainedSize + 2048 <= DiagnosticProxyWire.FRAME_BYTES, "Record cannot fit FETCH")
        synchronized(this) {
            protocolRequire(validity?.get() != false, "Retired proxy capture")
            protocolRequire(terminal == null && !finished, "Record after terminal")
            protocolRequire(sequence > lastSequence && ordinal < Long.MAX_VALUE, "Out-of-order source record")
            lastSequence = sequence
            val staged = maxOf(frameBytes, sourceBytes + 4)
            val item = Item(++ordinal, sequence, sourceSession, source, staged, retainedSize)
            val recordLimit = QUEUE_RECORDS - if (critical) 0 else CRITICAL_RECORDS
            val byteLimit = QUEUE_BYTES - TERMINAL_BYTES - if (critical) 0 else CRITICAL_BYTES
            if (queue.size >= recordLimit || queuedBytes + staged > byteLimit) {
                queueLoss.record(sequence)
                return false
            }
            queue.addLast(item)
            queuedBytes += staged
            return true
        }
    }

    /** One reserved cell records ENDED or EOF after all complete preceding records. */
    fun terminal(value: JSONObject?, confirmed: Boolean, outcome: String? = null) {
        protocolRequire(!confirmed || value != null && outcome != null, "Missing confirmed terminal outcome")
        protocolRequire(confirmed || outcome == null, "Unconfirmed terminal has outcome")
        protocolRequire(outcome == null || outcome.toByteArray(Charsets.UTF_8).size <= BOOTSTRAP_BYTES,
            "Oversized diagnostic outcome")
        val serialized = value?.toString()
        protocolRequire(serialized == null || serialized.toByteArray(Charsets.UTF_8).size <=
            DiagnosticProxyWire.FRAME_BYTES, "Oversized proxy terminal")
        serialized?.let {
            StrictJson.decode(it.toByteArray(Charsets.UTF_8), maxDepth = DiagnosticProxyWire.LANE_DEPTH, trackSizes = false)
        }
        synchronized(this) {
            protocolRequire(validity?.get() != false, "Retired proxy capture")
            protocolRequire(terminal == null && !finished, "Duplicate proxy terminal")
            terminal = Terminal(ordinal, serialized, confirmed, outcome)
        }
    }

    fun sourceLoss(value: JSONObject) {
        fun fields(objectValue: JSONObject, vararg names: String) {
            protocolRequire(objectValue.keys().asSequence().toSet() == names.toSet(), "Invalid source loss fields")
        }
        fun optionalDecimal(objectValue: JSONObject, key: String): Long? {
            return if (objectValue.isNull(key)) null else DiagnosticProxyWire.decimal(objectValue.opt(key))
        }
        fields(value, "schema", "publishedSeq", "oldestRetainedSeq", "latestRetainedSeq", "admission", "retention")
        protocolRequire(value.opt("schema") == "loss_watermarks", "Invalid source loss schema")
        val published = DiagnosticProxyWire.decimal(value.opt("publishedSeq"))
        val oldest = DiagnosticProxyWire.decimal(value.opt("oldestRetainedSeq"))
        val latest = DiagnosticProxyWire.decimal(value.opt("latestRetainedSeq"))
        protocolRequire(latest <= published && (oldest == 0L || oldest <= latest),
            "Invalid source loss watermarks")
        val admission = value.optJSONObject("admission")
            ?: throw org.screencopy.receiver.protocol.ProtocolException("PROTOCOL", "Missing admission loss")
        val drops = listOf("routine_capacity", "critical_capacity", "closed_capture", "expired", "oversized_record")
        fields(admission, *(drops + "total").toTypedArray())
        val total = DiagnosticProxyWire.decimal(admission.opt("total"))
        var accounted = 0L
        drops.forEach { name ->
            val drop = admission.optJSONObject(name)
                ?: throw org.screencopy.receiver.protocol.ProtocolException("PROTOCOL", "Missing admission loss reason")
            fields(drop, "count", "firstMonoNs", "lastMonoNs")
            val count = DiagnosticProxyWire.decimal(drop.opt("count"))
            accounted = try { Math.addExact(accounted, count) }
                catch (_: ArithmeticException) {
                    throw org.screencopy.receiver.protocol.ProtocolException("PROTOCOL", "Admission loss overflow")
                }
            val first = optionalDecimal(drop, "firstMonoNs")
            val last = optionalDecimal(drop, "lastMonoNs")
            protocolRequire(if (count == 0L) first == null && last == null
                else first != null && last != null && first <= last, "Invalid admission loss range")
        }
        protocolRequire(accounted == total, "Admission loss total mismatch")
        val retention = value.optJSONObject("retention")
            ?: throw org.screencopy.receiver.protocol.ProtocolException("PROTOCOL", "Missing retention loss")
        fields(retention, "routine", "critical", "oversized")
        listOf("routine", "critical", "oversized").forEach { name ->
            val loss = retention.optJSONObject(name)
                ?: throw org.screencopy.receiver.protocol.ProtocolException("PROTOCOL", "Missing retention loss reason")
            fields(loss, "count", "firstSeq", "lastSeq", "exactRange")
            val count = DiagnosticProxyWire.decimal(loss.opt("count"))
            val first = optionalDecimal(loss, "firstSeq")
            val last = optionalDecimal(loss, "lastSeq")
            val exact = loss.opt("exactRange") as? Boolean
                ?: throw org.screencopy.receiver.protocol.ProtocolException("PROTOCOL", "Invalid source loss range")
            protocolRequire(if (count == 0L) first == null && last == null && !exact
                else first != null && last != null && first <= last &&
                    (!exact || last - first == count - 1), "Invalid retention loss range")
        }
        val serialized = value.toString()
        val bytes = serialized.toByteArray(Charsets.UTF_8)
        protocolRequire(bytes.size <= SOURCE_LOSS_BYTES, "Oversized source loss")
        StrictJson.decode(bytes, maxDepth = DiagnosticProxyWire.SOURCE_DEPTH, trackSizes = false)
        synchronized(this) {
            protocolRequire(validity?.get() != false, "Retired proxy capture")
            protocolRequire(terminal == null && !finished, "Source loss after terminal")
            sourceLoss = serialized
        }
    }

    /** No socket, encoding, or caller callbacks run inside this lock. */
    @Synchronized fun consume(discard: Boolean = false) {
        protocolRequire(!discard || terminal != null, "Discard requires terminal cutoff")
        while (queue.isNotEmpty()) {
            val item = queue.removeFirst()
            queuedBytes -= item.stagedBytes
            if (discard) {
                forcedLoss.record(item.sequence)
                continue
            }
            while (retained.size >= DiagnosticRecorder.MAX_RECORDS ||
                retainedBytes + item.retainedBytes > DiagnosticRecorder.MAX_BYTES - BOOTSTRAP_BYTES - SOURCE_LOSS_BYTES) {
                val old = retained.removeFirst()
                retainedBytes -= old.retainedBytes
                retentionLoss.record(old.sequence)
            }
            retained.addLast(item)
            retainedBytes += item.retainedBytes
        }
        committedSequence = lastSequence
        if (terminal != null) {
            committedOutcome = terminal?.outcome
            finished = true
        }
    }

    @Synchronized fun closeUnconfirmed(discard: Boolean = false) {
        if (finished) return
        if (terminal == null) terminal = Terminal(ordinal, null, false, null)
        consume(discard)
    }

    fun committedOutcome(): String? = committedOutcome

    fun fetch(after: Long, limit: Int): JSONObject {
        require(after >= 0 && limit in 1..32)
        val snapshot = synchronized(this) {
            val selected = ArrayList<Item>()
            var size = 2048
            for (item in retained) {
                if (item.sequence <= after) continue
                if (selected.size == limit || size + item.retainedBytes + 1 > DiagnosticProxyWire.FRAME_BYTES) break
                selected.add(item)
                size += item.retainedBytes + 1
            }
            val next = selected.lastOrNull()?.sequence ?: after
            Snapshot(selected, next, retained.any { it.sequence > next }, retained.firstOrNull()?.sequence ?: 0,
                committedSequence, queue.size, queueLoss.snapshot(), forcedLoss.snapshot(), retentionLoss.snapshot(),
                terminal, finished, sourceLoss)
        }
        val records = JSONArray()
        for (item in snapshot.items) {
            records.put(JSONObject().put("role", "receiver").put("session", item.sourceSession ?: JSONObject.NULL)
                .put("captureId", captureId).put("clockId", clockId)
                .put("record", StrictJson.decode(item.record.toByteArray(Charsets.UTF_8),
                    maxDepth = DiagnosticProxyWire.SOURCE_DEPTH, trackSizes = false)))
        }
        return JSONObject().put("records", records).put("nextAfterSeq", "${snapshot.next}")
            .put("oldestSeq", "${snapshot.oldest}").put("latestSeq", "${snapshot.latest}")
            .put("hasMore", snapshot.hasMore).put("pendingRecords", snapshot.pending)
            .put("intakeLoss", snapshot.queueLoss.json()).put("forcedLoss", snapshot.forcedLoss.json())
            .put("retentionLoss", snapshot.retentionLoss.json())
            .put("sourceLoss", snapshot.sourceLoss?.let {
                StrictJson.decode(it.toByteArray(Charsets.UTF_8), maxDepth = DiagnosticProxyWire.SOURCE_DEPTH, trackSizes = false)
            } ?: JSONObject.NULL)
            .put("state", if (snapshot.finished) "finished" else if (snapshot.terminal != null) "finishing" else "active")
            .put("cutoffOrdinal", snapshot.terminal?.ordinal?.toString() ?: JSONObject.NULL)
            .put("endConfirmed", snapshot.terminal?.confirmed ?: false)
            .put("terminal", snapshot.terminal?.value?.let {
                StrictJson.decode(it.toByteArray(Charsets.UTF_8),
                    maxDepth = DiagnosticProxyWire.LANE_DEPTH, trackSizes = false).let { value ->
                    JSONObject().put("reason", value.opt("reason") ?: JSONObject.NULL)
                        .put("tailTruncated", value.opt("tailTruncated") ?: JSONObject.NULL)
                        .apply { value.optJSONObject("tailDetails")?.let { put("tailDetails", it) } }
                }
            } ?: JSONObject.NULL)
    }

    fun terminalValue(): JSONObject? {
        val value = synchronized(this) { terminal?.takeIf { finished }?.value } ?: return null
        return StrictJson.decode(value.toByteArray(Charsets.UTF_8),
            maxDepth = DiagnosticProxyWire.LANE_DEPTH, trackSizes = false)
    }

    private data class Snapshot(
        val items: List<Item>, val next: Long, val hasMore: Boolean, val oldest: Long, val latest: Long,
        val pending: Int, val queueLoss: Loss, val forcedLoss: Loss, val retentionLoss: Loss,
        val terminal: Terminal?, val finished: Boolean, val sourceLoss: String?
    )

    private data class Loss(val count: Long, val first: Long, val last: Long, val exact: Boolean) {
        fun json() = JSONObject().put("count", "$count")
            .put("firstSeq", if (count == 0L) JSONObject.NULL else "$first")
            .put("lastSeq", if (count == 0L) JSONObject.NULL else "$last")
            .put("exactRange", count > 0 && exact)
    }

    private fun DiagnosticRecorder.SequenceLoss.snapshot() = Loss(count, first, last, exact)
}

package dev.redrain.cast.diagnostics

import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReferenceArray

/** Fixed vocabulary: no media, arbitrary log text, addresses or exception messages. */
enum class DiagnosticKind(val group: String) {
    SESSION("session"), RECOVERY("recovery"), AUDIO_CAPTURE("audio.capture"),
    AUDIO_TRANSPORT("audio.transport"), AUDIO_QUEUE("audio.queue"), AUDIO_OUTPUT("audio.output"),
    VIDEO_PROGRESS("video.progress"), TRANSPORT_HEALTH("transport.health")
}

enum class DiagnosticStage {
    STARTED, ENDED, CONFIG, BEGIN, COMPLETE, FAILED, ADMITTED, REJECTED, PRESSURE,
    READY, RESET, WINDOW, SAMPLE, PROOF, HOLD, EVICTED
}

enum class DiagnosticReason {
    NONE, CAPTURE, RECEIVER, PRESSURE, SOURCE_WINDOW_LOST, PREROLL_MISSING,
    SOURCE_PENDING, PROOF_CAPACITY, STOPPED, FAILURE, STALE, CAPACITY, WINDOW_GAP, REPLACED
}
enum class DiagnosticPoint {
    UNSPECIFIED, READ, WRITE, DECODE, RESAMPLE, COMPENSATE, REFILL, TIMING, QUEUE, RECOVERY,
    AUDIO_START_WAIT, RECOVERY_RETIRE, RECOVERY_CONSTRUCT
}

internal data class Observation(
    var seq: Long,
    val ns: Long,
    val kind: DiagnosticKind,
    val stage: DiagnosticStage,
    val role: String,
    val session: Long,
    val track: Int,
    val generation: Int,
    val operation: Long,
    val pts: Long,
    val endPts: Long,
    val count: Long,
    val bytes: Long,
    val cutoff: Long,
    val reason: DiagnosticReason,
    val point: DiagnosticPoint,
    val metadata: DiagnosticMetadata?,
    val durationNs: Long = -1,
    val controlKind: String? = null,
    val controlData: JSONObject? = null
)

class DiagnosticError(val code: String) : IllegalArgumentException(code)

/**
 * Producers use only bounded atomics and a fixed MPSC queue. JSON, aggregation
 * snapshots and retention are confined to the independent diagnostic control lane.
 */
class DiagnosticRecorder(private val clock: () -> Long = System::nanoTime) {
    companion object {
        const val MAX_RECORDS = 4096
        const val MAX_BYTES = 2 * 1024 * 1024
        const val MAX_RECORD_BYTES = 2048
        const val PENDING = 1024
        const val CRITICAL_RESERVED = 128
        const val CRITICAL_RECORDS = 512
        const val CRITICAL_BYTES = 512 * 1024
        const val SLOW_OPERATION_NS = 20_000_000L

        private const val ROUTINE_RECORDS = MAX_RECORDS - CRITICAL_RECORDS
        private const val ROUTINE_BYTES = MAX_BYTES - CRITICAL_BYTES
        private val KIND_COUNT = DiagnosticKind.values().size
        private val POINT_COUNT = DiagnosticPoint.values().size
        private val FINAL_SUMMARY_RESERVED = KIND_COUNT * POINT_COUNT + DiagnosticVideoStage.values().size
        private val NONTERMINAL_PRIORITY_LIMIT = PENDING - FINAL_SUMMARY_RESERVED - 1
        private const val METRIC_FIELDS = 11
        private const val DROPPED_REASONS = 5
        private val DROP_NAMES = listOf(
            "routine_capacity", "critical_capacity", "closed_capture", "expired", "oversized_record"
        )

        val capabilities = mapOf(
            "sender" to setOf("session", "recovery", "audio.capture", "audio.transport", "audio.queue", "video.progress", "transport.health"),
            "receiver" to setOf("session", "recovery", "audio.transport", "audio.queue", "audio.output", "video.progress", "transport.health")
        )

        fun limits() = JSONObject().put("maxRecords", MAX_RECORDS).put("maxBytes", MAX_BYTES)
            .put("maxRecordBytes", MAX_RECORD_BYTES).put("maxDurationMs", 180000)
            .put("maxLineBytes", 16384).put("maxFetchRecords", 32).put("stagingRecords", PENDING)
            .put("reservedCriticalQueueRecords", CRITICAL_RESERVED)
            .put("reservedFinalSummaryRecords", FINAL_SUMMARY_RESERVED)
            .put("reservedTerminalRecords", 1)
            .put("reservedCriticalRetentionRecords", CRITICAL_RECORDS)
            .put("maxRecordsPerSecond", 256).put("maxTelemetryBytesPerSecond", 131072)
            .put("maxCommandsPerSecond", 20).put("frozenRetentionMs", 120000)
            .put("operationSummaryPeriodMs", "samplePeriodMs")
            .put("slowOperationThresholdNs", "$SLOW_OPERATION_NS")
    }

    internal data class Retained(val seq: Long, val event: JSONObject, val bytes: Int)

    internal class SequenceLoss {
        var count = 0L
        var first = 0L
        var last = 0L
        var exact = true
        fun record(seq: Long) {
            if (count == 0L) first = seq
            else if (seq != last + 1) exact = false
            last = seq
            count++
        }
        fun json() = JSONObject().put("count", "$count")
            .put("firstSeq", if (count == 0L) JSONObject.NULL else "$first")
            .put("lastSeq", if (count == 0L) JSONObject.NULL else "$last")
            .put("exactRange", count > 0 && exact)
    }

    internal class Capture(
        val id: String,
        val role: String,
        @Volatile var session: Long?,
        val flags: Set<String>,
        val started: Long,
        val expires: Long,
        val period: Long,
        val operationFloor: Long
    ) {
        // Non-negative state is the next sequence; the sign bit closes admission.
        val queueState = AtomicLong(0)
        val queueHead = AtomicLong(0)
        val queue = AtomicReferenceArray<Observation?>(PENDING)
        val dropCounts = AtomicLongArray(DROPPED_REASONS)
        val dropFirstNs = AtomicLongArray(DROPPED_REASONS).apply {
            for (i in 0 until length()) set(i, Long.MAX_VALUE)
        }
        val dropLastNs = AtomicLongArray(DROPPED_REASONS)
        val routine = ArrayDeque<Retained>()
        val critical = ArrayDeque<Retained>()
        var routineBytes = 0
        var criticalBytes = 0
        var evicted = 0L
        val routineEvictions = SequenceLoss()
        val criticalEvictions = SequenceLoss()
        val oversizedRecords = SequenceLoss()
        @Volatile var collecting = true
        var ended: Long? = null
        var reason: String? = null
        var sampleAt = started + period
        var aggregateAt = started

        val inFlightIds = AtomicLongArray(256)
        val inFlightStartedNs = AtomicLongArray(256)
        val inFlightKinds = AtomicLongArray(256)
        val inFlightPoints = AtomicLongArray(256)
        val inFlightTracks = AtomicLongArray(256)
        val inFlightGenerations = AtomicLongArray(256)
        val inFlightPts = AtomicLongArray(256)
        val inFlightEndPts = AtomicLongArray(256)
        val inFlightCounts = AtomicLongArray(256)
        val inFlightBytes = AtomicLongArray(256)
        val untrackedOperations = AtomicLong()
        val video = AtomicReferenceArray<DiagnosticVideoProgress?>(DiagnosticVideoStage.values().size)
        val staleVideoUpdates = AtomicLong()
        val videoGeneration = AtomicLong()

        // Per-kind/point fixed counters: starts, completions, unnumbered calls,
        // bytes, units, four duration buckets, slow count and maximum duration.
        val metrics = AtomicLongArray(KIND_COUNT * POINT_COUNT * METRIC_FIELDS)
        val slowExampleIds = AtomicLongArray(KIND_COUNT * POINT_COUNT)
        val slowExampleDurations = AtomicLongArray(KIND_COUNT * POINT_COUNT)
        val slowExampleTracks = AtomicLongArray(KIND_COUNT * POINT_COUNT)
        val slowExampleGenerations = AtomicLongArray(KIND_COUNT * POINT_COUNT)
        val slowExamplePts = AtomicLongArray(KIND_COUNT * POINT_COUNT)
        val slowExampleEndPts = AtomicLongArray(KIND_COUNT * POINT_COUNT)
        val slowExampleCounts = AtomicLongArray(KIND_COUNT * POINT_COUNT)
        val slowExampleBytes = AtomicLongArray(KIND_COUNT * POINT_COUNT)
    }

    private val sessions = ConcurrentHashMap<String, Long>()
    @Volatile private var capture: Capture? = null
    @Volatile private var gate: Capture? = null
    @Volatile internal var afterResponseSnapshot: (() -> Unit)? = null
    @Volatile internal var afterSequenceReservation: (() -> Unit)? = null
    private var consent = true
    private var delegatedSession: Long? = null
    private var delegatedActive = false
    @Volatile private var delegatedOwner: (() -> Boolean)? = null

    @Synchronized internal fun restrictToReceiver(session: Long, ownerActive: () -> Boolean = { true }) {
        revoke()
        delegatedSession = session
        delegatedActive = true
        delegatedOwner = ownerActive
        consent = true
    }
    @Synchronized internal fun endDelegated(session: Long) {
        if (delegatedSession != session || !delegatedActive) return
        delegatedActive = false
        disconnect("delegation_ended")
        delegatedOwner = null
    }
    @Synchronized internal fun unrestricted() {
        delegatedSession = null
        delegatedActive = false
        delegatedOwner = null
    }
    @Synchronized internal fun scope(): JSONObject? = delegatedSession?.let {
        JSONObject().put("role", "receiver").put("session", "$it")
            .put("active", delegatedActive && delegatedOwner?.invoke() != false)
    }
    @Synchronized fun grantConsent() { consent = true }
    private val operations = AtomicLong()
    val clockId: String = UUID.randomUUID().toString()
    @Volatile var lastEndReason: String? = null
        private set
    val collecting: Boolean get() = gate != null && delegatedOwner?.invoke() != false
    val samplePeriodNs: Long get() = gate?.period ?: Long.MAX_VALUE
    internal val captureIdentity: String? get() = capture?.id

    fun enabled(role: String, session: Long, kind: DiagnosticKind): Boolean {
        val current = gate ?: return false
        return current.collecting && delegatedOwner?.invoke() != false &&
            current.role == role && current.session == session && kind.group in current.flags
    }

    fun operation(role: String, session: Long, kind: DiagnosticKind): Long {
        val current = gate ?: return 0
        if (!enabled(role, session, kind)) return 0
        val id = operations.incrementAndGet()
        return if (gate === current && current.collecting) id else 0
    }

    fun videoConfigured(role: String, session: Long, generation: Int, metadata: DiagnosticVideoConfiguration) {
        val c = gate ?: return
        if (!enabled(role, session, DiagnosticKind.VIDEO_PROGRESS) || generation <= 0) return
        if (!advanceVideoGeneration(c, generation, clock())) return
        observe(role, session, DiagnosticKind.VIDEO_PROGRESS, DiagnosticStage.CONFIG, 1, generation, metadata = metadata)
    }

    private fun advanceVideoGeneration(c: Capture, generation: Int, ns: Long): Boolean {
        repeat(4) {
            val latest = c.videoGeneration.get()
            if (generation < latest) { c.staleVideoUpdates.incrementAndGet(); return false }
            if (generation.toLong() == latest || c.videoGeneration.compareAndSet(latest, generation.toLong())) return true
        }
        drop(c, Drop.ROUTINE_CAPACITY, ns)
        return false
    }

    fun video(
        role: String, session: Long, generation: Int, stage: DiagnosticVideoStage, pts: Long,
        bytes: Long = -1, count: Long = 1, keyFrame: Boolean? = null,
        reason: DiagnosticReason = DiagnosticReason.NONE, sourceAtNs: Long? = null
    ) {
        val c = gate ?: return
        if (!enabled(role, session, DiagnosticKind.VIDEO_PROGRESS) || generation <= 0 || count <= 0) return
        val ns = clock()
        if (ns >= c.expires) { drop(c, Drop.EXPIRED, ns); return }
        if (stage != DiagnosticVideoStage.ENCODED && !advanceVideoGeneration(c, generation, ns)) return
        val slot = stage.ordinal
        // Bounded contention: diagnostics must never delay a media producer.
        repeat(4) {
            val old = c.video.get(slot)
            if (old != null && old.generation > generation) {
                c.staleVideoUpdates.incrementAndGet()
                return
            }
            val previous = old?.takeIf { it.generation == generation }
            val next = DiagnosticVideoProgress(generation, stage,
                (previous?.count ?: 0) + count,
                if (bytes >= 0) (previous?.bytes ?: 0).coerceAtLeast(0) + bytes else previous?.bytes ?: -1,
                if (keyFrame != null) (previous?.keyFrames ?: 0).coerceAtLeast(0) + if (keyFrame) count else 0
                    else previous?.keyFrames ?: -1,
                previous?.firstPtsUs ?: pts, pts, previous?.firstAtNs ?: ns, ns, sourceAtNs,
                maxOf(previous?.maxPtsGapUs ?: 0, if (previous != null && pts >= 0 && previous.lastPtsUs >= 0)
                    (pts - previous.lastPtsUs).coerceAtLeast(0) else 0),
                maxOf(previous?.maxObservationGapNs ?: 0, previous?.let { (ns - it.lastAtNs).coerceAtLeast(0) } ?: 0),
                reason,
                (previous?.stale ?: 0) + if (reason == DiagnosticReason.STALE) count else 0,
                (previous?.capacity ?: 0) + if (reason == DiagnosticReason.CAPACITY) count else 0,
                (previous?.pressure ?: 0) + if (reason == DiagnosticReason.PRESSURE) count else 0,
                (previous?.sourcePending ?: 0) + if (reason == DiagnosticReason.SOURCE_PENDING) count else 0,
                (previous?.stopped ?: 0) + if (reason == DiagnosticReason.STOPPED) count else 0,
                (previous?.replaced ?: 0) + if (reason == DiagnosticReason.REPLACED) count else 0)
            if (c.video.compareAndSet(slot, old, next)) {
                if (old != null && previous == null) submitVideo(c, old, ns, DiagnosticStage.RESET)
                return
            }
        }
        drop(c, Drop.ROUTINE_CAPACITY, ns)
    }

    private fun submitVideo(c: Capture, value: DiagnosticVideoProgress, ns: Long,
                            stage: DiagnosticStage = DiagnosticStage.SAMPLE, priority: Boolean = false) {
        val metadata = if (value.videoStage != DiagnosticVideoStage.ENCODED &&
            value.generation < c.videoGeneration.get()) value.copy(retired = true) else value
        val event = observation(ns, DiagnosticKind.VIDEO_PROGRESS, stage, c.role, c.session ?: 0,
            1, value.generation, 0, value.lastPtsUs, -1, value.count, value.bytes, -1,
            value.reason, DiagnosticPoint.UNSPECIFIED, metadata)
        submit(c, event, priority, finalSummary = priority)
    }

    private fun sampleVideo(c: Capture, now: Long, priority: Boolean = false) {
        for (i in 0 until c.video.length()) c.video.get(i)?.let { submitVideo(c, it, now, priority = priority) }
    }

    fun observe(
        role: String, session: Long, kind: DiagnosticKind, stage: DiagnosticStage,
        track: Int = 0, generation: Int = 0, operation: Long = 0, pts: Long = -1,
        endPts: Long = -1, count: Long = -1, bytes: Long = -1, cutoff: Long = -1,
        reason: DiagnosticReason = DiagnosticReason.NONE, point: DiagnosticPoint = DiagnosticPoint.UNSPECIFIED,
        metadata: DiagnosticMetadata? = null
    ) {
        val current = gate ?: return
        if (delegatedOwner?.invoke() == false) return
        if (!current.collecting || current.role != role || current.session != session || kind.group !in current.flags) return
        if (operation > 0 && operation <= current.operationFloor) return
        if (operation == 0L && point != DiagnosticPoint.UNSPECIFIED && point != DiagnosticPoint.QUEUE &&
            point != DiagnosticPoint.RECOVERY &&
            (stage == DiagnosticStage.BEGIN || stage == DiagnosticStage.COMPLETE || stage == DiagnosticStage.FAILED)) return

        val ns = clock()
        if (ns >= current.expires) {
            drop(current, Drop.EXPIRED, ns)
            return
        }
        if (operation > 0 && (stage == DiagnosticStage.BEGIN || stage == DiagnosticStage.COMPLETE ||
                stage == DiagnosticStage.FAILED)) {
            operationObservation(current, ns, kind, stage, track, generation, operation, bytes, point, reason,
                role, session, pts, endPts, count, cutoff, metadata)
            return
        }
        if (operation == 0L && metadata == null && !isCritical(kind, stage) &&
            (stage == DiagnosticStage.BEGIN || stage == DiagnosticStage.COMPLETE || stage == DiagnosticStage.SAMPLE)) {
            addMetric(current, kind, point, 2, 1)
            if (bytes > 0) addMetric(current, kind, point, 3, bytes)
            if (count > 0) addMetric(current, kind, point, 4, count)
            return
        }
        submit(current, observation(ns, kind, stage, role, session, track, generation, operation, pts,
            endPts, count, bytes, cutoff, reason, point, metadata), isCritical(kind, stage))
    }

    private fun operationObservation(
        c: Capture, ns: Long, kind: DiagnosticKind, stage: DiagnosticStage, track: Int, generation: Int,
        operation: Long, bytes: Long, point: DiagnosticPoint, reason: DiagnosticReason, role: String,
        session: Long, pts: Long, endPts: Long, count: Long, cutoff: Long, metadata: DiagnosticMetadata?
    ) {
        val slot = (operation % c.inFlightIds.length()).toInt()
        if (stage == DiagnosticStage.BEGIN) {
            val claimed = c.inFlightIds.compareAndSet(slot, 0, -operation)
            if (claimed) {
                c.inFlightStartedNs.set(slot, ns)
                c.inFlightKinds.set(slot, kind.ordinal.toLong())
                c.inFlightPoints.set(slot, point.ordinal.toLong())
                c.inFlightTracks.set(slot, track.toLong())
                c.inFlightGenerations.set(slot, generation.toLong())
                c.inFlightPts.set(slot, pts)
                c.inFlightEndPts.set(slot, endPts)
                c.inFlightCounts.set(slot, count)
                c.inFlightBytes.set(slot, bytes)
                c.inFlightIds.set(slot, operation)
            } else {
                c.untrackedOperations.incrementAndGet()
            }
            addMetric(c, kind, point, 0, 1)
            return
        }

        var duration = -1L
        if (c.inFlightIds.get(slot) == operation) {
            val started = c.inFlightStartedNs.get(slot)
            duration = (ns - started).coerceAtLeast(0)
            if (!c.inFlightIds.compareAndSet(slot, operation, 0)) duration = -1L
        }
        if (duration < 0) c.untrackedOperations.incrementAndGet()
        addMetric(c, kind, point, 1, 1)
        if (bytes > 0) addMetric(c, kind, point, 3, bytes)
        if (count > 0) addMetric(c, kind, point, 4, count)
        if (duration >= 0) {
            val bucket = when {
                duration < 1_000_000L -> 5
                duration < 5_000_000L -> 6
                duration < SLOW_OPERATION_NS -> 7
                else -> 8
            }
            addMetric(c, kind, point, bucket, 1)
            if (duration >= SLOW_OPERATION_NS) {
                storeSlowExample(c, kind, point, operation, duration, track, generation, pts, endPts, count, bytes)
                addMetric(c, kind, point, 9, 1)
            }
            updateMax(c, kind, point, duration)
        }
        if (stage == DiagnosticStage.FAILED) {
            val event = observation(ns, kind, stage, role, session, track, generation, operation, pts,
                endPts, count, bytes, cutoff, reason, point, metadata).copyDuration(duration)
            submit(c, event, true)
        }
    }

    private fun Observation.copyDuration(value: Long) = Observation(
        seq, ns, kind, stage, role, session, track, generation, operation, pts, endPts, count, bytes,
        cutoff, reason, point, metadata, value, controlKind, controlData
    )

    private fun observation(
        ns: Long, kind: DiagnosticKind, stage: DiagnosticStage, role: String, session: Long,
        track: Int, generation: Int, operation: Long, pts: Long, endPts: Long, count: Long,
        bytes: Long, cutoff: Long, reason: DiagnosticReason, point: DiagnosticPoint,
        metadata: DiagnosticMetadata?
    ) = Observation(0, ns, kind, stage, role, session, track, generation, operation, pts, endPts,
        count, bytes, cutoff, reason, point, metadata)

    private fun isCritical(kind: DiagnosticKind, stage: DiagnosticStage) =
        kind == DiagnosticKind.SESSION || kind == DiagnosticKind.RECOVERY ||
            stage == DiagnosticStage.FAILED || stage == DiagnosticStage.REJECTED ||
            stage == DiagnosticStage.PRESSURE || stage == DiagnosticStage.EVICTED

    private fun metricIndex(kind: DiagnosticKind, point: DiagnosticPoint, field: Int): Int =
        (kind.ordinal * POINT_COUNT + point.ordinal) * METRIC_FIELDS + field

    private fun addMetric(c: Capture, kind: DiagnosticKind, point: DiagnosticPoint, field: Int, value: Long) {
        c.metrics.addAndGet(metricIndex(kind, point, field), value)
    }

    private fun updateMax(c: Capture, kind: DiagnosticKind, point: DiagnosticPoint, duration: Long) {
        val index = metricIndex(kind, point, 10)
        while (true) {
            val previous = c.metrics.get(index)
            if (duration <= previous || c.metrics.compareAndSet(index, previous, duration)) return
        }
    }

    private fun storeSlowExample(
        c: Capture, kind: DiagnosticKind, point: DiagnosticPoint, operation: Long,
        duration: Long, track: Int, generation: Int, pts: Long, endPts: Long, count: Long, bytes: Long
    ) {
        val index = kind.ordinal * POINT_COUNT + point.ordinal
        if (!c.slowExampleIds.compareAndSet(index, 0, -operation)) return
        c.slowExampleDurations.set(index, duration)
        c.slowExampleTracks.set(index, track.toLong())
        c.slowExampleGenerations.set(index, generation.toLong())
        c.slowExamplePts.set(index, pts)
        c.slowExampleEndPts.set(index, endPts)
        c.slowExampleCounts.set(index, count)
        c.slowExampleBytes.set(index, bytes)
        c.slowExampleIds.set(index, operation)
    }

    private data class SlowExample(
        val operation: Long, val duration: Long, val track: Int, val generation: Int,
        val pts: Long, val endPts: Long, val count: Long, val bytes: Long
    )

    private fun takeSlowExample(c: Capture, kind: DiagnosticKind, point: DiagnosticPoint): SlowExample? {
        val index = kind.ordinal * POINT_COUNT + point.ordinal
        val operation = c.slowExampleIds.get(index)
        if (operation <= 0) return null
        val value = SlowExample(operation, c.slowExampleDurations.get(index),
            c.slowExampleTracks.get(index).toInt(), c.slowExampleGenerations.get(index).toInt(),
            c.slowExamplePts.get(index), c.slowExampleEndPts.get(index),
            c.slowExampleCounts.get(index), c.slowExampleBytes.get(index))
        return if (c.slowExampleIds.compareAndSet(index, operation, 0)) value else null
    }

    private enum class Drop(val index: Int) {
        ROUTINE_CAPACITY(0), CRITICAL_CAPACITY(1), CLOSED_CAPTURE(2), EXPIRED(3), OVERSIZED_RECORD(4)
    }

    private fun drop(c: Capture, reason: Drop, ns: Long) {
        while (true) {
            val first = c.dropFirstNs.get(reason.index)
            if (first <= ns || c.dropFirstNs.compareAndSet(reason.index, first, ns)) break
        }
        while (true) {
            val last = c.dropLastNs.get(reason.index)
            if (last >= ns || c.dropLastNs.compareAndSet(reason.index, last, ns)) break
        }
        c.dropCounts.incrementAndGet(reason.index)
    }

    private fun tail(c: Capture) = c.queueState.get() and Long.MAX_VALUE
    private fun submit(
        c: Capture, event: Observation, priority: Boolean,
        countDrop: Boolean = true, finalSummary: Boolean = false
    ): Boolean {
        while (true) {
            val state = c.queueState.get()
            val ns = event.ns
            if (state < 0) {
                if (countDrop) drop(c, Drop.CLOSED_CAPTURE, ns)
                return false
            }
            val next = state
            val outstanding = next - c.queueHead.get()
            val limit = when {
                finalSummary -> PENDING - 1
                priority -> NONTERMINAL_PRIORITY_LIMIT
                else -> PENDING - CRITICAL_RESERVED
            }
            if (outstanding >= limit || next >= Long.MAX_VALUE - 1) {
                if (countDrop) drop(c, if (priority) Drop.CRITICAL_CAPACITY else Drop.ROUTINE_CAPACITY, ns)
                return false
            }
            if (!c.queueState.compareAndSet(state, state + 1)) continue
            event.seq = next + 1
            afterSequenceReservation?.invoke()
            c.queue.set((next % PENDING).toInt(), event)
            return true
        }
    }

    private fun submitFinal(c: Capture, event: Observation): Boolean {
        while (true) {
            val state = c.queueState.get()
            if (state < 0) return false
            val next = state
            val outstanding = next - c.queueHead.get()
            if (outstanding >= PENDING || next >= Long.MAX_VALUE - 1) {
                drop(c, Drop.CRITICAL_CAPACITY, event.ns)
                return false
            }
            val closed = Long.MIN_VALUE or (state + 1)
            if (!c.queueState.compareAndSet(state, closed)) continue
            event.seq = next + 1
            c.queue.set((next % PENDING).toInt(), event)
            return true
        }
    }

    private fun lifecycle(c: Capture, kind: String, data: JSONObject, terminal: Boolean = false) {
        val event = Observation(0, clock(), DiagnosticKind.SESSION, DiagnosticStage.SAMPLE,
            c.role, c.session ?: 0, 0, 0, 0, -1, -1, -1, -1, -1, DiagnosticReason.NONE,
            DiagnosticPoint.UNSPECIFIED, null, controlKind = kind, controlData = data)
        if (terminal) submitFinal(c, event) else submit(c, event, true)
    }

    @Synchronized fun sessionStarted(role: String, session: Long) {
        if (sessions.put(role, session) == session) return
        val c = capture ?: return
        tick()
        if (c.collecting && c.role == role && c.session == null) {
            gate = null
            c.session = session
            lifecycle(c, "SESSION_BOUND", JSONObject())
            gate = c
        }
        observe(role, session, DiagnosticKind.SESSION, DiagnosticStage.STARTED)
    }

    @Synchronized fun sessionEnded(role: String, session: Long) {
        sessions.remove(role, session)
        val c = capture ?: return
        if (c.collecting && c.role == role && c.session == session) end(c.id, "session_ended")
    }

    @Synchronized fun start(role: String, session: Long?, flags: Set<String>, durationMs: Int, periodMs: Int): JSONObject {
        if (!consent) throw DiagnosticError("PERMISSION_REQUIRED")
        delegatedSession?.let {
            if (role != "receiver" || session != it) throw DiagnosticError("SCOPE_MISMATCH")
            if (!delegatedActive || delegatedOwner?.invoke() == false) throw DiagnosticError("SESSION_ENDED")
        }
        tick()
        capture?.let { throw DiagnosticError(if (it.collecting) "CAPTURE_ACTIVE" else "RETAINED_CAPTURE_EXISTS") }
        if (role !in capabilities || flags.isEmpty() || flags.any { it !in capabilities.getValue(role) })
            throw DiagnosticError("UNSUPPORTED_FLAG")
        if (durationMs !in 1000..180000 || periodMs !in 100..5000) throw DiagnosticError("INVALID_ARGUMENT")
        if (session != null && sessions[role] != session) throw DiagnosticError("SESSION_NOT_FOUND")
        val now = clock()
        val c = Capture(UUID.randomUUID().toString(), role, session, flags.toSet(), now,
            now + durationMs * 1_000_000L, periodMs * 1_000_000L, operations.get())
        capture = c
        lifecycle(c, "CAPTURE_STARTED", JSONObject())
        if (session != null) lifecycle(c, "SESSION_BOUND", JSONObject())
        gate = c
        return limits().put("captureId", c.id).put("startedNs", "$now").put("expiresNs", "${c.expires}")
    }

    @Synchronized fun tick() {
        if (delegatedActive && delegatedOwner?.invoke() == false) delegatedSession?.let { endDelegated(it) }
        val c = capture ?: return
        val now = clock()
        if (c.collecting && now >= c.expires) end(c.id, "lease_expired")
        if (!c.collecting && now - checkNotNull(c.ended) >= 120_000_000_000L) {
            capture = null
            return
        }
        drain(c)
        if (c.collecting && now >= c.sampleAt) {
            flushAggregates(c, now)
            sampleVideo(c, now)
            c.sampleAt = now + c.period
            val drops = dropReasons(c)
            lifecycle(c, "RECORDER_SAMPLE", JSONObject().put("queueDepth", queueDepth(c))
                .put("droppedBeforeRetention", drops.getString("total"))
                .put("evictedAfterRetention", "${c.evicted}")
                .put("inFlightNotTracked", "${c.untrackedOperations.get()}")
                .put("staleVideoUpdates", "${c.staleVideoUpdates.get()}")
                .put("inFlightCount", countInFlight(c))
                .put("dropReasons", drops)
                .put("inFlight", inFlight(c, now)))
        }
    }

    private fun flushAggregates(c: Capture, now: Long, priority: Boolean = false) {
        val start = c.aggregateAt
        for (kind in DiagnosticKind.values()) {
            for (point in DiagnosticPoint.values()) {
                val base = metricIndex(kind, point, 0)
                val values = LongArray(METRIC_FIELDS) { c.metrics.getAndSet(base + it, 0) }
                if (values.all { it == 0L }) continue
                val slowExample = takeSlowExample(c, kind, point)
                val metadata = DiagnosticOperationSummary(point, start, now, values[0], values[1], values[2],
                    values[3], values[4], values[5], values[6], values[7], values[8], values[9], values[10],
                    slowExample?.operation, slowExample?.duration, slowExample?.track, slowExample?.generation,
                    slowExample?.pts?.takeIf { it >= 0 }, slowExample?.endPts?.takeIf { it >= 0 },
                    slowExample?.count?.takeIf { it >= 0 }, slowExample?.bytes?.takeIf { it >= 0 })
                val event = observation(now, kind, DiagnosticStage.SAMPLE, c.role, c.session ?: 0,
                    0, 0, 0, -1, -1, values[0] + values[1] + values[2], values[3], -1,
                    DiagnosticReason.NONE, point, metadata)
                if (!submit(c, event, priority, countDrop = false, finalSummary = priority)) {
                    for (i in values.indices) if (values[i] != 0L) {
                        if (i == 10) updateMax(c, kind, point, values[i])
                        else c.metrics.addAndGet(base + i, values[i])
                    }
                    slowExample?.let {
                        storeSlowExample(c, kind, point, it.operation, it.duration, it.track, it.generation,
                            it.pts, it.endPts, it.count, it.bytes)
                    }
                }
            }
        }
        c.aggregateAt = now
    }

    private fun inFlight(c: Capture, now: Long): JSONArray {
        val events = ArrayList<JSONObject>()
        for (i in 0 until c.inFlightIds.length()) {
            val id = c.inFlightIds.get(i)
            if (id <= 0) continue
            val started = c.inFlightStartedNs.get(i)
            val kind = DiagnosticKind.values().getOrNull(c.inFlightKinds.get(i).toInt()) ?: continue
            val point = DiagnosticPoint.values().getOrNull(c.inFlightPoints.get(i).toInt()) ?: continue
            val event = JSONObject().put("operationId", "$id").put("kind", kind.name)
                .put("point", point.name.lowercase()).put("startedNs", "$started")
                .put("ageNs", "${(now - started).coerceAtLeast(0)}")
                .put("track", c.inFlightTracks.get(i).toInt())
                .put("generation", c.inFlightGenerations.get(i).toInt())
            val pts = c.inFlightPts.get(i)
            val endPts = c.inFlightEndPts.get(i)
            val count = c.inFlightCounts.get(i)
            val bytes = c.inFlightBytes.get(i)
            if (pts >= 0) event.put("ptsUs", "$pts")
            if (endPts >= 0) event.put("endPtsUs", "$endPts")
            if (count >= 0) event.put("count", "$count")
            if (bytes >= 0) event.put("bytes", "$bytes")
            events.add(event)
        }
        return JSONArray(events.sortedBy { it.getString("startedNs").toLong() }.take(4))
    }

    private fun countInFlight(c: Capture): Int {
        var count = 0
        for (i in 0 until c.inFlightIds.length()) if (c.inFlightIds.get(i) > 0) count++
        return count
    }

    private fun queueDepth(c: Capture) = (tail(c) - c.queueHead.get()).coerceAtMost(PENDING.toLong()).toInt()

    @Synchronized fun end(id: String, reason: String = "requested"): JSONObject {
        val c = requireCapture(id)
        if (!c.collecting && clock() - checkNotNull(c.ended) >= 120_000_000_000L) {
            capture = null
            throw DiagnosticError("CAPTURE_NOT_FOUND")
        }
        if (c.collecting) {
            gate = null
            c.collecting = false
            val now = clock()
            val effective = if (now >= c.expires && reason in setOf("requested", "client_disconnected", "lease_expired"))
                "lease_expired" else reason
            c.ended = if (effective == "lease_expired") c.expires else now
            c.reason = effective
            lastEndReason = effective
            drain(c)
            flushAggregates(c, now, priority = true)
            sampleVideo(c, now, priority = true)
            lifecycle(c, "CAPTURE_ENDED", JSONObject().put("reason", effective)
                .put("inFlightCount", countInFlight(c))
                .put("inFlightNotTracked", "${c.untrackedOperations.get()}")
                .put("inFlight", inFlight(c, now)), terminal = true)
            drain(c)
        }
        return JSONObject().put("captureId", id).put("endedNs", "${c.ended}").put("reason", c.reason)
    }

    @Synchronized fun disconnect(reason: String = "client_disconnected") {
        capture?.takeIf { it.collecting }?.let { end(it.id, reason) }
    }
    @Synchronized internal fun disconnectCapture(id: String?, reason: String) {
        capture?.takeIf { it.collecting && it.id == id }?.let { end(it.id, reason) }
    }

    @Synchronized fun revoke() {
        consent = false
        disconnect("local_disabled")
        gate = null
        capture = null
    }

    @Synchronized fun clear(id: String): JSONObject {
        tick()
        val c = requireCapture(id)
        if (c.collecting) throw DiagnosticError("CAPTURE_ACTIVE")
        capture = null
        return JSONObject().put("captureId", id)
    }

    private fun oldest(c: Capture): Long = listOfNotNull(c.routine.peekFirst()?.seq, c.critical.peekFirst()?.seq)
        .minOrNull() ?: 0L

    private fun latest(c: Capture): Long = c.queueHead.get()

    private fun lossWatermarks(c: Capture, publishedSeq: Long, oldestRetainedSeq: Long) =
        JSONObject().put("schema", "loss_watermarks")
        .put("publishedSeq", "$publishedSeq").put("oldestRetainedSeq", "$oldestRetainedSeq")
        .put("latestRetainedSeq", "${listOfNotNull(c.routine.peekLast()?.seq, c.critical.peekLast()?.seq).maxOrNull() ?: 0}")
        .put("admission", dropReasons(c))
        .put("retention", JSONObject().put("routine", c.routineEvictions.json())
            .put("critical", c.criticalEvictions.json()).put("oversized", c.oversizedRecords.json()))

    private fun dropReasons(c: Capture): JSONObject {
        val reasons = JSONObject()
        var total = 0L
        for (drop in Drop.values()) {
            val count = c.dropCounts.get(drop.index)
            val first = c.dropFirstNs.get(drop.index)
            total += count
            reasons.put(DROP_NAMES[drop.index], JSONObject().put("count", "$count")
                .put("firstMonoNs", if (count == 0L || first == Long.MAX_VALUE) JSONObject.NULL else "$first")
                .put("lastMonoNs", if (count == 0L) JSONObject.NULL else "${c.dropLastNs.get(drop.index)}"))
        }
        return reasons.put("total", "$total")
    }

    private data class ResponseSnapshot(
        val oldestSeq: Long,
        val latestSeq: Long,
        val pendingRecords: Int,
        val droppedBeforeRetention: String,
        val evictedAfterRetention: Long,
        val lossWatermarks: JSONObject
    )

    private fun responseSnapshot(c: Capture): ResponseSnapshot {
        val oldestSeq = oldest(c)
        val latestSeq = latest(c)
        val pendingRecords = (tail(c) - latestSeq).toInt()
        val lossWatermarks = lossWatermarks(c, latestSeq, oldestSeq)
        val snapshot = ResponseSnapshot(oldestSeq, latestSeq, pendingRecords,
            lossWatermarks.getJSONObject("admission").getString("total"), c.evicted, lossWatermarks)
        afterResponseSnapshot?.invoke()
        return snapshot
    }

    @Synchronized fun status(): JSONObject {
        tick()
        val c = capture
        val snapshot = c?.let(::responseSnapshot)
        val available = JSONArray()
        sessions.forEach { (role, session) ->
            if (delegatedSession == null || role == "receiver" && session == delegatedSession)
                available.put(JSONObject().put("role", role).put("session", "$session"))
        }
        return JSONObject().put("state", if (c == null) "idle" else if (c.collecting) "collecting" else "frozen")
            .put("captureId", c?.id ?: JSONObject.NULL).put("sessions", available)
            .put("scope", scope() ?: JSONObject.NULL)
            .put("target", c?.let { JSONObject().put("role", it.role).put("session", it.session?.toString() ?: JSONObject.NULL) } ?: JSONObject.NULL)
            .put("flags", JSONArray(c?.flags?.toList() ?: emptyList<String>()))
            .put("expiresNs", c?.expires?.toString() ?: JSONObject.NULL)
            .put("endedNs", c?.ended?.toString() ?: JSONObject.NULL).put("endReason", c?.reason ?: JSONObject.NULL)
            .put("oldestSeq", "${snapshot?.oldestSeq ?: 0}")
            .put("latestSeq", "${snapshot?.latestSeq ?: 0}")
            .put("pendingRecords", snapshot?.pendingRecords ?: 0)
            .put("droppedBeforeRetention", snapshot?.droppedBeforeRetention ?: "0")
            .put("evictedAfterRetention", "${snapshot?.evictedAfterRetention ?: 0}")
            .put("lossWatermarks", snapshot?.lossWatermarks ?: JSONObject.NULL)
    }

    @Synchronized fun fetch(id: String, after: Long, limit: Int): JSONObject {
        tick()
        val c = requireCapture(id)
        val records = JSONArray()
        val routine = c.routine.iterator().asSequence().filter { it.seq > after }.iterator()
        val critical = c.critical.iterator().asSequence().filter { it.seq > after }.iterator()
        var nextRoutine = if (routine.hasNext()) routine.next() else null
        var nextCritical = if (critical.hasNext()) critical.next() else null
        var next = after
        var size = 2048
        while (nextRoutine != null || nextCritical != null) {
            val entry = when {
                nextRoutine == null -> nextCritical
                nextCritical == null -> nextRoutine
                nextRoutine.seq < nextCritical.seq -> nextRoutine
                else -> nextCritical
            } ?: break
            if (records.length() >= limit || size + entry.bytes > 15000) break
            records.put(entry.event)
            size += entry.bytes + 1
            next = entry.seq
            if (entry === nextRoutine) nextRoutine = if (routine.hasNext()) routine.next() else null
            else nextCritical = if (critical.hasNext()) critical.next() else null
        }
        val snapshot = responseSnapshot(c)
        return JSONObject().put("records", records).put("oldestSeq", "${snapshot.oldestSeq}")
            .put("latestSeq", "${snapshot.latestSeq}").put("nextAfterSeq", "$next")
            .put("hasMore", c.routine.any { it.seq > next } || c.critical.any { it.seq > next })
            .put("pendingRecords", snapshot.pendingRecords)
            .put("droppedBeforeRetention", snapshot.droppedBeforeRetention)
            .put("evictedAfterRetention", "${snapshot.evictedAfterRetention}")
            .put("lossWatermarks", snapshot.lossWatermarks)
    }

    private fun requireCapture(id: String): Capture =
        capture?.takeIf { it.id == id } ?: throw DiagnosticError("CAPTURE_NOT_FOUND")

    private fun drain(c: Capture) {
        repeat(PENDING) {
            val head = c.queueHead.get()
            if (head >= tail(c)) return
            val index = (head % PENDING).toInt()
            val event = c.queue.get(index) ?: return
            if (!c.queue.compareAndSet(index, event, null)) return
            c.queueHead.set(head + 1)
            val data = event.controlData ?: JSONObject().put("stage", event.stage.name.lowercase())
                .put("reason", event.reason.name.lowercase()).put("point", event.point.name.lowercase())
            fun number(name: String, value: Long) { if (value >= 0) data.put(name, "$value") }
            if (event.controlKind == null) {
                number("operationId", event.operation.takeIf { it > 0 } ?: -1)
                number("ptsUs", event.pts); number("endPtsUs", event.endPts); number("count", event.count)
                number("bytes", event.bytes); number("cutoffUs", event.cutoff); number("durationNs", event.durationNs)
                event.metadata?.let { data.put("metrics", it.json()) }
            }
            val eventKind = event.controlKind ?: event.kind.name
            val json = JSONObject().put("captureId", c.id).put("seq", "${event.seq}")
                .put("monoNs", "${event.ns}").put("kind", eventKind).put("data", data)
            val session = if (event.controlKind != null) c.session else event.session
            if (session != null) json.put("role", if (event.controlKind != null) c.role else event.role)
                .put("session", "$session")
            if (event.track > 0) json.put("track", event.track).put("generation", event.generation)
            retain(c, event.seq, json, isCritical(event.kind, event.stage) || event.controlKind != null)
        }
    }

    private fun retain(c: Capture, seq: Long, event: JSONObject, priority: Boolean) {
        val size = event.toString().toByteArray(Charsets.UTF_8).size
        if (size > MAX_RECORD_BYTES) {
            c.oversizedRecords.record(seq)
            drop(c, Drop.OVERSIZED_RECORD, clock())
            return
        }
        if (priority) {
            while (c.critical.size >= CRITICAL_RECORDS || c.criticalBytes + size > CRITICAL_BYTES) {
                val evicted = c.critical.removeFirst()
                c.criticalBytes -= evicted.bytes
                c.criticalEvictions.record(evicted.seq)
                c.evicted++
            }
            c.critical.addLast(Retained(seq, event, size))
            c.criticalBytes += size
        } else {
            while (c.routine.size >= ROUTINE_RECORDS || c.routineBytes + size > ROUTINE_BYTES) {
                val evicted = c.routine.removeFirst()
                c.routineBytes -= evicted.bytes
                c.routineEvictions.record(evicted.seq)
                c.evicted++
            }
            c.routine.addLast(Retained(seq, event, size))
            c.routineBytes += size
        }
    }
}

/** Process-wide session identities remain available even before local consent. */
object RemoteDiagnostics {
    val recorder = DiagnosticRecorder().apply { revoke() }
}

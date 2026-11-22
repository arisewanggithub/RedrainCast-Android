package dev.redrain.cast.diagnostics

import org.json.JSONObject
import org.screencopy.receiver.media.VideoSchedulingClock
import java.util.ArrayDeque

/**
 * PTS alone is not an occurrence identifier. Keep a PTS group until a later
 * decoded PTS seals it, and reject groups with duplicate outputs or callbacks.
 */
internal class DiagnosticVideoTiming(val generation: Int) {
    companion object { const val LIMIT = 128 }

    private class Group(val pts: Long) {
        var outputs = 0
        var releases = 0
        var pending = false
        var success = false
        var deadline: Long? = null
        var supplied = 0L
        var mapping = 0L
        var callbacks = 0
        var callbackAt = 0L
        var observedAt = 0L
        var callbackMapping = 0L
        var callbackSequence = 0L
        var availabilityNs: Long? = null
        var availabilityMapping = 0L
        var sealed = false
        var unsafe = false
        var scheduleSequence = 0L
        var clock: VideoSchedulingClock? = null
        var scheduleAtNs = 0L
        var mappingStable = true
        var schedulingOccurrence: Occurrence? = null
        var callbackOccurrence: Occurrence? = null
    }

    private class Range {
        var count = 0L
        var min = Long.MAX_VALUE
        var max = Long.MIN_VALUE
        fun add(value: Long) { count++; min = minOf(min, value); max = maxOf(max, value) }
        fun snapshot() = longArrayOf(count, min, max)
        fun copy() = Range().also { it.count = count; it.min = min; it.max = max }
    }

    private val groups = ArrayDeque<Group>()
    private val availability = Range()
    private val clamp = Range()
    private var scheduleSequence = 0L

    private data class ScheduleExample(val pts: Long, val deadline: Long?, val supplied: Long,
                                       val scheduleAt: Long, val mapping: Long, val mappingStable: Boolean,
                                       val clock: VideoSchedulingClock?)
    private data class PairExample(val delta: Long, val previous: ScheduleExample, val current: ScheduleExample)
    private class Occurrence(val pts: Long, val deadline: Long?, val atNs: Long, val mapping: Long,
                             val sequence: Long, val scheduling: ScheduleExample? = null) {
        var valid = false
        var retired = false
    }
    private class Pair(val previous: Occurrence, val current: Occurrence, val callback: Boolean)
    private val pairs = ArrayDeque<Pair>()
    private var previousCallback: Occurrence? = null
    private var previousSchedule: Occurrence? = null
    private fun example(g: Group) = ScheduleExample(g.pts, g.deadline, g.supplied, g.scheduleAtNs,
        g.mapping, g.mappingStable, g.clock)
    private var firstPts: Long? = null
    private var evictedThrough: Long? = null
    private var closed = false
    private var missing = 0L
    private var unmatched = 0L
    private var evicted = 0L
    private var failed = 0L
    private var noClock = 0L
    private var mappingChanged = 0L
    private var futureCallbacks = 0L
    private var callbackSequence = 0L
    private var incomplete = 0L
    private var outsideWindowCallbacks = 0L

    private class Correlations {
        var matched = 0L
        var unmatched = 0L
        var ambiguous = 0L
        var mappingChanged = 0L
        var ranges = Array(10) { Range() }
        var lastScheduleSequence = 0L
        var latestSchedule: ScheduleExample? = null
        var minimum: PairExample? = null
        var maximum: PairExample? = null
        var worstMagnitude = -1L
        var exemplar: Array<Long?>? = null
        var exemplarClock: ScheduleExample? = null
        fun copy() = Correlations().also {
            it.matched = matched; it.unmatched = unmatched; it.ambiguous = ambiguous
            it.mappingChanged = mappingChanged; it.ranges = Array(ranges.size) { n -> ranges[n].copy() }
            it.latestSchedule = latestSchedule; it.lastScheduleSequence = lastScheduleSequence
            it.minimum = minimum; it.maximum = maximum; it.worstMagnitude = worstMagnitude
            it.exemplar = exemplar; it.exemplarClock = exemplarClock
        }
    }
    private var committed = Correlations()

    @Synchronized fun decoded(pts: Long, delayUs: Long?, mappingStable: Boolean = true,
                              hasAvailability: Boolean = true, mapping: Long = 0) {
        if (closed) return
        val first = firstPts ?: pts.also { firstPts = it }
        groups.forEach {
            if (it.pts < pts) {
                it.sealed = true
                refresh(it)
            }
        }
        val group = find(pts) ?: add(pts).also { it.unsafe = pts <= first }
        group.outputs++
        refresh(group)
        if (!hasAvailability) return
        if (!mappingStable) mappingChanged++
        else if (delayUs == null) noClock++
        else difference(0, delayUs)?.let(::nanos)?.let {
            group.availabilityNs = it
            group.availabilityMapping = mapping
            availability.add(it)
        }
    }

    @Synchronized fun begin(pts: Long, deadline: Long?, supplied: Long, mapping: Long,
                            mappingStable: Boolean = true, clock: VideoSchedulingClock? = null,
                            scheduleAtNs: Long = supplied): Any? {
        if (closed) return null
        val group = find(pts) ?: add(pts).also { it.unsafe = true }
        group.releases++
        group.pending = true
        group.deadline = if (mappingStable) deadline else null
        if (!mappingStable) mappingChanged++
        else if (deadline == null) noClock++
        group.supplied = supplied
        group.mapping = mapping
        group.mappingStable = mappingStable
        group.clock = clock
        group.scheduleAtNs = scheduleAtNs
        group.scheduleSequence = ++scheduleSequence
        if (group.releases == 1) {
            val occurrence = Occurrence(pts, group.deadline, scheduleAtNs, mapping,
                group.scheduleSequence, example(group))
            group.schedulingOccurrence = occurrence
            link(previousSchedule, occurrence, false)
            previousSchedule = occurrence
        } else previousSchedule = null
        refresh(group)
        return group
    }

    @Synchronized fun finish(token: Any?, success: Boolean) {
        val group = token as? Group ?: return
        if (closed || !groups.contains(group)) return
        group.pending = false
        if (success) {
            group.success = true
            group.deadline?.let { difference(group.supplied, it)?.let(clamp::add) }
        } else {
            failed++
            group.success = false
        }
        refresh(group)
    }

    @Synchronized fun callback(pts: Long, supplied: Long, observed: Long, mapping: Long) {
        if (closed) return
        callbackSequence++
        if (observed < supplied) futureCallbacks++
        val group = find(pts)
        if (group == null) {
            unmatched++
            if (evictedThrough?.let { pts <= it } == true) outsideWindowCallbacks++
            previousCallback = null
            return
        }
        if (!group.pending && !group.success) {
            group.unsafe = true
            unmatched++
            refresh(group)
            previousCallback = null
            return
        }
        group.callbacks++
        group.callbackAt = supplied
        group.observedAt = observed
        group.callbackMapping = mapping
        group.callbackSequence = callbackSequence
        if (group.callbacks == 1) {
            val occurrence = Occurrence(pts, group.deadline, supplied, group.mapping, callbackSequence)
            group.callbackOccurrence = occurrence
            link(previousCallback, occurrence, true)
            previousCallback = occurrence
        } else previousCallback = null
        refresh(group)
    }

    private fun refresh(g: Group) {
        val valid = g.sealed && !g.pending && g.success && !g.unsafe && g.outputs == 1 && g.releases == 1
        g.schedulingOccurrence?.valid = valid
        g.callbackOccurrence?.valid = valid && g.callbacks == 1 && g.deadline != null &&
            g.mapping == g.callbackMapping && difference(g.callbackAt, checkNotNull(g.deadline)) != null &&
            difference(g.observedAt, g.callbackAt) != null
    }

    private fun link(previous: Occurrence?, current: Occurrence, callback: Boolean) {
        if (previous != null && current.sequence == previous.sequence + 1 && current.pts > previous.pts &&
            current.mapping == previous.mapping && current.deadline != null && previous.deadline != null)
            pairs.addLast(Pair(previous, current, callback))
    }

    private fun accumulatePair(pair: Pair, totals: Correlations) {
        val p = pair.previous
        val g = pair.current
        if (!p.valid || !g.valid) return
        val ptsNs = difference(g.pts, p.pts)?.let(::nanos) ?: return
        val deadlineNs = difference(checkNotNull(g.deadline), checkNotNull(p.deadline)) ?: return
        val r = totals.ranges
        if (pair.callback) {
            val callbackNs = difference(g.atNs, p.atNs) ?: return
            r[2].add(ptsNs); r[3].add(deadlineNs); r[4].add(callbackNs)
            difference(callbackNs, ptsNs)?.let(r[5]::add)
            difference(callbackNs, deadlineNs)?.let(r[6]::add)
        } else difference(deadlineNs, ptsNs)?.let { delta ->
            r[7].add(delta)
            val example = PairExample(delta, checkNotNull(p.scheduling), checkNotNull(g.scheduling))
            if (totals.minimum?.let { delta < it.delta } != false) totals.minimum = example
            if (totals.maximum?.let { delta > it.delta } != false) totals.maximum = example
        }
    }

    private fun find(pts: Long): Group? = groups.firstOrNull { it.pts == pts }
    private fun add(pts: Long): Group {
        if (groups.size == LIMIT) {
            val old = groups.removeFirst()
            evicted++
            if (old.pending) incomplete++
            else {
                if (old.sealed) {
                    accumulateSchedule(old, committed)
                    accumulateCallback(old, committed)
                }
                if (old.success && old.callbacks == 0) missing++
                else if (!old.sealed && (old.success || old.callbacks > 0)) incomplete++
            }
            evictedThrough = maxOf(evictedThrough ?: old.pts, old.pts)
            old.schedulingOccurrence?.retired = true
            old.callbackOccurrence?.retired = true
            // An adjacency stays reversible until both endpoints leave the duplicate-detection window.
            val iterator = pairs.iterator()
            while (iterator.hasNext()) {
                val pair = iterator.next()
                if (pair.previous.retired && pair.current.retired) {
                    accumulatePair(pair, committed)
                    iterator.remove()
                }
            }
        }
        return Group(pts).also {
            if (evictedThrough?.let { watermark -> pts <= watermark } == true) it.unsafe = true
            groups.addLast(it)
        }
    }

    private fun accumulateSchedule(g: Group, totals: Correlations) {
        val r = totals.ranges
        val newer = g.scheduleSequence > totals.lastScheduleSequence
        if (newer) totals.lastScheduleSequence = g.scheduleSequence
        if (g.success && !g.unsafe && g.outputs == 1 && g.releases == 1) {
            if (newer) totals.latestSchedule = example(g)
            g.clock?.let { c ->
                difference(g.scheduleAtNs, c.readAtNs)?.let(r[9]::add)
                c.audioObservationAtNs?.let { difference(g.scheduleAtNs, it)?.let(r[8]::add) }
            }
        }
    }

    private fun accumulateCallback(g: Group, totals: Correlations) {
        val r = totals.ranges
        when {
            g.unsafe || g.outputs != 1 || g.releases > 1 || g.callbacks > 1 -> {
                if (g.releases > 0 || g.callbacks > 0) {
                    totals.ambiguous++
                }
            }
            !g.success -> {
                if (g.callbacks > 0) totals.unmatched++
            }
            g.callbacks == 0 -> Unit
            g.deadline == null -> Unit
            g.mapping != g.callbackMapping -> { totals.mappingChanged++ }
            else -> {
                val deadline = checkNotNull(g.deadline)
                val error = difference(g.callbackAt, deadline)
                val observation = difference(g.observedAt, g.callbackAt)
                if (error == null || observation == null) return
                totals.matched++
                r[0].add(error)
                r[1].add(observation)
                val magnitude = if (error == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(error)
                if (magnitude > totals.worstMagnitude) {
                    totals.worstMagnitude = magnitude
                    totals.exemplar = arrayOf(g.pts, deadline, g.supplied, g.callbackAt, g.observedAt,
                        g.availabilityNs.takeIf { g.availabilityMapping == g.mapping })
                    totals.exemplarClock = example(g)
                }
            }
        }
    }

    private fun correlations(): Correlations {
        val totals = committed.copy()
        val eligible = groups.filter { it.sealed && !it.pending }
        eligible.forEach { accumulateSchedule(it, totals); accumulateCallback(it, totals) }
        pairs.forEach { accumulatePair(it, totals) }
        return totals
    }

    @Synchronized fun snapshot(final: Boolean): DiagnosticMetadata {
        if (final && !closed) {
            committed = correlations()
            // The final PTS is deliberately not sealed: more equal-PTS outputs may exist.
            groups.forEach {
                if (it.pending) incomplete++
                else if (it.success && it.callbacks == 0) missing++
                else if (it.sealed) Unit
                else if (it.callbacks > 0 || it.releases > 0) {
                    if (it.unsafe || it.outputs != 1 || it.releases > 1 || it.callbacks > 1) committed.ambiguous++
                    else if (!it.success && it.callbacks > 0) committed.unmatched++
                    else incomplete++
                }
            }
            groups.clear()
            pairs.clear()
            previousCallback = null
            previousSchedule = null
            closed = true
        }
        val totals = correlations()
        val pending = groups.count { it.pending || it.success && it.callbacks == 0 }
        val counts = longArrayOf(totals.matched, missing, pending.toLong(), unmatched + totals.unmatched,
            totals.ambiguous, evicted, incomplete, failed, noClock, mappingChanged + totals.mappingChanged,
            futureCallbacks, outsideWindowCallbacks)
        val ranges = arrayOf(availability.snapshot(), clamp.snapshot()) + totals.ranges.map { it.snapshot() }
        return Snapshot(counts, ranges, totals.exemplar?.clone(), totals.exemplarClock, totals.latestSchedule,
            totals.minimum, totals.maximum)
    }

    private class Snapshot(private val counts: LongArray, private val ranges: Array<LongArray>,
                           private val exemplar: Array<Long?>?, private val exemplarClock: ScheduleExample?,
                           private val latestSchedule: ScheduleExample?, private val minimum: PairExample?,
                           private val maximum: PairExample?) : DiagnosticMetadata {
        override fun json(): JSONObject {
            val json = JSONObject().put("schema", "video_frame_timing").put("capacity", LIMIT)
            val names = arrayOf("matched", "missing", "pending", "unmatched", "ambiguous", "evicted",
                "incomplete", "releaseFailed", "clockUnavailable", "mappingChanged", "futureCallbacks",
                "outsideWindowCallbacks")
            names.forEachIndexed { index, name -> json.put(name, "${counts[index]}") }
            val rangeNames = arrayOf("decodedMinusDeadline", "suppliedMinusDeadline", "callbackMinusDeadline",
                "observedMinusCallback", "ptsCadence", "deadlineCadence", "callbackCadence",
                "callbackCadenceMinusPts", "callbackCadenceMinusDeadline", "deadlineCadenceMinusPts",
                "audioObservationAge", "clockReadToSchedule")
            rangeNames.forEachIndexed { index, name ->
                val range = ranges[index]
                json.put(name, JSONObject().put("count", "${range[0]}")
                    .put("minNs", if (range[0] == 0L) JSONObject.NULL else "${range[1]}")
                    .put("maxNs", if (range[0] == 0L) JSONObject.NULL else "${range[2]}"))
            }
            val example = exemplar?.let { values ->
                JSONObject().apply {
                    arrayOf("ptsUs", "deadlineNs", "releaseNs", "callbackNs", "observedNs",
                        "decodedMinusDeadlineNs").forEachIndexed { index, name ->
                        put(name, values[index]?.toString() ?: JSONObject.NULL)
                    }
                }
            }
            json.put("worstCallbackErrorExample", example ?: JSONObject.NULL)
            example?.put("scheduling", schedulingJson(exemplarClock))
            json.put("latestSchedulingExample", schedulingJson(latestSchedule))
            json.put("minimumDeadlineCadenceExample", pairJson(minimum))
            json.put("maximumDeadlineCadenceExample", pairJson(maximum))
            return json
        }

        private fun pairJson(pair: PairExample?): Any = pair?.let {
            JSONObject().put("deadlineCadenceMinusPtsNs", "${it.delta}")
                .put("previous", schedulingJson(it.previous)).put("current", schedulingJson(it.current))
        } ?: JSONObject.NULL

        private fun schedulingJson(example: ScheduleExample?): Any = example?.let { e ->
            JSONObject().apply {
                put("mappingStable", e.mappingStable)
                fun field(name: String, value: Long?) { put(name, value?.toString() ?: JSONObject.NULL) }
                field("ptsUs", e.pts)
                field("deadlineNs", e.deadline)
                field("releaseNs", e.supplied)
                field("scheduleAtNs", e.scheduleAt)
                field("mappingRevision", e.mapping)
                put("clock", e.clock?.let { c ->
                    JSONObject().apply {
                        fun value(name: String, number: Long?) { put(name, number?.toString() ?: JSONObject.NULL) }
                        put("mode", c.mode)
                        value("readAtNs", c.readAtNs)
                        value("mappingRevision", c.mappingRevision)
                        value("delayUs", c.delayUs)
                        value("positionUs", c.positionUs)
                        value("sourcePositionUs", c.sourcePositionUs)
                        value("audioObservationSequence", c.audioObservationSequence)
                        value("audioObservationAtNs", c.audioObservationAtNs)
                        value("audioObservationAgeNs", c.audioObservationAtNs?.let {
                            try { Math.subtractExact(e.scheduleAt, it) } catch (_: ArithmeticException) { null }
                        })
                        put("audioOutputSource", c.audioOutputSource?.name ?: JSONObject.NULL)
                        value("audioTimestampAgeAtObservationUs", c.audioTimestampAgeUs)
                        value("observedAppliedOffsetUs", c.observedAppliedOffsetUs)
                        put("recoveringAudio", c.recoveringAudio)
                        put("lastPacketCorrection", c.lastPacketCorrection?.let {
                            JSONObject().put("sequence", "${it.sequence}").put("beforeOffsetUs", "${it.beforeOffsetUs}")
                                .put("afterOffsetUs", "${it.afterOffsetUs}").put("deltaUs", "${it.deltaUs}")
                                .put("appliedOffsetAtPacketUs", "${it.appliedOffsetUs}")
                        } ?: JSONObject.NULL)
                    }
                } ?: JSONObject.NULL)
            }
        } ?: JSONObject.NULL
    }

    private fun difference(a: Long, b: Long): Long? =
        try { Math.subtractExact(a, b) } catch (_: ArithmeticException) { null }
    private fun nanos(us: Long): Long? =
        try { Math.multiplyExact(us, 1000L) } catch (_: ArithmeticException) { null }
}

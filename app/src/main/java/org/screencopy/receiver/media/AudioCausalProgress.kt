package org.screencopy.receiver.media

import dev.redrain.cast.diagnostics.CausalEvidence

/** Worker-owned progress; the pressure path reads only copies already in the session cache. */
internal class AudioCausalProgress(private val publish: (CausalEvidence.Stage, LongArray, Long) -> Unit) {
    private var phase = 0L
    private var phaseAt = -1L
    private var stepBegin = -1L
    private var stepEnd = -1L
    private var dequeueAt = -1L
    private var dequeuePts = -1L
    private var first = longArrayOf(-1, -1, -1, -1, -1)
    private var maximum = first
    private var slow = 0L

    fun phase(value: Long, now: Long) {
        phase = value
        phaseAt = now
        worker(now)
    }
    fun begin(now: Long) { stepBegin = now; phase(0, now) }
    fun end(now: Long) { stepEnd = now; worker(now) }
    fun dequeue(now: Long, pts: Long) { dequeueAt = now; dequeuePts = pts; worker(now) }
    fun interval(begin: Long, end: Long, cpu: Long, kind: Long = 0) {
        val duration = end - begin
        if (duration < 5_000_000) return
        val value = longArrayOf(begin, end, duration, cpu, kind)
        if (slow++ == 0L) first = value
        if (duration > maximum[2]) maximum = value
        worker(end)
    }
    private fun worker(now: Long) = publish(CausalEvidence.Stage.WORKER,
        longArrayOf(phase, phaseAt, stepBegin, stepEnd, dequeueAt, dequeuePts) +
            first + maximum + slow, now)
}

/** Admission and dequeue callers already share TrackWorker's queue monitor. */
internal class AudioInputProgress(private val publish: (LongArray, Long) -> Unit) {
    private var arrivalAt = -1L
    private var arrivalPts = -1L
    private var deltaNs = -1L
    private var deltaPts = -1L
    private var expectedUs = -1L
    private var firstAt = -1L
    private var firstStep = -1L
    private var maxStep = -1L
    private var maxGap = -1L
    private var bursts = 0L
    private var arrivals = 0L
    private var admitAt = -1L
    private var admitPts = -1L
    private var dequeueAt = -1L
    private var dequeuePts = -1L
    fun arrival(now: Long, pts: Long, durationUs: Long) {
        if (arrivalAt >= 0) {
            deltaNs = now - arrivalAt
            deltaPts = pts - arrivalPts
            maxStep = maxOf(maxStep, deltaPts)
            maxGap = maxOf(maxGap, deltaNs)
            if (expectedUs > 0 && (deltaPts < 0 || deltaPts > expectedUs + 1) && firstAt < 0) {
                firstAt = now
                firstStep = deltaPts
            }
            if (durationUs > 0 && deltaNs in 0 until durationUs * 500) bursts++
        }
        arrivalAt = now; arrivalPts = pts; expectedUs = durationUs; arrivals++
        snapshot(now)
    }
    fun admitted(now: Long, pts: Long) { admitAt = now; admitPts = pts; snapshot(now) }
    fun dequeued(now: Long, pts: Long) { dequeueAt = now; dequeuePts = pts; snapshot(now) }
    private fun snapshot(now: Long) = publish(longArrayOf(arrivalAt, arrivalPts, deltaNs, deltaPts,
        expectedUs, firstAt, firstStep, maxStep, maxGap, bursts, arrivals, admitAt, admitPts,
        dequeueAt, dequeuePts), now)
}

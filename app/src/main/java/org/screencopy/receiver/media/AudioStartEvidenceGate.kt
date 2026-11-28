package org.screencopy.receiver.media

/** Audio-worker-owned transition gate; repeat omissions accompany the next published sample. */
internal class AudioStartEvidenceGate {
    private var at = Long.MIN_VALUE
    private var generation = -1
    private var reason = -1
    private var mode = -1
    private var flags = Long.MIN_VALUE
    private var revision = Long.MIN_VALUE
    private var target = Long.MIN_VALUE
    private var capture: String? = null
    private var omitted = 0L

    fun reset() { at = Long.MIN_VALUE; generation = -1; capture = null; omitted = 0 }

    fun take(generation: Int, decision: AudioStartDecision, now: Long, capture: String? = null): Long? {
        val previousOmissions = if (this.capture == capture) omitted else 0
        val changed = this.generation != generation || reason != decision.reason ||
            mode != decision.mode || flags != decision.flags ||
            revision != decision.revision || target != decision.targetUs || this.capture != capture
        if (!changed && at != Long.MIN_VALUE && now >= at && now - at < 250_000_000L) {
            if (omitted < Long.MAX_VALUE) omitted++
            return null
        }
        this.generation = generation
        reason = decision.reason
        mode = decision.mode
        flags = decision.flags
        revision = decision.revision
        target = decision.targetUs
        this.capture = capture
        at = now
        omitted = 0
        return previousOmissions
    }
}

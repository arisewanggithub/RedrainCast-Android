package org.screencopy.receiver.media

enum class AudioBufferState { BUFFERED, EMPTY, DECODING }

data class AudioUnderrunDiagnostic(
    val currentCount: Int,
    val delta: Long,
    val sinceEvent: Long,
    val total: Long,
    val counterReset: Boolean,
    val state: AudioBufferState,
    val emptyUs: Long,
    val lastEmptyUs: Long,
    val recoveries: Long,
    val queuedPackets: Int,
    val heldPacket: Boolean,
    val playbackActive: Boolean?,
    val timing: AudioOutputTiming
) {
    fun logMessage() = "event=AUDIO_UNDERRUN_RECOVERY underrun_current=$currentCount underrun_delta=$delta" +
        " underrun_since_event=$sinceEvent underrun_total=$total counter_reset=$counterReset buffer_state=$state" +
        " client_empty_us=$emptyUs last_client_empty_us=$lastEmptyUs client_recoveries=$recoveries" +
        " queued_packets=$queuedPackets held_packet=$heldPacket" +
        " playback_state=${when (playbackActive) { true -> "PLAYING"; false -> "NOT_PLAYING"; null -> "UNKNOWN" }}" +
        " ${timing.logMessage()}"
}

internal class AudioUnderrunMonitor {
    private var previous = 0
    private var total = 0L
    private var pendingDelta = 0L
    private var pendingReset = false
    private var emptySinceNs: Long? = null
    private var lastEmptyUs = 0L
    private var recoveries = 0L
    private var pendingRecovery = false
    private var lastEventNs: Long? = null

    fun start(current: Int) { previous = current }

    fun observe(now: Long, current: Int, timing: AudioOutputTiming, queued: Int, held: Boolean,
        playbackActive: Boolean?): AudioUnderrunDiagnostic? {
        val delta = (current.toLong() - previous).coerceAtLeast(0)
        pendingReset = pendingReset || current < previous
        previous = current
        total += delta
        pendingDelta += delta
        val empty = timing.submittedFrames == timing.consumedFrames
        if (empty) {
            if (emptySinceNs == null) emptySinceNs = now
        } else {
            emptySinceNs?.let {
                val duration = (now - it).coerceAtLeast(0) / 1000
                if (duration > 0) { lastEmptyUs = duration; recoveries++; pendingRecovery = true }
            }
            emptySinceNs = null
        }
        val emptyUs = emptySinceNs?.let { (now - it).coerceAtLeast(0) / 1000 } ?: 0
        if (pendingDelta == 0L && !pendingReset && !pendingRecovery && emptyUs < 1_000_000) return null
        if (lastEventNs?.let { now - it < 1_000_000_000L } == true) return null
        val state = when {
            !empty -> AudioBufferState.BUFFERED
            queued > 0 || held -> AudioBufferState.DECODING
            else -> AudioBufferState.EMPTY
        }
        return AudioUnderrunDiagnostic(current, delta, pendingDelta, total, pendingReset, state, emptyUs,
            lastEmptyUs, recoveries, queued, held, playbackActive, timing).also {
            pendingDelta = 0
            pendingReset = false
            pendingRecovery = false
            lastEventNs = now
        }
    }
}

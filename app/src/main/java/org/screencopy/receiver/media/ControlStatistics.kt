package org.screencopy.receiver.media

data class ControlStatisticsSnapshot(val rttMs: Long?, val lastValidControlAgeMs: Long)

/** Observes the existing heartbeat only; peer PING never starts an RTT measurement. */
class ControlStatistics(private var validAtMs: Long) {
    private data class Observation(val rttMs: Long?, val validAtMs: Long)
    @Volatile private var latest = Observation(null, validAtMs)
    private var pending: Pair<Int, Long>? = null
    private var rtt: Long? = null
    @Synchronized fun sentPing(nonce: Int, nowMs: Long) { pending = nonce to nowMs }
    @Synchronized fun validControl(nowMs: Long) {
        validAtMs = nowMs
        latest = Observation(rtt, validAtMs)
    }
    @Synchronized fun pong(nonce: Int, nowMs: Long) {
        val sent = pending
        if (sent != null && sent.first == nonce && nowMs >= sent.second) {
            rtt = nowMs - sent.second
            pending = null
            latest = Observation(rtt, validAtMs)
        }
    }
    fun snapshot(nowMs: Long): ControlStatisticsSnapshot {
        val observed = latest
        return ControlStatisticsSnapshot(observed.rttMs, (nowMs - observed.validAtMs).coerceAtLeast(0))
    }
}

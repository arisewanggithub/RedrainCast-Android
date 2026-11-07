package org.screencopy.receiver.media

data class ControlStatisticsSnapshot(val rttMs: Long?, val lastValidControlAgeMs: Long)

/** Observes the existing heartbeat only; peer PING never starts an RTT measurement. */
class ControlStatistics(private var validAtMs: Long) {
    private var pending: Pair<Int, Long>? = null
    private var rtt: Long? = null
    @Synchronized fun sentPing(nonce: Int, nowMs: Long) { pending = nonce to nowMs }
    @Synchronized fun validControl(nowMs: Long) { validAtMs = nowMs }
    @Synchronized fun pong(nonce: Int, nowMs: Long) {
        val sent = pending
        if (sent != null && sent.first == nonce && nowMs >= sent.second) {
            rtt = nowMs - sent.second
            pending = null
        }
    }
    @Synchronized fun snapshot(nowMs: Long) =
        ControlStatisticsSnapshot(rtt, (nowMs - validAtMs).coerceAtLeast(0))
}

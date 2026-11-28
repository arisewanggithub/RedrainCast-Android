package org.screencopy.receiver.media

/** Diagnostic clock, not a playback observation. Its virtual FIFO is capped at 100 ms. */
internal class VirtualVideoSyncClock(private val firstPtsUs: Long, nowNs: Long) {
    private var atNs = nowNs
    private var submittedUs = 0L
    private var consumedUs = 0L
    private var remainderNs = 0L

    fun submit(sampleUs: Long, nowNs: Long) {
        require(sampleUs >= submittedUs)
        advance(nowNs)
        submittedUs = sampleUs
        consumedUs = maxOf(consumedUs, submittedUs - 100_000)
    }

    fun position(nowNs: Long): Long {
        advance(nowNs)
        return Math.addExact(firstPtsUs, consumedUs)
    }

    private fun advance(nowNs: Long) {
        val elapsed = (nowNs - atNs).coerceAtLeast(0)
        atNs = maxOf(atNs, nowNs)
        if (consumedUs == submittedUs) {
            remainderNs = 0
            return
        }
        val wholeUs = elapsed / 1000
        val fraction = elapsed % 1000 + remainderNs
        val elapsedUs = wholeUs + fraction / 1000
        consumedUs += minOf(submittedUs - consumedUs, elapsedUs)
        remainderNs = if (consumedUs == submittedUs) 0 else fraction % 1000
    }
}

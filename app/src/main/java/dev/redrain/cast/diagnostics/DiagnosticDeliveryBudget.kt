package dev.redrain.cast.diagnostics

/** Bounded rolling-window admission for diagnostic egress; never waits for a reader. */
internal class DiagnosticDeliveryBudget {
    companion object {
        const val RECORDS_PER_SECOND = 256
        const val BYTES_PER_SECOND = 128 * 1024
        private const val WINDOW_NS = 1_000_000_000L
        private const val SLOTS = 256
    }

    private val times = LongArray(SLOTS)
    private val recordCounts = IntArray(SLOTS)
    private val byteCounts = IntArray(SLOTS)
    private var head = 0
    private var size = 0
    private var records = 0
    private var bytes = 0
    private var lastTime: Long? = null

    @Synchronized fun tryAcquire(now: Long, recordCount: Int, byteCount: Int,
                                 reserveBytes: Int = 0, reserveSlots: Int = 0): Boolean {
        require(recordCount in 0..RECORDS_PER_SECOND && byteCount in 1..BYTES_PER_SECOND)
        require(reserveBytes in 0..BYTES_PER_SECOND && reserveSlots in 0..SLOTS)
        val previous = lastTime
        require(previous == null || now >= previous)
        lastTime = now
        while (size > 0) {
            val elapsed = try { Math.subtractExact(now, times[head]) }
                catch (_: ArithmeticException) { Long.MAX_VALUE }
            if (elapsed < WINDOW_NS) break
            records -= recordCounts[head]
            bytes -= byteCounts[head]
            head = (head + 1) % SLOTS
            size--
        }
        if (size + 1 + reserveSlots > SLOTS || records + recordCount > RECORDS_PER_SECOND ||
            bytes + byteCount + reserveBytes > BYTES_PER_SECOND) return false
        val index = (head + size) % SLOTS
        times[index] = now
        recordCounts[index] = recordCount
        byteCounts[index] = byteCount
        size++
        records += recordCount
        bytes += byteCount
        return true
    }
}

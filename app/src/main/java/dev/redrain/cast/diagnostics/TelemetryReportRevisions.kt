package dev.redrain.cast.diagnostics

import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** At most one serial write and one replaceable pending revision per cast. */
internal class TelemetryReportRevisions(
    private val write: (ByteArray) -> Unit,
    private val failed: (Exception) -> Unit,
    private val executor: Executor = worker
) {
    enum class Revision { INITIAL, WINDOW_CLOSED, FINAL }
    private data class Pending(val revision: Revision, val snapshot: TelemetryHistory.Snapshot)
    private var latest = -1
    private var pending: Pending? = null
    private var scheduled = false

    companion object {
        private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(8),
            { action -> Thread(action, "telemetry-report-storage").apply { isDaemon = true } })
    }

    @Synchronized
    fun submit(revision: Revision, snapshot: TelemetryHistory.Snapshot): Boolean {
        if (revision.ordinal <= latest) return false
        val previous = latest
        latest = revision.ordinal
        pending = Pending(revision, snapshot)
        if (!scheduled) {
            scheduled = true
            try {
                executor.execute(::drain)
            } catch (error: RejectedExecutionException) {
                scheduled = false
                pending = null
                latest = previous
                failed(error)
                return false
            }
        }
        return true
    }

    private fun drain() {
        while (true) {
            val item = synchronized(this) {
                val next = pending
                pending = null
                if (next == null) scheduled = false
                next
            } ?: return
            try {
                write(TelemetryReport.encode(item.snapshot))
            } catch (error: IOException) {
                failed(error)
            } catch (error: RuntimeException) {
                failed(error)
            }
        }
    }
}

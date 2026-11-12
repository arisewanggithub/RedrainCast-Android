package dev.redrain.cast

import android.os.Process
import android.util.Log

internal enum class MediaThreadRole { AUDIO, VIDEO }

internal object MediaThreadDiagnostic {
    fun report(role: MediaThreadRole) = report(role,
        { Process.getThreadPriority(Process.myTid()) },
        { Log.i("RedrainCastMedia", it) }, { Log.w("RedrainCastMedia", it) })

    internal fun report(
        role: MediaThreadRole,
        getPriority: () -> Int,
        report: (String) -> Unit,
        warn: (String) -> Unit
    ) {
        val fields = "role=$role thread=${Thread.currentThread().name}"
        try {
            val actual = getPriority()
            report("event=MEDIA_THREAD_PRIORITY $fields actual_priority=$actual policy=unchanged")
        } catch (_: SecurityException) {
            warn("event=MEDIA_THREAD_PRIORITY_UNAVAILABLE $fields exception=SecurityException")
        } catch (_: IllegalArgumentException) {
            warn("event=MEDIA_THREAD_PRIORITY_UNAVAILABLE $fields exception=IllegalArgumentException")
        }
    }
}

package dev.redrain.cast

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** One metadata request across configuration recreation; it never retains an Activity. */
internal class LaunchUpdateCheck(
    private val source: UpdateSource,
    private val installedVersion: String,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
) {
    private val main = Handler(Looper.getMainLooper())
    private var started = false
    private var closed = false
    private var listener: (() -> Unit)? = null
    var available: ReleaseUpdate? = null
        private set
    var handled = false
        private set

    fun attach(changed: () -> Unit) { listener = changed }
    fun detach() { listener = null }
    fun start() {
        if (started || closed) return
        started = true
        worker.execute {
            val result = try { source.check(installedVersion) }
            catch (error: Exception) {
                Log.w("RedrainCast", "Launch update metadata check failed; no prompt shown", error)
                null
            }
            main.post {
                if (!closed) {
                    available = result
                    listener?.invoke()
                }
            }
        }
        worker.shutdown()
    }

    fun dismiss() { handled = true }
    fun close() {
        closed = true
        detach()
        source.cancel()
        worker.shutdownNow()
    }
}

package dev.screencopy.sender

import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketException

internal class SessionCancelledException(cause: Throwable? = null) : IOException("Session cancelled", cause)

/** Stop commits its terminal intent atomically; cancellation cannot replace a real earlier fault. */
internal class SessionOutcome {
    enum class Origin { STARTUP, CAPTURE, RECEIVER_READ, RECEIVER_WRITE, WATCHDOG, CLEANUP }
    data class Failure(val error: Exception, val origin: Origin)
    private val lock = Any()
    @Volatile var stopping = false
        private set
    @Volatile private var first: Failure? = null
    private var atStop: Failure? = null
    val failure get() = first?.error
    fun requestStop(): Boolean = synchronized(lock) {
        if (stopping) return false
        atStop = first
        stopping = true
        true
    }
    fun checkRunning() {
        if (stopping) throw SessionCancelledException()
        failure?.let { throw IOException(it.message, it) }
    }
    fun record(error: Exception, origin: Origin, closing: Boolean = false): Boolean = synchronized(lock) {
        if ((stopping || closing) && cancellation(error)) return false
        if (first != null) return false
        first = Failure(error, origin)
        true
    }
    fun terminal(session: Long, remoteStop: Boolean): Wire.Message = synchronized(lock) {
        val error = (if (stopping) atStop else first)?.error
        if (error != null) Wire.control(Wire.ERROR, session,
            Wire.obj("code" to errorCode(error), "message" to safeMessage(error), "fatal" to true))
        else Wire.control(if (remoteStop) Wire.SESSION_END else Wire.STOP, session, Wire.obj("reason" to "user"))
    }
    private fun cancellation(error: Exception): Boolean {
        if (error is InterruptedException || error is InterruptedIOException || error is EOFException || error is SocketException)
            return true
        var cause: Throwable? = error
        repeat(8) {
            if (cause is SessionCancelledException) return true
            cause = cause?.cause
        }
        return false
    }
    companion object {
        fun errorCode(error: Exception): String =
            if (error is java.net.SocketTimeoutException) "TIMEOUT" else
            listOf("UNSUPPORTED_CONFIG", "CONGESTION", "AUDIO_FAILED", "CAPTURE_FAILED", "DECODER_FAILED", "TIMEOUT", "PROTOCOL", "VERSION", "BUSY")
                .firstOrNull { error.message?.startsWith(it) == true } ?: "INTERNAL"
        fun safeMessage(error: Exception): String = when {
            error is org.json.JSONException -> "PROTOCOL: missing or invalid required field"
            error.message == null -> "${error.javaClass.simpleName}: operation failed"
            else -> error.message.orEmpty().take(400)
        }
    }
}

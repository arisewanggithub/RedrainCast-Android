package dev.screencopy.sender

import java.io.IOException
import java.net.SocketTimeoutException

internal object SenderFailureDiagnostics {
    private val frameName = Regex("[A-Za-z_][A-Za-z0-9_.$]{0,199}")

    fun chain(error: Throwable): List<Throwable> {
        val seen = java.util.IdentityHashMap<Throwable, Boolean>()
        val result = mutableListOf<Throwable>()
        var next: Throwable? = error
        while (next != null && result.size < 8 && seen.put(next, true) == null) {
            result.add(next)
            next = next.cause
        }
        return result
    }

    fun type(error: Throwable): String = when (error) {
        is SessionCancelledException -> "CANCELLED"
        is SocketTimeoutException -> "TIMEOUT"
        is IOException -> "IO"
        is InterruptedException -> "INTERRUPTED"
        is IllegalArgumentException -> "INVALID_INPUT"
        is IllegalStateException -> "INVALID_STATE"
        is IndexOutOfBoundsException -> "TRUNCATED_INPUT"
        else -> "UNKNOWN"
    }

    fun sanitize(error: IOException): IOException {
        if (error is SessionCancelledException) return error
        val root = chain(error).last()
        val code = SessionOutcome.errorCode(error)
        val truncated = if (root.cause != null) " chain_truncated=true" else ""
        return IOException("$code: Sender failure stage=SESSION root=${type(root)} category=${type(root)}$truncated", error)
    }

    fun causes(chain: List<Throwable>): List<String> = chain.mapIndexed { depth, cause ->
        "sender_failure_cause depth=$depth type=${type(cause)}"
    }

    fun stack(chain: List<Throwable>): List<String> = chain.flatMapIndexed { depth, cause ->
        cause.stackTrace.asSequence().filter { frame ->
            frame.className.startsWith("dev.screencopy.sender.") ||
                frame.className.startsWith("java.io.") || frame.className.startsWith("java.nio.")
        }.filter { frame -> frameName.matches(frame.className) && frameName.matches(frame.methodName) }
            .take(6).mapIndexed { index, frame ->
                "sender_failure_stack depth=$depth frame=$index at=${frame.className}.${frame.methodName} line=${frame.lineNumber.coerceAtLeast(-1)}"
            }.toList()
    }.takeLast(12)
}

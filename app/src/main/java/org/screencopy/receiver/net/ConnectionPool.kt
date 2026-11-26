package org.screencopy.receiver.net

import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal class ConnectionPool<C>(
    private val abort: (C) -> Unit,
    private val executor: ExecutorService = ThreadPoolExecutor(0, 32, 30, TimeUnit.SECONDS, SynchronousQueue()),
    private val overflowFactory: () -> ExecutorService = {
        ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, SynchronousQueue())
    }
) : AutoCloseable {
    private inner class Entry {
        var ordinary = true
        var readerExited = false
        var writerExited = false
    }
    internal class DiagnosticReservation internal constructor(private val retireWriter: () -> Unit) {
        fun writerExited() = retireWriter()
    }
    private val connections = mutableMapOf<C, Entry>()
    private var closed = false
    private var ordinary = 0
    private var diagnostic: Entry? = null
    private var overflow: ExecutorService? = null

    @Synchronized
    private fun releaseDiagnosticIfRetired(connection: C, entry: Entry) {
        if (diagnostic === entry && entry.readerExited && entry.writerExited) {
            diagnostic = null
            if (connections[connection] === entry) connections.remove(connection)
        }
    }

    /** Caller must first verify the control owner, generation, session and lane token. */
    @Synchronized
    fun reclassifyDiagnostic(connection: C): DiagnosticReservation? {
        val entry = connections[connection] ?: return null
        if (closed || !entry.ordinary || diagnostic != null) return null
        entry.ordinary = false
        diagnostic = entry
        ordinary--
        return DiagnosticReservation {
            synchronized(this) {
                entry.writerExited = true
                releaseDiagnosticIfRetired(connection, entry)
            }
        }
    }

    fun submit(connection: C, run: () -> Unit): Boolean {
        val admitted = try {
            trySubmit(connection, run)
        } catch (error: Exception) {
            try { abort(connection) } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            throw error
        }
        if (!admitted) abort(connection)
        return admitted
    }

    /** A rejection does not close the socket; caller retains ownership. */
    @Synchronized
    fun trySubmit(connection: C, run: () -> Unit): Boolean {
        if (closed || ordinary >= 32 || connections.containsKey(connection)) return false
        val entry = Entry()
        connections[connection] = entry
        ordinary++
        val task = Runnable {
            try { run() } finally {
                synchronized(this) {
                    if (entry.ordinary) {
                        connections.remove(connection)
                        ordinary--
                    } else {
                        entry.readerExited = true
                        releaseDiagnosticIfRetired(connection, entry)
                    }
                }
            }
        }
        var submitted = false
        try {
            try {
                executor.execute(task)
            } catch (_: RejectedExecutionException) {
                try {
                    val extra = overflow ?: overflowFactory().also { overflow = it }
                    extra.execute(task)
                } catch (_: RejectedExecutionException) {
                    return false
                }
            }
            submitted = true
            return true
        } finally {
            if (!submitted) {
                connections.remove(connection)
                ordinary--
            }
        }
    }

    @Synchronized
    fun snapshot(): List<C> = connections.keys.toList()

    @Synchronized internal fun ordinaryCount(): Int = ordinary

    override fun close() {
        val owned = synchronized(this) {
            if (closed) return
            closed = true
            executor.shutdown()
            overflow?.shutdown()
            connections.keys.toList()
        }
        val errors = mutableListOf<Exception>()
        owned.forEach {
            try { abort(it) } catch (e: Exception) { errors.add(e) }
        }
        if (errors.isNotEmpty()) throw IllegalStateException("Could not close all client sockets", errors.first()).also {
            errors.drop(1).forEach(it::addSuppressed)
        }
    }

    fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean {
        val nanos = unit.toNanos(timeout)
        val started = System.nanoTime()
        if (!executor.awaitTermination(nanos, TimeUnit.NANOSECONDS)) return false
        val remaining = (nanos - (System.nanoTime() - started)).coerceAtLeast(0)
        val extra = synchronized(this) { overflow }
        return extra?.awaitTermination(remaining, TimeUnit.NANOSECONDS) ?: true
    }
}

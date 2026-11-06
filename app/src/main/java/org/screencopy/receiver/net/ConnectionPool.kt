package org.screencopy.receiver.net

import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal class ConnectionPool<C>(
    private val abort: (C) -> Unit,
    private val executor: ExecutorService = ThreadPoolExecutor(0, 32, 30, TimeUnit.SECONDS, SynchronousQueue())
) : AutoCloseable {
    private val connections = mutableSetOf<C>()
    private var closed = false

    fun submit(connection: C, run: () -> Unit): Boolean {
        val admitted = synchronized(this) {
            if (closed) false else {
                connections.add(connection)
                try {
                    executor.execute {
                        try { run() } finally { synchronized(this) { connections.remove(connection) } }
                    }
                    true
                } catch (_: RejectedExecutionException) {
                    connections.remove(connection)
                    false
                }
            }
        }
        if (!admitted) abort(connection)
        return admitted
    }

    @Synchronized
    fun snapshot(): List<C> = connections.toList()

    override fun close() {
        val owned = synchronized(this) {
            if (closed) return
            closed = true
            executor.shutdown()
            connections.toList()
        }
        val errors = mutableListOf<Exception>()
        owned.forEach {
            try { abort(it) } catch (e: Exception) { errors.add(e) }
        }
        if (errors.isNotEmpty()) throw IllegalStateException("Could not close all client sockets", errors.first()).also {
            errors.drop(1).forEach(it::addSuppressed)
        }
    }

    fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = executor.awaitTermination(timeout, unit)
}

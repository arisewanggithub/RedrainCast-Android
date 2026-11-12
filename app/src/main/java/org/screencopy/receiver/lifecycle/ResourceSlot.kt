package org.screencopy.receiver.lifecycle

/** Serializes construction and retirement, including an in-flight Surface decoder configuration. */
class ResourceSlot<T : AutoCloseable> : AutoCloseable {
    private var resource: T? = null
    private val lifetime = Any()
    @Volatile var isClosed = false
        private set

    fun replace(create: () -> T): T {
        return checkNotNull(replaceIfOpen(create)) { "Surface lifetime has ended" }
    }

    @Synchronized
    fun replaceIfOpen(create: () -> T): T? {
        if (synchronized(lifetime) { isClosed }) return null
        retire()
        return create().also { resource = it }
    }

    @Synchronized
    fun release(value: T) {
        if (resource === value) retire()
    }

    fun whileOpen(action: () -> Unit): Boolean = synchronized(lifetime) {
        if (isClosed) return false
        action()
        true
    }

    override fun close() {
        synchronized(lifetime) { isClosed = true }
        synchronized(this) { retire() }
    }

    private fun retire() {
        val previous = resource
        resource = null
        previous?.close()
    }
}

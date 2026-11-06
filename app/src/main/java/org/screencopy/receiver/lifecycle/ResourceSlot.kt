package org.screencopy.receiver.lifecycle

/** Serializes construction and retirement, including an in-flight Surface decoder configuration. */
class ResourceSlot<T : AutoCloseable> : AutoCloseable {
    private var resource: T? = null
    @Volatile var isClosed = false
        private set

    @Synchronized
    fun replace(create: () -> T): T {
        check(!isClosed) { "Surface lifetime has ended" }
        retire()
        return create().also { resource = it }
    }

    @Synchronized
    fun release(value: T) {
        if (resource === value) retire()
    }

    @Synchronized
    override fun close() {
        isClosed = true
        retire()
    }

    private fun retire() {
        val previous = resource
        resource = null
        previous?.close()
    }
}

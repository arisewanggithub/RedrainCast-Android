package dev.screencopy.sender

import java.io.IOException

/** Source endpoint deduplication and expiry, also reset on actual network/interface changes. */
internal class ReceiverDirectory {
    private data class Key(val host: String, val port: Int?, val instanceId: String?)

    private val seen = linkedMapOf<Key, Discovery.Receiver>()

    @Synchronized fun update(receiver: Discovery.Receiver) {
        // A listener can rebind to a new ephemeral port while retaining its instance ID.
        val key = receiver.instanceId?.let { Key(receiver.host, null, it) }
            ?: Key(receiver.host, receiver.port, null)
        if (key !in seen && seen.size >= 64) throw IOException("Discovery receiver limit exceeded")
        seen[key] = receiver
    }
    @Synchronized fun snapshot(now: Long): List<Discovery.Receiver> {
        seen.values.removeAll { now - it.seen > 6_000_000_000L }
        return seen.values.toList()
    }
    @Synchronized fun networkChanged() { seen.clear() }
}

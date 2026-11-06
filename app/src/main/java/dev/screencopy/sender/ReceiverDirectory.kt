package dev.screencopy.sender

import java.io.IOException

/** Source endpoint deduplication and expiry, also reset on actual network/interface changes. */
internal class ReceiverDirectory {
    private val seen = linkedMapOf<Pair<String, Int>, Discovery.Receiver>()
    @Synchronized fun update(receiver: Discovery.Receiver) {
        val key = receiver.host to receiver.port
        if (key !in seen && seen.size >= 64) throw IOException("Discovery receiver limit exceeded")
        seen[key] = receiver
    }
    @Synchronized fun snapshot(now: Long): List<Discovery.Receiver> {
        seen.values.removeAll { now - it.seen > 6_000_000_000L }
        return seen.values.toList()
    }
    @Synchronized fun networkChanged() { seen.clear() }
}

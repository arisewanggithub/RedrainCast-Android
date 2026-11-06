package dev.redrain.cast

import dev.screencopy.sender.Discovery
import dev.screencopy.sender.ReceiverTarget

internal class SelfReceivers(private val instanceId: String) {
    private val retired = linkedSetOf<Pair<String, Int>>()

    @Synchronized fun remember(addresses: List<String>, port: Int) {
        (addresses + listOf("127.0.0.1", "::1", "localhost")).forEach { retired.add(it.lowercase() to port) }
        while (retired.size > 256) retired.remove(retired.first())
    }

    @Synchronized fun contains(target: ReceiverTarget): Boolean = target.host.lowercase() to target.port in retired

    @Synchronized fun visible(receivers: List<Discovery.Receiver>): List<Discovery.Receiver> =
        receivers.filter { it.instanceId?.equals(instanceId, ignoreCase = true) != true && !contains(it.target()) }
}

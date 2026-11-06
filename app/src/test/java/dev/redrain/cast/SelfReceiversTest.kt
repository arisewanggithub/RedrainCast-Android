package dev.redrain.cast

import dev.screencopy.sender.Discovery
import dev.screencopy.sender.ReceiverTarget
import org.junit.Assert.*
import org.junit.Test

class SelfReceiversTest {
    @Test fun currentAndRetiredSelfAreHiddenButAnotherLocalAppIsNot() {
        val identity = "a".repeat(32)
        val self = SelfReceivers(identity)
        self.remember(listOf("192.0.2.7"), 50100)
        self.remember(listOf("192.0.2.7"), 50200)
        val receivers = listOf(
            Discovery.Receiver("Self", "192.0.2.7", 50200, "available", 1, identity),
            Discovery.Receiver("Retired", "192.0.2.7", 50100, "available", 1),
            Discovery.Receiver("Own new address", "192.0.2.8", 50300, "available", 1, identity.uppercase()),
            Discovery.Receiver("Other instance", "192.0.2.7", 50400, "available", 1, "b".repeat(32)))
        assertEquals(listOf(receivers.last()), self.visible(receivers))
        assertTrue(self.contains(ReceiverTarget("localhost", 50100)))
        assertTrue(self.contains(ReceiverTarget("127.0.0.1", 50200)))
    }

    @Test fun discoveryPreservesAndValidatesStableInstanceIdentity() {
        val identity = "a".repeat(32)
        fun advertisement(id: String) =
            """{"protocol":"screencopy","version":3,"name":"Receiver","port":50100,"state":"available","instanceId":"$id"}""".toByteArray()
        assertEquals(identity, Discovery.decode(advertisement(identity), "192.0.2.1", 1).instanceId)
        assertThrows(java.io.IOException::class.java) { Discovery.decode(advertisement("invalid"), "192.0.2.1", 1) }
    }
}

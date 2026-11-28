package dev.screencopy.sender

import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiverDirectoryTest {
    @Test fun updatedPortReplacesOldEndpointForSameInstanceAndHost() {
        val directory = ReceiverDirectory()
        directory.update(receiver(port = 35191, seen = 10))
        directory.update(receiver(port = 32825, seen = 20))

        val receivers = directory.snapshot(20)
        assertEquals(1, receivers.size)
        assertEquals(32825, receivers.single().port)
    }

    @Test fun sameInstanceOnDifferentHostsRemainsDistinct() {
        val directory = ReceiverDirectory()
        directory.update(receiver(host = "192.168.1.108", port = 35191, seen = 10))
        directory.update(receiver(host = "192.168.1.109", port = 35191, seen = 20))

        assertEquals(2, directory.snapshot(20).size)
    }

    @Test fun legacyReceiversWithoutInstanceIdsRemainKeyedByEndpoint() {
        val directory = ReceiverDirectory()
        directory.update(receiver(port = 35191, seen = 10, instanceId = null))
        directory.update(receiver(port = 32825, seen = 20, instanceId = null))

        assertEquals(2, directory.snapshot(20).size)
    }

    private fun receiver(
        host: String = "192.168.1.108",
        port: Int,
        seen: Long,
        instanceId: String? = "0123456789abcdef0123456789abcdef"
    ) = Discovery.Receiver("Projector", host, port, "available", seen, instanceId)
}

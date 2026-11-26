package dev.screencopy.sender

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.screencopy.receiver.net.ReceiverServer
import org.screencopy.receiver.protocol.DiagnosticDiscovery
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class DiagnosticDiscoveryTest {
    private fun advertisement(extra: String = "") =
        """{"protocol":"screencopy","version":3,"name":"Receiver","port":27183,"state":"available"$extra}"""
    private fun decode(value: String) = Discovery.decode(value.toByteArray(), "192.168.1.3", 1)
    private fun rejects(value: String) {
        try { decode(value); fail("Invalid diagnostic advertisement accepted") }
        catch (_: IllegalArgumentException) {}
        catch (_: java.io.IOException) {}
    }

    @Test fun legacyAndValidEndpointRetainCastingIdentityWithoutTrustingTheHint() {
        assertNull(decode(advertisement()).diagnostic)
        val receiver = decode(advertisement(""","diagnostic":{"version":1,"port":43210,"access":"lan"}"""))
        assertEquals(43210, receiver.diagnostic?.port)
        assertEquals(27183, receiver.port)
        assertEquals("192.168.1.3", receiver.host)
        assertEquals(27183, receiver.target().port)
    }

    @Test fun malformedTypedValuesRangesMissingFieldsAndDuplicateKeysAreRejected() {
        for (envelope in listOf(
            "null", "true", "[]", "\"diagnostic\"", "{}",
            """{"version":1}""", """{"port":1234}""",
            """{"version":"1","port":1234}""", """{"version":true,"port":1234}""",
            """{"version":1.0,"port":1234}""", """{"version":0,"port":1234}""",
            """{"version":-1,"port":1234}""", """{"version":1,"port":"1234"}""",
            """{"version":1,"port":true}""", """{"version":1,"port":1234.0}""",
            """{"version":1,"port":0}""", """{"version":1,"port":65536}""",
            """{"version":1,"port":1234,"port":5678}""",
            """{"version":1,"version":2,"port":1234}""",
            """{"version":1,"port":1234}""",
            """{"version":1,"port":1234,"access":"tls"}""",
            """{"version":1,"port":1234,"access":null}""",
            """{"version":1,"port":1234,"access":"lan","access":"lan"}"""
        )) rejects(advertisement(""","diagnostic":$envelope"""))
        rejects(advertisement(""","diagnostic":{"version":1,"port":1234},"diagnostic":{"version":1,"port":5678}"""))
    }

    @Test fun futureRevisionIsIgnoredOnlyWhenEnvelopeIsWellTypedAndPortValid() {
        assertNull(decode(advertisement(""","diagnostic":{"version":2,"port":1234,"future":true}""")).diagnostic)
        rejects(advertisement(""","diagnostic":{"version":2,"port":"1234"}"""))
        rejects(advertisement(""","diagnostic":{"version":2,"port":65536}"""))
    }

    @Test fun maximumAndMinimumPortsAreAcceptedButOriginalPacketBudgetRemains1024Bytes() {
        for (port in listOf(1, 65535))
            assertEquals(port, decode(advertisement(""","diagnostic":{"version":1,"port":$port,"access":"lan"}""")).diagnostic?.port)
        val normal = advertisement(""","diagnostic":{"version":1,"port":1234,"access":"lan"}""")
        val atLimit = normal + " ".repeat(1024 - normal.toByteArray().size)
        assertEquals(1234, decode(atLimit).diagnostic?.port)
        rejects("$atLimit ")
    }

    @Test fun receiverNeverAdvertisesStandaloneDiagnosticEndpoint() {
        val called = AtomicInteger()
        ReceiverServer.forProtocolTests("Receiver", diagnosticDiscovery = {
            called.incrementAndGet()
            DiagnosticDiscovery(43210)
        }).use { server ->
            server.start()
            assertFalse(JSONObject(String(server.advertisement())).has("diagnostic"))
            assertNull(Discovery.decode(server.advertisement(), "127.0.0.1", 1).diagnostic)
            assertTrue(server.advertisement().size <= 1024)
            server.close()
            assertFalse(JSONObject(String(server.advertisement())).has("diagnostic"))
            assertEquals(0, called.get())
        }
    }

    @Test fun udpAdvertisementDoesNotExposeDiagnosticEndpoint() {
        val first = InetAddress.getByName("127.0.0.1")
        ReceiverServer.forProtocolTests("Receiver", diagnosticDiscovery = { DiagnosticDiscovery(43210) }).use { server ->
            server.start()
            DatagramSocket(InetSocketAddress(first, 0)).use { receiver ->
                receiver.soTimeout = 3000
                server.sendAdvertisement(InetSocketAddress(first, receiver.localPort))
                val packet = DatagramPacket(ByteArray(1024), 1024)
                receiver.receive(packet)
                assertEquals(first, packet.address)
                assertFalse(JSONObject(String(packet.data, 0, packet.length, Charsets.UTF_8)).has("diagnostic"))
            }
        }
    }
}

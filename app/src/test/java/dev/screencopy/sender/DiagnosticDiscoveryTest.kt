package dev.screencopy.sender

import dev.redrain.cast.diagnostics.DiagnosticIdentity
import dev.redrain.cast.diagnostics.DiagnosticListener
import dev.redrain.cast.diagnostics.DiagnosticRecorder
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.screencopy.receiver.net.ReceiverServer
import org.screencopy.receiver.protocol.DiagnosticDiscovery
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

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

    @Test fun receiverProviderAdvertisesOnlyEnabledSnapshotAndNeverAfterReceiverClose() {
        var snapshot: DiagnosticDiscovery? = null
        val address = InetAddress.getByName("127.0.0.1")
        ReceiverServer.forProtocolTests("Receiver", diagnosticDiscovery = { if (it == address) snapshot else null }).use { server ->
            server.start()
            assertFalse(JSONObject(String(server.advertisement(address))).has("diagnostic"))
            snapshot = DiagnosticDiscovery(43210)
            val advertised = JSONObject(String(server.advertisement(address)))
            val diagnostic = advertised.getJSONObject("diagnostic")
            assertEquals(setOf("version", "port", "access"), diagnostic.keys().asSequence().toSet())
            assertEquals("lan", diagnostic.getString("access"))
            assertEquals(1, diagnostic.getInt("version"))
            assertEquals(43210, diagnostic.getInt("port"))
            assertEquals(43210, Discovery.decode(server.advertisement(address), "127.0.0.1", 1).diagnostic?.port)
            assertTrue(server.advertisement(address).size <= 1024)
            assertFalse(advertised.toString().contains("secret"))
            assertFalse(advertised.toString().contains("fingerprint"))
            snapshot = null
            assertFalse(JSONObject(String(server.advertisement(address))).has("diagnostic"))
            snapshot = DiagnosticDiscovery(43210)
            server.close()
            assertFalse(JSONObject(String(server.advertisement(address))).has("diagnostic"))
        }
    }

    @Test fun udpHintUsesActualPacketSourceAndOmitsOtherInterface() {
        val first = InetAddress.getByName("127.0.0.1")
        val other = InetAddress.getByName("127.0.0.2")
        DiagnosticListener(DiagnosticRecorder(), DiagnosticIdentity(first)).use { firstListener ->
            DiagnosticListener(DiagnosticRecorder(), DiagnosticIdentity(other)).use { otherListener ->
                val active = AtomicReference(firstListener)
                ReceiverServer.forProtocolTests("Receiver",
                    diagnosticDiscovery = { active.get().discoveryFor(it) }).use { server ->
                    server.start()
                    DatagramSocket(InetSocketAddress(first, 0)).use { receiver ->
                        receiver.soTimeout = 3000
                        fun receive(): Pair<InetAddress, JSONObject> {
                            server.sendAdvertisement(InetSocketAddress(first, receiver.localPort))
                            val packet = DatagramPacket(ByteArray(1024), 1024)
                            receiver.receive(packet)
                            return packet.address to JSONObject(String(packet.data, 0, packet.length, Charsets.UTF_8))
                        }
                        val (source, firstPacket) = receive()
                        assertEquals(first, source)
                        val port = firstPacket.getJSONObject("diagnostic").getInt("port")
                        assertEquals(firstListener.discovery!!.port, port)
                        Socket().use { it.connect(InetSocketAddress(source, port), 1000) }
                        active.set(otherListener)
                        val (otherSource, secondPacket) = receive()
                        assertEquals(first, otherSource)
                        assertFalse(secondPacket.has("diagnostic"))
                    }
                }
            }
        }
    }
}

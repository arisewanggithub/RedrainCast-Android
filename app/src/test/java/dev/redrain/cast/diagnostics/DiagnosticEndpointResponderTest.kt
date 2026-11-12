package dev.redrain.cast.diagnostics

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.screencopy.receiver.protocol.DiagnosticDiscovery
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class DiagnosticEndpointResponderTest {
    private val address = InetAddress.getLoopbackAddress()
    private val nonce = "0123456789abcdef0123456789abcdef"
    private fun query(value: String = nonce): ByteArray = JSONObject()
        .put("protocol", DiagnosticEndpointResponder.PROTOCOL).put("version", 1)
        .put("nonce", value).toString().padEnd(256).toByteArray()
    private fun send(client: DatagramSocket, port: Int, bytes: ByteArray = query()) {
        client.send(DatagramPacket(bytes, bytes.size, address, port))
    }
    private fun receive(client: DatagramSocket): JSONObject {
        val packet = DatagramPacket(ByteArray(257), 257)
        client.receive(packet)
        assertTrue(packet.length <= 256)
        return JSONObject(String(packet.data, 0, packet.length))
    }
    private fun silent(client: DatagramSocket) {
        try { receive(client); fail("Unexpected endpoint exposure") } catch (_: SocketTimeoutException) {}
    }

    @Test fun strictBoundedQueriesRejectDuplicatesUnknownFieldsVersionsAndInvalidNonces() {
        assertEquals(nonce, DiagnosticEndpointResponder.nonce(query()))
        val valid = String(query()).trim()
        for (invalid in listOf(
            query().copyOf(255), query().copyOf(257), query("secret"),
            valid.replace("\"version\":1", "\"version\":\"1\"").padEnd(256).toByteArray(),
            valid.replace("\"version\":1", "\"version\":true").padEnd(256).toByteArray(),
            valid.replace("\"version\":1", "\"version\":1.0").padEnd(256).toByteArray(),
            valid.replace("\"version\":1", "\"version\":2").padEnd(256).toByteArray(),
            valid.replace("\"version\":1", "\"version\":1,\"version\":1").padEnd(256).toByteArray(),
            valid.replace("\"version\":1", "\"extra\":true,\"version\":1").padEnd(256).toByteArray()
        )) assertNull(DiagnosticEndpointResponder.nonce(invalid))
    }

    @Test fun activeCastCanDiscoverWithoutActivityStartupLogsOrStartingSampling() {
        val recorder = DiagnosticRecorder()
        recorder.sessionStarted("sender", 77)
        DiagnosticListener(recorder, DiagnosticIdentity(address), discoveryPort = 0).use { listener ->
            DatagramSocket().use { client ->
                client.soTimeout = 1000
                send(client, checkNotNull(listener.endpointDiscoveryPort))
                val response = receive(client)
                assertEquals(nonce, response.getString("nonce"))
                assertEquals(DiagnosticEndpointResponder.PROTOCOL, response.getString("protocol"))
                assertEquals(listener.discovery!!.json().toString(), response.getJSONObject("diagnostic").toString())
                assertEquals(setOf("protocol", "version", "nonce", "diagnostic"),
                    response.keys().asSequence().toSet())
                assertFalse(response.toString().contains("session"))
                assertFalse(recorder.collecting)
            }
        }
    }

    @Test fun consentAndNetworkGatesSuppressHintsBeforeAsynchronousCleanup() {
        val permitted = AtomicBoolean(true)
        val network = AtomicBoolean(true)
        DiagnosticListener(DiagnosticRecorder(), DiagnosticIdentity(address, network::get),
            accessAllowed = permitted::get, discoveryPort = 0).use { listener ->
            DatagramSocket().use { client ->
                client.soTimeout = 200
                val port = checkNotNull(listener.endpointDiscoveryPort)
                permitted.set(false)
                send(client, port)
                silent(client)
                permitted.set(true)
                network.set(false)
                send(client, port)
                silent(client)
                listener.revokeNetwork()
                assertNull(listener.endpointDiscoveryPort)
                assertNull(listener.discovery)
            }
        }
    }

    @Test fun disabledUnavailableAndRetiredOwnersNeverPublishAnEndpoint() {
        val owner = AtomicLong(77)
        val consent = AtomicBoolean(false)
        val endpoint = {
            if (consent.get() && owner.get() == 77L) DiagnosticDiscovery(41234) else null
        }
        DiagnosticEndpointResponder(address, 0, endpoint).use { responder ->
            DatagramSocket().use { client ->
                client.soTimeout = 200
                send(client, responder.localPort)
                silent(client)
                consent.set(true)
                owner.set(78)
                send(client, responder.localPort)
                silent(client)
            }
        }
    }

    @Test fun malformedTrafficDoesNotConsumeRateBudgetAndRepliesAreGloballyBounded() {
        val time = AtomicLong()
        DiagnosticEndpointResponder(address, 0, { DiagnosticDiscovery(41234) }, time::get).use { responder ->
            DatagramSocket().use { client ->
                client.soTimeout = 200
                send(client, responder.localPort, ByteArray(257))
                send(client, responder.localPort)
                assertEquals(nonce, receive(client).getString("nonce"))
                send(client, responder.localPort)
                silent(client)
                time.addAndGet(250_000_000L)
                send(client, responder.localPort)
                assertEquals(41234, receive(client).getJSONObject("diagnostic").getInt("port"))
            }
        }
    }

    @Test fun closingProcessOwnedResponderReleasesPortForNewConsentingProcess() {
        val first = DiagnosticEndpointResponder(address, 0, { DiagnosticDiscovery(41234) })
        val port = first.localPort
        first.close()
        assertEquals(-1, first.localPort)
        DiagnosticEndpointResponder(address, port, { null }).use { replacement ->
            DatagramSocket().use { client ->
                client.soTimeout = 200
                send(client, replacement.localPort)
                silent(client)
            }
        }

    }

    @Test fun occupiedDiscoveryPortIsNotReusedOrClosed() {
        DiagnosticEndpointResponder(address, 0, { DiagnosticDiscovery(41234) }).use { first ->
            try {
                DiagnosticEndpointResponder(address, first.localPort, { DiagnosticDiscovery(49999) }).use {
                    fail("Must not share or steal another owner's discovery socket")
                }
            } catch (_: java.io.IOException) {}
            DatagramSocket().use { client ->
                client.soTimeout = 1000
                send(client, first.localPort)
                assertEquals(41234, receive(client).getJSONObject("diagnostic").getInt("port"))
            }
        }
    }
}

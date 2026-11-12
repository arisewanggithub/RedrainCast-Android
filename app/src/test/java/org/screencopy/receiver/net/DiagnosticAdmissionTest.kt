package org.screencopy.receiver.net

import dev.screencopy.sender.ReceiverLink
import dev.screencopy.sender.ReceiverTarget
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.screencopy.receiver.protocol.*
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DiagnosticAdmissionTest {
    @Test fun admittedStatusCarriesOnlyActualBoundDiagnosticPortAndExactOwner() {
        val recorder = dev.redrain.cast.diagnostics.DiagnosticRecorder()
        dev.redrain.cast.diagnostics.DiagnosticListener(recorder,
            dev.redrain.cast.diagnostics.DiagnosticIdentity(InetAddress.getLoopbackAddress())).use { listener ->
            val access = Access()
            ReceiverServer.forProtocolTests("Receiver", diagnosticSessionAccess = access,
                diagnosticDiscovery = { listener.discoveryFor(it) }).use { server ->
                server.start()
                connect(server).use { peer ->
                    hello(peer)
                    send(peer, Type.SESSION_REQUEST, settings(), 77)
                    val starting = read(peer)
                    assertFalse(starting.json().getJSONObject("diagnostic").has("port"))
                    val enabled = read(peer)
                    val memory = dev.redrain.cast.diagnostics.DiagnosticConnections()
                    memory.begin(77, checkNotNull(peer.inetAddress.hostAddress), true, true)
                    memory.update(enabled.session, enabled.json())
                    val snapshot = memory.snapshot()
                    assertTrue(snapshot.getBoolean("available"))
                    assertEquals(listener.discovery!!.port, snapshot.getInt("port"))
                    assertNotEquals(server.listeningPort, snapshot.getInt("port"))
                    assertEquals("127.0.0.1", snapshot.getString("host"))
                    assertEquals("77", snapshot.getString("session"))
                    assertFalse(recorder.collecting)
                    send(peer, Type.STOP, JSONObject().put("reason", "done"), 77)
                    assertEquals(Type.SESSION_END, read(peer).type)
                }
            }
        }
    }

    @Test fun castOnAnotherInterfaceReportsUnavailableInsteadOfUnreachableFollowReceiverHint() {
        val bound = InetAddress.getByName("127.0.0.2")
        val recorder = dev.redrain.cast.diagnostics.DiagnosticRecorder()
        dev.redrain.cast.diagnostics.DiagnosticListener(recorder,
            dev.redrain.cast.diagnostics.DiagnosticIdentity(bound)).use { listener ->
            ReceiverServer.forProtocolTests("Receiver", diagnosticSessionAccess = Access(),
                diagnosticDiscovery = { listener.discoveryFor(it) }).use { server ->
                server.start()
                connect(server).use { peer ->
                    hello(peer)
                    send(peer, Type.SESSION_REQUEST, settings(), 77)
                    read(peer)
                    val result = read(peer)
                    assertEquals("unavailable", DiagnosticDelegation.state(result.json()))
                    val memory = dev.redrain.cast.diagnostics.DiagnosticConnections()
                    memory.begin(77, checkNotNull(peer.inetAddress.hostAddress), true, true)
                    memory.update(result.session, result.json())
                    assertFalse(memory.snapshot().getBoolean("available"))
                    assertFalse(memory.snapshot().has("port"))
                    send(peer, Type.STOP, JSONObject().put("reason", "done"), 77)
                    assertEquals(Type.SESSION_END, read(peer).type)
                }
            }
        }
    }

    private class Access : DiagnosticSessionAccess {
        val admissions = mutableListOf<Pair<Long, Boolean>>()
        val revoked = CountDownLatch(1)
        val ended = CountDownLatch(1)
        var fail = false
        override fun admitted(session: Long, requested: Boolean, ownerActive: () -> Boolean, report: (String) -> Unit) {
            assertTrue(ownerActive())
            admissions.add(session to requested)
            if (fail) throw java.io.IOException("synthetic diagnostics unavailable")
            if (requested) report("enabled")
        }
        override fun ended(session: Long) { ended.countDown() }
        override fun revoked(session: Long) { assertEquals(77L, session); revoked.countDown() }
    }
    private fun connect(server: ReceiverServer) =
        Socket(InetAddress.getLoopbackAddress(), server.listeningPort).apply { soTimeout = 3000 }
    private fun send(peer: Socket, type: Type, json: JSONObject, session: Long = 0) =
        Wire.write(peer.outputStream, Message.json(type, json, session))
    private fun read(peer: Socket) = Wire.read(peer.inputStream)
    private fun hello(peer: Socket, diagnostics: Any? = DiagnosticDelegation.capability()): Message {
        val hello = JSONObject().put("role", "sender").put("versions", JSONArray().put(3)).put("name", "Phone")
        if (diagnostics != null) hello.put("diagnostics", diagnostics)
        send(peer, Type.HELLO, hello)
        return read(peer)
    }
    private fun settings(enabled: Boolean? = true) = StrictJson.decode(
        """{"video":{"codec":"h264","encoder":"","bitrate":8000000,"maxSize":1920,"maxFps":60,"displayId":0,"orientation":"0","crop":null,"codecOptions":""},"audio":{"enabled":false,"codec":"opus","encoder":"","bitrate":192000,"source":"output","codecOptions":""}}""".toByteArray()
    ).apply { enabled?.let { put("diagnostic", DiagnosticDelegation.request(it)) } }

    @Test fun capabilityRequestAndAdmissionAreAdditiveWithNoReceiverStatsNegotiation() {
        val access = Access()
        ReceiverServer.forProtocolTests("Receiver", diagnosticSessionAccess = access,
            diagnosticDiscovery = { DiagnosticDiscovery(43210) }).use { server ->
            server.start()
            connect(server).use { peer ->
                assertTrue(DiagnosticDelegation.supported(hello(peer).json()))
                assertTrue(access.admissions.isEmpty())
                send(peer, Type.SESSION_REQUEST, settings(), 77)
                val accepted = read(peer)
                assertEquals(Type.SESSION_ACCEPT, accepted.type)
                assertEquals("starting", DiagnosticDelegation.state(accepted.json()))
                val enabled = read(peer)
                assertEquals(Type.STATUS, enabled.type)
                assertEquals("enabled", DiagnosticDelegation.state(enabled.json()))
                assertEquals(listOf(77L to true), access.admissions)
                assertFalse(server.playbackStatisticsRequested)
                send(peer, Type.STATUS, JSONObject().put("state", "diagnostics").put("message", "disabled")
                    .put("diagnostic", DiagnosticDelegation.request(false)), 77)
                assertTrue(access.revoked.await(2, TimeUnit.SECONDS))
                send(peer, Type.STOP, JSONObject().put("reason", "done"), 77)
                assertEquals(Type.SESSION_END, read(peer).type)
                assertTrue(access.ended.await(2, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun legacySenderHasNoDiagnosticFieldsAndUnsupportedReceiverDoesNotPretendEnabled() {
        val access = Access()
        ReceiverServer.forProtocolTests("Receiver", diagnosticSessionAccess = access).use { server ->
            server.start()
            connect(server).use { peer ->
                assertFalse(hello(peer, null).json().has("diagnostics"))
                send(peer, Type.SESSION_REQUEST, settings(null), 77)
                val accepted = read(peer)
                assertEquals(Type.SESSION_ACCEPT, accepted.type)
                assertFalse(accepted.json().has("diagnostic"))
                send(peer, Type.STOP, JSONObject().put("reason", "done"), 77)
                assertEquals(Type.SESSION_END, read(peer).type)
            }
        }
        ReceiverServer.forProtocolTests("Receiver").use { server ->
            server.start()
            connect(server).use { peer ->
                assertFalse(DiagnosticDelegation.supported(hello(peer).json()))
                send(peer, Type.SESSION_REQUEST, settings(), 77)
                val accepted = read(peer)
                assertEquals(Type.SESSION_ACCEPT, accepted.type)
                assertEquals("unsupported", DiagnosticDelegation.state(accepted.json()))
            }
        }
    }

    @Test fun senderReceiverLinkRecognizesSupportAndLegacyAbsenceWithoutRequiringIt() {
        for (access in listOf<Access?>(null, Access())) {
            ReceiverServer.forProtocolTests("Receiver", diagnosticSessionAccess = access).use { server ->
                server.start()
                ReceiverLink(ReceiverTarget("127.0.0.1", server.listeningPort, "Receiver")).use { link ->
                    link.connect()
                    assertEquals(access != null, link.delegatedDiagnostics)
                }
            }
        }
    }

    @Test fun malformedCapabilityAndRequestAreRejectedBeforeAnyDelegatedAdmission() {
        val access = Access()
        ReceiverServer.forProtocolTests("Receiver", diagnosticSessionAccess = access).use { server ->
            server.start()
            connect(server).use { peer ->
                Wire.write(peer.outputStream, Message(Type.HELLO, payload =
                    """{"role":"sender","versions":[3],"name":"Phone","diagnostics":{"version":1.0,"sessionDelegation":true}}""".toByteArray()))
                assertEquals(Type.ERROR, read(peer).type)
            }
            connect(server).use { peer ->
                assertEquals(Type.HELLO, hello(peer).type)
                send(peer, Type.SESSION_REQUEST, settings().put("diagnostic",
                    JSONObject().put("version", 1).put("enabled", "true")), 77)
                assertEquals(Type.REJECT, read(peer).type)
            }
            assertTrue(access.admissions.isEmpty())
        }
    }

    @Test fun unownedStatusCannotActivateAndBusyPeerCannotReplaceTheActiveDiagnosticOwner() {
        val access = Access()
        ReceiverServer.forProtocolTests("Receiver", diagnosticSessionAccess = access).use { server ->
            server.start()
            connect(server).use { unowned ->
                hello(unowned)
                send(unowned, Type.STATUS, JSONObject().put("state", "diagnostics").put("message", "enable")
                    .put("diagnostic", DiagnosticDelegation.request(true)))
                assertEquals(Type.ERROR, read(unowned).type)
            }
            assertTrue(access.admissions.isEmpty())
            connect(server).use { owner ->
                hello(owner)
                send(owner, Type.SESSION_REQUEST, settings(), 77)
                assertEquals(Type.SESSION_ACCEPT, read(owner).type)
                read(owner)
                connect(server).use { intruder ->
                    hello(intruder)
                    send(intruder, Type.SESSION_REQUEST, settings(), 88)
                    assertEquals(Type.REJECT, read(intruder).type)
                }
                assertEquals(listOf(77L to true), access.admissions)
                assertEquals(1L, access.ended.count)
                send(owner, Type.STATUS, JSONObject().put("state", "diagnostics").put("message", "disabled")
                    .put("diagnostic", DiagnosticDelegation.request(false)), 88)
                assertEquals(Type.ERROR, read(owner).type)
                assertEquals(1L, access.revoked.count)
            }
        }
    }

    @Test fun diagnosticsStartupFailureIsExplicitAndDoesNotFailHealthyCast() {
        val access = Access().apply { fail = true }
        ReceiverServer.forProtocolTests("Receiver", diagnosticSessionAccess = access).use { server ->
            server.start()
            connect(server).use { peer ->
                hello(peer)
                send(peer, Type.SESSION_REQUEST, settings(), 77)
                assertEquals(Type.SESSION_ACCEPT, read(peer).type)
                assertEquals("unavailable", DiagnosticDelegation.state(read(peer).json()))
                send(peer, Type.PING, JSONObject().put("nonce", 123), 77)
                assertEquals(Type.PONG, read(peer).type)
                send(peer, Type.STOP, JSONObject().put("reason", "done"), 77)
                assertEquals(Type.SESSION_END, read(peer).type)
            }
        }
    }

    @Test fun failedCastOwnershipLeaseNeverDelegates() {
        val admissions = AtomicInteger()
        val access = object : DiagnosticSessionAccess {
            override fun admitted(session: Long, requested: Boolean, ownerActive: () -> Boolean, report: (String) -> Unit) {
                admissions.incrementAndGet()
            }
            override fun ended(session: Long) {}
            override fun revoked(session: Long) {}
        }
        ReceiverServer.forProtocolTests("Receiver", acquireSession = { null }, diagnosticSessionAccess = access).use { server ->
            server.start()
            connect(server).use { peer ->
                hello(peer)
                send(peer, Type.SESSION_REQUEST, settings(), 77)
                assertEquals(Type.REJECT, read(peer).type)
                assertEquals(0, admissions.get())
            }
        }

    }

    @Test fun evenTheAdmittedOwnerCannotEnableDiagnosticsAfterAdmission() {
        val access = Access()
        ReceiverServer.forProtocolTests("Receiver", diagnosticSessionAccess = access).use { server ->
            server.start()
            connect(server).use { peer ->
                hello(peer)
                send(peer, Type.SESSION_REQUEST, settings(false), 77)
                assertEquals(Type.SESSION_ACCEPT, read(peer).type)
                assertEquals(listOf(77L to false), access.admissions)
                send(peer, Type.STATUS, JSONObject().put("state", "diagnostics").put("message", "enable")
                    .put("diagnostic", DiagnosticDelegation.request(true)), 77)
                assertEquals(Type.ERROR, read(peer).type)
                assertEquals(1L, access.revoked.count)
                assertEquals(listOf(77L to false), access.admissions)
            }
        }
    }
}

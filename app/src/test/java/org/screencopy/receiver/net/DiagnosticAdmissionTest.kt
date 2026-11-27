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
    @Test fun productionReceiverDoesNotAdvertiseOrNegotiateLegacyDiagnostics() {
        org.mockito.Mockito.mockStatic(android.util.Log::class.java).use {
            ReceiverServer("Receiver", org.mockito.Mockito.mock(android.view.Surface::class.java),
                { _, _ -> }, { _, _ -> }, {}, port = 0).use { server ->
                server.start()
                assertFalse(JSONObject(String(server.advertisement())).has("diagnostic"))
                connect(server).use { peer ->
                    assertFalse(hello(peer).json().has("diagnostics"))
                }
            }
        }
    }

    @Test fun armedOfferAdvertisesCapabilitiesWithoutCapturingBeforeAdmission() {
        val recorder = dev.redrain.cast.diagnostics.DiagnosticRecorder()
        ReceiverServer.forProtocolTests("Receiver", proxyRecorder = recorder).use { server ->
            server.start()
            connect(server).use { peer ->
                val offer = JSONObject().put("session", "77").put("pairId", "pair")
                    .put("armGeneration", "1").put("receiverFlags", JSONArray().put("session"))
                    .put("durationMs", 1000).put("samplePeriodMs", 100)
                send(peer, Type.HELLO, JSONObject().put("role", "sender").put("versions", JSONArray().put(3))
                    .put("name", "Phone").put("diagProxy", JSONObject().put("version", 2).put("offer", offer)))
                val response = read(peer)
                assertEquals(Type.HELLO, response.type)
                assertEquals(2, response.json().getJSONObject("diagProxy").getInt("version"))
                assertEquals("idle", recorder.status().getString("state"))
            }
        }
    }

    @Test fun unsolicitedProxyRequestCannotAcquireReceiverOwnership() {
        ReceiverServer.forProtocolTests("Receiver").use { server ->
            server.start()
            connect(server).use { peer ->
                assertEquals(Type.HELLO, hello(peer, null).type)
                send(peer, Type.SESSION_REQUEST, settings(null).put("diagProxy",
                    JSONObject().put("version", 2).put("pairId", "pair").put("armGeneration", "1")), 77)
                assertEquals(Type.REJECT, read(peer).type)
            }
            connect(server).use { peer ->
                assertEquals(Type.HELLO, hello(peer, null).type)
                send(peer, Type.SESSION_REQUEST, settings(null), 78)
                assertEquals(Type.SESSION_ACCEPT, read(peer).type)
                send(peer, Type.STOP, JSONObject().put("reason", "done"), 78)
                assertEquals(Type.SESSION_END, read(peer).type)
            }
        }
    }

    @Test(timeout = 15_000) fun ordinaryControlReadsDoNotCountDuplicateOperationsWithLiveRecorderOnOrOff() {
        val recorder = dev.redrain.cast.diagnostics.RemoteDiagnostics.recorder
        recorder.unrestricted()
        try {
            for (live in listOf(false, true)) {
                recorder.revoke()
                ReceiverServer.forProtocolTests("Receiver").use { server ->
                    server.start()
                    connect(server).use { peer ->
                        val hello = hello(peer, null, telemetry = true)
                        assertTrue(dev.redrain.cast.diagnostics.SessionTelemetryProtocol.supported(hello.json()))
                        send(peer, Type.SESSION_REQUEST, settings(null).put("sessionTelemetry",
                            dev.redrain.cast.diagnostics.SessionTelemetryProtocol.capability()), 77)
                        val accepted = read(peer)
                        assertEquals(Type.SESSION_ACCEPT, accepted.type)
                        assertTrue(dev.redrain.cast.diagnostics.SessionTelemetryProtocol.supported(accepted.json()))
                        var final: dev.redrain.cast.diagnostics.SessionTelemetryProtocol.Batch? = null
                        fun expect(type: Type): Message {
                            while (true) {
                                val message = read(peer)
                                if (message.type == Type.STATUS &&
                                    message.json().optString("state") == "session-telemetry") {
                                    val batch = dev.redrain.cast.diagnostics.SessionTelemetryProtocol.parse(message.payload)
                                    if (batch.final) final = batch
                                } else {
                                    assertEquals(type, message.type)
                                    return message
                                }
                            }
                        }
                        var captureId: String? = null
                        if (live) {
                            recorder.grantConsent()
                            captureId = recorder.start("receiver", 77, setOf("transport.health"), 10_000, 100)
                                .getString("captureId")
                        }
                        assertEquals(live, recorder.collecting)
                        repeat(7) { nonce ->
                            send(peer, Type.PING, JSONObject().put("nonce", nonce), 77)
                            expect(Type.PONG)
                        }
                        if (live) {
                            recorder.end(checkNotNull(captureId))
                            val records = recorder.fetch(checkNotNull(captureId), 0, 32).getJSONArray("records")
                            val summaries = (0 until records.length()).map { records.getJSONObject(it) }.filter {
                                it.optString("kind") == "TRANSPORT_HEALTH" &&
                                    it.optJSONObject("data")?.optString("point") == "read" &&
                                    it.optJSONObject("data")?.optJSONObject("metrics")
                                        ?.optString("schema") == "operation_summary"
                            }.map { it.getJSONObject("data").getJSONObject("metrics") }
                            assertTrue(summaries.isNotEmpty())
                            val started = summaries.sumOf { it.getString("started").toLong() }
                            val completed = summaries.sumOf { it.getString("completed").toLong() }
                            assertTrue("Unexpected read completions: $completed", completed in 6L..7L)
                            assertTrue("Unexpected read starts: $started, completed: $completed",
                                started in (completed - 1)..(completed + 1))
                            assertEquals(0L, summaries.sumOf { it.getString("zeroOperationEvents").toLong() })
                        }
                        send(peer, Type.STOP, JSONObject().put("reason", "done"), 77)
                        val terminal = expect(Type.SESSION_END)
                        val batch = checkNotNull(final)
                        assertEquals(batch.sequence.toString(), terminal.json().getString("finalSeq"))
                        val reads = batch.state.filter {
                            it.kind == dev.redrain.cast.diagnostics.DiagnosticKind.TRANSPORT_HEALTH.ordinal + 1 &&
                                it.track == 0 &&
                                it.detail[3] == dev.redrain.cast.diagnostics.DiagnosticPoint.READ.ordinal.toLong()
                        }
                        assertEquals(1, reads.size)
                        assertTrue(reads.single().detail[4] > 0)
                    }
                }
            }
        } finally { recorder.revoke() }
    }

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
        val admitted = CountDownLatch(1)
        val revoked = CountDownLatch(1)
        val ended = CountDownLatch(1)
        var fail = false
        override fun admitted(session: Long, requested: Boolean, ownerActive: () -> Boolean, report: (String) -> Unit) {
            assertTrue(ownerActive())
            admissions.add(session to requested)
            admitted.countDown()
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
    private fun hello(peer: Socket, diagnostics: Any? = DiagnosticDelegation.capability(),
                      telemetry: Boolean = false): Message {
        val hello = JSONObject().put("role", "sender").put("versions", JSONArray().put(3)).put("name", "Phone")
        if (diagnostics != null) hello.put("diagnostics", diagnostics)
        if (telemetry) hello.put("sessionTelemetry", dev.redrain.cast.diagnostics.SessionTelemetryProtocol.capability())
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

    @Test fun statisticsAndDiagnosticRequestsAreIndependent() {
        for (stats in listOf(false, true)) for (diagnostics in listOf(false, true)) {
            val access = Access()
            ReceiverServer.forProtocolTests("Receiver", supportsPlaybackStatistics = true,
                diagnosticSessionAccess = access, diagnosticDiscovery = { DiagnosticDiscovery(43210) }).use { server ->
                server.start()
                connect(server).use { peer ->
                    val offer = JSONObject().put("role", "sender").put("versions", JSONArray().put(3))
                        .put("name", "Phone").put("diagnostics", DiagnosticDelegation.capability())
                        .put("receiverOptions", ReceiverOptionProtocol.IMPLEMENTED.wireValue())
                    send(peer, Type.HELLO, offer)
                    assertEquals(Type.HELLO, read(peer).type)
                    val request = settings(if (diagnostics) true else null)
                    if (stats) request.put("receiverOptions", JSONObject().put("version", 1).put("playbackStats", true))
                    send(peer, Type.SESSION_REQUEST, request, 77)
                    assertEquals(Type.SESSION_ACCEPT, read(peer).type)
                    if (diagnostics) assertEquals(Type.STATUS, read(peer).type)
                    assertTrue(access.admitted.await(2, TimeUnit.SECONDS))
                    assertEquals(listOf(77L to diagnostics), access.admissions)
                    assertEquals(stats, server.playbackStatisticsRequested)
                    send(peer, Type.STOP, JSONObject().put("reason", "done"), 77)
                    assertEquals(Type.SESSION_END, read(peer).type)
                }
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

    @Test fun senderReceiverLinkDoesNotNegotiateLegacyReceiverEndpoint() {
        for (access in listOf<Access?>(null, Access())) {
            ReceiverServer.forProtocolTests("Receiver", diagnosticSessionAccess = access).use { server ->
                server.start()
                ReceiverLink(ReceiverTarget("127.0.0.1", server.listeningPort, "Receiver")).use { link ->
                    link.connect()
                    link.send(dev.screencopy.sender.Wire.control(dev.screencopy.sender.Wire.SESSION_REQUEST,
                        77, settings(enabled = null)))
                    val accepted = link.receive()
                    assertEquals(dev.screencopy.sender.Wire.SESSION_ACCEPT, accepted.type)
                    assertFalse(accepted.json().has("diagnostic"))
                    if (access != null) {
                        assertTrue(access.admitted.await(3, TimeUnit.SECONDS))
                        assertEquals(listOf(77L to false), access.admissions)
                    }
                    link.send(dev.screencopy.sender.Wire.control(dev.screencopy.sender.Wire.STOP,
                        77, JSONObject().put("reason", "done")))
                    assertEquals(dev.screencopy.sender.Wire.SESSION_END, link.receive().type)
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
                assertTrue(access.admitted.await(3, TimeUnit.SECONDS))
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

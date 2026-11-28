package dev.redrain.cast.diagnostics

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import java.net.InetAddress
import java.net.Socket
import java.net.InetSocketAddress
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal fun Socket.reserveDistinctSource(address: InetAddress, destinationPort: Int) {
    bind(InetSocketAddress(address, 0))
    assertNotEquals("Shutdown probe must have a distinct source port", destinationPort, localPort)
}

internal fun Socket.assertConnectionRefused(address: InetAddress, destinationPort: Int) {
    assertThrows(java.net.ConnectException::class.java) {
        connect(InetSocketAddress(address, destinationPort), 1000)
        fail("Unexpected TCP connection: local=$localSocketAddress remote=$remoteSocketAddress")
    }
}

class DiagnosticListenerTest {
    private var sequence = 0
    private fun listener(recorder: DiagnosticRecorder, disconnected: () -> Unit = {}) =
        DiagnosticListener(recorder, DiagnosticIdentity(InetAddress.getLoopbackAddress()), disconnected)
    private fun client(server: DiagnosticListener) =
        Socket(InetAddress.getLoopbackAddress(), server.discovery!!.port).apply {
            soTimeout = 7000
            receiveBufferSize = 1024
        }
    private fun command(client: Socket, op: String, args: JSONObject = JSONObject()): JSONObject {
        val request = JSONObject().put("v", 2).put("id", "r${++sequence}").put("op", op).put("args", args)
        client.outputStream.write((request.toString() + "\n").toByteArray())
        client.outputStream.flush()
        return JSONObject(String(checkNotNull(DiagnosticProtocol.readLine(client.inputStream))))
    }
    private fun start(client: Socket): String = command(client, "START", JSONObject().put("target",
        JSONObject().put("nextSession", true))
        .put("senderFlags", JSONArray(listOf("session"))).put("receiverFlags", JSONArray(listOf("session")))
        .put("durationMs", 10000).put("samplePeriodMs", 1000))
        .getJSONObject("result").getString("pairId")

    @Test fun duplicateAndRateLimitErrorsAreBudgetedOnWireAndReleaseCollector() {
        val closed = CountDownLatch(1)
        DiagnosticListener(DiagnosticRecorder(), DiagnosticIdentity(InetAddress.getLoopbackAddress()),
            collectorClosed = { closed.countDown() }, protocolClock = { 1_000_000_000L }).use { server ->
            client(server).use { first ->
                val request = JSONObject().put("v", 2).put("id", "same").put("op", "HELLO")
                    .put("args", JSONObject()).toString() + "\n"
                var bytes = 0
                var count = 0
                var duplicate = 0
                var limited = 0
                while (true) {
                    first.outputStream.write(request.toByteArray(Charsets.UTF_8))
                    first.outputStream.flush()
                    val line = DiagnosticProtocol.readLine(first.inputStream) ?: break
                    bytes += line.size + 1
                    count++
                    val response = JSONObject(String(line, Charsets.UTF_8))
                    if (!response.getBoolean("ok")) when (response.getJSONObject("error").getString("code")) {
                        "DUPLICATE_ID" -> duplicate++
                        "RATE_LIMITED" -> limited++
                        else -> fail("unexpected diagnostic error")
                    }
                }
                assertEquals(19, duplicate)
                assertTrue(limited > 0)
                assertTrue(count <= 256)
                assertTrue(bytes <= 128 * 1024)
            }
            assertTrue(closed.await(3, TimeUnit.SECONDS))
            client(server).use { next -> assertTrue(command(next, "HELLO").getBoolean("ok")) }
        }
    }

    @Test fun pythonCollectorPersistsBootstrapAndFinalStatusFromJvmListener() {
        val recorder = DiagnosticRecorder()
        val registry = DiagnosticArmRegistry(recorder)
        val python = System.getenv("DIAGNOSTIC_TEST_PYTHON") ?: "python"
        val output = File("build", "paired-python-${java.util.UUID.randomUUID()}")
        val collector = File("..", "tools${File.separator}collect-diagnostics.py").absoluteFile
        val reader = Executors.newSingleThreadExecutor()
        try {
            DiagnosticListener(recorder, DiagnosticIdentity(InetAddress.getLoopbackAddress()),
                armRegistry = registry).use { server ->
                val process = ProcessBuilder(python, "-B", collector.path, "--lan", "--sender", server.endpoint,
                    "--sender-flags", "session", "--receiver-flags", "session", "--duration", "10",
                    "--output", output.absolutePath).redirectErrorStream(true).start()
                try {
                    val lines = process.inputStream.bufferedReader()
                    val armed = reader.submit<Boolean> {
                        while (true) {
                            val line = lines.readLine() ?: return@submit false
                            if (line.contains("Capture armed")) return@submit true
                        }
                        @Suppress("UNREACHABLE_CODE") false
                    }
                    assertTrue("Python collector did not arm", armed.get(8, TimeUnit.SECONDS))
                    val claim = checkNotNull(registry.claim(77))
                    val stages = JSONArray()
                    org.screencopy.receiver.protocol.DiagnosticProxyNegotiation.stages.forEachIndexed { index, stage ->
                        stages.put(JSONObject().put("stage", stage).put("monoNs", "$index"))
                    }
                    val bootstrap = JSONObject().put("session", "77").put("pairId", claim.pairId)
                        .put("captureId", "receiver").put("clockId", "receiver-clock").put("complete", true)
                        .put("loss", false).put("stages", stages)
                    val intake = DiagnosticProxyIntake("77", claim.pairId, "receiver", "receiver-clock",
                        false, bootstrap)
                    registry.covered(claim, intake, "receiver", "receiver-clock")
                    val record = JSONObject().put("captureId", "receiver").put("seq", "1").put("monoNs", "1")
                        .put("kind", "SESSION").put("role", "receiver").put("session", "77")
                        .put("data", JSONObject().put("stage", "relayed"))
                    assertTrue(intake.record(record, 256))
                    intake.consume()
                    val outcome = JSONObject().put("reason", "receiver_retired_stop").put("endedNs", "2")
                        .put("mediaRetirement", "succeeded").put("ownershipCleanup", "unknown")
                        .put("tailTruncated", false).put("captureEnd", JSONObject().put("atNs", "2")
                            .put("reason", "receiver_retired_stop").put("complete", true).put("truncated", false))
                    intake.terminal(JSONObject(outcome.toString()), true, outcome.toString())
                    intake.consume()
                    registry.receiverEnded(claim, true)
                    claim.senderFinished.set(true)
                    registry.senderEnded(claim)
                    assertTrue("Python collector did not finish", process.waitFor(10, TimeUnit.SECONDS))
                    assertEquals(lines.readText(), 0, process.exitValue())
                    val evidence = File(output, "paired.jsonl").readLines().map { JSONObject(it) }
                    assertTrue(evidence.any { it.getString("type") == "event" &&
                        it.getJSONObject("value").optString("role") == "receiver" })
                    val statuses = evidence.filter { it.getString("type") == "status" }
                    assertTrue(statuses.any { it.getJSONObject("value").getJSONObject("bootstrap")
                        .getJSONArray("stages").length() == 8 })
                    assertEquals("receiver_retired_stop", statuses.last().getJSONObject("value")
                        .getJSONObject("receiverRole").getJSONObject("outcome").getString("reason"))
                } finally {
                    if (process.isAlive) process.destroyForcibly()
                }
            }
        } finally {
            reader.shutdownNow()
            output.deleteRecursively()
        }
    }

    @Test fun plainTcpHelloExposesCapabilitiesButCollectsOnlyAfterStartAndEndFreezes() {
        val recorder = DiagnosticRecorder()
        listener(recorder).use { server ->
            client(server).use { client ->
                val hello = command(client, "HELLO").getJSONObject("result")
                assertEquals("lan", hello.getString("access"))
                assertEquals(recorder.clockId, hello.getString("clockId"))
                assertTrue(hello.has("senderFlags"))
                assertTrue(hello.has("receiverFlags"))
                assertFalse(hello.toString().contains("secret"))
                assertFalse(hello.toString().contains("fingerprint"))
                assertFalse(recorder.collecting)
                val connections = command(client, "CONNECTIONS").getJSONObject("result")
                assertEquals("idle", connections.getString("state"))
                assertFalse(connections.has("host"))
                assertFalse(recorder.collecting)
                val id = start(client)
                assertTrue(recorder.collecting)
                command(client, "END", JSONObject().put("pairId", id))
                assertFalse(recorder.collecting)
                assertEquals("frozen", command(client, "STATUS").getJSONObject("result").getString("state"))
                assertTrue(command(client, "END", JSONObject().put("pairId", id)).getBoolean("ok"))
            }
        }
    }

    @Test fun addressSpecificHintsAndHelloMatchTheActualBoundSocket() {
        val address = InetAddress.getByName("127.0.0.2")
        DiagnosticListener(DiagnosticRecorder(), DiagnosticIdentity(address)).use { server ->
            assertNull(server.discoveryFor(InetAddress.getByName("127.0.0.1")))
            assertNull(server.discoveryFor(InetAddress.getByName("::1")))
            val port = checkNotNull(server.discoveryFor(address)).port
            Socket(address, port).use { client ->
                client.soTimeout = 3000
                val hello = command(client, "HELLO").getJSONObject("result")
                assertEquals(1, hello.getInt("pairedNextCast"))
                assertEquals(2, checkNotNull(server.discoveryFor(address)).version)
            }
        }
    }

    @Test fun stateBeforeHelloAndLegacyAuthAreRejectedWithoutStateDisclosure() {
        listener(DiagnosticRecorder()).use { server ->
            client(server).use { client ->
                val response = command(client, "STATUS")
                assertEquals("HELLO_REQUIRED", response.getJSONObject("error").getString("code"))
                assertFalse(response.has("result"))
                assertEquals(-1, client.inputStream.read())
            }
            client(server).use { client ->
                command(client, "HELLO")
                assertEquals("UNKNOWN_OPERATION", command(client, "AUTH").getJSONObject("error").getString("code"))
            }
        }
    }

    @Test fun onlyOneHelloClientCanOwnDiagnosticCommands() {
        listener(DiagnosticRecorder()).use { server ->
            client(server).use { first ->
                assertTrue(command(first, "HELLO").getBoolean("ok"))
                client(server).use { second ->
                    assertEquals("BUSY", command(second, "HELLO").getJSONObject("error").getString("code"))
                }
                assertTrue(command(first, "PING").getBoolean("ok"))
            }
        }
    }

    @Test fun helloDeadlineClosesIdlePlainTcpClient() {
        listener(DiagnosticRecorder()).use { server ->
            client(server).use { assertEquals(-1, it.inputStream.read()) }
        }
    }

    @Test fun onlyTwoPendingHelloConnectionsAreAdmitted() {
        listener(DiagnosticRecorder()).use { server ->
            client(server).use { first ->
                client(server).use { second ->
                    client(server).use { third ->
                        assertEquals(-1, third.inputStream.read())
                        assertTrue(command(first, "HELLO").getBoolean("ok"))
                        assertEquals("BUSY", command(second, "HELLO").getJSONObject("error").getString("code"))
                    }
                }
            }
        }
    }

    @Test fun discoverySnapshotsReflectPermissionRevokeCloseAndNeverStartRecording() {
        val allowed = AtomicBoolean(true)
        val recorder = DiagnosticRecorder()
        DiagnosticListener(recorder, DiagnosticIdentity(InetAddress.getLoopbackAddress()),
            accessAllowed = allowed::get).use { server ->
            val hint = checkNotNull(server.discovery)
            assertEquals(server.endpoint.substringAfterLast(':').toInt(), hint.port)
            assertEquals(setOf("version", "port", "access"), hint.json().keys().asSequence().toSet())
            assertEquals("lan", hint.json().getString("access"))
            assertFalse(recorder.collecting)
            allowed.set(false)
            assertNull(server.discovery)
            server.revokeNetwork()
            assertNull(server.discovery)
        }
        assertNull(DiagnosticAccess.discovery)
        assertFalse(DiagnosticAccess.enabled)
        assertTrue(DiagnosticAccess.warning.contains("LAN or VPN"))
        assertTrue(DiagnosticAccess.warning.contains("unencrypted and unauthenticated"))
    }

    @Test fun concurrentDiscoveryAndCloseCannotThrowOrReturnInvalidPort() {
        val server = listener(DiagnosticRecorder())
        val start = CountDownLatch(1)
        val reader = Executors.newSingleThreadExecutor()
        try {
            val completed = reader.submit {
                start.await()
                repeat(10000) { server.discovery?.let { assertTrue(it.port in 1..65535) } }
            }
            start.countDown()
            server.close()
            completed.get(2, TimeUnit.SECONDS)
            assertNull(server.discovery)
        } finally { server.close(); reader.shutdownNow() }
    }

    @Test fun concurrentRevokeWaitsForAllNetworkResourcesToClose() {
        concurrentShutdownWaitsForNetworkCleanup(close = false)
    }

    @Test fun concurrentCloseWaitsForAllNetworkResourcesToClose() {
        concurrentShutdownWaitsForNetworkCleanup(close = true)
    }

    private fun concurrentShutdownWaitsForNetworkCleanup(close: Boolean) {
        val revoking = CountDownLatch(1)
        val release = CountDownLatch(1)
        val registry = Mockito.mock(DiagnosticArmRegistry::class.java)
        Mockito.doAnswer {
            revoking.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "Revocation was not released" }
            null
        }.`when`(registry).revoke()
        val first = InetAddress.getByName("127.0.0.1")
        val second = InetAddress.getByName("127.0.0.2")
        val identity = DiagnosticIdentity(first, endpoints = listOf(
            DiagnosticIdentity.Endpoint(first, false), DiagnosticIdentity.Endpoint(second, true)))
        val server = DiagnosticListener(DiagnosticRecorder(), identity,
            discoveryPort = 0, armRegistry = registry)
        val callers = Executors.newFixedThreadPool(2)
        try {
            val port = checkNotNull(server.discovery).port
            Socket().use { firstProbe -> Socket().use { secondProbe ->
                firstProbe.reserveDistinctSource(first, port)
                secondProbe.reserveDistinctSource(second, port)
                client(server).use { collector ->
                    command(collector, "HELLO")
                    val owner = callers.submit { server.revokeNetwork() }
                    assertTrue(revoking.await(3, TimeUnit.SECONDS))
                    assertNull(server.discovery)
                    val calling = CountDownLatch(1)
                    val concurrent = callers.submit {
                        calling.countDown()
                        if (close) server.close() else server.revokeNetwork()
                    }
                    assertTrue(calling.await(3, TimeUnit.SECONDS))
                    assertThrows(java.util.concurrent.TimeoutException::class.java) {
                        concurrent.get(200, TimeUnit.MILLISECONDS)
                    }
                    release.countDown()
                    concurrent.get(3, TimeUnit.SECONDS)
                    owner.get(3, TimeUnit.SECONDS)
                    assertEquals(-1, collector.inputStream.read())
                    firstProbe.assertConnectionRefused(first, port)
                    secondProbe.assertConnectionRefused(second, port)
                    assertNull(server.endpointDiscoveryPort)
                    Mockito.verify(registry, Mockito.times(1)).revoke()
                    server.revokeNetwork()
                    Mockito.verify(registry, Mockito.times(1)).revoke()
                }
            } }
        } finally {
            release.countDown()
            callers.shutdown()
            try {
                assertTrue(callers.awaitTermination(3, TimeUnit.SECONDS))
            } finally { server.close() }
        }
    }

    @Test fun disconnectFreezesAndReconnectFetchesWithoutResuming() {
        val recorder = DiagnosticRecorder()
        val disconnected = CountDownLatch(1)
        listener(recorder) { disconnected.countDown() }.use { server ->
            val id = client(server).use { command(it, "HELLO"); start(it) }
            assertTrue(disconnected.await(3, TimeUnit.SECONDS))
            assertFalse(recorder.collecting)
            client(server).use { client ->
                command(client, "HELLO")
                assertEquals("frozen", command(client, "STATUS").getJSONObject("result").getString("state"))
                assertTrue(command(client, "FETCH", JSONObject().put("pairId", id)
                    .put("afterSenderSeq", "0").put("afterReceiverSeq", "0").put("limit", 32))
                    .getJSONObject("result").getJSONArray("records").length() >= 2)
                assertFalse(recorder.collecting)
            }
        }
    }

    @Test fun nonreadingClientCannotHoldCapturePastWriteDeadline() {
        val recorder = DiagnosticRecorder()
        val disconnected = CountDownLatch(1)
        val protocolTime = java.util.concurrent.atomic.AtomicLong()
        DiagnosticListener(recorder, DiagnosticIdentity(InetAddress.getLoopbackAddress()),
            collectorClosed = { disconnected.countDown() },
            protocolClock = { protocolTime.getAndAdd(1_000_000_000L) }).use { server ->
            client(server).use { client ->
                command(client, "HELLO")
                start(client)
                val writer = Thread {
                    try {
                        repeat(4096) { index ->
                            client.outputStream.write("""{"v":2,"id":"flood$index","op":"STATUS","args":{}}""".toByteArray() + byteArrayOf(10))
                        }
                        client.outputStream.flush()
                    } catch (_: java.io.IOException) {}
                }.apply { start() }
                try {
                    assertTrue(disconnected.await(8, TimeUnit.SECONDS))
                    assertFalse(recorder.collecting)
                    assertEquals("slow_client", recorder.status().getString("endReason"))
                } finally { client.close(); writer.join(2000); assertFalse(writer.isAlive) }
            }
        }
    }

    @Test fun permissionLossRevokesWithoutActivityOrAnotherCommand() {
        val recorder = DiagnosticRecorder()
        val allowed = AtomicBoolean(true)
        val revoked = CountDownLatch(1)
        val owner = AtomicReference<DiagnosticListener>()
        DiagnosticListener(recorder, DiagnosticIdentity(InetAddress.getLoopbackAddress()),
            accessAllowed = allowed::get, accessRevoked = {
                owner.get()?.revokeNetwork()
                revoked.countDown()
            }).use { server ->
            owner.set(server)
            client(server).use { client ->
                command(client, "HELLO")
                start(client)
                allowed.set(false)
                assertTrue(revoked.await(2, TimeUnit.SECONDS))
                assertFalse(recorder.collecting)
                assertNull(server.discovery)
                assertEquals("local_disabled", recorder.lastEndReason)
            }
        }
    }

    @Test fun oldVersionDoesNotAcquireTheCollectorSlot() {
        val recorder = DiagnosticRecorder()
        listener(recorder).use { server ->
            client(server).use { old ->
                old.outputStream.write("""{"v":1,"id":"old","op":"HELLO","args":{}}""".toByteArray() + byteArrayOf(10))
                val response = JSONObject(String(checkNotNull(DiagnosticProtocol.readLine(old.inputStream))))
                assertEquals("UNSUPPORTED_VERSION", response.getJSONObject("error").getString("code"))
                assertEquals(-1, old.inputStream.read())
            }
            client(server).use { current ->
                assertTrue(command(current, "HELLO").getBoolean("ok"))
                assertFalse(recorder.collecting)
            }
        }
    }
}

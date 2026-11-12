package dev.redrain.cast.diagnostics

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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
        val request = JSONObject().put("v", 1).put("id", "r${++sequence}").put("op", op).put("args", args)
        client.outputStream.write((request.toString() + "\n").toByteArray())
        client.outputStream.flush()
        return JSONObject(String(checkNotNull(DiagnosticProtocol.readLine(client.inputStream))))
    }
    private fun start(client: Socket): String = command(client, "START", JSONObject().put("target",
        JSONObject().put("role", "sender").put("nextSession", true))
        .put("flags", JSONArray(listOf("session"))).put("durationMs", 10000).put("samplePeriodMs", 1000))
        .getJSONObject("result").getString("captureId")

    @Test fun plainTcpHelloExposesCapabilitiesButCollectsOnlyAfterStartAndEndFreezes() {
        val recorder = DiagnosticRecorder()
        listener(recorder).use { server ->
            client(server).use { client ->
                val hello = command(client, "HELLO").getJSONObject("result")
                assertEquals("lan", hello.getString("access"))
                assertEquals(recorder.clockId, hello.getString("clockId"))
                assertTrue(hello.has("capabilities"))
                assertFalse(hello.toString().contains("secret"))
                assertFalse(hello.toString().contains("fingerprint"))
                assertFalse(recorder.collecting)
                val connections = command(client, "CONNECTIONS").getJSONObject("result")
                val own = connections.getJSONObject("self")
                assertEquals(server.discovery!!.port, own.getInt("port"))
                assertEquals(client.inetAddress.hostAddress, own.getString("host"))
                assertFalse(recorder.collecting)
                val id = start(client)
                assertTrue(recorder.collecting)
                command(client, "END", JSONObject().put("captureId", id))
                assertFalse(recorder.collecting)
                assertEquals("frozen", command(client, "STATUS").getJSONObject("result").getString("state"))
                assertTrue(command(client, "END", JSONObject().put("captureId", id)).getBoolean("ok"))
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
                val self = command(client, "HELLO").getJSONObject("result")
                    .getJSONObject("connections").getJSONObject("self")
                assertEquals(address.hostAddress, self.getString("host"))
                assertEquals(port, self.getInt("port"))
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
        assertTrue(DiagnosticAccess.warning.contains("Anyone on this LAN"))
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
                assertTrue(command(client, "FETCH", JSONObject().put("captureId", id).put("afterSeq", "0").put("limit", 32))
                    .getJSONObject("result").getJSONArray("records").length() >= 2)
                assertFalse(recorder.collecting)
            }
        }
    }

    @Test fun nonreadingClientCannotHoldCapturePastWriteDeadline() {
        val recorder = DiagnosticRecorder()
        val disconnected = CountDownLatch(1)
        listener(recorder) { disconnected.countDown() }.use { server ->
            client(server).use { client ->
                command(client, "HELLO")
                start(client)
                val writer = Thread {
                    try {
                        repeat(4096) { index ->
                            client.outputStream.write("""{"v":1,"id":"flood$index","op":"PING","args":{}}""".toByteArray() + byteArrayOf(10))
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

    @Test fun pythonCollectorCapturesBothActualJvmLanListenersWithoutPairing() {
        val directory = File("build", "diagnostics-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val sender = DiagnosticRecorder().apply { sessionStarted("sender", Long.MAX_VALUE) }
            val receiver = DiagnosticRecorder().apply { sessionStarted("receiver", Long.MAX_VALUE) }
            listener(sender).use { sending ->
                val delegated = DelegatedDiagnostics(receiver, java.util.concurrent.Executor { it.run() },
                    { true }, { false }, { allowed, maintenance ->
                        DiagnosticListener(receiver, DiagnosticIdentity(InetAddress.getLoopbackAddress()),
                            accessAllowed = allowed, accessRevoked = maintenance)
                    })
                try {
                    delegated.admitted(Long.MAX_VALUE, true, { true }, {})
                    DiagnosticAccess.connections.begin(Long.MAX_VALUE, "127.0.0.1", true, true)
                    DiagnosticAccess.connections.update(Long.MAX_VALUE, JSONObject().put("diagnostic",
                        org.screencopy.receiver.protocol.DiagnosticDelegation.result("enabled", Long.MAX_VALUE,
                            checkNotNull(delegated.discovery))))
                    assertFalse(sender.collecting)
                    assertFalse(receiver.collecting)
                    val script = """
import asyncio, importlib.util, json, pathlib, sys
root, output, sending = sys.argv[1:]
spec = importlib.util.spec_from_file_location("diagnostic_collector", pathlib.Path(root) / "tools" / "collect-diagnostics.py")
collector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(collector)
assert collector.main(["--lan", "--sender", sending, "--follow-receiver",
                       "--duration", "1", "--output", output]) == 0
clock_ids = set()
for role in ("sender", "receiver"):
    rows = [json.loads(line) for line in (pathlib.Path(output) / (role + ".jsonl")).read_text().splitlines()]
    metadata = next(row["value"] for row in rows if row["type"] == "capture_metadata")
    assert metadata["role"] == role
    assert metadata["access"] == "unauthenticated_lan", metadata
    clock_ids.add(metadata["clockId"])
    assert len([row for row in rows if row["type"] == "clock_sample"]) == 10
    events = [row["value"] for row in rows if row["type"] == "event"]
    assert any(event["kind"] == "CAPTURE_STARTED" for event in events)
    assert any(event["kind"] == "SESSION_BOUND" and event["session"] == str(2**63-1) for event in events)
    assert any(event["kind"] == "CAPTURE_ENDED" for event in events)
    assert any(row["type"] == "ended" for row in rows)
assert len(clock_ids) == 2
print("actual JVM plain LAN paired collector integration passed")
""".trimIndent()
                    val scriptFile = File(directory, "integration.py").apply { writeText(script) }
                    val sharedPython = File(System.getenv("LOCALAPPDATA") ?: "", "Copilot\\Toolchains\\python-3.13.9-arm64\\python.exe")
                    val python = System.getenv("DIAGNOSTICS_TEST_PYTHON")
                        ?: if (sharedPython.exists()) sharedPython.path else "python3"
                    val process = ProcessBuilder(python, scriptFile.path, File("..").canonicalPath,
                        directory.canonicalPath, sending.endpoint)
                        .redirectErrorStream(true).start()
                    try {
                        assertTrue("Collector did not finish", process.waitFor(30, TimeUnit.SECONDS))
                        val output = process.inputStream.bufferedReader().readText()
                        assertEquals(output, 0, process.exitValue())
                    } finally { if (process.isAlive) process.destroyForcibly() }
                    assertEquals("frozen", sender.status().getString("state"))
                    assertEquals("frozen", receiver.status().getString("state"))
                    assertEquals("${Long.MAX_VALUE}", receiver.scope()?.getString("session"))
                } finally { delegated.reset(); DiagnosticAccess.connections.revoke() }
            }
        } finally { directory.deleteRecursively() }
    }
}

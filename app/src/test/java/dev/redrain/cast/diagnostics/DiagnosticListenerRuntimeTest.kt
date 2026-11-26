package dev.redrain.cast.diagnostics

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.util.ReflectionHelpers
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@ConscryptMode(ConscryptMode.Mode.OFF)
class DiagnosticListenerRuntimeTest {
    private var sequence = 0
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun drainControlWorker() {
        ReflectionHelpers.getField<ExecutorService>(DiagnosticAccess, "worker")
            .submit {}.get(3, TimeUnit.SECONDS)
    }

    private fun enable(): Int {
        DiagnosticAccess.setEnabled(context, true) {
            DiagnosticIdentity(InetAddress.getLoopbackAddress())
        }
        drainControlWorker()
        assertTrue(DiagnosticAccess.enabled)
        return checkNotNull(DiagnosticAccess.discovery).port
    }

    private fun client(port: Int) = Socket().apply {
        soTimeout = 3000
        connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1000)
    }

    private fun command(client: Socket, op: String, args: JSONObject = JSONObject()): JSONObject {
        val request = JSONObject().put("v", 2).put("id", "r${++sequence}").put("op", op).put("args", args)
        client.outputStream.write((request.toString() + "\n").toByteArray(Charsets.UTF_8))
        client.outputStream.flush()
        return JSONObject(String(checkNotNull(DiagnosticProtocol.readLine(client.inputStream)), Charsets.UTF_8))
            .also { assertTrue(it.toString(), it.getBoolean("ok")) }.getJSONObject("result")
    }

    private fun disconnect(client: Socket) {
        client.shutdownOutput()
        // EOF is sent only after the server has released ownership and run its cleanup.
        assertEquals(-1, client.inputStream.read())
    }

    private fun disable() {
        DiagnosticAccess.setEnabled(context, false)
        drainControlWorker()
    }

    @Test fun productionHelloDisconnectKeepsConsentGenerationAndListenerUntilExplicitDisable() {
        val port = enable()
        val generation = DiagnosticAccess.consentGeneration
        val disabled = AtomicInteger()
        val observer = DiagnosticAccess.onDisabled(generation) { disabled.incrementAndGet() }
        try {
            client(port).use {
                command(it, "HELLO")
                disconnect(it)
            }

            assertTrue(DiagnosticAccess.enabled)
            assertEquals(generation, DiagnosticAccess.consentGeneration)
            assertEquals(0, disabled.get())
            assertEquals(port, checkNotNull(DiagnosticAccess.discovery).port)
            assertFalse(RemoteDiagnostics.recorder.collecting)
            client(port).use {
                command(it, "HELLO")
                assertEquals("idle", command(it, "STATUS").getString("state"))
                DiagnosticAccess.setEnabled(context, false)
                assertEquals(-1, it.inputStream.read())
            }
            assertFalse(DiagnosticAccess.enabled)
            assertNull(DiagnosticAccess.discovery)
            assertEquals(1, disabled.get())
        } finally {
            observer.close()
            disable()
        }
    }

    @Test fun networkChangeRevokesListenerAndClosesCollectorWithoutRebinding() {
        val valid = AtomicBoolean(true)
        val disabled = CountDownLatch(1)
        DiagnosticAccess.setEnabled(context, true) {
            DiagnosticIdentity(InetAddress.getLoopbackAddress(), isValid = valid::get)
        }
        drainControlWorker()
        val port = checkNotNull(DiagnosticAccess.discovery).port
        val observer = DiagnosticAccess.onDisabled(DiagnosticAccess.consentGeneration) { disabled.countDown() }
        try {
            client(port).use {
                command(it, "HELLO")
                valid.set(false)
                assertNull(DiagnosticAccess.discovery)
                assertTrue(disabled.await(3, TimeUnit.SECONDS))
                assertEquals(-1, it.inputStream.read())
                drainControlWorker()
                assertFalse(DiagnosticAccess.enabled)
                assertNull(DiagnosticAccess.discovery)
                assertThrows(java.io.IOException::class.java) { client(port).close() }
            }
        } finally {
            observer.close()
            disable()
        }
    }

    @Test fun productionCaptureDisconnectRetainsFrozenFetchAndExplicitDisableClearsAndCloses() {
        val port = enable()
        try {
            val id = client(port).use {
                command(it, "HELLO")
                val capture = command(it, "START", JSONObject().put("target",
                    JSONObject().put("nextSession", true))
                    .put("senderFlags", JSONArray(listOf("session"))).put("receiverFlags", JSONArray(listOf("session")))
                    .put("durationMs", 10000).put("samplePeriodMs", 1000)).getString("pairId")
                assertTrue(RemoteDiagnostics.recorder.collecting)
                disconnect(it)
                capture
            }
            assertTrue(DiagnosticAccess.enabled)
            assertFalse(RemoteDiagnostics.recorder.collecting)
            client(port).use {
                command(it, "HELLO")
                val status = command(it, "STATUS")
                assertEquals("frozen", status.getString("state"))
                assertEquals(id, status.getString("pairId"))
                assertEquals("client_disconnected", status.getString("endReason"))
                val records = command(it, "FETCH", JSONObject().put("pairId", id)
                    .put("afterSenderSeq", "0").put("afterReceiverSeq", "0").put("limit", 32)).getJSONArray("records")
                assertTrue(records.length() >= 2)
                assertFalse(RemoteDiagnostics.recorder.collecting)
                DiagnosticAccess.setEnabled(context, false)
                assertEquals(-1, it.inputStream.read())
            }
            drainControlWorker()
            assertFalse(DiagnosticAccess.enabled)
            assertNull(DiagnosticAccess.discovery)
            assertEquals("idle", RemoteDiagnostics.recorder.status().getString("state"))
            assertTrue(RemoteDiagnostics.recorder.status().isNull("captureId"))
            assertThrows(java.io.IOException::class.java) { client(port).close() }
        } finally { disable() }
    }

    @Test fun localBindPublishesEndpointOnlyWhileStillEnabled() {
        for (race in listOf("complete", "disabled", "failed")) {
            val binding = CountDownLatch(1)
            val release = CountDownLatch(1)
            try {
                DiagnosticAccess.setEnabled(context, true) {
                    binding.countDown()
                    check(release.await(5, TimeUnit.SECONDS)) { "Binding was not released" }
                    if (race == "failed") throw java.io.IOException("Synthetic bind failure")
                    DiagnosticIdentity(InetAddress.getLoopbackAddress())
                }
                assertTrue(binding.await(3, TimeUnit.SECONDS))
                assertTrue(DiagnosticAccess.enabled)
                assertNull(DiagnosticAccess.discovery)
                if (race == "disabled") DiagnosticAccess.setEnabled(context, false)
                release.countDown()
                drainControlWorker()
                if (race == "complete") assertNotNull(DiagnosticAccess.discovery)
                else {
                    assertFalse(DiagnosticAccess.enabled)
                    assertNull(DiagnosticAccess.discovery)
                }
            } finally {
                release.countDown()
                disable()
            }
        }
    }
}

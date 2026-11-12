package dev.redrain.cast.diagnostics

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.screencopy.receiver.protocol.DiagnosticDelegation
import org.screencopy.receiver.protocol.DiagnosticDiscovery

class DiagnosticConnectionsTest {
    private fun report(state: String = "enabled", session: Long = Long.MAX_VALUE, port: Int? = 34567) =
        JSONObject().put("diagnostic", DiagnosticDelegation.result(state, session, port?.let(::DiagnosticDiscovery)))

    @Test fun exactPortAndConnectedHostIgnoreAdvertisedHostAndPreserveInt64() {
        val memory = DiagnosticConnections()
        memory.begin(Long.MAX_VALUE, "192.0.2.10", true, true)
        val report = report()
        report.getJSONObject("diagnostic").put("host", "malicious.example")
        memory.update(Long.MAX_VALUE, report)
        val value = memory.snapshot()
        assertEquals("192.0.2.10", value.getString("host"))
        assertEquals(34567, value.getInt("port"))
        assertEquals("${Long.MAX_VALUE}", value.getString("session"))
        assertEquals("lan", value.getString("access"))
        assertEquals(1, value.getInt("version"))
    }

    @Test fun ownerChangesRevocationAndEndNeverReuseOrRenewAnEndpoint() {
        var now = 0L
        val memory = DiagnosticConnections { now }
        memory.begin(Long.MAX_VALUE, "192.0.2.10", true, true)
        memory.update(Long.MAX_VALUE, report())
        memory.ended(8)
        assertEquals("enabled", memory.snapshot().getString("state"))
        memory.ended(Long.MAX_VALUE)
        assertEquals("frozen", memory.snapshot().getString("state"))
        now = 119_999_999_999
        memory.ended(Long.MAX_VALUE)
        assertTrue(memory.snapshot().getBoolean("available"))
        now++
        assertEquals("grace_expired", memory.snapshot().getString("reason"))
        assertFalse(memory.snapshot().has("port"))
        memory.begin(8, "192.0.2.11", true, true)
        memory.update(Long.MAX_VALUE, report())
        memory.ended(Long.MAX_VALUE)
        assertEquals("8", memory.snapshot().getString("session"))
        assertFalse(memory.snapshot().getBoolean("available"))
        memory.update(8, report(session = 8))
        assertTrue(memory.snapshot().getBoolean("available"))
        memory.revoke()
        memory.update(8, report(session = 8))
        assertFalse(memory.snapshot().getBoolean("available"))
        assertFalse(memory.snapshot().has("host"))
        assertTrue(memory.snapshot().isNull("session"))
    }

    @Test fun malformedLegacyUnsupportedAndStaleResultsCannotBecomeEndpoints() {
        val malformed = listOf(
            report(session = 8),
            report().apply { getJSONObject("diagnostic").put("port", 0) },
            report().apply { getJSONObject("diagnostic").put("port", 65536) },
            report().apply { getJSONObject("diagnostic").put("port", "34567") },
            report().apply { getJSONObject("diagnostic").put("port", true) },
            report().apply { getJSONObject("diagnostic").put("version", 1.0) },
            report().apply { getJSONObject("diagnostic").put("access", "tls") },
            report().apply { getJSONObject("diagnostic").put("session", Long.MAX_VALUE) },
            report().apply { getJSONObject("diagnostic").put("state", "starting") },
            report().apply { getJSONObject("diagnostic").remove("session") })
        for (report in malformed) {
            val memory = DiagnosticConnections()
            memory.begin(Long.MAX_VALUE, "192.0.2.10", true, true)
            memory.update(Long.MAX_VALUE, report)
            assertEquals(report.toString(), "malformed", memory.snapshot().getString("reason"))
            assertFalse(memory.snapshot().has("port"))
        }
        val memory = DiagnosticConnections()
        memory.begin(Long.MAX_VALUE, "192.0.2.10", true, false)
        assertEquals("unsupported", memory.snapshot().getString("reason"))
        memory.begin(Long.MAX_VALUE, "192.0.2.10", true, true)
        memory.update(Long.MAX_VALUE, JSONObject().put("diagnostic", DiagnosticDelegation.result("enabled")))
        assertFalse(memory.snapshot().getBoolean("available"))
        memory.update(Long.MAX_VALUE, JSONObject())
        assertEquals("unsupported", memory.snapshot().getString("state"))
        memory.ended(Long.MAX_VALUE)
        assertEquals("cast_ended", memory.snapshot().getString("reason"))
        memory.update(Long.MAX_VALUE, report())
        assertFalse(memory.snapshot().getBoolean("available"))
    }

    @Test fun receiverRevocationFreezesWithoutRenewalAndCannotReactivate() {
        var now = 0L
        val memory = DiagnosticConnections { now }
        memory.begin(Long.MAX_VALUE, "192.0.2.10", true, true)
        memory.update(Long.MAX_VALUE, report("frozen"))
        now = 119_999_999_999
        memory.update(Long.MAX_VALUE, report("enabled"))
        memory.ended(Long.MAX_VALUE)
        assertEquals("frozen", memory.snapshot().getString("state"))
        now++
        assertFalse(memory.snapshot().getBoolean("available"))
        memory.begin(8, "192.0.2.11", true, true)
        memory.update(8, report("frozen", 8, null))
        memory.update(8, report("enabled", 8))
        assertFalse(memory.snapshot().getBoolean("available"))
    }
}

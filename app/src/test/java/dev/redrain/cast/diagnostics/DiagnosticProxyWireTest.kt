package dev.redrain.cast.diagnostics

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException

class DiagnosticProxyWireTest {
    private fun record() = JSONObject().put("captureId", "capture").put("seq", "9")
        .put("monoNs", "9223372036854775807").put("kind", "CAPTURE_STARTED").put("data", JSONObject())

    @Test fun unboundIdentityStaysUnboundAfterPairBinding() {
        val source = record()
        assertNull(DiagnosticProxyWire.validateSource(source, "receiver", "77", "capture", false))
        assertFalse(source.has("role"))
        assertFalse(source.has("session"))
        source.put("role", "receiver").put("session", "77").put("kind", "SESSION_BOUND")
        assertEquals("77", DiagnosticProxyWire.validateSource(source, "receiver", "77", "capture", false))
    }

    @Test fun rejectsHalfPresentWrongAndUnboundSessionBound() {
        val cases = listOf(
            record().put("role", "receiver"),
            record().put("session", "77"),
            record().put("role", "sender").put("session", "77"),
            record().put("role", "receiver").put("session", "78"),
            record().put("kind", "SESSION_BOUND")
        )
        for (source in cases) {
            try {
                DiagnosticProxyWire.validateSource(source, "receiver", "77", "capture", false)
                fail(source.toString())
            } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun exactDecimalAndFrameBounds() {
        assertEquals(Long.MAX_VALUE, DiagnosticProxyWire.decimal("9223372036854775807"))
        for (value in listOf("9223372036854775808", "01", "-1", 1, 1.0)) {
            try { DiagnosticProxyWire.decimal(value); fail("$value") }
            catch (_: IllegalArgumentException) {}
        }
        val output = ByteArrayOutputStream()
        val source = JSONObject().put("v", 2).put("type", "RECORD").put("record", record())
        DiagnosticProxyWire.writeFrame(output, source)
        assertEquals(source.toString(), DiagnosticProxyWire.readFrame(ByteArrayInputStream(output.toByteArray())).toString())
        val received = checkNotNull(DiagnosticProxyWire.readFrameWithSize(ByteArrayInputStream(output.toByteArray())))
        assertEquals(output.size(), received.bytes)
        assertEquals(source.toString(), received.value.toString())
        assertNull(DiagnosticProxyWire.readFrame(ByteArrayInputStream(byteArrayOf())))
        try {
            DiagnosticProxyWire.readFrame(ByteArrayInputStream(byteArrayOf(0, 0, 64, 1)))
            fail("oversized")
        } catch (_: IllegalArgumentException) {}
        try {
            DiagnosticProxyWire.readFrame(ByteArrayInputStream(byteArrayOf(0, 0, 0, 2, 123)))
            fail("truncated")
        } catch (_: EOFException) {}
        for (version in listOf(1, 2.0, "2")) {
            try {
                DiagnosticProxyWire.writeFrame(ByteArrayOutputStream(), JSONObject().put("v", version).put("type", "END"))
                fail("$version")
            } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun sourceDepthSevenAndLaneDepthEightAreAccepted() {
        fun nested(depth: Int): JSONObject {
            var value = JSONObject().put("x", 1)
            repeat(depth) { value = JSONObject().put("x", value) }
            return value
        }
        val source = record().put("data", nested(5))
        DiagnosticProxyWire.validateSource(source, "sender", null, "capture", false)
        DiagnosticProxyWire.writeFrame(ByteArrayOutputStream(), JSONObject().put("v", 2)
            .put("type", "RECORD").put("record", source))
        val tooDeep = record().put("data", nested(6))
        try {
            DiagnosticProxyWire.validateSource(tooDeep, "sender", null, "capture", false)
            fail("depth")
        } catch (_: IllegalArgumentException) {}
        try {
            DiagnosticProxyWire.writeFrame(ByteArrayOutputStream(), JSONObject().put("v", 2)
                .put("type", "RECORD").put("record", tooDeep))
            fail("lane depth")
        } catch (_: IllegalArgumentException) {}
    }

    @Test fun laneIdentityIsFencedByPairSessionGenerationAndCapture() {
        val binding = DiagnosticProxyWire.Binding("pair", 77, 9, "capture")
        val frame = JSONObject().put("v", 2).put("type", "END").put("pairId", "pair")
            .put("session", "77").put("armGeneration", "9").put("captureId", "capture")
        DiagnosticProxyWire.validateIdentity(frame, binding)
        for ((key, value) in listOf("pairId" to "other", "session" to "78",
            "armGeneration" to "10", "captureId" to "other")) {
            val invalid = JSONObject(frame.toString()).put(key, value)
            try { DiagnosticProxyWire.validateIdentity(invalid, binding); fail(key) }
            catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun operationIdentitiesRemainExactDecimalStrings() {
        val source = record().put("data", JSONObject().put("operationId", "${Long.MAX_VALUE}")
            .put("inFlight", org.json.JSONArray().put(JSONObject().put("operationId", "7")))
            .put("metrics", JSONObject().put("slowExampleOperationId", "8")))
        DiagnosticProxyWire.validateSource(source, "receiver", "77", "capture", false)
        val data = source.getJSONObject("data")
        data.put("operationId", 7)
        try { DiagnosticProxyWire.validateSource(source, "receiver", "77", "capture", false); fail() }
        catch (_: IllegalArgumentException) {}
    }
}

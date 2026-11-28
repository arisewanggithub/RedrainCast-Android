package dev.redrain.cast.diagnostics

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException

class DiagnosticProxyWireTest {
    @Test fun workerBoundariesRequireV2AndValidateStageAndCpuWithoutRejectingLegacy() {
        fun source(schema: String, type: Int, stage: String, cpu: Any) = record()
            .put("role", "receiver").put("session", "77").put("kind", "AUDIO_TIMING")
            .put("data", JSONObject().put("schema", schema).put("attempted", "1")
                .put("dropped", "0").put("droppedBeforeSequence", "0").put("batchDropped", "0")
                .put("events", org.json.JSONArray().put(org.json.JSONArray()
                    .put("1").put("200").put(1).put("0").put(type).put(stage).put(cpu))))
        for (type in 22..24) {
            DiagnosticProxyWire.validateSource(source("audio_timing_v2", type, "2", "-1"),
                "receiver", "77", "capture", false, true, true)
        }
        DiagnosticProxyWire.validateSource(source("audio_timing_v1", 21, "1", "2"),
            "receiver", "77", "capture", false, true)
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticProxyWire.validateSource(source("audio_timing_v3", 22, "13", "100"),
                "receiver", "77", "capture", false, true, true)
        }
        for (type in listOf(22, 25, 26, 27, 28, 29)) {
            DiagnosticProxyWire.validateSource(source("audio_timing_v3", type,
                if (type == 22) "13" else "0", "100"),
                "receiver", "77", "capture", false, true, true, true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticProxyWire.validateSource(source("audio_timing_v2", 22, "2", "100"),
                "receiver", "77", "capture", false, true)
        }
        for (invalid in listOf(
            source("audio_timing_v1", 22, "2", "100"),
            source("audio_timing_v2", 25, "2", "100"),
            source("audio_timing_v2", 22, "0", "100"),
            source("audio_timing_v2", 22, "13", "100"),
            source("audio_timing_v2", 22, "2", "-2"),
            source("audio_timing_v2", 22, "2", 100)
        )) {
            try {
                DiagnosticProxyWire.validateSource(invalid, "receiver", "77", "capture", false, true, true)
                fail("$invalid")
            } catch (_: IllegalArgumentException) {}
        }
    }

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

    @Test fun audioTimingTerminalRequiresValidSelectedSummary() {
        val source = record().put("kind", "CAPTURE_ENDED")
        val summary = JSONObject().put("attempted", "2").put("dropped", "1")
            .put("droppedBeforeSequence", "0").put("batchDropped", "0")
            .put("producersAtClose", "0").put("pendingProducers", "0")
        source.getJSONObject("data").put("audioTiming", summary)
        DiagnosticProxyWire.validateSource(source, "receiver", "77", "capture", false, true)
        for (invalid in listOf(
            JSONObject(source.toString()).also { it.getJSONObject("data").remove("audioTiming") },
            JSONObject(source.toString()).also { it.getJSONObject("data").getJSONObject("audioTiming").put("dropped", "3") },
            JSONObject(source.toString()).also { it.getJSONObject("data").getJSONObject("audioTiming").put("extra", "0") }
        )) {
            try { DiagnosticProxyWire.validateSource(invalid, "receiver", "77", "capture", false, true); fail("$invalid") }
            catch (_: IllegalArgumentException) {}
        }
        try { DiagnosticProxyWire.validateSource(source, "receiver", "77", "capture", false); fail() }
        catch (_: IllegalArgumentException) {}
    }
}

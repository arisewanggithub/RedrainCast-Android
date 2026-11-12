package dev.redrain.cast.diagnostics

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class DiagnosticProtocolTest {
    @Test fun helloAdvertisesVideoWithExplicitMeasurementLimitations() {
        val result = hello().getJSONObject("result")
        for (role in listOf("sender", "receiver")) {
            val flags = result.getJSONObject("capabilities").getJSONArray(role)
            assertTrue((0 until flags.length()).any { flags.getString(it) == "video.progress" })
        }
        assertFalse(result.getJSONObject("metricAvailability").getBoolean("physicalDisplay"))
    }
    private var now = 1_000_000_000L
    private val recorder = DiagnosticRecorder { now }
    private val protocol = DiagnosticProtocol(recorder, { now })
    private var request = 0
    private fun command(op: String, args: JSONObject = JSONObject(), id: String = "r${++request}") =
        protocol.handle(JSONObject().put("v", 1).put("id", id).put("op", op).put("args", args)
            .toString().toByteArray())
    private fun hello() = command("HELLO")
    private fun code(result: JSONObject) = result.getJSONObject("error").getString("code")
    private fun startArgs() = JSONObject().put("target", JSONObject().put("role", "sender").put("nextSession", true))
        .put("flags", JSONArray(listOf("session", "recovery"))).put("durationMs", 1000).put("samplePeriodMs", 100)

    @Test fun helloRequiredBeforeStateAndLegacyAuthIsUnknown() {
        assertEquals("HELLO_REQUIRED", code(command("STATUS")))
        assertTrue(protocol.closing)
        val wrong = DiagnosticProtocol(recorder)
        val response = wrong.handle("""{"v":1,"id":"a","op":"AUTH","args":{"secret":"wrong"}}""".toByteArray())
        assertEquals("UNKNOWN_OPERATION", code(response))
        assertFalse(wrong.ready)
    }
    @Test fun helloCapabilitiesClockAndCanonicalInt64AreExact() {
        val result = hello().getJSONObject("result")
        assertEquals("lan", result.getString("access"))
        assertFalse(result.toString().contains("secret"))
        assertFalse(result.toString().contains("fingerprint"))
        assertEquals(recorder.clockId, result.getString("clockId"))
        assertTrue(result.getJSONObject("capabilities").getJSONArray("receiver").toString().contains("audio.output"))
        val clock = command("CLOCK", JSONObject().put("clientSendNs", "${Long.MAX_VALUE}")).getJSONObject("result")
        assertEquals("${Long.MAX_VALUE}", clock.getString("clientSendNs"))
        for (invalid in listOf("01", "-1", "1.0", "9223372036854775808", "1e2")) {
            assertEquals("INVALID_ARGUMENT", code(command("CLOCK", JSONObject().put("clientSendNs", invalid))))
        }
        assertEquals("INVALID_ARGUMENT", code(command("CLOCK", JSONObject().put("clientSendNs", 1))))
    }
    @Test fun helloRejectsCredentialsAndCannotExposeMediaOrPlaybackCommands() {
        assertEquals("INVALID_ARGUMENT", code(command("HELLO", JSONObject().put("secret", "unused"))))
        assertFalse(protocol.ready)
        assertTrue(hello().getBoolean("ok"))
        assertEquals("INVALID_ARGUMENT", code(command("HELLO")))
        for (operation in listOf("AUTH", "SHELL", "READ_FILE", "LOGCAT", "PLAY", "STOP", "MEDIA"))
            assertEquals("UNKNOWN_OPERATION", code(command(operation)))
        assertFalse(recorder.collecting)
    }
    @Test fun duplicateIdsNeverRepeatSideEffectsAndEndIsIdempotent() {
        hello()
        val started = command("START", startArgs(), "start")
        val id = started.getJSONObject("result").getString("captureId")
        assertEquals("DUPLICATE_ID", code(command("START", startArgs(), "start")))
        val end = command("END", JSONObject().put("captureId", id)).getJSONObject("result")
        assertEquals(end.toString(), command("END", JSONObject().put("captureId", id)).getJSONObject("result").toString())
        assertEquals("frozen", command("STATUS").getJSONObject("result").getString("state"))
    }
    @Test fun strictTypedFieldsFlagsAndUnknownFieldsRejectAtomically() {
        hello()
        val args = startArgs()
        assertEquals("INVALID_ARGUMENT", code(protocol.handle(
            """{"v":1,"id":"fraction","op":"START","args":{"target":{"role":"sender","nextSession":true},"flags":["session"],"durationMs":1000.0,"samplePeriodMs":100}}""".toByteArray())))
        args.put("durationMs", 1000).put("flags", JSONArray(listOf("session", "session")))
        assertEquals("INVALID_ARGUMENT", code(command("START", args)))
        args.put("flags", JSONArray(listOf("video.unknown")))
        assertEquals("UNSUPPORTED_FLAG", code(command("START", args)))
        args.put("flags", JSONArray(listOf("session"))).put("surprise", true)
        assertEquals("INVALID_ARGUMENT", code(command("START", args)))
        assertEquals("idle", command("STATUS").getJSONObject("result").getString("state"))
    }
    @Test fun framingRejectsDuplicatesUtf8OversizeNestingAndNonObjects() {
        val invalid = listOf(
            """{"v":1,"id":"a","op":"PING","args":{},"id":"b"}""".toByteArray(),
            """[]""".toByteArray(), byteArrayOf(0xc3.toByte(), 0x28),
            ("{\"x\":" + "[".repeat(9) + "0" + "]".repeat(9) + "}").toByteArray(),
            ByteArray(16385) { 32 }
        )
        for (bytes in invalid) {
            try { protocol.handle(bytes); fail("Invalid frame accepted") } catch (_: IllegalArgumentException) {}
        }
        try {
            DiagnosticProtocol.readLine(ByteArrayInputStream(ByteArray(16385) { 32 }))
            fail("Oversized input accepted")
        } catch (_: IllegalArgumentException) {}
        try { DiagnosticProtocol.readLine(ByteArrayInputStream("{}".toByteArray())); fail() }
        catch (_: IllegalArgumentException) {}
    }
    @Test fun commandRateAndIdCapacityAreBounded() {
        hello()
        repeat(19) { assertTrue(command("PING").getBoolean("ok")) }
        assertEquals("RATE_LIMITED", code(command("PING")))
        now += 50_000_000
        assertTrue(command("PING").getBoolean("ok"))
        repeat(4096 - request) { now += 100_000_000; command("PING") }
        now += 100_000_000
        assertEquals("RATE_LIMITED", code(command("PING")))
        assertTrue(protocol.closing)
    }
    @Test fun fetchPaginationKeepsWireBoundAndDecimalCounters() {
        hello()
        recorder.sessionStarted("sender", Long.MAX_VALUE)
        val args = startArgs().put("target", JSONObject().put("role", "sender").put("session", "${Long.MAX_VALUE}"))
        val id = command("START", args).getJSONObject("result").getString("captureId")
        repeat(80) { recorder.observe("sender", Long.MAX_VALUE, DiagnosticKind.SESSION, DiagnosticStage.CONFIG) }
        val response = command("FETCH", JSONObject().put("captureId", id).put("afterSeq", "0").put("limit", 32))
        assertTrue(response.toString().toByteArray().size <= 16384)
        val result = response.getJSONObject("result")
        assertEquals(32, result.getJSONArray("records").length())
        assertTrue(result.getBoolean("hasMore"))
        assertEquals("0", result.getString("droppedBeforeRetention"))
        assertEquals("0", result.getString("evictedAfterRetention"))
        assertEquals("${Long.MAX_VALUE}", result.getJSONArray("records").getJSONObject(1).getString("session"))
    }
    @Test fun fetchRollingRecordBudgetCannotBeBypassedByRepeatedPages() {
        hello()
        val id = command("START", startArgs()).getJSONObject("result").getString("captureId")
        recorder.sessionStarted("sender", 73)
        repeat(40) { recorder.observe("sender", 73, DiagnosticKind.SESSION, DiagnosticStage.CONFIG) }
        val args = JSONObject().put("captureId", id).put("afterSeq", "0").put("limit", 32)
        repeat(8) { assertTrue(command("FETCH", args).getBoolean("ok")) }
        assertEquals("RATE_LIMITED", code(command("FETCH", args)))
        now += 1_000_000_000L
        assertTrue(command("FETCH", args).getBoolean("ok"))
    }
    @Test fun invalidIdentifiersAndOversizedOperationNamesCannotBeReflectedIntoOversizedResponses() {
        val longId = "a".repeat(16000)
        val result = command("AUTH", JSONObject(), longId)
        assertEquals("INVALID_ARGUMENT", code(result))
        assertTrue(result.isNull("id"))
        assertTrue(result.toString().toByteArray().size < 1024)
        val longOperation = command("A".repeat(16000))
        assertEquals("INVALID_ARGUMENT", code(longOperation))
        assertTrue(longOperation.isNull("op"))
        assertTrue(longOperation.toString().toByteArray().size < 1024)
    }
}

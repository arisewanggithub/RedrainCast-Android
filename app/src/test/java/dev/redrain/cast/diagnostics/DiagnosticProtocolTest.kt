package dev.redrain.cast.diagnostics

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class DiagnosticProtocolTest {
    private var now = 1_000_000_000L
    private val recorder = DiagnosticRecorder { now }
    private val registry = DiagnosticArmRegistry(recorder) { now }
    private val protocol = DiagnosticProtocol(recorder, { now }, registry = registry)
    private var sequence = 0
    private fun command(op: String, args: JSONObject = JSONObject(), id: String = "r${++sequence}") =
        protocol.handle(JSONObject().put("v", 2).put("id", id).put("op", op).put("args", args)
            .toString().toByteArray())
    private fun code(result: JSONObject) = result.getJSONObject("error").getString("code")
    private fun startArgs() = JSONObject().put("target", JSONObject().put("nextSession", true))
        .put("senderFlags", JSONArray(listOf("session", "recovery")))
        .put("receiverFlags", JSONArray(listOf("session")))
        .put("durationMs", 1000).put("samplePeriodMs", 100)

    @Test fun obsoleteVersionClosesBeforeCollectorSlotOrCaptureAllocation() {
        var acquired = 0
        val old = DiagnosticProtocol(recorder, { now }, { acquired++; true }, registry)
        val reply = old.handle("""{"v":1,"id":"old","op":"HELLO","args":{}}""".toByteArray())
        assertEquals("UNSUPPORTED_VERSION", code(reply))
        assertTrue(old.closing)
        assertEquals(0, acquired)
        assertEquals("idle", recorder.status().getString("state"))
        assertTrue(command("HELLO").getBoolean("ok"))
    }

    @Test fun helloAdvertisesPairedFlagsAndClockWithoutCredentials() {
        val result = command("HELLO").getJSONObject("result")
        assertEquals(1, result.getInt("pairedNextCast"))
        assertEquals(recorder.clockId, result.getString("clockId"))
        assertTrue(result.getJSONArray("receiverFlags").toString().contains("video.timing"))
        assertTrue(result.getJSONArray("senderFlags").toString().contains("video.progress"))
        assertFalse(result.getJSONObject("metricAvailability").getBoolean("physicalDisplay"))
        assertFalse(result.toString().contains("secret"))
    }

    @Test fun helloIsRequiredAndUnknownOperationsDoNotExposeState() {
        assertEquals("HELLO_REQUIRED", code(command("STATUS")))
        assertTrue(protocol.closing)
        val fresh = DiagnosticProtocol(recorder, { now }, registry = registry)
        fun send(op: String) = fresh.handle(JSONObject().put("v", 2).put("id", op).put("op", op)
            .put("args", JSONObject()).toString().toByteArray())
        assertTrue(send("HELLO").getBoolean("ok"))
        for (op in listOf("AUTH", "SHELL", "READ_FILE", "LOGCAT", "PLAY", "STOP", "MEDIA"))
            assertEquals("UNKNOWN_OPERATION", code(send(op)))
        assertFalse(recorder.collecting)
    }

    @Test fun startIsAtomicStrictAndEndIsIdempotent() {
        assertTrue(command("HELLO").getBoolean("ok"))
        val invalid = startArgs().put("receiverFlags", JSONArray(listOf("video.timing")))
        assertEquals("INVALID_ARGUMENT", code(command("START", invalid)))
        assertEquals("idle", recorder.status().getString("state"))
        val started = command("START", startArgs(), "start").getJSONObject("result")
        val pair = started.getString("pairId")
        assertEquals("armed", started.getString("state"))
        assertEquals("DUPLICATE_ID", code(command("START", startArgs(), "start")))
        val args = JSONObject().put("pairId", pair)
        val ended = command("END", args).getJSONObject("result")
        assertEquals(ended.toString(), command("END", args).getJSONObject("result").toString())
        assertEquals("frozen", command("STATUS").getJSONObject("result").getString("state"))
        assertTrue(command("CLEAR", args).getBoolean("ok"))
    }

    @Test fun invalidFieldsAndCanonicalInt64AreRejected() {
        command("HELLO")
        val duplicate = startArgs().put("senderFlags", JSONArray(listOf("session", "session")))
        assertEquals("INVALID_ARGUMENT", code(command("START", duplicate)))
        val unsupported = startArgs().put("receiverFlags", JSONArray(listOf("unknown")))
        assertEquals("UNSUPPORTED_FLAG", code(command("START", unsupported)))
        assertEquals("INVALID_ARGUMENT", code(command("START", startArgs().put("extra", true))))
        for (invalid in listOf("01", "-1", "1.0", "9223372036854775808", "1e2")) {
            assertEquals("INVALID_ARGUMENT", code(command("CLOCK", JSONObject()
                .put("role", "sender").put("clientSendNs", invalid))))
        }
        val clock = command("CLOCK", JSONObject().put("role", "sender")
            .put("clientSendNs", "${Long.MAX_VALUE}")).getJSONObject("result")
        assertEquals("${Long.MAX_VALUE}", clock.getString("clientSendNs"))
    }

    @Test fun fetchKeepsSourceIdentityAndIndependentCursorsWithinFrame() {
        command("HELLO")
        val pair = command("START", startArgs()).getJSONObject("result").getString("pairId")
        assertNotNull(registry.claim(Long.MAX_VALUE))
        repeat(80) { recorder.observe("sender", Long.MAX_VALUE, DiagnosticKind.SESSION, DiagnosticStage.CONFIG) }
        val response = command("FETCH", JSONObject().put("pairId", pair)
            .put("afterSenderSeq", "0").put("afterReceiverSeq", "0").put("limit", 32))
        assertTrue(response.toString().toByteArray().size <= 16_384)
        val result = response.getJSONObject("result")
        val records = result.getJSONArray("records")
        assertEquals(32, records.length())
        assertTrue(result.getJSONObject("sender").getBoolean("hasMore"))
        assertTrue(result.isNull("receiver"))
        assertTrue(records.getJSONObject(0).isNull("session"))
        assertFalse(records.getJSONObject(0).getJSONObject("record").has("role"))
        assertEquals("${Long.MAX_VALUE}", records.getJSONObject(1).getJSONObject("record").getString("session"))
    }

    @Test fun framingAndRequestIdsAreBounded() {
        val invalid = listOf(
            """{"v":2,"id":"a","op":"PING","args":{},"id":"b"}""".toByteArray(),
            """[]""".toByteArray(), byteArrayOf(0xc3.toByte(), 0x28),
            ("{\"x\":" + "[".repeat(9) + "0" + "]".repeat(9) + "}").toByteArray(),
            ByteArray(16_385) { 32 }
        )
        invalid.forEach { assertThrows(IllegalArgumentException::class.java) { protocol.handle(it) } }
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticProtocol.readLine(ByteArrayInputStream(ByteArray(16_385) { 32 }))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticProtocol.readLine(ByteArrayInputStream("{}".toByteArray()))
        }
        val long = command("AUTH", id = "a".repeat(16_000))
        assertEquals("INVALID_ARGUMENT", code(long))
        assertTrue(long.isNull("id"))
        assertTrue(long.toString().toByteArray().size < 1024)
    }

    @Test fun commandRateAndIdCapacityAreBounded() {
        command("HELLO")
        repeat(19) { assertTrue(command("PING").getBoolean("ok")) }
        assertEquals("RATE_LIMITED", code(command("PING")))
        now += 50_000_000
        assertTrue(command("PING").getBoolean("ok"))
        repeat(4097 - sequence) { now += 100_000_000; command("PING") }
        now += 100_000_000
        assertEquals("RATE_LIMITED", code(command("PING")))
        assertTrue(protocol.closing)
    }

    @Test fun repeatedFetchesAndErrorsShareActualDeliveryLimits() {
        val hello = command("HELLO")
        assertTrue(hello.getBoolean("ok"))
        val start = command("START", startArgs())
        val pair = start.getJSONObject("result").getString("pairId")
        val claim = checkNotNull(registry.claim(77))
        repeat(64) { recorder.observe("sender", 77, DiagnosticKind.SESSION, DiagnosticStage.CONFIG) }
        val args = JSONObject().put("pairId", pair).put("afterSenderSeq", "0")
            .put("afterReceiverSeq", "0").put("limit", 32)
        var wireBytes = hello.toString().toByteArray(Charsets.UTF_8).size + 1 +
            start.toString().toByteArray(Charsets.UTF_8).size + 1
        var records = 0
        var limited = false
        repeat(17) {
            val response = command("FETCH", args)
            wireBytes += response.toString().toByteArray(Charsets.UTF_8).size + 1
            if (response.getBoolean("ok")) records += response.getJSONObject("result").getJSONArray("records").length()
            else {
                assertEquals("RATE_LIMITED", code(response))
                limited = true
            }
        }
        assertTrue(limited)
        assertTrue(records <= 256)
        assertTrue(wireBytes <= 128 * 1024)
        assertTrue(claim.valid.get())
    }

    @Test fun deliveryByteLimitIncludesErrorEnvelopeAndNewline() {
        val hello = command("HELLO")
        assertTrue(hello.getBoolean("ok"))
        val start = command("START", startArgs().put("receiverFlags",
            JSONArray(listOf("session", "video.progress", "video.timing"))))
        val pair = start.getJSONObject("result").getString("pairId")
        val claim = checkNotNull(registry.claim(77))
        val stages = JSONArray()
        org.screencopy.receiver.protocol.DiagnosticProxyNegotiation.stages.forEachIndexed { index, stage ->
            stages.put(JSONObject().put("stage", stage).put("monoNs", "$index"))
        }
        val bootstrap = JSONObject().put("session", "77").put("pairId", pair).put("captureId", "remote")
            .put("clockId", "clock").put("complete", true).put("loss", false).put("stages", stages)
        val intake = DiagnosticProxyIntake("77", pair, "remote", "clock", true, bootstrap)
        registry.covered(claim, intake, "remote", "clock")
        val data = JSONObject().put("metrics", JSONObject().put("schema", "video_frame_timing")).put("padding", "")
        val record = JSONObject().put("captureId", "remote").put("seq", "1").put("monoNs", "1")
            .put("kind", "VIDEO_PROGRESS").put("role", "receiver").put("session", "77").put("data", data)
        data.put("padding", "x".repeat(12_000 - record.toString().toByteArray(Charsets.UTF_8).size))
        assertTrue(intake.record(record, 12_300))
        intake.consume()
        val args = JSONObject().put("pairId", pair).put("afterSenderSeq", "0")
            .put("afterReceiverSeq", "0").put("limit", 32)
        var bytes = hello.toString().toByteArray(Charsets.UTF_8).size + 1 +
            start.toString().toByteArray(Charsets.UTF_8).size + 1
        var limited = false
        repeat(17) {
            val response = command("FETCH", args, "%02d".format(it) + "a".repeat(62))
            bytes += response.toString().toByteArray(Charsets.UTF_8).size + 1
            if (!response.getBoolean("ok")) {
                assertEquals("RATE_LIMITED", code(response))
                limited = true
            }
        }
        assertTrue(limited)
        assertTrue(bytes <= 128 * 1024)
    }
}

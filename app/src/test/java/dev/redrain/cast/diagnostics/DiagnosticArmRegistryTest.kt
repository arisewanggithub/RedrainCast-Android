package dev.redrain.cast.diagnostics

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import org.screencopy.receiver.protocol.DiagnosticProxyNegotiation

class DiagnosticArmRegistryTest {
    private var now = 1_000_000_000L
    private val recorder = DiagnosticRecorder { now }
    private val registry = DiagnosticArmRegistry(recorder) { now }

    private fun start() = registry.start("owner", setOf("session"), setOf("session", "video.progress"), 1000, 100)

    private fun error(code: String, action: () -> Unit) {
        try { action(); fail("Expected $code") }
        catch (error: DiagnosticError) { assertEquals(code, error.code) }
    }

    @Test fun armClaimsOnlyFirstNewSessionAndDoesNotRenewItsDeadline() {
        val result = start()
        val id = result.getString("pairId")
        assertEquals("armed", result.getString("state"))
        assertTrue(result.isNull("session"))
        assertTrue(result.isNull("receiverCaptureId"))
        now += 200_000_000L
        val claim = checkNotNull(registry.claim(77))
        assertEquals(id, claim.pairId)
        assertEquals(800, registry.remainingMs(claim))
        assertNull(registry.claim(78))
        error("NOT_OWNER") { registry.end("other", id) }
        val failed = registry.setupFailed(claim, "unsupported")
        assertEquals("frozen", failed.getString("state"))
        assertEquals("setup_failed", failed.getString("endReason"))
        assertEquals("unsupported", failed.getString("setupStage"))
        assertEquals("77", failed.getString("session"))
        assertEquals("unavailable", failed.getJSONObject("receiverRole").getString("state"))
        assertTrue(failed.getJSONObject("receiverRole").isNull("outcome"))
        assertEquals("setup_failed", registry.end("new-collector", id).getString("endReason"))
    }

    @Test fun setupFailureIsFrozenOnceAndOnlySummarizedInFetch() {
        val pair = start()
        val id = pair.getString("pairId")
        val claim = checkNotNull(registry.claim(77))
        val first = DiagnosticArmRegistry.SetupFailure(DiagnosticArmRegistry.SetupCode.PROTOCOL,
            DiagnosticArmRegistry.SetupProvenance.receiver_control,
            DiagnosticArmRegistry.SetupReason.receiver_reported)
        registry.setupFailed(claim, "request", first)
        registry.setupFailed(claim, "lane", DiagnosticArmRegistry.SetupFailure(DiagnosticArmRegistry.SetupCode.TIMEOUT,
            DiagnosticArmRegistry.SetupProvenance.sender_local, DiagnosticArmRegistry.SetupReason.timeout))
        val status = registry.status("owner", id)
        assertEquals("request", status.getString("setupStage"))
        assertEquals(first.json().toString(), status.getJSONObject("setupFailure").toString())
        val page = registry.fetch("owner", id, 0, 0, 32)
        assertTrue(page.getBoolean("setupFailureAvailable"))
        assertFalse(page.has("setupFailure"))
        assertTrue(page.isNull("receiver"))
        assertTrue(DiagnosticProtocol.successBytes("r1", "FETCH", page) <= 16_384)
    }

    @Test fun freezingCoveredReceiverWithoutTerminalRetainsUnknownOutcome() {
        val pair = start()
        val claim = checkNotNull(registry.claim(77))
        val stages = JSONArray()
        DiagnosticProxyNegotiation.stages.forEachIndexed { index, stage ->
            stages.put(JSONObject().put("stage", stage).put("monoNs", "$index"))
        }
        val bootstrap = JSONObject().put("session", "77").put("pairId", claim.pairId)
            .put("captureId", "remote").put("clockId", "clock").put("complete", true)
            .put("loss", false).put("stages", stages)
        val intake = DiagnosticProxyIntake("77", claim.pairId, "remote", "clock", false, bootstrap)
        registry.covered(claim, intake, "remote", "clock")
        val frozen = registry.end("owner", claim.pairId)
        val outcome = frozen.getJSONObject("receiverRole").getJSONObject("outcome")
        assertFalse(outcome.getBoolean("confirmed"))
        assertTrue(outcome.isNull("endedNs"))
        assertTrue(outcome.isNull("reason"))
        assertFalse(outcome.getJSONObject("captureEnd").getBoolean("complete"))
        assertTrue(outcome.getJSONObject("captureEnd").getBoolean("truncated"))
        assertFalse(registry.fetch("owner", pair.getString("pairId"), 0, 0, 32)
            .getJSONObject("receiver").getBoolean("endConfirmed"))
    }

    @Test fun committedReceiverTerminalSurvivesFreezeBeforeNotification() {
        for (expiry in listOf(false, true)) {
            var time = 1_000_000_000L
            val source = DiagnosticRecorder { time }
            val arms = DiagnosticArmRegistry(source) { time }
            val pair = arms.start("owner", setOf("session"), setOf("session"), 1000, 100)
            val claim = checkNotNull(arms.claim(77))
            val stages = JSONArray()
            DiagnosticProxyNegotiation.stages.forEachIndexed { index, stage ->
                stages.put(JSONObject().put("stage", stage).put("monoNs", "$index"))
            }
            val bootstrap = JSONObject().put("session", "77").put("pairId", claim.pairId)
                .put("captureId", "remote").put("clockId", "clock").put("complete", true)
                .put("loss", false).put("stages", stages)
            val intake = DiagnosticProxyIntake("77", claim.pairId, "remote", "clock", false, bootstrap)
            arms.covered(claim, intake, "remote", "clock")
            repeat(65) { index ->
                val record = JSONObject().put("captureId", "remote").put("seq", "${index + 1}")
                    .put("monoNs", "${index + 1}").put("kind", "SESSION").put("data", JSONObject())
                assertEquals(index < 64, intake.record(record, 256))
            }
            val end = JSONObject().put("atNs", "90").put("reason", "receiver_retired_stop")
                .put("complete", true).put("truncated", false)
            val outcome = JSONObject().put("reason", "receiver_retired_stop").put("endedNs", "91")
                .put("mediaRetirement", "succeeded").put("ownershipCleanup", "unknown")
                .put("tailTruncated", false).put("captureEnd", end)
            intake.terminal(JSONObject(outcome.toString()), true, outcome.toString())
            intake.consume()
            if (expiry) time += 1_000_000_000L else arms.end("owner", claim.pairId)
            val status = arms.status("owner", claim.pairId)
            assertEquals("frozen", status.getString("state"))
            assertEquals(outcome.toString(), status.getJSONObject("receiverRole").getJSONObject("outcome").toString())
            val page = arms.fetch("owner", claim.pairId, 0, 0, 32)
            val receiver = page.getJSONObject("receiver")
            assertTrue(receiver.getBoolean("endConfirmed"))
            assertEquals("receiver_retired_stop", receiver.getJSONObject("terminal").getString("reason"))
            assertEquals("1", receiver.getJSONObject("intakeLoss").getString("count"))
            assertEquals(0, receiver.getInt("pendingRecords"))
            assertTrue(receiver.getBoolean("hasMore"))
            assertThrows(org.screencopy.receiver.protocol.ProtocolException::class.java) {
                intake.terminal(JSONObject(outcome.toString()), true, outcome.toString())
            }
            arms.receiverEnded(claim, true)
            assertEquals(outcome.toString(), arms.status("owner", pair.getString("pairId"))
                .getJSONObject("receiverRole").getJSONObject("outcome").toString())
        }
    }

    @Test fun finishingStatusRetainsOrderedCutoffWhenEndForcesPendingRecordsToDrop() {
        start()
        val claim = checkNotNull(registry.claim(77))
        val stages = JSONArray()
        DiagnosticProxyNegotiation.stages.forEachIndexed { index, stage ->
            stages.put(JSONObject().put("stage", stage).put("monoNs", "$index"))
        }
        val bootstrap = JSONObject().put("session", "77").put("pairId", claim.pairId)
            .put("captureId", "remote").put("clockId", "clock").put("complete", true)
            .put("loss", false).put("stages", stages)
        val intake = DiagnosticProxyIntake("77", claim.pairId, "remote", "clock", false, bootstrap)
        registry.covered(claim, intake, "remote", "clock")
        val record = JSONObject().put("captureId", "remote").put("seq", "1")
            .put("monoNs", "1").put("kind", "SESSION").put("data", JSONObject())
        assertTrue(intake.record(record, 256))
        val end = JSONObject().put("atNs", "2").put("reason", "receiver_retired_stop")
            .put("complete", true).put("truncated", false)
        val outcome = JSONObject().put("reason", "receiver_retired_stop").put("endedNs", "2")
            .put("mediaRetirement", "succeeded").put("ownershipCleanup", "unknown")
            .put("tailTruncated", false).put("captureEnd", end)
        registry.receiverFinishing(claim)
        intake.terminal(JSONObject(outcome.toString()), true, outcome.toString())
        assertEquals("finishing", registry.status("owner", claim.pairId)
            .getJSONObject("receiverRole").getString("state"))
        val pending = registry.fetch("owner", claim.pairId, 0, 0, 32).getJSONObject("receiver")
        assertEquals("finishing", pending.getString("state"))
        assertEquals(1, pending.getInt("pendingRecords"))
        val frozen = registry.end("owner", claim.pairId)
        assertEquals(outcome.toString(), frozen.getJSONObject("receiverRole").getJSONObject("outcome").toString())
        val page = registry.fetch("owner", claim.pairId, 0, 0, 32).getJSONObject("receiver")
        assertEquals("finished", page.getString("state"))
        assertEquals(0, page.getInt("pendingRecords"))
        assertEquals("1", page.getJSONObject("forcedLoss").getString("count"))
        assertTrue(page.getBoolean("endConfirmed"))
    }

    @Test fun foreignDisconnectCannotFreezeNewArmAndFrozenDataDoesNotRenew() {
        val first = start()
        registry.disconnected("old-owner", first.getString("pairId"), first.getString("armGeneration").toLong())
        assertEquals("armed", registry.status("owner", first.getString("pairId")).getString("state"))
        registry.disconnected("owner", first.getString("pairId"), first.getString("armGeneration").toLong())
        error("RETAINED_CAPTURE_EXISTS") { start() }
        now += 119_999_999_999L
        assertEquals("frozen", registry.status("new-owner", first.getString("pairId")).getString("state"))
        now++
        error("CAPTURE_NOT_FOUND") { registry.status("new-owner", first.getString("pairId")) }
        val second = start()
        assertNotEquals(first.getString("pairId"), second.getString("pairId"))
        assertNotEquals(first.getString("armGeneration"), second.getString("armGeneration"))
        registry.disconnected("owner", first.getString("pairId"), first.getString("armGeneration").toLong())
        assertEquals("armed", registry.status("owner", second.getString("pairId")).getString("state"))
    }

    @Test fun invalidReceiverFlagsCannotAllocateSenderCapture() {
        error("INVALID_ARGUMENT") {
            registry.start("owner", setOf("session"), setOf("video.timing"), 1000, 100)
        }

        assertEquals("idle", recorder.status().getString("state"))
        assertEquals("armed", start().getString("state"))
    }

    @Test fun committedTeardownDoesNotExtendTheDiagnosticLeaseOrDrainDeadline() {
        val pair = registry.start("owner", setOf("session"), setOf("session"), 30_000, 100)
        val claim = checkNotNull(registry.claim(77))
        claim.commitTerminal(now)
        claim.capTerminalDeadline(now + 2_000_000_000L)
        now += 1_999_999_999L
        assertEquals("binding", registry.status("owner", pair.getString("pairId")).getString("state"))
        now++
        assertEquals("terminal_deadline", registry.status("owner", pair.getString("pairId")).getString("endReason"))
    }

    @Test fun expiredAndRevokedClaimsCannotAffectReplacementCapture() {
        val first = start()
        val expired = checkNotNull(registry.claim(77))
        now += 1_000_000_000L
        assertEquals("lease_expired", registry.status("owner", first.getString("pairId")).getString("endReason"))
        assertFalse(expired.valid.get())
        registry.clear("owner", first.getString("pairId"))
        val second = start()
        val revoked = checkNotNull(registry.claim(78))
        registry.revoke()
        assertFalse(revoked.valid.get())
        error("CAPTURE_NOT_FOUND") { registry.status("owner", second.getString("pairId")) }
        recorder.grantConsent()
        val replacement = start()
        error("CAPTURE_NOT_FOUND") { registry.receiverEnded(expired, false) }
        error("CAPTURE_NOT_FOUND") { registry.receiverEnded(revoked, false) }
        assertEquals("armed", registry.status("owner", replacement.getString("pairId")).getString("state"))
    }

    @Test fun senderFrozenRetentionIsHeldUntilWholePairFreeze() {
        val result = recorder.startPairedSender(setOf("session"), 1000, 100)
        val capture = result.getString("captureId")
        recorder.end(capture, "session_ended")
        now += 130_000_000_000L
        recorder.tick()
        assertEquals(capture, recorder.status().getString("captureId"))
        recorder.releasePairRetention(capture, now)
        now += 119_999_999_999L
        recorder.tick()
        assertEquals(capture, recorder.status().getString("captureId"))
        now++
        recorder.tick()
        assertEquals("idle", recorder.status().getString("state"))
    }

    @Test fun maximumTimingRecordFitsPairedFetchAfterReceiverTerminal() {
        timingRecordWithReceiverIdentity("3e622c74-d68f-4909-ae43-c01d1cbd9ef2",
            "b89fb219-cbac-4c9f-b360-02d03ee69dc9")
    }

    @Test fun maximumTimingRecordWithEscapedIdentitiesFitsSingleFetch() {
        timingRecordWithReceiverIdentity("\u0001".repeat(64), "\u0002".repeat(64))
    }

    @Test fun maximumTimingRecordFitsWithSimultaneouslyLargeLossMetadata() {
        timingRecordWithReceiverIdentity("\u0001".repeat(64), "\u0002".repeat(64), stressMetadata = true)
    }

    @Test fun unconfirmedOutcomeFitsMaximumStatusAndFetchMetadata() {
        timingRecordWithReceiverIdentity("\u0001".repeat(64), "\u0002".repeat(64),
            stressMetadata = true, unconfirmed = true)
    }

    @Test fun pairedPagesAdvanceOnlyReturnedCursorsWithMultibyteRecords() {
        val pair = registry.start("owner", setOf("session"), setOf("session"), 1000, 100)
        val claim = checkNotNull(registry.claim(77))
        val stages = JSONArray()
        DiagnosticProxyNegotiation.stages.forEachIndexed { index, stage ->
            stages.put(JSONObject().put("stage", stage).put("monoNs", "$index"))
        }
        val bootstrap = JSONObject().put("session", "77").put("pairId", claim.pairId)
            .put("captureId", "capture").put("clockId", "clock").put("complete", true)
            .put("loss", false).put("stages", stages)
        val intake = DiagnosticProxyIntake("77", claim.pairId, "capture", "clock", false, bootstrap)
        registry.covered(claim, intake, "capture", "clock")
        repeat(32) {
            recorder.observe("sender", 77, DiagnosticKind.SESSION, DiagnosticStage.CONFIG,
                operation = (it + 1).toLong())
            val record = JSONObject().put("captureId", "capture").put("seq", "${it + 1}").put("monoNs", "$it")
                .put("kind", "SESSION").put("role", "receiver").put("session", "77")
                .put("data", JSONObject().put("text", "é\"漢".repeat(100)))
            assertTrue(intake.record(record, record.toString().toByteArray(Charsets.UTF_8).size + 256))
            intake.consume()
        }
        registry.end("owner", pair.getString("pairId"))
        val protocol = DiagnosticProtocol(recorder, { now }, registry = registry)
        fun request(id: String, op: String, args: JSONObject = JSONObject()): JSONObject =
            protocol.handle(JSONObject().put("v", 2).put("id", id).put("op", op).put("args", args)
                .toString().toByteArray(Charsets.UTF_8))
        assertTrue(request("hello", "HELLO").getBoolean("ok"))
        var sender = 0L
        var receiver = 0L
        var seenSender = 0
        var seenReceiver = 0
        var pages = 0
        do {
            val id = "p".repeat(63) + pages
            val response = request(id, "FETCH", JSONObject().put("pairId", claim.pairId)
                .put("afterSenderSeq", "$sender").put("afterReceiverSeq", "$receiver").put("limit", 32))
            assertTrue(response.toString(), response.getBoolean("ok"))
            assertTrue(response.toString().toByteArray(Charsets.UTF_8).size + 1 <= DiagnosticProxyWire.FRAME_BYTES)
            val page = response.getJSONObject("result")
            val records = page.getJSONArray("records")
            var lastSender = sender
            var lastReceiver = receiver
            repeat(records.length()) { index ->
                val item = records.getJSONObject(index)
                val seq = item.getJSONObject("record").getString("seq").toLong()
                if (item.getString("role") == "sender") { lastSender = seq; seenSender++ }
                else { lastReceiver = seq; seenReceiver++ }
            }
            assertEquals("$lastSender", page.getJSONObject("sender").getString("nextAfterSeq"))
            assertEquals("$lastReceiver", page.getJSONObject("receiver").getString("nextAfterSeq"))
            sender = lastSender
            receiver = lastReceiver
            pages++
            val more = page.getJSONObject("sender").getBoolean("hasMore") ||
                page.getJSONObject("receiver").getBoolean("hasMore")
            if (!more) break
            assertTrue("FETCH failed to progress", records.length() > 0 && pages < 10)
        } while (true)
        assertTrue(seenSender >= 32)
        assertEquals(32, seenReceiver)
        assertTrue(pages > 1)
    }

    @Test fun exactFrameBoundaryIncludesRecordAndNextByteDoesNotAdvanceCursor() {
        fun fetch(padding: Int): JSONObject {
            val source = DiagnosticRecorder { now }
            val arms = DiagnosticArmRegistry(source) { now }
            val pair = arms.start("owner", setOf("session"),
                setOf("session", "video.progress", "video.timing"), 1000, 100)
            val claim = checkNotNull(arms.claim(77))
            val capture = "\u0001".repeat(64)
            val clock = "\u0002".repeat(64)
            val stages = JSONArray()
            DiagnosticProxyNegotiation.stages.forEachIndexed { index, stage ->
                stages.put(JSONObject().put("stage", stage).put("monoNs", "$index"))
            }
            val bootstrap = JSONObject().put("session", "77").put("pairId", claim.pairId)
                .put("captureId", capture).put("clockId", clock).put("complete", true)
                .put("loss", false).put("stages", stages)
            val intake = DiagnosticProxyIntake("77", claim.pairId, capture, clock, true, bootstrap)
            arms.covered(claim, intake, capture, clock)
            val data = JSONObject().put("metrics", JSONObject().put("schema", "video_frame_timing"))
                .put("padding", "")
            val timing = JSONObject().put("captureId", capture).put("seq", "1").put("monoNs", "1")
                .put("kind", "VIDEO_PROGRESS").put("role", "receiver").put("session", "77").put("data", data)
            data.put("padding", "x".repeat(11_000 - timing.toString().toByteArray(Charsets.UTF_8).size))
            val second = JSONObject().put("captureId", capture).put("seq", "2").put("monoNs", "2")
                .put("kind", "SESSION").put("role", "receiver").put("session", "77")
                .put("data", JSONObject().put("text", "x".repeat(padding)))
            assertTrue(intake.record(timing, 11_256))
            assertTrue(intake.record(second, second.toString().toByteArray(Charsets.UTF_8).size + 256))
            intake.consume()
            intake.sourceLoss(source.status().getJSONObject("lossWatermarks"))
            arms.end("owner", pair.getString("pairId"))
            val cursor = source.fetch(claim.senderCaptureId, 0, 32).getString("nextAfterSeq")
            val protocol = DiagnosticProtocol(source, { now }, registry = arms)
            fun command(id: String, op: String, args: JSONObject = JSONObject()) =
                protocol.handle(JSONObject().put("v", 2).put("id", id).put("op", op).put("args", args)
                    .toString().toByteArray(Charsets.UTF_8))
            assertTrue(command("hello", "HELLO").getBoolean("ok"))
            return command("x".repeat(64), "FETCH", JSONObject().put("pairId", claim.pairId)
                .put("afterSenderSeq", cursor).put("afterReceiverSeq", "0").put("limit", 32))
        }
        val baseline = fetch(0)
        assertTrue(baseline.toString(), baseline.getBoolean("ok"))
        assertEquals(2, baseline.getJSONObject("result").getJSONArray("records").length())
        val padding = DiagnosticProxyWire.FRAME_BYTES - baseline.toString().toByteArray(Charsets.UTF_8).size - 1
        assertTrue("unusable boundary padding $padding", padding in 0..1200)
        val exact = fetch(padding)
        assertTrue(exact.toString(), exact.getBoolean("ok"))
        assertEquals(DiagnosticProxyWire.FRAME_BYTES, exact.toString().toByteArray(Charsets.UTF_8).size + 1)
        assertEquals("2", exact.getJSONObject("result").getJSONObject("receiver").getString("nextAfterSeq"))
        val overflow = fetch(padding + 1)
        assertTrue(overflow.toString(), overflow.getBoolean("ok"))
        val page = overflow.getJSONObject("result")
        assertEquals(1, page.getJSONArray("records").length())
        assertEquals("1", page.getJSONObject("receiver").getString("nextAfterSeq"))
        assertTrue(page.getJSONObject("receiver").getBoolean("hasMore"))
    }

    private fun timingRecordWithReceiverIdentity(capture: String, clock: String, stressMetadata: Boolean = false,
                                                 unconfirmed: Boolean = false) {
        if (stressMetadata) {
            DiagnosticArmRegistry::class.java.getDeclaredField("generation").apply { isAccessible = true }
                .setLong(registry, Long.MAX_VALUE - 1)
        }
        val pair = registry.start("owner",
            if (stressMetadata) DiagnosticRecorder.capabilities.getValue("sender") else setOf("session"),
            if (stressMetadata) DiagnosticRecorder.capabilities.getValue("receiver")
            else setOf("session", "video.progress", "video.timing"), 1000, 100)
        val sourceSession = if (stressMetadata) Long.MAX_VALUE else 77L
        val sourceSequence = if (stressMetadata) Long.MAX_VALUE else 1L
        val claim = checkNotNull(registry.claim(sourceSession))
        val stages = JSONArray()
        DiagnosticProxyNegotiation.stages.forEachIndexed { index, stage ->
            stages.put(JSONObject().put("stage", stage).put("monoNs", "$index"))
        }
        val bootstrap = JSONObject().put("session", "$sourceSession").put("pairId", claim.pairId)
            .put("captureId", capture).put("clockId", clock).put("complete", true)
            .put("loss", false).put("stages", stages)
        val intake = DiagnosticProxyIntake("$sourceSession", claim.pairId, capture, clock, true, bootstrap)
        registry.covered(claim, intake, capture, clock)
        val data = JSONObject().put("metrics", JSONObject().put("schema", "video_frame_timing")).put("padding", "")
        val record = JSONObject().put("captureId", capture).put("seq", "$sourceSequence").put("monoNs", "1")
            .put("kind", "VIDEO_PROGRESS").put("role", "receiver").put("session", "$sourceSession").put("data", data)
        data.put("padding", "x".repeat(12_000 - record.toString().toByteArray(Charsets.UTF_8).size))
        assertEquals(12_000, record.toString().toByteArray(Charsets.UTF_8).size)
        val lane = JSONObject().put("v", 2).put("type", "RECORD").put("pairId", claim.pairId)
            .put("session", "$sourceSession").put("armGeneration", "${claim.generation}").put("captureId", capture)
            .put("record", record)
        val laneBytes = lane.toString().toByteArray(Charsets.UTF_8).size + 4
        assertTrue(laneBytes <= DiagnosticProxyWire.FRAME_BYTES + 4)
        val encodedLane = java.io.ByteArrayOutputStream()
        DiagnosticProxyWire.writeFrame(encodedLane, lane)
        assertEquals(laneBytes, encodedLane.size())
        val accepted = intake.record(record, laneBytes)
        intake.consume()
        val loss = recorder.status().getJSONObject("lossWatermarks")
        val admission = loss.getJSONObject("admission")
        val count = "1844674407370955161"
        listOf("routine_capacity", "critical_capacity", "closed_capture", "expired", "oversized_record")
            .forEach { key ->
                admission.getJSONObject(key).put("count", count)
                    .put("firstMonoNs", if (stressMetadata) Long.MAX_VALUE.toString() else "1")
                    .put("lastMonoNs", Long.MAX_VALUE.toString())
            }
        admission.put("total", "9223372036854775805")
        val retention = loss.getJSONObject("retention")
        listOf("routine", "critical", "oversized").forEach { key ->
            retention.getJSONObject(key).put("count", Long.MAX_VALUE.toString())
                .put("firstSeq", "1").put("lastSeq", Long.MAX_VALUE.toString()).put("exactRange", true)
        }
        loss.put("publishedSeq", Long.MAX_VALUE.toString()).put("oldestRetainedSeq", "1")
            .put("latestRetainedSeq", Long.MAX_VALUE.toString())
        intake.sourceLoss(loss)
        val captureEnd = JSONObject().put("atNs", "1").put("reason", "receiver_retired_stop")
            .put("complete", true).put("truncated", false)
        val terminal = JSONObject().put("v", 2).put("type", "ENDED").put("pairId", claim.pairId)
            .put("session", "$sourceSession").put("armGeneration", "${claim.generation}").put("captureId", capture)
            .put("clockId", clock).put("reason", "receiver_retired_stop").put("endedNs", "1")
            .put("mediaRetirement", "succeeded").put("ownershipCleanup", "unknown")
            .put("tailTruncated", false).put("captureEnd", captureEnd).put("sourceLoss", loss)
        if (stressMetadata) {
            DiagnosticProxyIntake::class.java.getDeclaredField("ordinal").apply { isAccessible = true }
                .setLong(intake, Long.MAX_VALUE)
        }
        if (unconfirmed) {
            intake.closeUnconfirmed()
            registry.receiverEnded(claim, true)
        } else {
            val outcome = JSONObject().put("reason", "receiver_retired_stop")
                .put("endedNs", "1").put("mediaRetirement", "succeeded").put("ownershipCleanup", "unknown")
                .put("tailTruncated", false).put("captureEnd", captureEnd).toString()
            intake.terminal(terminal, true, outcome)
            intake.consume()
            registry.receiverEnded(claim, true)
        }
        val confirmed = registry.status("owner", pair.getString("pairId"))
            .getJSONObject("receiverRole").getJSONObject("outcome").toString()
        registry.receiverEnded(claim, true)
        assertEquals(confirmed, registry.status("owner", pair.getString("pairId"))
            .getJSONObject("receiverRole").getJSONObject("outcome").toString())
        registry.end("owner", pair.getString("pairId"))
        registry.receiverEnded(claim, true)
        assertEquals(confirmed, registry.status("owner", pair.getString("pairId"))
            .getJSONObject("receiverRole").getJSONObject("outcome").toString())
        if (stressMetadata) {
            val field = DiagnosticRecorder::class.java.getDeclaredField("capture").apply { isAccessible = true }
            val sender = field.get(recorder) as DiagnosticRecorder.Capture
            sender.queueState.set(-1)
            sender.queueHead.set(Long.MAX_VALUE)
            repeat(5) {
                sender.dropCounts.set(it, 1_844_674_407_370_955_161L)
                sender.dropFirstNs.set(it, Long.MAX_VALUE - 1)
                sender.dropLastNs.set(it, Long.MAX_VALUE)
            }
            listOf(sender.routineEvictions, sender.criticalEvictions, sender.oversizedRecords).forEach {
                it.count = Long.MAX_VALUE
                it.first = 1
                it.last = Long.MAX_VALUE
                it.exact = true
            }
            listOf("queueLoss", "forcedLoss", "retentionLoss").forEach { name ->
                val lossField = DiagnosticProxyIntake::class.java.getDeclaredField(name).apply { isAccessible = true }
                val sequenceLoss = lossField.get(intake) as DiagnosticRecorder.SequenceLoss
                sequenceLoss.count = Long.MAX_VALUE
                sequenceLoss.first = 1
                sequenceLoss.last = Long.MAX_VALUE
                sequenceLoss.exact = true
            }
        }
        val senderCursor = if (stressMetadata) Long.MAX_VALUE
            else recorder.fetch(claim.senderCaptureId, 0, 32).getString("nextAfterSeq").toLong()
        val protocol = DiagnosticProtocol(recorder, { now }, registry = registry)
        fun request(id: String, op: String, args: JSONObject = JSONObject()): JSONObject =
            protocol.handle(JSONObject().put("v", 2).put("id", id).put("op", op).put("args", args)
                .toString().toByteArray(Charsets.UTF_8))
        assertTrue(request("hello", "HELLO").getBoolean("ok"))
        val response = request("r".repeat(64), "FETCH", JSONObject().put("pairId", claim.pairId)
            .put("afterSenderSeq", "$senderCursor").put("afterReceiverSeq", "0").put("limit", 32))
        val responseBytes = response.toString().toByteArray(Charsets.UTF_8).size + 1
        assertTrue("A valid timing record must be admitted", accepted)
        assertTrue("FETCH rejected: $response", response.getBoolean("ok"))
        assertTrue("lane frame=$laneBytes bytes; single-record FETCH line=$responseBytes bytes",
            responseBytes <= DiagnosticProxyWire.FRAME_BYTES)
        val result = response.getJSONObject("result")
        assertFalse(result.has("senderFlags"))
        assertFalse(result.has("receiverFlags"))
        val item = result.getJSONArray("records").getJSONObject(0)
        assertEquals("receiver", item.getString("role"))
        assertEquals("$sourceSession", item.getString("session"))
        assertEquals(record.toString(), item.getJSONObject("record").toString())
        assertEquals("$sourceSequence", result.getJSONObject("receiver").getString("nextAfterSeq"))
        assertEquals("$senderCursor", result.getJSONObject("sender").getString("nextAfterSeq"))
        val statusResponse = request("s".repeat(64), "STATUS", JSONObject().put("pairId", claim.pairId))
        assertTrue(statusResponse.getBoolean("ok"))
        assertTrue(statusResponse.toString().toByteArray(Charsets.UTF_8).size + 1 <= DiagnosticProxyWire.FRAME_BYTES)
        val status = statusResponse.getJSONObject("result")
        assertTrue(status.has("senderFlags"))
        assertTrue(status.has("receiverFlags"))
        assertEquals(8, status.getJSONObject("bootstrap").getJSONArray("stages").length())
        val outcome = status.getJSONObject("receiverRole").getJSONObject("outcome")
        if (unconfirmed) {
            assertFalse(outcome.getBoolean("confirmed"))
            assertTrue(outcome.isNull("reason"))
            assertTrue(outcome.isNull("endedNs"))
            assertFalse(result.getJSONObject("receiver").getBoolean("endConfirmed"))
            assertFalse(result.getJSONObject("receiverRole").has("outcome"))
        } else assertEquals("receiver_retired_stop", outcome.getString("reason"))
    }
}

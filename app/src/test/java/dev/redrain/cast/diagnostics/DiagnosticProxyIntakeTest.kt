package dev.redrain.cast.diagnostics

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.screencopy.receiver.protocol.ProtocolException

class DiagnosticProxyIntakeTest {
    @Test fun sourceWatermarksAreRetainedAndMalformedCountsAreRejected() {
        val recorder = DiagnosticRecorder().apply {
            grantConsent()
            start("sender", null, setOf("session"), 1000, 100)
        }
        val loss = recorder.status().getJSONObject("lossWatermarks")
        val intake = intake()
        intake.sourceLoss(loss)
        assertEquals("loss_watermarks", intake.fetch(0, 1).getJSONObject("sourceLoss").getString("schema"))
        loss.getJSONObject("admission").put("total", "1")
        assertThrows(ProtocolException::class.java) { intake.sourceLoss(loss) }
        assertEquals("0", intake.fetch(0, 1).getJSONObject("sourceLoss")
            .getJSONObject("admission").getString("total"))
    }

    private fun intake(audioTiming: Boolean = false): DiagnosticProxyIntake {
        val stages = JSONArray()
        org.screencopy.receiver.protocol.DiagnosticProxyNegotiation.stages.forEachIndexed { index, stage ->
            stages.put(JSONObject().put("stage", stage).put("monoNs", "$index"))
        }
        val bootstrap = JSONObject().put("session", "77").put("pairId", "pair")
            .put("captureId", "capture").put("clockId", "clock").put("complete", true)
            .put("loss", false).put("stages", stages)
        return DiagnosticProxyIntake("77", "pair", "capture", "clock", false, bootstrap, audioTiming)
    }
    private fun record(seq: Int, bound: Boolean = true) = JSONObject().put("captureId", "capture")
        .put("seq", "$seq").put("monoNs", "$seq").put("kind", "SESSION").put("data", JSONObject())
        .apply { if (bound) put("role", "receiver").put("session", "77") }

    private fun timingRecord(seq: Int, ns: Long) = record(seq).put("kind", "AUDIO_TIMING")
        .put("data", JSONObject().put("schema", "audio_timing_v1")
            .put("attempted", "$seq").put("dropped", "0").put("droppedBeforeSequence", "0")
            .put("batchDropped", "0")
            .put("events", JSONArray().put(JSONArray().put("$seq").put("$ns").put(1).put("$seq")
                .put(DiagnosticAudioTiming.RECEIVED).put("9223372036854775807").put("9223372036854775807"))))

    @Test fun delayedMarkerRetainsReceiverTimeWindowAndLateBatchesWithoutReplayingCursor() {
        val selected = intake(audioTiming = true)
        for ((seq, ns) in listOf(1 to 14_000_000_000L, 2 to 16_000_000_000L, 3 to 19_000_000_000L)) {
            assertTrue(selected.record(timingRecord(seq, ns), 600))
            selected.consume()
        }
        selected.audioPressure(JSONObject().put("monoNs", "20000000000").put("session", "77")
            .put("generation", 1).put("reason", 1))
        assertTrue(selected.record(timingRecord(4, 17_000_000_000L), 600))
        assertTrue(selected.record(timingRecord(5, 31_000_000_000L), 600))
        selected.consume()
        val page = selected.fetch(2, 32)
        assertEquals(listOf("3", "4"), (0 until page.getJSONArray("records").length()).map {
            page.getJSONArray("records").getJSONObject(it).getJSONObject("record").getString("seq")
        })
        val timing = page.getJSONObject("audioTiming")
        assertEquals("16000000000", timing.getString("oldestEventNs"))
        assertEquals("19000000000", timing.getString("latestEventNs"))
        assertEquals("2", timing.getString("evictedCount"))
        assertTrue(timing.getBoolean("tailUnknown"))
    }

    @Test fun missingMarkerKeepsBoundedRollingTimingHistory() {
        val selected = intake(audioTiming = true)
        repeat(3000) { index ->
            assertTrue(selected.record(timingRecord(index + 1, index.toLong()), 600))
            selected.consume()
        }
        val timing = selected.fetch(0, 1).getJSONObject("audioTiming")
        assertTrue(timing.isNull("marker"))
        assertTrue(timing.getInt("retainedBytes") <= DiagnosticProxyIntake.AUDIO_TIMING_BYTES)
        assertTrue(timing.getString("evictedCount").toLong() > 0)
        assertTrue(timing.getBoolean("tailUnknown"))
    }

    @Test fun audioPressureIsIndependentOfSourceCursorAndRequiresSelection() {
        val marker = JSONObject().put("monoNs", "100").put("session", "77")
            .put("generation", 3).put("reason", 1)
        assertThrows(ProtocolException::class.java) { intake().audioPressure(marker) }
        val selected = intake(audioTiming = true)
        assertTrue(selected.fetch(0, 1).getJSONObject("audioTiming").isNull("marker"))
        assertTrue(selected.record(record(1), 256))
        selected.audioPressure(marker)
        selected.audioPressure(JSONObject().put("reason", 1).put("generation", 3)
            .put("session", "77").put("monoNs", "100"))
        assertEquals("100", selected.fetch(0, 1).getJSONObject("audioTiming")
            .getJSONObject("marker").getString("monoNs"))
        assertEquals("0", selected.fetch(0, 1).getString("nextAfterSeq"))
        assertThrows(ProtocolException::class.java) {
            selected.audioPressure(JSONObject(marker.toString()).put("monoNs", "101"))
        }
        selected.consume()
        assertEquals("1", selected.fetch(0, 1).getString("nextAfterSeq"))
    }

    @Test fun reservedTerminalWaitsForEarlierRecordsEvenWhenIntakeIsFull() {
        val intake = intake()
        assertEquals(8, intake.bootstrap().getJSONArray("stages").length())
        repeat(64) { assertTrue(intake.record(record(it + 1, it != 0), 256)) }
        assertFalse(intake.record(record(65), 256))
        intake.terminal(JSONObject().put("reason", "receiver_retired_stop"), true, "{}")
        val finishing = intake.fetch(0, 32)
        assertEquals("finishing", finishing.getString("state"))
        assertEquals("65", finishing.getString("cutoffOrdinal"))
        assertEquals(64, finishing.getInt("pendingRecords"))
        assertEquals("0", finishing.getString("latestSeq"))
        assertThrows(ProtocolException::class.java) { intake.record(record(66), 256) }
        intake.consume()
        val first = intake.fetch(0, 32)
        assertEquals("finished", first.getString("state"))
        assertTrue(first.getBoolean("endConfirmed"))
        assertEquals("65", first.getString("latestSeq"))
        assertEquals("1", first.getJSONObject("intakeLoss").getString("count"))
        assertEquals("65", first.getJSONObject("intakeLoss").getString("firstSeq"))
        assertTrue(first.getJSONArray("records").getJSONObject(0).isNull("session"))
        assertFalse(first.getJSONArray("records").getJSONObject(0).getJSONObject("record").has("role"))
        assertTrue(first.getBoolean("hasMore"))
        val second = intake.fetch(32, 32)
        assertEquals("64", second.getString("nextAfterSeq"))
        assertFalse(second.getBoolean("hasMore"))
        assertEquals("receiver_retired_stop", intake.terminalValue()!!.getString("reason"))
    }

    @Test fun routineSaturationPreservesBoundIdentityAndFailureInCriticalReserve() {
        val intake = intake()
        val bound = record(1).put("kind", "SESSION_BOUND")
        assertTrue(intake.record(bound, 256))
        for (seq in 2..56) {
            assertTrue(intake.record(record(seq).put("kind", "AUDIO_OUTPUT")
                .put("data", JSONObject().put("stage", "sample")), 256))
        }
        assertFalse(intake.record(record(57).put("kind", "AUDIO_OUTPUT")
            .put("data", JSONObject().put("stage", "sample")), 256))
        assertTrue(intake.record(record(58).put("kind", "AUDIO_OUTPUT")
            .put("data", JSONObject().put("stage", "failed")), 256))
        intake.consume()
        val first = intake.fetch(0, 32)
        val tail = intake.fetch(32, 32)
        assertEquals("SESSION_BOUND", first.getJSONArray("records").getJSONObject(0)
            .getJSONObject("record").getString("kind"))
        assertEquals("failed", tail.getJSONArray("records").getJSONObject(24)
            .getJSONObject("record").getJSONObject("data").getString("stage"))
        assertEquals("57", tail.getJSONObject("intakeLoss").getString("firstSeq"))
        assertEquals("0", tail.getJSONObject("retentionLoss").getString("count"))
    }

    @Test fun eofDiscardCountsQueuedRecordsAndNeverInventsUnknownTail() {
        val intake = intake()
        assertTrue(intake.record(record(3), 256))
        assertTrue(intake.record(record(5), 256))
        intake.terminal(null, false)
        intake.consume(discard = true)
        val page = intake.fetch(0, 32)
        assertEquals("finished", page.getString("state"))
        assertFalse(page.getBoolean("endConfirmed"))
        val loss = page.getJSONObject("forcedLoss")
        assertEquals("2", loss.getString("count"))
        assertEquals("3", loss.getString("firstSeq"))
        assertEquals("5", loss.getString("lastSeq"))
        assertFalse(loss.getBoolean("exactRange"))
        assertNull(intake.terminalValue())
    }

    @Test fun retiredFenceRejectsLateRecordsAndTerminalBeforeCutoffRuns() {
        val intake = intake()
        val validity = java.util.concurrent.atomic.AtomicBoolean(true)
        intake.bindValidity(validity)
        assertTrue(intake.record(record(1), 256))
        validity.set(false)
        assertThrows(ProtocolException::class.java) { intake.record(record(2), 256) }
        assertThrows(ProtocolException::class.java) {
            intake.terminal(JSONObject().put("reason", "receiver_retired_stop"), true, "{}")
        }
        intake.closeUnconfirmed(discard = true)
        val page = intake.fetch(0, 32)
        assertFalse(page.getBoolean("endConfirmed"))
        assertEquals("1", page.getJSONObject("forcedLoss").getString("count"))
        assertNull(intake.committedOutcome())
    }

    @Test fun terminalBytesRemainReservedWhenStagingHitsByteLimit() {
        val intake = intake()
        repeat(59) { assertTrue(intake.record(record(it + 1), 4096)) }
        assertFalse(intake.record(record(60), 4096))
        intake.terminal(JSONObject().put("padding", "x".repeat(16_000)), true, "{}")
        intake.consume(discard = true)
        val page = intake.fetch(0, 32)
        assertEquals("59", page.getJSONObject("forcedLoss").getString("count"))
        assertEquals("1", page.getJSONObject("intakeLoss").getString("count"))
        assertEquals("60", page.getString("cutoffOrdinal"))
        assertTrue(page.getBoolean("endConfirmed"))
    }

    @Test fun retentionEvictionAndSourceIdentityRemainExplicit() {
        val intake = intake()
        repeat(4097) {
            assertTrue(intake.record(record(it + 1), 256))
            intake.consume()
        }
        val page = intake.fetch(0, 32)
        assertEquals("2", page.getString("oldestSeq"))
        assertEquals("1", page.getJSONObject("retentionLoss").getString("count"))
        assertEquals("77", page.getJSONArray("records").getJSONObject(0).getString("session"))
        assertThrows(ProtocolException::class.java) {
            intake.record(record(4098).put("session", "78"), 256)
        }
    }
}

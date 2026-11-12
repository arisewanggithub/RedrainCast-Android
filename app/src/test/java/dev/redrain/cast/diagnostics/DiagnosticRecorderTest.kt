package dev.redrain.cast.diagnostics

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class DiagnosticRecorderTest {
    private var now = 1_000_000_000L
    private val recorder = DiagnosticRecorder { now }
    private fun start(
        flags: Set<String> = setOf("audio.output"), session: Long? = 73,
        durationMs: Int = 1000, periodMs: Int = 100
    ): String {
        recorder.sessionStarted("receiver", 73)
        return recorder.start("receiver", session, flags, durationMs, periodMs).getString("captureId")
    }
    private fun records(id: String) = recorder.fetch(id, 0, 32).getJSONArray("records")
    private fun allRecords(id: String): List<JSONObject> {
        val result = ArrayList<JSONObject>()
        var cursor = 0L
        do {
            val page = recorder.fetch(id, cursor, 32)
            val records = page.getJSONArray("records")
            for (index in 0 until records.length()) result.add(records.getJSONObject(index))
            cursor = page.getString("nextAfterSeq").toLong()
        } while (page.getBoolean("hasMore"))
        return result
    }
    private fun error(code: String, action: () -> Unit) {
        try { action(); fail("Expected $code") } catch (e: DiagnosticError) { assertEquals(code, e.code) }
    }
    private fun publishAfterResponseSnapshot(action: () -> Unit): AtomicReference<Throwable?> {
        val snapshotTaken = CountDownLatch(1)
        val publicationFinished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        Thread {
            try {
                if (!snapshotTaken.await(5, TimeUnit.SECONDS)) throw AssertionError("Response snapshot was not taken")
                action()
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                publicationFinished.countDown()
            }
        }.start()
        recorder.afterResponseSnapshot = {
            recorder.afterResponseSnapshot = null
            snapshotTaken.countDown()
            assertTrue("Concurrent publication did not finish",
                publicationFinished.await(5, TimeUnit.SECONDS))
        }
        return failure
    }
    private fun assertLossWatermarks(response: JSONObject) {
        val watermarks = response.getJSONObject("lossWatermarks")
        assertEquals(response.getString("latestSeq"), watermarks.getString("publishedSeq"))
        assertEquals(response.getString("oldestSeq"), watermarks.getString("oldestRetainedSeq"))
        assertTrue(watermarks.getString("latestRetainedSeq").toLong() <=
            watermarks.getString("publishedSeq").toLong())
        val admission = watermarks.getJSONObject("admission")
        assertEquals(response.getString("droppedBeforeRetention"), admission.getString("total"))
        val admissionTotal = listOf("routine_capacity", "critical_capacity", "closed_capture",
            "expired", "oversized_record").sumOf {
            admission.getJSONObject(it).getString("count").toLong()
        }
        assertEquals(admission.getString("total").toLong(), admissionTotal)
        val retention = watermarks.getJSONObject("retention")
        val evicted = retention.getJSONObject("routine").getString("count").toLong() +
            retention.getJSONObject("critical").getString("count").toLong()
        assertEquals(response.getString("evictedAfterRetention").toLong(), evicted)
    }
    @Test fun noHistoryOrClockReadsBeforeStartAndOnlySelectedGroups() {
        var clocks = 0
        val inactive = DiagnosticRecorder { clocks++; now }
        repeat(100) {
            inactive.observe("sender", 73, DiagnosticKind.AUDIO_CAPTURE, DiagnosticStage.BEGIN)
        }
        assertEquals(0, clocks)
        val id = start()
        recorder.observe("receiver", 73, DiagnosticKind.AUDIO_TRANSPORT, DiagnosticStage.BEGIN)
        recorder.observe("sender", 73, DiagnosticKind.AUDIO_OUTPUT, DiagnosticStage.BEGIN)
        recorder.observe("receiver", 74, DiagnosticKind.AUDIO_OUTPUT, DiagnosticStage.BEGIN)
        recorder.observe("receiver", 73, DiagnosticKind.AUDIO_OUTPUT, DiagnosticStage.BEGIN)
        assertEquals(2, records(id).length())
    }
    @Test fun endIsIdempotentRejectsLateObservationsAndRetainsExactly120Seconds() {
        val id = start()
        val ended = recorder.end(id)
        recorder.observe("receiver", 73, DiagnosticKind.AUDIO_OUTPUT, DiagnosticStage.COMPLETE)
        assertEquals(ended.toString(), recorder.end(id).toString())
        assertEquals("frozen", recorder.status().getString("state"))
        assertEquals(3, records(id).length())
        now += 119_999_999_999
        assertEquals("frozen", recorder.status().getString("state"))
        now++
        assertEquals("idle", recorder.status().getString("state"))
        error("CAPTURE_NOT_FOUND") { recorder.fetch(id, 0, 32) }
    }
    @Test fun leaseDisconnectAndConsentNeverRenewOrCarryIntoAnotherCast() {
        val id = start()
        now += 1_000_000_000
        assertEquals("lease_expired", recorder.end(id, "lease_expired").getString("reason"))
        recorder.clear(id)
        val second = start()
        recorder.disconnect()
        assertEquals("client_disconnected", recorder.end(second).getString("reason"))
        recorder.revoke()
        assertFalse(recorder.collecting)
        error("PERMISSION_REQUIRED") { recorder.start("receiver", null, setOf("session"), 1000, 100) }
        assertEquals("idle", recorder.status().getString("state"))
    }
    @Test fun nextSessionBindsOnlyNewIdentityAndNeverReplacement() {
        val id = start(setOf("session"), null)
        recorder.observe("receiver", 73, DiagnosticKind.SESSION, DiagnosticStage.CONFIG)
        assertEquals(1, records(id).length())
        recorder.sessionStarted("sender", 74)
        recorder.sessionStarted("receiver", 75)
        assertEquals("75", recorder.status().getJSONObject("target").getString("session"))
        recorder.sessionEnded("receiver", 75)
        recorder.sessionStarted("receiver", 76)
        assertEquals("frozen", recorder.status().getString("state"))
        assertEquals("75", recorder.status().getJSONObject("target").getString("session"))
    }
    @Test fun validationIsAtomicAndClearNeverStartsCollection() {
        error("UNSUPPORTED_FLAG") { recorder.start("receiver", null, setOf("video.unknown"), 1000, 100) }
        error("SESSION_NOT_FOUND") { recorder.start("receiver", 999, setOf("session"), 1000, 100) }
        assertEquals("idle", recorder.status().getString("state"))
        val id = start()
        error("CAPTURE_ACTIVE") { recorder.clear(id) }
        recorder.end(id)
        error("RETAINED_CAPTURE_EXISTS") { recorder.start("receiver", null, setOf("session"), 1000, 100) }
        recorder.clear(id)
        assertFalse(recorder.collecting)
    }
    @Test fun fullAdmissionDropsAndSequencesExposeGapsWithBoundedRetention() {
        val id = start()
        recorder.tick()
        repeat(3000) {
            recorder.observe("receiver", 73, DiagnosticKind.AUDIO_OUTPUT, DiagnosticStage.CONFIG)
        }
        recorder.tick()
        val page = recorder.fetch(id, 0, 32)
        assertEquals("${3000 - (DiagnosticRecorder.PENDING - DiagnosticRecorder.CRITICAL_RESERVED)}",
            page.getString("droppedBeforeRetention"))
        assertEquals("${2 + DiagnosticRecorder.PENDING - DiagnosticRecorder.CRITICAL_RESERVED}",
            page.getString("latestSeq"))
        val watermarks = page.getJSONObject("lossWatermarks")
        assertEquals(page.getString("latestSeq"), watermarks.getString("publishedSeq"))
        assertEquals(page.getString("droppedBeforeRetention"),
            watermarks.getJSONObject("admission").getString("total"))
        assertEquals(page.getString("droppedBeforeRetention"),
            watermarks.getJSONObject("admission").getJSONObject("routine_capacity").getString("count"))
        repeat(8) {
            repeat(1024) { recorder.observe("receiver", 73, DiagnosticKind.AUDIO_OUTPUT, DiagnosticStage.CONFIG) }
            recorder.tick()
        }
        val status = recorder.status()
        assertTrue(status.getString("evictedAfterRetention").toLong() > 0)
        assertTrue(status.getJSONObject("lossWatermarks").getJSONObject("retention")
            .getJSONObject("routine").getString("count").toLong() > 0)
        val empty = recorder.fetch(id, Long.MAX_VALUE, 32)
        assertEquals("${Long.MAX_VALUE}", empty.getString("nextAfterSeq"))
        assertEquals(0, empty.getJSONArray("records").length())
    }
    @Test fun blockedOperationAgeSurvivesWithoutInventedCompletion() {
        val id = start()
        val operation = recorder.operation("receiver", 73, DiagnosticKind.AUDIO_OUTPUT)
        recorder.observe("receiver", 73, DiagnosticKind.AUDIO_OUTPUT, DiagnosticStage.BEGIN,
            operation = operation, pts = 8800, endPts = 9200, count = 20, bytes = 80,
            point = DiagnosticPoint.WRITE)
        now += 200_000_000
        recorder.tick()
        val all = records(id)
        val sample = (0 until all.length()).map { all.getJSONObject(it) }.first { it.getString("kind") == "RECORDER_SAMPLE" }
        val pending = sample.getJSONObject("data").getJSONArray("inFlight").getJSONObject(0)
        assertEquals("$operation", pending.getString("operationId"))
        assertEquals("200000000", pending.getString("ageNs"))
        assertEquals("8800", pending.getString("ptsUs"))
        assertEquals("9200", pending.getString("endPtsUs"))
        assertEquals("20", pending.getString("count"))
        assertEquals("80", pending.getString("bytes"))
        recorder.end(id)
        val terminal = allRecords(id).last { it.getString("kind") == "CAPTURE_ENDED" }.getJSONObject("data")
        assertEquals(1, terminal.getInt("inFlightCount"))
        assertEquals("$operation", terminal.getJSONArray("inFlight").getJSONObject(0).getString("operationId"))
        assertEquals("200000000", terminal.getJSONArray("inFlight").getJSONObject(0).getString("ageNs"))
        assertEquals("8800", terminal.getJSONArray("inFlight").getJSONObject(0).getString("ptsUs"))
        assertFalse(recorder.collecting)
    }
    @Test fun concurrentProducersAndEndCannotReenableAnEpoch() {
        val id = start()
        val begin = CountDownLatch(1)
        val done = CountDownLatch(4)
        repeat(4) {
            Thread {
                begin.await()
                repeat(150) { recorder.observe("receiver", 73, DiagnosticKind.AUDIO_OUTPUT, DiagnosticStage.CONFIG) }
                done.countDown()
            }.start()
        }
        begin.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        recorder.end(id)
        recorder.tick()
        assertFalse(recorder.collecting)
        assertEquals("frozen", recorder.status().getString("state"))
        assertEquals((1L..603L).toList(), allRecords(id).map { it.getString("seq").toLong() })
        recorder.clear(id)
        val fresh = start()
        assertEquals("2", recorder.fetch(fresh, 0, 32).getString("latestSeq"))
    }
    @Test fun staleSlowWriteCannotEndAReplacementCapture() {
        val first = start()
        recorder.end(first)
        recorder.clear(first)
        val second = start()
        recorder.disconnectCapture(first, "slow_client")
        assertTrue(recorder.collecting)
        assertEquals(second, recorder.status().getString("captureId"))
    }
    @Test fun oldNativeCompletionCannotContaminateNewCaptureOnTheSameSession() {
        val first = start()
        val operation = recorder.operation("receiver", 73, DiagnosticKind.AUDIO_OUTPUT)
        recorder.observe("receiver", 73, DiagnosticKind.AUDIO_OUTPUT, DiagnosticStage.BEGIN,
            operation = operation, point = DiagnosticPoint.WRITE)
        recorder.end(first)
        recorder.clear(first)
        val second = start()
        recorder.observe("receiver", 73, DiagnosticKind.AUDIO_OUTPUT, DiagnosticStage.COMPLETE,
            operation = operation, point = DiagnosticPoint.WRITE)
        recorder.observe("receiver", 73, DiagnosticKind.AUDIO_OUTPUT, DiagnosticStage.COMPLETE,
            operation = 0, point = DiagnosticPoint.WRITE)
        assertEquals(2, records(second).length())
    }
    @Test fun completedFastOperationsAggregateDurationAndBytes() {
        val id = start()
        val operation = recorder.operation("receiver", 73, DiagnosticKind.AUDIO_OUTPUT)
        recorder.observe("receiver", 73, DiagnosticKind.AUDIO_OUTPUT, DiagnosticStage.BEGIN,
            operation = operation, point = DiagnosticPoint.WRITE)
        now += 500_000
        recorder.observe("receiver", 73, DiagnosticKind.AUDIO_OUTPUT, DiagnosticStage.COMPLETE,
            operation = operation, point = DiagnosticPoint.WRITE, count = 64, bytes = 128)
        now += 200_000_000
        recorder.tick()
        val summary = allRecords(id).first { it.getJSONObject("data").optJSONObject("metrics")
            ?.optString("schema") == "operation_summary" }
            .getJSONObject("data").getJSONObject("metrics")
        assertEquals("1", summary.getString("started"))
        assertEquals("1", summary.getString("completed"))
        assertEquals("128", summary.getString("bytes"))
        assertEquals("64", summary.getString("units"))
        assertEquals("1", summary.getJSONObject("durationBuckets").getString("under1Ms"))
        assertEquals("500000", summary.getString("maxDurationNs"))
        assertTrue(allRecords(id).none {
            it.getString("kind") == "AUDIO_OUTPUT" &&
                it.getJSONObject("data").getString("stage") in setOf("begin", "complete")
        })
    }

    @Test fun routineFloodCannotEvictRecoverySessionOrLifecycleEvidence() {
        val id = start(setOf("audio.output", "recovery", "session"))
        recorder.tick()
        recorder.observe("receiver", 73, DiagnosticKind.RECOVERY, DiagnosticStage.RESET,
            track = 2, generation = 9, reason = DiagnosticReason.FAILURE)
        repeat(24) {
            repeat(800) {
                recorder.observe("receiver", 73, DiagnosticKind.AUDIO_OUTPUT, DiagnosticStage.CONFIG)
            }
            recorder.tick()
        }
        recorder.observe("receiver", 73, DiagnosticKind.SESSION, DiagnosticStage.FAILED)
        recorder.end(id)
        val retained = allRecords(id)
        assertTrue(retained.any { it.getString("kind") == "RECOVERY" &&
            it.getJSONObject("data").getString("stage") == "reset" })
        assertTrue(retained.any { it.getString("kind") == "SESSION" &&
            it.getJSONObject("data").getString("stage") == "failed" })
        assertTrue(retained.any { it.getString("kind") == "CAPTURE_ENDED" })
        assertTrue(recorder.status().getString("evictedAfterRetention").toLong() > 0)
    }

    @Test fun sustainedThreeMinuteTrafficIsSampledAndStaysInsideRetentionBudget() {
        val id = start(durationMs = 180000, periodMs = 1000)
        repeat(180) {
            repeat(1254) {
                val operation = recorder.operation("receiver", 73, DiagnosticKind.AUDIO_OUTPUT)
                recorder.observe("receiver", 73, DiagnosticKind.AUDIO_OUTPUT, DiagnosticStage.BEGIN,
                    operation = operation, point = DiagnosticPoint.READ)
                recorder.observe("receiver", 73, DiagnosticKind.AUDIO_OUTPUT, DiagnosticStage.COMPLETE,
                    operation = operation, point = DiagnosticPoint.READ, bytes = 256)
            }
            now += 1_000_000_000L
            recorder.tick()
        }
        recorder.end(id)
        val retained = allRecords(id)
        val summaries = retained.filter {
            it.getJSONObject("data").optJSONObject("metrics")?.optString("schema") == "operation_summary"
        }
        assertEquals(180, summaries.size)
        assertEquals(225720L, summaries.sumOf {
            it.getJSONObject("data").getJSONObject("metrics").getString("completed").toLong()
        })
        assertTrue(retained.size < DiagnosticRecorder.MAX_RECORDS)
        val bytes = retained.sumOf { it.toString().toByteArray(Charsets.UTF_8).size }
        assertTrue(bytes <= DiagnosticRecorder.MAX_BYTES)
        assertEquals("0", recorder.status().getJSONObject("lossWatermarks").getJSONObject("retention")
            .getJSONObject("routine").getString("count"))
    }

    @Test fun zeroOperationTimingCallsCoalesceToOneTypedPerPeriodSummary() {
        val id = start()
        repeat(5000) {
            recorder.observe("receiver", 73, DiagnosticKind.AUDIO_OUTPUT, DiagnosticStage.SAMPLE,
                point = DiagnosticPoint.TIMING, bytes = 4)
        }
        now += 100_000_000L
        recorder.tick()
        val summary = allRecords(id).single { it.getJSONObject("data").optJSONObject("metrics")
            ?.optString("schema") == "operation_summary" }
            .getJSONObject("data").getJSONObject("metrics")
        assertEquals("5000", summary.getString("zeroOperationEvents"))
        assertEquals("20000", summary.getString("bytes"))
        assertEquals("timing", summary.getString("point"))
    }

    @Test fun slowOperationSummaryRetainsOneBoundedCorrelatedExample() {
        val id = start()
        val operation = recorder.operation("receiver", 73, DiagnosticKind.AUDIO_OUTPUT)
        recorder.observe("receiver", 73, DiagnosticKind.AUDIO_OUTPUT, DiagnosticStage.BEGIN,
            track = 2, generation = 7, operation = operation, point = DiagnosticPoint.AUDIO_START_WAIT)
        now += 25_000_000L
        recorder.observe("receiver", 73, DiagnosticKind.AUDIO_OUTPUT, DiagnosticStage.COMPLETE,
            track = 2, generation = 7, operation = operation, pts = 12345, count = 4,
            bytes = 256, point = DiagnosticPoint.AUDIO_START_WAIT)
        now += 100_000_000L
        recorder.tick()
        val metrics = allRecords(id).single { it.getJSONObject("data").optJSONObject("metrics")
            ?.optString("schema") == "operation_summary" }
            .getJSONObject("data").getJSONObject("metrics")
        assertEquals("audio_start_wait", metrics.getString("point"))
        assertEquals("1", metrics.getString("slow"))
        assertEquals("$operation", metrics.getString("slowExampleOperationId"))
        assertEquals("25000000", metrics.getString("slowExampleDurationNs"))
        assertEquals(2, metrics.getInt("slowExampleTrack"))
        assertEquals(7, metrics.getInt("slowExampleGeneration"))
        assertEquals("12345", metrics.getString("slowExamplePtsUs"))
        assertEquals("4", metrics.getString("slowExampleCount"))
        assertEquals("256", metrics.getString("slowExampleBytes"))
    }

    @Test fun fetchAndStatusCursorMetadataShareOneLivePublicationSnapshot() {
        val id = start()
        val fetchFailure = publishAfterResponseSnapshot {
            recorder.observe("receiver", 73, DiagnosticKind.AUDIO_OUTPUT, DiagnosticStage.CONFIG)
        }
        val page = recorder.fetch(id, 0, 32)
        assertNull(fetchFailure.get())
        assertLossWatermarks(page)
        val pageLatest = page.getString("latestSeq").toLong()
        val records = page.getJSONArray("records")
        for (index in 0 until records.length()) {
            assertTrue(records.getJSONObject(index).getString("seq").toLong() <= pageLatest)
        }

        val statusFailure = publishAfterResponseSnapshot {
            recorder.observe("receiver", 73, DiagnosticKind.AUDIO_OUTPUT, DiagnosticStage.CONFIG)
        }
        val status = recorder.status()
        assertNull(statusFailure.get())
        assertLossWatermarks(status)

        recorder.end(id)
        val frozen = recorder.status()
        assertEquals("frozen", frozen.getString("state"))
        assertLossWatermarks(frozen)
        assertLossWatermarks(recorder.fetch(id, 0, 32))
    }

    @Test fun admissionLossMetadataStaysAlignedWithConcurrentCapacityDrops() {
        val id = start()
        recorder.tick()
        val failure = publishAfterResponseSnapshot {
            repeat(DiagnosticRecorder.PENDING - DiagnosticRecorder.CRITICAL_RESERVED + 1) {
                recorder.observe("receiver", 73, DiagnosticKind.AUDIO_OUTPUT, DiagnosticStage.CONFIG)
            }
        }
        val page = recorder.fetch(id, 0, 32)
        assertNull(failure.get())
        assertEquals("0", page.getString("droppedBeforeRetention"))
        assertLossWatermarks(page)

        val updated = recorder.status()
        assertEquals("1", updated.getString("droppedBeforeRetention"))
        assertLossWatermarks(updated)
    }

    private fun withReservedProducer(action: (String) -> Unit) {
        val id = start()
        recorder.tick()
        val reserved = CountDownLatch(1)
        val publish = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        recorder.afterSequenceReservation = {
            recorder.afterSequenceReservation = null
            reserved.countDown()
            check(publish.await(5, TimeUnit.SECONDS)) { "Publication was not released" }
        }
        Thread {
            try {
                recorder.observe("receiver", 73, DiagnosticKind.AUDIO_OUTPUT, DiagnosticStage.CONFIG)
            } catch (error: Throwable) {
                failure.set(error)
            } finally { finished.countDown() }
        }.start()
        try {
            assertTrue(reserved.await(5, TimeUnit.SECONDS))
            action(id)
        } finally {
            publish.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertNull(failure.get())
        }
    }

    @Test fun frozenFetchDistinguishesUnpublishedReservationAndDeliversFinalTailAfterResume() {
        var id = ""
        var endedNs = ""
        val protocol = DiagnosticProtocol(recorder, { now })
        var request = 0
        fun command(op: String, args: JSONObject = JSONObject()): JSONObject {
            val response = protocol.handle(JSONObject().put("v", 1).put("id", "r${++request}")
                .put("op", op).put("args", args).toString().toByteArray())
            assertTrue(response.toString(), response.getBoolean("ok"))
            assertTrue(response.toString().toByteArray().size <= 16384)
            return response.getJSONObject("result")
        }
        fun fetch() = command("FETCH", JSONObject().put("captureId", id).put("afterSeq", "2").put("limit", 32))
        command("HELLO")
        withReservedProducer { capture ->
            id = capture
            val operation = recorder.operation("receiver", 73, DiagnosticKind.AUDIO_OUTPUT)
            recorder.observe("receiver", 73, DiagnosticKind.AUDIO_OUTPUT, DiagnosticStage.BEGIN,
                operation = operation, point = DiagnosticPoint.READ)
            endedNs = command("END", JSONObject().put("captureId", id)).getString("endedNs")
            repeat(3) {
                val page = fetch()
                assertEquals(0, page.getJSONArray("records").length())
                assertEquals("2", page.getString("latestSeq"))
                assertEquals("2", page.getString("nextAfterSeq"))
                assertFalse(page.getBoolean("hasMore"))
                assertEquals(3, page.getInt("pendingRecords"))
                assertLossWatermarks(page)
                assertEquals(3, recorder.status().getInt("pendingRecords"))
            }
        }
        val page = fetch()
        val tail = page.getJSONArray("records")
        assertEquals(3, tail.length())
        assertEquals(listOf("3", "4", "5"), (0 until tail.length()).map {
            tail.getJSONObject(it).getString("seq")
        })
        assertEquals("AUDIO_OUTPUT", tail.getJSONObject(0).getString("kind"))
        assertEquals("operation_summary", tail.getJSONObject(1).getJSONObject("data")
            .getJSONObject("metrics").getString("schema"))
        assertEquals("CAPTURE_ENDED", tail.getJSONObject(2).getString("kind"))
        assertEquals(0, page.getInt("pendingRecords"))
        assertFalse(page.getBoolean("hasMore"))
        assertEquals("5", page.getString("latestSeq"))
        assertEquals(endedNs, recorder.status().getString("endedNs"))
        assertEquals("frozen", recorder.status().getString("state"))
        assertLossWatermarks(page)
    }

    @Test fun stalledReservationKeepsAdmissionBoundedAndCannotRenewOrResurrectFrozenLease() {
        withReservedProducer { id ->
            repeat(3000) {
                recorder.observe("receiver", 73, DiagnosticKind.AUDIO_OUTPUT, DiagnosticStage.WINDOW)
            }
            recorder.end(id)
            val page = recorder.fetch(id, 2, 32)
            assertTrue(page.getInt("pendingRecords") in 1..DiagnosticRecorder.PENDING)
            assertTrue(page.getString("droppedBeforeRetention").toLong() > 0)
            assertFalse(page.getBoolean("hasMore"))
            assertEquals("2", page.getString("latestSeq"))
            assertLossWatermarks(page)
            now += 120_000_000_000L
            assertEquals("idle", recorder.status().getString("state"))
            error("CAPTURE_NOT_FOUND") { recorder.fetch(id, 2, 32) }
        }
        assertEquals("idle", recorder.status().getString("state"))
        assertFalse(recorder.collecting)
    }
}

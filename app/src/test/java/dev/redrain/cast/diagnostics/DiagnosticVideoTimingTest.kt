package dev.redrain.cast.diagnostics

import org.junit.Assert.*
import org.junit.Test
import org.screencopy.receiver.media.VideoSchedulingClock
import org.screencopy.receiver.media.AudioClockCorrection
import org.screencopy.receiver.media.AudioClockSource
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class DiagnosticVideoTimingTest {
    @Test fun replacementInFlightAtEndIsVisibleUntilOldFinalIsPublished() {
        val recorder = DiagnosticRecorder { 1_000_000_000L }
        recorder.sessionStarted("receiver", 91)
        val id = recorder.start("receiver", 91, setOf("video.progress", "video.timing"), 1000, 100)
            .getString("captureId")
        val old = checkNotNull(recorder.videoTiming(91, 1))
        old.decoded(10, null)
        old.begin(10, null, 0, 1)
        val swapped = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val result = AtomicReference<DiagnosticVideoTiming?>()
        recorder.afterVideoTimingReplacement = {
            swapped.countDown()
            check(resume.await(5, TimeUnit.SECONDS))
        }
        Thread {
            try { result.set(recorder.videoTiming(91, 2)) }
            catch (t: Throwable) { failure.set(t) }
            finally { done.countDown() }
        }.start()
        try {
            assertTrue(swapped.await(5, TimeUnit.SECONDS))
            recorder.end(id)
            val page = recorder.fetch(id, 0, 32)
            assertTrue("Old final must be pending before frozen drain completes", page.getInt("pendingRecords") > 0)
        } finally {
            resume.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
        }
        failure.get()?.let { throw it }
        assertNull(result.get())
        val page = recorder.fetch(id, 0, 32)
        assertEquals(0, page.getInt("pendingRecords"))
        val records = page.getJSONArray("records")
        val final = (0 until records.length()).map { records.getJSONObject(it) }.single {
            it.optInt("generation") == 1 && it.getJSONObject("data").optString("stage") == "reset"
        }
        assertEquals("1", final.getJSONObject("data").getJSONObject("metrics").getString("incomplete"))
        assertEquals("CAPTURE_ENDED", records.getJSONObject(records.length() - 1).getString("kind"))
    }

    @Test fun initialTrackerCreatedAfterEndIsNeverReturned() {
        val recorder = DiagnosticRecorder { 1_000_000_000L }
        recorder.sessionStarted("receiver", 91)
        val id = recorder.start("receiver", 91, setOf("video.progress", "video.timing"), 1000, 100)
            .getString("captureId")
        val beforeSwap = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val result = AtomicReference<DiagnosticVideoTiming?>()
        recorder.beforeVideoTimingSwap = {
            beforeSwap.countDown()
            check(resume.await(5, TimeUnit.SECONDS))
        }
        Thread {
            try { result.set(recorder.videoTiming(91, 1)) }
            catch (t: Throwable) { failure.set(t) }
            finally { done.countDown() }
        }.start()
        try {
            assertTrue(beforeSwap.await(5, TimeUnit.SECONDS))
            recorder.end(id)
            assertEquals(0, recorder.fetch(id, 0, 32).getInt("pendingRecords"))
        } finally {
            resume.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
        }
        failure.get()?.let { throw it }
        assertNull(result.get())
        assertNull(recorder.videoTiming(91, 1))
    }

    @Test fun competingReplacementsPublishTheirReservedTickets() {
        val recorder = DiagnosticRecorder { 1_000_000_000L }
        recorder.sessionStarted("receiver", 91)
        val id = recorder.start("receiver", 91, setOf("video.progress", "video.timing"), 1000, 100)
            .getString("captureId")
        checkNotNull(recorder.videoTiming(91, 1))
        val paused = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val done = CountDownLatch(1)
        val result = AtomicReference<DiagnosticVideoTiming?>()
        val failure = AtomicReference<Throwable?>()
        recorder.beforeVideoTimingSwap = {
            if (Thread.currentThread().name == "paused-replacement") {
                paused.countDown()
                check(resume.await(5, TimeUnit.SECONDS))
            }
        }
        Thread({
            try { result.set(recorder.videoTiming(91, 2)) }
            catch (t: Throwable) { failure.set(t) }
            finally { done.countDown() }
        }, "paused-replacement").start()
        val winner: DiagnosticVideoTiming
        try {
            assertTrue(paused.await(5, TimeUnit.SECONDS))
            winner = checkNotNull(recorder.videoTiming(91, 2))
            assertTrue(recorder.fetch(id, 0, 32).getInt("pendingRecords") > 0)
        } finally {
            resume.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
        }
        failure.get()?.let { throw it }
        assertSame(winner, result.get())
        recorder.end(id)
        val page = recorder.fetch(id, 0, 32)
        assertEquals(0, page.getInt("pendingRecords"))
        val records = page.getJSONArray("records")
        val stages = (0 until records.length()).map { records.getJSONObject(it) }
            .filter { it.optInt("generation") == 1 }
            .map { it.getJSONObject("data") }
        assertEquals(listOf("sample", "reset"), stages.map { it.getString("stage") })
        assertEquals("stale", stages.first().getString("reason"))
    }

    @Test fun fullRoutineQueueRejectsReplacementWithoutLosingOldTracker() {
        val recorder = DiagnosticRecorder { 1_000_000_000L }
        recorder.sessionStarted("receiver", 91)
        val id = recorder.start("receiver", 91, setOf("video.progress", "video.timing"), 1000, 100)
            .getString("captureId")
        val old = checkNotNull(recorder.videoTiming(91, 1))
        old.decoded(10, null)
        old.begin(10, null, 0, 1)
        recorder.tick()
        repeat(DiagnosticRecorder.PENDING - DiagnosticRecorder.CRITICAL_RESERVED) {
            recorder.observe("receiver", 91, DiagnosticKind.VIDEO_PROGRESS, DiagnosticStage.CONFIG)
        }
        assertNull(recorder.videoTiming(91, 2))
        recorder.end(id)
        val status = recorder.status()
        assertEquals("1", status.getJSONObject("lossWatermarks").getJSONObject("admission")
            .getJSONObject("routine_capacity").getString("count"))
        var cursor = 0L
        val records = mutableListOf<JSONObject>()
        do {
            val page = recorder.fetch(id, cursor, 32)
            val items = page.getJSONArray("records")
            repeat(items.length()) { records.add(items.getJSONObject(it)) }
            cursor = page.getString("nextAfterSeq").toLong()
        } while (page.getBoolean("hasMore"))
        val final = records.single {
            it.optInt("generation") == 1 &&
                it.getJSONObject("data").optJSONObject("metrics")?.optString("schema") == "video_frame_timing"
        }
        assertEquals("1", final.getJSONObject("data").getJSONObject("metrics").getString("incomplete"))
    }

    private fun outOfOrderCallbacks(): DiagnosticVideoTiming {
        val t = DiagnosticVideoTiming(1)
        t.decoded(0, 0)
        for (pts in listOf(10L, 20L, 30L)) {
            t.decoded(pts, 0)
            t.finish(t.begin(pts, pts * 1000, pts * 1000, 1), true)
        }
        listOf(10L, 30L, 20L).forEach { t.callback(it, it * 1000, it * 1000, 1) }
        t.decoded(40, 0)
        return t
    }

    @Test fun callbackAdjacencySurvivesPtsOrderRetirementOfAThenBThenC() {
        val t = outOfOrderCallbacks()
        val before = t.snapshot(false).json()
        assertEquals("3", before.getString("matched"))
        assertEquals("1", before.getJSONObject("callbackCadence").getString("count"))
        assertEquals("20000", before.getJSONObject("ptsCadence").getString("minNs"))
        repeat(DiagnosticVideoTiming.LIMIT) { n ->
            t.decoded(100 + n.toLong(), 0)
            if (n >= DiagnosticVideoTiming.LIMIT - 4) {
                val crossing = t.snapshot(false).json()
                assertEquals("1", crossing.getJSONObject("callbackCadence").getString("count"))
                assertEquals("20000", crossing.getJSONObject("ptsCadence").getString("minNs"))
            }
        }
        val retired = t.snapshot(true).json()
        assertEquals("3", retired.getString("matched"))
        assertEquals("1", retired.getJSONObject("callbackCadence").getString("count"))
        assertEquals("20000", retired.getJSONObject("ptsCadence").getString("maxNs"))
    }

    @Test fun adjacencyStillRetractsWhenOnlyItsPredecessorHasRetired() {
        val t = outOfOrderCallbacks()
        repeat(DiagnosticVideoTiming.LIMIT - 2) { t.decoded(100 + it.toLong(), 0) }
        assertEquals("1", t.snapshot(false).json().getJSONObject("callbackCadence").getString("count"))
        t.callback(30, 30_001, 30_002, 1)
        val invalidated = t.snapshot(false).json()
        assertEquals("2", invalidated.getString("matched"))
        assertEquals("1", invalidated.getString("ambiguous"))
        assertEquals("0", invalidated.getJSONObject("callbackCadence").getString("count"))
        t.decoded(300, 0)
        assertEquals("0", t.snapshot(true).json().getJSONObject("callbackCadence").getString("count"))
    }

    @Test fun deadlineAdjacencyAlsoSurvivesPtsOrderRetirement() {
        val t = DiagnosticVideoTiming(1)
        t.decoded(0, 0)
        listOf(10L, 20L, 30L).forEach { t.decoded(it, 0) }
        for ((pts, deadline) in listOf(10L to 10_000L, 30L to 40_000L, 20L to 20_000L))
            t.finish(t.begin(pts, deadline, deadline, 1), true)
        t.decoded(40, 0)
        assertEquals("10000", t.snapshot(false).json().getJSONObject("deadlineCadenceMinusPts").getString("minNs"))
        repeat(DiagnosticVideoTiming.LIMIT) { t.decoded(100 + it.toLong(), 0) }
        val retired = t.snapshot(true).json()
        assertEquals("1", retired.getJSONObject("deadlineCadenceMinusPts").getString("count"))
        assertEquals("10000", retired.getJSONObject("deadlineCadenceMinusPts").getString("maxNs"))
    }

    @Test fun fullyPopulatedReceiverFetchRetainsClockCorrectionsWithinFrameBound() {
        val recorder = DiagnosticRecorder { 1_000_000_000L }
        recorder.sessionStarted("receiver", 91)
        val id = recorder.start("receiver", 91, setOf("video.progress", "video.timing"), 1000, 100).getString("captureId")
        val t = checkNotNull(recorder.videoTiming(91, 1))
        t.decoded(0, 0)
        for (n in 1..4) {
            val pts = n * 100L
            val now = 1_000_000_000L + n * 100_000
            val c = VideoSchedulingClock(100, now - 100, 3, "CACHED_AUDIO", pts - 100, pts - 101,
                n.toLong(), now - 10_000, AudioClockSource.TIMESTAMP, 10, 50,
                AudioClockCorrection(2, 0, 100, 50), false)
            t.decoded(pts, 0)
            val deadline = now + if (n % 2 == 0) 10_000 else 20_000
            val token = t.begin(pts, deadline, now, 3, clock = c, scheduleAtNs = now)
            t.callback(pts, deadline + n, now, 3)
            t.finish(token, true)
        }
        t.decoded(500, 0)
        recorder.end(id)
        val sender = DiagnosticRecorder { 1_000_000_000L }
        val registry = DiagnosticArmRegistry(sender) { 1_000_000_000L }
        val pair = registry.start("collector", setOf("session"),
            setOf("video.progress", "video.timing"), 1000, 100).getString("pairId")
        val claim = checkNotNull(registry.claim(91))
        val stages = JSONArray()
        org.screencopy.receiver.protocol.DiagnosticProxyNegotiation.stages.forEachIndexed { index, stage ->
            stages.put(JSONObject().put("stage", stage).put("monoNs", "$index"))
        }
        val bootstrap = JSONObject().put("session", "91").put("pairId", pair).put("captureId", id)
            .put("clockId", recorder.clockId).put("complete", true).put("loss", false).put("stages", stages)
        val intake = DiagnosticProxyIntake("91", pair, id, recorder.clockId, true, bootstrap)
        var sourceCursor = 0L
        do {
            val sourcePage = recorder.fetch(id, sourceCursor, 32)
            val sourceRecords = sourcePage.getJSONArray("records")
            for (index in 0 until sourceRecords.length()) {
                val source = sourceRecords.getJSONObject(index)
                val frame = JSONObject().put("v", 2).put("type", "RECORD").put("record", source)
                val output = java.io.ByteArrayOutputStream()
                DiagnosticProxyWire.writeFrame(output, frame)
                intake.record(source, output.size())
                intake.consume()
            }
            sourceCursor = sourcePage.getString("nextAfterSeq").toLong()
        } while (sourcePage.getBoolean("hasMore"))
        registry.covered(claim, intake, id, recorder.clockId)
        var cursor = 0L
        var populated: JSONObject? = null
        do {
            val result = registry.fetch("collector", pair, Long.MAX_VALUE, cursor, 32)
            val reply = JSONObject().put("v", 2).put("id", "r1").put("op", "FETCH")
                .put("ok", true).put("result", result)
            assertTrue(reply.toString().toByteArray(Charsets.UTF_8).size <= 16384)
            val records = result.getJSONArray("records")
            for (index in 0 until records.length()) {
                val source = records.getJSONObject(index).getJSONObject("record")
                val metrics = source.getJSONObject("data").optJSONObject("metrics")
                if (metrics?.optString("schema") == "video_frame_timing") {
                    assertEquals("91", DiagnosticProxyWire.validateSource(source, "receiver", "91", id, true))
                    org.screencopy.receiver.protocol.StrictJson.decode(reply.toString().toByteArray(),
                        maxDepth = DiagnosticProxyWire.FETCH_DEPTH, trackSizes = false)
                    val correction = metrics.getJSONObject("minimumDeadlineCadenceExample")
                        .getJSONObject("previous").getJSONObject("clock").getJSONObject("lastPacketCorrection")
                    assertEquals("2", correction.getString("sequence"))
                    populated = reply
                }
            }
            cursor = result.getJSONObject("receiver").getString("nextAfterSeq").toLong()
        } while (result.getJSONObject("receiver").getBoolean("hasMore"))
        val directory = File(File("build"), "diagnostics-contract").apply { mkdirs() }
        File(directory, "receiver-timing-fetch.json").writeText(checkNotNull(populated).toString())
    }

    @Test fun legacyReceiverCaptureProducesOnlyLegacyBoundedRecords() {
        val recorder = DiagnosticRecorder { 1_000_000_000L }
        recorder.sessionStarted("receiver", 91)
        try {
            recorder.start("receiver", 91, setOf("video.timing"), 1000, 100)
            fail("timing requires progress")
        } catch (error: DiagnosticError) {
            assertEquals("INVALID_ARGUMENT", error.code)
        }
        val flags = listOf("session", "recovery", "audio.transport", "audio.queue", "audio.output",
            "video.progress", "transport.health")
        val start = recorder.start("receiver", 91, flags.toSet(), 1000, 100)
        assertEquals(2048, start.getInt("maxRecordBytes"))
        val id = start.getString("captureId")
        for (n in 1..10) {
            recorder.video("receiver", 91, 1, DiagnosticVideoStage.RELEASED, n * 100L,
                delayUs = -100, releaseCallNs = 100)
            assertNull(recorder.videoTiming(91, 1))
        }
        recorder.end(id)
        val result = recorder.fetch(id, 0, 32)
        assertFalse(result.getBoolean("hasMore"))
        val records = result.getJSONArray("records")
        var progress = false
        for (index in 0 until records.length()) {
            val record = records.getJSONObject(index)
            assertTrue(record.toString().toByteArray(Charsets.UTF_8).size <= DiagnosticRecorder.MAX_RECORD_BYTES)
            val schema = record.getJSONObject("data").optJSONObject("metrics")?.optString("schema")
            assertNotEquals("video_frame_timing", schema)
            if (schema == "video_progress") progress = true
        }
        assertTrue(progress)
        val directory = File(File("build"), "diagnostics-contract").apply { mkdirs() }
        val legacyHelloReply = JSONObject().put("v", 1).put("id", "r0").put("op", "HELLO")
            .put("ok", true).put("result", JSONObject())
        val fixture = JSONObject().put("hello", legacyHelloReply)
            .put("fetch", JSONObject().put("result", result))
        File(directory, "receiver-legacy-capture.json").writeText(fixture.toString())
    }

    @Test fun lateDuplicateRetractsRetainedCallbackMatchCadenceAndExemplar() {
        val t = DiagnosticVideoTiming(1)
        t.decoded(0, 0)
        t.decoded(10, 0)
        release(t, 10, 10_000, 10_010, 10_020)
        t.decoded(20, 0)
        release(t, 20, 20_000, 20_100, 20_200)
        t.decoded(30, 0)
        val before = t.snapshot(false).json()
        assertEquals("2", before.getString("matched"))
        assertEquals("1", before.getJSONObject("callbackCadence").getString("count"))
        t.callback(20, 20_101, 20_201, 1)
        val after = t.snapshot(false).json()
        assertEquals("1", after.getString("matched"))
        assertEquals("1", after.getString("ambiguous"))
        assertEquals("0", after.getString("unmatched"))
        assertEquals("0", after.getJSONObject("callbackCadence").getString("count"))
        assertEquals("10", after.getJSONObject("worstCallbackErrorExample").getString("ptsUs"))
        assertEquals("2", before.getString("matched"))
        t.decoded(10, 0)
        val duplicateOutput = t.snapshot(false).json()
        assertEquals("0", duplicateOutput.getString("matched"))
        assertEquals("2", duplicateOutput.getString("ambiguous"))
        assertEquals("0", duplicateOutput.getJSONObject("deadlineCadenceMinusPts").getString("count"))
        assertTrue(duplicateOutput.isNull("worstCallbackErrorExample"))
        assertEquals("0", t.snapshot(true).json().getString("matched"))
    }

    @Test fun callbackOrderAndBoundedRetirementRemainTruthfulForVeryLateDuplicates() {
        val t = DiagnosticVideoTiming(1)
        t.decoded(0, 0)
        t.decoded(10, 0)
        val a = t.begin(10, 10_000, 10_000, 1)
        t.finish(a, true)
        t.decoded(20, 0)
        release(t, 20, 20_000, 20_000, 20_000)
        t.callback(10, 10_000, 10_000, 1)
        t.decoded(30, 0)
        release(t, 30, 30_000, 30_000, 30_000)
        t.decoded(40, 0)
        assertEquals("1", t.snapshot(false).json().getJSONObject("callbackCadence").getString("count"))
        assertEquals("20000", t.snapshot(false).json().getJSONObject("ptsCadence").getString("minNs"))
        repeat(DiagnosticVideoTiming.LIMIT) { t.decoded(50 + it.toLong(), 0) }
        val committed = t.snapshot(false).json()
        assertEquals("3", committed.getString("matched"))
        assertEquals("1", committed.getJSONObject("callbackCadence").getString("count"))
        t.callback(10, 10_000, 10_001, 1)
        val outside = t.snapshot(true).json()
        assertEquals("3", outside.getString("matched"))
        assertEquals("1", outside.getString("outsideWindowCallbacks"))
        assertEquals("1", outside.getString("unmatched"))
        assertEquals("1", outside.getJSONObject("callbackCadence").getString("count"))
    }

    @Test fun fullPrecisionContextFitsBoundAndUnstableOrUnavailableClocksNeverInventDeadlines() {
        val t = DiagnosticVideoTiming(1)
        val base = 8_000_000_000_000_000_000L
        t.decoded(0, 0)
        for (n in 1..4) {
            val pts = 1_000_000_000_000_000L + n * 100
            val now = base + n * 100_000
            val c = VideoSchedulingClock(100, now - 100, Long.MAX_VALUE, "CACHED_AUDIO", pts - 100, pts - 101,
                Long.MAX_VALUE, 7_000_000_000_000_000_000L, AudioClockSource.TIMESTAMP, Long.MAX_VALUE,
                Long.MIN_VALUE, AudioClockCorrection(Long.MAX_VALUE, 3_000_000_000_000_000_000L,
                    4_000_000_000_000_000_000L, Long.MIN_VALUE), true)
            t.decoded(pts, 0)
            val deadline = now + if (n % 2 == 0) 10_000 else 20_000
            val token = t.begin(pts, deadline, now, Long.MAX_VALUE, clock = c, scheduleAtNs = now)
            t.callback(pts, deadline + n, now, Long.MAX_VALUE)
            t.finish(token, true)
        }
        t.decoded(2_000_000_000_000_000, 0)
        val json = t.snapshot(false).json()
        assertTrue(json.toString().toByteArray(Charsets.UTF_8).size + 512 < DiagnosticRecorder.MAX_VIDEO_TIMING_RECORD_BYTES)
        assertEquals("${Long.MAX_VALUE}", json.getJSONObject("latestSchedulingExample").getJSONObject("clock")
            .getString("audioObservationSequence"))
        val token = t.begin(2_000_000_000_000_000, base, base, 2, mappingStable = false)
        t.finish(token, true)
        t.decoded(2_000_000_000_000_001, null)
        val unstable = t.snapshot(false).json().getJSONObject("latestSchedulingExample")
        assertFalse(unstable.getBoolean("mappingStable"))
        assertTrue(unstable.isNull("deadlineNs"))
        val noClock = t.begin(2_000_000_000_000_001, null, base, 2,
            clock = VideoSchedulingClock(null, base, 2, "UNAVAILABLE", null, null, null, null,
                null, null, null, null, false))
        t.finish(noClock, true)
        t.decoded(2_000_000_000_000_002, null)
        val unknown = t.snapshot(false).json().getJSONObject("latestSchedulingExample").getJSONObject("clock")
        assertTrue(unknown.isNull("positionUs"))
        assertTrue(unknown.isNull("audioObservationAgeNs"))
        assertTrue(unknown.isNull("lastPacketCorrection"))
    }

    @Test fun schedulingPairsExposeStaleObservationAndReadLatencyWithoutNeedingCallbacks() {
        val t = DiagnosticVideoTiming(1)
        fun schedule(pts: Long, now: Long, position: Long, observation: Long?, sequence: Long?, mapping: Long = 1) {
            t.decoded(pts, 0)
            val clock = VideoSchedulingClock(pts - position, now - 100, mapping,
                if (observation == null) "MONOTONIC" else "CACHED_AUDIO", position, position,
                sequence, observation, null, null, null, null, false)
            val token = t.begin(pts, now + (pts - position) * 1000, now, mapping, clock = clock, scheduleAtNs = now)
            t.finish(token, true)
        }
        t.decoded(0, 0)
        schedule(10, 100_000, 10, 100_000, 7)
        schedule(20, 120_000, 10, 100_000, 7)
        schedule(30, 125_000, 30, 125_000, 8)
        t.decoded(40, 0)
        val frozen = t.snapshot(false)
        val json = frozen.json()
        assertEquals("0", json.getString("matched"))
        assertEquals("20000", json.getJSONObject("deadlineCadenceMinusPts").getString("maxNs"))
        assertEquals("-15000", json.getJSONObject("deadlineCadenceMinusPts").getString("minNs"))
        val pair = json.getJSONObject("maximumDeadlineCadenceExample")
        val before = pair.getJSONObject("previous").getJSONObject("clock")
        val after = pair.getJSONObject("current").getJSONObject("clock")
        assertEquals(before.getString("audioObservationSequence"), after.getString("audioObservationSequence"))
        assertEquals("20000", after.getString("audioObservationAgeNs"))
        assertTrue(after.isNull("audioOutputSource"))
        assertEquals("100", json.getJSONObject("clockReadToSchedule").getString("minNs"))
        schedule(40, 130_000, 40, null, null, mapping = 2)
        schedule(50, 140_000, 50, null, null, mapping = 3)
        t.decoded(60, 0)
        assertEquals("2", t.snapshot(false).json().getJSONObject("deadlineCadenceMinusPts").getString("count"))
        assertTrue(t.snapshot(false).json().getJSONObject("latestSchedulingExample").getJSONObject("clock")
            .isNull("audioObservationAgeNs"))
        t.snapshot(true)
        assertEquals(json.toString(), frozen.json().toString())
    }

    private fun release(t: DiagnosticVideoTiming, pts: Long, deadline: Long, callback: Long,
                        observed: Long, mapping: Long = 1, success: Boolean = true) {
        val token = t.begin(pts, deadline, deadline + 5, mapping)
        t.callback(pts, callback, observed, mapping)
        t.finish(token, success)
    }

    @Test fun reentrantCallbacksRemainSignedAndCadenceUsesActualPts() {
        val t = DiagnosticVideoTiming(1)
        t.decoded(100, 10)
        t.decoded(200, 20)
        release(t, 200, 1_000_000, 1_010_000, 1_000_000)
        t.decoded(230, -5, mapping = 1)
        release(t, 230, 1_030_000, 1_042_000, 1_043_000)
        t.decoded(270, 10)
        val json = t.snapshot(false).json()
        assertEquals("2", json.getString("matched"))
        assertEquals("1", json.getString("futureCallbacks"))
        assertEquals("-10000", json.getJSONObject("observedMinusCallback").getString("minNs"))
        assertEquals("10000", json.getJSONObject("callbackMinusDeadline").getString("minNs"))
        assertEquals("30000", json.getJSONObject("ptsCadence").getString("maxNs"))
        assertEquals("2000", json.getJSONObject("callbackCadenceMinusPts").getString("maxNs"))
        assertEquals("-20000", json.getJSONObject("decodedMinusDeadline").getString("minNs"))
        assertEquals("5", json.getJSONObject("suppliedMinusDeadline").getString("maxNs"))
        val example = json.getJSONObject("worstCallbackErrorExample")
        assertEquals("230", example.getString("ptsUs"))
        assertEquals("1030000", example.getString("deadlineNs"))
        assertEquals("1042000", example.getString("callbackNs"))
        assertEquals("5000", example.getString("decodedMinusDeadlineNs"))
    }

    @Test fun duplicatePtsAndFailedReentrantReleaseNeverMatch() {
        val t = DiagnosticVideoTiming(1)
        t.decoded(1, 0)
        t.decoded(2, 0)
        release(t, 2, 100, 101, 102)
        t.decoded(2, 0)
        release(t, 2, 100, 103, 104)
        t.decoded(3, 0)
        release(t, 3, 200, 201, 202, success = false)
        t.decoded(4, 0)
        val json = t.snapshot(true).json()
        assertEquals("0", json.getString("matched"))
        assertEquals("1", json.getString("ambiguous"))
        assertEquals("1", json.getString("releaseFailed"))
        assertEquals("1", json.getString("unmatched"))
    }

    @Test fun skippedOutputDoesNotInventOrBreakCallbackCadence() {
        val t = DiagnosticVideoTiming(1)
        t.decoded(0, 0)
        t.decoded(10, 0)
        release(t, 10, 100, 100, 100)
        t.decoded(20, 0) // decoded but not rendered
        t.decoded(40, 0)
        release(t, 40, 30_100, 30_100, 30_100)
        t.decoded(50, 0)
        val json = t.snapshot(false).json()
        assertEquals("2", json.getString("matched"))
        assertEquals("30000", json.getJSONObject("ptsCadence").getString("maxNs"))
        assertEquals("0", json.getJSONObject("callbackCadenceMinusPts").getString("maxNs"))
    }

    @Test fun livePendingExcludesCompletedCallbacksButIncludesOutstandingReleases() {
        val t = DiagnosticVideoTiming(1)
        t.decoded(1, 0)
        t.decoded(2, 0)
        val reentrant = t.begin(2, 100, 100, 1)
        assertEquals("1", t.snapshot(false).json().getString("pending"))
        t.callback(2, 100, 100, 1)
        assertEquals("1", t.snapshot(false).json().getString("pending"))
        t.finish(reentrant, true)
        assertEquals("0", t.snapshot(false).json().getString("pending"))

        t.decoded(3, 0)
        val awaitingCallback = t.begin(3, 200, 200, 1)
        t.finish(awaitingCallback, true)
        assertEquals("1", t.snapshot(false).json().getString("pending"))
        t.callback(3, 200, 200, 1)
        assertEquals("0", t.snapshot(false).json().getString("pending"))
    }

    @Test fun outOfOrderMissingEvictionAndMappingChangesAreConservative() {
        val t = DiagnosticVideoTiming(1)
        t.decoded(0, null)
        t.decoded(1, 0)
        val first = t.begin(1, 100, 100, 1)
        t.finish(first, true)
        t.decoded(2, 0)
        release(t, 2, 200, 200, 200)
        t.decoded(3, 0)
        t.callback(1, 100, 250, 1)
        val changed = t.begin(3, 300, 300, 1)
        t.callback(3, 300, 300, 2)
        t.finish(changed, true)
        t.decoded(4, 0)
        repeat(DiagnosticVideoTiming.LIMIT + 5) { n ->
            val pts = n + 4L
            val token = t.begin(pts, 400, 400, 2)
            t.finish(token, true)
            t.decoded(pts + 1, 0)
        }
        val json = t.snapshot(true).json()
        assertEquals("2", json.getString("matched"))
        assertEquals("0", json.getJSONObject("ptsCadence").getString("count"))
        assertEquals("1", json.getString("mappingChanged"))
        assertTrue(json.getString("evicted").toLong() > 0)
        assertTrue(json.getString("missing").toLong() >= DiagnosticVideoTiming.LIMIT)
        assertEquals("1", json.getString("clockUnavailable"))
    }

    @Test fun disabledCaptureAndRetirementNeverAcceptOldTokens() {
        var now = 1_000_000_000L
        val recorder = DiagnosticRecorder { now }
        recorder.sessionStarted("receiver", 7)
        assertNull(recorder.videoTiming(7, 1))
        val id = recorder.start("receiver", 7, setOf("video.progress", "video.timing"), 1000, 100).getString("captureId")
        val timing = checkNotNull(recorder.videoTiming(7, 1))
        timing.decoded(10, 0)
        val token = timing.begin(10, 100, 100, 1)
        now += 1
        recorder.end(id)
        timing.callback(10, 100, 100, 1)
        timing.finish(token, true)
        assertNull(recorder.videoTiming(7, 1))
        val result = timing.snapshot(false).json()
        assertEquals("1", result.getString("incomplete"))
        assertEquals("0", result.getString("matched"))
    }

    @Test fun generationReplacementAndNullClockDoNotCrossMatch() {
        var now = 1_000_000_000L
        val recorder = DiagnosticRecorder { now }
        recorder.sessionStarted("receiver", 8)
        val id = recorder.start("receiver", 8, setOf("video.progress", "video.timing"), 1000, 100).getString("captureId")
        val old = checkNotNull(recorder.videoTiming(8, 1))
        old.decoded(10, 0)
        old.decoded(20, 0)
        val token = old.begin(20, 100, 100, 1)
        old.callback(20, 100, 100, 1)
        val fresh = checkNotNull(recorder.videoTiming(8, 2))
        old.finish(token, true)
        assertNull(recorder.videoTiming(8, 1))
        fresh.decoded(20, null)
        fresh.decoded(30, null)
        val noClock = fresh.begin(30, null, 300, 1)
        fresh.callback(30, 300, 300, 1)
        fresh.finish(noClock, true)
        fresh.decoded(40, null)
        val result = fresh.snapshot(false).json()
        assertEquals("0", result.getString("matched"))
        assertEquals("4", result.getString("clockUnavailable"))
        assertEquals("1", old.snapshot(false).json().getString("incomplete"))
        now += 1
        recorder.end(id)
    }
}

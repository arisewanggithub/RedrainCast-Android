package dev.redrain.cast.diagnostics

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DiagnosticVideoTest {
    private var now = 1_000_000_000L
    private val recorder = DiagnosticRecorder { now }
    private fun start(role: String = "receiver", flags: Set<String> = setOf("video.progress")): String {
        recorder.sessionStarted(role, 73)
        return recorder.start(role, 73, flags, 180000, 100).getString("captureId")
    }
    private fun records(id: String): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        var cursor = 0L
        do {
            val page = recorder.fetch(id, cursor, 32)
            val loss = page.getJSONObject("lossWatermarks")
            assertEquals(page.getString("latestSeq"), loss.getString("publishedSeq"))
            assertEquals(page.getString("oldestSeq"), loss.getString("oldestRetainedSeq"))
            assertTrue(loss.getString("latestRetainedSeq").toLong() <= page.getString("latestSeq").toLong())
            val events = page.getJSONArray("records")
            repeat(events.length()) {
                val event = events.getJSONObject(it)
                assertTrue(event.getString("seq").toLong() > cursor)
                cursor = event.getString("seq").toLong()
                result.add(event)
            }
            assertEquals(cursor.toString(), page.getString("nextAfterSeq"))
        } while (page.getBoolean("hasMore"))
        return result
    }
    private fun video(id: String) = records(id).filter { it.getString("kind") == "VIDEO_PROGRESS" }
    private fun metric(event: JSONObject) = event.getJSONObject("data").getJSONObject("metrics")

    @Test fun disabledWrongRoleSessionAndUnselectedFlagNeverReadClock() {
        var clocks = 0
        val gated = DiagnosticRecorder { clocks++; now }
        fun update(role: String = "receiver", session: Long = 73) =
            gated.video(role, session, 1, DiagnosticVideoStage.DECODED, 100)
        repeat(1000) { update() }
        assertEquals(0, clocks)
        gated.sessionStarted("receiver", 73)
        val id = gated.start("receiver", 73, setOf("session"), 1000, 100).getString("captureId")
        val baseline = clocks
        repeat(1000) { update(); update("sender"); update(session = 74) }
        assertEquals(baseline, clocks)
        gated.end(id)
        val ended = clocks
        update()
        assertEquals(ended, clocks)
    }

    @Test fun highRateUpdatesAreCumulativeBoundedAndStillSampleWhenProgressStops() {
        val id = start()
        repeat(100_000) {
            recorder.video("receiver", 73, 1, DiagnosticVideoStage.DECODED, it * 20_000L, 10)
        }
        assertTrue(video(id).isEmpty())
        now += 100_000_000
        recorder.tick()
        val first = video(id).single()
        assertEquals(1, first.getInt("track"))
        assertEquals(1, first.getInt("generation"))
        assertEquals("100000", metric(first).getString("count"))
        assertEquals("1000000", metric(first).getString("bytes"))
        assertEquals("20000", metric(first).getString("maxPtsGapUs"))
        val observed = metric(first).getString("lastAtNs")
        now += 500_000_000
        recorder.tick()
        val stalled = video(id).last()
        assertEquals(observed, metric(stalled).getString("lastAtNs"))
        assertEquals("100000", metric(stalled).getString("count"))
        assertTrue(stalled.getString("monoNs").toLong() > first.getString("monoNs").toLong())
        assertEquals("0", recorder.fetch(id, 0, 32).getString("droppedBeforeRetention"))
        recorder.end(id)
        assertEquals("100000", metric(video(id).last()).getString("count"))
    }

    @Test fun sourcePtsGapsCallbackTimesReasonsAndGenerationsRemainDistinct() {
        val id = start()
        recorder.video("receiver", 73, 1, DiagnosticVideoStage.RENDERED, 1_000, sourceAtNs = 900)
        now += 40_000_000
        recorder.video("receiver", 73, 1, DiagnosticVideoStage.RENDERED, 81_000, sourceAtNs = 950)
        recorder.video("receiver", 73, 1, DiagnosticVideoStage.SKIPPED, 21_000, reason = DiagnosticReason.STALE)
        recorder.video("receiver", 73, 2, DiagnosticVideoStage.DECODER_INPUT, 100_000, 12, keyFrame = true)
        recorder.video("receiver", 73, 1, DiagnosticVideoStage.RENDERED, 99_000, sourceAtNs = 999)
        now += 100_000_000
        recorder.tick()
        val events = video(id)
        val rendered = events.single { metric(it).getString("videoStage") == "rendered" }
        assertEquals(1, rendered.getInt("generation"))
        assertTrue(metric(rendered).getBoolean("retired"))
        assertEquals("2", metric(rendered).getString("count"))
        assertEquals("80000", metric(rendered).getString("maxPtsGapUs"))
        assertEquals("40000000", metric(rendered).getString("maxObservationGapNs"))
        assertEquals("950", metric(rendered).getString("sourceAtNs"))
        val skipped = events.single { metric(it).getString("videoStage") == "skipped" }
        assertEquals("1", metric(skipped).getJSONObject("reasons").getString("stale"))
        val input = events.single { metric(it).getString("videoStage") == "decoder_input" }
        assertEquals(2, input.getInt("generation"))
        assertFalse(metric(input).getBoolean("retired"))
        assertEquals("1", metric(input).getString("keyFrames"))
        recorder.end(id)
        recorder.clear(id)
        val fresh = start()
        now += 100_000_000
        assertTrue(video(fresh).isEmpty())
    }

    @Test fun generationTransitionsPreserveOldSummaryAndSourceGenerationIsNotWireGeneration() {
        val id = start("sender")
        recorder.video("sender", 73, 1, DiagnosticVideoStage.ENCODED, 10)
        recorder.video("sender", 73, 1, DiagnosticVideoStage.WRITTEN, 10)
        recorder.video("sender", 73, 2, DiagnosticVideoStage.WRITTEN, 20)
        recorder.video("sender", 73, 1, DiagnosticVideoStage.ENCODED, 30)
        now += 100_000_000
        recorder.tick()
        val events = video(id)
        val reset = events.single { it.getJSONObject("data").getString("stage") == "reset" }
        assertEquals(1, reset.getInt("generation"))
        assertEquals("1", metric(reset).getString("count"))
        assertTrue(metric(reset).getBoolean("retired"))
        val source = events.single { metric(it).getString("videoStage") == "encoded" }
        assertEquals(1, source.getInt("generation"))
        assertEquals("2", metric(source).getString("count"))
        assertFalse(metric(source).getBoolean("retired"))
    }

    @Test fun videoSamplingUnderRetentionPressurePreservesCursorAndLossWatermarks() {
        val id = start()
        repeat(400) { index ->
            DiagnosticVideoStage.values().forEach {
                recorder.video("receiver", 73, 1, it, index * 20_000L)
            }
            now += 100_000_000
            recorder.tick()
        }
        recorder.end(id)
        val page = recorder.fetch(id, 0, 32)
        assertEquals("0", page.getString("droppedBeforeRetention"))
        assertTrue(page.getString("evictedAfterRetention").toLong() > 0)
        val retained = records(id)
        assertTrue(retained.size <= DiagnosticRecorder.MAX_RECORDS)
        assertEquals("CAPTURE_ENDED", retained.last().getString("kind"))
        val finalVideo = retained.last { it.getString("kind") == "VIDEO_PROGRESS" }
        assertEquals("400", metric(finalVideo).getString("count"))
    }

    @Test fun configurationRetiresPriorCallbacksAndTerminalSamplesSurviveFullRoutineStaging() {
        val id = start()
        recorder.video("receiver", 73, 1, DiagnosticVideoStage.RENDERED, 10, sourceAtNs = 123)
        recorder.videoConfigured("receiver", 73, 2,
            DiagnosticVideoConfiguration(DiagnosticCodec.H264, 640, 360, 60, false))
        recorder.video("receiver", 73, 1, DiagnosticVideoStage.RENDERED, 20, sourceAtNs = 456)
        repeat(2000) {
            recorder.observe("receiver", 73, DiagnosticKind.VIDEO_PROGRESS, DiagnosticStage.CONFIG)
        }
        recorder.end(id)
        val events = video(id)
        val final = events.last { it.getJSONObject("data").getString("stage") == "sample" }
        assertEquals(1, final.getInt("generation"))
        assertEquals("1", metric(final).getString("count"))
        assertEquals("10", metric(final).getString("lastPtsUs"))
        assertTrue(metric(final).getBoolean("retired"))
        val page = recorder.fetch(id, 0, 32)
        assertTrue(page.getString("droppedBeforeRetention").toLong() > 0)
        assertEquals("CAPTURE_ENDED", records(id).last().getString("kind"))
    }
}

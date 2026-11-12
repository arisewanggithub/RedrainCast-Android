package dev.screencopy.sender

import java.io.StringWriter
import java.util.concurrent.CountDownLatch
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VideoFrameTraceTest {
    @Test fun tenMinuteWindowDoesNotStopAtThirtySecondsAndHasCapacityFor240Fps() {
        var now = 0L
        val trace = VideoFrameTrace(true, 7, now = { now }, capacity = VideoFrameTrace.LONG_CAPACITY,
            durationNs = VideoFrameTrace.LONG_DURATION_NS)
        trace.record(VideoFrameStage.CAPTURE_CALLBACK)
        now = 30_000_000_000L
        trace.record(VideoFrameStage.ENCODED, 30_000_000)
        assertNull(trace.takeCompleted())
        now = VideoFrameTrace.LONG_DURATION_NS - 1
        trace.record(VideoFrameStage.WRITE_END, 599_999_999)
        assertNull(trace.takeCompleted())
        now++
        val snapshot = checkNotNull(trace.takeCompleted())
        assertEquals("duration", snapshot.reason)
        assertEquals(600_000_000_000L, snapshot.stoppedNs - snapshot.startedNs)
        assertTrue(snapshot.capacity >= 600 * 240 * VideoFrameStage.entries.size)
        assertEquals(3, snapshot.events.size)
        assertEquals(0, snapshot.dropped)
    }

    @Test fun disabledTraceDoesNotReadClockOrPublish() {
        val trace = VideoFrameTrace(false, 7, now = { error("Disabled trace read the clock") })
        trace.record(VideoFrameStage.ENCODED, 99)
        assertNull(trace.takeCompleted(force = true))
    }

    @Test fun durationStartsAtFirstEventAndDoesNotInventMissingFrames() {
        var now = 9_000_000_000L
        val trace = VideoFrameTrace(true, 7, now = { now }, durationNs = 100)
        assertNull(trace.takeCompleted())
        now += 1_000
        trace.record(VideoFrameStage.CAPTURE_ACQUIRED, 123_456)
        now += 10
        trace.record(VideoFrameStage.SUBMIT_END, 123_456)
        now += 10
        trace.record(VideoFrameStage.ENCODED, 123_456, 2)
        now += 10
        trace.record(VideoFrameStage.WRITE_END, 123_456, 3)
        assertNull(trace.takeCompleted())
        now += 70
        val snapshot = checkNotNull(trace.takeCompleted())
        assertEquals(9_000_001_000L, snapshot.startedNs)
        assertEquals("duration", snapshot.reason)
        assertEquals(0, snapshot.dropped)
        assertEquals(listOf(0, 1, 2, 3), snapshot.events.map { it.sequence })
        assertTrue(snapshot.events.all { it.ptsUs == 123_456L })
        assertEquals(listOf(0, 0, 2, 3), snapshot.events.map { it.generation })
        trace.record(VideoFrameStage.ENCODED, 999)
        assertNull(trace.takeCompleted(force = true))
    }

    @Test fun capacityOverflowAndEarlyStopAreExplicitAndPublishedOnlyOnce() {
        val trace = VideoFrameTrace(true, 7, now = { 0 }, capacity = 2)
        repeat(3) { trace.record(VideoFrameStage.ENCODED, it.toLong()) }
        val snapshot = checkNotNull(trace.takeCompleted())
        assertEquals("capacity", snapshot.reason)
        assertEquals(1, snapshot.dropped)
        assertEquals(2, snapshot.events.size)
        assertNull(trace.takeCompleted(force = true))
        val early = VideoFrameTrace(true, 8, now = { 0 })
        early.record(VideoFrameStage.SUBMIT_BEGIN, 42)
        val partial = checkNotNull(early.takeCompleted(force = true))
        assertEquals("session_end", partial.reason)
        assertEquals(listOf(VideoFrameStage.SUBMIT_BEGIN), partial.events.map { it.stage })
    }

    @Test fun jsonLinesContainOnlyTimestampMetadataAndExplicitWindowStatus() {
        val trace = VideoFrameTrace(true, 91, now = { 100 })
        trace.record(VideoFrameStage.CAPTURE_FILTERED, 500)
        val output = StringWriter()
        checkNotNull(trace.takeCompleted(force = true)).writeTo(output)
        val lines = output.toString().lineSequence().filter { it.isNotBlank() }.map(::JSONObject).toList()
        assertEquals(listOf("header", "event", "summary"), lines.map { it.getString("kind") })
        assertEquals(1_000_000_000L, lines[0].getLong("frequency"))
        assertEquals(91L, lines[0].getLong("session"))
        assertEquals("CAPTURE_FILTERED", lines[1].getString("stage"))
        assertEquals(500L, lines[1].getLong("ptsUs"))
        assertFalse(lines[2].getBoolean("windowComplete"))
        assertEquals(setOf("kind", "sequence", "atNs", "stage", "ptsUs", "generation"), lines[1].keys().asSequence().toSet())
    }

    @Test fun concurrentCaptureAndWriterKeepACompleteOrderedBoundedTrace() {
        val trace = VideoFrameTrace(true, 7, capacity = 512)
        val start = CountDownLatch(1)
        val threads = listOf(VideoFrameStage.SUBMIT_END, VideoFrameStage.WRITE_END).map { stage ->
            Thread {
                start.await()
                repeat(100) { trace.record(stage, it.toLong()) }
            }.apply { start() }
        }
        start.countDown()
        threads.forEach { it.join(2000); assertFalse(it.isAlive) }
        val snapshot = checkNotNull(trace.takeCompleted(force = true))
        assertEquals(200, snapshot.events.size)
        assertEquals((0 until 200).toList(), snapshot.events.map { it.sequence })
        assertTrue(snapshot.events.zipWithNext().all { (a, b) -> a.atNs <= b.atNs })
    }
}

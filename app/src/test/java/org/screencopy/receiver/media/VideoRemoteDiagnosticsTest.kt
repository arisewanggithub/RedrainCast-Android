package org.screencopy.receiver.media

import dev.redrain.cast.diagnostics.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class VideoRemoteDiagnosticsTest {
    @Test fun audioDeadlineAndItsDiagnosticSnapshotAreIdenticalWithRecordingOnOrOff() {
        val recorder = RemoteDiagnostics.recorder
        val deadlines = ArrayList<Long>()
        try {
            for (enabled in listOf(false, true)) {
                recorder.revoke()
                recorder.grantConsent()
                recorder.sessionStarted("receiver", 970)
                val capture = if (enabled)
                    recorder.start("receiver", 970, setOf("video.progress", "video.timing"), 10000, 1000).getString("captureId")
                    else null
                val now = AtomicLong(0)
                val clock = PlaybackClock(now::get)
                val source = clock.anchorObservedAudio(1_000_000, 48000)
                clock.audioWritten(100_000)
                clock.observeAudio(source, 5000, 5_000_000,
                    AudioOutputTiming(48000, 4800, 4800, 4800, 240, 0,
                        AudioClockSource.TIMESTAMP, 5_000_000,
                        timestampAtNs = 5_000_000, timestampEstimateFrames = 240)) { it * 1_000_000 / 48000 }
                val released = java.util.concurrent.atomic.AtomicInteger()
                val completed = CountDownLatch(1)
                val finish = CountDownLatch(1)
                val decoder = object : VideoDecoder by Decoder() {
                    override fun releaseOutput(index: Int, renderAtNs: Long?) {
                        if (renderAtNs != null) {
                            deadlines.add(renderAtNs)
                            released.incrementAndGet()
                        }
                    }
                }
                val player = VideoPlayer(VideoFormat("video/avc", 640, 360, emptyList(), 50),
                    clock, { code, detail -> fail("$code $detail") }, decoder,
                    onRotation = { now.addAndGet(7_000_000) })
                player.diagnosticSession = 970
                player.diagnosticGeneration = 1
                player.onDecoded = { pts -> now.set((pts - 1_280_000) * 1000) }
                player.onPlaybackStep = {
                    if (released.get() == 2) {
                        completed.countDown()
                        finish.await(3, TimeUnit.SECONDS)
                    }
                }
                try {
                    player.offer(AccessUnit(1_300_000, byteArrayOf(1), true))
                    player.offer(AccessUnit(1_320_000, byteArrayOf(1), false))
                    player.offer(AccessUnit(1_340_000, byteArrayOf(1), false))
                    player.start()
                    assertTrue(completed.await(3, TimeUnit.SECONDS))
                    player.halt()
                    assertEquals("Time spent after the clock read cannot inflate an absolute deadline",
                        320_000_000L, deadlines.last())
                    assertEquals(300_000_000L, deadlines[deadlines.size - 2])
                    assertEquals(1_005_000L, clock.currentUs())
                    if (capture != null) {
                        recorder.end(capture)
                        val scheduling = events(recorder, capture).single {
                            it.optJSONObject("data")?.optJSONObject("metrics")?.optString("schema") == "video_frame_timing"
                        }.getJSONObject("data").getJSONObject("metrics").getJSONObject("latestSchedulingExample")
                        val snapshot = scheduling.getJSONObject("clock")
                        assertEquals("40000000", scheduling.getString("scheduleAtNs"))
                        assertEquals(scheduling.getString("scheduleAtNs"), snapshot.getString("readAtNs"))
                        assertEquals("1040000", snapshot.getString("sourcePositionUs"))
                        assertEquals(deadlines.last(), scheduling.getString("deadlineNs").toLong())
                        assertEquals(deadlines.last(), scheduling.getString("scheduleAtNs").toLong() +
                            snapshot.getString("delayUs").toLong() * 1000)
                    }
                } finally {
                    finish.countDown()
                    player.close()
                    recorder.sessionEnded("receiver", 970)
                }
            }
            assertEquals(deadlines.take(2), deadlines.drop(2))
        } finally {
            recorder.revoke()
            recorder.grantConsent()
        }
    }

    @Test fun reentrantCallbackFromFailedCodecReleaseIsNotMatched() {
        val recorder = RemoteDiagnostics.recorder
        recorder.revoke()
        recorder.grantConsent()
        recorder.sessionStarted("receiver", 971)
        val id = recorder.start("receiver", 971, setOf("video.progress", "video.timing"), 10000, 1000).getString("captureId")
        val failed = CountDownLatch(1)
        val base = Decoder()
        val decoder = object : VideoDecoder by base {
            override fun releaseOutput(index: Int, renderAtNs: Long?) {
                if (renderAtNs != null) {
                    base.callback(800_000, renderAtNs)
                    throw IllegalStateException("release failed")
                }
                base.releaseOutput(index, renderAtNs)
            }
        }
        val player = VideoPlayer(VideoFormat("video/avc", 640, 360, emptyList(), 50), PlaybackClock(),
            { _, _ -> failed.countDown() }, decoder, 2)
        player.diagnosticSession = 971
        player.diagnosticGeneration = 2
        try {
            player.offer(AccessUnit(800_000, byteArrayOf(1), true))
            player.start()
            assertTrue(failed.await(3, TimeUnit.SECONDS))
            recorder.end(id)
            val timing = events(recorder, id).single {
                it.optJSONObject("data")?.optJSONObject("metrics")?.optString("schema") == "video_frame_timing"
            }.getJSONObject("data").getJSONObject("metrics")
            assertEquals("1", timing.getString("releaseFailed"))
            assertEquals("0", timing.getString("matched"))
        } finally {
            player.close()
            recorder.sessionEnded("receiver", 971)
            recorder.revoke()
            recorder.grantConsent()
        }
    }

    @Test fun matchedFrameTimingWorksWithoutStatisticsAndRetainsFutureCallback() {
        val recorder = RemoteDiagnostics.recorder
        recorder.revoke()
        recorder.grantConsent()
        recorder.sessionStarted("receiver", 972)
        val id = recorder.start("receiver", 972, setOf("video.progress", "video.timing"), 10000, 1000).getString("captureId")
        val now = AtomicLong(1_000_000_000L)
        val completed = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val decoder = object : VideoDecoder {
            override val description = "test"
            override val supportsRenderedStatistics = true
            private var listener: (Long, Long) -> Unit = { _, _ -> }
            private val outputs = ArrayDeque<VideoOutput>()
            private val timestamps = mutableMapOf<Int, Long>()
            private var index = 0
            @Volatile var released = 0
            override fun renderedStatistics(listener: (Long, Long) -> Unit) { this.listener = listener }
            override fun dequeueInput() = 0
            override fun input(index: Int): ByteBuffer = ByteBuffer.allocate(16)
            override fun queueInput(index: Int, size: Int, ptsUs: Long, keyFrame: Boolean) {
                val next = ++this.index
                timestamps[next] = ptsUs
                outputs.add(VideoOutput(next, ptsUs, size, 0))
            }
            override fun dequeueOutput(): VideoOutput? = outputs.removeFirstOrNull()
            override fun releaseOutput(index: Int, renderAtNs: Long?) {
                if (renderAtNs != null) {
                    listener(checkNotNull(timestamps[index]), renderAtNs + 5_000_000)
                    released++
                }
            }
            override fun close() {}
        }
        val player = VideoPlayer(VideoFormat("video/avc", 640, 360, emptyList(), 50),
            PlaybackClock(now::get) { now.addAndGet(it * 1_000_000) }, { _, _ -> }, decoder, 2)
        player.diagnosticSession = 972
        player.diagnosticGeneration = 2
        player.onPlaybackStep = {
            if (decoder.released == 3) {
                completed.countDown()
                finish.await(3, TimeUnit.SECONDS)
            }
        }
        try {
            listOf(800_000L, 820_000L, 850_000L).forEachIndexed { index, pts ->
                player.offer(AccessUnit(pts, byteArrayOf(1), index == 0))
            }
            player.start()
            assertTrue(completed.await(3, TimeUnit.SECONDS))
            player.halt()
            recorder.end(id)
            val timing = events(recorder, id).single {
                it.optJSONObject("data")?.optJSONObject("metrics")?.optString("schema") == "video_frame_timing"
            }.getJSONObject("data").getJSONObject("metrics")
            assertEquals("0", timing.getString("matched"))
            assertEquals("3", timing.getString("futureCallbacks"))
            assertEquals("0", timing.getJSONObject("callbackMinusDeadline").getString("count"))
            assertEquals("0", timing.getJSONObject("observedMinusCallback").getString("count"))
            assertTrue(timing.isNull("worstCallbackErrorExample"))
        } finally {
            finish.countDown()
            player.close()
            recorder.sessionEnded("receiver", 972)
            recorder.revoke()
            recorder.grantConsent()
        }
    }

    @Test fun skippedPicturesDistinguishRecoveryCutoffFromReplacementAndExcludeCodecConfig() {
        val recorder = RemoteDiagnostics.recorder
        recorder.revoke()
        recorder.grantConsent()
        recorder.sessionStarted("receiver", 975)
        val id = recorder.start("receiver", 975, setOf("video.progress"), 10000, 1000).getString("captureId")
        val decoder = Decoder(multipleOutputs = true)
        val now = AtomicLong(1_000_000_000L)
        val completed = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val player = VideoPlayer(VideoFormat("video/avc", 640, 360, emptyList(), 50),
            PlaybackClock(now::get) { now.addAndGet(it * 1_000_000) }, { _, _ -> }, decoder, 3)
        player.diagnosticSession = 975
        player.diagnosticGeneration = 3
        player.discardOutputBeforePts = 750_000
        player.onDecoded = { pts ->
            if (pts == 800_000L) now.addAndGet(10_000_000)
        }
        player.onPlaybackStep = {
            if (decoder.released) {
                completed.countDown()
                finish.await(3, TimeUnit.SECONDS)
            }
        }
        try {
            player.offer(AccessUnit(800_000, byteArrayOf(1), true))
            player.start()
            assertTrue(completed.await(3, TimeUnit.SECONDS))
            player.halt()
            recorder.end(id)
            val progress = events(recorder, id).filter {
                it.getString("kind") == "VIDEO_PROGRESS" && it.getJSONObject("data").getString("stage") == "sample" &&
                    it.getJSONObject("data").getJSONObject("metrics").getString("schema") == "video_progress"
            }.associate { it.getJSONObject("data").getJSONObject("metrics").let { metrics ->
                metrics.getString("videoStage") to metrics
            } }
            assertEquals("3", progress.getValue("decoded").getString("count"))
            assertEquals("2", progress.getValue("skipped").getString("count"))
            val reasons = progress.getValue("skipped").getJSONObject("reasons")
            assertEquals("1", reasons.getString("stale"))
            assertEquals("1", reasons.getString("replaced"))
            assertEquals("820000", progress.getValue("released").getString("lastPtsUs"))
            assertTrue(progress.getValue("released").isNull("bytes"))
            assertTrue(progress.getValue("released").isNull("keyFrames"))
        } finally {
            finish.countDown()
            player.close()
            recorder.sessionEnded("receiver", 975)
            recorder.revoke()
            recorder.grantConsent()
        }
    }

    @Test fun codecFailureCarriesStageGenerationAndAttemptedPtsWithoutExceptionText() {
        val recorder = RemoteDiagnostics.recorder
        recorder.revoke()
        recorder.grantConsent()
        recorder.sessionStarted("receiver", 974)
        val id = recorder.start("receiver", 974, setOf("video.progress"), 10000, 1000).getString("captureId")
        val failed = CountDownLatch(1)
        val decoder = object : VideoDecoder by Decoder() {
            override fun queueInput(index: Int, size: Int, ptsUs: Long, keyFrame: Boolean) {
                throw IllegalStateException("private codec exception text")
            }
        }
        val player = VideoPlayer(VideoFormat("video/avc", 640, 360, emptyList(), 50), PlaybackClock(),
            { _, _ -> failed.countDown() }, decoder, 8)
        player.diagnosticSession = 974
        player.diagnosticGeneration = 8
        try {
            player.offer(AccessUnit(800_000, byteArrayOf(1), true))
            player.start()
            assertTrue(failed.await(3, TimeUnit.SECONDS))
            recorder.end(id)
            val failure = events(recorder, id).single {
                it.getString("kind") == "VIDEO_PROGRESS" &&
                    it.getJSONObject("data").getString("stage") == "failed"
            }
            assertEquals(8, failure.getInt("generation"))
            assertEquals("800000", failure.getJSONObject("data").getString("ptsUs"))
            val metrics = failure.getJSONObject("data").getJSONObject("metrics")
            assertEquals("input_queue", metrics.getString("videoStage"))
            assertEquals("codec_api", metrics.getString("fault"))
            assertFalse(failure.toString().contains("private codec exception text"))
        } finally {
            player.close()
            recorder.sessionEnded("receiver", 974)
            recorder.revoke()
            recorder.grantConsent()
        }
    }

    @Test fun codecInputDecodeReleaseAndCallbackHaveIndependentPtsAndNoDisplayClaim() {
        val recorder = RemoteDiagnostics.recorder
        recorder.revoke()
        recorder.grantConsent()
        recorder.sessionStarted("receiver", 973)
        val id = recorder.start("receiver", 973, setOf("video.progress", "video.timing"), 10000, 100).getString("captureId")
        val now = AtomicLong(1_000_000_000L)
        val clock = PlaybackClock(now::get) { now.addAndGet(it * 1_000_000) }
        val completed = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val decoder = Decoder()
        val errors = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val player = VideoPlayer(VideoFormat("video/avc", 640, 360, emptyList(), 50), clock,
            { code, detail -> errors.add("$code:$detail") }, decoder, 4)
        player.diagnosticSession = 973
        player.diagnosticGeneration = 4
        player.onPlaybackStep = {
            if (decoder.released) {
                completed.countDown()
                finish.await(3, TimeUnit.SECONDS)
            }
        }
        try {
            player.offer(AccessUnit(800_000, byteArrayOf(1), true))
            player.start()
            assertTrue("Decoder release did not complete: $errors", completed.await(3, TimeUnit.SECONDS))
            assertTrue(errors.toString(), errors.isEmpty())
            player.halt()
            decoder.callback(900_000, 321) // A callback after retirement is not fresh progress.
            recorder.end(id)
            val events = events(recorder, id).filter { it.getString("kind") == "VIDEO_PROGRESS" }
            val configuration = events.single {
                it.getJSONObject("data").getString("stage") == "config"
            }.getJSONObject("data").getJSONObject("metrics")
            assertTrue(configuration.getBoolean("renderedCallback"))
            assertEquals("codec_release_or_render_callback_not_physical_display",
                configuration.getString("presentationEvidence"))
            val progress = events.filter { it.getJSONObject("data").getString("stage") == "sample" &&
                it.getJSONObject("data").getJSONObject("metrics").getString("schema") == "video_progress" }
            assertEquals(setOf("decoder_input", "decoded", "released", "rendered"),
                progress.map { it.getJSONObject("data").getJSONObject("metrics").getString("videoStage") }.toSet())
            progress.forEach {
                assertEquals(1, it.getInt("track"))
                assertEquals(4, it.getInt("generation"))
                val metrics = it.getJSONObject("data").getJSONObject("metrics")
                assertEquals("1", metrics.getString("count"))
                assertEquals("800000", metrics.getString("lastPtsUs"))
            }
            val rendered = progress.single {
                it.getJSONObject("data").getJSONObject("metrics").getString("videoStage") == "rendered"
            }.getJSONObject("data").getJSONObject("metrics")
            assertEquals("123", rendered.getString("sourceAtNs"))
            val released = progress.single {
                it.getJSONObject("data").getJSONObject("metrics").getString("videoStage") == "released"
            }.getJSONObject("data").getJSONObject("metrics")
            assertEquals("1", released.getString("holdSamples"))
            assertEquals("1", released.getString("releaseCallSamples"))
            assertEquals("0", released.getString("maxReleaseCallNs"))
            val timing = events.single {
                it.getJSONObject("data").optJSONObject("metrics")?.optString("schema") == "video_frame_timing"
            }.getJSONObject("data").getJSONObject("metrics")
            assertEquals("1", timing.getString("ambiguous"))
        } finally {
            finish.countDown()
            player.close()
            recorder.sessionEnded("receiver", 973)
            recorder.revoke()
            recorder.grantConsent()
        }
    }

    private fun events(recorder: DiagnosticRecorder, id: String): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        var cursor = 0L
        do {
            val page = recorder.fetch(id, cursor, 32)
            val records = page.getJSONArray("records")
            repeat(records.length()) { result.add(records.getJSONObject(it)) }
            cursor = page.getString("nextAfterSeq").toLong()
        } while (page.getBoolean("hasMore"))
        return result
    }

    private class Decoder(private val multipleOutputs: Boolean = false) : VideoDecoder {
        override val description = "test"
        override val supportsRenderedStatistics = true
        var callback: (Long, Long) -> Unit = { _, _ -> }
        override fun renderedStatistics(listener: (Long, Long) -> Unit) { callback = listener }
        private val outputs = ArrayDeque<VideoOutput>()
        @Volatile var released = false
        override fun dequeueInput() = 0
        override fun input(index: Int): ByteBuffer = ByteBuffer.allocate(16)
        override fun queueInput(index: Int, size: Int, ptsUs: Long, keyFrame: Boolean) {
            if (multipleOutputs) {
                outputs.add(VideoOutput(10, ptsUs, 0, 2))
                outputs.add(VideoOutput(11, ptsUs - 100_000, size, 0))
            }
            outputs.add(VideoOutput(1, ptsUs, size, 0))
            if (multipleOutputs) outputs.add(VideoOutput(2, ptsUs + 20_000, size, 0))
        }
        override fun dequeueOutput(): VideoOutput? = if (outputs.isEmpty()) null else outputs.removeFirst()
        override fun releaseOutput(index: Int, renderAtNs: Long?) {
            if (renderAtNs != null) {
                callback(if (multipleOutputs) 820_000 else 800_000, 123)
                released = true
            }
        }
        override fun close() {}
    }
}

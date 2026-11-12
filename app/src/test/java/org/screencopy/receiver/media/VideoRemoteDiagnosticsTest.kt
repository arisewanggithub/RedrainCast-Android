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
                it.getString("kind") == "VIDEO_PROGRESS" && it.getJSONObject("data").getString("stage") == "sample"
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
        val id = recorder.start("receiver", 973, setOf("video.progress"), 10000, 100).getString("captureId")
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
            val progress = events.filter { it.getJSONObject("data").getString("stage") == "sample" }
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

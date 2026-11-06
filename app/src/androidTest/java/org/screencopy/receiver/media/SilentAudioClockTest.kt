package org.screencopy.receiver.media

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class SilentAudioClockTest {
    @Test(timeout = 10_000)
    fun productionPcmOutputRunsPastTwoSecondsWithRealClockAndRateCorrection() = playback(120, false)

    @Test(timeout = 30_000)
    fun pacedCompensatedPcmKeepsNativeRateFixedWithoutSteadyUnderruns() = playback(750, false)

    @Test(timeout = 30_000)
    fun scriptedFfmpegCompensationPreservesSourceClockWithoutNativeRateChanges() = playback(750, true)

    @Test(timeout = 150_000)
    fun smallScriptedRateTransitionsKeepRealPcmPlaybackAlivePastTheReportedStopTime() = playback(6000, true)

    @Test(timeout = 10_000)
    fun aSingleDecodeStepRefillsRealNativeCapacityRatherThanOneFiveMillisecondChunk() {
        val failures = ConcurrentLinkedQueue<String>()
        val format = AudioFormat("pcm", 48000, 2, byteArrayOf())
        val output = PlatformAudioOutput(format) { code, detail -> failures.add("$code: $detail") }
        val clock = PlaybackClock().apply { audioExpected = true; anchorVideo(1_000_000) }
        val converter = FfmpegPcmResampler(48000, 2)
        val player = AudioPlayer(format, clock, { code, detail -> failures.add("$code: $detail") },
            { output }, resamplerFactory = { _, _ -> converter })
        try {
            val frames = minOf(2880, output.bufferFrames)
            player.offer(AccessUnit(1_000_000, ByteArray(frames * 4)))
            AudioPlayer::class.java.getDeclaredMethod("step").apply { isAccessible = true }.invoke(player)
            assertTrue(failures.toString(), failures.isEmpty())
            assertEquals((frames - player.retainedSourceFrames).toLong(), player.sourceSubmittedFrames)
            assertTrue("PCM was limited to one 5ms chunk", player.sourceSubmittedFrames > 240)
            checkBudget(output.timing())
        } finally { player.close() }
    }

    private fun playback(packets: Int, scripted: Boolean) {
        val failures = ConcurrentLinkedQueue<String>()
        val compensations = ConcurrentLinkedQueue<AudioCompensation>()
        val regulation = ConcurrentLinkedQueue<AudioRegulationDiagnostic>()
        val firstSubmittedPts = AtomicLong(-1)
        val format = AudioFormat("pcm", 48000, 2, byteArrayOf())
        val output = PlatformAudioOutput(format) { code, detail -> failures.add("$code: $detail") }
        val clock = PlaybackClock().apply { audioExpected = true; anchorVideo(1_000_000) }
        val converter = FfmpegPcmResampler(48000, 2)
        val resampler = object : PcmResampler by converter {
            override fun compensate(deltaFrames: Int, distanceFrames: Int) {
                val next = if (scripted) listOf(960, -960, 0)[compensations.size % 3] else deltaFrames
                val distance = if (next == 0) 0 else if (scripted) 192000 else distanceFrames
                converter.compensate(next, distance)
                compensations.add(AudioCompensation(next, distance))
            }
        }
        val stableOutput = object : AudioOutput by output {
            override fun setSpeed(speed: Float) = fail("AudioTrack rate must stay nominal during PCM compensation")
        }
        val player = AudioPlayer(format, clock, { code, detail -> failures.add("$code: $detail") },
            { stableOutput }, onRegulation = regulation::add, resamplerFactory = { _, _ -> resampler }).apply {
                onFirstSubmitted = firstSubmittedPts::set
            }
        try {
            player.start()
            val started = System.nanoTime()
            var warmupUnderruns = 0
            var previousPresented = 0L
            repeat(packets) { index ->
                val remaining = started + index * 20_000_000L - System.nanoTime()
                if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining)
                assertTrue("Actual PCM pipeline failed: $failures", failures.isEmpty())
                player.offer(AccessUnit(1_000_000 + index * 20_000L, ByteArray(3840)))
                val timing = output.timing()
                checkBudget(timing)
                assertTrue(timing.presentedFrames >= previousPresented)
                previousPresented = timing.presentedFrames
                assertEquals(1f, output.preparedSpeed, 0f)
                if (index == 150) warmupUnderruns = output.underrunCount
            }
            val steadyUnderruns = output.underrunCount
            val deliveryDeadline = System.nanoTime() + 1_000_000_000
            while (player.processedSourceFrames < packets * 960L && failures.isEmpty() &&
                System.nanoTime() < deliveryDeadline) Thread.sleep(2)
            val expected = packets * 960L - player.retainedSourceFrames
            val expectedUs = expected * 1_000_000L / 48000
            val deadline = System.nanoTime() + 1_000_000_000
            while ((player.sourceSubmittedFrames < expected || player.sourcePositionUs() < expectedUs) &&
                failures.isEmpty() && System.nanoTime() < deadline) Thread.sleep(2)
            android.util.Log.i("ScreenCopyReceiver", "event=FFMPEG_PCM_PROBE scripted=$scripted" +
                " source_frames=${player.sourceSubmittedFrames} expected_frames=$expected" +
                " processed_source_frames=${player.processedSourceFrames}" +
                " first_source_pts_us=${firstSubmittedPts.get()}" +
                " observed_dropped_output_frames=${regulation.lastOrNull()?.droppedFrames ?: "unobserved"}" +
                " queued_packets=${player.queuedPackets()}" +
                " filter_frames=${converter.delayFrames} compensation_updates=${compensations.size}" +
                " warmup_underruns=$warmupUnderruns steady_underruns=$steadyUnderruns" +
                " after_input_end_underruns=${output.underrunCount}")
            assertTrue(failures.toString(), failures.isEmpty())
            assertEquals(expected, player.sourceSubmittedFrames)
            assertEquals(expectedUs, player.sourcePositionUs())
            assertTrue("FFmpeg compensation was not exercised", compensations.size >= 2)
            assertTrue(compensations.all { it.samples == 0 || kotlin.math.abs(it.samples) <= it.distance / 50 })
            if (packets > 150) assertEquals("Steady output underruns", warmupUnderruns, steadyUnderruns)
            checkBudget(output.timing())
        } finally { player.close() }
    }

    private fun checkBudget(timing: AudioOutputTiming) {
        assertTrue(timing.logMessage(), timing.submittedFrames - timing.consumedFrames in 0..timing.bufferFrames.toLong())
        assertTrue(timing.logMessage(), timing.bufferFrames * 1000L <= timing.sampleRate * 130L)
        assertTrue(timing.logMessage(), timing.presentedFrames in 0..timing.consumedFrames)
    }
}

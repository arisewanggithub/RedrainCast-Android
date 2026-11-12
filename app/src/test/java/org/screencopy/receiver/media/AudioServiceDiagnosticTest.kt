package org.screencopy.receiver.media

import org.junit.Assert.*
import org.junit.Test

class AudioServiceDiagnosticTest {
    @Test fun aggregatesNativeEventsWithoutDependingOnSilenceAndPreservesRateLimitedCounts() {
        val monitor = AudioServiceDiagnostic(48000).apply { start(0, 7) }
        monitor.observe(9, 0, 0, 0, false)
        assertNull(monitor.report(1_000_000_000, 0))
        monitor.observe(12, 0, 0, 2, false)
        val first = checkNotNull(monitor.report(5_000_000_000, 0))
        fields(first, "window_us=5000000", "native_underruns=5", "native_underruns_total=5",
            "inserted_silence_frames=0", "empty_observations=2", "empty_with_pending_input=1")
        val second = checkNotNull(monitor.report(10_000_000_000, 0))
        fields(second, "native_underruns=0", "native_underruns_total=5", "empty_observations=0")
    }

    @Test fun timingKeepsCpuPairedWithLongestOperationAndRetainsPreRefillEvidence() {
        val monitor = AudioServiceDiagnostic(48000).apply { start(0, 0) }
        monitor.duration("step", 40_000_000, 2_000_000)
        monitor.duration("step", 2_000_000, 1_000_000)
        monitor.duration("input", 20_000_000, 100_000)
        monitor.duration("decode", 12_000_000, 10_000_000)
        monitor.beforeRefill(1_000_000, 15000, 960, 100)
        monitor.beforeRefill(41_000_000, 0, 480, 200)
        monitor.beforeRefill(42_000_000, 30000, 0, 300)
        val report = checkNotNull(monitor.report(5_000_000_000, 0))
        fields(report, "steps=2", "slow_steps_ge_5ms=1", "max_step_wall_us=40000",
            "max_step_cpu_us=2000", "max_step_noncpu_us=38000", "max_input_wall_us=20000",
            "max_decode_cpu_us=10000", "max_refill_gap_us=40000", "gap_previous_pre_padding_us=15000",
            "gap_pcm_frames=480", "gap_last_arrival_age_us=200", "min_padding_us=0",
            "min_padding_pcm_frames=480")
    }

    @Test fun countsAcceptedSilenceFramesSeparatelyFromEpisodesDiscardsAndZeroWrites() {
        val monitor = AudioServiceDiagnostic(48000).apply { start(0, 0) }
        monitor.wrote(96, false)
        monitor.wrote(144, false)
        monitor.wrote(0, true)
        monitor.wrote(240, true)
        monitor.wrote(240, false)
        val first = checkNotNull(monitor.report(5_000_000_000, 480))
        fields(first, "inserted_silence_frames=480", "silence_episodes=2",
            "discarded_pcm_frames=480", "zero_writes=1", "sample_rate=48000")
        monitor.wrote(240, false)
        val second = checkNotNull(monitor.report(10_000_000_000, 720))
        fields(second, "inserted_silence_frames=240", "silence_episodes=0", "discarded_pcm_frames=240")
    }

    @Test fun resetsDoNotSubtractLifetimeEventsAndShortFinalWindowIsEmitted() {
        val monitor = AudioServiceDiagnostic(48000).apply { start(0, 5) }
        monitor.observe(8, 1, 0, 0, false)
        monitor.observe(0, 1, 0, 0, false)
        monitor.observe(2, 1, 0, 0, false)
        val report = checkNotNull(monitor.report(1_000_000_000, 0, final = true))
        fields(report, "native_underruns=5", "native_underruns_total=5", "counter_resets=1",
            "window_us=1000000", "final=true")
    }

    private fun fields(message: String, vararg fields: String) {
        val tokens = message.split(' ').toSet()
        fields.forEach { assertTrue("$it missing from $message", it in tokens) }
    }
}

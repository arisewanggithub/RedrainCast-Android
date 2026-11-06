package org.screencopy.receiver.media

import org.junit.Assert.*
import org.junit.Test

class AudioUnderrunMonitorTest {
    private val buffered = AudioOutputTiming(48000, 6240, 6240, 1440, 1000, 0, AudioClockSource.TIMESTAMP)

    @Test fun lifetimeCountersAreInformationalRateLimitedAndNotResetAsAnErrorBudget() {
        val monitor = AudioUnderrunMonitor().apply { start(0) }
        val first = checkNotNull(monitor.observe(0, 9, buffered, 0, false, true))
        assertEquals(9L, first.delta)
        assertEquals(9L, first.total)
        assertEquals(AudioBufferState.BUFFERED, first.state)
        assertNull(monitor.observe(500000000, 12, buffered, 0, false, true))
        val next = checkNotNull(monitor.observe(1000000000, 12, buffered, 0, false, true))
        assertEquals(0L, next.delta)
        assertEquals(3L, next.sinceEvent)
        assertEquals(12L, next.total)
        val reset = checkNotNull(monitor.observe(2000000000, 0, buffered, 0, false, true))
        assertTrue(reset.counterReset)
        assertEquals(12L, reset.total)
    }

    @Test fun sampledEmptyDurationRecoveryAndPendingPacketsAreExplicitNotInferredSourceGaps() {
        val monitor = AudioUnderrunMonitor().apply { start(0) }
        val empty = buffered.copy(consumedFrames = 6240, presentedFrames = 6000)
        val first = checkNotNull(monitor.observe(0, 1, empty, 1, true, true))
        assertEquals(AudioBufferState.DECODING, first.state)
        val waiting = checkNotNull(monitor.observe(1000000000, 1, empty, 0, false, true))
        assertEquals(1000000L, waiting.emptyUs)
        assertEquals(AudioBufferState.EMPTY, waiting.state)
        val recovered = checkNotNull(monitor.observe(2000000000, 1, buffered, 0, false, true))
        assertEquals(2000000L, recovered.lastEmptyUs)
        assertEquals(1L, recovered.recoveries)
        assertEquals(AudioBufferState.BUFFERED, recovered.state)
        listOf("underrun_current=1", "client_empty_us=0", "last_client_empty_us=2000000", "queued_packets=0",
            "playback_state=PLAYING", "submitted_frames=6240", "presented_frames=1000")
            .forEach { assertTrue(recovered.logMessage().contains(it)) }
    }
}

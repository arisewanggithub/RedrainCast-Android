package org.screencopy.receiver.ui

import org.junit.Assert.*
import org.junit.Test

class FailureDiagnosticTest {
    @Test fun localAudioReasonsDistinguishSourceClockOutputRouteAndUnderrun() {
        val cases = listOf(
            "Playback failed: Audio source timestamp offset is 100000 us; exceeds the 50 ms synchronization bound" to FailureReason.SOURCE_TIMING,
            "Playback failed: Audio clock lead is -60000 us; exceeds the playback latency bound" to FailureReason.AUDIO_CLOCK,
            "Repeated audio underruns; check network" to FailureReason.AUDIO_UNDERRUN,
            "Audio output device changed; reconnect" to FailureReason.AUDIO_ROUTE,
            "AudioTrack write failed (-6)" to FailureReason.AUDIO_WRITE,
            "Audio output stalled for 250 ms" to FailureReason.AUDIO_STALL
        )
        for ((message, expected) in cases) {
            val diagnostic = FailureDiagnostic.classify("AUDIO_FAILED", FailureOrigin.AUDIO, "Playing", message)
            assertEquals(expected, diagnostic.reason)
            assertTrue(diagnostic.logMessage().contains("origin=AUDIO"))
            assertFalse(diagnostic.logMessage().contains(message))
        }
        assertEquals(100000L, FailureDiagnostic.classify("AUDIO_FAILED", FailureOrigin.AUDIO, "Playing", cases[0].first).offsetUs)
        assertEquals(-60000L, FailureDiagnostic.classify("AUDIO_FAILED", FailureOrigin.AUDIO, "Playing", cases[1].first).offsetUs)
    }

    @Test fun peerMessageAndNamesNeverEnterDiagnosticLogs() {
        val privateText = "some private application content with arbitrary fields"
        val diagnostic = FailureDiagnostic.classify("CAPTURE_FAILED", FailureOrigin.PEER, "Playing", privateText)
        assertEquals(FailureReason.PEER_REPORTED, diagnostic.reason)
        assertNull(diagnostic.offsetUs)
        assertFalse(diagnostic.logMessage().contains(privateText))
        assertEquals("code=CAPTURE_FAILED origin=PEER reason=PEER_REPORTED phase=Playing", diagnostic.logMessage())
    }

    @Test fun localStageAndExceptionIdentifyTheFaultWithoutLoggingArbitraryMessageText() {
        val diagnostic = FailureDiagnostic.classify("AUDIO_FAILED", FailureOrigin.AUDIO, "Playing",
            "Audio stage=RATE_CORRECTION exception=IllegalArgumentException: private platform message")
        assertEquals("RATE_CORRECTION", diagnostic.stage)
        assertEquals("IllegalArgumentException", diagnostic.exceptionType)
        assertTrue(diagnostic.logMessage().contains("stage=RATE_CORRECTION"))
        assertFalse(diagnostic.logMessage().contains("private"))
        val peer = FailureDiagnostic.classify("AUDIO_FAILED", FailureOrigin.PEER, "Playing",
            "Audio stage=OUTPUT_WRITE exception=Injected: private peer text")
        assertNull(peer.stage)
        assertNull(peer.exceptionType)
    }

    @Test fun localQueueMetadataIdentifiesTrackAndLimitWithoutAbsolutePtsOrPeerText() {
        val pressure = org.screencopy.receiver.media.QueuePressure(2, org.screencopy.receiver.media.QueueLimit.PTS,
            13, 49920, 14, 53760, 260000, 48, 512 * 1024, 250000, 250)
        val local = FailureDiagnostic.classify("CONGESTION", FailureOrigin.SESSION, "Playing", "unused", pressure)
        assertEquals(FailureReason.QUEUE_PTS, local.reason)
        val log = local.logMessage()
        listOf("origin=SESSION", "track=2", "queue_limit=PTS", "queued_packets=13", "queued_bytes=49920",
            "relative_pts_span_us=260000", "max_span_us=250000", "wait_ms=250").forEach { assertTrue(log, log.contains(it)) }
        val peer = FailureDiagnostic.classify("CONGESTION", FailureOrigin.PEER, "Playing", "peer queue text", pressure)
        assertEquals(FailureReason.PEER_REPORTED, peer.reason)
        assertNull(peer.pressure)
        assertFalse(peer.logMessage().contains("track="))
        val state = ReceiverScreen().reduce(ReceiverScreenEvent.Listener(listOf("192.168.1.20")))
            .reduce(ReceiverScreenEvent.Connecting)
            .reduce(ReceiverScreenEvent.Problem("CONGESTION", local.reason, local.pressure))
            .reduce(ReceiverScreenEvent.CleanupFinished)
        assertEquals(pressure, state.pressure)
        assertEquals(FailureReason.QUEUE_PTS, state.failureReason)
        assertNull(state.reduce(ReceiverScreenEvent.Connecting).pressure)
    }

    @Test fun ratePreparationDiagnosticsKeepControlledFramesRateSpeedAndDistinguishRuntimeStage() {
        val output = org.screencopy.receiver.media.OutputPreparationDiagnostic(
            org.screencopy.receiver.media.OutputPreparationReason.RATE_UNSUPPORTED,
            48000, 2, 5760, 6240, 6240, 6240, 6240, 1.005f, 48000, 2)
        val initial = FailureDiagnostic.classify("UNSUPPORTED_CONFIG", FailureOrigin.AUDIO, "Initializing tracks", "private vendor text",
            outputPreparation = output)
        assertEquals(FailureReason.RATE_UNSUPPORTED, initial.reason)
        assertEquals("OUTPUT_PREPARE", initial.stage)
        listOf("buffer_frames=6240", "sample_rate=48000", "intended_speed=1.005").forEach { assertTrue(initial.logMessage().contains(it)) }
        assertFalse(initial.logMessage().contains("private"))
        val exception = org.screencopy.receiver.media.AudioOutputPreparationException(output)
        val runtime = FailureDiagnostic.classify("UNSUPPORTED_CONFIG", FailureOrigin.AUDIO, "Playing",
            "Audio stage=RATE_CORRECTION exception=AudioOutputPreparationException: ${exception.message}")
        assertEquals("RATE_CORRECTION", runtime.stage)
        assertEquals(output, runtime.outputPreparation)
        val peer = FailureDiagnostic.classify("UNSUPPORTED_CONFIG", FailureOrigin.PEER, "Playing", exception.message.orEmpty(),
            outputPreparation = output)
        assertNull(peer.outputPreparation)
        assertFalse(peer.logMessage().contains("buffer_frames"))
    }

    @Test fun firstAudioFailureSurvivesDiscoveryWarningsCleanupAndReadyUntilRetry() {
        var state = ReceiverScreen().reduce(ReceiverScreenEvent.Listener(listOf("192.168.1.20")))
            .reduce(ReceiverScreenEvent.Connecting)
            .reduce(ReceiverScreenEvent.Problem("AUDIO_FAILED", FailureReason.SOURCE_TIMING))
        state = state.reduce(ReceiverScreenEvent.Notice("DISCOVERY"))
            .reduce(ReceiverScreenEvent.Problem("CLEANUP"))
            .reduce(ReceiverScreenEvent.CleanupFinished)
        assertEquals(ScreenPhase.READY, state.phase)
        assertEquals("AUDIO_FAILED", state.problem)
        assertEquals(FailureReason.SOURCE_TIMING, state.failureReason)
        val retry = state.reduce(ReceiverScreenEvent.Connecting)
        assertNull(retry.problem)
        assertNull(retry.failureReason)
    }

    @Test fun clockFailuresRetainOnlyControlledRelativeCountersAndMeasuredBuffer() {
        val timing = org.screencopy.receiver.media.AudioOutputTiming(48000, 6240, 48128, 41888, 35696, 0,
            org.screencopy.receiver.media.AudioClockSource.TIMESTAMP)
        val clock = org.screencopy.receiver.media.AudioClockDiagnostic(org.screencopy.receiver.media.AudioClockFault.CLIENT_BUFFER, timing)
        val exception = org.screencopy.receiver.media.AudioClockException(clock)
        val detail = "Audio stage=RATE_CORRECTION exception=AudioClockException: ${exception.message}"
        val diagnostic = FailureDiagnostic.classify("AUDIO_FAILED", FailureOrigin.AUDIO, "Playing", detail)
        assertEquals(FailureReason.AUDIO_CLOCK, diagnostic.reason)
        assertEquals(clock, diagnostic.audioClock)
        listOf("clock_reason=CLIENT_BUFFER", "buffered_us=130000", "submitted_frames=48128", "consumed_frames=41888",
            "presented_frames=35696", "transit_us=129000", "timestamp_age_us=0", "clock_source=TIMESTAMP")
            .forEach { assertTrue(diagnostic.logMessage(), diagnostic.logMessage().contains(it)) }
        val peer = FailureDiagnostic.classify("AUDIO_FAILED", FailureOrigin.PEER, "Playing", detail)
        assertNull(peer.audioClock)
        assertFalse(peer.logMessage().contains("submitted_frames"))
        assertEquals(258666L, FailureDiagnostic.classify("AUDIO_FAILED", FailureOrigin.AUDIO, "Playing",
            "Audio stage=RATE_CORRECTION exception=IllegalArgumentException: Audio buffered PCM is 258666 us; exceeds bound").offsetUs)
    }

    @Test fun videoStageCodecCodeAndRelativeMeasurementsSurviveClassificationWithoutPrivateText() {
        val video = org.screencopy.receiver.media.VideoDiagnostic(
            org.screencopy.receiver.media.VideoStage.OUTPUT_DEQUEUE, org.screencopy.receiver.media.VideoFault.CODEC_RESOURCE,
            "CodecException", 2, 360, 640, 90, 50, -320000, 12000, 16384, 32768, true, 1, 1,
            100, 99, 95, 3, 1, 1100, false, true)
        val error = org.screencopy.receiver.media.VideoPlaybackException(video, IllegalStateException("private vendor detail"))
        val diagnostic = FailureDiagnostic.classify("DECODER_FAILED", FailureOrigin.VIDEO, "Playing",
            "Video stage=OUTPUT_DEQUEUE exception=CodecException: ${error.message}")
        assertEquals(FailureReason.VIDEO_RESOURCE, diagnostic.reason)
        assertEquals(video, diagnostic.video)
        assertEquals("CodecException", diagnostic.exceptionType)
        assertEquals("OUTPUT_DEQUEUE", diagnostic.stage)
        listOf("codec_error=1100", "video_generation=2", "reconfigured=true", "frame_delay_us=-320000", "held_us=12000",
            "input_capacity=32768", "decoded_skipped=3").forEach { assertTrue(diagnostic.logMessage().contains(it)) }
        assertFalse(diagnostic.logMessage().contains("private"))
        val peer = FailureDiagnostic.classify("DECODER_FAILED", FailureOrigin.PEER, "Playing", error.message.orEmpty(),
            videoDiagnostic = video)
        assertEquals(FailureReason.PEER_REPORTED, peer.reason)
        assertNull(peer.video)
        assertFalse(peer.logMessage().contains("video_generation"))
    }

    @Test fun runtimeVideoFailureIsNotClassifiedAsUnsupportedAndFirstSpecificCauseSurvivesCleanup() {
        val old = FailureDiagnostic.classify("DECODER_FAILED", FailureOrigin.VIDEO, "Playing", "old generic video text")
        assertEquals(FailureReason.VIDEO_DECODER, old.reason)
        var state = ReceiverScreen().reduce(ReceiverScreenEvent.Listener(listOf("192.168.1.20")))
            .reduce(ReceiverScreenEvent.Connecting)
            .reduce(ReceiverScreenEvent.Problem("DECODER_FAILED", FailureReason.VIDEO_CLOCK_WAIT))
        state = state.reduce(ReceiverScreenEvent.Problem("CLEANUP", FailureReason.CLEANUP))
            .reduce(ReceiverScreenEvent.CleanupFinished)
        assertEquals(FailureReason.VIDEO_CLOCK_WAIT, state.failureReason)
        assertEquals("DECODER_FAILED", state.problem)
    }
}

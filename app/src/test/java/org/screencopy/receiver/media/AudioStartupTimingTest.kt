package org.screencopy.receiver.media

import dev.redrain.cast.diagnostics.*
import dev.redrain.cast.diagnostics.DiagnosticAudioTiming.Companion.OUTPUT_REQUEST
import dev.redrain.cast.diagnostics.DiagnosticAudioTiming.Companion.OUTPUT_SILENCE_RESULT
import dev.redrain.cast.diagnostics.DiagnosticAudioTiming.Companion.PLAYBACK_START
import dev.redrain.cast.diagnostics.DiagnosticAudioTiming.Companion.PREPARATION
import dev.redrain.cast.diagnostics.DiagnosticAudioTiming.Companion.REFILL
import dev.redrain.cast.diagnostics.DiagnosticAudioTiming.Companion.RESAMPLING
import dev.redrain.cast.diagnostics.DiagnosticAudioTiming.Companion.START_DECISION
import dev.redrain.cast.diagnostics.DiagnosticAudioTiming.Companion.START_GATE
import dev.redrain.cast.diagnostics.DiagnosticAudioTiming.Companion.STARTUP_SUBMISSION
import dev.redrain.cast.diagnostics.DiagnosticAudioTiming.Companion.TIMING
import dev.redrain.cast.diagnostics.DiagnosticAudioTiming.Companion.WORK_BEGIN
import dev.redrain.cast.diagnostics.DiagnosticAudioTiming.Companion.WORK_END
import dev.redrain.cast.diagnostics.DiagnosticAudioTiming.Companion.WORK_FAILED
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.lang.reflect.InvocationTargetException

class AudioStartupTimingTest {
    @Test fun startupSeparatesPreparationGateCpuResamplingAndFirstWrite() {
        Fixture().use { f ->
            f.clock.anchorVideo(1_000_000)
            f.clock.mappingObserved = { values, _, _ ->
                if (values[0] == CausalEvidence.Mapping.PREPARE.ordinal.toLong()) f.advance(6, 1)
            }
            f.write(7)
            f.pump()
            val events = f.events()
            fun boundary(stage: Int, type: Int) = events.single {
                it.type == type && it.value1 == stage.toLong()
            }
            fun duration(stage: Int) =
                boundary(stage, WORK_END).monoNs - boundary(stage, WORK_BEGIN).monoNs
            fun cpu(stage: Int) =
                boundary(stage, WORK_END).value2 - boundary(stage, WORK_BEGIN).value2
            assertEquals(6_000_000L, duration(PREPARATION))
            assertEquals(44_000_000L, duration(START_GATE))
            assertEquals(0L, cpu(START_GATE))
            assertEquals(40_000_000L, duration(RESAMPLING))
            assertEquals(4_000_000L, cpu(RESAMPLING))
            assertEquals(3_000_000L, duration(PLAYBACK_START))
            assertEquals(1_000_000L, cpu(PLAYBACK_START))
            assertTrue(boundary(PREPARATION, WORK_END).sequence < boundary(START_GATE, WORK_BEGIN).sequence)
            assertTrue(boundary(START_GATE, WORK_END).sequence < boundary(STARTUP_SUBMISSION, WORK_BEGIN).sequence)
            assertTrue(boundary(RESAMPLING, WORK_END).sequence < boundary(PLAYBACK_START, WORK_BEGIN).sequence)
            assertTrue(boundary(PLAYBACK_START, WORK_END).sequence < boundary(TIMING, WORK_BEGIN).sequence)
            val firstWrite = events.first { it.type == OUTPUT_REQUEST }
            assertTrue(boundary(TIMING, WORK_END).sequence < firstWrite.sequence)
            val decisions = events.filter { it.type == WORK_BEGIN && it.value1 == START_DECISION.toLong() }
            assertTrue(decisions.isNotEmpty())
            assertTrue(decisions.all { it.sequence > boundary(START_GATE, WORK_BEGIN).sequence &&
                    it.sequence < boundary(START_GATE, WORK_END).sequence && it.packetId == 7L })
            assertEquals(7L, boundary(PREPARATION, WORK_BEGIN).packetId)
            assertEquals(7L, boundary(START_GATE, WORK_END).packetId)
            assertTrue(events.filter { it.type in OUTPUT_REQUEST..OUTPUT_SILENCE_RESULT ||
                it.type in WORK_BEGIN..WORK_FAILED && it.value1 in listOf(
                    RESAMPLING.toLong(), PLAYBACK_START.toLong(), REFILL.toLong(), TIMING.toLong()) }
                .all { it.packetId == 0L })
            assertTrue(f.written > 0)
        }
    }

    @Test fun audioStartsWithoutVideoAndRemainsStartedAfterThreeSeconds() {
        Fixture(advanceOutput = true).use { f ->
            f.write(7)
            assertEquals(1, f.played)
            assertEquals(7L, f.events().single { it.type == WORK_BEGIN &&
                it.value1 == START_GATE.toLong() }.packetId)
            f.pump()
            val before = f.clock.currentUs()
            f.advance(4_000, 0)
            f.clock.checkStartup()
            f.pump()
            assertTrue(f.written > 0)
            assertTrue(checkNotNull(f.clock.currentUs()) > checkNotNull(before))
        }
    }

    @Test fun videoDuringAudioStartDoesNotResetThePreparedClock() {
        Fixture().use { f ->
            f.clock.mappingObserved = { values, _, _ ->
                if (values[0] == CausalEvidence.Mapping.PREPARE.ordinal.toLong()) {
                    val revision = f.clock.mappingRevision()
                    f.clock.anchorVideo(9_000_000)
                    assertEquals(revision, f.clock.mappingRevision())
                }
            }
            f.write(7)
            assertEquals(1, f.played)
            assertEquals(1_000_000L, f.clock.currentUs())
        }
    }

    @Test fun lateVideoUsesAudioClockForStaleAndFuturePicturesAndReplacement() {
        for (pts in listOf(500_000L, 5_000_000L)) Fixture().use { f ->
            f.write(7)
            val before = f.clock.currentUs()
            val revision = f.clock.mappingRevision()
            val decoder = VideoDecoderForStartup()
            val step = VideoPlayer::class.java.getDeclaredMethod("step").apply { isAccessible = true }
            fun player(generation: Long) = VideoPlayer(
                VideoFormat("video/avc", 640, 360, listOf(byteArrayOf(1)), 50),
                f.clock, { code, detail -> fail("$code: $detail") }, decoder, generation)
            player(1).use { video ->
                video.offer(AccessUnit(pts, byteArrayOf(1), true))
                step.invoke(video)
                step.invoke(video)
            }
            assertEquals(before, f.clock.currentUs())
            assertEquals(revision, f.clock.mappingRevision())
            assertEquals(if (pts < 1_000_000) f.now() else f.now() + 4_000_000_000,
                decoder.renderTimes.single())
            val replacement = VideoDecoderForStartup()
            VideoPlayer(VideoFormat("video/avc", 640, 360, listOf(byteArrayOf(1)), 50),
                f.clock, { code, detail -> fail("$code: $detail") }, replacement, 2).use { video ->
                video.offer(AccessUnit(pts + 20_000, byteArrayOf(1), true))
                step.invoke(video)
                step.invoke(video)
            }
            assertEquals(before, f.clock.currentUs())
            assertEquals(revision, f.clock.mappingRevision())
            assertEquals(1, f.played)
        }
    }

    @Test fun negotiatedRecoveryDiscardsStaleDecodedVideoWithoutResettingAudio() {
        Fixture().use { f ->
            f.write(7)
            val revision = f.clock.mappingRevision()
            val decoder = VideoDecoderForStartup()
            val step = VideoPlayer::class.java.getDeclaredMethod("step").apply { isAccessible = true }
            VideoPlayer(VideoFormat("video/avc", 640, 360, listOf(byteArrayOf(1)), 50),
                f.clock, { code, detail -> fail("$code: $detail") }, decoder).use { video ->
                video.dropStaleOutput = true
                video.offer(AccessUnit(500_000, byteArrayOf(1), true))
                step.invoke(video)
                step.invoke(video)
                assertTrue(decoder.renderTimes.isEmpty())
                assertEquals(1L, video.videoStatistics().skipped)
            }
            assertEquals(1, f.played)
            assertEquals(1_000_000L, f.clock.currentUs())
            assertEquals(revision, f.clock.mappingRevision())
        }
    }

    private class VideoDecoderForStartup : VideoDecoder {
        override val description = "Startup test decoder"
        private var output: VideoOutput? = null
        val renderTimes = mutableListOf<Long>()
        override fun dequeueInput() = 0
        override fun input(index: Int) = ByteBuffer.allocate(1)
        override fun queueInput(index: Int, size: Int, ptsUs: Long, keyFrame: Boolean) {
            output = VideoOutput(0, ptsUs, 1, 0)
        }
        override fun dequeueOutput() = output.also { output = null }
        override fun releaseOutput(index: Int, renderAtNs: Long?) { renderAtNs?.let(renderTimes::add) }
        override fun close() {}
    }

    @Test fun resamplerFailureClosesNestedWorkAsFailedWithoutPlayingOrWriting() {
        Fixture(failResample = true).use { f ->
            f.clock.anchorVideo(1_000_000)
            try {
                f.write(7)
                fail("resampler should fail")
            } catch (error: InvocationTargetException) {
                assertTrue(error.cause is IllegalStateException)
            }
            val events = f.events()
            assertEquals(listOf(RESAMPLING.toLong(), STARTUP_SUBMISSION.toLong()),
                events.filter { it.type == WORK_FAILED }.map { it.value1 })
            assertFalse(events.any { it.type == WORK_BEGIN && it.value1 == PLAYBACK_START.toLong() })
            assertEquals(0, f.played)
            assertEquals(0, f.written)
        }
    }

    @Test fun cancelledGateClosesNormallyWithoutClaimingPlaybackStarted() {
        Fixture().use { f ->
            f.clock.anchorVideo(1_000_000)
            f.clock.mappingObserved = { values, _, _ ->
                if (values[0] == CausalEvidence.Mapping.PREPARE.ordinal.toLong()) f.player.halt()
            }
            f.write(7)
            val events = f.events()
            assertTrue(events.any { it.type == WORK_END && it.value1 == START_GATE.toLong() })
            assertFalse(events.any { it.type == WORK_FAILED ||
                it.type == WORK_BEGIN && it.value1 == STARTUP_SUBMISSION.toLong() })
            assertEquals(0, f.played)
        }
    }

    @Test fun disabledTimingDoesNotReadWorkerCpuClock() {
        Fixture(enabled = false).use { f ->
            f.clock.anchorVideo(1_000_000)
            f.write(0)
            f.pump()
            assertEquals(1, f.played)
            assertTrue(f.written > 0)
        }
    }

    @Test fun legacyAudioTimingDoesNotSampleCpuOrEmitWorkEvenDuringStartup() {
        Fixture(work = false).use { f ->
            f.clock.anchorVideo(1_000_000)
            f.write(7)
            f.pump()
            val events = f.events()
            assertTrue(events.any { it.type == OUTPUT_REQUEST })
            assertFalse(events.any { it.type in WORK_BEGIN..WORK_FAILED })
            assertEquals(1, f.played)
            assertTrue(f.written > 0)
        }
    }

    @Test fun closingCallerDoesNotEmitWorkerCpuBoundaries() {
        Fixture(serviceCallback = {}).use { f ->
            f.clock.anchorVideo(1_000_000)
            f.write(7)
            f.events()
            f.closePlayer()
            assertTrue(f.events().isEmpty())
        }
    }

    @Test fun detailRecordsPhysicalWriteFramesCapacityAndSampledBoundaryCost() {
        Fixture(detail = true).use { f ->
            f.write(7)
            f.pump()
            val events = f.events().toMutableList()
            repeat(130) {
                f.pump()
                if (it % 16 == 15) events.addAll(f.events())
            }
            events.addAll(f.events())
            assertTrue(events.any { it.type == DiagnosticAudioTiming.OUTPUT_FRAMES &&
                it.value1 > 0 && it.value1 == it.value2 })
            assertTrue(events.any { it.type == DiagnosticAudioTiming.OUTPUT_DETAIL && it.value1 == 5760L })
            assertTrue(events.any { it.type == DiagnosticAudioTiming.OUTPUT_NATIVE_CAPACITY && it.value1 == -1L })
            assertTrue(events.any { it.type == WORK_BEGIN && it.value1 == DiagnosticAudioTiming.WRITE.toLong() })
            assertTrue(events.any { it.type == DiagnosticAudioTiming.TRACE_OVERHEAD &&
                it.value1 >= 0 && it.value2 >= 0 })
        }
    }

    private class Fixture(
        enabled: Boolean = true,
        failResample: Boolean = false,
        serviceCallback: ((String) -> Unit)? = null,
        work: Boolean = true,
        advanceOutput: Boolean = false,
        detail: Boolean = false
    ) : AutoCloseable {
        private val recorder = RemoteDiagnostics.recorder
        private var wall = 1_000_000_000L
        private var cpu = 1_000_000L
        private val timing: DiagnosticAudioTiming?
        private var closed = false
        private var playbackAt = 0L
        var played = 0
        var written = 0
        val clock = PlaybackClock({ wall }) { ms -> advance(ms, 0) }.apply { audioExpected = true }
        val player: AudioPlayer

        init {
            recorder.grantConsent()
            recorder.sessionStarted("receiver", 9890)
            if (enabled) recorder.start("receiver", 9890,
                if (detail) setOf("audio.timing", "audio.timing.work", "audio.timing.detail")
                else if (work) setOf("audio.timing", "audio.timing.work") else setOf("audio.timing"), 10000, 1000)
            timing = recorder.audioTiming(9890)
            player = testAudioPlayer(AudioFormat("pcm", 48000, 2, byteArrayOf()), clock,
                { code, detail -> fail("$code: $detail") }, {
                    object : AudioOutput {
                        override val underrunCount = 0
                        override fun play() { advance(3, 1); playbackAt = wall; played++ }
                        override fun pause() {}
                        override fun write(samples: ShortArray, offset: Int, count: Int): Int {
                            written += count
                            return count
                        }
                        override fun timing(): AudioOutputTiming {
                            advance(2, 1)
                            val frames = if (advanceOutput && played > 0)
                                minOf(written.toLong() / 2, (wall - playbackAt) * 48000 / 1_000_000_000) else 0
                            return AudioOutputTiming(48000, 5760, written.toLong() / 2,
                                frames, frames, null, AudioClockSource.HEAD_UNAVAILABLE)
                        }
                        override fun setSpeed(speed: Float) {}
                        override fun close() {}
                    }
                }, resamplerFactory = { _, channels ->
                    object : PcmResampler by IdentityPcmResampler(channels) {
                        override fun convert(samples: ShortArray, offsetFrames: Int, frames: Int): ShortArray {
                            advance(40, 4)
                            check(!failResample) { "injected resampler failure" }
                            return samples.copyOfRange(offsetFrames * channels, (offsetFrames + frames) * channels)
                        }
                    }
                }, onServiceDiagnostic = serviceCallback, threadCpuNs = {
                    check(enabled && work) { "Unselected work timing must not sample CPU" }
                    cpu
                }).apply { diagnosticSession = 9890; diagnosticGeneration = 2 }
        }

        fun advance(wallMs: Long, cpuMs: Long) { wall += wallMs * 1_000_000; cpu += cpuMs * 1_000_000 }
        fun now() = wall
        fun write(packet: Long) {
            AudioPlayer::class.java.getDeclaredMethod("write", ShortArray::class.java,
                Long::class.javaPrimitiveType, DiagnosticAudioTiming::class.java, Long::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(player, ShortArray(1920), 1_000_000L, timing, packet)
        }
        fun pump() {
            AudioPlayer::class.java.getDeclaredMethod("pumpOutput", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(player, false)
        }
        fun events() = checkNotNull(timing).drain(DiagnosticAudioTiming.CAPACITY).sortedBy { it.sequence }
        fun closePlayer() {
            if (!closed) {
                closed = true
                player.close()
            }
        }
        override fun close() {
            try { closePlayer() } finally {
                recorder.revoke()
                recorder.sessionEnded("receiver", 9890)
            }
        }
    }
}

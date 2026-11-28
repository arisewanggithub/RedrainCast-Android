package org.screencopy.receiver.media

import android.media.MediaCodec
import dev.redrain.cast.diagnostics.CausalEvidence
import dev.redrain.cast.diagnostics.ReceiverTelemetrySession
import dev.redrain.cast.diagnostics.SessionTelemetryProtocol
import dev.redrain.cast.diagnostics.TelemetryRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class AudioStalePipelineTest {
    @Test fun pcmBacklogReachesDecodedTrimWithoutRecovery() = backlog("pcm")
    @Test fun opusBacklogRetainsEveryDecoderDependency() = backlog("opus")
    @Test fun aacBacklogRetainsEveryDecoderDependency() = backlog("aac")
    @Test fun normalOperationAfterRecoveryUsesPlayedCutoff() = backlog("opus", recovered = true)
    @Test fun staleOpusExceedingTheExistingDecodeBoundStillFails() = backlog("opus", oversizedStale = true)

    @Test fun reorderedOpusBeforeTheProvenIslandStillConsumesAdmissionDuration() {
        exactScenario(listOf(960, 960)) { player, clock, service, progress, _ ->
            assertNull(player.tryOffer(AccessUnit(1_100_000, byteArrayOf(0))))
            service()
            progress(960, 960)
            service()
            assertEquals(1_120_000L, clock.cachedAudioDiscardCutoffUs())
            // Leave the real worker paused after proving only [1.100, 1.120].
            assertNull(player.tryOffer(AccessUnit(900_000, byteArrayOf(1))))
            val pressure = checkNotNull(player.tryOffer(AccessUnit(1_900_001, byteArrayOf(2))))
            assertEquals(QueueLimit.PTS, pressure.limit)
            assertEquals(1_000_001L, pressure.spanUs)
            assertEquals(DEFAULT_AUDIO_QUEUE_SPAN_US, pressure.maxSpanUs)
            assertEquals(1, player.queuedPackets())
            service()
            assertEquals(0L, player.staleDiscardedSourceFrames)
            assertNull(clock.cachedAudioDiscardCutoffUs())
        }
    }

    @Test fun queuedReorderedGapCannotExemptALaterStaleDependency() = reorderedGap(false)

    @Test fun legacyOfferReportsCongestionInsteadOfAcceptingAStaleDependency() = reorderedGap(true)

    private fun reorderedGap(legacy: Boolean) {
        exactScenario(listOf(2880, 2880, 960, 960)) { player, clock, service, progress, converted ->
            assertNull(player.tryOffer(AccessUnit(1_000_000, byteArrayOf(0))))
            service()
            progress(2880, 2880)
            assertNull(player.tryOffer(AccessUnit(1_060_000, byteArrayOf(1))))
            service()
            progress(5760, 5760)
            service()
            assertEquals(1_120_000L, clock.cachedAudioDiscardCutoffUs())
            assertNull(player.tryOffer(AccessUnit(2_000_000, byteArrayOf(2))))
            assertNull(player.tryOffer(AccessUnit(2_010_000, byteArrayOf(3))))
            val stale = AccessUnit(1_000_000, byteArrayOf(4))
            val pressure = if (legacy) assertThrows(QueueCongestionException::class.java) {
                player.offer(stale)
            }.pressure else checkNotNull(player.tryOffer(stale))
            assertEquals(QueueLimit.PTS, pressure.limit)
            assertEquals(1_010_000L, pressure.spanUs)
            assertEquals(DEFAULT_AUDIO_QUEUE_SPAN_US, pressure.maxSpanUs)
            assertEquals(2, player.queuedPackets())
            service() // Real Opus decode invalidates the native played-coverage island.
            assertNull(clock.cachedAudioDiscardCutoffUs())
            assertEquals(1, player.queuedPackets())
            service() // No subsequent admission is needed to keep the queue bounded.
            assertEquals(0, player.queuedPackets())
            assertEquals(0L, player.staleDiscardedSourceFrames)
            assertEquals(listOf(2880, 2880, 960, 960), converted.map { it.second })
        }
    }

    @Test fun contiguousNativeCoverageStillTrimsAnEntireDecodedDependencyWithinRawBudget() {
        exactScenario(listOf(2880, 2880, 5760, 960)) { player, clock, service, progress, converted ->
            assertNull(player.tryOffer(AccessUnit(1_000_000, byteArrayOf(0))))
            service()
            progress(2880, 2880)
            assertNull(player.tryOffer(AccessUnit(1_060_000, byteArrayOf(1))))
            service()
            progress(5760, 5760)
            service()
            assertEquals(1_120_000L, clock.cachedAudioDiscardCutoffUs())
            assertNull(player.tryOffer(AccessUnit(1_000_000, byteArrayOf(2))))
            assertNull(player.tryOffer(AccessUnit(1_120_000, byteArrayOf(3))))
            service()
            assertEquals(5760L, player.staleDiscardedSourceFrames)
            assertEquals(2, converted.size)
            service()
            assertEquals(0 to 960, converted.last())
            assertEquals(0, player.queuedPackets())
        }
    }

    @Test fun admissionRacingGapInvalidationOrRetirementNeverAcceptsTheWideSpan() {
        for (retire in listOf(false, true)) repeat(12) {
            exactScenario(listOf(2880, 2880, 960)) { player, clock, service, progress, _ ->
                assertNull(player.tryOffer(AccessUnit(1_000_000, byteArrayOf(0))))
                service()
                progress(2880, 2880)
                assertNull(player.tryOffer(AccessUnit(1_060_000, byteArrayOf(1))))
                service()
                progress(5760, 5760)
                service()
                assertEquals(1_120_000L, clock.cachedAudioDiscardCutoffUs())
                assertNull(player.tryOffer(AccessUnit(2_000_000, byteArrayOf(2))))
                assertNull(player.tryOffer(AccessUnit(2_010_000, byteArrayOf(3))))
                val start = CountDownLatch(1)
                val done = CountDownLatch(1)
                val error = AtomicReference<Throwable>()
                Thread {
                    try {
                        check(start.await(3, TimeUnit.SECONDS))
                        val pressure = checkNotNull(player.tryOffer(AccessUnit(1_000_000, byteArrayOf(4))))
                        assertEquals(1_010_000L, pressure.spanUs)
                        assertEquals(QueueLimit.PTS, pressure.limit)
                    } catch (stopped: TrackStoppedException) {
                        if (!retire) error.set(stopped)
                    } catch (failure: Throwable) {
                        error.set(failure)
                    } finally { done.countDown() }
                }.start()
                start.countDown()
                if (retire) player.halt() else service()
                assertTrue(done.await(3, TimeUnit.SECONDS))
                error.get()?.let { throw AssertionError("Admission race failed", it) }
                if (!retire) assertNull(clock.cachedAudioDiscardCutoffUs())
                assertEquals(if (retire) 2 else 1, player.queuedPackets())
            }
        }
    }

    @Test fun subMicrosecondGapDoesNotProveTheSampleCrossingIt() {
        exactScenario(listOf(1024, 1024, 1024, 1024, 1024)) { player, _, service, progress, converted ->
            listOf(1_000_000L, 1_021_333L, 1_042_666L, 1_064_000L).forEachIndexed { id, pts ->
                assertNull(player.tryOffer(AccessUnit(pts, byteArrayOf(id.toByte()))))
                service()
                if (id < 3) progress((id + 1L) * 1024, (id + 1L) * 1024)
            }
            progress(4096, 3584)
            service()
            assertNull(player.tryOffer(AccessUnit(1_063_999, byteArrayOf(4))))
            service()
            assertEquals(0L, player.staleDiscardedSourceFrames)
            assertEquals(0 to 1024, converted.last())
        }
    }

    @Test fun realOneMicrosecondGapDisablesTrimming() {
        exactScenario(listOf(960, 960, 960)) { player, clock, service, progress, converted ->
            assertNull(player.tryOffer(AccessUnit(1_000_000, byteArrayOf(0))))
            service()
            progress(960, 960)
            assertNull(player.tryOffer(AccessUnit(1_020_001, byteArrayOf(1))))
            service()
            progress(1920, 1920)
            service()
            assertNull(clock.cachedAudioDiscardCutoffUs())
            assertNull(player.tryOffer(AccessUnit(1_020_000, byteArrayOf(2))))
            service()
            assertEquals(0L, player.staleDiscardedSourceFrames)
            assertEquals(0 to 960, converted.last())
        }
    }

    @Test fun roundedAacEndpointsKeepPartialNativePlaybackUseful() {
        var now = 0L
        var submitted = 0L
        var head = 0L
        var stamp = FrameTimestamp(0, 0)
        val position = AudioPosition(48000, 5760, { now }, { stamp }, { head.toInt() })
        val clock = PlaybackClock({ now }, { now += it * 1_000_000 }).apply { audioExpected = true }
        val codec = mock(MediaCodec::class.java)
        val input = ByteBuffer.allocate(1)
        var packet: Pair<Int, Long>? = null
        val decoded = mutableListOf<Int>()
        val converted = mutableListOf<Pair<Int, Int>>()
        val written = mutableListOf<Short>()
        doReturn(0).`when`(codec).dequeueInputBuffer(anyLong())
        doReturn(input).`when`(codec).getInputBuffer(anyInt())
        doAnswer { call ->
            val id = input.get(0).toInt()
            decoded.add(id)
            packet = id to call.getArgument<Long>(3)
            null
        }.`when`(codec).queueInputBuffer(anyInt(), anyInt(), anyInt(), anyLong(), anyInt())
        doAnswer { call ->
            packet?.let { (id, pts) ->
                call.getArgument<MediaCodec.BufferInfo>(0).set(0, 1024 * 4, pts, 0)
                packet = null
                id
            } ?: MediaCodec.INFO_TRY_AGAIN_LATER
        }.`when`(codec).dequeueOutputBuffer(any(MediaCodec.BufferInfo::class.java), anyLong())
        doAnswer { call -> pcm(ShortArray(1024 * 2) { (call.getArgument<Int>(0) + 1).toShort() }) }
            .`when`(codec).getOutputBuffer(anyInt())
        val output = object : AudioOutput {
            override val underrunCount = 0
            override fun play() {}
            override fun pause() {}
            override fun close() {}
            override fun setSpeed(speed: Float) { fail("Native speed must not change") }
            override fun timing() = position.current()
            override fun write(samples: ShortArray, offset: Int, count: Int): Int {
                written.addAll(samples.slice(offset until offset + count))
                submitted += count / 2
                position.written(count / 2)
                return count
            }
        }
        val player = testAudioPlayer(AudioFormat("aac", 48000, 2, byteArrayOf(1)), clock,
            { code, detail -> fail("$code: $detail") }, { output },
            resamplerFactory = { _, _ -> object : PcmResampler {
                override val delayFrames = 0
                override fun convert(samples: ShortArray, offsetFrames: Int, frames: Int): ShortArray {
                    converted.add(offsetFrames to frames)
                    return samples.copyOfRange(offsetFrames * 2, (offsetFrames + frames) * 2)
                }
                override fun compensate(deltaFrames: Int, distanceFrames: Int) {}
                override fun close() {}
            } }, aacFactory = { codec })
        val step = AudioPlayer::class.java.getDeclaredMethod("serviceStep").apply { isAccessible = true }
        fun service() { step.invoke(player) }
        fun progress(frame: Long) {
            head = frame
            now += 1_000_000
            stamp = FrameTimestamp(frame, now)
        }
        try {
            val pts = listOf(1_000_000L, 1_021_333L, 1_042_666L, 1_064_000L)
            var fourthStart = 0L
            pts.forEachIndexed { id, time ->
                if (id == 3) fourthStart = submitted
                assertNull(player.tryOffer(AccessUnit(time, byteArrayOf(id.toByte()))))
                service()
                if (id < 3) progress(submitted)
            }
            progress(fourthStart + 512)
            service()
            assertNull("A scalar cutoff must not cross the unproved gap", clock.cachedAudioDiscardCutoffUs())
            assertNull(player.tryOffer(AccessUnit(1_064_000, byteArrayOf(4))))
            service()
            assertEquals(listOf(0, 1, 2, 3, 4), decoded)
            assertEquals(512L, player.staleDiscardedSourceFrames)
            assertEquals(512 to 512, converted.last())
            assertEquals(512 * 2, written.count { it == 5.toShort() })
            assertEquals(1024 * 2, written.count { it == 4.toShort() })
            verify(codec, never()).flush()
        } finally { player.close() }
    }

    @Test fun playbackHeadAheadOfDacCannotTrimAnUntouchedReorderedOpusInterval() {
        exactScenario(listOf(960, 4800, 960)) { player, clock, service, progress, converted ->
            assertNull(player.tryOffer(AccessUnit(1_000_000, byteArrayOf(0))))
            service()
            progress(960, null)
            assertNull(player.tryOffer(AccessUnit(1_100_000, byteArrayOf(1))))
            service()
            progress(5280, null) // Consumption says 110 ms; actual DAC is only at 10 ms.
            service()
            assertTrue(checkNotNull(clock.currentUs()) >= 1_110_000)
            assertNull(clock.cachedAudioDiscardCutoffUs())
            // Qualifying a lagging DAC timestamp must not inherit the fallback floor.
            progress(5280, 0)
            service()
            progress(5280, 480)
            service()
            assertNull(clock.cachedAudioDiscardCutoffUs()) // The source gap remains ambiguous.
            assertNull(player.tryOffer(AccessUnit(1_040_000, byteArrayOf(2))))
            service()
            assertEquals(0L, player.staleDiscardedSourceFrames)
            assertEquals(listOf(960, 4800, 960), converted.map { it.second })
        }
    }

    @Test fun negativeOffsetSlewCannotTrimUnplayedSamplesAfterOverlappingOpus() {
        exactScenario(listOf(960, 2880, 960)) { player, clock, service, progress, converted ->
            assertNull(player.tryOffer(AccessUnit(1_000_000, byteArrayOf(0))))
            service()
            progress(960, 960)
            assertNull(player.tryOffer(AccessUnit(1_004_000, byteArrayOf(1))))
            service()
            progress(3840, 3840)
            service()
            assertEquals(1_078_800L, clock.currentUs()) // Unslewed source ends at 1.064.
            assertNull(clock.cachedAudioDiscardCutoffUs())
            assertNull(player.tryOffer(AccessUnit(1_065_000, byteArrayOf(2))))
            service()
            assertEquals(0L, player.staleDiscardedSourceFrames)
            assertEquals(0, converted.last().first)
            assertEquals(960, converted.last().second)
        }
    }

    @Test fun qualifiedNativeFrameNotExtrapolationOrHeadFloorIsTheDiscardAuthority() {
        exactScenario(listOf(2880, 960)) { player, clock, service, progress, converted ->
            assertNull(player.tryOffer(AccessUnit(1_000_000, byteArrayOf(0))))
            service()
            progress(2880, null)
            service()
            assertEquals(1_060_000L, clock.currentUs())
            assertNull(clock.cachedAudioDiscardCutoffUs())
            progress(2880, 0)
            service()
            progress(2880, 480)
            service()
            assertEquals("DAC is 10 ms even though the sync floor is 60 ms", 1_010_000L,
                clock.cachedAudioDiscardCutoffUs())
            assertNull(player.tryOffer(AccessUnit(1_005_000, byteArrayOf(1))))
            service()
            assertEquals("Only the first 5 ms are proved stale, not the head-floor remainder",
                240L, player.staleDiscardedSourceFrames)
            assertEquals(240, converted.last().first)
            assertEquals(720, converted.last().second)
            progress(2880, null)
            service()
            assertNull("Loss of timestamp proof falls back to bounded FIFO handling", clock.cachedAudioDiscardCutoffUs())
        }
    }

    @Test fun delayedResamplingUsesOriginalSourceUnitsForNativeCutoff() {
        exactScenario(listOf(2880, 960, 960), delayed = true) { player, clock, service, progress, converted ->
            assertNull(player.tryOffer(AccessUnit(1_000_000, byteArrayOf(0))))
            service()
            progress(1200, 1200)
            assertNull(player.tryOffer(AccessUnit(1_060_000, byteArrayOf(1))))
            service()
            assertEquals("1200 output frames represent 2400 original frames", 1_050_000L,
                clock.cachedAudioDiscardCutoffUs())
            // Source before 1.050 is proved played; the unsent filter tail is not.
            assertNull(player.tryOffer(AccessUnit(1_010_000, byteArrayOf(2))))
            service()
            assertEquals(960L, player.staleDiscardedSourceFrames)
            assertEquals(2, converted.size)
        }
    }

    @Test fun aLateIntervalBeforeTheFirstMappedSourceIsNotProvedPlayed() {
        exactScenario(listOf(960, 960)) { player, clock, service, progress, converted ->
            assertNull(player.tryOffer(AccessUnit(1_100_000, byteArrayOf(0))))
            service()
            progress(960, 960)
            service()
            assertEquals(1_120_000L, clock.cachedAudioDiscardCutoffUs())
            assertNull(player.tryOffer(AccessUnit(1_040_000, byteArrayOf(1))))
            service()
            assertEquals(0L, player.staleDiscardedSourceFrames)
            assertEquals(960, converted.last().second)
            assertNull(clock.cachedAudioDiscardCutoffUs())
        }
    }

    private fun exactScenario(
        frames: List<Int>, delayed: Boolean = false,
        check: (AudioPlayer, PlaybackClock, () -> Unit, (Long, Long?) -> Unit,
            MutableList<Pair<Int, Int>>) -> Unit
    ) {
        var now = 0L
        var head = 0L
        var stamp: FrameTimestamp? = FrameTimestamp(0, 0)
        val clock = PlaybackClock({ now }, { now += it * 1_000_000 }).apply { audioExpected = true }
        val position = AudioPosition(48000, 5760, { now }, { stamp }, { head.toInt() })
        val converted = mutableListOf<Pair<Int, Int>>()
        val decoded = mutableListOf<Int>()
        val output = object : AudioOutput {
            override val underrunCount = 0
            override fun play() {}
            override fun pause() {}
            override fun close() {}
            override fun setSpeed(speed: Float) { fail("Native speed must not change") }
            override fun timing() = position.current()
            override fun write(samples: ShortArray, offset: Int, count: Int): Int {
                position.written(count / 2)
                return count
            }
        }
        val player = testAudioPlayer(AudioFormat("opus", 48000, 2, byteArrayOf(1)), clock,
            { code, detail -> fail("$code: $detail") }, { output },
            opusFactory = { object : OpusPacketDecoder {
                override fun decode(packet: ByteArray): ShortArray {
                    val id = packet[0].toInt()
                    assertEquals(decoded.size, id)
                    decoded.add(id)
                    return ShortArray(frames[id] * 2) { (id + 1).toShort() }
                }
                override fun close() {}
            } },
            resamplerFactory = { _, _ -> object : PcmResampler {
                override val delayFrames get() = if (delayed) 480 else 0
                override fun convert(samples: ShortArray, offsetFrames: Int, frames: Int): ShortArray {
                    converted.add(offsetFrames to frames)
                    val released = frames - if (delayed && converted.size == 1) 480 else 0
                    return ShortArray((if (delayed) released / 2 else released) * 2)
                }
                override fun compensate(deltaFrames: Int, distanceFrames: Int) {}
                override fun close() {}
            } })
        val step = AudioPlayer::class.java.getDeclaredMethod("serviceStep").apply { isAccessible = true }
        try {
            check(player, clock, { step.invoke(player); Unit }, { consumed, native ->
                head = consumed
                now += 1_000_000
                stamp = native?.let { FrameTimestamp(it, now) }
            }, converted)
        } finally { player.close() }
    }

    private fun backlog(codecName: String, recovered: Boolean = false, oversizedStale: Boolean = false) {
        var now = 0L
        var submitted = 0L
        var consumed = 0L
        var stamp = FrameTimestamp(0, 0)
        val position = AudioPosition(48000, 5760, { now }, { stamp }, { consumed.toInt() })
        val clock = PlaybackClock({ now }, { now += it * 1_000_000 }).apply { audioExpected = true }
        val inputs = mutableListOf<Int>()
        val converted = mutableListOf<Triple<Int, Int, ShortArray>>()
        val source = listOf(2880, 2880, 960, 960, 960).map { frames ->
            ShortArray(frames * 2) { (it + 1).toShort() }
        }
        val codec = if (codecName == "aac") mock(MediaCodec::class.java) else null
        val inputBuffer = ByteBuffer.allocate(1)
        var aacPacket: Pair<Int, Long>? = null
        codec?.let {
            doReturn(0).`when`(it).dequeueInputBuffer(anyLong())
            doAnswer { inputBuffer }.`when`(it).getInputBuffer(anyInt())
            doAnswer { call ->
                val id = inputBuffer.get(0).toInt()
                assertEquals(inputs.size, id)
                inputs.add(id)
                aacPacket = id to call.getArgument<Long>(3)
                null
            }.`when`(it).queueInputBuffer(anyInt(), anyInt(), anyInt(), anyLong(), anyInt())
            doAnswer { call ->
                aacPacket?.let { (id, pts) ->
                    call.getArgument<MediaCodec.BufferInfo>(0).set(0, source[id].size * 2, pts, 0)
                    aacPacket = null
                    id
                } ?: MediaCodec.INFO_TRY_AGAIN_LATER
            }.`when`(it).dequeueOutputBuffer(any(MediaCodec.BufferInfo::class.java), anyLong())
            doAnswer { call -> pcm(source[call.getArgument<Int>(0)]) }.`when`(it).getOutputBuffer(anyInt())
        }
        val output = object : AudioOutput {
            override val underrunCount = 0
            override fun play() {}
            override fun pause() {}
            override fun setSpeed(speed: Float) { fail("Native speed must not change") }
            override fun close() {}
            override fun timing() = position.current()
            override fun write(samples: ShortArray, offset: Int, count: Int): Int {
                val accepted = minOf(count / 2, (5760 - submitted + consumed).toInt())
                submitted += accepted
                position.written(accepted)
                return accepted * 2
            }
        }
        val telemetry = if (codecName == "pcm") ReceiverTelemetrySession({ true }) else null
        telemetry?.let { TelemetryRegistry.beginReceiver(9915, it) }
        val player = testAudioPlayer(AudioFormat(codecName, 48000, 2, byteArrayOf(1)), clock,
            { code, detail -> fail("$code: $detail") }, { output },
            opusFactory = { object : OpusPacketDecoder {
                override fun decode(packet: ByteArray): ShortArray {
                    val id = packet[0].toInt()
                    assertEquals(inputs.size, id)
                    inputs.add(id)
                    return if (oversizedStale && id == 2) ShortArray(5761 * 2) else source[id]
                }
                override fun close() {}
            } },
            resamplerFactory = { _, _ -> object : PcmResampler {
                override val delayFrames = 0
                override fun convert(samples: ShortArray, offsetFrames: Int, frames: Int): ShortArray =
                    samples.copyOfRange(offsetFrames * 2, (offsetFrames + frames) * 2).also {
                        converted.add(Triple(offsetFrames, frames, it))
                    }
                override fun compensate(deltaFrames: Int, distanceFrames: Int) {}
                override fun close() {}
            } }, aacFactory = codec?.let { { _: AudioFormat -> it } }).apply {
                diagnosticSession = 9915
                diagnosticGeneration = 1
            }
        val step = AudioPlayer::class.java.getDeclaredMethod("serviceStep").apply { isAccessible = true }
        fun packet(id: Int, pts: Long) = AccessUnit(pts,
            if (codecName == "pcm") pcm(source[id]).array() else byteArrayOf(id.toByte()))
        fun service() { step.invoke(player) }
        try {
            assertNull(player.tryOffer(packet(0, 1_000_000)))
            service()
            assertNull(clock.cachedAudioDiscardCutoffUs())
            consumed = submitted
            now += 1_000_000
            stamp = FrameTimestamp(consumed, now)
            assertNull(player.tryOffer(packet(1, 1_060_000)))
            service()
            consumed = submitted
            now += 1_000_000
            stamp = FrameTimestamp(consumed, now)
            service()
            val cutoff = checkNotNull(clock.cachedPlayedAudioSource())
            assertEquals(1_120_000L, cutoff)
            if (recovered) player.recoveryCutoffUs = 1_000_000
            val syncPosition = clock.currentUs()
            val processed = player.processedSourceFrames
            now += 1_000_000
            assertEquals(cutoff, clock.cachedAudioDiscardCutoffUs())
            assertNull(player.tryOffer(packet(2, 1_060_000)))
            assertNull(player.tryOffer(packet(3, cutoff - 1001)))
            // Partially covered dependencies keep their original PTS in the admission span.
            assertNull(player.tryOffer(packet(4, cutoff + 130_000)))
            assertEquals(3, player.queuedPackets())
            if (oversizedStale) {
                val error = assertThrows(InvocationTargetException::class.java) { service() }.cause
                assertTrue(error is PlaybackException)
                assertEquals("CONGESTION", (error as PlaybackException).code)
                assertEquals(0L, player.staleDiscardedSourceFrames)
                assertEquals(listOf(0, 1, 2), inputs)
                assertEquals(2, player.queuedPackets())
                return
            }
            service()
            assertEquals(2, player.queuedPackets())
            assertEquals(processed, player.processedSourceFrames)
            assertEquals(2, converted.size)
            service()
            assertEquals(1, player.queuedPackets())
            assertEquals("Overlap extending the accepted interval disables destructive trimming", 0, converted.last().first)
            assertEquals(960, converted.last().second)
            assertEquals(1.toShort(), converted.last().third[0])
            assertEquals(2.toShort(), converted.last().third[1])
            service()
            assertEquals(0, player.queuedPackets())
            assertEquals(0, converted.last().first)
            assertEquals(960, converted.last().second)
            assertEquals(syncPosition, clock.currentUs())
            assertEquals(processed + 960 + 960, player.processedSourceFrames)
            assertEquals(960L, player.staleDiscardedSourceFrames)
            if (codecName != "pcm") assertEquals(listOf(0, 1, 2, 3, 4), inputs)
            codec?.let { verify(it, never()).flush() }
            assertNull("Future source gap disables normal trimming", clock.cachedAudioDiscardCutoffUs())
            telemetry?.let {
                TelemetryRegistry.freezeReceiver(9915, 1)
                val batch = SessionTelemetryProtocol.parse(checkNotNull(
                    it.requestFinal(true, System.nanoTime() + 1_000_000_000)).payload)
                val trim = batch.events.single { row -> row.kind == CausalEvidence.KIND &&
                    row.stage == CausalEvidence.Stage.TRIM.ordinal && row.detail.firstOrNull() == 4L }
                assertEquals(listOf(4L, 1_060_000L, 1_080_000L,
                    cutoff, cutoff, 960L, 0L), trim.detail)
            }
        } finally {
            player.close()
            telemetry?.let {
                TelemetryRegistry.end("receiver", it)
                it.requestFinal(false, System.nanoTime())
            }
        }
        assertNull(clock.cachedAudioDiscardCutoffUs())
    }

    @Test fun unknownAndStalledCutoffsRetainPressureAndHardCapacityBounds() {
        val worker = object : TrackWorker("stale-bounds", 512, { _, _ -> fail("Not started") }, 2) {
            fun dequeue() = next()
            override fun step() {}
            override fun release() {}
        }
        try {
            assertNull(worker.tryOffer(AccessUnit(0, byteArrayOf(1))))
            assertEquals(QueueLimit.PTS, worker.tryOffer(AccessUnit(300_000, byteArrayOf(1)))?.limit)
            assertEquals(QueueLimit.PTS, worker.tryOffer(AccessUnit(1_400_000, byteArrayOf(1)))?.limit)
            worker.dequeue()
            repeat(48) { assertNull(worker.tryOffer(AccessUnit(0, byteArrayOf(1)))) }
            assertEquals(QueueLimit.PACKETS, worker.tryOffer(AccessUnit(0, byteArrayOf(1)))?.limit)
            repeat(48) { worker.dequeue() }
            assertNull(worker.tryOffer(AccessUnit(0, ByteArray(512))))
            assertEquals(QueueLimit.BYTES, worker.tryOffer(AccessUnit(0, byteArrayOf(1)))?.limit)
        } finally { worker.close() }
    }

    private fun pcm(samples: ShortArray): ByteBuffer =
        ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            samples.forEach { putShort(it) }
            flip()
        }
}

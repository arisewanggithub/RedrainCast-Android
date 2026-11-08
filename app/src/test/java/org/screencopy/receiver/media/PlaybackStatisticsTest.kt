package org.screencopy.receiver.media

import org.junit.Assert.*
import org.junit.Test

class PlaybackStatisticsTest {
    @Test fun enablingStatisticsPreservesAllSourceSamplesAndDoesNotAddNativeTimingQueries() {
        fun run(enabled: Boolean): Triple<ShortArray, Long, Int> {
            val now = java.util.concurrent.atomic.AtomicLong(1_000_000_000)
            val output = Output()
            val clock = PlaybackClock(now::get) { now.addAndGet(it * 1_000_000) }
                .apply { audioExpected = true; anchorVideo(1_000_000) }
            val player = testAudioPlayer(AudioFormat("pcm", 48000, 2, byteArrayOf()), clock,
                { code, detail -> fail("$code $detail") }, { output }).apply { statisticsEnabled = enabled }
            val step = AudioPlayer::class.java.getDeclaredMethod("step").apply { isAccessible = true }
            try {
                repeat(100) { index ->
                    val bytes = java.nio.ByteBuffer.allocate(3840).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    repeat(1920) { bytes.putShort((index * 1920 + it).toShort()) }
                    player.offer(AccessUnit(1_000_000 + index * 20_000L, bytes.array()))
                    step.invoke(player)
                    output.consume()
                    now.addAndGet(20_000_000)
                    clock.currentUs()
                }
                assertEquals(96000L, player.sourceSubmittedFrames)
                assertEquals(enabled, player.audioStatistics != null)
                return Triple(output.samples.toShortArray(), player.sourceSubmittedFrames, output.queries)
            } finally { player.close() }
        }
        val baseline = run(false)
        val observed = run(true)
        assertArrayEquals(baseline.first, observed.first)
        assertEquals(baseline.second, observed.second)
        assertEquals("Statistics must not add native timing queries", baseline.third, observed.third)
    }

    @Test fun cachedPlayedSourceReadsNeverQueryNativeOutputOrAdvanceTheSourceClock() {
        var now = 0L
        var queries = 0
        val clock = PlaybackClock({ now }, { now += it * 1_000_000 }).apply {
            audioExpected = true
            anchorVideo(1_000_000)
        }
        assertNull(clock.cachedPlayedAudioSource())
        clock.prepareAudio(TrimmedAudio(0, 960, 1_000_000), 48000)
        now = 50_000_000
        clock.anchorAudio(1_000_000, 48000) { queries++; 10_000 }
        clock.audioWritten(20_000)
        assertNull(clock.cachedPlayedAudioSource())
        assertEquals(1_010_000L, clock.currentUs())
        val observation = checkNotNull(clock.cachedPlayedAudioObservation())
        assertEquals(1_010_000L, observation.ptsUs)
        assertEquals(now, observation.observedAtNs)
        assertEquals(1, queries)
        now += 90_000_000
        repeat(100) {
            assertEquals(1_010_000L, clock.cachedPlayedAudioSource())
            assertSame("Reading a cache must not refresh its observation time", observation, clock.cachedPlayedAudioObservation())
        }
        assertEquals(1, queries)
        clock.detachAudio()
        assertNull(clock.cachedPlayedAudioSource())
        assertNull(clock.cachedPlayedAudioObservation())
    }

    private fun input(generation: Long = 1, epoch: Long = 1, count: Long = 0, audio: Boolean = true,
        observedMs: Long = 1000) =
        PlaybackStatisticsInput(7, generation, epoch, "Playing", "h264", if (audio) "raw" else null,
            1920, 1080, 60, 8_000_000, if (audio) 192000 else null, 90, "auto",
            count, count, count * 1000, count * 100,
            VideoStatistics(count, count, count / 10, count, if (count > 0) 1_100_000 else null, observedMs * 1_000_000),
            if (audio) AudioStatistics(20.0, 50.0, 40.0, count.toDouble(), count / 2.0,
                1_000_000, 150.0, observedMs * 1_000_000) else null,
            EncodedQueueStatistics(1, 1000, 20000, 5, 8 * 1024 * 1024), null, null, 10)

    @Test fun firstSampleIsUnknownThenRollingDeltasAllowGenuineIdleZero() {
        val sampler = PlaybackStatisticsSampler()
        assertNull(sampler.sample(1000, input()).rxFps)
        val snapshot = sampler.sample(2000, input(count = 60, observedMs = 2000))
        assertEquals(60.0, snapshot.rxFps!!, 0.0)
        assertEquals(60.0, snapshot.presentedFps!!, 0.0)
        assertEquals(0.48, snapshot.videoMbps!!, 0.00001)
        assertEquals(100.0, snapshot.syncMs!!, 0.0)
        assertEquals(600.0 / 66, snapshot.recentSkippedPercent!!, 0.00001)
        assertEquals(60.0, snapshot.recentSilenceMs!!, 0.0)
        assertEquals(0.0, sampler.sample(3000, input(count = 60)).rxFps!!, 0.0)
    }

    @Test fun skippedPercentageUsesActualPresentedPlusSkippedRatherThanDecodedOrReceived() {
        val sampler = PlaybackStatisticsSampler()
        sampler.sample(1000, input())
        val snapshot = sampler.sample(2000, input(count = 200).copy(video = VideoStatistics(100, 80, 30, 70, 1_100_000)))
        assertEquals(30.0, snapshot.recentSkippedPercent!!, 0.0)
    }

    @Test fun epochsGenerationsCounterRegressionsAndHiddenResetNeverAverageAcrossBaselines() {
        val sampler = PlaybackStatisticsSampler()
        sampler.sample(1000, input(count = 10))
        assertNull(sampler.sample(2000, input(generation = 2, count = 20)).rxFps)
        assertNull(sampler.sample(3000, input(generation = 2, epoch = 2, count = 30)).rxFps)
        assertNull(sampler.sample(4000, input(generation = 2, epoch = 2, count = 1)).rxFps)
        sampler.reset()
        assertNull(sampler.sample(5000, input(count = 50)).rxFps)
    }

    @Test fun unavailableAudioAndRenderedCallbacksAreHonestAndLabelsDoNotClaimLatencyOrNetworkLoss() {
        val sampler = PlaybackStatisticsSampler()
        val first = input(audio = false).copy(video = VideoStatistics(1, 1, 0, null, null))
        sampler.sample(1000, first)
        val snapshot = sampler.sample(2000, first)
        assertNull(snapshot.presentedFps)
        assertNull(snapshot.syncMs)
        assertNull(snapshot.audioMbps)
        val text = PlaybackStatisticsText.format(snapshot, 1080, 1920)
        assertTrue(text.contains("N/A"))
        assertTrue(text.contains("not network loss"))
        assertTrue(text.contains("Generation"))
        val model = PlaybackStatisticsText.display(snapshot, 1080, 1920)
        assertEquals("N/A", model.connections.last().rtt)
        assertEquals("Off", model.audio.first().value)
        assertEquals("N/A", model.footer.first { it.id == "playbackEpoch" }.value)
        assertEquals("N/A", model.footer.first { it.id == "occluded" }.value)
    }

    @Test fun commonLabelOrderFieldIdsUnitsAndNullSemanticsMatchGoldenDisplayManifest() {
        val fixture = javaClass.getResourceAsStream("/playback-statistics-display.json")!!.use {
            org.screencopy.receiver.protocol.StrictJson.decode(it.readBytes())
        }
        val model = PlaybackStatisticsText.display(PlaybackStatisticsSampler().sample(1000, input()), 1080, 1920)
        assertEquals(2, fixture.getInt("displayContractVersion"))
        assertFalse(fixture.getBoolean("wireContractChanged"))
        assertEquals(fixture.getString("header"), model.header)
        for ((group, fields) in listOf("kpis" to model.kpis, "totals" to model.totals,
            "video" to model.video, "audio" to model.audio, "footer" to model.footer,
            "diagnostics" to model.diagnostics)) {
            val expected = fixture.getJSONArray(group)
            assertEquals(expected.length(), fields.size)
            fields.forEachIndexed { index, field ->
                assertEquals(expected.getJSONObject(index).getString("id"), field.id)
                assertEquals(expected.getJSONObject(index).getString("label"), field.label)
                val entry = expected.getJSONObject(index)
                assertTrue(entry.optString("unit").isNotEmpty() || entry.optString("meaning").isNotEmpty())
            }
        }
        assertEquals(listOf("Channel", "Queued", "Span", "Last activity", "RTT"), model.connectionHeaders)
        assertEquals(listOf("Video", "Audio", "Control"), model.connections.map { it.channel })
        assertEquals("H.264", model.video.first().value)
        assertEquals("60 FPS", model.video.first { it.id == "captureLimit" }.value)
        for ((raw, label) in listOf("h265" to "H.265 (HEVC)", "av1" to "AV1")) {
            val display = PlaybackStatisticsText.display(PlaybackStatisticsSampler().sample(1000, input().copy(videoCodec = raw)), 1, 1)
            assertEquals(label, display.video.first().value)
        }
        for ((raw, label) in listOf("raw" to "PCM", "pcm" to "PCM", "opus" to "Opus", "aac" to "AAC")) {
            val display = PlaybackStatisticsText.display(PlaybackStatisticsSampler().sample(1000, input().copy(audioCodec = raw)), 1, 1)
            assertEquals(label, display.audio.first().value)
        }
    }

    @Test fun renderTargetAndDisplayOutputNeverBorrowEncodedOrAppViewDimensions() {
        val snapshot = PlaybackStatisticsSampler().sample(1000, input().copy(encodedWidth = 2496, encodedHeight = 1664))
        val unknown = PlaybackStatisticsText.display(snapshot, 1920, 1080)
        assertTrue(unknown.video.none { it.id == "renderTarget" })
        assertEquals("N/A", unknown.diagnostics.first { it.id == "renderTarget" }.value)
        assertEquals("N/A", unknown.video.first { it.id == "displayOutput" }.value)
        assertTrue(unknown.video.none { it.id == "receiverCanvas" })
        assertEquals("1920 × 1080 px", unknown.diagnostics.first { it.id == "receiverCanvas" }.value)
        val active4k = PlaybackStatisticsText.display(snapshot, 1920, 1080,
            PlaybackDisplayMeasurements(displayOutput = PixelDimensions(3840, 2160)))
        assertEquals("3840 × 2160 px", active4k.video.first { it.id == "displayOutput" }.value)
        assertTrue(active4k.video.none { it.id == "renderTarget" })
        assertEquals("N/A", active4k.diagnostics.first { it.id == "renderTarget" }.value)
        assertEquals("2496 × 1664", active4k.video.first { it.id == "encodedSize" }.value)
        val measured = PlaybackStatisticsText.display(snapshot, 1920, 1080,
            PlaybackDisplayMeasurements(renderTarget = PixelDimensions(2560, 1440),
                displayOutput = PixelDimensions(3840, 2160)))
        assertTrue(measured.video.none { it.id == "renderTarget" })
        assertEquals("2560 × 1440 px", measured.diagnostics.first { it.id == "renderTarget" }.value)
        assertEquals("3840 × 2160 px", measured.video.first { it.id == "displayOutput" }.value)
        assertTrue(active4k.diagnostics.last().value.contains("not scanout proof"))
    }

    @Test fun lastPacketActivityNeverReusesQueueAdmissionAgeAndOnlyControlHasRtt() {
        val sample = input().copy(videoLastPacketAgeMs = 125, audioLastPacketAgeMs = 250, rttMs = 78)
        val model = PlaybackStatisticsText.display(PlaybackStatisticsSampler().sample(1000, sample), 1920, 1080)
        assertEquals("125 ms", model.connections[0].lastActivity)
        assertEquals("250 ms", model.connections[1].lastActivity)
        assertEquals("10 ms", model.connections[2].lastActivity)
        assertEquals("78 ms", model.connections[2].rtt)
        assertEquals(listOf("—", "—"), model.connections.take(2).map { it.rtt })
        assertEquals("—", model.connections[2].queued)
        assertEquals("—", model.connections[2].span)
        val empty = model.copy(connections = PlaybackStatisticsText.display(
            PlaybackStatisticsSampler().sample(1000, input()), 1920, 1080).connections)
        assertEquals("N/A", empty.connections[0].lastActivity)
        assertEquals("N/A", empty.connections[1].lastActivity)
    }

    @Test fun observedPeakUsesOnlyOneHzSamplesAndResetsWithEpochNotAnInventedRecentWindowMaximum() {
        val sampler = PlaybackStatisticsSampler()
        sampler.sample(1000, input(count = 1))
        val second = sampler.sample(2000, input(count = 2, observedMs = 2000).copy(
            video = VideoStatistics(2, 2, 0, 2, 1_200_000, 2_000_000_000)))
        assertEquals(200.0, second.generationSyncPeakMs!!, 0.0)
        val third = sampler.sample(3000, input(count = 3, observedMs = 3000))
        assertEquals(100.0, third.recentSyncPeakMs!!, 0.0)
        assertEquals(200.0, third.generationSyncPeakMs!!, 0.0)
        val model = PlaybackStatisticsText.display(third, 1920, 1080)
        assertEquals("200.0 ms", model.audio.last().value)
        assertTrue(model.peakCaption.contains("sampled 1 Hz"))
        assertEquals(100.0, sampler.sample(4000, input(epoch = 2, count = 4, observedMs = 4000)).generationSyncPeakMs!!, 0.0)
    }

    @Test fun staggeredOrUnknownObservationTimesCannotCreateFalseSkewOrInflateThePeak() {
        val sampler = PlaybackStatisticsSampler()
        val aligned = input(count = 1)
        assertEquals(100.0, sampler.sample(1000, aligned).syncMs!!, 0.0)
        val lagged = input(count = 2, observedMs = 2000).copy(
            video = VideoStatistics(2, 2, 0, 2, 1_083_333, 2_000_000_000),
            audio = checkNotNull(aligned.audio).copy(playedSourcePtsUs = 1_000_000,
                playedSourceObservedAtNs = 1_910_000_000))
        val invalid = sampler.sample(2000, lagged)
        assertNull("Publication lag is not physical A/V skew", invalid.syncMs)
        assertNull(invalid.recentSyncPeakMs)
        assertEquals("Invalid observations cannot become a generation maximum", 100.0,
            invalid.generationSyncPeakMs!!, 0.0)
        for (missing in listOf(
            lagged.copy(video = lagged.video!!.copy(renderedAtNs = null)),
            lagged.copy(audio = lagged.audio!!.copy(playedSourceObservedAtNs = null)),
            input(count = 3, observedMs = 1000),
            input(count = 3, observedMs = 3000)
        )) assertNull(sampler.sample(2000, missing).syncMs)
    }

    @Test fun onlyFreshPairsWithinTenMillisecondsAreComparedWithoutExtrapolation() {
        val source = input(count = 1, observedMs = 2000)
        val audio = checkNotNull(source.audio)
        for ((separationNs, accepted) in listOf(10_000_000L to true, 10_000_001L to false)) {
            val pair = source.copy(audio = audio.copy(playedSourceObservedAtNs = 2_000_000_000 - separationNs))
            val result = PlaybackStatisticsSampler().sample(2000, pair)
            if (accepted) assertEquals(100.0, result.syncMs!!, 0.0) else assertNull(result.syncMs)
        }
        assertEquals(100.0, PlaybackStatisticsSampler().sample(2250, source).syncMs!!, 0.0)
        assertNull(PlaybackStatisticsSampler().sample(2251, source).syncMs)
        val reset = PlaybackStatisticsSampler().sample(3000, input(generation = 2, epoch = 2, count = 1, observedMs = 1000))
        assertNull(reset.syncMs)
        assertNull(reset.generationSyncPeakMs)
    }

    private class Output : AudioOutput {
        override val underrunCount = 0
        private var submitted = 0L
        private var played = 0L
        var queries = 0
        val samples = mutableListOf<Short>()
        override fun play() {}
        override fun pause() {}
        override fun timing(): AudioOutputTiming {
            queries++
            return AudioOutputTiming(48000, 3921, submitted, played, played, 0, AudioClockSource.TIMESTAMP)
        }
        override fun write(samples: ShortArray, offset: Int, count: Int): Int {
            val frames = minOf(count / 2, (3921 - submitted + played).toInt())
            repeat(frames * 2) { this.samples.add(samples[offset + it]) }
            submitted += frames
            return frames * 2
        }
        fun consume() { played = submitted }
        override fun setSpeed(speed: Float) = fail("Statistics must not change native rate")
        override fun close() {}
    }
}

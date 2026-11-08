package dev.screencopy.sender

import org.junit.Assert.*
import org.junit.Test
import org.screencopy.receiver.protocol.AudioPacketTiming
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

class StreamRecoverySenderTest {
    private val now = AtomicLong(1_000_000_000)
    private val mux = MediaBudget(true, now::get, 1)
    private val recovery = StreamRecoverySender(73, mux, now::get)
    private fun video(pts: Long, key: Boolean = false, rotation: Int = 0) =
        Wire.Message(Wire.VIDEO_FRAME, (if (key) 1 else 0) or (rotation / 90 shl 1), 73, 1, 1, pts,
            byteArrayOf(0, 0, 0, 1, if (key) 0x65 else 0x41, 1))
    private fun audio(pts: Long) = Wire.Message(Wire.AUDIO_PACKET, 0, 73, 2, 1, pts, ByteArray(3840))
    private fun finishWrite(track: Int) = checkNotNull(mux.pollLane(track, 1)).also {
        mux.beginWrite(it)
        recovery.written(it)
        mux.endWrite(it)
    }
    private fun configure(opus: Boolean = false) {
        recovery.config(1, 1, Wire.obj("codec" to "h264", "width" to 720, "height" to 1280))
        recovery.config(2, 1, Wire.obj("codec" to if (opus) "opus" else "raw", "sampleRate" to 48000, "channels" to 2))
        for (track in 1..2) { finishWrite(track); mux.ready(track, 1) }
        assertTrue(recovery.ready())
    }
    private fun activate(opus: Boolean = false) {
        configure(opus)
        recovery.packet(video(1_000_000, true))
        finishWrite(1)
        recovery.keyProved(1, 1_000_000)
        recovery.completed(1, 1, 1_000_000)
    }

    @Test fun audioFirstReadinessCannotSendRecoveryBeforeInitialVideoIsReady() {
        recovery.config(2, 1, Wire.obj("codec" to "raw", "sampleRate" to 48000, "channels" to 2))
        finishWrite(2); mux.ready(2, 1)
        assertFalse(recovery.ready())
        repeat(45) { index ->
            now.set(1_000_000_000 + index * 20_000_000L)
            recovery.packet(audio(1_000_000 + index * 20_000L))
            assertNull("Initial audio must not send media or another configuration", mux.pollLane(2, 1))
            assertTrue(mux.queuedSpanUs(2) <= 500_000)
            assertTrue(mux.queuedBytes() <= 512 * 1024)
        }
        now.set(1_900_000_000)
        recovery.config(1, 1, Wire.obj("codec" to "h264"))
        finishWrite(1); mux.ready(1, 1)
        assertTrue(recovery.ready())
        recovery.packet(video(1_900_000, true))
        assertEquals(1, finishWrite(1).generation)
        recovery.packet(audio(1_900_000))
        val first = finishWrite(2)
        assertEquals(Wire.AUDIO_PACKET, first.type)
        assertEquals(1, first.generation)
        assertEquals(1_900_000L, first.pts)
    }

    @Test fun videoFirstReadinessCannotRequestKeysOrRecoverBeforeInitialAudioIsReady() {
        recovery.config(1, 1, Wire.obj("codec" to "h264"))
        finishWrite(1); mux.ready(1, 1)
        assertFalse(recovery.ready())
        repeat(45) { index ->
            now.set(1_000_000_000 + index * 20_000_000L)
            recovery.packet(video(1_000_000 + index * 20_000L, key = true))
            assertNull(mux.pollLane(1, 1))
            assertFalse(recovery.retryKeyFrame())
        }
        now.set(1_900_000_000)
        recovery.config(2, 1, Wire.obj("codec" to "raw", "sampleRate" to 48000, "channels" to 2))
        finishWrite(2); mux.ready(2, 1)
        assertTrue(recovery.ready())
        recovery.packet(video(1_900_000, true))
        assertEquals(1, finishWrite(1).generation)
    }

    @Test fun delayedInitialReadinessRetainsOnlyFreshOrderedOpusPreroll() {
        recovery.config(2, 1, Wire.obj("codec" to "opus", "sampleRate" to 48000, "channels" to 2))
        finishWrite(2); mux.ready(2, 1); recovery.ready()
        repeat(45) { index ->
            now.set(1_000_000_000 + index * 20_000_000L)
            recovery.packet(audio(1_000_000 + index * 20_000L).copy(payload = byteArrayOf(8, index.toByte())))
            assertNull(mux.pollLane(2, 1))
            assertTrue(mux.queuedSpanUs(2) <= 500_000)
        }
        now.set(1_900_000_000)
        recovery.config(1, 1, Wire.obj("codec" to "h264"))
        finishWrite(1); mux.ready(1, 1)
        assertTrue(recovery.ready())
        assertNull(mux.pollLane(2, 1))
        recovery.packet(audio(1_900_000).copy(payload = byteArrayOf(8, 45)))
        repeat(5) { index ->
            val packet = finishWrite(2)
            assertEquals(1, packet.generation)
            assertEquals(1_820_000 + index * 20_000L, packet.pts)
            assertArrayEquals(byteArrayOf(8, (41 + index).toByte()), packet.payload)
        }
        assertNull(mux.pollLane(2, 1))
    }

    @Test fun firstInitialOpusPacketAfterReadinessCollectsItsPrerollWithoutAnotherGeneration() {
        configure(opus = true)
        now.set(1_600_000_000)
        repeat(4) { index ->
            recovery.packet(audio(1_600_000 + index * 20_000L).copy(payload = byteArrayOf(8, index.toByte())))
            assertNull(mux.pollLane(2, 1))
        }
        recovery.packet(audio(1_680_000).copy(payload = byteArrayOf(8, 4)))
        repeat(5) { index -> assertEquals(1_600_000 + index * 20_000L, finishWrite(2).pts) }
        assertNull(mux.pollLane(1, 1))
    }

    @Test fun lostInitialOpusPrefixRestartsAudioOnlyAfterAllInitialTracksAreReady() {
        recovery.config(2, 1, Wire.obj("codec" to "opus", "sampleRate" to 48000, "channels" to 2))
        finishWrite(2); mux.ready(2, 1); recovery.ready()
        repeat(6) { index ->
            recovery.packet(audio(1_000_000 + index * 20_000L).copy(payload = byteArrayOf(8, index.toByte())))
        }
        now.set(1_900_000_000)
        assertNull(mux.pollLane(2, 1))
        recovery.config(1, 1, Wire.obj("codec" to "h264"))
        finishWrite(1); mux.ready(1, 1)
        assertTrue(recovery.ready())
        assertNull(mux.pollLane(2, 1))
        recovery.packet(audio(1_900_000).copy(payload = byteArrayOf(8, 6)))
        val restart = finishWrite(2)
        assertEquals(Wire.STREAM_CONFIG, restart.type)
        assertEquals(2, restart.generation)
        assertEquals("1980000", restart.json().getString("recoveryPtsUs"))
        assertNull(mux.pollLane(1, 1))
        mux.ready(2, 2)
        recovery.ready()
        repeat(5) { index ->
            recovery.packet(audio(1_900_000 + index * 20_000L).copy(payload = byteArrayOf(8, index.toByte())))
        }
        repeat(5) { assertEquals(2, finishWrite(2).generation) }
        recovery.completed(2, 2, 1_980_000)
    }

    @Test fun audioFlowsBeforeVideoKeyProofAndDoesNotWaitForVideoRecoveryReadiness() {
        configure()
        recovery.packet(audio(1_000_000))
        assertEquals(1, finishWrite(2).generation)
        assertNull(mux.pollLane(1, 1))
        recovery.packet(video(1_000_000, true))
        finishWrite(1)
        recovery.keyProved(1, 1_000_000)
        recovery.completed(1, 1, 1_000_000)
        recovery.request(1, 1)
        assertEquals(2, finishWrite(1).generation)
        recovery.packet(audio(1_020_000))
        assertEquals(1, finishWrite(2).generation)
        assertNull(mux.pollLane(1, 1))
    }

    @Test fun videoPressureDropsOnlyTheVideoChainAndRetainsAudioBytesAndPts() {
        activate()
        val original = audio(1_100_000)
        recovery.packet(original)
        recovery.packet(video(1_100_000))
        recovery.packet(video(1_600_000))
        recovery.packet(video(1_600_001))
        assertEquals(Wire.STREAM_CONFIG, finishWrite(1).type)
        val retained = finishWrite(2)
        assertEquals(original.pts, retained.pts)
        assertArrayEquals(original.payload, retained.payload)
        assertEquals(1, retained.generation)
        mux.ready(1, 2)
        now.set(1_800_000_000)
        assertTrue(recovery.ready())
        recovery.packet(video(1_799_999, true))
        assertNull(mux.pollLane(1, 1))
        recovery.packet(video(1_800_000, true, 270))
        val key = finishWrite(1)
        assertEquals(2, key.generation)
        assertEquals(7, key.flags)
        assertEquals(1_800_000L, key.pts)
        recovery.keyProved(2, key.pts)
        recovery.completed(1, 2, key.pts)
    }

    @Test fun fullVideoByteBudgetDoesNotRestartOrHoldHealthyAudio() {
        activate()
        repeat(2) { index -> recovery.packet(video(1_000_000 + index * 20_000L).copy(payload = ByteArray(4 * 1024 * 1024))) }
        val original = audio(1_000_000)
        recovery.packet(original)
        val sent = finishWrite(2)
        assertEquals(Wire.AUDIO_PACKET, sent.type)
        assertEquals(1, sent.generation)
        assertEquals(original.pts, sent.pts)
        assertArrayEquals(original.payload, sent.payload)
        assertEquals(8 * 1024 * 1024, mux.queuedBytes())
        recovery.packet(video(1_040_000))
        assertEquals(Wire.STREAM_CONFIG, finishWrite(1).type)
        recovery.packet(audio(1_020_000))
        assertEquals(1, finishWrite(2).generation)
    }

    @Test fun impossibleAudioCompletionCannotClearTheRecoveryDeadline() {
        activate()
        recovery.request(2, 1)
        finishWrite(2); mux.ready(2, 2); recovery.ready()
        recovery.packet(audio(1_000_000))
        assertThrows(IOException::class.java) { recovery.completed(2, 2, Long.MAX_VALUE) }
        now.set(11_000_000_000)
        assertThrows(IOException::class.java) { recovery.checkDeadline() }
    }

    @Test fun queuedButUnsentAudioCannotProveRecoveryCompletion() {
        activate()
        recovery.request(2, 1)
        finishWrite(2); mux.ready(2, 2); recovery.ready()
        recovery.packet(audio(1_000_000))
        assertThrows(IOException::class.java) { recovery.completed(2, 2, 1_000_000) }
        now.set(11_000_000_000)
        assertThrows(IOException::class.java) { recovery.checkDeadline() }
    }

    @Test fun anInFlightAudioProofWaitsForSuccessfulWriteCompletion() {
        activate()
        recovery.request(2, 1)
        finishWrite(2); mux.ready(2, 2); recovery.ready()
        recovery.packet(audio(1_000_000))
        val packet = checkNotNull(mux.pollLane(2, 1))
        assertThrows(IOException::class.java) { recovery.completed(2, 2, packet.pts) }
        mux.beginWrite(packet)
        assertThrows(IOException::class.java) { recovery.completed(2, 2, 1_020_000) }
        recovery.completed(2, 2, packet.pts)
        recovery.completed(2, 2, packet.pts)
        assertThrows(IOException::class.java) { recovery.completed(2, 2, packet.pts + 1) }
        recovery.written(packet)
        mux.endWrite(packet)
        now.set(11_000_000_000)
        recovery.checkDeadline()
    }

    @Test fun anInFlightProofCannotClearTheDeadlineWhenItsWriteNeverSucceeds() {
        activate()
        recovery.request(2, 1)
        finishWrite(2); mux.ready(2, 2); recovery.ready()
        recovery.packet(audio(1_000_000))
        val packet = checkNotNull(mux.pollLane(2, 1))
        mux.beginWrite(packet)
        recovery.completed(2, 2, packet.pts)
        mux.endWrite(packet)
        now.set(11_000_000_000)
        assertThrows(IOException::class.java) { recovery.checkDeadline() }
    }

    @Test fun audioProofsCannotClaimHolesOrExclusiveEndsOfWrittenPackets() {
        activate()
        recovery.request(2, 1)
        finishWrite(2); mux.ready(2, 2); recovery.ready()
        recovery.packet(audio(1_000_000)); finishWrite(2)
        recovery.packet(audio(1_040_000)); finishWrite(2)
        for (pts in listOf(1_020_000L, 1_030_000L, 1_060_000L, Long.MAX_VALUE))
            assertThrows(IOException::class.java) { recovery.completed(2, 2, pts) }
        recovery.completed(2, 2, 1_040_000)
        now.set(11_000_000_000)
        recovery.checkDeadline()
    }

    @Test fun audioProofWindowIncludesOnlyTheAudiblePartOfAStraddlingPacket() {
        activate()
        recovery.request(2, 1)
        finishWrite(2); mux.ready(2, 2); recovery.ready()
        recovery.packet(audio(990_000)); finishWrite(2)
        assertThrows(IOException::class.java) { recovery.completed(2, 2, 999_999) }
        assertThrows(IOException::class.java) { recovery.completed(2, 2, 1_010_000) }
        recovery.completed(2, 2, 1_000_000)
    }

    @Test fun retiredGenerationWritesCannotProveNewAudioRecovery() {
        activate()
        recovery.request(2, 1)
        finishWrite(2); mux.ready(2, 2); recovery.ready()
        recovery.packet(audio(1_000_000))
        val old = checkNotNull(mux.pollLane(2, 1))
        mux.beginWrite(old)
        recovery.completed(2, 2, old.pts)
        recovery.request(2, 2)
        recovery.written(old)
        mux.endWrite(old)
        finishWrite(2); mux.ready(2, 3); recovery.ready()
        recovery.packet(audio(1_020_000))
        assertThrows(IOException::class.java) { recovery.completed(2, 3, 1_000_000) }
        finishWrite(2)
        recovery.completed(2, 3, 1_020_000)
    }

    @Test fun contiguousSentAudioDoesNotGrowProofHistoryAtPacketCadence() {
        activate()
        recovery.request(2, 1)
        finishWrite(2); mux.ready(2, 2); recovery.ready()
        repeat(5000) { index ->
            recovery.packet(audio(1_000_000 + index * 20L).copy(payload = ByteArray(4)))
            finishWrite(2)
        }
        recovery.completed(2, 2, 1_099_980)
    }

    @Test fun opusProofWindowUsesTheActualMultiFrameDuration() {
        activate(opus = true)
        recovery.request(2, 1)
        finishWrite(2); mux.ready(2, 2); recovery.ready()
        recovery.packet(audio(1_000_000).copy(payload = byteArrayOf(27, 2, 1, 1)))
        finishWrite(2)
        assertThrows(IOException::class.java) { recovery.completed(2, 2, 1_079_999) }
        assertThrows(IOException::class.java) { recovery.completed(2, 2, 1_120_000) }
        recovery.completed(2, 2, 1_119_999)
    }

    @Test fun disjointProofHistoryRetriesOnlyAudioUnderItsOriginalDeadline() {
        activate()
        recovery.request(2, 1)
        finishWrite(2); mux.ready(2, 2); recovery.ready()
        repeat(4096) { index ->
            recovery.packet(audio(1_000_000 + index * 40L).copy(payload = ByteArray(4)))
            finishWrite(2)
        }
        recovery.packet(audio(1_163_840).copy(payload = ByteArray(4)))
        val packet = checkNotNull(mux.pollLane(2, 1))
        mux.beginWrite(packet)
        recovery.written(packet)
        mux.endWrite(packet)
        assertEquals(3, finishWrite(2).generation)
        assertNull(mux.pollLane(1, 1))
        now.set(11_000_000_000)
        assertThrows(IOException::class.java) { recovery.checkDeadline() }
    }

    @Test fun audioPressureRestartsOnlyAudioAndVideoDoesNotNeedAnotherKey() {
        activate()
        recovery.packet(video(1_100_000))
        recovery.packet(audio(1_100_000))
        recovery.packet(audio(1_600_000))
        now.set(1_600_001_000)
        recovery.packet(audio(1_600_001))
        val reset = finishWrite(2)
        assertEquals(2, reset.generation)
        assertEquals("1600001", reset.json().getString("recoveryPtsUs"))
        assertEquals(1, finishWrite(1).generation)
        recovery.packet(video(1_620_000))
        val uninterrupted = finishWrite(1)
        assertEquals(1, uninterrupted.generation)
        assertEquals(0, uninterrupted.flags)
        mux.ready(2, 2)
        assertFalse(recovery.ready())
        recovery.packet(audio(1_620_000))
        val fresh = finishWrite(2)
        assertEquals(2, fresh.generation)
        recovery.completed(2, 2, fresh.pts)
        now.addAndGet(11_000_000_000)
        recovery.checkDeadline()
    }

    @Test fun audioRecoveryCollectsAndReleasesItsOwnOpusPrerollWithoutVideoProof() {
        activate(opus = true)
        recovery.request(2, 1)
        val reset = finishWrite(2)
        assertEquals("1080000", reset.json().getString("recoveryPtsUs"))
        mux.ready(2, 2)
        assertFalse(recovery.ready())
        val packets = (0..4).map { index ->
            audio(1_000_000 + index * 20_000L).copy(payload = byteArrayOf(8, index.toByte()))
        }
        packets.dropLast(1).forEach(recovery::packet)
        assertNull(mux.pollLane(2, 1))
        packets.last().also(recovery::packet)
        packets.forEach { original ->
            val released = finishWrite(2)
            assertEquals(2, released.generation)
            assertEquals(original.pts, released.pts)
            assertArrayEquals(original.payload, released.payload)
        }
        assertNull(mux.pollLane(1, 1))
        recovery.completed(2, 2, 1_080_000)
    }

    @Test fun concurrentRequestsAndProofsAreScopedToTheirOwnTrackGenerations() {
        activate()
        recovery.request(1, 1)
        recovery.request(2, 1)
        recovery.request(1, 1)
        recovery.request(2, 1)
        assertEquals(2, finishWrite(1).generation)
        assertEquals(2, finishWrite(2).generation)
        assertNull(mux.pollLane(1, 1))
        assertNull(mux.pollLane(2, 1))
        recovery.keyProved(1, 1_000_000)
        recovery.completed(1, 1, 1_000_000)
        assertThrows(IOException::class.java) { recovery.request(1, 3) }
        assertThrows(IOException::class.java) { recovery.request(3, 1) }
        for (track in 1..2) mux.ready(track, 2)
        now.addAndGet(500_000_000)
        assertTrue(recovery.ready())
        recovery.packet(video(1_500_000, true))
        recovery.packet(audio(1_000_000))
        finishWrite(1); finishWrite(2)
        recovery.keyProved(2, 1_500_000)
        recovery.completed(1, 2, 1_500_000)
        recovery.completed(2, 2, 1_000_000)
        now.addAndGet(11_000_000_000)
        recovery.checkDeadline()
    }

    @Test fun onlyUsableCompletionClearsEachTracksOriginalDeadline() {
        activate()
        recovery.request(1, 1)
        recovery.request(2, 1)
        finishWrite(1); finishWrite(2)
        mux.ready(1, 2); mux.ready(2, 2)
        now.set(10_900_000_000)
        assertTrue(recovery.ready())
        recovery.packet(video(10_900_000, true))
        finishWrite(1)
        recovery.keyProved(2, 10_900_000)
        recovery.completed(1, 2, 10_900_000)
        now.set(10_999_999_999)
        recovery.checkDeadline()
        now.incrementAndGet()
        val error = assertThrows(IOException::class.java) { recovery.checkDeadline() }
        assertTrue(error.message!!.contains("track 2"))
    }

    @Test fun repeatedResetsNeverExtendAStalledTracksDeadline() {
        activate()
        recovery.request(1, 1)
        finishWrite(1); mux.ready(1, 2)
        now.set(10_900_000_000)
        assertTrue(recovery.ready())
        recovery.packet(video(10_900_000, true))
        finishWrite(1)
        recovery.request(1, 2)
        assertEquals(3, finishWrite(1).generation)
        now.set(11_000_000_000)
        assertThrows(IOException::class.java) { recovery.checkDeadline() }
    }

    @Test fun proofsAreExactAndIdempotentWithoutCrossTrackFields() {
        activate()
        recovery.keyProved(1, 1_000_000)
        recovery.completed(1, 1, 1_000_000)
        assertThrows(IOException::class.java) { recovery.keyProved(1, 1_000_001) }
        assertThrows(IOException::class.java) { recovery.completed(1, 1, 1_000_001) }
        recovery.request(2, 1)
        finishWrite(2); mux.ready(2, 2); recovery.ready()
        recovery.packet(audio(1_000_000)); finishWrite(2)
        recovery.completed(2, 2, 1_000_000)
        recovery.completed(2, 2, 1_000_000)
        assertThrows(IOException::class.java) { recovery.completed(2, 2, 1_000_001) }
    }

    @Test fun keyFreshnessAndRequestCadenceRemainBounded() {
        configure()
        assertFalse(recovery.retryKeyFrame())
        now.addAndGet(499_999_999)
        assertFalse(recovery.retryKeyFrame())
        now.incrementAndGet()
        assertTrue(recovery.retryKeyFrame())
        recovery.packet(video(999_999, true))
        assertNull(mux.pollLane(1, 1))
        recovery.packet(video(1_250_000, true))
        assertEquals(1_250_000L, finishWrite(1).pts)
    }

    @Test fun malformedPacketsAndAudioFormatChangesRemainFatalDuringRecovery() {
        configure()
        recovery.packet(video(900_000))
        assertThrows(IOException::class.java) { recovery.packet(video(899_999)) }
        assertThrows(IOException::class.java) { recovery.packet(video(-1)) }
        assertThrows(IOException::class.java) { recovery.packet(video(1_000_000).copy(generation = 2)) }
        assertThrows(IOException::class.java) { recovery.packet(video(1_000_000).copy(session = 74)) }
        assertThrows(IOException::class.java) { recovery.config(2, 2, Wire.obj("codec" to "raw")) }
        assertThrows(org.screencopy.receiver.protocol.ProtocolException::class.java) {
            recovery.packet(video(1_000_000, true).copy(payload = byteArrayOf(0, 0, 1, 0x41, 1)))
        }
    }

    @Test fun videoOnlyRecoveryNeverCreatesAnAudioGeneration() {
        val budget = MediaBudget(false, now::get, 1)
        val sender = StreamRecoverySender(73, budget, now::get, withAudio = false)
        sender.config(1, 1, Wire.obj("codec" to "h264"))
        budget.endWrite(checkNotNull(budget.pollLane(1, 1))); budget.ready(1, 1)
        assertTrue(sender.ready())
        sender.packet(video(1_000_000, true, 180))
        val key = checkNotNull(budget.pollLane(1, 1))
        assertEquals(5, key.flags)
        sender.keyProved(1, key.pts)
        sender.completed(1, 1, key.pts)
        assertThrows(IOException::class.java) { sender.request(2, 1) }
        now.addAndGet(30_000_000_000)
        sender.checkDeadline()
    }

    @Test fun retainedOpusPacketsKeepEqualAndReversedPtsInAdmissionOrder() {
        activate(opus = true)
        recovery.request(2, 1)
        finishWrite(2); mux.ready(2, 2); recovery.ready()
        val original = listOf(1_000_000L, 1_020_000L, 1_020_000L, 1_019_551L, 1_040_000L, 1_060_000L, 1_080_000L)
            .mapIndexed { index, pts -> audio(pts).copy(payload = byteArrayOf(8, index.toByte())) }
        original.forEach(recovery::packet)
        original.forEach { packet ->
            val released = finishWrite(2)
            assertEquals(packet.pts, released.pts)
            assertArrayEquals(packet.payload, released.payload)
        }
    }

    @Test fun actualPrerollEvictionRetriesOnlyAudioWithoutExtendingItsDeadline() {
        activate(opus = true)
        recovery.request(2, 1)
        finishWrite(2)
        repeat(56) { index ->
            now.set(1_000_000_000 + index * 20_000_000L)
            recovery.packet(audio(1_000_000 + index * 20_000L).copy(payload = byteArrayOf(8, index.toByte())))
            assertTrue(mux.queuedSpanUs(2) <= 500_000)
        }
        mux.ready(2, 2); recovery.ready()
        val retry = finishWrite(2)
        assertEquals(3, retry.generation)
        assertNull(mux.pollLane(1, 1))
        now.set(11_000_000_000)
        assertThrows(IOException::class.java) { recovery.checkDeadline() }
    }

    @Test fun aMissingOpusPrerollGapWaitsWithoutInventingLossFromPtsOrder() {
        val timing = AudioPacketTiming("opus")
        configure(opus = true)
        mux.discardMediaForRecovery(2)
        for (pts in listOf(1_000_000L, 1_040_000L, 1_060_000L, 1_080_000L))
            assertNull(mux.holdRecoveryAudio(audio(pts).copy(payload = byteArrayOf(8, 1)), timing))
        assertEquals(RecoveryAudioCoverage.WAITING, mux.releaseRecoveryAudio(1_080_000, timing))
        assertNull(mux.pollLane(2, 1))
    }

    @Test fun aProvablyLostAacPrerollRestartsOnlyAudio() {
        val sender = StreamRecoverySender(73, mux, now::get)
        sender.config(1, 1, Wire.obj("codec" to "h264"))
        sender.config(2, 1, Wire.obj("codec" to "aac", "sampleRate" to 48000, "channels" to 2,
            "csd" to org.json.JSONArray().put("EZA=")))
        for (track in 1..2) { finishWrite(track); mux.ready(track, 1) }
        assertTrue(sender.ready())
        sender.packet(video(1_000_000, true)); finishWrite(1)
        sender.keyProved(1, 1_000_000); sender.completed(1, 1, 1_000_000)
        sender.request(2, 1)
        finishWrite(2); mux.ready(2, 2); sender.ready()
        sender.packet(audio(1_001_000).copy(payload = byteArrayOf(1)))
        assertEquals(3, finishWrite(2).generation)
        assertNull(mux.pollLane(1, 1))
        now.set(11_000_000_000)
        assertThrows(IOException::class.java) { sender.checkDeadline() }
    }
}

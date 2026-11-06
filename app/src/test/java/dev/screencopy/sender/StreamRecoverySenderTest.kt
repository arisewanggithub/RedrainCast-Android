package dev.screencopy.sender

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

class StreamRecoverySenderTest {
    @Test fun delayedKeyProofReleasesRetainedSourceAudioWithoutChangingGenerationOrPts() {
        for (delayMs in listOf(350L, 800L)) {
            val clock = AtomicLong(1_000_000_000)
            val budget = MediaBudget(true, clock::get, 1)
            val sender = StreamRecoverySender(73, budget, clock::get)
            sender.config(1, 1, Wire.obj("codec" to "h264"))
            sender.config(2, 1, audioConfig())
            for (track in 1..2) {
                budget.endWrite(checkNotNull(budget.pollLane(track, 1)))
                budget.ready(track, 1)
            }
            assertTrue(sender.ready())
            sender.packet(video(1_000_000, true))
            budget.endWrite(checkNotNull(budget.pollLane(1, 1)))
            val source = audio(1_000_000)
            sender.packet(source)
            clock.addAndGet(delayMs * 1_000_000)
            sender.keyProved(1, source.pts)
            val released = checkNotNull(budget.pollLane(2, 1))
            assertEquals(source.pts, released.pts)
            assertArrayEquals(source.payload, released.payload)
            budget.endWrite(released)
            assertNull("Decode transit alone must not request another generation", budget.pollLane(1, 1))
            sender.completed(1, source.pts, source.pts)
            clock.addAndGet(11_000_000_000)
            sender.checkDeadline()
        }
    }

    @Test fun delayedSourceAudioArrivalWaitsWithinTheOriginalRecoveryDeadline() {
        configure()
        recovery.packet(video(1_000_000, true))
        mux.endWrite(checkNotNull(mux.pollLane(1, 1)))
        now.addAndGet(350_000_000)
        recovery.keyProved(1, 1_000_000)
        now.addAndGet(450_000_000)
        recovery.checkDeadline()
        assertNull(mux.pollLane(1, 1))
        recovery.packet(audio(1_000_000))
        val packet = checkNotNull(mux.pollLane(2, 1))
        assertEquals(1_000_000L, packet.pts)
        mux.endWrite(packet)
        recovery.completed(1, 1_000_000, 1_000_000)
    }

    @Test fun anElevenHundredMillisecondProofRetriesOnceAfterRealHeldAudioPrefixEviction() {
        configure()
        recovery.packet(video(1_000_000, true))
        mux.endWrite(checkNotNull(mux.pollLane(1, 1)))
        repeat(56) { index ->
            now.set(1_000_000_000 + index * 20_000_000L)
            recovery.packet(audio(1_000_000 + index * 20_000L).copy(payload = ByteArray(3840) { index.toByte() }))
            assertTrue(mux.queuedSpanUs(2) <= 500_000)
            assertTrue(mux.queuedBytes() <= 26 * 3840)
        }
        assertEquals(2_100_000_000L, now.get())
        assertEquals(500_000L, mux.queuedSpanUs(2))
        assertEquals(26 * 3840, mux.queuedBytes())
        assertNull(mux.pollLane(1, 1))
        assertNull(mux.pollLane(2, 1))

        recovery.keyProved(1, 1_000_000)
        val retry = checkNotNull(mux.pollLane(1, 1))
        assertEquals(Wire.STREAM_CONFIG, retry.type)
        assertEquals(2, retry.generation)
        assertEquals(0, mux.queuedBytes())
        assertNull(mux.pollLane(1, 1))
        mux.endWrite(retry)
        mux.ready(1, 2)
        assertTrue(recovery.ready())

        val sourceKey = video(2_100_000, true)
        val sourceAudio = audio(sourceKey.pts).copy(payload = ByteArray(3840) { (it % 127).toByte() })
        recovery.packet(sourceKey)
        val key = checkNotNull(mux.pollLane(1, 1))
        assertEquals(2, key.generation)
        assertEquals(sourceKey.pts, key.pts)
        assertArrayEquals(sourceKey.payload, key.payload)
        mux.endWrite(key)
        recovery.packet(sourceAudio)
        now.addAndGet(350_000_000)
        recovery.keyProved(2, key.pts)
        val released = checkNotNull(mux.pollLane(2, 1))
        assertEquals(1, released.generation)
        assertEquals(sourceAudio.pts, released.pts)
        assertArrayEquals(sourceAudio.payload, released.payload)
        mux.endWrite(released)
        recovery.completed(2, key.pts, released.pts)
        assertNull(mux.pollLane(1, 1))
        assertNull(mux.pollLane(2, 1))
        now.set(11_000_000_000)
        recovery.checkDeadline()
    }

    @Test fun actualSelectedOpusPrerollEvictionRetriesWithAFreshAttemptWindow() {
        configureOpus()
        recovery.packet(video(1_080_000, true))
        mux.endWrite(checkNotNull(mux.pollLane(1, 1)))
        repeat(56) { index ->
            now.set(1_080_000_000 + index * 20_000_000L)
            recovery.packet(audio(1_000_000 + index * 20_000L).copy(payload = byteArrayOf(8, index.toByte())))
        }
        assertEquals(500_000L, mux.queuedSpanUs(2))
        assertEquals(52, mux.queuedBytes())
        assertNull(mux.pollLane(1, 1))
        recovery.keyProved(1, 1_080_000)
        val retry = mux.pollLane(1, 1)
        assertNotNull("Actual removal of the selected key's Opus preroll must retry", retry)
        assertEquals(2, checkNotNull(retry).generation)
        assertNull(mux.pollLane(1, 1))
        mux.endWrite(retry)
        mux.ready(1, 2)
        assertFalse("A retry must collect fresh compressed preroll", recovery.ready())
        val source = (0..4).map { index ->
            now.set(2_180_000_000 + index * 20_000_000L)
            audio(2_180_000 + index * 20_000L).copy(payload = byteArrayOf(8, index.toByte()))
                .also(recovery::packet)
        }
        assertTrue(recovery.retryKeyFrame())
        recovery.packet(video(2_260_000, true))
        val key = checkNotNull(mux.pollLane(1, 1))
        assertEquals(2, key.generation)
        assertEquals(2_260_000L, key.pts)
        mux.endWrite(key)
        now.addAndGet(350_000_000)
        recovery.keyProved(2, key.pts)
        source.forEach { original ->
            val released = checkNotNull(mux.pollLane(2, 1))
            assertEquals(original.pts, released.pts)
            assertArrayEquals(original.payload, released.payload)
            mux.endWrite(released)
        }
        recovery.completed(2, key.pts, key.pts)
        assertNull(mux.pollLane(1, 1))
    }

    @Test fun evictionOfOnlyObsoleteOpusPacketsDoesNotLoseTheSelectedWindow() {
        configureOpus()
        now.set(1_600_000_000)
        recovery.packet(video(1_600_000, true))
        mux.endWrite(checkNotNull(mux.pollLane(1, 1)))
        val source = (0..51).map { index ->
            val pts = 1_000_000 + index * 20_000L
            now.set(maxOf(now.get(), pts * 1000))
            audio(pts).copy(payload = byteArrayOf(8, index.toByte()))
                .also(recovery::packet)
        }
        assertEquals(500_000L, mux.queuedSpanUs(2))
        assertEquals(52, mux.queuedBytes())
        now.addAndGet(350_000_000)
        recovery.keyProved(1, 1_600_000)
        source.takeLast(26).forEach { original ->
            val released = checkNotNull(mux.pollLane(2, 1))
            assertEquals(original.pts, released.pts)
            assertArrayEquals(original.payload, released.payload)
            mux.endWrite(released)
        }
        recovery.completed(1, 1_600_000, 1_600_000)
        assertNull(mux.pollLane(1, 1))
        assertNull(mux.pollLane(2, 1))
    }

    @Test fun equalAndReversedOpusPtsRetainTheirOriginalDecodeOrder() {
        configureOpus()
        recovery.packet(video(1_080_000, true))
        mux.endWrite(checkNotNull(mux.pollLane(1, 1)))
        val source = listOf(1_000_000L, 1_020_000L, 1_020_000L, 1_019_551L, 1_040_000L, 1_060_000L, 1_080_000L)
            .mapIndexed { index, pts ->
                audio(pts).copy(payload = byteArrayOf(8, index.toByte())).also(recovery::packet)
            }
        now.addAndGet(350_000_000)
        recovery.keyProved(1, 1_080_000)
        source.forEach { original ->
            val released = checkNotNull(mux.pollLane(2, 1))
            assertEquals(original.pts, released.pts)
            assertArrayEquals(original.payload, released.payload)
            mux.endWrite(released)
        }
        recovery.completed(1, 1_080_000, 1_080_000)
        assertNull(mux.pollLane(1, 1))
    }

    @Test fun laterOpusTimestampCoverageCannotRestoreAnEvictedDecodeChainButRetirementClearsEvidence() {
        val budget = MediaBudget(true, now::get, 1)
        val timing = org.screencopy.receiver.protocol.AudioPacketTiming("opus")
        val key = 1_080_000L
        budget.discardMediaForRecovery()
        for (track in 1..2) {
            budget.offer(Wire.config(73, track, 1, Wire.obj("codec" to if (track == 1) "h264" else "opus")))
            budget.endWrite(checkNotNull(budget.pollLane(track, 1)))
            budget.ready(track, 1)
        }
        repeat(56) { index ->
            assertNull(budget.holdRecoveryAudio(
                audio(1_000_000 + index * 20_000L).copy(payload = byteArrayOf(8, index.toByte())), timing, key))
        }
        val overlap = (0..4).map { index ->
            audio(1_000_000 + index * 20_000L).copy(payload = byteArrayOf(8, (index + 60).toByte()))
        }
        overlap.forEach { assertNull(budget.holdRecoveryAudio(it, timing, key)) }
        assertEquals(80_000L, budget.queuedSpanUs(2))
        assertEquals(10, budget.queuedBytes())
        assertEquals(RecoveryAudioCoverage.LOST, budget.releaseRecoveryAudio(key, timing))

        budget.discardMediaForRecovery()
        budget.offer(Wire.config(73, 1, 2, Wire.obj("codec" to "h264")))
        val next = checkNotNull(budget.pollLane(1, 1))
        assertEquals(2, next.generation)
        budget.endWrite(next)
        budget.ready(1, 2)
        overlap.forEach { assertNull(budget.holdRecoveryAudio(it, timing, key)) }
        assertEquals(RecoveryAudioCoverage.READY, budget.releaseRecoveryAudio(key, timing))
        overlap.forEach { original ->
            val released = checkNotNull(budget.pollLane(2, 1))
            assertEquals(original.pts, released.pts)
            assertArrayEquals(original.payload, released.payload)
            budget.endWrite(released)
        }
        assertNull(budget.pollLane(2, 1))
    }

    @Test fun absentPcmWaitsInOneGenerationUntilTheExactOriginalTenSecondDeadline() {
        configure()
        recovery.packet(video(1_000_000, true))
        val key = checkNotNull(mux.pollLane(1, 1))
        assertEquals(1, key.generation)
        mux.endWrite(key)
        now.addAndGet(350_000_000)
        recovery.keyProved(1, key.pts)
        for (elapsedMs in listOf(350L, 800L, 1100L, 5000L, 9999L)) {
            now.set(1_000_000_000 + elapsedMs * 1_000_000)
            recovery.checkDeadline()
            assertEquals(0, mux.queuedBytes())
            assertNull(mux.pollLane(1, 1))
            assertNull(mux.pollLane(2, 1))
        }
        now.set(10_999_999_999)
        recovery.checkDeadline()
        now.incrementAndGet()
        val error = assertThrows(IOException::class.java) { recovery.checkDeadline() }
        assertTrue(error.message!!.startsWith("TIMEOUT:"))
        assertNull(mux.pollLane(1, 1))
        assertNull(mux.pollLane(2, 1))
    }

    @Test fun delayedVideoOnlyProofDoesNotExtendOrBypassTheTenSecondDeadline() {
        val budget = MediaBudget(false, now::get, 1)
        val sender = StreamRecoverySender(73, budget, now::get, withAudio = false)
        sender.config(1, 1, Wire.obj("codec" to "h264"))
        budget.endWrite(checkNotNull(budget.pollLane(1, 1)))
        budget.ready(1, 1)
        assertTrue(sender.ready())
        sender.packet(video(1_000_000, true))
        budget.endWrite(checkNotNull(budget.pollLane(1, 1)))
        now.set(11_000_000_000)
        assertThrows(IOException::class.java) { sender.keyProved(1, 1_000_000) }
        assertThrows(IOException::class.java) { sender.completed(1, 1_000_000, null) }
    }

    @Test fun missingRawSourceWindowRetriesInsteadOfOpeningAudioWithLateSamples() {
        configure()
        recovery.packet(video(1_000_000, true))
        mux.endWrite(checkNotNull(mux.pollLane(1, 1)))
        recovery.packet(audio(1_250_001))
        recovery.keyProved(1, 1_000_000)
        assertNull(mux.pollLane(2, 1))
        assertEquals(2, checkNotNull(mux.pollLane(1, 1)).generation)
    }

    @Test fun delayedProofStillRequiresCompleteCompressedPrerollAndExactOriginalPackets() {
        val budget = MediaBudget(true, now::get, 1)
        val sender = StreamRecoverySender(73, budget, now::get)
        sender.config(1, 1, Wire.obj("codec" to "h264"))
        sender.config(2, 1, Wire.obj("codec" to "opus", "sampleRate" to 48000, "channels" to 2))
        for (track in 1..2) {
            budget.endWrite(checkNotNull(budget.pollLane(track, 1)))
            budget.ready(track, 1)
        }
        now.set(1_080_000_000)
        assertTrue(sender.ready())
        val keyPts = 1_080_000L
        sender.packet(video(keyPts, true))
        budget.endWrite(checkNotNull(budget.pollLane(1, 1)))
        val source = (0..4).map { audio(1_000_000L + it * 20_000).copy(payload = byteArrayOf(8, it.toByte())) }
        source.forEach(sender::packet)
        now.addAndGet(800_000_000)
        sender.keyProved(1, keyPts)
        source.forEach { original ->
            val released = checkNotNull(budget.pollLane(2, 1))
            assertEquals(original.pts, released.pts)
            assertArrayEquals(original.payload, released.payload)
            budget.endWrite(released)
        }
        sender.completed(1, keyPts, keyPts)
        assertNull(budget.pollLane(1, 1))
    }

    @Test fun selectedKeyStillMustBeSourceFreshEvenThoughProofMayArriveLate() {
        configure()
        now.set(1_800_000_000)
        recovery.packet(video(1_549_999, true))
        assertNull(mux.pollLane(1, 1))
        recovery.packet(video(1_550_000, true))
        assertEquals(1_550_000L, checkNotNull(mux.pollLane(1, 1)).pts)
    }

    private val now = AtomicLong(1_000_000_000L)
    private val mux = MediaBudget(true, now::get, 1)
    private val recovery = StreamRecoverySender(73, mux, now::get)
    private fun video(pts: Long, key: Boolean = false, rotation: Int = 0, generation: Int = 1) =
        Wire.Message(Wire.VIDEO_FRAME, (if (key) 1 else 0) or (rotation / 90 shl 1), 73, 1, generation, pts,
            byteArrayOf(0, 0, 0, 1, if (key) 0x65 else 0x41, 0x88.toByte()))
    private fun audio(pts: Long) = Wire.Message(Wire.AUDIO_PACKET, 0, 73, 2, 1, pts, ByteArray(4))
    private fun audioConfig() = Wire.obj("codec" to "raw", "sampleRate" to 48000, "channels" to 2)

    private fun configure() {
        recovery.config(1, 1, Wire.obj("codec" to "h264", "width" to 720, "height" to 1280, "csd" to org.json.JSONArray().put("AAAAAWc=")))
        recovery.config(2, 1, audioConfig())
        for (track in 1..2) {
            val config = checkNotNull(mux.pollLane(track, 1))
            mux.endWrite(config)
            mux.ready(track, 1)
        }
        assertTrue(recovery.ready())
        assertFalse(recovery.ready())
    }

    private fun configureOpus() {
        recovery.config(1, 1, Wire.obj("codec" to "h264"))
        recovery.config(2, 1, Wire.obj("codec" to "opus", "sampleRate" to 48000, "channels" to 2))
        for (track in 1..2) {
            mux.endWrite(checkNotNull(mux.pollLane(track, 1)))
            mux.ready(track, 1)
        }
        now.addAndGet(80_000_000)
        assertTrue(recovery.ready())
    }

    private fun activate() {
        configure()
        recovery.packet(video(1_000_000, key = true))
        val first = checkNotNull(mux.pollLane(1, 1))
        mux.endWrite(first)
        recovery.packet(audio(1_000_000))
        recovery.keyProved(1, 1_000_000)
        mux.pollLane(2, 1)?.let(mux::endWrite)
        recovery.completed(1, 1_000_000, 1_000_000)
    }

    @Test fun startupWaitsForConfiguredOutputsAndAFreshKeyframeBeforeEitherTrack() {
        recovery.config(1, 1, Wire.obj("codec" to "h264"))
        recovery.config(2, 1, audioConfig())
        recovery.packet(video(800_000, key = true))
        recovery.packet(audio(800_000))
        assertEquals(4, mux.queuedBytes())
        for (track in 1..2) {
            val config = checkNotNull(mux.pollLane(track, 1))
            mux.endWrite(config); mux.ready(track, 1)
        }
        assertTrue(recovery.ready())
        recovery.packet(video(900_000, key = true))
        recovery.packet(video(1_000_000))
        recovery.packet(audio(1_000_000))
        assertNull(mux.pollLane(1, 1))
        assertNull(mux.pollLane(2, 1))
        recovery.packet(video(1_000_001, key = true, rotation = 270))
        recovery.packet(audio(1_020_000))
        val key = checkNotNull(mux.pollLane(1, 1))
        assertEquals(1_000_001L, key.pts)
        assertEquals(7, key.flags)
        assertEquals(1, key.generation)
        recovery.keyProved(1, key.pts)
        assertNotNull(mux.pollLane(2, 1))
    }

    @Test fun backlogDiscardsAWholeObsoleteChainAndRestartsOnlyAtFreshKeyAfterReady() {
        activate()
        recovery.packet(video(1_100_000))
        recovery.packet(audio(1_100_000))
        recovery.packet(video(1_600_000))
        assertTrue(mux.queuedBytes() > 0)
        recovery.packet(video(1_600_001))
        assertEquals(0, mux.queuedBytes())
        val reset = checkNotNull(mux.pollLane(1, 1))
        assertEquals(Wire.STREAM_CONFIG, reset.type)
        assertEquals(2, reset.generation)
        assertEquals(720, reset.json().getInt("width"))
        assertEquals("h264", reset.json().getString("codec"))
        recovery.packet(video(1_700_000, key = true))
        recovery.packet(audio(1_700_000))
        assertNull(mux.pollLane(1, 1))
        now.set(1_800_000_000L)
        mux.endWrite(reset); mux.ready(1, 2)
        assertTrue(recovery.ready())
        recovery.packet(video(1_750_000, key = true))
        recovery.packet(video(1_800_000))
        assertNull(mux.pollLane(1, 1))
        recovery.packet(video(1_800_001, key = true, rotation = 90))
        recovery.packet(video(1_820_000))
        recovery.packet(audio(1_820_000))
        val key = checkNotNull(mux.pollLane(1, 1))
        assertEquals(2, key.generation)
        assertEquals(3, key.flags)
        assertEquals(1_800_001L, key.pts)
        assertEquals(2, mux.pollLane(1, 1)?.generation)
        recovery.keyProved(2, key.pts)
        assertEquals(1, mux.pollLane(2, 1)?.generation)
    }

    @Test fun receiverRequestsAreGenerationBoundAndCoalescedWhileRecovering() {
        activate()
        recovery.request(1)
        recovery.request(1)
        val reset = checkNotNull(mux.pollLane(1, 1))
        assertEquals(2, reset.generation)
        assertNull(mux.pollLane(1, 1))
        assertThrows(IOException::class.java) { recovery.request(3) }
        now.addAndGet(500_000_000L)
        mux.endWrite(reset); mux.ready(1, 2)
        assertTrue(recovery.ready())
        recovery.request(1)
        assertNull(mux.pollLane(1, 1))
    }

    @Test fun requestsAreRetriedAtABoundedCadenceAndMissingKeyframesFailExplicitly() {
        configure()
        assertFalse(recovery.retryKeyFrame())
        now.addAndGet(499_999_999)
        assertFalse(recovery.retryKeyFrame())
        now.incrementAndGet()
        assertTrue(recovery.retryKeyFrame())
        assertFalse(recovery.retryKeyFrame())
        now.set(11_000_000_001L)
        val error = assertThrows(IOException::class.java) { recovery.checkDeadline() }
        assertTrue(error.message!!.startsWith("TIMEOUT:"))
    }

    @Test fun malformedOrBackwardsCapturePacketsAreRejectedEvenWhileDiscarding() {
        configure()
        recovery.packet(video(900_000))
        assertThrows(IOException::class.java) { recovery.packet(video(899_999)) }
        assertThrows(IOException::class.java) { recovery.packet(video(-1)) }
        assertThrows(IOException::class.java) { recovery.packet(video(1_100_000, generation = 2)) }
        assertThrows(IOException::class.java) { recovery.packet(video(1_200_000).copy(flags = 8)) }
        assertThrows(IOException::class.java) { recovery.packet(video(1_200_000).copy(session = 74)) }
    }

    @Test fun proofsAreGenerationAndKeyScopedAndMatchingDuplicatesAreIdempotent() {
        activate()
        recovery.keyProved(1, 1_000_000)
        recovery.completed(1, 1_000_000, 1_000_000)
        assertThrows(IOException::class.java) { recovery.keyProved(2, 1_000_000) }
        assertThrows(IOException::class.java) { recovery.completed(1, 1_000_000, 1_000_001) }
        assertThrows(IOException::class.java) { recovery.keyProved(1, 1_000_001) }
        recovery.request(1)
        recovery.keyProved(1, 1_000_000)
        recovery.completed(1, 1_000_000, 1_000_000)
        assertEquals(2, mux.pollLane(1, 1)?.generation)
    }

    @Test fun resetsReadinessAndKeyProofNeverExtendTheFirstUnresolvedDeadline() {
        configure()
        recovery.packet(video(1_000_000, key = true))
        now.addAndGet(251_000_000)
        recovery.keyProved(1, 1_000_000)
        recovery.request(1) // An actual recovery request resets generation, never the deadline.
        val config = checkNotNull(mux.pollLane(1, 1))
        assertEquals(2, config.generation)
        mux.endWrite(config)
        now.set(10_000_000_000)
        mux.ready(1, 2)
        assertTrue(recovery.ready())
        recovery.packet(video(10_000_000, key = true))
        recovery.packet(audio(10_000_000))
        recovery.keyProved(2, 10_000_000)
        now.set(11_000_000_000)
        assertThrows(IOException::class.java) { recovery.checkDeadline() }
    }

    @Test fun aKeyFlagWithoutRandomAccessOrWithChangedCsdIsFatal() {
        configure()
        assertThrows(org.screencopy.receiver.protocol.ProtocolException::class.java) {
            recovery.packet(video(1_000_000, key = true).copy(payload = byteArrayOf(0, 0, 1, 0x41, 1)))
        }
        assertThrows(org.screencopy.receiver.protocol.ProtocolException::class.java) {
            recovery.packet(video(1_000_001, key = true).copy(
                payload = byteArrayOf(0, 0, 1, 0x67, 2, 0, 0, 1, 0x65, 1)))
        }
    }

    @Test fun videoOnlyCompletesWithoutAudioAndWithoutChangingPictureTimestamps() {
        val budget = MediaBudget(false, now::get, 1)
        val sender = StreamRecoverySender(73, budget, now::get, withAudio = false)
        sender.config(1, 1, Wire.obj("codec" to "h264"))
        val config = checkNotNull(budget.pollLane(1, 1))
        budget.endWrite(config)
        budget.ready(1, 1)
        assertTrue(sender.ready())
        sender.packet(video(1_000_000, key = true, rotation = 180))
        val key = checkNotNull(budget.pollLane(1, 1))
        assertEquals(1_000_000L, key.pts)
        assertEquals(5, key.flags)
        now.addAndGet(800_000_000)
        sender.keyProved(1, key.pts)
        now.addAndGet(350_000_000)
        sender.completed(1, key.pts, null)
        now.addAndGet(30_000_000_000)
        sender.checkDeadline()
    }

    @Test fun retainedCompressedPrerollIsOrderedDurationAwareAndNeverRetimed() {
        val budget = MediaBudget(true, now::get, 1)
        budget.discardMediaForRecovery()
        for (track in 1..2) {
            budget.offer(Wire.config(73, track, 1, Wire.obj("codec" to if (track == 1) "h264" else "opus")))
            budget.endWrite(checkNotNull(budget.pollLane(track, 1)))
            budget.ready(track, 1)
        }
        val timing = org.screencopy.receiver.protocol.AudioPacketTiming("opus")
        val original = (0..4).map {
            audio(920_000L + it * 20_000).copy(payload = byteArrayOf(8, it.toByte()))
        }
        original.forEach { assertNull(budget.holdRecoveryAudio(it, timing)) }
        assertNull(budget.pollLane(2, 1))
        assertEquals(RecoveryAudioCoverage.READY, budget.releaseRecoveryAudio(1_000_000, timing))
        original.forEach {
            val packet = checkNotNull(budget.pollLane(2, 1))
            assertEquals(it.pts, packet.pts)
            assertArrayEquals(it.payload, packet.payload)
            budget.endWrite(packet)
        }
        assertNull(budget.pollLane(2, 1))
    }

    @Test fun missingCompressedPrerollDoesNotOpenTheAudioGate() {
        val budget = MediaBudget(true, now::get, 1)
        budget.discardMediaForRecovery()
        for (track in 1..2) {
            budget.offer(Wire.config(73, track, 1, Wire.obj("codec" to if (track == 1) "h264" else "opus")))
            budget.endWrite(checkNotNull(budget.pollLane(track, 1)))
            budget.ready(track, 1)
        }
        val timing = org.screencopy.receiver.protocol.AudioPacketTiming("opus")
        for (pts in listOf(920_000L, 960_000L, 980_000L, 1_000_000L))
            assertNull(budget.holdRecoveryAudio(audio(pts).copy(payload = byteArrayOf(8, 1)), timing))
        assertEquals(RecoveryAudioCoverage.WAITING, budget.releaseRecoveryAudio(1_000_000, timing))
        assertNull(budget.pollLane(2, 1))
    }

    @Test fun anApparentOpusPrefixGapWaitsRatherThanResettingTheLiveGeneration() {
        val budget = MediaBudget(true, now::get, 1)
        val sender = StreamRecoverySender(73, budget, now::get)
        sender.config(1, 1, Wire.obj("codec" to "h264"))
        sender.config(2, 1, Wire.obj("codec" to "opus", "sampleRate" to 48000, "channels" to 2))
        for (track in 1..2) {
            budget.endWrite(checkNotNull(budget.pollLane(track, 1)))
            budget.ready(track, 1)
        }
        now.set(1_080_000_000)
        assertTrue(sender.ready())
        sender.packet(video(1_080_000, true))
        budget.endWrite(checkNotNull(budget.pollLane(1, 1)))
        sender.packet(audio(1_020_000).copy(payload = byteArrayOf(8, 1)))
        now.addAndGet(800_000_000)
        sender.keyProved(1, 1_080_000)
        assertNull(budget.pollLane(1, 1))
        assertNull(budget.pollLane(2, 1))
        sender.packet(audio(1_000_000).copy(payload = byteArrayOf(8, 2)))
        assertNull(budget.pollLane(1, 1))
        now.set(11_000_000_000)
        assertThrows(IOException::class.java) { sender.checkDeadline() }
    }

    @Test fun aMonotonicAacPrerollGapProvesThatTheSourceWindowWasLost() {
        val budget = MediaBudget(true, now::get, 1)
        budget.discardMediaForRecovery()
        for (track in 1..2) {
            budget.offer(Wire.config(73, track, 1, Wire.obj("codec" to if (track == 1) "h264" else "aac")))
            budget.endWrite(checkNotNull(budget.pollLane(track, 1)))
            budget.ready(track, 1)
        }
        val timing = org.screencopy.receiver.protocol.AudioPacketTiming("aac")
        for (pts in listOf(978_666L, 1_001_000L))
            assertNull(budget.holdRecoveryAudio(audio(pts).copy(payload = byteArrayOf(1)), timing))
        assertEquals(RecoveryAudioCoverage.LOST, budget.releaseRecoveryAudio(1_000_000, timing))
        assertNull(budget.pollLane(2, 1))
    }
}

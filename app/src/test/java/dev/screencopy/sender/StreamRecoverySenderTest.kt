package dev.screencopy.sender

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

class StreamRecoverySenderTest {
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
        recovery.keyProved(1, 1_000_000) // Stale proof requests a new generation, not a new deadline.
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
        sender.keyProved(1, key.pts)
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
        assertTrue(budget.releaseRecoveryAudio(1_000_000, timing))
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
        assertFalse(budget.releaseRecoveryAudio(1_000_000, timing))
        assertNull(budget.pollLane(2, 1))
    }
}

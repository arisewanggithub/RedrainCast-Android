package dev.screencopy.sender

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class MediaBudgetTest {
    @Test fun orderlyTerminalWaitsForBothSelectedMediaFramesAndDropsUnsentFrames() {
        val mux = MediaBudget(true)
        val started = (1..2).map { track ->
            configure(mux, track, 1)
            mux.pollLane(track, 1)?.let(mux::endWrite)
            mux.ready(track, 1)
            frame(track, 1, 0, 10).also { mux.offer(it) }
        }
        for (message in started) {
            assertSame(message, mux.pollLane(message.track, 1))
            mux.beginWrite(message)
            mux.offer(frame(message.track, 1, 1, 20))
        }
        val terminal = Wire.control(Wire.SESSION_END, 1, Wire.obj("reason" to "user"))
        mux.finish(terminal, drainStartedMedia = true)
        assertEquals(0, mux.queuedBytes())
        assertNull(mux.pollLane(0, 1))
        mux.endWrite(started[0])
        assertNull("Completing video cannot cancel started audio", mux.pollLane(0, 1))
        mux.endWrite(started[1])
        assertSame(terminal, mux.pollLane(0, 1))
        assertNull(mux.pollLane(1, 1))
        assertNull(mux.pollLane(2, 1))
        assertThrows(SessionCancelledException::class.java) { mux.offer(frame(1, 1, 2, 1)) }
    }

    private fun frame(track: Int, generation: Int, pts: Long, size: Int) =
        Wire.Message(if (track == 1) Wire.VIDEO_FRAME else Wire.AUDIO_PACKET, 0, 1, track, generation, pts, ByteArray(size))
    private fun configure(mux: MediaBudget, track: Int, generation: Int) =
        mux.offer(Wire.config(1, track, generation, Wire.obj("codec" to if (track == 1) "h264" else "opus")))

    @Test fun independentWriterCompletionCannotEraseAnotherTracksCongestionEvidence() {
        for (blockedTrack in 1..2) {
            val now = AtomicLong(1_000_000_000L)
            val mux = MediaBudget(true, now::get, 1)
            for (track in 1..2) {
                configure(mux, track, 1)
                val config = checkNotNull(mux.pollLane(track, 1))
                mux.endWrite(config)
                mux.ready(track, 1)
            }
            val blocked = frame(blockedTrack, 1, 0, 17)
            mux.offer(blocked)
            assertSame(blocked, mux.pollLane(blockedTrack, 1))
            val otherTrack = 3 - blockedTrack
            val other = frame(otherTrack, 1, 0, 3)
            mux.offer(other)
            assertSame(other, mux.pollLane(otherTrack, 1))
            now.set(1_010_000_000L)
            mux.beginWrite(blocked)
            now.set(1_020_000_000L)
            mux.beginWrite(other)
            now.set(1_030_000_000L)
            mux.endWrite(other)
            val ping = Wire.control(Wire.PING, 1, Wire.obj("nonce" to 7))
            mux.offer(ping)
            assertSame(ping, mux.pollLane(0, 1))
            now.set(1_040_000_000L)
            mux.beginWrite(ping)
            now.set(1_050_000_000L)
            mux.endWrite(ping)
            mux.offer(frame(blockedTrack, 1, 100_000, 1))
            mux.offer(frame(blockedTrack, 1, 600_000, 1))
            now.set(1_200_000_000L)
            val failure = assertThrows(SenderCongestionException::class.java) {
                mux.offer(frame(blockedTrack, 1, 600_001, 1))
            }.snapshot
            assertEquals(SenderLimit.PTS_SPAN, failure.limit)
            assertEquals(500_001L, failure.attemptedSpanUs)
            assertEquals(if (blockedTrack == 1) SenderQueue.VIDEO else SenderQueue.AUDIO, failure.writerQueue)
            assertEquals(17, failure.writerBytes)
            assertEquals(190L, failure.writerMs)
            assertEquals(200L, failure.writerAdmittedAgeMs)
            assertEquals(0L, failure.lastWriteMs)
            mux.endWrite(blocked)
        }
    }

    @Test fun allTracksMustBeReadyAndThenFair() {
        val mux = MediaBudget(true)
        configure(mux, 1, 1); configure(mux, 2, 1)
        assertEquals(Wire.STREAM_CONFIG, mux.poll(1)?.type); assertEquals(Wire.STREAM_CONFIG, mux.poll(1)?.type)
        mux.offer(frame(1, 1, 1, 10)); mux.offer(frame(1, 1, 2, 10)); mux.offer(frame(2, 1, 1, 10))
        mux.ready(1, 1); assertNull(mux.poll(1)); mux.ready(2, 1)
        assertEquals(1, mux.poll(1)?.track); assertEquals(2, mux.poll(1)?.track); assertEquals(1, mux.poll(1)?.track)
        assertEquals(0, mux.queuedBytes())
    }
    @Test fun rotationDrainsOldGenerationAndKeepsAudioReady() {
        val mux = MediaBudget(true)
        configure(mux, 1, 1); configure(mux, 2, 1)
        mux.poll(1); mux.poll(1); mux.ready(1, 1); mux.ready(2, 1)
        mux.offer(frame(1, 1, 10, 5)); configure(mux, 1, 2)
        mux.offer(frame(1, 2, 20, 5)); mux.offer(frame(2, 1, 20, 5))
        assertEquals(Wire.VIDEO_FRAME, mux.poll(1)?.type); assertEquals(Wire.AUDIO_PACKET, mux.poll(1)?.type)
        val config = checkNotNull(mux.poll(1))
        assertEquals(2, config.generation); assertEquals(Wire.STREAM_CONFIG, config.type)
        assertNull(mux.poll(1)); mux.ready(1, 2); assertEquals(2, mux.poll(1)?.generation)
        assertTrue(mux.allReady()); assertThrows(IOException::class.java) { mux.ready(1, 1) }
    }
    @Test fun exactBytesAndDurationLimits() {
        val mux = MediaBudget(false)
        configure(mux, 1, 1); mux.poll(1); mux.ready(1, 1)
        mux.offer(frame(1, 1, 0, 4 * 1024 * 1024)); mux.offer(frame(1, 1, 500_000, 4 * 1024 * 1024))
        assertEquals(8 * 1024 * 1024, mux.queuedBytes())
        assertThrows(IOException::class.java) { mux.offer(frame(1, 1, 500_000, 1)) }
        val duration = MediaBudget(false)
        configure(duration, 1, 1); duration.poll(1)
        duration.offer(frame(1, 1, 0, 1)); duration.offer(frame(1, 1, 500_000, 1))
        assertThrows(IOException::class.java) { duration.offer(frame(1, 1, 500_001, 1)) }
    }
    @Test fun exactTrackByteBudgetsCannotConsumeEachOthersCapacity() {
        val mux = MediaBudget(true, System::nanoTime, 1)
        for (track in 1..2) {
            configure(mux, track, 1)
            mux.pollLane(track, 1); mux.endWrite()
            mux.ready(track, 1)
        }
        repeat(2) { index -> mux.offer(frame(1, 1, index.toLong(), 4 * 1024 * 1024)) }
        repeat(2) { index -> mux.offer(frame(2, 1, index.toLong(), 256 * 1024)) }
        assertEquals(8 * 1024 * 1024 + 512 * 1024, mux.queuedBytes())
        for ((track, bytes) in listOf(1 to 8 * 1024 * 1024, 2 to 512 * 1024)) {
            val pressure = checkNotNull(mux.tryOfferMedia(frame(track, 1, 2, 1)))
            assertEquals(SenderLimit.MEDIA_BYTES, pressure.limit)
            assertEquals(if (track == 1) SenderQueue.VIDEO else SenderQueue.AUDIO, pressure.queue)
            assertEquals(bytes, pressure.bytes)
            assertEquals(bytes, pressure.byteCap)
        }
        mux.discardMediaForRecovery(2)
        assertEquals(8 * 1024 * 1024, mux.queuedBytes())
        assertNull(mux.tryOfferMedia(frame(2, 1, 2, 4)))
    }

    @Test fun restartWaitsForPreviousVideoReadinessAndWritesWithoutBlockingAudio() {
        val mux = MediaBudget(true, System::nanoTime, 1_000_000L)
        configure(mux, 1, 1); configure(mux, 2, 1)
        mux.poll(1); mux.endWrite(); mux.poll(1); mux.endWrite()
        mux.ready(2, 1)
        val old = frame(1, 1, 1_000_000, 187755)
        mux.offer(old)
        val executor = Executors.newSingleThreadExecutor()
        val waiting = CountDownLatch(1)
        try {
            val drained = executor.submit { waiting.countDown(); mux.awaitVideoDrain() }
            assertTrue(waiting.await(2, TimeUnit.SECONDS))
            assertNull(mux.poll(1))
            assertThrows(java.util.concurrent.TimeoutException::class.java) { drained.get(20, TimeUnit.MILLISECONDS) }
            mux.ready(1, 1)
            assertSame(old, mux.poll(1))
            assertThrows(java.util.concurrent.TimeoutException::class.java) { drained.get(20, TimeUnit.MILLISECONDS) }
            mux.beginWrite(old)
            assertThrows(java.util.concurrent.TimeoutException::class.java) { drained.get(20, TimeUnit.MILLISECONDS) }
            mux.endWrite()
            drained.get(2, TimeUnit.SECONDS)

            val audio = frame(2, 1, 1_000_000, 4)
            mux.offer(audio)
            mux.awaitVideoDrain()
            assertSame(audio, mux.poll(1)); mux.endWrite()
            configure(mux, 1, 2); mux.offer(frame(1, 2, 1_726_606, 88074))
            assertEquals(0L, mux.queuedSpanUs(1))
            assertEquals(88074, mux.queuedBytes())
            assertEquals(2, mux.poll(1)?.generation); mux.endWrite()
            assertNull(mux.poll(1)); mux.ready(1, 2)
            assertEquals(1_726_606L, mux.poll(1)?.pts)
        } finally { executor.shutdownNow() }
    }
    @Test fun rotationGapStillCountsTowardTheUnchangedWholeTrackSpanLimitWithoutADrain() {
        val mux = MediaBudget(false, System::nanoTime, 1_000_000L)
        configure(mux, 1, 1); mux.poll(1); mux.ready(1, 1)
        val old = frame(1, 1, 1_000_000, 187755)
        val resumed = frame(1, 2, 1_726_606, 88074)
        mux.offer(old); configure(mux, 1, 2)
        val error = assertThrows(SenderCongestionException::class.java) { mux.offer(resumed) }
        assertEquals(SenderLimit.PTS_SPAN, error.snapshot.limit)
        assertEquals(726_606L, error.snapshot.attemptedSpanUs)
        assertEquals(187755, mux.queuedBytes())
        assertSame(old, mux.poll(1))
        assertEquals(2, mux.poll(1)?.generation)
        assertNull(mux.poll(1))
        mux.ready(1, 2)
        mux.offer(resumed)
        assertEquals(0L, mux.queuedSpanUs(1))
    }
    @Test fun stoppingWakesAnEncoderRestartWaitingForReadiness() {
        val mux = MediaBudget(false)
        configure(mux, 1, 1); mux.poll(1); mux.endWrite()
        val executor = Executors.newSingleThreadExecutor()
        val waiting = CountDownLatch(1)
        try {
            val drained = executor.submit { waiting.countDown(); mux.awaitVideoDrain() }
            assertTrue(waiting.await(2, TimeUnit.SECONDS))
            mux.finish(Wire.control(Wire.SESSION_END, 1, Wire.obj("reason" to "test")))
            val error = assertThrows(java.util.concurrent.ExecutionException::class.java) {
                drained.get(2, TimeUnit.SECONDS)
            }
            assertTrue(error.cause is SessionCancelledException)
        } finally { executor.shutdownNow() }
    }
    @Test fun encoderRestartDrainFailsExplicitlyAfterTenSecondsWithoutDiscardingMedia() {
        val mux = MediaBudget(false)
        configure(mux, 1, 1); mux.poll(1); mux.endWrite(); mux.ready(1, 1)
        val old = frame(1, 1, 1_000_000, 10)
        mux.offer(old)
        val started = System.nanoTime()
        val error = assertThrows(IOException::class.java) { mux.awaitVideoDrain() }
        assertTrue(System.nanoTime() - started >= TimeUnit.SECONDS.toNanos(10))
        assertTrue(error.message!!.startsWith("CONGESTION:"))
        assertEquals(10, mux.queuedBytes())
        assertSame(old, mux.poll(1))
    }
    @Test fun interruptingAnEncoderRestartPreservesQueuedMedia() {
        val mux = MediaBudget(false)
        configure(mux, 1, 1); mux.poll(1); mux.endWrite(); mux.ready(1, 1)
        val old = frame(1, 1, 1_000_000, 10)
        mux.offer(old)
        val waiting = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val thread = Thread {
            waiting.countDown()
            try { mux.awaitVideoDrain() }
            catch (e: InterruptedException) { interrupted.countDown() }
        }
        thread.start()
        try {
            assertTrue(waiting.await(2, TimeUnit.SECONDS))
            thread.interrupt()
            assertTrue(interrupted.await(2, TimeUnit.SECONDS))
            assertSame(old, mux.poll(1))
            assertEquals(0, mux.queuedBytes())
        } finally { thread.interrupt(); thread.join(2000) }
    }
    @Test fun controlsPrioritizedAndBounded() {
        val mux = MediaBudget(false)
        repeat(64) { mux.offer(Wire.control(Wire.PING, 1, Wire.obj("nonce" to it))) }
        assertThrows(IOException::class.java) { mux.offer(Wire.control(Wire.PING, 1, Wire.obj("nonce" to 65))) }
        assertEquals(Wire.PING, mux.poll(1)?.type)
    }

    @Test fun oldReadyCannotEraseNextGenerationDeadline() {
        var time = 1L
        val mux = MediaBudget(false) { time }
        configure(mux, 1, 1); configure(mux, 1, 2)
        assertEquals(1, mux.poll(1)?.generation)
        mux.ready(1, 1)
        assertEquals(2, mux.poll(1)?.generation)
        time += 9_999_999_999L
        assertNull(mux.expiredTrack())
        time++
        assertEquals(1, mux.expiredTrack())
        assertThrows(IOException::class.java) { mux.ready(1, 1) }
        assertEquals(1, mux.expiredTrack())
        mux.ready(1, 2)
        assertNull(mux.expiredTrack())
    }

    @Test fun audioReadinessDoesNotClearVideoRestartDeadline() {
        var time = 1L
        val mux = MediaBudget(true) { time }
        configure(mux, 1, 1); configure(mux, 2, 1)
        mux.poll(1); mux.poll(1); mux.ready(1, 1); mux.ready(2, 1)
        configure(mux, 1, 2); mux.poll(1)
        mux.offer(frame(2, 1, 100, 3))
        assertEquals(2, mux.poll(1)?.track)
        time += 10_000_000_000L
        assertEquals(1, mux.expiredTrack())
    }

    @Test fun wakingConcurrentConfigWriterRetainsItsNewDeadline() {
        val time = AtomicLong(1)
        val mux = MediaBudget(false, time::get)
        configure(mux, 1, 1); configure(mux, 1, 2)
        mux.poll(1)
        val waiting = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val nextConfig = executor.submit<Wire.Message?> { waiting.countDown(); mux.poll(2000) }
            assertTrue(waiting.await(2, TimeUnit.SECONDS))
            mux.ready(1, 1)
            assertEquals(2, nextConfig.get(2, TimeUnit.SECONDS)?.generation)
            time.addAndGet(10_000_000_000L)
            assertEquals(1, mux.expiredTrack())
        } finally { executor.shutdownNow() }
    }
}

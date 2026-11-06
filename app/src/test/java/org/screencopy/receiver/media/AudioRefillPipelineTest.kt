package org.screencopy.receiver.media

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class AudioRefillPipelineTest {
    @Test fun oneDecodeStepServicesAWholeFortyMillisecondMixerQuantum() {
        Fixture().use { fixture ->
            fixture.packet(2880)
            fixture.step()
            assertEquals(2880L, fixture.player.sourceSubmittedFrames)
            fixture.output.mix()
            assertEquals(0, fixture.output.underrunCount)
            assertEquals(960L, fixture.output.buffered)
            assertEquals((0 until 5760).map(Int::toShort), fixture.output.samples)
            assertTrue(fixture.output.maximumBuffered <= 3921)
        }
    }

    @Test fun partialNativeWritesAreDrainedButAZeroWriteYieldsWithoutSpinning() {
        Fixture(maximumWrite = 96, zeroAfter = 3).use { fixture ->
            fixture.packet(2880)
            fixture.step()
            assertEquals(4, fixture.output.calls)
            assertEquals(288L, fixture.player.sourceSubmittedFrames)
            fixture.output.zeroAfter = Int.MAX_VALUE
            fixture.step()
            assertEquals(2880L, fixture.player.sourceSubmittedFrames)
            assertEquals((0 until 5760).map(Int::toShort), fixture.output.samples)
            assertTrue(fixture.output.maximumBuffered <= 3921)
        }
    }

    @Test fun anInputGapAddsAtMostOneFiveMillisecondSilenceBlock() {
        Fixture().use { fixture ->
            fixture.packet(1920)
            fixture.step()
            fixture.output.mix()
            val before = fixture.output.submitted
            fixture.step()
            assertEquals(240L, fixture.output.submitted - before)
            fixture.step()
            assertEquals(240L, fixture.output.submitted - before)
            assertEquals(1920L, fixture.player.sourceSubmittedFrames)
        }
    }

    @Test fun queuedPcmDoesNotGetAnInventedSilencePrefixDuringPreRefill() {
        Fixture().use { fixture ->
            fixture.packet(960)
            fixture.step()
            fixture.output.mix(960)
            fixture.packet(960, 1_020_000)
            fixture.step()
            assertEquals(1920L, fixture.player.sourceSubmittedFrames)
            assertEquals(1920L, fixture.output.submitted)
            assertEquals(3840, fixture.output.samples.size)
        }
    }

    @Test fun decodedPcmIsRefilledBeforeWaitingOnInputAdmission() {
        Fixture().use { fixture ->
            fixture.packet(960)
            fixture.step()
            fixture.output.mix(720)
            fixture.packet(4800, 1_020_000)
            fixture.step()
            assertEquals(3921L, fixture.output.buffered)
            fixture.output.mix()
            val monitor = checkNotNull(TrackWorker::class.java.getDeclaredField("monitor")
                .apply { isAccessible = true }.get(fixture.player))
            val failure = AtomicReference<Throwable>()
            val worker = Thread({
                try { fixture.step() } catch (error: Throwable) { failure.set(error) }
            }, "refill-before-admission-test")
            try {
                synchronized(monitor) {
                    worker.start()
                    val deadline = System.nanoTime() + 2_000_000_000
                    while (fixture.player.sourceSubmittedFrames < 5760 && failure.get() == null &&
                        System.nanoTime() < deadline) Thread.sleep(1)
                    assertNull(failure.get())
                    assertEquals(5760L, fixture.player.sourceSubmittedFrames)
                    assertTrue(fixture.output.buffered >= 1920)
                }
            } finally {
                worker.join(2000)
                assertFalse("Refill test worker did not stop", worker.isAlive)
            }
            assertNull(failure.get())
        }
    }

    private class Fixture(maximumWrite: Int = Int.MAX_VALUE, zeroAfter: Int = Int.MAX_VALUE) : AutoCloseable {
        private val time = AtomicLong(1_000_000_000)
        val output = QuantumOutput(maximumWrite, zeroAfter)
        private val clock = PlaybackClock(time::get) { time.addAndGet(it * 1_000_000) }
            .apply { audioExpected = true; anchorVideo(1_000_000) }
        val player = testAudioPlayer(AudioFormat("pcm", 48000, 2, byteArrayOf()), clock,
            { code, detail -> fail("$code: $detail") }, { output })
        private val step = AudioPlayer::class.java.getDeclaredMethod("step").apply { isAccessible = true }
        fun packet(frames: Int, pts: Long = 1_000_000) {
            val bytes = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
            repeat(frames * 2) { bytes.putShort(it.toShort()) }
            player.offer(AccessUnit(pts, bytes.array()))
        }
        fun step() { step.invoke(player) }
        override fun close() = player.close()
    }

    private class QuantumOutput(private val maximumWrite: Int, var zeroAfter: Int) : AudioOutput {
        override var underrunCount = 0
        override var playbackActive = false
        var submitted = 0L
        private var consumed = 0L
        val buffered get() = submitted - consumed
        var maximumBuffered = 0L
        var calls = 0
        val samples = mutableListOf<Short>()
        override fun play() { playbackActive = true }
        override fun pause() { playbackActive = false }
        override fun write(samples: ShortArray, offset: Int, count: Int): Int {
            if (calls++ >= zeroAfter) return 0
            val frames = minOf(count / 2, maximumWrite, (3921 - buffered).toInt())
            for (index in offset until offset + frames * 2) this.samples.add(samples[index])
            submitted += frames
            maximumBuffered = maxOf(maximumBuffered, buffered)
            return frames * 2
        }
        fun mix(frames: Int = 1920) {
            if (buffered < frames) underrunCount++
            consumed += minOf(frames.toLong(), buffered)
        }
        override fun timing() = AudioOutputTiming(48000, 3921, submitted, consumed, consumed,
            0, AudioClockSource.TIMESTAMP)
        override fun setSpeed(speed: Float) {}
        override fun close() {}
    }
}

package org.screencopy.receiver.media

import android.media.MediaCodec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class AudioAacRecoveryPipelineTest {
    @Test fun lowRateAacNeedsMoreThanOneFullAccessUnitBeyondTheBoundary() {
        assertFalse(aacPastRecoveryBoundary(4_000_000, 4_125_000, 8000))
        assertFalse(aacPastRecoveryBoundary(4_000_000, 4_128_000, 8000))
        assertTrue(aacPastRecoveryBoundary(4_000_000, 4_128_001, 8000))
    }

    @Test(timeout = 15_000) fun waitingAndCodecHeldAacOutputRemainPreservedAfterInputQueueDrains() {
        val now = AtomicLong()
        val enteredStart = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        val inputBlocked = CountDownLatch(1)
        val outputHeld = CountDownLatch(1)
        val firstSubmitted = CountDownLatch(1)
        val ringFilled = CountDownLatch(1)
        val allSubmitted = CountDownLatch(1)
        val laterSubmitted = CountDownLatch(1)
        val allowInput = AtomicBoolean()
        val allowOutput = AtomicBoolean()
        val inputCount = AtomicInteger()
        val outputCount = AtomicInteger()
        val errors = ConcurrentLinkedQueue<String>()
        val written = ConcurrentLinkedQueue<Short>()
        val writtenFrames = AtomicLong()
        val startNs = AtomicLong()
        val codec = mock(MediaCodec::class.java)
        fun pcm(first: Int, frames: Int): ByteBuffer =
            ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
                repeat(frames * 2) { putShort((first + it / 2 + 1).toShort()) }
                flip()
            }
        val outputBuffers = listOf(pcm(0, 960), pcm(960, 5760), pcm(6720, 960), pcm(7680, 5760))
        doAnswer {
            if (inputCount.get() == 1 && !allowInput.get()) {
                inputBlocked.countDown()
                MediaCodec.INFO_TRY_AGAIN_LATER
            } else 0
        }.`when`(codec).dequeueInputBuffer(anyLong())
        doAnswer { ByteBuffer.allocate(16) }.`when`(codec).getInputBuffer(anyInt())
        doAnswer { inputCount.incrementAndGet(); null }.`when`(codec)
            .queueInputBuffer(anyInt(), anyInt(), anyInt(), anyLong(), anyInt())
        doAnswer { invocation ->
            val info = invocation.getArgument<MediaCodec.BufferInfo>(0)
            when {
                inputCount.get() >= 1 && outputCount.get() == 0 -> {
                    info.set(0, 3840, 4_000_000, 0)
                    outputCount.incrementAndGet()
                    0
                }
                inputCount.get() >= 2 && outputCount.get() == 1 -> {
                    if (!allowOutput.get()) {
                        outputHeld.countDown()
                        MediaCodec.INFO_TRY_AGAIN_LATER
                    } else {
                        info.set(0, 23040, 4_020_000, 0)
                        outputCount.incrementAndGet()
                        1
                    }
                }
                inputCount.get() >= 3 && outputCount.get() == 2 -> {
                    info.set(0, 3840, 4_200_000, 0)
                    outputCount.incrementAndGet()
                    2
                }
                inputCount.get() >= 4 && outputCount.get() == 3 -> {
                    info.set(0, 23040, 4_220_000, 0)
                    outputCount.incrementAndGet()
                    3
                }
                else -> MediaCodec.INFO_TRY_AGAIN_LATER
            }
        }.`when`(codec).dequeueOutputBuffer(any(MediaCodec.BufferInfo::class.java), anyLong())
        doAnswer { invocation -> outputBuffers[invocation.getArgument<Int>(0)].duplicate() }
            .`when`(codec).getOutputBuffer(anyInt())
        val output = object : AudioOutput {
            override val underrunCount = 0
            override fun play() { startNs.set(now.get()) }
            override fun pause() {}
            override fun setSpeed(speed: Float) {}
            override fun timing(): AudioOutputTiming {
                val submitted = writtenFrames.get()
                val consumed = minOf(submitted, (now.get() - startNs.get()).coerceAtLeast(0) * 48000 / 1_000_000_000)
                return AudioOutputTiming(48000, 5760, submitted, consumed, consumed, null, AudioClockSource.HEAD_UNAVAILABLE)
            }
            override fun write(samples: ShortArray, offset: Int, count: Int): Int {
                val timing = timing()
                val frames = minOf(count / 2, (5760 - timing.submittedFrames + timing.consumedFrames).toInt())
                repeat(frames * 2) { written.add(samples[offset + it]) }
                val total = writtenFrames.addAndGet(frames.toLong())
                if (total >= 5760) ringFilled.countDown()
                if (total >= 6720) allSubmitted.countDown()
                if (total >= 7680) laterSubmitted.countDown()
                return frames * 2
            }
            override fun close() {}
        }
        val clock = PlaybackClock(now::get) {
            enteredStart.countDown()
            check(releaseStart.await(3, TimeUnit.SECONDS))
        }.apply {
            anchorVideo(1_000_000)
            beginAudioRecovery()
        }
        val player = testAudioPlayer(AudioFormat("aac", 48000, 2, byteArrayOf(1)), clock,
            { code, detail -> errors.add("$code: $detail"); allSubmitted.countDown() }, { output },
            aacFactory = { codec }).apply {
                recoveryCutoffUs = 4_000_000
                onFirstSubmitted = { firstSubmitted.countDown() }
            }
        val waiting = AudioPlayer::class.java.getDeclaredField("waiting").apply { isAccessible = true }
        val regulator = AudioPlayer::class.java.getDeclaredField("regulator").apply { isAccessible = true }
        fun await(condition: () -> Boolean) {
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!condition() && System.nanoTime() < end) Thread.sleep(1)
            assertTrue("Production audio worker made no progress", condition())
        }
        var closed = false
        try {
            player.start()
            assertNull(player.tryOffer(AccessUnit(4_000_000, byteArrayOf(0))))
            assertTrue(enteredStart.await(3, TimeUnit.SECONDS))
            assertNull(player.tryOffer(AccessUnit(4_020_000, byteArrayOf(1))))
            now.set(3_000_000_000)
            releaseStart.countDown()
            assertTrue(firstSubmitted.await(3, TimeUnit.SECONDS))
            assertTrue(inputBlocked.await(3, TimeUnit.SECONDS))
            assertNotNull(waiting.get(player))
            allowInput.set(true)
            assertTrue(outputHeld.await(3, TimeUnit.SECONDS))
            assertNull(waiting.get(player))
            assertEquals(0, player.queuedPackets())
            assertEquals(1, outputCount.get())
            await { player.aacRecoveryInputBoundaryUs == 4_020_000L || errors.isNotEmpty() }
            assertTrue(player.preservingRecovery)
            assertNull(player.tryOffer(AccessUnit(4_200_000, byteArrayOf(2))))
            await { inputCount.get() == 3 || errors.isNotEmpty() }
            assertTrue(player.preservingRecovery)
            assertNull(player.recoverySourceBoundary)
            allowOutput.set(true)
            assertTrue(ringFilled.await(3, TimeUnit.SECONDS))
            assertEquals(961.toShort(), written.elementAt(1920))
            now.addAndGet(20_000_000)
            assertTrue(allSubmitted.await(3, TimeUnit.SECONDS))
            now.addAndGet(20_000_000)
            assertTrue(laterSubmitted.await(3, TimeUnit.SECONDS))
            await { !player.preservingRecovery || errors.isNotEmpty() }
            assertTrue(errors.toString(), errors.isEmpty())
            assertNull(player.tryOffer(AccessUnit(4_220_000, byteArrayOf(3))))
            await { (regulator.get(player) as AudioRegulator).discardedFrames > 0 || errors.isNotEmpty() }
            closed = true
            player.close()
            assertTrue(errors.toString(), errors.isEmpty())
            assertTrue(player.sourceSubmittedFrames >= 7680L)
            val samples = written.toList()
            assertTrue(samples.size >= 13440)
            samples.take(13440).forEachIndexed { index, sample ->
                assertEquals("sample $index", (index / 2 + 1).toShort(), sample)
            }
            assertEquals(6721.toShort(), samples[13440])
        } finally {
            releaseStart.countDown()
            allowInput.set(true)
            allowOutput.set(true)
            if (!closed) player.close()
        }
    }
}

package org.screencopy.receiver.media

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

class ResamplerFailureTest {
    @Test fun resamplerInitializationFailureClosesOutputWithoutIdentityFallback() {
        val output = Output()
        val error = assertThrows(IllegalStateException::class.java) {
            testAudioPlayer(AudioFormat("pcm", 48000, 2, byteArrayOf()), PlaybackClock(),
                { _, _ -> fail("Worker must not start") }, { output },
                resamplerFactory = { _, _ -> throw IllegalStateException("fixture native initialization failure") })
        }
        assertTrue(error.message!!.contains("native initialization"))
        assertTrue(output.closed)
        assertEquals(0, output.submitted)
    }

    @Test fun nativeConversionFailureIsExplicitAndNeverSubmitsUnregulatedPcm() =
        failure("AUDIO_FAILED", "PCM_RESAMPLE") { throw IllegalStateException("fixture native conversion failure") }

    @Test fun malformedNativePcmIsRejected() = failure("AUDIO_FAILED", "PCM_RESAMPLE") { ShortArray(3) }

    @Test fun oversizedNativePcmIsRejectedWithoutGrowingTheConvertedBufferBudget() =
        failure("CONGESTION", "PCM_RESAMPLE") { ShortArray((5760 + 257) * 2) }

    private fun failure(code: String, stage: String, convert: () -> ShortArray) {
        val time = AtomicLong(1_000_000_000)
        val clock = PlaybackClock(time::get) { time.addAndGet(it * 1_000_000) }
            .apply { audioExpected = true; anchorVideo(1_000_000) }
        val output = Output()
        val errors = ConcurrentLinkedQueue<String>()
        var closed = false
        val player = testAudioPlayer(AudioFormat("pcm", 48000, 2, byteArrayOf()), clock,
            { actual, detail -> errors.add("$actual:$detail") }, { output },
            resamplerFactory = { _, _ -> object : PcmResampler {
                override val delayFrames = 0
                override fun convert(samples: ShortArray, offsetFrames: Int, frames: Int) = convert()
                override fun compensate(deltaFrames: Int, distanceFrames: Int) {}
                override fun close() { closed = true }
            } })
        try {
            player.start()
            player.offer(AccessUnit(1_000_000, ByteArray(3840)))
            val deadline = System.nanoTime() + 2_000_000_000
            while (errors.isEmpty() && System.nanoTime() < deadline) Thread.sleep(1)
            assertEquals(1, errors.size)
            assertTrue(errors.toString(), errors.single().startsWith("$code:"))
            assertTrue(errors.toString(), errors.single().contains("stage=$stage"))
            assertEquals(0, output.submitted)
        } finally { player.close() }
        assertTrue(closed)
        assertTrue(output.closed)
    }

    private class Output : AudioOutput {
        override val underrunCount = 0
        var submitted = 0
        var closed = false
        override fun play() {}
        override fun pause() {}
        override fun write(samples: ShortArray, offset: Int, count: Int): Int { submitted += count; return count }
        override fun timing() = AudioOutputTiming(48000, 6240, submitted.toLong() / 2,
            submitted.toLong() / 2, submitted.toLong() / 2, null, AudioClockSource.HEAD_UNAVAILABLE)
        override fun setSpeed(speed: Float) = fail("Native speed must remain fixed")
        override fun close() { closed = true }
    }
}

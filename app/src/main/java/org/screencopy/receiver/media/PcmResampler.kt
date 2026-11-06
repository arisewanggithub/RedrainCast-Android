package org.screencopy.receiver.media

import androidx.annotation.Keep

interface PcmResampler : AutoCloseable {
    fun convert(samples: ShortArray, offsetFrames: Int, frames: Int): ShortArray
    fun compensate(deltaFrames: Int, distanceFrames: Int)
    val delayFrames: Int
}

/**
 * Stateful interleaved s16 resampling. Positive compensation adds output frames.
 * Offsets, lengths, compensation and delay are frames (not interleaved samples).
 * A zero-length conversion never flushes; close discards the current epoch's tail.
 */
@Keep
internal class FfmpegPcmResampler(rate: Int, channels: Int) : PcmResampler {
    private var handle = run {
        loadNative()
        try {
            create(rate, channels)
        } catch (error: UnsatisfiedLinkError) {
            throw IllegalStateException("FFmpeg PCM resampler JNI initialization failed", error)
        }
    }

    @Synchronized
    override fun convert(samples: ShortArray, offsetFrames: Int, frames: Int): ShortArray {
        check(handle != 0L) { "PCM resampler is closed" }
        return convertNative(handle, samples, offsetFrames, frames)
    }

    @Synchronized
    override fun compensate(deltaFrames: Int, distanceFrames: Int) {
        check(handle != 0L) { "PCM resampler is closed" }
        compensateNative(handle, deltaFrames, distanceFrames)
    }

    override val delayFrames: Int
        @Synchronized get() {
            check(handle != 0L) { "PCM resampler is closed" }
            return delayNative(handle)
        }

    @Synchronized
    override fun close() {
        if (handle != 0L) {
            destroy(handle)
            handle = 0L
        }
    }

    private external fun create(rate: Int, channels: Int): Long
    private external fun convertNative(handle: Long, samples: ShortArray, offsetFrames: Int, frames: Int): ShortArray
    private external fun compensateNative(handle: Long, deltaFrames: Int, distanceFrames: Int)
    private external fun delayNative(handle: Long): Int
    private external fun destroy(handle: Long)

    companion object {
        private var loaded = false

        @Synchronized
        private fun loadNative() {
            if (!loaded) {
                try {
                    System.loadLibrary("screencopy_resampler")
                } catch (error: UnsatisfiedLinkError) {
                    throw IllegalStateException("FFmpeg PCM resampler library could not be loaded", error)
                }
                loaded = true
            }
        }
    }
}

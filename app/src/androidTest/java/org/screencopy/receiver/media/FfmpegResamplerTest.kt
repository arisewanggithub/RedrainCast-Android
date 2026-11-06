package org.screencopy.receiver.media

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class FfmpegResamplerTest {
    @Test fun monoAndFrameAlignedOffsetsUseTheSameConfiguredSampleDomain() {
        FfmpegPcmResampler(48000, 1).use { mono ->
            val converted = mono.convert(ShortArray(960) { 1234 }, 0, 960)
            assertEquals(960 - mono.delayFrames, converted.size)
            assertTrue(converted.all { abs(it.toInt() - 1234) <= 2 })
        }
        FfmpegPcmResampler(48000, 2).use { direct ->
            FfmpegPcmResampler(48000, 2).use { offset ->
                val samples = tone(0, 960)
                val prefixed = ShortArray(200) { Short.MAX_VALUE } + samples
                assertArrayEquals(direct.convert(samples, 0, 960), offset.convert(prefixed, 100, 960))
            }
        }
    }

    @Test fun nominalFilterDelayAccountsForEverySourceFrameAndKeepsStereoSeparate() {
        FfmpegPcmResampler(48000, 2).use { converter ->
            var input = 0L
            var output = 0L
            var previous: Int? = null
            repeat(100) { packet ->
                val samples = tone(packet * 960, 960)
                val converted = converter.convert(samples, 0, 960)
                input += 960
                output += converted.size / 2
                assertEquals(input, output + converter.delayFrames)
                for (frame in 0 until converted.size / 2) {
                    val left = converted[frame * 2].toInt()
                    val right = converted[frame * 2 + 1].toInt()
                    assertTrue("Stereo channels mixed", abs(left + right) <= 2)
                    previous?.let { assertTrue("PCM discontinuity", abs(left - it) < 1500) }
                    previous = left
                }
            }
            assertTrue(output > 95000)
        }
    }

    @Test fun cancellingCompensationDoesNotReinitializeTheNominalFilter() {
        FfmpegPcmResampler(48000, 2).use { first ->
            FfmpegPcmResampler(48000, 2).use { second ->
                repeat(100) { packet ->
                    val samples = tone(packet * 960, 960)
                    second.compensate(0, 0)
                    assertArrayEquals(first.convert(samples, 0, 960), second.convert(samples, 0, 960))
                    assertEquals(first.delayFrames, second.delayFrames)
                }
            }
        }
    }

    @Test fun boundedCompensationChangesSampleCountWithoutResettingOutputContinuity() {
        FfmpegPcmResampler(48000, 2).use { converter ->
            converter.compensate(960, 192000)
            var produced = 0L
            var previous: Int? = null
            repeat(250) { packet ->
                val converted = converter.convert(tone(packet * 960, 960), 0, 960)
                produced += converted.size / 2
                assertTrue(converted.size / 2 <= 5760 + 256)
                for (frame in 0 until converted.size / 2) {
                    val sample = converted[frame * 2].toInt()
                    previous?.let { assertTrue("Compensation introduced a PCM discontinuity", abs(sample - it) < 1500) }
                    previous = sample
                }
            }
            assertTrue("Compensation did not add the requested bounded output", produced > 240000 + 800)
            assertTrue(produced < 240000 + 1200)
        }
    }

    @Test fun invalidInputAndRetiredResamplerFailInsteadOfReturningIdentityPcm() {
        val converter = FfmpegPcmResampler(48000, 2)
        try {
            assertThrows(IllegalArgumentException::class.java) { converter.convert(ShortArray(8), 3, 2) }
            assertThrows(IllegalArgumentException::class.java) { converter.convert(ShortArray(24000), 0, 6000) }
            assertThrows(IllegalArgumentException::class.java) { converter.compensate(5000, 192000) }
        } finally { converter.close() }
        converter.close()
        assertThrows(IllegalStateException::class.java) { converter.convert(ShortArray(1920), 0, 960) }
    }

    private fun tone(start: Int, frames: Int) = ShortArray(frames * 2).also { samples ->
        repeat(frames) { frame ->
            val value = (sin((start + frame) * 2.0 * Math.PI * 440 / 48000) * 10000).toInt().toShort()
            samples[frame * 2] = value
            samples[frame * 2 + 1] = (-value.toInt()).toShort()
        }
    }
}

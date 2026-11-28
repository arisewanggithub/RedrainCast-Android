package org.screencopy.receiver.media

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class VirtualDiscardAudioOutputTest {
    @Test fun boundedPacingDoesNotAccumulateCreditWhileEmptyOrPaused() {
        val now = AtomicLong()
        val output = VirtualDiscardAudioOutput(48000, 2, now::get)
        val pcm = ShortArray(12000) { 123 }
        output.play()
        now.addAndGet(5_000_000_000)
        assertEquals(9600, output.write(pcm, 0, pcm.size))
        assertEquals(0, output.write(pcm, 0, 2))
        assertEquals(0L, output.timing().consumedFrames)
        now.addAndGet(25_000_000)
        assertEquals(1200L, output.timing().consumedFrames)
        output.pause()
        now.addAndGet(500_000_000)
        assertEquals(1200L, output.timing().consumedFrames)
        output.play()
        now.addAndGet(200_000_000)
        val timing = output.timing()
        assertEquals(4800L, timing.consumedFrames)
        assertTrue(timing.virtualDiscard)
        assertNull(timing.qualifiedTimestampFrames)
        assertNull(timing.timestampAgeUs)
        assertTrue(timing.logMessage().contains("clock_source=VIRTUAL_DISCARD"))
        assertNull(output.playbackActive)
        assertTrue(pcm.all { it == 123.toShort() })
        output.close()
    }

    @Test fun virtualProgressDrivesClockButNeverQualifiesPlayedCoverage() {
        val now = AtomicLong(1_000_000_000)
        val clock = PlaybackClock(now::get) { now.addAndGet(it * 1_000_000) }.apply { audioExpected = true }
        val output = VirtualDiscardAudioOutput(48000, 2, now::get)
        val player = testAudioPlayer(AudioFormat("pcm", 48000, 2, byteArrayOf()), clock,
            { code, detail -> fail("$code: $detail") }, { output })
        try {
            val write = AudioPlayer::class.java.getDeclaredMethod("write", ShortArray::class.java,
                Long::class.javaPrimitiveType, dev.redrain.cast.diagnostics.DiagnosticAudioTiming::class.java,
                Long::class.javaPrimitiveType).apply { isAccessible = true }
            val pump = AudioPlayer::class.java.getDeclaredMethod("pumpOutput", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
            write.invoke(player, ShortArray(1920), 1_000_000L, null, 0L)
            pump.invoke(player, false)
            val initial = checkNotNull(clock.currentUs())
            now.addAndGet(10_000_000)
            pump.invoke(player, false)
            assertTrue(checkNotNull(clock.currentUs()) > initial)
            assertNull(clock.cachedAudioDiscardCutoffUs())
            assertNull(player.audioStatistics)
            assertTrue(output.timing().virtualDiscard)
        } finally {
            player.close()
        }
    }

    @Test fun concurrentWritesAndReadsRemainWithinCapacity() {
        val now = AtomicLong(1_000_000_000)
        val output = VirtualDiscardAudioOutput(48000, 2, now::get)
        output.play()
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val failure = AtomicReference<Throwable>()
        repeat(2) {
            Thread {
                try {
                    check(start.await(3, TimeUnit.SECONDS))
                    repeat(2000) {
                        now.addAndGet(50_000)
                        output.write(ShortArray(480), 0, 480)
                        val timing = output.timing()
                        check(timing.consumedFrames in 0..timing.submittedFrames)
                        check(timing.submittedFrames - timing.consumedFrames in 0..timing.bufferFrames.toLong())
                    }
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                } finally {
                    done.countDown()
                }
            }.start()
        }
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        failure.get()?.let { throw AssertionError(it) }
        output.close()
    }

    @Test fun recoveryCompletesOnlyAfterSourcePcmIsAcceptedByVirtualFifo() {
        val now = AtomicLong(1_000_000_000)
        val clock = PlaybackClock(now::get) { now.addAndGet(it * 1_000_000) }.apply {
            audioExpected = true
            anchorVideo(1_000_000)
            beginAudioRecovery()
        }
        val output = VirtualDiscardAudioOutput(48000, 2, now::get)
        val player = testAudioPlayer(AudioFormat("pcm", 48000, 2, byteArrayOf()), clock,
            { code, detail -> fail("$code: $detail") }, { output })
        var proof: Long? = null
        player.recoveryCutoffUs = 1_000_000
        player.onFirstSubmitted = { proof = it }
        try {
            assertNull(player.tryOffer(AccessUnit(1_000_000, ByteArray(3840))))
            assertNull(proof)
            AudioPlayer::class.java.getDeclaredMethod("serviceStep").apply { isAccessible = true }.invoke(player)
            assertEquals(1_000_000L, proof)
            assertTrue(output.timing().submittedFrames > 0)
            assertNull(clock.cachedAudioDiscardCutoffUs())
        } finally {
            player.close()
        }
    }

    @Test fun virtualTimestampCannotQualifyAsNativeCoverage() {
        val timeline = AudioPresentationTimeline(48000)
        timeline.write(true, 2, 480, originalSourceStartUnits = 48_000_000_000L) { 960 }
        val timing = AudioOutputTiming(48000, 4800, 480, 480, 480, 0,
            AudioClockSource.TIMESTAMP, qualifiedTimestampFrames = 480, virtualDiscard = true)
        assertNull(timeline.discardCoverage(timing))
    }
}

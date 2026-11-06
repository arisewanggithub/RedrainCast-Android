package org.screencopy.receiver.media

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.screencopy.receiver.protocol.Configuration
import org.screencopy.receiver.protocol.Message
import org.screencopy.receiver.protocol.Request
import org.screencopy.receiver.protocol.StrictJson
import org.screencopy.receiver.protocol.TrackState
import org.screencopy.receiver.protocol.Type
import org.screencopy.receiver.protocol.Wire
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class NativeOpusPtsOrderTest {
    @Test fun nativeOpusKeepsReversedStartupPtsPreskipAndMonotonicPresentation() {
        val config = roundTrip(Message.json(Type.STREAM_CONFIG, StrictJson.decode(
            """{"codec":"opus","sampleRate":48000,"channels":2,"csd":["T3B1c0hlYWQBAjgBgLsAAAAAAA=="],"codecDelayNs":6500000,"seekPrerollNs":80000000}""".toByteArray()), 1, 2, 1))
        val format = Configuration.audio(config.json(), Request("h264", "opus", 60, ""))
        val state = TrackState(listOf(1, 2))
        state.configure(1, 1, "h264"); state.ready(1)
        state.configure(2, 1, format.codec); state.ready(2)
        val now = AtomicLong()
        val clock = PlaybackClock(now::get) { now.addAndGet(it * 1_000_000) }
            .apply { audioExpected = true; anchorVideo(1_000_000) }
        val output = SilentRecordingOutput(now)
        val errors = ConcurrentLinkedQueue<String>()
        val adjustments = ConcurrentLinkedQueue<AudioClockCorrection>()
        // The default factory executes bundled JNI Opus; only output is recorded, never played.
        val player = AudioPlayer(format, clock, { code, detail -> errors.add("$code: $detail") },
            { output }, onClockCorrection = adjustments::add)
        val payload = byteArrayOf(0xf8.toByte(), 0xff.toByte(), 0xfe.toByte())
        var start = 0L
        var previous = Long.MIN_VALUE
        try {
            player.start()
            listOf(1_000_000L, 999_551L).forEachIndexed { index, pts ->
                if (index > 0) now.set(start + 20_000_000)
                val message = roundTrip(Message(Type.AUDIO_PACKET, 1, 2, 1, pts, payload = payload))
                state.media(message)
                assertEquals(pts, message.ptsUs)
                assertArrayEquals(payload, message.payload)
                player.offer(AccessUnit(message.ptsUs, message.payload))
                await { output.frames.get() >= (index + 1) * 960 - 312 || errors.isNotEmpty() }
                assertTrue(errors.toString(), errors.isEmpty())
                if (index == 0) start = now.get()
                val current = checkNotNull(clock.currentUs())
                assertTrue(current >= previous)
                previous = current
            }
            assertEquals(1608L, output.frames.get())
            assertEquals(0L, output.nonzero.get())
            assertTrue(adjustments.toString(), adjustments.any { it.afterOffsetUs == -20_449L })
        } finally { player.close() }
    }

    private fun roundTrip(message: Message): Message = ByteArrayOutputStream().let {
        Wire.write(it, message)
        Wire.read(ByteArrayInputStream(it.toByteArray()))
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(1)
        assertTrue("Native Opus worker did not progress", condition())
    }
    private class SilentRecordingOutput(private val now: AtomicLong) : AudioOutput {
        val frames = AtomicLong()
        val nonzero = AtomicLong()
        override val underrunCount = 0
        private var start = 0L
        override fun play() { start = now.get() }
        override fun pause() {}
        override fun timing(): AudioOutputTiming {
            val consumed = minOf(frames.get(), (now.get() - start).coerceAtLeast(0) * 48000 / 1_000_000_000)
            return AudioOutputTiming(48000, 6240, frames.get(), consumed, consumed, null, AudioClockSource.HEAD_UNAVAILABLE)
        }
        override fun write(samples: ShortArray, offset: Int, count: Int): Int {
            repeat(count) { if (samples[offset + it].toInt() != 0) nonzero.incrementAndGet() }
            frames.addAndGet(count / 2L)
            return count
        }
        override fun setSpeed(speed: Float) {}
        override fun close() {}
    }
}

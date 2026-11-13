package org.screencopy.receiver.media

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class VideoCatchupTest {
    @Test fun futurePicturesKeepEveryPresentationDeadline() {
        Fixture(listOf(20_000, 40_000, 60_000)).use { fixture ->
            fixture.decode()
            repeat(3) { fixture.step() }
            assertEquals(listOf(0, 1, 2), fixture.decoder.rendered)
            assertEquals(listOf(1_020_000_000L, 1_040_000_000L, 1_060_000_000L),
                fixture.decoder.renderTimes)
            assertTrue(fixture.decoder.skipped.isEmpty())
            assertEquals(1_000_000_000L, fixture.now.get())
            assertEquals(1, fixture.decoder.maximumOutstanding)
            assertEquals(3L, fixture.player.videoStatistics().scheduled)
            assertEquals(0L, fixture.player.videoStatistics().skipped)
        }
    }

    @Test fun exactlyDuePictureIsNotReplacedByAFuturePicture() {
        Fixture(listOf(0, 20_000)).use { fixture ->
            fixture.decode()
            fixture.step()
            assertEquals(listOf(0), fixture.decoder.rendered)
            assertEquals(listOf(1_000_000_000L), fixture.decoder.renderTimes)
            assertTrue(fixture.decoder.skipped.isEmpty())
            fixture.step()
            assertEquals(listOf(0, 1), fixture.decoder.rendered)
            assertEquals(listOf(1_000_000_000L, 1_020_000_000L), fixture.decoder.renderTimes)
        }
    }

    @Test fun catchupStopsAtTheFirstFuturePictureInsteadOfDrainingFuturePictures() {
        Fixture(listOf(0, 20_000, 40_000, 60_000)).use { fixture ->
            fixture.decode()
            fixture.now.addAndGet(30_000_000)
            fixture.step()
            assertEquals(listOf(0, 1), fixture.decoder.skipped)
            assertEquals(listOf(2), fixture.decoder.rendered)
            assertEquals(listOf(1_040_000_000L), fixture.decoder.renderTimes)
            fixture.step()
            assertEquals(listOf(2, 3), fixture.decoder.rendered)
            assertEquals(listOf(1_040_000_000L, 1_060_000_000L), fixture.decoder.renderTimes)
            assertEquals(2, fixture.decoder.maximumOutstanding)
        }
    }

    @Test fun catchupStopsAtAnExactlyDuePicture() {
        Fixture(listOf(0, 20_000, 40_000)).use { fixture ->
            fixture.decode()
            fixture.now.addAndGet(20_000_000)
            fixture.step()
            assertEquals(listOf(0), fixture.decoder.skipped)
            assertEquals(listOf(1), fixture.decoder.rendered)
            assertEquals(listOf(1_020_000_000L), fixture.decoder.renderTimes)
            fixture.step()
            assertEquals(listOf(1, 2), fixture.decoder.rendered)
        }
    }

    @Test fun allLatePicturesStillCatchUpToTheLatestAvailablePicture() {
        Fixture(listOf(0, 20_000, 40_000)).use { fixture ->
            fixture.decode()
            fixture.now.addAndGet(500_000_000)
            fixture.step()
            assertEquals(listOf(0, 1), fixture.decoder.skipped)
            assertEquals(listOf(2), fixture.decoder.rendered)
            assertEquals(listOf(1_500_000_000L), fixture.decoder.renderTimes)
            assertEquals(listOf(8_000_000_000L), fixture.decoder.inputs)
            assertEquals(2, fixture.decoder.maximumOutstanding)
        }
    }

    @Test fun catchupStillHasAnEightReplacementBudgetPerStep() {
        Fixture((0..10).map { it * 20_000L }).use { fixture ->
            fixture.decode()
            fixture.now.addAndGet(500_000_000)
            fixture.step()
            assertEquals((0..7).toList(), fixture.decoder.skipped)
            assertEquals(listOf(8), fixture.decoder.rendered)
            assertEquals(2, fixture.decoder.maximumOutstanding)
            fixture.step()
            assertEquals((0..7).toList() + 9, fixture.decoder.skipped)
            assertEquals(listOf(8, 10), fixture.decoder.rendered)
        }
    }

    @Test fun clockAdvanceWhileDecodingReevaluatesTheNextHeldPicture() {
        Fixture(listOf(0, 20_000, 40_000)).use { fixture ->
            fixture.decode()
            fixture.now.addAndGet(10_000_000)
            fixture.player.onDecoded = { pts ->
                if (pts == 8_000_020_000L) fixture.now.addAndGet(20_000_000)
            }
            fixture.step()
            assertEquals(listOf(0, 1), fixture.decoder.skipped)
            assertEquals(listOf(2), fixture.decoder.rendered)
            assertEquals(listOf(1_040_000_000L), fixture.decoder.renderTimes)
        }
    }

    @Test fun absentClockDoesNotAuthorizeReplacingPictures() {
        Fixture(listOf(0, 20_000), anchored = false).use { fixture ->
            fixture.clock.audioExpected = true
            fixture.decode()
            repeat(2) { fixture.step() }
            assertEquals(listOf(0, 1), fixture.decoder.rendered)
            assertEquals(listOf(1_000_000_000L, 1_000_000_000L), fixture.decoder.renderTimes)
            assertTrue(fixture.decoder.skipped.isEmpty())
        }
    }

    @Test(timeout = 5000) fun retirementDuringCatchupInterruptsDecodeWithoutPresentingOrReportingFailure() {
        Fixture(listOf(0, 20_000, 40_000)).use { fixture ->
            fixture.decode()
            fixture.now.addAndGet(10_000_000)
            val entered = CountDownLatch(1)
            val gate = CountDownLatch(1)
            fixture.player.onDecoded = {
                entered.countDown()
                assertTrue(gate.await(2, TimeUnit.SECONDS))
            }
            try {
                fixture.player.start()
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                fixture.player.close()
                assertTrue(fixture.decoder.closed)
                assertTrue(fixture.decoder.rendered.isEmpty())
                assertTrue(fixture.errors.isEmpty())
                assertThrows(TrackStoppedException::class.java) {
                    fixture.player.tryOffer(AccessUnit(8_000_060_000, byteArrayOf(1)))
                }
            } finally { gate.countDown() }
        }
    }

    @Test fun unexpectedCatchupInterruptionReportsFailureWithoutPresenting() {
        Fixture(listOf(0, 20_000)).use { fixture ->
            fixture.decode()
            fixture.now.addAndGet(10_000_000)
            val failed = CountDownLatch(1)
            fixture.onFailure = { failed.countDown() }
            fixture.player.onDecoded = { throw InterruptedException("test interruption") }
            fixture.player.start()
            assertTrue(failed.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("DECODER_FAILED: Playback interrupted unexpectedly"), fixture.errors)
            assertTrue(fixture.decoder.rendered.isEmpty())
        }
    }

    @Test fun retirementFromDecodedCallbackStopsFurtherReplacementAndPresentation() {
        Fixture(listOf(0, 20_000, 40_000)).use { fixture ->
            fixture.decode()
            fixture.now.addAndGet(10_000_000)
            val retired = CountDownLatch(1)
            fixture.player.onDecoded = {
                fixture.player.halt()
                retired.countDown()
            }
            fixture.player.start()
            assertTrue(retired.await(2, TimeUnit.SECONDS))
            fixture.player.close()
            assertTrue(fixture.decoder.closed)
            assertTrue(fixture.decoder.skipped.isEmpty())
            assertTrue(fixture.decoder.rendered.isEmpty())
            assertTrue(fixture.errors.isEmpty())
            assertEquals(2, fixture.decoder.maximumOutstanding)
        }
    }

    private class Fixture(offsets: List<Long>, anchored: Boolean = true) : AutoCloseable {
        val now = AtomicLong(1_000_000_000)
        val clock = PlaybackClock(now::get).apply { if (anchored) anchorVideo(8_000_000_000) }
        val decoder = Decoder(offsets)
        val errors = mutableListOf<String>()
        var onFailure: () -> Unit = {}
        val player = VideoPlayer(VideoFormat("video/avc", 640, 360, listOf(byteArrayOf(1)), 50),
            clock, { code, detail -> errors.add("$code: $detail"); onFailure() }, decoder)
        private val step = VideoPlayer::class.java.getDeclaredMethod("step").apply { isAccessible = true }
        fun decode() {
            player.offer(AccessUnit(8_000_000_000, byteArrayOf(1), true))
            step()
        }
        fun step() { step.invoke(player) }
        override fun close() = player.close()
    }

    private class Decoder(private val offsets: List<Long>) : VideoDecoder {
        override val description = "Catch-up test decoder"
        val inputs = mutableListOf<Long>()
        val rendered = mutableListOf<Int>()
        val renderTimes = mutableListOf<Long>()
        val skipped = mutableListOf<Int>()
        private val outputs = ArrayDeque<VideoOutput>()
        private var outstanding = 0
        var maximumOutstanding = 0
        var closed = false
        override fun dequeueInput() = 0
        override fun input(index: Int): ByteBuffer = ByteBuffer.allocate(1)
        override fun queueInput(index: Int, size: Int, ptsUs: Long, keyFrame: Boolean) {
            inputs.add(ptsUs)
            offsets.forEachIndexed { picture, offset ->
                outputs.addLast(VideoOutput(picture, ptsUs + offset, 1, 0))
            }
        }
        override fun dequeueOutput(): VideoOutput? = outputs.removeFirstOrNull()?.also {
            outstanding++
            maximumOutstanding = maxOf(maximumOutstanding, outstanding)
        }
        override fun releaseOutput(index: Int, renderAtNs: Long?) {
            if (renderAtNs == null) skipped.add(index) else {
                rendered.add(index)
                renderTimes.add(renderAtNs)
            }
            outstanding--
        }
        override fun close() { closed = true }
    }
}

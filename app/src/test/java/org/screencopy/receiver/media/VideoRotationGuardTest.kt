package org.screencopy.receiver.media

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.screencopy.receiver.protocol.HdrVideoColor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class VideoRotationGuardTest {
    private val hdr = VideoFormat("video/hevc", 640, 360, emptyList(), 60, videoColor = HdrVideoColor())

    @Test fun nonzeroHdrInitialRotationFailsBeforeCreatingDecoder() {
        val created = AtomicInteger()
        for (rotation in listOf(90, 180, 270)) {
            val failure = assertThrows(PlaybackException::class.java) {
                VideoPlayer(hdr.copy(rotation = rotation), PlaybackClock(), { _, _ -> },
                    factory = { _, _ -> created.incrementAndGet(); Decoder() })
            }
            assertEquals("UNSUPPORTED_CONFIG", failure.code)
        }
        assertEquals(0, created.get())
    }

    @Test fun nonzeroHdrPictureFailsBeforeInputDequeueCopyQueueOrRender() {
        for (rotation in listOf(90, 180, 270)) {
            val decoder = Decoder()
            val failed = CountDownLatch(1)
            val code = AtomicReference<String>()
            val detail = AtomicReference<String>()
            VideoPlayer(hdr, PlaybackClock(), { actualCode, actualDetail ->
                code.set(actualCode); detail.set(actualDetail); failed.countDown()
            }, decoder).use { player ->
                player.start()
                player.offer(AccessUnit(1_000_000, byteArrayOf(0, 0, 0, 1, 0x26, 1, 0x80.toByte()),
                    keyFrame = true, rotation = rotation))
                assertTrue(failed.await(3, TimeUnit.SECONDS))
                assertEquals("UNSUPPORTED_CONFIG", code.get())
                assertEquals(0, decoder.dequeued.get())
                assertEquals(0, decoder.copied.get())
                assertEquals(0, decoder.inputs.size)
                assertEquals(0, decoder.rendered.get())
                val diagnostic = checkNotNull(VideoDiagnostic.fromLocalDetail(detail.get()))
                assertEquals(VideoStage.INPUT_KEYFRAME, diagnostic.stage)
                assertEquals(0L, diagnostic.queuedFrames)
            }
        }
    }

    @Test fun sdrPortraitLandscapeReturnKeepsPacketPtsAndRotationWithoutReplacingDecoder() {
        val decoder = Decoder()
        val failed = AtomicReference<String>()
        val rotations = ConcurrentLinkedQueue<Int>()
        val format = hdr.copy(mime = "video/avc", videoColor = null)
        VideoPlayer(format, PlaybackClock(), { code, _ -> failed.set(code) }, decoder,
            onRotation = rotations::add).use { player ->
            player.start()
            for ((index, rotation) in listOf(0, 270, 0, 90, 180, 270).withIndex()) {
                player.offer(AccessUnit(1_000_000 + index * 20_000L, byteArrayOf(1), index == 0, rotation))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (decoder.rendered.get() <= index && failed.get() == null && System.nanoTime() < deadline)
                    Thread.sleep(2)
                assertNull(failed.get())
                assertTrue(decoder.rendered.get() > index)
            }
            assertEquals(listOf(0, 270, 0, 90, 180, 270), rotations.toList())
            assertEquals((0..5).map { 1_000_000 + it * 20_000L }, decoder.inputs.toList())
        }
    }

    private class Decoder : VideoDecoder {
        override val description = "rotation guard fixture"
        val dequeued = AtomicInteger()
        val copied = AtomicInteger()
        val rendered = AtomicInteger()
        val inputs = ConcurrentLinkedQueue<Long>()
        private val pictures = ConcurrentLinkedQueue<VideoOutput>()
        override fun dequeueInput(): Int { dequeued.incrementAndGet(); return 0 }
        override fun input(index: Int): ByteBuffer { copied.incrementAndGet(); return ByteBuffer.allocate(64) }
        override fun queueInput(index: Int, size: Int, ptsUs: Long, keyFrame: Boolean) {
            inputs.add(ptsUs)
            pictures.add(VideoOutput(0, ptsUs, size, 0))
        }
        override fun dequeueOutput() = pictures.poll()
        override fun releaseOutput(index: Int, renderAtNs: Long?) { if (renderAtNs != null) rendered.incrementAndGet() }
        override fun close() = Unit
    }
}

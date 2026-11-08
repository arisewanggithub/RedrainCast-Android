package org.screencopy.receiver.media

import android.media.MediaCodecInfo
import android.media.MediaFormat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.screencopy.receiver.protocol.HdrStaticMetadata
import org.screencopy.receiver.protocol.HdrVideoColor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@ConscryptMode(ConscryptMode.Mode.OFF)
class HdrDecoderTest {
    @Test fun hdrAdmissionFailuresPreserveTheExactReasonAndStructuredDiagnostic() {
        val metadata = HdrStaticMetadata(listOf(35400, 14600, 8500, 39850, 6550, 2300, 15635, 16450), 1000, 50, 1000, 400)
        val lightLevel = java.nio.ByteBuffer.allocate(12).apply {
            put(byteArrayOf(0, 0, 0, 1, 0x4e, 1, 0x90.toByte(), 4))
            putShort(1001.toShort())
            putShort(400.toShort())
        }.array() + byteArrayOf(0x80.toByte())
        for ((bytes, code, reason) in listOf(
            Triple(byteArrayOf(0, 0, 0, 1, 0x40, 1, 0x80.toByte()), "UNSUPPORTED_CONFIG", "Unannounced HDR HEVC CSD change"),
            Triple(lightLevel, "UNSUPPORTED_CONFIG", "HDR light-level SEI differs from negotiated metadata"),
            Triple(byteArrayOf(1), "PROTOCOL", "HDR HEVC requires Annex B")
        )) {
            val detail = java.util.concurrent.atomic.AtomicReference<String>()
            val failureCode = java.util.concurrent.atomic.AtomicReference<String>()
            val failed = java.util.concurrent.CountDownLatch(1)
            val inputCalls = java.util.concurrent.atomic.AtomicInteger()
            val decoder = object : VideoDecoder {
                override val description = "HDR admission fixture"
                override fun dequeueInput() = 0
                override fun input(index: Int): java.nio.ByteBuffer {
                    inputCalls.incrementAndGet()
                    return java.nio.ByteBuffer.allocate(32)
                }
                override fun queueInput(index: Int, size: Int, ptsUs: Long, keyFrame: Boolean) = Unit
                override fun dequeueOutput(): VideoOutput? = null
                override fun releaseOutput(index: Int, renderAtNs: Long?) = Unit
                override fun close() = Unit
            }
            VideoPlayer(VideoFormat("video/hevc", 640, 360, emptyList(), 60, videoColor = HdrVideoColor(metadata)),
                PlaybackClock(), { actualCode, actualDetail ->
                    failureCode.set(actualCode)
                    detail.set(actualDetail)
                    failed.countDown()
                }, decoder).use { player ->
                player.start()
                player.offer(AccessUnit(1_000_000, bytes, true))
                assertTrue(failed.await(3, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals(code, failureCode.get())
                assertTrue(detail.get(), detail.get().contains(": $reason: Video failure: "))
                val diagnostic = checkNotNull(VideoDiagnostic.fromLocalDetail(detail.get()))
                assertEquals(VideoStage.INPUT_BUFFER, diagnostic.stage)
                assertEquals(bytes.size, diagnostic.inputBytes)
                assertEquals(0, diagnostic.inputCapacity)
                assertEquals(0L, diagnostic.queuedFrames)
                assertEquals(0L, diagnostic.decodedFrames)
                assertEquals(0L, diagnostic.renderSubmitted)
                assertEquals(0, inputCalls.get())
            }
        }
    }

    @Test fun lostHdrOutputNeverSubmitsTheHeldPictureForRendering() {
        val rendered = java.util.concurrent.atomic.AtomicInteger()
        val failure = java.util.concurrent.atomic.AtomicReference<String>()
        val failed = java.util.concurrent.CountDownLatch(1)
        val pictures = java.util.concurrent.ConcurrentLinkedQueue<VideoOutput>()
        val decoder = object : VideoDecoder {
            override val description = "HDR output guard fixture"
            override fun dequeueInput() = 0
            override fun input(index: Int) = java.nio.ByteBuffer.allocate(32)
            override fun queueInput(index: Int, size: Int, ptsUs: Long, keyFrame: Boolean) {
                pictures.add(VideoOutput(0, ptsUs, size, 0))
            }
            override fun dequeueOutput() = pictures.poll()
            override fun releaseOutput(index: Int, renderAtNs: Long?) { if (renderAtNs != null) rendered.incrementAndGet() }
            override fun close() = Unit
        }
        VideoPlayer(VideoFormat("video/hevc", 640, 360, emptyList(), 60, videoColor = HdrVideoColor()),
            PlaybackClock(), { code, _ -> failure.set(code); failed.countDown() }, decoder,
            outputAvailable = { false }).use { player ->
            player.start()
            player.offer(AccessUnit(1000000, byteArrayOf(0, 0, 0, 1, 0x26, 1, 0x80.toByte()), true))
            assertTrue(failed.await(3, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals("UNSUPPORTED_CONFIG", failure.get())
            assertEquals(0, rendered.get())
        }
    }

    private fun output() = MediaFormat().apply {
        setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
        setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084)
        setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
    }
    @Test fun mediaFormatCarriesHdrProfileColorAndDescriptor() {
        val color = HdrVideoColor(HdrStaticMetadata(listOf(35400, 14600, 8500, 39850, 6550, 2300, 15635, 16450), 1000, 50, 1000, 400))
        val format = VideoPlayer.mediaFormat(VideoFormat("video/hevc", 1920, 1080, listOf(byteArrayOf(1)), 60, videoColor = color))
        assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10, format.getInteger(MediaFormat.KEY_PROFILE))
        assertEquals(MediaFormat.COLOR_TRANSFER_ST2084, format.getInteger(MediaFormat.KEY_COLOR_TRANSFER))
        assertEquals(25, format.getByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO)!!.remaining())
        AndroidVideoDecoder.validateColor(format, false)
        val sdr = VideoPlayer.mediaFormat(VideoFormat("video/avc", 640, 360, listOf(byteArrayOf(1)), 60))
        assertFalse(sdr.containsKey(MediaFormat.KEY_PROFILE))
        assertFalse(sdr.containsKey(MediaFormat.KEY_COLOR_TRANSFER))
    }
    @Test fun uncappedHdrMediaFormatPreservesRasterAndColorWithoutAFrameRateHint() {
        val format = VideoPlayer.mediaFormat(VideoFormat("video/hevc", 3456, 2160, emptyList(), 0,
            videoColor = HdrVideoColor()))
        assertFalse(format.containsKey(MediaFormat.KEY_FRAME_RATE))
        assertEquals(3456, format.getInteger(MediaFormat.KEY_WIDTH))
        assertEquals(2160, format.getInteger(MediaFormat.KEY_HEIGHT))
        assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10, format.getInteger(MediaFormat.KEY_PROFILE))
        AndroidVideoDecoder.validateColor(format, false)
    }
    @Test fun missingUnsupportedOrIncorrectOutputDoesNotVerifyToneMapping() {
        val input = MediaFormat()
        assertThrows(PlaybackException::class.java) { AndroidVideoDecoder.validateToneMapInput(input) }
        input.setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST, 0)
        assertThrows(PlaybackException::class.java) { AndroidVideoDecoder.validateToneMapInput(input) }
        input.setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        AndroidVideoDecoder.validateToneMapInput(input)
        assertThrows(PlaybackException::class.java) { AndroidVideoDecoder.validateColor(output(), true) }
        val sdr = output().apply {
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
        }
        AndroidVideoDecoder.validateColor(sdr, true)
        assertThrows(PlaybackException::class.java) { AndroidVideoDecoder.validateColor(sdr, false) }
        assertThrows(PlaybackException::class.java) { AndroidVideoDecoder.validateColor(MediaFormat(), false) }
    }
    @Test @Config(sdk = [30]) fun apiThirtyCannotClaimDecoderToneMapping() {
        val input = MediaFormat().apply { setInteger("color-transfer-request", MediaFormat.COLOR_TRANSFER_SDR_VIDEO) }
        assertThrows(PlaybackException::class.java) { AndroidVideoDecoder.validateToneMapInput(input) }
        assertThrows(IllegalArgumentException::class.java) {
            VideoPlayer.mediaFormat(VideoFormat("video/hevc", 640, 360, emptyList(), 60,
                videoColor = HdrVideoColor(), toneMapToSdr = true))
        }
    }
}

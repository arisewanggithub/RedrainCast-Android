package org.screencopy.receiver.ui

import android.app.Activity
import android.graphics.Rect
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.screencopy.receiver.media.PlaybackException
import org.screencopy.receiver.media.VideoFormat
import org.screencopy.receiver.protocol.HdrVideoColor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class VideoPresentationTest {
    @Test fun sdrTextureCornersStayVisibleAcrossPortraitLandscapePortraitAndEveryQuarterTurn() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            for ((width, height) in listOf(1920 to 1080, 1080 to 1920, 2236 to 1080, 1080 to 2236)) {
                for ((sourceWidth, sourceHeight) in listOf(1080 to 1920, 1080 to 2340, 641 to 1139)) {
                    val box = FrameLayout(controller.get())
                    val texture = TextureView(controller.get())
                    box.addView(texture)
                    for (rotation in listOf(0, 270, 0, 90, 180, 270)) {
                        box.layout(0, 0, width, height)
                        VideoPresentation.fit(texture, box, sourceWidth.toDouble() / sourceHeight, rotation)
                        box.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                        box.layout(0, 0, width, height)
                        val corners = floatArrayOf(0f, 0f, texture.width.toFloat(), 0f,
                            0f, texture.height.toFloat(), texture.width.toFloat(), texture.height.toFloat())
                        texture.matrix.mapPoints(corners)
                        for (index in corners.indices step 2) {
                            corners[index] += texture.left
                            corners[index + 1] += texture.top
                        }
                        verifyShownCorners(corners, width, height, sourceWidth, sourceHeight, rotation)
                    }
                }
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun acceptedColorNotCodecNameSelectsSdrTextureOrNativeHdrSurface() {
        val sdr = VideoFormat("video/hevc", 1080, 1920, emptyList(), 60)
        assertEquals(VideoPresentation.Mode.SDR_TEXTURE, VideoPresentation.mode(sdr))
        assertEquals(VideoPresentation.Mode.HDR_SURFACE,
            VideoPresentation.mode(sdr.copy(videoColor = HdrVideoColor())))
        assertEquals(VideoPresentation.Mode.HDR_SURFACE,
            VideoPresentation.mode(sdr.copy(videoColor = HdrVideoColor(), toneMapToSdr = true)))
    }

    @Test fun nonzeroHdrRotationFailsExplicitlyInsteadOfUsingTextureOrSilentlyDroppingRotation() {
        for (rotation in listOf(90, 180, 270)) {
            val error = assertThrows(PlaybackException::class.java) {
                VideoPresentation.requireSupportedRotation(true, rotation)
            }
            assertEquals("UNSUPPORTED_CONFIG", error.code)
            assertThrows(PlaybackException::class.java) {
                VideoPresentation.mode(VideoFormat("video/hevc", 1080, 1920, emptyList(), 60,
                    rotation, HdrVideoColor()))
            }
            VideoPresentation.requireSupportedRotation(false, rotation)
        }
        VideoPresentation.requireSupportedRotation(true, 0)
        assertTrue(org.robolectric.shadows.ShadowLog.getLogsForTag("ScreenCopyReceiver")
            .any { it.msg.contains("event=UNSUPPORTED_HDR_ROTATION rotation=270") })
    }

    @Test fun formerNative270GeometryPlacesEntireVideoOutsideParentInActualCompositorOrder() {
        // AOSP applies set(dsdx,dtdy,dtdx,dsdy), not the transposed order of the removed oracle.
        val dsdx = 0f
        val dtdx = 1f
        val dtdy = -1f
        val dsdy = 0f
        val xs = mutableListOf<Float>()
        val ys = mutableListOf<Float>()
        for ((x, y) in listOf(0f to 0f, 1080f to 0f, 0f to 1920f, 1080f to 1920f)) {
            xs.add(dsdx * x + dtdy * y)
            ys.add(dtdx * x + dsdy * y + 1080f)
        }
        val shown = Rect(xs.min().toInt(), ys.min().toInt(), xs.max().toInt(), ys.max().toInt())
        assertEquals(Rect(-1920, 1080, 0, 2160), shown)
        assertFalse(Rect.intersects(shown, Rect(0, 0, 1920, 1080)))
    }

    @Test fun decoderKeepsNaturalRasterAndCsdUnrotatedForEveryInitialRotation() {
        val configuration = byteArrayOf(0, 0, 0, 1, 103)
        for (mime in listOf("video/avc", "video/hevc")) for (rotation in listOf(0, 90, 180, 270)) {
            val source = VideoFormat(mime, 1080, 1920, listOf(configuration), 60, rotation)
            val actual = org.screencopy.receiver.media.VideoPlayer.mediaFormat(source)
            assertEquals(0, actual.getInteger(android.media.MediaFormat.KEY_ROTATION))
            assertEquals(1080, actual.getInteger(android.media.MediaFormat.KEY_WIDTH))
            assertEquals(1920, actual.getInteger(android.media.MediaFormat.KEY_HEIGHT))
            assertEquals(60, actual.getInteger(android.media.MediaFormat.KEY_FRAME_RATE))
            assertEquals(rotation, source.rotation)
            val outputConfiguration = ByteArray(configuration.size)
            checkNotNull(actual.getByteBuffer("csd-0")).get(outputConfiguration)
            assertArrayEquals(configuration, outputConfiguration)
        }
    }

    @Test fun paddedDecoderCropRetainsItsVisibleRasterForFitting() {
        val actual = android.media.MediaFormat.createVideoFormat("video/avc", 1104, 1936).apply {
            setInteger(android.media.MediaFormat.KEY_CROP_LEFT, 12)
            setInteger(android.media.MediaFormat.KEY_CROP_RIGHT, 1091)
            setInteger(android.media.MediaFormat.KEY_CROP_TOP, 8)
            setInteger(android.media.MediaFormat.KEY_CROP_BOTTOM, 1927)
        }
        org.screencopy.receiver.media.AndroidVideoDecoder.validateRaster(actual, 1080, 1920)
        assertEquals(Rect(0, 0, 1920, 1080),
            checkNotNull(VideoPresentation.geometry(1920, 1080, 1080, 1920, 270)).destination)
    }

    @Test fun unlaidOutWindowDefersFitAndInvalidRotationIsRejected() {
        assertNull(VideoPresentation.geometry(0, 1080, 1080, 1920, 270))
        assertThrows(IllegalArgumentException::class.java) {
            VideoPresentation.geometry(1920, 1080, 1080, 1920, 45)
        }
    }

    @Test fun presentationStatisticsMatchAcceptedRouteOnlyWithObservedTransferMetadata() {
        val sdr = VideoFormat("video/avc", 640, 360, emptyList(), 60)
        val hdr = sdr.copy(mime = "video/hevc", videoColor = HdrVideoColor())
        val actual = android.media.MediaFormat().apply {
            setInteger(android.media.MediaFormat.KEY_COLOR_TRANSFER, android.media.MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            setInteger(android.media.MediaFormat.KEY_COLOR_STANDARD, android.media.MediaFormat.COLOR_STANDARD_BT709)
            setInteger(android.media.MediaFormat.KEY_COLOR_RANGE, android.media.MediaFormat.COLOR_RANGE_LIMITED)
        }
        assertEquals(VideoPresentation.Mode.SDR_TEXTURE, VideoPresentation.mode(sdr))
        assertEquals(org.screencopy.receiver.media.VideoPresentationOutput.Sdr,
            org.screencopy.receiver.media.AndroidVideoDecoder.outputStatistics(actual, sdr.mime, false, false).presentation?.output)
        actual.setInteger(android.media.MediaFormat.KEY_COLOR_TRANSFER, android.media.MediaFormat.COLOR_TRANSFER_ST2084)
        actual.setInteger(android.media.MediaFormat.KEY_COLOR_STANDARD, android.media.MediaFormat.COLOR_STANDARD_BT2020)
        assertEquals(VideoPresentation.Mode.HDR_SURFACE, VideoPresentation.mode(hdr))
        assertEquals(org.screencopy.receiver.media.VideoPresentationOutput.Hdr,
            org.screencopy.receiver.media.AndroidVideoDecoder.outputStatistics(actual, hdr.mime, true, false).presentation?.output)
        assertNull(org.screencopy.receiver.media.AndroidVideoDecoder
            .outputStatistics(android.media.MediaFormat(), sdr.mime, false, false).presentation)
        assertNull(org.screencopy.receiver.media.AndroidVideoDecoder
            .outputStatistics(android.media.MediaFormat(), hdr.mime, true, false).presentation)
    }

    private fun verifyShownCorners(corners: FloatArray, width: Int, height: Int,
        sourceWidth: Int, sourceHeight: Int, rotation: Int) {
        val shownWidth = if (rotation % 180 == 0) sourceWidth else sourceHeight
        val shownHeight = if (rotation % 180 == 0) sourceHeight else sourceWidth
        val scale = minOf(width.toDouble() / shownWidth, height.toDouble() / shownHeight)
        val fittedWidth = (shownWidth * scale).toInt()
        val fittedHeight = (shownHeight * scale).toInt()
        val left = (width - fittedWidth) / 2f
        val top = (height - fittedHeight) / 2f
        val expected = listOf(left to top, left + fittedWidth to top,
            left to top + fittedHeight, left + fittedWidth to top + fittedHeight)
        val order = when (rotation) {
            90 -> listOf(1, 3, 0, 2)
            180 -> listOf(3, 2, 1, 0)
            270 -> listOf(2, 0, 3, 1)
            else -> listOf(0, 1, 2, 3)
        }
        for (index in 0..3) {
            assertEquals(expected[order[index]].first, corners[index * 2], 1f)
            assertEquals(expected[order[index]].second, corners[index * 2 + 1], 1f)
            assertTrue(corners[index * 2] in -1f..width + 1f)
            assertTrue(corners[index * 2 + 1] in -1f..height + 1f)
        }
    }
}

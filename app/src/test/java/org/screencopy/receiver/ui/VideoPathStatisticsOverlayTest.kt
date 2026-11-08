package org.screencopy.receiver.ui

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import dev.redrain.cast.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.screencopy.receiver.media.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@ConscryptMode(ConscryptMode.Mode.OFF)
class VideoPathStatisticsOverlayTest {
    private fun snapshot() = PlaybackStatisticsSampler().sample(1000,
        PlaybackStatisticsInput(1, 1, 1, "Playing", "h265", null, 640, 360, 60,
            8000000, null, 90, "auto", 0, 0, 0, 0, null, null, null, null, null, 0,
            decodedVideo = DecodedVideoStatistics(VideoStreamFormat.Hdr10, VideoTransfer.Pq, "Main 10", 10),
            presentation = VideoPresentationStatistics(VideoPresentationOutput.Hdr)))

    @Test fun addedFieldsUseResourcesAcrossLayoutModesAndClearOnHide() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val parent = FrameLayout(activity)
            val overlay = PlaybackStatisticsOverlay(activity)
            parent.addView(overlay, FrameLayout.LayoutParams(-1, -2))
            activity.setContentView(parent)
            val density = activity.resources.displayMetrics.density
            for ((widthDp, heightDp) in listOf(800 to 800, 315 to 682, 795 to 384, 384 to 795)) {
                val width = (widthDp * density).toInt()
                val height = (heightDp * density).toInt()
                overlay.show(snapshot(), width, height)
                for ((id, resource) in listOf("videoDynamicRange" to R.string.rc_stats_stream_range,
                    "videoDecodeFormat" to R.string.rc_stats_profile_depth, "presentationOutput" to R.string.rc_stats_output)) {
                    assertEquals(activity.getString(resource),
                        overlay.findViewWithTag<TextView>("label:$id").contentDescription.toString())
                    val cell = overlay.findViewWithTag<TextView>("value:$id")
                    assertNotNull(cell)
                    assertFalse(cell.isClickable)
                    assertFalse(cell.isFocusable)
                }
                val separator = if (minOf(widthDp, heightDp) < 600) "·" else " · "
                assertEquals("HDR10${separator}PQ", overlay.findViewWithTag<TextView>("value:videoDynamicRange").text.toString())
                assertEquals("Main 10$separator${activity.getString(R.string.rc_stats_bit_depth, 10)}",
                    overlay.findViewWithTag<TextView>("value:videoDecodeFormat").text.toString())
                assertEquals(activity.getString(R.string.rc_stats_hdr_output),
                    overlay.findViewWithTag<TextView>("value:presentationOutput").text.toString())
            }
            overlay.show(snapshot().copy(input = snapshot().input.copy(decodedVideo = null, presentation = null)), 800, 800)
            for (id in listOf("videoDynamicRange", "videoDecodeFormat", "presentationOutput"))
                assertEquals(activity.getString(R.string.rc_stats_unknown),
                    overlay.findViewWithTag<TextView>("value:$id").text.toString())
            overlay.show(null, 800, 800)
            assertEquals(View.GONE, overlay.visibility)
            assertEquals("", overlay.text)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun detailedViewUsesSameMetadataAndHonestUnknownComponentFormatting() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val details = PlaybackStatisticsDetails(controller.get())
            val partial = snapshot().copy(input = snapshot().input.copy(
                decodedVideo = snapshot().input.decodedVideo!!.copy(bitDepth = null), presentation = null))
            details.show(partial, 1080, 2236)
            assertTrue(details.panel.text.contains("Profile / depth: Main 10 · N/A"))
            assertTrue(details.panel.text.contains("Output: N/A"))
            assertTrue(details.panel.text.contains("Render target: N/A"))
            details.show(null, 0, 0)
            assertEquals(View.GONE, details.panel.visibility)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun legacyLayoutRetainsMetadataRowsAtPhoneDensityAndFontScaleInBothOrientations() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val configuration = android.content.res.Configuration(activity.resources.configuration).apply {
                densityDpi = 450
                fontScale = 1.15f
            }
            val context = activity.createConfigurationContext(configuration)
            val parent = FrameLayout(context)
            val overlay = PlaybackStatisticsOverlay(context)
            parent.addView(overlay, FrameLayout.LayoutParams(-1, -2))
            activity.setContentView(parent)
            val sample = snapshot().copy(input = snapshot().input.copy(
                encodedWidth = 2496, encodedHeight = 1664, audioCodec = "raw",
                audio = AudioStatistics(89.6, 50.0, 12.0, 135.3, 5.0, 1049800, 2310.2),
                videoQueue = EncodedQueueStatistics(2, 2048, 20000, 25, 8 * 1024 * 1024),
                audioQueue = EncodedQueueStatistics(1, 3840, 20000, 10, 512 * 1024),
                rttMs = 78, controlAgeMs = 266))
            for ((width, height) in listOf(2236 to 1080, 1080 to 2236)) {
                overlay.show(sample, width, height,
                    measurements = PlaybackDisplayMeasurements(displayOutput = PixelDimensions(3840, 2160)))
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                parent.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                parent.layout(0, 0, width, height)
                assertTrue("Panel exceeds viewport at ${width}x$height", overlay.height <= height)
                assertTrue("Panel obscures half the player at ${width}x$height: ${overlay.width}x${overlay.height}",
                    overlay.width.toLong() * overlay.height < width.toLong() * height * 0.50)
                for (id in listOf("videoDynamicRange", "videoDecodeFormat", "presentationOutput")) {
                    val cell = overlay.findViewWithTag<TextView>("value:$id")
                    assertTrue(cell.width > 0 && cell.height > 0)
                    assertEquals(10f * context.resources.displayMetrics.scaledDensity, cell.textSize, 0.1f)
                    assertTrue(cell.layout.height <= cell.height - cell.compoundPaddingTop - cell.compoundPaddingBottom)
                    repeat(cell.lineCount) { line ->
                        assertTrue(cell.layout.getLineRight(line) <= cell.width -
                            cell.compoundPaddingLeft - cell.compoundPaddingRight + 1)
                        assertEquals(0, cell.layout.getEllipsisCount(line))
                    }
                    assertEquals(1, overlay.findViewWithTag<TextView>("label:$id").lineCount)
                }
            }
        } finally { controller.pause().stop().destroy() }
    }
}

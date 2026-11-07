package org.screencopy.receiver.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TableLayout
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.screencopy.receiver.media.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class PlaybackStatisticsOverlayTest {
    private fun snapshot() = PlaybackStatisticsSnapshot(
        PlaybackStatisticsInput(7, 2, 3, "Playing", "h264", "raw", 888, 1920, 60,
            8_000_000, null, 270, "auto", 282, 200, 1_000_000, 500_000,
            VideoStatistics(282, 270, 14, 268, 1_234_000),
            AudioStatistics(89.6, 50.0, 12.0, 135.3, 5.0, 1_049_800, 2310.2),
            EncodedQueueStatistics(2, 2048, 20000, 25, 8 * 1024 * 1024),
            EncodedQueueStatistics(1, 3840, 20000, 10, 512 * 1024), 78, 266, 125, 150),
        11.8, 11.8, 8.9, 0.04, 1.4, 25.0, 12.3, 0.0, 184.2, 184.2, 234.4)

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun nativeRenderedGridFitsAllMetricsAndLastRowWithoutShrinkingOrClippingInnerText() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val container = FrameLayout(activity).apply { setBackgroundColor(Color.rgb(38, 72, 91)) }
        val overlay = PlaybackStatisticsOverlay(activity)
        container.addView(overlay, FrameLayout.LayoutParams(-1, -2, Gravity.TOP or Gravity.START))
        activity.setContentView(container)
        val density = activity.resources.displayMetrics.density
        try {
            for ((widthDp, heightDp) in listOf(800 to 800, 1920 to 1080)) {
                val width = (widthDp * density).toInt()
                val height = (heightDp * density).toInt()
                overlay.show(snapshot(), width, height)
                shadowOf(Looper.getMainLooper()).idle()
                container.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                container.layout(0, 0, width, height)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    container.draw(Canvas(bitmap))
                    val directory = java.io.File("build/reports/playback-statistics").apply { mkdirs() }
                    java.io.File(directory, "overlay-${widthDp}x$heightDp.png").outputStream().use {
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally { bitmap.recycle() }
                val textViews = descendants(overlay).filterIsInstance<TextView>().filter { it.visibility == View.VISIBLE }
                assertTrue(overlay.text.contains("Playback statistics"))
                assertFalse(overlay.text.contains("DESIGN PREVIEW"))
                assertTrue(overlay.text.contains("Occluded: N/A"))
                val complete = checkNotNull(overlay.display)
                for (field in complete.kpis + complete.totals + complete.video + complete.audio +
                    complete.footer + complete.details) {
                    val cell = overlay.findViewWithTag<TextView>("value:${field.id}")
                    assertNotNull("Missing metric ${field.id}", cell)
                    assertTrue("Metric ${field.id} has no visible layout", cell.width > 0 && cell.height > 0)
                }
                complete.connections.forEach { connection ->
                    for (column in 0..4) {
                        val cell = overlay.findViewWithTag<TextView>("connection:${connection.id}:$column")
                        assertNotNull(cell)
                        assertTrue("Missing connection cell ${connection.id}:$column", cell.width > 0 && cell.height > 0)
                    }
                }
                descendants(overlay).filterIsInstance<TextView>().forEach {
                    assertTrue("Text cell has no measured area: ${it.text}", it.width > 0 && it.height > 0)
                }
                assertTrue(overlay.text.contains("Playback epoch: N/A"))
                val kpiGrid = overlay.findViewWithTag<ViewGroup>("statisticsKpis")
                assertEquals(2, kpiGrid.childCount)
                assertEquals(4, (kpiGrid.getChildAt(0) as ViewGroup).childCount)
                assertEquals(4, (kpiGrid.getChildAt(1) as ViewGroup).childCount)
                val totalsGrid = overlay.findViewWithTag<ViewGroup>("statisticsTotals")
                val totalLabels = totalsGrid.getChildAt(0) as ViewGroup
                val totalValues = totalsGrid.getChildAt(1) as ViewGroup
                assertEquals(3, totalLabels.childCount)
                assertEquals(3, totalValues.childCount)
                assertEquals(listOf("Decoded", "Presented", "Skipped"),
                    (0 until 3).map { (totalLabels.getChildAt(it) as TextView).text.toString() })
                val totalWidths = (0 until 3).map { totalValues.getChildAt(it).width }
                assertTrue(totalWidths.maxOrNull()!! - totalWidths.minOrNull()!! <= 1)
                for (index in 0 until 3) {
                    assertEquals(totalLabels.getChildAt(index).left, totalValues.getChildAt(index).left)
                    assertTrue(totalValues.top >= totalLabels.bottom)
                }
                assertTrue(overlay.findViewWithTag<View>("connectionTable") is ViewGroup)
                assertTrue("Panel clipped at ${widthDp}x$heightDp: ${overlay.height}>$height", overlay.height <= height)
                assertTrue("Panel should remain top-left and width-bounded", overlay.width <= minOf(width, (1100 * density).toInt()))
                for (text in textViews) {
                    val expectedSp = if (minOf(widthDp, heightDp) < 600) 10f else 16f
                    assertEquals(expectedSp * activity.resources.displayMetrics.scaledDensity, text.textSize, 0.1f)
                    assertFalse(text.isFocusable)
                    assertFalse(text.isClickable)
                    assertTrue("Missing native text layout: ${text.text}", text.layout != null)
                    val layout = text.layout
                    assertTrue("Inner text height clipped at ${widthDp}x$heightDp: ${text.text} layout=${layout.height}, view=${text.height}, padding=${text.compoundPaddingTop + text.compoundPaddingBottom}", layout.height <=
                        text.height - text.compoundPaddingTop - text.compoundPaddingBottom)
                    for (line in 0 until layout.lineCount) {
                        assertTrue("Inner line horizontally clipped: ${text.text}",
                            layout.getLineRight(line) <= text.width - text.compoundPaddingLeft - text.compoundPaddingRight + 1)
                        assertTrue("Inner line has ellipsis: ${text.text}", layout.getEllipsisCount(line) == 0)
                    }
                    var y = text.top
                    var parent = text.parent
                    while (parent is View && parent !== container) { y += parent.top; parent = parent.parent }
                    assertTrue("Last row outside canvas: ${text.text}", y + text.height <= height)
                }
                val videoValue = overlay.findViewWithTag<TextView>("value:videoCodec")
                val sizeValue = overlay.findViewWithTag<TextView>("value:encodedSize")
                assertEquals(videoValue.right, sizeValue.right)
                assertEquals(Gravity.END or Gravity.CENTER_VERTICAL, videoValue.gravity)
                val kpiValues = kpiGrid.getChildAt(1) as ViewGroup
                val kpiViews = (0 until 4).map { kpiValues.getChildAt(it) }
                assertTrue(kpiViews.maxOf { it.width } - kpiViews.minOf { it.width } <= 1)
                assertTrue(kpiViews.maxOf { it.top } - kpiViews.minOf { it.top } <= 1)
                assertFooterAligned(overlay)
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun fullPhoneStatisticsUseDenseTextAndFitGalaxyCanvasAt115PercentFont() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val configuration = android.content.res.Configuration(activity.resources.configuration).apply {
            densityDpi = 450
            fontScale = 1.15f
        }
        val context = activity.createConfigurationContext(configuration)
        val parent = FrameLayout(context).apply { setBackgroundColor(Color.rgb(38, 72, 91)) }
        val overlay = PlaybackStatisticsOverlay(context)
        parent.addView(overlay, FrameLayout.LayoutParams(-1, -2))
        activity.setContentView(parent)
        try {
            for ((width, height) in listOf(2236 to 1080, 1080 to 2236)) {
                overlay.show(snapshot().copy(input = snapshot().input.copy(encodedWidth = 2496, encodedHeight = 1664,
                    video = VideoStatistics(12345678, 12345670, 125, 12345553, 1_234_000))),
                    width, height, measurements = PlaybackDisplayMeasurements(displayOutput = PixelDimensions(3840, 2160)))
                shadowOf(Looper.getMainLooper()).idle()
                parent.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                parent.layout(0, 0, width, height)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    parent.draw(Canvas(bitmap))
                    val directory = java.io.File("build/reports/playback-statistics").apply { mkdirs() }
                    java.io.File(directory, "phone-full-dense-${width}x$height-font115.png").outputStream().use {
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally { bitmap.recycle() }
                assertNotNull(overlay.findViewWithTag<View>("statisticsKpis"))
                assertNotNull(overlay.findViewWithTag<View>("statisticsTotals"))
                assertNotNull(overlay.findViewWithTag<View>("connectionTable"))
                assertTrue(overlay.text.contains("Encoded size: 2496 × 1664"))
                assertTrue(overlay.text.contains("Network loss"))
                assertTrue(overlay.text.contains("Inserted silence"))
                assertTrue(overlay.text.contains("Device played"))
                assertTrue(overlay.text.contains("Occluded: N/A"))
                assertFalse(overlay.text.contains("Render target"))
                assertNull(overlay.findViewWithTag<View>("value:renderTarget"))
                assertTrue(overlay.text.contains("Display mode: 3840 × 2160 px"))
                assertEquals("Display px", overlay.findViewWithTag<TextView>("label:displayOutput").text.toString())
                assertFalse(overlay.text.contains("App view size"))
                assertFooterAligned(overlay)
                assertTrue("Phone panel must leave part of the player uncovered: panel=${overlay.width}x${overlay.height}, canvas=${width}x$height",
                    overlay.width.toLong() * overlay.height < width.toLong() * height * 0.50)
                assertTrue(overlay.width <= (width * if (width > height) 0.65f else 0.9f).toInt())
                assertTrue("Full panel exceeds available height", overlay.height <= height)
                descendants(overlay).filterIsInstance<TextView>().forEach { text ->
                    assertEquals(10f * context.resources.displayMetrics.scaledDensity, text.textSize, 0.1f)
                    assertTrue("Full phone text clipped at ${width}x$height: ${text.text} layout=${text.layout.height} height=${text.height}", text.layout.height <=
                        text.height - text.compoundPaddingTop - text.compoundPaddingBottom)
                    repeat(text.lineCount) { line ->
                        assertTrue(text.layout.getLineRight(line) <= text.width - text.compoundPaddingLeft -
                            text.compoundPaddingRight + 1)
                        assertEquals(0, text.layout.getEllipsisCount(line))
                    }
                    if ((text.tag as? String)?.startsWith("label:") == true)
                        assertEquals("Short phone labels must not wrap at ${width}x$height: ${text.text}", 1, text.lineCount)
                }
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun fullDiagnosticsScrollsToEveryMetricAt115PercentWithoutChangingReceiverCanvas() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val configuration = android.content.res.Configuration(activity.resources.configuration).apply {
            densityDpi = 450
            fontScale = 1.15f
        }
        val context = activity.createConfigurationContext(configuration)
        val parent = FrameLayout(context)
        val details = PlaybackStatisticsDetails(context).apply { isSmoothScrollingEnabled = false }
        parent.addView(details, FrameLayout.LayoutParams(-1, -1))
        activity.setContentView(parent)
        try {
            for ((width, height) in listOf(2236 to 1080, 1080 to 2236)) {
                details.show(snapshot(), width, height)
                shadowOf(Looper.getMainLooper()).idle()
                parent.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                parent.layout(0, 0, width, height)
                assertTrue(details.panel.text.contains("Configured bitrate"))
                assertTrue(details.panel.text.contains("Network loss"))
                assertTrue(details.panel.text.contains("Render target: N/A"))
                assertEquals("N/A", details.panel.findViewWithTag<TextView>("value:renderTarget").text.toString())
                assertEquals("$width × $height px",
                    details.panel.display!!.diagnostics.first { it.id == "receiverCanvas" }.value)
                val totalsGrid = details.panel.findViewWithTag<ViewGroup>("statisticsTotals")
                val labels = totalsGrid.getChildAt(0) as ViewGroup
                val values = totalsGrid.getChildAt(1) as ViewGroup
                assertEquals(3, values.childCount)
                assertTrue(values.top >= labels.bottom)
                descendants(details.panel).filterIsInstance<TextView>().forEach { text ->
                    assertTrue("Full diagnostic inner text clipped: ${text.text}", text.layout.height <=
                        text.height - text.compoundPaddingTop - text.compoundPaddingBottom)
                    repeat(text.lineCount) { assertEquals(0, text.layout.getEllipsisCount(it)) }
                }
                details.fullScroll(View.FOCUS_DOWN)
                val footer = details.panel.findViewWithTag<View>("statisticsFooter")
                val visible = android.graphics.Rect(0, 0, footer.width, footer.height)
                details.offsetDescendantRectToMyCoords(footer, visible)
                visible.offset(-details.scrollX, -details.scrollY)
                assertTrue("Footer must be fully inside its scroll viewport: rect=$visible, viewport=${details.height}, scroll=${details.scrollY}",
                    visible.top >= details.paddingTop && visible.bottom <= details.height - details.paddingBottom)
                assertFalse(details.canScrollVertically(1))
            }
            details.show(null, 0, 0)
            assertEquals("", details.panel.text)
            assertEquals(View.GONE, details.panel.visibility)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun panelNeverInterceptsInputAndSkippedColorIsNotAnInventedHealthIndicator() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val container = FrameLayout(activity)
        var touched = false
        val player = View(activity).apply { setOnTouchListener { _, _ -> touched = true; true } }
        val overlay = PlaybackStatisticsOverlay(activity)
        container.addView(player, FrameLayout.LayoutParams(-1, -1))
        container.addView(overlay, FrameLayout.LayoutParams(-1, -2))
        activity.setContentView(container)
        overlay.show(snapshot(), 800, 800)
        container.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        container.layout(0, 0, 800, 800)
        val positive = overlay.findViewWithTag<TextView>("value:skippedRecent").currentTextColor
        overlay.show(snapshot().copy(recentSkippedPercent = 0.0), 800, 800)
        assertNotEquals(positive, overlay.findViewWithTag<TextView>("value:skippedRecent").currentTextColor)
        val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 10f, 10f, 0)
        try { container.dispatchTouchEvent(event); assertTrue(touched) } finally { event.recycle() }
        overlay.show(null, 0, 0)
        assertEquals(View.GONE, overlay.visibility)
        assertEquals("", overlay.text)
        controller.pause().stop().destroy()
    }

    private fun assertFooterAligned(overlay: PlaybackStatisticsOverlay) {
        for ((key, expected) in listOf(
            "statisticsFooter" to listOf("State", "Generation", "Playback epoch", "Occluded"),
            "statisticsPlatformDetails" to listOf("Scheduled frames", "Audio epoch", "Device played"))) {
            val grid = overlay.findViewWithTag<ViewGroup>(key)
            val labels = grid.getChildAt(0) as ViewGroup
            val values = grid.getChildAt(1) as ViewGroup
            assertEquals(expected.size, labels.childCount)
            assertEquals(expected.size, values.childCount)
            assertTrue(values.top >= labels.bottom)
            val widths = (0 until values.childCount).map { values.getChildAt(it).width }
            assertTrue(widths.maxOrNull()!! - widths.minOrNull()!! <= 1)
            for (index in expected.indices) {
                val label = labels.getChildAt(index) as TextView
                val value = values.getChildAt(index) as TextView
                val compact = label.textSize <= 12f * label.resources.displayMetrics.scaledDensity
                val caption = if (compact && expected[index] == "Playback epoch") "Play epoch" else expected[index]
                assertEquals(caption, label.text.toString())
                assertEquals(expected[index], label.contentDescription.toString())
                assertEquals("Values need the same column origin as their title", label.left, value.left)
                assertEquals(label.width, value.width)
                assertEquals(Gravity.START or Gravity.CENTER_VERTICAL, value.gravity)
                assertEquals(label.compoundPaddingLeft + label.layout.getLineLeft(0),
                    value.compoundPaddingLeft + value.layout.getLineLeft(0), 0.1f)
            }
        }
        assertEquals("Playing", overlay.findViewWithTag<TextView>("value:state").text.toString())
    }
}

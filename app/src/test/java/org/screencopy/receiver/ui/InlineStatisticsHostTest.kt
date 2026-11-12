package org.screencopy.receiver.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Canvas
import android.os.Looper
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import dev.redrain.cast.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.util.ReflectionHelpers
import org.screencopy.receiver.media.PlaybackDisplayMeasurements
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@ConscryptMode(ConscryptMode.Mode.OFF)
class InlineStatisticsHostTest {
    private class Targets : StatisticsSurfaceTargets {
        override val supported = true
        val creates = AtomicInteger()
        val detaches = AtomicInteger()
        val releases = CountDownLatch(1)
        override fun create(parent: SurfaceView, width: Int, height: Int,
            detachedCommitted: () -> Unit): StatisticsSurfaceTarget {
            creates.incrementAndGet()
            return object : StatisticsSurfaceTarget {
                override fun show() {}
                override fun draw(content: (Canvas) -> Unit) {}
                override fun detach() { detaches.incrementAndGet() }
                override fun release() { releases.countDown() }
            }
        }
    }

    @Test fun declassifiedParentUsesSecurePassThroughPanelAndTracksInsetAnchorGeometry() {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        val activity = controller.get()
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val protection = StatisticsWindowProtection.forWindow(activity.window)
        val root = FrameLayout(activity)
        val anchor = FrameLayout(activity)
        val inline = PlaybackStatisticsSurface(activity)
        inline.bindWindow(protection)
        anchor.addView(inline)
        root.addView(anchor, FrameLayout.LayoutParams(600, 400).apply { leftMargin = 27; topMargin = 49 })
        activity.setContentView(root)
        controller.start().resume().visible()
        assertTrue(protection.clearSecureFlag())
        val targets = Targets()
        var secureOnFirstAttachment = false
        val host = InlineStatisticsHost(activity, anchor, inline, protection,
            createPanel = { PlaybackStatisticsSurface(activity, false, targets).apply {
                getChildAt(0).visibility = View.GONE
                addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(view: View) {
                        val attributes = view.rootView.layoutParams as WindowManager.LayoutParams
                        secureOnFirstAttachment =
                            attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
                    }
                    override fun onViewDetachedFromWindow(view: View) {}
                })
            } })
        try {
            root.measure(View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 900, 800)
            host.start(900, 800, PlaybackDisplayMeasurements(), { true }) { null }
            shadowOf(Looper.getMainLooper()).idle()
            val dialog = checkNotNull(host.dialog)
            val panel = checkNotNull(host.panel)
            val window = checkNotNull(dialog.window)
            assertTrue(dialog.isShowing)
            assertTrue("FLAG_SECURE must precede the very first attachment", secureOnFirstAttachment)
            assertTrue(StatisticsWindowProtection.forWindow(window).canHostStatistics(panel))
            assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
            val attributes = window.attributes
            assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_PANEL, attributes.type)
            assertSame(anchor.windowToken, attributes.token)
            val required = WindowManager.LayoutParams.FLAG_SECURE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            assertEquals(required, attributes.flags and required)
            assertEquals(0, attributes.flags and (WindowManager.LayoutParams.FLAG_DIM_BEHIND or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN))
            assertEquals(0, attributes.fitInsetsTypes)
            assertEquals(Gravity.TOP or Gravity.LEFT, attributes.gravity)
            val location = IntArray(2)
            anchor.getLocationInWindow(location)
            assertEquals(location[0], attributes.x)
            assertEquals(location[1], attributes.y)
            assertEquals(600, attributes.width)
            assertEquals(400, attributes.height)
            panel.start { null }
            panel.surfaceCreated(panel.holder)
            panel.surfaceChanged(panel.holder, 0, 600, 400)
            assertEquals(1, targets.creates.get())
            anchor.layout(61, 93, 461, 693)
            root.viewTreeObserver.dispatchOnGlobalLayout()
            assertSame(dialog, host.dialog)
            anchor.getLocationInWindow(location)
            assertEquals(location[0], window.attributes.x)
            assertEquals(location[1], window.attributes.y)
            assertEquals(400, window.attributes.width)
            assertEquals(600, window.attributes.height)
            controller.pause()
            assertTrue("Temporary player UI pause must not dismiss the HUD", dialog.isShowing)
            host.stop()
            assertFalse(dialog.isShowing)
            assertNull(host.panel)
            assertTrue(targets.detaches.get() > 0)
            assertTrue(targets.releases.await(2, TimeUnit.SECONDS))
            root.viewTreeObserver.dispatchOnGlobalLayout()
            shadowOf(Looper.getMainLooper()).idle()
            assertNull("Stale layout/dispose work cannot recreate a stopped panel", host.dialog)
            host.start(900, 800, PlaybackDisplayMeasurements(), { true }) { null }
            assertNotSame(dialog, host.dialog)
        } finally {
            host.stop()
            controller.stop().destroy()
        }
    }

    @Test fun newlyWrappedAttachedDecorCannotAuthorizeInlineButGetsAFreshPanel() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val root = FrameLayout(activity)
        val inline = PlaybackStatisticsSurface(activity)
        root.addView(inline)
        activity.setContentView(root)
        val wrapper = checkNotNull(Dialog(activity).window)
        wrapper.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        ReflectionHelpers.setField(wrapper, "mDecor", activity.window.decorView)
        val protection = StatisticsWindowProtection.forWindow(wrapper)
        assertFalse(protection.canHostStatistics(inline))
        val host = InlineStatisticsHost(activity, root, inline, protection)
        try {
            root.layout(0, 0, 500, 300)
            host.start(500, 300, PlaybackDisplayMeasurements(), { true }) { null }
            val panel = checkNotNull(host.panel)
            val window = checkNotNull(host.dialog?.window)
            assertNotSame(wrapper, window)
            assertTrue(StatisticsWindowProtection.forWindow(window).canHostStatistics(panel))
            root.removeAllViews()
            host.stop()
            assertNull(host.dialog)
        } finally {
            host.stop()
            controller.pause().stop().destroy()
        }
    }

    @Test fun missingTokenAndFailedSetupAreExplicitAndStopAllowsFreshRetry() {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        val activity = controller.get()
        val root = FrameLayout(activity)
        val inline = PlaybackStatisticsSurface(activity)
        root.addView(inline)
        activity.setContentView(root)
        var attempts = 0
        val host = InlineStatisticsHost(activity, root, inline, StatisticsWindowProtection.forWindow(activity.window),
            createPanel = {
                attempts++
                throw WindowManager.BadTokenException("synthetic stale parent")
            })
        try {
            host.start(500, 300, PlaybackDisplayMeasurements(), { true }) { null }
            assertEquals(0, attempts)
            assertEquals(activity.getString(R.string.rc_statistics_host_unavailable),
                (inline.getChildAt(1) as TextView).text.toString())
            controller.start().resume().visible()
            root.layout(0, 0, 500, 300)
            root.viewTreeObserver.dispatchOnGlobalLayout()
            assertEquals(1, attempts)
            assertNull(host.dialog)
            assertNull(host.panel)
            host.start(500, 300, PlaybackDisplayMeasurements(), { true }) { null }
            assertEquals("Do not continuously retry failed window creation", 1, attempts)
            host.stop()
            host.start(500, 300, PlaybackDisplayMeasurements(), { true }) { null }
            assertEquals(2, attempts)
        } finally {
            host.stop()
            controller.pause().stop().destroy()
        }
    }

    @Test fun anchorDetachRetiresPanelAndReattachCreatesANewSecureWindow() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val root = FrameLayout(activity)
        val anchor = FrameLayout(activity)
        val inline = PlaybackStatisticsSurface(activity)
        anchor.addView(inline)
        root.addView(anchor, FrameLayout.LayoutParams(500, 300))
        activity.setContentView(root)
        val host = InlineStatisticsHost(activity, anchor, inline, StatisticsWindowProtection.forWindow(activity.window))
        try {
            anchor.layout(0, 0, 500, 300)
            host.start(500, 300, PlaybackDisplayMeasurements(), { true }) { null }
            val first = checkNotNull(host.dialog)
            root.removeView(anchor)
            assertFalse(first.isShowing)
            assertNull(host.panel)
            assertNull(host.dialog)
            root.addView(anchor)
            anchor.layout(0, 0, 500, 300)
            root.viewTreeObserver.dispatchOnGlobalLayout()
            val replacement = checkNotNull(host.dialog)
            assertNotSame(first, replacement)
            assertTrue(StatisticsWindowProtection.forWindow(checkNotNull(replacement.window))
                .canHostStatistics(checkNotNull(host.panel)))
            replacement.dismiss()
            host.stop()
            host.start(500, 300, PlaybackDisplayMeasurements(), { true }) { null }
            val latest = checkNotNull(host.dialog)
            shadowOf(Looper.getMainLooper()).idle()
            assertSame("A queued old Dialog dismissal must not retire the replacement", latest, host.dialog)
            host.stop()
            root.viewTreeObserver.dispatchOnGlobalLayout()
            assertNull(host.dialog)
        } finally {
            host.stop()
            controller.pause().stop().destroy()
        }
    }

    @Test fun initiallySecureEligibleWindowKeepsExistingInlinePath() {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        val activity = controller.get()
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val protection = StatisticsWindowProtection.forWindow(activity.window)
        val root = FrameLayout(activity)
        val inline = PlaybackStatisticsSurface(activity)
        inline.bindWindow(protection)
        root.addView(inline)
        activity.setContentView(root)
        controller.start().resume().visible()
        val host = InlineStatisticsHost(activity, root, inline, protection)
        try {
            host.start(500, 300, PlaybackDisplayMeasurements(), { true }) { null }
            assertEquals(View.VISIBLE, inline.visibility)
            assertNull(host.dialog)
            host.stop()
            assertEquals(View.GONE, inline.visibility)
        } finally {
            host.stop()
            controller.pause().stop().destroy()
        }
    }
}

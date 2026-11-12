package org.screencopy.receiver.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import org.screencopy.receiver.media.PlaybackDisplayMeasurements
import org.screencopy.receiver.media.PlaybackStatisticsSnapshot

/**
 * An ineligible Activity window cannot be reclassified. A fresh attached panel
 * provides its own secure ancestry without changing the Activity or its input.
 */
internal class InlineStatisticsHost(
    private val activity: Activity,
    private val anchor: View,
    private val inline: PlaybackStatisticsSurface,
    private val protection: StatisticsWindowProtection,
    private val supported: Boolean = Build.VERSION.SDK_INT >= 31,
    private val createPanel: () -> PlaybackStatisticsSurface = { PlaybackStatisticsSurface(activity) }
) {
    internal var dialog: Dialog? = null
        private set
    internal var panel: PlaybackStatisticsSurface? = null
        private set
    private var active = false
    private var failed = false
    private var reportedMissingToken = false
    private var source: (() -> PlaybackStatisticsSnapshot?)? = null
    private var selected: (() -> Boolean)? = null
    private var canvasWidth = 0
    private var canvasHeight = 0
    private var measurements = PlaybackDisplayMeasurements()
    private val layout = ViewTreeObserver.OnGlobalLayoutListener { update() }
    private val attachment = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {
            if (active) {
                anchor.viewTreeObserver.addOnGlobalLayoutListener(layout)
                update()
            }
        }
        override fun onViewDetachedFromWindow(view: View) {
            anchor.viewTreeObserver.removeOnGlobalLayoutListener(layout)
            dismissPanel()
        }
    }

    fun start(width: Int, height: Int, value: PlaybackDisplayMeasurements,
        selected: () -> Boolean, source: () -> PlaybackStatisticsSnapshot?) {
        canvasWidth = width
        canvasHeight = height
        measurements = value
        this.source = source
        this.selected = selected
        if (!active) {
            active = true
            anchor.addOnAttachStateChangeListener(attachment)
            anchor.viewTreeObserver.addOnGlobalLayoutListener(layout)
        }
        update()
    }

    private fun update() {
        if (!active || failed) return
        val source = source ?: return
        val selected = selected ?: return
        if (!supported || protection.canHostStatistics(inline)) {
            dismissPanel()
            inline.measurements(canvasWidth, canvasHeight, measurements)
            inline.start(selected, source)
            return
        }
        inline.stop()
        if (!anchor.isAttachedToWindow || anchor.windowToken == null) {
            if (!reportedMissingToken) {
                reportedMissingToken = true
                android.util.Log.w("ScreenCopyReceiver", "Secure statistics panel awaits its attached parent window token")
            }
            inline.showHostUnavailable()
            return
        }
        if (anchor.width <= 0 || anchor.height <= 0) return
        try {
            if (dialog == null) {
                val next = Dialog(protection.panelContext(anchor), android.R.style.Theme_Translucent_NoTitleBar)
                next.setOwnerActivity(activity)
                next.requestWindowFeature(Window.FEATURE_NO_TITLE)
                val window = checkNotNull(next.window)
                val decor = window.decorView
                window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND or
                    WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR)
                window.setDecorFitsSystemWindows(false)
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                window.attributes = window.attributes.apply {
                    type = WindowManager.LayoutParams.TYPE_APPLICATION_PANEL
                    token = anchor.windowToken
                    gravity = Gravity.TOP or Gravity.LEFT
                    dimAmount = 0f
                    windowAnimations = 0
                    setFitInsetsTypes(0)
                    // The subwindow uses the parent Window frame, not the display frame.
                    flags = flags and WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN.inv()
                }
                decor.setPadding(0, 0, 0, 0)
                decor.elevation = 0f
                val content = createPanel()
                content.bindWindow(StatisticsWindowProtection.forWindow(window))
                next.setContentView(content)
                dialog = next
                panel = content
                next.setOnDismissListener {
                    if (dialog === next) {
                        dismissPanel()
                        if (active) fail(IllegalStateException("Statistics panel window dismissed unexpectedly"))
                    }
                }
                position(window)
                next.show()
            }
            val current = checkNotNull(dialog)
            position(checkNotNull(current.window))
            panel?.measurements(canvasWidth, canvasHeight, measurements)
            panel?.start(selected, source)
        } catch (error: WindowManager.BadTokenException) {
            fail(error)
        } catch (error: WindowManager.InvalidDisplayException) {
            fail(error)
        } catch (error: SecurityException) {
            fail(error)
        } catch (error: IllegalStateException) {
            fail(error)
        } catch (error: IllegalArgumentException) {
            fail(error)
        }
    }

    private fun position(window: Window) {
        check(window.attributes.token === anchor.windowToken &&
            (!window.decorView.isAttachedToWindow ||
                window.decorView.display?.displayId == anchor.display?.displayId)) {
            "Statistics panel parent token or display changed"
        }
        val location = IntArray(2)
        anchor.getLocationInWindow(location)
        val attributes = window.attributes
        if (attributes.x != location[0] || attributes.y != location[1] ||
            attributes.width != anchor.width || attributes.height != anchor.height) {
            window.attributes = attributes.apply {
                x = location[0]
                y = location[1]
                width = anchor.width
                height = anchor.height
            }
        }
    }

    private fun fail(error: RuntimeException) {
        failed = true
        dismissPanel()
        android.util.Log.e("ScreenCopyReceiver", "Secure inline statistics window unavailable", error)
        inline.showHostUnavailable()
    }

    private fun dismissPanel() {
        val retired = dialog
        val retiredPanel = panel
        dialog = null
        panel = null
        retired?.setOnDismissListener(null)
        try { retiredPanel?.stop() } finally { retired?.dismiss() }
    }

    fun stop() {
        active = false
        failed = false
        reportedMissingToken = false
        source = null
        selected = null
        anchor.removeOnAttachStateChangeListener(attachment)
        anchor.viewTreeObserver.removeOnGlobalLayoutListener(layout)
        dismissPanel()
        inline.stop()
    }
}

package dev.redrain.cast

import android.app.Activity
import android.os.Looper
import android.content.ClipboardManager
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.screencopy.sender.Discovery
import dev.screencopy.sender.ReceiverTarget
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class HomeViewTest {
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun button(home: HomeView, description: String) =
        descendants(home).filterIsInstance<Button>().single { it.contentDescription == description }

    private fun text(home: HomeView, value: String) =
        descendants(home).filterIsInstance<TextView>().single { it.text.toString() == value }

    @Test fun nearbyRowsAreStableBusyIsDisabledAndSelectedExpiredReceiverCanStop() {
        val controller = Robolectric.buildActivity(Activity::class.java)
        controller.get().setTheme(R.style.Theme_RedrainCast)
        controller.setup()
        val activity = controller.get()
        var sent: ReceiverTarget? = null
        var stopped = 0
        val home = HomeView(activity, {}, {}, { sent = it }, { stopped++ }, {}, {})
        activity.setContentView(home)
        try {
            assertEquals(View.VISIBLE, text(home, activity.getString(R.string.rc_no_receivers)).visibility)
            val available = Discovery.Receiver("Living room", "192.168.1.10", 12345, "available", 1)
            val busy = Discovery.Receiver("Office", "192.168.1.11", 12345, "busy", 1)
            home.setReceivers(listOf(available, busy))
            val sendDescription = activity.getString(R.string.rc_send_to, available.name)
            val send = button(home, sendDescription)
            assertTrue(send.isEnabled)
            assertFalse(button(home, activity.getString(R.string.rc_send_to, busy.name)).isEnabled)
            send.performClick()
            assertEquals(available.target(), sent)
            send.isFocusableInTouchMode = true
            send.requestFocus()
            home.setReceivers(listOf(busy.copy(seen = 2), available.copy(seen = 2)))
            assertSame(send, button(home, sendDescription))
            assertTrue(send.hasFocus())

            home.render(HomeView.State(readiness = "PAUSED", sending = true, outgoing = available.target()))
            home.setReceivers(emptyList())
            val stop = button(home, activity.getString(R.string.rc_stop_to, available.name))
            assertSame(send, stop)
            assertEquals("Stop", stop.text.toString())
            assertTrue(stop.isEnabled)
            stop.performClick()
            assertEquals(1, stopped)
            home.render(HomeView.State())
            assertEquals(View.VISIBLE, text(home, activity.getString(R.string.rc_no_receivers)).visibility)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun suppliedDirectoryIsRenderedWithoutAdditionalFilteringAndReceivingDisablesSend() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val home = HomeView(activity, {}, {}, {}, {}, {}, {})
        activity.setContentView(home)
        try {
            val receiver = Discovery.Receiver("Receiver", "10.0.0.8", 44123, "available", 0)
            home.setReceivers(listOf(receiver, receiver.copy(seen = 1)))
            val send = button(home, activity.getString(R.string.rc_send_to, receiver.name))
            assertTrue(send.isEnabled)
            home.render(HomeView.State(receiving = true, readiness = "CONNECTING"))
            assertFalse(send.isEnabled)
            assertFalse((text(home, activity.getString(R.string.rc_manual_connect)) as Button).isEnabled)
            assertEquals(View.VISIBLE, text(home, activity.getString(R.string.rc_connecting)).visibility)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun endpointCopyUsesExactCanonicalAddressAndErrorDismissInvokesOwner() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        var dismisses = 0
        val home = HomeView(activity, {}, {}, {}, {}, {}, { dismisses++ })
        activity.setContentView(home)
        try {
            val endpoint = "[fe80::1234%wlan0]:45678"
            val snapshot = HomeView.State(readiness = "READY", endpoints = listOf(endpoint), error = "An actionable error")
            home.render(snapshot)
            val copy = button(home, activity.getString(R.string.rc_copy_endpoint, endpoint))
            home.render(snapshot.copy(detail = "Ready on this network"))
            assertSame(copy, button(home, activity.getString(R.string.rc_copy_endpoint, endpoint)))
            copy.performClick()
            assertEquals(endpoint, activity.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
            (text(home, activity.getString(R.string.rc_dismiss)) as Button).performClick()
            assertEquals(1, dismisses)
            home.render(snapshot.copy(error = null))
            assertEquals(View.GONE, text(home, activity.getString(R.string.rc_error_heading)).parent.let { (it as View).visibility })
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun cardsUseLandscapePhoneAndWideTabletColumnsWithoutSqueezingNarrowWindows() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val home = HomeView(activity, {}, {}, {}, {}, {}, {})
        activity.setContentView(home)
        try {
            val density = activity.resources.displayMetrics.density
            fun resize(width: Int, height: Int) {
                repeat(2) {
                    home.measure(
                        View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec((height * density).toInt(), View.MeasureSpec.EXACTLY)
                    )
                    home.layout(0, 0, home.measuredWidth, home.measuredHeight)
                }
            }
            val cards = home.findViewWithTag<LinearLayout>("home_cards")
            val send = home.findViewWithTag<View>("send_card")
            val receive = home.findViewWithTag<View>("receive_card")
            fun assertColumns() {
                assertEquals(LinearLayout.HORIZONTAL, cards.orientation)
                assertEquals(send.width.toDouble(), receive.width.toDouble(), 1.0)
                assertTrue("Card width ${send.width} must be below half the viewport ${home.width}",
                    send.width < home.width / 2)
                assertTrue(receive.left >= send.right)
                assertEquals(send.top, receive.top)
                assertEquals(1f, (send.layoutParams as LinearLayout.LayoutParams).weight, 0f)
            }
            resize(400, 800)
            assertEquals(LinearLayout.VERTICAL, cards.orientation)
            assertTrue(receive.top >= send.bottom)
            resize(760, 360)
            assertColumns()
            resize(600, 340)
            assertColumns()
            resize(600, 800)
            assertEquals(LinearLayout.VERTICAL, cards.orientation)
            resize(900, 1200)
            assertColumns()
            resize(500, 320)
            assertEquals(LinearLayout.VERTICAL, cards.orientation)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun primaryPageWordingMatchesWindowsWhileAndroidPermissionHelpStaysPlatformSpecific() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val home = HomeView(activity, {}, {}, {}, {}, {}, {})
        activity.setContentView(home)
        try {
            for (label in listOf("Send and receive screens.", "Send screen", "Receive screen",
                "Nearby receivers", "Connect by IP : Port", "Connection help")) {
                assertNotNull(text(home, label))
            }
            assertTrue(descendants(home).any { it.contentDescription?.toString() == "Settings" })
            assertTrue(activity.getString(R.string.flow_help).contains("Use Stop on the sender"))
            assertFalse(activity.getString(R.string.flow_help).contains("reveal Stop receiving"))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun newErrorsAppearAboveCardsAndReturnToTopOnlyOnce() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val home = HomeView(activity, {}, {}, {}, {}, {}, {})
        activity.setContentView(home)
        try {
            home.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY))
            home.layout(0, 0, 400, 500)
            home.scrollTo(0, 100)
            assertTrue(home.scrollY > 0)
            val snapshot = HomeView.State(error = "Action failed")
            home.render(snapshot)
            shadowOf(Looper.getMainLooper()).idle()
            val error = home.findViewWithTag<View>("home_error")
            val cards = home.findViewWithTag<View>("home_cards")
            val body = error.parent as ViewGroup
            assertTrue(body.indexOfChild(error) < body.indexOfChild(cards))
            assertEquals(View.VISIBLE, error.visibility)
            assertEquals(0, home.scrollY)
            home.scrollTo(0, 100)
            home.render(snapshot.copy(status = "Idle"))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Polling must not keep pulling users back to the same error", 100, home.scrollY)
        } finally { controller.pause().stop().destroy() }
    }

    @Test @Config(qualifiers = "night") fun nightThemeUsesReadableDarkSurfaces() {
        val controller = Robolectric.buildActivity(Activity::class.java)
        controller.get().setTheme(R.style.Theme_RedrainCast)
        controller.setup()
        try {
            val activity = controller.get()
            assertNotEquals(activity.getColor(R.color.rc_background), activity.getColor(R.color.rc_text))
            val home = HomeView(activity, {}, {}, {}, {}, {}, {})
            activity.setContentView(home)
            home.render(HomeView.State(readiness = "READY"))
            assertEquals(activity.getColor(R.color.rc_ready), text(home, activity.getString(R.string.rc_ready)).currentTextColor)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}

package org.screencopy.receiver.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.SurfaceView
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.TextView
import android.widget.FrameLayout
import dev.redrain.cast.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import org.screencopy.receiver.media.*
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class StatisticsPanelRendererTest {
    private fun protectedActivity() = Robolectric.buildActivity(Activity::class.java).create().apply {
        get().window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        StatisticsWindowProtection.forWindow(get().window)
        start().resume().visible()
    }

    private fun snapshot(at: Long = 1000) = PlaybackStatisticsSampler().sample(at,
        PlaybackStatisticsInput(1, 1, 1, "Playing", "h264", null, 640, 360, 60,
            8_000_000, null, 0, "auto", 1, 0, 100, 0,
            VideoStatistics(1, 1, 0, 1, 1000), null, null, null, 10, 0))

    @Test fun androidElevenFailsClosedInsteadOfCreatingAnInsecureChild() {
        assertFalse(AndroidStatisticsSurfaceTargets().supported)
        assertTrue(AndroidStatisticsSurfaceTargets(31).supported)
        val view = PlaybackStatisticsSurface(RuntimeEnvironment.getApplication())
        view.start { snapshot() }
        view.surfaceCreated(view.holder)
        view.surfaceChanged(view.holder, 0, 800, 900)
        assertFalse(view.refreshing)
        assertEquals(View.GONE, view.getChildAt(0).visibility)
        assertEquals(View.VISIBLE, view.getChildAt(1).visibility)
        assertEquals(view.context.getString(R.string.rc_statistics_os_unsupported),
            (view.getChildAt(1) as TextView).text.toString())
        view.stop()
    }

    @Test fun insecureHostWindowIsRejectedBeforeCreatingAChildProducer() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val anchor = SurfaceView(controller.get())
            controller.get().setContentView(anchor)
            val error = assertThrows(IllegalStateException::class.java) {
                AndroidStatisticsSurfaceTargets(31).create(anchor, 800, 900)
            }
            assertEquals("Statistics child surfaces require a FLAG_SECURE host window", error.message)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun insecureOrPreviouslySecureHostNeverAllocatesAProducer() {
        for (initiallySecure in listOf(false, true)) {
            val controller = Robolectric.buildActivity(Activity::class.java).setup()
            val targets = BitmapTargets()
            val view = PlaybackStatisticsSurface(controller.get(), false, targets)
            view.getChildAt(0).visibility = View.GONE
            controller.get().setContentView(view)
            val window = controller.get().window
            if (initiallySecure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            view.bindWindow(StatisticsWindowProtection.forWindow(window))
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            try {
                view.start { null }
                view.surfaceCreated(view.holder)
                view.surfaceChanged(view.holder, 0, 800, 900)
                assertTrue(targets.outputs.isEmpty())
                assertFalse(view.refreshing)
                assertEquals(view.context.getString(R.string.rc_statistics_secure_window_required),
                    (view.getChildAt(1) as TextView).text.toString())
                assertEquals(0, window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
            } finally {
                view.stop()
                controller.pause().stop().destroy()
            }
        }
    }

    @Test fun ownerVetoesFlagRemovalForTheWholeWindowLifetimeWithoutInterceptingCallbacks() {
        val controller = protectedActivity()
        val targets = BitmapTargets()
        val view = PlaybackStatisticsSurface(controller.get(), false, targets)
        view.getChildAt(0).visibility = View.GONE
        controller.get().setContentView(view)
        val window = controller.get().window
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val original = checkNotNull(window.callback)
        val protection = StatisticsWindowProtection.forWindow(window)
        var attributesForwarded = 0
        val forwarding = object : Window.Callback by original {
            override fun onWindowAttributesChanged(attrs: WindowManager.LayoutParams) {
                attributesForwarded++
                original.onWindowAttributesChanged(attrs)
            }
        }
        view.bindWindow(protection)
        try {
            view.start { null }
            view.surfaceCreated(view.holder)
            view.surfaceChanged(view.holder, 0, 800, 900)
            val active = renderer(view)
            shadowOf(active.looper).idle()
            assertEquals(1, targets.outputs[0].shows)
            assertSame(original, window.callback)
            window.callback = forwarding
            assertFalse(protection.clearSecureFlag())
            assertEquals("Veto must prevent the update, not race its compositor commit", 0, attributesForwarded)
            assertTrue(view.refreshing)
            assertEquals(WindowManager.LayoutParams.FLAG_SECURE,
                window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
            view.stop()
            assertTrue(targets.outputs[0].released.await(2, TimeUnit.SECONDS))
            active.frame.run()
            assertEquals(1, targets.outputs[0].releases)
            assertFalse("Retirement must not permit declassification of old layers", protection.clearSecureFlag())
            assertSame(forwarding, window.callback)
        } finally {
            view.stop()
            targets.outputs.forEach { it.bitmap.recycle() }
            controller.pause().stop().destroy()
        }
    }

    @Test fun multiplePanelsShareIrrevocableProtectionButANewWindowDoesNotInheritIt() {
        val controller = protectedActivity()
        val activity = controller.get()
        val window = activity.window
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val protection = StatisticsWindowProtection.forWindow(window)
        assertSame(protection, StatisticsWindowProtection.forWindow(window))
        val original = window.callback
        val targets = BitmapTargets()
        val panels = List(2) { PlaybackStatisticsSurface(activity, false, targets) }
        activity.setContentView(FrameLayout(activity).apply {
            panels.forEach { addView(it) }
        })
        try {
            panels.forEach {
                it.getChildAt(0).visibility = View.GONE
                it.bindWindow(protection)
                it.start { null }
                it.surfaceCreated(it.holder)
                it.surfaceChanged(it.holder, 0, 800, 900)
            }
            assertEquals(2, targets.outputs.size)
            panels.first().stop()
            assertFalse(StatisticsWindowProtection.forWindow(window).clearSecureFlag())
            assertTrue(panels.last().refreshing)
            panels.last().stop()
            assertFalse(protection.clearSecureFlag())
            assertSame(original, window.callback)
            val newWindow = checkNotNull(Dialog(activity).window)
            newWindow.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            val newProtection = StatisticsWindowProtection.forWindow(newWindow)
            assertNotSame(protection, newProtection)
            assertTrue(newProtection.clearSecureFlag())
            assertEquals(0, newWindow.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
            assertEquals(WindowManager.LayoutParams.FLAG_SECURE,
                window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        } finally {
            panels.forEach { it.stop() }
            targets.outputs.forEach {
                assertTrue(it.released.await(2, TimeUnit.SECONDS))
                it.bitmap.recycle()
            }
            controller.pause().stop().destroy()
        }
    }

    @Test fun flagRemovalReentrantWithAllocationIsVetoedBeforeItCanReachWms() {
        val controller = protectedActivity()
        val window = controller.get().window
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val protection = StatisticsWindowProtection.forWindow(window)
        val targets = BitmapTargets {
            assertFalse(protection.clearSecureFlag())
            assertEquals(WindowManager.LayoutParams.FLAG_SECURE,
                window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        }
        val view = PlaybackStatisticsSurface(controller.get(), false, targets)
        view.getChildAt(0).visibility = View.GONE
        controller.get().setContentView(view)
        view.bindWindow(protection)
        try {
            view.start { null }
            view.surfaceCreated(view.holder)
            view.surfaceChanged(view.holder, 0, 800, 900)
            assertEquals(0, targets.outputs.single().shows)
            assertFalse(view.refreshing)
        } finally {
            view.stop()
            targets.outputs.forEach {
                assertTrue(it.released.await(2, TimeUnit.SECONDS))
                it.bitmap.recycle()
            }
            controller.pause().stop().destroy()
        }
    }

    @Test @Config(qualifiers = "fr-rFR")
    fun unownedHostShowsResourceBackedUnavailableStatusWithLocaleFallback() {
        val targets = BitmapTargets()
        val view = PlaybackStatisticsSurface(RuntimeEnvironment.getApplication(), false, targets)
        view.start { null }
        view.surfaceCreated(view.holder)
        view.surfaceChanged(view.holder, 0, 800, 900)
        try {
            assertEquals("fr", view.resources.configuration.locales[0].language)
            assertEquals(View.VISIBLE, view.getChildAt(1).visibility)
            assertEquals(view.context.getString(R.string.rc_statistics_secure_window_required),
                (view.getChildAt(1) as TextView).text.toString())
            assertTrue(targets.outputs.isEmpty())
            assertFalse(view.refreshing)
        } finally { view.stop() }
    }

    @Test fun queuedDetachmentCannotClearProtectionButEveryCommittedTokenCan() {
        val controller = protectedActivity()
        val window = controller.get().window
        val protection = StatisticsWindowProtection.forWindow(window)
        val targets = BitmapTargets()
        val view = PlaybackStatisticsSurface(controller.get(), false, targets)
        view.getChildAt(0).visibility = View.GONE
        controller.get().setContentView(view)
        view.bindWindow(protection)
        try {
            view.start { null }
            view.surfaceCreated(view.holder)
            view.surfaceChanged(view.holder, 0, 800, 900)
            view.surfaceChanged(view.holder, 0, 600, 700)
            view.stop()
            targets.outputs.forEach { assertTrue(it.released.await(2, TimeUnit.SECONDS)) }
            assertTrue(targets.outputs.all { it.detached })
            assertFalse("Queued detachment is not a commit acknowledgement", protection.clearSecureFlag())
            targets.outputs[0].detachedCommitted()
            assertEquals(WindowManager.LayoutParams.FLAG_SECURE,
                window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
            targets.outputs[0].detachedCommitted()
            assertEquals(WindowManager.LayoutParams.FLAG_SECURE,
                window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
            targets.outputs[1].detachedCommitted()
            assertEquals(0, window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            view.start { null }
            assertFalse("Re-adding flags does not prove a previously declassified root secure", view.refreshing)
            assertEquals(2, targets.outputs.size)
        } finally {
            view.stop()
            targets.outputs.forEach { it.bitmap.recycle() }
            controller.pause().stop().destroy()
        }
    }

    @Test fun lateOrPreviouslyDeclassifiedHostsCannotObtainStatisticsCapability() {
        for (late in listOf(false, true)) {
            val controller = if (late) Robolectric.buildActivity(Activity::class.java).setup()
                else protectedActivity()
            val window = controller.get().window
            val protection = StatisticsWindowProtection.forWindow(window)
            if (!late) assertTrue(protection.clearSecureFlag())
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            val targets = BitmapTargets()
            val view = PlaybackStatisticsSurface(controller.get(), false, targets)
            view.getChildAt(0).visibility = View.GONE
            controller.get().setContentView(view)
            view.bindWindow(protection)
            try {
                view.start { null }
                view.surfaceCreated(view.holder)
                view.surfaceChanged(view.holder, 0, 800, 900)
                assertTrue(targets.outputs.isEmpty())
                assertFalse(view.refreshing)
                assertEquals(View.VISIBLE, view.getChildAt(1).visibility)
                assertEquals(view.context.getString(R.string.rc_statistics_secure_window_required),
                    (view.getChildAt(1) as TextView).text.toString())
            } finally {
                view.stop()
                controller.pause().stop().destroy()
            }
        }
    }

    @Test fun receivingCancelsOnlyTheClearRequestNotUnacknowledgedRetirementTokens() {
        val controller = protectedActivity()
        val window = controller.get().window
        val protection = StatisticsWindowProtection.forWindow(window)
        val first = checkNotNull(protection.retainForStatistics(window.decorView))
        val second = checkNotNull(protection.retainForStatistics(window.decorView))
        try {
            assertFalse(protection.clearSecureFlag())
            protection.secureForReceiving()
            first()
            assertTrue(protection.canHostStatistics(window.decorView))
            assertEquals(WindowManager.LayoutParams.FLAG_SECURE,
                window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
            assertFalse("A new send still waits for every original token", protection.clearSecureFlag())
            first()
            assertEquals(WindowManager.LayoutParams.FLAG_SECURE,
                window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
            second()
            assertEquals(0, window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
            protection.secureForReceiving()
            assertFalse(protection.canHostStatistics(window.decorView))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun neverAttachedWithoutAProducerDoesNotRetainProtection() {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        val activity = controller.get()
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val protection = StatisticsWindowProtection.forWindow(activity.window)
        val targets = BitmapTargets()
        val view = PlaybackStatisticsSurface(activity, false, targets)
        activity.setContentView(view)
        try {
            view.bindWindow(protection)
            view.start { null }
            assertFalse(view.isAttachedToWindow)
            assertTrue(targets.outputs.isEmpty())
            assertTrue("No producer was admitted, so there is no retirement to wait for",
                protection.clearSecureFlag())
            assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        } finally {
            view.stop()
            controller.destroy()
        }
    }

    @Test fun asynchronousOldCommitCannotDeclassifyANewerLiveGeneration() {
        val controller = protectedActivity()
        val window = controller.get().window
        val protection = StatisticsWindowProtection.forWindow(window)
        val targets = BitmapTargets()
        val view = PlaybackStatisticsSurface(controller.get(), false, targets)
        view.getChildAt(0).visibility = View.GONE
        controller.get().setContentView(view)
        view.bindWindow(protection)
        try {
            view.start { null }
            view.surfaceCreated(view.holder)
            view.surfaceChanged(view.holder, 0, 800, 900)
            view.surfaceChanged(view.holder, 0, 600, 700)
            Handler(Looper.getMainLooper()).post { targets.outputs[0].detachedCommitted() }
            assertFalse(protection.clearSecureFlag())
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(WindowManager.LayoutParams.FLAG_SECURE,
                window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
            view.stop()
            targets.outputs.forEach { assertTrue(it.released.await(2, TimeUnit.SECONDS)) }
            Handler(Looper.getMainLooper()).post { targets.outputs[1].detachedCommitted() }
            assertEquals(WindowManager.LayoutParams.FLAG_SECURE,
                window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
            targets.outputs[0].detachedCommitted()
            assertEquals(0, window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        } finally {
            view.stop()
            targets.outputs.forEach { it.bitmap.recycle() }
            controller.pause().stop().destroy()
        }
    }

    @Test fun failedDetachmentIsNotAPrivacyAcknowledgement() {
        val controller = protectedActivity()
        val window = controller.get().window
        val failure = IllegalStateException("injected transaction listener registration failure")
        val attempts = AtomicInteger()
        val targets = BitmapTargets { target ->
            target.beforeDetach = {
                if (attempts.incrementAndGet() == 1) throw failure
            }
        }
        val view = PlaybackStatisticsSurface(controller.get(), false, targets)
        view.getChildAt(0).visibility = View.GONE
        controller.get().setContentView(view)
        view.bindWindow(StatisticsWindowProtection.forWindow(window))
        try {
            view.start { null }
            view.surfaceCreated(view.holder)
            view.surfaceChanged(view.holder, 0, 800, 900)
            assertSame(failure, assertThrows(IllegalStateException::class.java) { view.stop() })
            assertFalse(view.refreshing)
            assertTrue(targets.outputs.single().released.await(2, TimeUnit.SECONDS))
            assertFalse(StatisticsWindowProtection.forWindow(window).clearSecureFlag())
            assertEquals(WindowManager.LayoutParams.FLAG_SECURE,
                window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        } finally {
            view.stop()
            targets.outputs.forEach { it.bitmap.recycle() }
            controller.pause().stop().destroy()
        }
    }

    @Test fun dialogUsesItsOwnSecureWindowNotTheActivityContextWindow() {
        val controller = protectedActivity()
        val activity = controller.get()
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val targets = BitmapTargets()
        val view = PlaybackStatisticsSurface(activity, false, targets)
        view.getChildAt(0).visibility = View.GONE
        val dialog = Dialog(activity)
        val window = checkNotNull(dialog.window)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val protection = StatisticsWindowProtection.forWindow(window)
        dialog.setContentView(view)
        dialog.show()
        try {
            view.bindWindow(StatisticsWindowProtection.forWindow(activity.window))
            view.start { null }
            view.surfaceCreated(view.holder)
            view.surfaceChanged(view.holder, 0, 800, 900)
            assertTrue("A secure activity must not authorize a different window", targets.outputs.isEmpty())
            view.bindWindow(protection)
            assertEquals(1, targets.outputs.size)
            assertEquals(1, targets.outputs[0].shows)
            view.stop()
            assertTrue(targets.outputs[0].released.await(2, TimeUnit.SECONDS))
        } finally {
            view.stop()
            targets.outputs.forEach { it.bitmap.recycle() }
            dialog.dismiss()
            controller.pause().stop().destroy()
        }
    }

    @Test fun unauthorizedRawFlagChangeDuringHiddenCreationNeverShowsTheUnusedTarget() {
        val controller = protectedActivity()
        val window = controller.get().window
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val targets = BitmapTargets { window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        val view = PlaybackStatisticsSurface(controller.get(), false, targets)
        view.getChildAt(0).visibility = View.GONE
        controller.get().setContentView(view)
        view.bindWindow(StatisticsWindowProtection.forWindow(window))
        try {
            view.start { null }
            view.surfaceCreated(view.holder)
            view.surfaceChanged(view.holder, 0, 800, 900)
            val output = targets.outputs.single()
            assertEquals(0, output.shows)
            assertEquals(1, output.releases)
            assertTrue(output.detached)
            assertFalse(view.refreshing)
        } finally {
            view.stop()
            targets.outputs.forEach { it.bitmap.recycle() }
            controller.pause().stop().destroy()
        }
    }

    @Test fun showFailureReleasesUnusedResourcesAndShowsAnExplicitError() {
        val controller = protectedActivity()
        val window = controller.get().window
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val targets = BitmapTargets { target ->
            target.beforeShow = { throw IllegalStateException("injected compositor show failure") }
        }
        val view = PlaybackStatisticsSurface(controller.get(), false, targets)
        view.getChildAt(0).visibility = View.GONE
        controller.get().setContentView(view)
        view.bindWindow(StatisticsWindowProtection.forWindow(window))
        try {
            view.start { null }
            view.surfaceCreated(view.holder)
            view.surfaceChanged(view.holder, 0, 800, 900)
            assertEquals(1, targets.outputs.single().releases)
            assertFalse(view.refreshing)
            assertEquals(view.context.getString(R.string.rc_statistics_rendering_unavailable),
                (view.getChildAt(1) as TextView).text.toString())
        } finally {
            view.stop()
            targets.outputs.forEach { it.bitmap.recycle() }
            controller.pause().stop().destroy()
        }
    }

    @Test fun failedTargetSetupShowsUnavailableAndCanRetryOnNextSurface() {
        val controller = protectedActivity()
        var creations = 0
        val targets = object : StatisticsSurfaceTargets {
            override val supported = true
            override fun create(parent: SurfaceView, width: Int, height: Int,
                detachedCommitted: () -> Unit): StatisticsSurfaceTarget {
                creations++
                throw IllegalStateException("injected compositor setup failure")
            }
        }
        val view = PlaybackStatisticsSurface(controller.get(), false, targets)
        view.getChildAt(0).visibility = View.GONE
        controller.get().setContentView(view)
        controller.get().window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        view.bindWindow(StatisticsWindowProtection.forWindow(controller.get().window))
        view.start { snapshot() }
        view.surfaceCreated(view.holder)
        view.surfaceChanged(view.holder, 0, 800, 900)
        assertEquals(1, creations)
        assertFalse(view.refreshing)
        assertEquals(View.VISIBLE, view.getChildAt(1).visibility)
        view.surfaceDestroyed(view.holder)
        view.surfaceCreated(view.holder)
        view.surfaceChanged(view.holder, 0, 800, 900)
        assertEquals(2, creations)
        view.stop()
        assertFalse("Aborted allocation without a commit proof must retain protection",
            StatisticsWindowProtection.forWindow(controller.get().window).clearSecureFlag())
        assertEquals(WindowManager.LayoutParams.FLAG_SECURE,
            controller.get().window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        controller.pause().stop().destroy()
    }

    @Test fun failedWorkerStartupReleasesUnusedResourcesExactlyOnce() {
        val disposals = AtomicInteger()
        val renderer = StatisticsPanelRenderer({ null }, dispose = { disposals.incrementAndGet() }) {
            fail("A failed worker must not draw")
        }
        val thread = ReflectionHelpers.getField<HandlerThread>(renderer, "thread")
        thread.start()
        assertThrows(IllegalThreadStateException::class.java) { renderer.start() }
        assertFalse(renderer.running)
        assertEquals(1, disposals.get())
        renderer.close()
        renderer.frame.run()
        assertEquals(1, disposals.get())
    }

    @Test(timeout = 10_000) fun recreatedSurfaceCanRenderWhileRetiredTargetIsBlockedAndLateDrawStaysIsolated() {
        replaceBlockedTarget(recreate = true)
    }

    @Test(timeout = 10_000) fun stopAndRestartDoesNotWaitForThePreviousProducer() {
        replaceBlockedTarget(recreate = false)
    }

    private fun replaceBlockedTarget(recreate: Boolean) {
        val controller = protectedActivity()
        val drawing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val outputs = mutableListOf<BitmapTarget>()
        val targets = object : StatisticsSurfaceTargets {
            override val supported = true
            override fun create(parent: SurfaceView, width: Int, height: Int,
                detachedCommitted: () -> Unit): StatisticsSurfaceTarget =
                BitmapTarget(width, height).also { target ->
                    target.detachedCommitted = detachedCommitted
                    if (outputs.isEmpty()) target.beforeDraw = {
                        drawing.countDown()
                        check(release.await(3, TimeUnit.SECONDS))
                    }
                    outputs.add(target)
                }
        }
        val samples = AtomicInteger()
        val view = PlaybackStatisticsSurface(controller.get(), false, targets)
        view.getChildAt(0).visibility = View.GONE
        controller.get().setContentView(view)
        controller.get().window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        view.bindWindow(StatisticsWindowProtection.forWindow(controller.get().window))
        try {
            view.start { samples.incrementAndGet(); snapshot() }
            view.surfaceCreated(view.holder)
            view.surfaceChanged(view.holder, 0, 800, 900)
            val old = renderer(view)
            val oldLooper = old.looper
            val draw = executor.submit { shadowOf(oldLooper).idle() }
            assertTrue(drawing.await(2, TimeUnit.SECONDS))
            val beforeClose = System.nanoTime()
            if (recreate) view.surfaceDestroyed(view.holder) else view.stop()
            assertTrue("The UI lifecycle must not wait for a blocked producer",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - beforeClose) < 500)
            assertNull(ReflectionHelpers.getField<StatisticsPanelRenderer?>(view, "renderer"))
            assertTrue(outputs[0].detached)
            assertEquals(0, outputs[0].releases)
            old.frame.run()
            assertEquals(1, samples.get())
            if (recreate) {
                view.surfaceCreated(view.holder)
                view.surfaceChanged(view.holder, 0, 800, 900)
            } else {
                view.start { samples.incrementAndGet(); snapshot() }
            }
            val replacement = renderer(view)
            assertNotSame(old, replacement)
            shadowOf(replacement.looper).idle()
            assertEquals(2, outputs.size)
            assertEquals(1, outputs[1].presentations)
            assertTrue(outputs[1].pixels().any { Color.alpha(it) > 0 })
            assertFalse("A hung retired producer must never authorize window declassification",
                StatisticsWindowProtection.forWindow(controller.get().window).clearSecureFlag())
            release.countDown()
            draw.get(2, TimeUnit.SECONDS)
            assertEquals(1, outputs[0].releases)
            assertSame(oldLooper, outputs[0].releaseLooper)
            assertEquals("Detached content cannot be presented", 0, outputs[0].presentations)
            shadowOf(Looper.getMainLooper()).idle()
            assertSame("Old disposal must not clear the new generation", replacement, renderer(view))
            old.frame.run()
            assertEquals(2, samples.get())
            assertEquals(1, outputs[1].presentations)
        } finally {
            release.countDown()
            view.stop()
            outputs.forEach { assertTrue(it.released.await(2, TimeUnit.SECONDS)) }
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
            outputs.forEach { it.bitmap.recycle() }
            controller.pause().stop().destroy()
        }
    }

    @Test fun resizeOwnsANewProducerAndRejectsRetiredHeightAndDisposalCallbacks() {
        val controller = protectedActivity()
        val outputs = mutableListOf<BitmapTarget>()
        val targets = object : StatisticsSurfaceTargets {
            override val supported = true
            override fun create(parent: SurfaceView, width: Int, height: Int,
                detachedCommitted: () -> Unit): StatisticsSurfaceTarget =
                BitmapTarget(width, height).also {
                    it.detachedCommitted = detachedCommitted
                    outputs.add(it)
                }
        }
        val view = PlaybackStatisticsSurface(controller.get(), true, targets)
        view.getChildAt(0).visibility = View.GONE
        controller.get().setContentView(view)
        controller.get().window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        view.bindWindow(StatisticsWindowProtection.forWindow(controller.get().window))
        val heights = mutableListOf<Int>()
        view.onContentHeight = { heights.add(it) }
        view.start { snapshot() }
        view.surfaceCreated(view.holder)
        view.surfaceChanged(view.holder, 0, 800, 900)
        val first = renderer(view)
        val firstLooper = first.looper
        shadowOf(firstLooper).idle()
        try {
            view.surfaceChanged(view.holder, 0, 600, 700)
            val next = renderer(view)
            assertNotSame(first, next)
            assertTrue(outputs[0].detached)
            assertTrue(outputs[0].released.await(2, TimeUnit.SECONDS))
            shadowOf(next.looper).idle()
            shadowOf(Looper.getMainLooper()).idle()
            assertSame(next, renderer(view))
            assertEquals(1, heights.size)
            assertEquals(1, outputs[0].releases)
            assertEquals(600, outputs[1].bitmap.width)
            assertEquals(700, outputs[1].bitmap.height)
            val count = outputs[0].presentations
            first.frame.run()
            assertEquals(count, outputs[0].presentations)
            view.stop()
            assertTrue(outputs[1].released.await(2, TimeUnit.SECONDS))
            assertEquals(1, outputs[1].releases)
        } finally {
            view.stop()
            outputs.forEach { assertTrue(it.released.await(2, TimeUnit.SECONDS)) }
            outputs.forEach { it.bitmap.recycle() }
            controller.pause().stop().destroy()
        }
    }

    private fun renderer(view: PlaybackStatisticsSurface) =
        checkNotNull(ReflectionHelpers.getField<StatisticsPanelRenderer?>(view, "renderer"))

    private class BitmapTargets(
        private val duringCreation: (BitmapTarget) -> Unit = {}
    ) : StatisticsSurfaceTargets {
        override val supported = true
        val outputs = mutableListOf<BitmapTarget>()
        override fun create(parent: SurfaceView, width: Int, height: Int,
            detachedCommitted: () -> Unit): StatisticsSurfaceTarget =
            BitmapTarget(width, height).also {
                it.detachedCommitted = detachedCommitted
                outputs.add(it)
                duringCreation(it)
            }
    }

    private class BitmapTarget(width: Int, height: Int) : StatisticsSurfaceTarget {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        var beforeDraw: () -> Unit = {}
        var beforeShow: () -> Unit = {}
        var beforeDetach: () -> Unit = {}
        var detachedCommitted: () -> Unit = {}
        var shows = 0
        @Volatile var detached = false
        var presentations = 0
        var releases = 0
        var releaseLooper: Looper? = null
        val released = CountDownLatch(1)
        override fun show() {
            beforeShow()
            shows++
        }
        override fun draw(content: (Canvas) -> Unit) {
            beforeDraw()
            content(Canvas(bitmap))
            if (!detached) presentations++
        }
        override fun detach() {
            beforeDetach()
            detached = true
        }
        override fun release() {
            detach()
            releases++
            releaseLooper = Looper.myLooper()
            released.countDown()
        }
        fun pixels() = IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }
    }

    @Test fun ownLooperKeepsDrawingGridWithoutDispatchingPlayerUiAndShowsStaleness() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val latest = AtomicReference<PlaybackStatisticsSnapshot?>(snapshot())
        var now = 1000L
        val rendered = mutableListOf<String>()
        var playerUiRan = false
        Handler(Looper.getMainLooper()).post { playerUiRan = true }
        var table: PlaybackStatisticsOverlay? = null
        val bitmap = Bitmap.createBitmap(800, 900, Bitmap.Config.ARGB_8888)
        val renderer = StatisticsPanelRenderer({ latest.get() }, { now }) { value ->
            val panel = table ?: PlaybackStatisticsOverlay(activity).also { table = it }
            panel.show(value, 800, 900)
            panel.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.AT_MOST))
            panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            if (value != null) panel.draw(canvas)
            rendered.add(panel.text)
        }
        fun pixels() = IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }
        try {
            renderer.start()
            assertNotSame(Looper.getMainLooper(), renderer.looper)
            shadowOf(renderer.looper).idle()
            assertEquals(1, rendered.size)
            assertTrue(rendered.last().contains("State: Playing"))
            val initialPixels = pixels()
            assertTrue("The renderer must draw the grid, not just update its model",
                initialPixels.any { Color.alpha(it) > 0 })
            now = 5000
            shadowOf(renderer.looper).idleFor(Duration.ofSeconds(1))
            assertEquals(2, rendered.size)
            assertTrue(rendered.last().contains("Statistics stale (4000 ms)"))
            assertTrue(rendered.last().contains("Received FPS: N/A"))
            assertFalse("The stale frame must actually repaint without main-looper dispatch",
                initialPixels.contentEquals(pixels()))
            assertFalse("Statistics must not require main/player callback dispatch", playerUiRan)
            latest.set(null)
            shadowOf(renderer.looper).idleFor(Duration.ofSeconds(1))
            assertEquals("", rendered.last())
            assertTrue("A missing session must clear the previously drawn grid",
                pixels().all { Color.alpha(it) == 0 })
            renderer.close()
            val count = rendered.size
            renderer.frame.run()
            assertEquals(count, rendered.size)
            assertFalse(renderer.running)
        } finally {
            renderer.close()
            bitmap.recycle()
            controller.pause().stop().destroy()
        }
    }

    @Test fun closeCancelsRecurringWorkAndNewRendererCanRestart() {
        var frames = 0
        var samples = 0
        val first = StatisticsPanelRenderer({ samples++; snapshot() }) { frames++ }
        first.start()
        shadowOf(first.looper).idle()
        assertEquals(1, frames)
        val queuedCallback = first.frame
        first.close()
        queuedCallback.run()
        assertEquals(1, frames)
        assertEquals("A retired callback must not even read its former session", 1, samples)
        assertFalse(first.running)
        val second = StatisticsPanelRenderer({ null }) { frames++ }
        try {
            second.start()
            shadowOf(second.looper).idle()
            assertEquals(2, frames)
        } finally { second.close() }
    }

    @Test(timeout = 10_000) fun closeReturnsWhileDrawIsBlockedAndWorkerReleasesItsHandleExactlyOnce() {
        val drawing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val frames = AtomicInteger()
        val disposals = AtomicInteger()
        val disposalLooper = AtomicReference<Looper>()
        val executor = Executors.newFixedThreadPool(2)
        val renderer = StatisticsPanelRenderer({ snapshot() }, dispose = {
            disposals.incrementAndGet()
            disposalLooper.set(Looper.myLooper())
        }) {
            drawing.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            frames.incrementAndGet()
        }
        try {
            renderer.start()
            val looper = renderer.looper
            val draw = executor.submit { shadowOf(looper).idle() }
            assertTrue(drawing.await(2, TimeUnit.SECONDS))
            executor.submit { renderer.close() }.get(500, TimeUnit.MILLISECONDS)
            assertFalse(renderer.running)
            assertEquals("close must return before the blocked draw finishes", 0, frames.get())
            assertEquals("Only the drawing worker may release its active handle", 0, disposals.get())
            renderer.frame.run()
            assertEquals(0, disposals.get())
            release.countDown()
            draw.get(2, TimeUnit.SECONDS)
            assertEquals(1, disposals.get())
            assertSame(looper, disposalLooper.get())
            renderer.close()
            renderer.frame.run()
            assertEquals(1, frames.get())
            assertEquals(1, disposals.get())
        } finally {
            release.countDown()
            renderer.close()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test(timeout = 10_000) fun closeDuringBlockedSnapshotNeverDrawsAndDefersDisposalToItsWorker() {
        val sampling = CountDownLatch(1)
        val release = CountDownLatch(1)
        val frames = AtomicInteger()
        val disposals = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)
        val renderer = StatisticsPanelRenderer({
            sampling.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            snapshot()
        }, dispose = { disposals.incrementAndGet() }) { frames.incrementAndGet() }
        try {
            renderer.start()
            val looper = renderer.looper
            val sample = executor.submit { shadowOf(looper).idle() }
            assertTrue(sampling.await(2, TimeUnit.SECONDS))
            executor.submit { renderer.close() }.get(500, TimeUnit.MILLISECONDS)
            assertEquals(0, disposals.get())
            release.countDown()
            sample.get(2, TimeUnit.SECONDS)
            assertEquals(0, frames.get())
            assertEquals(1, disposals.get())
            renderer.frame.run()
            assertEquals(0, frames.get())
            assertEquals(1, disposals.get())
        } finally {
            release.countDown()
            renderer.close()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test fun permissionOrSessionSelectionEndingClearsAndStopsWithoutMainLooper() {
        var selected = true
        val frames = mutableListOf<PlaybackStatisticsSnapshot?>()
        val renderer = StatisticsPanelRenderer({ snapshot() }, enabled = { selected }) { frames.add(it) }
        try {
            renderer.start()
            shadowOf(renderer.looper).idle()
            assertNotNull(frames.last())
            selected = false
            shadowOf(renderer.looper).idleFor(Duration.ofSeconds(1))
            assertNull(frames.last())
            assertFalse(renderer.running)
            val count = frames.size
            renderer.frame.run()
            assertEquals(count, frames.size)
        } finally { renderer.close() }
    }
}

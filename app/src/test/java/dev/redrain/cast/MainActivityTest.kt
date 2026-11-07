package dev.redrain.cast

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.SurfaceTexture
import android.os.Looper
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.TextureView
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import dev.screencopy.sender.ReceiverTarget
import dev.screencopy.sender.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.net.InetAddress
import java.net.Socket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class MainActivityTest {
    private val previousUpdateFactory = MainActivity.updateFactory
    @Before fun disableRealUpdateRequests() { MainActivity.updateFactory = ::noUpdateCheck }
    @After fun restoreUpdateFactory() { MainActivity.updateFactory = previousUpdateFactory }

    @Test fun activeDisplayModeIsCachedSeparatelyAndObserverRetiresOnBackground() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val manager = activity.getSystemService(android.hardware.display.DisplayManager::class.java)
        val display = manager.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        val originalMode = display.mode
        try {
            val mode4k = org.robolectric.util.ReflectionHelpers.callConstructor(android.view.Display.Mode::class.java,
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, originalMode.modeId),
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 3840),
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 2160),
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(Float::class.javaPrimitiveType, 60f))
            org.robolectric.shadows.ShadowDisplayManager.setSupportedModes(display.displayId, mode4k)
            shadowOf(Looper.getMainLooper()).idle()
            activity.onWindowFocusChanged(true)
            val observed = org.robolectric.util.ReflectionHelpers.getField<org.screencopy.receiver.media.PixelDimensions?>(
                activity, "displayOutput")
            assertEquals(org.screencopy.receiver.media.PixelDimensions(3840, 2160), observed)
            assertTrue(org.robolectric.util.ReflectionHelpers.getField<Boolean>(activity, "observingDisplay"))
            controller.pause().stop()
            assertNull(org.robolectric.util.ReflectionHelpers.getField<org.screencopy.receiver.media.PixelDimensions?>(
                activity, "displayOutput"))
            assertFalse(org.robolectric.util.ReflectionHelpers.getField<Boolean>(activity, "observingDisplay"))
            val listener = org.robolectric.util.ReflectionHelpers.getField<android.hardware.display.DisplayManager.DisplayListener>(
                activity, "displayChanges")
            listener.onDisplayChanged(display.displayId)
            assertNull("A queued callback must not republish mode metadata after Stop",
                org.robolectric.util.ReflectionHelpers.getField<org.screencopy.receiver.media.PixelDimensions?>(activity, "displayOutput"))
        } finally {
            org.robolectric.shadows.ShadowDisplayManager.setSupportedModes(display.displayId, originalMode)
            controller.destroy()
        }
        await { activity.resourcesRetired }
    }

    @Test fun connectingRetiresIdleDiagnosticsBeforeAnySessionSelectionOrPlayback() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        try {
            org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(activity, "showPlaybackDiagnostics")
            shadowOf(Looper.getMainLooper()).idle()
            val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(dialog.isShowing)
            org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(activity, "screenEvent",
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(
                    org.screencopy.receiver.ui.ReceiverScreenEvent::class.java,
                    org.screencopy.receiver.ui.ReceiverScreenEvent.Connecting))
            assertFalse(dialog.isShowing)
            assertNull(org.robolectric.util.ReflectionHelpers.getField<android.app.AlertDialog?>(activity, "statisticsDialog"))
            assertNull(org.robolectric.util.ReflectionHelpers.getField<org.screencopy.receiver.ui.PlaybackStatisticsDetails?>(
                activity, "statisticsDetails"))
            assertNull(shadowOf(activity).nextStartedActivityForResult)
            assertNull(shadowOf(activity).nextStartedService)
        } finally { controller.pause().stop().destroy() }
        await { activity.resourcesRetired }
    }

    @Test fun fullPlaybackDiagnosticsWithoutAnActiveCastIsInformationalAndClosesOnBackground() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        try {
            org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(activity, "showPlaybackDiagnostics")
            shadowOf(Looper.getMainLooper()).idle()
            val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(dialog.isShowing)
            val details = dialog.window!!.decorView.findViewWithTag<org.screencopy.receiver.ui.PlaybackStatisticsDetails>(
                "playbackStatisticsDetails")
            assertEquals(View.GONE, details.panel.visibility)
            assertNull(shadowOf(activity).nextStartedActivityForResult)
            assertNull(shadowOf(activity).nextStartedService)
            assertNull(shadowOf(activity).lastRequestedPermission)
            controller.pause()
            assertFalse(dialog.isShowing)
        } finally { controller.stop().destroy() }
        await { activity.resourcesRetired }
    }

    @Test fun defaultOffStatisticsHasNoRecurringUiTimerAndBackgroundClearsOverlay() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val handler = org.robolectric.util.ReflectionHelpers.getField<android.os.Handler>(activity, "main")
        val refresh = org.robolectric.util.ReflectionHelpers.getField<Runnable>(activity, "statisticsRefresh")
        val overlay = activity.findViewById<ViewGroup>(android.R.id.content)
            .findViewWithTag<org.screencopy.receiver.ui.PlaybackStatisticsOverlay>("playbackStatistics")
        try {
            assertFalse(handler.hasCallbacks(refresh))
            refresh.run()
            assertFalse(handler.hasCallbacks(refresh))
            assertEquals(View.GONE, overlay.visibility)
            controller.pause()
            assertFalse(handler.hasCallbacks(refresh))
            assertEquals("", overlay.text)
        } finally { controller.stop().destroy() }
        await { activity.resourcesRetired }
    }

    @Test fun recreationBeforeConsentCancelsReservationWithoutLaunchingCapture() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val original = controller.get()
        val request = CastState.requests.reserve(Settings().request().toString(), ReceiverTarget("192.0.2.1", 50000))
        org.robolectric.util.ReflectionHelpers.setField(original, "pendingRequestId", request.id)
        try {
            controller.recreate()
            val replacement = controller.get()
            assertNotSame(original, replacement)
            assertNull("An unlaunched consent request must not strand the outgoing role", CastState.requests.snapshot())
            assertNull(CastState.roles.role())
            assertNull(shadowOf(replacement).nextStartedActivityForResult)
            assertNull(shadowOf(replacement).lastRequestedPermission)
            assertNull(shadowOf(replacement).nextStartedService)
            replacement.onSurfaceTextureAvailable(SurfaceTexture(0), 720, 1280)
            await { replacement.currentListeningPort != null }
            assertTrue(replacement.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        } finally {
            CastState.requests.finish(request.id)
            controller.pause().stop().destroy()
        }
        await { original.resourcesRetired && controller.get().resourcesRetired }
    }

    @Test fun restoredUnlaunchedReservationIsCancelledWithoutRestartingConsent() {
        val request = CastState.requests.reserve(Settings().request().toString(), ReceiverTarget("192.0.2.1", 50000))
        val state = android.os.Bundle().apply { putLong("pendingRequest", request.id) }
        val controller = Robolectric.buildActivity(MainActivity::class.java).create(state).start().resume().visible()
        val activity = controller.get()
        try {
            assertNull(CastState.requests.snapshot())
            assertNull(CastState.roles.role())
            assertNull(shadowOf(activity).nextStartedActivityForResult)
            assertNull(shadowOf(activity).lastRequestedPermission)
            assertNull(shadowOf(activity).nextStartedService)
        } finally {
            CastState.requests.finish(request.id)
            controller.pause().stop().destroy()
        }
        await { activity.resourcesRetired }
    }

    @Test fun recreationPreservesLaunchedPermissionAndProjectionResultsWithoutRelaunching() {
        for ((method, code) in listOf("requestAudioOrProjection" to 10, "requestProjection" to 11)) {
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            val original = controller.get()
            val request = CastState.requests.reserve(Settings().request().toString(), ReceiverTarget("192.0.2.1", 50000))
            org.robolectric.util.ReflectionHelpers.setField(original, "pendingRequestId", request.id)
            try {
                org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(original, method)
                assertEquals(code, checkNotNull(shadowOf(original).nextStartedActivityForResult).requestCode)
                assertTrue(checkNotNull(CastState.requests.snapshot()).consentStarted)
                controller.recreate()
                val replacement = controller.get()
                assertEquals(request.id, checkNotNull(CastState.requests.snapshot()).id)
                assertEquals(RoleOwner.Role.SEND, CastState.roles.role())
                assertNull("Recreation must not launch duplicate consent", shadowOf(replacement).nextStartedActivityForResult)
                if (code == 10) replacement.onRequestPermissionsResult(code, arrayOf(Manifest.permission.RECORD_AUDIO),
                    intArrayOf(android.content.pm.PackageManager.PERMISSION_DENIED))
                else org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(replacement, "onActivityResult",
                    org.robolectric.util.ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, code),
                    org.robolectric.util.ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, Activity.RESULT_CANCELED),
                    org.robolectric.util.ReflectionHelpers.ClassParameter.from(Intent::class.java, null))
                assertNull(CastState.requests.snapshot())
                assertNull(CastState.roles.role())
                assertNull(shadowOf(replacement).nextStartedService)
            } finally {
                CastState.requests.finish(request.id)
                controller.pause().stop().destroy()
            }
            await { original.resourcesRetired && controller.get().resourcesRetired }
        }
    }

    @Test fun nonFinishingDestructionPreservesLaunchedConsentUntilSuccessfulResult() {
        for ((method, code) in listOf("requestAudioOrProjection" to 10, "requestProjection" to 11)) {
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            val original = controller.get()
            val replacementController = Robolectric.buildActivity(MainActivity::class.java)
            val request = CastState.requests.reserve(Settings().request().toString(), ReceiverTarget("192.0.2.1", 50000))
            org.robolectric.util.ReflectionHelpers.setField(original, "pendingRequestId", request.id)
            var destroyed = false
            var restored = false
            try {
                org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(original, method)
                assertEquals(code, checkNotNull(shadowOf(original).nextStartedActivityForResult).requestCode)
                val state = android.os.Bundle()
                controller.pause().stop().saveInstanceState(state)
                assertFalse(original.isFinishing)
                assertFalse(original.isChangingConfigurations)
                controller.destroy()
                destroyed = true
                assertEquals("Non-finishing destruction must retain launched consent",
                    true, CastState.requests.snapshot()?.consentStarted)
                assertEquals(RoleOwner.Role.SEND, CastState.roles.role())

                replacementController.create(state).start().resume().visible()
                restored = true
                val replacement = replacementController.get()
                assertNull("Restoration must not launch duplicate consent", shadowOf(replacement).nextStartedActivityForResult)
                assertNull(shadowOf(replacement).lastRequestedPermission)
                if (code == 10) {
                    replacement.onRequestPermissionsResult(code, arrayOf(Manifest.permission.RECORD_AUDIO),
                        intArrayOf(android.content.pm.PackageManager.PERMISSION_GRANTED))
                    assertEquals(11, checkNotNull(shadowOf(replacement).nextStartedActivityForResult).requestCode)
                }
                org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(replacement, "onActivityResult",
                    org.robolectric.util.ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 11),
                    org.robolectric.util.ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, Activity.RESULT_OK),
                    org.robolectric.util.ReflectionHelpers.ClassParameter.from(Intent::class.java, Intent("test-projection")))
                val service = checkNotNull(shadowOf(replacement).nextStartedService)
                assertEquals(CastService::class.java.name, service.component?.className)
                assertEquals(request.id, service.getLongExtra("request", -1))
                assertNull(shadowOf(replacement).nextStartedService)
            } finally {
                CastState.requests.finish(request.id)
                if (!destroyed) controller.pause().stop().destroy()
                if (restored) replacementController.pause().stop().destroy()
            }
            await { original.resourcesRetired && replacementController.get().resourcesRetired }
        }
    }

    @Test fun finishingActivityCancelsLaunchedConsent() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val request = CastState.requests.reserve(Settings().request().toString(), ReceiverTarget("192.0.2.1", 50000))
        org.robolectric.util.ReflectionHelpers.setField(activity, "pendingRequestId", request.id)
        var destroyed = false
        try {
            org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(activity, "requestProjection")
            assertTrue(checkNotNull(CastState.requests.snapshot()).consentStarted)
            activity.finish()
            assertTrue(activity.isFinishing)
            controller.pause().stop().destroy()
            destroyed = true
            assertNull(CastState.requests.snapshot())
            assertNull(CastState.roles.role())
            assertNull(shadowOf(activity).nextStartedService)
        } finally {
            CastState.requests.finish(request.id)
            if (!destroyed) controller.pause().stop().destroy()
        }
        await { activity.resourcesRetired }
    }

    @Test fun staleActivityCancellationDoesNotOverwriteAnAdoptedServiceStatus() {
        val previousMessage = CastState.message
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val request = CastState.requests.reserve(Settings().request().toString(), ReceiverTarget("192.0.2.1", 50000))
        org.robolectric.util.ReflectionHelpers.setField(activity, "pendingRequestId", request.id)
        try {
            CastState.requests.beginConsent(request.id)
            CastState.requests.adopt(request.id)
            CastState.message = "Streaming"
            org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(activity, "cancelPending",
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(String::class.java, "Cancelled"))
            assertEquals("Streaming", CastState.message)
            assertTrue(checkNotNull(CastState.requests.snapshot()).adopted)
            assertEquals(RoleOwner.Role.SEND, CastState.roles.role())
        } finally {
            CastState.requests.finish(request.id)
            controller.pause().stop().destroy()
            CastState.message = previousMessage
        }
        await { activity.resourcesRetired }
    }

    @Test fun newReceiveSessionClearsPreviousErrorButRetainsNewFailures() {
        val previousFailure = CastState.failure
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val errorCard = activity.window.decorView.findViewWithTag<View>("home_error")
        fun event(value: org.screencopy.receiver.ui.ReceiverScreenEvent) {
            org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(activity, "screenEvent",
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(
                    org.screencopy.receiver.ui.ReceiverScreenEvent::class.java, value))
        }
        try {
            CastState.failure = "Previous send failure"
            event(org.screencopy.receiver.ui.ReceiverScreenEvent.Problem("PROTOCOL"))
            assertEquals(View.VISIBLE, errorCard.visibility)
            event(org.screencopy.receiver.ui.ReceiverScreenEvent.CleanupFinished)
            assertEquals("Cleanup must retain a genuine failure", View.VISIBLE, errorCard.visibility)
            event(org.screencopy.receiver.ui.ReceiverScreenEvent.Connecting)
            assertEquals("A new receive session must not inherit the previous error", View.GONE, errorCard.visibility)
            org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(activity, "showPlayer")
            event(org.screencopy.receiver.ui.ReceiverScreenEvent.CleanupFinished)
            assertEquals("Normal Stop must not reveal the old failure again", View.GONE, errorCard.visibility)
            event(org.screencopy.receiver.ui.ReceiverScreenEvent.Problem("DECODER_FAILED"))
            assertEquals(View.VISIBLE, errorCard.visibility)
        } finally {
            controller.pause().stop().destroy()
            CastState.failure = previousFailure
        }
        await { activity.resourcesRetired }
    }

    @Test fun simulatedAndroid17DenialBlocksSocketsWithoutRequestingCaptureOrMic() {
        org.robolectric.util.ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", 37)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        try {
            activity.onSurfaceTextureAvailable(SurfaceTexture(0), 720, 1280)
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(activity.currentListeningPort)
            assertNull(CastState.roles.role())
            assertNotNull(shadowOf(activity).lastRequestedPermission)
            assertOnlyLocalNetworkPrompt(activity)
            activity.onRequestPermissionsResult(LocalNetworkPermission.REQUEST,
                arrayOf(LocalNetworkPermission.NAME), intArrayOf(android.content.pm.PackageManager.PERMISSION_DENIED))
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(800))
            assertNull(activity.currentListeningPort)
            assertNull(shadowOf(activity).nextStartedActivityForResult)
            assertTrue(buttons(activity.window.decorView).any {
                it.tag == "local_network_permission" && it.visibility == View.VISIBLE && it.isFocusable })
            assertNotEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,
                activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        } finally {
            controller.pause().stop().destroy()
            org.robolectric.util.ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", 30)
        }
    }

    @Test fun simulatedAndroid17LanGrantResumesListeningWithoutCaptureConsent() {
        org.robolectric.util.ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", 37)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        try {
            activity.onSurfaceTextureAvailable(SurfaceTexture(0), 720, 1280)
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(activity.currentListeningPort)
            assertOnlyLocalNetworkPrompt(activity)
            shadowOf(activity.application).grantPermissions(LocalNetworkPermission.NAME)
            activity.onRequestPermissionsResult(LocalNetworkPermission.REQUEST,
                arrayOf(LocalNetworkPermission.NAME), intArrayOf(android.content.pm.PackageManager.PERMISSION_GRANTED))
            await { activity.currentListeningPort != null }
            assertNull(shadowOf(activity).nextStartedActivityForResult)
            assertNull(CastState.roles.role())
        } finally {
            controller.pause().stop().destroy()
            org.robolectric.util.ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", 30)
        }
    }

    private fun assertOnlyLocalNetworkPrompt(activity: MainActivity) {
        val request = checkNotNull(shadowOf(activity).nextStartedActivityForResult)
        assertEquals(LocalNetworkPermission.REQUEST, request.requestCode)
        assertEquals("android.content.pm.action.REQUEST_PERMISSIONS", request.intent.action)
        assertArrayEquals(arrayOf(LocalNetworkPermission.NAME),
            request.intent.getStringArrayExtra("android.content.pm.extra.REQUEST_PERMISSIONS_NAMES"))
        assertNull(shadowOf(activity).nextStartedActivityForResult)
    }

    private fun buttons(view: View): List<Button> = buildList {
        if (view is Button) add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(buttons(view.getChildAt(index)))
    }

    private fun await(condition: () -> Boolean) {
        val start = System.nanoTime()
        while (!condition() && System.nanoTime() - start < 5_000_000_000L) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("Activity lifecycle did not reach expected state", condition())
    }

    @Test fun automaticListenerNeedsNoCaptureConsentAndDoesNotReserveAnIncomingSession() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        try {
            assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            assertNull(shadowOf(activity).lastRequestedPermission)
            assertNull(shadowOf(activity).nextStartedActivityForResult)
            assertNotEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,
                activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
            assertFalse(buttons(activity.window.decorView).any { it.text == "Receive" })
            assertFalse(buttons(activity.window.decorView).any { it.text.contains("Find receivers") })
            activity.onSurfaceTextureAvailable(SurfaceTexture(0), 720, 1280)
            await { activity.currentListeningPort != null }
            Socket(InetAddress.getLoopbackAddress(), checkNotNull(activity.currentListeningPort)).use {
                assertTrue(it.isConnected)
            }
            assertNull(CastState.roles.role())
        } finally { controller.pause().stop().destroy() }
        await { CastState.roles.role() == null && activity.resourcesRetired }
    }

    @Test fun foregroundListeningStopsWhenBackgroundedAndRestartsWithoutManualControls() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        try {
            activity.onSurfaceTextureAvailable(SurfaceTexture(0), 720, 1280)
            await { activity.currentListeningPort != null }
            val port = checkNotNull(activity.currentListeningPort)
            controller.pause().stop()
            assertNull(activity.currentListeningPort)
            await {
                try { Socket(InetAddress.getLoopbackAddress(), port).use { false } }
                catch (_: java.io.IOException) { true }
            }
            controller.start().resume()
            await { activity.currentListeningPort != null }
        } finally { controller.pause().stop().destroy() }
        await { activity.resourcesRetired }
    }

    @Test fun declinedConsentRestoresReadinessAndDoesNotStartForegroundService() {
        val request = CastState.requests.reserve(Settings().request().toString(), ReceiverTarget("192.0.2.1", 50000))
        CastState.requests.beginConsent(request.id)
        val state = android.os.Bundle().apply { putLong("pendingRequest", request.id) }
        val controller = Robolectric.buildActivity(MainActivity::class.java).create(state).start().resume().visible()
        val activity = controller.get()
        try {
            activity.onSurfaceTextureAvailable(SurfaceTexture(0), 720, 1280)
            assertNull(activity.currentListeningPort)
            org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(activity, "onActivityResult",
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 11),
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, Activity.RESULT_CANCELED),
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(Intent::class.java, null))
            await { activity.currentListeningPort != null }
            assertNull(CastState.requests.snapshot())
            assertNull(CastState.roles.role())
            assertNull(shadowOf(activity).nextStartedService)
        } finally {
            CastState.requests.finish(request.id)
            controller.pause().stop().destroy()
        }
        await { activity.resourcesRetired }
    }

    @Test fun outgoingServiceOwnsPausedReceiverUntilCleanupThenForegroundAutomaticallyResumes() {
        val request = CastState.requests.reserve(Settings().request().toString(), ReceiverTarget("192.0.2.1", 50000))
        CastState.requests.adopt(request.id)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        try {
            activity.onSurfaceTextureAvailable(SurfaceTexture(0), 720, 1280)
            assertNull(activity.currentListeningPort)
            assertEquals(RoleOwner.Role.SEND, CastState.roles.role())
            assertEquals("Outgoing app UI must be capturable, not a secure black window", 0,
                activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
            CastState.requests.finish(request.id)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
            await { activity.currentListeningPort != null }
            assertNull(CastState.roles.role())
            assertTrue("Receiver security must be restored before it becomes ready",
                activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            controller.pause().stop().start().resume()
            assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        } finally {
            CastState.requests.finish(request.id)
            controller.pause().stop().destroy()
        }
        await { activity.resourcesRetired }
    }

    @Test fun serviceStartupFailureReleasesReservationAndKeepsErrorVisible() {
        val request = CastState.requests.reserve(Settings().request().toString(), ReceiverTarget("192.0.2.1", 50000))
        val service = Robolectric.buildService(CastService::class.java).create()
        try {
            service.get().onStartCommand(Intent().setAction("start").putExtra("request", request.id), 0, 1)
            assertNull(CastState.roles.role())
            assertNull(CastState.requests.snapshot())
            assertTrue(CastState.failure?.contains("consent") == true)
        } finally {
            service.destroy()
            CastState.requests.finish(request.id)
            CastState.failure = null
        }
    }

    @Test fun receivingVideoHasNoInitialOrInteractiveLocalSessionControls() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val video = org.robolectric.util.ReflectionHelpers.getField<FrameLayout>(activity, "videoBox")
        val home = org.robolectric.util.ReflectionHelpers.getField<HomeView>(activity, "home")
        try {
            assertEquals(1, video.childCount)
            val texture = video.getChildAt(0)
            assertTrue(texture is TextureView)
            org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(activity, "showPlayer")
            assertEquals(View.GONE, home.visibility)
            assertEquals(1, video.childCount)
            assertFalse(video.hasOnClickListeners())
            val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 10f, 10f, 0)
            val up = MotionEvent.obtain(0, 1, MotionEvent.ACTION_UP, 10f, 10f, 0)
            try { video.dispatchTouchEvent(down); video.dispatchTouchEvent(up) }
            finally { down.recycle(); up.recycle() }
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, video.childCount)
            assertSame(texture, video.getChildAt(0))
            assertTrue(buttons(video).isEmpty())
            assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(activity, "hidePlayer")
            assertEquals(View.VISIBLE, home.visibility)
        } finally { controller.pause().stop().destroy() }
        await { activity.resourcesRetired }
    }

    @Test fun appIdentityIsUnifiedAndNew() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        assertEquals("dev.redrain.cast", context.packageName)
        assertEquals("RedrainCast", context.applicationInfo.loadLabel(context.packageManager).toString())
        assertEquals(30, context.applicationInfo.minSdkVersion)
    }
}

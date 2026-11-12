package dev.redrain.cast

import android.app.AlertDialog
import android.content.Intent
import android.os.Looper
import dev.screencopy.sender.ReceiverTarget
import dev.screencopy.sender.Settings
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class ImmediateUpdateWorker : AbstractExecutorService() {
    private var closed = false
    override fun execute(command: Runnable) { command.run() }
    override fun shutdown() { closed = true }
    override fun shutdownNow(): MutableList<Runnable> { closed = true; return mutableListOf() }
    override fun isShutdown() = closed
    override fun isTerminated() = closed
    override fun awaitTermination(timeout: Long, unit: TimeUnit) = closed
}

internal fun noUpdateCheck(version: String) = LaunchUpdateCheck(object : UpdateSource {
    override fun check(installedVersion: String): ReleaseUpdate? = null
    override fun cancel() = Unit
}, version, ImmediateUpdateWorker())

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class LaunchUpdateTest {
    private val previousFactory = MainActivity.updateFactory
    private lateinit var controller: ActivityController<MainActivity>
    private var checks = 0
    private var checkedVersion: String? = null
    private var cancelled = 0
    private var failRequest = false
    private var result: ReleaseUpdate? = ReleaseUpdate("0.5.0",
        "https://github.com/arisewanggithub/RedrainCast-Android/releases/tag/v0.5.0")

    @Before fun configure() {
        MainActivity.updateFactory = { version ->
            LaunchUpdateCheck(object : UpdateSource {
                override fun check(installedVersion: String): ReleaseUpdate? {
                    checks++
                    checkedVersion = installedVersion
                    if (failRequest) error("Fixture update request failed")
                    return result
                }
                override fun cancel() { cancelled++ }
            }, version, ImmediateUpdateWorker())
        }
    }

    @After fun cleanup() {
        if (::controller.isInitialized) {
            controller.pause().stop().destroy()
            val activity = controller.get()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!activity.resourcesRetired && System.nanoTime() < deadline) Thread.sleep(5)
            assertTrue(activity.resourcesRetired)
        }
        MainActivity.updateFactory = previousFactory
    }

    private fun open() {
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        controller.get().onWindowFocusChanged(true)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun prompt() = checkNotNull(ShadowAlertDialog.getLatestAlertDialog())

    @Test fun debugBuildComparesItsBaseVersionWithoutTreatingDebugAsAPrerelease() {
        result = null
        open()
        assertEquals("0.4.19", checkedVersion)
        assertEquals(if (BuildConfig.DEBUG) "0.4.19-debug" else "0.4.19", BuildConfig.VERSION_NAME)
        assertEquals(1, checks)
        assertNull(ShadowAlertDialog.getLatestAlertDialog())
    }

    @Test fun newerReleasePromptsOnlyOnceAndLaterDoesNotRetryOnOrdinaryResume() {
        open()
        val dialog = prompt()
        assertTrue(dialog.isShowing)
        assertEquals(1, checks)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        controller.pause().stop().start().resume()
        controller.get().onWindowFocusChanged(true)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
        assertFalse(dialog.isShowing)
        assertEquals(1, checks)
    }

    @Test fun currentVersionAndFailedChecksDoNotShowPromptsOrDisruptReadiness() {
        failRequest = true
        open()
        assertNull(ShadowAlertDialog.getLatestAlertDialog())
        assertEquals(1, checks)
        assertNull(CastState.roles.role())
        assertNull(shadowOf(controller.get()).nextStartedActivity)
    }

    @Test fun noNewReleaseDoesNotShowADialog() {
        result = null
        open()
        assertNull(ShadowAlertDialog.getLatestAlertDialog())
        assertEquals(1, checks)
    }

    @Test fun recreationRetainsTheCheckAndLaterChoiceWithoutAnotherRequest() {
        open()
        prompt().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        controller.recreate()
        controller.get().onWindowFocusChanged(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, checks)
        assertFalse(prompt().isShowing)
    }

    @Test fun lifecycleDismissalDoesNotCountAsTheUsersLaterChoice() {
        open()
        val oldDialog = prompt()
        controller.pause().stop()
        assertFalse(oldDialog.isShowing)
        controller.start().resume()
        controller.get().onWindowFocusChanged(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(prompt().isShowing)
        assertNotSame(oldDialog, prompt())
        assertEquals(1, checks)
    }

    @Test fun resultWaitsForResumedWindowFocusWithoutStartingAnotherRequest() {
        controller = Robolectric.buildActivity(MainActivity::class.java).create().start()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(ShadowAlertDialog.getLatestAlertDialog())
        controller.resume().visible()
        assertNull(ShadowAlertDialog.getLatestAlertDialog())
        controller.get().onWindowFocusChanged(true)
        assertTrue(prompt().isShowing)
        assertEquals(1, checks)
    }

    @Test fun updateWaitsUntilTheOutgoingReservationIsReleased() {
        val request = CastState.requests.reserve(Settings().request().toString(), ReceiverTarget("192.0.2.1", 50000))
        try {
            open()
            assertNull(ShadowAlertDialog.getLatestAlertDialog())
            assertEquals(request.id, CastState.requests.snapshot()?.id)
            CastState.requests.finish(request.id)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
            assertTrue(prompt().isShowing)
            assertEquals(1, checks)
        } finally { CastState.requests.finish(request.id) }
    }

    @Test fun incomingSessionDismissesThePromptWithoutHandlingItOrReleasingItsRole() {
        open()
        val old = prompt()
        val lease = CastState.roles.acquire(RoleOwner.Role.RECEIVE)
        try {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
            assertFalse(old.isShowing)
            assertTrue(CastState.roles.owns(lease))
            CastState.roles.release(lease)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
            assertTrue(prompt().isShowing)
            assertEquals(1, checks)
        } finally { CastState.roles.release(lease) }
    }

    @Test fun updatePromptYieldsToNewApplicationErrorAndReturnsAfterDismissal() {
        open()
        val old = prompt()
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(controller.get(), "showError",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Exception::class.java,
                IllegalStateException("Synthetic listener failure")))
        assertFalse("A new application error must withdraw the existing update modal", old.isShowing)
        dismissHomeError()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
        assertTrue(prompt().isShowing)
        assertNotSame(old, prompt())
        assertEquals(1, checks)
    }

    @Test fun updatePromptYieldsToLaterServiceFailureWithoutMarkingUpdateHandled() {
        val previousFailure = CastState.failure
        try {
            open()
            val old = prompt()
            CastState.failure = "Synthetic sender failure"
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
            assertFalse("Global sender failure must take priority over an existing update modal", old.isShowing)
            dismissHomeError()
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
            assertTrue(prompt().isShowing)
            assertNotSame(old, prompt())
            assertEquals(1, checks)
        } finally { CastState.failure = previousFailure }
    }

    private fun dismissHomeError() {
        val card = controller.get().window.decorView.findViewWithTag<android.view.ViewGroup>("home_error")
        val dismiss = (0 until card.childCount).map(card::getChildAt).filterIsInstance<android.widget.Button>().single()
        dismiss.performClick()
    }

    @Test fun downloadOpensOnlyTheBrowserReleasePageAfterExplicitPress() {
        open()
        assertNull(shadowOf(controller.get()).nextStartedActivity)
        prompt().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val intent = checkNotNull(shadowOf(controller.get()).nextStartedActivity)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(result?.page, intent.dataString)
        assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertNull(intent.type)
        assertNull(shadowOf(controller.get()).nextStartedService)
    }

    @Test fun closingARetainedCheckCancelsItAndRejectsLateResults() {
        var pending: Runnable? = null
        val worker = object : AbstractExecutorService() {
            private var closed = false
            override fun execute(command: Runnable) { pending = command }
            override fun shutdown() { closed = true }
            override fun shutdownNow(): MutableList<Runnable> { closed = true; return mutableListOf() }
            override fun isShutdown() = closed
            override fun isTerminated() = closed
            override fun awaitTermination(timeout: Long, unit: TimeUnit) = closed
        }
        val check = LaunchUpdateCheck(object : UpdateSource {
            override fun check(installedVersion: String) = result
            override fun cancel() { cancelled++ }
        }, "0.4.10", worker)
        var notifications = 0
        check.attach { notifications++ }
        check.start()
        check.close()
        checkNotNull(pending).run()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(check.available)
        assertEquals(0, notifications)
        assertEquals(1, cancelled)
    }

    @Test fun metadataRequestRunsOffTheUiThread() {
        val finished = java.util.concurrent.CountDownLatch(1)
        val background = AtomicBoolean()
        val check = LaunchUpdateCheck(object : UpdateSource {
            override fun check(installedVersion: String): ReleaseUpdate? {
                background.set(Looper.myLooper() != Looper.getMainLooper())
                finished.countDown()
                return null
            }
            override fun cancel() = Unit
        }, "0.4.10")
        try {
            check.start()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertTrue(background.get())
        } finally { check.close() }
    }
}

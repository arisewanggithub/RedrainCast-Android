package org.screencopy.receiver.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.screencopy.receiver.protocol.ReceiverOrientation

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ReceiverDisplayOrientationTest {
    @Test @Config(qualifiers = "land")
    fun leanbackAndLegacyTelevisionFeaturesKeepTheReceiverLandscape() {
        @Suppress("DEPRECATION")
        val televisionFeatures = listOf(PackageManager.FEATURE_LEANBACK, PackageManager.FEATURE_TELEVISION)
        for (feature in televisionFeatures) withActivity { activity ->
            shadowOf(activity.packageManager).setSystemFeature(feature, true)
            verifyFixed(activity, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
            shadowOf(activity.packageManager).setSystemFeature(feature, false)
        }
    }

    @Test @Config(qualifiers = "land-television")
    fun televisionUiModeKeepsLandscapeWithoutFeatureDeclarations() = withActivity { activity ->
        verifyFixed(activity, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
    }

    @Test @Config(qualifiers = "port-television")
    fun televisionUiModeKeepsPortraitWithoutFeatureDeclarations() = withActivity { activity ->
        verifyFixed(activity, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
    }

    @Test fun landscapeOnlyDisplayKeepsLandscapeWithoutTelevisionFeatures() = withActivity { activity ->
        shadowOf(activity.packageManager).setSystemFeature(PackageManager.FEATURE_SCREEN_LANDSCAPE, true)
        shadowOf(activity.packageManager).setSystemFeature(PackageManager.FEATURE_SCREEN_PORTRAIT, false)
        verifyFixed(activity, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
    }

    @Test fun portraitOnlyDisplayKeepsPortraitWithoutTelevisionFeatures() = withActivity { activity ->
        shadowOf(activity.packageManager).setSystemFeature(PackageManager.FEATURE_SCREEN_LANDSCAPE, false)
        shadowOf(activity.packageManager).setSystemFeature(PackageManager.FEATURE_SCREEN_PORTRAIT, true)
        verifyFixed(activity, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
    }

    @Test @Config(qualifiers = "land-television")
    fun declaredPortraitOnlyAxisWinsOverTelevisionWindowConfiguration() = withActivity { activity ->
        shadowOf(activity.packageManager).setSystemFeature(PackageManager.FEATURE_SCREEN_LANDSCAPE, false)
        shadowOf(activity.packageManager).setSystemFeature(PackageManager.FEATURE_SCREEN_PORTRAIT, true)
        verifyFixed(activity, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
    }

    @Test fun portableDisplayStillFollowsVideoAndHonorsExplicitPortrait() = withActivity { activity ->
        shadowOf(activity.packageManager).setSystemFeature(PackageManager.FEATURE_SCREEN_LANDSCAPE, true)
        shadowOf(activity.packageManager).setSystemFeature(PackageManager.FEATURE_SCREEN_PORTRAIT, true)
        verifyPortable(activity)
    }

    @Test fun missingOrientationFeaturesDoNotAssumeAFixedLandscapeDisplay() = withActivity { activity ->
        shadowOf(activity.packageManager).setSystemFeature(PackageManager.FEATURE_SCREEN_LANDSCAPE, false)
        shadowOf(activity.packageManager).setSystemFeature(PackageManager.FEATURE_SCREEN_PORTRAIT, false)
        verifyPortable(activity)
    }

    @Test fun fixedLandscapePreservesFittedPortraitPicturesAndQuarterTurns() = withActivity { activity ->
        shadowOf(activity.packageManager).setSystemFeature(PackageManager.FEATURE_SCREEN_LANDSCAPE, true)
        shadowOf(activity.packageManager).setSystemFeature(PackageManager.FEATURE_SCREEN_PORTRAIT, false)
        verifyFittedPictures(activity, 1920, 1080, 9.0 / 16, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
    }

    @Test fun fixedPortraitPreservesFittedLandscapePicturesAndQuarterTurns() = withActivity { activity ->
        shadowOf(activity.packageManager).setSystemFeature(PackageManager.FEATURE_SCREEN_LANDSCAPE, false)
        shadowOf(activity.packageManager).setSystemFeature(PackageManager.FEATURE_SCREEN_PORTRAIT, true)
        verifyFittedPictures(activity, 1080, 1920, 16.0 / 9, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
    }

    private fun verifyFittedPictures(activity: Activity, boxWidth: Int, boxHeight: Int, aspect: Double, axis: Int) {
        val policy = SessionOrientation.forActivity(activity)
        policy.begin(Any(), ReceiverOrientation.AUTO)
        for (rotation in listOf(0, 90, 180, 270)) {
            policy.followVideo(aspect, rotation)
            val sourceWidth = if (aspect < 1) 1080 else 1920
            val sourceHeight = if (aspect < 1) 1920 else 1080
            val geometry = checkNotNull(VideoPresentation.geometry(boxWidth, boxHeight, sourceWidth, sourceHeight, rotation))
            assertEquals(axis, activity.requestedOrientation)
            val width = geometry.destination.width()
            val height = geometry.destination.height()
            assertTrue(width in 1..boxWidth)
            assertTrue(height in 1..boxHeight)
            assertEquals(if (rotation % 180 == 0) aspect else 1.0 / aspect, width.toDouble() / height, 0.002)
        }
        policy.restore()
    }

    private fun verifyFixed(activity: Activity, axis: Int) {
        val policy = SessionOrientation.forActivity(activity)
        for (mode in ReceiverOrientation.entries) {
            val owner = Any()
            policy.begin(owner, mode)
            policy.followVideo(9.0 / 16, 0)
            assertEquals(axis, activity.requestedOrientation)
            policy.restore(owner)
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER, activity.requestedOrientation)
        }
    }

    private fun verifyPortable(activity: Activity) {
        val policy = SessionOrientation.forActivity(activity)
        policy.begin(Any(), ReceiverOrientation.AUTO)
        policy.followVideo(9.0 / 16, 0)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.requestedOrientation)
        policy.followVideo(9.0 / 16, 90)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, activity.requestedOrientation)
        policy.begin(Any(), ReceiverOrientation.PORTRAIT)
        policy.followVideo(9.0 / 16, 90)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.requestedOrientation)
        policy.restore()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER, activity.requestedOrientation)
    }

    private fun withActivity(block: (Activity) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER
            block(activity)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}

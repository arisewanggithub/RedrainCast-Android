package org.screencopy.receiver.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.view.TextureView
import android.widget.FrameLayout
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
    @Test fun leanbackAndLegacyTelevisionFeaturesKeepTheReceiverLandscape() {
        @Suppress("DEPRECATION")
        val televisionFeatures = listOf(PackageManager.FEATURE_LEANBACK, PackageManager.FEATURE_TELEVISION)
        for (feature in televisionFeatures) withActivity { activity ->
            shadowOf(activity.packageManager).setSystemFeature(feature, true)
            verifyFixedLandscape(activity)
            shadowOf(activity.packageManager).setSystemFeature(feature, false)
        }
    }

    @Test @Config(qualifiers = "television")
    fun televisionUiModeKeepsLandscapeWithoutFeatureDeclarations() = withActivity { activity ->
        verifyFixedLandscape(activity)
    }

    @Test fun landscapeOnlyDisplayKeepsLandscapeWithoutTelevisionFeatures() = withActivity { activity ->
        shadowOf(activity.packageManager).setSystemFeature(PackageManager.FEATURE_SCREEN_LANDSCAPE, true)
        shadowOf(activity.packageManager).setSystemFeature(PackageManager.FEATURE_SCREEN_PORTRAIT, false)
        verifyFixedLandscape(activity)
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
        shadowOf(activity.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        val policy = SessionOrientation.forActivity(activity)
        policy.begin(Any(), ReceiverOrientation.AUTO)
        val box = FrameLayout(activity).apply { layout(0, 0, 1920, 1080) }
        val texture = TextureView(activity)
        box.addView(texture)
        for (rotation in listOf(0, 90, 180, 270)) {
            policy.followVideo(9.0 / 16, rotation)
            VideoPresentation.fit(texture, box, 9.0 / 16, rotation)
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, activity.requestedOrientation)
            assertEquals(rotation.toFloat(), texture.rotation, 0f)
            val width = if (rotation % 180 == 0) texture.layoutParams.width else texture.layoutParams.height
            val height = if (rotation % 180 == 0) texture.layoutParams.height else texture.layoutParams.width
            assertTrue(width in 1..1920)
            assertTrue(height in 1..1080)
            assertEquals(if (rotation % 180 == 0) 9.0 / 16 else 16.0 / 9, width.toDouble() / height, 0.002)
        }
        policy.restore()
    }

    private fun verifyFixedLandscape(activity: Activity) {
        val policy = SessionOrientation.forActivity(activity)
        for (mode in ReceiverOrientation.entries) {
            val owner = Any()
            policy.begin(owner, mode)
            policy.followVideo(9.0 / 16, 0)
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, activity.requestedOrientation)
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

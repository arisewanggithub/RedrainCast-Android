package dev.redrain.cast

import android.Manifest
import android.content.pm.PackageManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class LocalNetworkPermissionTest {
    @Test fun onlyAndroid17AndLaterRequireTheAdditionalLanConsent() {
        assertFalse(LocalNetworkPermission.required(30))
        assertFalse(LocalNetworkPermission.required(36))
        assertTrue(LocalNetworkPermission.required(37))
        assertTrue(LocalNetworkPermission.required(38))
        assertEquals(Manifest.permission.ACCESS_LOCAL_NETWORK, LocalNetworkPermission.NAME)
        assertNotEquals(Manifest.permission.RECORD_AUDIO, LocalNetworkPermission.NAME)
    }

    @Test fun manifestTargets37ButOlderAndroidDoesNotRequestCaptureOrLanPermission() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        try {
            assertEquals(37, activity.applicationInfo.targetSdkVersion)
            assertTrue(LocalNetworkPermission.granted(activity))
            val permissions = activity.packageManager.getPackageInfo(activity.packageName,
                PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
            assertTrue(LocalNetworkPermission.NAME in permissions)
            assertNull(org.robolectric.Shadows.shadowOf(activity).lastRequestedPermission)
            assertNull(org.robolectric.Shadows.shadowOf(activity).nextStartedActivityForResult)
        } finally { controller.pause().stop().destroy() }
    }
}

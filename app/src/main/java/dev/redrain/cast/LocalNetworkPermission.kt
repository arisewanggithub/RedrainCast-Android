package dev.redrain.cast

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

internal object LocalNetworkPermission {
    const val REQUEST = 13
    @SuppressLint("InlinedApi") // This is a string name; requests/checks are gated to API 37.
    const val NAME = Manifest.permission.ACCESS_LOCAL_NETWORK

    fun required(sdk: Int) = sdk >= 37

    fun granted(context: Context): Boolean =
        !required(Build.VERSION.SDK_INT) || context.checkSelfPermission(NAME) == PackageManager.PERMISSION_GRANTED
}

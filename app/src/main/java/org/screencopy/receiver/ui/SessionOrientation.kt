package org.screencopy.receiver.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import org.screencopy.receiver.protocol.ReceiverOrientation

/** UI-thread-only ownership; stale session cleanup cannot restore over a newer session. */
internal class SessionOrientation(
    private val current: () -> Int,
    private val request: (Int) -> Unit,
    private val fixedOrientation: Int?
) {
    constructor(current: () -> Int, request: (Int) -> Unit) : this(current, request, null)

    init {
        require(fixedOrientation == null || fixedOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE ||
            fixedOrientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
    }

    private var owner: Any? = null
    private var mode: ReceiverOrientation? = null
    private var videoPolicy: Int? = null
    var previousPolicy: Int? = null
        private set

    fun begin(token: Any, requested: ReceiverOrientation) {
        if (owner === token && mode == requested) return
        if (owner !== token) {
            restore()
            previousPolicy = current()
            owner = token
        }
        mode = requested
        videoPolicy = null
        val target = fixedOrientation ?: when (requested) {
            ReceiverOrientation.AUTO -> checkNotNull(previousPolicy)
            ReceiverOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            ReceiverOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        if (current() != target) request(target)
    }

    fun followVideo(aspect: Double, rotation: Int) {
        require(aspect.isFinite() && aspect > 0 && rotation in listOf(0, 90, 180, 270))
        if (owner == null || mode != ReceiverOrientation.AUTO || fixedOrientation != null) return
        val displayedAspect = if (rotation % 180 == 0) aspect else 1.0 / aspect
        val target = when {
            displayedAspect > 1.0 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            displayedAspect < 1.0 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> return
        }
        if (videoPolicy == target) return
        videoPolicy = target
        if (current() != target) request(target)
    }

    fun restore(token: Any? = owner) {
        if (owner == null || owner !== token) return
        val previous = checkNotNull(previousPolicy)
        if (current() != previous) request(previous)
        owner = null
        mode = null
        videoPolicy = null
        previousPolicy = null
    }

    companion object {
        fun forActivity(activity: Activity): SessionOrientation {
            val features = activity.packageManager
            val television = activity.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK ==
                Configuration.UI_MODE_TYPE_TELEVISION
            @Suppress("DEPRECATION")
            val leanback = features.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
                features.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
            val landscape = features.hasSystemFeature(PackageManager.FEATURE_SCREEN_LANDSCAPE)
            val portrait = features.hasSystemFeature(PackageManager.FEATURE_SCREEN_PORTRAIT)
            val fixedOrientation = when {
                landscape && !portrait -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                portrait && !landscape -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                television || leanback -> if (activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT)
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                else -> null
            }
            android.util.Log.i("ScreenCopyReceiver", "event=RECEIVER_ORIENTATION_POLICY fixed_orientation=$fixedOrientation")
            return SessionOrientation({ activity.requestedOrientation }, { activity.requestedOrientation = it }, fixedOrientation)
        }
    }
}

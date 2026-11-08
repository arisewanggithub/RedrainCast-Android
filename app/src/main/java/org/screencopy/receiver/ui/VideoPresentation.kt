package org.screencopy.receiver.ui

import android.graphics.Rect
import android.view.Gravity
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import org.screencopy.receiver.media.VideoFormat
import org.screencopy.receiver.media.VideoPlayer

internal object VideoPresentation {
    enum class Mode { SDR_TEXTURE, HDR_SURFACE }

    data class Geometry(val sourceWidth: Int, val sourceHeight: Int, val destination: Rect, val rotation: Int)

    fun mode(format: VideoFormat): Mode {
        val hdr = format.videoColor != null
        requireSupportedRotation(hdr, format.rotation)
        return if (hdr) Mode.HDR_SURFACE else Mode.SDR_TEXTURE
    }

    fun requireSupportedRotation(hdr: Boolean, rotation: Int) = VideoPlayer.requireSupportedRotation(hdr, rotation)

    fun geometry(width: Int, height: Int, sourceWidth: Int, sourceHeight: Int, rotation: Int): Geometry? {
        require(sourceWidth > 0 && sourceHeight > 0)
        val destination = fitted(width, height, sourceWidth.toDouble() / sourceHeight, rotation) ?: return null
        return Geometry(sourceWidth, sourceHeight, destination, rotation)
    }

    fun fit(texture: TextureView, box: View, aspect: Double, rotation: Int) {
        val destination = fitted(box.width, box.height, aspect, rotation) ?: return
        val rotated = rotation % 180 != 0
        val width = if (rotated) destination.height() else destination.width()
        val height = if (rotated) destination.width() else destination.height()
        texture.rotation = rotation.toFloat()
        if (texture.layoutParams.width != width || texture.layoutParams.height != height)
            texture.layoutParams = FrameLayout.LayoutParams(width, height, Gravity.CENTER)
    }

    fun fitHdr(surface: SurfaceView, box: View, aspect: Double) {
        val destination = fitted(box.width, box.height, aspect, 0) ?: return
        check(surface.rotation == 0f) { "HDR SurfaceView must remain unrotated" }
        if (surface.layoutParams.width != destination.width() || surface.layoutParams.height != destination.height())
            surface.layoutParams = FrameLayout.LayoutParams(destination.width(), destination.height(), Gravity.CENTER)
    }

    private fun fitted(width: Int, height: Int, aspect: Double, rotation: Int): Rect? {
        require(aspect.isFinite() && aspect > 0 && rotation in listOf(0, 90, 180, 270))
        if (width <= 0 || height <= 0) return null
        val shownAspect = if (rotation % 180 != 0) 1.0 / aspect else aspect
        val fittedWidth = minOf(width, (height * shownAspect).toInt()).coerceAtLeast(1)
        val fittedHeight = minOf(height, (width / shownAspect).toInt()).coerceAtLeast(1)
        val left = (width - fittedWidth) / 2
        val top = (height - fittedHeight) / 2
        return Rect(left, top, left + fittedWidth, top + fittedHeight)
    }
}

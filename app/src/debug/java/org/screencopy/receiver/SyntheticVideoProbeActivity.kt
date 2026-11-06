package org.screencopy.receiver

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.WindowInsets
import android.widget.FrameLayout
import java.util.concurrent.atomic.AtomicInteger
import org.screencopy.receiver.protocol.ReceiverOrientation
import org.screencopy.receiver.ui.SessionOrientation
import org.screencopy.receiver.ui.VideoPresentation

/** No network, capture, microphone, or real-media input: synthetic instrumentation only. */
class SyntheticVideoProbeActivity : Activity(), TextureView.SurfaceTextureListener {
    lateinit var texture: TextureView
        private set
    lateinit var box: FrameLayout
        private set
    lateinit var root: FrameLayout
        private set
    var output: Surface? = null
        private set
    val updates = AtomicInteger()
    val configurations = AtomicInteger()
    var pictureRotation = -1
        private set
    private var parentPortrait: Boolean? = null
    private val orientationOwner = Any()
    private val sessionOrientation = SessionOrientation({ requestedOrientation }, { requestedOrientation = it })
    private var automaticOrientation = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        box = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        texture = TextureView(this).apply { surfaceTextureListener = this@SyntheticVideoProbeActivity }
        box.addView(texture, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
        root.addView(box, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        setContentView(root)
        window.decorView.post {
            checkNotNull(window.insetsController) { "Synthetic window insets controller unavailable after attachment" }
                .hide(WindowInsets.Type.systemBars())
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> resizeParent() }
        box.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fit() }
    }

    fun parentShape(portrait: Boolean?) {
        parentPortrait = portrait
        resizeParent()
    }

    fun presentRotation(degrees: Int) {
        pictureRotation = degrees
        sessionOrientation.followVideo(1080.0 / 1920, degrees)
        fit()
    }

    fun beginAutoOrientation() {
        sessionOrientation.begin(orientationOwner, ReceiverOrientation.AUTO)
        automaticOrientation = true
    }

    fun syntheticMetadata(): String {
        val transform = FloatArray(9)
        texture.getTransform(Matrix()).getValues(transform)
        val image = texture.surfaceTexture
        val imageTransform = if (image != null && updates.get() > 0) {
            // TextureView owns latching; read only metadata, never updateTexImage or pixels.
            FloatArray(16).also { image.getTransformMatrix(it) }.contentToString()
        } else "not-yet-latched"
        fun size(view: android.view.View) =
            "${view.width}x${view.height}/layout=${view.layoutParams.width}x${view.layoutParams.height}" +
                "/pending=${view.isLayoutRequested}"
        return "root=${size(root)} parent=${size(box)} view=${size(texture)}" +
            " bounds=[${texture.left},${texture.top},${texture.right},${texture.bottom}]" +
            " picture_rotation=$pictureRotation view_rotation=${texture.rotation}" +
            " orientation_auto=$automaticOrientation requested_orientation=$requestedOrientation" +
            " displayed_aspect=${if (pictureRotation % 180 == 0) 1080.0 / 1920 else 1920.0 / 1080}" +
            " pivot=[${texture.pivotX},${texture.pivotY}]" +
            " translation=[${texture.translationX},${texture.translationY}]" +
            " scale=[${texture.scaleX},${texture.scaleY}]" +
            " texture_transform=${transform.contentToString()} surface_texture_matrix=$imageTransform" +
            " surface_identity=${image?.let { System.identityHashCode(it) }}" +
            " updates=${updates.get()} configurations=${configurations.get()}"
    }

    private fun resizeParent() {
        if (root.width <= 0 || root.height <= 0) return
        val portrait = parentPortrait
        val ratio = if (portrait == null) root.width.toDouble() / root.height
            else if (portrait) 1080.0 / 1920 else 2283.0 / 1080
        val width = minOf(root.width, (root.height * ratio).toInt())
        val height = minOf(root.height, (root.width / ratio).toInt())
        if (box.layoutParams.width != width || box.layoutParams.height != height)
            box.layoutParams = FrameLayout.LayoutParams(width, height, Gravity.CENTER)
        fit()
    }

    private fun fit() {
        if (pictureRotation >= 0)
            VideoPresentation.fit(texture, box, 1080.0 / 1920, pictureRotation)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        configurations.incrementAndGet()
        root.post { resizeParent() }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        output = Surface(surface)
        fit()
    }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) { updates.incrementAndGet() }
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        output?.release()
        output = null
        return true
    }
    override fun onDestroy() {
        sessionOrientation.restore(orientationOwner)
        texture.surfaceTextureListener = null
        output?.release()
        output = null
        super.onDestroy()
    }
}

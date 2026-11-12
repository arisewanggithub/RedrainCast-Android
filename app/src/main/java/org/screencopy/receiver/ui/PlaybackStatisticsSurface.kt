package org.screencopy.receiver.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Paint
import android.graphics.PorterDuff
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceControl
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import dev.redrain.cast.R
import org.screencopy.receiver.media.PlaybackDisplayMeasurements
import org.screencopy.receiver.media.PlaybackStatisticsSnapshot
import java.util.concurrent.atomic.AtomicBoolean
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * All FLAG_SECURE removal by this window's owner must go through clearSecureFlag.
 * Register before the initially secure Window attaches. Producer retirement needs
 * a compositor commit acknowledgement, otherwise protection lasts until destruction.
 * This is an app ownership contract, not interception of arbitrary Window mutations.
 */
class StatisticsWindowProtection private constructor(window: Window) {
    private val windowReference = WeakReference(window)
    private val initiallySecure = !window.decorView.isAttachedToWindow &&
        window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
    private var declassified = false
    private var clearRequested = false
    private val pending = mutableSetOf<Any>()

    internal fun canHostStatistics(view: View): Boolean {
        requireMainThread()
        val window = windowReference.get() ?: return false
        return initiallySecure && !declassified && !clearRequested &&
            view.rootView === window.decorView &&
            window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
    }

    internal fun retainForStatistics(view: View): (() -> Unit)? {
        if (!canHostStatistics(view)) return null
        val token = Any()
        pending.add(token)
        return {
            requireMainThread()
            if (pending.remove(token) && pending.isEmpty() && clearRequested) clearSecureFlag()
        }
    }

    internal fun panelContext(anchor: View): Context {
        requireMainThread()
        val window = checkNotNull(windowReference.get()) { "Statistics host window was released" }
        check(anchor.isAttachedToWindow && anchor.rootView === window.decorView &&
            anchor.windowToken === window.decorView.windowToken) { "Statistics panel has no matching host window" }
        check(anchor.display != null && anchor.display?.displayId == window.decorView.display?.displayId) {
            "Statistics panel and host window must share a display"
        }
        return window.context
    }

    fun secureForReceiving() {
        requireMainThread()
        clearRequested = false
        // Cancelling a deferred clear cannot undo an actual declassification.
        windowReference.get()?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun clearSecureFlag(): Boolean {
        requireMainThread()
        if (pending.isNotEmpty()) {
            clearRequested = true
            return false
        }
        val window = windowReference.get()
        if (window == null) {
            android.util.Log.w("ScreenCopyReceiver", "Cannot change protection of a released statistics window")
            return false
        }
        declassified = true
        clearRequested = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        return true
    }

    companion object {
        private val owners = WeakHashMap<Window, StatisticsWindowProtection>()

        internal fun forWindow(window: Window): StatisticsWindowProtection {
            requireMainThread()
            return owners.getOrPut(window) { StatisticsWindowProtection(window) }
        }

        private fun requireMainThread() {
            check(Looper.myLooper() === Looper.getMainLooper()) { "Window protection belongs to the UI thread" }
        }
    }
}

/**
 * The table is detached from the activity's ViewRoot. Only this renderer's looper
 * measures and draws it; the video surface and main looper are not frame clocks.
 */
class PlaybackStatisticsSurface internal constructor(
    context: Context,
    private val detailed: Boolean,
    private val targets: StatisticsSurfaceTargets
) : FrameLayout(context), SurfaceHolder.Callback {
    constructor(context: Context, detailed: Boolean = false) :
        this(context, detailed, AndroidStatisticsSurfaceTargets())

    private val anchor = SurfaceView(context).apply {
        setZOrderOnTop(true)
        setSecure(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        holder.addCallback(this@PlaybackStatisticsSurface)
    }
    private val unavailable = TextView(context).apply {
        setText(R.string.rc_statistics_rendering_unavailable)
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.BLACK)
        visibility = View.GONE
    }
    internal val holder: SurfaceHolder get() = anchor.holder
    @Volatile private var source: (() -> PlaybackStatisticsSnapshot?)? = null
    @Volatile private var selected: (() -> Boolean)? = null
    private data class DisplayInput(val width: Int, val height: Int, val measurements: PlaybackDisplayMeasurements)
    @Volatile private var displayInput = DisplayInput(0, 0, PlaybackDisplayMeasurements())
    @Volatile private var surfaceSize = 0 to 0
    private var renderer: StatisticsPanelRenderer? = null
    private var target: StatisticsSurfaceTarget? = null
    private var rendererOwner: Any? = null
    private var surfaceReady = false
    private var reportedHeight = 0
    private var windowProtection: StatisticsWindowProtection? = null
    var onContentHeight: ((Int) -> Unit)? = null

    internal val refreshing: Boolean get() = renderer?.running == true

    init {
        tag = "playbackStatistics"
        addView(anchor, LayoutParams(-1, -1))
        addView(unavailable, LayoutParams(-1, -2))
        isClickable = false
        isFocusable = false
        visibility = View.GONE
    }

    override fun dispatchTouchEvent(event: MotionEvent?): Boolean = false

    fun bindWindow(protection: StatisticsWindowProtection) {
        retireRenderer()
        windowProtection = protection
        if (source != null) startRenderer()
    }

    fun start(selected: () -> Boolean = { true }, source: () -> PlaybackStatisticsSnapshot?) {
        this.selected = selected
        this.source = source
        visibility = View.VISIBLE
        if (!targets.supported) {
            android.util.Log.w("ScreenCopyReceiver",
                "Independent secure statistics rendering requires Android 12 or later")
            anchor.visibility = View.GONE
            unavailable.setText(R.string.rc_statistics_os_unsupported)
            unavailable.visibility = View.VISIBLE
            return
        }
        if (windowProtection == null) {
            unavailable.setText(R.string.rc_statistics_secure_window_required)
            unavailable.visibility = View.VISIBLE
            return
        }
        unavailable.visibility = View.GONE
        startRenderer()
    }

    fun measurements(width: Int, height: Int, value: PlaybackDisplayMeasurements) {
        displayInput = DisplayInput(width, height, value)
    }

    fun stop() {
        source = null
        selected = null
        retireRenderer()
        visibility = View.GONE
    }

    internal fun showHostUnavailable() {
        stop()
        unavailable.setText(R.string.rc_statistics_host_unavailable)
        unavailable.visibility = View.VISIBLE
        visibility = View.VISIBLE
    }

    private fun secureHost(): Boolean = windowProtection?.canHostStatistics(this) == true

    private fun startRenderer() {
        val (width, height) = surfaceSize
        if (!targets.supported || !surfaceReady || source == null ||
            width <= 0 || height <= 0) return
        if (!secureHost()) {
            retireRenderer()
            unavailable.setText(R.string.rc_statistics_secure_window_required)
            unavailable.visibility = View.VISIBLE
            android.util.Log.w("ScreenCopyReceiver", "Statistics require their actual secure host window")
            return
        }
        if (renderer != null) return
        val detachedCommitted = checkNotNull(windowProtection?.retainForStatistics(this))
        val owner = Any()
        rendererOwner = owner
        val output = try {
            targets.create(anchor, width, height, detachedCommitted)
        } catch (error: RuntimeException) {
            android.util.Log.e("ScreenCopyReceiver", "Statistics surface setup failed", error)
            rendererOwner = null
            unavailable.setText(R.string.rc_statistics_rendering_unavailable)
            unavailable.visibility = View.VISIBLE
            return
        }
        if (!secureHost()) {
            rendererOwner = null
            output.release()
            unavailable.setText(R.string.rc_statistics_secure_window_required)
            unavailable.visibility = View.VISIBLE
            return
        }
        unavailable.visibility = View.GONE
        var table: PlaybackStatisticsOverlay? = null
        lateinit var next: StatisticsPanelRenderer
        var constructed = false
        try {
            next = StatisticsPanelRenderer({ source?.invoke() },
            enabled = { this.selected?.invoke() == true },
            dispose = {
                try { output.release() } finally {
                    post {
                        if (renderer === next) {
                            renderer = null
                            target = null
                            rendererOwner = null
                            if (source != null && this.selected?.invoke() == true) {
                                unavailable.setText(R.string.rc_statistics_rendering_unavailable)
                                unavailable.visibility = View.VISIBLE
                            }
                        }
                    }
                }
            }) { snapshot ->
                val panel = table ?: PlaybackStatisticsOverlay(context, detailed).also {
                    it.layoutParams = android.widget.FrameLayout.LayoutParams(-1, -2)
                    table = it
                }
                val input = displayInput
                if (width > 0 && height > 0) {
                    panel.show(snapshot, input.width, input.height, width,
                        if (detailed) input.height else height, input.measurements)
                    panel.layoutParams = android.widget.FrameLayout.LayoutParams(
                        panel.layoutParams?.width?.takeIf { it > 0 } ?: width, -2)
                    panel.measure(View.MeasureSpec.makeMeasureSpec(panel.layoutParams.width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
                    val contentHeight = panel.measuredHeight
                    if (detailed) {
                        post {
                            if (rendererOwner === owner && contentHeight != reportedHeight) {
                                reportedHeight = contentHeight
                                onContentHeight?.invoke(contentHeight)
                            }
                        }
                    }
                    output.draw { canvas ->
                        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                        if (snapshot != null) panel.draw(canvas) else if (this.selected?.invoke() == true) {
                            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                color = Color.WHITE
                                textSize = 14 * resources.displayMetrics.scaledDensity
                            }
                            canvas.drawText("Statistics unavailable", 8f, paint.textSize + 8f, paint)
                        }
                    }
                }
            }
            constructed = true
        } finally {
            if (!constructed) {
                rendererOwner = null
                output.release()
            }
        }
        renderer = next
        target = output
        var launched = false
        try {
            output.show()
            next.start()
            launched = true
        } catch (error: RuntimeException) {
            android.util.Log.e("ScreenCopyReceiver", "Statistics renderer startup failed", error)
            unavailable.setText(R.string.rc_statistics_rendering_unavailable)
            unavailable.visibility = View.VISIBLE
        } finally {
            if (!launched) {
                next.close()
                renderer = null
                target = null
                rendererOwner = null
            }
        }
    }

    private fun retireRenderer() {
        rendererOwner = null
        val retired = renderer
        val output = target
        renderer = null
        target = null
        try { output?.detach() } finally { retired?.close() }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        surfaceSize = 0 to 0
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (surfaceSize != (width to height)) retireRenderer()
        surfaceSize = width to height
        startRenderer()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        retireRenderer()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

}

internal interface StatisticsSurfaceTargets {
    val supported: Boolean
    fun create(parent: SurfaceView, width: Int, height: Int,
        detachedCommitted: () -> Unit = {}): StatisticsSurfaceTarget
}

internal interface StatisticsSurfaceTarget {
    fun show()
    fun draw(content: (Canvas) -> Unit)
    fun detach()
    fun release()
}

internal class AndroidStatisticsSurfaceTargets(
    private val sdk: Int = Build.VERSION.SDK_INT
) : StatisticsSurfaceTargets {
    // Secure ancestors protect their children starting with Android 12.
    override val supported get() = sdk >= Build.VERSION_CODES.S

    override fun create(parent: SurfaceView, width: Int, height: Int,
        detachedCommitted: () -> Unit): StatisticsSurfaceTarget {
        check(supported) { "Secure child surfaces require Android 12 or later" }
        val window = parent.rootView.layoutParams as? WindowManager.LayoutParams
        check(window != null && window.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) {
            "Statistics child surfaces require a FLAG_SECURE host window"
        }
        val parentLayer = parent.surfaceControl
        check(parentLayer != null && parentLayer.isValid) { "Statistics anchor has no valid compositor layer" }
        val layer = SurfaceControl.Builder()
            .setName("Playback statistics")
            .setParent(parentLayer)
            .setBufferSize(width, height)
            .setFormat(PixelFormat.RGBA_8888)
            .setOpaque(false)
            .build()
        var surface: Surface? = null
        var constructed = false
        fun detachLayer() {
            SurfaceControl.Transaction().use {
                it.setVisibility(layer, false).reparent(layer, null)
                if (Build.VERSION.SDK_INT >= 33) {
                    it.addTransactionCommittedListener({ command ->
                        if (!Handler(Looper.getMainLooper()).post(command))
                            android.util.Log.e("ScreenCopyReceiver", "Statistics commit dispatch rejected; retaining window protection")
                    }, detachedCommitted)
                }
                it.apply()
            }
        }
        try {
            val producer = Surface(layer)
            surface = producer
            val result = object : StatisticsSurfaceTarget {
                private val layerLock = Any()
                private val releaseStarted = AtomicBoolean()
                private var released = false
                private var detached = false

                override fun show() = synchronized(layerLock) {
                    check(!released && !detached) { "Statistics target is retired" }
                    SurfaceControl.Transaction().use {
                        it.setLayer(layer, 1).setVisibility(layer, true).apply()
                    }
                }

                override fun draw(content: (Canvas) -> Unit) {
                    val canvas = producer.lockCanvas(null)
                    try { content(canvas) } finally { producer.unlockCanvasAndPost(canvas) }
                }

                override fun detach() = synchronized(layerLock) {
                    if (!released && !detached) {
                        detachLayer()
                        detached = true
                    }
                }

                override fun release() {
                    if (!releaseStarted.compareAndSet(false, true)) return
                    try { producer.release() } finally {
                        synchronized(layerLock) {
                            if (!released) {
                                try { detach() } finally {
                                    released = true
                                    layer.release()
                                }
                            }
                        }
                    }
                }
            }
            constructed = true
            return result
        } finally {
            if (!constructed) {
                try { surface?.release() } finally {
                    try {
                        detachLayer()
                    } finally { layer.release() }
                }
            }
        }
    }
}

internal class StatisticsPanelRenderer(
    private val snapshot: () -> PlaybackStatisticsSnapshot?,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val enabled: () -> Boolean = { true },
    private val dispose: () -> Unit = {},
    private val draw: (PlaybackStatisticsSnapshot?) -> Unit
) : AutoCloseable {
    private val thread = HandlerThread("Playback-statistics-renderer")
    private var handler: Handler? = null
    private val closed = AtomicBoolean()
    private val disposed = AtomicBoolean()
    internal val running get() = !closed.get()
    private val cleanup = Runnable {
        if (disposed.compareAndSet(false, true)) {
            try { dispose() } finally { thread.quitSafely() }
        }
    }
    internal val frame = object : Runnable {
        override fun run() {
            if (closed.get()) return
            try {
                if (!enabled()) {
                    draw(null)
                    close()
                    return
                }
                val value = snapshot()?.forDisplay(nowMs())
                if (!closed.get()) draw(value)
                if (!closed.get()) handler?.postDelayed(this, 1000)
            } catch (error: RuntimeException) {
                android.util.Log.e("ScreenCopyReceiver", "Statistics renderer failed", error)
                close()
            } finally {
                if (closed.get()) cleanup.run()
            }
        }
    }

    fun start() {
        check(!closed.get()) { "Statistics renderer is already closed" }
        var launched = false
        try {
            thread.start()
            val queue = Handler(thread.looper)
            handler = queue
            check(queue.post(frame)) { "Statistics renderer could not start" }
            launched = true
        } finally {
            if (!launched) {
                closed.set(true)
                cleanup.run()
            }
        }
    }

    internal val looper get() = thread.looper

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val queue = handler
        if (queue == null) {
            cleanup.run()
        } else {
            queue.removeCallbacksAndMessages(null)
            check(queue.post(cleanup) || disposed.get()) { "Statistics renderer could not schedule disposal" }
        }
    }
}

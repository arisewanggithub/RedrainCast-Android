package dev.screencopy.sender

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.view.Surface
import java.io.Closeable
import java.io.IOException

/** One consent token, one VirtualDisplay; size switches reuse it with a new Surface. */
class ProjectionOwner(
    val projection: MediaProjection,
    private val density: Int,
    private val playbackMute: PlaybackMute,
    private val stopped: (Exception) -> Unit
) : Closeable {
    private var display: VirtualDisplay? = null
    private var closed = false
    private var released = false
    private var resized: ((Int, Int) -> Unit)? = null
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            val current = synchronized(this@ProjectionOwner) {
                if (closed) return
                closed = true
                resized = null
                display.also { display = null }
            }
            val error = IOException("CAPTURE_FAILED: Android stopped screen sharing. Press Play and approve a new capture session.")
            try { current?.release() } catch (e: Exception) { error.addSuppressed(e) }
            try { playbackMute.close() } catch (e: Exception) { error.addSuppressed(e) }
            stopped(error)
        }
        override fun onCapturedContentResize(width: Int, height: Int) {
            val listener = synchronized(this@ProjectionOwner) { if (closed) null else resized }
            listener?.invoke(width, height)
        }
    }
    init { projection.registerCallback(callback, Handler(Looper.getMainLooper())) }

    @Synchronized fun attach(surface: Surface, width: Int, height: Int, resize: ((Int, Int) -> Unit)? = null) {
        if (closed) throw IOException("CAPTURE_FAILED: screen-sharing permission was revoked")
        resized = resize
        val current = display
        if (current == null) {
            display = projection.createVirtualDisplay("RedrainCast", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null)
                ?: throw IOException("CAPTURE_FAILED: Android could not create the capture display")
        } else {
            current.resize(width, height, density)
            current.surface = surface
        }
    }

    @Synchronized fun detach() {
        resized = null
        display?.surface = null
    }

    @Synchronized fun resize(width: Int, height: Int) {
        if (closed) throw IOException("CAPTURE_FAILED: screen-sharing permission was revoked")
        display?.resize(width, height, density)
    }

    @Synchronized fun mutePlayback(settings: Settings) {
        if (closed) throw IOException("CAPTURE_FAILED: screen-sharing permission was revoked")
        if (PlaybackMute.applies(settings)) playbackMute.mute()
    }

    override fun close() {
        val current = synchronized(this) {
            if (released) return
            released = true
            closed = true
            resized = null
            display.also { display = null }
        }
        var failure: Exception? = null
        fun cleanup(action: () -> Unit) {
            try { action() } catch (e: Exception) {
                if (failure == null) failure = e else failure?.addSuppressed(e)
            }
        }
        cleanup { current?.release() }
        cleanup { projection.unregisterCallback(callback) }
        cleanup { projection.stop() }
        cleanup { playbackMute.close() }
        failure?.let { throw IOException("Projection/audio cleanup failed", it) }
    }
}

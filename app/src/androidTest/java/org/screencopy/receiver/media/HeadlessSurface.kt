package org.screencopy.receiver.media

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Test-only EGL consumer; never creates a window, Activity, or captured screen. */
internal class HeadlessSurface : AutoCloseable {
    private val thread = HandlerThread("ScreenCopy-test-EGL").apply { start() }
    private val handler = Handler(thread.looper)
    private var display = EGL14.EGL_NO_DISPLAY
    private var context = EGL14.EGL_NO_CONTEXT
    private var pbuffer = EGL14.EGL_NO_SURFACE
    private val texture = IntArray(1)
    private var consumer: SurfaceTexture? = null
    private var output: Surface? = null
    private val drainFailure = AtomicReference<Throwable?>()
    val drainedFrames = AtomicInteger()
    val surface: Surface get() = checkNotNull(output)

    init {
        try {
            onOwner {
                display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                check(display != EGL14.EGL_NO_DISPLAY) { "No offscreen EGL display" }
                check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1)) { "EGL initialization failed" }
                val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
                val attributes = intArrayOf(
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_NONE)
                val count = IntArray(1)
                check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) {
                    "No offscreen EGL ES2 configuration"
                }
                context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
                check(context != EGL14.EGL_NO_CONTEXT) { "Offscreen EGL context unavailable" }
                pbuffer = EGL14.eglCreatePbufferSurface(display, configs[0],
                    intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
                check(pbuffer != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) {
                    "Offscreen EGL pbuffer unavailable"
                }
                GLES20.glGenTextures(1, texture, 0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture[0])
                for (parameter in intArrayOf(GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_TEXTURE_MAG_FILTER))
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, parameter, GLES20.GL_LINEAR)
                for (parameter in intArrayOf(GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_TEXTURE_WRAP_T))
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, parameter, GLES20.GL_CLAMP_TO_EDGE)
                check(texture[0] != 0 && GLES20.glGetError() == GLES20.GL_NO_ERROR) { "External EGL texture unavailable" }
                consumer = SurfaceTexture(texture[0]).apply {
                    setDefaultBufferSize(320, 240)
                    setOnFrameAvailableListener({ available ->
                        try {
                            available.updateTexImage()
                            drainedFrames.incrementAndGet()
                        } catch (failure: RuntimeException) { drainFailure.compareAndSet(null, failure) }
                    }, handler)
                }
                output = Surface(checkNotNull(consumer))
            }
        } catch (failure: Throwable) {
            try { close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    fun checkHealthy() {
        drainFailure.get()?.let { throw IllegalStateException("Offscreen SurfaceTexture drain failed", it) }
    }

    private fun onOwner(action: () -> Unit) {
        val done = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        check(handler.post {
            try { action() } catch (failure: Throwable) { error.set(failure) } finally { done.countDown() }
        }) { "Offscreen EGL thread stopped" }
        check(done.await(10, TimeUnit.SECONDS)) { "Offscreen EGL operation timed out" }
        error.get()?.let { throw IllegalStateException("Offscreen EGL operation failed", it) }
    }

    override fun close() {
        try {
            onOwner {
                output?.release()
                output = null
                consumer?.setOnFrameAvailableListener(null)
                consumer?.release()
                consumer = null
                if (texture[0] != 0) GLES20.glDeleteTextures(1, texture, 0)
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                    EGL14.eglReleaseThread()
                    EGL14.eglTerminate(display)
                }
            }
        } finally {
            thread.quitSafely()
            thread.join(5000)
            check(!thread.isAlive) { "Offscreen EGL thread did not terminate" }
        }
    }
}

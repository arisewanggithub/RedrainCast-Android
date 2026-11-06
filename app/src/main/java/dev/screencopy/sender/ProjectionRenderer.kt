package dev.screencopy.sender

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** GPU-only normalization into an authoritative fixed-natural encoder raster. */
internal class ProjectionRenderer(
    private val context: Context, private val output: Surface, private val size: VideoSize,
    private val settings: Settings,
    initialSource: CaptureDisplay = ProjectionInventory.displays(context).displays.single(),
    private val onFrame: (Long, Int) -> Unit = { _, _ -> },
    private val fail: (Exception) -> Unit
) : Closeable {
    private val thread = HandlerThread("projection-gl").apply { start() }
    private val handler = Handler(thread.looper)
    private val closed = AtomicBoolean()
    private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var window = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var texture = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var input: Surface? = null
    private var source = initialSource
    private var lastFrame = 0L
    private val matrix = FloatArray(16)
    private fun floats(values: FloatArray) = ByteBuffer.allocateDirect(values.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }
    private val vertices = floats(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))

    fun start(): Surface {
        val done = CountDownLatch(1)
        var failure: Exception? = null
        handler.post {
            try { initialize() } catch (e: Exception) { failure = e }
            finally { done.countDown() }
        }
        if (!done.await(10, TimeUnit.SECONDS)) throw IOException("CAPTURE_FAILED: GPU capture initialization timed out")
        failure?.let { throw IOException("CAPTURE_FAILED: GPU capture initialization failed", it) }
        return checkNotNull(input)
    }

    private fun initialize() {
        check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1)) { "EGL initialization failed" }
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            0x3142, 1, EGL14.EGL_NONE), 0, configs, 0, 1, count, 0) && count[0] > 0) { "Recordable EGL config unavailable" }
        eglContext = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        window = EGL14.eglCreateWindowSurface(display, configs[0], output, intArrayOf(EGL14.EGL_NONE), 0)
        check(eglContext != EGL14.EGL_NO_CONTEXT && window != EGL14.EGL_NO_SURFACE &&
            EGL14.eglMakeCurrent(display, window, window, eglContext)) { "Encoder EGL Surface unavailable" }
        val vertex = shader(GLES20.GL_VERTEX_SHADER,
            "attribute vec4 p; attribute vec4 uv; uniform mat4 m; varying vec2 t; void main(){gl_Position=p;t=(m*uv).xy;}")
        val fragment = shader(GLES20.GL_FRAGMENT_SHADER,
            "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 t; uniform samplerExternalOES s; void main(){gl_FragColor=texture2D(s,t);}")
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex); GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        check(linked[0] != 0) { "GPU capture program link failed" }
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0); texture = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        surfaceTexture = SurfaceTexture(texture).apply {
            setDefaultBufferSize(source.width, source.height)
            setOnFrameAvailableListener({ if (!closed.get()) try { draw() } catch (e: Exception) {
                if (closed.compareAndSet(false, true)) fail(IOException("CAPTURE_FAILED: GPU capture failed", e))
            } }, handler)
        }
        input = Surface(checkNotNull(surfaceTexture))
    }

    fun resize(updated: CaptureDisplay) {
        handler.post {
            if (!closed.get()) try {
                source = updated
                DisplayInventory(listOf(source)).validate(settings)
                surfaceTexture?.setDefaultBufferSize(source.width, source.height)
            } catch (e: Exception) { fail(IOException("CAPTURE_FAILED: capture resize failed", e)) }
        }
    }

    private fun draw() {
        val image = checkNotNull(surfaceTexture)
        image.updateTexImage()
        val time = image.timestamp
        if (time <= 0 || lastFrame > 0 && time - lastFrame < 1_000_000_000L / settings.maxFps) return
        lastFrame = time
        image.getTransformMatrix(matrix)
        val plan = ProjectionTransform.coordinates(settings, source)
        val coordinates = floats(plan.first)
        val ratio = plan.second
        val outRatio = size.width.toFloat() / size.height
        val width = if (ratio < outRatio) (size.height * ratio).toInt() else size.width
        val height = if (ratio > outRatio) (size.width / ratio).toInt() else size.height
        GLES20.glClearColor(0f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glViewport((size.width - width) / 2, (size.height - height) / 2, width, height)
        GLES20.glUseProgram(program)
        val position = GLES20.glGetAttribLocation(program, "p")
        val uv = GLES20.glGetAttribLocation(program, "uv")
        GLES20.glEnableVertexAttribArray(position); GLES20.glEnableVertexAttribArray(uv)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 0, coordinates)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "m"), 1, false, matrix, 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "s"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "GPU draw failed" }
        onFrame(time / 1000, VideoRotation.degrees(settings, source))
        check(EGLExt.eglPresentationTimeANDROID(display, window, time) && EGL14.eglSwapBuffers(display, window)) { "GPU frame delivery failed" }
    }

    private fun shader(type: Int, code: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, code); GLES20.glCompileShader(shader)
        val status = IntArray(1); GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) { GLES20.glDeleteShader(shader); throw IOException("CAPTURE_FAILED: GPU shader unavailable") }
        return shader
    }

    override fun close() {
        closed.set(true)
        val done = CountDownLatch(1)
        handler.post {
            try {
                input?.release(); input = null
                surfaceTexture?.release(); surfaceTexture = null
                if (program != 0) GLES20.glDeleteProgram(program)
                if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
                EGL14.eglReleaseThread(); EGL14.eglTerminate(display)
            } finally { done.countDown(); thread.quitSafely() }
        }
        if (!done.await(5, TimeUnit.SECONDS)) throw IOException("Capture GPU cleanup timed out")
        thread.join(1000)
        if (thread.isAlive) throw IOException("Capture GPU worker did not stop")
    }
}

internal object ProjectionTransform {
    fun coordinates(settings: Settings, display: CaptureDisplay): Pair<FloatArray, Float> {
        val rotation = checkNotNull(display.rotation)
        val naturalWidth = display.naturalWidth.toFloat()
        val naturalHeight = display.naturalHeight.toFloat()
        val cropped = settings.cropWidth > 0
        val x = if (cropped) settings.cropX / naturalWidth else 0f
        val y = if (cropped) settings.cropY / naturalHeight else 0f
        val w = if (cropped) settings.cropWidth / naturalWidth else 1f
        val h = if (cropped) settings.cropHeight / naturalHeight else 1f
        fun rotate(u: Float, v: Float): Pair<Float, Float> = when (rotation) {
            1 -> v to (1f - u)
            2 -> (1f - u) to (1f - v)
            3 -> (1f - v) to u
            else -> u to v
        }
        // Natural top-left, top-right, bottom-right, bottom-left mapped to the current display.
        val corners = listOf(rotate(x, y), rotate(x + w, y), rotate(x + w, y + h), rotate(x, y + h))
        val order = listOf(3, 2, 0, 1)
        val uv = order.flatMap { index -> listOf(corners[index].first, 1f - corners[index].second) }.toFloatArray()
        val baseWidth = if (cropped) settings.cropWidth else display.naturalWidth
        val baseHeight = if (cropped) settings.cropHeight else display.naturalHeight
        val ratio = baseWidth.toFloat() / baseHeight
        return uv to ratio
    }
}

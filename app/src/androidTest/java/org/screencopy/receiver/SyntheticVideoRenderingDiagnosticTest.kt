package org.screencopy.receiver

import android.app.KeyguardManager
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.PixelCopy
import android.view.Surface
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.screencopy.sender.CaptureDisplay
import dev.screencopy.sender.ProjectionRenderer
import dev.screencopy.sender.Settings
import dev.screencopy.sender.VideoSize
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.screencopy.receiver.media.PlaybackClock
import org.screencopy.receiver.media.AndroidVideoDecoder
import org.screencopy.receiver.media.VideoPlayer

/**
 * Manual, idle-device diagnostics. Fixtures contain only four synthetic colors.
 * Never launch this runner while the user is casting. PixelCopy reads this isolated window only.
 */
@RunWith(AndroidJUnit4::class)
class SyntheticVideoRenderingDiagnosticTest {
    private val main = Handler(Looper.getMainLooper())
    private val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
    // Independent clockwise quadrant permutation, not production geometry/rotation code.
    private val permutations = arrayOf(intArrayOf(0, 1, 2, 3), intArrayOf(2, 0, 3, 1),
        intArrayOf(3, 2, 1, 0), intArrayOf(1, 3, 0, 2))

    @Test fun firstHevcQuarterTurnRacesAutoWindowResizeAcrossFreshStarts() {
        requireUnlocked()
        var previousTexture: SurfaceTexture? = null
        for (firstRotation in listOf(90, 270)) repeat(3) { trial ->
            ActivityScenario.launch(SyntheticVideoProbeActivity::class.java).use { scenario ->
                val texture = awaitSurface(scenario)
                assertNotSame("Trial reused a previous SurfaceTexture", previousTexture, texture)
                previousTexture = texture
                orientWindow(scenario, true)
                scenario.onActivity { it.beginAutoOrientation(); it.parentShape(true) }
                val label = "receiver-first trial=$trial first_rotation=$firstRotation"
                Pipeline(scenario, label, firstRotation).use { pipeline ->
                    SyntheticCornerGpu(pipeline.encoder.input).use { gpu ->
                        fun draw(degrees: Int, drain: Boolean) {
                            pipeline.checkHealthy()
                            val pts = System.nanoTime() / 1000
                            pipeline.encoder.rotations[pts] = degrees
                            gpu.draw(pts)
                            if (drain) pipeline.encoder.drain()
                        }
                        pipeline.trace(scenario, "startup-before-pictures")
                        repeat(2) { draw(0, false) }
                        await("first natural-aspect picture policy") {
                            pipeline.encoder.drain()
                            pipeline.checkHealthy()
                            activityValue(scenario) { it.pictureRotation == 0 }
                        }
                        val before = pipeline.presented.get()
                        val updates = activityInt(scenario) { it.updates.get() }
                        // Undrained natural pictures remain in flight when the first quarter turn arrives.
                        repeat(2) { draw(0, false) }
                        pipeline.trace(scenario, "before-first-rotation natural_burst_inflight=2")
                        repeat(2) { draw(firstRotation, false) }
                        pipeline.trace(scenario, "during-first-rotation rotated_burst_inflight=2")
                        repeat(6) { draw(firstRotation, true) }
                        awaitPresented(scenario, pipeline, texture, firstRotation, before, updates)
                        awaitStableWindow(scenario, false)
                        pipeline.trace(scenario, "after-first-rotation-stable")
                        assertTrue("First rotated picture did not overlap a pending layout",
                            pipeline.firstRotationPendingLayout.get())
                        checkPixels(scenario, firstRotation, label)

                        val returnBefore = pipeline.presented.get()
                        val returnUpdates = activityInt(scenario) { it.updates.get() }
                        repeat(2) { draw(firstRotation, false) }
                        repeat(8) { draw(0, true) }
                        awaitPresented(scenario, pipeline, texture, 0, returnBefore, returnUpdates)
                        awaitStableWindow(scenario, true)
                        pipeline.trace(scenario, "after-return-stable")
                        checkPixels(scenario, 0, "$label return")
                    }
                }
            }
        }
    }

    @Test fun firstProjectionResizeRacesAutoWindowWithIndependentCropAcrossFreshStarts() {
        requireUnlocked()
        var previousTexture: SurfaceTexture? = null
        for (cropped in listOf(false, true)) repeat(3) { trial ->
            ActivityScenario.launch(SyntheticVideoProbeActivity::class.java).use { scenario ->
                val texture = awaitSurface(scenario)
                assertNotSame("Sender trial reused a previous SurfaceTexture", previousTexture, texture)
                previousTexture = texture
                orientWindow(scenario, true)
                scenario.onActivity { it.beginAutoOrientation(); it.parentShape(true) }
                val label = "sender-first trial=$trial crop=${if (cropped) "1080x1920@0,228" else "none"}"
                Pipeline(scenario, label, 270).use { pipeline ->
                    val settings = Settings().apply {
                        videoCodec = "h265"
                        audio = false
                        maxFps = 30
                        if (cropped) {
                            cropWidth = 1080; cropHeight = 1920; cropX = 0; cropY = 228
                        }
                    }
                    val initial = CaptureDisplay(0, "Synthetic", 1080, 2340, 0, 1)
                    val context = InstrumentationRegistry.getInstrumentation().targetContext
                    ProjectionRenderer(context, pipeline.encoder.input, VideoSize(1080, 1920), settings,
                        initialSource = initial,
                        onFrame = { pts, rotation -> pipeline.encoder.rotations[pts] = rotation },
                        fail = { pipeline.failures.add("ProjectionRenderer: $it") }).use { renderer ->
                        val input = renderer.start()
                        pipeline.trace(scenario, "startup-before-source-pictures")
                        drawAospFixture(input, 0, cropped)
                        await("initial synthetic ProjectionRenderer picture policy") {
                            pipeline.checkHealthy()
                            pipeline.encoder.drain()
                            activityValue(scenario) { it.pictureRotation == 0 }
                        }
                        val before = pipeline.presented.get()
                        val updates = activityInt(scenario) { it.updates.get() }
                        drawAospFixture(input, 0, cropped)
                        pipeline.trace(scenario, "before-first-source-resize natural_picture_inflight=1")
                        renderer.resize(CaptureDisplay(0, "Synthetic", 2340, 1080, 1, 1))
                        var correctBuffers = 0
                        // Produce immediately, including stale-sized buffers; no resize/layout readiness barrier.
                        await("first source rotation and continuing correct-sized buffers") {
                            pipeline.checkHealthy()
                            if (drawAospFixture(input, 1, cropped)) correctBuffers++ else correctBuffers = 0
                            pipeline.encoder.drain()
                            correctBuffers >= 10 && pipeline.presented.get() >= before + 3
                        }
                        awaitPresented(scenario, pipeline, texture, 270, before, updates)
                        awaitStableWindow(scenario, false)
                        pipeline.trace(scenario, "after-first-source-resize-stable correct_buffers=$correctBuffers")
                        assertTrue("First normalized picture did not overlap a pending receiver layout",
                            pipeline.firstRotationPendingLayout.get())
                        val aspect = if (cropped) 1080.0 / 1920 else 1080.0 / 2340
                        checkPixels(scenario, 270, "$label display_rotation=1", aspect)

                        val returnBefore = pipeline.presented.get()
                        val returnUpdates = activityInt(scenario) { it.updates.get() }
                        renderer.resize(initial)
                        correctBuffers = 0
                        await("source return with continuing correct-sized buffers") {
                            pipeline.checkHealthy()
                            if (drawAospFixture(input, 0, cropped)) correctBuffers++ else correctBuffers = 0
                            pipeline.encoder.drain()
                            correctBuffers >= 10 && pipeline.presented.get() >= returnBefore + 3
                        }
                        awaitPresented(scenario, pipeline, texture, 0, returnBefore, returnUpdates)
                        awaitStableWindow(scenario, true)
                        pipeline.trace(scenario, "after-source-return-stable")
                        checkPixels(scenario, 0, "$label return", aspect)
                    }
                }
            }
        }
    }

    @Test fun hardwareHevcPicturesFillProductionTextureAcrossRotationsAndLiveResizes() {
        requireUnlocked()
        ActivityScenario.launch(SyntheticVideoProbeActivity::class.java).use { scenario ->
            val texture = awaitSurface(scenario)
            Pipeline(scenario).use { pipeline ->
                SyntheticCornerGpu(pipeline.encoder.input).use { gpu ->
                    for (windowPortrait in listOf(true, false, true)) {
                        orientWindow(scenario, windowPortrait)
                        for (parentPortrait in listOf(true, false)) {
                            scenario.onActivity { it.parentShape(parentPortrait) }
                            await("parent shape") {
                                activityValue(scenario) {
                                    if (parentPortrait) it.box.height > it.box.width else it.box.width > it.box.height
                                }
                            }
                            for (degrees in listOf(0, 90, 180, 270)) {
                                val before = pipeline.presented.get()
                                val updates = activityInt(scenario) { it.updates.get() }
                                repeat(8) {
                                    pipeline.checkHealthy()
                                    val pts = System.nanoTime() / 1000
                                    pipeline.encoder.rotations[pts] = degrees
                                    gpu.draw(pts)
                                    pipeline.encoder.drain()
                                }
                                awaitPresented(scenario, pipeline, texture, degrees, before, updates)
                                checkPixels(scenario, degrees,
                                    "receiver window=${if (windowPortrait) "portrait" else "landscape"}" +
                                        " parent=${if (parentPortrait) "portrait" else "landscape"}")
                            }
                        }
                    }
                    assertTrue("No real window configuration changes observed",
                        activityInt(scenario) { it.configurations.get() } >= 2)
                }
            }
        }
    }

    @Test fun productionProjectionGpuNormalizesIndependentAospCornersAndOffCenterCrop() {
        requireUnlocked()
        ActivityScenario.launch(SyntheticVideoProbeActivity::class.java).use { scenario ->
            val texture = awaitSurface(scenario)
            orientWindow(scenario, false)
            for (cropped in listOf(false, true)) {
                Pipeline(scenario).use { pipeline ->
                    val settings = Settings().apply {
                        videoCodec = "h265"
                        audio = false
                        maxFps = 30
                        if (cropped) {
                            cropWidth = 1080
                            cropHeight = 1920
                            cropX = 0
                            cropY = 228
                        }
                    }
                    val context = InstrumentationRegistry.getInstrumentation().targetContext
                    val initial = CaptureDisplay(0, "Synthetic", 1080, 2340, 0, 1)
                    val frames = AtomicInteger()
                    ProjectionRenderer(context, pipeline.encoder.input, VideoSize(1080, 1920), settings,
                        initialSource = initial,
                        onFrame = { pts, rotation ->
                            pipeline.encoder.rotations[pts] = rotation
                            frames.incrementAndGet()
                        },
                        fail = { pipeline.failures.add("ProjectionRenderer: $it") }).use { renderer ->
                        val input = renderer.start()
                        for (sourceRotation in listOf(0, 1, 0)) {
                            val source = if (sourceRotation == 1)
                                CaptureDisplay(0, "Synthetic", 2340, 1080, 1, 1) else initial
                            renderer.resize(source)
                            await("synthetic producer buffer resize") {
                                pipeline.checkHealthy()
                                val resized = drawAospFixture(input, sourceRotation, cropped)
                                pipeline.encoder.drain()
                                resized
                            }
                            val rotation = if (sourceRotation == 1) 270 else 0
                            val before = pipeline.presented.get()
                            val updates = activityInt(scenario) { it.updates.get() }
                            repeat(10) {
                                pipeline.checkHealthy()
                                val previous = frames.get()
                                assertTrue("Synthetic producer changed size unexpectedly",
                                    drawAospFixture(input, sourceRotation, cropped))
                                await("synthetic ProjectionRenderer frame") {
                                    pipeline.checkHealthy()
                                    frames.get() > previous
                                }
                                pipeline.encoder.drain()
                            }
                            awaitPresented(scenario, pipeline, texture, rotation, before, updates)
                            // Without crop, 1080x2340 fits inside the fixed 1080x1920 raster.
                            val contentAspect = if (cropped) 1080.0 / 1920 else 1080.0 / 2340
                            checkPixels(scenario, rotation,
                                "sender production-ProjectionTransform source=${source.width}x${source.height}" +
                                    " displayRotation=$sourceRotation crop=${if (cropped) "1080x1920@0,228" else "none"}",
                                contentAspect)
                        }
                    }
                }
            }
        }
    }

    private inner class Pipeline(scenario: ActivityScenario<SyntheticVideoProbeActivity>,
        private val traceLabel: String? = null, private val firstRotation: Int? = null) : AutoCloseable {
        val failures = ConcurrentLinkedQueue<String>()
        val presented = AtomicInteger()
        val firstRotationPendingLayout = AtomicBoolean()
        private val firstTransition = AtomicBoolean()
        private val queued = AtomicInteger()
        private val decoderMetadata = AtomicReference("not-yet-output")
        private val encoderMetadata = AtomicReference("not-yet-configured")
        private var player: VideoPlayer? = null
        val encoder = SyntheticHevcEncoder(
            configured = { format ->
                var output: Surface? = null
                scenario.onActivity { output = it.output }
                val decoder = SyntheticDecoderMetadata(
                    AndroidVideoDecoder(format, checkNotNull(output)) { presented.incrementAndGet() },
                    { decoderMetadata.set(it) })
                Log.i(TAG, "codec=${decoder.description}")
                player = VideoPlayer(format, PlaybackClock(),
                    { code, detail -> failures.add("$code: $detail") }, decoder,
                    onPresentation = { Log.i(TAG, "SYNTHETIC_VIDEO_PRESENTATION ${it.logMessage()}") },
                    onRotation = { degrees ->
                        main.post {
                            scenario.onActivity {
                                if (degrees == firstRotation && firstTransition.compareAndSet(false, true)) {
                                    traceActivity(it, "during-first-rotation-before-auto-fit")
                                    it.parentShape(false)
                                    // Actual AUTO policy requests landscape and fit runs in this same UI turn.
                                    it.presentRotation(degrees)
                                    firstRotationPendingLayout.set(it.root.isLayoutRequested ||
                                        it.box.isLayoutRequested || it.texture.isLayoutRequested)
                                    traceActivity(it, "during-first-rotation-after-auto-fit")
                                } else {
                                    if (degrees == 0 && firstTransition.get()) it.parentShape(true)
                                    it.presentRotation(degrees)
                                    if (traceLabel != null) traceActivity(it, "rotation-policy-applied")
                                }
                            }
                        }
                    }).also { it.start() }
            },
            packet = { checkHealthy(); checkNotNull(player).offer(it); queued.incrementAndGet() },
            outputMetadata = { encoderMetadata.set(it); Log.i(TAG, "SYNTHETIC_ENCODER_OUTPUT $it") }
        ).also { Log.i(TAG, "synthetic_hardware_encoder=${it.description} raster=1080x1920") }

        fun trace(scenario: ActivityScenario<SyntheticVideoProbeActivity>, phase: String) {
            scenario.onActivity { traceActivity(it, phase) }
        }

        private fun traceActivity(activity: SyntheticVideoProbeActivity, phase: String) {
            Log.i(TAG, "SYNTHETIC_METADATA probe=$traceLabel phase=$phase" +
                " queued=${queued.get()} presented=${presented.get()}" +
                " encoder_output=[${encoderMetadata.get()}] decoder_output=[${decoderMetadata.get()}]" +
                " ${activity.syntheticMetadata()}")
        }

        fun checkHealthy() { assertTrue("Synthetic pipeline failures: $failures", failures.isEmpty()) }

        override fun close() {
            try { player?.close() } finally { encoder.close() }
            checkHealthy()
        }
    }

    private fun requireUnlocked() {
        assertEquals("Manual diagnostic only: enable after fresh identity + idle-cast checks",
            "true", InstrumentationRegistry.getArguments().getString("syntheticVideoProbe"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("Manual safety gate: receiver must be unlocked and interactive",
            context.getSystemService(PowerManager::class.java).isInteractive &&
                !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
    }

    private fun awaitSurface(scenario: ActivityScenario<SyntheticVideoProbeActivity>): SurfaceTexture {
        await("synthetic TextureView surface") {
            activityValue(scenario) { it.output != null && it.box.width > 0 && it.box.height > 0 }
        }
        var texture: SurfaceTexture? = null
        scenario.onActivity {
            texture = it.texture.surfaceTexture
            assertTrue("GPU window required", it.window.decorView.isHardwareAccelerated)
            assertEquals("Only the isolated synthetic probe may be copied", 0,
                it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        }
        return checkNotNull(texture)
    }

    private fun orientWindow(scenario: ActivityScenario<SyntheticVideoProbeActivity>, portrait: Boolean) {
        scenario.onActivity {
            it.requestedOrientation = if (portrait) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        await("synthetic window orientation") {
            activityValue(scenario) { if (portrait) it.root.height > it.root.width else it.root.width > it.root.height }
        }
    }

    private fun awaitStableWindow(scenario: ActivityScenario<SyntheticVideoProbeActivity>, portrait: Boolean) {
        var previous = ""
        var stableSince = System.nanoTime()
        await("stable final ${if (portrait) "portrait" else "landscape"} window") {
            var size = ""
            val ready = activityValue(scenario) {
                size = "${it.root.width}x${it.root.height}/${it.box.width}x${it.box.height}" +
                    "/${it.texture.width}x${it.texture.height}"
                (if (portrait) it.root.height > it.root.width else it.root.width > it.root.height) &&
                    !it.root.isLayoutRequested && !it.box.isLayoutRequested && !it.texture.isLayoutRequested
            }
            if (!ready || size != previous) {
                previous = size
                stableSince = System.nanoTime()
                false
            } else System.nanoTime() - stableSince >= 100_000_000L
        }
    }

    private fun awaitPresented(scenario: ActivityScenario<SyntheticVideoProbeActivity>, pipeline: Pipeline,
        original: SurfaceTexture, degrees: Int, before: Int, updates: Int) {
        await("decoded pictures and fitted layout for rotation $degrees") {
            pipeline.checkHealthy()
            pipeline.encoder.drain()
            pipeline.presented.get() >= before + 3 && activityValue(scenario) {
                assertSame("Resize replaced the decoder SurfaceTexture", original, it.texture.surfaceTexture)
                it.pictureRotation == degrees && it.updates.get() >= updates + 3 &&
                    it.texture.width == it.texture.layoutParams.width &&
                    it.texture.height == it.texture.layoutParams.height
            }
        }
        // PixelCopy is a window-composition check, after layout and at least three presented frames.
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private data class Copy(val bitmap: Bitmap, val box: Rect)

    private fun copySyntheticWindow(scenario: ActivityScenario<SyntheticVideoProbeActivity>): Copy {
        val done = CountDownLatch(1)
        val result = AtomicInteger(-1)
        var copy: Copy? = null
        scenario.onActivity {
            assertEquals(0, it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
            val position = IntArray(2)
            it.box.getLocationInWindow(position)
            val bitmap = Bitmap.createBitmap(it.window.decorView.width, it.window.decorView.height, Bitmap.Config.ARGB_8888)
            copy = Copy(bitmap, Rect(position[0], position[1], position[0] + it.box.width, position[1] + it.box.height))
            PixelCopy.request(it.window, bitmap, { status -> result.set(status); done.countDown() }, main)
        }
        val captured = checkNotNull(copy)
        if (!done.await(5, TimeUnit.SECONDS) || result.get() != PixelCopy.SUCCESS) {
            captured.bitmap.recycle()
            fail("Synthetic-window PixelCopy failed: status=${result.get()}")
        }
        return captured
    }

    private fun checkPixels(scenario: ActivityScenario<SyntheticVideoProbeActivity>, rotation: Int,
        label: String, contentAspect: Double = 1080.0 / 1920) {
        val copy = copySyntheticWindow(scenario)
        try {
            val box = copy.box
            // Independent uniform fit: do not use VideoPresentation or ProjectionTransform as the oracle.
            val shownWidth = if (rotation % 180 == 0) 1080.0 else 1920.0
            val shownHeight = if (rotation % 180 == 0) 1920.0 else 1080.0
            val scale = min(box.width() / shownWidth, box.height() / shownHeight)
            val rasterWidth = shownWidth * scale
            val rasterHeight = shownHeight * scale
            val naturalContentWidth = min(1080.0, 1920 * contentAspect)
            val naturalContentHeight = min(1920.0, 1080 / contentAspect)
            val contentWidth = (if (rotation % 180 == 0) naturalContentWidth else naturalContentHeight) * scale
            val contentHeight = (if (rotation % 180 == 0) naturalContentHeight else naturalContentWidth) * scale
            val left = box.exactCenterX() - contentWidth / 2
            val top = box.exactCenterY() - contentHeight / 2
            val right = left + contentWidth
            val bottom = top + contentHeight
            val centerX = (left + right) / 2
            val centerY = (top + bottom) / 2
            val stride = max(4, min(contentWidth, contentHeight).toInt() / 100)
            val margin = max(5, stride)
            val total = IntArray(4)
            val matched = IntArray(4)
            var outside = 0
            var blackOutside = 0
            var coloredInside = 0
            var minimumX = Int.MAX_VALUE
            var minimumY = Int.MAX_VALUE
            var maximumX = -1
            var maximumY = -1
            for (y in box.top + margin until box.bottom - margin step stride) {
                for (x in box.left + margin until box.right - margin step stride) {
                    val pixel = copy.bitmap.getPixel(x, y)
                    val inside = x > left + margin && x < right - margin && y > top + margin && y < bottom - margin
                    if (inside && abs(x - centerX) > margin && abs(y - centerY) > margin) {
                        val quadrant = (if (y < centerY) 0 else 2) + if (x < centerX) 0 else 1
                        total[quadrant]++
                        if (near(pixel, colors[permutations[rotation / 90][quadrant]])) matched[quadrant]++
                        if (colors.any { near(pixel, it) }) {
                            coloredInside++
                            minimumX = min(minimumX, x); minimumY = min(minimumY, y)
                            maximumX = max(maximumX, x); maximumY = max(maximumY, y)
                        }
                    } else if (x < left - margin || x > right + margin || y < top - margin || y > bottom + margin) {
                        outside++
                        if (max(Color.red(pixel), max(Color.green(pixel), Color.blue(pixel))) <= 32) blackOutside++
                    }
                }
            }
            val ratios = (0..3).map { if (total[it] == 0) 0.0 else matched[it].toDouble() / total[it] }
            val occupancy = if (total.sum() == 0) 0.0 else coloredInside.toDouble() / total.sum()
            val letterbox = if (outside == 0) 1.0 else blackOutside.toDouble() / outside
            val metrics = String.format(Locale.ROOT,
                "%s rotation=%d box=%s raster=%.1fx%.1f expected_content=[%.1f,%.1f,%.1f,%.1f]" +
                    " colored_bounds=[%d,%d,%d,%d] quadrant_samples=%s quadrant_match=%s occupancy=%.5f" +
                    " outside_samples=%d black_outside=%.5f",
                label, rotation, box.toShortString(), rasterWidth, rasterHeight, left, top, right, bottom,
                minimumX, minimumY, maximumX, maximumY, total.contentToString(), ratios, occupancy, outside, letterbox)
            Log.i(TAG, "PIXEL_METRICS $metrics")
            for (q in 0..3) {
                assertTrue("Not enough quadrant samples: $metrics", total[q] >= 100)
                assertTrue("Wrong rotation or missing quadrant $q: $metrics", ratios[q] >= 0.98)
            }
            assertTrue("Tiny strip / incomplete fitted content: $metrics", occupancy >= 0.98)
            assertTrue("Nonblack fitted letterbox: $metrics", letterbox >= 0.99)
            val tolerance = margin + stride * 2
            assertTrue("Colored content does not span fitted rectangle: $metrics",
                minimumX <= left + tolerance && maximumX >= right - tolerance &&
                    minimumY <= top + tolerance && maximumY >= bottom - tolerance)
        } finally { copy.bitmap.recycle() }
    }

    private fun near(actual: Int, expected: Int): Boolean =
        abs(Color.red(actual) - Color.red(expected)) <= 55 &&
            abs(Color.green(actual) - Color.green(expected)) <= 55 &&
            abs(Color.blue(actual) - Color.blue(expected)) <= 55

    private fun drawAospFixture(surface: Surface, rotation: Int, cropped: Boolean): Boolean {
        val canvas = surface.lockCanvas(null)
        try {
            val width = if (rotation == 1) 2340 else 1080
            val height = if (rotation == 1) 1080 else 2340
            // Resize posts to the GL worker; retry stale-sized producer buffers within a bounded wait.
            if (canvas.width != width || canvas.height != height) {
                Log.i(TAG, "producer_resize_pending actual=${canvas.width}x${canvas.height} expected=${width}x$height")
                canvas.drawColor(Color.MAGENTA)
                return false
            }
            canvas.drawColor(Color.MAGENTA)
            val paint = Paint()
            val cropTop = if (cropped) 228f else 0f
            val cropHeight = if (cropped) 1920f else 2340f
            for (q in 0..3) {
                paint.color = colors[q]
                val x0 = q % 2 * 540f
                val x1 = x0 + 540
                val y0 = cropTop + q / 2 * cropHeight / 2
                val y1 = y0 + cropHeight / 2
                // AOSP ROTATION_90 is counterclockwise: (x,y) -> (y, naturalWidth-x).
                // This physical rectangle fixture does not call any production mapping helper.
                if (rotation == 1) canvas.drawRect(y0, 1080 - x1, y1, 1080 - x0, paint)
                else canvas.drawRect(x0, y0, x1, y1, paint)
            }
            return true
        } finally { surface.unlockCanvasAndPost(canvas) }
    }

    private fun activityValue(scenario: ActivityScenario<SyntheticVideoProbeActivity>,
        read: (SyntheticVideoProbeActivity) -> Boolean): Boolean {
        val value = AtomicBoolean()
        scenario.onActivity { value.set(read(it)) }
        return value.get()
    }

    private fun activityInt(scenario: ActivityScenario<SyntheticVideoProbeActivity>,
        read: (SyntheticVideoProbeActivity) -> Int): Int {
        val value = AtomicInteger()
        scenario.onActivity { value.set(read(it)) }
        return value.get()
    }

    private fun await(description: String, ready: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (System.nanoTime() < deadline) {
            if (ready()) return
            Thread.sleep(20)
        }
        assertTrue("Timed out waiting for $description", ready())
    }

    companion object { private const val TAG = "SyntheticVideoProbe" }
}

package org.screencopy.receiver.ui

import android.graphics.Color
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Parcel
import android.os.Build
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.screencopy.receiver.SyntheticVideoProbeActivity

@RunWith(AndroidJUnit4::class)
class StatisticsSurfaceOwnershipTest {
    @Test fun parcelledProducerRemainsValidWhenIndependentRendererHandleIsReleased() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val check = FutureTask<Unit> {
            ImageReader.newInstance(8, 8, PixelFormat.RGBA_8888, 2).use { reader ->
                val borrowed = reader.surface
                assertTrue(borrowed.isValid)
                val parcel = Parcel.obtain()
                val target = try {
                    borrowed.writeToParcel(parcel, 0)
                    parcel.setDataPosition(0)
                    Surface.CREATOR.createFromParcel(parcel)
                } finally { parcel.recycle() }
                try {
                    assertNotSame(borrowed, target)
                    assertTrue(borrowed.isValid)
                    assertTrue(target.isValid)
                } finally { target.release() }
                assertFalse(target.isValid)
                assertTrue(borrowed.isValid)
            }
        }
        instrumentation.runOnMainSync(check)
        check.get()
    }

    @Test
    @SdkSuppress(minSdkVersion = 31)
    fun ownedProducerFinishesAfterAnchorDestructionWithoutTouchingTheReplacement() {
        val executor = Executors.newFixedThreadPool(2)
        val drawing = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val first = AtomicReference<StatisticsSurfaceTarget>()
        val second = AtomicReference<StatisticsSurfaceTarget>()
        val protection = AtomicReference<StatisticsWindowProtection>()
        val committed = CountDownLatch(2)
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        val lifecycle = ActivityLifecycleCallback { activity, stage ->
            if (activity is SyntheticVideoProbeActivity && stage == Stage.CREATED) {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                protection.set(StatisticsWindowProtection.forWindow(activity.window))
            }
        }
        onMain { monitor.addLifecycleCallback(lifecycle) }
        try {
            ActivityScenario.launch(SyntheticVideoProbeActivity::class.java).use { scenario ->
                val root = AtomicReference<FrameLayout>()
                scenario.onActivity { activity ->
                    root.set(FrameLayout(activity).also { activity.setContentView(it) })
                    assertTrue(protection.get().canHostStatistics(root.get()))
                }
                fun attach(): Pair<SurfaceView, CountDownLatch> {
                    val created = CountDownLatch(1)
                    val destroyed = CountDownLatch(1)
                    val view = onMain {
                        SurfaceView(root.get().context).apply {
                            setSecure(true)
                            setZOrderOnTop(true)
                            holder.setFormat(PixelFormat.TRANSLUCENT)
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) { created.countDown() }
                                override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}
                                override fun surfaceDestroyed(holder: SurfaceHolder) { destroyed.countDown() }
                            })
                            root.get().addView(this, FrameLayout.LayoutParams(64, 64))
                        }
                    }
                    assertTrue(created.await(5, TimeUnit.SECONDS))
                    return view to destroyed
                }
                val (oldAnchor, destroyed) = attach()
                first.set(onMain {
                    val acknowledge = checkNotNull(protection.get().retainForStatistics(root.get()))
                    AndroidStatisticsSurfaceTargets().create(oldAnchor, 64, 64) {
                        acknowledge()
                        committed.countDown()
                    }.also { it.show() }
                })
                val inFlight = executor.submit {
                    first.get().draw { canvas ->
                        canvas.drawColor(Color.RED)
                        drawing.countDown()
                        check(resume.await(5, TimeUnit.SECONDS))
                    }
                }
                try {
                    assertTrue(drawing.await(5, TimeUnit.SECONDS))
                    onMain {
                        first.get().detach()
                        root.get().removeView(oldAnchor)
                    }
                    assertTrue(destroyed.await(5, TimeUnit.SECONDS))
                    val (newAnchor) = attach()
                    second.set(onMain {
                        val acknowledge = checkNotNull(protection.get().retainForStatistics(root.get()))
                        AndroidStatisticsSurfaceTargets().create(newAnchor, 64, 64) {
                            acknowledge()
                            committed.countDown()
                        }.also { it.show() }
                    })
                    onMain { assertFalse(protection.get().clearSecureFlag()) }
                    executor.submit { second.get().draw { it.drawColor(Color.GREEN) } }
                        .get(5, TimeUnit.SECONDS)
                    resume.countDown()
                    inFlight.get(5, TimeUnit.SECONDS)
                    executor.submit { first.get().release() }.get(5, TimeUnit.SECONDS)
                    executor.submit { second.get().draw { it.drawColor(Color.BLUE) } }
                        .get(5, TimeUnit.SECONDS)
                    assertTrue(onMain { newAnchor.holder.surface.isValid })
                    scenario.onActivity { activity ->
                        assertFalse(protection.get().clearSecureFlag())
                        assertEquals(WindowManager.LayoutParams.FLAG_SECURE,
                            activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
                    }
                } finally {
                    resume.countDown()
                    try { inFlight.get(5, TimeUnit.SECONDS) } finally {
                        onMain {
                            first.get()?.detach()
                            second.get()?.detach()
                        }
                        executor.submit {
                            try { first.get()?.release() } finally { second.get()?.release() }
                        }.get(5, TimeUnit.SECONDS)
                    }
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    assertTrue("Real detach commits must acknowledge both owned layers",
                        committed.await(5, TimeUnit.SECONDS))
                    scenario.onActivity { activity ->
                        assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }
            }
        } finally {
            resume.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            onMain { monitor.removeLifecycleCallback(lifecycle) }
        }
    }

    private fun <T> onMain(action: () -> T): T {
        val task = FutureTask(action)
        InstrumentationRegistry.getInstrumentation().runOnMainSync(task)
        return task.get()
    }
}

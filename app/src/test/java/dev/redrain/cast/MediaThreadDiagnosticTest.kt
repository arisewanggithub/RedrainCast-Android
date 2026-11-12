package dev.redrain.cast

import android.os.Process
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@ConscryptMode(ConscryptMode.Mode.OFF)
class MediaThreadDiagnosticTest {
    @Test fun productionReadbackUsesActualWorkerAndPreservesBetterDefaultAndBackgroundPriorities() {
        val creatorPriority = Process.getThreadPriority(Process.myTid())
        val done = CountDownLatch(1)
        val error = AtomicReference<Throwable>()
        val worker = Thread({
            try {
                for (priority in listOf(-8, -1, 0, 10)) {
                    Process.setThreadPriority(priority)
                    for (role in MediaThreadRole.entries) {
                        MediaThreadDiagnostic.report(role)
                        assertEquals(priority, Process.getThreadPriority(Process.myTid()))
                        assertTrue(ShadowLog.getLogsForTag("RedrainCastMedia").any {
                            it.msg == "event=MEDIA_THREAD_PRIORITY role=$role thread=media-diagnostic-test" +
                                " actual_priority=$priority policy=unchanged"
                        })
                    }
                }
            } catch (e: Throwable) { error.set(e) }
            finally { done.countDown() }
        }, "media-diagnostic-test")
        worker.start()
        assertTrue(done.await(3, TimeUnit.SECONDS))
        worker.join(1000)
        assertFalse(worker.isAlive)
        error.get()?.let { throw AssertionError(it) }
        assertEquals(creatorPriority, Process.getThreadPriority(Process.myTid()))
    }

    @Test fun deniedReadbackIsExplicitAndAdvisory() {
        val warnings = mutableListOf<String>()
        MediaThreadDiagnostic.report(MediaThreadRole.AUDIO,
            { throw SecurityException("platform denied readback") },
            { fail("Denial must not look successful") }, warnings::add)
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("event=MEDIA_THREAD_PRIORITY_UNAVAILABLE"))
        assertTrue(warnings.single().contains("exception=SecurityException"))
    }

    @Test fun unavailableThreadReadbackDoesNotFailStartup() {
        val warnings = mutableListOf<String>()
        MediaThreadDiagnostic.report(MediaThreadRole.VIDEO,
            { throw IllegalArgumentException("thread unavailable") },
            { fail("Unavailable readback must warn") }, warnings::add)
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("exception=IllegalArgumentException"))
    }
}

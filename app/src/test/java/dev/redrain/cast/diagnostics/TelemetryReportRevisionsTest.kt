package dev.redrain.cast.diagnostics

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.Executor

class TelemetryReportRevisionsTest {
    @Test fun queuedRevisionsCollapseToNewestAndFailuresAreReported() {
        val work = ArrayList<Runnable>()
        val executor = Executor { work.add(it) }
        val history = TelemetryHistory(false)
        val collector = SessionTelemetryCollector { 1 }
        collector.event(TelemetryRow(1, DiagnosticStage.PRESSURE.ordinal, 1, 1, 1, 0, listOf(0, 0, 0, 0, 1)), pressure = true)
        history.accept(TelemetryHistory.Endpoint.SENDER, collector.publish(), 1)
        val first = history.initial()!!
        val final = history.finish(1)!!
        val written = ArrayList<ByteArray>()
        val failed = ArrayList<Exception>()
        val revisions = TelemetryReportRevisions({ written.add(it) }, failed::add, executor)
        assertTrue(revisions.submit(TelemetryReportRevisions.Revision.INITIAL, first))
        assertTrue(revisions.submit(TelemetryReportRevisions.Revision.FINAL, final))
        assertFalse(revisions.submit(TelemetryReportRevisions.Revision.WINDOW_CLOSED, first))
        assertEquals(1, work.size)
        work.single().run()
        assertEquals(1, written.size)
        assertTrue(org.screencopy.receiver.protocol.StrictJson.decode(written.single()).getBoolean("final"))
        assertTrue(failed.isEmpty())

        val work2 = ArrayList<Runnable>()
        val failures = ArrayList<Exception>()
        val broken = TelemetryReportRevisions({ throw IOException("disk") }, failures::add, Executor { work2.add(it) })
        assertTrue(broken.submit(TelemetryReportRevisions.Revision.INITIAL, first))
        work2.single().run()
        assertEquals(1, failures.size)
        assertTrue(broken.submit(TelemetryReportRevisions.Revision.FINAL, final))
    }
}

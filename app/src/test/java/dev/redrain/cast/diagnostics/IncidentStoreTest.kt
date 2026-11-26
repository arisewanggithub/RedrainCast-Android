package dev.redrain.cast.diagnostics

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class IncidentStoreTest {
    @Test fun activeReplacementIsSerializedAcrossInstancesForListReadAndClear() {
        for (operation in listOf("list", "read", "clear")) {
            val directory = File("build", "incident-test-${UUID.randomUUID()}")
            val context = Mockito.mock(Context::class.java)
            Mockito.`when`(context.applicationContext).thenReturn(context)
            Mockito.`when`(context.filesDir).thenReturn(directory)
            val key = "a".repeat(32)
            val name = "incident-$key.json"
            val initial = bundle(0).toString().toByteArray()
            val latest = bundle(0).put("peerState", "unsupported").toString().toByteArray()
            val paused = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val writerDone = CountDownLatch(1)
            val operationStarted = CountDownLatch(1)
            val operationDone = CountDownLatch(1)
            val failure = AtomicReference<Throwable>()
            val result = AtomicReference<Any?>()
            val writer = IncidentStore(context, afterStartWrite = {
                paused.countDown()
                check(resume.await(5, TimeUnit.SECONDS))
            }, atomicFile = ::platformAtomicFile)
            val other = IncidentStore(context, executor = java.util.concurrent.Executor { task ->
                Thread {
                    operationStarted.countDown()
                    task.run()
                }.apply { isDaemon = true; start() }
            })
            try {
                IncidentStore(context).writeTelemetry(key, initial)
                Thread {
                    try {
                        writer.writeTelemetry(key, latest)
                    } catch (error: Throwable) {
                        failure.set(error)
                    } finally {
                        writerDone.countDown()
                    }
                }.apply { isDaemon = true; start() }
                assertTrue(paused.await(5, TimeUnit.SECONDS))
                val staging = File(File(directory, "pressure-incidents"), "$name.new")
                assertTrue(staging.isFile)
                assertArrayEquals(initial, File(File(directory, "pressure-incidents"), name).readBytes())
                when (operation) {
                    "list" -> other.list { result.set(it); operationDone.countDown() }
                    "read" -> other.read(name) { result.set(it); operationDone.countDown() }
                    else -> other.clear { result.set(it); operationDone.countDown() }
                }
                assertTrue(operationStarted.await(5, TimeUnit.SECONDS))
                assertFalse("$operation completed during an active write", operationDone.await(200, TimeUnit.MILLISECONDS))
                assertTrue("$operation deleted an active staging file", staging.isFile)
                resume.countDown()
                assertTrue(writerDone.await(5, TimeUnit.SECONDS))
                assertNull("$operation writer failed:\n${failure.get()?.stackTraceToString()}", failure.get())
                assertTrue(operationDone.await(5, TimeUnit.SECONDS))
                val file = File(File(directory, "pressure-incidents"), name)
                when (operation) {
                    "list" -> {
                        @Suppress("UNCHECKED_CAST")
                        assertEquals(listOf(name), (result.get() as List<IncidentStore.Report>).map { it.name })
                        assertArrayEquals(latest, file.readBytes())
                    }
                    "read" -> {
                        assertEquals(IncidentStore.ReadResult.Success(latest.toString(Charsets.UTF_8)), result.get())
                        assertArrayEquals(latest, file.readBytes())
                    }
                    else -> {
                        assertEquals(true, result.get())
                        assertFalse(file.exists())
                    }
                }
                assertFalse(staging.exists())
            } finally {
                resume.countDown()
                writerDone.await(5, TimeUnit.SECONDS)
                directory.deleteRecursively()
            }
        }
    }

    @Test fun listCallbackDoesNotHoldSharedStorageLock() {
        val directory = File("build", "incident-test-${UUID.randomUUID()}")
        val context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.applicationContext).thenReturn(context)
        Mockito.`when`(context.filesDir).thenReturn(directory)
        val direct = java.util.concurrent.Executor { it.run() }
        val store = IncidentStore(context, executor = direct)
        val other = IncidentStore(context, executor = direct)
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        try {
            store.list {
                Thread {
                    try {
                        other.writeTelemetry("a".repeat(32), bundle(0).toString().toByteArray())
                    } catch (error: Throwable) {
                        failure.set(error)
                    } finally {
                        done.countDown()
                    }
                }.apply { isDaemon = true; start() }
                assertTrue(done.await(5, TimeUnit.SECONDS))
            }
            assertNull(failure.get())
            assertEquals(1, reports(store).size)
        } finally { directory.deleteRecursively() }
    }

    @Test fun telemetryReportsLargerThanLegacyLimitPersistReadAndClear() {
        val directory = File("build", "incident-test-${UUID.randomUUID()}")
        val context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.applicationContext).thenReturn(context)
        Mockito.`when`(context.filesDir).thenReturn(directory)
        val store = IncidentStore(context, executor = java.util.concurrent.Executor { it.run() })
        val history = TelemetryHistory(false)
        val collector = SessionTelemetryCollector { 1 }
        collector.event(TelemetryRow(1, DiagnosticStage.PRESSURE.ordinal, 1, 1, 1, 0, listOf(0, 0, 0, 0, 1)), pressure = true)
        repeat(64) {
            collector.state(it, TelemetryRow(1, 2, 1, 1, 1, 0, List(15) { Long.MAX_VALUE }))
        }
        try {
            repeat(25) { history.accept(TelemetryHistory.Endpoint.SENDER, collector.publish(), it.toLong() + 1) }
            assertFalse(directory.exists())
            val initial = history.initial()!!
            val final = history.finish(26)!!
            val bytes = TelemetryReport.encode(final)
            assertTrue(bytes.size > 64 * 1024)
            val work = ArrayList<Runnable>()
            val errors = ArrayList<Exception>()
            val revisions = TelemetryReportRevisions({ store.writeTelemetry("a".repeat(32), it) },
                errors::add, java.util.concurrent.Executor { work.add(it) })
            assertTrue(revisions.submit(TelemetryReportRevisions.Revision.INITIAL, initial))
            assertTrue(revisions.submit(TelemetryReportRevisions.Revision.FINAL, final))
            assertEquals(1, work.size)
            assertFalse(directory.exists())
            work.single().run()
            assertTrue(errors.isEmpty())
            val name = "incident-${"a".repeat(32)}.json"
            store.read(name) {
                val content = (it as IncidentStore.ReadResult.Success).content
                assertEquals(bytes.toString(Charsets.UTF_8), content)
                val exported = JSONObject(IncidentSummary.export(content))
                assertTrue(exported.getString("summary").contains("Receiver telemetry: unsupported"))
                assertTrue(exported.getJSONObject("vocabulary").getString("telemetryRow").contains("localNs"))
            }
            store.clear { assertTrue(it) }
            store.read(name) { assertSame(IncidentStore.ReadResult.Missing, it) }
            assertThrows(IllegalArgumentException::class.java) {
                store.writeTelemetry("b".repeat(32), ByteArray(TelemetryReport.MAX_BYTES + 1))
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun readFailuresKeepReportsAndReturnUnderlyingErrorOnce() {
        val directory = File("build", "incident-test-${UUID.randomUUID()}")
        val owned = File(directory, "pressure-incidents").apply { mkdirs() }
        val context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.applicationContext).thenReturn(context)
        Mockito.`when`(context.filesDir).thenReturn(directory)
        val file = File(owned, "incident-${"%032x".format(0)}.json").apply { writeText(bundle(0).toString()) }
        val failure = IOException("Report read failed")
        var reads = 0
        val store = IncidentStore(context, readBytes = {
            if (++reads == 2) throw failure else it.readBytes()
        }, executor = java.util.concurrent.Executor { it.run() })
        try {
            var callbacks = 0
            store.read(file.name) {
                callbacks++
                assertSame(failure, (it as IncidentStore.ReadResult.Failure).error)
            }
            assertEquals(1, callbacks)
            assertEquals(2, reads)
            assertEquals(bundle(0).toString(), file.readText())
            assertTrue(ShadowLog.getLogsForTag("PressureIncident").any { it.throwable === failure })
            store.read(file.name) { assertEquals(IncidentStore.ReadResult.Success(file.readText()), it) }

            val corrupt = File(owned, "incident-${"%032x".format(1)}.json").apply { writeText("{") }
            val cleanupFailure = IncidentStore(context, deleteFile = { false },
                executor = java.util.concurrent.Executor { it.run() })
            cleanupFailure.read(file.name) {
                assertTrue((it as IncidentStore.ReadResult.Failure).error is IllegalStateException)
            }
            assertTrue(file.exists())
            assertTrue(corrupt.exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun rejectedReadReturnsFailureOnceWithoutTouchingRetainedReport() {
        val directory = File("build", "incident-test-${UUID.randomUUID()}")
        val owned = File(directory, "pressure-incidents").apply { mkdirs() }
        val context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.applicationContext).thenReturn(context)
        Mockito.`when`(context.filesDir).thenReturn(directory)
        val file = File(owned, "incident-${"%032x".format(0)}.json").apply { writeText(bundle(0).toString()) }
        val failure = java.util.concurrent.RejectedExecutionException("Queue full")
        val store = IncidentStore(context, executor = java.util.concurrent.Executor { throw failure })
        try {
            var callbacks = 0
            store.read(file.name) {
                callbacks++
                assertSame(failure, (it as IncidentStore.ReadResult.Failure).error)
            }
            assertEquals(1, callbacks)
            assertEquals(bundle(0).toString(), file.readText())
            assertTrue(ShadowLog.getLogsForTag("PressureIncident").any { it.throwable === failure })
        } finally { directory.deleteRecursively() }
    }

    @Test fun absentMalformedAndExpiredReadsRemainMissing() {
        val directory = File("build", "incident-test-${UUID.randomUUID()}")
        val owned = File(directory, "pressure-incidents").apply { mkdirs() }
        val context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.applicationContext).thenReturn(context)
        Mockito.`when`(context.filesDir).thenReturn(directory)
        val store = IncidentStore(context, executor = java.util.concurrent.Executor { it.run() })
        val file = File(owned, "incident-${"%032x".format(0)}.json")
        try {
            store.read(file.name) { assertSame(IncidentStore.ReadResult.Missing, it) }
            file.writeText("{")
            store.read(file.name) { assertSame(IncidentStore.ReadResult.Missing, it) }
            assertFalse(file.exists())
            file.writeText(bundle(0).toString())
            assertTrue(file.setLastModified(System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000))
            store.read(file.name) { assertSame(IncidentStore.ReadResult.Missing, it) }
            assertFalse(file.exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun corruptBackupCannotReplaceValidBaseAndRichAndLegacyReportsCoexist() {
        val directory = File("build", "incident-test-${UUID.randomUUID()}")
        val owned = File(directory, "pressure-incidents").apply { mkdirs() }
        val context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.applicationContext).thenReturn(context)
        Mockito.`when`(context.filesDir).thenReturn(directory)
        val store = IncidentStore(context)
        try {
            val base = File(owned, "incident-${"%032x".format(0)}.json")
            val original = bundle(0).toString()
            base.writeText(original)
            val backup = File(owned, "${base.name}.bak").apply { writeText("{") }
            val rich = bundle(1)
            rich.put("local", snapshot("sender", final = false, evidence = true))
            val other = File(owned, "incident-${"%032x".format(1)}.json").apply { writeText(rich.toString()) }
            assertEquals(setOf(base.name, other.name), reports(store).map { it.name }.toSet())
            assertEquals(original, base.readText())
            assertFalse(backup.exists())
            assertEquals(rich.toString(), other.readText())
        } finally { directory.deleteRecursively() }
    }
    @Test fun interruptedWriteRestoresBackupBeforeListingAndReading() {
        val directory = File("build", "incident-test-${UUID.randomUUID()}")
        val incidentDirectory = File(directory, "pressure-incidents").apply { mkdirs() }
        val context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.applicationContext).thenReturn(context)
        Mockito.`when`(context.filesDir).thenReturn(directory)
        val now = 2_000_000_000_000L
        val store = IncidentStore(context) { now }
        val base = File(incidentDirectory, "incident-${"%032x".format(0)}.json")
        val backup = File(incidentDirectory, "${base.name}.bak")
        val staging = File(incidentDirectory, "${base.name}.new")
        try {
            val original = bundle(0).toString()
            backup.writeText(original)
            assertTrue(backup.setLastModified(now - 1000))
            staging.writeText("incomplete")

            assertEquals(listOf(IncidentStore.Report(base.name, now - 1000)), reports(store))
            assertFalse(backup.exists())
            assertFalse(staging.exists())
            val result = AtomicReference<IncidentStore.ReadResult>()
            val latch = CountDownLatch(1)
            store.read(base.name) { result.set(it); latch.countDown() }
            assertTrue(latch.await(5, TimeUnit.SECONDS))
            assertEquals(IncidentStore.ReadResult.Success(original), result.get())
        } finally { directory.deleteRecursively() }
    }

    @Test fun legacyBackupTakesPrecedenceOverExistingBase() {
        val directory = File("build", "incident-test-${UUID.randomUUID()}")
        val incidentDirectory = File(directory, "pressure-incidents").apply { mkdirs() }
        val context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.applicationContext).thenReturn(context)
        Mockito.`when`(context.filesDir).thenReturn(directory)
        val store = IncidentStore(context)
        val base = File(incidentDirectory, "incident-${"%032x".format(0)}.json")
        val backup = File(incidentDirectory, "${base.name}.bak")
        try {
            val original = bundle(0).toString()
            base.writeText(bundle(0).put("peerState", "unsupported").toString())
            backup.writeText(original)

            assertEquals(listOf(base.name), reports(store).map { it.name })
            assertEquals(original, base.readText())
            assertFalse(backup.exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun invalidRecoveredBackupDoesNotDisplaceValidReports() {
        val directory = File("build", "incident-test-${UUID.randomUUID()}")
        val incidentDirectory = File(directory, "pressure-incidents").apply { mkdirs() }
        val context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.applicationContext).thenReturn(context)
        Mockito.`when`(context.filesDir).thenReturn(directory)
        val now = 2_000_000_000_000L
        val store = IncidentStore(context) { now }
        try {
            val originals = (0 until 8).map { index ->
                File(incidentDirectory, "incident-${"%032x".format(index)}.json").apply {
                    writeText(bundle(index).toString())
                    assertTrue(setLastModified(now - 1000))
                }
            }
            val base = File(incidentDirectory, "incident-${"%032x".format(8)}.json")
            val backup = File(incidentDirectory, "${base.name}.bak").apply {
                writeText("{")
                assertTrue(setLastModified(now))
            }
            val staging = File(incidentDirectory, "incident-${"%032x".format(9)}.json.new")
                .apply { writeText(bundle(9).toString()) }

            assertEquals(originals.map { it.name }.toSet(), reports(store).map { it.name }.toSet())
            assertFalse(backup.exists())
            assertFalse(base.exists())
            assertFalse(staging.exists())
            originals.forEachIndexed { index, file -> assertEquals(bundle(index).toString(), file.readText()) }
        } finally { directory.deleteRecursively() }
    }

    @Test fun rollbackClampsFutureFilesAndKeepsNewSaveUntilCorrectedExpiry() {
        val directory = File("build", "incident-test-${UUID.randomUUID()}")
        val context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.applicationContext).thenReturn(context)
        Mockito.`when`(context.filesDir).thenReturn(directory)
        var now = 2_000_000_000_000L
        val store = IncidentStore(context) { now }
        try {
            repeat(8) { index ->
                store.write("%032x".format(index), bundle(index))
                reports(store)
            }
            now -= 60_000
            store.write("%032x".format(8), bundle(8))
            val retained = reports(store)
            assertEquals(8, retained.size)
            assertTrue(retained.any { it.name == "incident-${"%032x".format(8)}.json" })
            assertTrue(
                "rolled-back now=$now; future reports=" + retained.filter { it.timestampMs > now }.map {
                    "${it.name}: reported=${it.timestampMs}, filesystem=" +
                        File(File(directory, "pressure-incidents"), it.name).lastModified()
                },
                retained.all { it.timestampMs <= now }
            )
            now += 7L * 24 * 60 * 60 * 1000
            val expired = reports(store)
            assertTrue(
                "expiry now=$now cutoff=${now - 7L * 24 * 60 * 60 * 1000}; " +
                    "before=$retained; remaining=$expired; filesystem=" +
                    File(directory, "pressure-incidents").listFiles()?.map { "${it.name}:${it.lastModified()}" } +
                    "; logs=" +
                    ShadowLog.getLogsForTag("PressureIncident").takeLast(20).map { "${it.msg} ${it.throwable}" },
                expired.isEmpty()
            )
        } finally { directory.deleteRecursively() }
    }

    @Test fun invalidFilesDoNotDisplaceValidFilesBeforeCountPruning() {
        val directory = File("build", "incident-test-${UUID.randomUUID()}")
        val incidentDirectory = File(directory, "pressure-incidents").apply { mkdirs() }
        val context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.applicationContext).thenReturn(context)
        Mockito.`when`(context.filesDir).thenReturn(directory)
        val now = 2_000_000_000_000L
        val store = IncidentStore(context) { now }
        try {
            repeat(8) { index ->
                File(incidentDirectory, "incident-${"%032x".format(index)}.json").apply {
                    writeText(bundle(index).toString())
                    assertTrue(setLastModified(now - 1000))
                }
            }
            File(incidentDirectory, "incident-${"%032x".format(8)}.json").apply {
                writeBytes(ByteArray(TelemetryReport.MAX_BYTES + 1))
                assertTrue(setLastModified(now))
            }
            assertEquals(8, reports(store).size)
        } finally { directory.deleteRecursively() }
    }

    @Test fun availablePeerWithoutSnapshotIsRemovedBeforeRetentionCounting() {
        val directory = File("build", "incident-test-${UUID.randomUUID()}")
        val incidentDirectory = File(directory, "pressure-incidents").apply { mkdirs() }
        val context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.applicationContext).thenReturn(context)
        Mockito.`when`(context.filesDir).thenReturn(directory)
        val now = 2_000_000_000_000L
        val store = IncidentStore(context) { now }
        try {
            val bundles = (0 until 8).map { bundle(it) }
            bundles[1].remove("peerProvenance")
            bundles[2].put("peer", snapshot("receiver", final = true))
                .put("peerState", "available").put("peerProvenance", "episode")
            bundles[7].put("local", snapshot("receiver", final = true))
                .put("peerState", "not_collected_on_receiver")
                .put("captureOrigin", "remote_fetch_after_teardown")
                .put("correlation", "uncorrelated_remote_fetch").apply {
                    remove("peerProvenance")
                    for (field in listOf("coalesced", "suppressedPressure", "inboundDropped", "sendDropped"))
                        put(field, JSONObject.NULL)
                }
            val originals = bundles.mapIndexed { index, value ->
                File(incidentDirectory, "incident-${"%032x".format(index)}.json").apply {
                    writeText(value.toString())
                    assertTrue(setLastModified(now - 1000))
                }
            }
            val inconsistent = File(incidentDirectory, "incident-${"%032x".format(8)}.json").apply {
                writeText(bundle(8).put("peerState", "available").toString())
                assertTrue(setLastModified(now))
            }

            assertEquals(originals.map { it.name }.toSet(), reports(store).map { it.name }.toSet())
            assertFalse(inconsistent.exists())
            originals.forEachIndexed { index, file ->
                assertEquals(bundles[index].toString(), file.readText())
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun retentionAndClearTouchOnlyOwnedFiles() {
        val directory = File("build", "incident-test-${UUID.randomUUID()}")
        val context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.applicationContext).thenReturn(context)
        Mockito.`when`(context.filesDir).thenReturn(directory)
        val store = IncidentStore(context)
        try {
            repeat(10) { index ->
                store.write("%032x".format(index), bundle(index))
                reports(store)
            }
            val reports = reports(store)
            assertEquals(8, reports.size)
            val incidentDirectory = File(directory, "pressure-incidents")
            val sentinel = File(incidentDirectory, "unrelated.txt").apply { writeText("keep") }
            val old = File(incidentDirectory, reports.last().name)
            assertTrue(old.setLastModified(System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000))
            assertEquals(7, reports(store).size)
            val latch = CountDownLatch(1)
            var cleared = false
            store.clear { cleared = it; latch.countDown() }
            assertTrue(latch.await(5, TimeUnit.SECONDS))
            assertTrue(cleared)
            assertTrue(reports(store).isEmpty())
            assertEquals("keep", sentinel.readText())
        } finally { directory.deleteRecursively() }
    }

    @Test fun existingNonDirectoryStoragePathFailsListAndClearInsteadOfReportingSuccess() {
        val filesDir = File("build", "incident-test-${UUID.randomUUID()}").apply { mkdirs() }
        val storagePath = File(filesDir, "pressure-incidents").apply { writeText("not a directory") }
        val context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.applicationContext).thenReturn(context)
        Mockito.`when`(context.filesDir).thenReturn(filesDir)
        val store = IncidentStore(context)
        try {
            val listed = AtomicReference<List<IncidentStore.Report>?>()
            val listLatch = CountDownLatch(1)
            store.list { listed.set(it); listLatch.countDown() }
            assertTrue(listLatch.await(5, TimeUnit.SECONDS))
            assertNull("An enumeration failure must not look like an empty directory", listed.get())

            val cleared = AtomicReference<Boolean>()
            val clearLatch = CountDownLatch(1)
            store.clear { cleared.set(it); clearLatch.countDown() }
            assertTrue(clearLatch.await(5, TimeUnit.SECONDS))
            assertFalse("A failed directory scan must not report a successful clear", cleared.get())
            assertTrue(storagePath.isFile)
        } finally { filesDir.deleteRecursively() }
    }

    @Test fun newerCorruptBundlesDoNotEvictEightValidLocalPeerLegacyAndFallbackReports() {
        val directory = File("build", "incident-test-${UUID.randomUUID()}")
        val incidentDirectory = File(directory, "pressure-incidents").apply { mkdirs() }
        val context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.applicationContext).thenReturn(context)
        Mockito.`when`(context.filesDir).thenReturn(directory)
        val now = 2_000_000_000_000L
        val store = IncidentStore(context) { now }
        try {
            val bundles = (0 until 8).map { bundle(it) }
            bundles[1].remove("peerProvenance") // Legacy report.
            for ((index, provenance) in listOf("episode", "uncorrelated", "unknown").withIndex()) {
                bundles[index + 2].put("peer", snapshot("receiver", final = true))
                    .put("peerState", listOf("available", "uncorrelated", "provenance_unknown")[index])
                    .put("peerProvenance", provenance)
            }
            bundles[5].put("peer", snapshot("receiver", final = false))
                .put("peerState", "partial").put("peerProvenance", "episode")
            bundles[6].put("local", snapshot("receiver", final = true))
                .put("peerState", "not_collected_on_receiver")
            bundles[7].put("local", snapshot("receiver", final = true))
                .put("peerState", "not_collected_on_receiver")
                .put("captureOrigin", "remote_fetch_after_teardown")
                .put("correlation", "uncorrelated_remote_fetch").apply {
                    remove("peerProvenance")
                    for (field in listOf("coalesced", "suppressedPressure", "inboundDropped", "sendDropped"))
                        put(field, JSONObject.NULL)
                }
            val originals = bundles.mapIndexed { index, value ->
                File(incidentDirectory, "incident-${"%032x".format(index)}.json").apply {
                    writeText(value.toString())
                    assertTrue(setLastModified(now - 1000))
                }
            }
            val corrupt = File(incidentDirectory, "incident-${"%032x".format(8)}.json")
            val malformed = mutableListOf("{\"version\":1,", "{}", bundle(8).put("version", "1").toString(),
                bundle(8).put("local", JSONObject()).toString(),
                bundle(8).put("peer", "not a snapshot").toString(),
                bundle(8).put("peer", snapshot("sender", true)).toString(),
                bundle(8).put("peerState", 1).toString(),
                bundle(8).put("sendDropped", -1).toString(),
                bundle(8).put("clockCorrelation", JSONObject.NULL).toString(),
                bundle(8).put("captureOrigin", "remote_fetch_after_teardown").toString(),
                bundle(8).put("correlation", "uncorrelated_remote_fetch").toString(),
                bundle(8).put("captureOrigin", "other").put("correlation", "other").toString(),
                bundle(8).toString() + "{}")
            for (field in listOf("version", "incident", "local", "peer", "peerState",
                "coalesced", "suppressedPressure", "inboundDropped", "sendDropped", "clockCorrelation")) {
                malformed += bundle(8).apply { remove(field) }.toString()
            }
            for (text in malformed) {
                corrupt.writeText(text)
                assertTrue(corrupt.setLastModified(now))
                assertEquals(originals.map { it.name }.toSet(), reports(store).map { it.name }.toSet())
                assertFalse(corrupt.exists())
                originals.forEachIndexed { index, file -> assertEquals(bundles[index].toString(), file.readText()) }
            }
            assertTrue(ShadowLog.getLogsForTag("PressureIncident").any {
                it.msg == "storage_corrupt_report_discarded"
            })
        } finally { directory.deleteRecursively() }
    }

    @Test fun readIoAndCorruptionCleanupFailuresAreSurfacedWithoutEvictingValidReports() {
        val directory = File("build", "incident-test-${UUID.randomUUID()}")
        val incidentDirectory = File(directory, "pressure-incidents").apply { mkdirs() }
        val context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.applicationContext).thenReturn(context)
        Mockito.`when`(context.filesDir).thenReturn(directory)
        try {
            val valid = (0 until 8).map { index ->
                File(incidentDirectory, "incident-${"%032x".format(index)}.json").apply {
                    writeText(bundle(index).toString())
                }
            }
            val unreadable = valid.last()
            val ioFailure = IncidentStore(context, readBytes = {
                if (it == unreadable) throw IOException("Read failed") else it.readBytes()
            })
            assertNull(listResult(ioFailure))
            assertTrue(valid.all { it.exists() })
            assertTrue(ShadowLog.getLogsForTag("PressureIncident").any { it.msg == "storage_failure=IOException" })

            val corrupt = File(incidentDirectory, "incident-${"%032x".format(8)}.json").apply { writeText("{") }
            val cleanupFailure = IncidentStore(context, deleteFile = { if (it == corrupt) false else it.delete() })
            assertNull(listResult(cleanupFailure))
            assertTrue(corrupt.exists())
            assertTrue(valid.all { it.exists() })
            assertTrue(ShadowLog.getLogsForTag("PressureIncident").any {
                it.msg == "storage_failure=corrupt_report_cleanup_failed"
            })
        } finally { directory.deleteRecursively() }
    }

    private fun snapshot(role: String, final: Boolean, evidence: Boolean = false): JSONObject {
        val value = JSONObject("""
            {"role":"sender","final":false,"frames":[],"configuration":[],"droppedEvents":"0",
             "omittedFrames":0,"omittedRecords":0}
        """.trimIndent()).put("role", role).put("final", final)
        if (evidence) value.put("evidence", JSONObject("""
            {"version":1,"first":[],"terminal":[],"milestones":[],"latest":[],"cached":[],
             "omitted":"0","journalStartSequence":"0","journalEndSequence":"0","sampledAtNs":"0",
             "omittedRangeNs":[null,null],"overwrittenRangeUnknown":false,"droppedFields":"0",
             "historyOmittedRecords":"0","operations":{"records":[],"omitted":0}}
        """.trimIndent())).put("omittedFrameRanges", org.json.JSONArray())
        return value
    }

    private fun platformAtomicFile(file: File): AtomicFile {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return AtomicFile(file)
        // Android's rename replaces an existing file; Robolectric's Windows File.renameTo cannot.
        return object : AtomicFile(file) {
            override fun finishWrite(stream: FileOutputStream) {
                stream.fd.sync()
                stream.close()
                Files.move(File(file.parentFile, "${file.name}.new").toPath(), file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private fun bundle(index: Int) = JSONObject().put("version", 1).put("incident", "%032x".format(index))
        .put("local", snapshot("sender", final = false)).put("peer", JSONObject.NULL)
        .put("peerState", "awaiting_snapshot").put("peerProvenance", JSONObject.NULL)
        .put("coalesced", "0").put("suppressedPressure", "0").put("inboundDropped", "0").put("sendDropped", "0")
        .put("clockCorrelation", "unsynchronized; monotonic times are local to each peer")

    private fun reports(store: IncidentStore): List<IncidentStore.Report> {
        return checkNotNull(listResult(store)) {
            "Report listing failed; logs=" +
                ShadowLog.getLogsForTag("PressureIncident").takeLast(20).map { "${it.msg} ${it.throwable}" }
        }
    }

    private fun listResult(store: IncidentStore): List<IncidentStore.Report>? {
        val value = AtomicReference<List<IncidentStore.Report>?>()
        val latch = CountDownLatch(1)
        store.list { value.set(it); latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        return value.get()
    }
}

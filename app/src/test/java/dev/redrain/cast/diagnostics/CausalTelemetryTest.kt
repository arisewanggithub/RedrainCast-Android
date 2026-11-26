package dev.redrain.cast.diagnostics

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.screencopy.receiver.media.AudioCausalProgress
import org.screencopy.receiver.media.AudioInputProgress
import org.screencopy.receiver.media.AudioPosition
import org.screencopy.receiver.media.PlaybackClock
import org.screencopy.receiver.media.AudioFormat
import org.screencopy.receiver.media.AudioOutput
import org.screencopy.receiver.media.testAudioPlayer
import org.mockito.Mockito
import org.screencopy.receiver.protocol.ProtocolException
import org.screencopy.receiver.protocol.StrictJson
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class CausalTelemetryTest {
    @Test fun nestedAudioCallsRestoreActiveParentAndKeepEachDuration() {
        for (freezeInsideParent in listOf(true, false)) {
            val now = AtomicLong(100)
            val telemetry = ReceiverTelemetrySession({ true })
            val id = if (freezeInsideParent) 9711L else 9712L
            TelemetryRegistry.beginReceiver(id, telemetry)
            val player = testAudioPlayer(AudioFormat("pcm", 48000, 2, byteArrayOf()),
                PlaybackClock(now::get), { _, _ -> fail("unexpected audio failure") },
                { Mockito.mock(AudioOutput::class.java) })
            player.diagnosticSession = id
            player.diagnosticGeneration = 1
            var parentId = 0L
            var childId = 0L
            try {
                player.diagnosticCall(DiagnosticPoint.DECODE) { parent ->
                    parentId = parent
                    now.set(200)
                    player.diagnosticCall(DiagnosticPoint.RESAMPLE) { child ->
                        childId = child
                        now.set(210)
                    }
                    if (freezeInsideParent) TelemetryRegistry.freezeReceiver(id, 1)
                    now.set(250)
                }
                if (!freezeInsideParent) TelemetryRegistry.freezeReceiver(id, 1)
                assertTrue(parentId > 0 && childId > 0 && parentId != childId)
                val final = checkNotNull(telemetry.requestFinal(true, System.nanoTime() + 1_000_000_000))
                val batch = SessionTelemetryProtocol.parse(final.payload, "receiver")
                val row = batch.events.single { it.kind == CausalEvidence.KIND &&
                    it.stage == CausalEvidence.Stage.OUTPUT.ordinal && it.value == 1L }
                assertEquals(parentId, row.detail[1])
                assertEquals(DiagnosticPoint.DECODE.ordinal.toLong(), row.detail[2])
                assertEquals(100L, row.detail[3])
                assertEquals(if (freezeInsideParent) 210L else 250L, row.detail[4])
                assertEquals(if (freezeInsideParent) 10L else 150L, row.detail[5])
                assertEquals(if (freezeInsideParent) DiagnosticPoint.RESAMPLE.ordinal.toLong()
                    else DiagnosticPoint.DECODE.ordinal.toLong(), row.detail[19])
            } finally {
                TelemetryRegistry.end("receiver", telemetry)
                telemetry.requestFinal(false, System.nanoTime())
                player.close()
            }
        }
    }

    @Test fun failedNestedAudioCallRestoresParentWithoutInventingCompletion() {
        val now = AtomicLong(100)
        val telemetry = ReceiverTelemetrySession({ true })
        TelemetryRegistry.beginReceiver(9713, telemetry)
        val player = testAudioPlayer(AudioFormat("pcm", 48000, 2, byteArrayOf()),
            PlaybackClock(now::get), { _, _ -> fail("unexpected audio failure") },
            { Mockito.mock(AudioOutput::class.java) })
        player.diagnosticSession = 9713
        player.diagnosticGeneration = 1
        var parentId = 0L
        try {
            player.diagnosticCall(DiagnosticPoint.DECODE) { parent ->
                parentId = parent
                now.set(200)
                assertThrows(IllegalStateException::class.java) {
                    player.diagnosticCall(DiagnosticPoint.RESAMPLE) {
                        now.set(210)
                        throw IllegalStateException("conversion failed")
                    }
                }
                TelemetryRegistry.freezeReceiver(9713, 1)
            }
            val final = checkNotNull(telemetry.requestFinal(true, System.nanoTime() + 1_000_000_000))
            val row = SessionTelemetryProtocol.parse(final.payload, "receiver").events.single {
                it.kind == CausalEvidence.KIND && it.stage == CausalEvidence.Stage.OUTPUT.ordinal && it.value == 1L
            }
            assertEquals(parentId, row.detail[1])
            assertEquals(DiagnosticPoint.DECODE.ordinal.toLong(), row.detail[2])
            assertEquals(100L, row.detail[3])
            assertEquals(-1L, row.detail[4])
            assertEquals(-1L, row.detail[5])
            assertEquals(-1L, row.detail[19])
        } finally {
            TelemetryRegistry.end("receiver", telemetry)
            telemetry.requestFinal(false, System.nanoTime())
            player.close()
        }
    }

    private fun checkpoint(stage: CausalEvidence.Stage, generation: Int = 1, at: Long = 1,
                           values: List<Long> = List(CausalEvidence.fields(stage.ordinal).size) { Long.MAX_VALUE }) =
        TelemetryRow(CausalEvidence.KIND, stage.ordinal, 2, generation, at, 0, values)

    private fun pressure(generation: Int = 1) = TelemetryRow(DiagnosticKind.RECOVERY.ordinal + 1,
        DiagnosticStage.PRESSURE.ordinal, 2, generation, 900_000_000, 260_000,
        listOf(2, 13, 49920, 14, 53760, 48, 524288, 250000, 0))

    private fun report(payload: ByteArray): JSONObject {
        val history = TelemetryHistory(true)
        history.accept(TelemetryHistory.Endpoint.RECEIVER, payload, 1_000_000_000)
        val sequence = SessionTelemetryProtocol.parse(payload, "receiver").sequence
        history.terminal(TelemetryHistory.Endpoint.RECEIVER, sequence, true, 1_000_000_000)
        return StrictJson.decode(TelemetryReport.encode(checkNotNull(history.finish(1_000_000_000))))
    }

    private fun finalBatch(report: JSONObject): SessionTelemetryProtocol.Batch {
        assertTrue(TelemetryReport.validate(report))
        val blocks = report.getJSONObject("receiver").getJSONArray("blocks")
        return SessionTelemetryProtocol.parse(blocks.getJSONArray(blocks.length() - 1)
            .getJSONObject(1).toString().toByteArray(), "receiver")
    }

    @Test fun subPublicationAnomalySurvivesCoalescingRecoveryOverflowAndReplacedStatusInFinalReport() {
        val collector = SessionTelemetryCollector { 1_000_000_000 }
        CausalEvidence.Stage.entries.take(8).forEach {
            collector.causal.record(if (it == CausalEvidence.Stage.BUILD)
                checkpoint(it, values = listOf(Long.MAX_VALUE, -1, 1)) else checkpoint(it))
        }
        val progress = AudioCausalProgress { stage, values, at ->
            collector.causal.record(checkpoint(stage, at = at, values = values.toList()))
        }
        progress.begin(100_000_000)
        progress.dequeue(110_000_000, 2_000_000)
        progress.interval(120_000_000, 130_000_000, 1_000_000)
        progress.interval(150_000_000, 190_000_000, 2_000_000)
        progress.end(200_000_000)
        val input = AudioInputProgress { values, at ->
            collector.causal.record(checkpoint(CausalEvidence.Stage.INPUT, at = at, values = values.toList()))
        }
        input.arrival(100_000_000, 1_000_000, 20_000)
        input.admitted(100_000_000, 1_000_000)
        input.arrival(120_000_000, 1_020_000, 20_000)
        input.arrival(121_000_000, 1_200_000, 20_000)
        collector.causal.freeze(1, 900_000_000)
        collector.event(pressure(), pressure = true)
        val discarded = collector.publish()
        assertTrue(discarded.size <= 8192)
        collector.publicationUnsent() // The replaceable STATUS was never received.
        repeat(128) {
            collector.event(TelemetryRow(1, 2, 1, 1, 1, 1, List(20) { Long.MIN_VALUE }))
        }
        repeat(1000) { n ->
            collector.state(n % 64, TelemetryRow(1, 2, 1, 2, 1, 1, List(20) { Long.MIN_VALUE }))
            collector.causal.record(checkpoint(CausalEvidence.Stage.WORKER, 2, n + 1L))
            val mapping = MutableList(CausalEvidence.fields(CausalEvidence.Stage.MAPPING.ordinal).size) { Long.MIN_VALUE }
            mapping[0] = when (n) {
                0 -> CausalEvidence.Mapping.RECOVERY_BEGIN.ordinal.toLong()
                1 -> CausalEvidence.Mapping.DETACH.ordinal.toLong()
                2 -> CausalEvidence.Mapping.PREPARE.ordinal.toLong()
                else -> CausalEvidence.Mapping.VIDEO_RELEASE.ordinal.toLong()
            }
            collector.causal.record(checkpoint(CausalEvidence.Stage.MAPPING, 2, n + 1L, mapping))
            collector.causal.record(checkpoint(CausalEvidence.Stage.START, 2, n + 1L))
        }
        collector.causal.record(checkpoint(CausalEvidence.Stage.MAPPING, 1, Long.MAX_VALUE))
        collector.event(TelemetryRow(DiagnosticKind.RECOVERY.ordinal + 1, DiagnosticStage.FAILED.ordinal,
            2, 2, Long.MAX_VALUE, Long.MAX_VALUE, List(12) { Long.MAX_VALUE }), failure = true)
        val payload = collector.publish(final = true)
        assertTrue(payload.size <= 8192)
        val result = report(payload)
        val batch = finalBatch(result)
        assertTrue(batch.final)
        assertTrue(batch.events.size <= 64)
        assertTrue(batch.state.size <= 64)
        assertTrue(batch.omitted > 0)
        assertTrue(batch.coalesced > 0)
        assertEquals(1, batch.unsent)
        val frozen = batch.events.filter { it.kind == CausalEvidence.KIND && it.value == 1L }
        val worker = frozen.single { it.stage == CausalEvidence.Stage.WORKER.ordinal }
        assertEquals(1, worker.generation)
        assertEquals(120_000_000L, worker.detail[6])
        assertEquals(10_000_000L, worker.detail[8])
        assertEquals(150_000_000L, worker.detail[11])
        assertEquals(40_000_000L, worker.detail[13])
        assertEquals(110_000_000L, worker.detail[4])
        val arrivals = frozen.single { it.stage == CausalEvidence.Stage.INPUT.ordinal }
        assertEquals(180_000L, arrivals.detail[6])
        assertEquals(1L, arrivals.detail[9])
        val accounting = batch.events.single { it.kind == CausalEvidence.KIND &&
            it.stage == CausalEvidence.Stage.CAPTURE.ordinal }
        assertTrue(accounting.detail[2] > 0)
        assertTrue(accounting.detail[2] <= accounting.detail[3])
        val later = batch.events.filter { it.kind == CausalEvidence.KIND && it.value == 2L }
        assertEquals(accounting.detail[3] - later.size, accounting.detail[2])
        assertTrue(later.any { it.stage == CausalEvidence.Stage.MAPPING.ordinal &&
            it.detail[0] == CausalEvidence.Mapping.DETACH.ordinal.toLong() })
        assertTrue(later.any { it.stage == CausalEvidence.Stage.MAPPING.ordinal &&
            it.detail[0] == CausalEvidence.Mapping.PREPARE.ordinal.toLong() })
        assertEquals(1, batch.firstPressure!!.generation)
        assertEquals(2, batch.firstFailure!!.generation)
        assertTrue(batch.events.any { it.kind == CausalEvidence.KIND &&
            it.stage == CausalEvidence.Stage.START.ordinal && it.value == 4L && it.generation == 1 })
        val exported = JSONObject(IncidentSummary.export(result.toString()))
        assertTrue(exported.getString("summary").contains("firstSlowBeginNs=120000000"))
        assertEquals(9, exported.getJSONObject("vocabulary").getJSONArray("telemetryCausalLayouts").length())
    }

    @Test fun firstPressureReadsCachedTimingBoundaryWhileNativeQueryAndClockAreHeld() {
        val queryEntered = CountDownLatch(1)
        val clockHeld = CountDownLatch(1)
        val release = CountDownLatch(1)
        val headCalls = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)
        val telemetry = ReceiverTelemetrySession({ true })
        val clock = PlaybackClock()
        TelemetryRegistry.beginReceiver(9701, telemetry)
        val position = AudioPosition(48000, 9600, System::nanoTime, {
            queryEntered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            null
        }, { headCalls.incrementAndGet(); 0 }, { boundary ->
            TelemetryRegistry.causal(9701, CausalEvidence.Stage.OUTPUT, 1,
                *longArrayOf(boundary.toLong(), 77, DiagnosticPoint.TIMING.ordinal.toLong(),
                    123, -1, -1, -1, 0, 0, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1))
        })
        try {
            val query = executor.submit { position.current() }
            val holder = executor.submit {
                synchronized(clock) {
                    clockHeld.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(queryEntered.await(2, TimeUnit.SECONDS))
            assertTrue(clockHeld.await(2, TimeUnit.SECONDS))
            TelemetryRegistry.freezeReceiver(9701, 1)
            telemetry.event(pressure(), pressure = true)
            val final = checkNotNull(telemetry.requestFinal(true, System.nanoTime() + 1_000_000_000))
            assertEquals("No output inspection is needed to freeze or serialize", 0, headCalls.get())
            assertEquals(1L, release.count)
            val batch = finalBatch(report(final.payload))
            val boundary = batch.events.single { it.kind == CausalEvidence.KIND &&
                it.stage == CausalEvidence.Stage.OUTPUT.ordinal && it.value == 1L }
            assertEquals(CausalEvidence.Output.TIMESTAMP_BEGIN.ordinal.toLong(), boundary.detail[0])
            assertEquals(77L, boundary.detail[1])
            assertEquals(-1L, boundary.detail[11])
            assertTrue(batch.events.any { it.kind == CausalEvidence.KIND &&
                it.stage == CausalEvidence.Stage.BUILD.ordinal && it.detail[1] == -1L })
            release.countDown()
            query.get(2, TimeUnit.SECONDS)
            holder.get(2, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            TelemetryRegistry.end("receiver", telemetry)
            telemetry.requestFinal(false, System.nanoTime())
            executor.shutdownNow()
        }
    }

    @Test fun legacyReportsRemainReadableButLegacyPeersCannotReceiveNewVocabulary() {
        assertFalse(SessionTelemetryProtocol.supported(JSONObject().put("sessionTelemetry",
            SessionTelemetryProtocol.capability().put("version", 1))))
        val legacy = SessionTelemetryCollector { 1 }.publish().toString(Charsets.UTF_8)
            .replace("\"version\":2", "\"version\":1")
        assertEquals(1, SessionTelemetryProtocol.parse(legacy.toByteArray()).sequence)
        val collector = SessionTelemetryCollector { 1 }
        collector.causal.record(checkpoint(CausalEvidence.Stage.BUILD, values = listOf(38, -1, 1)))
        val payload = collector.publish().toString(Charsets.UTF_8)
        assertThrows(ProtocolException::class.java) {
            SessionTelemetryProtocol.parse(payload.replace("\"version\":2", "\"version\":1").toByteArray())
        }
        assertThrows(ProtocolException::class.java) {
            SessionTelemetryProtocol.parse(payload.toByteArray(), "sender")
        }
        assertThrows(ProtocolException::class.java) {
            SessionTelemetryProtocol.parse(payload.replace("[\"38\",\"-1\",\"1\"]", "[\"38\"]").toByteArray())
        }
        assertThrows(ProtocolException::class.java) {
            SessionTelemetryProtocol.parse(payload.replace("[\"38\",\"-1\",\"1\"]", "[\"38\",\"-1\",\"2\"]").toByteArray())
        }
    }
}

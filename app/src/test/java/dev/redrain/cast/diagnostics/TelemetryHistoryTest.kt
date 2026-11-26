package dev.redrain.cast.diagnostics

import org.junit.Assert.*
import org.junit.Test

class TelemetryHistoryTest {
    private val second = 1_000_000_000L
    private fun collector() = SessionTelemetryCollector { 1 }
    private fun pressure() = TelemetryRow(1, DiagnosticStage.PRESSURE.ordinal, 1, 1, 1, 5, listOf(0, 0, 0, 0, 1))

    @Test fun normalCastCreatesNoReportAndUnsupportedReceiverIsIgnored() {
        val history = TelemetryHistory(receiverSupported = false)
        val sender = collector()
        assertTrue(history.accept(TelemetryHistory.Endpoint.SENDER, sender.publish(), second))
        assertFalse(history.accept(TelemetryHistory.Endpoint.RECEIVER, collector().publish(), second))
        assertNull(history.initial())
        assertNull(history.closeWindow(20 * second))
        assertNull(history.finish(20 * second))
    }

    @Test fun pressureFreezesHistoryAndFinalRequiresMatchingTerminalAndCleanup() {
        val history = TelemetryHistory(receiverSupported = true)
        val sender = collector()
        val receiver = collector()
        history.accept(TelemetryHistory.Endpoint.SENDER, sender.publish(), second)
        history.accept(TelemetryHistory.Endpoint.RECEIVER, receiver.publish(), second)
        receiver.event(pressure(), pressure = true)
        receiver.publish() // A missing telemetry publication is not a media-loss count.
        history.accept(TelemetryHistory.Endpoint.RECEIVER, receiver.publish(), 2 * second)
        assertEquals(1, history.initial()!!.receiver.missingPublications)
        assertNull(history.closeWindow(11 * second))
        val closed = history.closeWindow(12 * second)!!
        assertTrue(closed.windowClosed)
        history.accept(TelemetryHistory.Endpoint.RECEIVER, receiver.publish(final = true), 20 * second)
        history.terminal(TelemetryHistory.Endpoint.RECEIVER, 4, true, 20 * second)
        val final = history.finish(20 * second)!!
        assertTrue(final.receiverComplete)
        assertTrue(final.final)
        assertEquals(listOf(1L, 3L, 4L), final.receiver.blocks.map { it.sequence })
        assertEquals(1, final.sender.blocks.size)
        val report = org.screencopy.receiver.protocol.StrictJson.decode(TelemetryReport.encode(final))
        val receiverBlocks = report.getJSONObject("receiver").getJSONArray("blocks")
        assertEquals("2000000000", receiverBlocks.getJSONArray(1).getString(0))
        assertEquals("3", receiverBlocks.getJSONArray(1).getJSONObject(1).getString("seq"))
        val gap = report.getJSONObject("receiver").getJSONArray("gaps").getJSONArray(0)
        assertEquals("2", gap.getString(0))
        assertEquals("2", gap.getString(1))
        val summary = IncidentSummary.text(report)
        assertTrue(summary.contains("Receiver telemetry: complete"))
        assertTrue(summary.contains("sequence gaps=1"))
        assertTrue(summary.contains("Sequence gaps count publications, not media loss"))
    }

    @Test fun cleanupFailureCreatesReportButMissingOrMismatchedFinalIsIncomplete() {
        val history = TelemetryHistory(receiverSupported = true)
        history.accept(TelemetryHistory.Endpoint.RECEIVER, collector().publish(final = true), second)
        history.terminal(TelemetryHistory.Endpoint.RECEIVER, 2, false, second)
        val report = history.finish(second)!!
        assertFalse(report.receiverComplete)
        assertEquals(false, report.receiver.cleanupSucceeded)
    }

    @Test fun delayedFinishRetainsFinalsOutsideFrozenWindowAndTail() {
        val history = TelemetryHistory(receiverSupported = true)
        val sender = collector()
        val receiver = collector()
        sender.event(pressure(), pressure = true)
        history.accept(TelemetryHistory.Endpoint.SENDER, sender.publish(), second)
        history.closeWindow(11 * second)
        history.accept(TelemetryHistory.Endpoint.SENDER, sender.publish(final = true), 12 * second)
        history.accept(TelemetryHistory.Endpoint.RECEIVER, receiver.publish(final = true), 12 * second)
        history.terminal(TelemetryHistory.Endpoint.SENDER, 2, true, 12 * second)
        history.terminal(TelemetryHistory.Endpoint.RECEIVER, 1, true, 12 * second)

        val result = history.finish(23 * second)!!
        assertTrue(result.receiverComplete)
        assertEquals(listOf(1L, 2L), result.sender.blocks.map { it.sequence })
        assertEquals(listOf(1L), result.receiver.blocks.map { it.sequence })
        val report = org.screencopy.receiver.protocol.StrictJson.decode(TelemetryReport.encode(result))
        assertTrue(TelemetryReport.validate(report))
        assertTrue(report.getJSONObject("receiver").getJSONArray("blocks")
            .getJSONArray(0).getJSONObject(1).getBoolean("final"))
    }

    @Test fun evictedFinalsSurviveRetentionAndReportBudgets() {
        val history = TelemetryHistory(receiverSupported = true)
        val sender = collector()
        val receiver = collector()
        for (collector in listOf(sender, receiver)) {
            repeat(64) {
                collector.state(it, TelemetryRow(1, 2, 1, 1, 1, 1, List(15) { Long.MAX_VALUE }))
            }
        }
        sender.event(pressure(), pressure = true)
        receiver.event(pressure(), pressure = true)
        val senderBatch = org.screencopy.receiver.protocol.StrictJson.decode(sender.publish())
        val receiverBatch = org.screencopy.receiver.protocol.StrictJson.decode(receiver.publish())
        fun payload(batch: org.json.JSONObject, sequence: Int, final: Boolean = false): ByteArray =
            batch.put("seq", "$sequence").put("final", final).toString().toByteArray(Charsets.UTF_8)
        repeat(220) { index ->
            history.accept(TelemetryHistory.Endpoint.SENDER, payload(senderBatch, index + 1), second + index)
            history.accept(TelemetryHistory.Endpoint.RECEIVER, payload(receiverBatch, index + 1), second + index)
        }
        history.closeWindow(11 * second)
        history.accept(TelemetryHistory.Endpoint.SENDER, payload(senderBatch, 221, final = true), 12 * second)
        history.accept(TelemetryHistory.Endpoint.RECEIVER, payload(receiverBatch, 221, final = true), 12 * second)
        history.terminal(TelemetryHistory.Endpoint.SENDER, 221, true, 12 * second)
        history.terminal(TelemetryHistory.Endpoint.RECEIVER, 221, true, 12 * second)
        repeat(220) { index ->
            history.accept(TelemetryHistory.Endpoint.SENDER, payload(senderBatch, index + 222), 73 * second + index)
            history.accept(TelemetryHistory.Endpoint.RECEIVER, payload(receiverBatch, index + 222), 73 * second + index)
        }
        assertTrue(history.retainedPayloadBytes() <= TelemetryHistory.RETAINED_BYTES)
        val result = history.finish(74 * second)!!
        assertTrue(result.receiverComplete)
        for (source in listOf(result.sender, result.receiver)) {
            assertEquals(1, source.blocks.count { it.sequence == 221L })
            assertTrue(source.truncated > 0)
        }
        val bytes = TelemetryReport.encode(result)
        assertTrue(bytes.size <= TelemetryReport.MAX_BYTES)
        val report = org.screencopy.receiver.protocol.StrictJson.decode(bytes)
        assertTrue(TelemetryReport.validate(report))
        for (endpoint in listOf("sender", "receiver")) {
            val source = report.getJSONObject(endpoint)
            assertTrue("$endpoint omitted=${source.getString("reportOmitted")}, " +
                "senderBytes=${result.sender.blocks.sumOf { it.size }}, " +
                "receiverBytes=${result.receiver.blocks.sumOf { it.size }}, reportBytes=${bytes.size}",
                source.getString("reportOmitted").toLong() > 0)
            val blocks = source.getJSONArray("blocks")
            assertEquals(1, (0 until blocks.length()).count {
                val batch = blocks.getJSONArray(it).getJSONObject(1)
                batch.getString("seq") == "221" && batch.getBoolean("final")
            })
        }
    }

    @Test fun boundedRetainedPayloadUnderExcessPublications() {
        val history = TelemetryHistory(receiverSupported = true)
        val sender = collector()
        sender.event(pressure(), pressure = true)
        repeat(64) {
            sender.state(it, TelemetryRow(1, 2, 1, 1, 1, 1, List(15) { Long.MAX_VALUE }))
        }
        repeat(200) { i ->
            history.accept(TelemetryHistory.Endpoint.SENDER, sender.publish(), second + i)
        }
        assertTrue(history.retainedPayloadBytes() <= TelemetryHistory.RETAINED_BYTES)
        assertTrue(history.initial()!!.sender.truncated > 0)
        val report = TelemetryReport.encode(history.finish(second + 200)!!)
        assertTrue(report.size <= TelemetryReport.MAX_BYTES)
        org.screencopy.receiver.protocol.StrictJson.decode(report)
    }
}

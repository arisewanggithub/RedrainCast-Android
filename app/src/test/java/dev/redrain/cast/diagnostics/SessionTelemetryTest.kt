package dev.redrain.cast.diagnostics

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.screencopy.receiver.protocol.ProtocolException
import org.screencopy.receiver.protocol.StrictJson

class SessionTelemetryTest {
    private fun row(value: Long = 1, detail: List<Long> = emptyList()) =
        TelemetryRow(1, 2, 1, 1, 100, value, detail)

    @Test fun boundedPublicationRetainsFirstCausesAcrossOverflow() {
        val collector = SessionTelemetryCollector { 123 }
        repeat(64) { collector.state(it, row(it.toLong(), List(15) { Long.MIN_VALUE })) }
        repeat(128) { collector.event(row(it.toLong(), List(15) { Long.MAX_VALUE })) }
        collector.event(row(999).copy(stage = DiagnosticStage.PRESSURE.ordinal, detail = listOf(0, 0, 0, 0, 1)), pressure = true)
        collector.event(row(1000).copy(stage = DiagnosticStage.FAILED.ordinal, detail = listOf(0, 0, 0, 0, 1)), failure = true)
        val bytes = collector.publish()
        assertTrue(bytes.size <= 8192)
        val batch = SessionTelemetryProtocol.parse(bytes)
        assertEquals(999, batch.firstPressure!!.value)
        assertEquals(1000, batch.firstFailure!!.value)
        assertTrue(batch.dropped >= 66)
        assertTrue(batch.omitted > 0)
        assertTrue(batch.state.size <= 64)
        assertTrue(batch.events.isNotEmpty())
        assertTrue(batch.events.size <= 64)
        val next = SessionTelemetryProtocol.parse(collector.publish(final = true))
        assertEquals(2, next.sequence)
        assertTrue(next.final)
        assertEquals(999, next.firstPressure!!.value)
        assertTrue(next.omitted >= batch.omitted)
    }

    @Test fun coalescingUnsentAndDefensiveCopiesAreAccounted() {
        val collector = SessionTelemetryCollector { 3 }
        val detail = mutableListOf(4L, 0, 0, 0, 1)
        val value = row(detail = detail).copy(stage = DiagnosticStage.PRESSURE.ordinal)
        collector.state(0, value)
        collector.state(0, value)
        collector.event(value, pressure = true)
        detail[0] = 9
        collector.publicationUnsent()
        val result = SessionTelemetryProtocol.parse(collector.publish())
        assertEquals(1, result.coalesced)
        assertEquals(1, result.unsent)
        assertEquals(listOf(4L, 0, 0, 0, 1), result.state.single().detail)
        assertEquals(listOf(4L, 0, 0, 0, 1), result.firstPressure!!.detail)
    }

    @Test fun latestCleanupStateSurvivesCrowdedFinalPublication() {
        val collector = SessionTelemetryCollector { 3 }
        repeat(63) { collector.state(it, row(it.toLong(), List(20) { Long.MIN_VALUE })) }
        collector.state(63, row(1000))
        collector.state(63, row(1001))
        val final = SessionTelemetryProtocol.parse(collector.publish(final = true))
        assertTrue(final.omitted > 0)
        assertTrue(final.state.any { it.value == 1001L })
    }

    @Test fun staleOperationCompletionCannotClearNewGeneration() {
        val collector = SessionTelemetryCollector { 123 }
        fun op(stage: DiagnosticStage, generation: Int, id: Long) =
            TelemetryRow(DiagnosticKind.AUDIO_OUTPUT.ordinal + 1, stage.ordinal, 2, generation, 100, 1_000_000,
                listOf(64, -1, 0, DiagnosticPoint.WRITE.ordinal.toLong(), id))
        collector.operation(0, op(DiagnosticStage.BEGIN, 1, 1))
        collector.operation(0, op(DiagnosticStage.BEGIN, 2, 2))
        collector.operation(0, op(DiagnosticStage.COMPLETE, 1, 1))
        assertEquals(2, SessionTelemetryProtocol.parse(collector.publish()).state.single().generation)
        collector.operation(0, op(DiagnosticStage.COMPLETE, 2, 2))
        assertTrue(SessionTelemetryProtocol.parse(collector.publish()).state.isEmpty())
    }

    @Test fun operationCompletionMatchesIdAndKnownGeneration() {
        val collector = SessionTelemetryCollector { 123 }
        fun op(stage: DiagnosticStage, generation: Int, id: Long) =
            TelemetryRow(DiagnosticKind.TRANSPORT_HEALTH.ordinal + 1, stage.ordinal, 1, generation, 100, -1,
                listOf(-1, -1, 0, DiagnosticPoint.READ.ordinal.toLong(), id))
        fun inFlight() = SessionTelemetryProtocol.parse(collector.publish()).state

        collector.operation(10, op(DiagnosticStage.BEGIN, 0, 10), unknownGeneration = true)
        collector.operation(10, op(DiagnosticStage.COMPLETE, 3, 9))
        assertEquals(10, inFlight().single().detail[4])
        collector.operation(10, op(DiagnosticStage.COMPLETE, 3, 10))
        assertTrue(inFlight().isEmpty())
        collector.operation(10, op(DiagnosticStage.BEGIN, 0, 11), unknownGeneration = true)
        collector.operation(10, op(DiagnosticStage.FAILED, 4, 11))
        assertTrue(inFlight().isEmpty())
        collector.operation(10, op(DiagnosticStage.BEGIN, 2, 12))
        collector.operation(10, op(DiagnosticStage.COMPLETE, 3, 12))
        assertEquals(2, inFlight().single().generation)
        collector.operation(10, op(DiagnosticStage.COMPLETE, 2, 12))
        assertTrue(inFlight().isEmpty())
        collector.operation(10, op(DiagnosticStage.BEGIN, 0, 13))
        collector.operation(10, op(DiagnosticStage.COMPLETE, 1, 13))
        assertEquals(0, inFlight().single().generation)
    }

    @Test fun staleRecoveryCompletionCannotClearNewerTrackOperation() {
        val collector = SessionTelemetryCollector { 123 }
        fun op(stage: DiagnosticStage, generation: Int, point: DiagnosticPoint, id: Long) =
            TelemetryRow(DiagnosticKind.RECOVERY.ordinal + 1, stage.ordinal, 1, generation, id * 100, 0,
                listOf(0, 0, 0, point.ordinal.toLong(), id))
        val retire = op(DiagnosticStage.BEGIN, 1, DiagnosticPoint.RECOVERY_RETIRE, 10)
        val construct = op(DiagnosticStage.BEGIN, 2, DiagnosticPoint.RECOVERY_CONSTRUCT, 11)
        collector.operation(6, retire)
        collector.operation(6, construct)
        collector.operation(6, retire.copy(stage = DiagnosticStage.COMPLETE.ordinal))
        collector.operation(6, construct.copy(stage = DiagnosticStage.FAILED.ordinal, generation = 1))
        assertEquals(construct, SessionTelemetryProtocol.parse(collector.publish()).state.single())
        collector.operation(6, construct.copy(stage = DiagnosticStage.COMPLETE.ordinal))
        assertTrue(SessionTelemetryProtocol.parse(collector.publish()).state.isEmpty())
    }

    @Test fun futureCapabilitySchemasAreUnsupportedButMalformedVersionsStillReject() {
        fun supported(capability: JSONObject) =
            SessionTelemetryProtocol.supported(JSONObject().put("sessionTelemetry", capability))

        assertFalse(supported(JSONObject().put("version", 2)))
        assertFalse(supported(JSONObject().put("version", 2).put("newField", JSONObject().put("mode", "batch"))
            .put("intervalMs", "different schema")))
        for (invalid in listOf<Any>("2", 2.0, 0, -1, 2147483648L, JSONObject.NULL)) {
            assertThrows(ProtocolException::class.java) {
                supported(JSONObject().put("version", invalid).put("newField", true))
            }
        }
        assertThrows(ProtocolException::class.java) { supported(JSONObject().put("newField", true)) }
        for (invalid in listOf(
            JSONObject().put("version", 1),
            SessionTelemetryProtocol.capability().put("extra", true),
            SessionTelemetryProtocol.capability().put("intervalMs", 0),
            SessionTelemetryProtocol.capability().put("maxBatchBytes", -1),
            SessionTelemetryProtocol.capability().put("maxBatchBytes", "8192"))) {
            assertThrows(ProtocolException::class.java) { supported(invalid) }
        }
        assertThrows(ProtocolException::class.java) {
            SessionTelemetryProtocol.supported(StrictJson.decode(
                """{"sessionTelemetry":{"version":2,"version":2,"newField":true}}""".toByteArray()))
        }
    }

    @Test fun capabilityAndStrictPayloadValidation() {
        assertFalse(SessionTelemetryProtocol.supported(JSONObject()))
        assertTrue(SessionTelemetryProtocol.supported(JSONObject().put("sessionTelemetry",
            SessionTelemetryProtocol.capability())))
        assertFalse(SessionTelemetryProtocol.supported(JSONObject().put("sessionTelemetry",
            SessionTelemetryProtocol.capability().put("version", 2))))
        assertThrows(ProtocolException::class.java) {
            SessionTelemetryProtocol.supported(JSONObject().put("sessionTelemetry",
                SessionTelemetryProtocol.capability().put("intervalMs", "1000")))
        }
        val valid = SessionTelemetryCollector { 123 }.publish().toString(Charsets.UTF_8)
        assertThrows(ProtocolException::class.java) {
            SessionTelemetryProtocol.parse(valid.replace("\"version\":1", "\"version\":1,\"version\":1")
                .toByteArray())
        }
        assertThrows(ProtocolException::class.java) {
            SessionTelemetryProtocol.parse(ByteArray(8193))
        }
        assertThrows(ProtocolException::class.java) {
            SessionTelemetryProtocol.parse(byteArrayOf(0xc3.toByte(), 0x28))
        }
        val paddedCollector = SessionTelemetryCollector { 123 }
        paddedCollector.event(row())
        val padded = paddedCollector.publish().toString(Charsets.UTF_8)
            .replace("\"events\":[[", "\"events\":[[" + " ".repeat(512))
        assertTrue(padded.contains(" ".repeat(512)))
        assertThrows(ProtocolException::class.java) {
            SessionTelemetryProtocol.parse(padded.toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) { row(detail = List(21) { 0 }) }
    }

    @Test fun anchorsRejectWrongStageKindShapeAndRole() {
        fun encoded(anchor: TelemetryRow, pressure: Boolean): ByteArray {
            val collector = SessionTelemetryCollector { 123 }
            collector.event(anchor, pressure = pressure, failure = !pressure)
            return collector.publish()
        }
        val valid = TelemetryRow(DiagnosticKind.RECOVERY.ordinal + 1, DiagnosticStage.PRESSURE.ordinal,
            1, 1, 100, 8, listOf(0, 1, 2, 3, 4, 5, 6, 7, 8))
        assertEquals(valid, SessionTelemetryProtocol.parse(encoded(valid, true), "receiver").firstPressure)
        for (invalid in listOf(valid.copy(stage = DiagnosticStage.CONFIG.ordinal),
            valid.copy(kind = DiagnosticKind.SESSION.ordinal + 1),
            valid.copy(detail = listOf(0, 1, 2)))) {
            assertThrows(ProtocolException::class.java) {
                SessionTelemetryProtocol.parse(encoded(invalid, true), "receiver")
            }
        }
        assertThrows(ProtocolException::class.java) {
            SessionTelemetryProtocol.parse(encoded(valid, true), "sender")
        }
        assertThrows(ProtocolException::class.java) {
            SessionTelemetryProtocol.parse(encoded(valid.copy(stage = DiagnosticStage.FAILED.ordinal), false), "receiver")
        }
    }

    @Test fun exportPressureDictionaryMatchesProductionTelemetryRows() {
        val sender = TelemetryRow(DiagnosticKind.RECOVERY.ordinal + 1, DiagnosticStage.PRESSURE.ordinal,
            1, 1, 100, 99, (0L..18L).toList())
        val receiver = sender.copy(track = 2, detail = (0L..8L).toList())
        val history = TelemetryHistory(true)
        val senderCollector = SessionTelemetryCollector { 123 }
        val receiverCollector = SessionTelemetryCollector { 123 }
        senderCollector.event(sender, pressure = true)
        receiverCollector.event(receiver, pressure = true)
        history.accept(TelemetryHistory.Endpoint.SENDER, senderCollector.publish(), 123)
        history.accept(TelemetryHistory.Endpoint.RECEIVER, receiverCollector.publish(), 123)
        val report = TelemetryReport.encode(checkNotNull(history.finish(123))).toString(Charsets.UTF_8)
        val exported = JSONObject(IncidentSummary.export(report))
        val vocabulary = exported.getJSONObject("vocabulary")
        val layouts = vocabulary.getJSONArray("telemetryPressureLayouts")
        for ((index, row) in listOf(sender, receiver).withIndex()) {
            val layout = layouts.getJSONObject(index)
            assertEquals(row.kind, layout.getInt("kind"))
            assertEquals(row.stage, layout.getInt("stage"))
            val fields = layout.getJSONArray("details")
            assertEquals(row.detail.size, fields.length())
            assertTrue(fields.getString(0).startsWith("limit("))
            val role = if (index == 0) "sender" else "receiver"
            val encoded = exported.getJSONObject(role).getJSONArray("blocks").getJSONArray(0)
                .getJSONObject(1).getJSONArray("firstPressure").getJSONArray(6)
            val decoded = (0 until fields.length()).associate { fields.getString(it) to encoded.getString(it).toLong() }
            if (index == 0) {
                assertEquals(1L, decoded["ptsUs"])
                assertEquals(2L, decoded["count"])
                assertEquals(18L, decoded["lastWriteMs"])
            } else {
                assertEquals(1L, decoded["queuedPackets"])
                assertEquals(7L, decoded["maxSpanUs"])
                assertEquals(8L, decoded["waitedMs"])
            }
        }
        val legacy = vocabulary.getJSONArray("eventLayouts")
        assertEquals(17, legacy.getJSONObject(0).getInt("detailCount"))
        assertEquals(8, legacy.getJSONObject(1).getInt("detailCount"))
    }
}

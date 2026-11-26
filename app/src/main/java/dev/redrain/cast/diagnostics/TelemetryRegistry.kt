package dev.redrain.cast.diagnostics

import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReference

/** Session-scoped, bounded producer admission; serialization stays on the telemetry workers. */
internal object TelemetryRegistry {
    private data class Sink(
        val session: Long,
        val owner: Any,
        val state: (Int, TelemetryRow) -> Unit,
        val operation: (Int, TelemetryRow, Boolean) -> Unit,
        val event: (TelemetryRow, Boolean, Boolean, Int?) -> Unit
    ) {
        val sampled = AtomicLongArray(64)
        fun sample(slot: Int): Boolean {
            val now = System.nanoTime().coerceAtLeast(1)
            val last = sampled.get(slot)
            return (last == 0L || now - last >= 1_000_000_000L) && sampled.compareAndSet(slot, last, now)
        }
    }
    private val sender = AtomicReference<Sink?>()
    private val receiver = AtomicReference<Sink?>()

    fun beginSender(session: Long, owner: SenderTelemetrySession) {
        sender.set(Sink(session, owner, owner::state, owner::operation, owner::event))
    }

    fun beginReceiver(session: Long, owner: ReceiverTelemetrySession) {
        receiver.set(Sink(session, owner, owner::state, owner::operation, owner::event))
        causal(session, CausalEvidence.Stage.BUILD, 0,
            dev.redrain.cast.BuildConfig.VERSION_CODE.toLong(), -1, CausalEvidence.LAYOUT)
    }

    fun causal(session: Long, stage: CausalEvidence.Stage, generation: Int, vararg values: Long,
               atNs: Long = System.nanoTime()) {
        val owner = find("receiver", session)?.owner as? ReceiverTelemetrySession ?: return
        if (generation < 0) return
        owner.causal(TelemetryRow(CausalEvidence.KIND, stage.ordinal, 2, generation,
            atNs.coerceAtLeast(0), 0, values.toList()))
    }

    fun freezeReceiver(session: Long, generation: Int) {
        (find("receiver", session)?.owner as? ReceiverTelemetrySession)
            ?.freezeCausal(generation, System.nanoTime().coerceAtLeast(0))
    }

    fun end(role: String, owner: Any?) {
        if (owner == null) return
        val slot = slot(role) ?: return
        val current = slot.get()
        if (current?.owner === owner) slot.compareAndSet(current, null)
    }

    fun has(role: String, session: Long): Boolean = find(role, session) != null

    fun evidence(role: String, session: Long, track: Int, generation: Int,
                 stage: IncidentEvidence.Stage, vararg values: Long) {
        val stateIndex = when (stage) {
            IncidentEvidence.Stage.QUEUE_STATE -> if (track in 0..2) 48 + track else null
            IncidentEvidence.Stage.AUDIO_STATE -> if (track == 2) 51 else null
            IncidentEvidence.Stage.ADMISSION_BEGIN -> if (track in 1..2) 51 + track else null
            IncidentEvidence.Stage.ADMISSION_END -> if (track in 1..2) 53 + track else null
            IncidentEvidence.Stage.WORKER_BEGIN -> if (track in 1..2) 55 + track else null
            IncidentEvidence.Stage.WORKER_END -> if (track in 1..2) 57 + track else null
            else -> null
        }
        if (stage in sampledEvidence) {
            val sink = find(role, session) ?: return
            val index = stateIndex ?: return
            if (generation < 0) return
            if (stage == IncidentEvidence.Stage.QUEUE_STATE || sink.sample(index)) sink.state(index, row(TelemetryRow.EVIDENCE_KIND, stage.ordinal,
                track, generation, 0, values.take(20)))
            return
        }
        event(role, session, TelemetryRow.EVIDENCE_KIND, stage.ordinal, track, generation,
            detail = values.take(20))
    }

    fun video(role: String, session: Long, generation: Int, stage: DiagnosticVideoStage,
              pts: Long, bytes: Long) {
        val sink = find(role, session) ?: return
        if (generation < 0) return
        val index = stage.ordinal
        if (sink.sample(index)) sink.state(index, row(0, stage.ordinal, 1, generation, pts, listOf(bytes)))
    }

    fun observe(role: String, session: Long, kind: DiagnosticKind, stage: DiagnosticStage,
                track: Int, generation: Int, operation: Long, pts: Long, bytes: Long, cutoff: Long,
                reason: DiagnosticReason, point: DiagnosticPoint) {
        val sink = find(role, session) ?: return
        if (track !in 0..2 || generation < 0) return
        val critical = stage == DiagnosticStage.PRESSURE || stage == DiagnosticStage.FAILED ||
            kind == DiagnosticKind.RECOVERY && (stage == DiagnosticStage.RESET ||
                stage == DiagnosticStage.READY || stage == DiagnosticStage.COMPLETE)
        val index = observationSlot(kind, point, track)
        val operationIndex = operationSlot(role, kind, point, track)
        if (operationIndex != null && operation > 0 &&
            (stage == DiagnosticStage.BEGIN || stage == DiagnosticStage.COMPLETE || stage == DiagnosticStage.FAILED)) {
            // Media read starts before the packet header reveals its generation.
            sink.operation(operationIndex, row(kind.ordinal + 1, stage.ordinal, track, generation, pts,
                listOf(bytes, cutoff, reason.ordinal.toLong(), point.ordinal.toLong(), operation)),
                role == "receiver" && generation == 0 && stage == DiagnosticStage.BEGIN &&
                    point == DiagnosticPoint.READ && track in 1..2 &&
                    (kind == DiagnosticKind.TRANSPORT_HEALTH ||
                        kind == DiagnosticKind.AUDIO_TRANSPORT && track == 2))
        }
        val sample = index != null && stage != DiagnosticStage.BEGIN && sink.sample(index)
        if (sample || critical) {
            val value = row(kind.ordinal + 1, stage.ordinal, track, generation, pts,
                listOf(bytes, cutoff, reason.ordinal.toLong(), point.ordinal.toLong(), operation))
            if (sample) sink.state(checkNotNull(index), value)
            if (critical) sink.event(value, stage == DiagnosticStage.PRESSURE,
                stage == DiagnosticStage.FAILED && (kind == DiagnosticKind.SESSION || kind == DiagnosticKind.RECOVERY), null)
        }
    }

    fun event(role: String, session: Long, kind: Int, stage: Int, track: Int, generation: Int,
              value: Long = 0, detail: List<Long> = emptyList(), pressure: Boolean = false,
              failure: Boolean = false, causalGeneration: Int? = null) {
        val sink = find(role, session) ?: return
        if (track !in 0..2 || generation < 0 || causalGeneration != null && causalGeneration < 0) return
        sink.event(row(kind, stage, track, generation, value, detail), pressure, failure, causalGeneration)
    }

    fun state(role: String, session: Long, slot: Int, kind: Int, stage: Int, track: Int,
              generation: Int, value: Long, detail: List<Long>) {
        val sink = find(role, session) ?: return
        if (track !in 0..2 || generation < 0) return
        sink.state(slot, row(kind, stage, track, generation, value, detail))
    }

    fun trackConfiguration(role: String, session: Long, track: Int, generation: Int, codec: String,
                           width: Int, height: Int, sampleRate: Int, channels: Int) {
        event(role, session, DiagnosticKind.SESSION.ordinal + 1, DiagnosticStage.CONFIG.ordinal,
            track, generation, detail = listOf(codec(codec), width.toLong(), height.toLong(),
                sampleRate.toLong(), channels.toLong()))
    }

    fun sessionConfiguration(role: String, session: Long, videoCodec: String, audioCodec: String?,
                             fps: Int, videoBitrate: Int, audioBitrate: Int?) {
        event(role, session, DiagnosticKind.SESSION.ordinal + 1, DiagnosticStage.CONFIG.ordinal, 0, 0,
            detail = listOf(codec(videoCodec), codec(audioCodec), fps.toLong(), videoBitrate.toLong(),
                audioBitrate?.toLong() ?: -1))
    }

    private fun codec(value: String?): Long = when (value) {
        "h264" -> 1; "h265" -> 2; "av1" -> 3; "raw", "pcm" -> 4; "opus" -> 5; "aac" -> 6; else -> 0
    }
    private fun row(kind: Int, stage: Int, track: Int, generation: Int, value: Long, detail: List<Long>) =
        TelemetryRow(kind, stage, track, generation, System.nanoTime().coerceAtLeast(0), value, detail)
    private val sampledEvidence = setOf(
        IncidentEvidence.Stage.QUEUE_STATE, IncidentEvidence.Stage.AUDIO_STATE,
        IncidentEvidence.Stage.ADMISSION_BEGIN, IncidentEvidence.Stage.ADMISSION_END,
        IncidentEvidence.Stage.WORKER_BEGIN, IncidentEvidence.Stage.WORKER_END
    )

    private fun operationSlot(role: String, kind: DiagnosticKind, point: DiagnosticPoint, track: Int): Int? = when (kind) {
        // Receiver recovery is serialized per track. These slots otherwise belong only to sender producers.
        DiagnosticKind.RECOVERY -> if (role == "receiver" && track in 1..2 &&
            point in setOf(DiagnosticPoint.RECOVERY_RETIRE, DiagnosticPoint.RECOVERY_CONSTRUCT))
            if (track == 1) 6 else 8 else null
        DiagnosticKind.AUDIO_OUTPUT -> if (track != 2) null else when (point) {
            DiagnosticPoint.WRITE -> 0
            DiagnosticPoint.DECODE -> 1
            DiagnosticPoint.RESAMPLE -> 2
            DiagnosticPoint.COMPENSATE -> 3
            DiagnosticPoint.TIMING -> 4
            DiagnosticPoint.AUDIO_START_WAIT -> 5
            else -> null
        }
        DiagnosticKind.AUDIO_CAPTURE -> if (role == "sender" && track == 2 && point == DiagnosticPoint.READ) 6 else null
        DiagnosticKind.AUDIO_TRANSPORT -> if (track != 2) null else when (point) {
            DiagnosticPoint.READ -> 7
            DiagnosticPoint.WRITE -> if (role == "sender") 8 else null
            else -> null
        }
        DiagnosticKind.TRANSPORT_HEALTH -> when (point) {
            DiagnosticPoint.READ -> 9 + track
            DiagnosticPoint.WRITE -> 12 + track
            else -> null
        }
        else -> null
    }

    // Slots 0..15 are video stages, 48..59 sampled evidence, 61..62 queue metrics,
    // 60 is sender outcome / receiver audio-start decision, and 63 sender cleanup failure.
    // Unknown sources are not sampled.
    private fun observationSlot(kind: DiagnosticKind, point: DiagnosticPoint, track: Int): Int? = when (kind) {
        DiagnosticKind.SESSION -> 16 + track
        DiagnosticKind.RECOVERY -> when (point) {
            DiagnosticPoint.UNSPECIFIED -> 19 + track
            DiagnosticPoint.RECOVERY_RETIRE -> if (track in 1..2) 21 + track else null
            DiagnosticPoint.RECOVERY_CONSTRUCT -> if (track in 1..2) 23 + track else null
            DiagnosticPoint.RECOVERY -> if (track in 1..2) 25 + track else null
            else -> null
        }
        DiagnosticKind.AUDIO_CAPTURE -> if (track != 2) null else when (point) {
            DiagnosticPoint.READ -> 28
            DiagnosticPoint.UNSPECIFIED -> 29
            else -> null
        }
        DiagnosticKind.AUDIO_TRANSPORT -> if (track != 2) null else when (point) {
            DiagnosticPoint.READ -> 30
            DiagnosticPoint.WRITE -> 31
            else -> null
        }
        DiagnosticKind.AUDIO_QUEUE -> if (track != 2) null else when (point) {
            DiagnosticPoint.UNSPECIFIED -> 32
            DiagnosticPoint.QUEUE -> 33
            else -> null
        }
        DiagnosticKind.AUDIO_OUTPUT -> if (track != 2) null else when (point) {
            DiagnosticPoint.WRITE -> 34
            DiagnosticPoint.DECODE -> 35
            DiagnosticPoint.RESAMPLE -> 36
            DiagnosticPoint.COMPENSATE -> 37
            DiagnosticPoint.TIMING -> 38
            DiagnosticPoint.AUDIO_START_WAIT -> 39
            else -> null
        }
        DiagnosticKind.VIDEO_PROGRESS -> if (track == 1 && point == DiagnosticPoint.UNSPECIFIED) 40 else null
        DiagnosticKind.TRANSPORT_HEALTH -> when (point) {
            DiagnosticPoint.READ -> 41 + track
            DiagnosticPoint.WRITE -> 44 + track
            else -> null
        }
    }
    private fun slot(role: String) = when (role) { "sender" -> sender; "receiver" -> receiver; else -> null }
    private fun find(role: String, session: Long) = slot(role)?.get()?.takeIf { it.session == session }
}

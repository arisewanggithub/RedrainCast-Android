package dev.redrain.cast.diagnostics

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicReferenceArray

/** Telemetry v2 only. Values identify provenance, not another diagnostic vocabulary. */
internal object CausalEvidence {
    val KIND = TelemetryRow.EVIDENCE_KIND + 1
    const val LAYOUT = 1L
    enum class Stage {
        BUILD, WORKER, SERVICE, OUTPUT, INPUT, MAPPING, START, TRIM, CAPTURE
    }
    enum class Mapping { VIDEO_ANCHOR, PREPARE, RECOVERY_BEGIN, RECOVERY_END, VIDEO_RELEASE, AUDIO_ATTACH, PACKET, DETACH, ANCHOR }
    enum class Output { CALL_BEGIN, CALL_END, CALL_FAILED, POSITION_ENTER, POSITION_LOCKED, TIMESTAMP_BEGIN, TIMESTAMP_END, HEAD_BEGIN, HEAD_END, UNDERRUN_BEGIN, UNDERRUN_END }
    val phases = listOf("STEP", "STARTUP_CHECK", "STARTUP_OUTPUT_WAIT", "OPUS_DECODE", "PCM_DECODE",
        "AAC_INPUT", "AAC_FORMAT", "AAC_OUTPUT", "STARTUP_TRIM", "STARTUP_SYNC", "OUTPUT_START",
        "SOURCE_MAP", "OUTPUT_CLOCK", "OUTPUT_WRITE", "PCM_RESAMPLE", "PCM_COMPENSATION", "UNDERRUN_CHECK",
        "THREAD_START", "THREAD_PREPARE")

    fun fields(stage: Int): List<String> = when (Stage.entries[stage]) {
        Stage.BUILD -> listOf("versionCode", "sourceRevision;-1 unavailable", "layoutRevision")
        Stage.WORKER -> listOf("phase", "phaseBeginNs", "stepBeginNs", "stepEndNs",
            "lastDequeueNs", "lastDequeuePtsUs", "firstSlowBeginNs", "firstSlowEndNs",
            "firstSlowWallNs", "firstSlowCpuNs", "firstSlowKind", "maxSlowBeginNs", "maxSlowEndNs",
            "maxSlowWallNs", "maxSlowCpuNs", "maxSlowKind", "slowIntervals")
        Stage.SERVICE -> listOf("windowBeginNs", "steps", "slowStepsGe5ms", "maxRefillGapNs",
            "gapPreviousPaddingUs", "gapPcmFrames", "gapArrivalAgeUs", "minimumPaddingUs",
            "minimumPaddingPcmFrames", "zeroWrites", "silenceFrames", "silenceEpisodes",
            "starvationSamples", "queuedStarvationSamples", "totalUnderruns", "windowUnderruns",
            "firstAnomalyNs", "counterResets")
        Stage.OUTPUT -> listOf("boundary", "operationId", "point", "callBeginNs", "lastCompleteNs",
            "lastDurationNs", "maxDurationNs", "zeroWrites", "partialWrites", "firstZeroNs",
            "firstPartialNs", "outputObservedNs", "submittedFrames", "consumedFrames", "presentedFrames",
            "timestampAgeUs", "clockSource", "underruns", "lastOutputProgressNs", "lastCompletePoint")
        Stage.INPUT -> listOf("lastArrivalNs", "lastArrivalPtsUs", "lastArrivalDeltaNs",
            "lastPtsDeltaUs", "expectedPacketUs", "firstDiscontinuityNs", "firstDiscontinuityStepUs",
            "maxPtsStepUs", "maxArrivalGapNs", "burstPackets", "arrivals", "lastAdmitNs",
            "lastAdmitPtsUs", "lastDequeueNs", "lastDequeuePtsUs")
        Stage.MAPPING -> listOf("reason", "oldRevision", "newRevision", "oldBasePtsUs", "oldBaseNs",
            "newBasePtsUs", "newBaseNs", "oldMode", "newMode", "oldPositionFloorUs",
            "newPositionFloorUs", "audioObservationPtsUs", "audioObservationNs", "flags", "audioObservationSequence",
            "oldAudioObservationPtsUs", "oldAudioObservationNs", "packetBeforeOffsetUs", "packetAfterOffsetUs",
            "packetCorrectionSequence;0 unavailable")
        Stage.START -> listOf("operationId", "decision", "mode", "targetPtsUs", "positionUs",
            "delayUs;int64-min unavailable", "revision", "flags", "waitedUs", "releasedVideoPtsUs",
            "rawPtsUs", "effectiveTrimCutoffUs", "trimReason;-1 none")
        Stage.TRIM -> listOf("reason:0 startup,1 recovery,2 recoveryRefresh,3 clockPrepare,4 playedAudio", "rawPtsUs",
            "trimmedPtsUs", "cutoffUs", "clockPositionUs", "rawFrames", "trimmedFrames")
        Stage.CAPTURE -> listOf("layoutRevision", "availableStageMask", "omittedLaterTransitions",
            "laterTransitionSequence", "triggerTrack")
    }

    /**
     * Eight independently timestamped checkpoints and the generation's first gate decision,
     * an immutable first-fault copy, and bounded subsequent mapping/start transitions.
     */
    class Cache {
        private data class Frozen(val generation: Int, val atNs: Long, val track: Int, val rows: List<TelemetryRow>)
        private val latest = AtomicReferenceArray<TelemetryRow?>(8)
        private val first = AtomicReference<Frozen?>()
        private val firstStart = AtomicReference<TelemetryRow?>()
        private val transitions = AtomicReferenceArray<TelemetryRow?>(6)
        private val sequence = AtomicLong()
        private val omitted = AtomicLong()

        fun record(row: TelemetryRow) {
            require(row.kind == KIND && row.stage in 0..7 && row.value == 0L)
            val immutable = row.copy(detail = row.detail.toList())
            val index = row.stage
            while (true) {
                val old = latest.get(index)
                if (old != null && (old.generation > row.generation ||
                        old.generation == row.generation && old.atNs > row.atNs)) {
                    if (first.get() != null && row.stage in listOf(Stage.MAPPING.ordinal, Stage.START.ordinal)) {
                        sequence.incrementAndGet()
                        omitted.incrementAndGet()
                    }
                    return
                }
                if (latest.compareAndSet(index, old, immutable)) break
            }
            if (row.stage == Stage.START.ordinal) {
                while (true) {
                    val old = firstStart.get()
                    if (old != null && old.generation >= row.generation) break
                    if (firstStart.compareAndSet(old, immutable.copy(value = 4))) break
                }
            }
            if (first.get() != null && row.stage in listOf(Stage.MAPPING.ordinal, Stage.START.ordinal)) {
                sequence.incrementAndGet()
                val value = immutable.copy(value = 2)
                val slot = if (row.stage == Stage.MAPPING.ordinal) 0 else 2
                transitions.compareAndSet(slot, null, value)
                if (row.stage == Stage.MAPPING.ordinal) {
                    val reasonSlot = when (row.detail.firstOrNull()) {
                        Mapping.DETACH.ordinal.toLong() -> 4
                        Mapping.PREPARE.ordinal.toLong() -> 5
                        else -> null
                    }
                    reasonSlot?.let { transitions.compareAndSet(it, null, value) }
                }
                while (true) {
                    val previous = transitions.get(slot + 1)
                    if (previous != null && previous.atNs > value.atNs) { omitted.incrementAndGet(); break }
                    if (transitions.compareAndSet(slot + 1, previous, value)) {
                        if (previous != null && previous != transitions.get(slot) &&
                            previous != transitions.get(4) && previous != transitions.get(5))
                            omitted.incrementAndGet()
                        break
                    }
                }
            }
        }

        fun freeze(generation: Int, atNs: Long, track: Int = 2) {
            if (first.get() != null) return
            val rows = (0 until latest.length()).mapNotNull { latest.get(it)?.copy(value = 1) } +
                listOfNotNull(firstStart.get())
            first.compareAndSet(null, Frozen(generation, atNs, track, rows))
        }

        fun rows(): List<TelemetryRow> {
            val frozen = first.get()
            val checkpoints = frozen?.rows ?: ((0 until latest.length()).mapNotNull { latest.get(it) } +
                listOfNotNull(firstStart.get()))
            if (checkpoints.isEmpty()) return emptyList()
            val later = (0 until transitions.length()).mapNotNull { transitions.get(it) }
                .distinct().sortedBy { it.atNs }
            val mask = checkpoints.fold(0L) { mask, row -> mask or (1L shl row.stage) }
            return checkpoints + later + TelemetryRow(KIND, Stage.CAPTURE.ordinal, 2,
                frozen?.generation ?: 0, frozen?.atNs ?: 0, 3,
                listOf(LAYOUT, mask, omitted.get(), sequence.get(), frozen?.track?.toLong() ?: -1))
        }
    }
}

package dev.redrain.cast.diagnostics

import org.json.JSONArray
import org.json.JSONObject

internal object IncidentSummary {
    private const val UNKNOWNS = "Unknowns: peer clocks are not synchronized; absent stages are not proof of non-execution. " +
        "No inference of physical display, radio, or network root cause. Cached state is timestamped, not a live resource inspection."
    fun export(content: String): String {
        val bundle = JSONObject(content)
        bundle.put("summary", text(bundle))
        bundle.put("vocabulary", JSONObject()
            .put("lifecycleStages", JSONArray(IncidentEvidence.Stage.entries.map { it.name }))
            .put("operationKinds", JSONArray(DiagnosticKind.entries.map { it.name }))
            .put("eventKinds", JSONArray(listOf("VIDEO") + DiagnosticKind.entries.map { it.name } + "QUEUE"))
            .put("operationPoints", JSONArray(DiagnosticPoint.entries.map { it.name }))
            .put("stages", JSONArray(DiagnosticStage.entries.map { it.name }))
            .put("reasons", JSONArray(DiagnosticReason.entries.map { it.name }))
            .put("zero", JSONArray(listOf("UNUSED")))
            .put("videoStages", JSONArray(DiagnosticVideoStage.entries.map { it.name }))
            .put("audioClockSources", JSONArray(org.screencopy.receiver.media.AudioClockSource.entries.map { it.name }))
            .put("audioStartDecisions", JSONArray(listOf("NOT_WAITING", "WAIT_TARGET", "RETRY_PREROLL",
                "RETRY_STALE_RECOVERY", "READY", "CANCELLED", "FAILED")))
            .put("audioStartClockModes", JSONArray(listOf("UNAVAILABLE", "MONOTONIC",
                "AUDIO_INITIAL", "CALLBACK_AUDIO", "CACHED_AUDIO")))
            .put("telemetryCausalLayouts", JSONArray().apply {
                CausalEvidence.Stage.entries.forEach { stage ->
                    put(JSONObject().put("kind", CausalEvidence.KIND).put("stage", stage.ordinal)
                        .put("name", stage.name).put("details", JSONArray(CausalEvidence.fields(stage.ordinal))))
                }
            })
            .put("causalProvenance", "value:0 current checkpoint,1 frozen first fault,2 bounded transition published after the latch,3 capture accounting,4 first gate decision of the checkpoint generation (also frozen at first fault). Each row has its own original observation time/generation; publication after the latch does not prove the mutation occurred after it. Not an atomic media-resource snapshot. -1 unavailable except signed START delay (int64-min unavailable) and signed packet offsets (valid only when packetCorrectionSequence>0). Wall-minus-CPU includes waits, locks and GC, not proof of scheduler starvation. Boundary entry is not proof of a blocked native call.")
            .put("causalWorkerPhases", JSONArray(CausalEvidence.phases))
            .put("causalIntervalKinds", JSONArray(listOf("STEP", "INPUT", "DECODE", "REFILL")))
            .put("causalMappingReasons", JSONArray(CausalEvidence.Mapping.entries.map { it.name }))
            .put("causalOutputBoundaries", JSONArray(CausalEvidence.Output.entries.map { it.name }))
            .put("telemetryAudioStartLayout", JSONObject().put("role", "receiver")
                .put("kind", DiagnosticKind.AUDIO_OUTPUT.ordinal + 1).put("stage", DiagnosticStage.SAMPLE.ordinal)
                .put("value", "targetSourcePtsUs;-1 before first wait")
                .put("details", JSONArray(listOf("point(AUDIO_START_WAIT)", "operationId;0 unavailable",
                    "decision(audioStartDecisions)", "clockMode(audioStartClockModes)", "clockPositionUs",
                    "delayUs;int64-min unavailable", "firstVideoPtsUs", "releasedVideoPtsUs", "waitedUs",
                    "mappingRevision", "flags:1 audioExpected,2 audioStarted,4 resumingAudio,8 recoveringAudio",
                    "outputObservedAtNs", "submittedFrames", "consumedFrames", "presentedFrames",
                    "timestampAgeUs", "outputClockSource(audioClockSources)", "underruns",
                    "lastOutputProgressNs", "queuedPackets"))))
            .put("telemetryAudioStartRetentionLayout", JSONObject().put("role", "receiver")
                .put("kind", DiagnosticKind.AUDIO_OUTPUT.ordinal + 1).put("stage", DiagnosticStage.SAMPLE.ordinal)
                .put("value", "targetSourcePtsUs;-1 before first wait")
                .put("details", JSONArray(listOf("point(AUDIO_START_WAIT)", "operationId;0 unavailable",
                    "audioObservationAgeUs;-1 unavailable", "retainedDecodedBytes", "retainedDecodedPackets",
                    "encodedQueueBytes", "encodedQueuePackets", "decisionSampledNs", "queueSampledNs",
                    "audioObservationSequence", "audioObservationNs", "audioObservationPtsUs",
                    "audioOutputSource(audioClockSources)", "nativeTimestampNs", "nativeTimestampFrame",
                    "appliedOffsetUs;int64-min unavailable", "correctionSequence;0 unavailable"))))
            .put("telemetryAudioStartRetentionLegacyLayout", JSONObject().put("role", "receiver")
                .put("kind", DiagnosticKind.AUDIO_OUTPUT.ordinal + 1).put("stage", DiagnosticStage.SAMPLE.ordinal)
                .put("value", "targetSourcePtsUs;-1 before first wait")
                .put("details", JSONArray(listOf("point(AUDIO_START_WAIT)", "operationId;0 unavailable",
                    "audioObservationAgeUs;-1 unavailable", "retainedDecodedBytes", "retainedDecodedPackets",
                    "encodedQueueBytes", "encodedQueuePackets"))))
            .put("terminalCauses", JSONArray(IncidentEvidence.Cause.entries.map { it.name }))
            .put("senderLimits", JSONArray(dev.screencopy.sender.SenderLimit.entries.map { it.name }))
            .put("receiverLimits", JSONArray(org.screencopy.receiver.media.QueueLimit.entries.map { it.name }))
            .put("senderQueues", JSONArray(dev.screencopy.sender.SenderQueue.entries.map { it.name }))
            .put("recoveryStates", JSONArray(listOf("WAIT_READY", "WAIT_SOURCE", "WAIT_PROOF", "ACTIVE")))
            .put("senderRecoveryTriggers", JSONArray(listOf("UNKNOWN", "capture", "receiver", "sender",
                "audio-preroll-capacity", "initial-audio-preroll-retired", "audio-source-window-lost",
                "audio-proof-window-capacity")))
            .put("senderPhases", JSONArray(dev.screencopy.sender.SessionStopTrace.Phase.entries.map { it.name }))
            .put("senderOrigins", JSONArray(dev.screencopy.sender.SessionOutcome.Origin.entries.map { it.name }))
            .put("receiverOrigins", JSONArray(org.screencopy.receiver.ui.FailureOrigin.entries.map { it.name }))
            .put("frameRecord", "[localNs,samples,events]")
            .put("sampleRecord", "[localNs,eventKind,stage,track,generation,ptsUs,bytes,value]")
            .put("sampleValue", "Point-specific completion: -2-operationPoint; otherwise operation ID or cutoffUs; -1 unknown. VIDEO kind uses videoStages, other kinds use stages.")
            .put("eventRecord", "[localNs,eventKind,stage,track,generation,ptsUs,value,reason,details]")
            .put("eventLayouts", eventLayouts())
            .put("telemetryPressureLayouts", JSONArray()
                .put(JSONObject().put("role", "sender").put("kind", DiagnosticKind.RECOVERY.ordinal + 1)
                    .put("stage", DiagnosticStage.PRESSURE.ordinal).put("value", "attemptedSpanUs")
                    .put("details", JSONArray(listOf("limit(senderLimits)", "ptsUs", "count", "countCap", "bytes",
                        "byteCap", "incomingBytes", "trackCount", "trackBytes", "queuedSpanUs", "sourceStepUs",
                        "ageMs", "waitMs", "ready", "writerQueue(senderQueues;-1 unknown)", "writerBytes",
                        "writerMs", "writerAdmittedAgeMs", "lastWriteMs"))))
                .put(JSONObject().put("role", "receiver").put("kind", DiagnosticKind.RECOVERY.ordinal + 1)
                    .put("stage", DiagnosticStage.PRESSURE.ordinal).put("value", "attemptedSpanUs")
                    .put("details", JSONArray(listOf("limit(receiverLimits)", "queuedPackets", "queuedBytes",
                        "attemptedPackets", "attemptedBytes", "maxPackets", "maxBytes", "maxSpanUs", "waitedMs")))))
            .put("lifecycleLayouts", lifecycleLayouts())
            .put("encoding", "All dictionary indices are zero-based. eventKinds uses VIDEO=0, DiagnosticKind+1, QUEUE=DiagnosticKind.size+1; operationKinds is DiagnosticKind without offset. eventLayouts describes version 1 records only; telemetryPressureLayouts describes version 2 telemetry rows. Match by role, kind, stage AND details length. Unrecognized codes/layouts are UNKNOWN, never another dictionary. Tracks: 0 session/control, 1 video, 2 audio. Decimal strings are int64; -1 is unavailable unless a field explicitly defines it.")
            .put("terminalRule", "FAILED SESSION with no details, or FAILED RECOVERY with sender 8/receiver 11 deadline details or one detail naming UNSPECIFIED/RECOVERY. Two-detail operation failures and legacy one-detail RECOVERY_RETIRE/RECOVERY_CONSTRUCT boundaries are not terminal evidence. Other one-detail points are unknown, not terminal proof.")
            .put("lifecycleRecord", "[localNs,track,generation,lifecycleStage,values]")
            .put("operationRecord", "[kind,track,point,inflightId,beginNs,lastSuccessNs,generation,ptsUs,bytes]")
            .put("telemetryRow", "[eventKind,stage,track,generation,localNs,value,details]; " +
                "eventKind ${TelemetryRow.EVIDENCE_KIND} uses lifecycleStages; 0 uses videoStages, " +
                "eventKind ${CausalEvidence.KIND} uses telemetryCausalLayouts (telemetry v2 only); " +
                "other kinds use stages. Each report block is [senderArrivalNs,batch]. " +
                "Sender SESSION SETTLED state: value is terminalCauses; details are senderPhases,senderOrigins. " +
                "It is the authoritative retained outcome, distinct from firstFailure.")
            .put("values", JSONObject()
                .put("FORMAT_OBSERVED", "codecCode,width,height,sampleRate,channels")
                .put("codecCodes", "0 unknown,1 H264,2 H265,3 AV1,4 PCM,5 Opus,6 AAC")
                .put("RECOVERY_QUEUED", "cooldownDelayMs")
                .put("RECOVERY_RUNNING", "cooldownDelayMs")
                .put("RECOVERY_CANCELLED", "cooldownDelayMs; recovery scheduler cancellation only")
                .put("DEADLINE", "originalUnresolvedStartNs,absoluteDeadlineNs,recoveryState")
                .put("DEADLINE_EXPIRED", "originalUnresolvedStartNs,observedExpiryNs,recoveryState,cutoffUs,sourcePtsUs")
                .put("RECOVERY_COMPLETE", "usableSourcePtsUs,cutoffUs; this role resolved its recovery deadline")
                .put("recoveryStates", "0 WAIT_READY,1 WAIT_SOURCE,2 WAIT_PROOF,3 ACTIVE")
                .put("QUEUE_STATE", "count,bytes,spanUs,oldestAdmissionNs; sender media adds ready,writeBeginNs,lastWriteDurationMs")
                .put("AUDIO_STATE", "decodedBufferedFrames,underruns,submittedFrames,consumedFrames,presentedFrames,timestampAgeUs,clockSource,queuedPackets,pending,waiting,lastOutputProgressNs")
                .put("FIRST_OUTPUT", "ptsUs,outputBoundaryOrdinal (videoStages for track 1, operationPoints for track 2); video means decoder release, audio means accepted source PCM, not silence")
                .put("RETIRE_BEGIN_END_CONSTRUCT_BEGIN_END", "diagnosticStageOrdinal")
                .put("TERMINAL", "eventKind,reason,value,upToNineOriginalNumericDetails; decode with role and eventLayouts; operation failures are not terminal")
                .put("TERMINAL_CAUSE", "terminalCauseOrdinal,phase,origin; sender uses senderPhases/senderOrigins, receiver phase is -1 and origin uses receiverOrigins; -1 unknown")
                .put("READY_CANCELLED", "Queued STREAM_READY discarded by control writer (including initial READY); not recovery scheduler cancellation")
                .put("EMPTY_BOUNDARIES", "REQUEST,CONFIG_ENQUEUED,CONFIG_WRITING,CONFIG_WRITTEN,CONFIG_RECEIVED,READY_ENQUEUED,READY_WRITING,READY_WRITTEN,READY_RECEIVED,READY_PROCESSED,READY_WRITE_FAILED,READY_CANCELLED")
                .put("ADMISSION_BEGIN", "ptsUs,bytes; end may carry 0 for rejected admission")
                .put("WORKER_BEGIN_END", "Boundary time only; an absent end is not proof of a root cause"))
            .put("clock", "Each role has an independent unsynchronized monotonic clock. Zero lastSuccessNs is unknown."))
        return bundle.toString(2)
    }

    private fun eventLayouts(): JSONArray {
        val result = JSONArray()
        fun layout(role: String, kind: String, stage: String, reason: String, value: String, fields: String) {
            val fieldsList = if (fields.isEmpty()) emptyList() else fields.split(",")
            val kindCodes = when {
                kind.startsWith("SESSION") -> listOf(DiagnosticKind.SESSION.ordinal + 1)
                kind == "RECOVERY" -> listOf(DiagnosticKind.RECOVERY.ordinal + 1)
                kind == "QUEUE" -> listOf(DiagnosticKind.entries.size + 1)
                else -> (1..DiagnosticKind.entries.size).toList()
            }
            val tracks = when {
                kind == "SESSION(track=0)" -> listOf(0)
                kind == "SESSION(track=1/2)" || kind == "QUEUE" -> listOf(1, 2)
                else -> listOf(0, 1, 2)
            }
            result.put(JSONObject().put("role", role).put("kind", kind).put("stage", stage)
                .put("kindCodes", JSONArray(kindCodes)).put("trackCodes", JSONArray(tracks))
                .put("stageCodes", JSONArray(stage.split("/").map { DiagnosticStage.valueOf(it).ordinal }))
                .put("detailCount", fieldsList.size)
                .put("reasonDictionary", reason).put("value", value)
                .put("details", JSONArray(fieldsList)))
        }
        layout("sender", "RECOVERY", "PRESSURE", "senderLimits", "attemptedSpanUs",
            "count,countCap,bytes,byteCap,incomingBytes,trackCount,trackBytes,queuedSpanUs,sourceStepUs,ageMs,waitMs,ready,writerQueue(senderQueues;-1 unknown),writerBytes,writerMs,writerAdmittedAgeMs,lastWriteMs")
        layout("receiver", "RECOVERY", "PRESSURE", "receiverLimits", "attemptedSpanUs",
            "queuedPackets,queuedBytes,attemptedPackets,attemptedBytes,maxPackets,maxBytes,maxSpanUs,waitedMs")
        layout("sender", "QUEUE", "SAMPLE", "zero", "spanUs",
            "count,countCap,bytes,byteCap,oldestAgeMs,ready,writing,writerBytes,writerMs,lastWriteMs")
        layout("receiver", "QUEUE", "SAMPLE", "zero", "spanUs", "count,countCap,bytes,byteCap,oldestAgeMs")
        layout("sender", "RECOVERY", "FAILED", "zero", "cutoffUs",
            "recoveryState(recoveryStates),elapsedNs,budgetNs,sentKeyPtsUs,keyProved,pendingAudioCompletionPtsUs,writtenAudioCount,lastTrigger(senderRecoveryTriggers)")
        layout("receiver", "RECOVERY", "FAILED", "zero", "recoveryStartMs",
            "nowMs,budgetMs,tracksReady,videoRecovering,audioRecovering,awaitingRecoveryKey,audioRecoveryAdmitted,audioRecoveryCutoffUs,keyProvedGeneration,completedGeneration,pendingVideoProof")
        layout("both", "non-VIDEO/non-QUEUE", "BEGIN/COMPLETE/FAILED", "operationPoints", "operationId",
            "elapsedNs,bytes")
        layout("both", "RECOVERY", "PRESSURE/RESET/READY/COMPLETE/FAILED", "reasons", "cutoffUs", "operationPoint")
        layout("both", "SESSION", "FAILED", "reasons", "senderOriginOrMinusOne", "")
        layout("both", "SESSION(track=0)", "CONFIG", "zero", "unused",
            "videoCodec,audioCodec,fps,videoBitrate,audioBitrate")
        layout("both", "SESSION(track=1/2)", "CONFIG", "zero", "unused",
            "codec,width,height,sampleRate,channels")
        return result
    }

    private fun lifecycleLayouts(): JSONObject = JSONObject().apply {
        for (stage in IncidentEvidence.Stage.entries) {
            val fields = when (stage) {
                IncidentEvidence.Stage.RECOVERY_QUEUED, IncidentEvidence.Stage.RECOVERY_RUNNING,
                IncidentEvidence.Stage.RECOVERY_CANCELLED -> listOf("cooldownDelayMs")
                IncidentEvidence.Stage.RETIRE_BEGIN, IncidentEvidence.Stage.RETIRE_END,
                IncidentEvidence.Stage.CONSTRUCT_BEGIN, IncidentEvidence.Stage.CONSTRUCT_END -> listOf("stage(stages)")
                IncidentEvidence.Stage.FIRST_OUTPUT -> listOf("ptsUs", "boundary(track1:videoStages;track2:operationPoints)")
                IncidentEvidence.Stage.DEADLINE -> listOf("unresolvedStartNs", "absoluteDeadlineNs", "state(recoveryStates)")
                IncidentEvidence.Stage.DEADLINE_EXPIRED ->
                    listOf("unresolvedStartNs", "observedExpiryNs", "state(recoveryStates)", "cutoffUs", "sourcePtsUs")
                IncidentEvidence.Stage.RECOVERY_COMPLETE -> listOf("usableSourcePtsUs", "cutoffUs")
                IncidentEvidence.Stage.TERMINAL -> listOf("eventKind(eventKinds)", "reason(eventLayouts)", "value(eventLayouts)",
                    "remaining:up to 9 original numeric details(eventLayouts)")
                IncidentEvidence.Stage.TERMINAL_CAUSE -> listOf("cause(terminalCauses)",
                    "phase(senderPhases;receiver:-1)", "origin(senderOrigins/receiverOrigins)")
                IncidentEvidence.Stage.AUDIO_STATE -> listOf("decodedBufferedFrames", "underruns", "submittedFrames",
                    "consumedFrames", "presentedFrames", "timestampAgeUs", "clockSource(audioClockSources)",
                    "queuedPackets", "pending", "waiting", "lastOutputProgressNs")
                IncidentEvidence.Stage.QUEUE_STATE -> listOf("count", "bytes", "spanUs", "oldestAdmissionNs",
                    "sender media only:ready", "sender media only:writeBeginNs", "sender media only:lastWriteDurationMs")
                IncidentEvidence.Stage.FORMAT_OBSERVED -> listOf("codecCode(values.codecCodes)", "width", "height",
                    "sampleRate", "channels")
                IncidentEvidence.Stage.ADMISSION_BEGIN -> listOf("ptsUs", "bytes")
                IncidentEvidence.Stage.ADMISSION_END -> listOf("optional:0 if rejected; empty if admitted")
                else -> emptyList()
            }
            put(stage.name, JSONArray(fields))
        }
    }

    private fun telemetryText(bundle: JSONObject): String = buildString {
        appendLine("Session telemetry report — bounded metadata, not a complete trace")
        appendLine("Receiver telemetry: ${if (!bundle.optBoolean("receiverSupported")) "unsupported" else
            if (bundle.optBoolean("receiverComplete")) "complete" else "incomplete"}.")
        for (side in listOf("sender", "receiver")) {
            val source = bundle.optJSONObject(side) ?: continue
            appendLine("$side: publications ${source.optString("firstSequence")}–${source.optString("lastSequence")}; " +
                "sequence gaps=${source.optString("missingPublications")}, " +
                "history truncations=${source.optString("truncated")}, report omissions=${source.optString("reportOmitted")}.")
            val blocks = source.optJSONArray("blocks") ?: JSONArray()
            val latest = if (blocks.length() == 0) null else blocks.optJSONArray(blocks.length() - 1)?.optJSONObject(1)
            for (field in listOf("firstPressure", "firstFailure")) {
                val row = latest?.optJSONArray(field)
                appendLine("$field: " + if (row == null) "unknown/not retained." else
                    "kind=${row.optInt(0)} stage=${row.optInt(1)} track=${row.optInt(2)} " +
                        "generation=${row.optInt(3)} localNs=${row.optString(4)} value=${row.optString(5)} " +
                        "details=${row.optJSONArray(6)}.")
            }
            if (side == "sender") {
                val current = latest?.optJSONArray("current")
                val settled = (0 until (current?.length() ?: 0)).mapNotNull { current?.optJSONArray(it) }
                    .firstOrNull { it.optInt(0) == DiagnosticKind.SESSION.ordinal + 1 &&
                        it.optInt(1) == DiagnosticStage.SETTLED.ordinal }
                val details = settled?.optJSONArray(6)
                if (settled != null) appendLine("Settled outcome: " +
                    "cause=${IncidentEvidence.Cause.entries.getOrNull(settled.optString(5).toIntOrNull() ?: -1)?.name ?: "unknown"}, " +
                    "phase=${dev.screencopy.sender.SessionStopTrace.Phase.entries.getOrNull(details?.optString(0)?.toIntOrNull() ?: -1)?.name ?: "unknown"}, " +
                    "origin=${dev.screencopy.sender.SessionOutcome.Origin.entries.getOrNull(details?.optString(1)?.toIntOrNull() ?: -1)?.name ?: "unknown"}.")
            }
            if (side == "receiver") {
                val build = (blocks.length() - 1 downTo 0).firstNotNullOfOrNull { index ->
                    val batch = blocks.optJSONArray(index)?.optJSONObject(1) ?: return@firstNotNullOfOrNull null
                    runCatching {
                        SessionTelemetryProtocol.parse(batch.toString().toByteArray(Charsets.UTF_8), "receiver")
                            .state.firstNotNullOfOrNull { row ->
                                DiagnosticBuild.version(row)?.let { it to row.detail[0] }
                            }
                    }.getOrNull()
                }
                if (build != null) appendLine("Receiver build: ${build.first} (versionCode ${build.second}).")
                val events = latest?.optJSONArray("events")
                for (i in 0 until (events?.length() ?: 0)) {
                    val row = events?.optJSONArray(i) ?: continue
                    if (row.optInt(0) != CausalEvidence.KIND) continue
                    val stage = CausalEvidence.Stage.entries.getOrNull(row.optInt(1)) ?: continue
                    val values = row.optJSONArray(6) ?: continue
                    val fields = CausalEvidence.fields(stage.ordinal)
                    if (values.length() != fields.size) continue
                    appendLine("Causal ${stage.name} provenance=${row.optString(5)} " +
                        "generation=${row.optInt(3)} localNs=${row.optString(4)}: " +
                        fields.mapIndexed { index, field ->
                            "$field=${causalValue(stage, index, values.optString(index))}"
                        }.joinToString(", ") + ".")
                }
            }
            appendLine("Final sequence=${source.optString("finalSequence")}, terminal sequence=" +
                "${source.optString("terminalSequence")}, cleanup=${source.opt("cleanupSucceeded")}, " +
                "final skip reason=${source.optString("finalSkipReason", "unknown")}.")
        }
        appendLine("Sequence gaps count publications, not media loss. $UNKNOWNS")
    }

    private fun causalValue(stage: CausalEvidence.Stage, index: Int, raw: String): String {
        val trimReason = stage == CausalEvidence.Stage.TRIM && index == 0 ||
            stage == CausalEvidence.Stage.START && index == 12
        if (trimReason && raw == "-1") return "NONE(-1)"
        val dictionary = when {
            trimReason -> listOf("STARTUP", "RECOVERY", "RECOVERY_REFRESH", "CLOCK_PREPARE")
            stage == CausalEvidence.Stage.MAPPING && index == 0 -> CausalEvidence.Mapping.entries.map { it.name }
            stage == CausalEvidence.Stage.OUTPUT && index == 0 -> CausalEvidence.Output.entries.map { it.name }
            stage == CausalEvidence.Stage.OUTPUT && index in listOf(2, 19) -> DiagnosticPoint.entries.map { it.name }
            stage == CausalEvidence.Stage.WORKER && index == 0 -> CausalEvidence.phases
            stage == CausalEvidence.Stage.WORKER && index in listOf(10, 15) ->
                listOf("STEP", "INPUT", "DECODE", "REFILL")
            stage == CausalEvidence.Stage.START && index == 1 ->
                listOf("NOT_WAITING", "WAIT_TARGET", "RETRY_PREROLL", "RETRY_STALE_RECOVERY", "READY", "CANCELLED", "FAILED")
            stage == CausalEvidence.Stage.START && index == 2 ||
                stage == CausalEvidence.Stage.MAPPING && index in listOf(7, 8) ->
                listOf("UNAVAILABLE", "MONOTONIC", "AUDIO_INITIAL", "CALLBACK_AUDIO", "CACHED_AUDIO")
            else -> return raw
        }
        return dictionary.getOrNull(raw.toIntOrNull() ?: -1)?.let { "$it($raw)" } ?: "UNKNOWN($raw)"
    }

    fun text(bundle: JSONObject): String = if (bundle.opt("version") == 2) telemetryText(bundle) else buildString {
        appendLine("Automatic cast incident — bounded metadata, not a complete trace")
        appendLine("Peer: ${bundle.optString("peerState", "unknown")}; " +
            "provenance=${bundle.opt("peerProvenance") as? String ?: "unknown"}.")
        for (side in listOf("local", "peer")) {
            val snapshot = bundle.optJSONObject(side) ?: continue
            appendLine("${snapshot.optString("role")} ($side): capture ${if (snapshot.optBoolean("final"))
                "window closed; evidence may be incomplete and updates may follow" else "in progress"}.")
            val events = ArrayList<JSONArray>()
            val frames = snapshot.optJSONArray("frames") ?: JSONArray()
            for (i in 0 until frames.length()) {
                val records = frames.getJSONArray(i).getJSONArray(2)
                for (j in 0 until records.length()) events.add(records.getJSONArray(j))
            }
            val pressure = events.filter { it.optInt(2) == DiagnosticStage.PRESSURE.ordinal }
                .minByOrNull { it.optString(0).toLongOrNull() ?: Long.MAX_VALUE }
            if (pressure == null) appendLine("First observed pressure: unknown/not retained.")
            else {
                val detail = pressure.optJSONArray(8)
                val description = when (detail?.length()) {
                    17 -> "queued=${detail.optString(0)} packets/${detail.optString(2)} bytes, " +
                        "attemptedSpanUs=${pressure.optString(6)}, lastSocketWriteMs=${detail.optString(16)}"
                    8 -> "queued=${detail.optString(0)} packets/${detail.optString(1)} bytes, " +
                        "attemptedSpanUs=${pressure.optString(6)}, spanLimitUs=${detail.optString(6)}"
                    else -> "details unknown"
                }
                appendLine("First observed pressure: local ns=${pressure.optString(0)}, " +
                    "track=${pressure.optInt(3)} generation=${pressure.optInt(4)}; $description; " +
                    "limit/reason=${when {
                        snapshot.optString("role") == "receiver" && detail?.length() == 8 ->
                            org.screencopy.receiver.media.QueueLimit.entries.getOrNull(pressure.optInt(7))?.name
                        snapshot.optString("role") == "sender" && detail?.length() == 17 ->
                            dev.screencopy.sender.SenderLimit.entries.getOrNull(pressure.optInt(7))?.name
                        detail?.length() == 1 -> DiagnosticReason.entries.getOrNull(pressure.optInt(7))?.name
                        else -> null
                    } ?: "UNKNOWN(${pressure.optInt(7)})"}.")
            }
            val stalled = events.filter { it.optInt(2) == DiagnosticStage.BEGIN.ordinal &&
                (it.optJSONArray(8)?.optString(0)?.toLongOrNull() ?: 0) >= 100_000_000 }
                .maxByOrNull { it.optJSONArray(8)?.optString(0)?.toLongOrNull() ?: 0 }
            if (stalled != null) {
                val point = DiagnosticPoint.entries.getOrNull(stalled.optInt(7))?.name ?: "UNKNOWN(${stalled.optInt(7)})"
                appendLine("In-flight evidence: $point track=${stalled.optInt(3)} generation=${stalled.optInt(4)}, " +
                    "ageNs=${stalled.optJSONArray(8)?.optString(0)}. This locates an observed operation, not a root cause.")
            } else appendLine("Stalled stage: unknown; no retained aged in-flight operation.")
            val failedOperation = events.lastOrNull {
                val detail = it.optJSONArray(8)
                it.optInt(2) == DiagnosticStage.FAILED.ordinal &&
                    (detail?.length() == 2 || it.optInt(1) == 2 && detail?.length() == 1 &&
                        detail.optString(0).toIntOrNull() in listOf(DiagnosticPoint.RECOVERY_RETIRE.ordinal,
                            DiagnosticPoint.RECOVERY_CONSTRUCT.ordinal))
            }
            if (failedOperation != null) {
                val point = if (failedOperation.optJSONArray(8)?.length() == 2) failedOperation.optInt(7)
                    else failedOperation.getJSONArray(8).getString(0).toIntOrNull() ?: -1
                appendLine("Failed operation: ${DiagnosticPoint.entries.getOrNull(point)?.name ?: "UNKNOWN($point)"} " +
                    "track=${failedOperation.optInt(3)} generation=${failedOperation.optInt(4)}; not terminal evidence.")
            }
            val terminalEvents = events.filter { IncidentHistory.terminalFailure(it.optInt(1), it.optInt(2),
                it.optJSONArray(8)?.length() ?: -1, it.optJSONArray(8)?.optString(0)?.toLongOrNull()) }
            val terminal = terminalEvents.lastOrNull { (it.optJSONArray(8)?.length() ?: 0) >= 8 } ?: terminalEvents.lastOrNull()
            appendLine(if (terminal == null) "Terminal cause: unknown/not observed." else
                "Terminal evidence: track=${terminal.optInt(3)} generation=${terminal.optInt(4)} " +
                    "reason=${if (terminal.optInt(1) == 2 && terminal.optJSONArray(8)?.length() in listOf(8, 11))
                        "RECOVERY_DEADLINE" else DiagnosticReason.entries.getOrNull(terminal.optInt(7))?.name
                        ?: "UNKNOWN(${terminal.optInt(7)})"}, " +
                    "values=${terminal.optJSONArray(8)}.")
            if (snapshot.optString("role") == "sender" && terminal?.optJSONArray(8)?.length() == 8) {
                val detail = terminal.getJSONArray(8)
                val phase = listOf("WAIT_READY", "WAIT_SOURCE", "WAIT_PROOF", "ACTIVE")
                    .getOrNull(detail.optString(0).toIntOrNull() ?: -1) ?: "unknown"
                appendLine("Recovery deadline expired: $phase, elapsedNs=${detail.optString(1)}, budgetNs=${detail.optString(2)}.")
            }
            val evidence = snapshot.optJSONObject("evidence")
            if (evidence != null) {
                val terminalRecords = evidence.optJSONArray("terminal") ?: JSONArray()
                val cause = (0 until terminalRecords.length()).map { terminalRecords.getJSONArray(it) }
                    .firstOrNull { it.optInt(3) == IncidentEvidence.Stage.TERMINAL_CAUSE.ordinal }
                if (cause != null) appendLine("Terminal cause code: " + (IncidentEvidence.Cause.entries.getOrNull(
                    cause.getJSONArray(4).optString(0).toIntOrNull() ?: -1)?.name ?: "UNKNOWN") + ".")
                val operations = evidence.optJSONObject("operations")?.optJSONArray("records") ?: JSONArray()
                val sampled = evidence.optString("sampledAtNs").toLongOrNull() ?: 0
                val active = (0 until operations.length()).map { operations.getJSONArray(it) }
                    .filter { (it.optString(3).toLongOrNull() ?: 0) > 0 }
                    .minByOrNull { it.optString(4).toLongOrNull() ?: Long.MAX_VALUE }
                if (active != null) appendLine("Cached operation: " +
                    "${DiagnosticKind.entries.getOrNull(active.optInt(0))?.name ?: "UNKNOWN(${active.optInt(0)})"}/" +
                    "${DiagnosticPoint.entries.getOrNull(active.optInt(2))?.name ?: "UNKNOWN(${active.optInt(2)})"}, track=${active.optInt(1)}, " +
                    "generation=${active.optString(6)}, " +
                    "inflightAgeNs=${(sampled - (active.optString(4).toLongOrNull() ?: sampled)).coerceAtLeast(0)}, " +
                    "lastSuccessLocalNs=${active.optString(5)} (0 means unknown).")
                val records = evidence.optJSONArray("cached") ?: JSONArray()
                for (i in 0 until records.length()) {
                    val record = records.getJSONArray(i)
                    val values = record.getJSONArray(4)
                    when (record.optInt(3)) {
                        IncidentEvidence.Stage.QUEUE_STATE.ordinal -> appendLine("Cached queue: track=${record.optInt(1)}, " +
                            "count=${values.optString(0)}, bytes=${values.optString(1)}, spanUs=${values.optString(2)}, " +
                            "oldestAdmissionLocalNs=${values.optString(3)}, observedLocalNs=${record.optString(0)}.")
                        IncidentEvidence.Stage.AUDIO_STATE.ordinal -> appendLine("Cached audio: " +
                            "decodedBufferedFrames=${values.optString(0)}, underruns=${values.optString(1)}, " +
                            "timestampAgeUs=${values.optString(5)}, lastOutputProgressLocalNs=${values.optString(10)}, " +
                            "observedLocalNs=${record.optString(0)}.")
                    }
                }
                val ready = (0 until records.length()).map { records.getJSONArray(it) }
                    .filter { it.optInt(3) in IncidentEvidence.Stage.READY_ENQUEUED.ordinal..
                        IncidentEvidence.Stage.READY_PROCESSED.ordinal }
                appendLine("Latest observed READY boundaries: " + if (ready.isEmpty()) "unknown." else
                    ready.take(5).joinToString { "track=${it.optInt(1)} gen=${it.optInt(2)} " +
                        IncidentEvidence.Stage.entries[it.getInt(3)].name } + ".")
            } else appendLine("Detailed lifecycle evidence: unavailable (legacy peer/report).")
            appendLine("Loss: frames=${snapshot.optInt("omittedFrames")}, partial-frame records=" +
                "${evidence?.optString("historyOmittedRecords") ?: snapshot.optInt("omittedRecords")}, " +
                "producer drops=${snapshot.optString("droppedEvents")}; timeline omissions=${evidence?.optString("omitted") ?: "unknown"}.")
        }
        appendLine(UNKNOWNS)
    }.let { if (it.length <= 5000) it else it.take(4999 - UNKNOWNS.length) + "\n" + UNKNOWNS }
}

package dev.screencopy.sender

import org.json.JSONObject
import org.screencopy.receiver.protocol.AudioPacketTiming
import org.screencopy.receiver.protocol.RandomAccessPoint
import java.io.IOException
import java.util.Base64
import dev.redrain.cast.diagnostics.*

/** Each track owns its recovery generation; the other TCP lane keeps its original timeline. */
internal class StreamRecoverySender(
    private val session: Long,
    private val budget: MediaBudget,
    private val now: () -> Long = System::nanoTime,
    private val diagnostic: (String) -> Unit = {},
    private val withAudio: Boolean = true
) {
    private enum class State { WAIT_READY, WAIT_SOURCE, WAIT_PROOF, ACTIVE }
    private val state = Array(3) { State.WAIT_READY }
    private val sourceGeneration = IntArray(3)
    private val wireGeneration = IntArray(3)
    private val sourcePts = LongArray(3) { -1 }
    private val configs = arrayOfNulls<JSONObject>(3)
    private val unresolvedAt = arrayOfNulls<Long>(3)
    private val minimumPts = LongArray(3)
    private val sentKeyPts = arrayOfNulls<Long>(3)
    private val completedPts = arrayOfNulls<Long>(3)
    private var keyProved = false
    private var lastKeyRequest: Long? = null
    private var audioTiming: AudioPacketTiming? = null
    private var initialReady = false
    private var initialAudioCutoffPending = false
    // Sent-interval eligibility is separate from admission-ordered decoder preroll coverage.
    private val writtenAudio = ArrayList<Pair<Long, Long>>()
    private var pendingAudioCompletion: Long? = null

    @Synchronized fun config(track: Int, generation: Int, config: JSONObject) {
        requireTrack(track)
        requireWithinDeadline()
        if (generation != sourceGeneration[track] + 1)
            throw IOException("PROTOCOL: nonsequential capture configuration during recovery")
        if (track == 2 && sourceGeneration[track] > 0)
            throw IOException("AUDIO_FAILED: audio format changes require a new capture session")
        sourceGeneration[track] = generation
        sourcePts[track] = -1
        configs[track] = JSONObject(config.toString())
        if (track == 2) audioTiming = AudioPacketTiming.fromConfig(config)
        begin(track, "capture", initial = wireGeneration[track] == 0)
    }

    @Synchronized fun ready(): Boolean {
        requireWithinDeadline()
        if (!initialReady) {
            if (!budget.allReady()) return false
            initialReady = true
        }
        for (track in 1..if (withAudio) 2 else 1) {
            if (state[track] != State.WAIT_READY || !budget.trackReady(track)) continue
            RemoteDiagnostics.recorder.observe("sender", session, DiagnosticKind.RECOVERY, DiagnosticStage.READY,
                track, wireGeneration[track], cutoff = minimumPts[track])
            if (track == 2 && wireGeneration[track] == 1) {
                val first = budget.firstQueuedAudioPts()
                initialAudioCutoffPending = first == null
                minimumPts[2] = maxOf(now() / 1000,
                    first ?: 0)
                state[track] = State.WAIT_SOURCE
                unresolvedAt[track] = null
            } else {
                state[track] = State.WAIT_SOURCE
                if (track == 1) minimumPts[track] = now() / 1000
            }
        }
        releaseAudioIfReady()
        return retryKeyFrame()
    }

    @Synchronized fun request(track: Int, generation: Int) {
        requireTrack(track)
        requireWithinDeadline()
        validateGeneration(track, generation)
        if (generation != wireGeneration[track] || state[track] == State.WAIT_READY) return
        begin(track, "receiver")
    }

    @Synchronized fun keyProved(generation: Int, pts: Long) {
        requireWithinDeadline()
        if (!acknowledgement(1, generation, pts)) return
        if (state[1] != State.WAIT_PROOF && state[1] != State.ACTIVE)
            throw IOException("PROTOCOL: unexpected decoded-key proof")
        keyProved = true
    }

    @Synchronized fun completed(track: Int, generation: Int, pts: Long) {
        requireTrack(track)
        requireWithinDeadline()
        if (!acknowledgement(track, generation, pts)) return
        if (state[track] == State.ACTIVE) {
            if (completedPts[track] != pts) throw IOException("PROTOCOL: conflicting recovery completion proof")
            return
        }
        if (state[track] != State.WAIT_PROOF || track == 1 && !keyProved)
            throw IOException("PROTOCOL: unexpected track recovery completion")
        if (track == 2) {
            if (pendingAudioCompletion?.let { it != pts } == true)
                throw IOException("PROTOCOL: conflicting recovery completion proof")
            if (writtenAudio.none { pts >= it.first && pts < it.second }) {
                val writing = budget.writingAudio()
                if (writing == null || writing.generation != generation || pts < writing.pts ||
                    pts >= checkNotNull(audioTiming).endPts(writing.pts, writing.payload))
                    throw IOException("PROTOCOL: audio completion does not match submitted source audio")
                pendingAudioCompletion = pts
                return
            }
            pendingAudioCompletion = null
            writtenAudio.clear()
        }
        completedPts[track] = pts
        unresolvedAt[track] = null
        state[track] = State.ACTIVE
        diagnostic("event=STREAM_RECOVERY_COMPLETE track=$track generation=$generation pts_us=$pts")
        RemoteDiagnostics.recorder.observe("sender", session, DiagnosticKind.RECOVERY, DiagnosticStage.COMPLETE,
            track, generation, pts = pts, cutoff = minimumPts[track])
    }

    @Synchronized fun written(packet: Wire.Message) {
        if (packet.type != Wire.AUDIO_PACKET) return
        if (!withAudio || packet.track != 2 || packet.session != session || packet.generation > wireGeneration[2])
            throw IOException("PROTOCOL: invalid audio write completion")
        if (packet.generation != wireGeneration[2] || state[2] != State.WAIT_PROOF) return
        requireWithinDeadline()
        var start = maxOf(packet.pts, minimumPts[2])
        var end = checkNotNull(audioTiming).endPts(packet.pts, packet.payload)
        if (start >= end) return
        var index = 0
        while (index < writtenAudio.size && writtenAudio[index].second < start) index++
        while (index < writtenAudio.size && writtenAudio[index].first <= end) {
            val merged = writtenAudio.removeAt(index)
            start = minOf(start, merged.first)
            end = maxOf(end, merged.second)
        }
        if (writtenAudio.size >= 4096) {
            diagnostic("event=STREAM_RECOVERY_PROOF_CAPACITY track=2 generation=${wireGeneration[2]} intervals=${writtenAudio.size} max_intervals=4096")
            begin(2, "audio-proof-window-capacity")
            return
        }
        writtenAudio.add(index, start to end)
        pendingAudioCompletion?.let { completed(2, packet.generation, it) }
    }

    private fun acknowledgement(track: Int, generation: Int, pts: Long): Boolean {
        validateGeneration(track, generation)
        if (generation < wireGeneration[track]) return false
        if (pts < 0 || track == 1 && pts != sentKeyPts[1] || track == 2 && pts < minimumPts[2])
            throw IOException("PROTOCOL: proof does not match the track recovery source window")
        return true
    }

    @Synchronized fun packet(packet: Wire.Message) {
        requireWithinDeadline()
        Wire.validate(packet.type, packet.flags, packet.session, packet.track, packet.generation, packet.pts, packet.payload.size)
        val track = packet.track
        requireTrack(track)
        if (packet.generation != sourceGeneration[track] || packet.session != session ||
            packet.type != (if (track == 1) Wire.VIDEO_FRAME else Wire.AUDIO_PACKET) ||
            (track == 1 || audioTiming?.codec != "opus") && packet.pts < sourcePts[track])
            throw IOException("PROTOCOL: invalid capture media during track recovery")
        sourcePts[track] = packet.pts
        val mapped = packet.copy(generation = wireGeneration[track])
        if (track == 2 && state[2] in setOf(State.WAIT_READY, State.WAIT_SOURCE)) {
            if (initialReady && wireGeneration[2] == 1 && initialAudioCutoffPending) {
                minimumPts[2] = maxOf(now() / 1000, packet.pts)
                initialAudioCutoffPending = false
            }
            val pressure = budget.holdRecoveryAudio(mapped, checkNotNull(audioTiming),
                if (initialReady && !initialAudioCutoffPending) minimumPts[2] else null)
            if (pressure != null) {
                if (!initialReady) throw SenderCongestionException(pressure)
                begin(2, "audio-preroll-capacity")
            }
            releaseAudioIfReady()
            return
        }
        if (state[track] == State.WAIT_READY) {
            if (track == 1) videoDropped(mapped, DiagnosticReason.SOURCE_PENDING)
            return
        }
        if (track == 1 && state[1] == State.WAIT_SOURCE) {
            if (packet.flags and 1 == 0 || packet.pts < minimumPts[1] || now() / 1000 - packet.pts > 250_000) {
                videoDropped(mapped, DiagnosticReason.STALE)
                return
            }
            val config = checkNotNull(configs[1])
            val csd = config.optJSONArray("csd")
            RandomAccessPoint.verify(config.getString("codec"), packet.payload,
                if (csd == null) emptyList() else (0 until csd.length()).map { Base64.getDecoder().decode(csd.getString(it)) })
        }
        val pressure = budget.tryOfferMedia(mapped)
        if (pressure != null) {
            if (track == 1) videoDropped(mapped, DiagnosticReason.CAPACITY)
            diagnostic("event=STREAM_RECOVERY_PRESSURE ${pressure.fields()}")
            begin(track, "sender")
            return
        }

        if (track == 1 && state[1] == State.WAIT_SOURCE) {
            state[1] = State.WAIT_PROOF
            sentKeyPts[1] = packet.pts
            diagnostic("event=STREAM_RECOVERY_KEY track=1 generation=${wireGeneration[1]} pts_us=${packet.pts}")
        }
    }

    private fun videoDropped(packet: Wire.Message, reason: DiagnosticReason) {
        RemoteDiagnostics.recorder.video("sender", session, packet.generation, DiagnosticVideoStage.SOURCE_DROPPED,
            packet.pts, packet.payload.size.toLong(), keyFrame = packet.flags and 1 != 0, reason = reason)
    }

    private fun releaseAudioIfReady() {
        if (!withAudio || state[2] != State.WAIT_SOURCE) return
        if (wireGeneration[2] == 1) budget.firstQueuedAudioPts()?.let { first ->
            val timing = checkNotNull(audioTiming)
            val primed = Math.addExact(first, timing.prerollUs)
            if (timing.prerollUs > 0 && primed > minimumPts[2] && budget.initialAudioPrefixRetired()) {
                begin(2, "initial-audio-preroll-retired")
                return
            }
            minimumPts[2] = maxOf(minimumPts[2], primed)
        }
        when (budget.releaseRecoveryAudio(minimumPts[2], checkNotNull(audioTiming), freshRawWindow = wireGeneration[2] > 1)) {
            RecoveryAudioCoverage.READY -> state[2] = if (wireGeneration[2] == 1) State.ACTIVE else State.WAIT_PROOF
            RecoveryAudioCoverage.LOST -> begin(2, "audio-source-window-lost")
            RecoveryAudioCoverage.WAITING -> Unit
        }
    }

    @Synchronized fun checkDeadline() {
        requireWithinDeadline()
        releaseAudioIfReady()
    }

    private fun requireWithinDeadline() {
        for (track in 1..if (withAudio) 2 else 1) {
            if (unresolvedAt[track]?.let { now() - it >= 10_000_000_000L } == true)
                throw IOException("TIMEOUT: track $track recovery produced no usable fresh media within ten seconds")
        }
    }

    @Synchronized fun retryKeyFrame(): Boolean {
        requireWithinDeadline()
        if (state[1] != State.WAIT_SOURCE ||
            lastKeyRequest?.let { now() - it < 500_000_000L } == true) return false
        lastKeyRequest = now()
        return true
    }

    private fun begin(track: Int, reason: String, initial: Boolean = false) {
        RemoteDiagnostics.recorder.observe("sender", session, DiagnosticKind.RECOVERY, DiagnosticStage.RESET,
            track, wireGeneration[track], cutoff = minimumPts[track],
            reason = when (reason) {
                "capture" -> DiagnosticReason.CAPTURE
                "receiver" -> DiagnosticReason.RECEIVER
                "audio-source-window-lost" -> DiagnosticReason.SOURCE_WINDOW_LOST
                "initial-audio-preroll-retired" -> DiagnosticReason.PREROLL_MISSING
                "audio-proof-window-capacity" -> DiagnosticReason.PROOF_CAPACITY
                else -> DiagnosticReason.PRESSURE
            })
        if (unresolvedAt[track] == null) unresolvedAt[track] = now()
        if (!initial || track == 2) budget.discardMediaForRecovery(track)
        state[track] = State.WAIT_READY
        completedPts[track] = null
        if (track == 1) {
            sentKeyPts[1] = null
            keyProved = false
        } else {
            minimumPts[2] = Math.addExact(now() / 1000, checkNotNull(audioTiming).prerollUs)
            initialAudioCutoffPending = false
            writtenAudio.clear()
            pendingAudioCompletion = null
        }
        wireGeneration[track] = next(wireGeneration[track])
        RemoteDiagnostics.recorder.observe("sender", session, DiagnosticKind.RECOVERY, DiagnosticStage.BEGIN,
            track, wireGeneration[track], cutoff = minimumPts[track])
        val config = JSONObject(checkNotNull(configs[track]).toString())
        if (track == 2 && !initial) config.put("recoveryPtsUs", minimumPts[2].toString())
        budget.offer(Wire.config(session, track, wireGeneration[track], config))
        diagnostic("event=STREAM_RECOVERY_BEGIN track=$track reason=$reason generation=${wireGeneration[track]}")
    }

    private fun validateGeneration(track: Int, generation: Int) {
        if (generation <= 0 || generation > wireGeneration[track])
            throw IOException("PROTOCOL: invalid track recovery generation")
    }

    private fun requireTrack(track: Int) {
        if (track !in 1..2 || track == 2 && !withAudio)
            throw IOException("PROTOCOL: recovery track is not enabled")
    }

    private fun next(generation: Int): Int {
        if (generation == Int.MAX_VALUE) throw IOException("PROTOCOL: track recovery generation exhausted")
        return generation + 1
    }
}

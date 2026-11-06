package dev.screencopy.sender

import org.json.JSONObject
import org.screencopy.receiver.protocol.AudioPacketTiming
import org.screencopy.receiver.protocol.RandomAccessPoint
import java.io.IOException
import java.util.Base64

/** The ordered config, decoded-key proof and audio proof define one bounded recovery transaction. */
internal class StreamRecoverySender(
    private val session: Long,
    private val budget: MediaBudget,
    private val now: () -> Long = System::nanoTime,
    private val diagnostic: (String) -> Unit = {},
    private val withAudio: Boolean = true
) {
    private enum class State { WAIT_READY, WAIT_KEY, WAIT_KEY_PROOF, WAIT_AUDIO, WAIT_COMPLETE, ACTIVE }
    private var state = State.WAIT_READY
    private val sourceGeneration = IntArray(3)
    private val wireGeneration = IntArray(3)
    private val sourcePts = LongArray(3) { -1 }
    private var opus = false
    private var videoConfig: JSONObject? = null
    private var audioTiming: AudioPacketTiming? = null
    private var unresolvedAt: Long? = null
    private var lastKeyRequest: Long? = null
    private var minimumKeyPts = 0L
    private var sentKeyPts: Long? = null
    private var completedAudioPts: Long? = null

    init { budget.discardMediaForRecovery() }

    @Synchronized fun config(track: Int, generation: Int, config: JSONObject) {
        require(track in 1..2)
        if (generation != sourceGeneration[track] + 1)
            throw IOException("PROTOCOL: nonsequential capture configuration during recovery")
        if (track == 2 && sourceGeneration[track] > 0)
            throw IOException("AUDIO_FAILED: audio format changes require a new capture session")
        sourceGeneration[track] = generation
        sourcePts[track] = -1
        if (track == 2) {
            opus = config.opt("codec") == "opus"
            audioTiming = AudioPacketTiming.fromConfig(config)
        } else {
            videoConfig = JSONObject(config.toString())
            if (unresolvedAt == null) unresolvedAt = now()
            if (wireGeneration[1] > 0) budget.discardMediaForRecovery()
            sentKeyPts = null
            state = State.WAIT_READY
        }
        wireGeneration[track] = next(wireGeneration[track])
        budget.offer(Wire.config(session, track, wireGeneration[track], config))
    }

    @Synchronized fun ready(): Boolean {
        if (state != State.WAIT_READY || !budget.allReady()) return false
        state = State.WAIT_KEY
        val preroll = if (withAudio) checkNotNull(audioTiming).prerollUs else 0
        minimumKeyPts = maxOf(now() / 1000, checkNotNull(unresolvedAt) / 1000 + preroll)
        return retryKeyFrame()
    }

    @Synchronized fun request(generation: Int) {
        if (generation <= 0 || generation > wireGeneration[1])
            throw IOException("PROTOCOL: recovery request has an invalid video generation")
        if (generation != wireGeneration[1] || state == State.WAIT_READY || state == State.WAIT_KEY) return
        reset("receiver")
    }

    @Synchronized fun keyProved(generation: Int, pts: Long) {
        if (!acknowledgement(generation, pts)) return
        if (state == State.ACTIVE || state == State.WAIT_AUDIO || state == State.WAIT_COMPLETE) return
        if (state != State.WAIT_KEY_PROOF) throw IOException("PROTOCOL: unexpected decoded-key proof")
        if (now() / 1000 - pts > 250_000) { reset("stale-key-proof"); return }
        state = if (withAudio) State.WAIT_AUDIO else State.WAIT_COMPLETE
        releaseAudioIfReady()
    }

    @Synchronized fun completed(generation: Int, pts: Long, audioPts: Long?) {
        if (!acknowledgement(generation, pts)) return
        if (state == State.ACTIVE) {
            if (audioPts != completedAudioPts) throw IOException("PROTOCOL: conflicting recovery completion proof")
            return
        }
        if (state != State.WAIT_COMPLETE || withAudio && audioPts == null ||
            audioPts?.let { it < pts || it - pts > 250_000 } == true)
            throw IOException("PROTOCOL: invalid recovery completion proof")
        unresolvedAt = null
        completedAudioPts = audioPts
        state = State.ACTIVE
        diagnostic("event=STREAM_RECOVERY_COMPLETE generation=$generation pts_us=$pts")
    }

    private fun acknowledgement(generation: Int, pts: Long): Boolean {
        if (generation <= 0 || generation > wireGeneration[1]) throw IOException("PROTOCOL: invalid proof generation")
        if (generation < wireGeneration[1]) return false
        if (pts != sentKeyPts) throw IOException("PROTOCOL: proof does not match the recovery keyframe")
        return true
    }

    @Synchronized fun packet(packet: Wire.Message) {
        Wire.validate(packet.type, packet.flags, packet.session, packet.track, packet.generation, packet.pts, packet.payload.size)
        val track = packet.track
        if (track !in 1..2 || packet.generation != sourceGeneration[track])
            throw IOException("PROTOCOL: capture packet does not match its configuration")
        if (packet.session != session || packet.type != (if (track == 1) Wire.VIDEO_FRAME else Wire.AUDIO_PACKET) ||
            (track == 1 || !opus) && packet.pts < sourcePts[track])
            throw IOException("PROTOCOL: invalid capture media during recovery")
        sourcePts[track] = packet.pts
        val mapped = packet.copy(generation = wireGeneration[track])
        if (track == 2 && state != State.ACTIVE && state != State.WAIT_COMPLETE) {
            val pressure = budget.holdRecoveryAudio(mapped, checkNotNull(audioTiming))
            if (pressure != null) reset("audio-preroll-capacity")
            releaseAudioIfReady()
            return
        }
        if (state == State.WAIT_READY) return
        if (state == State.WAIT_KEY) {
            if (track != 1 || packet.flags and 1 == 0 || packet.pts < minimumKeyPts ||
                now() / 1000 - packet.pts > 250_000) return
            val config = checkNotNull(videoConfig)
            val csd = config.optJSONArray("csd")
            RandomAccessPoint.verify(config.getString("codec"), packet.payload,
                if (csd == null) emptyList() else (0 until csd.length()).map { Base64.getDecoder().decode(csd.getString(it)) })
        }
        val pressure = budget.tryOfferMedia(mapped)
        if (pressure != null) {
            diagnostic("event=STREAM_RECOVERY_PRESSURE ${pressure.fields()}")
            reset("sender")
            return
        }
        if (state == State.WAIT_KEY) {
            state = State.WAIT_KEY_PROOF
            sentKeyPts = packet.pts
            diagnostic("event=STREAM_RECOVERY_KEY generation=${wireGeneration[1]} pts_us=${packet.pts}")
        }
    }

    private fun releaseAudioIfReady() {
        if (state != State.WAIT_AUDIO) return
        val key = checkNotNull(sentKeyPts)
        if (now() / 1000 - key > 250_000) { reset("stale-audio-window"); return }
        if (budget.releaseRecoveryAudio(key, checkNotNull(audioTiming))) state = State.WAIT_COMPLETE
    }

    @Synchronized fun checkDeadline() {
        if (unresolvedAt?.let { now() - it >= 10_000_000_000L } == true)
            throw IOException("TIMEOUT: stream recovery did not complete within ten seconds of the first unresolved request")
        releaseAudioIfReady()
    }

    @Synchronized fun retryKeyFrame(): Boolean {
        if (state != State.WAIT_KEY || now() / 1000 < minimumKeyPts ||
            lastKeyRequest?.let { now() - it < 500_000_000L } == true) return false
        lastKeyRequest = now()
        return true
    }

    private fun reset(reason: String) {
        val config = videoConfig ?: throw IOException("PROTOCOL: recovery preceded video configuration")
        if (unresolvedAt == null) unresolvedAt = now()
        budget.discardMediaForRecovery()
        wireGeneration[1] = next(wireGeneration[1])
        sentKeyPts = null
        state = State.WAIT_READY
        budget.offer(Wire.config(session, 1, wireGeneration[1], config))
        diagnostic("event=STREAM_RECOVERY_BEGIN reason=$reason generation=${wireGeneration[1]}")
    }

    private fun next(value: Int): Int {
        if (value == Int.MAX_VALUE) throw IOException("PROTOCOL: stream generation exhausted")
        return value + 1
    }
}

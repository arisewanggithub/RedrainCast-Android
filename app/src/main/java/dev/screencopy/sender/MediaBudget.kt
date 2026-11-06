package dev.screencopy.sender

import java.io.IOException
import java.util.ArrayDeque
import org.screencopy.receiver.protocol.AudioPacketTiming

/** Capture readers wait only for bounded local admission, never while holding a network writer lock. */
class MediaBudget internal constructor(private val withAudio: Boolean, private val clock: () -> Long,
                                       private val admissionNanos: Long) {
    constructor(withAudio: Boolean, clock: () -> Long = System::nanoTime) : this(withAudio, clock, 500_000_000L)
    init { require(admissionNanos in 1..500_000_000L) }
    private data class Entry(val message: Wire.Message, val admitted: Long)
    private val controls = ArrayDeque<Entry>()
    private val video = ArrayDeque<Entry>()
    private val audio = ArrayDeque<Entry>()
    private val monitor = Object()
    private var mediaBytes = 0
    private var controlBytes = 0
    private var configCount = 0
    private var turn = 1
    private val generation = IntArray(3)
    private val announced = IntArray(3)
    private val ready = IntArray(3)
    private val readinessDeadline = LongArray(3)
    private val lastPts = LongArray(3) { -1 }
    private val opusSourcePts = BooleanArray(3)
    private var started = false
    private var ending = false
    private var recoveryAudioHeld = false
    private val pendingWrites = arrayOfNulls<Entry>(3)
    private val writing = BooleanArray(3)
    private val writerBytes = IntArray(3)
    private val writeBegin = LongArray(3)
    private val writeAdmitted = LongArray(3)
    private val lastWriteMs = LongArray(3)

    fun offer(message: Wire.Message) = offerInternal(message, admissionNanos)

    internal fun tryOfferMedia(message: Wire.Message): SenderQueueFailure? {
        require(message.type in setOf(Wire.VIDEO_FRAME, Wire.AUDIO_PACKET))
        return try { offerInternal(message, 0); null }
        catch (pressure: SenderCongestionException) { pressure.snapshot }
    }

    internal fun discardMediaForRecovery() = synchronized(monitor) {
        recoveryAudioHeld = true
        for (queue in listOf(video, audio)) {
            val entries = queue.iterator()
            while (entries.hasNext()) {
                val entry = entries.next()
                if (entry.message.type in setOf(Wire.VIDEO_FRAME, Wire.AUDIO_PACKET)) {
                    mediaBytes -= entry.message.payload.size
                    entries.remove()
                }
            }
        }
        monitor.notifyAll()
    }

    internal fun holdRecoveryAudio(message: Wire.Message, timing: AudioPacketTiming): SenderQueueFailure? = synchronized(monitor) {
        require(message.type == Wire.AUDIO_PACKET && message.track == 2)
        timing.endPts(message.pts, message.payload)
        var pressure = tryOfferMedia(message)
        while (pressure != null) {
            val oldest = audio.firstOrNull { it.message.type == Wire.AUDIO_PACKET } ?: break
            audio.remove(oldest)
            mediaBytes -= oldest.message.payload.size
            pressure = tryOfferMedia(message)
        }
        pressure
    }

    internal fun releaseRecoveryAudio(cutoff: Long, timing: AudioPacketTiming): Boolean = synchronized(monitor) {
        val start = timing.windowStart(cutoff)
        while (true) {
            val oldest = audio.firstOrNull { it.message.type == Wire.AUDIO_PACKET } ?: break
            if (timing.endPts(oldest.message.pts, oldest.message.payload) > start) break
            audio.remove(oldest)
            mediaBytes -= oldest.message.payload.size
        }
        val packets = audio.filter { it.message.type == Wire.AUDIO_PACKET }
        if (packets.isEmpty() || timing.prerollUs > 0 && packets.first().message.pts > start ||
            packets.none { timing.endPts(it.message.pts, it.message.payload) > cutoff }) return@synchronized false
        if (timing.prerollUs > 0) {
            var covered = start
            val sampleUs = (1_000_000L + timing.rate - 1) / timing.rate
            for (entry in packets) {
                if (entry.message.pts > covered && entry.message.pts - covered > sampleUs)
                    return@synchronized false
                covered = maxOf(covered, timing.endPts(entry.message.pts, entry.message.payload))
                if (covered > cutoff) break
            }
        }
        recoveryAudioHeld = false
        monitor.notifyAll()
        true
    }

    private fun offerInternal(message: Wire.Message, waitNanos: Long) = synchronized(monitor) {
        val begin = System.nanoTime()
        val track = message.track
        val opusConfig = message.type == Wire.STREAM_CONFIG && track == 2 &&
            message.json().opt("codec") == "opus"
        while (true) {
            if (ending) throw SessionCancelledException()
            if (message.type in setOf(Wire.STREAM_CONFIG, Wire.VIDEO_FRAME, Wire.AUDIO_PACKET) && track !in 1..2)
                throw protocol(message, CaptureProtocolReason.INVALID_TRACK)
            if (message.type == Wire.STREAM_CONFIG && message.generation != generation[track] + 1)
                throw protocol(message, CaptureProtocolReason.CONFIG_SEQUENCE)
            if (message.type in setOf(Wire.VIDEO_FRAME, Wire.AUDIO_PACKET)) {
                if (message.generation != generation[track]) throw protocol(message, CaptureProtocolReason.MEDIA_GENERATION)
                val opus = message.type == Wire.AUDIO_PACKET && track == 2 && opusSourcePts[track]
                if (message.pts < 0 || !opus && message.pts < lastPts[track])
                    throw protocol(message, CaptureProtocolReason.PTS_DECREASE)
            }
            val limit = violation(message) ?: break
            val remaining = waitNanos - (System.nanoTime() - begin)
            if (remaining <= 0 || message.payload.size > 8 * 1024 * 1024 ||
                message.type !in setOf(Wire.VIDEO_FRAME, Wire.AUDIO_PACKET) && message.payload.size > 256 * 1024)
                throw SenderCongestionException(snapshot(message, limit, (System.nanoTime() - begin) / 1_000_000))
            monitor.wait(remaining / 1_000_000, (remaining % 1_000_000).toInt())
        }
        val entry = Entry(message, clock())
        if (message.type == Wire.STREAM_CONFIG) {
            generation[track] = message.generation; lastPts[track] = -1
            opusSourcePts[track] = opusConfig
            val queue = if (track == 1) video else audio
            queue.add(entry); configCount++; controlBytes += message.payload.size
        } else if (message.type != Wire.VIDEO_FRAME && message.type != Wire.AUDIO_PACKET) {
            controls.add(entry); controlBytes += message.payload.size
        } else {
            lastPts[track] = message.pts
            val queue = if (track == 1) video else audio
            queue.add(entry); mediaBytes += message.payload.size
        }
        monitor.notifyAll()
    }
    private fun protocol(message: Wire.Message, reason: CaptureProtocolReason): SenderProtocolException {
        val track = message.track
        val valid = track in 1..2
        val stage = when (message.type) {
            Wire.STREAM_CONFIG -> CaptureProtocolStage.MUX_CONFIG
            Wire.STREAM_READY -> CaptureProtocolStage.MUX_READY
            else -> CaptureProtocolStage.MUX_MEDIA
        }
        val current = if (valid) generation[track] else 0
        val delta = if (valid && message.type in setOf(Wire.VIDEO_FRAME, Wire.AUDIO_PACKET) && lastPts[track] >= 0)
            message.pts - lastPts[track] else 0
        val sourceTrack = when (track) { 1 -> CaptureTrack.VIDEO; 2 -> CaptureTrack.AUDIO; else -> CaptureTrack.UNKNOWN }
        return SenderProtocolException(CaptureProtocolFailure(reason, stage, sourceTrack, message.generation,
            if (message.type == Wire.STREAM_CONFIG) current + 1 else if (message.type == Wire.STREAM_READY && valid) announced[track] else current,
            current, if (valid) announced[track] else 0, if (valid) ready[track] else 0, delta))
    }
    private fun violation(message: Wire.Message): SenderLimit? {
        val media = message.type in setOf(Wire.VIDEO_FRAME, Wire.AUDIO_PACKET)
        val queue = if (message.track == 1) video else audio
        if (!media) {
            if (controls.size + configCount >= 64) return SenderLimit.CONTROL_COUNT
            if (controlBytes.toLong() + message.payload.size > 256 * 1024) return SenderLimit.CONTROL_BYTES
        }
        if (media || message.type == Wire.STREAM_CONFIG) {
            if (queue.size >= 1024) return SenderLimit.TRACK_COUNT
        }
        if (media) {
            if (mediaBytes.toLong() + message.payload.size > 8 * 1024 * 1024) return SenderLimit.MEDIA_BYTES
            if (sourceSpan(queue, message.pts) > 500_000) return SenderLimit.PTS_SPAN
        }
        return null
    }
    private fun snapshot(message: Wire.Message, limit: SenderLimit, waited: Long): SenderQueueFailure {
        val control = limit in setOf(SenderLimit.CONTROL_COUNT, SenderLimit.CONTROL_BYTES)
        val media = message.type in setOf(Wire.VIDEO_FRAME, Wire.AUDIO_PACKET)
        val queue = when (message.track) { 1 -> video; 2 -> audio; else -> controls }
        val lane = kind(message).ordinal
        val frames = queue.filter { it.message.type in setOf(Wire.VIDEO_FRAME, Wire.AUDIO_PACKET) }
        return SenderQueueFailure(kind(message), limit, if (control) controls.size + configCount else queue.size,
            if (control) 64 else 1024, if (control) controlBytes else mediaBytes,
            if (control) 256 * 1024 else 8 * 1024 * 1024, message.payload.size, queue.size,
            frames.sumOf { it.message.payload.size }, sourceSpan(queue),
            if (media) sourceSpan(queue, message.pts) else 0,
            if (media && message.track in 1..2 && lastPts[message.track] >= 0) message.pts - lastPts[message.track] else 0,
            queue.peek()?.let { ((clock() - it.admitted) / 1_000_000).coerceAtLeast(0) } ?: 0,
            waited, started && (message.track == 0 || ready[message.track] == message.generation),
            if (writing[lane]) kind(message) else null, writerBytes[lane],
            if (writing[lane]) ((clock() - writeBegin[lane]) / 1_000_000).coerceAtLeast(0) else 0,
            if (writing[lane]) ((clock() - writeAdmitted[lane]) / 1_000_000).coerceAtLeast(0) else 0,
            lastWriteMs[lane])
    }
    private fun kind(message: Wire.Message) = when (message.track) {
        1 -> SenderQueue.VIDEO; 2 -> SenderQueue.AUDIO; else -> SenderQueue.CONTROL
    }
    private fun sourceSpan(queue: ArrayDeque<Entry>, incoming: Long? = null): Long {
        var minimum = incoming ?: Long.MAX_VALUE
        var maximum = incoming ?: Long.MIN_VALUE
        for (entry in queue) {
            if (entry.message.type != Wire.VIDEO_FRAME && entry.message.type != Wire.AUDIO_PACKET) continue
            minimum = minOf(minimum, entry.message.pts)
            maximum = maxOf(maximum, entry.message.pts)
        }
        return if (maximum == Long.MIN_VALUE) 0 else maximum - minimum
    }
    fun beginWrite(message: Wire.Message) = synchronized(monitor) {
        val lane = kind(message).ordinal
        writing[lane] = true
        writerBytes[lane] = message.payload.size
        writeBegin[lane] = clock()
        writeAdmitted[lane] = pendingWrites[lane]?.takeIf { it.message === message }?.admitted ?: writeBegin[lane]
    }
    fun endWrite(message: Wire.Message? = null) = synchronized(monitor) {
        val lanes = if (message == null) 0..2 else kind(message).ordinal..kind(message).ordinal
        for (lane in lanes) {
            if (writing[lane]) lastWriteMs[lane] = ((clock() - writeBegin[lane]) / 1_000_000).coerceAtLeast(0)
            writing[lane] = false
            writerBytes[lane] = 0
            pendingWrites[lane] = null
        }
        monitor.notifyAll()
    }

    fun awaitVideoDrain() = synchronized(monitor) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (true) {
            if (ending) throw SessionCancelledException()
            if (video.isEmpty() && ready[1] == generation[1] &&
                pendingWrites[1] == null) break
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0)
                throw IOException("CONGESTION: previous video generation did not drain within 10000 ms before encoder restart")
            monitor.wait(remaining / 1_000_000, (remaining % 1_000_000).toInt())
        }
    }

    fun ready(track: Int, gen: Int) = synchronized(monitor) {
        if (track !in 1..2 || track == 2 && !withAudio || gen != announced[track] || ready[track] == gen)
            throw protocol(Wire.Message(Wire.STREAM_READY, 0, 0, track, gen, -1, byteArrayOf()), CaptureProtocolReason.READY_MISMATCH)
        ready[track] = gen
        readinessDeadline[track] = 0
        if (ready[1] > 0 && (!withAudio || ready[2] > 0)) started = true
        monitor.notifyAll()
    }
    fun poll(waitMs: Long): Wire.Message? = synchronized(monitor) {
        take() ?: run { monitor.wait(waitMs); take() }
    }
    /** One consumer per TCP lane, so a blocked media write never delays heartbeat/control. */
    fun pollLane(lane: Int, waitMs: Long): Wire.Message? = synchronized(monitor) {
        require(lane in 0..2)
        take(lane) ?: run { monitor.wait(waitMs); take(lane) }
    }
    fun finish(message: Wire.Message) = synchronized(monitor) {
        require(message.type in setOf(Wire.STOP, Wire.SESSION_END, Wire.ERROR))
        if (ending) return
        // Explicit session termination abandons unsent media, never normal congestion recovery.
        ending = true
        controls.clear(); video.clear(); audio.clear()
        mediaBytes = 0; controlBytes = message.payload.size; configCount = 0
        controls.add(Entry(message, clock()))
        monitor.notifyAll()
    }
    private fun take(lane: Int? = null): Wire.Message? {
        if ((lane == null || lane == 0) && controls.isNotEmpty()) {
            val entry = controls.remove()
            controlBytes -= entry.message.payload.size; pendingWrites[0] = entry; monitor.notifyAll()
            return entry.message
        }
        repeat(2) {
            val track = turn
            turn = if (turn == 1) 2 else 1
            if (lane != null && lane != track) return@repeat
            val queue = if (track == 1) video else audio
            val entry = queue.peek()
            val message = entry?.message
            if (message != null) {
                if (message.type == Wire.STREAM_CONFIG) {
                    if (announced[track] == ready[track]) {
                        queue.remove(); announced[track] = message.generation
                        readinessDeadline[track] = clock() + 10_000_000_000L
                        configCount--; controlBytes -= message.payload.size
                        pendingWrites[track] = entry; monitor.notifyAll()
                        return message
                    }
                } else if (started && ready[track] == message.generation && (track != 2 || !recoveryAudioHeld)) {
                    queue.remove(); mediaBytes -= message.payload.size
                    pendingWrites[track] = entry; monitor.notifyAll()
                    return message
                }
            }
        }
        return null
    }
    fun queuedBytes(): Int = synchronized(monitor) { mediaBytes }
    internal fun queuedSpanUs(track: Int): Long = synchronized(monitor) {
        sourceSpan(if (track == 1) video else audio)
    }
    fun allReady(): Boolean = synchronized(monitor) {
        ready[1] == generation[1] && generation[1] > 0 &&
            (!withAudio || ready[2] == generation[2] && generation[2] > 0)
    }
    fun expiredTrack(): Int? = synchronized(monitor) {
        (1..2).firstOrNull { readinessDeadline[it] != 0L && clock() >= readinessDeadline[it] }
    }
}

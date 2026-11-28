package org.screencopy.receiver.media

data class VideoFormat(
    val mime: String,
    val width: Int,
    val height: Int,
    val configuration: List<ByteArray>,
    val frameRate: Int,
    val rotation: Int = 0,
    val videoColor: org.screencopy.receiver.protocol.HdrVideoColor? = null,
    val toneMapToSdr: Boolean = false
)

data class AudioFormat(
    val codec: String,
    val sampleRate: Int,
    val channels: Int,
    val configuration: ByteArray,
    val preSkip: Int = 0,
    val outputGainQ8: Int = 0
)

data class AccessUnit(val ptsUs: Long, val bytes: ByteArray, val keyFrame: Boolean = false, val rotation: Int = 0) {
    internal var diagnosticAudioTiming: dev.redrain.cast.diagnostics.DiagnosticAudioTiming? = null
    internal var diagnosticPacketId = 0L
}

open class PlaybackException(message: String, val code: String = "DECODER_FAILED") : IllegalStateException(message)

enum class QueueLimit { PACKETS, BYTES, PTS }

data class QueuePressure(
    val track: Int,
    val limit: QueueLimit,
    val queuedPackets: Int,
    val queuedBytes: Int,
    val attemptedPackets: Int,
    val attemptedBytes: Long,
    val spanUs: Long,
    val maxPackets: Int,
    val maxBytes: Int,
    val maxSpanUs: Long,
    val waitedMs: Long = 0,
    val detectedAtNs: Long = -1,
    val incomingPtsUs: Long = -1,
    val encodedBytes: Int = -1,
    val retainedDecodedBytes: Int = -1
) {
    fun logMessage(): String = "track=$track queue_limit=$limit queued_packets=$queuedPackets queued_bytes=$queuedBytes" +
        " attempted_packets=$attemptedPackets attempted_bytes=$attemptedBytes relative_pts_span_us=$spanUs" +
        " max_packets=$maxPackets max_bytes=$maxBytes max_span_us=$maxSpanUs wait_ms=$waitedMs"
}

class QueueCongestionException(val pressure: QueuePressure) :
    PlaybackException("Playback admission stalled: ${pressure.logMessage()}", "CONGESTION")

/** Original source units are microseconds multiplied by the source sample rate. */
data class PlayedAudioCoverage(val startUnits: Long, val endUnits: Long, val sampleRate: Int) {
    init { require(sampleRate > 0 && startUnits >= 0 && endUnits >= startUnits) }
}

/** Duration and byte limits apply as well as a packet count limit. */
class PacketBudget(
    private val maxBytes: Int,
    private val maxDurationUs: Long
) {
    private val entries = ArrayDeque<Pair<Long, Int>>()
    private var bytes = 0

    @Synchronized
    fun add(packet: AccessUnit) {
        pressure(packet)?.let { throw QueueCongestionException(it) }
        entries.addLast(packet.ptsUs to packet.bytes.size)
        bytes += packet.bytes.size
    }

    @Synchronized
    fun pressure(packet: AccessUnit, track: Int = 0, maxPackets: Int = Int.MAX_VALUE, waitedMs: Long = 0,
                 retainedBytes: Int = 0): QueuePressure? {
        require(packet.ptsUs >= 0) { "Negative presentation timestamp" }
        require(retainedBytes >= 0)
        // Played proof can be invalidated by an earlier FIFO decode. It cannot
        // exempt queued dependencies from the admission duration bound.
        val pts = entries.map { it.first } + packet.ptsUs
        val minimum = pts.minOrNull() ?: 0L
        val maximum = pts.maxOrNull() ?: minimum
        val attemptedBytes = bytes.toLong() + retainedBytes + packet.bytes.size
        val limit = when {
            entries.size >= maxPackets -> QueueLimit.PACKETS
            attemptedBytes > maxBytes -> QueueLimit.BYTES
            maximum - minimum > maxDurationUs -> QueueLimit.PTS
            else -> return null
        }
        return QueuePressure(track, limit, entries.size, bytes + retainedBytes, entries.size + 1, attemptedBytes,
            maximum - minimum, maxPackets, maxBytes, maxDurationUs, waitedMs)
    }

    @Synchronized
    fun remove() {
        bytes -= entries.removeFirst().second
    }

    @Synchronized
    fun clear() { entries.clear(); bytes = 0 }
}

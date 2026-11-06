package org.screencopy.receiver.media

enum class AudioClockSource { TIMESTAMP, HEAD_UNAVAILABLE, HEAD_WARMUP, HEAD_STALE, HEAD_INVALID }
enum class AudioClockFault { CLIENT_BUFFER, HEAD_REGRESSION, HEAD_AHEAD, SUBMISSION }

data class AudioOutputTiming(
    val sampleRate: Int,
    val bufferFrames: Int,
    val submittedFrames: Long,
    val consumedFrames: Long,
    val presentedFrames: Long,
    val timestampAgeUs: Long?,
    val clockSource: AudioClockSource
) {
    val bufferedUs: Long get() = framesToUs((submittedFrames - consumedFrames).coerceAtLeast(0))
    val presentedUs: Long get() = framesToUs(presentedFrames)
    private fun framesToUs(frames: Long) = frames / sampleRate * 1_000_000 + frames % sampleRate * 1_000_000 / sampleRate
    fun logMessage() = "sample_rate=$sampleRate buffer_frames=$bufferFrames submitted_frames=$submittedFrames" +
        " consumed_frames=$consumedFrames presented_frames=$presentedFrames buffered_us=$bufferedUs" +
        " transit_us=${framesToUs((consumedFrames - presentedFrames).coerceAtLeast(0))}" +
        " timestamp_available=${timestampAgeUs != null} timestamp_age_us=${timestampAgeUs ?: 0} clock_source=$clockSource"
}

data class AudioClockDiagnostic(val fault: AudioClockFault, val timing: AudioOutputTiming, val headDeltaFrames: Long? = null) {
    fun logMessage() = "clock_reason=$fault ${timing.logMessage()}" +
        (headDeltaFrames?.let { " head_delta_frames=$it" } ?: "")

    companion object {
        internal fun fromLocalDetail(detail: String): AudioClockDiagnostic? {
            val fields = detail.substringAfter("Audio output clock invalid: ", "").split(' ').mapNotNull {
                val pair = it.split('=', limit = 2)
                if (pair.size == 2) pair[0] to pair[1] else null
            }.toMap()
            val fault = AudioClockFault.entries.firstOrNull { it.name == fields["clock_reason"] } ?: return null
            val source = AudioClockSource.entries.firstOrNull { it.name == fields["clock_source"] } ?: return null
            val rate = fields["sample_rate"]?.toIntOrNull()?.takeIf { it in 1..96000 } ?: return null
            val frames = fields["buffer_frames"]?.toIntOrNull()?.takeIf { it > 0 } ?: return null
            return AudioClockDiagnostic(fault, AudioOutputTiming(rate, frames,
                fields["submitted_frames"]?.toLongOrNull() ?: return null,
                fields["consumed_frames"]?.toLongOrNull() ?: return null,
                fields["presented_frames"]?.toLongOrNull() ?: return null,
                if (fields["timestamp_available"] == "true") fields["timestamp_age_us"]?.toLongOrNull() ?: return null else null,
                source), fields["head_delta_frames"]?.toLongOrNull())
        }
    }
}

class AudioClockException(val diagnostic: AudioClockDiagnostic) :
    PlaybackException("Audio output clock invalid: ${diagnostic.logMessage()}", "AUDIO_FAILED")

internal data class FrameTimestamp(val frames: Long, val nanoTime: Long)

/** Client consumption controls capacity; a qualified DAC timestamp controls A/V presentation. */
internal class AudioPosition(
    private val rate: Int,
    private val bufferFrames: Int,
    private val nanoTime: () -> Long,
    private val timestamp: () -> FrameTimestamp?,
    private val playbackHead: () -> Int
) {
    private var submitted = 0L
    private var consumed = 0L
    private var previousHead = 0L
    private var presented = 0L
    private var previousTimestamp: FrameTimestamp? = null
    private var trustedTimestamp: FrameTimestamp? = null
    private var transitFrames = 0L
    private var source = AudioClockSource.HEAD_UNAVAILABLE
    private var ageUs: Long? = null
    private var speed = 1f

    @Synchronized fun written(frames: Int) {
        if (frames < 0) fail(AudioClockFault.SUBMISSION)
        submitted = Math.addExact(submitted, frames.toLong())
    }

    @Synchronized fun speed(value: Float) { speed = value }

    @Synchronized fun current(): AudioOutputTiming {
        // Android requires the presentation counter to be read before the consumption counter.
        val stamp = timestamp()
        val head = playbackHead().toLong() and 0xffffffffL
        val now = nanoTime()
        ageUs = stamp?.let { (now - it.nanoTime) / 1000 }
        val delta = (head - previousHead) and 0xffffffffL
        if (delta > Int.MAX_VALUE) {
            source = AudioClockSource.HEAD_INVALID
            fail(AudioClockFault.HEAD_REGRESSION, headDelta = delta - 0x100000000L)
        }
        val nextConsumed = Math.addExact(consumed, delta)
        if (nextConsumed > submitted) {
            source = AudioClockSource.HEAD_INVALID
            fail(AudioClockFault.HEAD_AHEAD, nextConsumed, delta)
        }
        previousHead = head
        consumed = nextConsumed
        if (submitted - consumed > bufferFrames) fail(AudioClockFault.CLIENT_BUFFER)

        source = AudioClockSource.HEAD_UNAVAILABLE
        var estimate = (consumed - transitFrames).coerceAtLeast(0)
        if (stamp != null) {
            // Native timestamps use a wrapping 32-bit position in the same client frame domain.
            var frames = stamp.frames
            if (frames in 0..0xffffffffL) {
                frames += consumed and -0x100000000L
                if (frames > consumed) frames -= 0x100000000L
            }
            val candidate = FrameTimestamp(frames, stamp.nanoTime)
            val previous = previousTimestamp
            source = when {
                frames < 0 || frames > consumed || stamp.nanoTime > now -> AudioClockSource.HEAD_INVALID
                now - stamp.nanoTime > 100_000_000L -> AudioClockSource.HEAD_STALE
                previous != null && (frames < previous.frames || stamp.nanoTime < previous.nanoTime) -> AudioClockSource.HEAD_INVALID
                candidate == trustedTimestamp ||
                    previous != null && frames > previous.frames && stamp.nanoTime > previous.nanoTime -> AudioClockSource.TIMESTAMP
                else -> AudioClockSource.HEAD_WARMUP
            }
            if (source == AudioClockSource.TIMESTAMP) {
                val elapsedFrames = ((now - stamp.nanoTime) * rate / 1_000_000_000.0 * speed).toLong()
                estimate = (frames + elapsedFrames).coerceIn(0, consumed)
                transitFrames = consumed - estimate
                trustedTimestamp = candidate
            }
            if (source != AudioClockSource.HEAD_INVALID) previousTimestamp = candidate
        }
        // On timestamp loss/staleness, keep the learned hardware transit instead of jumping to head.
        presented = maxOf(presented, estimate).coerceAtMost(consumed)
        return timing()
    }

    private fun timing(head: Long = consumed) =
        AudioOutputTiming(rate, bufferFrames, submitted, head, presented, ageUs, source)

    private fun fail(fault: AudioClockFault, head: Long = consumed, headDelta: Long? = null): Nothing =
        throw AudioClockException(AudioClockDiagnostic(fault, timing(head), headDelta))
}

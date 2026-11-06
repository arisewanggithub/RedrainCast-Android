package org.screencopy.receiver.media

data class AudioClockCorrection(
    val sequence: Long,
    val beforeOffsetUs: Long,
    val afterOffsetUs: Long,
    val appliedOffsetUs: Long
) {
    val deltaUs: Long get() = Math.subtractExact(afterOffsetUs, beforeOffsetUs)
    fun logMessage() = "event=AUDIO_SOURCE_CLOCK_ADJUST stage=SOURCE_MAP sequence=$sequence" +
        " before_offset_us=$beforeOffsetUs after_offset_us=$afterOffsetUs" +
        " delta_us=$deltaUs applied_offset_us=$appliedOffsetUs"
}

/** Source anchors follow presented samples, never the newest packet still buffered at the output. */
internal class AudioSourceClock(private val firstPtsUs: Long, sampleRate: Int) {
    init { require(firstPtsUs >= 0 && sampleRate > 0) { "Invalid audio source clock format" } }
    private data class Anchor(val sampleUs: Long, val offsetUs: Long)
    private val anchors = ArrayDeque<Anchor>().apply { add(Anchor(0, 0)) }
    private val maxAnchors = sampleRate / 4 + 2
    private var submittedUs = 0L
    private var playedUs = 0L
    private var appliedOffsetUs = 0L
    private var slewRemainder = 0L
    private var lastPacketSampleUs = -1L
    private var lastObservedOffsetUs = 0L
    private var sequence = 0L

    fun packet(sampleUs: Long, ptsUs: Long): AudioClockCorrection? {
        require(sampleUs >= 0 && ptsUs >= 0) { "Negative audio timeline position" }
        if (sampleUs <= lastPacketSampleUs || sampleUs < playedUs)
            throw PlaybackException("Audio sample position decreased within a generation")
        val offset = Math.subtractExact(Math.subtractExact(ptsUs, firstPtsUs), sampleUs)
        val before = lastObservedOffsetUs
        if (anchors.last().offsetUs != offset) {
            if (anchors.size >= maxAnchors) throw PlaybackException("Audio clock history exceeded the bounded output window", "CONGESTION")
            anchors.addLast(Anchor(sampleUs, offset))
        }
        lastPacketSampleUs = sampleUs
        lastObservedOffsetUs = offset
        sequence++
        return if (offset == before) null else AudioClockCorrection(sequence, before, offset, appliedOffsetUs)
    }

    fun written(sampleUs: Long) {
        require(sampleUs >= maxOf(submittedUs, playedUs)) { "Audio submitted sample position decreased" }
        submittedUs = sampleUs
    }

    fun current(positionUs: Long): Long {
        require(positionUs >= 0) { "Audio presented sample position is negative" }
        val presented = maxOf(playedUs, minOf(positionUs, submittedUs))
        while (anchors.size > 1 && anchors.elementAt(1).sampleUs <= presented) {
            advance(anchors.elementAt(1).sampleUs)
            anchors.removeFirst()
        }
        advance(presented)
        return Math.addExact(firstPtsUs, Math.addExact(playedUs, appliedOffsetUs))
    }

    fun bufferedUs(): Long = (submittedUs - playedUs).coerceAtLeast(0)
    internal fun pendingAnchors(): Int = anchors.size

    private fun advance(positionUs: Long) {
        check(positionUs >= playedUs) { "Audio source mapping attempted to rewind presented samples" }
        val elapsed = positionUs - playedUs
        // Capture-clock phase slew is separate from resampler frame-count compensation.
        val credit = elapsed + slewRemainder
        val maximum = credit / 50
        slewRemainder = credit % 50
        val difference = Math.subtractExact(anchors.first().offsetUs, appliedOffsetUs)
        appliedOffsetUs = Math.addExact(appliedOffsetUs, difference.coerceIn(-maximum, maximum))
        playedUs = positionUs
    }
}

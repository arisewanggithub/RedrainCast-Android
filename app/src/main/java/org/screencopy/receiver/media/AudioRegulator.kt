package org.screencopy.receiver.media

import kotlin.math.abs

data class AudioRegulationDiagnostic(
    val bufferedFrames: Int, val targetFrames: Int, val limitFrames: Int,
    val averageFrames: Double, val speed: Float, val sourceFrames: Long,
    val silenceFrames: Long, val droppedFrames: Long, val recoveries: Long,
    val compensationSamples: Int = 0, val compensationDistance: Int = 0
) {
    fun logMessage() = "event=AUDIO_REGULATION buffered_frames=$bufferedFrames target_frames=$targetFrames" +
        " limit_frames=$limitFrames average_frames=${averageFrames.toLong()} equivalent_speed=$speed" +
        " compensation_samples=$compensationSamples compensation_distance=$compensationDistance native_speed=1.0" +
        " source_frames=$sourceFrames inserted_silence_frames=$silenceFrames dropped_output_frames=$droppedFrames" +
        " source_recoveries=$recoveries"
}

internal data class AudioChunk(val samples: ShortArray, val offset: Int, val frames: Int, val ptsUs: Long)
internal data class AudioCompensation(val samples: Int, val distance: Int)

/** Decoded FIFO only: compressed packets always retain their codec dependencies. */
internal class AudioRegulator(private val rate: Int, private val channels: Int) {
    private data class Entry(val samples: ShortArray, val slice: TrimmedAudio, val sourceUnits: Long, var used: Int = 0)
    private val queue = ArrayDeque<Entry>()
    val targetFrames = rate * 50 / 1000
    private val startupLimit = targetFrames + rate * 10 / 1000
    private val playingLimit = targetFrames * 11 / 10 + rate * 60 / 1000
    var bufferedFrames = 0
        private set
    var started = false
        private set
    private var average = targetFrames.toDouble()
    private var convertedSinceCorrection = 0L
    private var compensating = false
    private var empty = false
    private var source = 0L
    private var silence = 0L
    private var dropped = 0L
    internal val discardedFrames: Long get() = dropped
    private var recoveries = 0L
    private var speed = 1f
    private var lastCompensation = AudioCompensation(0, 0)

    init { require(rate in 1..96000 && channels in 1..2) }
    fun offer(samples: ShortArray, slice: TrimmedAudio,
        sourceUnits: Long = slice.frames * 1_000_000L, observe: Boolean = true) {
        require(slice.skipFrames >= 0 && slice.frames > 0 &&
            (slice.skipFrames.toLong() + slice.frames) * channels <= samples.size)
        require(sourceUnits >= 0)
        queue.addLast(Entry(samples, slice, sourceUnits))
        bufferedFrames += slice.frames
        val excess = (bufferedFrames - limit()).coerceAtLeast(0)
        if (excess > 0) {
            discard(excess)
            dropped += excess
            average = (average - excess).coerceAtLeast(0.0)
        }
        if (!started && bufferedFrames >= targetFrames) started = true
        if (observe && started) average += (bufferedFrames - average) / 128
    }
    fun peek(maxFrames: Int): AudioChunk? {
        require(maxFrames > 0)
        if (!started || queue.isEmpty()) return null
        val first = queue.first()
        return AudioChunk(first.samples, (first.slice.skipFrames + first.used) * channels,
            minOf(maxFrames, first.slice.frames - first.used),
            first.slice.audiblePtsUs + first.used * first.sourceUnits / first.slice.frames / rate)
    }
    fun sourceUnits(frames: Int): Long {
        val first = queue.first()
        require(frames in 1..first.slice.frames - first.used)
        return (first.used + frames) * first.sourceUnits / first.slice.frames -
            first.used * first.sourceUnits / first.slice.frames
    }
    fun startAfterPreroll() { started = true }
    fun take(maxFrames: Int): AudioChunk? = peek(maxFrames)?.also { discard(it.frames) }
    fun consumed(frames: Int) {
        require(frames in 1..bufferedFrames)
        discard(frames)
        sourceWritten(frames)
    }
    fun sourceWritten(frames: Int) {
        require(frames > 0)
        source += frames
        if (empty) { recoveries++; empty = false }
    }
    fun insertedSilence(frames: Int) {
        require(frames > 0)
        silence += frames
        if (started) {
            empty = true
            average += frames
        }
    }
    fun converted(inputFrames: Int, outputFrames: Int): AudioCompensation? {
        require(inputFrames > 0 && outputFrames >= 0)
        average = (average + outputFrames - inputFrames).coerceAtLeast(0.0)
        if (started) average += (bufferedFrames - average) / 128
        convertedSinceCorrection += outputFrames
        if (convertedSinceCorrection < rate || !started) return null
        convertedSinceCorrection %= rate
        var difference = targetFrames - average
        val deadband = rate * (if (compensating) 1 else 4) / 1000.0
        if (abs(difference) < deadband || difference < 0 && bufferedFrames < targetFrames) difference = 0.0
        val distance = rate * 4
        val correction = difference.coerceIn(-distance / 50.0, distance / 50.0).toInt()
        speed = 1f - correction.toFloat() / distance
        compensating = correction != 0
        return AudioCompensation(correction, if (compensating) distance else 0).also { lastCompensation = it }
    }
    fun diagnostic() = AudioRegulationDiagnostic(bufferedFrames, targetFrames, limit(), average, speed,
        source, silence, dropped, recoveries, lastCompensation.samples, lastCompensation.distance)
    fun clear() {
        queue.clear()
        bufferedFrames = 0
        started = false
        average = targetFrames.toDouble()
        convertedSinceCorrection = 0
        compensating = false
        empty = false
        source = 0
        silence = 0
        dropped = 0
        recoveries = 0
        speed = 1f
        lastCompensation = AudioCompensation(0, 0)
    }
    private fun limit() = if (started) playingLimit else startupLimit
    private fun discard(frames: Int) {
        var remaining = frames
        while (remaining > 0) {
            val first = queue.first()
            val count = minOf(remaining, first.slice.frames - first.used)
            first.used += count
            remaining -= count
            bufferedFrames -= count
            if (first.used == first.slice.frames) queue.removeFirst()
        }
    }
}

/** Device frames include underrun silence; source presentation must not. */
internal class AudioPresentationTimeline(private val rate: Int) {
    private data class Span(val deviceStart: Long, val deviceEnd: Long, val sourceStart: Long, val sourceEnd: Long)
    private val spans = ArrayDeque<Span>()
    private var deviceWritten = 0L
    private var sourceWritten = 0L
    private var sourcePresented = 0L
    @Synchronized fun write(source: Boolean, channels: Int, frames: Int,
        sourceUnits: (Int) -> Long = { it * 1_000_000L }, operation: () -> Int): Int {
        val samples = operation()
        check(samples >= 0) { "AudioTrack write failed ($samples)" }
        check(samples <= frames * channels && samples % channels == 0) { "AudioTrack returned an invalid sample count" }
        if (samples > 0) submittedUnits(samples / channels, if (source) sourceUnits(samples / channels) else 0)
        return samples
    }
    @Synchronized fun presentedUs(position: () -> Long): Long = presentedUs(position())
    @Synchronized fun submitted(frames: Int, source: Boolean) {
        submittedUnits(frames, if (source) frames * 1_000_000L else 0)
    }
    private fun submittedUnits(frames: Int, units: Long) {
        require(frames > 0)
        require(units >= 0)
        if (spans.size >= rate / 4 + 2) throw PlaybackException("Audio presentation history exceeded its bound", "CONGESTION")
        val endDevice = Math.addExact(deviceWritten, frames.toLong())
        val endSource = Math.addExact(sourceWritten, units)
        spans.addLast(Span(deviceWritten, endDevice, sourceWritten, endSource))
        deviceWritten = endDevice
        sourceWritten = endSource
    }
    @Synchronized fun presentedUs(deviceFrames: Long): Long {
        require(deviceFrames in 0..deviceWritten)
        while (spans.isNotEmpty() && spans.first().deviceEnd <= deviceFrames) {
            sourcePresented = maxOf(sourcePresented, spans.removeFirst().sourceEnd)
        }
        spans.firstOrNull()?.let {
            val current = it.sourceStart + (deviceFrames - it.deviceStart) *
                (it.sourceEnd - it.sourceStart) / (it.deviceEnd - it.deviceStart)
            sourcePresented = maxOf(sourcePresented, current)
        }
        return sourcePresented / rate
    }
    @Synchronized fun clear() {
        spans.clear()
        deviceWritten = 0
        sourceWritten = 0
        sourcePresented = 0
    }
}

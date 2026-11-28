package org.screencopy.receiver.media

/**
 * Debug experiment only: a bounded virtual FIFO advances at nominal sample rate.
 * No PCM is retained and none of these counters represents physical playback.
 */
internal class VirtualDiscardAudioOutput(
    private val rate: Int,
    private val channels: Int,
    private val nanoTime: () -> Long = System::nanoTime
) : AudioOutput {
    init {
        require(rate in 1..96000 && channels in 1..2)
    }

    private val capacity = maxOf(1, rate / 10)
    private var submitted = 0L
    private var consumed = 0L
    private var lastNs = nanoTime()
    private var fraction = 0L
    private var playing = false
    private var closed = false

    private fun advance() {
        val now = nanoTime()
        val elapsed = (now - lastNs).coerceIn(0, 1_000_000_000L)
        lastNs = now
        val queued = submitted - consumed
        if (!playing || queued == 0L) {
            fraction = 0
            return
        }
        val units = elapsed * rate + fraction
        val frames = minOf(queued, units / 1_000_000_000L)
        consumed += frames
        fraction = if (frames == queued) 0 else units % 1_000_000_000L
    }

    override val underrunCount: Int get() = 0
    override val playbackActive: Boolean? get() = null

    @Synchronized override fun play() {
        check(!closed)
        advance()
        playing = true
    }

    @Synchronized override fun pause() {
        if (closed) return
        advance()
        playing = false
    }

    @Synchronized override fun write(samples: ShortArray, offset: Int, count: Int): Int {
        check(!closed)
        require(offset >= 0 && count >= 0 && offset <= samples.size && count <= samples.size - offset)
        require(offset % channels == 0 && count % channels == 0)
        advance()
        val frames = minOf(count / channels, (capacity - (submitted - consumed)).toInt())
        submitted = Math.addExact(submitted, frames.toLong())
        return frames * channels
    }

    @Synchronized override fun timing(): AudioOutputTiming {
        check(!closed)
        advance()
        return AudioOutputTiming(rate, capacity, submitted, consumed, consumed, null,
            AudioClockSource.HEAD_UNAVAILABLE, positionAtNs = lastNs, virtualDiscard = true)
    }

    override fun setSpeed(speed: Float) {
        require(speed == 1f) { "Virtual discard only supports nominal pacing" }
    }

    @Synchronized override fun close() {
        if (!closed) {
            advance()
            playing = false
            closed = true
        }
    }
}

package org.screencopy.receiver.media

/** sourceUnits are millionths of an original source sample, independent of output sample count. */
internal data class MappedPcm(val offsetFrames: Int, val frames: Int, val ptsUs: Long, val sourceUnits: Long)

/** Carries original source anchors through filter lookahead and changing output sample counts. */
internal class ResampledAudioTimeline(private val rate: Int) {
    private data class Input(val frames: Int, val ptsUs: Long, var used: Int = 0)
    private val pending = ArrayDeque<Input>()
    private var accepted = 0L
    private var mapped = 0L
    @Volatile var retainedFrames = 0
        private set

    fun offer(frames: Int, ptsUs: Long) {
        require(frames > 0 && ptsUs >= 0)
        if (pending.size >= 256) throw PlaybackException("Resampler source history exceeded its bound", "CONGESTION")
        pending.add(Input(frames, ptsUs))
        accepted = Math.addExact(accepted, frames.toLong())
        retainedFrames = Math.toIntExact(accepted - mapped)
    }

    fun output(frames: Int, delayFrames: Int): List<MappedPcm> {
        require(frames >= 0 && delayFrames >= 0)
        if (delayFrames > rate * 120 / 1000)
            throw PlaybackException("Resampler filter delay exceeded 120 ms", "CONGESTION")
        if (frames == 0) return emptyList()
        val released = accepted - delayFrames - mapped
        if (released <= 0 || released > rate * 120 / 1000 + 256)
            throw PlaybackException("Resampler returned invalid source/output accounting")
        val result = mutableListOf<MappedPcm>()
        var consumed = 0L
        var output = 0
        var carryUnits = 0L
        var carryPts: Long? = null
        while (consumed < released) {
            val input = pending.firstOrNull() ?: throw PlaybackException("Resampler exhausted source timestamp history")
            val take = minOf(released - consumed, (input.frames - input.used).toLong()).toInt()
            val first = input.used
            input.used += take
            consumed += take
            val end = (consumed * frames / released).toInt()
            val pts = Math.addExact(input.ptsUs, first * 1_000_000L / rate)
            carryUnits += take * 1_000_000L
            if (carryPts == null) carryPts = pts
            if (end > output) {
                result.add(MappedPcm(output, end - output, checkNotNull(carryPts), carryUnits))
                carryUnits = 0
                carryPts = null
            }
            output = end
            if (input.used == input.frames) pending.removeFirst()
        }
        mapped += released
        retainedFrames = Math.toIntExact(accepted - mapped)
        check(output == frames)
        check(carryUnits == 0L)
        return result
    }
}

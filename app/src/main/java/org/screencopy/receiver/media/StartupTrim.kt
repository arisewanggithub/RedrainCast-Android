package org.screencopy.receiver.media

data class TrimmedAudio(val skipFrames: Int, val frames: Int, val audiblePtsUs: Long)

class StartupTrim(preSkip: Int, private val sampleRate: Int) {
    private var remaining = preSkip

    init {
        require(preSkip in 0..65535 && sampleRate > 0) { "Invalid audio startup metadata" }
    }

    fun apply(frames: Int, ptsUs: Long): TrimmedAudio {
        require(frames >= 0 && ptsUs >= 0) { "Invalid audio packet timing" }
        val skip = minOf(remaining, frames)
        remaining -= skip
        return TrimmedAudio(skip, frames - skip, ptsUs + skip * 1_000_000L / sampleRate)
    }
}

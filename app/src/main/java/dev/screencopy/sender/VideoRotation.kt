package dev.screencopy.sender

import java.io.IOException
import java.util.ArrayDeque

internal object VideoRotation {
    fun degrees(settings: Settings, source: CaptureDisplay): Int {
        val turns = settings.orientation.removePrefix("@").toInt() / 90
        val rotation = if (settings.orientation.startsWith("@")) 0 else checkNotNull(source.rotation)
        // Android display rotation is counterclockwise; wire presentation turns are clockwise.
        return (turns - rotation + 4) % 4 * 90
    }
}

/** Match GPU capture orientation to encoded PTS, including encoder-generated repeated frames. */
internal class VideoRotationTimeline {
    private val changes = ArrayDeque<Pair<Long, Int>>()
    private var lastPts = -1L
    private var lastRotation = -1
    private var current = -1

    @Synchronized fun offer(pts: Long, rotation: Int) {
        if (pts < 0 || pts < lastPts || rotation !in listOf(0, 90, 180, 270))
            throw IOException("CAPTURE_FAILED: invalid video rotation metadata")
        if (rotation != lastRotation) {
            if (changes.size >= 1024) throw IOException("CONGESTION: video rotation metadata is not being consumed")
            changes.addLast(pts to rotation)
        }
        lastPts = pts; lastRotation = rotation
    }

    @Synchronized fun at(pts: Long): Int {
        while (changes.peekFirst()?.first?.let { it <= pts } == true) current = changes.removeFirst().second
        if (current < 0) throw IOException("CAPTURE_FAILED: encoded video preceded rotation metadata")
        return current
    }
}

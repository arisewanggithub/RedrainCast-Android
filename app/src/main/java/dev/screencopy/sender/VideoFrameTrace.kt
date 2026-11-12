package dev.screencopy.sender

import java.io.Writer

internal enum class VideoFrameStage {
    CAPTURE_CALLBACK, CAPTURE_ACQUIRED, CAPTURE_FILTERED, SUBMIT_BEGIN, SUBMIT_END,
    ENCODED, SINK_RETURNED, WRITE_BEGIN, WRITE_END
}

internal data class VideoFrameEvent(
    val sequence: Int, val atNs: Long, val stage: VideoFrameStage, val ptsUs: Long, val generation: Int
)

internal data class VideoFrameSnapshot(
    val session: Long, val startedNs: Long, val stoppedNs: Long, val reason: String,
    val capacity: Int, val dropped: Int, val events: List<VideoFrameEvent>
) {
    fun writeTo(writer: Writer) {
        writer.appendLine("""{"kind":"header","session":$session,"frequency":1000000000,"startedNs":$startedNs,"capacity":$capacity}""")
        for (event in events) writer.appendLine(
            """{"kind":"event","sequence":${event.sequence},"atNs":${event.atNs},"stage":"${event.stage}","ptsUs":${event.ptsUs},"generation":${event.generation}}""")
        writer.appendLine("""{"kind":"summary","reason":"$reason","stoppedNs":$stoppedNs,"events":${events.size},"dropped":$dropped,"windowComplete":${reason == "duration" && dropped == 0}}""")
    }
}

internal class VideoFrameTrace(
    private val enabled: Boolean,
    private val session: Long,
    private val now: () -> Long = System::nanoTime,
    private val capacity: Int = 16_384,
    private val durationNs: Long = 30_000_000_000
) {
    companion object {
        const val LONG_DURATION_NS = 600_000_000_000L
        const val LONG_CAPACITY = 1_440_000
    }

    private val events = ArrayList<VideoFrameEvent>()
    private var startedNs: Long? = null
    private var stoppedNs = 0L
    private var reason: String? = null
    private var dropped = 0
    private var published = false

    init { require(capacity > 0 && durationNs > 0) }

    fun record(stage: VideoFrameStage, ptsUs: Long = -1, generation: Int = 0) {
        if (!enabled) return
        synchronized(this) {
            if (reason != null || published) return
            val at = now()
            val start = startedNs ?: at.also { startedNs = it }
            if (at - start >= durationNs) {
                reason = "duration"
                stoppedNs = at
            } else if (events.size == capacity) {
                reason = "capacity"
                stoppedNs = at
                dropped++
            } else events.add(VideoFrameEvent(events.size, at, stage, ptsUs, generation))
        }
    }

    @Synchronized fun takeCompleted(force: Boolean = false): VideoFrameSnapshot? {
        if (!enabled || published) return null
        val start = startedNs ?: return null
        val at = now()
        if (reason == null) {
            if (at - start >= durationNs) reason = "duration"
            else if (force) reason = "session_end"
            else return null
            stoppedNs = at
        }
        published = true
        return VideoFrameSnapshot(session, start, stoppedNs, checkNotNull(reason), capacity, dropped, events.toList())
            .also { events.clear() }
    }
}

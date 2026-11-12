package org.screencopy.receiver.media

internal class AudioEventHistory {
    private data class Event(val ns: Long, val kind: String, val a: Long, val b: Long)
    private val events = ArrayDeque<Event>()
    private var lastRefillNs: Long? = null
    private var lastDumpNs: Long? = null
    private var previousUnderruns: Int? = null
    private var pendingUnderruns = 0L
    private var sequence = 0L
    private var overwritten = 0L

    @Synchronized fun record(ns: Long, kind: String, a: Long, b: Long) {
        if (events.size == 512) { events.removeFirst(); overwritten++ }
        events.addLast(Event(ns, kind, a, b))
    }

    @Synchronized fun refill(ns: Long, paddingUs: Long, pcmFrames: Int) {
        if (paddingUs > 5000 && lastRefillNs?.let { ns - it < 5_000_000 } == true) return
        lastRefillNs = ns
        record(ns, "refill", paddingUs, pcmFrames.toLong())
    }

    @Synchronized fun start(count: Int) { previousUnderruns = count }

    @Synchronized fun dump(ns: Long, count: Int): List<String> {
        previousUnderruns?.let { pendingUnderruns += (count.toLong() - it).coerceAtLeast(0) }
        previousUnderruns = count
        if (pendingUnderruns == 0L || lastDumpNs?.let { ns - it < 1_000_000_000 } == true)
            return emptyList()
        sequence++
        lastDumpNs = ns
        val result = mutableListOf("event=AUDIO_UNDERRUN_HISTORY id=$sequence mono_ns=$ns" +
            " native_delta=$pendingUnderruns native_count=$count entries=${events.size} overwritten=$overwritten")
        // Separate bounded records avoid Android logcat's per-message truncation.
        events.toList().chunked(8).forEachIndexed { index, chunk ->
            result.add("event=AUDIO_HISTORY id=$sequence part=$index data=" + chunk.joinToString(";") {
                "${(it.ns - ns) / 1000},${it.kind},${it.a},${it.b}"
            })
        }
        pendingUnderruns = 0
        return result
    }
}

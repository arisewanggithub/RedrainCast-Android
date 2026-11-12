package org.screencopy.receiver.media

/** Worker-local aggregates; wall minus CPU includes waits/locks/GC, not just scheduler delay. */
internal class AudioServiceDiagnostic(private val rate: Int) {
    private data class Duration(val wallNs: Long, val cpuNs: Long) {
        fun fields(name: String) = "${name}_wall_us=${wallNs / 1000} ${name}_cpu_us=${cpuNs / 1000}" +
            " ${name}_noncpu_us=${(wallNs - cpuNs).coerceAtLeast(0) / 1000}"
    }

    var started = false
        private set
    private var startNs = 0L
    private var windowNs = 0L
    private var previousUnderruns = 0
    private var totalUnderruns = 0L
    private var windowUnderruns = 0L
    private var counterResets = 0L
    private var steps = 0L
    private var slowSteps = 0L
    private val longest = mutableMapOf<String, Duration>()
    private var previousRefillNs: Long? = null
    private var previousPaddingUs = 0L
    private var maxRefillGapNs = 0L
    private var gapPreviousPaddingUs = 0L
    private var gapPcmFrames = 0
    private var gapArrivalAgeUs: Long? = null
    private var minimumPaddingUs = Long.MAX_VALUE
    private var minimumPaddingPcmFrames = 0
    private var zeroWrites = 0L
    private var silenceFrames = 0L
    private var silenceEpisodes = 0L
    private var inSilence = false
    private var starvationSamples = 0L
    private var queuedStarvationSamples = 0L
    private var lastDiscardedFrames = 0L

    fun start(nowNs: Long, underruns: Int) {
        started = true
        startNs = nowNs
        windowNs = nowNs
        previousUnderruns = underruns
    }

    fun duration(stage: String, wallNs: Long, cpuNs: Long) {
        if (!started) return
        val wall = wallNs.coerceAtLeast(0)
        if (wall > (longest[stage]?.wallNs ?: -1))
            longest[stage] = Duration(wall, cpuNs.coerceAtLeast(0))
        if (stage == "step") {
            steps++
            if (wallNs >= 5_000_000) slowSteps++
        }
    }

    fun beforeRefill(nowNs: Long, paddingUs: Long, pcmFrames: Int, arrivalAgeUs: Long?) {
        if (!started) return
        previousRefillNs?.let { previous ->
            val gap = nowNs - previous
            if (gap > maxRefillGapNs) {
                maxRefillGapNs = gap
                gapPreviousPaddingUs = previousPaddingUs
                gapPcmFrames = pcmFrames
                gapArrivalAgeUs = arrivalAgeUs
            }
        }
        previousRefillNs = nowNs
        previousPaddingUs = paddingUs
        if (paddingUs < minimumPaddingUs) {
            minimumPaddingUs = paddingUs
            minimumPaddingPcmFrames = pcmFrames
        }
    }

    fun wrote(frames: Int, source: Boolean) {
        if (!started) return
        if (frames == 0) { zeroWrites++; return }
        if (!source) {
            silenceFrames += frames
            if (!inSilence) silenceEpisodes++
        }
        inSilence = !source
    }

    fun observe(underruns: Int, paddingFrames: Long, pcmFrames: Int, queued: Int, held: Boolean) {
        if (!started) return
        val delta = (underruns.toLong() - previousUnderruns).coerceAtLeast(0)
        if (underruns < previousUnderruns) counterResets++
        previousUnderruns = underruns
        totalUnderruns += delta
        windowUnderruns += delta
        if (paddingFrames == 0L && pcmFrames == 0) {
            starvationSamples++
            if (queued > 0 || held) queuedStarvationSamples++
        }
    }

    fun report(nowNs: Long, discardedFrames: Long, final: Boolean = false): String? {
        if (!started || nowNs <= windowNs || !final && nowNs - windowNs < 5_000_000_000L) return null
        val message = "event=AUDIO_SERVICE_WINDOW elapsed_us=${(nowNs - startNs) / 1000}" +
            " window_us=${(nowNs - windowNs) / 1000} final=$final steps=$steps slow_steps_ge_5ms=$slowSteps" +
            " native_underruns=$windowUnderruns native_underruns_total=$totalUnderruns counter_resets=$counterResets" +
            " inserted_silence_frames=$silenceFrames silence_episodes=$silenceEpisodes" +
            " discarded_pcm_frames=${discardedFrames - lastDiscardedFrames} sample_rate=$rate zero_writes=$zeroWrites" +
            " max_refill_gap_us=${maxRefillGapNs / 1000} gap_previous_pre_padding_us=$gapPreviousPaddingUs" +
            " gap_pcm_frames=$gapPcmFrames gap_last_arrival_age_us=${gapArrivalAgeUs ?: "unknown"}" +
            " min_padding_us=${if (minimumPaddingUs == Long.MAX_VALUE) "unknown" else minimumPaddingUs}" +
            " min_padding_pcm_frames=$minimumPaddingPcmFrames" +
            " empty_observations=$starvationSamples empty_with_pending_input=$queuedStarvationSamples" +
            listOf("step", "input", "decode", "refill").joinToString("") { stage ->
                " " + (longest[stage] ?: Duration(0, 0)).fields("max_$stage")
            }
        windowNs = nowNs
        lastDiscardedFrames = discardedFrames
        windowUnderruns = 0
        counterResets = 0
        steps = 0
        slowSteps = 0
        longest.clear()
        maxRefillGapNs = 0
        gapPreviousPaddingUs = 0
        gapPcmFrames = 0
        gapArrivalAgeUs = null
        minimumPaddingUs = Long.MAX_VALUE
        minimumPaddingPcmFrames = 0
        zeroWrites = 0
        silenceFrames = 0
        silenceEpisodes = 0
        starvationSamples = 0
        queuedStarvationSamples = 0
        return message
    }
}

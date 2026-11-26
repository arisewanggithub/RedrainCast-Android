package org.screencopy.receiver.media

internal data class VideoSchedulingClock(
    val delayUs: Long?, val readAtNs: Long, val mappingRevision: Long,
    val mode: String, val positionUs: Long?, val sourcePositionUs: Long?,
    val audioObservationSequence: Long?, val audioObservationAtNs: Long?,
    val audioOutputSource: AudioClockSource?, val audioTimestampAgeUs: Long?,
    val observedAppliedOffsetUs: Long?, val lastPacketCorrection: AudioClockCorrection?,
    val recoveringAudio: Boolean
)

internal data class AudioStartDecision(
    val reason: Int, val mode: Int, val targetUs: Long, val positionUs: Long?,
    val delayUs: Long?, val firstVideoUs: Long?, val releasedVideoUs: Long?,
    val waitedUs: Long, val revision: Long, val flags: Long
)

class PlaybackClock(
    private val nanoTime: () -> Long = System::nanoTime,
    private val sleepMs: (Long) -> Unit = Thread::sleep
) {
    private var basePtsUs = -1L
    private var baseNs = 0L
    private var audioPositionUs: (() -> Long)? = null
    private var audioSource: AudioSourceClock? = null
    private var lastPositionUs: Long? = null
    @Volatile private var observedAudio: SourceTimestampObservation? = null
    private var audioObservationSequence = 0L
    private var observedOutputTiming: AudioOutputTiming? = null
    private var observedAppliedOffsetUs: Long? = null
    private var lastPacketCorrection: AudioClockCorrection? = null
    private var audioProjection: ((Long) -> Long?)? = null
    private var lastVideoPositionUs: Long? = null
    private var releasedVideoUs: Long? = null
    internal fun cachedPlayedAudioSource(): Long? = observedAudio?.ptsUs
    internal fun cachedPlayedAudioObservation(): SourceTimestampObservation? = observedAudio
    @Volatile var audioExpected = false
    @Volatile internal var recoveryStartup = false
    private var firstVideoPtsUs: Long? = null
    private var startupAtNs: Long? = null
    private var audioStarted = false
    @Volatile private var resumingAudio = false
    private var recoveringAudio = false
    private var firstVideoAtNs: Long? = null
    private var prerollEpochUs: Long? = null
    private var videoPrerollEnded = false
    private var mappingRevision = 0L
    @Synchronized internal fun mappingRevision(): Long = mappingRevision

    @Synchronized
    fun anchorVideo(ptsUs: Long) {
        if (firstVideoPtsUs == null) {
            firstVideoPtsUs = ptsUs
            firstVideoAtNs = nanoTime()
            if (audioExpected && !audioStarted && prerollEpochUs?.let { ptsUs > it } == true) {
                prerollEpochUs = ptsUs
                basePtsUs = -1
                mappingRevision++
            }
        }
        noteMedia()
        if (!audioExpected) anchor(ptsUs)
    }

    @Synchronized
    fun noteAudioPacket() = noteMedia()

    @Synchronized
    fun checkStartup() {
        if (!recoveryStartup && !recoveringAudio && audioExpected && !audioStarted &&
            startupAtNs?.let { nanoTime() - it >= 3_000_000_000L } == true)
            throw PlaybackException("Audio/video startup did not complete within three seconds")
    }

    @Synchronized
    fun prepareAudio(slice: TrimmedAudio, rate: Int): TrimmedAudio? {
        if (!audioExpected || audioStarted) return slice
        if (resumingAudio) {
            if (basePtsUs < 0) {
                basePtsUs = slice.audiblePtsUs
                baseNs = nanoTime() + 50_000_000L
                mappingRevision++
            }
            return slice
        }
        checkStartup()
        val videoPts = firstVideoPtsUs ?: return null
        val epoch = maxOf(videoPts, slice.audiblePtsUs)
        if (epoch - minOf(videoPts, slice.audiblePtsUs) > 3_000_000)
            throw PlaybackException("Initial track timestamps exceed the three-second synchronization budget")
        val skip = minOf(slice.frames.toLong(), ((epoch - slice.audiblePtsUs) * rate + 999_999) / 1_000_000).toInt()
        val result = TrimmedAudio(slice.skipFrames + skip, slice.frames - skip, slice.audiblePtsUs + skip * 1_000_000L / rate)
        prerollEpochUs = epoch
        if (result.frames > 0 && (basePtsUs < 0 || basePtsUs != result.audiblePtsUs)) {
            basePtsUs = result.audiblePtsUs
            baseNs = nanoTime() + 50_000_000L
            mappingRevision++
        }
        return result
    }

    @Synchronized
    fun canPrepareAudio(): Boolean = !audioExpected || firstVideoPtsUs != null || audioStarted || resumingAudio

    @Synchronized
    internal fun beginAudioRecovery() {
        audioProjection = null
        mappingRevision++
        recoveringAudio = true
        resumingAudio = true
        if (basePtsUs < 0) firstVideoPtsUs?.let {
            basePtsUs = it
            baseNs = checkNotNull(firstVideoAtNs)
        }
    }

    @Synchronized
    internal fun endAudioRecovery() {
        audioProjection = null
        mappingRevision++
        recoveringAudio = false
        resumingAudio = false
    }

    @Synchronized
    internal fun videoPresented(ptsUs: Long, presentationAtNs: Long = nanoTime()) {
        require(ptsUs >= 0) { "Negative video presentation timestamp" }
        releasedVideoUs = ptsUs
        if (!recoveringAudio || audioSource != null) return
        val current = positionLocked()
        if (current == null || ptsUs > current) {
            basePtsUs = ptsUs
            baseNs = presentationAtNs
            lastPositionUs = current
            mappingRevision++
        }
    }

    @Synchronized
    fun videoDelayUs(ptsUs: Long): Long? {
        checkStartup()
        require(ptsUs >= 0) { "Negative presentation timestamp" }
        return 0
    }

    @Synchronized
    fun endVideoPreroll() { videoPrerollEnded = true }

    fun audioStartDelayUs(ptsUs: Long): Long {
        observeCallback()
        return synchronized(this) { audioStartDelayLocked(ptsUs) }
    }

    private fun audioStartDelayLocked(ptsUs: Long): Long {
        anchor(ptsUs)
        val delay = Math.subtractExact(ptsUs, checkNotNull(positionLocked()))
        if (!resumingAudio && delay > 3_000_000) throw PlaybackException("Audio startup exceeds the three-second synchronization budget")
        if (!resumingAudio && delay < -250_000) throw PlaybackException("Audio arrived over 250 ms behind the presentation clock")
        return delay
    }

    fun awaitAudioStart(ptsUs: Long, running: () -> Boolean): Boolean =
        awaitAudioStartObserved(ptsUs, {}, running)

    internal fun awaitAudioStartObserved(ptsUs: Long, observed: (AudioStartDecision) -> Unit,
                                         running: () -> Boolean): Boolean {
        val started = nanoTime()
        var publishedAt: Long? = null
        var publishedReason = -1
        var publishedRevision = -1L
        var publishedMode = -1
        fun snapshotLocked(reason: Int, delay: Long? = null): AudioStartDecision? {
            val mode = when {
                audioSource == null -> if (basePtsUs < 0) 0 else 1
                observedAudio == null -> 2
                audioPositionUs != null -> 3
                else -> 4
            }
            val now = nanoTime()
            if (publishedReason == reason && publishedRevision == mappingRevision && publishedMode == mode &&
                publishedAt?.let { now - it < 1_000_000_000L } == true) return null
            publishedAt = now
            publishedReason = reason
            publishedRevision = mappingRevision
            publishedMode = mode
            return AudioStartDecision(reason, mode, ptsUs,
                delay?.let { ptsUs - it } ?: positionLocked(), delay, firstVideoPtsUs, releasedVideoUs,
                (now - started).coerceAtLeast(0) / 1000, mappingRevision,
                (if (audioExpected) 1L else 0L) + (if (audioStarted) 2L else 0L) +
                    (if (resumingAudio) 4L else 0L) + (if (recoveringAudio) 8L else 0L))
        }
        try {
            while (running()) {
                val preroll = synchronized(this) {
                    if (audioExpected && !audioStarted && !resumingAudio &&
                        (basePtsUs < 0 || prerollEpochUs?.let { ptsUs < it } == true))
                        snapshotLocked(2) else null
                }
                if (preroll != null) { observed(preroll); return false }
                observeCallback()
                var reason: Int
                val snapshot = synchronized(this) {
                    val delay = audioStartDelayLocked(ptsUs)
                    reason = when {
                        resumingAudio && delay < -250_000 -> 3
                        delay <= 0 -> 4
                        else -> 1
                    }
                    snapshotLocked(reason, delay)
                }
                snapshot?.let(observed)
                if (reason == 3) return false
                if (reason == 4) return true
                if (nanoTime() - started >= if (resumingAudio) 10_000_000_000L else 3_000_000_000L)
                    throw PlaybackException(if (resumingAudio) "Audio recovery produced no usable source audio within ten seconds"
                        else "Audio startup did not complete within three seconds",
                        if (resumingAudio) "AUDIO_FAILED" else "DECODER_FAILED")
                sleepMs(2)
            }
            synchronized(this) { snapshotLocked(5) }?.let(observed)
            return false
        } catch (error: Exception) {
            synchronized(this) { snapshotLocked(6) }?.let(observed)
            throw error
        }
    }

    internal fun anchorAudio(ptsUs: Long, sampleRate: Int = 48000, positionUs: () -> Long) {
        observeCallback()
        synchronized(this) {
            attachAudio(ptsUs, sampleRate)
            audioPositionUs = positionUs
        }
    }

    @Synchronized
    internal fun anchorObservedAudio(ptsUs: Long, sampleRate: Int): AudioSourceClock =
        attachAudio(ptsUs, sampleRate)

    private fun attachAudio(ptsUs: Long, sampleRate: Int): AudioSourceClock {
        check(audioStartDelayLocked(ptsUs) <= 0) { "Audio cannot start before its source presentation time" }
        audioPositionUs = null
        mappingRevision++
        return AudioSourceClock(ptsUs, sampleRate).also {
            audioSource = it
            observedAudio = null
            audioProjection = null
            observedOutputTiming = null
            observedAppliedOffsetUs = null
            lastPacketCorrection = null
            audioStarted = true
            resumingAudio = recoveringAudio
        }
    }

    @Synchronized
    internal fun observeAudio(source: AudioSourceClock, sampleUs: Long, observedAtNs: Long,
                              timing: AudioOutputTiming? = null,
                              presentedSourceAtFrame: ((Long) -> Long)? = null) {
        if (audioSource !== source) return
        val positionAtNs = timing?.positionAtNs ?: observedAtNs
        if (observedAudio?.let { positionAtNs < it.observedAtNs } == true) return
        observedAudio = SourceTimestampObservation(source.current(sampleUs), positionAtNs)
        audioObservationSequence++
        observedOutputTiming = timing
        observedAppliedOffsetUs = source.appliedOffsetUs()
        audioProjection = if (!recoveringAudio && timing?.clockSource == AudioClockSource.TIMESTAMP &&
            timing.positionAtNs != null && timing.timestampAtNs != null && timing.timestampEstimateFrames != null &&
            timing.positionAtNs <= observedAtNs && timing.sampleRate > 0 &&
            timing.positionAtNs - timing.timestampAtNs in 0..AudioPosition.TIMESTAMP_VALID_NS &&
            timing.playbackSpeed.isFinite() && timing.playbackSpeed > 0 &&
            timing.presentedFrames in 0..timing.consumedFrames &&
            timing.timestampEstimateFrames in 0..timing.consumedFrames &&
            presentedSourceAtFrame != null) {
            val atNs = timing.positionAtNs
            val remainingNs = AudioPosition.TIMESTAMP_VALID_NS - (atNs - timing.timestampAtNs)
            val prediction: (Long) -> Long? = { nowNs ->
                val elapsedNs = nowNs - atNs
                if (elapsedNs in 0..remainingNs) {
                    val frames = (elapsedNs * timing.sampleRate / 1_000_000_000.0 * timing.playbackSpeed).toLong()
                    val estimate = timing.timestampEstimateFrames +
                        minOf(frames, timing.consumedFrames - timing.timestampEstimateFrames)
                    presentedSourceAtFrame(maxOf(timing.presentedFrames, estimate))
                } else null
            }
            prediction
        } else null
    }

    internal fun audioPacket(sampleUs: Long, ptsUs: Long): AudioClockCorrection? {
        val source = synchronized(this) { checkNotNull(audioSource) }
        observeCallback()
        return synchronized(this) {
            check(audioSource === source) { "Audio source changed during packet observation" }
            source.packet(sampleUs, ptsUs).also {
                if (it != null) {
                    mappingRevision++
                    lastPacketCorrection = it
                }
            }
        }
    }

    @Synchronized
    internal fun audioWritten(sampleUs: Long) { checkNotNull(audioSource).written(sampleUs) }

    internal fun monotonicNs(): Long = nanoTime()

    fun detachAudio() {
        val source = synchronized(this) { audioSource }
        try {
            observeCallback()
        } finally {
            detachAudioIfCurrent(source)
        }
    }

    internal fun detachObservedAudio(source: AudioSourceClock) {
        detachAudioIfCurrent(source)
    }

    private fun detachAudioIfCurrent(source: AudioSourceClock?) {
        synchronized(this) {
            if (audioSource !== source) return
            if (source != null) {
                basePtsUs = checkNotNull(positionLocked())
                baseNs = nanoTime()
            }
            resumingAudio = audioStarted || recoveringAudio
            audioPositionUs = null
            audioSource = null
            observedAudio = null
            audioProjection = null
            observedOutputTiming = null
            observedAppliedOffsetUs = null
            lastPacketCorrection = null
            mappingRevision++
        }
    }

    fun currentUs(): Long? {
        observeCallback()
        return synchronized(this) { positionLocked() }
    }

    @Synchronized
    internal fun canProveVideo(ptsUs: Long, previousObservation: SourceTimestampObservation?): Boolean {
        if (audioSource != null && (observedAudio == null || observedAudio === previousObservation)) return false
        return positionLocked()?.let { ptsUs - it >= -250_000 } != false
    }

    private fun observeCallback() {
        val (source, callback) = synchronized(this) {
            val source = audioSource ?: return
            val callback = audioPositionUs ?: return
            source to callback
        }
        val sampleUs = callback()
        observeAudio(source, sampleUs, nanoTime())
    }

    internal fun videoSchedulingClock(ptsUs: Long): VideoSchedulingClock {
        observeCallback()
        return synchronized(this) {
            val now = nanoTime()
            var raw: Long? = null
            val actual = positionLocked(now) { raw = it }
            audioProjection?.invoke(now)?.let { sampleUs ->
                raw = audioSource?.projected(sampleUs)
            }
            val position = raw?.let {
                maxOf(it, actual ?: it, lastVideoPositionUs ?: it).also { guarded -> lastVideoPositionUs = guarded }
            }
            VideoSchedulingClock(position?.let { ptsUs - it }, now, mappingRevision,
                when {
                    audioSource != null && observedAudio == null -> "AUDIO_INITIAL"
                    audioSource != null && audioPositionUs != null -> "CALLBACK_AUDIO"
                    audioSource != null -> "CACHED_AUDIO"
                    position != null -> "MONOTONIC"
                    else -> "UNAVAILABLE"
                }, position, raw,
                observedAudio?.let { audioObservationSequence }, observedAudio?.observedAtNs,
                observedOutputTiming?.clockSource, observedOutputTiming?.timestampAgeUs,
                observedAppliedOffsetUs, lastPacketCorrection, recoveringAudio)
        }
    }

    private fun positionLocked(atNs: Long = nanoTime(), onSourcePosition: ((Long?) -> Unit)? = null): Long? {
        val position = audioSource?.let {
            observedAudio?.ptsUs ?: it.current(0)
        } ?: run {
            if (basePtsUs < 0) {
                onSourcePosition?.invoke(null)
                return null
            }
            basePtsUs + (atNs - baseNs) / 1000
        }
        onSourcePosition?.invoke(position)
        return maxOf(position, lastPositionUs ?: position).also { lastPositionUs = it }
    }

    fun delayUs(ptsUs: Long): Long? = currentUs()?.let { ptsUs - it }

    private fun anchor(ptsUs: Long) {
        require(ptsUs >= 0) { "Negative presentation timestamp" }
        val now = nanoTime()
        if (basePtsUs < 0) {
            basePtsUs = ptsUs
            baseNs = now
            mappingRevision++
        } else if (audioSource == null && now < baseNs && ptsUs < basePtsUs) {
            basePtsUs = ptsUs
            mappingRevision++
        }
    }

    private fun noteMedia() {
        if (startupAtNs == null) startupAtNs = nanoTime()
    }

}

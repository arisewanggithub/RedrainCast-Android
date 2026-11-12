package org.screencopy.receiver.media

enum class VideoStreamFormat { Sdr, Hdr10 }
enum class VideoTransfer { Bt709, Pq }
enum class VideoPresentationOutput { Sdr, Hdr, SdrToneMapped }

data class DecodedVideoStatistics(
    val streamFormat: VideoStreamFormat?,
    val transfer: VideoTransfer?,
    val codecProfile: String?,
    val bitDepth: Int?
)

data class VideoPresentationStatistics(val output: VideoPresentationOutput)

internal data class VideoPathStatistics(
    val video: DecodedVideoStatistics?,
    val presentation: VideoPresentationStatistics?
)

/** Attribute callbacks to the format of the submitted picture, not the newest decoder format. */
internal class VideoPathStatisticsCache(initial: DecodedVideoStatistics? = null) {
    private val pending = LinkedHashMap<Long, VideoPathStatistics>()
    private val rendered = LinkedHashSet<Long>()
    private var lastScheduledPtsUs: Long? = null
    private var closed = false
    private var presentedAtNs = Long.MIN_VALUE
    private var video = initial
    private var presentation: VideoPresentationStatistics? = null
    @Volatile private var latest = VideoPathStatistics(initial, null)

    @Synchronized fun decoded(path: VideoPathStatistics) {
        if (!closed) {
            video = path.video
            latest = VideoPathStatistics(video, presentation)
        }
    }

    @Synchronized fun scheduled(ptsUs: Long, path: VideoPathStatistics) {
        if (closed) return
        val ambiguous = ptsUs == lastScheduledPtsUs || pending.containsKey(ptsUs)
        pending[ptsUs] = if (ambiguous) VideoPathStatistics(null, null) else path
        if (ambiguous) rendered.remove(ptsUs)
        lastScheduledPtsUs = ptsUs
        if (pending.size > 64) pending.remove(pending.keys.first())
    }

    @Synchronized fun rendered(ptsUs: Long, atNs: Long) {
        if (closed) return
        val path = pending.remove(ptsUs)
        if (atNs < presentedAtNs) return
        if (!rendered.add(ptsUs)) return
        if (rendered.size > 64) rendered.remove(rendered.first())
        presentedAtNs = atNs
        presentation = path?.presentation
        latest = VideoPathStatistics(video, presentation)
    }

    fun snapshot() = latest

    @Synchronized fun close() {
        closed = true
        pending.clear()
        rendered.clear()
        video = null
        presentation = null
        latest = VideoPathStatistics(null, null)
    }
}

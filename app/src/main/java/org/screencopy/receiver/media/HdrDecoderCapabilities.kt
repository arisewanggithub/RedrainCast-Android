package org.screencopy.receiver.media

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import org.screencopy.receiver.protocol.ReceiveCapabilities

internal object HdrDecoderCapabilities {
    private val decoders: List<MediaCodecInfo> by lazy {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter(::eligible)
    }

    fun capabilities(nativeHdrAvailable: Boolean?): ReceiveCapabilities =
        ReceiveCapabilities(decoders.isNotEmpty(), nativeHdrAvailable, hdrToSdr = false)

    internal fun eligible(info: MediaCodecInfo): Boolean =
        !info.isEncoder && info.isHardwareAccelerated &&
            info.supportedTypes.any { it.equals("video/hevc", true) } &&
            info.getCapabilitiesForType("video/hevc").profileLevels.any {
                it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10
            }

    fun find(format: MediaFormat, candidates: List<MediaCodecInfo> = decoders): String? = candidates.firstOrNull { info ->
        if (!eligible(info)) return@firstOrNull false
        val caps = info.getCapabilitiesForType("video/hevc")
        val width = format.getInteger(MediaFormat.KEY_WIDTH)
        val height = format.getInteger(MediaFormat.KEY_HEIGHT)
        caps.isFormatSupported(format) &&
            caps.videoCapabilities?.areSizeAndRateSupported(width, height, format.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble()) == true
    }?.name

    internal fun rejection(format: MediaFormat, candidates: List<MediaCodecInfo>,
        report: (String) -> Unit = { android.util.Log.e("ScreenCopyReceiver", it) }): String {
        val mime = checkNotNull(format.getString(MediaFormat.KEY_MIME))
        val width = format.getInteger(MediaFormat.KEY_WIDTH)
        val height = format.getInteger(MediaFormat.KEY_HEIGHT)
        val rate = format.getInteger(MediaFormat.KEY_FRAME_RATE)
        val profile = if (format.containsKey(MediaFormat.KEY_PROFILE)) format.getInteger(MediaFormat.KEY_PROFILE) else null
        val requestedProfile = if (profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10)
            "HEVC Main10 HDR10 ($profile)" else profile?.toString() ?: "unspecified"
        val hardware = candidates.filter { !it.isEncoder && it.isHardwareAccelerated &&
            it.supportedTypes.any { type -> type.equals(mime, true) } }
        val request = "No device decoder supports requested $mime ${width}x$height@$rate profile=$requestedProfile;"
        report("event=VIDEO_DECODER_SELECTION_REJECT $request hardware_candidates=${hardware.size}")
        val constraints = hardware.joinToString("; ") { info ->
            val caps = info.getCapabilitiesForType(mime)
            val profiles = caps.profileLevels.joinToString(",") { "${it.profile}/${it.level}" }
            val video = caps.videoCapabilities
            val raster = if (video == null) "video_capabilities=unavailable" else {
                val sizeSupported = video.isSizeSupported(width, height)
                val rates = if (sizeSupported) video.getSupportedFrameRatesFor(width, height).toString() else "unavailable_for_requested_raster"
                val widthAlignment = video.widthAlignment
                val heightAlignment = video.heightAlignment
                "widths=${video.supportedWidths} heights=${video.supportedHeights}" +
                    " alignment=${widthAlignment}x$heightAlignment aligned=${width % widthAlignment == 0 && height % heightAlignment == 0}" +
                    " raster_supported=$sizeSupported" +
                    " rates_at_raster=$rates size_rate_supported=${video.areSizeAndRateSupported(width, height, rate.toDouble())}"
            }
            val detail = "${info.name}: profile_levels=$profiles profile_supported=${profile == null || caps.profileLevels.any { it.profile == profile }}" +
                " $raster format_supported=${caps.isFormatSupported(format)}"
            report("event=VIDEO_DECODER_SELECTION_CANDIDATE $detail")
            detail
        }
        val detail = "$request Select a supported resolution/frame rate; 1920x1080@60 is a retry option, not a compatibility guarantee." +
            " HDR also requires native display support; use SDR when unavailable. No settings were changed." +
            " hardware_candidates=${hardware.size}: ${constraints.ifEmpty { "none" }}."
        // Leave room for the structured diagnostic within the existing 2048-character ERROR message.
        return if (detail.length <= 1100) detail else
            detail.take(1000) + " [constraints truncated; full candidates in ScreenCopyReceiver log]"
    }
}

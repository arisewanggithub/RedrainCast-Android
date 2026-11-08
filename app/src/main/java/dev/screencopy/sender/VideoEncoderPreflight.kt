package dev.screencopy.sender

import android.media.MediaCodecInfo
import kotlin.math.ceil
import kotlin.math.floor

/** Queries public codec metadata without starting capture. */
internal object VideoEncoderPreflight {
    fun validate(settings: Settings, inventory: DisplayInventory): VideoGeometry {
        val selected = HardwareEncoderDiscovery.read().resolve(settings.videoCodec, settings.videoEncoder)
        val geometry = ScrcpyVideoSizing.calculate(settings, inventory)
        val mime = when (settings.videoCodec) { "h264" -> "video/avc"; "h265" -> "video/hevc"; else -> "video/av01" }
        val encoders = try {
            HardwareEncoderDiscovery.codecs(settings.videoCodec).filter { selected == it.name }
        } catch (e: IllegalStateException) {
            throw VideoGeometryException(geometry, VideoGeometryReason.METADATA_UNAVAILABLE, "Video capability query failed. Retry and retry.")
        } catch (e: SecurityException) {
            throw VideoGeometryException(geometry, VideoGeometryReason.METADATA_UNAVAILABLE, "Video capability access unavailable. Retry and retry.")
        }
        val checks = encoders.map { encoder ->
            try {
                val caps = encoder.getCapabilitiesForType(mime).videoCapabilities
                if (caps == null) EncoderGeometryCheck(null, null, null) else {
                    val limits = VideoLimits(caps.supportedWidths.lower, caps.supportedWidths.upper,
                        caps.supportedHeights.lower, caps.supportedHeights.upper, caps.widthAlignment, caps.heightAlignment,
                        caps.supportedFrameRates.lower.toDouble(), caps.supportedFrameRates.upper.toDouble())
                    val size = geometry.output
                    val supported = size.width > 0 && size.height > 0 && caps.isSizeSupported(size.width, size.height)
                    val rate = supported && (geometry.fps == 0 ||
                        caps.areSizeAndRateSupported(size.width, size.height, geometry.fps.toDouble()))
                    EncoderGeometryCheck(limits, supported, rate, if (supported && rate) null else suggestion(caps, limits, geometry))
                }
            } catch (e: IllegalArgumentException) {
                android.util.Log.w("ScreenCopyShell", "video_geometry_metadata=QUERY_REJECTED")
                EncoderGeometryCheck(null, null, null)
            } catch (e: IllegalStateException) {
                android.util.Log.w("ScreenCopyShell", "video_geometry_metadata=QUERY_FAILED")
                EncoderGeometryCheck(null, null, null)
            }
        }
        VideoGeometryValidation.validate(geometry, checks, automatic = false)
        return geometry
    }
    private fun suggestion(caps: MediaCodecInfo.VideoCapabilities, limits: VideoLimits, geometry: VideoGeometry): Pair<VideoSize, Int>? {
        fun aligned(value: Int, alignment: Int) = ((value + alignment - 1) / alignment) * alignment
        return try {
            var h = aligned(geometry.output.height.coerceIn(maxOf(1, limits.minHeight), limits.maxHeight), limits.heightAlignment)
            if (h > limits.maxHeight) return null
            val widths = caps.getSupportedWidthsFor(h)
            val w = aligned(geometry.output.width.coerceIn(maxOf(1, widths.lower), widths.upper), limits.widthAlignment)
            if (w > widths.upper) return null
            val heights = caps.getSupportedHeightsFor(w)
            h = aligned(h.coerceIn(maxOf(1, heights.lower), heights.upper), limits.heightAlignment)
            if (h > heights.upper || !caps.isSizeSupported(w, h)) return null
            if (geometry.fps == 0) return VideoSize(w, h) to 0
            val rates = caps.getSupportedFrameRatesFor(w, h)
            val fps = geometry.fps.coerceIn(maxOf(1, ceil(rates.lower).toInt()), minOf(240, floor(rates.upper).toInt()))
            if (caps.areSizeAndRateSupported(w, h, fps.toDouble())) VideoSize(w, h) to fps else null
        } catch (e: IllegalArgumentException) { null } // This candidate has no verified suggestion.
    }
}

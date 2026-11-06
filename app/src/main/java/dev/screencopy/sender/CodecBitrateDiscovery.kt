package dev.screencopy.sender

import android.util.Log

/** Called inside the verified shell/root UserService, not inferred from ordinary app UID. */
internal object CodecBitrateDiscovery {
    fun read(): CodecBitrates {
        val result = mutableListOf<EncoderBitrate>()
        for ((codec, mime) in HardwareEncoderPolicy.mimeTypes) {
            for (encoder in HardwareEncoderDiscovery.codecs(codec)) {
                try {
                    val capabilities = encoder.getCapabilitiesForType(mime)
                    val range = if (mime.startsWith("video/")) capabilities.videoCapabilities?.bitrateRange
                        else capabilities.audioCapabilities?.bitrateRange
                    result.add(if (range == null) EncoderBitrate(codec, encoder.name, null, null, "not published")
                        else EncoderBitrate(codec, encoder.name, range.lower, range.upper))
                } catch (e: IllegalArgumentException) {
                    Log.w("ScreenCopyShell", "Encoder bitrate metadata unavailable: ${encoder.name}", e)
                    result.add(EncoderBitrate(codec, encoder.name, null, null, "capability query rejected"))
                } catch (e: IllegalStateException) {
                    Log.w("ScreenCopyShell", "Encoder bitrate metadata unavailable: ${encoder.name}", e)
                    result.add(EncoderBitrate(codec, encoder.name, null, null, "capability query failed"))
                }
            }
        }
        check(result.size <= 256) { "Encoder bitrate enumeration exceeds limit" }
        return CodecBitrates(result)
    }
}

package dev.screencopy.sender

import android.content.Context
import android.hardware.display.DisplayManager
import android.graphics.Point
import android.view.Display
import java.io.IOException

object ProjectionInventory {
    data class Snapshot(val capabilities: String, val ready: Boolean = true, val generation: Long = 0)

    internal fun displays(context: Context): DisplayInventory {
        val display = checkNotNull(context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)) {
            "Default display is unavailable"
        }
        // Service/application WindowMetrics can retain the Activity's previous rotation.
        val size = Point()
        repeat(3) {
            val rotation = display.rotation
            @Suppress("DEPRECATION")
            display.getRealSize(size)
            if (display.rotation == rotation)
                return DisplayInventory(listOf(CaptureDisplay(Display.DEFAULT_DISPLAY, display.name,
                    size.x, size.y, rotation, 1)))
        }
        throw IOException("Capture display rotation is changing. Retry after the display settles.")
    }

    fun read(context: Context): Snapshot {
        val encoders = HardwareEncoderDiscovery.read()
        val listing = HardwareEncoderPolicy.mimeTypes.keys.flatMap { codec ->
            val track = if (HardwareEncoderPolicy.requiresHardware(codec)) "video" else "audio"
            encoders.names(codec).map { "--$track-codec=$codec --$track-encoder='$it'" }
        }.joinToString("\n")
        return Snapshot(listOf(listing, encoders.report(), CodecBitrateDiscovery.read().report(), displays(context).report()).joinToString("\n"))
    }

    fun validate(context: Context, settings: Settings, natural: Boolean = true): Settings {
        require(natural) { "UNSUPPORTED_CONFIG: fixed-natural capture is required" }
        settings.validate()
        check(settings.displayId == Display.DEFAULT_DISPLAY) { "UNSUPPORTED_CONFIG: MediaProjection captures the default display only. Choose Main screen." }
        val resolved = HardwareEncoderDiscovery.read().resolve(settings)
        Bitrates.validate(resolved, CodecBitrateDiscovery.read())
        val inventory = displays(context)
        inventory.validate(resolved)
        VideoEncoderPreflight.validate(resolved, inventory)
        return resolved
    }
}

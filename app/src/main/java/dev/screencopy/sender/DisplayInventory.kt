package dev.screencopy.sender

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

internal data class CaptureDisplay(val id: Int, val name: String, val width: Int, val height: Int,
                                   val rotation: Int?, val type: Int) {
    init {
        require(id >= 0 && name.length <= 256 && width in 1..65536 && height in 1..65536 &&
            (rotation == null || rotation in 0..3)) { "Invalid privileged display metadata" }
    }
    val naturalWidth get() = if (rotation == 1 || rotation == 3) height else width
    val naturalHeight get() = if (rotation == 1 || rotation == 3) width else height
    fun label(): String {
        val kind = when { id == 0 -> "Main screen"; type == 1 -> "Built-in screen"
            type in 2..3 -> "External screen"; type == 5 -> "Virtual display"; else -> "Display" }
        return kind + (if (name.isBlank()) "" else " - $name") + " (${width}x$height; ID $id)"
    }
}

internal class DisplayInventory(val displays: List<CaptureDisplay>) {
    data class Choice(val id: Int, val display: CaptureDisplay?) {
        override fun toString() = display?.label() ?: "Unavailable display (ID $id) - refresh or choose another"
    }
    init {
        require(displays.size <= 128 && displays.map { it.id }.distinct().size == displays.size) {
            "Invalid or duplicate display inventory"
        }
    }
    fun requireAvailable() {
        if (displays.isEmpty()) throw IOException("No capture displays available. Refresh displays or retry.")
    }
    fun choices(selected: Int): List<Choice> {
        val choices = displays.sortedWith(compareBy<CaptureDisplay> { it.id != 0 }.thenBy { it.id })
            .map { Choice(it.id, it) }.toMutableList()
        if (displays.none { it.id == selected }) choices.add(0, Choice(selected, null))
        return choices
    }
    fun validate(settings: Settings) {
        requireAvailable()
        val display = displays.singleOrNull { it.id == settings.displayId }
            ?: throw IOException("Selected display is unavailable. Refresh displays and choose a screen explicitly.")
        if (settings.cropWidth > 0) {
            if (display.rotation == null) throw IOException("Natural display bounds unavailable. Refresh displays before applying crop.")
            Settings.require(settings.cropX.toLong() + settings.cropWidth <= display.naturalWidth &&
                settings.cropY.toLong() + settings.cropHeight <= display.naturalHeight,
                "Crop rectangle exceeds natural display bounds ${display.naturalWidth}x${display.naturalHeight}")
        }
    }
    fun report(): String = PREFIX + JSONArray().apply {
        displays.forEach { put(Wire.obj("id" to it.id, "name" to it.name, "width" to it.width,
            "height" to it.height, "rotation" to (it.rotation ?: JSONObject.NULL), "type" to it.type)) }
    }
    fun updateReport(report: String) = report.lineSequence().filterNot { it.startsWith(PREFIX) }.joinToString("\n") +
        "\n" + this.report()
    companion object {
        private const val PREFIX = "SCREENCOPY_DISPLAYS:"
        fun fromReport(report: String): DisplayInventory {
            val structured = report.lineSequence().filter { it.startsWith(PREFIX) }.toList()
            if (structured.size > 1) throw IOException("Duplicate privileged display inventory; retry.")
            if (structured.isNotEmpty()) {
                val array = JSONArray(structured.single().removePrefix(PREFIX))
                if (array.length() > 128) throw IOException("Display inventory exceeds limit")
                return DisplayInventory((0 until array.length()).map {
                    val value = array.getJSONObject(it)
                    CaptureDisplay(value.getInt("id"), value.getString("name"), value.getInt("width"),
                        value.getInt("height"), if (value.isNull("rotation")) null else value.getInt("rotation"), value.getInt("type"))
                })
            }
            val parsed = Regex("--display-id=([0-9]+)\\s+\\(([^)]*)\\)").findAll(report).map { match ->
                val size = Regex("([0-9]+)x([0-9]+)").matchEntire(match.groupValues[2])
                    ?: throw IOException("Display size unavailable. Retry to refresh display inventory.")
                CaptureDisplay(match.groupValues[1].toInt(), "", size.groupValues[1].toInt(),
                    size.groupValues[2].toInt(), null, 0)
            }.toList()
            return DisplayInventory(parsed)
        }
    }
}

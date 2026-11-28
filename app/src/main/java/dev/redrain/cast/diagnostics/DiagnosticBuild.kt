package dev.redrain.cast.diagnostics

import dev.redrain.cast.BuildConfig

/** Numeric encoding keeps the complete build name in the existing telemetry row format. */
internal object DiagnosticBuild {
    const val MARKER = -20261126L

    fun row(atNs: Long): TelemetryRow? {
        val bytes = BuildConfig.VERSION_NAME.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > 112) return null
        val chunks = bytes.asList().chunked(7).map { chunk ->
            chunk.fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 255) }
        }
        return TelemetryRow(DiagnosticKind.SESSION.ordinal + 1, DiagnosticStage.SAMPLE.ordinal,
            0, 0, atNs.coerceAtLeast(0), MARKER,
            listOf(BuildConfig.VERSION_CODE.toLong(), bytes.size.toLong()) + chunks)
    }

    fun version(row: TelemetryRow): String? {
        if (row.kind != DiagnosticKind.SESSION.ordinal + 1 || row.stage != DiagnosticStage.SAMPLE.ordinal ||
            row.track != 0 || row.generation != 0 || row.value != MARKER || row.detail.size < 3) return null
        val length = row.detail[1]
        if (length !in 1..112 || row.detail.size != 2 + (length.toInt() + 6) / 7) return null
        val bytes = ByteArray(length.toInt())
        var offset = 0
        for (chunk in row.detail.drop(2)) {
            val size = minOf(7, bytes.size - offset)
            if (chunk < 0 || chunk ushr (size * 8) != 0L) return null
            for (i in size - 1 downTo 0) bytes[offset++] = (chunk ushr (i * 8)).toByte()
        }
        val result = bytes.toString(Charsets.UTF_8)
        return result.takeIf { it.toByteArray(Charsets.UTF_8).contentEquals(bytes) }
    }
}

package dev.screencopy.sender

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Converts Android encoder initialization to the existing receiver's codec contract. */
internal object ProjectionPackets {
    fun opusHead(bytes: ByteArray): ByteArray {
        if (bytes.size == 19 && bytes.copyOfRange(0, 8).contentEquals("OpusHead".toByteArray(Charsets.US_ASCII)))
            return bytes
        var offset = 0
        while (offset + 16 <= bytes.size) {
            val name = String(bytes, offset, 8, Charsets.US_ASCII)
            val length = ByteBuffer.wrap(bytes, offset + 8, 8).order(ByteOrder.nativeOrder()).long
            if (length < 0 || length > bytes.size - offset - 16) throw IOException("AUDIO_FAILED: invalid Android Opus initialization")
            if (name == "AOPUSHDR") {
                val header = bytes.copyOfRange(offset + 16, offset + 16 + length.toInt())
                if (header.size != 19 || !header.copyOfRange(0, 8).contentEquals("OpusHead".toByteArray(Charsets.US_ASCII)))
                    throw IOException("AUDIO_FAILED: invalid OpusHead")
                return header
            }
            offset += 16 + length.toInt()
        }
        throw IOException("AUDIO_FAILED: Android encoder did not provide OpusHead")
    }

    fun options(format: android.media.MediaFormat, options: String) {
        if (options.isEmpty()) return
        for (entry in options.split(',')) {
            val (key, value) = entry.split('=')
            val parts = key.split(':')
            when (parts.getOrElse(1) { "int" }) {
                "long" -> format.setLong(parts[0], value.toLong())
                "float" -> format.setFloat(parts[0], value.toFloat())
                else -> format.setInteger(parts[0], value.toInt())
            }
        }
    }
}

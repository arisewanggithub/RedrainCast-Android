package dev.screencopy.sender

import java.io.IOException

/** AudioRecord nonblocking reads may contain only a few samples; emit bounded 20 ms PCM packets. */
internal class RawPcmPackets(
    private val originUs: Long,
    private val emit: (ByteArray, Long) -> Unit
) {
    private val pending = ByteArray(3840)
    private var size = 0
    private var frames = 0L

    init { require(originUs >= 0) { "PCM capture origin must be nonnegative" } }

    fun append(bytes: ByteArray, count: Int) {
        if (count <= 0 || count > bytes.size || count % 4 != 0)
            throw IOException("AUDIO_FAILED: invalid stereo PCM input")
        var offset = 0
        while (offset < count) {
            val copied = minOf(count - offset, pending.size - size)
            bytes.copyInto(pending, size, offset, offset + copied)
            size += copied
            offset += copied
            if (size == pending.size) {
                emit(pending.copyOf(), originUs + frames * 1_000_000 / 48000)
                frames += pending.size / 4
                size = 0
            }
        }
    }
}

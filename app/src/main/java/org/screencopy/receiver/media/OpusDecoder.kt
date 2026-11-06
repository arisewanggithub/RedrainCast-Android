package org.screencopy.receiver.media

interface OpusPacketDecoder : AutoCloseable {
    fun decode(packet: ByteArray): ShortArray
}

class OpusDecoder(private val channels: Int, gainQ8: Int = 0) : OpusPacketDecoder {
    private var handle = create(channels, gainQ8)

    override fun decode(packet: ByteArray): ShortArray {
        check(handle != 0L) { "Opus decoder is closed" }
        return decodePacket(handle, packet, channels)
    }

    override fun close() {
        if (handle != 0L) destroy(handle)
        handle = 0
    }

    private external fun create(channels: Int, gainQ8: Int): Long
    private external fun decodePacket(handle: Long, packet: ByteArray, channels: Int): ShortArray
    private external fun destroy(handle: Long)

    companion object {
        init {
            System.loadLibrary("screencopy_opus")
        }
    }
}

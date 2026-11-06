package dev.screencopy.sender

import org.json.JSONObject
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.EOFException

/** Exact scrcpy 3.3.4 Streamer/DesktopConnection framing, never a generic header stripper. */
object ScrcpyAdapter {
    interface Sink {
        fun config(track: Int, generation: Int, config: JSONObject)
        fun packet(packet: Wire.Message)
        // Synchronous sinks have no queued video; buffered sinks wait before encoder replacement.
        fun awaitVideoDrain() {}
    }
    fun read(source: InputStream, track: Int, settings: Settings, session: Long, sink: Sink, initialized: () -> Unit = {}) {
        val progress = CaptureReadProgress(track, if (track == 1) settings.videoCodec else settings.audioCodec)
        try { readPackets(source, track, settings, session, sink, initialized, progress) }
        catch (e: EOFException) { throw progress.eof(e) }
    }
    private inline fun forward(context: () -> AdapterProtocolContext, action: () -> Unit) {
        try { action() }
        catch (e: SenderProtocolException) { throw e.withAdapter(context()) }
    }
    private fun readPackets(source: InputStream, track: Int, settings: Settings, session: Long, sink: Sink,
                            initialized: () -> Unit, progress: CaptureReadProgress) {
        val input = DataInputStream(source)
        val id = input.readInt()
        val codec = if (track == 1) settings.videoCodec else settings.audioCodec
        val expected = when (codec) {
            "h264" -> 0x68323634; "h265" -> 0x68323635; "av1" -> 0x00617631; "opus" -> 0x6f707573
            "aac" -> 0x00616163; "raw" -> 0x00726177
            else -> throw IOException("UNSUPPORTED_CONFIG: codec adapter unavailable")
        }
        if (id == 0 || id == 1) throw IOException(if (track == 2)
            "AUDIO_FAILED: scrcpy could not start enabled audio. Unlock Android 11; choose an available encoder or explicitly disable audio."
            else "CAPTURE_FAILED: scrcpy disabled video")
        if (id != expected) throw IOException("CAPTURE_FAILED: backend codec differs from selected $codec")
        progress.header()
        var headerWidth = 0; var headerHeight = 0
        if (track == 1) {
            progress.stage(CaptureReadStage.VIDEO_DIMENSIONS)
            headerWidth = input.readInt(); headerHeight = input.readInt()
            if (headerWidth !in 1..16384 || headerHeight !in 1..16384) throw IOException("Invalid scrcpy video header")
            progress.dimensions(headerWidth, headerHeight)
        }
        var previous: ByteArray? = null
        var generation = 0
        var configPackets = 0L
        var mediaPackets = 0L
        var repeatedConfig = false
        var previousMediaPts: Long? = null
        if (codec == "raw") {
            generation = 1
            progress.config(1)
            forward({ AdapterProtocolContext(CapturePacketKind.CONFIG, 0, 0, false, 0, 0) }) {
                sink.config(track, 1, CodecConfig.audio("raw", byteArrayOf()))
            }
            initialized()
        }
        while (!Thread.currentThread().isInterrupted) {
            progress.stage(CaptureReadStage.PACKET_HEADER)
            val flagged = input.readLong()
            val config = flagged and Long.MIN_VALUE != 0L
            val key = flagged and (1L shl 62) != 0L
            val pts = flagged and 0x3fffffffffffffffL
            val length = input.readInt()
            val max = if (config) Wire.MAX_CONTROL else if (track == 1) Wire.MAX_VIDEO else Wire.MAX_AUDIO
            if (length !in 1..max) throw IOException("CAPTURE_FAILED: scrcpy packet exceeds limit")
            progress.packet(length); progress.stage(CaptureReadStage.PACKET_PAYLOAD)
            val data = ByteArray(length).also(input::readFully)
            if (config) {
                progress.stage(CaptureReadStage.CONFIG)
                configPackets++
                repeatedConfig = data.contentEquals(previous)
                if (!data.contentEquals(previous)) {
                    val actual = if (track == 1) CodecConfig.video(codec, data) else CodecConfig.audio(codec, data)
                    if (track == 1 && generation == 0 &&
                        (actual.getInt("width") != headerWidth || actual.getInt("height") != headerHeight))
                        throw IOException("CAPTURE_FAILED: SPS dimensions differ from backend header")
                    forward({ AdapterProtocolContext(CapturePacketKind.CONFIG, configPackets, mediaPackets, repeatedConfig, length, 0) }) {
                        sink.config(track, ++generation, actual)
                    }
                    previous = data
                    progress.config(generation)
                    if (generation == 1) initialized()
                } else if (track == 1) {
                    // Encoder restart may retain the SPS but still needs a decoder generation reset.
                    forward({ AdapterProtocolContext(CapturePacketKind.CONFIG, configPackets, mediaPackets, true, length, 0) }) {
                        sink.config(track, ++generation, CodecConfig.video(codec, data))
                    }
                    progress.config(generation)
                }
            } else {
                if (generation == 0) throw IOException("CAPTURE_FAILED: media arrived before codec initialization")
                if (codec == "raw" && length % 4 != 0) throw IOException("AUDIO_FAILED: unaligned stereo PCM")
                if (track == 1 && codec != "av1" && (data.size < 4 || data[0] != 0.toByte() || data[1] != 0.toByte() ||
                    !(data[2] == 1.toByte() || data[2] == 0.toByte() && data[3] == 1.toByte())))
                    throw IOException("CAPTURE_FAILED: expected Annex B access unit")
                if (track == 1 && codec == "av1") Av1Configuration.obus(data)
                mediaPackets++
                progress.stage(CaptureReadStage.MEDIA)
                forward({ AdapterProtocolContext(if (key && track == 1) CapturePacketKind.KEY_FRAME else CapturePacketKind.MEDIA,
                    configPackets, mediaPackets, repeatedConfig, length, previousMediaPts?.let { pts - it } ?: 0) }) {
                    sink.packet(Wire.Message(if (track == 1) Wire.VIDEO_FRAME else Wire.AUDIO_PACKET,
                        if (key && track == 1) 1 else 0, session, track, generation, pts, data))
                }
                previousMediaPts = pts
                progress.media()
            }
        }
    }
}

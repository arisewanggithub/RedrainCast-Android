package dev.screencopy.sender

import java.io.EOFException
import java.io.InputStream

internal enum class CaptureReadStage { CODEC_HEADER, VIDEO_DIMENSIONS, PACKET_HEADER, PACKET_PAYLOAD, CONFIG, MEDIA, SERVER_OUTPUT, VIDEO_PREFLIGHT }
internal enum class ServerErrorKind { NONE, VIDEO_ENCODING, ENCODER_CREATE, AUDIO_CAPTURE, DISPLAY, OPENGL, SERVER_ERROR }
internal enum class ServerErrorClass { NONE, ILLEGAL_ARGUMENT, ILLEGAL_STATE, IO, CODEC, SECURITY }
internal data class ServerErrorEvidence(val kind: ServerErrorKind = ServerErrorKind.NONE,
    val exception: ServerErrorClass = ServerErrorClass.NONE, val guardianReady: Boolean = false, val outputEnded: Boolean = false)

/** Continuously drains output, retaining only fixed categories; raw lines never leave this parser. */
internal class CaptureServerLog {
    private val lock = Object()
    private var evidence = ServerErrorEvidence()
    fun snapshot() = synchronized(lock) { evidence }
    fun awaitError(): ServerErrorEvidence = synchronized(lock) {
        if (evidence.kind == ServerErrorKind.NONE && !evidence.outputEnded) try { lock.wait(100) }
        catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        evidence
    }
    fun drain(input: InputStream) {
        val line = StringBuilder()
        val bytes = ByteArray(2048)
        while (true) {
            val count = input.read(bytes)
            if (count == -1) break
            for (index in 0 until count) {
                val value = bytes[index].toInt() and 255
                if (value == 10) { observe(line.toString()); line.setLength(0) }
                else if (line.length < 1024) line.append(if (value in 32..126) value.toChar() else ' ')
            }
        }
        if (line.isNotEmpty()) observe(line.toString())
        synchronized(lock) { evidence = evidence.copy(outputEnded = true); lock.notifyAll() }
    }
    private fun observe(line: String) {
        synchronized(lock) {
            if (line.startsWith("SCREENCOPY_GUARDIAN_READY:")) evidence = evidence.copy(guardianReady = true)
            val kind = when {
                line.contains("Capture/encoding error") -> ServerErrorKind.VIDEO_ENCODING
                line.contains("Could not create") && line.contains("encoder", true) -> ServerErrorKind.ENCODER_CREATE
                line.contains("Audio capture error") || line.contains("Failed to start audio capture") -> ServerErrorKind.AUDIO_CAPTURE
                line.contains("Unknown display id") || line.contains("Crop ") && line.contains("exceeds") -> ServerErrorKind.DISPLAY
                line.contains("ERROR") && line.contains("OpenGL", true) -> ServerErrorKind.OPENGL
                line.contains("ERROR:") -> ServerErrorKind.SERVER_ERROR
                else -> ServerErrorKind.NONE
            }
            val exception = when {
                line.contains("CodecException") -> ServerErrorClass.CODEC
                line.contains("IllegalArgumentException") -> ServerErrorClass.ILLEGAL_ARGUMENT
                line.contains("IllegalStateException") -> ServerErrorClass.ILLEGAL_STATE
                line.contains("IOException") -> ServerErrorClass.IO
                line.contains("SecurityException") -> ServerErrorClass.SECURITY
                else -> ServerErrorClass.NONE
            }
            if (evidence.kind == ServerErrorKind.NONE && kind != ServerErrorKind.NONE)
                evidence = evidence.copy(kind = kind, exception = exception)
            else if (evidence.kind != ServerErrorKind.NONE && evidence.exception == ServerErrorClass.NONE && exception != ServerErrorClass.NONE)
                evidence = evidence.copy(exception = exception)
            if (evidence.kind != ServerErrorKind.NONE) lock.notifyAll()
        }
    }
}
internal data class CaptureEofEvidence(val track: CaptureTrack, val stage: CaptureReadStage,
    val format: CaptureFormat, val codecHeader: Boolean, val dimensions: Boolean,
    val generation: Int, val configs: Long, val media: Long, val packetBytes: Int,
    val server: ServerErrorEvidence = ServerErrorEvidence(), val outputWidth: Int = 0, val outputHeight: Int = 0) {
    fun fields() = "reason=STREAM_EOF stage=$stage track=$track format=$format codec_header=$codecHeader dimensions=$dimensions" +
        " generation=$generation config_packets=$configs media_packets=$media packet_bytes=$packetBytes output_width=$outputWidth output_height=$outputHeight" +
        " server_observed=${server.kind} server_exception=${server.exception} guardian_ready=${server.guardianReady} server_output_ended=${server.outputEnded}"
}
internal class CaptureStreamEof(val evidence: CaptureEofEvidence, cause: Exception? = null) : EOFException(
    "${if (evidence.track == CaptureTrack.AUDIO) "AUDIO_FAILED" else "CAPTURE_FAILED"}: ${evidence.track} stream ended at ${evidence.stage}; " +
        "configurations ${evidence.configs}, media packets ${evidence.media}, server observation ${evidence.server.kind}."
) {
    init { if (cause != null) initCause(cause) }
    fun withServer(server: ServerErrorEvidence) = CaptureStreamEof(evidence.copy(server = server), this)
}
internal class CaptureReadProgress(track: Int, codec: String) {
    private val sourceTrack = if (track == 1) CaptureTrack.VIDEO else CaptureTrack.AUDIO
    private val format = captureFormat(codec)
    private var stage = CaptureReadStage.CODEC_HEADER
    private var header = false
    private var dimensions = false
    private var packetBytes = 0
    private var width = 0
    private var height = 0
    private var generation = 0
    private var configs = 0L
    private var media = 0L
    fun stage(stage: CaptureReadStage) { this.stage = stage }
    fun header() { header = true }
    fun dimensions(width: Int, height: Int) { dimensions = true; this.width = width; this.height = height }
    fun packet(bytes: Int) { packetBytes = bytes }
    fun config(generation: Int) { this.generation = generation; configs++ }
    fun media() { media++ }
    fun eof(cause: EOFException) = CaptureStreamEof(CaptureEofEvidence(sourceTrack, stage, format, header, dimensions,
        generation, configs, media, packetBytes, outputWidth = width, outputHeight = height), cause)
}
internal fun captureFormat(codec: String) = when (codec) {
    "h264" -> CaptureFormat.H264; "h265" -> CaptureFormat.H265; "av1" -> CaptureFormat.AV1
    "opus" -> CaptureFormat.OPUS; "aac" -> CaptureFormat.AAC; "raw" -> CaptureFormat.PCM
    else -> CaptureFormat.UNKNOWN
}

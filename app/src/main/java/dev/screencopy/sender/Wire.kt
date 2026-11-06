package dev.screencopy.sender

import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** ScrcpySend / ScrcpyRecv wire protocol version 3, separate from app release versions. */
object Wire {
    const val HELLO = 1
    const val VERSION = 3
    const val SESSION_REQUEST = 3
    const val SESSION_ACCEPT = 4
    const val REJECT = 5
    const val STREAM_CONFIG = 6
    const val STREAM_READY = 7
    const val VIDEO_FRAME = 8
    const val AUDIO_PACKET = 9
    const val STATUS = 10
    const val ERROR = 11
    const val PING = 12
    const val PONG = 13
    const val STOP = 14
    const val SESSION_END = 15
    const val MAX_VIDEO = 4 * 1024 * 1024
    const val MAX_AUDIO = 256 * 1024
    const val MAX_CONTROL = 65536

    data class Message(
        val type: Int, val flags: Int, val session: Long, val track: Int,
        val generation: Int, val pts: Long, val payload: ByteArray
    ) {
        fun json(): JSONObject = parseJson(payload)
    }

    fun control(type: Int, session: Long, json: JSONObject) =
        Message(type, 0, session, 0, 0, -1, json.toString().toByteArray(Charsets.UTF_8))

    fun config(session: Long, track: Int, generation: Int, json: JSONObject) =
        Message(STREAM_CONFIG, 0, session, track, generation, -1, json.toString().toByteArray(Charsets.UTF_8))

    fun validate(type: Int, flags: Int, session: Long, track: Int, generation: Int, pts: Long, size: Int) {
        if (type !in 1..15 || type == 2 || session < 0) throw IOException("PROTOCOL: invalid/reserved type or session")
        val media = type == VIDEO_FRAME || type == AUDIO_PACKET
        val max = when (type) { VIDEO_FRAME -> MAX_VIDEO; AUDIO_PACKET -> MAX_AUDIO; else -> MAX_CONTROL }
        if (size !in 0..max || media && size == 0) throw IOException("PROTOCOL: invalid payload length")
        if (flags != 0 && !(type == VIDEO_FRAME && flags in 1..7)) throw IOException("PROTOCOL: reserved flags")
        if (media || type == STREAM_CONFIG || type == STREAM_READY) {
            if (session == 0L || generation < 1 || track !in 1..2 ||
                type == VIDEO_FRAME && track != 1 || type == AUDIO_PACKET && track != 2
            ) throw IOException("PROTOCOL: invalid track/generation")
        } else if (track != 0 || generation != 0) throw IOException("PROTOCOL: control track")
        if (if (media) pts < 0 else pts != -1L) throw IOException("PROTOCOL: invalid PTS")
    }

    fun read(source: InputStream): Message {
        val input = DataInputStream(source)
        if (input.readInt() != 0x53435059) throw IOException("VERSION: SCPY wire magic required; TLS is unsupported")
        if (input.readUnsignedShort() != VERSION) throw IOException("VERSION: wire protocol version 3 is required")
        val type = input.readUnsignedShort()
        val size = input.readInt()
        val flags = input.readInt()
        val session = input.readLong()
        val track = input.readInt()
        val generation = input.readInt()
        val pts = input.readLong()
        validate(type, flags, session, track, generation, pts, size)
        val payload = ByteArray(size).also(input::readFully)
        return Message(type, flags, session, track, generation, pts, payload).also {
            if (type != VIDEO_FRAME && type != AUDIO_PACKET) it.json()
        }
    }

    fun write(target: OutputStream, message: Message) {
        with(message) {
            validate(type, flags, session, track, generation, pts, payload.size)
            // Keep tiny header writes off TCP_NODELAY sockets without copying an entire large frame.
            val prefixSize = minOf(payload.size, 16 * 1024)
            val prefix = ByteBuffer.allocate(40 + prefixSize).apply {
                putInt(0x53435059); putShort(VERSION.toShort()); putShort(type.toShort())
                putInt(payload.size); putInt(flags); putLong(session)
                putInt(track); putInt(generation); putLong(pts)
                put(payload, 0, prefixSize)
            }.array()
            target.write(prefix)
            if (prefixSize < payload.size) target.write(payload, prefixSize, payload.size - prefixSize)
            target.flush()
        }
    }

    fun parseJson(bytes: ByteArray): JSONObject {
        try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            Json.validate(text)
            val tokener = JSONTokener(text)
            val value = tokener.nextValue()
            if (value !is JSONObject || tokener.nextClean() != '\u0000') throw IOException("PROTOCOL: JSON object required")
            return value
        } catch (e: JSONException) {
            throw IOException("PROTOCOL: malformed JSON", e)
        } catch (e: CharacterCodingException) {
            throw IOException("PROTOCOL: malformed JSON", e)
        }
    }

    fun obj(vararg pairs: Pair<String, Any?>): JSONObject = JSONObject().apply {
        pairs.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
    }
}

package org.screencopy.receiver.net

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.screencopy.receiver.protocol.Message
import org.screencopy.receiver.protocol.Type
import org.screencopy.receiver.protocol.Wire
import java.net.InetAddress
import java.net.Socket

internal class MediaSockets private constructor(val video: Socket, val audio: Socket) : AutoCloseable {
    fun output(track: Int) = when (track) {
        1 -> video.outputStream
        2 -> audio.outputStream
        else -> error("Expected media track")
    }

    override fun close() {
        video.close()
        audio.close()
    }

    companion object {
        fun attach(server: ReceiverServer, accepted: Message): MediaSockets {
            assertEquals(Type.SESSION_ACCEPT, accepted.type)
            val token = accepted.json().getString("mediaToken")
            val video = connect(server, accepted.session, token, "video")
            try {
                return MediaSockets(video, connect(server, accepted.session, token, "audio"))
            } catch (error: Exception) {
                video.close()
                throw error
            }
        }

        fun connect(server: ReceiverServer, session: Long, token: String, channel: String): Socket {
            val peer = Socket(InetAddress.getLoopbackAddress(), server.listeningPort).apply { soTimeout = 3000; tcpNoDelay = true }
            try {
                Wire.write(peer.outputStream, Message.json(Type.HELLO,
                    JSONObject().put("role", "media").put("channel", channel).put("token", token), session))
                val response = Wire.read(peer.inputStream)
                assertEquals(Type.HELLO, response.type)
                assertEquals(session, response.session)
                assertEquals(channel, response.json().getString("channel"))
                return peer
            } catch (error: Throwable) {
                peer.close()
                throw error
            }
        }
    }
}

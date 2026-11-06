package org.screencopy.receiver.net

import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.screencopy.receiver.protocol.*
import java.net.InetAddress
import java.net.Socket

@RunWith(AndroidJUnit4::class)
class PlainSessionTest {
    @Test fun codeFreeProductionListenerAcceptsOneCastWithoutPairingOrHumanIdentifiers() {
        val texture = SurfaceTexture(false)
        val surface = Surface(texture)
        val server = ReceiverServer("Family receiver test", surface, { _, _ -> }, { _, _ -> }, {}, port = 0)
        try {
            server.start()
            connect(server).use { owner ->
                hello(owner)
                send(owner, Type.SESSION_REQUEST, settings(), 1)
                assertEquals(Type.SESSION_ACCEPT, Wire.read(owner.inputStream).type)
                connect(server).use { contender ->
                    hello(contender)
                    send(contender, Type.SESSION_REQUEST, settings(), 2)
                    assertEquals("BUSY", Wire.read(contender.inputStream).json().string("code"))
                }
                send(owner, Type.STOP, JSONObject().put("reason", "user"), 1)
                assertEquals(Type.SESSION_END, Wire.read(owner.inputStream).type)
            }
        } finally {
            try { server.close() } finally { surface.release(); texture.release() }
        }
    }

    @Test fun restartAtTheSameEndpointNeedsNoNewHumanIdentifier() {
        val texture = SurfaceTexture(false)
        val surface = Surface(texture)
        fun server(port: Int) = ReceiverServer("Family receiver test", surface, { _, _ -> }, { _, _ -> }, {}, port = port)
        try {
            val port = server(0).use { it.start(); connect(it).use { peer -> hello(peer) }; it.listeningPort }
            server(port).use {
                it.start()
                connect(it).use { peer -> hello(peer) }
            }
        } finally { surface.release(); texture.release() }
    }

    private fun connect(server: ReceiverServer) = Socket(InetAddress.getLoopbackAddress(), server.listeningPort).apply { soTimeout = 3000 }
    private fun hello(peer: Socket) {
        send(peer, Type.HELLO, JSONObject().put("role", "sender").put("versions", JSONArray().put(3))
            .put("name", "Phone").put("code", true).put("sessionCode", "obsolete and ignored"))
        val response = Wire.read(peer.inputStream)
        assertEquals(Type.HELLO, response.type)
        assertEquals(setOf("role", "versions", "name"), response.json().keys().asSequence().toSet())
    }
    private fun send(peer: Socket, type: Type, payload: JSONObject, session: Long = 0) = Wire.write(peer.outputStream, Message.json(type, payload, session))
    private fun settings() = StrictJson.decode(
        """{"video":{"codec":"h264","encoder":"","bitrate":8000000,"maxSize":1920,"maxFps":60,"displayId":0,"orientation":"0","crop":null,"codecOptions":""},"audio":{"enabled":false,"codec":"opus","encoder":"","bitrate":192000,"source":"output","codecOptions":""}}""".toByteArray()
    )
}

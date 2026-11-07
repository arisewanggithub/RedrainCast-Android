package org.screencopy.receiver.net

import dev.screencopy.sender.ReceiverLink
import dev.screencopy.sender.ReceiverTarget
import dev.screencopy.sender.Settings
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.screencopy.receiver.protocol.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ReceiverOptionsPipelineTest {
    @Test fun newAndroidSenderAndReceiverUseTheSameMutualOnOffRequestAndIdleAudioSocket() {
        for (enabled in listOf(false, true)) {
            val selected = AtomicReference<Request>()
            ReceiverServer.forProtocolTests("Receiver", supportsPlaybackStatistics = true,
                validateRequest = selected::set).use { server ->
                server.start()
                ReceiverLink(ReceiverTarget("127.0.0.1", server.listeningPort)).use { link ->
                    link.connect()
                    assertTrue(link.receiverOptions.playbackStats)
                    val settings = Settings().apply { audio = false; showPlaybackStatistics = enabled }
                    val request = ReceiverOptionProtocol.apply(settings.request(),
                        link.receiverOptions.select(settings.showPlaybackStatistics))
                    assertEquals(enabled, request.has("receiverOptions"))
                    link.send(dev.screencopy.sender.Wire.control(dev.screencopy.sender.Wire.SESSION_REQUEST, 73, request))
                    val accepted = link.receive()
                    assertEquals(dev.screencopy.sender.Wire.SESSION_ACCEPT, accepted.type)
                    link.attach(73, accepted, false)
                    assertEquals(enabled, selected.get().receiverOptions.playbackStats)
                    settings.showPlaybackStatistics = !enabled
                    assertEquals(enabled, selected.get().receiverOptions.playbackStats)
                    link.send(dev.screencopy.sender.Wire.control(dev.screencopy.sender.Wire.STOP, 73,
                        JSONObject().put("reason", "mutual option complete")))
                    assertEquals(dev.screencopy.sender.Wire.SESSION_END, link.receive().type)
                }
            }
        }
    }

    @Test fun senderRejectsMalformedRawAcknowledgementsInsteadOfCoercingNumberTokensOrByteBounds() {
        for (envelope in listOf(
            """{"version":1.0,"supported":["playbackStats"]}""",
            """{"version":1e0,"supported":["playbackStats"]}""",
            """{"version":2,"supported":["playbackStats","playbackStats"]}""",
            """{"version":1,"supported":[],"future":""" + " ".repeat(2048) + "null}"
        )) {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
                val executor = Executors.newSingleThreadExecutor()
                val responder = executor.submit {
                    listener.accept().use { peer ->
                        Wire.read(peer.inputStream)
                        val raw = """{"role":"receiver","name":"raw fixture","versions":[3],"receiverOptions":$envelope}"""
                        Wire.write(peer.outputStream, Message(Type.HELLO, 0, 0, 0, -1, payload = raw.toByteArray()))
                    }
                }
                try {
                    ReceiverLink(ReceiverTarget("127.0.0.1", listener.localPort)).use { link ->
                        val error = assertThrows(java.io.IOException::class.java) { link.connect() }
                        assertTrue(error.message!!.startsWith("PROTOCOL:"))
                    }
                    responder.get(4, TimeUnit.SECONDS)
                } finally {
                    executor.shutdownNow()
                    assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
                }
            }
        }
    }

    @Test fun oldNewAndUnavailableFactoriesNegotiateOnlyActualIntersectionAndSelection() {
        for (factory in listOf(false, true)) for (offer in listOf(false, true))
            for (enabled in listOf(false, true)) for (recovery in listOf(false, true)) {
            val selected = AtomicReference<Request>()
            ReceiverServer.forProtocolTests("Receiver", supportsPlaybackStatistics = factory,
                validateRequest = selected::set).use { server ->
                server.start()
                Socket(InetAddress.getLoopbackAddress(), server.listeningPort).use { peer ->
                    peer.soTimeout = 3000
                    val hello = JSONObject().put("role", "sender").put("name", "wire fixture")
                        .put("versions", JSONArray().put(3))
                    if (offer) hello.put("receiverOptions", ReceiverOptionProtocol.IMPLEMENTED.wireValue())
                    if (recovery) hello.put("streamRecovery", true).put("streamRecoveryVersion", 1)
                    Wire.write(peer.outputStream, Message.json(Type.HELLO, hello))
                    val response = Wire.read(peer.inputStream)
                    assertEquals(Type.HELLO, response.type)
                    assertEquals(factory && offer, response.json().has("receiverOptions"))
                    val request = Settings().apply { audio = false }.request()
                    if (enabled) request.put("receiverOptions", JSONObject().put("version", 1).put("playbackStats", true))
                    Wire.write(peer.outputStream, Message.json(Type.SESSION_REQUEST, request, 73))
                    val acceptance = Wire.read(peer.inputStream)
                    assertEquals(Type.SESSION_ACCEPT, acceptance.type)
                    assertEquals(factory && offer && enabled, selected.get().receiverOptions.playbackStats)
                    MediaSockets.attach(server, acceptance).use {
                        assertNull(server.playbackStatistics)
                        Wire.write(peer.outputStream, Message.json(Type.STOP, JSONObject().put("reason", "done"), 73))
                        assertEquals(Type.SESSION_END, Wire.read(peer.inputStream).type)
                        assertNull(server.playbackStatistics)
                    }
                }
            }
        }
    }

    @Test fun newSenderOffersCapabilitiesAndOldReceiverFallbackOmitsEntireSelection() {
        for (revision in listOf<Int?>(null, 1, 2)) {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
                val executor = Executors.newSingleThreadExecutor()
                val received = executor.submit<JSONObject> {
                    listener.accept().use { peer ->
                        peer.soTimeout = 3000
                        val offer = Wire.read(peer.inputStream).json()
                        assertTrue(ReceiverOptionProtocol.capabilities(offer).playbackStats)
                        val reply = JSONObject().put("role", "receiver").put("name", "Windows wire fixture")
                            .put("versions", JSONArray().put(3)).put("videoRotation", true)
                        if (revision != null) reply.put("receiverOptions",
                            JSONObject().put("version", revision).put("supported", JSONArray().put("playbackStats")))
                        Wire.write(peer.outputStream, Message.json(Type.HELLO, reply))
                        Wire.read(peer.inputStream).json()
                    }
                }
                try {
                    ReceiverLink(ReceiverTarget("127.0.0.1", listener.localPort, "fixture")).use { link ->
                        link.connect()
                        val options = link.receiverOptions.select(true)
                        link.send(dev.screencopy.sender.Wire.control(dev.screencopy.sender.Wire.SESSION_REQUEST, 73,
                            ReceiverOptionProtocol.apply(Settings().request(), options)))
                        val request = received.get(4, TimeUnit.SECONDS)
                        assertEquals(revision == 1, request.has("receiverOptions"))
                        if (revision == 1)
                            assertTrue(ReceiverOptionProtocol.selection(request, ReceiverOptionProtocol.IMPLEMENTED).playbackStats)
                    }
                } finally {
                    executor.shutdownNow()
                    assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
                }
            }
        }
    }
}

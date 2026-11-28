package dev.screencopy.sender

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import org.screencopy.receiver.net.ReceiverServer
import org.screencopy.receiver.protocol.Request
import org.screencopy.receiver.protocol.Type
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.net.ServerSocket
import java.io.IOException

class SenderAudioSelectionTest {
    @Test(timeout = 10_000) fun olderReceiverWithoutAudioOnlyAckGetsExplicitUpdateOrEnableVideoError() {
        ServerSocket(0).use { legacy ->
            val peer = CompletableFuture.runAsync {
                legacy.accept().use { socket ->
                    val offer = Wire.read(socket.inputStream)
                    assertEquals(Wire.HELLO, offer.type)
                    assertEquals(1, offer.json().getInt("audioOnlyVersion"))
                    Wire.write(socket.outputStream, Wire.control(Wire.HELLO, 0,
                        JSONObject().put("role", "receiver").put("versions", JSONArray().put(3))
                            .put("name", "Legacy receiver")))
                }
            }
            val link = ReceiverLink(ReceiverTarget("127.0.0.1", legacy.localPort), senderVideoEnabled = false)
            try {
                val error = assertThrows(IOException::class.java) { link.connect() }
                peer.get(3, TimeUnit.SECONDS)
                assertTrue(error.toString(), error.toString().contains("does not support audio-only casting"))
                assertTrue(error.toString(), error.toString().contains("Update the receiver or enable video"))
            } finally { link.close() }
        }
    }

    @Test(timeout = 15_000) fun receiverRejectsAudioConfigurationOnVideoOnlySessionBeforeCreatingPlayer() {
        var audioPlayers = 0
        ReceiverServer.forProtocolTests("Receiver",
            audioFactory = { _, _, _ -> audioPlayers++; error("Disabled audio must not create a player") }).use { receiver ->
            receiver.start()
            ReceiverLink(ReceiverTarget("127.0.0.1", receiver.listeningPort)).use { link ->
                link.connect()
                val settings = Settings().apply { audio = false }
                link.send(Wire.control(Wire.SESSION_REQUEST, 17, settings.request()))
                val accepted = link.receive()
                assertEquals(listOf(1), Request.parse(settings.request()).tracks)
                link.attach(17, accepted, false)
                link.send(Wire.config(17, 2, 1, CodecConfig.audio("raw", byteArrayOf())))
                var response = link.receive()
                while (response.type == Wire.STATUS) response = link.receive()
                assertEquals(Wire.ERROR, response.type)
                assertEquals("PROTOCOL", response.json().getString("code"))
                assertEquals(0, audioPlayers)
            }
        }
    }

    @Test(timeout = 20_000) fun newSessionUsesItsAudioSnapshotForWireTracksAndCaptureStartup() {
        val requests = CopyOnWriteArrayList<Request>()
        val acceptedTracks = CopyOnWriteArrayList<List<Int>>()
        var audioPlayers = 0
        val original = Settings().apply { audioCodec = "opus"; maxFps = 47 }
        for (enabled in listOf(true, false)) {
            ReceiverServer.forProtocolTests("Receiver", validateRequest = requests::add,
                audioFactory = { _, _, _ -> audioPlayers++; error("Audio must not be configured during this test") },
                controlWrite = { socket, message ->
                    if (message.type == Type.SESSION_ACCEPT) {
                        val tracks = message.json().getJSONArray("tracks")
                        acceptedTracks.add((0 until tracks.length()).map(tracks::getInt))
                    }
                    org.screencopy.receiver.protocol.Wire.write(socket.outputStream, message)
                }).use { receiver ->
                receiver.start()
                var started: Settings? = null
                val fallbackSink = Mockito.mock(ScrcpyAdapter.Sink::class.java)
                Mockito.mockConstruction(ProjectionCapture::class.java) { capture, _ ->
                    Mockito.`when`(capture.validate(Mockito.any(Settings::class.java) ?: Settings()))
                        .thenAnswer { Settings.from((it.arguments[0] as Settings).saved()) }
                    Mockito.doAnswer {
                        started = Settings.from(it.getArgument<Settings>(0).saved())
                        (it.getArgument<ScrcpyAdapter.Sink>(2) as SenderSession).stop()
                        null
                    }.`when`(capture).start(Mockito.any(Settings::class.java) ?: Settings(),
                        Mockito.anyLong(), Mockito.any(ScrcpyAdapter.Sink::class.java) ?: fallbackSink)
                }.use { captures ->
                    Mockito.mockStatic(android.util.Log::class.java).use {
                        original.audio = enabled
                        val statuses = mutableListOf<String>()
                        val sender = SenderSession(Mockito.mock(Context::class.java), original,
                            ReceiverTarget("127.0.0.1", receiver.listeningPort), statuses::add, {}, {})
                        sender.projectionOwner = Mockito.mock(ProjectionOwner::class.java)
                        original.audio = !enabled
                        sender.run()
                        assertNotNull(statuses.toString(), started)
                        assertEquals(enabled, checkNotNull(started).audio)
                        assertEquals("opus", checkNotNull(started).audioCodec)
                        assertEquals(47, checkNotNull(started).maxFps)
                        assertEquals(if (enabled) listOf(1, 2) else listOf(1), requests.last().tracks)
                        assertEquals(requests.last().tracks, acceptedTracks.last())
                        Mockito.verify(captures.constructed().single(), Mockito.atLeastOnce()).close()
                        assertTrue(statuses.toString(), statuses.none { it.startsWith("Error:") })
                    }
                }
            }
        }
        assertEquals(listOf("opus", null), requests.map { it.audioCodec })
        assertEquals(listOf(listOf(1, 2), listOf(1)), acceptedTracks)
        assertEquals(0, audioPlayers)
    }
}

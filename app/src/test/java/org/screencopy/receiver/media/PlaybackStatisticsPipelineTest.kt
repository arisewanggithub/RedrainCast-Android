package org.screencopy.receiver.media

import dev.screencopy.sender.Settings
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.screencopy.receiver.net.MediaSockets
import org.screencopy.receiver.net.ReceiverServer
import org.screencopy.receiver.protocol.*
import java.net.InetAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue

class PlaybackStatisticsPipelineTest {
    @Test(timeout = 15_000) fun typedSnapshotsFollowRealMediaLanesGenerationHideAndStopWithoutNativeQueries() = stream(false)
    @Test(timeout = 15_000) fun negotiatedStatisticsSurviveRecoveryKeyCompletionAndNativeEpochReplacement() = stream(true)

    private fun stream(recovery: Boolean) {
        val failures = ConcurrentLinkedQueue<String>()
        val clocks = ConcurrentLinkedQueue<PlaybackClock>()
        ReceiverServer.forProtocolTests("Receiver", supportsPlaybackStatistics = true,
            onFailure = { failures.add(it.logMessage()) },
            videoFactory = { format, clock, failed, generation ->
                clocks.add(clock)
                VideoPlayer(format, clock, failed, Decoder(), generation)
            }).use { server ->
            server.start()
            Socket(InetAddress.getLoopbackAddress(), server.listeningPort).use { peer ->
                peer.soTimeout = 4000
                val completed = mutableSetOf<Long>()
                fun send(type: Type, json: JSONObject, session: Long = 73) =
                    Wire.write(peer.outputStream, Message.json(type, json, session))
                fun expect(type: Type): Message {
                    while (true) {
                        val packet = Wire.read(peer.inputStream)
                        if (packet.type == Type.PING) { send(Type.PONG, packet.json()); continue }
                        if (recovery && packet.type == Type.STATUS) {
                            assertTrue(packet.json().getString("state") in listOf(StreamRecoveryProtocol.KEY, StreamRecoveryProtocol.COMPLETE))
                            if (packet.json().getString("state") == StreamRecoveryProtocol.COMPLETE)
                                completed.add(packet.json().getLong("generation"))
                            continue
                        }
                        assertEquals(type, packet.type)
                        return packet
                    }
                }
                val hello = JSONObject().put("role", "sender").put("name", "stats fixture")
                    .put("versions", JSONArray().put(3))
                    .put("receiverOptions", ReceiverOptionProtocol.IMPLEMENTED.wireValue())
                if (recovery) hello.put("streamRecovery", true).put("streamRecoveryVersion", 2)
                send(Type.HELLO, hello, 0)
                assertTrue(ReceiverOptionProtocol.capabilities(expect(Type.HELLO).json()).playbackStats)
                send(Type.SESSION_REQUEST, ReceiverOptionProtocol.apply(Settings().apply { audio = false }.request(), ReceiverOptions(true)))
                MediaSockets.attach(server, expect(Type.SESSION_ACCEPT)).use { media ->
                    fun configure(generation: Long): Long {
                        Wire.write(media.video.outputStream, Message.json(Type.STREAM_CONFIG,
                            JSONObject().put("codec", "h264").put("width", 640).put("height", 360)
                                .put("csd", JSONArray().put("AAAAAWdC")), 73, 1, generation))
                        expect(Type.STREAM_READY)
                        val pts = maxOf(1_000_000 + generation * 20_000,
                            clocks.last().currentUs() ?: 0)
                        Wire.write(media.video.outputStream, Message(Type.VIDEO_FRAME, 73, 1, generation,
                            pts, 1, byteArrayOf(0, 0, 0, 1, 0x65, 1)))
                        return pts
                    }
                    server.showPlaybackStatistics(true)
                    val firstPts = configure(1)
                    await { server.playbackStatistics?.input?.video?.rendered == 1L }
                    val first = checkNotNull(server.playbackStatistics)
                    assertEquals(1L, first.input.generation)
                    assertEquals(6L, first.input.videoBytes)
                    assertEquals(1L, first.input.receivedVideo)
                    assertEquals(firstPts, first.input.video?.renderedPtsUs)
                    assertEquals(640, first.input.encodedWidth)
                    assertNotNull(first.input.videoLastPacketAgeMs)
                    assertTrue(checkNotNull(first.input.videoLastPacketAgeMs) >= 0)
                    assertNull(first.input.audioLastPacketAgeMs)
                    assertEquals(0, first.input.videoQueue?.entries)
                    assertNull(first.input.videoQueue?.oldestAgeMs)
                    assertNull(first.input.audio)
                    assertNull(first.presentedFps)
                    server.showPlaybackStatistics(false)
                    assertNull(server.playbackStatistics)
                    server.showPlaybackStatistics(true)
                    await { server.playbackStatistics != null }
                    assertNull(checkNotNull(server.playbackStatistics).rxFps)
                    val nextPts = configure(2)
                    await { server.playbackStatistics?.input?.generation == 2L }
                    val next = checkNotNull(server.playbackStatistics)
                    assertEquals(1L, next.input.video?.rendered)
                    assertEquals(nextPts, next.input.video?.renderedPtsUs)
                    if (recovery) assertSame(clocks.first(), clocks.last())
                    assertNull(next.presentedFps)
                    send(Type.STOP, JSONObject().put("reason", "statistics complete"))
                    expect(Type.SESSION_END)
                    if (recovery) assertEquals(setOf(1L, 2L), completed)
                    assertNull(server.playbackStatistics)
                }
            }
            assertTrue(failures.toString(), failures.isEmpty())
        }
    }

    private fun await(ready: () -> Boolean) {
        val end = System.nanoTime() + 3_000_000_000
        while (!ready() && System.nanoTime() < end) Thread.sleep(2)
        assertTrue("Statistics snapshot not ready", ready())
    }

    private class Decoder : VideoDecoder {
        override val description = "fake rendered-callback fixture"
        override val supportsRenderedStatistics = true
        private var callback: (Long, Long) -> Unit = { _, _ -> }
        private var output: VideoOutput? = null
        private var pts = 0L
        override fun renderedStatistics(listener: (Long, Long) -> Unit) { callback = listener }
        override fun dequeueInput() = 0
        override fun input(index: Int) = ByteBuffer.allocate(32)
        override fun queueInput(index: Int, size: Int, ptsUs: Long, keyFrame: Boolean) {
            pts = ptsUs
            output = VideoOutput(0, ptsUs, size, 0)
        }
        override fun dequeueOutput(): VideoOutput? = output.also { output = null }
        override fun releaseOutput(index: Int, renderAtNs: Long?) { if (renderAtNs != null) callback(pts, renderAtNs) }
        override fun close() {}
    }
}

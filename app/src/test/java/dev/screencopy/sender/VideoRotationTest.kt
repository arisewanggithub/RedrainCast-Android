package dev.screencopy.sender

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class VideoRotationTest {
    @Test fun naturalCropAndEncoderSizeStayFixedForEveryDisplayRotationAndManualOverride() {
        for (rotation in 0..3) for (locked in listOf(false, true)) for (degrees in listOf(0, 90, 180, 270)) {
            val settings = Settings().apply {
                cropWidth = 540; cropHeight = 1200; cropX = 270; cropY = 600
                maxSize = 1920; orientation = (if (locked) "@" else "") + degrees
            }
            val source = if (rotation % 2 == 0) CaptureDisplay(0, "Panel", 1080, 2400, rotation, 1)
                else CaptureDisplay(0, "Panel", 2400, 1080, rotation, 1)
            val geometry = ScrcpyVideoSizing.calculate(settings, DisplayInventory(listOf(source)))
            assertEquals(VideoSize(544, 1200), geometry.output)
            val transform = ProjectionTransform.coordinates(settings, source)
            assertEquals(540f / 1200, transform.second, 0.00001f)
            assertTrue(transform.first.all { it in 0.24999f..0.75001f })
            assertEquals((degrees - (if (locked) 0 else rotation * 90) + 360) % 360, VideoRotation.degrees(settings, source))
        }
    }

    @Test fun encodedPacketsCarryRotationWithoutChangingCsdGenerationPayloadOrPts() {
        val configs = mutableListOf<Int>()
        val packets = mutableListOf<Wire.Message>()
        val timeline = VideoRotationTimeline()
        val sink = object : ScrcpyAdapter.Sink {
            override fun config(track: Int, generation: Int, config: org.json.JSONObject) { configs += generation }
            override fun packet(packet: Wire.Message) { packets += packet }
        }
        val output = EncodedOutput(1, "h264", 44, sink, timeline::at)
        val csd = BackendTest().sps(640, 480)
        val bytes = WireTest.unhex("000000016588")
        for ((index, rotation) in listOf(0, 90, 180, 270, 0).withIndex()) {
            val pts = 1_000_000L + index * 20_000
            timeline.offer(pts, rotation)
            output.configure(csd)
            output.packet(bytes, pts, index == 0)
        }
        assertEquals(listOf(1), configs)
        assertEquals(listOf(1, 2, 4, 6, 0), packets.map { it.flags })
        assertTrue(packets.all { it.generation == 1 && it.payload === bytes })
        for (packet in packets) {
            val stream = ByteArrayOutputStream()
            Wire.write(stream, packet)
            val actual = Wire.read(stream.toByteArray().inputStream())
            assertEquals(packet.flags, actual.flags)
            assertEquals(packet.pts, actual.pts)
        }
        assertThrows(IOException::class.java) { Wire.write(ByteArrayOutputStream(), packets.first().copy(flags = 8)) }
    }

    @Test fun metadataWaitsForItsFrameAndRepeatedFramesKeepThePriorRotation() {
        val timeline = VideoRotationTimeline()
        assertThrows(IOException::class.java) { timeline.at(1) }
        timeline.offer(100, 0); timeline.offer(200, 90); timeline.offer(300, 180)
        assertEquals(0, timeline.at(100))
        assertEquals(0, timeline.at(199))
        assertEquals(90, timeline.at(250))
        assertEquals(180, timeline.at(400))
        assertThrows(IOException::class.java) { timeline.offer(299, 0) }
        assertThrows(IOException::class.java) { timeline.offer(500, 45) }
    }

    @Test fun capabilityNegotiationKeepsLegacyPeersAndRejectsMalformedCapabilities() {
        for (capability in listOf(null, false, true, "true", org.json.JSONObject.NULL)) {
            ServerSocket(0).use { server ->
                val peer = CompletableFuture.runAsync {
                    server.accept().use { socket ->
                        val hello = Wire.read(socket.inputStream)
                        assertTrue(hello.json().getBoolean("videoRotation"))
                        val response = Wire.obj("role" to "receiver", "versions" to org.json.JSONArray().put(3), "name" to "Fixture")
                        if (capability != null) response.put("videoRotation", capability)
                        Wire.write(socket.outputStream, Wire.control(Wire.HELLO, 0, response))
                    }
                }
                ReceiverLink(ReceiverTarget("127.0.0.1", server.localPort)).use { link ->
                    if (capability == "true" || capability === org.json.JSONObject.NULL)
                        assertThrows(IOException::class.java) { link.connect(2000) }
                    else { link.connect(2000); assertEquals(capability == true, link.videoRotation) }
                }
                peer.get(3, TimeUnit.SECONDS)
            }
        }
    }
}

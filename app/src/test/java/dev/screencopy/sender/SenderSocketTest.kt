package dev.screencopy.sender

import org.json.JSONArray
import dev.redrain.cast.diagnostics.DiagnosticKind
import dev.redrain.cast.diagnostics.DiagnosticPoint
import dev.redrain.cast.diagnostics.DiagnosticStage
import dev.redrain.cast.diagnostics.IncidentEvidence
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class SenderSocketTest {
    @Test fun explicitWorkSelectionRejectsOldReceiverBeforeSessionRequestWhileLegacyStillConnects() {
        for (work in listOf(false, true)) ServerSocket(0).use { server ->
            val flags = if (work) setOf("audio.timing", "audio.timing.work") else setOf("audio.timing")
            val peer = CompletableFuture.runAsync {
                server.accept().use { control ->
                    control.soTimeout = 3000
                    val offered = Wire.read(control.inputStream).json().getJSONObject("diagProxy")
                        .getJSONObject("offer").getJSONArray("receiverFlags")
                    assertEquals(flags, (0 until offered.length()).map { offered.getString(it) }.toSet())
                    val oldCapability = org.json.JSONObject().put("version", 2)
                        .put("receiverFlags", JSONArray().put("session").put("audio.timing"))
                    Wire.write(control.outputStream, Wire.control(Wire.HELLO, 0,
                        hello().json().put("diagProxy", oldCapability)))
                    assertEquals(-1, control.inputStream.read())
                }
            }
            ReceiverLink(ReceiverTarget("127.0.0.1", server.localPort)).use { link ->
                val claim = dev.redrain.cast.diagnostics.DiagnosticArmRegistry.Claim(
                    "pair", 9, 71, "sender-capture", flags, 100, Long.MAX_VALUE)
                if (!work) link.connectArmed(3000, claim) { 1378 }
                else assertTrue(assertThrows(IOException::class.java) {
                    link.connectArmed(3000, claim) { 1378 }
                }.message!!.contains("UNSUPPORTED_CONFIG"))
            }
            peer.get(3, TimeUnit.SECONDS)
        }
    }

    @Test fun armedHelloSendsRemainingLeaseAndRequiresReceiverCapability() {
        for (supported in listOf(false, true)) ServerSocket(0).use { server ->
            val peer = CompletableFuture.runAsync {
                server.accept().use { control ->
                    control.soTimeout = 3000
                    val offer = Wire.read(control.inputStream).json().getJSONObject("diagProxy")
                        .getJSONObject("offer")
                    assertEquals("71", offer.getString("session"))
                    assertEquals("pair", offer.getString("pairId"))
                    assertEquals("9", offer.getString("armGeneration"))
                    assertEquals(1378, offer.getInt("durationMs"))
                    assertEquals("[\"session\"]", offer.getJSONArray("receiverFlags").toString())
                    val response = hello().json()
                    if (supported) response.put("diagProxy",
                        org.screencopy.receiver.protocol.DiagnosticProxyNegotiation.receiverCapability())
                    Wire.write(control.outputStream, Wire.control(Wire.HELLO, 0, response))
                }
            }
            ReceiverLink(ReceiverTarget("127.0.0.1", server.localPort)).use { link ->
                val claim = dev.redrain.cast.diagnostics.DiagnosticArmRegistry.Claim(
                    "pair", 9, 71, "sender-capture", setOf("session"), 100, Long.MAX_VALUE)
                if (supported) link.connectArmed(3000, claim) { 1378 }
                else assertTrue(assertThrows(IOException::class.java) {
                    link.connectArmed(3000, claim) { 1378 }
                }.message!!.contains("UNSUPPORTED_CONFIG"))
            }
            peer.get(3, TimeUnit.SECONDS)
        }
    }

    @Test fun armedRequestAndAcceptancePreservePairIdentity() {
        ReceiverLink(ReceiverTarget("127.0.0.1", 1)).use { link ->
            val claim = dev.redrain.cast.diagnostics.DiagnosticArmRegistry.Claim(
                "pair", 9, 71, "sender-capture", setOf("session"), 100, Long.MAX_VALUE)
            val request = org.json.JSONObject()
            link.selectDiagnosticProxy(request, claim)
            val selected = request.getJSONObject("diagProxy")
            assertEquals(3, selected.length())
            assertEquals(2, selected.getInt("version"))
            assertEquals("pair", selected.getString("pairId"))
            assertEquals("9", selected.getString("armGeneration"))
            val proxy = org.json.JSONObject().put("version", 2).put("pairId", "pair")
                .put("armGeneration", "9").put("laneToken", "a".repeat(64))
                .put("captureId", "receiver-capture").put("clockId", "receiver-clock")
            val acceptance = org.json.JSONObject().put("diagProxy", proxy)
            assertEquals("receiver-capture", link.acceptedDiagnosticProxy(acceptance, claim).captureId)
            proxy.put("armGeneration", "10")
            assertThrows(IOException::class.java) { link.acceptedDiagnosticProxy(acceptance, claim) }
        }
    }

    @Test fun localCloseRetiresBothMediaSocketsBeforeControl() {
        val link = ReceiverLink(ReceiverTarget("127.0.0.1", 1))
        val sockets = ReceiverLink::class.java.getDeclaredField("sockets").apply { isAccessible = true }
            .get(link) as Array<*>
        val closed = mutableListOf<Int>()
        for (lane in 0..2) java.lang.reflect.Array.set(sockets, lane, object : Socket() {
            override fun close() { closed.add(lane) }
        })
        link.close()
        link.close()
        assertEquals(listOf(1, 2, 0), closed)
    }

    private val session = 71L
    private val token = "aB".repeat(32)
    private fun hello(rotation: Boolean? = true, uncapped: Boolean? = null) = Wire.control(Wire.HELLO, 0,
        Wire.obj("role" to "receiver", "versions" to JSONArray().put(3), "name" to "Fixture").apply {
            if (rotation != null) put("videoRotation", rotation)
            if (uncapped != null) put("uncappedFps", uncapped)
        })
    private fun accepted(audio: Boolean, value: String = token) = Wire.control(Wire.SESSION_ACCEPT, session,
        Wire.obj("tracks" to JSONArray().put(1).apply { if (audio) put(2) }, "mediaToken" to value))
    private fun ready(track: Int) = Wire.Message(Wire.STREAM_READY, 0, session, track, 1, -1,
        Wire.obj("ok" to true).toString().toByteArray())
    private fun frame(track: Int) = Wire.Message(if (track == 1) Wire.VIDEO_FRAME else Wire.AUDIO_PACKET,
        0, session, track, 1, 123, byteArrayOf(1, 2, 3, 4))

    private fun controlHello(server: ServerSocket, recovery: Boolean = false): Socket {
        val control = server.accept().apply { soTimeout = 3000 }
        val request = Wire.read(control.inputStream)
        assertEquals(Wire.HELLO, request.type)
        assertEquals(0L, request.session)
        assertEquals("sender", request.json().getString("role"))
        assertEquals("RedrainCast", request.json().getString("name"))
        assertEquals("[3]", request.json().getJSONArray("versions").toString())
        assertTrue(request.json().getBoolean("videoRotation"))
        val proxy = request.json().getJSONObject("diagProxy")
        assertEquals(2, proxy.getInt("version"))
        assertFalse(proxy.has("offer"))
        assertFalse(request.json().has("diagnostics"))
        val response = hello()
        if (recovery) Wire.write(control.outputStream, Wire.control(Wire.HELLO, 0,
            response.json().put("streamRecovery", true).put("streamRecoveryVersion",
                org.screencopy.receiver.protocol.StreamRecoveryProtocol.VERSION)))
        else Wire.write(control.outputStream, response)
        return control
    }

    private fun mediaHello(server: ServerSocket, channel: String): Socket {
        val media = server.accept().apply { soTimeout = 3000 }
        val request = Wire.read(media.inputStream)
        assertEquals(Wire.HELLO, request.type)
        assertEquals(session, request.session)
        assertEquals("media", request.json().getString("role"))
        assertEquals(channel, request.json().getString("channel"))
        assertEquals(token, request.json().getString("token"))
        Wire.write(media.outputStream, Wire.control(Wire.HELLO, session, Wire.obj("channel" to channel)))
        return media
    }

    private fun negotiate(link: ReceiverLink, audio: Boolean) {
        link.connect()
        link.requireVideoRotation()
        link.send(Wire.control(Wire.SESSION_REQUEST, session, Settings().apply { this.audio = audio }.request()))
        link.attach(session, link.receive(), audio)
    }

    @Test fun uncappedFpsNeedsExplicitReceiverSupportButLegacyPeersStillPermitCaps() {
        for (supported in listOf(null, false, true)) ServerSocket(0).use { server ->
            val peer = CompletableFuture.runAsync {
                server.accept().use { control ->
                    control.soTimeout = 3000
                    val offered = Wire.read(control.inputStream).json()
                    assertTrue(offered.getBoolean("uncappedFps"))
                    assertEquals(2, offered.getJSONObject("sessionTelemetry").getInt("version"))
                    Wire.write(control.outputStream, hello(uncapped = supported))
                }
            }
            ReceiverLink(ReceiverTarget("127.0.0.1", server.localPort)).use { link ->
                link.connect()
                assertFalse(link.sessionTelemetry)
                assertEquals(supported == true, link.uncappedFps)
                link.requireFpsSupport(60)
                if (supported == true) link.requireFpsSupport(0)
                else {
                    val error = assertThrows(IOException::class.java) { link.requireFpsSupport(0) }
                    assertTrue(error.message!!.contains("UNSUPPORTED_CONFIG"))
                    assertTrue(error.message!!.contains("select an explicit Maximum capture FPS"))
                }
            }
            peer.get(3, TimeUnit.SECONDS)
        }
    }

    @Test fun audioOnlyAcknowledgementMustBeOfferedAndMatchExactVersion() {
        data class Case(val videoEnabled: Boolean, val acknowledgement: Any?, val expectedError: String?)
        val cases = listOf(
            Case(false, 1, null),
            Case(false, null, "UNSUPPORTED_CONFIG"),
            Case(false, 2, "UNSUPPORTED_CONFIG"),
            Case(false, "1", "PROTOCOL"),
            Case(true, 1, "PROTOCOL")
        )
        for ((videoEnabled, acknowledgement, expectedError) in cases) ServerSocket(0).use { server ->
            val peer = CompletableFuture.runAsync {
                server.accept().use { control ->
                    control.soTimeout = 3000
                    val offer = Wire.read(control.inputStream).json()
                    assertEquals(!videoEnabled, offer.has("audioOnlyVersion"))
                    if (!videoEnabled) assertEquals(1, offer.getInt("audioOnlyVersion"))
                    val response = hello().json()
                    if (acknowledgement != null) response.put("audioOnlyVersion", acknowledgement)
                    Wire.write(control.outputStream, Wire.control(Wire.HELLO, 0, response))
                    assertEquals(-1, control.inputStream.read())
                }
            }
            val link = ReceiverLink(ReceiverTarget("127.0.0.1", server.localPort),
                senderVideoEnabled = videoEnabled)
            try {
                if (expectedError == null) link.connect()
                else {
                    val error = assertThrows(IOException::class.java) { link.connect() }
                    assertTrue(error.toString(), error.toString().contains(expectedError))
                }
            } finally { link.close() }
            peer.get(3, TimeUnit.SECONDS)
        }
    }

    @Test fun sessionTelemetryHelloRequestAndAcceptSelectOnlyExactCapability() {
        val capability = dev.redrain.cast.diagnostics.SessionTelemetryProtocol.capability()
        for (offered in listOf<org.json.JSONObject?>(capability, null,
            dev.redrain.cast.diagnostics.SessionTelemetryProtocol.capability().put("version", 1),
            dev.redrain.cast.diagnostics.SessionTelemetryProtocol.capability().put("intervalMs", 2000),
            dev.redrain.cast.diagnostics.SessionTelemetryProtocol.capability().put("maxBatchBytes", 8191))) {
            ServerSocket(0).use { server ->
                val selected = offered === capability
                val peer = CompletableFuture.runAsync {
                    server.accept().use { control ->
                        control.soTimeout = 3000
                        val helloRequest = Wire.read(control.inputStream).json()
                        val advertised = helloRequest.getJSONObject("sessionTelemetry")
                        assertEquals(2, advertised.getInt("version"))
                        assertEquals(1000, advertised.getInt("intervalMs"))
                        assertEquals(8192, advertised.getInt("maxBatchBytes"))
                        val response = hello().json()
                        if (offered != null) response.put("sessionTelemetry", offered)
                        Wire.write(control.outputStream, Wire.control(Wire.HELLO, 0, response))
                        val request = Wire.read(control.inputStream).json()
                        assertEquals(selected, request.has("sessionTelemetry"))
                        val acceptance = accepted(false).json()
                        if (selected) acceptance.put("sessionTelemetry", capability)
                        Wire.write(control.outputStream, Wire.control(Wire.SESSION_ACCEPT, session, acceptance))
                    }
                }
                ReceiverLink(ReceiverTarget("127.0.0.1", server.localPort)).use { link ->
                    link.connect()
                    assertEquals(selected, link.sessionTelemetry)
                    val request = Settings().apply { audio = false }.request()
                    link.selectSessionTelemetry(request)
                    link.send(Wire.control(Wire.SESSION_REQUEST, session, request))
                    assertEquals(selected, link.acceptedSessionTelemetry(link.receive().json()))
                }
                peer.get(3, TimeUnit.SECONDS)
            }
        }
    }

    @Test fun futureTelemetryHelloStillAttachesAndRoutesSessionWithoutTelemetry() {
        for (version in listOf(1, 3)) ServerSocket(0).use { server ->
            val peer = CompletableFuture.runAsync {
                server.accept().use { control ->
                    control.soTimeout = 3000
                    assertEquals(Wire.HELLO, Wire.read(control.inputStream).type)
                    val future = org.json.JSONObject().put("version", version)
                        .put("batching", org.json.JSONObject().put("mode", "new"))
                    Wire.write(control.outputStream, Wire.control(Wire.HELLO, 0,
                        hello().json().put("sessionTelemetry", future)))
                    val request = Wire.read(control.inputStream)
                    assertEquals(Wire.SESSION_REQUEST, request.type)
                    assertEquals(session, request.session)
                    assertFalse(request.json().has("sessionTelemetry"))
                    Wire.write(control.outputStream, accepted(false))
                    mediaHello(server, "video").use { video ->
                        mediaHello(server, "audio").use {
                            val config = Wire.read(video.inputStream)
                            assertEquals(Wire.STREAM_CONFIG, config.type)
                            assertEquals(session, config.session)
                            assertEquals(1, config.track)
                            Wire.write(control.outputStream, ready(1))
                            assertEquals(Wire.VIDEO_FRAME, Wire.read(video.inputStream).type)
                            val ping = Wire.read(control.inputStream)
                            assertEquals(Wire.PING, ping.type)
                            assertEquals(session, ping.session)
                            Wire.write(control.outputStream, Wire.control(Wire.PONG, session, Wire.obj("nonce" to 4)))
                            assertEquals(Wire.STOP, Wire.read(control.inputStream).type)
                            Wire.write(control.outputStream, Wire.control(Wire.SESSION_END, session, Wire.obj("reason" to "user")))
                        }
                    }
                }
            }
            ReceiverLink(ReceiverTarget("127.0.0.1", server.localPort)).use { link ->
                link.connect()
                assertFalse(link.sessionTelemetry)
                val request = Settings().apply { audio = false }.request()
                link.selectSessionTelemetry(request)
                assertFalse(request.has("sessionTelemetry"))
                link.send(Wire.control(Wire.SESSION_REQUEST, session, request))
                val acceptance = link.receive()
                assertFalse(link.acceptedSessionTelemetry(acceptance.json()))
                link.attach(session, acceptance, false)
                link.send(Wire.config(session, 1, 1, Wire.obj("codec" to "h264")))
                assertEquals(Wire.STREAM_READY, link.receive().type)
                link.send(frame(1))
                link.send(Wire.control(Wire.PING, session, Wire.obj("nonce" to 4)))
                val pong = link.receive()
                assertEquals(Wire.PONG, pong.type)
                assertEquals(session, pong.session)
                link.send(Wire.control(Wire.STOP, session, Wire.obj("reason" to "user")))
                assertEquals(Wire.SESSION_END, link.receive().type)
            }
            peer.get(5, TimeUnit.SECONDS)
        }
    }

    @Test fun sessionTelemetryRejectsMalformedHelloAndUnsolicitedOrMalformedAcceptance() {
        for (invalid in listOf<Any>("yes", org.json.JSONObject.NULL,
            dev.redrain.cast.diagnostics.SessionTelemetryProtocol.capability().put("intervalMs", "1000"),
            dev.redrain.cast.diagnostics.SessionTelemetryProtocol.capability().put("extra", 1))) {
            ServerSocket(0).use { server ->
                val peer = CompletableFuture.runAsync {
                    server.accept().use { control ->
                        Wire.read(control.inputStream)
                        Wire.write(control.outputStream, Wire.control(Wire.HELLO, 0,
                            hello().json().put("sessionTelemetry", invalid)))
                    }
                }
                ReceiverLink(ReceiverTarget("127.0.0.1", server.localPort)).use { link ->
                    assertThrows(IOException::class.java) { link.connect() }
                }
                peer.get(3, TimeUnit.SECONDS)
            }
        }
        ServerSocket(0).use { server ->
            val peer = CompletableFuture.runAsync { controlHello(server).use { } }
            ReceiverLink(ReceiverTarget("127.0.0.1", server.localPort)).use { link ->
                link.connect()
                assertThrows(IOException::class.java) {
                    link.acceptedSessionTelemetry(org.json.JSONObject().put("sessionTelemetry",
                        dev.redrain.cast.diagnostics.SessionTelemetryProtocol.capability()))
                }
                assertThrows(IOException::class.java) {
                    link.acceptedSessionTelemetry(org.json.JSONObject().put("sessionTelemetry", "invalid"))
                }
            }
            peer.get(3, TimeUnit.SECONDS)
        }
    }

    @Test fun uncappedCapabilityRejectsNonBooleanAcknowledgements() {
        for (invalid in listOf("true", 1, org.json.JSONObject.NULL)) ServerSocket(0).use { server ->
            val peer = CompletableFuture.runAsync {
                server.accept().use { control ->
                    control.soTimeout = 3000
                    Wire.read(control.inputStream)
                    Wire.write(control.outputStream, Wire.control(Wire.HELLO, 0, hello().json().put("uncappedFps", invalid)))
                }
            }
            ReceiverLink(ReceiverTarget("127.0.0.1", server.localPort)).use { link ->
                val error = assertThrows(IOException::class.java) { link.connect() }
                assertTrue(error.message!!.contains("uncappedFps must be boolean"))
            }
            peer.get(3, TimeUnit.SECONDS)
        }
    }

    @Test fun attachesBothSocketsAndRoutesConfigsMediaReadinessAndStopToTheirOwnLanes() {
        for (audio in listOf(false, true)) ServerSocket(0).use { server ->
            val peer = CompletableFuture.runAsync {
                controlHello(server).use { control ->
                    val request = Wire.read(control.inputStream)
                    assertEquals(Wire.SESSION_REQUEST, request.type)
                    assertEquals(audio, request.json().getJSONObject("audio").getBoolean("enabled"))
                    Wire.write(control.outputStream, accepted(audio))
                    mediaHello(server, "video").use { video ->
                        mediaHello(server, "audio").use { sound ->
                            val videoConfig = Wire.read(video.inputStream)
                            assertEquals(Wire.STREAM_CONFIG, videoConfig.type)
                            assertEquals(1, videoConfig.track)
                            if (audio) {
                                val audioConfig = Wire.read(sound.inputStream)
                                assertEquals(Wire.STREAM_CONFIG, audioConfig.type)
                                assertEquals(2, audioConfig.track)
                            } else {
                                sound.soTimeout = 150
                                assertThrows(SocketTimeoutException::class.java) { sound.inputStream.read() }
                            }
                            Wire.write(control.outputStream, ready(1))
                            if (audio) Wire.write(control.outputStream, ready(2))
                            assertEquals(Wire.VIDEO_FRAME, Wire.read(video.inputStream).type)
                            if (audio) assertEquals(Wire.AUDIO_PACKET, Wire.read(sound.inputStream).type)
                            assertEquals(Wire.PING, Wire.read(control.inputStream).type)
                            Wire.write(control.outputStream, Wire.control(Wire.PONG, session, Wire.obj("nonce" to 4)))
                            assertEquals(Wire.STOP, Wire.read(control.inputStream).type)
                            Wire.write(control.outputStream, Wire.control(Wire.SESSION_END, session, Wire.obj("reason" to "user")))
                        }
                    }
                }
            }
            ReceiverLink(ReceiverTarget("127.0.0.1", server.localPort)).use { link ->
                negotiate(link, audio)
                val budget = MediaBudget(audio)
                for (track in 1..if (audio) 2 else 1) {
                    budget.offer(Wire.config(session, track, 1, Wire.obj("codec" to if (track == 1) "h264" else "raw")))
                    budget.offer(frame(track))
                    link.send(checkNotNull(budget.pollLane(track, 1)))
                    assertNull(budget.pollLane(track, 1))
                }
                val first = link.receive()
                assertEquals(Wire.STREAM_READY, first.type)
                budget.ready(first.track, first.generation)
                if (audio) {
                    assertFalse(budget.allReady())
                    assertNull(budget.pollLane(1, 1))
                    val second = link.receive()
                    budget.ready(second.track, second.generation)
                }
                assertTrue(budget.allReady())
                link.send(checkNotNull(budget.pollLane(1, 1)))
                if (audio) link.send(checkNotNull(budget.pollLane(2, 1)))
                link.send(Wire.control(Wire.PING, session, Wire.obj("nonce" to 4)))
                assertEquals(Wire.PONG, link.receive().type)
                link.send(Wire.control(Wire.STOP, session, Wire.obj("reason" to "user")))
                assertEquals(Wire.SESSION_END, link.receive().type)
            }
            peer.get(5, TimeUnit.SECONDS)
        }
    }

    @Test fun firstControlReadIsAttributedWhileMediaAttachmentIsStillPending() {
        for (truncated in listOf(false, true)) ServerSocket(0).use { server ->
            val readStarted = CountDownLatch(1)
            val mediaWaiting = CountDownLatch(1)
            val sendControl = CountDownLatch(1)
            val read = AtomicReference<CompletableFuture<Wire.Message>>()
            val reports = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
            val finished = CountDownLatch(1)
            val capture = dev.redrain.cast.diagnostics.SenderTelemetrySession(false, { reports.add(it) },
                storageExecutor = java.util.concurrent.Executor { it.run() }, onFinished = finished::countDown)
            dev.redrain.cast.diagnostics.TelemetryRegistry.beginSender(session, capture)
            dev.redrain.cast.diagnostics.TelemetryRegistry.event("sender", session,
                DiagnosticKind.RECOVERY.ordinal + 1, DiagnosticStage.PRESSURE.ordinal, 1, 1,
                detail = listOf(0, 0, 0, 0, 1), pressure = true)
            val peer = CompletableFuture.runAsync {
                controlHello(server, recovery = true).use { control ->
                    Wire.read(control.inputStream)
                    Wire.write(control.outputStream, accepted(false))
                    server.accept().use { video ->
                        video.soTimeout = 3000
                        assertEquals("video", Wire.read(video.inputStream).json().getString("channel"))
                        mediaWaiting.countDown()
                        check(sendControl.await(3, TimeUnit.SECONDS))
                        if (truncated) {
                            val bytes = ByteArrayOutputStream().also { Wire.write(it, ready(1)) }.toByteArray()
                            control.outputStream.write(bytes, 0, 20)
                            control.outputStream.flush()
                            control.shutdownOutput()
                        } else Wire.write(control.outputStream, ready(1))
                        Wire.write(video.outputStream, Wire.control(Wire.HELLO, session, Wire.obj("channel" to "video")))
                        mediaHello(server, "audio").use { audio ->
                            assertEquals(-1, audio.inputStream.read())
                        }
                    }
                }
            }
            try {
                ReceiverLink(ReceiverTarget("127.0.0.1", server.localPort)).use { link ->
                    link.connect()
                    link.send(Wire.control(Wire.SESSION_REQUEST, session, Settings().request()))
                    val acceptance = link.receive()
                    val attachment = CompletableFuture.runAsync {
                        link.attach(session, acceptance, false) {
                            val result = CompletableFuture<Wire.Message>()
                            read.set(result)
                            CompletableFuture.runAsync {
                                try { result.complete(link.receive { readStarted.countDown() }) }
                                catch (error: Exception) { result.completeExceptionally(error) }
                            }
                        }
                    }
                    assertTrue(readStarted.await(3, TimeUnit.SECONDS))
                    assertTrue(mediaWaiting.await(3, TimeUnit.SECONDS))
                    assertFalse(attachment.isDone)
                    sendControl.countDown()
                    if (truncated) {
                        assertTrue(assertThrows(ExecutionException::class.java) {
                            read.get().get(3, TimeUnit.SECONDS)
                        }.cause is IOException)
                    } else {
                        assertEquals(Wire.STREAM_READY, read.get().get(3, TimeUnit.SECONDS).type)
                    }
                    attachment.get(3, TimeUnit.SECONDS)
                }
                peer.get(3, TimeUnit.SECONDS)
                capture.finish()
                assertTrue(finished.await(3, TimeUnit.SECONDS))
                val report = org.screencopy.receiver.protocol.StrictJson.decode(reports.last())
                val blocks = report.getJSONObject("sender").getJSONArray("blocks")
                val rows = (0 until blocks.length()).flatMap { i ->
                    val batch = blocks.getJSONArray(i).getJSONObject(1)
                    val parsed = dev.redrain.cast.diagnostics.SessionTelemetryProtocol.parse(batch.toString().toByteArray())
                    parsed.state + parsed.events
                }
                assertEquals(!truncated, rows.any {
                    it.kind == dev.redrain.cast.diagnostics.TelemetryRow.EVIDENCE_KIND &&
                        it.stage == IncidentEvidence.Stage.READY_RECEIVED.ordinal
                })
                assertEquals(truncated, rows.any {
                    it.kind == DiagnosticKind.TRANSPORT_HEALTH.ordinal + 1 &&
                        it.stage == DiagnosticStage.FAILED.ordinal
                })
            } finally {
                sendControl.countDown()
                dev.redrain.cast.diagnostics.TelemetryRegistry.end("sender", capture)
                capture.finish()
            }
        }
    }

    @Test fun malformedTokensTracksAndTransportOverridesCloseControlBeforeAnyAttachment() {
        val invalid = listOf(
            accepted(false, ""), accepted(false, "f".repeat(63)), accepted(false, "g".repeat(64)),
            accepted(false, "a".repeat(65)),
            Wire.control(Wire.SESSION_ACCEPT, session, Wire.obj("tracks" to JSONArray().put(1))),
            Wire.control(Wire.SESSION_ACCEPT, session, accepted(false).json().put("tracks", JSONArray().put(1).put(1))),
            Wire.control(Wire.SESSION_ACCEPT, session, accepted(false).json().put("tracks", JSONArray().put("1"))),
            Wire.control(Wire.SESSION_ACCEPT, session, accepted(false).json().put("mediaTransport", "tcp"))
        )
        for (accept in invalid) ServerSocket(0).use { server ->
            val peer = CompletableFuture.runAsync {
                controlHello(server).use { control ->
                    Wire.read(control.inputStream)
                    Wire.write(control.outputStream, accept)
                    assertEquals(-1, control.inputStream.read())
                    server.soTimeout = 100
                    assertThrows(SocketTimeoutException::class.java) { server.accept() }
                }
            }
            ReceiverLink(ReceiverTarget("127.0.0.1", server.localPort)).use { link ->
                link.connect()
                link.send(Wire.control(Wire.SESSION_REQUEST, session, Settings().apply { audio = false }.request()))
                val acceptance = link.receive()
                var validated = false
                assertThrows(Exception::class.java) {
                    link.attach(session, acceptance, false) { validated = true }
                }
                assertFalse(validated)
            }
            peer.get(3, TimeUnit.SECONDS)
        }
    }

    @Test fun rejectedTokenAndWrongAttachmentAcksCloseEveryOwnedSocket() {
        val replies = listOf(
            Wire.control(Wire.REJECT, session, Wire.obj("code" to "PROTOCOL", "message" to "Bad media token")),
            Wire.control(Wire.HELLO, session, Wire.obj("channel" to "audio")),
            Wire.control(Wire.HELLO, session + 1, Wire.obj("channel" to "video"))
        )
        for (reply in replies) ServerSocket(0).use { server ->
            val peer = CompletableFuture.runAsync {
                controlHello(server).use { control ->
                    Wire.read(control.inputStream)
                    Wire.write(control.outputStream, accepted(false))
                    server.accept().use { video ->
                        video.soTimeout = 2000
                        assertEquals(token, Wire.read(video.inputStream).json().getString("token"))
                        Wire.write(video.outputStream, reply)
                        assertEquals(-1, video.inputStream.read())
                        assertEquals(-1, control.inputStream.read())
                    }
                }
            }
            ReceiverLink(ReceiverTarget("127.0.0.1", server.localPort)).use { link ->
                assertThrows(IOException::class.java) { negotiate(link, false) }
            }
            peer.get(3, TimeUnit.SECONDS)
        }
    }

    @Test fun closingDuringSecondAttachmentInterruptsPendingReadAndReleasesBothMediaSockets() {
        ServerSocket(0).use { server ->
            val attaching = CountDownLatch(1)
            val peer = CompletableFuture.runAsync {
                controlHello(server).use { control ->
                    Wire.read(control.inputStream)
                    Wire.write(control.outputStream, accepted(false))
                    mediaHello(server, "video").use { video ->
                        server.accept().use { audio ->
                            audio.soTimeout = 2000
                            assertEquals("audio", Wire.read(audio.inputStream).json().getString("channel"))
                            attaching.countDown()
                            assertEquals(-1, audio.inputStream.read())
                            assertEquals(-1, video.inputStream.read())
                            assertEquals(-1, control.inputStream.read())
                        }

                    }
                }
            }
            ReceiverLink(ReceiverTarget("127.0.0.1", server.localPort)).use { link ->
                val work = CompletableFuture.runAsync { negotiate(link, false) }
                assertTrue(attaching.await(2, TimeUnit.SECONDS))
                link.close()
                assertTrue(assertThrows(ExecutionException::class.java) { work.get(2, TimeUnit.SECONDS) }.cause is IOException)
            }
            peer.get(3, TimeUnit.SECONDS)
        }
    }

    @Test fun rejectedAudioAttachmentAlsoReleasesPreviouslyAttachedVideoAndControl() {
        ServerSocket(0).use { server ->
            val peer = CompletableFuture.runAsync {
                controlHello(server).use { control ->
                    Wire.read(control.inputStream)
                    Wire.write(control.outputStream, accepted(false))
                    mediaHello(server, "video").use { video ->
                        server.accept().use { audio ->
                            audio.soTimeout = 2000
                            assertEquals("audio", Wire.read(audio.inputStream).json().getString("channel"))
                            Wire.write(audio.outputStream, Wire.control(Wire.REJECT, session,
                                Wire.obj("code" to "PROTOCOL", "message" to "Audio token rejected")))
                            assertEquals(-1, audio.inputStream.read())
                            assertEquals(-1, video.inputStream.read())
                            assertEquals(-1, control.inputStream.read())
                        }
                    }
                }
            }
            ReceiverLink(ReceiverTarget("127.0.0.1", server.localPort)).use { link ->
                assertTrue(assertThrows(IOException::class.java) { negotiate(link, false) }
                    .message.orEmpty().contains("Audio token rejected"))
            }
            peer.get(3, TimeUnit.SECONDS)
        }
    }

    @Test fun controlHeartbeatAndStopRemainIndependentOfBlockedVideoWriter() {
        ServerSocket(0).use { server ->
            val videoStarted = CountDownLatch(1)
            val peer = CompletableFuture.runAsync {
                controlHello(server).use { control ->
                    Wire.read(control.inputStream)
                    Wire.write(control.outputStream, accepted(false))
                    mediaHello(server, "video").use { video ->
                        video.receiveBufferSize = 1024
                        mediaHello(server, "audio").use {
                            assertTrue(videoStarted.await(2, TimeUnit.SECONDS))
                            assertEquals(Wire.PING, Wire.read(control.inputStream).type)
                            Wire.write(control.outputStream, Wire.control(Wire.PONG, session, Wire.obj("nonce" to 1)))
                            assertEquals(Wire.STOP, Wire.read(control.inputStream).type)
                            Wire.write(control.outputStream, Wire.control(Wire.SESSION_END, session, Wire.obj("reason" to "user")))
                        }
                    }
                }
            }
            ReceiverLink(ReceiverTarget("127.0.0.1", server.localPort)).use { link ->
                negotiate(link, false)
                val write = CompletableFuture.runAsync {
                    videoStarted.countDown()
                    link.send(frame(1).copy(payload = ByteArray(Wire.MAX_VIDEO)))
                }
                link.send(Wire.control(Wire.PING, session, Wire.obj("nonce" to 1)))
                assertEquals(Wire.PONG, link.receive().type)
                link.send(Wire.control(Wire.STOP, session, Wire.obj("reason" to "user")))
                assertEquals(Wire.SESSION_END, link.receive().type)
                link.close()
                try { write.get(2, TimeUnit.SECONDS) }
                catch (error: ExecutionException) { assertTrue(error.cause is IOException) }
            }
            peer.get(3, TimeUnit.SECONDS)
        }
    }

    @Test fun absentWindowsRotationCapabilityIsAnExplicitUnsupportedConfiguration() {
        ServerSocket(0).use { server ->
            val peer = CompletableFuture.runAsync {
                server.accept().use { socket ->
                    Wire.read(socket.inputStream)
                    Wire.write(socket.outputStream, hello(null))
                    assertEquals(-1, socket.inputStream.read())
                }
            }
            ReceiverLink(ReceiverTarget("127.0.0.1", server.localPort)).use { link ->
                link.connect()
                assertTrue(assertThrows(IOException::class.java) { link.requireVideoRotation() }
                    .message.orEmpty().startsWith("UNSUPPORTED_CONFIG"))
                assertThrows(IOException::class.java) { link.send(frame(1)) }
            }
            peer.get(3, TimeUnit.SECONDS)
        }
    }

    @Test fun absoluteHelloDeadlineCannotBeExtendedByTrickledBytes() {
        ServerSocket(0).use { server ->
            val peer = CompletableFuture.runAsync {
                try {
                    server.accept().use { socket ->
                        Wire.read(socket.inputStream)
                        val bytes = ByteArrayOutputStream().also { Wire.write(it, hello()) }.toByteArray()
                        for (byte in bytes) {
                            socket.outputStream.write(byte.toInt())
                            Thread.sleep(20)
                        }
                    }
                } catch (_: IOException) { }
            }
            ReceiverLink(ReceiverTarget("127.0.0.1", server.localPort)).use { link ->
                assertThrows(SocketTimeoutException::class.java) { link.connect(150) }
            }
            peer.get(3, TimeUnit.SECONDS)
        }
    }
}

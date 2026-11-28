package dev.screencopy.sender

import org.json.JSONArray
import java.io.Closeable
import java.io.IOException
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.screencopy.receiver.protocol.StreamRecoveryProtocol
import org.screencopy.receiver.protocol.ReceiverOptionProtocol
import org.screencopy.receiver.protocol.ReceiverOptionCapabilities
import dev.redrain.cast.diagnostics.SessionTelemetryProtocol
import dev.redrain.cast.diagnostics.DiagnosticArmRegistry
import org.screencopy.receiver.protocol.DiagnosticProxyNegotiation
import org.json.JSONObject

/** Three plain TCP connections on a trusted LAN; the media token binds them to one session. */
class ReceiverLink(private val target: ReceiverTarget, private val senderVideoEnabled: Boolean = true) : Closeable {
    private val sockets = Array(3) { Socket() }
    private val gates = Array(3) { Any() }
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val closed = AtomicBoolean()
    private val mediaClosed = AtomicBoolean()
    @Volatile private var attached = false
    internal val mediaAttached get() = attached
    @Volatile private var activeSession = 0L
    var videoRotation: Boolean = false
        private set
    var streamRecovery: Boolean = false
        private set
    var uncappedFps: Boolean = false
        private set
    var audioOnlyVersion: Int? = null
        private set
    var receiverOptions = ReceiverOptionCapabilities()
        private set
    internal var sessionTelemetry = false
        private set
    val connectedHost: String get() = checkNotNull(sockets[0].inetAddress?.hostAddress)

    private fun connectSocket(socket: Socket) {
        if (closed.get()) throw SessionCancelledException()
        socket.tcpNoDelay = true
        socket.soTimeout = 10_000
        socket.sendBufferSize = 64 * 1024
        socket.receiveBufferSize = 64 * 1024
        socket.connect(InetSocketAddress(target.host, target.port), 10_000)
    }

    private fun <T> deadline(timeoutMillis: Long, action: () -> T): T {
        require(timeoutMillis > 0) { "Deadline must be positive" }
        if (closed.get()) throw SessionCancelledException()
        val expired = AtomicBoolean()
        val timeout = timer.schedule({ expired.set(true); close() }, timeoutMillis, TimeUnit.MILLISECONDS)
        try {
            val result = action()
            if (expired.get()) throw SocketTimeoutException("TIMEOUT: receiver operation exceeded its deadline")
            return result
        } catch (error: IOException) {
            if (expired.get()) throw SocketTimeoutException("TIMEOUT: receiver operation exceeded its deadline").apply { initCause(error) }
            throw error
        } finally { timeout.cancel(false) }
    }

    private enum class AttachmentState { ACTIVE, COMPLETED, EXPIRED }

    private fun attachmentDeadline(timeoutMillis: Long,
                                   onTimeout: (SocketTimeoutException) -> Unit,
                                   action: () -> Unit) {
        require(timeoutMillis > 0) { "Deadline must be positive" }
        if (closed.get()) throw SessionCancelledException()
        val state = AtomicReference(AttachmentState.ACTIVE)
        val timeoutError = SocketTimeoutException("TIMEOUT: receiver operation exceeded its deadline")
        val timeout = timer.schedule({
            if (state.compareAndSet(AttachmentState.ACTIVE, AttachmentState.EXPIRED)) {
                try { onTimeout(timeoutError) } finally { close() }
            }
        }, timeoutMillis, TimeUnit.MILLISECONDS)
        try {
            action()
            state.compareAndSet(AttachmentState.ACTIVE, AttachmentState.COMPLETED)
            if (state.get() == AttachmentState.EXPIRED) throw timeoutError
        } catch (error: IOException) {
            state.compareAndSet(AttachmentState.ACTIVE, AttachmentState.COMPLETED)
            if (state.get() == AttachmentState.EXPIRED) {
                if (error !== timeoutError) timeoutError.initCause(error)
                throw timeoutError
            }
            throw error
        } finally {
            state.compareAndSet(AttachmentState.ACTIVE, AttachmentState.COMPLETED)
            timeout.cancel(false)
        }
    }

    fun connect(timeoutMillis: Long = 10_000) = connectInternal(timeoutMillis, null, null)

    internal fun connectArmed(timeoutMillis: Long, claim: DiagnosticArmRegistry.Claim,
                              remainingMs: () -> Int) {
        val leaseMillis = (claim.expiresNs - System.nanoTime()) / 1_000_000L
        if (leaseMillis <= 0) throw SocketTimeoutException("TIMEOUT: diagnostic arm expired")
        connectInternal(minOf(timeoutMillis, leaseMillis), claim, remainingMs)
    }

    private fun connectInternal(timeoutMillis: Long, claim: DiagnosticArmRegistry.Claim?,
                                remainingMs: (() -> Int)?) {
        try {
            deadline(timeoutMillis) {
                connectSocket(sockets[0])
                val proxy = DiagnosticProxyNegotiation.capability()
                if (claim != null) {
                    val remaining = checkNotNull(remainingMs).invoke()
                    if (remaining !in 1..180_000) throw IOException("TIMEOUT: diagnostic arm expired")
                    proxy.put("offer", JSONObject().put("session", claim.session.toString())
                        .put("pairId", claim.pairId).put("armGeneration", claim.generation.toString())
                        .put("receiverFlags", JSONArray(claim.receiverFlags.sorted()))
                        .put("durationMs", remaining).put("samplePeriodMs", claim.samplePeriodMs))
                }
                val senderHello = Wire.obj(
                    "role" to "sender", "versions" to JSONArray().put(3),
                    "name" to "RedrainCast", "videoRotation" to true, "streamRecovery" to true, "uncappedFps" to true,
                    "streamRecoveryVersion" to StreamRecoveryProtocol.VERSION,
                    "receiverOptions" to ReceiverOptionProtocol.IMPLEMENTED.wireValue(),
                    "diagProxy" to proxy,
                    "sessionTelemetry" to SessionTelemetryProtocol.capability()
                )
                if (!senderVideoEnabled) senderHello.put("audioOnlyVersion", 1)
                send(Wire.control(Wire.HELLO, 0, senderHello))
                val hello = receive()
                reject(hello)
                if (hello.type != Wire.HELLO || hello.session != 0L)
                    throw IOException("PROTOCOL: receiver HELLO session zero required")
                val json = hello.json()
                val offeredAudioOnlyVersion = if (senderVideoEnabled) null else 1
                if (json.has("audioOnlyVersion")) {
                    if (offeredAudioOnlyVersion == null)
                        throw IOException("PROTOCOL: unsolicited audioOnlyVersion acknowledgement")
                    if (json.opt("audioOnlyVersion") !is Int)
                        throw IOException("PROTOCOL: audioOnlyVersion must be an integer")
                    audioOnlyVersion = json.getInt("audioOnlyVersion").also {
                        if (it != offeredAudioOnlyVersion)
                            throw IOException("UNSUPPORTED_CONFIG: This receiver does not support audio-only casting version $offeredAudioOnlyVersion. Update the receiver or enable video.")
                    }
                }
                if (offeredAudioOnlyVersion != null && audioOnlyVersion != offeredAudioOnlyVersion)
                    throw IOException("UNSUPPORTED_CONFIG: This receiver does not support audio-only casting. Update the receiver or enable video.")
                if (claim != null) {
                    val supported = try {
                        DiagnosticProxyNegotiation.supportsReceiver(
                            org.screencopy.receiver.protocol.StrictJson.decode(hello.payload), claim.receiverFlags)
                    } catch (error: org.screencopy.receiver.protocol.ProtocolException) {
                        throw IOException("PROTOCOL: ${error.message}", error)
                    }
                    if (!supported) throw IOException("UNSUPPORTED_CONFIG: receiver does not support requested diagnostics")
                }
                sessionTelemetry = telemetrySupported(org.screencopy.receiver.protocol.StrictJson.decode(hello.payload))
                receiverOptions = try {
                    ReceiverOptionProtocol.capabilities(org.screencopy.receiver.protocol.StrictJson.decode(hello.payload))
                        .intersect(ReceiverOptionProtocol.IMPLEMENTED)
                } catch (error: org.screencopy.receiver.protocol.ProtocolException) {
                    throw IOException("PROTOCOL: ${error.message}", error)
                }
                val name = Json.string(json, "name")
                if (Json.string(json, "role") != "receiver" || name.isEmpty() || name.length > 80 || name.any(Char::isISOControl))
                    throw IOException("PROTOCOL: invalid receiver HELLO")
                val versions = json.getJSONArray("versions")
                if (versions.length() !in 1..16 || (0 until versions.length()).any { versions.get(it) !is Int })
                    throw IOException("PROTOCOL: invalid version list")
                if ((0 until versions.length()).none { versions.getInt(it) == 3 })
                    throw IOException("VERSION: receiver must support version 3")
                if (json.has("videoRotation") && json.opt("videoRotation") !is Boolean)
                    throw IOException("PROTOCOL: videoRotation must be boolean")
                videoRotation = json.opt("videoRotation") == true
                if (json.has("uncappedFps") && json.opt("uncappedFps") !is Boolean)
                    throw IOException("PROTOCOL: uncappedFps must be boolean")
                uncappedFps = json.opt("uncappedFps") == true
                if (json.has("streamRecovery") && json.opt("streamRecovery") !is Boolean)
                    throw IOException("PROTOCOL: streamRecovery must be boolean")
                streamRecovery = json.opt("streamRecovery") == true
                val recoveryVersion = if (json.has("streamRecoveryVersion")) Json.integer(json, "streamRecoveryVersion") else null
                if (recoveryVersion != null && recoveryVersion <= 0)
                    throw IOException("PROTOCOL: invalid stream recovery version")
                if (streamRecovery && recoveryVersion != StreamRecoveryProtocol.VERSION)
                    throw IOException("UNSUPPORTED_CONFIG: incompatible stream recovery protocol; update the receiver")
                if (streamRecovery) sockets[0].soTimeout = 0
            }
        } catch (error: Exception) { close(); throw error }
    }

    internal fun selectSessionTelemetry(request: JSONObject) {
        if (sessionTelemetry) request.put("sessionTelemetry", SessionTelemetryProtocol.capability())
    }

    internal fun selectDiagnosticProxy(request: JSONObject, claim: DiagnosticArmRegistry.Claim) {
        request.put("diagProxy", JSONObject().put("version", DiagnosticProxyNegotiation.VERSION)
            .put("pairId", claim.pairId).put("armGeneration", claim.generation.toString()))
    }

    internal fun acceptedDiagnosticProxy(acceptance: JSONObject, claim: DiagnosticArmRegistry.Claim):
        DiagnosticProxyNegotiation.Accepted = try {
        DiagnosticProxyNegotiation.accepted(acceptance, DiagnosticProxyNegotiation.Offer(
            claim.session, claim.pairId, claim.generation, claim.receiverFlags, claim.expiresNs, claim.samplePeriodMs))
    } catch (error: org.screencopy.receiver.protocol.ProtocolException) {
        throw IOException("PROTOCOL: ${error.message}", error)
    }

    internal fun acceptedSessionTelemetry(acceptance: JSONObject): Boolean {
        val selected = telemetrySupported(acceptance)
        if (selected && !sessionTelemetry)
            throw IOException("PROTOCOL: unsolicited session telemetry capability")
        return selected
    }

    private fun telemetrySupported(json: JSONObject): Boolean = try {
        SessionTelemetryProtocol.supported(json)
    } catch (error: org.screencopy.receiver.protocol.ProtocolException) {
        throw IOException("PROTOCOL: ${error.message}", error)
    }

    /** Fixed-natural capture always needs presentation metadata, including future display rotations. */
    fun requireVideoRotation() {
        if (!videoRotation)
            throw IOException("UNSUPPORTED_CONFIG: receiver does not support videoRotation metadata required by fixed-natural capture")
    }

    fun requireFpsSupport(maxFps: Int) {
        if (maxFps == 0 && !uncappedFps)
            throw IOException("UNSUPPORTED_CONFIG: receiver does not support uncapped capture FPS; update the receiver or select an explicit Maximum capture FPS")
    }

    fun attach(session: Long, acceptance: Wire.Message, withAudio: Boolean, timeoutMillis: Long = 10_000,
               onFailure: (Exception) -> Unit = {}, onValidated: () -> Unit = {}) =
        attachTracks(session, acceptance, true, withAudio, timeoutMillis, onFailure, onValidated)

    internal fun attachTracks(session: Long, acceptance: Wire.Message, withVideo: Boolean, withAudio: Boolean,
               timeoutMillis: Long = 10_000, onFailure: (Exception) -> Unit = {},
               onValidated: () -> Unit = {}) {
        val failureGate = Any()
        var failurePublished = false
        fun publishFailure(error: Exception) = synchronized(failureGate) {
            if (!failurePublished) {
                failurePublished = true
                onFailure(error)
            }
        }
        try {
            attachmentDeadline(timeoutMillis, ::publishFailure) {
                reject(acceptance)
                if (session <= 0 || acceptance.session != session || acceptance.type != Wire.SESSION_ACCEPT || attached)
                    throw IOException("PROTOCOL: invalid SESSION_ACCEPT")
                val json = acceptance.json()
                if (json.has("mediaTransport"))
                    throw IOException("PROTOCOL: media transport overrides are not supported")
                val tracks = json.getJSONArray("tracks")
                val actual = (0 until tracks.length()).map {
                    if (tracks.get(it) !is Int) throw IOException("PROTOCOL: track must be an integer")
                    tracks.getInt(it)
                }.sorted()
                val expected = listOfNotNull(1.takeIf { withVideo }, 2.takeIf { withAudio })
                if (actual.size != actual.toSet().size || actual != expected)
                    throw IOException("PROTOCOL: receiver did not accept exactly the requested tracks")
                val token = Json.string(json, "mediaToken")
                if (!token.matches(Regex("[0-9A-Fa-f]{64}")))
                    throw IOException("PROTOCOL: invalid media connection token")
                activeSession = session
                onValidated()
                for (track in 1..2) {
                    val channel = if (track == 1) "video" else "audio"
                    val socket = sockets[track]
                    connectSocket(socket)
                    Wire.write(socket.outputStream, Wire.control(Wire.HELLO, session,
                        Wire.obj("role" to "media", "channel" to channel, "token" to token)))
                    val ack = Wire.read(socket.inputStream)
                    reject(ack)
                    if (ack.type != Wire.HELLO || ack.session != session || Json.string(ack.json(), "channel") != channel)
                        throw IOException("PROTOCOL: receiver did not attach $channel connection")
                    socket.soTimeout = 0
                }
                attached = true
            }
        } catch (error: Exception) {
            // Publish the attachment failure before closing control wakes its concurrent reader.
            try { publishFailure(error) } finally { close() }
            throw error
        }
    }

    fun send(message: Wire.Message) {
        val media = message.type in setOf(Wire.STREAM_CONFIG, Wire.VIDEO_FRAME, Wire.AUDIO_PACKET)
        val lane = if (media) message.track else 0
        if (media && (!attached || lane !in 1..2 || message.session != activeSession))
            throw IOException("PROTOCOL: media connections are not attached to this session")
        if (message.type == Wire.VIDEO_FRAME && message.flags and 6 != 0 && !videoRotation)
            throw IOException("UNSUPPORTED_CONFIG: receiver did not negotiate videoRotation")
        synchronized(gates[lane]) {
            if (closed.get()) throw SessionCancelledException()
            if (message.type == Wire.STREAM_CONFIG)
                dev.redrain.cast.diagnostics.TelemetryRegistry.evidence("sender", message.session, message.track,
                    message.generation, dev.redrain.cast.diagnostics.IncidentEvidence.Stage.CONFIG_WRITING)
            deadline(10_000) { Wire.write(sockets[lane].outputStream, message) }
        }
        if (message.type == Wire.STREAM_CONFIG)
            dev.redrain.cast.diagnostics.TelemetryRegistry.evidence("sender", message.session, message.track,
                message.generation, dev.redrain.cast.diagnostics.IncidentEvidence.Stage.CONFIG_WRITTEN)
    }

    fun receive(): Wire.Message = receive {}

    internal fun receive(onReadStarted: () -> Unit): Wire.Message {
        val diagnostics = dev.redrain.cast.diagnostics.RemoteDiagnostics.recorder
        val operation = diagnostics.operation("sender", activeSession,
            dev.redrain.cast.diagnostics.DiagnosticKind.TRANSPORT_HEALTH)
        diagnostics.observe("sender", activeSession, dev.redrain.cast.diagnostics.DiagnosticKind.TRANSPORT_HEALTH,
            dev.redrain.cast.diagnostics.DiagnosticStage.BEGIN, operation = operation,
            point = dev.redrain.cast.diagnostics.DiagnosticPoint.READ)
        try {
            onReadStarted()
            return readControl().also {
                diagnostics.observe("sender", activeSession, dev.redrain.cast.diagnostics.DiagnosticKind.TRANSPORT_HEALTH,
                    dev.redrain.cast.diagnostics.DiagnosticStage.COMPLETE, operation = operation,
                    bytes = it.payload.size.toLong(), point = dev.redrain.cast.diagnostics.DiagnosticPoint.READ)
            }
        } catch (error: Exception) {
            diagnostics.observe("sender", activeSession, dev.redrain.cast.diagnostics.DiagnosticKind.TRANSPORT_HEALTH,
                dev.redrain.cast.diagnostics.DiagnosticStage.FAILED, operation = operation,
                point = dev.redrain.cast.diagnostics.DiagnosticPoint.READ)
            throw error
        }
    }

    private fun readControl(): Wire.Message {
        if (!streamRecovery) return Wire.read(sockets[0].inputStream).also { message ->
            if (message.type == Wire.STREAM_READY)
                dev.redrain.cast.diagnostics.TelemetryRegistry.evidence("sender", message.session, message.track,
                    message.generation, dev.redrain.cast.diagnostics.IncidentEvidence.Stage.READY_RECEIVED)
        }
        val expired = AtomicBoolean()
        var timeout: ScheduledFuture<*>? = null
        try {
            val message = Wire.read(sockets[0].inputStream) {
                timeout = timer.schedule({ expired.set(true); close() }, 10_000, TimeUnit.MILLISECONDS)
            }
            if (expired.get()) throw SocketTimeoutException("TIMEOUT: control frame exceeded its deadline")
            if (message.type == Wire.STREAM_READY)
                dev.redrain.cast.diagnostics.TelemetryRegistry.evidence("sender", message.session, message.track,
                    message.generation, dev.redrain.cast.diagnostics.IncidentEvidence.Stage.READY_RECEIVED)
            return message
        } catch (error: IOException) {
            if (expired.get()) throw SocketTimeoutException("TIMEOUT: control frame exceeded its deadline").apply { initCause(error) }
            throw error
        } finally { timeout?.cancel(false) }
    }

    internal fun watchMedia(track: Int) {
        require(track in 1..2 && attached)
        val count = sockets[track].inputStream.read()
        if (count < 0) throw EOFException("Receiver closed media track $track")
        throw IOException("PROTOCOL: unexpected media track reply")
    }

    internal fun closeMedia() {
        if (!mediaClosed.compareAndSet(false, true)) return
        var failure: IOException? = null
        for (lane in 1..2) try { sockets[lane].close() } catch (error: IOException) {
            if (failure == null) failure = error else failure?.addSuppressed(error)
        }
        failure?.let { throw it }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { closeMedia() } finally {
            try { sockets[0].close() } finally { timer.shutdownNow() }
        }
    }

    private fun reject(message: Wire.Message) {
        if (message.type == Wire.REJECT || message.type == Wire.ERROR)
            throw ReceiverReportedFailure(Json.string(message.json(), "code"), Json.string(message.json(), "message"))
    }
}

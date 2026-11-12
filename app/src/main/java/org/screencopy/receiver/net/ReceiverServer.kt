package org.screencopy.receiver.net

import android.media.MediaCodecList
import dev.redrain.cast.diagnostics.*
import android.view.Surface
import org.json.JSONArray
import org.json.JSONObject
import org.screencopy.receiver.lifecycle.ResourceSlot
import org.screencopy.receiver.media.AccessUnit
import org.screencopy.receiver.media.AudioFormat
import org.screencopy.receiver.media.AudioPlayer
import org.screencopy.receiver.media.PlaybackClock
import org.screencopy.receiver.media.PlaybackException
import org.screencopy.receiver.media.QueueCongestionException
import org.screencopy.receiver.media.QueuePressure
import org.screencopy.receiver.media.AudioOutputPreparationException
import org.screencopy.receiver.media.OutputPreparationDiagnostic
import org.screencopy.receiver.media.VideoPlayer
import org.screencopy.receiver.media.TrackWorker
import org.screencopy.receiver.media.TrackStoppedException
import org.screencopy.receiver.media.VideoPlaybackException
import org.screencopy.receiver.media.VideoDiagnostic
import org.screencopy.receiver.media.VideoFormat
import org.screencopy.receiver.media.PlaybackStatisticsInput
import org.screencopy.receiver.media.PlaybackStatisticsSampler
import org.screencopy.receiver.media.PlaybackStatisticsSnapshot
import org.screencopy.receiver.media.ControlStatistics
import org.screencopy.receiver.media.SourceTimestampObservation
import org.screencopy.receiver.protocol.*
import org.screencopy.receiver.ui.ReceiverScreenEvent
import org.screencopy.receiver.ui.FailureDiagnostic
import org.screencopy.receiver.ui.FailureOrigin
import org.screencopy.receiver.ui.ReceiverOrientationException
import org.screencopy.receiver.ui.VideoPresentation
import java.io.EOFException
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Protocol v3 uses one control socket and token-bound video/audio sockets, including an idle audio
 * socket when audio is disabled. Readiness, liveness and session controls always use control.
 *
 * Callbacks run on receiver threads. Presentation startup must synchronously acquire a UI-owned
 * orientation lease; its close restores only that session. Video size is the encoded raster size.
 * Rotation callbacks carry a predicate the UI must recheck before applying queued presentation work.
 * Unnegotiated rotation defaults to zero, as required for the Windows sender's logical raster.
 */
class ReceiverServer private constructor(
    name: String,
    private val surface: Surface?,
    private val onStatus: (String, String) -> Unit,
    private val onVideoSize: (Int, Int) -> Unit,
    private val onListener: (String) -> Unit,
    private val port: Int,
    private val videoOutput: ResourceSlot<VideoPlayer>,
    private val validateRequest: (Request) -> Unit,
    private val onScreenEvent: (ReceiverScreenEvent) -> Unit,
    private val onFailure: (FailureDiagnostic) -> Unit,
    private val onPresentationStart: (ReceiverOrientation) -> AutoCloseable,
    private val videoFactory: ((VideoFormat, PlaybackClock, (String, String) -> Unit, Long) -> VideoPlayer)?,
    private val audioFactory: ((AudioFormat, PlaybackClock, (String, String) -> Unit) -> AudioPlayer)? = null,
    private val clockFactory: () -> PlaybackClock = ::PlaybackClock,
    private val onDiscoveryDiagnostic: (String) -> Unit = {},
    private val onVideoRotation: (Int, () -> Boolean) -> Unit = { _, _ -> },
    private val acquireSession: (() -> AutoCloseable?)? = null,
    private val instanceId: String = UUID.randomUUID().toString().replace("-", ""),
    private val onFramePresented: (() -> Boolean) -> Unit = {},
    private val supportsPlaybackStatistics: Boolean = false,
    private val receiveCapabilities: () -> org.screencopy.receiver.protocol.ReceiveCapabilities = {
        org.screencopy.receiver.protocol.ReceiveCapabilities()
    },
    private val videoSurface: ((VideoFormat) -> Surface)? = null,
    private val timeMilliseconds: () -> Long = { System.nanoTime() / 1_000_000 },
    private val controlWrite: (Socket, Message) -> Unit = { target, message -> Wire.write(target.outputStream, message) },
    private val diagnosticDiscovery: (InetAddress) -> DiagnosticDiscovery? = { null },
    private val diagnosticSessionAccess: org.screencopy.receiver.protocol.DiagnosticSessionAccess? = null
) : AutoCloseable {
    private fun now() = timeMilliseconds()
    private data class VideoProofCandidate(
        val generation: Long, val ptsUs: Long, val audioObservation: SourceTimestampObservation?
    )
    constructor(
        name: String, surface: Surface,
        onStatus: (String, String) -> Unit, onVideoSize: (Int, Int) -> Unit,
        onListener: (String) -> Unit,
        port: Int = PORT, videoOutput: ResourceSlot<VideoPlayer> = ResourceSlot(),
        onScreenEvent: (ReceiverScreenEvent) -> Unit = {},
        onPresentationStart: (ReceiverOrientation) -> AutoCloseable = {
            if (it != ReceiverOrientation.AUTO) throw ReceiverOrientationException()
            AutoCloseable {}
        },
        onVideoRotation: (Int, () -> Boolean) -> Unit = { _, _ -> },
        acquireSession: (() -> AutoCloseable?)? = null,
        instanceId: String = UUID.randomUUID().toString().replace("-", ""),
        onFramePresented: (() -> Boolean) -> Unit = {},
        supportsPlaybackStatistics: Boolean = false,
        receiveCapabilities: () -> org.screencopy.receiver.protocol.ReceiveCapabilities = {
            org.screencopy.receiver.protocol.ReceiveCapabilities()
        },
        videoSurface: ((VideoFormat) -> Surface)? = null,
        diagnosticDiscovery: (InetAddress) -> DiagnosticDiscovery? = { null },
        diagnosticSessionAccess: org.screencopy.receiver.protocol.DiagnosticSessionAccess? = null
    ) : this(name, surface, onStatus, onVideoSize, onListener, port, videoOutput, ::validateCapabilities, onScreenEvent,
        { android.util.Log.e("ScreenCopyReceiver", it.logMessage()) }, onPresentationStart, null,
        onDiscoveryDiagnostic = { android.util.Log.i("ScreenCopyReceiver", it) }, onVideoRotation = onVideoRotation,
        acquireSession = acquireSession, instanceId = instanceId, onFramePresented = onFramePresented,
        supportsPlaybackStatistics = supportsPlaybackStatistics, receiveCapabilities = receiveCapabilities,
        videoSurface = videoSurface, diagnosticDiscovery = diagnosticDiscovery, diagnosticSessionAccess = diagnosticSessionAccess)

    private val name = name.take(80)
    private val running = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val active = AtomicReference<Connection?>()
    val busyForUpdate: Boolean get() = active.get() != null
    val playbackStatisticsRequested: Boolean get() = active.get()?.statisticsRequested() == true
    val playbackStatisticsPending: Boolean get() = active.get()?.statisticsPending() == true
    @Volatile var playbackStatistics: PlaybackStatisticsSnapshot? = null
        get() = field?.takeIf {
            statisticsVisible.get() && active.get()?.statisticsCurrent(it.input.session, it.input.generation, it.input.audioEpoch) == true
        }
        private set
    private val statisticsVisible = AtomicBoolean(false)
    @Synchronized fun showPlaybackStatistics(visible: Boolean) {
        if (statisticsVisible.getAndSet(visible) != visible || !visible) {
            playbackStatistics = null
            active.get()?.resetStatistics()
        }
        if (visible && statisticsTask == null && !statisticsTimer.isShutdown) {
            statisticsTask = statisticsTimer.scheduleWithFixedDelay(
                {
                    val owner = active.get()
                    if (owner?.statisticsRequested() == true) owner.sampleStatistics(now())
                    else showPlaybackStatistics(false)
                }, 0, 1, TimeUnit.SECONDS)
        } else if (!visible) {
            statisticsTask?.cancel(false)
            statisticsTask = null
        }
    }
    private val clients = ConnectionPool<Connection>({ it.abort() })
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val statisticsTimer = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "Receiver-statistics")
    }
    private var statisticsTask: java.util.concurrent.ScheduledFuture<*>? = null
    private var listener: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var udp: DatagramSocket? = null
    @Volatile private var networkSignature = ""
    private var ticks = 0
    private val advertisementPackets = AtomicLong()
    private var lastMaintenanceWarning = 0L
    val listeningPort: Int get() = checkNotNull(listener).localPort

    fun start() {
        require(port in 0..65535) { "TCP port must be between 0 and 65535" }
        require(name.isNotEmpty() && name.none { it.code < 32 }) { "Receiver name must be nonempty without control characters" }
        require(instanceId.matches(Regex("[A-Fa-f0-9]{32}"))) { "Receiver instance ID must be 32 hexadecimal characters" }
        check(started.compareAndSet(false, true)) { "Create a new receiver for each listener startup" }
        running.set(true)
        try {
            listener = ServerSocket()
            checkNotNull(listener).apply {
                reuseAddress = true
                bind(InetSocketAddress(port), 32)
            }
            udp = DatagramSocket().apply { broadcast = true }
            networkSignature = addresses().joinToString()
            acceptThread = Thread({ acceptConnections() }, "ScreenCopy-listener").also { it.start() }
            timer.scheduleWithFixedDelay({ maintenance() }, 0, 1, TimeUnit.SECONDS)
            publishListener()
            available()
            onDiscoveryDiagnostic("event=RECEIVER_LISTENER_STARTED tcpPort=$listeningPort")
        } catch (e: Exception) {
            onDiscoveryDiagnostic("event=RECEIVER_LISTENER_START_FAILED exception=${e.javaClass.simpleName}")
            close()
            throw e
        }
    }

    fun addresses(): List<String> = interfaces().flatMap { item ->
        item.inetAddresses.toList().filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
    }.sorted()

    fun disconnect(reason: String) { active.get()?.requestStop(reason) }

    fun displayCapabilitiesChanged() { active.get()?.checkHdrOutput() }

    private fun acceptConnections() {
        try {
            while (running.get()) {
                val socket = checkNotNull(listener).accept()
                val connection = Connection(socket)
                if (!clients.submit(connection) { connection.run() }) {
                    if (running.get()) onStatus("Connection limit reached", "At most 32 incoming connections are allowed. Wait and retry.")
                }
            }
        } catch (e: IOException) {
            if (running.get()) {
                onStatus("Listener failed", "${e.javaClass.simpleName}: close and reopen receiver to retry")
                onScreenEvent(ReceiverScreenEvent.ListenerStopped)
                onScreenEvent(ReceiverScreenEvent.Problem("LISTENER"))
            }
        }
    }

    private fun maintenance() {
        if (!running.get()) return
        var stage = "SESSIONS"
        try {
            clients.snapshot().forEach { it.tick() }
            if (ticks++ % 2 == 0) {
                stage = "INTERFACES"
                val signature = addresses().joinToString()
                if (signature != networkSignature) {
                    networkSignature = signature
                    disconnect("Receiver network changed; reconnect using its current address")
                    publishListener()
                    if (active.get() == null) available()
                }
                stage = "ADVERTISEMENT"
                advertise()
            }
        } catch (e: Exception) {
            val now = System.nanoTime()
            if (lastMaintenanceWarning == 0L || now - lastMaintenanceWarning >= 10_000_000_000L) {
                onDiscoveryDiagnostic("event=DISCOVERY_MAINTENANCE_FAILED stage=$stage exception=${e.javaClass.simpleName} running=${running.get()}")
                lastMaintenanceWarning = now
            }
            onStatus("Network maintenance error", "${e.message ?: e.javaClass.simpleName}. Manual connection may still be available; reopen receiver if it persists.")
            onScreenEvent(ReceiverScreenEvent.Notice("DISCOVERY"))
        }
    }

    private fun advertise() {
        val targets = interfaces().flatMap { it.interfaceAddresses }.mapNotNull { it.broadcast }.toSet()
        targets.forEach { address -> sendAdvertisement(InetSocketAddress(address, DISCOVERY_PORT)) }
        if (ticks % 10 == 1)
            onDiscoveryDiagnostic("event=DISCOVERY_ADVERTISE targets=${targets.size} destinations=${targets.map { it.hostAddress }.sorted().joinToString(",")} udpPort=$DISCOVERY_PORT packets=${advertisementPackets.get()} busy=${active.get() != null}")
    }

    internal fun sendAdvertisement(target: InetSocketAddress) {
        val socket = checkNotNull(udp)
        synchronized(socket) {
            val source = try {
                socket.connect(target)
                socket.localAddress
            } catch (e: SocketException) {
                socket.disconnect()
                onDiscoveryDiagnostic("event=DISCOVERY_ROUTE_UNAVAILABLE exception=${e.javaClass.simpleName}")
                null
            }
            try {
                val bytes = advertisement(source)
                socket.send(DatagramPacket(bytes, bytes.size, target))
            } finally {
                socket.disconnect()
            }
            advertisementPackets.incrementAndGet()
        }
    }

    internal fun advertisement(source: InetAddress? = null): ByteArray {
        val bytes = JSONObject().put("protocol", "screencopy").put("version", Wire.VERSION)
            .put("name", name).put("port", listeningPort)
            .put("instanceId", instanceId)
            .put("receiveCapabilities", receiveCapabilities().json())
            .put("state", if (active.get() == null) "available" else "busy")
            .apply { if (running.get() && source != null) diagnosticDiscovery(source)?.let { put("diagnostic", it.json()) } }
            .toString().toByteArray(Charsets.UTF_8)
        check(bytes.size <= 1024) { "Discovery advertisement exceeds 1024 bytes" }
        return bytes
    }

    private fun publishListener() {
        if (!running.get()) return
        val endpoints = addresses().map { "$it:$listeningPort" }
        if (running.get()) onScreenEvent(ReceiverScreenEvent.Listener(endpoints))
        if (running.get()) onListener("$name\n" +
            endpoints.joinToString("\n").ifEmpty { "No local IPv4 network; connect Wi-Fi or Ethernet (TCP $listeningPort)" } +
            "\nUnencrypted trusted-LAN TCP.")
    }

    private fun available() {
        if (running.get()) {
            onStatus("Available", "Select this screen's IP address on the sender. Surface ready; audio starts only when requested.")
            onScreenEvent(ReceiverScreenEvent.CleanupFinished)
        }
    }

    override fun close() {
        running.set(false)
        onDiscoveryDiagnostic("event=RECEIVER_LISTENER_STOPPED")
        timer.shutdownNow()
        statisticsTimer.shutdownNow()
        val errors = mutableListOf<Exception>()
        fun cleanup(action: () -> Unit) {
            try { action() } catch (e: Exception) { errors.add(e) }
        }
        cleanup { listener?.close() }
        cleanup { udp?.close() }
        cleanup { clients.close() }
        cleanup {
            acceptThread?.join(2000)
            check(acceptThread?.isAlive != true) { "Listener thread did not terminate after socket close" }
        }
        cleanup { check(clients.awaitTermination(5, TimeUnit.SECONDS)) { "Session cleanup timed out; reopen the app before receiving" } }
        cleanup { check(timer.awaitTermination(2, TimeUnit.SECONDS)) { "Network cleanup timed out" } }
        cleanup { check(statisticsTimer.awaitTermination(2, TimeUnit.SECONDS)) { "Statistics cleanup timed out" } }
        if (errors.isNotEmpty()) throw IllegalStateException("Receiver cleanup failed; reopen the app", errors.first()).also {
            errors.drop(1).forEach(it::addSuppressed)
        }
    }

    private inner class Connection(private val socket: Socket) {
        private var sessionLease: AutoCloseable? = null
        @Volatile private var writer: ControlWriter? = null
        @Volatile private var lastRead = now()
        @Volatile private var lastWrite = now()
        @Volatile private var phaseAt = now()
        @Volatile private var phase = "Setup"
        @Volatile private var session = 0L
        private var diagnosticsNegotiated = false
        private var diagnosticOwner = false
        private fun diagnosticReport(state: String) {
            if (active.get() !== this || !accepted || socket.isClosed || stopping.get()) return
            try {
                val endpoint = diagnosticDiscovery(socket.localAddress)
                val publishedState = if (state in setOf("enabled", "local", "frozen") && endpoint == null)
                    "unavailable" else state
                send(Type.STATUS, JSONObject().put("state", "diagnostics").put("message", publishedState)
                    .put("diagnostic", org.screencopy.receiver.protocol.DiagnosticDelegation.result(
                        publishedState, session, endpoint)))
            } catch (_: Exception) {
                onDiscoveryDiagnostic("event=DIAGNOSTIC_STATUS_UNAVAILABLE nonfatal=true")
            }
        }
        private fun diagnosticEnd() {
            if (!diagnosticOwner || active.get() !== this) return
            try { diagnosticSessionAccess?.ended(session) }
            catch (_: Exception) {
                onDiscoveryDiagnostic("event=DIAGNOSTIC_END_UNAVAILABLE nonfatal=true")
            }
        }
        private var senderName = ""
        private val stopping = AtomicBoolean(false)
        @Volatile private var stopAt = 0L
        @Volatile private var stopWrittenAt: Long? = null
        private val stopWritten = CountDownLatch(1)
        @Volatile private var readStartedAt: Long? = null
        @Volatile private var receiverStopRequested = false
        private val orderlyStop: Boolean get() =
            receiverStopRequested && streamRecovery && accepted && stopping.get() && !terminalError
        private val playbackError = AtomicReference<String?>()
        private val firstFailure = AtomicReference<FailureDiagnostic?>()
        private val connectionError = AtomicReference<Pair<String, String>?>()
        private val mediaDisconnect = AtomicReference<Pair<Exception, Long>?>()
        private val failureRetirement = AtomicBoolean()
        private val playbackLock = Any()
        private val trackPlaybackOperations = Array(3) { Any() }
        private val recoveryExecutor = Executors.newSingleThreadExecutor()
        private val recoveryScheduler = TrackRecoveryScheduler(::now)
        private var playbackClock: PlaybackClock? = null
        private val clock: PlaybackClock get() = synchronized(playbackLock) {
            playbackClock ?: clockFactory().also { playbackClock = it }
        }
        @Volatile private var video: VideoPlayer? = null
        @Volatile private var audio: AudioPlayer? = null
        private var presentation: AutoCloseable? = null
        @Volatile private var request: Request? = null
        private var tracks: TrackState? = null
        private var requestSummary = ""
        private var actualVideo = ""
        private var actualAudio = "Audio disabled explicitly"
        private var decoderDescription = ""
        @Volatile private var terminalError = false
        private var initializingTrack = 0
        private val framePresented = AtomicBoolean(false)
        @Volatile private var tracksReady = false
        @Volatile private var videoGeneration = 0L
        private var videoRotation = false
        private var hdrVideoVersion: Int? = null
        private var streamRecovery = false
        private var negotiatedOptions = ReceiverOptionCapabilities()
        fun checkHdrOutput() {
            val caps = receiveCapabilities()
            val toneMapped = synchronized(playbackLock) { activeVideoFormat?.toneMapToSdr == true }
            if (request?.videoColor != null && (!caps.acceptsHdr ||
                caps.nativeHdrAvailable != true && !toneMapped))
                mediaFailed(ProtocolException("UNSUPPORTED_CONFIG",
                    "HDR display output became unavailable; restart casting with SDR"))
        }

        private fun validateHdrOutput(format: VideoFormat): VideoFormat {
            if (format.videoColor == null) return format
            val caps = receiveCapabilities()
            protocolRequire(caps.acceptsHdr, "HDR output is unavailable; restart casting with SDR", "UNSUPPORTED_CONFIG")
            return format.copy(toneMapToSdr = caps.nativeHdrAvailable != true)
        }
        private val statisticsSampler = PlaybackStatisticsSampler()
        private val receivedVideo = AtomicLong()
        private val receivedAudio = AtomicLong()
        private val videoPayload = AtomicLong()
        private val audioPayload = AtomicLong()
        @Volatile private var lastVideoPacketAt: Long? = null
        @Volatile private var lastAudioPacketAt: Long? = null
        @Volatile private var actualRotation = 0
        private val controlStatistics = ControlStatistics(now())
        fun resetStatistics() {
            statisticsSampler.reset()
            if (active.get() === this) playbackStatistics = null
        }
        fun statisticsCurrent(id: Long, generation: Long, epoch: Long): Boolean =
            session == id && videoGeneration == generation && audioEpoch == epoch && accepted &&
                !stopping.get() && !terminalError && firstFailure.get() == null &&
                request?.receiverOptions?.playbackStats == true
        fun statisticsRequested(): Boolean = accepted && !stopping.get() && !terminalError &&
            firstFailure.get() == null && request?.receiverOptions?.playbackStats == true
        fun statisticsPending(): Boolean = !accepted && !stopping.get() && !terminalError &&
            firstFailure.get() == null
        @Volatile private var videoRecovering = false
        @Volatile private var audioRecovering = false
        private val recovering: Boolean get() = videoRecoveryAt > 0 || audioRecoveryAt > 0
        private var awaitingRecoveryKey = false
        @Volatile private var videoRecoveryAt = 0L
        @Volatile private var audioRecoveryAt = 0L
        private var recoveryFormat: AudioFormat? = null
        @Volatile private var activeVideoFormat: VideoFormat? = null
        @Volatile private var recoveryKeyPts: Long? = null
        @Volatile private var pendingVideoProof: VideoProofCandidate? = null
        private var audioRecoveryAdmitted = false
        private var audioRecoveryCutoff = 0L
        private var keyProvedGeneration = 0L
        private var completedGeneration = 0L
        private var completedAudioEpoch = 0L
        @Volatile private var audioGeneration = 0L
        @Volatile private var retiredAudioGeneration = 0L
        private var audioPacketTiming: AudioPacketTiming? = null
        @Volatile private var retiredVideoThrough = 0L
        @Volatile private var audioEpoch = 0L
        @Volatile private var retiredAudioThrough = 0L
        private val mediaLock = Any()
        private val mediaAttached = CountDownLatch(1)
        private val mediaChannels = arrayOfNulls<Connection>(2)
        private val mediaToken = ByteArray(32).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        @Volatile private var accepted = false
        @Volatile private var mediaOwner: Connection? = null
        private var mediaTrack = 0

        private fun attachMedia(hello: Message): Connection {
            session = hello.session
            val json = hello.json()
            val channel = json.string("channel", 16)
            protocolRequire(channel == "video" || channel == "audio", "Expected video or audio channel")
            val token = json.string("token", 64)
            val owner = active.get() ?: throw ProtocolException("PROTOCOL", "Unknown or retired media session")
            synchronized(owner.mediaLock) {
                protocolRequire(active.get() === owner && owner.accepted && !owner.stopping.get() &&
                    hello.session == owner.session && token == owner.mediaToken,
                    "Unknown or retired media session")
                mediaTrack = if (channel == "video") 1 else 2
                protocolRequire(owner.mediaChannels[mediaTrack - 1] == null, "Duplicate media channel")
                mediaOwner = owner
                owner.mediaChannels[mediaTrack - 1] = this
                if (owner.mediaChannels.all { it != null }) owner.mediaAttached.countDown()
            }
            return owner
        }

        private fun readMedia(owner: Connection) {
            send(Type.HELLO, JSONObject().put("channel", if (mediaTrack == 1) "video" else "audio"))
            protocolRequire(owner.mediaAttached.await(10, TimeUnit.SECONDS),
                "Both video and audio media connections are required", "TIMEOUT")
            socket.soTimeout = 0
            phase = "Media"
            while (running.get() && !socket.isClosed && (!owner.stopping.get() || owner.orderlyStop)) {
                val operation = RemoteDiagnostics.recorder.operation("receiver", owner.session,
                    if (mediaTrack == 2 && RemoteDiagnostics.recorder.enabled("receiver", owner.session, DiagnosticKind.AUDIO_TRANSPORT))
                        DiagnosticKind.AUDIO_TRANSPORT else DiagnosticKind.TRANSPORT_HEALTH)
                if (mediaTrack == 2) RemoteDiagnostics.recorder.observe("receiver", owner.session,
                    DiagnosticKind.AUDIO_TRANSPORT, DiagnosticStage.BEGIN, mediaTrack, operation = operation,
                    point = DiagnosticPoint.READ)
                RemoteDiagnostics.recorder.observe("receiver", owner.session, DiagnosticKind.TRANSPORT_HEALTH,
                    DiagnosticStage.BEGIN, mediaTrack, operation = operation, point = DiagnosticPoint.READ)
                val message = try { read() } catch (e: Exception) {
                    if (mediaTrack == 2) RemoteDiagnostics.recorder.observe("receiver", owner.session,
                        DiagnosticKind.AUDIO_TRANSPORT, DiagnosticStage.FAILED, mediaTrack, operation = operation,
                        reason = DiagnosticReason.FAILURE, point = DiagnosticPoint.READ)
                    RemoteDiagnostics.recorder.observe("receiver", owner.session, DiagnosticKind.TRANSPORT_HEALTH,
                        DiagnosticStage.FAILED, mediaTrack, operation = operation,
                        reason = DiagnosticReason.FAILURE, point = DiagnosticPoint.READ)
                    throw e
                }
                if (mediaTrack == 2) RemoteDiagnostics.recorder.observe("receiver", owner.session,
                    DiagnosticKind.AUDIO_TRANSPORT, DiagnosticStage.COMPLETE, mediaTrack, message.generation.toInt(),
                    operation, message.ptsUs, bytes = message.payload.size.toLong(), point = DiagnosticPoint.READ)
                RemoteDiagnostics.recorder.observe("receiver", owner.session, DiagnosticKind.TRANSPORT_HEALTH,
                    DiagnosticStage.COMPLETE, mediaTrack, message.generation.toInt(), operation, message.ptsUs,
                    bytes = message.payload.size.toLong(), point = DiagnosticPoint.READ)
                if (owner.stopping.get()) {
                    if (owner.orderlyStop) continue
                    return
                }
                protocolRequire(message.session == owner.session && message.track == mediaTrack &&
                    (message.type == Type.STREAM_CONFIG ||
                        message.type == if (mediaTrack == 1) Type.VIDEO_FRAME else Type.AUDIO_PACKET),
                    "Packet does not belong to this media channel")
                protocolRequire(mediaTrack == 1 || owner.request?.audioCodec != null, "Audio was not enabled")
                if (message.type == Type.STREAM_CONFIG) owner.configure(message)
                else owner.receiveMedia(message)
            }
        }

        private fun receiveMedia(message: Message) {
            if (message.track == 1) RemoteDiagnostics.recorder.video("receiver", session,
                message.generation.toInt(), DiagnosticVideoStage.RECEIVED, message.ptsUs,
                message.payload.size.toLong(), keyFrame = message.flags and 1 != 0)
            val packet = AccessUnit(message.ptsUs, message.payload, message.flags and 1 != 0,
                (message.flags ushr 1) * 90)
            var admissionEpoch = 0L
            var recoveryKeyAccepted = false
            val target: TrackWorker? = synchronized(playbackLock) {
                if (stopping.get()) return@synchronized null
                protocolRequire(accepted, "Session has ended")
                protocolRequire(videoRotation || message.flags <= 1, "Video rotation was not negotiated")
                checkNotNull(tracks).media(message)
                if (request?.receiverOptions?.playbackStats == true) {
                    if (message.track == 1) {
                        receivedVideo.incrementAndGet()
                        videoPayload.addAndGet(message.payload.size.toLong())
                        lastVideoPacketAt = now()
                    } else {
                        receivedAudio.incrementAndGet()
                        audioPayload.addAndGet(message.payload.size.toLong())
                        lastAudioPacketAt = now()
                    }
                }
                val audioEnd = if (streamRecovery && message.type == Type.AUDIO_PACKET)
                    checkNotNull(audioPacketTiming).endPts(packet.ptsUs, packet.bytes) else null
                if (message.type == Type.AUDIO_PACKET && request?.audioCodec == "raw")
                    protocolRequire(message.payload.size % 4 == 0, "Raw audio is not sample aligned")
                if (streamRecovery && message.type == Type.VIDEO_FRAME && videoRecovering) {
                    if (!awaitingRecoveryKey || !packet.keyFrame) return@synchronized null
                    RandomAccessPoint.verify(checkNotNull(request).videoCodec, packet.bytes, checkNotNull(activeVideoFormat).configuration)
                    videoRecovering = false
                    awaitingRecoveryKey = false
                    recoveryKeyPts = packet.ptsUs
                    pendingVideoProof = null
                    checkNotNull(video).discardOutputBeforePts = packet.ptsUs
                    recoveryKeyAccepted = true
                }
                if (streamRecovery && message.type == Type.AUDIO_PACKET && audioRecovering) {
                    if (message.generation <= retiredAudioGeneration) return@synchronized null
                    if (!audioRecoveryAdmitted) {
                        val cutoff = maxOf(audioRecoveryCutoff, clock.currentUs() ?: audioRecoveryCutoff)
                        if (checkNotNull(audioEnd) <= checkNotNull(audioPacketTiming).windowStart(cutoff))
                            return@synchronized null
                        checkNotNull(audio).recoveryCutoffUs = cutoff
                        audioRecoveryAdmitted = true
                    }
                }
                if (message.type == Type.VIDEO_FRAME) {
                    clock.anchorVideo(packet.ptsUs)
                    if (request?.videoCodec != "av1")
                        protocolRequire(Configuration.annexB(message.payload), "Expected Annex B video access unit")
                    checkNotNull(video)
                } else checkNotNull(audio).also { admissionEpoch = audioEpoch }
            }
            if (recoveryKeyAccepted)
                onDiscoveryDiagnostic("event=STREAM_RECOVERY_KEY track=1 generation=${message.generation} pts_us=${message.ptsUs}")
            if (target == null) {
                if (message.track == 1) RemoteDiagnostics.recorder.video("receiver", session,
                    message.generation.toInt(), DiagnosticVideoStage.REJECTED, message.ptsUs,
                    message.payload.size.toLong(), reason = DiagnosticReason.STALE)
                if (message.track == 2) RemoteDiagnostics.recorder.observe("receiver", session,
                    DiagnosticKind.AUDIO_TRANSPORT, DiagnosticStage.REJECTED, 2, message.generation.toInt(),
                    pts = message.ptsUs, bytes = message.payload.size.toLong(), reason = DiagnosticReason.STALE)
                return
            }
            // Admission may wait; never hold the other media lane behind a full track.
            try {
                val pressure = if (!streamRecovery) { target.offer(packet); null } else target.tryOffer(packet)
                if (message.track == 1) RemoteDiagnostics.recorder.video("receiver", session,
                    message.generation.toInt(), if (pressure == null) DiagnosticVideoStage.ADMITTED else DiagnosticVideoStage.REJECTED,
                    message.ptsUs, message.payload.size.toLong(), keyFrame = packet.keyFrame,
                    reason = if (pressure == null) DiagnosticReason.NONE else DiagnosticReason.CAPACITY)
                pressure?.let { requestRecovery(it) }
                if (message.track == 2) RemoteDiagnostics.recorder.observe("receiver", session,
                    DiagnosticKind.AUDIO_TRANSPORT, if (pressure == null) DiagnosticStage.ADMITTED else DiagnosticStage.REJECTED,
                    2, message.generation.toInt(), pts = message.ptsUs, bytes = message.payload.size.toLong(),
                    reason = if (pressure == null) DiagnosticReason.NONE else DiagnosticReason.CAPACITY)
            } catch (pressure: QueueCongestionException) {
                if (message.track == 1) RemoteDiagnostics.recorder.video("receiver", session,
                    message.generation.toInt(), DiagnosticVideoStage.REJECTED, message.ptsUs,
                    message.payload.size.toLong(), reason = DiagnosticReason.CAPACITY)
                throw pressure
            } catch (retired: TrackStoppedException) {
                val oldEpoch = synchronized(playbackLock) {
                    if (message.track == 1) video !== target || message.generation <= retiredVideoThrough
                    else audio !== target || admissionEpoch <= retiredAudioThrough
                }
                if (!oldEpoch && accepted && !stopping.get()) throw retired
                if (message.track == 1) RemoteDiagnostics.recorder.video("receiver", session,
                    message.generation.toInt(), DiagnosticVideoStage.REJECTED, message.ptsUs,
                    message.payload.size.toLong(), reason = DiagnosticReason.STALE)
                onDiscoveryDiagnostic("event=RETIRED_MEDIA_ADMISSION track=${message.track}")
            }
        }

        private fun requestRecovery(pressure: QueuePressure) {
            val track = pressure.track
            val generation = synchronized(playbackLock) {
                if (!accepted || stopping.get() || !tracksReady) return
                if (track == 1) {
                    if (videoRecovering) return
                    videoRecovering = true
                    retiredVideoThrough = maxOf(retiredVideoThrough, videoGeneration)
                    awaitingRecoveryKey = false
                    if (videoRecoveryAt == 0L) videoRecoveryAt = now()
                    recoveryKeyPts = null
                    pendingVideoProof = null
                } else {
                    if (audioRecovering && audioGeneration <= retiredAudioGeneration) return
                    clock.beginAudioRecovery()
                    audioRecovering = true
                    retiredAudioThrough = audioEpoch
                    retiredAudioGeneration = audioGeneration
                    audioRecoveryAdmitted = false
                    if (audioRecoveryAt == 0L) audioRecoveryAt = now()
                }
                phase = "Recovering"
                playbackStatistics = null
                if (track == 1) videoGeneration else audioGeneration
            }
            onDiscoveryDiagnostic("event=STREAM_RECOVERY_REQUEST track=$track generation=$generation ${pressure.logMessage()}")
            RemoteDiagnostics.recorder.observe("receiver", session, DiagnosticKind.RECOVERY, DiagnosticStage.PRESSURE,
                track, generation.toInt(), reason = DiagnosticReason.PRESSURE)
            send(Type.STATUS, JSONObject().put("state", "stream-recovery-request")
                .put("message", "Receiver is discarding an obsolete dependency chain")
                .put("track", track).put("generation", generation.toInt()))
        }

        private fun closeMedia() {
            synchronized(mediaLock) {
                accepted = false
                mediaAttached.countDown()
                mediaChannels.filterNotNull().forEach { it.abort() }
            }
        }

        private fun mediaFailed(error: Exception) {
            if (!running.get() || stopping.get() || !accepted) return
            if (error is EOFException || error is SocketException) {
                if (mediaDisconnect.compareAndSet(null, error to now())) {
                    onDiscoveryDiagnostic("event=MEDIA_DISCONNECT_AWAITING_CONTROL exception=${error.javaClass.simpleName}")
                    closeMedia()
                }
                return
            }
            terminateMediaFailure(error)
        }

        private fun terminateMediaFailure(error: Exception) {
            if (!running.get() || stopping.get()) return
            val code = when (error) {
                is ProtocolException -> error.code
                is PlaybackException -> error.code
                is java.net.SocketTimeoutException -> "TIMEOUT"
                is IOException -> "PROTOCOL"
                else -> "INTERNAL"
            }
            val detail = "Media connection failed: ${error.message ?: error.javaClass.simpleName}"
            if (!connectionError.compareAndSet(null, code to detail)) return
            stopAt = now()
            if (!stopping.compareAndSet(false, true)) return
            terminalError = true
            reportFailure(code, detail, when {
                error is ReceiverOrientationException -> FailureOrigin.PRESENTATION
                initializingTrack == 1 -> FailureOrigin.VIDEO
                initializingTrack == 2 -> FailureOrigin.AUDIO
                else -> FailureOrigin.SESSION
            }, (error as? QueueCongestionException)?.pressure,
                (error as? AudioOutputPreparationException)?.diagnostic,
                (error as? VideoPlaybackException)?.diagnostic)
            onStatus("Session error: $code", "$detail\nReconnect after correcting the sender settings or network.")
            try {
                send(Type.ERROR, JSONObject().put("code", code).put("message", detail).put("fatal", true))
            } finally { retireFailedSession() }
        }

        fun run() {
            try {
                socket.tcpNoDelay = true
                socket.soTimeout = 10_000
                writer = ControlWriter(socket, { message ->
                    lastWrite = now()
                    if (message.type == Type.STOP) {
                        stopWrittenAt = lastWrite
                        stopWritten.countDown()
                    }
                }, { detail ->
                    connectionError.compareAndSet(null, "INTERNAL" to detail)
                    if (orderlyStop && running.get() && !socket.isClosed)
                        failOrderlyStop(ProtocolException("PROTOCOL", detail))
                    else socket.close()
                }, controlWrite, ::now)
                val hello = read()
                protocolRequire(hello.type == Type.HELLO, "Expected HELLO")
                val h = hello.json()
                if (h.string("role") == "media") {
                    readMedia(attachMedia(hello))
                    return
                }
                protocolRequire(hello.session == 0L, "Control HELLO requires session zero")
                protocolRequire(h.string("role") == "sender", "Unexpected peer role")
                diagnosticsNegotiated = org.screencopy.receiver.protocol.DiagnosticDelegation.supported(h) &&
                    diagnosticSessionAccess != null
                val versions = h.array("versions")
                protocolRequire(versions.length() in 1..16 && (0 until versions.length()).all { versions.opt(it) is Int }, "Invalid version list")
                protocolRequire((0 until versions.length()).any { versions.opt(it) == Wire.VERSION }, "No compatible protocol version", "VERSION")
                senderName = h.string("name", 80)
                protocolRequire(senderName.isNotEmpty() && senderName.none { it.isISOControl() }, "Invalid sender name")
                protocolRequire(!h.has("videoRotation") || h.opt("videoRotation") is Boolean, "videoRotation must be boolean")
                videoRotation = h.opt("videoRotation") == true
                protocolRequire(!h.has("uncappedFps") || h.opt("uncappedFps") is Boolean, "uncappedFps must be boolean")
                hdrVideoVersion = if (h.has("hdrVideoVersion")) h.integer("hdrVideoVersion") else null
                protocolRequire(hdrVideoVersion == null || checkNotNull(hdrVideoVersion) > 0, "Invalid hdrVideoVersion")
                protocolRequire(!h.has("streamRecovery") || h.opt("streamRecovery") is Boolean, "streamRecovery must be boolean")
                val version = if (h.has("streamRecoveryVersion")) h.integer("streamRecoveryVersion") else null
                protocolRequire(version == null || version > 0, "Invalid stream recovery version")
                streamRecovery = h.opt("streamRecovery") == true && version == StreamRecoveryProtocol.VERSION
                negotiatedOptions = ReceiverOptionProtocol.capabilities(h).intersect(
                    ReceiverOptionCapabilities(supportsPlaybackStatistics))
                controlStatistics.validControl(now())
                val response = JSONObject().put("role", "receiver").put("versions", JSONArray().put(Wire.VERSION)).put("name", name)
                    .put("uncappedFps", true)
                if (videoRotation) response.put("videoRotation", true)
                if (streamRecovery) response.put("streamRecovery", true).put("streamRecoveryVersion", StreamRecoveryProtocol.VERSION)
                if (negotiatedOptions.playbackStats) response.put("receiverOptions", negotiatedOptions.wireValue())
                if (hdrVideoVersion == 1) response.put("receiveCapabilities", receiveCapabilities().json())
                if (diagnosticsNegotiated) response.put("diagnostics",
                    org.screencopy.receiver.protocol.DiagnosticDelegation.capability())
                send(Type.HELLO, response)
                phase = "Negotiating"
                phaseAt = now()
                socket.soTimeout = org.screencopy.receiver.protocol.HdrPreparationDeadline.milliseconds(hdrVideoVersion)
                val negotiation = readControlUntilRequest()
                socket.soTimeout = 10_000
                session = negotiation.session
                protocolRequire(session > 0, "Session ID must be positive")
                if (!active.compareAndSet(null, this)) {
                    send(Type.REJECT, JSONObject().put("code", "BUSY").put("message", "Receiver already has an active sender"))
                    return
                }
                if (acquireSession != null) {
                    sessionLease = acquireSession.invoke()
                    if (sessionLease == null) {
                        send(Type.REJECT, JSONObject().put("code", "BUSY").put("message", "This device is sending or ending another cast"))
                        return
                    }
                }
                onScreenEvent(ReceiverScreenEvent.Connecting)
                request = Request.parse(negotiation.json(), negotiatedOptions, hdrVideoVersion == 1, receiveCapabilities())
                controlStatistics.validControl(now())
                val settings = checkNotNull(request)
                validateRequest(settings)
                requestSummary = settings.summary
                tracks = TrackState(settings.tracks)
                clock.audioExpected = settings.audioCodec != null
                phase = "Initializing tracks"
                phaseAt = now()
                onStatus(phase, "$senderName\n$requestSummary")
                synchronized(mediaLock) { accepted = true }
                send(Type.SESSION_ACCEPT, JSONObject().put("tracks", JSONArray(settings.tracks)).put("mediaToken", mediaToken).apply {
                    settings.videoColor?.let { put("videoColor", it.json()) }
                    if (settings.diagnosticRequested) put("diagnostic",
                        org.screencopy.receiver.protocol.DiagnosticDelegation.result(
                            if (diagnosticsNegotiated) "starting" else "unsupported", session, null))
                })
                diagnosticOwner = true
                RemoteDiagnostics.recorder.sessionStarted("receiver", session)
                try {
                    diagnosticSessionAccess?.admitted(session, settings.diagnosticRequested && diagnosticsNegotiated,
                        { active.get() === this && accepted && !stopping.get() && !socket.isClosed }, ::diagnosticReport)
                } catch (_: Exception) {
                    diagnosticReport("unavailable")
                    onDiscoveryDiagnostic("event=DIAGNOSTIC_START_UNAVAILABLE nonfatal=true")
                }
                if (streamRecovery) socket.soTimeout = 0
                while (running.get() && !socket.isClosed) {
                    val operation = RemoteDiagnostics.recorder.operation("receiver", session, DiagnosticKind.TRANSPORT_HEALTH)
                    RemoteDiagnostics.recorder.observe("receiver", session, DiagnosticKind.TRANSPORT_HEALTH,
                        DiagnosticStage.BEGIN, operation = operation, point = DiagnosticPoint.READ)
                    val message = try { read() } catch (e: Exception) {
                        RemoteDiagnostics.recorder.observe("receiver", session, DiagnosticKind.TRANSPORT_HEALTH,
                            DiagnosticStage.FAILED, operation = operation, reason = DiagnosticReason.FAILURE,
                            point = DiagnosticPoint.READ)
                        throw e
                    }
                    RemoteDiagnostics.recorder.observe("receiver", session, DiagnosticKind.TRANSPORT_HEALTH,
                        DiagnosticStage.COMPLETE, operation = operation, bytes = message.payload.size.toLong(),
                        point = DiagnosticPoint.READ)
                    protocolRequire(message.session == session, "Session ID changed")
                    if (message.type == Type.SESSION_END) {
                        protocolRequire(stopping.get(), "Unexpected SESSION_END")
                        message.json().string("reason", 1024)
                        if (orderlyStop) awaitStopWrite()
                        return
                    }
                    if (message.type == Type.STOP) {
                        diagnosticEnd()
                        if (receiveStop(message.json().string("reason", 1024))) return
                        continue
                    }
                    if (stopping.get() && !(orderlyStop && message.type == Type.ERROR)) continue
                    when (message.type) {
                        Type.STREAM_CONFIG, Type.VIDEO_FRAME, Type.AUDIO_PACKET ->
                            throw ProtocolException("PROTOCOL", "Media and configuration require their dedicated connections")
                        Type.PING -> send(Type.PONG, JSONObject().put("nonce", message.json().integer("nonce")))
                        Type.PONG -> receivedPong(message.json().integer("nonce"))
                        Type.STATUS -> {
                            val json = message.json()
                            json.string("state", 80)
                            json.string("message", 2048)
                            if (json.has("diagnostic")) {
                                protocolRequire(active.get() === this && diagnosticOwner && diagnosticsNegotiated,
                                    "Diagnostic control requires admitted owner")
                                protocolRequire(!org.screencopy.receiver.protocol.DiagnosticDelegation.requested(json),
                                    "Diagnostics cannot be enabled after admission")
                                try { diagnosticSessionAccess?.revoked(session) }
                                catch (_: Exception) { diagnosticReport("unavailable") }
                            }
                        }
                        Type.ERROR -> {
                            val json = message.json()
                            val code = json.string("code", 80)
                            val detail = json.string("message", 2048)
                            protocolRequire(code in ERROR_CODES, "Unknown sender error code")
                            if (json.boolean("fatal")) {
                                reportFailure(code, detail, FailureOrigin.PEER)
                                throw ProtocolException(code, "Sender: $detail")
                            }
                            onStatus("Sender warning: $code", detail)
                        }
                        else -> throw ProtocolException("PROTOCOL", "Message is not allowed in current session state")
                    }
                    controlStatistics.validControl(now())
                }
            } catch (e: Exception) {
                val stoppingOrderly = orderlyStop
                terminalError = true
                val owner = mediaOwner
                if (owner != null) {
                    if (owner.orderlyStop && !socket.isClosed &&
                        !(e is EOFException && readStartedAt == null)) owner.failOrderlyStop(e)
                    else owner.mediaFailed(e)
                } else if (stoppingOrderly && running.get() && !socket.isClosed) {
                    failOrderlyStop(e)
                } else if (running.get() && !stopping.get() && playbackError.get() == null) {
                    val externalError = connectionError.get()
                    val code = externalError?.first ?: when (e) {
                        is ProtocolException -> e.code
                        is PlaybackException -> e.code
                        is java.net.SocketTimeoutException -> "TIMEOUT"
                        is EOFException, is SocketException -> "PROTOCOL"
                        else -> if (phase == "Initializing tracks") "UNSUPPORTED_CONFIG" else "INTERNAL"
                    }
                    val detail = externalError?.second ?: when {
                        e is EOFException -> "Sender disconnected or truncated a message"
                        else -> e.message ?: e.javaClass.simpleName
                    }
                    try {
                        send(if (phase == "Negotiating" && session > 0) Type.REJECT else Type.ERROR,
                            JSONObject().put("code", code).put("message", detail).put("fatal", true))
                    } catch (sendError: Exception) {
                        if (active.get() === this) onStatus("Connection closed", "$detail; could not deliver error (${sendError.javaClass.simpleName})")
                    }
                    if (active.get() === this) {
                        onStatus("Session error: $code", "$detail\nReconnect after correcting the sender settings or network.")
                        reportFailure(code, detail,
                            when {
                                e is ReceiverOrientationException -> FailureOrigin.PRESENTATION
                                phase == "Initializing tracks" && initializingTrack == 2 -> FailureOrigin.AUDIO
                                phase == "Initializing tracks" && initializingTrack == 1 -> FailureOrigin.VIDEO
                                else -> FailureOrigin.SESSION
                            },
                            (e as? QueueCongestionException)?.pressure, (e as? AudioOutputPreparationException)?.diagnostic,
                            (e as? VideoPlaybackException)?.diagnostic)
                    } else {
                        onDiscoveryDiagnostic("event=UNOWNED_CONNECTION_FAILURE code=$code phase=$phase exception=${e.javaClass.simpleName}")
                    }
                }
            } finally {
                diagnosticEnd()
                var cleanupFailed = false
                if (mediaOwner == null) closeMedia()
                recoveryScheduler.shutdown()
                recoveryExecutor.shutdown()
                try {
                    check(recoveryScheduler.awaitTermination(3, TimeUnit.SECONDS)) { "Track recovery workers did not retire" }
                    check(recoveryExecutor.awaitTermination(3, TimeUnit.SECONDS)) { "Recovery worker did not retire" }
                } catch (e: Exception) {
                    cleanupFailed = true
                    onStatus("Recovery cleanup failed", e.message ?: e.javaClass.simpleName)
                }
                try {
                    if (mediaOwner == null) {
                        stopPlayback()
                    }
                } catch (e: Exception) {
                    cleanupFailed = true
                    onStatus("Playback cleanup failed", "${e.message ?: e.javaClass.simpleName}; reopen receiver")
                    reportFailure("INTERNAL", "Playback cleanup failed", FailureOrigin.CLEANUP)
                } finally {
                    try {
                        writer?.close()
                    } catch (e: Exception) {
                        cleanupFailed = true
                        onStatus("Connection cleanup failed", "${e.message ?: e.javaClass.simpleName}; reopen receiver")
                        reportFailure("INTERNAL", "Connection cleanup failed", FailureOrigin.CLEANUP)
                    } finally {
                        try { socket.close() } finally {
                            val released = active.compareAndSet(this, null)
                            if (released) RemoteDiagnostics.recorder.sessionEnded("receiver", session)
                            try { sessionLease?.close() } catch (e: Exception) {
                                cleanupFailed = true
                                onStatus("Session cleanup failed", e.message ?: e.javaClass.simpleName)
                                reportFailure("INTERNAL", "Session ownership cleanup failed", FailureOrigin.CLEANUP)
                            } finally {
                                sessionLease = null
                                if (released && running.get() && !cleanupFailed) available()
                            }
                        }
                    }
                }
            }
        }

        private fun read(): Message = Wire.read(socket.inputStream, { readStartedAt = now() }).also {
            readStartedAt = null
            lastRead = now()
        }

        private fun readControlUntilRequest(): Message {
            while (true) {
                val message = read()
                protocolRequire(!org.screencopy.receiver.protocol.HdrPreparationDeadline.expired(
                    hdrVideoVersion, 0, now() - phaseAt),
                    "Session preparation exceeded its absolute deadline", "TIMEOUT")
                if (message.type == Type.SESSION_REQUEST) return message
                protocolRequire(message.session == 0L, "Unexpected session before negotiation")
                when (message.type) {
                    Type.PING -> send(Type.PONG, JSONObject().put("nonce", message.json().integer("nonce")))
                    Type.PONG -> receivedPong(message.json().integer("nonce"))
                    else -> throw ProtocolException("PROTOCOL", "Expected SESSION_REQUEST")
                }
                controlStatistics.validControl(now())
            }
        }

        private fun configure(message: Message) {
            if (streamRecovery && message.generation > 1) {
                configureRecovery(message)
                return
            }
            synchronized(trackPlaybackOperations[message.track]) {
                configureInitial(message)
            }
        }

        private fun configureInitial(message: Message) {
            val snapshot = synchronized(playbackLock) {
                if (stopping.get()) return
                protocolRequire(accepted && active.get() === this, "Session has ended")
                if (message.track == 1 && videoOutput.isClosed) null else checkNotNull(request) to clock
            }
            if (snapshot == null) {
                onDiscoveryDiagnostic("event=RETIRED_TRACK_CONFIGURATION track=${message.track} generation=${message.generation}")
                return
            }
            val (settings, shared) = snapshot
            val json = message.json()
            val videoFormat = if (message.track == 1) validateHdrOutput(Configuration.video(json, settings)) else null
            val audioFormat = if (message.track == 2) Configuration.audio(json, settings) else null
            var startPresentation = false
            val retired = synchronized(playbackLock) {
                if (stopping.get()) return
                protocolRequire(accepted && active.get() === this && request === settings, "Session has ended")
                if (message.track == 1 && videoOutput.isClosed) {
                    return@synchronized null
                }
                resetStatistics()
                if (message.track == 1) lastVideoPacketAt = null else lastAudioPacketAt = null
                phase = "Initializing tracks"
                initializingTrack = message.track
                phaseAt = now()
                val codec = if (message.track == 2) settings.audioCodec else settings.videoCodec
                checkNotNull(tracks).configure(message.track, message.generation, codec?.takeIf { json.opt("codec") == it })
                startPresentation = message.track == 1 && presentation == null
                if (message.track == 1) {
                    if (message.generation > 1) shared.endVideoPreroll()
                    retiredVideoThrough = maxOf(retiredVideoThrough, videoGeneration)
                    videoGeneration = message.generation
                    (video to null).also { video = null }
                } else {
                    retiredAudioThrough = maxOf(retiredAudioThrough, audioEpoch)
                    audioGeneration = message.generation
                    (null to audio).also { audio = null }
                }
            }
            if (retired == null) {
                onDiscoveryDiagnostic("event=RETIRED_TRACK_CONFIGURATION track=${message.track} generation=${message.generation}")
                return
            }
            try { retired.first?.let { videoOutput.release(it) } } finally { retired.second?.close() }
            fun current() = running.get() && accepted && !stopping.get() && active.get() === this &&
                (message.track != 1 || !videoOutput.isClosed) &&
                (if (message.track == 1) videoGeneration else audioGeneration) == message.generation
            var createdVideo: VideoPlayer? = null
            var createdAudio: AudioPlayer? = null
            var createdPresentation: AutoCloseable? = null
            var installed = false
            var playing = false
            try {
                if (!current()) return
                if (videoFormat != null) {
                    createdVideo = createVideoPlayer(videoFormat, shared, message.generation) ?: return
                    if (current()) onVideoSize(videoFormat.width, videoFormat.height)
                } else createdAudio = createAudioPlayer(checkNotNull(audioFormat), shared)
                if (!current()) return
                if (startPresentation) createdPresentation = onPresentationStart(settings.receiverOrientation)
                val audioDescription = createdAudio?.decoderDescription
                fun installIfCurrent() {
                    synchronized(playbackLock) {
                        if (!current()) return@synchronized
                        if (videoFormat != null) {
                            video = createdVideo
                            activeVideoFormat = videoFormat
                            actualVideo = "Actual video: ${videoFormat.mime} ${videoFormat.width}x${videoFormat.height}, generation ${message.generation}"
                        } else {
                            audio = createdAudio
                            recoveryFormat = audioFormat
                            audioPacketTiming = AudioPacketTiming.from(checkNotNull(audioFormat))
                            actualAudio = "Actual audio: ${audioFormat.codec} ${audioFormat.sampleRate} Hz ${audioFormat.channels}ch, pre-skip ${audioFormat.preSkip}, generation ${message.generation}\n${checkNotNull(audioDescription)}"
                        }
                        if (createdPresentation != null) presentation = createdPresentation
                        installed = true
                        val trackState = checkNotNull(tracks)
                        trackState.ready(message.track)
                        if (trackState.allReady()) {
                            tracksReady = true
                            if (streamRecovery) {
                                videoRecovering = true
                                awaitingRecoveryKey = true
                                shared.recoveryStartup = true
                                if (videoRecoveryAt == 0L) videoRecoveryAt = now()
                                phase = "Recovering"
                            } else phase = "Playing"
                            playing = true
                        }
                        send(Type.STREAM_READY, JSONObject().put("ok", true), message.track, message.generation)
                    }
                }
                if (message.track == 1) videoOutput.whileOpen { installIfCurrent() }
                else installIfCurrent()
                if (playing && current())
                    onStatus("Playing: $senderName", "$requestSummary\n$actualVideo\n$decoderDescription\n$actualAudio\nSurface ready; bounded synchronized playback active.")
            } finally {
                if (!installed) {
                    onDiscoveryDiagnostic("event=RETIRED_TRACK_CONFIGURATION track=${message.track} generation=${message.generation}")
                    try { createdVideo?.let { videoOutput.release(it) } } finally {
                        try { createdAudio?.close() } finally { createdPresentation?.close() }
                    }
                }
            }
        }

        private inline fun <T> diagnosticRecoveryCall(track: Int, generation: Long, point: DiagnosticPoint, call: () -> T): T {
            val diagnostics = RemoteDiagnostics.recorder
            if (!diagnostics.enabled("receiver", session, DiagnosticKind.RECOVERY)) return call()
            val operation = diagnostics.operation("receiver", session, DiagnosticKind.RECOVERY)
            diagnostics.observe("receiver", session, DiagnosticKind.RECOVERY, DiagnosticStage.BEGIN,
                track, generation.toInt(), operation = operation, point = point)
            try {
                val result = call()
                diagnostics.observe("receiver", session, DiagnosticKind.RECOVERY, DiagnosticStage.COMPLETE,
                    track, generation.toInt(), operation = operation, point = point)
                return result
            } catch (e: Exception) {
                diagnostics.observe("receiver", session, DiagnosticKind.RECOVERY, DiagnosticStage.FAILED,
                    track, generation.toInt(), operation = operation, point = point, reason = DiagnosticReason.FAILURE)
                throw e
            }
        }

        private fun configureRecovery(message: Message) {
            val track = message.track
            if (track == 1) lastVideoPacketAt = null else lastAudioPacketAt = null
            val settings = synchronized(playbackLock) {
                if (stopping.get()) return
                protocolRequire(accepted && active.get() === this && tracksReady, "Recovery preceded initialized tracks")
                checkNotNull(request)
            }
            val json = message.json()
            val videoFormat = if (track == 1) validateHdrOutput(Configuration.video(json, settings)) else null
            val audioFormat = if (track == 2) Configuration.audio(json, settings) else null
            synchronized(playbackLock) {
                if (stopping.get()) return
                protocolRequire(accepted && active.get() === this && request === settings && tracksReady, "Recovery preceded initialized tracks")
                protocolRequire(message.generation <= Int.MAX_VALUE, "Recovery track generation exhausted")
                if (track == 2) {
                    val original = checkNotNull(recoveryFormat)
                    val proposed = checkNotNull(audioFormat)
                    protocolRequire(proposed.copy(configuration = original.configuration) == original &&
                        proposed.configuration.contentEquals(original.configuration),
                        "Audio format changes require a new capture session", "UNSUPPORTED_CONFIG")
                    audioRecoveryCutoff = StreamRecoveryProtocol.timestamp(json, "recoveryPtsUs")
                }
                checkNotNull(tracks).configure(track, message.generation, if (track == 1) settings.videoCodec else settings.audioCodec)
                if (track == 1) {
                    videoRecovering = true
                    awaitingRecoveryKey = false
                    recoveryKeyPts = null
                    pendingVideoProof = null
                    if (videoRecoveryAt == 0L) videoRecoveryAt = now()
                    retiredVideoThrough = maxOf(retiredVideoThrough, videoGeneration)
                    videoGeneration = message.generation
                } else {
                    clock.beginAudioRecovery()
                    audioRecovering = true
                    audioRecoveryAdmitted = false
                    if (audioRecoveryAt == 0L) audioRecoveryAt = now()
                    retiredAudioThrough = audioEpoch
                    retiredAudioGeneration = audioGeneration
                    audioGeneration = message.generation
                }
                phase = "Recovering"
            }
            fun current() = accepted && !stopping.get() && active.get() === this &&
                (track != 1 || !videoOutput.isClosed) &&
                (if (track == 1) videoGeneration else audioGeneration) == message.generation
            recoveryScheduler.schedule(track, ::current) {
                try {
                    synchronized(trackPlaybackOperations[track]) operations@ {
                        if (!current()) return@operations
                        val retired: Pair<VideoPlayer?, AudioPlayer?> = synchronized(playbackLock) {
                            if (track == 1) (video to null).also { video = null }
                            else (null to audio).also { audio = null }
                        }
                        diagnosticRecoveryCall(track, message.generation, DiagnosticPoint.RECOVERY_RETIRE) {
                            try { retired.first?.let { videoOutput.release(it) } } finally { retired.second?.close() }
                        }
                        if (!current()) return@operations
                        val sameRaster = videoFormat?.let { format ->
                            activeVideoFormat?.let { it.width == format.width && it.height == format.height } == true
                        } ?: true
                        var createdVideo: VideoPlayer? = null
                        var createdAudio: AudioPlayer? = null
                        var installed = false
                        try {
                            if (track == 1) {
                                createdVideo = diagnosticRecoveryCall(track, message.generation, DiagnosticPoint.RECOVERY_CONSTRUCT) {
                                    createVideoPlayer(checkNotNull(videoFormat), clock, message.generation)
                                }
                                if (createdVideo == null) {
                                    onDiscoveryDiagnostic("event=RETIRED_TRACK_CONFIGURATION track=$track generation=${message.generation}")
                                    return@operations
                                }
                            }
                            else createdAudio = diagnosticRecoveryCall(track, message.generation, DiagnosticPoint.RECOVERY_CONSTRUCT) {
                                createAudioPlayer(checkNotNull(audioFormat).copy(preSkip = 0), clock, audioRecoveryCutoff)
                            }
                            if (!sameRaster && current()) onVideoSize(checkNotNull(videoFormat).width, videoFormat.height)
                            val audioDescription = createdAudio?.decoderDescription
                            fun installIfCurrent() {
                                synchronized(playbackLock) {
                                    if (!current()) return@synchronized
                                    if (track == 1) {
                                        video = createdVideo
                                        activeVideoFormat = videoFormat
                                        actualVideo = "Actual video: ${checkNotNull(videoFormat).mime} ${videoFormat.width}x${videoFormat.height}, generation ${message.generation}"
                                        framePresented.set(false)
                                        awaitingRecoveryKey = true
                                    } else {
                                        audio = createdAudio
                                        actualAudio = "Audio playback resynchronized; ${checkNotNull(audioDescription)}"
                                    }
                                    checkNotNull(tracks).ready(track)
                                    installed = true
                                    send(Type.STREAM_READY, JSONObject().put("ok", true), track, message.generation)
                                }
                            }
                            if (track == 1) videoOutput.whileOpen { installIfCurrent() }
                            else installIfCurrent()
                            if (!installed) {
                                createdVideo?.let { videoOutput.release(it) }
                                createdAudio?.close()
                                return@operations
                            }
                            if (current()) {
                                onDiscoveryDiagnostic("event=STREAM_RECOVERY_READY track=$track generation=${message.generation}")
                            }
                        } catch (e: Exception) {
                            if (!installed) {
                                try { createdVideo?.let { videoOutput.release(it) } } finally { createdAudio?.close() }
                            }
                            throw e
                        }
                    }
                } catch (e: Exception) {
                    if (accepted && !stopping.get() &&
                        (if (track == 1) videoGeneration else audioGeneration) == message.generation && active.get() === this)
                        playbackFailed(if (e is PlaybackException) e.code else if (track == 1) "DECODER_FAILED" else "AUDIO_FAILED",
                            "Track $track recovery failed: ${e.message ?: e.javaClass.simpleName}",
                            if (track == 1) FailureOrigin.VIDEO else FailureOrigin.AUDIO)
                    else if (e is InterruptedException || accepted && !stopping.get())
                        onDiscoveryDiagnostic("event=STREAM_RECOVERY_RETIRED exception=${e.javaClass.simpleName}")
                    else {
                        onStatus("Recovery cleanup failed", e.message ?: e.javaClass.simpleName)
                        reportFailure("INTERNAL", "Recovery resource retirement failed", FailureOrigin.CLEANUP)
                    }
                }
            }
        }

        private fun createVideoPlayer(format: VideoFormat, shared: PlaybackClock, generation: Long): VideoPlayer? {
            onDiscoveryDiagnostic("event=VIDEO_CONFIGURATION_ADMISSION generation=$generation")
            return videoOutput.replaceIfOpen {
                val failed: (String, String) -> Unit = { code, detail ->
                    if (accepted && !stopping.get() && generation == videoGeneration && generation > retiredVideoThrough)
                        playbackFailed(code, detail, FailureOrigin.VIDEO)
                    else onDiscoveryDiagnostic("event=RETIRED_VIDEO_FAILURE generation=$generation code=$code")
                }
                (videoFactory?.invoke(format, shared, failed, generation) ?: VideoPlayer(format,
                    videoSurface?.invoke(format) ?: checkNotNull(surface), shared,
                    { diagnostic ->
                        decoderDescription = diagnostic
                        onStatus("Initializing video", "$senderName\n$requestSummary\nActual: ${format.width}x${format.height}; $diagnostic")
                    }, failed, {
                        if (running.get() && !stopping.get() && !videoRecovering && tracksReady && active.get() === this && videoGeneration == generation &&
                            framePresented.compareAndSet(false, true)) {
                            onScreenEvent(ReceiverScreenEvent.FramePresented)
                            onFramePresented { running.get() && active.get() === this && !stopping.get() && videoGeneration == generation }
                        }
                    }, generation, outputAvailable = {
                        val caps = receiveCapabilities()
                        caps.nativeHdrAvailable == true || format.toneMapToSdr && caps.hdrToSdr
                    }, onRotation = { rotation ->
                        val current = { running.get() && !videoRecovering && accepted && !stopping.get() && active.get() === this && videoGeneration == generation }
                        if (current()) {
                            VideoPresentation.requireSupportedRotation(format.videoColor != null, rotation)
                            actualRotation = if (videoRotation) rotation else 0
                            if (videoRotation) onVideoRotation(rotation, current)
                        }
                    })).also {
                    it.diagnosticSession = session
                    it.diagnosticGeneration = generation.toInt()
                    it.statisticsEnabled = request?.receiverOptions?.playbackStats == true
                    it.dropStaleOutput = streamRecovery
                    it.onDecoded = { pts -> proveVideoKey(generation, pts) }
                    it.onPlaybackStep = { retryVideoKey(generation) }
                    it.start()
                }
            }
        }

        private fun createAudioPlayer(format: AudioFormat, shared: PlaybackClock, cutoff: Long? = null): AudioPlayer {
            val epoch = ++audioEpoch
            val failed: (String, String) -> Unit = { code, detail ->
                if (accepted && !stopping.get() && epoch == audioEpoch && epoch > retiredAudioThrough)
                    playbackFailed(code, detail, FailureOrigin.AUDIO)
                else onDiscoveryDiagnostic("event=RETIRED_AUDIO_FAILURE epoch=$epoch code=$code")
            }
            return (audioFactory?.invoke(format, shared, failed) ?: AudioPlayer(format, shared, failed,
                onClockCorrection = { android.util.Log.i("ScreenCopyReceiver", it.logMessage()) },
                onOutputTiming = { android.util.Log.i("ScreenCopyReceiver", "event=AUDIO_OUTPUT_CLOCK stage=PCM_COMPENSATION ${it.logMessage()}") },
                onUnderrunRecovery = { android.util.Log.w("ScreenCopyReceiver", it.logMessage()) },
                onRegulation = { android.util.Log.i("ScreenCopyReceiver", it.logMessage()) })).also {
                it.diagnosticSession = session
                it.diagnosticGeneration = audioGeneration.toInt()
                it.statisticsEnabled = request?.receiverOptions?.playbackStats == true
                it.recoveryCutoffUs = cutoff
                if (RemoteDiagnostics.recorder.enabled("receiver", session, DiagnosticKind.SESSION))
                    RemoteDiagnostics.recorder.observe("receiver", session, DiagnosticKind.SESSION,
                        DiagnosticStage.CONFIG, 2, audioGeneration.toInt(),
                        metadata = DiagnosticConfiguration(DiagnosticCodec.from(format.codec),
                            format.sampleRate, format.channels, audioPacketTiming?.prerollUs))
                RemoteDiagnostics.recorder.observe("receiver", session, DiagnosticKind.RECOVERY,
                    DiagnosticStage.CONFIG, 2, audioGeneration.toInt(), cutoff = cutoff ?: -1,
                    count = format.preSkip.toLong())
                it.onFirstSubmitted = { pts -> completeAudio(epoch, pts) }
                it.start()
            }
        }

        private fun proveVideoKey(generation: Long, decodedPts: Long) {
            if (!streamRecovery || recoveryKeyPts == null) return
            val completed = synchronized(playbackLock) {
                val key = recoveryKeyPts ?: return@synchronized null
                if (!accepted || stopping.get() || generation != videoGeneration ||
                    generation <= retiredVideoThrough || decodedPts < key || keyProvedGeneration == generation) return@synchronized null
                val candidate = VideoProofCandidate(generation, decodedPts, clock.cachedPlayedAudioObservation())
                pendingVideoProof = candidate
                tryVideoProof(candidate)
            }
            completed?.let { reportRecoveryComplete(1, it) }
        }

        private fun retryVideoKey(generation: Long) {
            val candidate = pendingVideoProof ?: return
            if (candidate.generation != generation) return
            val completed = synchronized(playbackLock) {
                if (pendingVideoProof !== candidate) return@synchronized null
                tryVideoProof(candidate)
            }
            completed?.let { reportRecoveryComplete(1, it) }
        }

        private fun tryVideoProof(candidate: VideoProofCandidate): Long? {
            val key = recoveryKeyPts ?: return null
            val generation = candidate.generation
            if (!accepted || stopping.get() || generation != videoGeneration ||
                generation <= retiredVideoThrough || candidate.ptsUs < key || keyProvedGeneration == generation) return null
            if (!clock.canProveVideo(candidate.ptsUs, candidate.audioObservation)) return null
            send(Type.STATUS, StreamRecoveryProtocol.status(StreamRecoveryProtocol.KEY, 1, generation, key))
            keyProvedGeneration = generation
            pendingVideoProof = null
            return completeRecovery(1, key)
        }

        private fun completeAudio(epoch: Long, pts: Long) {
            val completed = synchronized(playbackLock) {
                if (!streamRecovery || !accepted || stopping.get() || !audioRecovering || epoch != audioEpoch ||
                    epoch <= retiredAudioThrough || pts < audioRecoveryCutoff) return@synchronized null
                completeRecovery(2, pts)
            }
            completed?.let { reportRecoveryComplete(2, it) }
        }

        private fun completeRecovery(track: Int, pts: Long): Long? {
            val generation = if (track == 1) videoGeneration else audioGeneration
            if (track == 1 && completedGeneration == generation || track == 2 && completedAudioEpoch == audioEpoch) return null
            send(Type.STATUS, StreamRecoveryProtocol.status(StreamRecoveryProtocol.COMPLETE, track, generation, pts))
            if (track == 1) {
                completedGeneration = generation
                videoRecoveryAt = 0
                clock.recoveryStartup = false
            } else {
                completedAudioEpoch = audioEpoch
                audioRecoveryAt = 0
                audioRecovering = false
                clock.endAudioRecovery()
            }
            phase = if (recovering) "Recovering" else "Playing"
            return generation
        }

        private fun reportRecoveryComplete(track: Int, generation: Long) {
            onDiscoveryDiagnostic("event=STREAM_RECOVERY_COMPLETE track=$track generation=$generation")
            RemoteDiagnostics.recorder.observe("receiver", session, DiagnosticKind.RECOVERY, DiagnosticStage.COMPLETE,
                track, generation.toInt())
        }

        private fun send(type: Type, json: JSONObject, track: Int = 0, generation: Long = 0) {
            synchronized(playbackLock) {
                if (stopping.get() && type in setOf(Type.STATUS, Type.STREAM_READY)) return
                checkNotNull(writer) { "TCP writer is not available" }.offer(Message.json(type, json, session, track, generation))
                if (type == Type.STREAM_READY) RemoteDiagnostics.recorder.observe("receiver", session,
                    DiagnosticKind.RECOVERY, DiagnosticStage.READY, track, generation.toInt(),
                    cutoff = if (track == 2) audioRecoveryCutoff else -1)
            }
        }

        private fun reportFailure(code: String, detail: String, origin: FailureOrigin, pressure: QueuePressure? = null,
            outputPreparation: OutputPreparationDiagnostic? = null, videoDiagnostic: VideoDiagnostic? = null) {
            if (active.get() === this) playbackStatistics = null
            val diagnostic = FailureDiagnostic.classify(code, origin, phase, detail, pressure, outputPreparation, videoDiagnostic)
            if (!firstFailure.compareAndSet(null, diagnostic)) return
            RemoteDiagnostics.recorder.observe("receiver", session, DiagnosticKind.SESSION, DiagnosticStage.FAILED,
                track = when (origin) { FailureOrigin.AUDIO -> 2; FailureOrigin.VIDEO -> 1; else -> 0 },
                reason = DiagnosticReason.FAILURE)
            onFailure(diagnostic)
            onScreenEvent(ReceiverScreenEvent.Problem(code, diagnostic.reason, diagnostic.pressure))
        }

        private fun playbackFailed(code: String, reason: String, origin: FailureOrigin) {
            synchronized(playbackLock) {
                if (stopping.get() || !playbackError.compareAndSet(null, reason)) return
                terminalError = true
                stopAt = now()
                stopping.set(true)
            }
            onStatus("Playback failed", "$reason\nReconnect after reducing sender load or fixing the output device.")
            if (active.get() === this) reportFailure(code, reason, if (code == "AUDIO_FAILED") FailureOrigin.AUDIO else origin)
            try {
                send(Type.ERROR, JSONObject().put("code", code).put("message", reason).put("fatal", true))
            } catch (e: Exception) {
                onStatus("Playback failed", "$reason; could not notify sender (${e.javaClass.simpleName})")
            } finally {
                retireFailedSession()
            }
        }

        private fun retireFailedSession() {
            closeMedia()
            if (!failureRetirement.compareAndSet(false, true)) return
            try {
                recoveryExecutor.execute {
                    try {
                        stopPlayback()
                    } catch (error: Exception) {
                        onStatus("Playback cleanup failed", error.message ?: error.javaClass.simpleName)
                        reportFailure("INTERNAL", "Playback cleanup failed", FailureOrigin.CLEANUP)
                    } finally {
                        try {
                            writer?.close()
                        } catch (error: Exception) {
                            onStatus("Connection cleanup failed", error.message ?: error.javaClass.simpleName)
                            reportFailure("INTERNAL", "Connection cleanup failed", FailureOrigin.CLEANUP)
                        } finally { socket.close() }
                    }
                }
            } catch (error: java.util.concurrent.RejectedExecutionException) {
                onStatus("Session cleanup failed", "Playback retirement worker is unavailable")
                reportFailure("INTERNAL", "Playback retirement worker is unavailable", FailureOrigin.CLEANUP)
                socket.close()
            }
        }

        fun requestStop(reason: String) {
            if (active.get() === this) playbackStatistics = null
            synchronized(playbackLock) {
                if (stopping.get() || terminalError) return
                receiverStopRequested = true
                stopAt = now()
                if (!stopping.compareAndSet(false, true)) {
                    receiverStopRequested = false
                    return
                }
            }
            try {
                if (orderlyStop) socket.soTimeout = 0
                send(Type.STOP, JSONObject().put("reason", reason))
            } catch (e: Exception) {
                onStatus("Disconnect error", "${e.message ?: e.javaClass.simpleName}; connection closed")
                abort()
            } finally {
                if (!orderlyStop) closeMedia()
                try {
                    stopPlayback()
                } catch (e: Exception) {
                    onStatus("Playback cleanup failed", "${e.message ?: e.javaClass.simpleName}; reopen receiver")
                    socket.close()
                }
            }

        }

        private fun receiveStop(reason: String): Boolean {
            val crossed = synchronized(playbackLock) {
                if (orderlyStop) true else {
                    if (stopping.get()) return false
                    stopAt = now()
                    stopping.set(true)
                    false
                }
            }
            if (crossed) awaitStopWrite() else {
                closeMedia()
                stopPlayback()
                send(Type.SESSION_END, JSONObject().put("reason", reason))
            }
            return true
        }

        private fun failOrderlyStop(error: Exception) {
            val code = when (error) {
                is ProtocolException -> error.code
                is java.net.SocketTimeoutException -> "TIMEOUT"
                is IOException -> "PROTOCOL"
                else -> "INTERNAL"
            }
            val detail = "Orderly Stop failed: ${error.message ?: error.javaClass.simpleName}"
            connectionError.compareAndSet(null, code to detail)
            terminalError = true
            if (active.get() === this) {
                reportFailure(code, detail, FailureOrigin.SESSION)
                onStatus("Session error: $code", "$detail; reconnect")
            } else {
                onDiscoveryDiagnostic("event=UNOWNED_CONNECTION_FAILURE code=$code phase=$phase exception=${error.javaClass.simpleName}")
            }
            abort()
        }

        private fun awaitStopWrite() {
            protocolRequire(stopWritten.await(22, TimeUnit.SECONDS) && stopWrittenAt != null,
                "Receiver STOP did not finish before terminal acknowledgement", "TIMEOUT")
        }

        private fun stopPlayback() {
            val installed = synchronized(playbackLock) { video to audio }
            try { installed.first?.halt() } finally { installed.second?.halt() }
            synchronized(trackPlaybackOperations[1]) {
                synchronized(trackPlaybackOperations[2]) { retirePlayers() }
            }
        }

        private fun retirePlayers() {
            playbackStatistics = null
            resetStatistics()
            val retired = synchronized(playbackLock) {
                tracksReady = false
                val values = Triple(video, audio, presentation)
                video = null
                audio = null
                presentation = null
                values
            }
            val (oldVideo, oldAudio, oldPresentation) = retired
            try {
                try { oldVideo?.halt() } finally { oldAudio?.halt() }
            } finally {
                try {
                    oldVideo?.let {
                        diagnosticRecoveryCall(1, videoGeneration, DiagnosticPoint.RECOVERY_RETIRE) { videoOutput.release(it) }
                    }
                } finally {
                    try {
                        oldAudio?.let {
                            diagnosticRecoveryCall(2, audioGeneration, DiagnosticPoint.RECOVERY_RETIRE) { it.close() }
                        }
                    } finally {
                        oldPresentation?.close()
                    }
                }
            }
        }

        fun tick() {
            val time = now()
            val progressTimeout = if (phase == "Negotiating")
                org.screencopy.receiver.protocol.HdrPreparationDeadline.milliseconds(hdrVideoVersion) else 10_000
            val progressExpired = org.screencopy.receiver.protocol.HdrPreparationDeadline.expired(
                if (phase == "Negotiating") hdrVideoVersion else null, time - lastRead,
                if (phase == "Playing" || phase == "Recovering" || stopping.get()) 0 else time - phaseAt)
            if (mediaOwner != null) {
                if (readStartedAt?.let { time - it >= 10_000 } == true) {
                    val error = ProtocolException("TIMEOUT", "Media frame read blocked for 10 seconds")
                    val owner = checkNotNull(mediaOwner)
                    if (owner.orderlyStop) owner.failOrderlyStop(error) else owner.mediaFailed(error)
                    socket.close()
                } else if (writer?.stalled(time) == true) {
                    mediaOwner?.mediaFailed(ProtocolException("TIMEOUT", "Media HELLO write blocked"))
                    socket.close()
                }
                return
            }
            if (orderlyStop) {
                when {
                    writer?.stalled(time) == true ->
                        failOrderlyStop(ProtocolException("TIMEOUT", "Control write blocked for 10 seconds"))
                    readStartedAt?.let { time - it >= 10_000 } == true ->
                        failOrderlyStop(ProtocolException("TIMEOUT", "Control frame read blocked for 10 seconds"))
                    stopWrittenAt == null && time - stopAt >= 22_000 ->
                        failOrderlyStop(ProtocolException("TIMEOUT", "Receiver STOP was not written within 22 seconds"))
                    stopWrittenAt?.let { time - it >= 22_000 } == true ->
                        failOrderlyStop(ProtocolException("TIMEOUT", "SESSION_END did not arrive within 22 seconds of STOP write"))
                }
                return
            }
            val disconnected = mediaDisconnect.get()
            if (!stopping.get() && disconnected != null && time - disconnected.second >= 500) {
                terminateMediaFailure(disconnected.first)
                return
            }
            when {
                !stopping.get() && streamRecovery && (videoRecoveryAt > 0 && time - videoRecoveryAt >= 10_000 ||
                    audioRecoveryAt > 0 && time - audioRecoveryAt >= 10_000) -> {
                    val track = if (videoRecoveryAt > 0 && time - videoRecoveryAt >= 10_000) 1 else 2
                    connectionError.compareAndSet(null, "TIMEOUT" to "Track $track recovery produced no usable fresh media within 10 seconds")
                    socket.close()
                }
                stopping.get() && time - stopAt >= 2000 -> socket.close()
                progressExpired -> {
                    connectionError.compareAndSet(null, "TIMEOUT" to "No progress from sender within ${progressTimeout / 1000} seconds")
                    if (active.get() === this) onStatus("Session timed out", "No progress from sender within ${progressTimeout / 1000} seconds. Check its network and retry.")
                    socket.close()
                }
                writer?.stalled(time) == true -> {
                    connectionError.compareAndSet(null, "TIMEOUT" to "Control write blocked for 10 seconds")
                    if (active.get() === this) onStatus("Connection stalled", "Control write blocked for 10 seconds; reconnect")
                    socket.close()
                }
                writer != null && phase != "Setup" && time - lastWrite >= 2000 -> {
                    val nonce = (time % Int.MAX_VALUE).toInt()
                    controlStatistics.sentPing(nonce, time)
                    send(Type.PING, JSONObject().put("nonce", nonce))
                }
            }
        }

        fun abort() {
            if (active.get() === this) playbackStatistics = null
            receiverStopRequested = false
            stopping.set(true)
            stopWritten.countDown()
            closeMedia()
            socket.close()
        }

        private fun receivedPong(nonce: Int) {
            controlStatistics.pong(nonce, now())
        }

        fun sampleStatistics(time: Long) {
            if (active.get() !== this) return
            val settings = request
            if (!statisticsVisible.get() || settings?.receiverOptions?.playbackStats != true ||
                stopping.get() || !accepted || terminalError || firstFailure.get() != null) {
                playbackStatistics = null
                resetStatistics()
                return
            }
            val players = video to audio
            val format = activeVideoFormat
            val epochs = videoGeneration to audioEpoch
            val control = controlStatistics.snapshot(time)
            val videoPath = players.first?.videoPathStatistics()
            val audioObservation = players.second?.freshAudioStatistics(time)
            val snapshot = statisticsSampler.sample(time, PlaybackStatisticsInput(
                session, epochs.first, epochs.second, when {
                    recovering -> "Recovering"
                    !tracksReady -> "Preparing"
                    settings.audioCodec != null && audioObservation == null -> "$phase · audio unavailable/stale"
                    else -> phase
                },
                settings.videoCodec, settings.audioCodec, format?.width, format?.height, settings.fps,
                settings.videoBitrate, settings.audioBitrate, actualRotation, settings.receiverOrientation.wireValue,
                receivedVideo.get(), receivedAudio.get(), videoPayload.get(), audioPayload.get(),
                players.first?.videoStatistics(), audioObservation,
                players.first?.queueStatistics(), players.second?.queueStatistics(), control.rttMs,
                control.lastValidControlAgeMs,
                lastVideoPacketAt?.let { (time - it).coerceAtLeast(0) },
                lastAudioPacketAt?.let { (time - it).coerceAtLeast(0) },
                decodedVideo = videoPath?.video, presentation = videoPath?.presentation))
            if (active.get() === this && video === players.first && audio === players.second &&
                videoGeneration == epochs.first && audioEpoch == epochs.second && statisticsRequested() && statisticsVisible.get())
                playbackStatistics = snapshot
        }
    }

    companion object {
        const val PORT = 0
        const val DISCOVERY_PORT = 27183
        const val DEFAULT_NAME = "RedrainCast"
        private val ERROR_CODES = setOf("VERSION", "BUSY", "UNSUPPORTED_CONFIG", "PROTOCOL",
            "CAPTURE_FAILED", "AUDIO_FAILED", "DECODER_FAILED", "TIMEOUT", "CONGESTION", "INTERNAL")
        internal fun forProtocolTests(name: String, onStatus: (String, String) -> Unit = { _, _ -> },
            onListener: (String) -> Unit = {}, validateRequest: (Request) -> Unit = {},
            onScreenEvent: (ReceiverScreenEvent) -> Unit = {}, port: Int = 0,
            onFailure: (FailureDiagnostic) -> Unit = {},
            onPresentationStart: (ReceiverOrientation) -> AutoCloseable = { AutoCloseable {} },
            videoFactory: ((VideoFormat, PlaybackClock, (String, String) -> Unit, Long) -> VideoPlayer)? = null,
            audioFactory: ((AudioFormat, PlaybackClock, (String, String) -> Unit) -> AudioPlayer)? = null,
            clockFactory: () -> PlaybackClock = ::PlaybackClock,
            onVideoSize: (Int, Int) -> Unit = { _, _ -> },
            onVideoRotation: (Int, () -> Boolean) -> Unit = { _, _ -> },
            acquireSession: (() -> AutoCloseable?)? = null,
            instanceId: String = UUID.randomUUID().toString().replace("-", ""),
            supportsPlaybackStatistics: Boolean = false,
            receiveCapabilities: () -> org.screencopy.receiver.protocol.ReceiveCapabilities = {
                org.screencopy.receiver.protocol.ReceiveCapabilities()
            },
            timeMilliseconds: () -> Long = { System.nanoTime() / 1_000_000 },
            controlWrite: (Socket, Message) -> Unit = { target, message -> Wire.write(target.outputStream, message) },
            videoOutput: ResourceSlot<VideoPlayer> = ResourceSlot(),
            onDiscoveryDiagnostic: (String) -> Unit = {},
            diagnosticDiscovery: (InetAddress) -> DiagnosticDiscovery? = { null },
            diagnosticSessionAccess: org.screencopy.receiver.protocol.DiagnosticSessionAccess? = null) =
            ReceiverServer(name, null, onStatus, onVideoSize, onListener, port, videoOutput, validateRequest, onScreenEvent,
                onFailure, onPresentationStart, videoFactory, audioFactory, clockFactory, onVideoRotation = onVideoRotation,
                acquireSession = acquireSession, instanceId = instanceId, supportsPlaybackStatistics = supportsPlaybackStatistics,
                receiveCapabilities = receiveCapabilities, timeMilliseconds = timeMilliseconds, controlWrite = controlWrite,
                onDiscoveryDiagnostic = onDiscoveryDiagnostic, diagnosticDiscovery = diagnosticDiscovery,
                diagnosticSessionAccess = diagnosticSessionAccess)

        private fun validateCapabilities(settings: Request) {
            val types = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }.flatMap { it.supportedTypes.toList() }
            protocolRequire(types.any { it.equals(Configuration.mime(settings.videoCodec), true) }, "No decoder for selected video codec", "UNSUPPORTED_CONFIG")
            if (settings.audioCodec == "aac") protocolRequire(types.any { it.equals("audio/mp4a-latm", true) }, "No AAC decoder", "UNSUPPORTED_CONFIG")
            if (settings.audioCodec == "opus") {
                try {
                    org.screencopy.receiver.media.OpusDecoder(2).use { }
                } catch (_: LinkageError) {
                    throw ProtocolException("UNSUPPORTED_CONFIG", "Bundled Opus decoder could not load for this device ABI")
                }
            }
        }
        private fun interfaces(): List<NetworkInterface> =
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { it.isUp && !it.isLoopback }
    }
}

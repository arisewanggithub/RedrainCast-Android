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
    // Legacy delegation injection is reachable only through forProtocolTests, never the app constructor.
    private val diagnosticDiscovery: (InetAddress) -> DiagnosticDiscovery? = { null },
    private val diagnosticSessionAccess: org.screencopy.receiver.protocol.DiagnosticSessionAccess? = null,
    private val proxyRecorder: DiagnosticRecorder = RemoteDiagnostics.recorder,
    private val proxyLaneReceived: (Long) -> Unit = {},
    private val proxyBeforeRead: () -> Unit = {},
    private val proxyBeforeReady: () -> Unit = {},
    private val proxyBeforeFetch: () -> Unit = {},
    private val proxyAfterRetirement: () -> Unit = {},
    private val proxyBeforeRetirementFlush: () -> Unit = {},
    private val proxyAfterOrdinaryCleanupFence: () -> Unit = {},
    private val incidentSave: () -> ((String, JSONObject) -> Unit) = { { _, _ -> } }
) : AutoCloseable {
    private fun now() = timeMilliseconds()
    private data class ProxyRetirement(val reason: String, val succeeded: Boolean, val fenceNs: Long)
    private data class VideoProofCandidate(
        val generation: Long, val ptsUs: Long, val audioObservation: SourceTimestampObservation?
    )
    private enum class FatalWork { NOT_STARTED, RUNNING, RETIRED, FAILED }
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
        incidentSave: () -> ((String, JSONObject) -> Unit) = { { _, _ -> } }
    ) : this(name, surface, onStatus, onVideoSize, onListener, port, videoOutput, ::validateCapabilities, onScreenEvent,
        { android.util.Log.e("ScreenCopyReceiver", it.logMessage()) }, onPresentationStart, null,
        onDiscoveryDiagnostic = { android.util.Log.i("ScreenCopyReceiver", it) }, onVideoRotation = onVideoRotation,
        acquireSession = acquireSession, instanceId = instanceId, onFramePresented = onFramePresented,
        supportsPlaybackStatistics = supportsPlaybackStatistics, receiveCapabilities = receiveCapabilities,
        videoSurface = videoSurface,
        incidentSave = incidentSave)

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
    private val proxyWriter = java.util.concurrent.ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS,
        java.util.concurrent.SynchronousQueue())
    private val proxyCloser = java.util.concurrent.ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS,
        java.util.concurrent.ArrayBlockingQueue(1), { task ->
            Thread(task, "diagnostic-lane-closer").apply { isDaemon = true }
        })
    private val timer = Executors.newSingleThreadScheduledExecutor()
    // shutdownNow can remove accepted timer tasks, so retain discard work until it runs or shutdown drains it.
    private val proxyDiscardLock = Any()
    private val pendingProxyDiscards = mutableSetOf<Runnable>()
    private var drainingProxyDiscards = false
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
                val connection = Connection(socket, System.nanoTime())
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
            try {
                socket.connect(target)
            } catch (e: SocketException) {
                socket.disconnect()
                onDiscoveryDiagnostic("event=DISCOVERY_ROUTE_UNAVAILABLE exception=${e.javaClass.simpleName}")
            }
            try {
                val bytes = advertisement()
                socket.send(DatagramPacket(bytes, bytes.size, target))
            } finally {
                socket.disconnect()
            }
            advertisementPackets.incrementAndGet()
        }
    }

    internal fun advertisement(): ByteArray {
        val bytes = JSONObject().put("protocol", "screencopy").put("version", Wire.VERSION)
            .put("name", name).put("port", listeningPort)
            .put("instanceId", instanceId)
            .put("receiveCapabilities", receiveCapabilities().json())
            .put("state", if (active.get() == null) "available" else "busy")
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
        statisticsTimer.shutdownNow()
        val errors = mutableListOf<Exception>()
        fun cleanup(action: () -> Unit) {
            try { action() } catch (e: Exception) { errors.add(e) }
        }
        cleanup { listener?.close() }
        cleanup { udp?.close() }
        cleanup { clients.close() }
        timer.shutdownNow()
        proxyWriter.shutdown()
        proxyCloser.shutdown()
        cleanup {
            acceptThread?.join(2000)
            check(acceptThread?.isAlive != true) { "Listener thread did not terminate after socket close" }
        }
        cleanup { check(clients.awaitTermination(5, TimeUnit.SECONDS)) { "Session cleanup timed out; reopen the app before receiving" } }
        cleanup { check(proxyWriter.awaitTermination(5, TimeUnit.SECONDS)) { "Diagnostic writer did not terminate" } }
        cleanup { active.get()?.receiverDestroyed() }
        val discards = synchronized(proxyDiscardLock) {
            drainingProxyDiscards = true
            pendingProxyDiscards.toList()
        }
        discards.forEach { discard -> cleanup { discard.run() } }
        cleanup { check(timer.awaitTermination(2, TimeUnit.SECONDS)) { "Network cleanup timed out" } }
        cleanup { check(statisticsTimer.awaitTermination(2, TimeUnit.SECONDS)) { "Statistics cleanup timed out" } }
        if (errors.isNotEmpty()) throw IllegalStateException("Receiver cleanup failed; reopen the app", errors.first()).also {
            errors.drop(1).forEach(it::addSuppressed)
        }
    }

    private inner class Connection(private val socket: Socket, private val socketAcceptedNs: Long) {
        private var helloReceivedNs = -1L
        private var helloValidatedNs = -1L
        private var helloReplyQueuedNs = -1L
        private var requestReceivedNs = -1L
        private var ownerAdmittedNs = -1L
        private var proxyCaptureStartedNs = -1L
        @Volatile private var proxyOffer: DiagnosticProxyNegotiation.Offer? = null
        @Volatile private var proxyCaptureId: String? = null
        private var proxyLaneToken: String? = null
        private val proxyGate = AtomicBoolean()
        private val proxyCaptureGate = AtomicBoolean()
        private val proxyControlClosed = AtomicBoolean()
        private val proxyDiscardScheduled = AtomicBoolean()
        private val proxyLaneCloseStarted = AtomicBoolean()
        private val proxyRetirementCloseScheduled = AtomicBoolean()
        private val proxyRetirement = AtomicReference<ProxyRetirement?>()
        @Volatile private var proxyOrdinaryRetirementComplete = false
        @Volatile private var proxyLane: Connection? = null
        @Volatile private var proxyReady = false
        @Volatile private var proxyWritingReady = false
        private val proxyReadyPublished = CountDownLatch(1)
        @Volatile private var diagnosticLane = false
        private val proxyWake = java.util.concurrent.ArrayBlockingQueue<Unit>(1)
        private val proxyControls = java.util.concurrent.ArrayBlockingQueue<JSONObject>(8)
        private var sessionLease: AutoCloseable? = null
        @Volatile private var writer: ControlWriter? = null
        @Volatile private var lastRead = now()
        @Volatile private var lastWrite = now()
        @Volatile private var phaseAt = now()
        @Volatile private var phase = "Setup"
        @Volatile private var session = 0L
        private var diagnosticsNegotiated = false
        private var telemetryOffered = false
        @Volatile private var telemetryNegotiated = false
        @Volatile private var telemetry: ReceiverTelemetrySession? = null
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
        private val fatalWork = AtomicReference(FatalWork.NOT_STARTED)
        private val resourceRetirementFailed = AtomicBoolean()
        private val retirementLock = Any()
        private var controlCleanupStarted = false
        private val controlCleanupFinished = CountDownLatch(1)
        private var retirementFailed = false
        private var ownershipReleased = false
        private var ordinaryStopInProgress = false
        private val fatalReply = AtomicReference<Pair<String, String>?>()
        private val fatalTerminalClaimed = AtomicBoolean()
        private val fatalAdmissionLock = Any()
        private var fatalCleanupDeadline: java.util.concurrent.ScheduledFuture<*>? = null
        private val playbackLock = Any()
        private val trackPlaybackOperations = Array(3) { Any() }
        private val recoveryExecutor = Executors.newSingleThreadExecutor()
        private val recoveryScheduler = TrackRecoveryScheduler(::now, observed = { track, generation, state, delayMs ->
            TelemetryRegistry.evidence("receiver", session, track, generation, when (state) {
                TrackRecoveryScheduler.State.QUEUED -> IncidentEvidence.Stage.RECOVERY_QUEUED
                TrackRecoveryScheduler.State.RUNNING -> IncidentEvidence.Stage.RECOVERY_RUNNING
                TrackRecoveryScheduler.State.CANCELLED -> IncidentEvidence.Stage.RECOVERY_CANCELLED
            }, delayMs)
        })
        private var controlWriteOperation = 0L
        private var playbackClock: PlaybackClock? = null
        private val clock: PlaybackClock get() = synchronized(playbackLock) {
            playbackClock ?: clockFactory().also {
                it.diagnosticSession = session
                playbackClock = it
            }
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
        // Media EOF revokes routing, not the admitted control session's final-telemetry contract.
        @Volatile private var sessionAccepted = false
        @Volatile private var mediaOwner: Connection? = null
        private var mediaTrack = 0

        private fun attachMedia(hello: Message): Connection {
            session = hello.session
            val json = hello.json()
            val channel = json.string("channel", 16)
            protocolRequire(channel == "video" || channel == "audio", "Expected video or audio channel")
            val token = json.string("token", 64)
            val owner = active.get() ?: throw ProtocolException("PROTOCOL", "Unknown or retired media session")
            if (owner.proxyWritingReady && !owner.proxyReady) {
                val remaining = owner.requestReceivedNs + 5_000_000_000L - System.nanoTime()
                if (remaining > 0) owner.proxyReadyPublished.await(remaining, TimeUnit.NANOSECONDS)
            }
            synchronized(owner.mediaLock) {
                protocolRequire(active.get() === owner && owner.accepted && !owner.stopping.get() &&
                    hello.session == owner.session && token == owner.mediaToken &&
                    (owner.proxyOffer == null || owner.proxyReady),
                    "Unknown or retired media session")
                mediaTrack = if (channel == "video") 1 else 2
                protocolRequire(owner.mediaChannels[mediaTrack - 1] == null, "Duplicate media channel")
                mediaOwner = owner
                owner.mediaChannels[mediaTrack - 1] = this
                if (owner.mediaChannels.all { it != null }) owner.mediaAttached.countDown()
            }
            return owner
        }

        private fun runProxyLane(hello: Message) {
            val owner = active.get() ?: throw ProtocolException("PROTOCOL", "No diagnostic owner")
            val offer: DiagnosticProxyNegotiation.Offer
            val capture: String
            val token: String
            val reservation: ConnectionPool.DiagnosticReservation
            val remaining: Int
            val laneAdmittedNs: Long
            synchronized(owner.mediaLock) {
                offer = owner.proxyOffer ?: throw ProtocolException("PROTOCOL", "No diagnostic offer")
                capture = owner.proxyCaptureId ?: throw ProtocolException("PROTOCOL", "No diagnostic capture")
                token = owner.proxyLaneToken ?: throw ProtocolException("PROTOCOL", "No diagnostic token")
                protocolRequire(active.get() === owner && owner.accepted && owner.proxyGate.get() &&
                    !owner.stopping.get() && !owner.socket.isClosed && owner.proxyLane == null &&
                    System.nanoTime() - owner.requestReceivedNs < 5_000_000_000L,
                    "Diagnostic owner no longer active")
                remaining = DiagnosticProxyNegotiation.laneHello(hello.json(), hello.session, offer, token)
                owner.proxyLane = this
                reservation = clients.reclassifyDiagnostic(this) ?: run {
                    owner.proxyLane = null
                    throw ProtocolException("BUSY", "Diagnostic lane unavailable")
                }
                laneAdmittedNs = System.nanoTime()
            }
            session = hello.session
            var writerSubmitted = false
            try {
            val expiry = try { proxyRecorder.shortenAdmittedReceiver(capture, remaining, helloReceivedNs) }
            catch (e: Exception) {
                reservation.writerExited()
                owner.proxyGate.set(false)
                throw e
            }
            val stages = JSONArray()
            listOf(owner.socketAcceptedNs, owner.helloReceivedNs, owner.helloValidatedNs,
                owner.helloReplyQueuedNs, owner.requestReceivedNs, owner.ownerAdmittedNs,
                owner.proxyCaptureStartedNs, laneAdmittedNs).forEachIndexed { index, timestamp ->
                stages.put(JSONObject().put("stage", DiagnosticProxyNegotiation.stages[index])
                    .put("monoNs", timestamp.toString()))
            }
            val bootstrap = JSONObject().put("session", session.toString()).put("pairId", offer.pairId)
                .put("captureId", capture).put("clockId", proxyRecorder.clockId)
                .put("complete", true).put("loss", false).put("stages", stages)
            DiagnosticProxyNegotiation.validateBootstrap(bootstrap, session, offer.pairId, capture, proxyRecorder.clockId)
            val ready = JSONObject().put("version", DiagnosticProxyNegotiation.VERSION).put("state", "ready")
                .put("pairId", offer.pairId).put("armGeneration", offer.generation.toString())
                .put("captureId", capture).put("clockId", proxyRecorder.clockId).put("bootstrap", bootstrap)
            try {
                proxyWriter.execute {
                    try { writeProxy(owner, offer, capture, expiry, ready) }
                    catch (e: Exception) {
                        onDiscoveryDiagnostic("event=DIAGNOSTIC_WRITER_FAILED exception=${e.javaClass.simpleName}")
                    } finally {
                        owner.proxyGate.set(false)
                        owner.proxyCaptureGate.set(false)
                        try {
                            try { socket.close() } catch (_: IOException) {}
                            discardProxyCapture(capture, "lane_closed")
                        } finally { reservation.writerExited() }
                    }
                }
                writerSubmitted = true
            } catch (e: java.util.concurrent.RejectedExecutionException) {
                reservation.writerExited()
                owner.proxyGate.set(false)
                throw ProtocolException("BUSY", "Diagnostic writer unavailable")
            }
            socket.soTimeout = 0
            val binding = DiagnosticProxyWire.Binding(offer.pairId, session, offer.generation, capture)
            try {
                proxyBeforeRead()
                while (owner.proxyGate.get() && !socket.isClosed) {
                    val frame = DiagnosticProxyWire.readFrame(socket.inputStream) ?: break
                    DiagnosticProxyWire.validateIdentity(frame, binding)
                    when (frame.getString("type")) {
                        "END" -> {
                            protocolRequire(frame.keys().asSequence().toSet() ==
                                setOf("v", "type", "pairId", "session", "armGeneration", "captureId"),
                                "Invalid END fields")
                            break
                        }
                        "CLOCK_REQUEST" -> {
                            protocolRequire(frame.keys().asSequence().toSet() ==
                                setOf("v", "type", "pairId", "session", "armGeneration", "captureId", "requestId"),
                                "Invalid CLOCK_REQUEST fields")
                            val requestId = frame.string("requestId", 64)
                            protocolRequire(requestId.isNotEmpty(), "Invalid clock request")
                            val response = proxyFrame(offer, capture, "CLOCK_RESPONSE")
                                .put("requestId", requestId).put("receiveNs", System.nanoTime().toString())
                                .put("clockId", proxyRecorder.clockId)
                            protocolRequire(proxyControls.offer(response), "Diagnostic control queue full")
                            proxyWake.offer(Unit)
                        }
                        else -> throw ProtocolException("PROTOCOL", "Unexpected diagnostic lane message")
                    }
                }
            } finally {
                owner.proxyGate.set(false)
                owner.proxyCaptureGate.set(false)
                proxyWake.offer(Unit)
            }
            } finally {
                if (!writerSubmitted) {
                    reservation.writerExited()
                    synchronized(owner.mediaLock) {
                        if (owner.proxyLane === this) owner.proxyLane = null
                    }
                    owner.closeProxy()
                }
            }
        }

        private fun proxyFrame(offer: DiagnosticProxyNegotiation.Offer, capture: String, type: String) =
            JSONObject().put("v", 2).put("type", type).put("pairId", offer.pairId)
                .put("session", offer.session.toString()).put("armGeneration", offer.generation.toString())
                .put("captureId", capture)

        private fun writeProxy(owner: Connection, offer: DiagnosticProxyNegotiation.Offer, capture: String,
                               expiry: Long, ready: JSONObject) {
            val deadline = minOf(expiry, owner.requestReceivedNs + 5_000_000_000L)
            val setupNs = deadline - System.nanoTime()
            protocolRequire(setupNs > 0 && owner.proxyGate.get(), "Diagnostic setup expired", "TIMEOUT")
            val timeout = timer.schedule({ socket.close() }, setupNs, TimeUnit.NANOSECONDS)
            try {
                proxyBeforeReady()
                owner.proxyWritingReady = true
                try {
                    Wire.write(socket.outputStream, Message.json(Type.HELLO, ready, session))
                    owner.proxyReady = true
                } finally { owner.proxyReadyPublished.countDown() }
            } finally { timeout.cancel(false) }
            val expiryTask = timer.schedule({
                owner.proxyGate.set(false)
                owner.proxyCaptureGate.set(false)
                try { socket.close() } catch (_: IOException) {}
            }, (expiry - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
            val wake = timer.scheduleWithFixedDelay({ proxyWake.offer(Unit) }, 0, 100, TimeUnit.MILLISECONDS)
            val budget = DiagnosticDeliveryBudget()
            var cursor = 0L
            try {
                while (owner.proxyGate.get() && !socket.isClosed) {
                    proxyWake.take()
                    val retirement = owner.proxyRetirement.get()
                    if (retirement != null) {
                        proxyBeforeRetirementFlush()
                        flushProxyRetirement(owner, offer, capture, expiry, cursor, retirement, budget)
                        break
                    }
                    while (true) {
                        val control = proxyControls.poll() ?: break
                        control.put("sendNs", System.nanoTime().toString())
                        val controlBytes = control.toString().toByteArray(Charsets.UTF_8).size + 4
                        if (!budget.tryAcquire(System.nanoTime(), 0, controlBytes)) {
                            protocolRequire(proxyControls.offer(control), "Diagnostic control queue full")
                            break
                        }
                        writeProxyFrame(control)
                    }
                    proxyBeforeFetch()
                    val page = proxyRecorder.fetch(capture, cursor, 32)
                    val loss = proxyFrame(offer, capture, "LOSS").put("watermarks", page.getJSONObject("lossWatermarks"))
                    val lossBytes = loss.toString().toByteArray(Charsets.UTF_8).size + 4
                    if (budget.tryAcquire(System.nanoTime(), 0, lossBytes)) writeProxyFrame(loss)
                    val records = page.getJSONArray("records")
                    for (index in 0 until records.length()) {
                        val record = records.getJSONObject(index)
                        DiagnosticProxyWire.validateSource(record, "receiver", offer.session.toString(),
                            capture, "video.timing" in offer.flags)
                        val frame = proxyFrame(offer, capture, "RECORD").put("record", record)
                        val bytes = frame.toString().toByteArray(Charsets.UTF_8).size + 4
                        protocolRequire(bytes <= DiagnosticProxyWire.FRAME_BYTES + 4, "Oversized diagnostic record frame")
                        if (!budget.tryAcquire(System.nanoTime(), 1, bytes)) break
                        writeProxyFrame(frame)
                        cursor = DiagnosticProxyWire.decimal(record.opt("seq"))
                    }
                }
            } finally {
                wake.cancel(false)
                expiryTask.cancel(false)
            }
        }

        private fun writeProxyFrame(frame: JSONObject) {
            val timeout = timer.schedule({ socket.close() }, 2, TimeUnit.SECONDS)
            try { DiagnosticProxyWire.writeFrame(socket.outputStream, frame) }
            finally { timeout.cancel(false) }
        }

        private fun flushProxyRetirement(owner: Connection, offer: DiagnosticProxyNegotiation.Offer,
                                         capture: String, expiry: Long, after: Long,
                                         retirement: ProxyRetirement, budget: DiagnosticDeliveryBudget) {
            val deadline = minOf(expiry, retirement.fenceNs + 100_000_000L)
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0 || !owner.proxyGate.get() || socket.isClosed) return
            val timeout = timer.schedule({ socket.close() }, remaining, TimeUnit.NANOSECONDS)
            try {
                val ended = proxyRecorder.finishAdmittedReceiver(capture, retirement.reason)
                var cursor = after
                var truncated = true
                var finalLoss: JSONObject? = null
                while (System.nanoTime() < deadline && owner.proxyGate.get() && !socket.isClosed) {
                    val page = proxyRecorder.fetch(capture, cursor, 32)
                    finalLoss = page.getJSONObject("lossWatermarks")
                    val records = page.getJSONArray("records")
                    var sent = 0
                    for (index in 0 until records.length()) {
                        val record = records.getJSONObject(index)
                        DiagnosticProxyWire.validateSource(record, "receiver", offer.session.toString(),
                            capture, "video.timing" in offer.flags)
                        val frame = proxyFrame(offer, capture, "RECORD").put("record", record)
                        val bytes = frame.toString().toByteArray(Charsets.UTF_8).size + 4
                        if (System.nanoTime() >= deadline || !budget.tryAcquire(System.nanoTime(), 1, bytes)) break
                        DiagnosticProxyWire.writeFrame(socket.outputStream, frame)
                        cursor = DiagnosticProxyWire.decimal(record.opt("seq"))
                        sent++
                    }
                    if (sent != records.length()) break
                    if (!page.getBoolean("hasMore") && page.getInt("pendingRecords") == 0) {
                        truncated = false
                        break
                    }
                    if (records.length() == 0) break
                }
                val loss = finalLoss ?: return
                if (System.nanoTime() >= deadline || !owner.proxyGate.get() || socket.isClosed) return
                val terminal = proxyFrame(offer, capture, "ENDED").put("clockId", proxyRecorder.clockId)
                    .put("reason", retirement.reason).put("endedNs", ended.getString("endedNs"))
                    .put("mediaRetirement", if (retirement.succeeded) "succeeded" else "failed")
                    .put("ownershipCleanup", "unknown").put("tailTruncated", truncated)
                    .put("captureEnd", JSONObject().put("atNs", retirement.fenceNs.toString())
                        .put("reason", retirement.reason).put("complete", !truncated).put("truncated", truncated))
                    .put("sourceLoss", loss)
                val bytes = terminal.toString().toByteArray(Charsets.UTF_8).size + 4
                if (budget.tryAcquire(System.nanoTime(), 0, bytes))
                    DiagnosticProxyWire.writeFrame(socket.outputStream, terminal)
            } finally { timeout.cancel(false) }
        }

        private fun proxyRetired(succeeded: Boolean, fatal: Boolean) {
            if (proxyOffer == null) return
            val reason = if (fatal) "receiver_retired_fatal" else "receiver_retired_stop"
            val retired = ProxyRetirement(reason, succeeded, System.nanoTime())
            if (proxyRetirement.compareAndSet(null, retired)) {
                proxyCaptureGate.set(false)
                proxyLane?.proxyWake?.offer(Unit)
            }
            completeLocalProxyRetirement()
        }

        private fun completeLocalProxyRetirement() {
            val retirement = proxyRetirement.get() ?: return
            if (!receiverStopRequested || stopWrittenAt == null) return
            if (retirement.reason == "receiver_retired_stop" && retirement.succeeded &&
                !terminalError && !proxyControlClosed.get()) proxyOrdinaryRetirementComplete = true
            // Legacy STOP can be written before media retirement; publish both before releasing its ACK.
            stopWritten.countDown()
        }

        private fun closeProxy() {
            if (proxyOffer == null) return
            proxyGate.set(false)
            proxyCaptureGate.set(false)
            val lane = proxyLane
            lane?.proxyWake?.offer(Unit)
            if (lane != null) {
                if (proxyLaneCloseStarted.compareAndSet(false, true)) {
                    val close = Runnable { try { lane.socket.close() } catch (_: IOException) {} }
                    try { proxyCloser.execute(close) }
                    catch (_: java.util.concurrent.RejectedExecutionException) {
                        Thread(close, "diagnostic-lane-close-fallback").apply { isDaemon = true; start() }
                    }
                }
                return
            }
            val capture = proxyCaptureId ?: return
            if (!proxyDiscardScheduled.compareAndSet(false, true)) return
            val claimed = AtomicBoolean()
            lateinit var discard: Runnable
            discard = Runnable {
                if (claimed.compareAndSet(false, true)) {
                    try { discardProxyCapture(capture, "control_closed") }
                    finally { synchronized(proxyDiscardLock) { pendingProxyDiscards.remove(discard) } }
                }
            }
            val runHere = synchronized(proxyDiscardLock) {
                if (drainingProxyDiscards) true else {
                    pendingProxyDiscards.add(discard)
                    try { timer.execute(discard) }
                    catch (_: java.util.concurrent.RejectedExecutionException) {
                        // Shutdown drains registered work even if the timer rejects it.
                    }
                    false
                }
            }
            if (runHere) discard.run()
        }

        private fun closeProxyAfterOrdinaryRetirement() {
            val retirement = proxyRetirement.get()
            if (!proxyOrdinaryRetirementComplete || retirement?.reason != "receiver_retired_stop" ||
                !retirement.succeeded ||
                proxyLane == null || !proxyGate.get()) {
                closeProxy()
                return
            }
            if (!proxyRetirementCloseScheduled.compareAndSet(false, true)) return
            val remaining = retirement.fenceNs + 100_000_000L - System.nanoTime()
            if (remaining <= 0) {
                closeProxy()
                return
            }
            try {
                timer.schedule({ closeProxy() }, remaining, TimeUnit.NANOSECONDS)
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                closeProxy()
            }
        }

        private fun fenceProxyControl() {
            proxyControlClosed.set(true)
            closeProxy()
        }

        private fun closeTransport() {
            fenceProxyControl()
            socket.close()
        }

        private fun discardProxyCapture(id: String, reason: String) {
            try {
                proxyRecorder.finishAdmittedReceiver(id, reason)
                proxyRecorder.discardAdmittedReceiver(id)
            } catch (e: DiagnosticError) {
                onDiscoveryDiagnostic("event=DIAGNOSTIC_CAPTURE_CLEANUP_FAILED code=${e.code}")
            }
        }

        private fun readMedia(owner: Connection) {
            send(Type.HELLO, JSONObject().put("channel", if (mediaTrack == 1) "video" else "audio"))
            protocolRequire(owner.mediaAttached.await(10, TimeUnit.SECONDS),
                "Both video and audio media connections are required", "TIMEOUT")
            socket.soTimeout = 0
            phase = "Media"
            while (running.get() && !socket.isClosed && (!owner.stopping.get() || owner.orderlyStop)) {
                val operations = RemoteDiagnostics.recorder.transportOperations(
                    "receiver", owner.session, mediaTrack == 2)
                if (mediaTrack == 2) RemoteDiagnostics.recorder.observe("receiver", owner.session,
                    DiagnosticKind.AUDIO_TRANSPORT, DiagnosticStage.BEGIN, mediaTrack, operation = operations.audio,
                    point = DiagnosticPoint.READ)
                RemoteDiagnostics.recorder.observe("receiver", owner.session, DiagnosticKind.TRANSPORT_HEALTH,
                    DiagnosticStage.BEGIN, mediaTrack, operation = operations.health, point = DiagnosticPoint.READ)
                val message = try { read() } catch (e: Exception) {
                    if (mediaTrack == 2) RemoteDiagnostics.recorder.observe("receiver", owner.session,
                        DiagnosticKind.AUDIO_TRANSPORT, DiagnosticStage.FAILED, mediaTrack,
                        operation = operations.audio,
                        reason = DiagnosticReason.FAILURE, point = DiagnosticPoint.READ)
                    RemoteDiagnostics.recorder.observe("receiver", owner.session, DiagnosticKind.TRANSPORT_HEALTH,
                        DiagnosticStage.FAILED, mediaTrack, operation = operations.health,
                        reason = DiagnosticReason.FAILURE, point = DiagnosticPoint.READ)
                    throw e
                }
                if (mediaTrack == 2) RemoteDiagnostics.recorder.observe("receiver", owner.session,
                    DiagnosticKind.AUDIO_TRANSPORT, DiagnosticStage.COMPLETE, mediaTrack, message.generation.toInt(),
                    operations.audio, message.ptsUs, bytes = message.payload.size.toLong(), point = DiagnosticPoint.READ)
                RemoteDiagnostics.recorder.observe("receiver", owner.session, DiagnosticKind.TRANSPORT_HEALTH,
                    DiagnosticStage.COMPLETE, mediaTrack, message.generation.toInt(), operations.health, message.ptsUs,
                    bytes = message.payload.size.toLong(), point = DiagnosticPoint.READ)
                if (owner.stopping.get()) {
                    if (owner.orderlyStop) continue
                    return
                }
                val previousGeneration = owner.currentMediaGeneration(mediaTrack)
                try {
                    protocolRequire(message.session == owner.session && message.track == mediaTrack &&
                        (message.type == Type.STREAM_CONFIG ||
                            message.type == if (mediaTrack == 1) Type.VIDEO_FRAME else Type.AUDIO_PACKET),
                        "Packet does not belong to this media channel")
                    protocolRequire(mediaTrack == 1 || owner.request?.audioCodec != null, "Audio was not enabled")
                    if (message.type == Type.STREAM_CONFIG) owner.configure(message)
                    else owner.receiveMedia(message)
                } catch (e: Exception) {
                    if (owner.mediaFailed(e, mediaTrack, message.generation, previousGeneration)) return
                }
            }
        }

        private fun receiveMedia(message: Message) {
            TelemetryRegistry.evidence("receiver", session, message.track, message.generation.toInt(),
                IncidentEvidence.Stage.ADMISSION_BEGIN, message.ptsUs, message.payload.size.toLong())
            var admitted = false
            try {
            if (message.track == 1) RemoteDiagnostics.recorder.video("receiver", session,
                message.generation.toInt(), DiagnosticVideoStage.RECEIVED, message.ptsUs,
                message.payload.size.toLong(), keyFrame = message.flags and 1 != 0)
            val packet = AccessUnit(message.ptsUs, message.payload, message.flags and 1 != 0,
                (message.flags ushr 1) * 90)
            var admissionEpoch = 0L
            var audioDurationUs = -1L
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
                audioDurationUs = if (message.type != Type.AUDIO_PACKET) -1L
                    else audioEnd?.minus(packet.ptsUs) ?:
                        checkNotNull(audioPacketTiming).diagnosticDurationUs(packet.bytes) ?: -1L
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
            if (target is AudioPlayer) target.packetReceived(packet.ptsUs, packet.bytes.size)
            // Admission may wait; never hold the other media lane behind a full track.
            try {
                val pressure = if (!streamRecovery) { target.offer(packet, audioDurationUs); null }
                    else target.tryOffer(packet, audioDurationUs)
                admitted = pressure == null
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
            } finally {
                if (admitted) TelemetryRegistry.evidence("receiver", session, message.track, message.generation.toInt(),
                    IncidentEvidence.Stage.ADMISSION_END)
                else TelemetryRegistry.evidence("receiver", session, message.track, message.generation.toInt(),
                    IncidentEvidence.Stage.ADMISSION_END, 0)
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
                    TelemetryRegistry.freezeReceiver(session, audioGeneration.toInt())
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
            recordTelemetryPressure(pressure, generation.toInt())
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

        private fun currentMediaGeneration(track: Int): Long = synchronized(playbackLock) {
            if (track == 1) videoGeneration else audioGeneration
        }

        private fun mediaFailed(error: Exception, failureTrack: Int = 0, failureGeneration: Long? = null,
                                previousGeneration: Long? = null): Boolean {
            if (!running.get() || stopping.get() || !accepted) return true
            if (failureTrack == 0 && (error is EOFException || error is SocketException)) {
                if (mediaDisconnect.compareAndSet(null, error to now())) {
                    onDiscoveryDiagnostic("event=MEDIA_DISCONNECT_AWAITING_CONTROL exception=${error.javaClass.simpleName}")
                    closeMedia()
                }
                return true
            }
            return terminateMediaFailure(error, failureTrack = failureTrack, failureGeneration = failureGeneration,
                previousGeneration = previousGeneration)
        }

        private fun terminateMediaFailure(error: Exception, detailOverride: String? = null,
                                          failureTrack: Int = 0, failureGeneration: Long? = null,
                                          previousGeneration: Long? = null): Boolean {
            synchronized(retirementLock) {
                if (controlCleanupStarted) return true
                if (!running.get() || stopping.get()) return true
                val code = when (error) {
                    is ProtocolException -> error.code
                    is PlaybackException -> error.code
                    is java.net.SocketTimeoutException -> "TIMEOUT"
                    is IOException -> "PROTOCOL"
                    else -> "INTERNAL"
                }
                val detail = detailOverride ?: "Media connection failed: ${error.message ?: error.javaClass.simpleName}"
                synchronized(playbackLock) {
                    if (failureTrack != 0) {
                        val currentGeneration = if (failureTrack == 1) videoGeneration else audioGeneration
                        if (!accepted || active.get() !== this) return true
                        if (currentGeneration != previousGeneration &&
                            currentGeneration != failureGeneration) return false
                    }
                    if (!connectionError.compareAndSet(null, code to detail)) return true
                    stopAt = now()
                    if (!stopping.compareAndSet(false, true)) return true
                    terminalError = true
                }
                val origin = when {
                    error is ReceiverOrientationException -> FailureOrigin.PRESENTATION
                    failureTrack == 1 -> FailureOrigin.VIDEO
                    failureTrack == 2 -> FailureOrigin.AUDIO
                    initializingTrack == 1 -> FailureOrigin.VIDEO
                    initializingTrack == 2 -> FailureOrigin.AUDIO
                    else -> FailureOrigin.SESSION
                }
                reportFailure(code, detail, origin, (error as? QueueCongestionException)?.pressure,
                    (error as? AudioOutputPreparationException)?.diagnostic,
                    (error as? VideoPlaybackException)?.diagnostic,
                    if (failureTrack != 0)
                        failureGeneration?.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt() ?: 0 else null,
                    failureTrack.takeIf { it != 0 })
                onStatus("Session error: $code", "$detail\nReconnect after correcting the sender settings or network.")
                beginFatalRetirement(code, detail)
                retireFailedSession()
                return true
            }
        }

        private fun createControlWriter() = ControlWriter(socket, { message ->
                    lastWrite = now()
                    if (message.type == Type.ERROR && fatalTerminalClaimed.get()) {
                        closeTransport()
                        writer?.close()
                    }
                    if (message.type == Type.STOP) {
                        stopWrittenAt = lastWrite
                        if (proxyOffer == null) stopWritten.countDown()
                        else completeLocalProxyRetirement()
                    }
                }, { detail ->
                    fenceProxyControl()
                    connectionError.compareAndSet(null, "INTERNAL" to detail)
                    if (orderlyStop && running.get() && !socket.isClosed)
                        failOrderlyStop(ProtocolException("PROTOCOL", detail))
                    else closeTransport()
                }, controlWrite, ::now, observed@{ message, boundary, packets, bytes, oldestMs ->
                    if (active.get() !== this || message.session != session ||
                        !TelemetryRegistry.has("receiver", session)) return@observed
                    if (boundary == ControlWriter.Boundary.WRITING) {
                        controlWriteOperation = RemoteDiagnostics.recorder.operation("receiver", session, DiagnosticKind.TRANSPORT_HEALTH)
                        RemoteDiagnostics.recorder.observe("receiver", session, DiagnosticKind.TRANSPORT_HEALTH,
                            DiagnosticStage.BEGIN, operation = controlWriteOperation, bytes = message.payload.size.toLong(),
                            point = DiagnosticPoint.WRITE)
                    } else if (boundary == ControlWriter.Boundary.WRITTEN) {
                        RemoteDiagnostics.recorder.observe("receiver", session, DiagnosticKind.TRANSPORT_HEALTH,
                            DiagnosticStage.COMPLETE, operation = controlWriteOperation, bytes = message.payload.size.toLong(),
                            point = DiagnosticPoint.WRITE)
                    } else if (boundary == ControlWriter.Boundary.FAILED) {
                        RemoteDiagnostics.recorder.observe("receiver", session, DiagnosticKind.TRANSPORT_HEALTH,
                            DiagnosticStage.FAILED, operation = controlWriteOperation, bytes = message.payload.size.toLong(),
                            point = DiagnosticPoint.WRITE)
                    }
                    ControlWriter.observeReadyBoundary(message, boundary)
                    if (packets >= 0) TelemetryRegistry.evidence("receiver", session, 0, 0,
                        IncidentEvidence.Stage.QUEUE_STATE, packets.toLong(), bytes.toLong(), -1,
                        if (oldestMs >= 0) oldestMs * 1_000_000 else -1)
                }, ::closeTransport)

        fun run() {
            try {
                socket.tcpNoDelay = true
                socket.soTimeout = 10_000
                val hello = try {
                    read { type -> if (type == Type.HELLO) helloReceivedNs = System.nanoTime() }
                } catch (error: Exception) {
                    writer = createControlWriter()
                    throw error
                }
                val (h, role) = try {
                    protocolRequire(hello.type == Type.HELLO, "Expected HELLO")
                    val json = hello.json()
                    json to json.string("role")
                } catch (error: Exception) {
                    writer = createControlWriter()
                    throw error
                }
                if (role == "diagnostic") {
                    diagnosticLane = true
                    proxyLaneReceived(helloReceivedNs)
                    runProxyLane(hello)
                    return
                }
                writer = createControlWriter()
                if (role == "media") {
                    readMedia(attachMedia(hello))
                    return
                }
                protocolRequire(hello.session == 0L, "Control HELLO requires session zero")
                protocolRequire(role == "sender", "Unexpected peer role")
                proxyOffer = DiagnosticProxyNegotiation.offer(h, helloReceivedNs)
                diagnosticsNegotiated = org.screencopy.receiver.protocol.DiagnosticDelegation.supported(h) &&
                    diagnosticSessionAccess != null
                telemetryOffered = SessionTelemetryProtocol.supported(h)
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
                helloValidatedNs = System.nanoTime()
                controlStatistics.validControl(now())
                val response = JSONObject().put("role", "receiver").put("versions", JSONArray().put(Wire.VERSION)).put("name", name)
                    .put("uncappedFps", true)
                if (videoRotation) response.put("videoRotation", true)
                if (streamRecovery) response.put("streamRecovery", true).put("streamRecoveryVersion", StreamRecoveryProtocol.VERSION)
                if (negotiatedOptions.playbackStats) response.put("receiverOptions", negotiatedOptions.wireValue())
                if (hdrVideoVersion == 1) response.put("receiveCapabilities", receiveCapabilities().json())
                if (diagnosticsNegotiated) response.put("diagnostics",
                    org.screencopy.receiver.protocol.DiagnosticDelegation.capability())
                if (telemetryOffered) response.put("sessionTelemetry", SessionTelemetryProtocol.capability())
                if (h.optJSONObject("diagProxy")?.opt("version") == DiagnosticProxyNegotiation.VERSION)
                    response.put("diagProxy", DiagnosticProxyNegotiation.receiverCapability())
                send(Type.HELLO, response)
                helloReplyQueuedNs = System.nanoTime()
                phase = "Negotiating"
                phaseAt = now()
                socket.soTimeout = org.screencopy.receiver.protocol.HdrPreparationDeadline.milliseconds(hdrVideoVersion)
                val negotiation = readControlUntilRequest()
                socket.soTimeout = 10_000
                session = negotiation.session
                protocolRequire(session > 0, "Session ID must be positive")
                DiagnosticProxyNegotiation.requestMatches(negotiation.json(), session, proxyOffer)
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
                ownerAdmittedNs = System.nanoTime()
                proxyOffer?.let { offer ->
                    proxyGate.set(true)
                    proxyCaptureGate.set(true)
                    if (proxyControlClosed.get()) {
                        proxyGate.set(false)
                        proxyCaptureGate.set(false)
                    }
                    val started = proxyRecorder.startAdmittedReceiver(session, offer.flags,
                        offer.expiresNs, offer.samplePeriodMs, proxyCaptureGate)
                    proxyCaptureId = started.getString("captureId")
                    proxyCaptureStartedNs = started.getString("startedNs").toLong()
                    if (proxyControlClosed.get()) {
                        closeProxy()
                        throw ProtocolException("PROTOCOL", "Diagnostic control transport closed")
                    }
                    proxyLaneToken = ByteArray(32).also { SecureRandom().nextBytes(it) }
                        .joinToString("") { "%02x".format(it.toInt() and 255) }
                }
                onScreenEvent(ReceiverScreenEvent.Connecting)
                request = Request.parse(negotiation.json(), negotiatedOptions, hdrVideoVersion == 1, receiveCapabilities())
                controlStatistics.validControl(now())
                val settings = checkNotNull(request)
                telemetryNegotiated = SessionTelemetryProtocol.supported(negotiation.json())
                protocolRequire(!telemetryNegotiated || telemetryOffered, "Unsolicited session telemetry capability")
                validateRequest(settings)
                requestSummary = settings.summary
                tracks = TrackState(settings.tracks)
                clock.audioExpected = settings.audioCodec != null
                phase = "Initializing tracks"
                phaseAt = now()
                onStatus(phase, "$senderName\n$requestSummary")
                RemoteDiagnostics.recorder.sessionStarted("receiver", session)
                if (telemetryNegotiated) {
                    lateinit var owner: ReceiverTelemetrySession
                    owner = ReceiverTelemetrySession(
                        offerTelemetry = { payload ->
                            if (stopping.get() || socket.isClosed) false
                            else when (checkNotNull(writer).offerTelemetry(Message(Type.STATUS, session = session, payload = payload))) {
                                ControlWriter.TelemetryAdmission.ACCEPTED -> true
                                ControlWriter.TelemetryAdmission.REPLACED -> { owner.publicationUnsent(); true }
                                ControlWriter.TelemetryAdmission.REJECTED -> false
                            }
                        },
                        failed = { onDiscoveryDiagnostic("event=TELEMETRY_FAILED error=${it.javaClass.simpleName}") }
                    )
                    telemetry = owner
                    TelemetryRegistry.beginReceiver(session, owner)
                    TelemetryRegistry.sessionConfiguration("receiver", session, settings.videoCodec, settings.audioCodec,
                        settings.fps, settings.videoBitrate, settings.audioBitrate)
                }
                synchronized(mediaLock) { accepted = true; sessionAccepted = true }
                send(Type.SESSION_ACCEPT, JSONObject().put("tracks", JSONArray(settings.tracks)).put("mediaToken", mediaToken).apply {
                    settings.videoColor?.let { put("videoColor", it.json()) }
                    if (settings.diagnosticRequested) put("diagnostic",
                        org.screencopy.receiver.protocol.DiagnosticDelegation.result(
                            if (diagnosticsNegotiated) "starting" else "unsupported", session, null))
                    if (telemetryNegotiated) put("sessionTelemetry", SessionTelemetryProtocol.capability())
                    proxyOffer?.let { offer ->
                        put("diagProxy", JSONObject().put("version", DiagnosticProxyNegotiation.VERSION)
                            .put("pairId", offer.pairId).put("armGeneration", offer.generation.toString())
                            .put("laneToken", checkNotNull(proxyLaneToken))
                            .put("captureId", checkNotNull(proxyCaptureId)).put("clockId", proxyRecorder.clockId))
                    }
                })
                diagnosticOwner = true
                try {
                    diagnosticSessionAccess?.admitted(session, settings.diagnosticRequested && diagnosticsNegotiated,
                        { active.get() === this && accepted && !stopping.get() && !socket.isClosed }, ::diagnosticReport)
                } catch (_: Exception) {
                    diagnosticReport("unavailable")
                    onDiscoveryDiagnostic("event=DIAGNOSTIC_START_UNAVAILABLE nonfatal=true")
                }
                if (streamRecovery) socket.soTimeout = 0
                while (running.get() && !socket.isClosed) {
                    val message = read()
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
                if (diagnosticLane) {
                    onDiscoveryDiagnostic("event=DIAGNOSTIC_LANE_FAILED exception=${e.javaClass.simpleName}")
                } else {
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
                    synchronized(retirementLock) {
                        if (!stopping.get()) {
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
                            try {
                                if (sessionAccepted && telemetryNegotiated) {
                                    stopping.set(true)
                                    stopAt = now()
                                    beginFatalRetirement(code, detail)
                                    retireFailedSession()
                                } else send(if (phase == "Negotiating" && session > 0) Type.REJECT else Type.ERROR,
                                    JSONObject().put("code", code).put("message", detail).put("fatal", true))
                            } catch (sendError: Exception) {
                                if (active.get() === this) onStatus("Connection closed", "$detail; could not deliver error (${sendError.javaClass.simpleName})")
                            }
                        }
                    }
                }
                }
            } finally {
                if (diagnosticLane) {
                    recoveryScheduler.shutdown()
                    recoveryExecutor.shutdown()
                    closeTransport()
                } else {
                diagnosticEnd()
                var cleanupFailed = false
                var fatalControlFailed = false
                if (mediaOwner == null) closeMedia()
                // Claim and submission are serialized with fatal initiation; only that worker halts players.
                val fatalWorker = synchronized(retirementLock) {
                    controlCleanupStarted = true
                    fatalWork.get() != FatalWork.NOT_STARTED
                }
                if (!fatalWorker || !telemetryNegotiated || socket.isClosed) closeProxyAfterOrdinaryRetirement()
                if (proxyOrdinaryRetirementComplete) proxyAfterOrdinaryCleanupFence()
                recoveryScheduler.shutdown()
                recoveryExecutor.shutdown()
                if (!fatalWorker || !telemetryNegotiated) {
                    try {
                        check(recoveryScheduler.awaitTermination(3, TimeUnit.SECONDS)) { "Track recovery workers did not retire" }
                        check(recoveryExecutor.awaitTermination(3, TimeUnit.SECONDS)) { "Recovery worker did not retire" }
                    } catch (e: Exception) {
                        cleanupFailed = true
                        resourceRetirementFailed.set(true)
                        onStatus("Recovery cleanup failed", e.message ?: e.javaClass.simpleName)
                    }
                }
                try {
                    if (mediaOwner == null && !fatalWorker) {
                        stopPlayback()
                    }
                } catch (e: Exception) {
                    cleanupFailed = true
                    onStatus("Playback cleanup failed", "${e.message ?: e.javaClass.simpleName}; reopen receiver")
                    reportFailure("INTERNAL", "Playback cleanup failed", FailureOrigin.CLEANUP)
                } finally {
                    if (mediaOwner == null && !fatalWorker && fatalReply.get() != null) {
                        try { completeFatalRetirement(!cleanupFailed) }
                        catch (error: Exception) {
                            fatalControlFailed = true
                            onStatus("Connection cleanup failed", error.message ?: error.javaClass.simpleName)
                            closeTransport()
                        }
                    }
                    try {
                        if (!fatalWorker || !telemetryNegotiated) writer?.close()
                    } catch (e: Exception) {
                        cleanupFailed = true
                        fatalControlFailed = true
                        onStatus("Connection cleanup failed", "${e.message ?: e.javaClass.simpleName}; reopen receiver")
                        reportFailure("INTERNAL", "Connection cleanup failed", FailureOrigin.CLEANUP)
                    } finally {
                        try {
                            if (!fatalWorker || !telemetryNegotiated) {
                                proxyControlClosed.set(true)
                                closeProxyAfterOrdinaryRetirement()
                                socket.close()
                            }
                        } finally {
                            if (fatalWorker) finishFatalCleanup(control = true, failed = fatalControlFailed)
                            else finishOrdinaryCleanup(cleanupFailed)
                        }
                        }
                    }
                }
            }
        }

        private fun read(received: (Type) -> Unit = {}): Message {
            val observed = active.get() === this && accepted
            val operation = if (observed)
                RemoteDiagnostics.recorder.operation("receiver", session, DiagnosticKind.TRANSPORT_HEALTH) else 0
            if (observed) RemoteDiagnostics.recorder.observe("receiver", session, DiagnosticKind.TRANSPORT_HEALTH,
                DiagnosticStage.BEGIN, operation = operation, point = DiagnosticPoint.READ)
            try {
                return Wire.read(socket.inputStream, { readStartedAt = now() }, received).also {
                    readStartedAt = null
                    lastRead = now()
                    if (observed) RemoteDiagnostics.recorder.observe("receiver", session, DiagnosticKind.TRANSPORT_HEALTH,
                        DiagnosticStage.COMPLETE, operation = operation, bytes = it.payload.size.toLong(),
                        point = DiagnosticPoint.READ)
                }
            } catch (error: Exception) {
                if (!diagnosticLane && mediaOwner == null) fenceProxyControl()
                if (observed) RemoteDiagnostics.recorder.observe("receiver", session, DiagnosticKind.TRANSPORT_HEALTH,
                    DiagnosticStage.FAILED, operation = operation, reason = DiagnosticReason.FAILURE,
                    point = DiagnosticPoint.READ)
                throw error
            }
        }

        private fun readControlUntilRequest(): Message {
            while (true) {
                val message = read { type -> if (type == Type.SESSION_REQUEST) requestReceivedNs = System.nanoTime() }
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
            TelemetryRegistry.evidence("receiver", session, message.track, message.generation.toInt(),
                IncidentEvidence.Stage.CONFIG_RECEIVED)
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
            TelemetryRegistry.trackConfiguration("receiver", session, message.track, message.generation.toInt(),
                if (message.track == 1) settings.videoCodec else audioFormat?.codec.orEmpty(),
                videoFormat?.width ?: 0, videoFormat?.height ?: 0, audioFormat?.sampleRate ?: 0, audioFormat?.channels ?: 0)
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
            retireResources {
                try { retired.first?.let { videoOutput.release(it) } } finally { retired.second?.close() }
            }
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
                } else createdAudio = createAudioPlayer(checkNotNull(audioFormat), shared, message.generation)
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
                    retireResources {
                        try { createdVideo?.let { videoOutput.release(it) } } finally {
                            try { createdAudio?.close() } finally { createdPresentation?.close() }
                        }
                    }
                }
            }
        }

        private inline fun <T> diagnosticRecoveryCall(track: Int, generation: Long, point: DiagnosticPoint, call: () -> T): T {
            val diagnostics = RemoteDiagnostics.recorder
            if (!diagnostics.enabled("receiver", session, DiagnosticKind.RECOVERY) &&
                !TelemetryRegistry.has("receiver", session)) return call()
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

        private fun retireResources(action: () -> Unit) {
            try {
                action()
            } catch (e: Exception) {
                resourceRetirementFailed.set(true)
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
            TelemetryRegistry.trackConfiguration("receiver", session, track, message.generation.toInt(),
                if (track == 1) settings.videoCodec else audioFormat?.codec.orEmpty(),
                videoFormat?.width ?: 0, videoFormat?.height ?: 0, audioFormat?.sampleRate ?: 0, audioFormat?.channels ?: 0)
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
                    TelemetryRegistry.freezeReceiver(session, audioGeneration.toInt())
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
            recoveryScheduler.schedule(track, message.generation.toInt(), ::current) {
                try {
                    synchronized(trackPlaybackOperations[track]) operations@ {
                        if (!current()) return@operations
                        val retired: Pair<VideoPlayer?, AudioPlayer?> = synchronized(playbackLock) {
                            if (track == 1) (video to null).also { video = null }
                            else (null to audio).also { audio = null }
                        }
                        retireResources {
                            diagnosticRecoveryCall(track, message.generation, DiagnosticPoint.RECOVERY_RETIRE) {
                                try { retired.first?.let { videoOutput.release(it) } } finally { retired.second?.close() }
                            }
                        }
                        if (!current()) return@operations
                        val sameRaster = videoFormat?.let { format ->
                            activeVideoFormat?.let { it.width == format.width && it.height == format.height } == true
                        } ?: true
                        var createdVideo: VideoPlayer? = null
                        var createdAudio: AudioPlayer? = null
                        var installed = false
                        fun retireCreated() {
                            val oldVideo = createdVideo
                            val oldAudio = createdAudio
                            createdVideo = null
                            createdAudio = null
                            retireResources {
                                try { oldVideo?.let { videoOutput.release(it) } } finally { oldAudio?.close() }
                            }
                        }
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
                                createAudioPlayer(checkNotNull(audioFormat).copy(preSkip = 0), clock,
                                    message.generation, audioRecoveryCutoff)
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
                                retireCreated()
                                return@operations
                            }
                            if (current()) {
                                onDiscoveryDiagnostic("event=STREAM_RECOVERY_READY track=$track generation=${message.generation}")
                            }
                        } catch (e: Exception) {
                            if (!installed) retireCreated()
                            throw e
                        }
                    }
                } catch (e: Exception) {
                    if (accepted && !stopping.get() &&
                        (if (track == 1) videoGeneration else audioGeneration) == message.generation && active.get() === this)
                        playbackFailed(if (e is PlaybackException) e.code else if (track == 1) "DECODER_FAILED" else "AUDIO_FAILED",
                            "Track $track recovery failed: ${e.message ?: e.javaClass.simpleName}",
                            if (track == 1) FailureOrigin.VIDEO else FailureOrigin.AUDIO, message.generation)
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
                        playbackFailed(code, detail, FailureOrigin.VIDEO, generation)
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

        private fun createAudioPlayer(format: AudioFormat, shared: PlaybackClock, generation: Long,
                                      cutoff: Long? = null): AudioPlayer {
            val epoch = ++audioEpoch
            val failed: (String, String) -> Unit = { code, detail ->
                if (accepted && !stopping.get() && epoch == audioEpoch && epoch > retiredAudioThrough)
                    playbackFailed(code, detail, FailureOrigin.AUDIO, generation, epoch)
                else onDiscoveryDiagnostic("event=RETIRED_AUDIO_FAILURE epoch=$epoch code=$code")
            }
            return (audioFactory?.invoke(format, shared, failed) ?: AudioPlayer(format, shared, failed,
                onClockCorrection = { android.util.Log.i("ScreenCopyReceiver", it.logMessage()) },
                onOutputTiming = { android.util.Log.i("ScreenCopyReceiver", "event=AUDIO_OUTPUT_CLOCK stage=PCM_COMPENSATION ${it.logMessage()}") },
                onUnderrunRecovery = { android.util.Log.w("ScreenCopyReceiver", it.logMessage()) },
                onRegulation = { android.util.Log.i("ScreenCopyReceiver", it.logMessage()) },
                onServiceDiagnostic = { android.util.Log.i("ScreenCopyReceiver", "session=$session audio_epoch=$epoch $it") })).also {
                it.diagnosticSession = session
                it.diagnosticGeneration = generation.toInt()
                shared.diagnosticGeneration = generation.toInt()
                it.resetAudioStartEvidence()
                it.statisticsEnabled = request?.receiverOptions?.playbackStats == true
                it.recoveryCutoffUs = cutoff
                if (RemoteDiagnostics.recorder.enabled("receiver", session, DiagnosticKind.SESSION))
                    RemoteDiagnostics.recorder.observe("receiver", session, DiagnosticKind.SESSION,
                        DiagnosticStage.CONFIG, 2, generation.toInt(),
                        metadata = DiagnosticConfiguration(DiagnosticCodec.from(format.codec),
                            format.sampleRate, format.channels, audioPacketTiming?.prerollUs))
                RemoteDiagnostics.recorder.observe("receiver", session, DiagnosticKind.RECOVERY,
                    DiagnosticStage.CONFIG, 2, generation.toInt(), cutoff = cutoff ?: -1,
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

        private fun recordTelemetryPressure(pressure: QueuePressure, generation: Int) {
            TelemetryRegistry.event("receiver", session, DiagnosticKind.RECOVERY.ordinal + 1,
                DiagnosticStage.PRESSURE.ordinal, pressure.track, generation, pressure.spanUs,
                listOf(pressure.limit.ordinal.toLong(), pressure.queuedPackets.toLong(), pressure.queuedBytes.toLong(),
                    pressure.attemptedPackets.toLong(), pressure.attemptedBytes, pressure.maxPackets.toLong(),
                    pressure.maxBytes.toLong(), pressure.maxSpanUs, pressure.waitedMs), pressure = true)
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

        private fun sendTerminal(type: Type, reason: String, cleanupSucceeded: Boolean = true,
                                 errorCode: String? = null,
                                 skip: ReceiverTelemetrySession.FinalSkipReason? = null) {
            fun body() = if (type == Type.ERROR) JSONObject().put("code", errorCode)
                .put("message", reason).put("fatal", true) else JSONObject().put("reason", reason)
            if (!telemetryNegotiated) {
                synchronized(playbackLock) {
                    checkNotNull(writer).offerTerminalTransaction(type, null, now() + 2000,
                        { Message.json(type, body(), session) })
                }
                return
            }
            val deadlineNs = System.nanoTime() + TelemetryTerminalBudget.FINAL_TRANSACTION_NS
            val owner = telemetry
            var result: ReceiverTelemetrySession.FinalResult? = null
            checkNotNull(writer) { "TCP writer is not available" }.offerTerminalTransaction(
                    type, null, now() + TelemetryTerminalBudget.FINAL_TRANSACTION_NS / 1_000_000,
                    { sent -> Message.json(type, body().apply {
                        if (telemetryNegotiated) {
                            put("finalSeq", if (sent) result?.batch?.sequence?.toString() ?: JSONObject.NULL else JSONObject.NULL)
                            put("cleanupSucceeded", cleanupSucceeded)
                            if (!sent) put("finalSkipReason",
                                (result?.reason ?: if (result?.batch != null)
                                    ReceiverTelemetrySession.FinalSkipReason.WRITE_SKIPPED
                                else ReceiverTelemetrySession.FinalSkipReason.UNAVAILABLE).name)
                        }

                    }, session) }, bounded = true, prepareFinal = { discardedTelemetry ->
                        if (discardedTelemetry) owner?.publicationUnsent()
                        TelemetryRegistry.end("receiver", owner)
                        result = if (skip == null) owner?.requestFinalResult(cleanupSucceeded, deadlineNs) else {
                            owner?.requestFinal(false, System.nanoTime())
                            ReceiverTelemetrySession.FinalResult(null, skip)
                        }
                        result?.batch?.let { Message(Type.STATUS, session = session, payload = it.payload) }
                    })
        }

        private fun beginFatalRetirement(code: String, detail: String) {
            if (!fatalReply.compareAndSet(null, code to detail)) return
            if (!telemetryNegotiated) {
                try { send(Type.ERROR, JSONObject().put("code", code).put("message", detail).put("fatal", true)) }
                catch (error: Exception) {
                    onStatus("Connection cleanup failed", error.message ?: error.javaClass.simpleName)
                    closeTransport()
                }
                return
            }
            fatalCleanupDeadline = timer.schedule({
                try { completeFatalRetirement(false, ReceiverTelemetrySession.FinalSkipReason.UNAVAILABLE) }
                catch (error: Exception) {
                    onStatus("Connection cleanup failed", error.message ?: error.javaClass.simpleName)
                    closeTransport()
                    writer?.close()
                }
            }, TelemetryTerminalBudget.FATAL_CLEANUP_MS, TimeUnit.MILLISECONDS)
        }

        private fun completeFatalRetirement(cleanupSucceeded: Boolean,
                                            skip: ReceiverTelemetrySession.FinalSkipReason? = null) {
            synchronized(fatalAdmissionLock) {
                val reply = fatalReply.get() ?: return
                if (!telemetryNegotiated || !fatalTerminalClaimed.compareAndSet(false, true)) return
                fatalCleanupDeadline?.cancel(false)
                sendTerminal(Type.ERROR, reply.second, cleanupSucceeded, reply.first, skip)
            }
        }

        private fun reportFailure(code: String, detail: String, origin: FailureOrigin, pressure: QueuePressure? = null,
            outputPreparation: OutputPreparationDiagnostic? = null, videoDiagnostic: VideoDiagnostic? = null,
            failureGeneration: Int? = null, messageTrack: Int? = null) {
            if (active.get() === this) playbackStatistics = null
            val diagnostic = FailureDiagnostic.classify(code, origin, phase, detail, pressure, outputPreparation, videoDiagnostic)
            if (!firstFailure.compareAndSet(null, diagnostic)) return
            val failureTrack = messageTrack ?: when (origin) { FailureOrigin.AUDIO -> 2; FailureOrigin.VIDEO -> 1; else -> 0 }
            val causalGeneration = failureGeneration ?: if (failureTrack == 0) 0 else synchronized(playbackLock) {
                if (failureTrack == 1) videoGeneration.toInt() else audioGeneration.toInt()
            }
            pressure?.let { recordTelemetryPressure(it,
                (if (it.track == 1) videoGeneration else if (it.track == 2) audioGeneration else 0).toInt()) }
            TelemetryRegistry.event("receiver", session, DiagnosticKind.SESSION.ordinal + 1,
                DiagnosticStage.FAILED.ordinal, failureTrack, 0, IncidentEvidence.cause(code),
                listOf(origin.ordinal.toLong()), failure = true, causalGeneration = causalGeneration)
            RemoteDiagnostics.recorder.observe("receiver", session, DiagnosticKind.SESSION, DiagnosticStage.FAILED,
                track = when (origin) { FailureOrigin.AUDIO -> 2; FailureOrigin.VIDEO -> 1; else -> 0 },
                reason = DiagnosticReason.FAILURE)
            onFailure(diagnostic)
            onScreenEvent(ReceiverScreenEvent.Problem(code, diagnostic.reason, diagnostic.pressure))
        }

        private fun playbackFailed(code: String, reason: String, origin: FailureOrigin,
                                   generation: Long, epoch: Long? = null) {
            synchronized(retirementLock) {
                if (controlCleanupStarted) return
                synchronized(playbackLock) {
                    if (!accepted || active.get() !== this ||
                        if (origin == FailureOrigin.VIDEO)
                            generation != videoGeneration || generation <= retiredVideoThrough
                        else generation != audioGeneration ||
                            epoch != null && (epoch != audioEpoch || epoch <= retiredAudioThrough)) return
                    if (stopping.get() || !playbackError.compareAndSet(null, reason)) return
                    terminalError = true
                    stopAt = now()
                    stopping.set(true)
                }
                onStatus("Playback failed", "$reason\nReconnect after reducing sender load or fixing the output device.")
                val reportOrigin = if (code == "AUDIO_FAILED") FailureOrigin.AUDIO else origin
                if (active.get() === this) reportFailure(code, reason, reportOrigin,
                    failureGeneration = generation.toInt().takeIf { reportOrigin == origin })
                beginFatalRetirement(code, reason)
                retireFailedSession()
            }
        }

        private fun retireFailedSession() {
            closeMedia()
            if (!fatalWork.compareAndSet(FatalWork.NOT_STARTED, FatalWork.RUNNING)) return
            try {
                recoveryExecutor.execute {
                    var cleanupSucceeded = true
                    var interrupted = false
                    try {
                        stopPlayback()
                    } catch (error: Exception) {
                        cleanupSucceeded = false
                        if (running.get() && active.get() === this)
                            onStatus("Playback cleanup failed", error.message ?: error.javaClass.simpleName)
                        reportFailure("INTERNAL", "Playback cleanup failed", FailureOrigin.CLEANUP)
                    } finally {
                        recoveryScheduler.shutdown()
                        while (true) {
                            try {
                                if (recoveryScheduler.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)) break
                            } catch (_: InterruptedException) {
                                interrupted = true
                            }
                        }
                        cleanupSucceeded = cleanupSucceeded && !resourceRetirementFailed.get()
                        val retired = cleanupSucceeded
                        proxyRetired(retired, fatal = true)
                        try {
                            completeFatalRetirement(cleanupSucceeded)
                            writer?.close()
                        } catch (error: Exception) {
                            cleanupSucceeded = false
                            if (running.get() && active.get() === this)
                                onStatus("Connection cleanup failed", error.message ?: error.javaClass.simpleName)
                            reportFailure("INTERNAL", "Connection cleanup failed", FailureOrigin.CLEANUP)
                        } finally {
                            closeProxy()
                            closeTransport()
                            finishFatalCleanup(control = false, failed = !cleanupSucceeded, retired = retired)
                            if (interrupted) Thread.currentThread().interrupt()
                        }
                    }
                }
            } catch (error: java.util.concurrent.RejectedExecutionException) {
                onStatus("Session cleanup failed", "Playback retirement worker is unavailable")
                reportFailure("INTERNAL", "Playback retirement worker is unavailable", FailureOrigin.CLEANUP)
                finishFatalCleanup(control = false, failed = true)
            }
        }

        private fun finishFatalCleanup(control: Boolean, failed: Boolean = false, retired: Boolean = false) {
            synchronized(retirementLock) {
                if (control) controlCleanupFinished.countDown() else {
                    if (!fatalWork.compareAndSet(FatalWork.RUNNING,
                            if (retired) FatalWork.RETIRED else FatalWork.FAILED)) return
                }
                retirementFailed = retirementFailed || failed
                releaseOwnershipIfReady()
            }
        }

        fun receiverDestroyed() {
            try {
                if (fatalWork.get() != FatalWork.NOT_STARTED) {
                    closeTransport()
                    writer?.close()
                }
            } finally {
                synchronized(retirementLock) {
                    releaseOwnershipIfReady()
                }
            }
        }

        private fun finishOrdinaryCleanup(failed: Boolean) {
            synchronized(retirementLock) {
                retirementFailed = retirementFailed || failed
                controlCleanupFinished.countDown()
                releaseOwnershipIfReady()
            }
        }

        private fun releaseOwnershipIfReady() {
            if (ownershipReleased || controlCleanupFinished.count != 0L) return
            val work = fatalWork.get()
            val failedForReport = if (work != FatalWork.NOT_STARTED) {
                // A control timeout is not retirement. Failed retirement remains fenced until destruction.
                if (running.get() && work != FatalWork.RETIRED) return
                retirementFailed
            } else {
                if (running.get() && (ordinaryStopInProgress || resourceRetirementFailed.get())) return
                retirementFailed || resourceRetirementFailed.get()
            }
            ownershipReleased = true
            releaseSession(failedForReport)
        }

        private fun releaseSession(cleanupFailed: Boolean) {
            var error: Throwable? = null
            var fatalError: Error? = null
            fun record(e: Throwable) {
                if (error == null) error = e else if (error !== e) error?.addSuppressed(e)
                if (e is Error && fatalError == null) fatalError = e
            }
            fun attempt(action: () -> Unit) {
                try { action() } catch (e: Throwable) { record(e) }
            }
            try {
                if (active.get() === this) {
                    attempt { RemoteDiagnostics.recorder.sessionEnded("receiver", session) }
                    attempt { TelemetryRegistry.end("receiver", telemetry) }
                    attempt { telemetry?.requestFinal(false, System.nanoTime()) }
                }
                attempt { sessionLease?.close() }
                error?.let { failure ->
                    attempt { onStatus("Session cleanup failed", failure.message ?: failure.javaClass.simpleName) }
                    attempt { reportFailure("INTERNAL", "Session ownership cleanup failed", FailureOrigin.CLEANUP) }
                    try {
                        val details = listOf(failure, *failure.suppressed).joinToString("; ") {
                            "${it.javaClass.simpleName}: ${it.message ?: "no detail"}"
                        }
                        onDiscoveryDiagnostic("event=SESSION_CLEANUP_FAILED errors=$details")
                    } catch (e: Throwable) {
                        record(e)
                        try {
                            android.util.Log.e("ScreenCopyReceiver", "Session cleanup reporting failed", failure)
                        } catch (loggingError: Throwable) {
                            record(loggingError)
                            try { failure.printStackTrace() } catch (fallbackError: Throwable) { record(fallbackError) }
                        }
                    }
                }
            } finally {
                sessionLease = null
                val released = active.compareAndSet(this, null)
                if (released && running.get() && !cleanupFailed && error == null &&
                    !resourceRetirementFailed.get()) available()
            }
            fatalError?.let { throw it }
        }

        fun requestStop(reason: String) {
            if (active.get() === this) playbackStatistics = null
            synchronized(retirementLock) {
                if (controlCleanupStarted) return
                synchronized(playbackLock) {
                    if (stopping.get() || terminalError) return
                    receiverStopRequested = true
                    stopAt = now()
                    if (!stopping.compareAndSet(false, true)) {
                        receiverStopRequested = false
                        return
                    }
                }
                ordinaryStopInProgress = true
            }
            try {
                if (!telemetryNegotiated) {
                    try {
                        if (orderlyStop) socket.soTimeout = 0
                        sendTerminal(Type.STOP, reason)
                    } catch (e: Exception) {
                        onStatus("Disconnect error", "${e.message ?: e.javaClass.simpleName}; connection closed")
                        abort()
                    } finally {
                        if (!orderlyStop) closeMedia()
                        var cleanupSucceeded = true
                        try { stopPlayback() } catch (e: Exception) {
                            cleanupSucceeded = false
                            onStatus("Playback cleanup failed", "${e.message ?: e.javaClass.simpleName}; reopen receiver")
                            closeTransport()
                        } finally { proxyRetired(cleanupSucceeded, fatal = false) }
                    }
                    return
                }
                try {
                    if (orderlyStop) socket.soTimeout = 0
                    if (!orderlyStop) closeMedia()
                    var cleanupSucceeded = true
                    try {
                        stopPlaybackAndRecovery()
                    } catch (e: Exception) {
                        cleanupSucceeded = false
                        onStatus("Playback cleanup failed", "${e.message ?: e.javaClass.simpleName}; reopen receiver")
                        reportFailure("INTERNAL", "Playback cleanup failed", FailureOrigin.CLEANUP)
                    }
                    proxyRetired(cleanupSucceeded, fatal = false)
                    sendTerminal(Type.STOP, reason, cleanupSucceeded)
                } catch (e: Exception) {
                    onStatus("Disconnect error", "${e.message ?: e.javaClass.simpleName}; connection closed")
                    abort()
                }
            } finally {
                synchronized(retirementLock) {
                    ordinaryStopInProgress = false
                    if (fatalWork.get() == FatalWork.NOT_STARTED) releaseOwnershipIfReady()
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
                if (telemetryNegotiated) {
                    var cleanupSucceeded = true
                    try {
                        stopPlaybackAndRecovery()
                    } catch (e: Exception) {
                        cleanupSucceeded = false
                        onStatus("Playback cleanup failed", "${e.message ?: e.javaClass.simpleName}; reopen receiver")
                        reportFailure("INTERNAL", "Playback cleanup failed", FailureOrigin.CLEANUP)
                    }
                    proxyRetired(cleanupSucceeded, fatal = false)
                    proxyAfterRetirement()
                    sendTerminal(Type.SESSION_END, reason, cleanupSucceeded)
                } else {
                    var cleanupSucceeded = true
                    try { stopPlayback() } catch (e: Exception) {
                        cleanupSucceeded = false
                        throw e
                    } finally { proxyRetired(cleanupSucceeded, fatal = false) }
                    proxyAfterRetirement()
                    sendTerminal(Type.SESSION_END, reason)
                }
                proxyOrdinaryRetirementComplete = true
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
            retireResources {
                val installed = synchronized(playbackLock) { video to audio }
                try { installed.first?.halt() } finally { installed.second?.halt() }
                synchronized(trackPlaybackOperations[1]) {
                    synchronized(trackPlaybackOperations[2]) { retirePlayers() }
                }
            }
        }

        private fun stopPlaybackAndRecovery() {
            stopPlayback()
            recoveryScheduler.shutdown()
            recoveryExecutor.shutdown()
            retireResources {
                check(recoveryScheduler.awaitTermination(3, TimeUnit.SECONDS)) { "Track recovery workers did not retire" }
                check(recoveryExecutor.awaitTermination(3, TimeUnit.SECONDS)) { "Recovery worker did not retire" }
                check(!resourceRetirementFailed.get()) { "Playback resource retirement failed" }
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
            if (diagnosticLane) return
            if (proxyOffer != null && !proxyReady && requestReceivedNs > 0 &&
                System.nanoTime() - requestReceivedNs >= 5_000_000_000L) {
                proxyGate.set(false)
                closeTransport()
                return
            }
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
                    closeTransport()
                } else if (writer?.stalled(time) == true) {
                    mediaOwner?.mediaFailed(ProtocolException("TIMEOUT", "Media HELLO write blocked"))
                    closeTransport()
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
            if (telemetryNegotiated && fatalReply.get() != null) return
            val disconnected = mediaDisconnect.get()
            if (!stopping.get() && disconnected != null && time - disconnected.second >= 500) {
                terminateMediaFailure(disconnected.first)
                return
            }
            when {
                !stopping.get() && streamRecovery && (videoRecoveryAt > 0 && time - videoRecoveryAt >= 10_000 ||
                    audioRecoveryAt > 0 && time - audioRecoveryAt >= 10_000) -> {
                    val track = if (videoRecoveryAt > 0 && time - videoRecoveryAt >= 10_000) 1 else 2
                    TelemetryRegistry.event("receiver", session,
                        DiagnosticKind.RECOVERY.ordinal + 1, DiagnosticStage.FAILED.ordinal,
                        track, (if (track == 1) videoGeneration else audioGeneration).toInt(),
                        if (track == 1) videoRecoveryAt else audioRecoveryAt,
                        listOf(recoveryKeyPts ?: -1, time, 10_000, if (tracksReady) 1L else 0L,
                            if (videoRecovering) 1L else 0L, if (audioRecovering) 1L else 0L,
                            if (awaitingRecoveryKey) 1L else 0L, if (audioRecoveryAdmitted) 1L else 0L,
                            audioRecoveryCutoff, keyProvedGeneration, completedGeneration,
                            if (pendingVideoProof != null) 1L else 0L), failure = true)
                    val detail = "Track $track recovery produced no usable fresh media within 10 seconds"
                    if (sessionAccepted && telemetryNegotiated) {
                        terminateMediaFailure(ProtocolException("TIMEOUT", detail), detail)
                    } else {
                        connectionError.compareAndSet(null, "TIMEOUT" to detail)
                        closeTransport()
                    }
                }
                stopping.get() && time - stopAt >= 2000 -> closeTransport()
                progressExpired -> {
                    connectionError.compareAndSet(null, "TIMEOUT" to "No progress from sender within ${progressTimeout / 1000} seconds")
                    if (active.get() === this) onStatus("Session timed out", "No progress from sender within ${progressTimeout / 1000} seconds. Check its network and retry.")
                    closeTransport()
                }
                writer?.stalled(time) == true -> {
                    connectionError.compareAndSet(null, "TIMEOUT" to "Control write blocked for 10 seconds")
                    if (active.get() === this) onStatus("Connection stalled", "Control write blocked for 10 seconds; reconnect")
                    closeTransport()
                }
                writer != null && phase != "Setup" && time - lastWrite >= 2000 -> {
                    val nonce = (time % Int.MAX_VALUE).toInt()
                    controlStatistics.sentPing(nonce, time)
                    send(Type.PING, JSONObject().put("nonce", nonce))
                }
            }
        }

        fun abort() {
            fenceProxyControl()
            if (active.get() === this) playbackStatistics = null
            receiverStopRequested = false
            stopping.set(true)
            stopWritten.countDown()
            closeMedia()
            closeTransport()
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
            diagnosticSessionAccess: org.screencopy.receiver.protocol.DiagnosticSessionAccess? = null,
            proxyRecorder: DiagnosticRecorder = RemoteDiagnostics.recorder,
            proxyLaneReceived: (Long) -> Unit = {},
            proxyBeforeRead: () -> Unit = {},
            proxyBeforeReady: () -> Unit = {},
            proxyBeforeFetch: () -> Unit = {},
            proxyAfterRetirement: () -> Unit = {},
            proxyBeforeRetirementFlush: () -> Unit = {},
            proxyAfterOrdinaryCleanupFence: () -> Unit = {},
            incidentSave: () -> ((String, JSONObject) -> Unit) = { { _, _ -> } }) =
            ReceiverServer(name, null, onStatus, onVideoSize, onListener, port, videoOutput, validateRequest, onScreenEvent,
                onFailure, onPresentationStart, videoFactory, audioFactory, clockFactory, onVideoRotation = onVideoRotation,
                acquireSession = acquireSession, instanceId = instanceId, supportsPlaybackStatistics = supportsPlaybackStatistics,
                receiveCapabilities = receiveCapabilities, timeMilliseconds = timeMilliseconds, controlWrite = controlWrite,
                onDiscoveryDiagnostic = onDiscoveryDiagnostic, diagnosticDiscovery = diagnosticDiscovery,
                diagnosticSessionAccess = diagnosticSessionAccess, proxyRecorder = proxyRecorder,
                proxyLaneReceived = proxyLaneReceived, proxyBeforeRead = proxyBeforeRead, proxyBeforeReady = proxyBeforeReady,
                proxyBeforeFetch = proxyBeforeFetch, proxyAfterRetirement = proxyAfterRetirement,
                proxyBeforeRetirementFlush = proxyBeforeRetirementFlush,
                proxyAfterOrdinaryCleanupFence = proxyAfterOrdinaryCleanupFence, incidentSave = incidentSave)

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

package dev.screencopy.sender

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.screencopy.receiver.protocol.StreamRecoveryProtocol

/**
 * One consent-backed cast. Assign [projectionOwner] before running on a worker thread.
 * The service owns that projection across size switches and closes it after [finished].
 * Capture requires an explicitly advertised videoRotation capability; it never falls back
 * to a display-relative encoder raster for older receivers.
 */
class SenderSession internal constructor(
    private val context: Context, settings: Settings, private val receiver: ReceiverTarget,
    private val status: (String) -> Unit, private val finished: () -> Unit, private val diagnostic: (String) -> Unit
) : Runnable, ScrcpyAdapter.Sink {
    constructor(context: Context, settings: Settings, receiver: ReceiverTarget,
                status: (String) -> Unit, finished: () -> Unit) :
        this(context, settings, receiver, status, finished, {
            android.util.Log.i("ScreenCopySender", it)
            try { SenderFailureStore.recordDiagnostic(context, it) }
            catch (e: Exception) { android.util.Log.w("ScreenCopySender", "diagnostic_store=${e.javaClass.simpleName}") }
        })
    private var settings = Settings.from(settings.saved(), validate = false)
    private val requestedStatistics = settings.showPlaybackStatistics
    private var statisticsUnavailable = false
    private fun streamingStatus(value: String) = status(if (statisticsUnavailable)
        "$value - Playback statistics unavailable on this receiver" else value)
    private val session = (SecureRandom().nextLong() and Long.MAX_VALUE) or 1
    private val mux = MediaBudget(settings.audio)
    private val outcome = SessionOutcome()
    private val trace = SessionStopTrace(diagnostic)
    private val terminalQueued = AtomicBoolean()
    private val threads = mutableListOf<Thread>()
    private val terminalSent = CountDownLatch(1)
    private val receiverReleased = CountDownLatch(1)
    private val receiverTerminal = AtomicBoolean()
    private val sizeSwitch = SizeSwitchAuthorization()
    private val captureCleanup = Any()
    @Volatile private var linkClosed = false
    @Volatile private var startupTransportAborted = false
    @Volatile private var playing = false
    @Volatile private var cleanupComplete = false
    @Volatile private var cleanupSucceeded = true
    val cleanSizeSwitch get() = cleanupComplete &&
        sizeSwitch.permitsRestart(cleanupSucceeded && outcome.failure == null)
    private val timer = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var link: ReceiverLink? = null
    @Volatile private var backend: ProjectionCapture? = null
    @Volatile private var recovery: StreamRecoverySender? = null
    var projectionOwner: ProjectionOwner? = null
    private val stopping get() = outcome.stopping
    @Volatile private var requestSent = false
    @Volatile private var acceptedSession = false
    @Volatile private var remoteStop = false
    @Volatile private var closing = false
    @Volatile private var lastIncoming = 0L
    @Volatile private var writeStarted = 0L
    @Volatile private var phaseDeadline = 0L

    private fun phase(value: String, stage: SessionStopTrace.Phase) {
        trace.phase(stage)
        check(); status(value); phaseDeadline = now() + 10_000_000_000L
    }
    private fun check() {
        if (closing) throw SessionCancelledException()
        outcome.checkRunning()
    }
    @Synchronized fun stop() {
        sizeSwitch.cancel()
        requestStop()
    }
    @Synchronized fun stopForSizeSwitch(): Boolean {
        if (stopping || closing || !sizeSwitch.request(settings.videoCodec, playing && acceptedSession, outcome.failure != null))
            return false
        requestStop()
        return true
    }
    @Synchronized private fun requestStop() {
        if (!outcome.requestStop()) return
        trace.mark(SessionStopTrace.Stage.REQUESTED)
        status("Stopping")
        queueTerminal()
        if (!playing) Thread({
            trace.mark(SessionStopTrace.Stage.STARTUP_CANCEL)
            if (!playing) {
                if (requestSent) try { terminalSent.await(500, TimeUnit.MILLISECONDS) }
                catch (e: InterruptedException) { Thread.currentThread().interrupt(); fail(e, SessionOutcome.Origin.CLEANUP) }
                startupTransportAborted = true
                closeLink()
            }
            abortCapture()
        }, "cancel-capture-startup").start()
    }
    private fun fail(error: Exception, origin: SessionOutcome.Origin) {
        if (origin == SessionOutcome.Origin.CLEANUP) cleanupSucceeded = false
        val atFailure = trace.currentPhase()
        val classified = if (origin == SessionOutcome.Origin.CAPTURE && error !is SenderProtocolException &&
            SessionOutcome.errorCode(error) == "PROTOCOL") SenderProtocolException(CaptureProtocolFailure.unknown(error), error) else error
        val retained = outcome.record(classified, origin, closing)
        if (retained) sizeSwitch.cancel()
        if (retained) trace.firstFailure(classified, atFailure, origin, session, settings,
            lastReceiveAgeMs = lastIncoming.takeIf { it > 0 }?.let { ((now() - it) / 1_000_000).coerceAtLeast(0) },
            writeElapsedMs = writeStarted.takeIf { it > 0 }?.let { ((now() - it) / 1_000_000).coerceAtLeast(0) })
        trace.cause(origin, classified, retained, atFailure)
        if (retained && classified is SenderCongestionException) trace.congestion(classified.snapshot, atFailure)
        if (retained && classified is CaptureStreamEof) trace.startup(classified, atFailure,
            captureFormat(settings.videoCodec), captureFormat(settings.audioCodec), settings.audio, settings)
        if (retained && classified is VideoGeometryException) trace.geometry(classified, captureFormat(settings.videoCodec), atFailure)
        if (retained && classified is SenderProtocolException) {
            val evidence = classified.evidence
            val codec = when (evidence.track) {
                CaptureTrack.VIDEO -> settings.videoCodec
                CaptureTrack.AUDIO -> settings.audioCodec
                else -> ""
            }
            val format = when (codec) {
                "h264" -> CaptureFormat.H264; "h265" -> CaptureFormat.H265; "av1" -> CaptureFormat.AV1
                "opus" -> CaptureFormat.OPUS; "aac" -> CaptureFormat.AAC; "raw" -> CaptureFormat.PCM
                else -> CaptureFormat.UNKNOWN
            }
            trace.captureProtocol(evidence, format, atFailure, origin)
        }
    }
    fun captureFailed(error: Exception) = fail(error, SessionOutcome.Origin.CAPTURE)
    internal fun recordMediaFailure(error: Exception, origin: SessionOutcome.Origin) {
        val transport = error is java.io.EOFException || error is java.net.SocketException ||
            error is java.io.InterruptedIOException
        fail(if (transport && error is IOException) MediaConnectionFailure(error) else error, origin)
    }
    fun validateSizeSwitch(requested: Settings): Settings {
        check()
        val capture = backend ?: throw IOException("CAPTURE_FAILED: receiver capture mode is not negotiated")
        val resolved = capture.validate(requested)
        check()
        return resolved
    }
    internal fun terminalMessage(): Wire.Message = outcome.terminal(session, remoteStop)
    private fun queueTerminal() {
        if (requestSent && terminalQueued.compareAndSet(false, true)) {
            mux.finish(terminalMessage())
            trace.mark(SessionStopTrace.Stage.TERMINAL_QUEUED)
        }
    }

    override fun run() {
        try {
            trace.sessionStarted(session)
            settings.validate()
            timer.scheduleWithFixedDelay(::watchdog, 500, 500, TimeUnit.MILLISECONDS)
            phase("Checking screen-sharing permission", SessionStopTrace.Phase.ACCESS)
            val owner = projectionOwner ?: throw IOException("CAPTURE_FAILED: screen-sharing consent is required")
            phase("ConnectingReceiver", SessionStopTrace.Phase.CONNECTING)
            val connected = ReceiverLink(receiver).also { link = it }
            check()
            connected.connect()
            lastIncoming = now()
            trace.phase(SessionStopTrace.Phase.CAPABILITIES)
            connected.requireVideoRotation()
            connected.requireFpsSupport(settings.maxFps)
            if (connected.streamRecovery)
                recovery = StreamRecoverySender(session, mux, diagnostic = { line ->
                    diagnostic(line)
                    if (line.startsWith("event=STREAM_RECOVERY_BEGIN")) status("Recovering stream")
                    if (line.startsWith("event=STREAM_RECOVERY_COMPLETE")) streamingStatus("Streaming")
                }, withAudio = settings.audio)
            diagnostic("event=STREAM_RECOVERY_NEGOTIATED enabled=${connected.streamRecovery}")
            val capture = ProjectionCapture(context, owner, ::captureFailed).also { backend = it }
            settings = capture.validate(settings)
            diagnostic("event=VIDEO_ROTATION_NEGOTIATED fixed_natural=${connected.videoRotation}")
            phase("Negotiating", SessionStopTrace.Phase.NEGOTIATING)
            val options = connected.receiverOptions.select(requestedStatistics)
            statisticsUnavailable = requestedStatistics && !options.playbackStats
            if (statisticsUnavailable)
                diagnostic("event=RECEIVER_OPTION_UNAVAILABLE option=playbackStats nonfatal=true")
            val request = org.screencopy.receiver.protocol.ReceiverOptionProtocol.apply(settings.request(), options)
            connected.send(Wire.control(Wire.SESSION_REQUEST, session, request))
            worker("receiver-control-write") { writeLoop(0) }
            requestSent = true
            if (stopping) queueTerminal()
            val accepted = connected.receive()
            lastIncoming = now()
            if (accepted.session != session) throw IOException("PROTOCOL: negotiation session mismatch")
            if (accepted.type == Wire.REJECT || accepted.type == Wire.ERROR)
                throw IOException("${Json.string(accepted.json(), "code")}: ${Json.string(accepted.json(), "message")}")
            check()
            acceptedSession = true
            worker("receiver-read", ::readLoop)
            connected.attach(session, accepted, settings.audio)
            check()
            for (track in 1..2) {
                worker("receiver-media-write-$track") { writeLoop(track) }
                worker("receiver-media-read-$track") {
                    try { connected.watchMedia(track) }
                    catch (e: Exception) { recordMediaFailure(e, SessionOutcome.Origin.RECEIVER_READ) }
                }
            }
            phase("StartingCapture", SessionStopTrace.Phase.CAPTURE_START)
            capture.start(settings, session, this)
            while (!stopping && outcome.failure == null) {
                if (mux.allReady()) phaseDeadline = 0
                Thread.sleep(100)
            }
        } catch (e: Exception) {
            fail(e, SessionOutcome.Origin.STARTUP)
            if (e is InterruptedException) Thread.currentThread().interrupt()
        } finally {
            retireTransport()
            timer.shutdownNow()
            threads.forEach(Thread::interrupt)
            for (thread in threads) if (thread != Thread.currentThread()) {
                try {
                    thread.join(1000)
                    if (thread.isAlive) fail(IOException("Session reader did not stop"), SessionOutcome.Origin.CLEANUP)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    fail(IOException("Session reader cleanup interrupted", e), SessionOutcome.Origin.CLEANUP)
                }
            }
            trace.mark(SessionStopTrace.Stage.READERS_JOINED)
            status(outcome.failure?.let { "Error: ${SessionOutcome.safeMessage(it)}" } ?: "Idle")
            trace.mark(SessionStopTrace.Stage.FINISHED)
            cleanupComplete = true
            finished()
        }
    }

    internal fun retireTransport() {
        synchronized(this) { closing = true }
        trace.phase(SessionStopTrace.Phase.CLEANUP)
        phaseDeadline = now() + 2_000_000_000L
        status("Stopping")
        trace.mark(SessionStopTrace.Stage.CLOSING)
        queueTerminal()
        closeCapture()
        try {
            if (requestSent) terminalSent.await(minOf(500_000_000L,
                (phaseDeadline - now()).coerceAtLeast(0)), TimeUnit.NANOSECONDS)
            try { link?.closeMedia() } catch (error: IOException) { fail(error, SessionOutcome.Origin.CLEANUP) }
            if (acceptedSession) {
                val drained = receiverReleased.await((phaseDeadline - now()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
                if (!drained) fail(IOException("TIMEOUT: receiver terminal response did not arrive within two seconds"),
                    SessionOutcome.Origin.CLEANUP)
                else if (!receiverTerminal.get() && !startupTransportAborted && outcome.failure == null)
                    fail(IOException("PROTOCOL: receiver closed control before terminal acknowledgement"),
                        SessionOutcome.Origin.CLEANUP)
                if (sizeSwitch.requested && !sizeSwitch.receiverClosed)
                    fail(IOException("TIMEOUT: size change receiver cleanup was not acknowledged"),
                        SessionOutcome.Origin.CLEANUP)
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            fail(IOException("Control teardown interrupted", error), SessionOutcome.Origin.CLEANUP)
        } finally {
            trace.mark(SessionStopTrace.Stage.RECEIVER_DRAIN_DONE)
            closeLink()
        }
    }

    private fun watchdog() {
        val current = now()
        if (closing) {
            // Bound terminal TCP delivery without interrupting independently bounded codec cleanup.
            if (current > phaseDeadline) {
                if (sizeSwitch.requested && !sizeSwitch.receiverClosed)
                    fail(IOException("TIMEOUT: size change receiver cleanup was not acknowledged"), SessionOutcome.Origin.CLEANUP)
                closeLink()
            }
            return
        }
        if (stopping) return
        try {
            recovery?.checkDeadline()
            if (recovery?.retryKeyFrame() == true) checkNotNull(backend).requestKeyFrame()
        } catch (error: IOException) {
            fail(error, SessionOutcome.Origin.WATCHDOG)
            closeLink(); abortCapture(); return
        }
        if (phaseDeadline > 0 && current > phaseDeadline || lastIncoming > 0 && current - lastIncoming > 10_000_000_000L ||
            writeStarted > 0 && current - writeStarted > 10_000_000_000L) {
            fail(IOException("TIMEOUT: receiver/capture did not make progress within 10 seconds"), SessionOutcome.Origin.WATCHDOG)
            closeLink(); abortCapture(); return
        }
        mux.expiredTrack()?.let { track ->
            fail(IOException("TIMEOUT: receiver did not initialize track $track"), SessionOutcome.Origin.WATCHDOG); closeLink(); abortCapture()
        }
    }
    private fun abortCapture() {
        trace.mark(SessionStopTrace.Stage.STARTUP_BRIDGE_CLOSE_BEGIN)
        try { closeCapture() }
        finally { trace.mark(SessionStopTrace.Stage.STARTUP_BRIDGE_CLOSE_END) }
    }
    private fun closeCapture() = synchronized(captureCleanup) {
        try { backend?.close() } catch (e: Exception) { fail(e, SessionOutcome.Origin.CLEANUP) }
    }
    private fun closeLink() {
        linkClosed = true
        try { link?.close() } catch (e: IOException) { fail(e, SessionOutcome.Origin.CLEANUP) }
        trace.mark(SessionStopTrace.Stage.LINK_CLOSED)
    }
    private fun worker(name: String, action: () -> Unit) { threads.add(Thread(action, name).also { it.start() }) }
    private fun readLoop() {
        try {
            val connected = checkNotNull(link) { "Receiver connection missing" }
            while (!linkClosed) {
                val message = connected.receive()
                lastIncoming = now()
                if (message.session != session) throw IOException("PROTOCOL: wrong session")
                val json = message.json()
                if (closing && message.type !in setOf(Wire.ERROR, Wire.STOP, Wire.SESSION_END)) continue
                when (message.type) {
                    Wire.STREAM_READY -> {
                        if (!Json.bool(json, "ok")) throw IOException("DECODER_FAILED: track not ready")
                        mux.ready(message.track, message.generation)
                        if (recovery?.ready() == true) checkNotNull(backend).requestKeyFrame()
                        if (mux.allReady() && !stopping && !closing && outcome.failure == null) {
                            phaseDeadline = 0
                            playing = true
                            trace.phase(SessionStopTrace.Phase.STREAMING)
                            streamingStatus(if (recovery == null) "Streaming" else "Resynchronizing stream")
                        }
                    }
                    Wire.PING -> {
                        val nonce = Json.integer(json, "nonce")
                        if (!stopping && !closing) try {
                            mux.offer(Wire.control(Wire.PONG, session, Wire.obj("nonce" to nonce)))
                        } catch (e: SessionCancelledException) {
                            if (!stopping && !closing) throw e
                        }
                    }
                    Wire.PONG -> Json.integer(json, "nonce")
                    Wire.STATUS -> {
                        val state = Json.string(json, "state")
                        val detail = Json.string(json, "message")
                        if (state.length > 80 || detail.length > 2048)
                            throw IOException("PROTOCOL: oversized STATUS fields")
                        if (state in setOf(StreamRecoveryProtocol.REQUEST, StreamRecoveryProtocol.KEY, StreamRecoveryProtocol.COMPLETE)) {
                            val current = recovery ?: throw IOException("PROTOCOL: stream recovery was not negotiated")
                            val track = StreamRecoveryProtocol.track(json)
                            val generation = Json.integer(json, "generation")
                            if (track == 2 && !settings.audio || track == 1 && json.has("audioPtsUs") ||
                                track == 2 && json.has("keyPtsUs"))
                                throw IOException("PROTOCOL: recovery proof does not match its track")
                            when (state) {
                                StreamRecoveryProtocol.REQUEST -> current.request(track, generation)
                                StreamRecoveryProtocol.KEY -> {
                                    if (track != 1) throw IOException("PROTOCOL: audio cannot acknowledge a video key")
                                    current.keyProved(generation, StreamRecoveryProtocol.timestamp(json, "keyPtsUs"))
                                }
                                StreamRecoveryProtocol.COMPLETE -> current.completed(track, generation,
                                    StreamRecoveryProtocol.timestamp(json, if (track == 1) "keyPtsUs" else "audioPtsUs"))
                            }
                        }
                    }
                    Wire.ERROR -> {
                        val code = Json.string(json, "code"); val text = Json.string(json, "message")
                        val error = ReceiverReportedFailure(code, text)
                        if (Json.bool(json, "fatal")) {
                            receiverTerminal.set(true)
                            diagnostic("receiver_session_error ${Wire.obj("code" to code, "fatal" to true)}")
                            throw error
                        }
                        status("Receiver warning: $code: $text")
                    }
                    Wire.SESSION_END -> {
                        Json.string(json, "reason")
                        receiverTerminal.set(true)
                        if (sizeSwitch.requested) sizeSwitch.acknowledge()
                        else { remoteStop = true; stop(); return }
                    }
                    Wire.STOP -> {
                        Json.string(json, "reason"); receiverTerminal.set(true); remoteStop = true; stop(); return
                    }
                    else -> throw IOException("PROTOCOL: unexpected receiver message ${message.type}")
                }
            }
        } catch (e: Exception) {
            if (sizeSwitch.requested && !linkClosed) {
                if (e is java.io.EOFException && sizeSwitch.acknowledged) sizeSwitch.peerClosed()
                else fail(IOException("Size change lost receiver before confirmed cleanup", e), SessionOutcome.Origin.RECEIVER_READ)
            } else fail(e, SessionOutcome.Origin.RECEIVER_READ)
        } finally { receiverReleased.countDown() }
    }
    @Synchronized private fun writeCompleted(message: Wire.Message) {
        if (!stopping && !closing) recovery?.written(message)
    }

    private fun writeLoop(lane: Int) {
        var ping = 0L
        try {
            val connected = checkNotNull(link) { "Receiver connection missing" }
            while (!Thread.currentThread().isInterrupted) {
                val time = now()
                val message = if (lane == 0 && time - ping > 2_000_000_000L && !closing && !stopping && acceptedSession) {
                    ping = time
                    Wire.control(Wire.PING, session, Wire.obj("nonce" to (time and 0x7fffffff).toInt()))
                } else mux.pollLane(lane, 100)
                if (message != null) {
                    if (lane == 0) writeStarted = now()
                    mux.beginWrite(message)
                    try {
                        connected.send(message)
                        writeCompleted(message)
                    } finally {
                        mux.endWrite(message)
                        if (lane == 0) writeStarted = 0
                    }
                    if (message.type in setOf(Wire.STOP, Wire.SESSION_END, Wire.ERROR)) {
                        trace.mark(SessionStopTrace.Stage.TERMINAL_SENT); terminalSent.countDown(); return
                    }
                }
                if (closing && terminalSent.count == 0L) return
            }
        } catch (e: Exception) {
            if (lane == 0) fail(e, SessionOutcome.Origin.RECEIVER_WRITE)
            else recordMediaFailure(e, SessionOutcome.Origin.RECEIVER_WRITE)
            if (lane == 0) terminalSent.countDown()
        }
    }
    override fun config(track: Int, generation: Int, config: JSONObject) {
        check()
        val current = recovery
        if (current == null) mux.offer(Wire.config(session, track, generation, config))
        else current.config(track, generation, config)
        status(if (track == 1) "Preparing video" else "Preparing audio")
    }
    override fun packet(packet: Wire.Message) {
        check()
        val current = recovery
        if (current == null) mux.offer(packet) else current.packet(packet)
    }
    override fun awaitVideoDrain() {
        check()
        mux.awaitVideoDrain()
        check()
    }

    companion object {
        private fun now() = System.nanoTime()
    }
}

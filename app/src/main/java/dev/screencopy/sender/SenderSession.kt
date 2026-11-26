package dev.screencopy.sender

import android.content.Context
import dev.redrain.cast.diagnostics.*
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.screencopy.receiver.protocol.ProtocolException
import org.screencopy.receiver.protocol.StreamRecoveryProtocol

internal fun setupFailure(error: Exception, setupTimedOut: Boolean): DiagnosticArmRegistry.SetupFailure {
    val cause = (error as? DiagnosticProxySetupException)?.cause as? Exception ?: error
    val remote = cause as? ReceiverReportedFailure
    val timeout = cause is java.net.SocketTimeoutException || SessionOutcome.errorCode(cause) == "TIMEOUT" ||
        (setupTimedOut && cause is java.net.SocketException)
    val code = if (remote != null) remote.code else if (timeout) "TIMEOUT" else SessionOutcome.errorCode(cause)
    val reason = when {
        remote != null -> DiagnosticArmRegistry.SetupReason.receiver_reported
        timeout -> DiagnosticArmRegistry.SetupReason.timeout
        cause is java.io.EOFException -> DiagnosticArmRegistry.SetupReason.eof
        cause is java.net.ConnectException || cause is java.net.NoRouteToHostException ||
            cause is java.net.UnknownHostException -> DiagnosticArmRegistry.SetupReason.connect
        else -> DiagnosticArmRegistry.SetupReason.setup
    }
    return DiagnosticArmRegistry.SetupFailure(DiagnosticArmRegistry.SetupCode.valueOf(code),
        if (remote != null) DiagnosticArmRegistry.SetupProvenance.receiver_control
        else DiagnosticArmRegistry.SetupProvenance.sender_local, reason)
}

internal fun finishSenderDiagnostic(
    recorder: DiagnosticRecorder, arms: DiagnosticArmRegistry, claim: DiagnosticArmRegistry.Claim?,
    session: Long, failed: Boolean, retire: () -> Unit, dispatch: (() -> Unit) -> Unit
) {
    recorder.observe("sender", session, DiagnosticKind.SESSION,
        if (failed) DiagnosticStage.FAILED else DiagnosticStage.ENDED,
        reason = if (failed) DiagnosticReason.FAILURE else DiagnosticReason.STOPPED)
    recorder.sessionEnded("sender", session)
    if (claim != null) {
        claim.senderFinished.set(true)
        retire()
        dispatch {
            try { arms.senderEnded(claim) }
            catch (_: DiagnosticError) { /* Revocation already fenced this arm. */ }
        }
    }
}

/**
 * One consent-backed cast. Assign [projectionOwner] before running on a worker thread.
 * The service owns that projection across size switches and closes it after [finished].
 * Capture requires an explicitly advertised videoRotation capability; it never falls back
 * to a display-relative encoder raster for older receivers.
 */
class SenderSession internal constructor(
    private val context: Context, settings: Settings, private val receiver: ReceiverTarget,
    private val status: (String) -> Unit, private val finished: () -> Unit, private val diagnostic: (String) -> Unit,
    private val diagnosticSetupTimeoutNs: Long = 5_000_000_000L,
    private val diagnosticBeforeReadyCommit: () -> Unit = {},
    private val diagnosticAfterReadyCheck: () -> Unit = {}
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
    private val outcomePublication = Any()
    private val trace = SessionStopTrace(diagnostic)
    private var frameTrace: VideoFrameTrace? = null
    private val terminalQueued = AtomicBoolean()
    private val threads = mutableListOf<Thread>()
    private val terminalSent = CountDownLatch(1)
    private val receiverReleased = CountDownLatch(1)
    private val receiverTerminal = AtomicBoolean()
    private val sizeSwitch = SizeSwitchAuthorization()
    private val captureCleanup = Any()
    private val captureAbortStarted = AtomicBoolean()
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
    @Volatile private var telemetryNegotiated = false
    @Volatile private var telemetry: SenderTelemetrySession? = null
    @Volatile private var proxyClaim: DiagnosticArmRegistry.Claim? = null
    @Volatile private var proxyClient: DiagnosticProxyClient? = null
    @Volatile private var proxyEstablished = false
    var projectionOwner: ProjectionOwner? = null
    private val stopping get() = outcome.stopping
    @Volatile private var requestSent = false
    @Volatile private var acceptedSession = false
    private var receiverReaderStarted = false
    @Volatile private var remoteStop = false
    @Volatile private var closing = false
    @Volatile private var mediaRetiring = false
    @Volatile private var terminalWriting = false
    @Volatile private var lastIncoming = 0L
    @Volatile private var writeStarted = 0L
    @Volatile private var phaseDeadline = 0L
    @Volatile private var terminalWrittenAt: Long? = null
    @Volatile private var fatalTeardown = false

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
        val committed = synchronized(outcomePublication) {
            outcome.requestStop { proxyClaim?.commitTerminal(now()) }
        }
        if (!committed) return
        if (!proxyEstablished) {
            val client = proxyClient
            if (client != null) try { timer.execute { client.cancel("startup_cancelled") } }
            catch (_: java.util.concurrent.RejectedExecutionException) { }
        }
        trace.mark(SessionStopTrace.Stage.REQUESTED)
        status("Stopping")
        queueTerminal()
        val orderlyRemoteStop = remoteStop && acceptedSession && link?.streamRecovery == true && link?.mediaAttached == true
        if (!playing) startCaptureAbort {
            trace.mark(SessionStopTrace.Stage.STARTUP_CANCEL)
            if (!playing && !orderlyRemoteStop) {
                if (requestSent) try { terminalSent.await(500, TimeUnit.MILLISECONDS) }
                catch (e: InterruptedException) { Thread.currentThread().interrupt(); fail(e, SessionOutcome.Origin.CLEANUP) }
                startupTransportAborted = true
                closeLink()
            }
        }
    }
    private fun fail(error: Exception, origin: SessionOutcome.Origin) {
        if (origin == SessionOutcome.Origin.CLEANUP) cleanupSucceeded = false
        val atFailure = trace.currentPhase()
        val classified = if (origin == SessionOutcome.Origin.CAPTURE && error !is SenderProtocolException &&
            SessionOutcome.errorCode(error) == "PROTOCOL") SenderProtocolException(CaptureProtocolFailure.unknown(error), error) else error
        val retained = synchronized(outcomePublication) {
            outcome.record(classified, origin, closing, commit = {
                if (origin != SessionOutcome.Origin.RECEIVER_READ)
                    proxyClaim?.commitTerminal(now())
            }).also { accepted ->
                if (accepted) {
                    TelemetryRegistry.event("sender", session, DiagnosticKind.SESSION.ordinal + 1,
                        DiagnosticStage.FAILED.ordinal, (classified as? RecoveryDeadlineException)?.track ?: 0,
                        (classified as? RecoveryDeadlineException)?.generation ?: 0,
                        IncidentEvidence.cause(SessionOutcome.errorCode(classified)),
                        listOf(atFailure.ordinal.toLong(), origin.ordinal.toLong()), failure = true)
                    TelemetryRegistry.state("sender", session, 60, DiagnosticKind.SESSION.ordinal + 1,
                        DiagnosticStage.SETTLED.ordinal, (classified as? RecoveryDeadlineException)?.track ?: 0,
                        (classified as? RecoveryDeadlineException)?.generation ?: 0,
                        IncidentEvidence.cause(SessionOutcome.errorCode(classified)),
                        listOf(atFailure.ordinal.toLong(), origin.ordinal.toLong()))
                }
            }
        }
        if (retained) sizeSwitch.cancel()
        if (origin == SessionOutcome.Origin.CLEANUP) TelemetryRegistry.state("sender", session, 63,
            DiagnosticKind.SESSION.ordinal + 1, DiagnosticStage.FAILED.ordinal, 0, 0,
            IncidentEvidence.cause(SessionOutcome.errorCode(classified)),
            listOf(atFailure.ordinal.toLong(), origin.ordinal.toLong()))
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
        if (origin == SessionOutcome.Origin.RECEIVER_READ && error is java.io.EOFException &&
            terminalWriting && link?.streamRecovery == true) {
            try {
                if (!terminalSent.await(22, TimeUnit.SECONDS)) {
                    fail(IOException("TIMEOUT: terminal write did not finish after media EOF", error), origin)
                    closeLink()
                    return
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                fail(IOException("Media EOF classification interrupted", interrupted), origin)
                closeLink()
                return
            }
        }
        if ((stopping || closing) && !linkClosed && !mediaRetiring && outcome.failure == null && link?.streamRecovery == true &&
            error is IOException) {
            fail(IOException("${if (error is java.net.SocketTimeoutException) "TIMEOUT" else "PROTOCOL"}: " +
                "media transport failed during orderly Stop", error), origin)
            closeLink()
            return
        }
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
            mux.finish(terminalMessage(), drainStartedMedia = orderlyTeardown)
            trace.mark(SessionStopTrace.Stage.TERMINAL_QUEUED)
        }
    }

    override fun run() {
        mux.diagnosticSession = session
        var setupStage = "hello"
        val setupTimedOut = AtomicBoolean()
        try {
            val armed = DiagnosticAccess.arms.claim(session)
            proxyClaim = armed
            if (armed == null) RemoteDiagnostics.recorder.sessionStarted("sender", session)
            trace.sessionStarted(session)
            if (dev.redrain.cast.BuildConfig.DEBUG &&
                android.util.Log.isLoggable("RedrainVideoTrace", android.util.Log.DEBUG)) {
                val longWindow = android.util.Log.isLoggable("RedrainVideoTraceLong", android.util.Log.DEBUG)
                val durationNs = if (longWindow) VideoFrameTrace.LONG_DURATION_NS else 30_000_000_000L
                val capacity = if (longWindow) VideoFrameTrace.LONG_CAPACITY else 16_384
                frameTrace = VideoFrameTrace(true, session, capacity = capacity, durationNs = durationNs)
                diagnostic("event=VIDEO_FRAME_TRACE_ENABLED session=$session duration_ms=${durationNs / 1_000_000} capacity=$capacity")
            }
            settings.validate()
            startWatchdog()
            phase("Checking screen-sharing permission", SessionStopTrace.Phase.ACCESS)
            val owner = projectionOwner ?: throw IOException("CAPTURE_FAILED: screen-sharing consent is required")
            phase("ConnectingReceiver", SessionStopTrace.Phase.CONNECTING)
            val connected = ReceiverLink(receiver).also { link = it }
            if (armed != null) timer.scheduleWithFixedDelay({
                if (!armed.valid.get()) {
                    proxyClient?.cancel("capture_ended")
                    if (!armed.bootstrapAccepted.get()) connected.close()
                }
            }, 0, 100, TimeUnit.MILLISECONDS)
            check()
            if (armed == null) connected.connect()
            else connected.connectArmed(10_000, armed) { DiagnosticAccess.arms.remainingMs(armed) }
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
            val capture = ProjectionCapture(context, owner, ::captureFailed, frameTrace).also { backend = it }
            settings = capture.validate(settings)
            diagnostic("event=VIDEO_ROTATION_NEGOTIATED fixed_natural=${connected.videoRotation}")
            phase("Negotiating", SessionStopTrace.Phase.NEGOTIATING)
            val options = connected.receiverOptions.select(requestedStatistics)
            statisticsUnavailable = requestedStatistics && !options.playbackStats
            if (statisticsUnavailable)
                diagnostic("event=RECEIVER_OPTION_UNAVAILABLE option=playbackStats nonfatal=true")
            val request = org.screencopy.receiver.protocol.ReceiverOptionProtocol.apply(settings.request(), options)
            connected.selectSessionTelemetry(request)
            if (armed != null) connected.selectDiagnosticProxy(request, armed)
            val proxyDeadline = if (armed != null) minOf(now() + diagnosticSetupTimeoutNs, phaseDeadline, armed.expiresNs) else 0
            setupStage = "request"
            val setupTimeout = if (armed != null) timer.schedule({
                val expired = synchronized(outcomePublication) {
                    if (armed.bootstrapAccepted.get()) false else {
                        setupTimedOut.set(true)
                        true
                    }
                }
                if (expired) {
                    proxyClient?.cancel("setup_timeout")
                    connected.close()
                }
            }, (proxyDeadline - now()).coerceAtLeast(0), TimeUnit.NANOSECONDS) else null
            connected.send(Wire.control(Wire.SESSION_REQUEST, session, request))
            worker("receiver-control-write") { writeLoop(0) }
            requestSent = true
            if (stopping) queueTerminal()
            val accepted = connected.receive()
            lastIncoming = now()
            if (accepted.session != session) throw IOException("PROTOCOL: negotiation session mismatch")
            if (accepted.type == Wire.REJECT || accepted.type == Wire.ERROR)
                throw ReceiverReportedFailure(Json.string(accepted.json(), "code"), Json.string(accepted.json(), "message"))
            val acceptedJson = org.screencopy.receiver.protocol.StrictJson.decode(accepted.payload)
            telemetryNegotiated = connected.acceptedSessionTelemetry(acceptedJson)
            try {
                telemetry = SenderTelemetrySession(telemetryNegotiated, IncidentStore(context),
                    java.util.UUID.randomUUID().toString().replace("-", ""),
                    { error -> android.util.Log.w("SessionTelemetry", "telemetry_failed=${error.javaClass.simpleName}") })
                    .also { TelemetryRegistry.beginSender(session, it) }
                TelemetryRegistry.sessionConfiguration("sender", session, settings.videoCodec,
                    settings.audioCodec.takeIf { settings.audio }, settings.maxFps,
                    settings.videoBitrate, settings.audioBitrate.takeIf { settings.audio && settings.audioCodec != "raw" })
            } catch (e: Exception) {
                android.util.Log.w("SessionTelemetry", "telemetry_start_failed=${e.javaClass.simpleName}")
            }
            check()
            acceptedSession = true
            if (armed != null) {
                startReceiverReader()
                setupStage = "lane"
                val selection = connected.acceptedDiagnosticProxy(acceptedJson, armed)
                check()
                val client = DiagnosticProxyClient(receiver, DiagnosticAccess.arms, armed, timer,
                    beforeReadyCommit = diagnosticBeforeReadyCommit, startupStopped = { outcome.stopping })
                proxyClient = client
                check()
                client.start(selection, proxyDeadline, commitReady = { commit ->
                    synchronized(outcomePublication) {
                        if (setupTimedOut.get()) throw java.net.SocketTimeoutException("TIMEOUT: diagnostic setup expired")
                        check()
                        diagnosticAfterReadyCheck()
                        commit()
                        DiagnosticAccess.arms.beginMedia(armed)
                        proxyEstablished = true
                    }
                }) { DiagnosticAccess.arms.remainingMs(armed) }
                check()
                setupTimeout?.cancel(false)
                check()
            }
            attachReceiver(connected, accepted)
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
            synchronized(outcomePublication) {
                fail(e, SessionOutcome.Origin.STARTUP)
                proxyClaim?.let { claim ->
                    if (!proxyEstablished) {
                        val stage = (e as? DiagnosticProxySetupException)?.stage
                            ?: if (e.message?.contains("UNSUPPORTED_CONFIG") == true) "unsupported" else setupStage
                        val failure = outcome.failure
                        if (failure != null) RemoteDiagnostics.recorder.observe("sender", session,
                            DiagnosticKind.SESSION, DiagnosticStage.FAILED, reason = DiagnosticReason.FAILURE)
                        try {
                            DiagnosticAccess.arms.setupFailed(claim, stage,
                                failure?.let { setupFailure(it, setupTimedOut.get() && it === e) })
                        } catch (_: DiagnosticError) { /* Revocation already fenced this arm. */ }
                    }
                }
            }
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
            dumpVideoTrace(force = true)
            status(outcome.failure?.let { "Error: ${SessionOutcome.safeMessage(it)}" } ?: "Idle")
            trace.mark(SessionStopTrace.Stage.FINISHED)
            cleanupComplete = true
            finishSenderDiagnostic(RemoteDiagnostics.recorder, DiagnosticAccess.arms, proxyClaim, session,
                outcome.failure != null, { proxyClient?.retire() }, DiagnosticAccess::dispatch)
            TelemetryRegistry.end("sender", telemetry)
            telemetry?.finish(cleanupSucceeded)
            finished()
        }
    }

    internal fun attachReceiver(connected: ReceiverLink, accepted: Wire.Message) {
        connected.attach(session, accepted, settings.audio,
            onFailure = { fail(it, SessionOutcome.Origin.STARTUP) }) {
            startReceiverReader()
        }
    }

    private fun startReceiverReader() = synchronized(this) {
        if (!closing && !receiverReaderStarted) {
            worker("receiver-read", ::readLoop)
            receiverReaderStarted = true
        }
    }

    internal fun retireTransport() {
        trace.phase(SessionStopTrace.Phase.CLEANUP)
        val orderly = orderlyTeardown
        val fatal = negotiatedFatalCleanup && terminalMessage().type == Wire.ERROR
        val timeoutNanos = when {
            orderly -> 22_000_000_000L
            fatal -> TelemetryTerminalBudget.CONTROL_FRAME_NS
            else -> 2_000_000_000L
        }
        synchronized(this) {
            fatalTeardown = fatal
            phaseDeadline = if (orderly && !remoteStop && terminalWrittenAt != null)
                checkNotNull(terminalWrittenAt) + 2_000_000_000L else now() + timeoutNanos
            proxyClaim?.capTerminalDeadline(phaseDeadline)
            closing = true
        }
        status("Stopping")
        trace.mark(SessionStopTrace.Stage.CLOSING)
        queueTerminal()
        closeCapture()
        try {
            if (requestSent) {
                val delivered = terminalSent.await(
                    if (orderly || fatal) (retirementDeadline() - now()).coerceAtLeast(0) else minOf(500_000_000L,
                        (phaseDeadline - now()).coerceAtLeast(0)), TimeUnit.NANOSECONDS)
                if ((orderly || fatal) && !delivered)
                    fail(IOException(if (fatal) "TIMEOUT: fatal terminal frame did not finish within 10 seconds"
                        else "TIMEOUT: orderly terminal frame did not finish within 22 seconds"),
                        SessionOutcome.Origin.CLEANUP)
            }
            if (orderly && !remoteStop) terminalWrittenAt?.let { phaseDeadline = it + 2_000_000_000L }
            mediaRetiring = true
            try { link?.closeMedia() } catch (error: IOException) { fail(error, SessionOutcome.Origin.CLEANUP) }
            if (receiverReaderStarted) {
                val drained = receiverReleased.await((retirementDeadline() - now()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
                if (!drained) fail(IOException(if (fatal)
                    "TIMEOUT: receiver fatal terminal response exceeded its negotiated cleanup budget"
                    else "TIMEOUT: receiver terminal response did not arrive within two seconds"),
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

    private val orderlyTeardown: Boolean get() =
        link?.streamRecovery == true && (playing || remoteStop && link?.mediaAttached == true) &&
            acceptedSession && outcome.failure == null

    private val negotiatedFatalCleanup: Boolean get() =
        telemetryNegotiated && acceptedSession && outcome.failure != null && !receiverTerminal.get() && !linkClosed

    private fun retirementDeadline(): Long {
        val writtenAt = terminalWrittenAt
        return if (fatalTeardown && writtenAt != null)
            writtenAt + TelemetryTerminalBudget.FATAL_RESPONSE_NS else phaseDeadline
    }

    internal fun startWatchdog() {
        timer.scheduleWithFixedDelay(::watchdog, 500, 500, TimeUnit.MILLISECONDS)
    }

    private fun watchdog() {
        dumpVideoTrace()
        val current = now()
        if (closing) {
            // Bound terminal TCP delivery without interrupting independently bounded codec cleanup.
            val frameStarted = writeStarted
            val frameExpired = fatalTeardown && frameStarted > 0 &&
                current - frameStarted > TelemetryTerminalBudget.CONTROL_FRAME_NS
            if (current > retirementDeadline() || frameExpired) {
                if ((orderlyTeardown || fatalTeardown) && (terminalSent.count != 0L || !receiverTerminal.get()))
                    fail(IOException(if (fatalTeardown) {
                        if (terminalWrittenAt == null || frameExpired)
                            "TIMEOUT: fatal terminal frame did not finish within 10 seconds"
                        else "TIMEOUT: receiver fatal terminal response exceeded its negotiated cleanup budget"
                    } else if (terminalSent.count != 0L)
                        "TIMEOUT: orderly terminal frame did not finish within 22 seconds"
                        else "TIMEOUT: receiver terminal response did not arrive within two seconds"), SessionOutcome.Origin.CLEANUP)
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
            if (!negotiatedFatalCleanup) closeLink()
            startCaptureAbort(); return
        }
        if (phaseDeadline > 0 && current > phaseDeadline || lastIncoming > 0 && current - lastIncoming > 10_000_000_000L ||
            writeStarted > 0 && current - writeStarted > 10_000_000_000L) {
            fail(IOException("TIMEOUT: receiver/capture did not make progress within 10 seconds"), SessionOutcome.Origin.WATCHDOG)
            closeLink(); startCaptureAbort(); return
        }
        mux.expiredTrack()?.let { track ->
            fail(IOException("TIMEOUT: receiver did not initialize track $track"), SessionOutcome.Origin.WATCHDOG); closeLink(); startCaptureAbort()
        }
    }
    private fun dumpVideoTrace(force: Boolean = false) {
        val snapshot = frameTrace?.takeCompleted(force) ?: return
        val file = java.io.File(context.filesDir, "video-frame-$session.jsonl")
        try {
            file.bufferedWriter(Charsets.UTF_8).use(snapshot::writeTo)
            diagnostic("event=VIDEO_FRAME_TRACE_SAVED session=$session file=${file.name}" +
                " events=${snapshot.events.size} reason=${snapshot.reason} dropped=${snapshot.dropped}")
        } catch (error: IOException) {
            android.util.Log.e("ScreenCopySender", "event=VIDEO_FRAME_TRACE_WRITE_FAILED session=$session", error)
        } catch (error: SecurityException) {
            android.util.Log.e("ScreenCopySender", "event=VIDEO_FRAME_TRACE_WRITE_FAILED session=$session", error)
        }
    }
    private fun startCaptureAbort(beforeAbort: () -> Unit = {}) {
        if (!captureAbortStarted.compareAndSet(false, true)) return
        try {
            Thread({
                try { beforeAbort() }
                finally { abortCapture() }
            }, "cancel-capture-startup").start()
        } catch (error: Exception) {
            fail(IOException("Capture abort could not be started", error), SessionOutcome.Origin.CLEANUP)
            closeLink()
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
    private fun recordReceiverTerminal(json: JSONObject) {
        if (!telemetryNegotiated) return
        val rawSequence = json.opt("finalSeq")
        val sequence = if (rawSequence == null || rawSequence === JSONObject.NULL) null
            else SessionTelemetryProtocol.number(rawSequence).also {
                if (it <= 0) throw IOException("PROTOCOL: invalid final telemetry sequence")
            }
        val cleanup = json.opt("cleanupSucceeded")
        if (cleanup != null && cleanup !is Boolean)
            throw IOException("PROTOCOL: invalid receiver cleanup outcome")
        if (sequence != null && cleanup == null)
            throw IOException("PROTOCOL: missing receiver cleanup outcome")
        val rawSkip = json.opt("finalSkipReason")
        val skip = if (rawSkip == null) null else {
            if (rawSkip !is String) throw IOException("PROTOCOL: invalid final telemetry skip reason")
            ReceiverTelemetrySession.FinalSkipReason.entries.firstOrNull { it.name == rawSkip }
                ?: throw IOException("PROTOCOL: unknown final telemetry skip reason")
        }
        if (sequence != null && skip != null)
            throw IOException("PROTOCOL: final telemetry cannot be both sent and skipped")
        telemetry?.terminal(sequence, cleanup == true, skipReason = skip)
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
                val telemetryStatus = message.type == Wire.STATUS && json.optString("state") == "session-telemetry"
                if ((stopping || closing) && !telemetryStatus &&
                    message.type !in setOf(Wire.ERROR, Wire.STOP, Wire.SESSION_END)) continue
                when (message.type) {
                    Wire.STREAM_READY -> {
                        if (!Json.bool(json, "ok")) throw IOException("DECODER_FAILED: track not ready")
                        mux.ready(message.track, message.generation)
                        if (recovery?.ready() == true) checkNotNull(backend).requestKeyFrame()
                        TelemetryRegistry.evidence("sender", session, message.track, message.generation,
                            IncidentEvidence.Stage.READY_PROCESSED)
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
                        if (telemetryStatus) {
                            if (telemetryNegotiated) telemetry?.acceptPeer(message.payload)
                            continue
                        }
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
                            recordReceiverTerminal(json)
                            proxyClaim?.commitTerminal(now())
                            receiverTerminal.set(true)
                            diagnostic("receiver_session_error ${Wire.obj("code" to code, "fatal" to true)}")
                            throw error
                        }
                        status("Receiver warning: $code: $text")
                    }
                    Wire.SESSION_END -> {
                        Json.string(json, "reason")
                        recordReceiverTerminal(json)
                        proxyClaim?.commitTerminal(now())
                        receiverTerminal.set(true)
                        if (sizeSwitch.requested) sizeSwitch.acknowledge()
                        else { remoteStop = true; stop(); return }
                    }
                    Wire.STOP -> {
                        Json.string(json, "reason"); recordReceiverTerminal(json)
                        proxyClaim?.commitTerminal(now())
                        receiverTerminal.set(true); remoteStop = true; stop(); return
                    }
                    else -> throw IOException("PROTOCOL: unexpected receiver message ${message.type}")
                }
            }
        } catch (error: Exception) {
            val e = if (error is ProtocolException) IOException("${error.code}: ${error.message}", error) else error
            if (sizeSwitch.requested && !linkClosed) {
                if (e is java.io.EOFException && sizeSwitch.acknowledged) sizeSwitch.peerClosed()
                else fail(IOException("Size change lost receiver before confirmed cleanup", e), SessionOutcome.Origin.RECEIVER_READ)
            } else if ((stopping || closing) && !linkClosed && link?.streamRecovery == true &&
                e is IOException && e !is ReceiverReportedFailure) {
                fail(IOException("${if (e is java.net.SocketTimeoutException) "TIMEOUT" else "PROTOCOL"}: " +
                    "control transport failed during orderly Stop", e),
                    SessionOutcome.Origin.RECEIVER_READ)
                closeLink()
            } else fail(e, SessionOutcome.Origin.RECEIVER_READ)
        } finally { receiverReleased.countDown() }
    }
    @Synchronized private fun writeCompleted(message: Wire.Message) {
        if (!stopping && !closing) recovery?.written(message)
    }

    private fun writeFailed(lane: Int, error: Exception) {
        if (lane == 0) {
            if ((stopping || closing) && !linkClosed && link?.streamRecovery == true && error is IOException)
                fail(IOException("${if (error is java.net.SocketTimeoutException) "TIMEOUT" else "PROTOCOL"}: " +
                    "control write failed during orderly Stop", error), SessionOutcome.Origin.RECEIVER_WRITE)
            else fail(error, SessionOutcome.Origin.RECEIVER_WRITE)
            if (outcome.failure != null) closeLink()
            terminalSent.countDown()
        } else {
            val orderly = orderlyTeardown && (stopping || closing) && !linkClosed && !mediaRetiring
            recordMediaFailure(error, SessionOutcome.Origin.RECEIVER_WRITE)
            if (orderly && outcome.failure != null && !linkClosed) closeLink()
        }
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
                    if (message.type in setOf(Wire.STOP, Wire.SESSION_END, Wire.ERROR)) terminalWriting = true
                    try {
                        if (message.type == Wire.VIDEO_FRAME) RemoteDiagnostics.recorder.video(
                            "sender", session, message.generation, DiagnosticVideoStage.WRITE_STARTED, message.pts,
                            message.payload.size.toLong(), keyFrame = message.flags and 1 != 0)
                        val operations = RemoteDiagnostics.recorder.transportOperations("sender", session, lane == 2)
                        if (lane == 2) RemoteDiagnostics.recorder.observe("sender", session, DiagnosticKind.AUDIO_TRANSPORT,
                            DiagnosticStage.BEGIN, lane, message.generation, operations.audio, message.pts,
                            bytes = message.payload.size.toLong(), point = DiagnosticPoint.WRITE)
                        RemoteDiagnostics.recorder.observe("sender", session, DiagnosticKind.TRANSPORT_HEALTH,
                            DiagnosticStage.BEGIN, lane, message.generation, operations.health, message.pts,
                            bytes = message.payload.size.toLong(), point = DiagnosticPoint.WRITE)
                        if (message.type == Wire.VIDEO_FRAME)
                            frameTrace?.record(VideoFrameStage.WRITE_BEGIN, message.pts, message.generation)
                        try {
                            connected.send(message)
                        } catch (error: Exception) {
                            if (message.type == Wire.VIDEO_FRAME) RemoteDiagnostics.recorder.video(
                                "sender", session, message.generation, DiagnosticVideoStage.WRITE_FAILED, message.pts,
                                message.payload.size.toLong(), reason = DiagnosticReason.FAILURE)
                            if (lane == 2) RemoteDiagnostics.recorder.observe("sender", session, DiagnosticKind.AUDIO_TRANSPORT,
                                DiagnosticStage.FAILED, lane, message.generation, operations.audio, message.pts,
                                bytes = message.payload.size.toLong(), reason = DiagnosticReason.FAILURE, point = DiagnosticPoint.WRITE)
                            RemoteDiagnostics.recorder.observe("sender", session, DiagnosticKind.TRANSPORT_HEALTH,
                                DiagnosticStage.FAILED, lane, message.generation, operations.health, message.pts,
                                reason = DiagnosticReason.FAILURE, point = DiagnosticPoint.WRITE)
                            throw error
                        }
                        if (message.type == Wire.VIDEO_FRAME) RemoteDiagnostics.recorder.video(
                            "sender", session, message.generation, DiagnosticVideoStage.WRITTEN, message.pts,
                            message.payload.size.toLong(), keyFrame = message.flags and 1 != 0)
                        if (lane == 2) RemoteDiagnostics.recorder.observe("sender", session, DiagnosticKind.AUDIO_TRANSPORT,
                            DiagnosticStage.COMPLETE, lane, message.generation, operations.audio, message.pts,
                            bytes = message.payload.size.toLong(), point = DiagnosticPoint.WRITE)
                        RemoteDiagnostics.recorder.observe("sender", session, DiagnosticKind.TRANSPORT_HEALTH,
                            DiagnosticStage.COMPLETE, lane, message.generation, operations.health, message.pts,
                            bytes = message.payload.size.toLong(), point = DiagnosticPoint.WRITE)
                        if (message.type == Wire.VIDEO_FRAME)
                            frameTrace?.record(VideoFrameStage.WRITE_END, message.pts, message.generation)
                        writeCompleted(message)
                    } catch (error: Exception) {
                        writeFailed(lane, error)
                        return
                    } finally {
                        mux.endWrite(message)
                        if (lane == 0) writeStarted = 0
                    }
                    if (message.type in setOf(Wire.STOP, Wire.SESSION_END, Wire.ERROR)) {
                        terminalWrittenAt = now()
                        if (orderlyTeardown) {
                            mediaRetiring = true
                            if (message.type == Wire.STOP) phaseDeadline = checkNotNull(terminalWrittenAt) + 2_000_000_000L
                        }
                        trace.mark(SessionStopTrace.Stage.TERMINAL_SENT); terminalSent.countDown(); return
                    }
                }
                if (closing && terminalSent.count == 0L) return
            }
        } catch (e: Exception) {
            writeFailed(lane, e)
        }
    }
    override fun config(track: Int, generation: Int, config: JSONObject) {
        check()
        TelemetryRegistry.trackConfiguration("sender", session, track, generation, config.optString("codec"),
            config.optInt("width"), config.optInt("height"), config.optInt("sampleRate"), config.optInt("channels"))
        RemoteDiagnostics.recorder.observe("sender", session, DiagnosticKind.SESSION, DiagnosticStage.CONFIG,
            track, generation, metadata = if (track == 2 && RemoteDiagnostics.recorder.enabled("sender", session, DiagnosticKind.SESSION))
                DiagnosticConfiguration(DiagnosticCodec.from(config.optString("codec")), config.optInt("sampleRate"),
                    config.optInt("channels"), when (config.optString("codec")) {
                        "raw", "pcm" -> 0; "opus" -> 80_000; else -> null
                    }) else null)
        val current = recovery
        if (current == null) mux.offer(Wire.config(session, track, generation, config))
        else current.config(track, generation, config)
        status(if (track == 1) "Preparing video" else "Preparing audio")
    }
    override fun packet(packet: Wire.Message) {
        check()
        val current = recovery
        if (current == null) mux.offer(packet) else current.packet(packet)
        if (packet.type == Wire.VIDEO_FRAME)
            frameTrace?.record(VideoFrameStage.SINK_RETURNED, packet.pts, packet.generation)
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

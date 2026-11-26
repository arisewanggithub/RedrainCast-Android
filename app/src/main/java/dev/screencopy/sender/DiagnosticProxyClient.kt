package dev.screencopy.sender

import dev.redrain.cast.diagnostics.DiagnosticArmRegistry
import dev.redrain.cast.diagnostics.DiagnosticError
import dev.redrain.cast.diagnostics.DiagnosticProxyIntake
import dev.redrain.cast.diagnostics.DiagnosticProxyWire
import org.json.JSONObject
import org.screencopy.receiver.protocol.DiagnosticProxyNegotiation
import org.screencopy.receiver.protocol.StrictJson
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class DiagnosticProxySetupException(val stage: String, cause: Exception) :
    IOException("Diagnostic setup failed: $stage", cause)

/** Sender-owned lane; the reader never runs on a cast or collector I/O worker. */
internal class DiagnosticProxyClient(
    private val target: ReceiverTarget,
    private val registry: DiagnosticArmRegistry,
    private val claim: DiagnosticArmRegistry.Claim,
    private val timer: ScheduledExecutorService,
    private val onRecord: () -> Unit = {},
    private val onTerminal: () -> Unit = {},
    private val onFailure: (Exception) -> Unit = { android.util.Log.w("RemoteDiagnostics", "Diagnostic lane failed", it) },
    private val onClosed: () -> Unit = {},
    private val beforeReadyCommit: () -> Unit = {},
    private val afterBootstrapPublished: () -> Unit = {},
    private val afterReadyCommit: () -> Unit = {},
    private val afterTerminalReserved: () -> Unit = {},
    private val startupStopped: () -> Boolean = { false },
    private val retirementTimer: ScheduledExecutorService = retirementScheduler
) {
    companion object {
        // Receiver flushes for at most 100 ms; allow additional delivery and reader scheduling time.
        private const val RETIREMENT_GRACE_MS = 250L
        private val retirementScheduler = ScheduledThreadPoolExecutor(1) { action ->
            Thread(action, "Diagnostic-proxy-retirement").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
    }

    private val open = AtomicBoolean(true)
    private val transport = AtomicReference<Socket?>()
    private val closingReason = AtomicReference<String?>()
    private data class ClockPending(val id: String, val clockId: String, val result: CompletableFuture<JSONObject>)
    private val pendingClock = AtomicReference<ClockPending?>()
    private val writeLock = Any()
    private val setupGate = Any()
    private val retirementGate = Any()
    private var retirementTask: ScheduledFuture<*>? = null
    private var setupExpired = false
    private var readyCommitted = false
    @Volatile private var intake: DiagnosticProxyIntake? = null

    fun start(accepted: DiagnosticProxyNegotiation.Accepted, deadlineNs: Long,
              commitReady: (() -> Unit) -> Unit = { it() }, remainingMs: () -> Int) {
        var stage = "lane"
        val remainingNs = minOf(deadlineNs, claim.expiresNs) - System.nanoTime()
        if (remainingNs <= 0 || !claim.valid.get()) throw SocketTimeoutException("TIMEOUT: diagnostic setup expired")
        val socket = Socket()
        if (!transport.compareAndSet(null, socket) || !open.get()) {
            socket.close()
            throw SessionCancelledException()
        }
        val timeout = timer.schedule({ expireSetup() }, remainingNs, TimeUnit.NANOSECONDS)
        var published = false
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(target.host, target.port),
                minOf(2000L, (remainingNs / 1_000_000L).coerceAtLeast(1)).toInt())
            val afterConnect = minOf(deadlineNs, claim.expiresNs) - System.nanoTime()
            if (afterConnect <= 0 || !open.get() || !claim.valid.get())
                throw SocketTimeoutException("TIMEOUT: diagnostic setup expired")
            socket.soTimeout = (afterConnect / 1_000_000L).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
            val remaining = remainingMs()
            if (remaining !in 1..180_000) throw SocketTimeoutException("TIMEOUT: diagnostic lease expired")
            val hello = JSONObject().put("role", "diagnostic").put("version", 2)
                .put("pairId", claim.pairId).put("armGeneration", claim.generation.toString())
                .put("token", accepted.token).put("remainingMs", remaining)
            Wire.write(socket.outputStream, Wire.control(Wire.HELLO, claim.session, hello))
            val ready = Wire.read(socket.inputStream)
            if (ready.type != Wire.HELLO || ready.session != claim.session)
                throw IOException("PROTOCOL: invalid diagnostic READY")
            stage = "bootstrap"
            val offer = DiagnosticProxyNegotiation.Offer(claim.session, claim.pairId, claim.generation,
                claim.receiverFlags, claim.expiresNs, claim.samplePeriodMs)
            val bootstrap = DiagnosticProxyNegotiation.ready(StrictJson.decode(ready.payload), offer, accepted)
            val receiver = DiagnosticProxyIntake(claim.session.toString(), claim.pairId, accepted.captureId,
                accepted.clockId, "video.timing" in claim.receiverFlags, bootstrap)
            intake = receiver
            socket.soTimeout = 0
            val reader = Thread({ read(socket, receiver, accepted.captureId, accepted.clockId) },
                "Diagnostic-proxy-reader").apply { isDaemon = true }
            beforeReadyCommit()
            synchronized(setupGate) {
                if (setupExpired) throw SocketTimeoutException("TIMEOUT: diagnostic setup expired")
                if (!open.get() || !claim.valid.get()) throw SessionCancelledException()
                commitReady {
                    if (System.nanoTime() >= minOf(deadlineNs, claim.expiresNs)) {
                        setupExpired = true
                        throw SocketTimeoutException("TIMEOUT: diagnostic setup expired")
                    }
                    if (!open.get() || !claim.valid.get()) throw SessionCancelledException()
                    try { registry.covered(claim, receiver, accepted.captureId, accepted.clockId, deadlineNs) }
                    catch (e: SocketTimeoutException) {
                        setupExpired = true
                        throw e
                    }
                    published = true
                    afterBootstrapPublished()
                    try { registry.registerClock(claim) { clientSendNs -> clock(accepted, clientSendNs) } }
                    catch (e: DiagnosticError) {
                        if (!claim.bootstrapAccepted.get() || claim.valid.get()) throw e
                    }
                    reader.start()
                }
                readyCommitted = true
                timeout.cancel(false)
            }
            afterReadyCommit()
        } catch (e: Exception) {
            cancel("setup_failed")
            if (published) {
                intake?.closeUnconfirmed(discard = true)
                try { registry.receiverEnded(claim, false) }
                catch (_: DiagnosticError) { /* Revocation already fenced this arm. */ }
            }
            val expired = synchronized(setupGate) { setupExpired }
            val cause = if (expired && e !is SocketTimeoutException)
                SocketTimeoutException("TIMEOUT: diagnostic setup expired").apply { initCause(e) }
            else if ((closingReason.get() == "startup_cancelled" || startupStopped()) &&
                (e is java.net.SocketException || e is EOFException || e is java.io.InterruptedIOException))
                SessionCancelledException(e) else e
            throw DiagnosticProxySetupException(stage, cause)
        } finally { timeout.cancel(false) }
    }

    internal fun expireSetup() {
        val expired = synchronized(setupGate) {
            if (readyCommitted || setupExpired) false else {
                setupExpired = true
                true
            }
        }
        if (expired) cancel("setup_timeout")
    }

    fun cancel(reason: String) {
        closingReason.compareAndSet(null, reason)
        open.set(false)
        synchronized(retirementGate) {
            retirementTask?.cancel(false)
            retirementTask = null
        }
        try { transport.get()?.close() } catch (_: IOException) {}
    }

    fun retire() {
        try {
            synchronized(retirementGate) {
                if (!open.get() || retirementTask != null) return
                retirementTask = retirementTimer.schedule(
                    { cancel("sender_finished") }, RETIREMENT_GRACE_MS, TimeUnit.MILLISECONDS)
            }
        } catch (_: RejectedExecutionException) {
            cancel("sender_finished")
        }
    }

    private fun read(socket: Socket, receiver: DiagnosticProxyIntake, captureId: String, clockId: String) {
        val binding = DiagnosticProxyWire.Binding(claim.pairId, claim.session, claim.generation, captureId)
        var expected = false
        var confirmed = false
        try {
            while (open.get() && claim.valid.get()) {
                val frame = DiagnosticProxyWire.readFrameWithSize(socket.inputStream) ?: throw EOFException()
                DiagnosticProxyWire.validateIdentity(frame.value, binding)
                val common = setOf("v", "type", "pairId", "session", "armGeneration", "captureId")
                when (frame.value.opt("type")) {
                    "RECORD" -> {
                        if (frame.value.keys().asSequence().toSet() != common + "record")
                            throw IOException("PROTOCOL: invalid diagnostic RECORD")
                        val record = frame.value.opt("record") as? JSONObject
                            ?: throw IOException("PROTOCOL: missing diagnostic record")
                        receiver.record(record, frame.bytes)
                        receiver.consume()
                        onRecord()
                    }
                    "LOSS" -> {
                        if (frame.value.keys().asSequence().toSet() != common + "watermarks")
                            throw IOException("PROTOCOL: invalid diagnostic LOSS")
                        val watermarks = frame.value.opt("watermarks") as? JSONObject
                            ?: throw IOException("PROTOCOL: missing diagnostic watermarks")
                        receiver.sourceLoss(watermarks)
                    }
                    "CLOCK_RESPONSE" -> {
                        if (frame.value.keys().asSequence().toSet() != common +
                            setOf("requestId", "receiveNs", "sendNs", "clockId"))
                            throw IOException("PROTOCOL: invalid diagnostic CLOCK_RESPONSE")
                        val pending = pendingClock.get()
                            ?: throw IOException("PROTOCOL: unsolicited diagnostic clock response")
                        if (frame.value.opt("requestId") != pending.id || frame.value.opt("clockId") != pending.clockId)
                            throw IOException("PROTOCOL: diagnostic clock identity mismatch")
                        val received = DiagnosticProxyWire.decimal(frame.value.opt("receiveNs"))
                        val sent = DiagnosticProxyWire.decimal(frame.value.opt("sendNs"))
                        if (sent < received) throw IOException("PROTOCOL: reversed diagnostic clock timestamps")
                        if (!pendingClock.compareAndSet(pending, null))
                            throw IOException("PROTOCOL: stale diagnostic clock response")
                        pending.result.complete(frame.value)
                    }
                    "ENDED" -> {
                        if (frame.value.keys().asSequence().toSet() != common +
                            setOf("clockId", "reason", "endedNs", "mediaRetirement", "ownershipCleanup",
                                "tailTruncated", "captureEnd", "sourceLoss") ||
                            frame.value.opt("clockId") != clockId)
                            throw IOException("PROTOCOL: invalid diagnostic terminal")
                        val reason = frame.value.opt("reason") as? String
                        if (reason !in setOf("receiver_retired_stop", "receiver_retired_fatal"))
                            throw IOException("PROTOCOL: invalid diagnostic retirement reason")
                        DiagnosticProxyWire.decimal(frame.value.opt("endedNs"))
                        if (frame.value.opt("mediaRetirement") !in setOf("succeeded", "failed") ||
                            frame.value.opt("ownershipCleanup") != "unknown" ||
                            frame.value.opt("tailTruncated") !is Boolean)
                            throw IOException("PROTOCOL: invalid diagnostic retirement outcome")
                        val end = frame.value.opt("captureEnd") as? JSONObject
                            ?: throw IOException("PROTOCOL: missing diagnostic capture end")
                        if (end.keys().asSequence().toSet() != setOf("atNs", "reason", "complete", "truncated") ||
                            end.opt("reason") != reason || end.opt("complete") !is Boolean ||
                            end.opt("truncated") != frame.value.opt("tailTruncated") ||
                            end.opt("complete") == end.opt("truncated"))
                            throw IOException("PROTOCOL: invalid diagnostic capture outcome")
                        DiagnosticProxyWire.decimal(end.opt("atNs"))
                        val loss = frame.value.opt("sourceLoss") as? JSONObject
                            ?: throw IOException("PROTOCOL: missing diagnostic final loss")
                        receiver.sourceLoss(loss)
                        val terminalOutcome = JSONObject().put("confirmed", true).put("reason", reason)
                            .put("endedNs", frame.value.getString("endedNs"))
                            .put("mediaRetirement", frame.value.getString("mediaRetirement"))
                            .put("ownershipCleanup", "unknown")
                            .put("tailTruncated", frame.value.getBoolean("tailTruncated"))
                            .put("captureEnd", end).toString()
                        registry.receiverFinishing(claim)
                        receiver.terminal(frame.value, confirmed = true, outcome = terminalOutcome)
                        afterTerminalReserved()
                        receiver.consume()
                        expected = true
                        confirmed = true
                        break
                    }
                    else -> throw IOException("PROTOCOL: invalid diagnostic lane message")
                }

            }
            if (!expected) expected = claim.terminalCommitted.get()
        } catch (e: Exception) {
            expected = claim.terminalCommitted.get()
            if (open.get() && claim.valid.get())
                onFailure(e)
        } finally {
            open.set(false)
            synchronized(retirementGate) {
                retirementTask?.cancel(false)
                retirementTask = null
            }
            pendingClock.getAndSet(null)?.result?.completeExceptionally(IOException("Diagnostic lane closed"))
            try { registry.receiverFinishing(claim) }
            catch (_: DiagnosticError) { /* A revoked or replaced arm is already fenced. */ }
            receiver.closeUnconfirmed()
            try { registry.receiverEnded(claim, expected) }
            catch (_: DiagnosticError) { /* A revoked or replaced arm is already fenced. */ }
            if (confirmed) onTerminal()
            try { socket.close() } catch (_: IOException) {}
            onClosed()
        }
    }

    private fun clock(accepted: DiagnosticProxyNegotiation.Accepted, clientSendNs: String): JSONObject {
        val socket = transport.get() ?: throw DiagnosticError("CLOCK_UNAVAILABLE")
        if (!open.get() || !claim.valid.get()) throw DiagnosticError("CLOCK_UNAVAILABLE")
        val pending = ClockPending(java.util.UUID.randomUUID().toString(), accepted.clockId, CompletableFuture())
        if (!pendingClock.compareAndSet(null, pending)) throw DiagnosticError("BUSY")
        val timeout = timer.schedule({ cancel("clock_timeout") }, 2, TimeUnit.SECONDS)
        try {
            val request = JSONObject().put("v", 2).put("type", "CLOCK_REQUEST").put("pairId", claim.pairId)
                .put("session", claim.session.toString()).put("armGeneration", claim.generation.toString())
                .put("captureId", accepted.captureId).put("requestId", pending.id)
            synchronized(writeLock) { DiagnosticProxyWire.writeFrame(socket.outputStream, request) }
            val response = pending.result.get(2, TimeUnit.SECONDS)
            return JSONObject().put("clientSendNs", clientSendNs)
                .put("receiveNs", response.getString("receiveNs")).put("sendNs", response.getString("sendNs"))
                .put("senderClockId", registry.senderClockId()).put("receiverClockId", accepted.clockId)
        } catch (e: Exception) {
            cancel("clock_failed")
            throw DiagnosticError("CLOCK_UNAVAILABLE")
        } finally {
            pendingClock.compareAndSet(pending, null)
            timeout.cancel(false)
        }
    }
}

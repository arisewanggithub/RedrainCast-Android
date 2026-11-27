package dev.redrain.cast.diagnostics

import org.json.JSONArray
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Sender-local single-use arm. Lane setup and pair draining are coordinated separately. */
internal class DiagnosticArmRegistry(
    private val recorder: DiagnosticRecorder,
    private val clock: () -> Long = System::nanoTime
) {
    private companion object {
        const val UNCONFIRMED_OUTCOME = """{"confirmed":false,"reason":null,"endedNs":null,"mediaRetirement":"unknown","ownershipCleanup":"unknown","tailTruncated":true,"captureEnd":{"atNs":null,"reason":null,"complete":false,"truncated":true}}"""
    }
    enum class SetupCode { VERSION, BUSY, UNSUPPORTED_CONFIG, PROTOCOL, CAPTURE_FAILED, AUDIO_FAILED,
        DECODER_FAILED, TIMEOUT, CONGESTION, INTERNAL }
    enum class SetupProvenance { receiver_control, sender_local }
    enum class SetupReason { receiver_reported, timeout, eof, connect, setup }
    data class SetupFailure(val code: SetupCode, val provenance: SetupProvenance, val reason: SetupReason) {
        init {
            require((provenance == SetupProvenance.receiver_control) == (reason == SetupReason.receiver_reported))
        }
        fun json() = JSONObject().put("code", code.name).put("provenance", provenance.name).put("reason", reason.name)
    }
    data class Claim(
        val pairId: String, val generation: Long, val session: Long, val senderCaptureId: String,
        val receiverFlags: Set<String>, val samplePeriodMs: Int, val expiresNs: Long,
        val valid: AtomicBoolean = AtomicBoolean(true),
        val bootstrapAccepted: AtomicBoolean = AtomicBoolean(),
        val terminalCommitted: AtomicBoolean = AtomicBoolean(),
        val terminalDeadlineNs: AtomicLong = AtomicLong(),
        val senderFinished: AtomicBoolean = AtomicBoolean()
    ) {
        fun commitTerminal(now: Long) {
            terminalCommitted.set(true)
            capTerminalDeadline(minOf(expiresNs, now + 22_000_000_000L))
        }

        fun capTerminalDeadline(deadline: Long) {
            while (true) {
                val previous = terminalDeadlineNs.get()
                if (previous != 0L && previous <= deadline) return
                if (terminalDeadlineNs.compareAndSet(previous, deadline)) return
            }
        }
    }
    private class Arm(
        val owner: String, val id: String, val generation: Long, val captureId: String,
        val senderFlags: Set<String>, val receiverFlags: Set<String>, val periodMs: Int,
        val startedNs: Long, val expiresNs: Long
    ) {
        var state = "armed"
        var session: Long? = null
        var reason: String? = null
        var setupStage: String? = null
        var setupFailure: SetupFailure? = null
        var frozenAt: Long? = null
        val valid = AtomicBoolean(true)
        val bootstrapAccepted = AtomicBoolean()
        val terminalCommitted = AtomicBoolean()
        val terminalDeadlineNs = AtomicLong()
        val senderFinished = AtomicBoolean()
        var receiverFinished = false
        var receiverCoverageLost = false
        var receiverFinishing = false
        var receiver: DiagnosticProxyIntake? = null
        var receiverCaptureId: String? = null
        var receiverClockId: String? = null
        var receiverOutcome: String? = null
        var clockRequest: ((String) -> JSONObject)? = null
        var preferReceiver = false
    }

    private var generation = 0L
    private var arm: Arm? = null

    @Synchronized fun start(
        owner: String, senderFlags: Set<String>, receiverFlags: Set<String>, durationMs: Int, periodMs: Int
    ): JSONObject {
        if (owner.isEmpty()) throw DiagnosticError("INVALID_ARGUMENT")
        expire()
        arm?.let { throw DiagnosticError(if (it.state == "frozen") "RETAINED_CAPTURE_EXISTS" else "CAPTURE_ACTIVE") }
        if (receiverFlags.isEmpty() || receiverFlags.any { it !in DiagnosticRecorder.capabilities.getValue("receiver") })
            throw DiagnosticError("UNSUPPORTED_FLAG")
        if ("video.timing" in receiverFlags && "video.progress" !in receiverFlags)
            throw DiagnosticError("INVALID_ARGUMENT")
        if (generation == Long.MAX_VALUE) throw DiagnosticError("INTERNAL")
        val started = recorder.startPairedSender(senderFlags, durationMs, periodMs)
        val next = Arm(owner, UUID.randomUUID().toString(), ++generation, started.getString("captureId"),
            java.util.Collections.unmodifiableSet(HashSet(senderFlags)),
            java.util.Collections.unmodifiableSet(HashSet(receiverFlags)), periodMs, started.getString("startedNs").toLong(),
            started.getString("expiresNs").toLong())
        arm = next
        return context(next)
    }

    @Synchronized fun claim(session: Long): Claim? {
        expire()
        val current = arm ?: return null
        if (current.state != "armed") return null
        if (!recorder.claimNextSender(current.captureId, session)) return null
        current.session = session
        current.state = "binding"
        return claim(current)
    }

    /** Sample immediately before HELLO; negotiation never renews the arm. */
    @Synchronized fun remainingMs(claim: Claim): Int {
        expire()
        val current = requireClaim(claim)
        if (current.state != "binding") throw DiagnosticError("SESSION_ENDED")
        val remaining = current.expiresNs - clock()
        if (remaining < 1_000_000L) throw DiagnosticError("SESSION_ENDED")
        return (remaining / 1_000_000L).coerceAtMost(180_000).toInt()
    }

    @Synchronized fun setupFailed(claim: Claim, stage: String, failure: SetupFailure? = null): JSONObject {
        if (!stage.matches(Regex("[a-z_]{1,32}"))) throw DiagnosticError("INVALID_ARGUMENT")
        expire()
        val current = requireClaim(claim)
        if (current.state == "binding") {
            current.setupStage = stage
            current.setupFailure = failure
            freeze(current, "setup_failed")
        }
        return context(current)
    }

    @Synchronized fun covered(claim: Claim, receiver: DiagnosticProxyIntake,
                              captureId: String, clockId: String, deadlineNs: Long = claim.expiresNs) {
        expire()
        val current = requireClaim(claim)
        if (current.state != "binding" || !current.valid.get() ||
            !recorder.pairedSenderActive(current.captureId, claim.session)) throw DiagnosticError("SESSION_ENDED")
        // Monitor acquisition and recorder validation may outlive the setup deadline.
        if (clock() >= minOf(deadlineNs, current.expiresNs))
            throw SocketTimeoutException("TIMEOUT: diagnostic setup expired")
        receiver.bindValidity(current.valid)
        current.receiver = receiver
        current.receiverCaptureId = captureId
        current.receiverClockId = clockId
        current.state = "covered"
        current.bootstrapAccepted.set(true)
    }

    fun receiverFetch(claim: Claim, after: Long, limit: Int): JSONObject {
        val receiver = synchronized(this) {
            expire()
            requireClaim(claim).receiver ?: throw DiagnosticError("CAPTURE_NOT_FOUND")
        }
        return receiver.fetch(after, limit)
    }

    fun fetch(owner: String, pairId: String, afterSender: Long, afterReceiver: Long, limit: Int,
              responseId: String = "r1"): JSONObject {
        if (afterSender < 0 || afterReceiver < 0 || limit !in 1..32) throw DiagnosticError("INVALID_ARGUMENT")
        var snapshot = synchronized(this) {
            expire()
            val current = requirePair(pairId)
            if (current.state != "frozen" && current.owner != owner) throw DiagnosticError("NOT_OWNER")
            FetchSnapshot(current, fetchContext(current), current.receiver, current.preferReceiver, current.session)
        }
        val senderPage = recorder.fetch(snapshot.arm.captureId, afterSender, limit)
        snapshot = synchronized(this) {
            expire()
            val current = requirePair(pairId)
            if (current !== snapshot.arm) throw DiagnosticError("CAPTURE_NOT_FOUND")
            if (current.state != "frozen" && current.owner != owner) throw DiagnosticError("NOT_OWNER")
            FetchSnapshot(current, fetchContext(current), current.receiver, current.preferReceiver, current.session)
        }
        val senderRecords = senderPage.getJSONArray("records")
        senderPage.remove("records")
        senderPage.put("lossWatermarks", compactLoss(senderPage.getJSONObject("lossWatermarks")))
        val senderMore = senderPage.getBoolean("hasMore")
        senderPage.put("nextAfterSeq", afterSender.toString())
        val receiverPage = snapshot.receiver?.fetch(afterReceiver, limit)
        val receiverRecords = receiverPage?.getJSONArray("records") ?: JSONArray()
        val receiverMore = receiverPage?.getBoolean("hasMore") ?: false
        receiverPage?.remove("records")
        receiverPage?.optJSONObject("sourceLoss")?.let { receiverPage.put("sourceLoss", compactLoss(it)) }
        receiverPage?.put("nextAfterSeq", afterReceiver.toString())
        val records = JSONArray()
        val result = snapshot.context.put("sender", senderPage)
            .put("receiver", receiverPage ?: JSONObject.NULL).put("records", records)
        var senderIndex = 0
        var receiverIndex = 0
        var senderCursor = afterSender
        var receiverCursor = afterReceiver
        var receiverTurn = snapshot.preferReceiver
        while (records.length() < limit &&
            (senderIndex < senderRecords.length() || receiverIndex < receiverRecords.length())) {
            var appended = false
            repeat(2) {
                if (!appended) {
                    val receiverRole = receiverTurn
                    receiverTurn = !receiverTurn
                    val list = if (receiverRole) receiverRecords else senderRecords
                    val index = if (receiverRole) receiverIndex else senderIndex
                    if (index < list.length()) {
                        val item = if (receiverRole) list.getJSONObject(index) else {
                            val record = list.getJSONObject(index)
                            val sourceSession = DiagnosticProxyWire.validateSource(record, "sender",
                                snapshot.session?.toString(), snapshot.arm.captureId, false)
                            JSONObject().put("role", "sender").put("session", sourceSession ?: JSONObject.NULL)
                                .put("captureId", snapshot.arm.captureId).put("clockId", recorder.clockId)
                                .put("record", record)
                        }
                        val seq = DiagnosticProxyWire.decimal(item.getJSONObject("record").opt("seq"))
                        records.put(item)
                        if (receiverRole) receiverPage?.put("nextAfterSeq", seq.toString())
                        else senderPage.put("nextAfterSeq", seq.toString())
                        senderPage.put("hasMore",
                            senderIndex + (if (receiverRole) 0 else 1) < senderRecords.length() || senderMore)
                        receiverPage?.put("hasMore",
                            receiverIndex + (if (receiverRole) 1 else 0) < receiverRecords.length() || receiverMore)
                        if (DiagnosticProtocol.successBytes(responseId, "FETCH", result) <= DiagnosticProxyWire.FRAME_BYTES) {
                            if (receiverRole) { receiverIndex++; receiverCursor = seq }
                            else { senderIndex++; senderCursor = seq }
                            appended = true
                        } else {
                            records.remove(records.length() - 1)
                            if (receiverRole) receiverPage?.put("nextAfterSeq", receiverCursor.toString())
                            else senderPage.put("nextAfterSeq", senderCursor.toString())
                        }
                    }
                }
            }
            if (!appended) {
                if (records.length() == 0) throw DiagnosticError("RECORD_TOO_LARGE")
                break
            }
        }
        senderPage.put("hasMore", senderIndex < senderRecords.length() || senderMore)
        receiverPage?.put("hasMore", receiverIndex < receiverRecords.length() || receiverMore)
        synchronized(this) {
            expire()
            val current = requirePair(pairId)
            if (current !== snapshot.arm) throw DiagnosticError("CAPTURE_NOT_FOUND")
            if (current.state != "frozen" && current.owner != owner) throw DiagnosticError("NOT_OWNER")
            current.preferReceiver = receiverTurn
        }
        return result
    }

    private data class FetchSnapshot(
        val arm: Arm, val context: JSONObject, val receiver: DiagnosticProxyIntake?,
        val preferReceiver: Boolean, val session: Long?
    )

    private fun compactLoss(loss: JSONObject): JSONArray {
        val admission = loss.getJSONObject("admission")
        val reasons = JSONArray()
        listOf("routine_capacity", "critical_capacity", "closed_capture", "expired", "oversized_record")
            .forEach { key ->
                val value = admission.getJSONObject(key)
                reasons.put(JSONArray().put(value.get("count"))
                    .put(value.get("firstMonoNs")).put(value.get("lastMonoNs")))
            }
        val retention = loss.getJSONObject("retention")
        val compactRetention = JSONArray()
        listOf("routine", "critical", "oversized").forEach { key ->
            val value = retention.getJSONObject(key)
            compactRetention.put(JSONArray().put(value.get("count")).put(value.get("firstSeq"))
                .put(value.get("lastSeq")).put(value.get("exactRange")))
        }
        return JSONArray().put("compact1").put(loss.get("publishedSeq")).put(loss.get("oldestRetainedSeq"))
            .put(loss.get("latestRetainedSeq")).put(admission.getString("total"))
            .put(reasons).put(compactRetention)
    }

    @Synchronized fun receiverFinishing(claim: Claim) {
        expire()
        val current = requireClaim(claim)
        if (current.state != "frozen" && current.receiver != null && !current.receiverFinished)
            current.receiverFinishing = true
    }

    @Synchronized fun receiverEnded(claim: Claim, expected: Boolean) {
        expire()
        val current = requireClaim(claim)
        if (current.state == "frozen") return
        current.receiverFinished = true
        current.receiverFinishing = false
        val outcome = current.receiver?.committedOutcome()
        if (outcome != null && (current.receiverOutcome == null || current.receiverOutcome == UNCONFIRMED_OUTCOME))
            current.receiverOutcome = outcome
        else if (current.receiver != null && current.receiverOutcome == null)
            current.receiverOutcome = UNCONFIRMED_OUTCOME
        if (!expected && !current.bootstrapAccepted.get()) {
            current.setupStage = "lane"
            freeze(current, "setup_failed")
        } else if (!expected) {
            current.receiverCoverageLost = true
            current.clockRequest = null
            if (current.senderFinished.get()) freeze(current, "coverage_lost")
            else current.state = "draining"
        }
        else if (current.senderFinished.get()) freeze(current, "session_ended")
        else current.state = "draining"
    }

    fun beginMedia(claim: Claim) {
        if (!claim.bootstrapAccepted.get()) throw DiagnosticError("SESSION_ENDED")
    }

    @Synchronized fun registerClock(claim: Claim, request: (String) -> JSONObject) {
        val current = requireClaim(claim)
        if (current.state != "covered" || !current.valid.get()) throw DiagnosticError("SESSION_ENDED")
        current.clockRequest = request
    }

    fun receiverClock(owner: String, pairId: String, clientSendNs: String): JSONObject {
        val snapshot = synchronized(this) {
            expire()
            val current = requirePair(pairId)
            if (current.owner != owner) throw DiagnosticError("NOT_OWNER")
            current to (current.clockRequest ?: throw DiagnosticError("CLOCK_UNAVAILABLE"))
        }
        val response = snapshot.second(clientSendNs)
        synchronized(this) {
            expire()
            val current = requirePair(pairId)
            if (current !== snapshot.first || current.owner != owner ||
                current.state != "covered" || current.clockRequest !== snapshot.second)
                throw DiagnosticError("CLOCK_UNAVAILABLE")
        }
        return response
    }

    fun senderClockId(): String = recorder.clockId

    @Synchronized fun senderEnded(claim: Claim) {
        expire()
        val current = requireClaim(claim)
        if (current.state == "frozen") return
        if (current.state == "binding") freeze(current, "setup_failed")
        else if (current.receiverFinished)
            freeze(current, if (current.receiverCoverageLost) "coverage_lost" else "session_ended")
        else current.state = "draining"
    }

    @Synchronized fun tick() = expire()

    @Synchronized fun end(owner: String, pairId: String): JSONObject {
        expire()
        val current = requirePair(pairId)
        if (current.state != "frozen" && current.owner != owner) throw DiagnosticError("NOT_OWNER")
        if (current.state != "frozen") freeze(current, "requested")
        return context(current)
    }

    @Synchronized fun disconnected(owner: String, pairId: String, armGeneration: Long,
                                   reason: String = "client_disconnected") {
        expire()
        val current = arm ?: return
        if (current.owner == owner && current.id == pairId && current.generation == armGeneration &&
            current.state != "frozen") freeze(current, reason)
    }

    @Synchronized fun status(owner: String, pairId: String): JSONObject {
        expire()
        val current = requirePair(pairId)
        if (current.state != "frozen" && current.owner != owner) throw DiagnosticError("NOT_OWNER")
        return context(current)
    }

    @Synchronized fun statusCurrent(owner: String): JSONObject {
        expire()
        val current = arm ?: return JSONObject().put("state", "idle")
        if (current.state != "frozen" && current.owner != owner) throw DiagnosticError("NOT_OWNER")
        return context(current)
    }

    @Synchronized fun connections(owner: String): JSONObject {
        expire()
        val current = arm ?: return JSONObject().put("state", "idle")
        if (current.state != "frozen" && current.owner != owner) throw DiagnosticError("NOT_OWNER")
        return JSONObject().put("state", current.state).put("pairId", current.id)
            .put("armGeneration", current.generation.toString())
            .put("session", current.session?.toString() ?: JSONObject.NULL)
            .put("senderCaptureId", current.captureId).put("senderClockId", recorder.clockId)
            .put("receiverCaptureId", current.receiverCaptureId ?: JSONObject.NULL)
            .put("receiverClockId", current.receiverClockId ?: JSONObject.NULL)
    }

    @Synchronized fun clear(owner: String, pairId: String): JSONObject {
        expire()
        val current = requirePair(pairId)
        if (current.state != "frozen") {
            if (current.owner != owner) throw DiagnosticError("NOT_OWNER")
            throw DiagnosticError("CAPTURE_ACTIVE")
        }
        recorder.clear(current.captureId)
        arm = null
        return JSONObject().put("pairId", pairId)
    }

    @Synchronized fun revoke() {
        arm?.valid?.set(false)
        arm = null
        recorder.revoke()
    }

    private fun expire() {
        val current = arm ?: return
        val now = clock()
        if (current.state != "frozen" && now >= current.expiresNs) freeze(current, "lease_expired")
        val deadline = current.terminalDeadlineNs.get()
        if (current.state != "frozen" && deadline > 0 && now >= deadline)
            freeze(current, "terminal_deadline")
        if (current.state == "frozen" && now - checkNotNull(current.frozenAt) >= 120_000_000_000L) {
            recorder.tick()
            arm = null
        }
    }

    private fun freeze(current: Arm, reason: String) {
        current.valid.set(false)
        current.clockRequest = null
        current.receiver?.closeUnconfirmed(discard = true)
        if (current.receiver != null &&
            (current.receiverOutcome == null || current.receiverOutcome == UNCONFIRMED_OUTCOME))
            current.receiverOutcome = current.receiver?.committedOutcome() ?: UNCONFIRMED_OUTCOME
        val (ended, frozenAt) = recorder.finishPairedSender(current.captureId, reason)
        current.state = "frozen"
        current.reason = if (ended.getString("reason") == "lease_expired") "lease_expired" else reason
        if (current.reason != "setup_failed") {
            current.setupStage = null
            current.setupFailure = null
        }
        current.frozenAt = frozenAt
    }

    private fun requirePair(id: String): Arm = arm?.takeIf { it.id == id }
        ?: throw DiagnosticError("CAPTURE_NOT_FOUND")

    private fun requireClaim(claim: Claim): Arm {
        val current = requirePair(claim.pairId)
        if (current.generation != claim.generation || current.captureId != claim.senderCaptureId ||
            current.session != claim.session) throw DiagnosticError("CAPTURE_NOT_FOUND")
        return current
    }

    private fun claim(current: Arm) = Claim(current.id, current.generation, checkNotNull(current.session),
        current.captureId, current.receiverFlags, current.periodMs, current.expiresNs, current.valid,
        current.bootstrapAccepted, current.terminalCommitted, current.terminalDeadlineNs, current.senderFinished)

    private fun fetchContext(current: Arm) = context(current).apply {
        remove("setupFailure")
        put("setupFailureAvailable", current.setupFailure != null)
        if (!isNull("bootstrap")) put("bootstrap", JSONObject().put("complete", true).put("loss", false))
        getJSONObject("receiverRole").remove("outcome")
        remove("senderFlags")
        remove("receiverFlags")
        remove("startedNs")
        remove("expiresNs")
    }

    private fun context(current: Arm) = JSONObject().put("state", current.state).put("pairId", current.id)
        .put("armGeneration", "${current.generation}").put("session", current.session?.toString() ?: JSONObject.NULL)
        .put("senderCaptureId", current.captureId).put("senderClockId", recorder.clockId)
        .put("receiverCaptureId", current.receiverCaptureId ?: JSONObject.NULL)
        .put("receiverClockId", current.receiverClockId ?: JSONObject.NULL)
        .put("bootstrap", current.receiver?.bootstrap() ?: JSONObject.NULL)
        .put("senderFlags", JSONArray(current.senderFlags.sorted())).put("receiverFlags", JSONArray(current.receiverFlags.sorted()))
        .put("startedNs", "${current.startedNs}").put("expiresNs", "${current.expiresNs}")
        .put("endReason", current.reason ?: JSONObject.NULL).put("setupStage", current.setupStage ?: JSONObject.NULL)
        .put("setupFailure", current.setupFailure?.json() ?: JSONObject.NULL)
        .put("senderRole", JSONObject().put("state",
            if (current.state == "frozen" || current.senderFinished.get()) "finished" else "active")
            .put("ownershipCleanup", "unknown"))
        .put("receiverRole", JSONObject().put("state",
            if (current.receiver == null) "unavailable"
            else if (current.state == "frozen" || current.receiverFinished) "finished"
            else if (current.receiverFinishing) "finishing" else "active")
            .put("outcome", current.receiverOutcome?.let { JSONObject(it) } ?: JSONObject.NULL)
            .put("ownershipCleanup", "unknown"))
}

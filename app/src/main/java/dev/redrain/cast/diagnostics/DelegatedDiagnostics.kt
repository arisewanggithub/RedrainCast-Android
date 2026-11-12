package dev.redrain.cast.diagnostics

import org.screencopy.receiver.protocol.DiagnosticDiscovery
import org.screencopy.receiver.protocol.DiagnosticSessionAccess
import java.util.concurrent.Executor
import java.net.InetAddress

/** Only the admitted SCPY control owner calls this; no settings are changed. */
internal class DelegatedDiagnostics(
    private val recorder: DiagnosticRecorder,
    private val executor: Executor,
    private val permitted: () -> Boolean,
    private val localMode: () -> Boolean,
    private val open: (() -> Boolean, () -> Unit) -> DiagnosticListener,
    private val clock: () -> Long = System::nanoTime,
    private val localReady: () -> Boolean = { true },
    private val localGeneration: () -> Long = { 0 }
) : DiagnosticSessionAccess {
    private var generation = 0L
    private var session: Long? = null
    private var active = false
    private var deadline: Long? = null
    private var listener: DiagnosticListener? = null
    private var report: ((String) -> Unit)? = null
    private var ownerActive: () -> Boolean = { false }
    private class LocalReport(
        val session: Long, val consent: Long, val ownerActive: () -> Boolean,
        val report: (String) -> Unit, var published: Boolean = false, var invalidated: Boolean = false
    )
    private var localReport: LocalReport? = null
    val discovery: DiagnosticDiscovery?
        get() {
            tick()
            return synchronized(this) { listener }?.discovery
        }
    fun discoveryFor(address: InetAddress): DiagnosticDiscovery? {
        tick()
        return synchronized(this) { listener }?.discoveryFor(address)
    }
    val status: String?
        get() {
            tick()
            return synchronized(this) {
                session?.let { "Delegated receiver session: $it\n" +
                    "LAN TCP endpoint: ${listener?.endpoint ?: "unavailable"}\n" +
                    "Access: ${if (active) "enabled; awaiting explicit PC START" else "frozen retrieval only"}" }
            }
        }

    override fun admitted(session: Long, requested: Boolean, ownerActive: () -> Boolean, report: (String) -> Unit) {
        val epoch = synchronized(this) {
            reset()
            if (localMode()) {
                if (requested) localReport = LocalReport(session, localGeneration(), ownerActive, report)
                null
            } else if (!requested) null else {
                this.session = session
                active = true
                this.ownerActive = ownerActive
                this.report = report
                recorder.restrictToReceiver(session, ownerActive)
                generation
            }
        }
        if (epoch == null) {
            localListenerChanged()
            return
        }
        if (!permitted()) { fail(epoch); return }
        try {
            executor.execute {
                val current = synchronized(this) { epoch == generation && active && !localMode() }
                if (!current) return@execute
                if (!ownerActive()) { ended(session); return@execute }
                try {
                    if (!permitted()) { fail(epoch); return@execute }
                    val published = synchronized(this) {
                        if (epoch == generation && active && !localMode() && permitted() && ownerActive()) {
                            listener = open({ allowed(epoch) }, { tick() })
                            true
                        } else false
                    }
                    if (published) {
                        val currentOwner = synchronized(this) { epoch == generation && active && !localMode() }
                        if (currentOwner && ownerActive()) report("enabled")
                    }
                } catch (_: Exception) { fail(epoch) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { fail(epoch) }
    }

    /** Binding/consent notifications never invoke the admitted owner under our lock. */
    fun localListenerChanged() {
        val notification = synchronized(this) {
            val current = localReport ?: return
            if (current.invalidated) return
            if (!current.ownerActive()) { localReport = null; return }
            if (!localMode() || current.consent != localGeneration()) {
                current.invalidated = true
                current to "unavailable"
            } else if (localReady() && !current.published) {
                current.published = true
                current to "local"
            } else null
        } ?: return
        val (current, state) = notification
        val valid = synchronized(this) {
            localReport === current && (state != "local" ||
                localMode() && current.consent == localGeneration())
        }
        if (valid && current.ownerActive()) current.report(state)
    }

    private fun allowed(epoch: Long): Boolean {
        val retired = synchronized(this) { session?.takeIf { active && !ownerActive() } }
        retired?.let { ended(it) }
        return synchronized(this) {
            epoch == generation && session != null && !localMode() && permitted() &&
                (active || deadline?.let { clock() < it } == true)
        }
    }

    private fun fail(epoch: Long) {
        val notification = synchronized(this) {
            if (epoch != generation) return
            report.also { reset() }
        }
        notification?.invoke("unavailable")
    }

    override fun ended(session: Long) {
        val notification = synchronized(this) {
            if (localReport?.session == session) localReport = null
            if (this.session != session || !active) return
            active = false
            recorder.endDelegated(session)
            deadline = clock() + 120_000_000_000L
            report
        }
        notification?.invoke("frozen")
    }
    override fun revoked(session: Long) = ended(session)

    fun tick() {
        val (epoch, retired, bound) = synchronized(this) {
            if (session == null) return
            Triple(generation, session?.takeIf { active && !ownerActive() }, listener)
        }
        retired?.let { ended(it) }
        if (!permitted()) { fail(epoch); return }
        synchronized(this) {
            if (epoch != generation) return
            if (deadline?.let { clock() >= it } == true) { reset(); return }
        }
        if (bound != null && bound.discovery == null) fail(epoch)
    }

    /** Local explicit access supersedes delegation; it must not inherit its scope/history. */
    @Synchronized fun reset() {
        generation++
        localReport = null
        val owned = session != null
        session = null
        active = false
        deadline = null
        report = null
        ownerActive = { false }
        listener?.close()
        listener = null
        if (owned) { recorder.revoke(); recorder.unrestricted() }
    }
}

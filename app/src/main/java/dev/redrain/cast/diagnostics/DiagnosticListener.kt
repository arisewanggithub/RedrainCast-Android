package dev.redrain.cast.diagnostics

import android.content.Context
import dev.redrain.cast.LocalNetworkPermission
import java.net.InetSocketAddress
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Separate plain LAN TCP socket and budgets; never uses a casting executor, timer or socket. */
internal class DiagnosticListener(
    private val recorder: DiagnosticRecorder, private val identity: DiagnosticIdentity,
    private val collectorClosed: () -> Unit = {},
    private val accessAllowed: () -> Boolean = { true },
    private val accessRevoked: () -> Unit = {},
    private val discoveryPort: Int? = null,
    armRegistry: DiagnosticArmRegistry? = null,
    private val protocolClock: () -> Long = System::nanoTime
) : AutoCloseable {
    constructor(context: Context, recorder: DiagnosticRecorder,
        identity: DiagnosticIdentity = DiagnosticIdentity.android(context), accessRevoked: () -> Unit) :
        this(recorder, identity, accessAllowed = {
            LocalNetworkPermission.granted(context.applicationContext)
        }, accessRevoked = accessRevoked, discoveryPort = DiagnosticEndpointResponder.PORT)
    private val closed = AtomicBoolean()
    private val pending = AtomicInteger()
    private val connected = AtomicBoolean()
    private val registry = armRegistry ?: if (recorder === RemoteDiagnostics.recorder) DiagnosticAccess.arms
        else DiagnosticArmRegistry(recorder)
    private class Peer(val transport: Socket) { val reason = AtomicReference<String?>(null) }
    private val sockets = ConcurrentHashMap<Socket, Peer>()
    private val workers = Executors.newFixedThreadPool(3)
    private val timer = Executors.newScheduledThreadPool(2)
    private val listener: ServerSocket
    private var endpointResponder: DiagnosticEndpointResponder? = null
    internal val endpointDiscoveryPort: Int? get() = endpointResponder?.localPort?.takeIf { it > 0 }
    val endpoint: String
    val discovery: org.screencopy.receiver.protocol.DiagnosticDiscovery?
        get() {
            val port = listener.localPort
            return if (port in 1..65535 && !closed.get() && !listener.isClosed && listener.isBound && allowed())
                org.screencopy.receiver.protocol.DiagnosticDiscovery(port, 2) else null
        }
    fun discoveryFor(address: InetAddress) = if (address == identity.address) discovery else null
    val collectorConnected: Boolean get() = connected.get()
    private fun allowed() = accessAllowed() && identity.valid()

    init {
        var opened: ServerSocket? = null
        try {
            listener = ServerSocket()
            opened = listener
            listener.bind(InetSocketAddress(identity.address, 0), 3)
            endpoint = "${identity.address.hostAddress}:${listener.localPort}"
            if (discoveryPort != null) {
                try {
                    endpointResponder = DiagnosticEndpointResponder(identity.address, discoveryPort, { discovery })
                } catch (e: java.io.IOException) {
                    android.util.Log.w("RemoteDiagnostics", "Unicast diagnostic discovery unavailable", e)
                }
            }
            timer.scheduleWithFixedDelay({
                if (!closed.get()) {
                    if (allowed()) {
                        recorder.tick()
                        registry.tick()
                    } else accessRevoked()
                }
            }, 100, 100, TimeUnit.MILLISECONDS)
            Thread(::accept, "remote-diagnostics-accept").apply { isDaemon = true; start() }
        } catch (e: Exception) {
            workers.shutdownNow(); timer.shutdownNow()
            try { opened?.close() } catch (_: java.io.IOException) {}
            endpointResponder?.close()
            identity.close()
            throw e
        }
    }

    private fun accept() {
        while (!closed.get()) {
            val transport = try { listener.accept() } catch (_: java.io.IOException) {
                val failed = !closed.get()
                revokeNetwork()
                if (failed) accessRevoked()
                return
            }
            if (closed.get()) {
                try { transport.close() } catch (_: java.io.IOException) {}
                continue
            }
            if (pending.incrementAndGet() > 2) {
                pending.decrementAndGet()
                try { transport.close() } catch (_: java.io.IOException) {}
                continue
            }
            val socket = transport
            sockets[socket] = Peer(transport)
            try { workers.execute { serve(socket) } }
            catch (_: java.util.concurrent.RejectedExecutionException) {
                pending.decrementAndGet(); sockets.remove(socket); transport.close()
            }
        }
    }
    private fun deadline(socket: Socket, ms: Long, reason: String? = null,
                         protocol: DiagnosticProtocol? = null): ScheduledFuture<*> {
        val ownership = protocol?.ownership()
        return timer.schedule({
            val peer = sockets[socket]
            if (reason != null) peer?.reason?.compareAndSet(null, reason)
            try { peer?.transport?.close() } catch (_: java.io.IOException) {}
            if (reason != null && !closed.get()) protocol?.disconnected(ownership, reason)
        }, ms, TimeUnit.MILLISECONDS)
    }

    private fun serve(socket: Socket) {
        var acquired = false
        var pendingReleased = false
        var watchdog: ScheduledFuture<*>? = null
        var protocol: DiagnosticProtocol? = null
        try {
            socket.soTimeout = 5000
            socket.sendBufferSize = 16384
            watchdog = deadline(socket, 5000)
            val current = DiagnosticProtocol(recorder, clock = protocolClock, acquire = {
                val accepted = !closed.get() && allowed() && connected.compareAndSet(false, true)
                if (accepted) acquired = true
                accepted
            }, registry = registry).also { protocol = it }
            while (!closed.get()) {
                val bytes = DiagnosticProtocol.readLine(socket.inputStream) ?: break
                if (!allowed()) { accessRevoked(); break }
                val response = try { current.handle(bytes) } catch (e: IllegalArgumentException) {
                    break
                } catch (_: DiagnosticDeliveryExhausted) {
                    break
                }
                if (current.ready && !pendingReleased) {
                    watchdog.cancel(false)
                    pending.decrementAndGet(); pendingReleased = true
                    socket.soTimeout = 0
                }
                val writeDeadline = deadline(socket, 2000, if (acquired) "slow_client" else null, current)
                try {
                    socket.outputStream.write((response.toString() + "\n").toByteArray(Charsets.UTF_8))
                    socket.outputStream.flush()
                } finally { writeDeadline.cancel(false) }
                if (current.closing) break
            }
        } catch (_: java.io.IOException) {
            // A peer disconnect is expected and cannot affect casting.
        } catch (e: Exception) {
            if (!closed.get()) android.util.Log.e("RemoteDiagnostics", "Diagnostic listener failure", e)
            if (acquired && !closed.get()) protocol?.disconnected(protocol?.ownership(), "internal_error")
        } finally {
            watchdog?.cancel(false)
            if (!pendingReleased) pending.decrementAndGet()
            if (acquired) {
                if (!closed.get()) protocol?.disconnected(protocol?.ownership(),
                    sockets[socket]?.reason?.get() ?: "client_disconnected")
                connected.set(false)
                collectorClosed()
            }
            try { sockets.remove(socket)?.transport?.close() } catch (_: java.io.IOException) {}
            try { socket.close() } catch (_: java.io.IOException) {}
        }
    }

    fun revokeNetwork() {
        if (!closed.compareAndSet(false, true)) return
        registry.revoke()
        endpointResponder?.close()
        try { listener.close() } catch (_: java.io.IOException) {}
        sockets.values.forEach { try { it.transport.close() } catch (_: java.io.IOException) {} }
        workers.shutdownNow(); timer.shutdownNow()
    }

    override fun close() {
        revokeNetwork()
        identity.close()
    }
}

/** Consent is process-only and independent of Activity/overlay lifetime. */
object DiagnosticAccess {
    internal val arms = DiagnosticArmRegistry(RemoteDiagnostics.recorder)
    private val worker = Executors.newSingleThreadExecutor()
    internal fun dispatch(action: () -> Unit) { worker.execute(action) }
    private val disableObservers = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    fun onDisabled(generation: Long, observer: () -> Unit): AutoCloseable {
        disableObservers.add(observer)
        if (!enabled || generation != epoch) observer()
        return AutoCloseable { disableObservers.remove(observer) }
    }
    @Volatile private var listener: DiagnosticListener? = null
    @Volatile var enabled = false
        private set
    @Volatile var state = "Disabled"
        private set
    val discovery: org.screencopy.receiver.protocol.DiagnosticDiscovery?
        get() = if (enabled) listener?.discovery else null
    const val warning = "Anyone on this LAN can read bounded diagnostic metadata and start, end or clear diagnostic captures while enabled (or frozen for up to 120 seconds after a capture ends). An explicitly armed cast collects receiver evidence through the sender without a receiver settings change. Traffic is unencrypted and unauthenticated. No media contents or casting controls are exposed. Enable only on a trusted LAN; disable after use."
    val status: String get() = if (!enabled) state else listener?.let {
        "LAN TCP endpoint: ${it.endpoint}\nAccess: no pairing or authentication\n" +
            "Unicast discovery: ${if (it.endpointDiscoveryPort != null) "available (UDP 27184)" else "unavailable"}\n" +
            "Collector: ${if (it.collectorConnected) "connected" else "not connected"}\n" +
            "Capture: ${if (RemoteDiagnostics.recorder.collecting) "collecting" else "inactive/frozen"}"
    } ?: state
    @Volatile private var epoch = 0L
    val consentGeneration: Long get() = epoch

    fun setEnabled(context: Context, allow: Boolean) {
        setEnabled(context, allow) { DiagnosticIdentity.android(context.applicationContext) }
    }

    internal fun setEnabled(context: Context, allow: Boolean,
        expectedGeneration: Long? = null,
        identity: () -> DiagnosticIdentity) {
        val granted = LocalNetworkPermission.granted(context)
        val next = allow && granted
        val generation = synchronized(this) {
            if (expectedGeneration != null && expectedGeneration != epoch) return
            if (next == enabled) {
                if (allow && !granted) state = "Unavailable: local network permission required"
                return
            }
            ++epoch
            enabled = next
            if (!next) {
                // Gates close synchronously. Socket/key cleanup is independent of the UI/media lanes.
                arms.revoke()
                listener?.revokeNetwork()
                state = "Disabled"
            } else state = "Starting LAN listener…"
            epoch
        }
        if (!next) disableObservers.forEach { it() }
        val application = context.applicationContext
        worker.execute {
            listener?.close(); listener = null
            if (enabled && next && generation == epoch) {
                try {
                    val created = DiagnosticListener(application, RemoteDiagnostics.recorder, identity(), accessRevoked = {
                        setEnabled(application, false, generation) { DiagnosticIdentity.android(application) }
                    })
                    synchronized(this) {
                        if (enabled && generation == epoch) {
                            RemoteDiagnostics.recorder.grantConsent()
                            listener = created; state = "Enabled"
                            android.util.Log.i("RemoteDiagnostics",
                                "event=DIAGNOSTIC_LISTENER_STARTED endpoint=${created.endpoint} version=2 access=lan role=local")
                        } else created.close()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("RemoteDiagnostics", "Could not enable diagnostic listener", e)
                    val failed = synchronized(this) {
                        if (generation == epoch) {
                            enabled = false; state = "Unavailable: check LAN permission and local IPv4 network"
                            true
                        } else false
                    }
                    if (failed) disableObservers.forEach { it() }
                }
            }
        }
    }
}

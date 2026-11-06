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
import java.util.concurrent.atomic.AtomicBoolean
import org.screencopy.receiver.protocol.StreamRecoveryProtocol

/** Three plain TCP connections on a trusted LAN; the media token binds them to one session. */
class ReceiverLink(private val target: ReceiverTarget) : Closeable {
    private val sockets = Array(3) { Socket() }
    private val gates = Array(3) { Any() }
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val closed = AtomicBoolean()
    private val mediaClosed = AtomicBoolean()
    @Volatile private var attached = false
    @Volatile private var activeSession = 0L
    var videoRotation: Boolean = false
        private set
    var streamRecovery: Boolean = false
        private set

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

    fun connect(timeoutMillis: Long = 10_000) {
        try {
            deadline(timeoutMillis) {
                connectSocket(sockets[0])
                send(Wire.control(Wire.HELLO, 0, Wire.obj(
                    "role" to "sender", "versions" to JSONArray().put(3),
                    "name" to "RedrainCast", "videoRotation" to true, "streamRecovery" to true,
                    "streamRecoveryVersion" to StreamRecoveryProtocol.VERSION
                )))
                val hello = receive()
                reject(hello)
                if (hello.type != Wire.HELLO || hello.session != 0L)
                    throw IOException("PROTOCOL: receiver HELLO session zero required")
                val json = hello.json()
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
                if (json.has("streamRecovery") && json.opt("streamRecovery") !is Boolean)
                    throw IOException("PROTOCOL: streamRecovery must be boolean")
                streamRecovery = json.opt("streamRecovery") == true
                val recoveryVersion = if (json.has("streamRecoveryVersion")) Json.integer(json, "streamRecoveryVersion") else null
                if (recoveryVersion != null && recoveryVersion <= 0)
                    throw IOException("PROTOCOL: invalid stream recovery version")
                if (streamRecovery && recoveryVersion != StreamRecoveryProtocol.VERSION)
                    throw IOException("UNSUPPORTED_CONFIG: incompatible stream recovery protocol; update the receiver")
            }
        } catch (error: Exception) { close(); throw error }
    }

    /** Fixed-natural capture always needs presentation metadata, including future display rotations. */
    fun requireVideoRotation() {
        if (!videoRotation)
            throw IOException("UNSUPPORTED_CONFIG: receiver does not support videoRotation metadata required by fixed-natural capture")
    }

    fun attach(session: Long, acceptance: Wire.Message, withAudio: Boolean, timeoutMillis: Long = 10_000) {
        try {
            deadline(timeoutMillis) {
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
                if (actual != if (withAudio) listOf(1, 2) else listOf(1))
                    throw IOException("PROTOCOL: receiver did not accept exactly the requested tracks")
                val token = Json.string(json, "mediaToken")
                if (!token.matches(Regex("[0-9A-Fa-f]{64}")))
                    throw IOException("PROTOCOL: invalid media connection token")
                activeSession = session
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
        } catch (error: Exception) { close(); throw error }
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
            deadline(10_000) { Wire.write(sockets[lane].outputStream, message) }
        }
    }

    fun receive(): Wire.Message = Wire.read(sockets[0].inputStream)

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
            throw IOException("${Json.string(message.json(), "code")}: ${Json.string(message.json(), "message")}")
    }
}

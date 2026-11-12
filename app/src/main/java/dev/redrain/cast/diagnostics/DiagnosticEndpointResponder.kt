package dev.redrain.cast.diagnostics

import org.json.JSONObject
import org.screencopy.receiver.protocol.DiagnosticDiscovery
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

/** Unicast bootstrap only; no sampling, session identifiers, or casting work. */
internal class DiagnosticEndpointResponder(
    address: InetAddress,
    port: Int = PORT,
    private val endpoint: () -> DiagnosticDiscovery?,
    private val clock: () -> Long = System::nanoTime
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val socket = DatagramSocket(null)
    val localPort: Int get() = socket.localPort

    init {
        try {
            socket.bind(InetSocketAddress(address, port))
            Thread({
                var lastReply: Long? = null
                while (!closed.get()) {
                    try {
                        val request = DatagramPacket(ByteArray(SIZE + 1), SIZE + 1)
                        socket.receive(request)
                        val source = request.address
                        if (source !is Inet4Address ||
                            !(source.isSiteLocalAddress || address.isLoopbackAddress && source.isLoopbackAddress)) continue
                        val now = clock()
                        if (lastReply?.let { now - it < 250_000_000L } == true) continue
                        val nonce = nonce(request.data.copyOf(request.length)) ?: continue
                        val hint = endpoint() ?: continue
                        val bytes = JSONObject().put("protocol", PROTOCOL).put("version", 1)
                            .put("nonce", nonce).put("diagnostic", hint.json())
                            .toString().toByteArray(Charsets.UTF_8)
                        if (bytes.size > SIZE || endpoint() != hint || closed.get()) continue
                        lastReply = now
                        socket.send(DatagramPacket(bytes, bytes.size, source, request.port))
                    } catch (_: java.io.IOException) {
                        close()
                    } catch (_: IllegalArgumentException) {
                        // Malformed untrusted queries never reach the diagnostic or casting protocol.
                    }
                }
            }, "diagnostic-endpoint-discovery").apply { isDaemon = true; start() }
        } catch (e: Exception) {
            socket.close()
            throw e
        }
    }

    override fun close() {
        closed.set(true)
        socket.close()
    }

    companion object {
        const val PORT = 27184
        const val SIZE = 256
        const val PROTOCOL = "redraincast-diagnostics"

        internal fun nonce(bytes: ByteArray): String? {
            if (bytes.size != SIZE) return null
            return try {
                val json = org.screencopy.receiver.protocol.StrictJson.decode(bytes, maxDepth = 2, trackSizes = false)
                if (json.keys().asSequence().toSet() != setOf("protocol", "version", "nonce") ||
                    json.opt("protocol") != PROTOCOL || json.opt("version") != 1) return null
                (json.opt("nonce") as? String)?.takeIf { it.matches(Regex("[a-f0-9]{32}")) }
            } catch (_: Exception) { null }
        }
    }
}

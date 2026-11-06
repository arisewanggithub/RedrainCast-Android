package dev.screencopy.sender

import android.content.Context
import android.net.wifi.WifiManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.LinkProperties
import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

class Discovery(context: Context, private val update: (List<Receiver>) -> Unit, private val error: (String) -> Unit) : Closeable {
    data class Receiver(val name: String, val host: String, val port: Int, val state: String, val seen: Long,
        val instanceId: String? = null) {
        fun target() = ReceiverTarget(host, port, name)
        override fun toString() = "$host:$port - $name ($state)"
    }
    private val socket = DatagramSocket(null)
    private val lock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
        .createMulticastLock("screencopy-discovery").apply { setReferenceCounted(false) }
    @Volatile private var closed = false
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val networkVersion = java.util.concurrent.atomic.AtomicLong()
    private val signature = java.util.concurrent.atomic.AtomicReference("")
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { if (!closed) networkVersion.incrementAndGet() }
        override fun onLost(network: Network) { if (!closed) { signature.set(""); networkVersion.incrementAndGet() } }
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            val current = "${network.networkHandle}:${properties.interfaceName}:${properties.linkAddresses.joinToString()}"
            if (!closed && signature.getAndSet(current) != current) networkVersion.incrementAndGet()
        }
    }
    private var networkRegistered = false
    private val worker: Thread

    init {
        try {
            socket.reuseAddress = true; socket.bind(InetSocketAddress(27183)); socket.soTimeout = 1000
            lock.acquire()
            connectivity.registerDefaultNetworkCallback(networkCallback); networkRegistered = true
        } catch (e: Exception) { socket.close(); if (lock.isHeld) lock.release(); throw e }
        worker = Thread(::read, "receiver-discovery").also { it.start() }
        android.util.Log.i("ScreenCopySender", "event=DISCOVERY_STARTED udpPort=${socket.localPort} bindAddress=${socket.localAddress.hostAddress} multicastHeld=${lock.isHeld} networkCallback=$networkRegistered")
    }
    private fun read() {
        val directory = ReceiverDirectory()
        var network = networkVersion.get()
        var lastWarning = 0L
        var lastSummary = System.nanoTime()
        var packets = 0L
        var accepted = 0L
        var rejected = 0L
        while (!closed) {
            val beforeRead = networkVersion.get()
            if (beforeRead != network) { directory.networkChanged(); network = beforeRead }
            try {
                val packet = DatagramPacket(ByteArray(1025), 1025)
                socket.receive(packet)
                packets++
                if (packets == 1L)
                    android.util.Log.i("ScreenCopySender", "event=DISCOVERY_FIRST_PACKET source=${packet.address.hostAddress} udpSourcePort=${packet.port} bytes=${packet.length}")
                if (packet.length > 1024) throw IOException("Oversized discovery packet ignored")
                val receiver = decode(packet.data.copyOf(packet.length),
                    packet.address.hostAddress ?: throw IOException("Missing source address"), System.nanoTime())
                if (beforeRead == networkVersion.get()) {
                    directory.update(receiver); accepted++
                    if (accepted == 1L)
                        android.util.Log.i("ScreenCopySender", "event=DISCOVERY_FIRST_ACCEPT source=${receiver.host} tcpPort=${receiver.port} state=${receiver.state}")
                }
            } catch (e: SocketTimeoutException) {
                // Expire entries even on a quiet LAN.
            } catch (e: Exception) {
                rejected++
                if (!closed && System.nanoTime() - lastWarning > 5_000_000_000L) {
                    android.util.Log.w("ScreenCopySender", "event=DISCOVERY_READ_FAILED exception=${e.javaClass.simpleName} socketClosed=${socket.isClosed}")
                    error("Discovery unavailable or invalid advertisement. Retry and open Receiver on the same Wi-Fi.")
                    lastWarning = System.nanoTime()
                }
            }
            val now = System.nanoTime()
            if (networkVersion.get() != network) { directory.networkChanged(); network = networkVersion.get() }
            if (!closed) update(directory.snapshot(now))
            if (!closed && now - lastSummary >= 10_000_000_000L) {
                android.util.Log.i("ScreenCopySender", "event=DISCOVERY_RECEIVE packets=$packets accepted=$accepted rejected=$rejected receivers=${directory.snapshot(now).size} networkVersion=$network")
                lastSummary = now
            }
        }
    }
    override fun close() {
        closed = true; socket.close(); if (lock.isHeld) lock.release(); worker.interrupt()
        if (networkRegistered) { connectivity.unregisterNetworkCallback(networkCallback); networkRegistered = false }
        android.util.Log.i("ScreenCopySender", "event=DISCOVERY_STOPPED")
    }

    companion object {
        fun decode(bytes: ByteArray, source: String, now: Long): Receiver {
            if (bytes.size > 1024) throw IOException("Oversized discovery packet")
            val json = Wire.parseJson(bytes)
            if (Json.string(json, "protocol") != "screencopy" || Json.integer(json, "version") != Wire.VERSION)
                throw IOException("VERSION: discovery requires wire protocol version 3")
            val name = Json.string(json, "name")
            val port = Json.integer(json, "port")
            val state = Json.string(json, "state")
            ReceiverTarget(source, port, name)
            if (state !in setOf("available", "busy")) throw IOException("Invalid discovery state")
            val identity = if (json.has("instanceId")) Json.string(json, "instanceId") else null
            if (identity != null && !identity.matches(Regex("[A-Fa-f0-9]{32}")))
                throw IOException("Invalid discovery instance identity")
            return Receiver(name, source, port, state, now, identity)
        }
    }
}

package dev.redrain.cast.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dev.redrain.cast.LocalNetworkPermission
import java.net.Inet4Address
import java.net.InetAddress

/** Network binding only: LAN diagnostics intentionally has no identity or credentials. */
internal class DiagnosticIdentity(
    val address: InetAddress,
    private val isValid: () -> Boolean = { true },
    private val cleanup: () -> Unit = {}
) : AutoCloseable {
    fun valid(): Boolean = isValid()
    override fun close() = cleanup()

    companion object {
        fun android(context: Context): DiagnosticIdentity {
            check(LocalNetworkPermission.granted(context)) { "Local network permission required" }
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val network = cm.activeNetwork ?: error("Local network unavailable")
            val capabilities = cm.getNetworkCapabilities(network) ?: error("Local network unavailable")
            check(!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) { "Wi-Fi or Ethernet is required" }
            val address = cm.getLinkProperties(network)?.linkAddresses?.map { it.address }
                ?.filterIsInstance<Inet4Address>()?.firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }
                ?: error("Local IPv4 interface unavailable")
            return DiagnosticIdentity(address, isValid = {
                val current = cm.activeNetwork
                val currentCapabilities = current?.let { cm.getNetworkCapabilities(it) }
                current == network && currentCapabilities != null &&
                    !currentCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    (currentCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        currentCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
                    cm.getLinkProperties(network)?.linkAddresses?.any { it.address == address } == true
            })
        }
    }
}

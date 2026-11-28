package dev.redrain.cast.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dev.redrain.cast.LocalNetworkPermission
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean

/** Network bindings only: diagnostics intentionally has no identity or credentials. */
internal class DiagnosticIdentity(
    val address: InetAddress,
    private val isValid: () -> Boolean = { true },
    private val cleanup: () -> Unit = {},
    val endpoints: List<Endpoint> = listOf(Endpoint(address, false))
) : AutoCloseable {
    data class Endpoint(val address: InetAddress, val vpn: Boolean)
    fun valid(): Boolean = isValid()
    override fun close() = cleanup()

    companion object {
        fun android(context: Context): DiagnosticIdentity {
            check(LocalNetworkPermission.granted(context)) { "Local network permission required" }
            val cm = context.getSystemService(ConnectivityManager::class.java)
            return android(cm, ::interfaceHasAddress)
        }

        private data class Binding(
            val active: Network, val network: Network, val interfaceName: String, val address: Inet4Address,
            val vpn: Boolean
        )

        internal fun android(
            cm: ConnectivityManager,
            interfaceHasAddress: (String, Inet4Address, Boolean) -> Boolean
        ): DiagnosticIdentity {
            val bindings = selectBindings(cm, interfaceHasAddress)
                ?: error("No eligible LAN or VPN IPv4 interface")
            val revoked = AtomicBoolean()
            return DiagnosticIdentity(bindings.first().address, isValid = {
                if (!revoked.get() && selectBindings(cm, interfaceHasAddress) != bindings) revoked.set(true)
                !revoked.get()
            }, endpoints = bindings.map { Endpoint(it.address, it.vpn) })
        }

        @Suppress("DEPRECATION")
        private fun selectBindings(
            cm: ConnectivityManager,
            interfaceHasAddress: (String, Inet4Address, Boolean) -> Boolean
        ): List<Binding>? {
            val active = cm.activeNetwork ?: return null
            val activeCapabilities = cm.getNetworkCapabilities(active) ?: return null
            val networks = if (activeCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
                (cm.allNetworks.toList() + active).distinct() else listOf(active)
            val candidates = mutableListOf<Binding>()
            for (network in networks) {
                val caps = cm.getNetworkCapabilities(network)
                val vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                if (caps == null || !(vpn || caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) continue
                val link = cm.getLinkProperties(network) ?: return null
                val name = link.interfaceName?.takeIf { it.isNotBlank() } ?: return null
                candidates += link.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>()
                    .filter { eligibleAddress(it, vpn) }
                    .distinct().map { Binding(active, network, name, it, vpn) }
            }
            if (candidates.size !in 1..16 || candidates.map { it.address }.distinct().size != candidates.size ||
                candidates.any { !interfaceHasAddress(it.interfaceName, it.address, it.vpn) }) return null
            return candidates.sortedWith(compareBy({ it.address.hostAddress }, { it.interfaceName }))
        }

        internal fun eligibleAddress(address: Inet4Address, vpn: Boolean): Boolean =
            (address.address[0].toInt() and 255) in 1..223 &&
                !address.isLoopbackAddress && !address.isLinkLocalAddress &&
                (vpn || address.isSiteLocalAddress)

        private fun interfaceHasAddress(name: String, address: Inet4Address, vpn: Boolean): Boolean = try {
            val iface = NetworkInterface.getByName(name)
            iface != null && iface.isUp && !iface.isLoopback && (vpn || !iface.isVirtual) &&
                iface.inetAddresses.toList().contains(address)
        } catch (_: SocketException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}

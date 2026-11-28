package dev.redrain.cast.diagnostics

import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.net.Inet4Address
import java.net.InetAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@ConscryptMode(ConscryptMode.Mode.OFF)
class DiagnosticIdentityTest {
    private val cm = Mockito.mock(ConnectivityManager::class.java)
    private val vpn = Mockito.mock(Network::class.java)
    private val wifi = Mockito.mock(Network::class.java)
    private val ethernet = Mockito.mock(Network::class.java)
    private var active: Network? = vpn
    private val capabilities = mutableMapOf<Network, NetworkCapabilities>()
    private val links = mutableMapOf<Network, LinkProperties>()
    private val interfaces = mutableMapOf<String, Set<String>>()

    init {
        Mockito.`when`(cm.activeNetwork).thenAnswer { active }
        Mockito.`when`(cm.allNetworks).thenAnswer { capabilities.keys.toTypedArray() }
        for (network in listOf(vpn, wifi, ethernet)) {
            Mockito.`when`(cm.getNetworkCapabilities(network)).thenAnswer { capabilities[network] }
            Mockito.`when`(cm.getLinkProperties(network)).thenAnswer { links[network] }
        }
    }

    private fun network(network: Network, name: String, addresses: List<String>, vararg transports: Int) {
        capabilities[network] = Mockito.mock(NetworkCapabilities::class.java).apply {
            for (transport in listOf(NetworkCapabilities.TRANSPORT_VPN, NetworkCapabilities.TRANSPORT_WIFI,
                NetworkCapabilities.TRANSPORT_ETHERNET)) {
                Mockito.`when`(hasTransport(transport)).thenReturn(transport in transports)
            }
        }
        links[network] = Mockito.mock(LinkProperties::class.java).apply {
            Mockito.`when`(interfaceName).thenReturn(name)
            val linkAddresses = addresses.map { value ->
                Mockito.mock(LinkAddress::class.java).apply {
                    Mockito.`when`(address).thenReturn(InetAddress.getByName(value))
                }
            }
            Mockito.`when`(this.linkAddresses).thenReturn(linkAddresses)
        }
        interfaces[name] = addresses.toSet()
    }

    private fun identity() = DiagnosticIdentity.android(cm) { name, address, _ ->
        interfaces[name]?.contains(address.hostAddress) == true
    }

    private fun address(value: String) = InetAddress.getByName(value) as Inet4Address

    @Test fun activeVpnWithWifiBindsBothRealInterfacesAndRevokesOnChanges() {
        network(vpn, "tun0", listOf("100.64.1.2"),
            NetworkCapabilities.TRANSPORT_VPN, NetworkCapabilities.TRANSPORT_WIFI)
        network(wifi, "wlan0", listOf("192.168.1.2"), NetworkCapabilities.TRANSPORT_WIFI)
        val identity = identity()
        assertEquals(setOf("100.64.1.2", "192.168.1.2"),
            identity.endpoints.map { it.address.hostAddress }.toSet())
        assertTrue(identity.endpoints.single { it.address == address("100.64.1.2") }.vpn)
        assertTrue(identity.valid())
        network(wifi, "wlan0", listOf("192.168.1.3"), NetworkCapabilities.TRANSPORT_WIFI)
        assertFalse(identity.valid())
        network(wifi, "wlan0", listOf("192.168.1.2"), NetworkCapabilities.TRANSPORT_WIFI)
        assertFalse(identity.valid())
        val restored = identity()
        interfaces.remove("tun0")
        assertFalse(restored.valid())
        interfaces["tun0"] = setOf("100.64.1.2")
        val restoredAgain = identity()
        active = wifi
        assertFalse(restoredAgain.valid())
    }

    @Test fun vpnOnlyAndExistingPhysicalNetworkWorkButNoEligibleAddressFailsClosed() {
        network(vpn, "tun0", listOf("100.64.1.2"), NetworkCapabilities.TRANSPORT_VPN)
        val vpnOnly = identity()
        assertEquals("100.64.1.2", vpnOnly.address.hostAddress)
        capabilities.remove(vpn)
        assertFalse(vpnOnly.valid())
        network(vpn, "tun0", listOf("100.64.1.2"), NetworkCapabilities.TRANSPORT_VPN)
        active = wifi
        network(wifi, "wlan0", listOf("192.168.1.2"), NetworkCapabilities.TRANSPORT_WIFI)
        val local = identity()
        assertEquals(listOf("192.168.1.2"), local.endpoints.map { it.address.hostAddress })
        network(wifi, "wlan0", listOf("169.254.1.2", "8.8.8.8"), NetworkCapabilities.TRANSPORT_WIFI)
        assertFalse(local.valid())
        assertThrows(IllegalStateException::class.java) { identity() }
        active = null
        assertThrows(IllegalStateException::class.java) { identity() }
    }

    @Test fun ambiguityOrUnverifiedInterfaceNeverSilentlyDropsAnEndpoint() {
        network(vpn, "tun0", listOf("100.64.1.2"), NetworkCapabilities.TRANSPORT_VPN)
        network(wifi, "wlan0", listOf("192.168.1.2"), NetworkCapabilities.TRANSPORT_WIFI)
        val combined = identity()
        network(ethernet, "eth0", listOf("192.168.1.2"), NetworkCapabilities.TRANSPORT_ETHERNET)
        assertFalse(combined.valid())
        assertThrows(IllegalStateException::class.java) { identity() }
        network(ethernet, "eth0", listOf("192.168.2.2"), NetworkCapabilities.TRANSPORT_ETHERNET)
        interfaces.remove("eth0")
        assertThrows(IllegalStateException::class.java) { identity() }
    }

    @Test fun discoverySourcesAllowVpnUnicastButKeepPhysicalPrivateAndRejectInvalidAddresses() {
        val lan = address("192.168.1.2")
        val tunnel = address("100.64.1.2")
        assertTrue(DiagnosticEndpointResponder.eligibleSource(address("192.168.1.3"), lan, false))
        assertFalse(DiagnosticEndpointResponder.eligibleSource(address("100.64.1.3"), lan, false))
        assertTrue(DiagnosticEndpointResponder.eligibleSource(address("100.64.1.3"), tunnel, true))
        assertTrue(DiagnosticEndpointResponder.eligibleSource(address("8.8.8.8"), tunnel, true))
        for (invalid in listOf("0.0.0.0", "127.0.0.1", "169.254.1.1", "224.0.0.1", "255.255.255.255"))
            assertFalse(DiagnosticEndpointResponder.eligibleSource(address(invalid), tunnel, true))
    }
}

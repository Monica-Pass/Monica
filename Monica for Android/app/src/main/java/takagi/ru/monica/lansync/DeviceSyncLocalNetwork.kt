package takagi.ru.monica.lansync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Network
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** Only known peer addresses, local Wi-Fi gateways and interface broadcasts; no subnet scan. */
internal object DeviceSyncLocalNetwork {
    fun targets(context: Context, remembered: Set<String> = emptySet()): List<String> {
        val gateways = runCatching {
            val manager = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching emptyList()
            manager.allNetworks.filter { network ->
                manager.getNetworkCapabilities(network)?.let {
                    it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                } == true
            }.flatMap { manager.getLinkProperties(it)?.routes.orEmpty() }
                .mapNotNull { it.gateway?.hostAddress }
        }.getOrDefault(emptyList())
        val broadcasts = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback && !it.isPointToPoint }
                .flatMap { it.interfaceAddresses }.filter { local(it.address) }
                .mapNotNull { it.broadcast?.hostAddress }
        }.getOrDefault(emptyList())
        return (remembered + gateways + broadcasts).filter { parseLocal(it) != null }.distinct().take(32)
    }

    fun parseLocal(value: String): Inet4Address? {
        // Reject hostnames before resolving so discovery never performs DNS lookups.
        if (!value.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))) return null
        val octets = value.split('.').map { it.toIntOrNull() ?: return null }
        if (octets.any { it !in 0..255 }) return null
        return (InetAddress.getByAddress(octets.map { it.toByte() }.toByteArray()) as? Inet4Address)?.takeIf(::local)
    }

    private fun wifiNetworks(context: Context): List<Pair<Network, List<android.net.LinkAddress>>> = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching emptyList()
        // Respect an active VPN. Binding local sockets must not evade its routing policy.
        if (manager.getNetworkCapabilities(manager.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) return@runCatching emptyList()
        manager.allNetworks.filter {
            manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }.map { it to manager.getLinkProperties(it)?.linkAddresses.orEmpty() }
    }.getOrDefault(emptyList())

    fun networkFor(context: Context, target: InetAddress): Network? = wifiNetworks(context).firstOrNull { (_, addresses) ->
        addresses.any { link ->
            val local = link.address as? Inet4Address ?: return@any false
            if (target !is Inet4Address || target.isLoopbackAddress) return@any false
            val mask = -1 shl (32 - link.prefixLength.coerceIn(1, 32))
            fun bits(address: InetAddress) = address.address.fold(0) { bits, byte -> (bits shl 8) or (byte.toInt() and 255) }
            (bits(local) and mask) == (bits(target) and mask)
        }
    }?.first

    fun withNetworks(context: Context, request: JSONObject): JSONObject {
        val handles = JSONObject()
        wifiNetworks(context).forEach { (network, links) ->
            links.mapNotNull { it.address as? Inet4Address }.filter(::local).forEach {
                handles.put(it.hostAddress!!, network.networkHandle)
            }
        }
        return request.put("network_handles", handles)
    }

    fun signature(context: Context): String {
        val handles = withNetworks(context, JSONObject()).getJSONObject("network_handles")
        return handles.keys().asSequence().toList().sorted().joinToString { "$it:${handles.getLong(it)}" } +
            "/" + targets(context).sorted().joinToString()
    }

    private fun local(address: InetAddress): Boolean = address is Inet4Address &&
        (address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress)
}

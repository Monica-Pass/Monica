package takagi.ru.monica.lansync

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Coalesced route/interface hints only. Never requests or binds a network. */
internal class DeviceSyncNetworkObserver(context: Context) : AutoCloseable {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val mutableChanges = MutableStateFlow(0L)
    val changes: StateFlow<Long> = mutableChanges
    @Volatile private var active = false
    private var defaultRegistered = false
    private var wifiRegistered = false
    private val publish = Runnable { if (active) mutableChanges.value = mutableChanges.value + 1 }
    private fun changed() { if (active) { main.removeCallbacks(publish); main.postDelayed(publish, 150) } }
    private fun callback() = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = changed()
        override fun onLost(network: Network) = changed()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = changed()
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = changed()
    }
    private val default = callback()
    private val wifi = callback()

    @Synchronized fun start() {
        if (active) return
        active = true
        try { manager?.registerDefaultNetworkCallback(default, main); defaultRegistered = manager != null } catch (_: RuntimeException) { }
        try {
            val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
            manager?.registerNetworkCallback(request, wifi, main); wifiRegistered = manager != null
        } catch (_: RuntimeException) { }
        changed()
    }

    @Synchronized override fun close() {
        active = false
        main.removeCallbacks(publish)
        if (defaultRegistered) runCatching { manager?.unregisterNetworkCallback(default) }
        if (wifiRegistered) runCatching { manager?.unregisterNetworkCallback(wifi) }
        defaultRegistered = false; wifiRegistered = false
    }
}

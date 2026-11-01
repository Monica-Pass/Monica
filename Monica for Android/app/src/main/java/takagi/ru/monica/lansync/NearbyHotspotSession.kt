package takagi.ru.monica.lansync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import takagi.ru.monica.security.SessionManager
import java.net.Inet4Address

internal data class NearbyHotspotState(val phase: String = "idle", val networkName: String = "", val error: String? = null) {
    val hosting get() = phase == "hosting"
    val active get() = phase in setOf("starting", "hosting", "connecting", "joined")
    override fun toString() = "NearbyHotspotState($phase)"
}

/** Owns only an explicitly requested LocalOnlyHotspot reservation or Wi-Fi join request. */
internal class NearbyHotspotSession private constructor(context: Context) : DefaultLifecycleObserver {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(NearbyHotspotState())
    val state: StateFlow<NearbyHotspotState> = mutableState
    private var generation = 0L
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var request: ConnectivityManager.NetworkCallback? = null
    private var credentials: Pair<String, String>? = null
    private var joined: (() -> Unit)? = null
    private val expiry = Runnable { failed("connection_failed") }
    private fun allowed() = SessionManager.isUnlocked.value && DeviceSyncService.isAppVisible

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            SessionManager.isUnlocked.collect { if (!it) close() }
        }
    }

    override fun onStop(owner: LifecycleOwner) { close() }

    fun invitation(pairing: String): String? = credentials?.let {
        if (!allowed() || !state.value.hosting) null else NearbyHotspotInvitation.create(it.first, it.second, pairing)?.link
    }

    fun permissionDenied() { close(); mutableState.value = NearbyHotspotState(error = "permission_denied") }

    @Suppress("DEPRECATION")
    fun host(onReady: () -> Unit) {
        close()
        if (!allowed()) return
        if (!hasPermission()) { permissionDenied(); return }
        val wifi = app.getSystemService(WifiManager::class.java)
        if (wifi == null) { failed("unsupported"); return }
        val epoch = generation
        mutableState.value = NearbyHotspotState("starting")
        // Also bounds late callbacks if the platform never completes its request.
        main.postDelayed(expiry, 10 * 60_000L)
        try {
            wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(owned: WifiManager.LocalOnlyHotspotReservation) {
                    if (generation != epoch || !allowed() || state.value.phase != "starting") { owned.close(); return }
                    val configuration = owned.wifiConfiguration
                    val ssid = if (Build.VERSION.SDK_INT >= 30) owned.softApConfiguration.ssid else configuration?.SSID?.unquote()
                    val passphrase = if (Build.VERSION.SDK_INT >= 30) owned.softApConfiguration.passphrase else configuration?.preSharedKey?.unquote()
                    if (ssid.isNullOrEmpty() || passphrase.isNullOrEmpty()) { owned.close(); failed("unsupported"); return }
                    reservation = owned; credentials = ssid to passphrase
                    mutableState.value = NearbyHotspotState("hosting", ssid)
                    onReady()
                }
                override fun onFailed(reason: Int) { if (generation == epoch) failed(if (reason == ERROR_NO_CHANNEL) "unsupported" else "connection_failed") }
                override fun onStopped() { if (generation == epoch) failed("connection_failed") }
            }, main)
        } catch (_: SecurityException) { failed("permission_denied") }
        catch (_: RuntimeException) { failed("connection_failed") }
    }

    fun join(invitation: NearbyHotspotInvitation, onConnected: () -> Unit) {
        close()
        if (!allowed()) return
        if (Build.VERSION.SDK_INT < 29) { failed("unsupported"); return }
        if (!hasPermission()) { permissionDenied(); return }
        val manager = app.getSystemService(ConnectivityManager::class.java) ?: run { failed("unsupported"); return }
        val epoch = generation
        joined = onConnected
        val networkName = invitation.ssid
        mutableState.value = NearbyHotspotState("connecting", networkName)
        val callback = object : ConnectivityManager.NetworkCallback() {
            private var delivered = false
            fun connected(network: Network, links: LinkProperties?) {
                if (delivered || generation != epoch || !allowed()) return
                val capabilities = manager.getNetworkCapabilities(network) ?: return
                if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    links?.linkAddresses?.any { it.address is Inet4Address && !it.address.isLoopbackAddress } != true) return
                delivered = true
                mutableState.value = NearbyHotspotState("joined", networkName)
                val proceed = joined; joined = null
                main.removeCallbacks(expiry); main.postDelayed(expiry, 10 * 60_000L)
                proceed?.invoke()
            }
            override fun onAvailable(network: Network) { connected(network, manager.getLinkProperties(network)) }
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) { connected(network, properties) }
            override fun onLost(network: Network) { if (generation == epoch) failed("connection_failed") }
            override fun onUnavailable() { if (generation == epoch) failed("connection_failed") }
        }
        request = callback
        main.postDelayed(expiry, 120_000)
        try {
            val specifier = WifiNetworkSpecifier.Builder().setSsid(invitation.ssid).setWpa2Passphrase(invitation.passphrase).build()
            val network = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(specifier).build()
            manager.requestNetwork(network, callback, main, 120_000)
        } catch (_: SecurityException) { failed("permission_denied") }
        catch (_: RuntimeException) { failed("connection_failed") }
    }

    private fun failed(error: String) { close(); mutableState.value = NearbyHotspotState(error = error) }

    fun close() {
        generation++
        main.removeCallbacks(expiry)
        val owned = reservation; reservation = null
        val registered = request; request = null
        credentials = null; joined = null
        runCatching { owned?.close() }
        runCatching { registered?.let { app.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it) } }
        mutableState.value = NearbyHotspotState()
    }

    private fun hasPermission(): Boolean = requiredPermissions().all { ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED }
    private fun String.unquote(): String = if (length >= 2 && first() == '"' && last() == '"') substring(1, length - 1) else this
    companion object {
        @Volatile private var instance: NearbyHotspotSession? = null
        fun get(context: Context): NearbyHotspotSession = instance ?: synchronized(this) { instance ?: NearbyHotspotSession(context).also { instance = it } }
        fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}

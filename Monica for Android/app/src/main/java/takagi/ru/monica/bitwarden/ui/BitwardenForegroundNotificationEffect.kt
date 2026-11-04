package takagi.ru.monica.bitwarden.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.bitwarden.sync.runBitwardenForegroundNotifications
import takagi.ru.monica.security.SessionManager
import java.util.concurrent.atomic.AtomicBoolean

/** One instance at the unlocked main activity, independent of the selected vault/page. */
@Composable
internal fun BitwardenForegroundNotificationEffect(
    enabled: Boolean,
    onSyncRequested: (Long, () -> Boolean) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val repository = remember(context) { BitwardenRepository.getInstance(context) }
    val preferenceFlow = remember(repository) { repository.observeNotificationPreferences() }
    val preferences by preferenceFlow.collectAsState(initial = false to true)
    val autoSyncEnabled = preferences.first
    val wifiOnly = preferences.second
    val appUnlocked by SessionManager.isUnlocked.collectAsState()
    val appGeneration by SessionManager.lockGeneration.collectAsState()
    val requestSync by rememberUpdatedState(onSyncRequested)
    LaunchedEffect(context, lifecycle, enabled, autoSyncEnabled, wifiOnly, appUnlocked, appGeneration) {
        if (!enabled || !autoSyncEnabled || !appUnlocked) return@LaunchedEffect
        val generation = appGeneration
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            notificationNetworks(context, wifiOnly).collectLatest { network ->
                if (network != null) {
                    val active = AtomicBoolean(true)
                    try {
                        runBitwardenForegroundNotifications(repository,
                            networkAllowed = {
                                active.get() && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                                    SessionManager.isUnlocked.value && SessionManager.lockGeneration.value == generation &&
                                    repository.isAutoSyncEnabled && notificationNetwork(context, repository.isSyncOnWifiOnly) == network
                            },
                            requestSync = { vaultId, stillValid -> requestSync(vaultId, stillValid) })
                    } finally { active.set(false) }
                }
            }
        }
    }
}

/** Uses the same active-network/Wi-Fi policy as the sync orchestrator, including VPN handling. */
internal fun notificationNetwork(context: Context, wifiOnly: Boolean): Network? {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
    val network = manager.activeNetwork ?: return null
    val capabilities = manager.getNetworkCapabilities(network) ?: return null
    if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return null
    if (wifiOnly && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
    return network
}

private fun notificationNetworks(context: Context, wifiOnly: Boolean) = callbackFlow {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    if (manager == null) { trySend(null); close(); return@callbackFlow }
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { trySend(notificationNetwork(context, wifiOnly)) }
        override fun onLost(network: Network) { trySend(notificationNetwork(context, wifiOnly)) }
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            trySend(notificationNetwork(context, wifiOnly))
        }
    }
    try {
        manager.registerDefaultNetworkCallback(callback)
        trySend(notificationNetwork(context, wifiOnly))
    } catch (_: RuntimeException) {
        trySend(null)
        close()
    }
    awaitClose { runCatching { manager.unregisterNetworkCallback(callback) } }
}.distinctUntilChanged()

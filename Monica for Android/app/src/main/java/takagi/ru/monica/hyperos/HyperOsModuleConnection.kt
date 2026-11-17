package takagi.ru.monica.hyperos

import android.os.Build
import io.github.libxposed.service.MonicaFrameworkPreferences
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import takagi.ru.monica.hyperos.hooks.HookPolicy

internal data class HyperOsModuleState(
    val connected: Boolean = false,
    val supported: Boolean = false,
    val options: Set<String> = emptySet(),
    val scopes: Set<String> = emptySet(),
)

/** Lazy: opening the compatibility page is the first caller; normal startup never binds. */
internal object HyperOsModuleConnection {
    private val connections = MutableStateFlow<List<XposedService>>(emptyList())
    val changes = connections.asStateFlow()
    private val writeLock = Mutex()
    private var registered = false
    @Synchronized fun register() {
        if (registered) return
        registered = true
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) { synchronized(connections) { connections.value = (connections.value + service).distinct() } }
            override fun onServiceDied(service: XposedService) { synchronized(connections) { connections.value = connections.value.filterNot { it === service } } }
        })
    }
    private fun service(): XposedService? = connections.value.singleOrNull()?.takeIf {
        it.apiVersion >= 102 && (it.frameworkProperties and XposedService.PROP_CAP_REMOTE) != 0L &&
            (it.frameworkProperties and XposedService.PROP_CAP_SYSTEM) != 0L
    }
    suspend fun read(): HyperOsModuleState = withContext(Dispatchers.IO) {
        runCatching {
            val remote = service() ?: return@withContext HyperOsModuleState()
            val prefs = MonicaFrameworkPreferences.read(remote, HookPolicy.GROUP).all
            HyperOsModuleState(true, HookPolicy.supported(Build.VERSION.SDK_INT, android.os.Process.myUid() / 100000, Build.MANUFACTURER),
                HookPolicy.KEYS.filter { HookPolicy.enabled(prefs, it) }.toSet(), remote.scope.toSet())
        }.getOrDefault(HyperOsModuleState())
    }
    suspend fun set(key: String, enabled: Boolean) = writeLock.withLock {
        withContext(Dispatchers.IO) {
            require(key in HookPolicy.KEYS)
            require(android.os.Process.myUid() / 100000 == 0)
            if (enabled) require(HookPolicy.supported(Build.VERSION.SDK_INT, 0, Build.MANUFACTURER))
            val remote = requireNotNull(service())
            val prefs = MonicaFrameworkPreferences.read(remote, HookPolicy.GROUP)
            check(prefs.edit().putInt("user_id", 0).putBoolean(key, enabled).commit())
            check(HookPolicy.enabled(MonicaFrameworkPreferences.read(remote, HookPolicy.GROUP).all, key) == enabled)
        }
    }
}

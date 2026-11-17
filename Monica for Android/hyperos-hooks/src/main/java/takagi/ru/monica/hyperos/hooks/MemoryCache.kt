// Adapted from HyperPasskey (GPL-3.0), see hyperos-builtins.txt.
package takagi.ru.monica.hyperos.hooks

import org.luckypray.dexkit.DexKitCacheBridge
import org.luckypray.dexkit.annotations.DexKitExperimentalApi
import java.util.concurrent.ConcurrentHashMap

@OptIn(DexKitExperimentalApi::class)
object MemoryCache : DexKitCacheBridge.Cache {
    private val values = ConcurrentHashMap<String, String>()
    private val lists = ConcurrentHashMap<String, List<String>>()

    override fun getString(key: String, default: String?): String? =
        values[key] ?: default

    override fun putString(key: String, value: String) {
        values[key] = value
    }

    override fun getStringList(key: String, default: List<String>?): List<String>? =
        lists[key] ?: default

    override fun putStringList(key: String, value: List<String>) {
        lists[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
        lists.remove(key)
    }

    override fun getAllKeys(): Collection<String> =
        values.keys + lists.keys

    override fun clearAll() {
        values.clear()
        lists.clear()
    }
}

package takagi.ru.monica.security

import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/** Decrypted view only. All durable edits go through LocalVaultRecovery's authenticated writer. */
internal class PasswordRecoveryPreferences(
    val selection: RecoveryStoreSelection.Password,
    values: Map<String, *>,
) : SharedPreferences {
    private val lock = Any()
    private var values = copyValues(values)
    private var retired = false
    private val listeners = ConcurrentHashMap.newKeySet<SharedPreferences.OnSharedPreferenceChangeListener>()

    companion object {
        private fun copyValues(values: Map<String, *>): Map<String, Any> = values.mapValues { (_, value) ->
            if (value is Set<*>) value.toSet() else requireNotNull(value)
        }
    }

    internal fun replace(values: Map<String, *>) {
        val changed = synchronized(lock) {
            check(!retired) { "Password recovery session retired" }
            val next = copyValues(values)
            val keys = (this.values.keys + next.keys).filter { this.values[it] != next[it] }
            this.values = next
            keys
        }
        if (changed.isNotEmpty() && listeners.isNotEmpty()) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                changed.forEach { key -> listeners.forEach { it.onSharedPreferenceChanged(this, key) } }
            }
        }
    }

    internal fun retire() = synchronized(lock) {
        retired = true
        values = emptyMap()
        listeners.clear()
    }

    private inline fun <T> read(action: () -> T): T = synchronized(lock) {
        check(!retired) { "Password recovery session retired" }
        action()
    }

    override fun getAll(): MutableMap<String, *> = read { copyValues(values).toMutableMap() }
    override fun contains(key: String?) = read { values.containsKey(key) }
    override fun getString(key: String?, defValue: String?): String? = read { values[key] as String? ?: defValue }
    override fun getBoolean(key: String?, defValue: Boolean) = read { values[key] as Boolean? ?: defValue }
    override fun getInt(key: String?, defValue: Int) = read { values[key] as Int? ?: defValue }
    override fun getLong(key: String?, defValue: Long) = read { values[key] as Long? ?: defValue }
    override fun getFloat(key: String?, defValue: Float) = read { values[key] as Float? ?: defValue }
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = read {
        (values[key] as? Set<*>)?.map { it as String }?.toMutableSet() ?: defValues?.toMutableSet()
    }
    override fun edit(): SharedPreferences.Editor = error("Password recovery edits require the canonical writer")
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        listener?.let(listeners::add)
    }
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        listener?.let(listeners::remove)
    }
}

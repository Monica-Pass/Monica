package takagi.ru.monica.security

import android.content.SharedPreferences
import org.json.JSONObject
import takagi.ru.monica.security.RecoverableBitwardenSettings.SNAPSHOT
import takagi.ru.monica.security.RecoverableBitwardenSettings.booleans
import takagi.ru.monica.security.RecoverableBitwardenSettings.longs

/** The canonical encrypted snapshot is the only backing store in password recovery mode. */
internal class PasswordBitwardenSettings(private val security: SecurityManager) : SharedPreferences {
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()
    private fun snapshot(): JSONObject = security.getProtectedString(SNAPSHOT)?.let(::JSONObject) ?: JSONObject()
    override fun getAll(): MutableMap<String, *> = snapshot().let { json ->
        (longs + booleans).filter(json::has).associateWith { key ->
            if (key in longs) json.getLong(key) else json.getBoolean(key)
        }.toMutableMap()
    }
    override fun contains(key: String?) = snapshot().has(key)
    override fun getLong(key: String?, defValue: Long) = snapshot().let { if (it.has(key)) it.getLong(key) else defValue }
    override fun getBoolean(key: String?, defValue: Boolean) = snapshot().let { if (it.has(key)) it.getBoolean(key) else defValue }
    override fun getString(key: String?, defValue: String?): String? { check(!contains(key)); return defValue }
    override fun getInt(key: String?, defValue: Int): Int { check(!contains(key)); return defValue }
    override fun getFloat(key: String?, defValue: Float): Float { check(!contains(key)); return defValue }
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
        check(!contains(key)); return defValues?.toMutableSet()
    }
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        listener?.let(listeners::add)
    }
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        listener?.let(listeners::remove)
    }
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val changes = linkedMapOf<String, Any?>()
        private var clear = false
        override fun putLong(key: String?, value: Long) = apply { require(key in longs); changes[requireNotNull(key)] = value }
        override fun putBoolean(key: String?, value: Boolean) = apply { require(key in booleans); changes[requireNotNull(key)] = value }
        override fun remove(key: String?) = apply { require(key in longs || key in booleans); changes[requireNotNull(key)] = null }
        override fun clear() = apply { clear = true }
        override fun putString(key: String?, value: String?): SharedPreferences.Editor = error("Unsupported Bitwarden option")
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = error("Unsupported Bitwarden option")
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = error("Unsupported Bitwarden option")
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = error("Unsupported Bitwarden option")
        override fun commit(): Boolean = runCatching {
            val changed = mutableSetOf<String>()
            val success = security.updateProtectedObject(SNAPSHOT) { json ->
                if (clear) { changed.addAll(json.keys().asSequence().toList()); changed.forEach(json::remove) }
                changes.forEach { (key, value) ->
                    if (json.opt(key) != value) changed.add(key)
                    if (value == null) json.remove(key) else json.put(key, value)
                }
            }
            if (success && changed.isNotEmpty()) android.os.Handler(android.os.Looper.getMainLooper()).post {
                changed.forEach { key -> listeners.forEach { it.onSharedPreferenceChanged(this@PasswordBitwardenSettings, key) } }
            }
            success
        }.getOrDefault(false)
        override fun apply() {
            if (!commit()) android.util.Log.w("BitwardenSettings", "Password settings could not be committed")
        }
    }
}

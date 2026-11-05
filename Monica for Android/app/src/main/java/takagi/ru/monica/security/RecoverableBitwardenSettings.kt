package takagi.ru.monica.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

/** Only UI/sync options live here; vaults and queued edits remain in their original database. */
internal object RecoverableBitwardenSettings {
    private val lock = Any()
    private const val SNAPSHOT = "bitwarden_settings_recovery_v1"
    private val longs = setOf("active_vault_id", "last_sync_time")
    private val booleans = setOf("auto_sync_enabled", "sync_on_wifi_only", "never_lock_bitwarden")

    fun open(context: Context): SharedPreferences = synchronized(lock) {
        val recovery = LocalVaultRecovery(context)
        val active = recovery.activeStore()
        val security = SecurityManager(context)
        val prefs = try {
            if (active != null) {
                // Use the same recovered Keystore generation, never recreate the lost legacy key.
                openRecovered(context, active, security)
            } else SecurePreferencesStore.openRaw(context, SecurePreferencesStore.BITWARDEN,
                MasterKey.DEFAULT_MASTER_KEY_ALIAS).preferences
        } catch (error: SecureStorageUnavailableException) {
            // Without verified Monica recovery there is no authority to replace this store.
            throw error
        }
        // Startup may read safe defaults before the recovered vault is authenticated.
        // Keep its original recovery envelope until a normal password/biometric unlock.
        if (active == null || security.isVaultRuntimeUnlocked()) mirror(prefs, security)
        object : SharedPreferences by prefs {
            override fun edit(): SharedPreferences.Editor {
                val editor = prefs.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun commit(): Boolean = synchronized(lock) {
                        if (recovery.activeStore() != active) return@synchronized false
                        try {
                            val success = editor.commit()
                            if (success && (active == null || security.isVaultRuntimeUnlocked())) mirror(prefs, security)
                            success
                        } catch (_: Exception) { false }
                    }
                    override fun apply() {
                        if (!commit()) android.util.Log.w("BitwardenSettings", "Settings mirror unavailable; originals retained")
                    }
                    // Delegation otherwise returns the underlying editor and bypasses commit().
                    override fun putString(key: String, value: String?) = apply { editor.putString(key, value) }
                    override fun putStringSet(key: String, values: MutableSet<String>?) = apply { editor.putStringSet(key, values) }
                    override fun putInt(key: String, value: Int) = apply { editor.putInt(key, value) }
                    override fun putLong(key: String, value: Long) = apply { editor.putLong(key, value) }
                    override fun putFloat(key: String, value: Float) = apply { editor.putFloat(key, value) }
                    override fun putBoolean(key: String, value: Boolean) = apply { editor.putBoolean(key, value) }
                    override fun remove(key: String) = apply { editor.remove(key) }
                    override fun clear() = apply { editor.clear() }
                }
            }
        }
    }

    private fun openRecovered(context: Context, active: Pair<String, String>, security: SecurityManager): SharedPreferences {
        val name = active.first + "_bitwarden"
        val prefs = SecurePreferencesStore.openRaw(context, name, active.second).preferences
        if (!prefs.getBoolean("recovery_initialized", false)) {
            val snapshot = security.getProtectedString(SNAPSHOT)?.let { JSONObject(it) }
            val editor = prefs.edit()
            longs.forEach { if (snapshot?.has(it) == true) editor.putLong(it, snapshot.getLong(it)) }
            booleans.forEach { if (snapshot?.has(it) == true) editor.putBoolean(it, snapshot.getBoolean(it)) }
            // Recovery never initiates a cloud write or restores an unlocked Bitwarden session.
            editor.putBoolean("auto_sync_enabled", false).putBoolean("never_lock_bitwarden", false)
                .putBoolean("recovery_initialized", true)
            check(editor.commit()) { "Cannot persist recovered Bitwarden settings" }
        }
        return prefs
    }

    private fun mirror(prefs: SharedPreferences, security: SecurityManager) {
        val snapshot = JSONObject()
        longs.forEach { if (prefs.contains(it)) snapshot.put(it, prefs.getLong(it, 0)) }
        booleans.forEach { if (prefs.contains(it)) snapshot.put(it, prefs.getBoolean(it, false)) }
        val encoded = snapshot.toString()
        if (security.getProtectedString(SNAPSHOT) != encoded) security.putProtectedString(SNAPSHOT, encoded)
    }
}

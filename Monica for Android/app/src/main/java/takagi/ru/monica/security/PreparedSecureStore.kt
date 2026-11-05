package takagi.ru.monica.security

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/** Reuses only a caller-accepted startup result, never a timed-out worker's result. */
internal object PreparedSecureStore {
    private const val KEYSET = "__androidx_security_crypto_encrypted_prefs_key_keyset__"
    private const val VALUES = "__androidx_security_crypto_encrypted_prefs_value_keyset__"
    @Volatile private var accepted: Entry? = null

    private class Entry(val directory: String, val opened: SecurePreferencesStore.Opened)

    fun cached(context: Context): SecurePreferencesStore.Opened? {
        val entry = accepted ?: return null
        if (entry.directory != context.applicationInfo.dataDir) return null
        val prefs = entry.opened.preferences as? CheckedPreferences ?: return null
        return if (runCatching { prefs.checkActive() }.isSuccess) entry.opened else {
            if (accepted === entry) accepted = null
            null
        }
    }

    fun open(context: Context): SecurePreferencesStore.Opened {
        cached(context)?.let { return it }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            throw SecureStorageUnavailableException(SecurePreferencesStore.MONICA, "SECURE_STORAGE_NOT_READY")
        }
        return fresh(context)
    }

    fun fresh(context: Context): SecurePreferencesStore.Opened {
        val directory = context.applicationInfo.dataDir
        val before = selector(directory)
        val opened = SecurePreferencesStore.open(context, SecurePreferencesStore.MONICA)
        check(before == selector(directory)) { "Secure storage changed while opening" }
        val name = LocalVaultRecovery(context).activeStore()?.first ?: SecurePreferencesStore.MONICA
        val raw = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        return opened.copy(preferences = CheckedPreferences(opened.preferences, directory, before, raw))
    }

    fun accept(context: Context, opened: SecurePreferencesStore.Opened) {
        (opened.preferences as CheckedPreferences).checkActive()
        accepted = Entry(context.applicationInfo.dataDir, opened)
    }

    fun invalidate() { accepted = null }

    fun unwrap(preferences: SharedPreferences): SharedPreferences {
        val checked = preferences as? CheckedPreferences ?: return preferences
        checked.checkActive()
        return checked.delegate
    }

    // Do not acquire LocalVaultRecovery's monitor here: a slow staged recovery can hold it.
    // Read the small atomic selector itself so another live process cannot keep the old store.
    private fun selector(directory: String): String? {
        val base = File(directory, "files/local-vault-recovery/active-store.json")
        val backup = File(base.path + ".bak")
        val file = if (backup.exists()) backup else base
        if (!file.exists()) return null
        require(file.length() in 1..4096) { "Invalid storage selector" }
        return file.inputStream().use { input ->
            val bytes = ByteArray(4097)
            var count = 0
            while (count < bytes.size) {
                val n = input.read(bytes, count, bytes.size - count)
                if (n < 0) break
                count += n
            }
            require(count in 1..4096)
            String(bytes, 0, count, Charsets.UTF_8)
        }
    }

    private class CheckedPreferences(
        val delegate: SharedPreferences,
        private val directory: String,
        private val selectorAtOpen: String?,
        private val raw: SharedPreferences,
    ) : SharedPreferences by delegate {
        private val keys = raw.getString(KEYSET, null)
        private val values = raw.getString(VALUES, null)

        fun checkActive() {
            if (selectorAtOpen != selector(directory) || keys != raw.getString(KEYSET, null) ||
                values != raw.getString(VALUES, null)) {
                invalidate()
                SecurityManager.clearRuntimeUnlockCache()
                SessionManager.markLocked()
                SecondarySessionManager.markLocked()
                throw SecureStorageUnavailableException(SecurePreferencesStore.MONICA, "SECURE_STORAGE_CHANGED")
            }
        }
        private inline fun <T> checked(action: () -> T): T {
            checkActive()
            return action().also { checkActive() }
        }
        override fun getAll(): MutableMap<String, *> = checked { delegate.all }
        override fun getString(key: String?, defValue: String?) = checked { delegate.getString(key, defValue) }
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) = checked { delegate.getStringSet(key, defValues) }
        override fun getBoolean(key: String?, defValue: Boolean) = checked { delegate.getBoolean(key, defValue) }
        override fun getInt(key: String?, defValue: Int) = checked { delegate.getInt(key, defValue) }
        override fun getLong(key: String?, defValue: Long) = checked { delegate.getLong(key, defValue) }
        override fun getFloat(key: String?, defValue: Float) = checked { delegate.getFloat(key, defValue) }
        override fun contains(key: String?) = checked { delegate.contains(key) }
        override fun edit(): SharedPreferences.Editor = checked {
            val editor = delegate.edit()
            object : SharedPreferences.Editor by editor {
                override fun putString(key: String?, value: String?) = apply { checkActive(); editor.putString(key, value) }
                override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { checkActive(); editor.putStringSet(key, values) }
                override fun putBoolean(key: String?, value: Boolean) = apply { checkActive(); editor.putBoolean(key, value) }
                override fun putInt(key: String?, value: Int) = apply { checkActive(); editor.putInt(key, value) }
                override fun putLong(key: String?, value: Long) = apply { checkActive(); editor.putLong(key, value) }
                override fun putFloat(key: String?, value: Float) = apply { checkActive(); editor.putFloat(key, value) }
                override fun remove(key: String?) = apply { checkActive(); editor.remove(key) }
                override fun clear() = apply { checkActive(); editor.clear() }
                override fun commit(): Boolean = checked { editor.commit() }
                override fun apply() { checked { editor.apply() } }
            }
        }
    }
}

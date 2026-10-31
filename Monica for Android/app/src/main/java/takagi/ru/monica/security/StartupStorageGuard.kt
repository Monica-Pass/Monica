package takagi.ru.monica.security

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import kotlinx.coroutines.CancellationException

/** Stop subsequent preference operations when an abandoned native call eventually returns. */
internal class StartupStorageGuard(base: Context) {
    @Volatile var abandoned = false
        private set

    fun abandon() = synchronized(this) { abandoned = true }
    /** Serialize withdrawal with the final, disk-only recovery selector publication. */
    fun publishWhileActive(operation: () -> Unit) = synchronized(this) {
        checkActive()
        operation()
    }
    fun checkActive() {
        if (abandoned) throw CancellationException("Secure startup attempt abandoned")
    }
    private inline fun <T> checked(operation: () -> T): T {
        checkActive()
        val result = operation()
        checkActive()
        return result
    }

    val context: Context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getApplicationInfo() = checked { super.getApplicationInfo() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = checked {
            val delegate = super.getSharedPreferences(name, mode)
            object : SharedPreferences by delegate {
                override fun getAll(): MutableMap<String, *> = checked { delegate.all }
                override fun getString(key: String?, defValue: String?): String? = checked { delegate.getString(key, defValue) }
                override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = checked { delegate.getStringSet(key, defValues) }
                override fun getBoolean(key: String?, defValue: Boolean) = checked { delegate.getBoolean(key, defValue) }
                override fun getInt(key: String?, defValue: Int) = checked { delegate.getInt(key, defValue) }
                override fun getLong(key: String?, defValue: Long) = checked { delegate.getLong(key, defValue) }
                override fun getFloat(key: String?, defValue: Float) = checked { delegate.getFloat(key, defValue) }
                override fun contains(key: String?) = checked { delegate.contains(key) }
                override fun edit(): SharedPreferences.Editor = checked {
                    val editor = delegate.edit()
                    object : SharedPreferences.Editor by editor {
                        override fun commit() = checked { editor.commit() }
                        override fun apply() = checked { editor.apply() }
                        // Keep fluent chains on the guarded editor, including AndroidX keyset writes.
                        override fun putString(key: String?, value: String?) = apply { checkActive(); editor.putString(key, value) }
                        override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { checkActive(); editor.putStringSet(key, values) }
                        override fun putBoolean(key: String?, value: Boolean) = apply { checkActive(); editor.putBoolean(key, value) }
                        override fun putInt(key: String?, value: Int) = apply { checkActive(); editor.putInt(key, value) }
                        override fun putLong(key: String?, value: Long) = apply { checkActive(); editor.putLong(key, value) }
                        override fun putFloat(key: String?, value: Float) = apply { checkActive(); editor.putFloat(key, value) }
                        override fun remove(key: String?) = apply { checkActive(); editor.remove(key) }
                        override fun clear() = apply { checkActive(); editor.clear() }
                    }
                }
            }
        }
    }
}

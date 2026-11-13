package takagi.ru.monica.passkey.hardware

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Device preference only: never changes software credentials or key material. */
internal object HardwarePasskeyPreferences {
    private const val FILE = "hardware_passkey_preferences"
    private const val ENABLED = "enabled"
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = runCatching { prefs(context).getBoolean(ENABLED, false) }.getOrDefault(false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(ENABLED, enabled).apply()
    }

    fun observe(context: Context) = callbackFlow {
        val preferences = prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == ENABLED || key == null) trySend(isEnabled(context))
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(isEnabled(context))
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()
}

package takagi.ru.monica.passkey.hardware

import android.content.Context

/** Restores the absent-key state as well as both explicit user choices. */
internal class HardwarePasskeyPreferenceFixture(private val context: Context) {
    private val preferences = context.getSharedPreferences("hardware_passkey_preferences", Context.MODE_PRIVATE)
    private val previous = if (preferences.contains("enabled")) preferences.getBoolean("enabled", false) else null

    fun removeChoice() { check(preferences.edit().remove("enabled").commit()) }

    fun restore() {
        val edit = preferences.edit()
        if (previous == null) edit.remove("enabled") else edit.putBoolean("enabled", previous)
        check(edit.commit())
    }
}

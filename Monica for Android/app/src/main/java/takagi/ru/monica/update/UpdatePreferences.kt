package takagi.ru.monica.update

import android.content.Context
import android.content.SharedPreferences
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Non-sensitive update preferences; no account, vault or credential state is stored here. */
class UpdatePreferences(context: Context) {
    private val appContext = context.applicationContext

    // Lazy access keeps disk-backed preference initialization off the composition thread.
    private fun preferences(): SharedPreferences = appContext.getSharedPreferences("app_updates", Context.MODE_PRIVATE)

    suspend fun read(): UpdatePreferencesState = mutex.withLock {
        withContext(Dispatchers.IO) {
            val preferences = preferences()
            UpdatePreferencesState(
                autoCheckEnabled = preferences.getBoolean(AUTO_CHECK, true),
                lastAttemptAtMillis = preferences.getLong(LAST_ATTEMPT, 0L),
                skippedReleaseIdentity = preferences.getString(SKIPPED_RELEASE, null),
            )
        }
    }

    suspend fun setAutoCheckEnabled(enabled: Boolean) = edit { putBoolean(AUTO_CHECK, enabled) }
    suspend fun recordAttempt(nowMillis: Long) = edit { putLong(LAST_ATTEMPT, nowMillis) }
    suspend fun skipRelease(identity: String) {
        require(identity.isNotBlank() && identity.length <= 256) { "Invalid release identity" }
        edit { putString(SKIPPED_RELEASE, identity) }
    }
    suspend fun clearSkippedRelease() = edit { remove(SKIPPED_RELEASE) }

    private suspend fun edit(change: SharedPreferences.Editor.() -> Unit): Unit = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!preferences().edit().apply(change).commit()) throw IOException("Unable to save update preferences")
        }
    }

    private companion object {
        val mutex = Mutex()
        const val AUTO_CHECK = "auto_check_enabled"
        const val LAST_ATTEMPT = "last_attempt_at_millis"
        const val SKIPPED_RELEASE = "skipped_release_identity"
    }
}

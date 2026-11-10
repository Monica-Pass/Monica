package takagi.ru.monica.update

import takagi.ru.monica.utils.UpdateCheckResult

enum class UpdateChannel {
    STANDARD, FDROID;

    companion object {
        fun defaultForApplicationId(applicationId: String): UpdateChannel =
            if (applicationId.endsWith(".fdroid")) FDROID else STANDARD
    }
}

data class UpdatePreferencesState(
    val autoCheckEnabled: Boolean = true,
    val lastAttemptAtMillis: Long = 0L,
    val skippedReleaseIdentity: String? = null,
)

/** Foreground checks only. The caller records an attempt before starting network work. */
object AppUpdatePolicy {
    const val CHECK_INTERVAL_MILLIS = 24L * 60L * 60L * 1000L

    fun shouldCheck(state: UpdatePreferencesState, nowMillis: Long, manual: Boolean = false): Boolean {
        if (manual) return true
        if (!state.autoCheckEnabled) return false
        val last = state.lastAttemptAtMillis
        // A changed wall clock must not disable checks indefinitely. Recording the new
        // attempt establishes a fresh baseline even when that request subsequently fails.
        return last <= 0L || nowMillis < last || nowMillis - last >= CHECK_INTERVAL_MILLIS
    }

    fun shouldPresent(result: UpdateCheckResult, state: UpdatePreferencesState, manual: Boolean = false): Boolean =
        manual || (state.autoCheckEnabled && result.isUpdateAvailable &&
            result.releaseIdentity != state.skippedReleaseIdentity)
}

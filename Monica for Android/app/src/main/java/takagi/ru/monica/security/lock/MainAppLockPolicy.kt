package takagi.ru.monica.security.lock

import android.content.Context
import takagi.ru.monica.security.SecurityManager

/**
 * Single decision point for Monica main-process startup authentication.
 *
 * Allowed callers:
 * - MainActivity cold-start bootstrap
 * - MainActivity foreground restoration
 *
 * Main-app projection of the shared developer verification policy.
 */
object MainAppLockPolicy {

    fun resolveAccessState(
        securityManager: SecurityManager,
        context: Context,
        autoLockMinutes: Int,
        disablePasswordVerification: Boolean
    ): MainAppAccessState {
        val firstTime = try {
            !securityManager.isMasterPasswordSet()
        } catch (_: takagi.ru.monica.security.SecureStorageUnavailableException) {
            return storageChangedState()
        }
        if (firstTime) {
            return MainAppAccessState(
                isFirstTime = true,
                bypassEnabled = false,
                canRestoreSession = false,
                reason = "first_time_setup_required"
            )
        }

        if (disablePasswordVerification) {
            return MainAppAccessState(
                isFirstTime = false,
                bypassEnabled = true,
                canRestoreSession = false,
                reason = "startup_password_verification_disabled"
            )
        }

        val canRestoreSession = try {
            securityManager.canRestoreMainAppSession(context, autoLockMinutes)
        } catch (_: takagi.ru.monica.security.SecureStorageUnavailableException) {
            return storageChangedState()
        }
        return MainAppAccessState(
            isFirstTime = false,
            bypassEnabled = false,
            canRestoreSession = canRestoreSession,
            reason = if (canRestoreSession) "restorable_session" else "authentication_required"
        )
    }

    private fun storageChangedState() = MainAppAccessState(
        isFirstTime = false,
        bypassEnabled = false,
        canRestoreSession = false,
        reason = "secure_storage_changed",
    )
}

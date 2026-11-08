package takagi.ru.monica.autofill_ng

import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.utils.SettingsManager

object AccountFillPolicy {
    fun resolveAccountIdentifier(
        entry: PasswordEntry,
        securityManager: SecurityManager
    ): String {
        if (AutofillPasswordSource.isNative(entry)) return entry.username
        return try {
            if (looksEncryptedPayload(entry.username)) {
                securityManager.decryptData(entry.username)
            } else {
                entry.username
            }
        } catch (_: Exception) {
            entry.username
        }
    }

    fun resolveAccountIdentifierForDisplay(entry: PasswordEntry): String {
        if (AutofillPasswordSource.isNative(entry)) return entry.username.trim()
        val candidate = entry.username.trim()
        if (candidate.isBlank() || looksEncryptedPayload(candidate)) {
            return ""
        }
        return candidate
    }

    fun shouldFillEmailWithAccount(context: Context): Boolean {
        return runCatching {
            runBlocking {
                SettingsManager(context).settingsFlow.first().separateUsernameAccountEnabled
            }
        }.getOrDefault(false)
    }

    private fun looksEncryptedPayload(value: String): Boolean {
        return value.startsWith("V2|") ||
            value.startsWith("MDK|") ||
            value.startsWith("C2|") ||
            (value.contains("==") && value.length > 20)
    }
}




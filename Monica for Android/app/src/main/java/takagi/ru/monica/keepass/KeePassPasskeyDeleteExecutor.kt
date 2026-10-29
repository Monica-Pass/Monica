package takagi.ru.monica.keepass

import android.util.Log
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.repository.KeePassCompatibilityBridge

class KeePassPasskeyDeleteExecutor(
    private val bridge: KeePassCompatibilityBridge?
) {
    suspend fun delete(
        passkey: PasskeyEntry,
        deleteLocal: suspend (PasskeyEntry) -> Unit
    ): Result<Unit> {
        if (KeePassMonicaPasswordStore.isUiId(passkey.id)) return runCatching { deleteLocal(passkey) }
        val databaseId = passkey.keepassDatabaseId
        if (databaseId != null && passkey.passkeyMode == PasskeyEntry.MODE_KEEPASS_COMPAT) {
            val keepassBridge = bridge ?: return Result.failure(
                IllegalStateException("KeePass bridge unavailable")
            )
            val deletedCount = keepassBridge.deleteLegacyPasskeys(
                databaseId = databaseId,
                passkeys = listOf(passkey)
            ).getOrElse { return Result.failure(it) }
            if (deletedCount <= 0) {
                Log.e(TAG, "KeePass passkey delete affected 0 entries for db=$databaseId")
                return Result.failure(IllegalStateException("KeePass passkey delete affected 0 entries"))
            }
        }

        deleteLocal(passkey)
        return Result.success(Unit)
    }

    private companion object {
        const val TAG = "KeePassPasskeyDelete"
    }
}

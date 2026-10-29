package takagi.ru.monica.keepass

import android.util.Log
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.repository.KeePassCompatibilityBridge
import takagi.ru.monica.utils.KeePassCustomFieldData

class KeePassPasswordUpdateExecutor(
    private val bridge: KeePassCompatibilityBridge?
) {
    suspend fun syncUpdatedEntry(
        existingEntry: PasswordEntry?,
        updatedEntry: PasswordEntry,
        resolvePassword: (PasswordEntry) -> String,
        customFields: List<KeePassCustomFieldData> = emptyList()
    ): Result<Unit> {
        return syncUpdatedEntry(
            existingEntry = existingEntry,
            updatedEntry = updatedEntry,
            resolvePassword = resolvePassword,
            customFields = customFields,
            persistUpdate = null
        )
    }

    suspend fun syncUpdatedEntry(
        existingEntry: PasswordEntry?,
        updatedEntry: PasswordEntry,
        resolvePassword: (PasswordEntry) -> String,
        customFields: List<KeePassCustomFieldData> = emptyList(),
        persistUpdate: (suspend (PasswordEntry) -> Unit)?
    ): Result<Unit> {
        val keepassBridge = bridge
        if (KeePassMonicaPasswordStore.isUiId(updatedEntry.id)) return runCatching {
            check(existingEntry?.keepassDatabaseId == updatedEntry.keepassDatabaseId) { "Use a copy to change original-vault ownership" }
            requireNotNull(persistUpdate).invoke(updatedEntry)
        }
        if (keepassBridge == null) {
            persistUpdate?.invoke(updatedEntry)
            return Result.success(Unit)
        }
        val oldKeepassId = existingEntry?.keepassDatabaseId
        val newKeepassId = updatedEntry.keepassDatabaseId

        if (newKeepassId != null) {
            val updateResult = keepassBridge.updateLegacyPasswordEntry(
                databaseId = newKeepassId,
                entry = updatedEntry,
                resolvePassword = resolvePassword,
                customFields = customFields
            )
            if (updateResult.isFailure) {
                Log.e(TAG, "KeePass update failed: ${updateResult.exceptionOrNull()?.message}")
                return Result.failure(
                    updateResult.exceptionOrNull()
                        ?: IllegalStateException("KeePass update failed")
                )
            }
        }

        persistUpdate?.invoke(updatedEntry)

        if (oldKeepassId != null && oldKeepassId != newKeepassId) {
            val deleteResult = keepassBridge.deleteLegacyPasswordEntries(
                databaseId = oldKeepassId,
                entries = listOf(existingEntry)
            )
            if (deleteResult.isFailure) {
                Log.e(TAG, "KeePass delete failed: ${deleteResult.exceptionOrNull()?.message}")
                return Result.failure(
                    deleteResult.exceptionOrNull()
                        ?: IllegalStateException("KeePass delete failed")
                )
            }
        }

        return Result.success(Unit)
    }

    private companion object {
        const val TAG = "KeePassPasswordUpdate"
    }
}

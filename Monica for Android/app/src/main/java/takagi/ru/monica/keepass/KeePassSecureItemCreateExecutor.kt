package takagi.ru.monica.keepass

import android.util.Log
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.repository.KeePassCompatibilityBridge

class KeePassSecureItemCreateExecutor(
    private val bridge: KeePassCompatibilityBridge?
) {
    suspend fun create(
        item: SecureItem,
        insertItem: suspend (SecureItem) -> Long,
        rollbackItem: suspend (Long) -> Unit
    ): Long? {
        val id = insertItem(item)
        if (KeePassMonicaPasswordStore.isUiId(id)) return id
        val databaseId = item.keepassDatabaseId ?: return id
        val keepassBridge = bridge ?: return id

        val syncResult = keepassBridge.updateLegacySecureItem(
            databaseId = databaseId,
            item = item.copy(id = id)
        )
        if (syncResult.isFailure) {
            rollbackItem(id)
            val error = syncResult.exceptionOrNull()
            Log.e(TAG, "KeePass write failed: ${error?.message}", error)
            return null
        }

        return id
    }

    private companion object {
        const val TAG = "KeePassSecureCreate"
    }
}

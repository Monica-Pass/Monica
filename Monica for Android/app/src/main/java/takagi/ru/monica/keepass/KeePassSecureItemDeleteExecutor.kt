package takagi.ru.monica.keepass

import android.util.Log
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.repository.KeePassCompatibilityBridge

class KeePassSecureItemDeleteExecutor(
    private val bridge: KeePassCompatibilityBridge?,
    private val repository: takagi.ru.monica.repository.SecureItemRepository? = null,
) {
    suspend fun delete(item: SecureItem, useRecycleBin: Boolean): Boolean {
        if (KeePassMonicaPasswordStore.isUiId(item.id)) return runCatching {
            requireNotNull(repository).deleteOriginalKeePassItem(item, useRecycleBin)
        }.isSuccess
        val databaseId = item.keepassDatabaseId ?: return true
        val keepassBridge = bridge ?: return true

        if (KeePassDeletePolicy.allowPermanentFallback(useRecycleBin)) {
            return runDirectDelete(keepassBridge, databaseId, item)
        }

        val moveToRecycleBin = keepassBridge.moveLegacySecureItemsToRecycleBin(
            databaseId = databaseId,
            items = listOf(item)
        )
        val movedCount = moveToRecycleBin.getOrNull()
        if (movedCount != null && movedCount > 0) {
            return true
        }

        if (movedCount != null) {
            Log.w(
                TAG,
                "KeePass move to recycle bin affected 0 entries for db=$databaseId; preserve the item"
            )
        }

        val failureMessage = moveToRecycleBin.exceptionOrNull()?.message.orEmpty()
        Log.w(
            TAG,
            "KeePass move to recycle bin failed; permanent delete was not requested: $failureMessage"
        )
        return false
    }

    private suspend fun runDirectDelete(
        bridge: KeePassCompatibilityBridge,
        databaseId: Long,
        item: SecureItem
    ): Boolean {
        val directDelete = bridge.deleteLegacySecureItems(
            databaseId = databaseId,
            items = listOf(item)
        )
        if (directDelete.isFailure) {
            Log.e(TAG, "KeePass delete failed: ${directDelete.exceptionOrNull()?.message}")
            return false
        }
        val deletedCount = directDelete.getOrNull() ?: 0
        if (deletedCount <= 0) {
            Log.e(TAG, "KeePass delete affected 0 entries for db=$databaseId")
            return false
        }
        return true
    }

    private companion object {
        const val TAG = "KeePassSecureDelete"
    }
}

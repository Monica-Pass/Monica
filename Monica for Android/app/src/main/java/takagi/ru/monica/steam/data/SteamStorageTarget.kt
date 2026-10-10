package takagi.ru.monica.steam.data

import takagi.ru.monica.ui.components.UnifiedMoveCategoryTarget

data class SteamStorageTarget(
    val source: SteamStorageSource,
    val categoryId: Long? = null,
    val folderId: String? = null,
    val groupPath: String? = null
) {
    companion object {
        fun from(target: UnifiedMoveCategoryTarget): SteamStorageTarget = when (target) {
            UnifiedMoveCategoryTarget.Uncategorized -> SteamStorageTarget(SteamStorageSource.Local)
            is UnifiedMoveCategoryTarget.MonicaCategory -> SteamStorageTarget(SteamStorageSource.Local, categoryId = target.categoryId)
            is UnifiedMoveCategoryTarget.MdbxDatabaseTarget -> SteamStorageTarget(SteamStorageSource.Mdbx(target.databaseId))
            is UnifiedMoveCategoryTarget.MdbxFolderTarget -> SteamStorageTarget(SteamStorageSource.Mdbx(target.databaseId), folderId = target.folderId)
            is UnifiedMoveCategoryTarget.KeePassDatabaseTarget -> SteamStorageTarget(SteamStorageSource.KeePass(target.databaseId))
            is UnifiedMoveCategoryTarget.KeePassGroupTarget -> SteamStorageTarget(SteamStorageSource.KeePass(target.databaseId), groupPath = target.groupPath)
            is UnifiedMoveCategoryTarget.BitwardenVaultTarget -> SteamStorageTarget(SteamStorageSource.Bitwarden(target.vaultId))
            is UnifiedMoveCategoryTarget.BitwardenFolderTarget -> SteamStorageTarget(SteamStorageSource.Bitwarden(target.vaultId), folderId = target.folderId)
        }
    }
}

internal suspend fun executeSteamTransfer(
    sameDatabase: Boolean,
    action: SteamMaFileTransferAction,
    writeTarget: suspend (relocate: Boolean) -> Unit,
    deleteSource: suspend () -> Unit
) {
    writeTarget(sameDatabase && action == SteamMaFileTransferAction.MOVE)
    // A failed write or copy must never remove the source. A relocation already updates it in place.
    if (!sameDatabase && action == SteamMaFileTransferAction.MOVE) deleteSource()
}

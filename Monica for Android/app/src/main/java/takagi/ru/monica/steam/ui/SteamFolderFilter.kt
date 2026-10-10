package takagi.ru.monica.steam.ui

import takagi.ru.monica.steam.data.SteamAccount
import takagi.ru.monica.steam.data.SteamStorageSource
import takagi.ru.monica.ui.components.UnifiedCategoryFilterSelection

internal data class SteamFolderFilter(val source: SteamStorageSource, val folderId: String? = null)

internal fun steamFolderFilter(selection: UnifiedCategoryFilterSelection): SteamFolderFilter? = when (selection) {
    UnifiedCategoryFilterSelection.Local -> SteamFolderFilter(SteamStorageSource.Local)
    is UnifiedCategoryFilterSelection.Custom -> SteamFolderFilter(SteamStorageSource.Local, selection.categoryId.toString())
    is UnifiedCategoryFilterSelection.MdbxDatabaseFilter -> SteamFolderFilter(SteamStorageSource.Mdbx(selection.databaseId))
    is UnifiedCategoryFilterSelection.MdbxFolderFilter -> SteamFolderFilter(SteamStorageSource.Mdbx(selection.databaseId), selection.folderId)
    is UnifiedCategoryFilterSelection.KeePassDatabaseFilter -> SteamFolderFilter(SteamStorageSource.KeePass(selection.databaseId))
    is UnifiedCategoryFilterSelection.KeePassGroupFilter -> SteamFolderFilter(SteamStorageSource.KeePass(selection.databaseId), selection.groupPath)
    is UnifiedCategoryFilterSelection.BitwardenVaultFilter -> SteamFolderFilter(SteamStorageSource.Bitwarden(selection.vaultId))
    is UnifiedCategoryFilterSelection.BitwardenFolderFilter -> SteamFolderFilter(SteamStorageSource.Bitwarden(selection.vaultId), selection.folderId)
    else -> null
}

internal fun SteamFolderFilter.toMenuSelection(): UnifiedCategoryFilterSelection = when (val source = source) {
    SteamStorageSource.Local -> folderId?.toLongOrNull()?.let { UnifiedCategoryFilterSelection.Custom(it) }
        ?: UnifiedCategoryFilterSelection.Local
    is SteamStorageSource.Mdbx -> folderId?.let { UnifiedCategoryFilterSelection.MdbxFolderFilter(source.databaseId, it) }
        ?: UnifiedCategoryFilterSelection.MdbxDatabaseFilter(source.databaseId)
    is SteamStorageSource.KeePass -> folderId?.let { UnifiedCategoryFilterSelection.KeePassGroupFilter(source.databaseId, it) }
        ?: UnifiedCategoryFilterSelection.KeePassDatabaseFilter(source.databaseId)
    is SteamStorageSource.Bitwarden -> folderId?.let { UnifiedCategoryFilterSelection.BitwardenFolderFilter(source.vaultId, it) }
        ?: UnifiedCategoryFilterSelection.BitwardenVaultFilter(source.vaultId)
}

internal fun filterSteamAccountsByFolder(accounts: List<SteamAccount>, filter: SteamFolderFilter): List<SteamAccount> {
    val folderId = filter.folderId ?: return accounts
    return accounts.filter { account ->
        when (filter.source) {
            SteamStorageSource.Local -> account.categoryId?.toString() == folderId
            else -> account.storageFolderId == folderId
        }
    }
}

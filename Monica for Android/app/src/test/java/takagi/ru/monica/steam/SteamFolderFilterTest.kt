package takagi.ru.monica.steam

import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.steam.data.*
import takagi.ru.monica.steam.ui.*
import takagi.ru.monica.ui.components.UnifiedCategoryFilterSelection as Selection

class SteamFolderFilterTest {
    private val account = SteamAccount(1, "76561198000000000", "fixture", "Fixture", "android:fixture",
        "c2VjcmV0", null, null, null, null, null, null, "{}", false, 0, 1, 1)

    @Test fun eachMenuSelectionPreservesSourceAndFolderIdentity() {
        val selections = listOf(Selection.Local, Selection.Custom(7), Selection.MdbxDatabaseFilter(2),
            Selection.MdbxFolderFilter(2, "Games/Personal"), Selection.KeePassDatabaseFilter(3),
            Selection.KeePassGroupFilter(3, "Games/Personal"), Selection.BitwardenVaultFilter(4),
            Selection.BitwardenFolderFilter(4, "uuid/with:delimiters"))
        selections.forEach { assertEquals(it, requireNotNull(steamFolderFilter(it)).toMenuSelection()) }
        assertNull(steamFolderFilter(Selection.All))
    }
    @Test fun localCategoriesFilterExactlyAndRootShowsEveryAccount() {
        val accounts = listOf(account, account.copy(id=2,categoryId=7), account.copy(id=3,categoryId=8))
        assertEquals(listOf(2L), filterSteamAccountsByFolder(accounts, SteamFolderFilter(SteamStorageSource.Local,"7")).map { it.id })
        assertEquals(accounts, filterSteamAccountsByFolder(accounts, SteamFolderFilter(SteamStorageSource.Local)))
    }
    @Test fun externalFoldersUseOwningVaultMetadataAndExactMembership() {
        val accounts = listOf(account, account.copy(id=2,storageFolderId="Games"),
            account.copy(id=3,storageFolderId="Games/Personal"), account.copy(id=4,categoryId=7))
        for (source in listOf(SteamStorageSource.Mdbx(2),SteamStorageSource.KeePass(3),SteamStorageSource.Bitwarden(4))) {
            assertEquals(listOf(2L),filterSteamAccountsByFolder(accounts,SteamFolderFilter(source,"Games")).map { it.id })
            assertEquals(listOf(3L),filterSteamAccountsByFolder(accounts,SteamFolderFilter(source,"Games/Personal")).map { it.id })
            assertEquals(emptyList<SteamAccount>(),filterSteamAccountsByFolder(accounts,SteamFolderFilter(source,"7")))
        }
    }
    @Test fun folderAndSearchFiltersComposeWithoutLosingMetadata() {
        val accounts = listOf(account.copy(categoryId=7),account.copy(id=2,categoryId=8))
        val visible = filterSteamAccounts(filterSteamAccountsByFolder(accounts,SteamFolderFilter(SteamStorageSource.Local,"7")),"fixture")
        assertEquals(listOf(1L),visible.map { it.id })
        assertEquals("Games",account.copy(storageFolderId="Games").copy(accessToken="updated").storageFolderId)
    }
}

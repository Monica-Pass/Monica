package takagi.ru.monica.passkey

import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.utils.SavedCategoryFilterState

class PasskeyPageSyncPolicyTest {
    @Test fun localAndMdbxFiltersNeverUseTheActiveBitwardenAccount() {
        listOf("local", "local_starred", "local_uncategorized", "custom", "keepass_database", "keepass_group",
            "mdbx_database", "mdbx_folder").forEach { type ->
            assertNull(type, PasskeyPageSyncPolicy.bitwardenStatusVaultId(SavedCategoryFilterState(type, 7L), true, 99L))
        }
    }
    @Test fun cloudFolderUsesItsOwnAccountAndWaitsForRestoration() {
        val filter = SavedCategoryFilterState("bitwarden_folder", 7L, text = "folder")
        assertEquals(7L, PasskeyPageSyncPolicy.bitwardenStatusVaultId(filter, true, 99L))
        assertNull(PasskeyPageSyncPolicy.bitwardenStatusVaultId(filter, false, 99L))
        assertEquals(99L, PasskeyPageSyncPolicy.bitwardenStatusVaultId(SavedCategoryFilterState(), true, 99L))
    }
    @Test fun keepassRefreshOnlyIncludesExistingDatabasesInCurrentScope() {
        val ids = listOf(1L, 2L)
        assertEquals(ids, PasskeyPageSyncPolicy.keePassDatabaseIds(SavedCategoryFilterState(), ids))
        assertEquals(listOf(2L), PasskeyPageSyncPolicy.keePassDatabaseIds(SavedCategoryFilterState("keepass_group", 2L), ids))
        assertTrue(PasskeyPageSyncPolicy.keePassDatabaseIds(SavedCategoryFilterState("keepass_database", 3L), ids).isEmpty())
        listOf("local", "bitwarden_vault", "mdbx_database").forEach {
            assertTrue(PasskeyPageSyncPolicy.keePassDatabaseIds(SavedCategoryFilterState(it, 1L), ids).isEmpty())
        }
    }
}

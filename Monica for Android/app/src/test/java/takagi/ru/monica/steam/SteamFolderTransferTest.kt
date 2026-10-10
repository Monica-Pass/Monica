package takagi.ru.monica.steam

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.steam.data.*
import takagi.ru.monica.ui.components.UnifiedMoveCategoryTarget

class SteamFolderTransferTest {
    @Test fun folderTargetsKeepTheirDatabaseAndFolderIdentity() {
        assertEquals(SteamStorageTarget(SteamStorageSource.Mdbx(3), folderId = "nested"),
            SteamStorageTarget.from(UnifiedMoveCategoryTarget.MdbxFolderTarget(3, "nested")))
        assertEquals("Games/Personal", SteamStorageTarget.from(UnifiedMoveCategoryTarget.KeePassGroupTarget(4, "Games/Personal")).groupPath)
        assertEquals("remote-folder", SteamStorageTarget.from(UnifiedMoveCategoryTarget.BitwardenFolderTarget(5, "remote-folder")).folderId)
        assertEquals(9L, SteamStorageTarget.from(UnifiedMoveCategoryTarget.MonicaCategory(9)).categoryId)
    }
    @Test fun sameDatabaseMoveRelocatesWithoutDeleting() = runBlocking {
        var relocated = false
        executeSteamTransfer(true, SteamMaFileTransferAction.MOVE, { relocated = it }, { fail("Must not delete relocated entry") })
        assertTrue(relocated)
    }
    @Test fun copiesAlwaysInsertAndKeepSource() = runBlocking {
        for (same in listOf(false, true)) executeSteamTransfer(same, SteamMaFileTransferAction.COPY,
            { assertFalse(it) }, { fail("Copy must not delete source") })
    }
    @Test fun failedDestinationLeavesSourceAndSuccessfulMoveDeletesOnlyAfterWrite() = runBlocking {
        val operations = mutableListOf<String>()
        try {
            executeSteamTransfer(false, SteamMaFileTransferAction.MOVE, { error("disk full") }, { fail("Source must survive") })
            fail("Failure must propagate")
        } catch (_: IllegalStateException) { }
        executeSteamTransfer(false, SteamMaFileTransferAction.MOVE, { operations += "write" }, { operations += "delete" })
        assertEquals(listOf("write", "delete"), operations)
    }
}

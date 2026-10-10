package takagi.ru.monica.steam

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.*
import takagi.ru.monica.repository.*
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.steam.data.*

class SteamMdbxFolderTest {
    @Test fun realMdbxMoveCopyAndSessionEditKeepFolderAndIndependentRecords() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        val security = SecurityManager(context)
        val repository = Mdbx2Repository(context, db.localMdbxDatabaseDao(), security)
        val password = "transfer-fixture"
        val file = repository.createInitializedVaultFile(MdbxTigaMode.SKY,
            MdbxVaultCredential(MdbxUnlockMethod.MASTER_PASSWORD, password = password))
        try {
            val id = db.localMdbxDatabaseDao().insertDatabase(LocalMdbxDatabase(
                name = "Steam transfer test", filePath = file.absolutePath,
                workingCopyPath = file.absolutePath, cacheCopyPath = file.absolutePath,
                storageLocation = MdbxStorageLocation.INTERNAL.name, sourceType = MdbxSourceType.LOCAL_INTERNAL.name,
                engineType = MdbxEngineType.RUST_MDBX2.name, tigaMode = MdbxTigaMode.SKY.name,
                encryptedPassword = security.encryptData(password), unlockMethod = MdbxUnlockMethod.MASTER_PASSWORD.storedValue,
                lastSyncStatus = MdbxSyncStatus.LOCAL_ONLY.name))
            val parent = repository.createFolder(id, "Games", null)
            val child = repository.createFolder(id, "Personal", parent.folderId)
            val store = SteamMdbxAccountStore(repository)
            val account = SteamAccount(0, "76561198000000000", "fixture", "Fixture", "android:fixture",
                "c2VjcmV0", null, null, null, null, null, null, "{}", false, 0, 1, 1)
            val original = store.upsertAccount(id, "steam_mafile:original", account, parent.folderId, relocate = true)
            store.upsertAccount(id, original.entryId, account, child.folderId, relocate = true)
            assertEquals(1, repository.listSteamMaFileEntries(id).size)
            store.upsertAccount(id, "steam_mafile:copy", account, parent.folderId, relocate = true)
            store.upsertAccount(id, original.entryId, account.copy(accessToken = "updated-fixture"))
            val rows = repository.listSteamMaFileEntries(id)
            assertEquals(child.folderId, rows.single { it.entryId == original.entryId }.collectionId)
            assertEquals(parent.folderId, rows.single { it.entryId == "steam_mafile:copy" }.collectionId)
            assertEquals(2, store.loadAccounts(id).size)
            store.deleteAccount(id, original.entryId)
            assertEquals("steam_mafile:copy", repository.listSteamMaFileEntries(id).single().entryId)
        } finally { db.close(); file.delete() }
    }
}

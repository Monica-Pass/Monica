package takagi.ru.monica.credentialexchange

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.data.*
import takagi.ru.monica.keepass.KeePassDeleteInvestigationTest
import takagi.ru.monica.repository.Mdbx2Repository
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.steam.data.SteamDatabase
import takagi.ru.monica.ui.screens.ExportDataScreen
import takagi.ru.monica.ui.theme.MonicaTheme
import uniffi.mdbx_ffi.MdbxWriteCommand
import java.util.UUID

class ExportSourceRecordCountTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun passkey(vault: Long? = null, cipher: String? = null) = PasskeyEntry(
        credentialId = UUID.randomUUID().toString(), rpId = "synthetic.test", rpName = "Synthetic",
        userId = "user", userName = "Synthetic", userDisplayName = "Synthetic", privateKeyAlias = "test-only",
        publicKey = "test-only", bitwardenVaultId = vault, bitwardenCipherId = cipher)

    @Test fun localTotalsIncludeAllContentAndExcludeOtherSourcesAndTrash() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        val unlocked = SessionManager.isUnlocked.value
        SessionManager.markUnlocked()
        try {
            val counts = DatabaseRecordCounts(context, db)
            val steam = SteamDatabase.getDatabase(context).steamAccountDao().count().toLong()
            assertEquals(DatabaseRecordCount.Ready(steam), counts.load(ImportDestination.Local))
            db.passwordEntryDao().insertPasswordEntry(PasswordEntry(title = "Local", website = "", username = "", password = ""))
            db.passwordEntryDao().insertPasswordEntry(PasswordEntry(title = "Archived", website = "", username = "", password = "", isArchived = true))
            db.passwordEntryDao().insertPasswordEntry(PasswordEntry(title = "Trash", website = "", username = "", password = "", isDeleted = true))
            db.passwordEntryDao().insertPasswordEntry(PasswordEntry(title = "Native mirror", website = "", username = "", password = "", keepassDatabaseId = 987L))
            db.secureItemDao().insertItem(SecureItem(itemType = ItemType.NOTE, title = "Note", itemData = "{}"))
            db.secureItemDao().insertItem(SecureItem(itemType = ItemType.BANK_CARD, title = "Card", itemData = "{}"))
            db.secureItemDao().insertItem(SecureItem(itemType = ItemType.TOTP, title = "Trash", itemData = "{}", isDeleted = true))
            db.passkeyDao().insert(passkey())
            assertEquals(DatabaseRecordCount.Ready(steam + 5), counts.load(ImportDestination.Local))
        } finally { db.close(); if (!unlocked) SessionManager.markLocked() }
    }

    @Test fun bitwardenLoginAndPasskeyProjectionsCountAsOneCipher() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        try {
            db.passwordEntryDao().insertPasswordEntry(PasswordEntry(title = "Login", website = "", username = "", password = "",
                bitwardenVaultId = 123L, bitwardenCipherId = "same-cipher"))
            db.passkeyDao().insert(passkey(123L, "same-cipher"))
            db.secureItemDao().insertItem(SecureItem(itemType = ItemType.NOTE, title = "Note", itemData = "{}",
                bitwardenVaultId = 123L, bitwardenCipherId = "note-cipher"))
            db.passkeyDao().insert(passkey(456L, "other-vault"))
            assertEquals(2L, DatabaseRecordCounts(context, db).countRoomRecords(ImportDestination(ImportDestinationKind.BITWARDEN, 123L)))
        } finally { db.close() }
    }

    @Test fun actualExportPageAndPickerShowNativeCountAndLockStateWithoutWriting() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { _, service, id, room, uri ->
            val fixtureName = "Export count $id"
            val registry = PasswordDatabase.getDatabase(context).localKeePassDatabaseDao()
            registry.updateDatabase(checkNotNull(registry.getDatabaseById(id)).copy(name = fixtureName))
            service.openNativeBrowser(id).getOrThrow()
            val before = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            val countText = context.getString(R.string.export_source_record_count, 300L)
            compose.setContent { MonicaTheme { ExportDataScreen(
                onNavigateBack = {}, onExportZip = { _, _, _, _, _ -> Result.success("unused") },
                onExportKdbx = { _, _, _, _ -> Result.success("unused") },
                onLoadSteamMaFileCandidates = { Result.success(emptyList()) },
                onExportSteamMaFile = { _, _, _, _ -> Result.success("unused") }) } }
            compose.waitUntil(15000) { compose.onAllNodesWithText("Monica", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Monica", useUnmergedTree = true).performClick()
            compose.waitUntil(20000) { compose.onAllNodesWithText(countText, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(fixtureName))
            compose.onNodeWithText(fixtureName, useUnmergedTree = true).performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText(countText, useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
            compose.onNodeWithText(countText, useUnmergedTree = true).assertIsDisplayed()
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(context.cacheDir, "export-source-count-page.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            assertTrue(room.passwordEntryDao().getPasswordEntriesByKeePassDatabaseSync(id).isEmpty())
            assertArrayEquals(before, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            compose.runOnIdle { service.lockDatabase(id) }
            compose.waitUntil(10000) { compose.onAllNodesWithText(context.getString(R.string.export_source_count_locked), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithText(countText, useUnmergedTree = true).assertCountEquals(0)
            assertFalse(service.isDatabaseUnlocked(id))
            assertArrayEquals(before, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        }
    }

    @Test fun mdbxNativeTotalsExcludeFoldersAndTombstonesWithoutRoomProjection() = runBlocking {
        val db = PasswordDatabase.getDatabase(context)
        val security = SecurityManager(context)
        val repository = Mdbx2Repository(context, db.localMdbxDatabaseDao(), security)
        val unlocked = SessionManager.isUnlocked.value
        SessionManager.markUnlocked()
        val password = "Synthetic count fixture!"
        val file = repository.createInitializedVaultFile(MdbxTigaMode.SKY, password)
        val id = db.localMdbxDatabaseDao().insertDatabase(LocalMdbxDatabase(name = "Count fixture",
            filePath = file.absolutePath, workingCopyPath = file.absolutePath,
            engineType = MdbxEngineType.RUST_MDBX2.name, sourceType = MdbxSourceType.LOCAL_INTERNAL.name,
            storageLocation = MdbxStorageLocation.INTERNAL.name, encryptedPassword = security.encryptData(password)))
        try {
            val source = ImportDestination(ImportDestinationKind.MDBX, id)
            val counts = DatabaseRecordCounts(context)
            assertEquals(DatabaseRecordCount.Ready(0), counts.load(source))
            repository.withVaultForSync(id) { _, vault ->
                val folder = UUID.randomUUID().toString()
                vault.executeWriteOperation(UUID.randomUUID().toString(), "count-fixture", listOf(
                    MdbxWriteCommand.CreateProject(folder, "Folder"),
                    MdbxWriteCommand.CreateEntry(UUID.randomUUID().toString(), folder, "com.example.future", "Future", "{}")))
            }
            val login = PasswordEntry(title = "Deleted login", website = "", username = "", password = security.encryptData("test"),
                mdbxDatabaseId = id, replicaGroupId = UUID.randomUUID().toString())
            repository.upsertPassword(login)
            repository.deletePassword(login)
            val head = repository.getCurrentHeadCommitId(id)
            assertTrue(db.passwordEntryDao().getByMdbxDatabaseIdSync(id).isEmpty())
            assertEquals(DatabaseRecordCount.Ready(1), counts.load(source))
            assertEquals(head, repository.getCurrentHeadCommitId(id))
            assertTrue(db.passwordEntryDao().getByMdbxDatabaseIdSync(id).isEmpty())
        } finally {
            db.localMdbxDatabaseDao().deleteDatabaseById(id)
            repository.deleteOwnedVaultFile(file)
            if (!unlocked) SessionManager.markLocked()
        }
    }
}

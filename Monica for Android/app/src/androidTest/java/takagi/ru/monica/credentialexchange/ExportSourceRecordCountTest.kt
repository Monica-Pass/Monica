package takagi.ru.monica.credentialexchange

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.database.modifiers.modifyContent
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

    private fun fileCounters(uri: android.net.Uri): List<Int> = context.contentResolver
        .query(uri, arrayOf("reads", "commits"), null, null, null)!!.use {
            it.moveToFirst(); listOf(it.getInt(0), it.getInt(1))
        }

    private data class DisplayedDatabase(
        val id: Long,
        val count: Long,
        val service: takagi.ru.monica.utils.KeePassKdbxService,
        val store: takagi.ru.monica.keepass.KeePassMonicaPasswordStore,
        val uri: android.net.Uri,
        val bytes: ByteArray,
        val name: String,
    )

    private suspend fun withSeveralDisplayedDatabases(block: suspend (List<DisplayedDatabase>) -> Unit) {
        val displayed = mutableListOf<DisplayedDatabase>()
        suspend fun open(index: Int) {
            KeePassDeleteInvestigationTest().scenario(databaseTransform = { database ->
                database.modifyParentGroup {
                    copy(entries = entries.take(index + 1))
                }
            }) { _, service, id, _, uri ->
                val registry = PasswordDatabase.getDatabase(context).localKeePassDatabaseDao()
                val name = "Displayed count ${index + 1} $id"
                registry.updateDatabase(checkNotNull(registry.getDatabaseById(id)).copy(name = name))
                val store = takagi.ru.monica.keepass.KeePassMonicaPasswordStore(service, SecurityManager(context))
                assertEquals(index + 1, store.refresh(id).rows.size)
                displayed += DisplayedDatabase(id, (index + 1).toLong(), service, store, uri,
                    context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }, name)
                if (index < 3) open(index + 1) else {
                    takagi.ru.monica.utils.KeePassKdbxService.clearActiveDatabase()
                    assertTrue("Exercise actual LRU eviction", displayed.count { !it.service.isDatabaseUnlocked(it.id) } >= 2)
                    displayed.forEach { item ->
                        assertEquals("Password-list presentation remains readable", item.count.toInt(), item.store.rows(item.id).first().size)
                    }
                    block(displayed)
                }
                displayed.removeAt(displayed.lastIndex)
            }
        }
        open(0)
    }

    @Test fun alreadyDisplayedNativeCountsSurviveDecodedCacheEvictionWithoutReadingOrWriting() = runBlocking {
        withSeveralDisplayedDatabases { displayed ->
            val counts = DatabaseRecordCounts(context)
            val warmBefore = displayed.map { it.service.isDatabaseUnlocked(it.id) }
            displayed.forEach { item ->
                val ioBefore = fileCounters(item.uri)
                assertEquals(DatabaseRecordCount.Ready(item.count), counts.load(ImportDestination(ImportDestinationKind.KEEPASS, item.id)))
                assertEquals("Counting performs no file reads or commits", ioBefore, fileCounters(item.uri))
                assertArrayEquals(item.bytes, context.contentResolver.openInputStream(item.uri)!!.use { it.readBytes() })
            }
            assertEquals("Counting must not reopen evicted decoded sessions", warmBefore, displayed.map { it.service.isDatabaseUnlocked(it.id) })
        }
    }

    @Test fun unopenedSourceWithStoredCredentialsIsNotSilentlyOpenedToCount() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { _, service, id, _, uri ->
            assertFalse(service.isDatabaseUnlocked(id))
            val before = fileCounters(uri)
            assertEquals(DatabaseRecordCount.Locked, DatabaseRecordCounts(context).load(ImportDestination(ImportDestinationKind.KEEPASS, id)))
            assertEquals(before, fileCounters(uri))
            assertFalse(service.isDatabaseUnlocked(id))
        }
    }

    @Test fun globalLockAndReunlockRevokePreviouslyDisplayedCounts() = runBlocking {
        withSeveralDisplayedDatabases { displayed ->
            val counts = DatabaseRecordCounts(context)
            SessionManager.markLocked()
            displayed.forEach { assertEquals(DatabaseRecordCount.Locked, counts.load(ImportDestination(ImportDestinationKind.KEEPASS, it.id))) }
            SessionManager.markUnlocked()
            displayed.forEach { item ->
                val before = fileCounters(item.uri)
                assertEquals(DatabaseRecordCount.Locked, counts.load(ImportDestination(ImportDestinationKind.KEEPASS, item.id)))
                assertEquals(before, fileCounters(item.uri))
            }
            val item = displayed.first()
            item.service.openNativeBrowser(item.id).getOrThrow()
            assertEquals(DatabaseRecordCount.Ready(item.count), counts.load(ImportDestination(ImportDestinationKind.KEEPASS, item.id)))
        }
    }

    @Test fun sourceInvalidationRevokesAnEvictedCountUntilASuccessfulRead() = runBlocking {
        withSeveralDisplayedDatabases { displayed ->
            val item = displayed.first()
            val source = ImportDestination(ImportDestinationKind.KEEPASS, item.id)
            val counts = DatabaseRecordCounts(context)
            takagi.ru.monica.utils.KeePassKdbxService.invalidateProcessCache(item.id)
            val before = fileCounters(item.uri)
            assertEquals(DatabaseRecordCount.Locked, counts.load(source))
            assertEquals(before, fileCounters(item.uri))
            displayed.drop(1).forEach { assertEquals(DatabaseRecordCount.Ready(it.count), counts.load(ImportDestination(ImportDestinationKind.KEEPASS, it.id))) }
            item.service.openNativeBrowser(item.id).getOrThrow()
            assertEquals(DatabaseRecordCount.Ready(item.count), counts.load(source))
        }
    }

    @Test fun failedReadAfterInvalidationCannotRestoreAnOldTotal() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { _, service, id, _, uri ->
            service.openNativeBrowser(id).getOrThrow()
            context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(byteArrayOf(1, 2, 3)) }
            takagi.ru.monica.utils.KeePassKdbxService.invalidateProcessCache(id)
            assertTrue(service.openNativeBrowser(id).isFailure)
            val before = fileCounters(uri)
            assertEquals(DatabaseRecordCount.Locked, DatabaseRecordCounts(context).load(ImportDestination(ImportDestinationKind.KEEPASS, id)))
            assertEquals(before, fileCounters(uri))
        }
    }

    @Test fun emptyUnlockedNativeDatabaseShowsZeroWithoutAFileRead() = runBlocking {
        KeePassDeleteInvestigationTest().scenario(databaseTransform = { database ->
            database.modifyParentGroup { copy(entries = emptyList()) }
        }) { _, service, id, _, uri ->
            service.openNativeBrowser(id).getOrThrow()
            val before = fileCounters(uri)
            assertEquals(DatabaseRecordCount.Ready(0), DatabaseRecordCounts(context).load(ImportDestination(ImportDestinationKind.KEEPASS, id)))
            assertEquals(before, fileCounters(uri))
        }
    }

    @Test fun nativeTotalsIncludeUnmappedEntriesButExcludeHistoryAndDisabledRecycleSubtrees() = runBlocking {
        KeePassDeleteInvestigationTest().scenario(databaseTransform = { database ->
            database.modifyContent {
                val originals = group.entries
                val recycle = app.keemobile.kotpass.models.Group.createRecycleBin("Synthetic recycle").copy(
                    entries = listOf(originals[1]),
                    groups = listOf(app.keemobile.kotpass.models.Group.createRecycleBin("Nested").copy(entries = listOf(originals[3]))),
                )
                copy(meta = meta.copy(recycleBinEnabled = false, recycleBinUuid = recycle.uuid),
                    group = group.copy(entries = listOf(originals[0].copy(history = listOf(originals[1])),
                        originals[2].copy(fields = app.keemobile.kotpass.models.EntryFields.of(
                            "Title" to app.keemobile.kotpass.models.EntryValue.Plain("Unmapped synthetic kind"),
                            "FutureField" to app.keemobile.kotpass.models.EntryValue.Plain("synthetic")))),
                        groups = group.groups + recycle))
            }
        }) { _, service, id, room, uri ->
            service.openNativeBrowser(id).getOrThrow()
            val before = fileCounters(uri)
            assertEquals(DatabaseRecordCount.Ready(2), DatabaseRecordCounts(context).load(ImportDestination(ImportDestinationKind.KEEPASS, id)))
            assertEquals(before, fileCounters(uri))
            assertTrue(room.passwordEntryDao().getPasswordEntriesByKeePassDatabaseSync(id).isEmpty())
        }
    }

    @Test fun nativeWriteRefreshesSelectedSourceCountWithoutReopeningThePicker() = runBlocking {
        KeePassDeleteInvestigationTest().scenario(databaseTransform = { database ->
            database.modifyParentGroup { copy(entries = entries.take(1)) }
        }) { _, service, id, _, _ ->
            val registry = PasswordDatabase.getDatabase(context).localKeePassDatabaseDao()
            val name = "Live count $id"
            registry.updateDatabase(checkNotNull(registry.getDatabaseById(id)).copy(name = name))
            val native = service.openNativeBrowser(id).getOrThrow()
            compose.setContent { MonicaTheme { ExportDataScreen(
                onNavigateBack = {}, onExportZip = { _, _, _, _, _ -> Result.success("unused") },
                onExportKdbx = { _, _, _, _ -> Result.success("unused") },
                onLoadSteamMaFileCandidates = { Result.success(emptyList()) },
                onExportSteamMaFile = { _, _, _, _ -> Result.success("unused") }) } }
            compose.waitUntil(15000) { compose.onAllNodesWithText("Monica", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Monica", useUnmergedTree = true).performClick()
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(name))
            compose.onNodeWithText(name, useUnmergedTree = true).performClick()
            val one = context.getString(R.string.export_source_record_count, 1L)
            compose.waitUntil(15000) { compose.onAllNodesWithText(one, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            service.createNativeEntry(id, native.rootGroup.identity.groupUuid,
                listOf(takagi.ru.monica.keepass.KeePassFieldChange("Title", "Added synthetic entry", false)), native.sourceRevision.sha256)
            val two = context.getString(R.string.export_source_record_count, 2L)
            compose.waitUntil(15000) { compose.onAllNodesWithText(two, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(two, useUnmergedTree = true).assertIsDisplayed()
            assertEquals(DatabaseRecordCount.Ready(2), DatabaseRecordCounts(context).load(ImportDestination(ImportDestinationKind.KEEPASS, id)))
        }
    }

    @Test fun sourcePickerShowsAllAlreadyDisplayedDatabasesAfterWarmCacheEviction() = runBlocking {
        withSeveralDisplayedDatabases { displayed ->
            compose.setContent { MonicaTheme { ExportDataScreen(
                onNavigateBack = {}, onExportZip = { _, _, _, _, _ -> Result.success("unused") },
                onExportKdbx = { _, _, _, _ -> Result.success("unused") },
                onLoadSteamMaFileCandidates = { Result.success(emptyList()) },
                onExportSteamMaFile = { _, _, _, _ -> Result.success("unused") }) } }
            compose.waitUntil(15000) { compose.onAllNodesWithText("Monica", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Monica", useUnmergedTree = true).performClick()
            displayed.forEach { item ->
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(item.name))
                val countText = context.getString(R.string.export_source_record_count, item.count)
                compose.waitUntil(15000) { compose.onAllNodesWithText(countText, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            }
            val first = displayed.first()
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(first.name))
            compose.onNodeWithText(first.name, useUnmergedTree = true).performClick()
            val countText = context.getString(R.string.export_source_record_count, first.count)
            compose.onNodeWithText(countText, useUnmergedTree = true).assertIsDisplayed()
            compose.runOnIdle { first.service.lockDatabase(first.id) }
            compose.waitUntil(10000) { compose.onAllNodesWithText(context.getString(R.string.export_source_count_locked), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithText(countText, useUnmergedTree = true).assertCountEquals(0)
            displayed.forEach { item -> assertArrayEquals(item.bytes, context.contentResolver.openInputStream(item.uri)!!.use { it.readBytes() }) }
        }
    }

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

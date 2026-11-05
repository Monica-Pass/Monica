package takagi.ru.monica.utils

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.*
import takagi.ru.monica.notes.domain.NoteCategoryFilter
import takagi.ru.monica.notes.domain.NoteContentCodec
import takagi.ru.monica.notes.domain.NoteScopeIndex
import takagi.ru.monica.repository.SecureItemRepository
import takagi.ru.monica.security.SecurityManager
import java.io.File
import java.util.UUID

/** Exercises the same Room selection and ZIP codec used by cloud backup, without a cloud account. */
class LocalNoteBackupInstrumentedTest {
    @Test fun legacyLocalNotesRoundTripInEncryptedCloudArchive() = runBlocking { roundTrip(true) }
    @Test fun legacyLocalNotesRoundTripInPlainCloudArchive() = runBlocking { roundTrip(false) }

    private suspend fun roundTrip(encrypted: Boolean) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "local-note-backup-${UUID.randomUUID()}-"
        val root = File(base.cacheDir, prefix).apply { mkdirs() }
        val names = mutableSetOf<String>()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getApplicationInfo() = android.content.pm.ApplicationInfo(base.applicationInfo).apply {
                dataDir = root.path
            }
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "no_backup").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                names.add(name)
                return base.getSharedPreferences(prefix + name, mode)
            }
        }
        SecurityManager.clearRuntimeUnlockCache()
        val db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        try {
            val repository = SecureItemRepository(db.secureItemDao())
            fun note(id: Long): SecureItem {
                val (data, cache) = NoteContentCodec.encode("合成笔记 $id\n  preserve spaces  ", tags = listOf("fixture"))
                return SecureItem(id = id, itemType = ItemType.NOTE, title = "Synthetic $id",
                    itemData = data, notes = cache)
            }
            val originals = listOf(note(1).copy(bitwardenVaultId = 7),
                note(2).copy(bitwardenVaultId = 7, keepassDatabaseId = 9),
                note(3).copy(isArchived = true), note(4),
                note(5).copy(isDeleted = true), note(6).copy(bitwardenCipherId = "real-cipher"),
                note(7).copy(keepassDatabaseId = 9), note(8).copy(mdbxDatabaseId = 3))
            originals.forEach { db.secureItemDao().insertItem(it) }
            val displayed = repository.getItemsByType(ItemType.NOTE).first()
            assertEquals(listOf(1L, 2L, 4L),
                NoteScopeIndex(displayed).select(NoteCategoryFilter.Local).map { displayed[it].id }.sorted())
            assertEquals(4, repository.getLocalItemCountByType(ItemType.NOTE))
            val selected = repository.getAllLocalItems()
            assertEquals(listOf(1L, 2L, 3L, 4L), selected.map { it.id })
            val preferences = BackupPreferences(includePasswords = false, includeNotes = true,
                includeAuthenticators = false, includeDocuments = false, includeBankCards = false,
                includePasskeys = false, includeGeneratorHistory = false, includeImages = false,
                includeTimeline = false, includeTrash = false, includeTrashAndHistory = false,
                includeWebDavConfig = false, includeLocalKeePass = false)
            val helper = WebDavHelper(context)
            val password = if (encrypted) "Synthetic archive password 325!" else null
            val (archive, report) = helper.createBackupZip(emptyList(), selected, preferences,
                contentScope = BackupContentScope.MONICA_LOCAL_ONLY,
                allowBackupEncryption = encrypted, backupEncryptionPassword = password).getOrThrow()
            try {
                assertTrue(report.success)
                assertTrue(report.failedItems.isEmpty())
                assertEquals(4, report.successItems.notes)
                assertEquals(4, report.totalItems.notes)
                if (encrypted) assertTrue(helper.restoreFromBackupFile(archive, "wrong-password",
                    restoreMonicaConfig = false, importDataOnly = true).isFailure)
                val restored = helper.restoreFromBackupFile(archive, password,
                    restoreMonicaConfig = false, importDataOnly = true).getOrThrow()
                val notes = restored.content.secureItems.sortedBy { it.id }
                assertEquals(selected.map { it.id }, notes.map { it.id })
                assertEquals(selected.map { it.title }, notes.map { it.title })
                assertEquals(selected.map { it.itemData }, notes.map { it.itemData })
                assertEquals(selected.map { it.notes }, notes.map { it.notes })
                assertEquals(selected.map { it.isArchived }, notes.map { it.isArchived })
                assertTrue(notes.all { it.keepassDatabaseId == null && it.bitwardenVaultId == null })
                assertEquals(originals, db.secureItemDao().getItemsByIds(originals.map { it.id }))
            } finally { archive.delete() }
        } finally {
            db.close()
            SecurityManager.clearRuntimeUnlockCache()
            names.forEach { base.deleteSharedPreferences(prefix + it) }
            check(root.parentFile == base.cacheDir && root.name.startsWith("local-note-backup-"))
            root.deleteRecursively()
        }
    }
}

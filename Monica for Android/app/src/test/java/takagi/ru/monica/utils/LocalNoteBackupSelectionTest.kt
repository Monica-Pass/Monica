package takagi.ru.monica.utils

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import takagi.ru.monica.data.*
import takagi.ru.monica.notes.domain.NoteCategoryFilter
import takagi.ru.monica.notes.domain.NoteContentCodec
import takagi.ru.monica.notes.domain.NoteScopeIndex
import takagi.ru.monica.repository.SecureItemRepository

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class LocalNoteBackupSelectionTest {
    private lateinit var db: PasswordDatabase
    private lateinit var repository: SecureItemRepository

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PasswordDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = SecureItemRepository(db.secureItemDao())
    }

    @After fun cleanup() { db.close() }

    private fun note(id: Long) = SecureItem(id = id, itemType = ItemType.NOTE,
        title = "Synthetic note $id", itemData = NoteContentCodec.encode("Synthetic content $id").first)

    private suspend fun seedLegacyLocalNotes(): List<SecureItem> {
        // These historical shapes are deliberately supported as MonicaLocal by
        // the existing ownership resolver and the actual note-page Local filter.
        val rows = listOf(note(1).copy(bitwardenVaultId = 7),
            note(2).copy(bitwardenVaultId = 7, keepassDatabaseId = 9))
        rows.forEach { db.secureItemDao().insertItem(it) }
        val listed = repository.getItemsByType(ItemType.NOTE).first()
        assertTrue(rows.all { it.isLocalOnlyItem() })
        assertEquals(2, NoteScopeIndex(listed).select(NoteCategoryFilter.Local).size)
        return rows
    }

    @Test fun supportedLegacyLocalNotesVisibleInNotePageAreCountedForBackup() = runBlocking {
        val before = seedLegacyLocalNotes()
        assertEquals(2, repository.getLocalItemCountByType(ItemType.NOTE))
        assertEquals(before, db.secureItemDao().getItemsByIds(before.map { it.id }))
    }

    @Test fun supportedLegacyLocalNotesReachTheCloudArchiveInput() = runBlocking {
        val before = seedLegacyLocalNotes()
        val selected = repository.getAllLocalItems()
        assertEquals(before.map { it.id }, selected.map { it.id }.sorted())
        assertTrue(selected.all { BackupContentPolicy.shouldIncludeSecureItem(it, BackupContentScope.MONICA_LOCAL_ONLY) })
        assertEquals(before, db.secureItemDao().getItemsByIds(before.map { it.id }))
    }

    @Test fun ordinaryLocalNotesStillCountAndReachBackup() = runBlocking {
        val rows = listOf(note(1), note(2))
        rows.forEach { db.secureItemDao().insertItem(it) }
        assertEquals(2, repository.getLocalItemCountByType(ItemType.NOTE))
        assertEquals(rows.map { it.id }, repository.getAllLocalItems().map { it.id }.sorted())
    }

    @Test fun realCipherBindingWithoutVaultIdIsNotCountedAsLocal() = runBlocking {
        val remote = note(1).copy(bitwardenCipherId = "real-cipher", syncStatus = "SYNCED")
        db.secureItemDao().insertItem(remote)
        assertFalse(remote.isLocalOnlyItem())
        assertEquals(0, repository.getLocalItemCountByType(ItemType.NOTE))
        assertTrue(repository.getAllLocalItems().isEmpty())
        assertEquals(remote, db.secureItemDao().getItemById(remote.id))
    }

    @Test fun ownershipBoundariesAgreeWithNoteScopeAndRemainEligibleForArchive() = runBlocking {
        val rows = listOf(
            note(1),
            note(2).copy(bitwardenVaultId = 7),
            note(3).copy(bitwardenVaultId = 7, keepassDatabaseId = 9),
            note(4).copy(bitwardenVaultId = 7, bitwardenCipherId = "\u2003", syncStatus = "none"),
            note(5).copy(keepassDatabaseId = 9),
            note(6).copy(bitwardenVaultId = 7, bitwardenCipherId = "cipher"),
            note(7).copy(bitwardenVaultId = 7, bitwardenFolderId = "folder"),
            note(8).copy(bitwardenVaultId = 7, bitwardenRevisionDate = "revision"),
            note(9).copy(bitwardenVaultId = 7, bitwardenLocalModified = true),
            note(10).copy(bitwardenVaultId = 7, syncStatus = "PENDING"),
            note(11).copy(bitwardenCipherId = "cipher-only"),
            note(12).copy(mdbxDatabaseId = 3),
            note(13).copy(mdbxDatabaseId = 3, bitwardenVaultId = 7),
            note(14).copy(keepassDatabaseId = 9, keepassEntryUuid = "entry",
                bitwardenVaultId = 7, bitwardenCipherId = "conflict"),
            note(15).copy(keepassDatabaseId = 9, keepassGroupUuid = "group", bitwardenVaultId = 7),
            note(16).copy(keepassDatabaseId = 9, keepassGroupPath = "path", bitwardenVaultId = 7),
            note(17).copy(keepassDatabaseId = 9, bitwardenVaultId = 7, bitwardenCipherId = "bw")
        )
        rows.forEach { db.secureItemDao().insertItem(it) }
        val expected = listOf(1L, 2L, 3L, 4L)
        assertEquals(expected, NoteScopeIndex(rows).select(NoteCategoryFilter.Local).map { rows[it].id }.sorted())
        assertEquals(expected, repository.getAllLocalItems().map { it.id })
        assertEquals(expected.size, repository.getLocalItemCountByType(ItemType.NOTE))
        // The general ZIP policy also serves explicit offline exports. Cloud backup
        // must first select its local records; all of those must survive that policy.
        assertEquals(expected, repository.getAllLocalItems().filter {
            BackupContentPolicy.shouldIncludeSecureItem(it, BackupContentScope.MONICA_LOCAL_ONLY)
        }.map { it.id })
        assertEquals(rows, db.secureItemDao().getItemsByIds(rows.map { it.id }))
    }

    @Test fun backupCountsIncludeArchivedButExcludeTrashAndOtherTypes() = runBlocking {
        val rows = listOf(note(1), note(2).copy(isArchived = true, bitwardenVaultId = 7),
            note(3).copy(isDeleted = true), note(4).copy(isDeleted = true, isArchived = true),
            note(5).copy(itemType = ItemType.TOTP), note(6).copy(itemType = ItemType.PASSWORD),
            note(7).copy(isArchived = true, keepassDatabaseId = 9))
        rows.forEach { db.secureItemDao().insertItem(it) }
        assertEquals(2, repository.getLocalItemCountByType(ItemType.NOTE))
        assertEquals(1, repository.getLocalItemCountByType(ItemType.TOTP))
        assertEquals(0, repository.getLocalItemCountByType(ItemType.PASSWORD))
        assertEquals(listOf(1L, 2L, 5L), repository.getAllLocalItems().map { it.id })
        assertEquals(rows, db.secureItemDao().getItemsByIds(rows.map { it.id }))
    }

    @Test fun moreThanOneThousandLocalNotesAreSelectedWithoutSqlBindOverflow() = runBlocking {
        val rows = (1L..1101L).map { note(it).copy(bitwardenVaultId = 7) }
        db.secureItemDao().insertItems(rows)
        assertEquals(rows.size, repository.getLocalItemCountByType(ItemType.NOTE))
        assertEquals(rows, repository.getAllLocalItems())
    }
}

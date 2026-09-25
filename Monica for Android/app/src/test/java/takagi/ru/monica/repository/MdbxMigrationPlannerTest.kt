package takagi.ru.monica.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.MdbxEngineType
import takagi.ru.monica.data.MdbxSourceType

class MdbxMigrationPlannerTest {
    @Test
    fun localMdbx1WithSupportedContentIsEligible() {
        val plan = MdbxMigrationPlanner.build(
            source = sourceDatabase(),
            folders = emptyList(),
            entries = listOf(entry("password:1", "login", """{"kind":"password"}""")),
            attachments = emptyList()
        )

        assertTrue(plan.isEligible)
        assertEquals("Personal (MDBX2)", plan.suggestedTargetName)
        assertEquals(1, plan.activeEntryCount)
        assertEquals(0, plan.deletedEntryCount)
    }

    @Test
    fun nestedAndImplicitFoldersPreserveHierarchy() {
        val plan = MdbxMigrationPlanner.build(
            source = sourceDatabase(),
            folders = listOf(
                folder("parent", null, "Work"),
                folder("child", "parent", "Servers")
            ),
            entries = listOf(
                entry("password:1", "login", """{"mdbx_folder_id":"child"}"""),
                entry("password:2", "login", """{"category_id":7}""")
            ),
            attachments = emptyList()
        )

        assertTrue(plan.isEligible)
        val child = plan.folders.first { it.sourceFolderId == "child" }
        assertEquals("Servers", child.targetDisplayName)
        assertEquals("parent", child.sourceParentFolderId)
        assertFalse(child.flattened)
        assertEquals("Category 7", plan.folders.first { it.sourceFolderId == "category:7" }.targetDisplayName)
        assertEquals(0, plan.warningCount(MdbxMigrationWarningKind.NESTED_FOLDERS_FLATTENED))
        assertEquals(1, plan.warningCount(MdbxMigrationWarningKind.IMPLICIT_FOLDERS_CREATED))
    }

    @Test
    fun missingAndCyclicFolderParentsBlockMigration() {
        val missing = MdbxMigrationPlanner.build(
            source = sourceDatabase(),
            folders = listOf(folder("child", "missing", "Child")),
            entries = emptyList(),
            attachments = emptyList()
        )
        assertTrue(missing.hasBlocker(MdbxMigrationBlockerKind.MISSING_FOLDER_PARENT))

        val cyclic = MdbxMigrationPlanner.build(
            source = sourceDatabase(),
            folders = listOf(
                folder("first", "second", "First"),
                folder("second", "first", "Second")
            ),
            entries = emptyList(),
            attachments = emptyList()
        )
        assertTrue(cyclic.hasBlocker(MdbxMigrationBlockerKind.FOLDER_CYCLE))
    }

    @Test
    fun invalidSourceDuplicatesAndAttachmentProblemsBlockMigration() {
        val duplicateEntry = entry("same", "login", "{}")
        val plan = MdbxMigrationPlanner.build(
            source = sourceDatabase(
                engine = MdbxEngineType.RUST_MDBX2,
                sourceType = MdbxSourceType.REMOTE_WEBDAV
            ),
            folders = emptyList(),
            entries = listOf(duplicateEntry, duplicateEntry),
            attachments = listOf(
                attachment(
                    id = "attachment-1",
                    parent = "missing",
                    size = 64L * 1024L * 1024L + 1L,
                    wrappedCek = null
                )
            )
        )

        assertFalse(plan.isEligible)
        assertTrue(plan.hasBlocker(MdbxMigrationBlockerKind.SOURCE_ENGINE_UNSUPPORTED))
        assertTrue(plan.hasBlocker(MdbxMigrationBlockerKind.DUPLICATE_ENTRY_ID))
        assertTrue(plan.hasBlocker(MdbxMigrationBlockerKind.ATTACHMENT_TOO_LARGE))
        assertTrue(plan.hasBlocker(MdbxMigrationBlockerKind.ATTACHMENT_KEY_MISSING))
        assertTrue(plan.hasBlocker(MdbxMigrationBlockerKind.ATTACHMENT_PARENT_MISSING))
    }

    @Test
    fun remoteMdbx1CopiesCanUpgradeWithAnExplicitScopeWarning() {
        listOf(MdbxSourceType.REMOTE_WEBDAV, MdbxSourceType.REMOTE_ONEDRIVE).forEach { sourceType ->
            val plan = MdbxMigrationPlanner.build(
                source = sourceDatabase(sourceType = sourceType),
                folders = emptyList(),
                entries = listOf(entry("password:1", "login", "{}")),
                attachments = emptyList()
            )
            assertTrue(plan.isEligible)
            assertEquals(1, plan.warningCount(MdbxMigrationWarningKind.REMOTE_LOCAL_COPY_ONLY))
        }
    }

    @Test
    fun unknownDeletedContentIsCopiedWithExplicitWarnings() {
        val plan = MdbxMigrationPlanner.build(
            source = sourceDatabase(),
            folders = emptyList(),
            entries = listOf(entry("future:1", "future-type", "{}", deleted = true)),
            attachments = listOf(attachment("deleted", "future:1", deleted = true))
        )

        assertTrue(plan.isEligible)
        assertEquals(1, plan.warningCount(MdbxMigrationWarningKind.UNKNOWN_ENTRY_TYPES_COPIED))
        assertEquals(1, plan.warningCount(MdbxMigrationWarningKind.DELETED_ENTRIES_COPIED))
        assertEquals(1, plan.warningCount(MdbxMigrationWarningKind.DELETED_ATTACHMENTS_IGNORED))
        assertTrue(plan.attachments.isEmpty())
    }

    private fun sourceDatabase(
        engine: MdbxEngineType = MdbxEngineType.KOTLIN_MDBX1,
        sourceType: MdbxSourceType = MdbxSourceType.LOCAL_INTERNAL
    ) = LocalMdbxDatabase(
        id = 11L,
        name = "Personal",
        filePath = "personal.mdbx",
        sourceType = sourceType.name,
        engineType = engine.name
    )

    private fun entry(id: String, type: String, payload: String, deleted: Boolean = false) =
        MdbxStoredVaultEntry(id, type, id, payload, deleted)

    private fun folder(id: String, parent: String?, name: String) =
        MdbxStoredFolderEntry(id, parent, name, name.lowercase(), 1L)

    private fun attachment(
        id: String,
        parent: String,
        size: Long = 1L,
        wrappedCek: String? = "portable-attachment-cek-v1:test",
        deleted: Boolean = false
    ) = MdbxStoredAttachment(
        attachmentId = id,
        projectId = parent,
        entryId = parent,
        fileName = "$id.bin",
        mimeType = "application/octet-stream",
        contentHash = "hash",
        originalSize = size,
        storedSize = 1L,
        wrappedCek = wrappedCek,
        createdAtMillis = 1L,
        updatedAtMillis = 1L,
        deleted = deleted,
        blob = byteArrayOf(1)
    )

    private fun MdbxMigrationPlan.warningCount(kind: MdbxMigrationWarningKind): Int =
        warnings.firstOrNull { it.kind == kind }?.count ?: 0

    private fun MdbxMigrationPlan.hasBlocker(kind: MdbxMigrationBlockerKind): Boolean =
        blockers.any { it.kind == kind }
}

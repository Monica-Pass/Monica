package takagi.ru.monica.repository

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.attachments.AttachmentContainer
import takagi.ru.monica.attachments.facade.AttachmentFacade
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.attachments.model.AttachmentSource
import takagi.ru.monica.credentialexchange.ImportDestination
import takagi.ru.monica.credentialexchange.TransferFixture
import takagi.ru.monica.data.Category
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.security.SessionManager

/** Uses actual local rows and the same repository used by Manage original information. */
class DatabaseManagerLocalHierarchyTest {
    private val local = DatabaseManagerLocation(ImportDestination.Local)

    private suspend fun category(f: TransferFixture, path: String): Long =
        f.db.categoryDao().insert(Category(name = path))

    private suspend fun password(f: TransferFixture, suffix: String, category: Long? = null): Long =
        f.db.passwordEntryDao().insertPasswordEntry(PasswordEntry(title = "${f.prefix}-$suffix",
            website = "https://example.invalid", username = "synthetic", password = "synthetic value", categoryId = category))

    private suspend fun ownCategories(f: TransferFixture) =
        f.db.categoryDao().getDeviceSyncCategories().filter { it.name.startsWith(f.prefix) }.sortedBy { it.id }

    @Test fun browseBuildsMissingAncestorsWithoutWritingCategories() = runBlocking {
        val f = TransferFixture()
        try {
            val repo = DatabaseManagerRepository(f.context)
            val root = "${f.prefix}-Bitwarden测试文件夹"
            val childPath = "$root/子目录"
            val leaf = category(f, "$childPath/第三层")
            val realRoot = category(f, "${f.prefix}-actual")
            val realChild = category(f, "${f.prefix}-actual/child")
            val pw = password(f, "password", leaf)
            val direct = password(f, "direct", realRoot)
            val note = f.db.secureItemDao().insertItem(SecureItem(title = "${f.prefix}-note",
                itemType = ItemType.NOTE, itemData = "synthetic note", categoryId = leaf))
            val key = f.db.passkeyDao().insertCopy(PasskeyEntry(credentialId = f.credentialId, rpId = f.rpId,
                rpName = f.prefix, userId = "synthetic", userName = "synthetic", userDisplayName = f.prefix,
                publicKey = "synthetic", privateKeyAlias = "", categoryId = leaf))
            val bitwardenCategory = f.db.categoryDao().insert(Category(name = "${f.prefix}-remote/hidden", bitwardenVaultId = -1))
            val mdbxCategory = f.db.categoryDao().insert(Category(name = "${f.prefix}-mdbx/hidden", mdbxDatabaseId = -1))
            val before = f.db.categoryDao().getDeviceSyncCategories()
            val original = f.db.passwordEntryDao().getPasswordEntryById(pw)
            val snapshot = repo.browse(local)
            val parent = snapshot.index.children(null).single { it.name == root }
            assertEquals("local-path:$root", parent.id)
            val child = snapshot.index.children(parent.id).single()
            assertEquals("子目录", child.name)
            assertEquals("local-path:$childPath", child.id)
            val grandchild = snapshot.index.children(child.id).single()
            assertEquals("folder:$leaf", grandchild.id)
            assertEquals("第三层", grandchild.name)
            assertEquals(setOf("password:$pw", "item:$note", "passkey:$key"),
                snapshot.index.children(grandchild.id).map { it.id }.toSet())
            assertEquals(setOf("folder:$realChild", "password:$direct"),
                snapshot.index.children("folder:$realRoot").map { it.id }.toSet())
            assertFalse(snapshot.nodes.any { it.id in setOf("folder:$bitwardenCategory", "folder:$mdbxCategory") })
            assertEquals(listOf(root, "子目录", "第三层"), snapshot.index.ancestors(grandchild.id).map { it.name })
            repeat(2) { repo.browse(local.copy(folderId = child.id)) }
            assertEquals(before, f.db.categoryDao().getDeviceSyncCategories())
            assertEquals(original, f.db.passwordEntryDao().getPasswordEntryById(pw))
            assertEquals(leaf, f.db.secureItemDao().getItemById(note)!!.categoryId)
            assertEquals(leaf, f.db.passkeyDao().getPasskeyByRecordId(key)!!.categoryId)
        } finally { f.close() }
    }

    @Test fun createAndRenameNestedFoldersPreserveDescendantIds() = runBlocking {
        val f = TransferFixture()
        try {
            val repo = DatabaseManagerRepository(f.context)
            val root = "${f.prefix}-A"
            val child = category(f, "$root/child")
            val sibling = category(f, "${root}2/untouched")
            val pw = password(f, "retained", child)
            val original = f.db.passwordEntryDao().getPasswordEntryById(pw)
            val created = repo.createFolder(local.copy(folderId = "folder:$child"), "grandchild")
            val grandchild = created.substringAfter(':').toLong()
            assertEquals("$root/child/grandchild", f.db.categoryDao().getCategoryById(grandchild)!!.name)
            val renamedRoot = "${f.prefix}-renamed"
            repo.rename(local, repo.browse(local).nodes.single { it.id == "local-path:$root" }, renamedRoot)
            assertEquals("$renamedRoot/child", f.db.categoryDao().getCategoryById(child)!!.name)
            assertEquals("$renamedRoot/child/grandchild", f.db.categoryDao().getCategoryById(grandchild)!!.name)
            assertEquals("${root}2/untouched", f.db.categoryDao().getCategoryById(sibling)!!.name)
            repo.rename(local.copy(folderId = "local-path:$renamedRoot"),
                repo.browse(local).nodes.single { it.id == "folder:$child" }, "new child")
            assertEquals("$renamedRoot/new child/grandchild", f.db.categoryDao().getCategoryById(grandchild)!!.name)
            assertEquals(original, f.db.passwordEntryDao().getPasswordEntryById(pw))
            val occupied = "${f.prefix}-occupied"
            category(f, "$occupied/existing")
            val beforeCollision = ownCategories(f)
            assertTrue(runCatching { repo.rename(local,
                repo.browse(local).nodes.single { it.id == "local-path:$renamedRoot" }, occupied) }.isFailure)
            assertTrue(runCatching { repo.createFolder(local.copy(folderId = "local-path:$renamedRoot"), "new child") }.isFailure)
            assertEquals(beforeCollision, ownCategories(f))
            val duplicateRoot = "${f.prefix}-duplicates"
            category(f, "$duplicateRoot/x")
            val duplicate = category(f, "$duplicateRoot//x")
            val beforeDuplicate = ownCategories(f)
            assertTrue(runCatching { repo.rename(local,
                repo.browse(local).nodes.single { it.id == "local-path:$duplicateRoot" }, "${f.prefix}-duplicate-renamed") }.isFailure)
            assertTrue(runCatching { repo.createFolder(local.copy(folderId = "folder:$duplicate"), "child") }.isFailure)
            assertEquals(beforeDuplicate, ownCategories(f))
        } finally { f.close() }
    }

    @Test fun moveSubtreeRebasesPathsAndRejectsCyclesAndCollisions() = runBlocking {
        val f = TransferFixture()
        try {
            val repo = DatabaseManagerRepository(f.context)
            val root = "${f.prefix}-tree"
            val leaf = category(f, "$root/A/B/C")
            val sibling = category(f, "$root/A2/untouched")
            val target = category(f, "$root/D")
            val pw = password(f, "retained", leaf)
            val original = f.db.passwordEntryDao().getPasswordEntryById(pw)
            val sourceId = "local-path:$root/A/B"
            val result = repo.transfer(local, local.copy(folderId = "folder:$target"), setOf(sourceId), false,
                repo.browse(local).revision)
            assertEquals(result.failures.toString(), 1, result.completed)
            assertEquals("$root/D/B/C", f.db.categoryDao().getCategoryById(leaf)!!.name)
            assertEquals("$root/A2/untouched", f.db.categoryDao().getCategoryById(sibling)!!.name)
            assertEquals(original, f.db.passwordEntryDao().getPasswordEntryById(pw))
            val movedId = "local-path:$root/D/B"
            for (destination in listOf(movedId, "folder:$leaf")) {
                val before = ownCategories(f)
                val rejected = repo.transfer(local, local.copy(folderId = destination), setOf(movedId), false,
                    repo.browse(local).revision)
                assertEquals(0, rejected.completed)
                assertEquals(1, rejected.failures.size)
                assertEquals(before, ownCategories(f))
            }
            category(f, "$root/Other/B/existing")
            val beforeCollision = ownCategories(f)
            val rejected = repo.transfer(local, local.copy(folderId = "local-path:$root/Other"), setOf(movedId), false,
                repo.browse(local).revision)
            assertEquals(0, rejected.completed)
            assertEquals(1, rejected.failures.size)
            assertEquals(beforeCollision, ownCategories(f))
        } finally { f.close() }
    }

    @Test fun implicitFolderDeletionIncludesDescendantsAndRejectsExternalOwnership() = runBlocking {
        val f = TransferFixture()
        try {
            val repo = DatabaseManagerRepository(f.context)
            val root = "${f.prefix}-delete"
            val leaf = category(f, "$root/child/grandchild")
            val outsideCategory = category(f, "${root}2/keep")
            val pw = password(f, "delete-password", leaf)
            val outside = password(f, "keep-password", outsideCategory)
            val note = f.db.secureItemDao().insertItem(SecureItem(title = "${f.prefix}-delete-note",
                itemType = ItemType.NOTE, itemData = "synthetic note", categoryId = leaf))
            val key = f.db.passkeyDao().insertCopy(PasskeyEntry(credentialId = f.credentialId, rpId = f.rpId,
                rpName = f.prefix, userId = "synthetic", userName = "synthetic", userDisplayName = f.prefix,
                publicKey = "synthetic", privateKeyAlias = "", categoryId = leaf))
            AttachmentContainer.facade(f.context).addInlineAttachment(AttachmentFacade.InlineUploadRequest(
                AttachmentOwner.password(pw), AttachmentSource.LOCAL, "synthetic.bin", "application/octet-stream", byteArrayOf(5, 7, 9), true))
            val externalStore = f.mdbx()
            val external = f.db.passwordEntryDao().insertPasswordEntry(PasswordEntry(title = "${f.prefix}-external",
                website = "", username = "", password = "synthetic", categoryId = leaf, mdbxDatabaseId = externalStore.databaseId))
            val snapshot = repo.browse(local)
            assertTrue(runCatching { repo.delete(local, setOf("local-path:$root"), snapshot.revision) }.isFailure)
            assertFalse(f.db.passwordEntryDao().getPasswordEntryById(pw)!!.isDeleted)
            assertFalse(f.db.passwordEntryDao().getPasswordEntryById(external)!!.isDeleted)
            assertNotNull(f.db.categoryDao().getCategoryById(leaf))
            assertEquals(1, f.db.attachmentDao().getActiveByParents(listOf(pw)).size)
            f.db.passwordEntryDao().deletePasswordEntryById(external)
            repo.delete(local, setOf("local-path:$root", "password:$pw"), repo.browse(local).revision)
            assertNull(f.db.categoryDao().getCategoryById(leaf))
            assertTrue(f.db.passwordEntryDao().getPasswordEntryById(pw)!!.isDeleted)
            assertNull(f.db.passwordEntryDao().getPasswordEntryById(pw)!!.categoryId)
            assertTrue(f.db.secureItemDao().getItemById(note)!!.isDeleted)
            assertNull(f.db.passkeyDao().getPasskeyByRecordId(key))
            assertTrue(f.db.attachmentDao().getActiveByParents(listOf(pw)).isEmpty())
            assertFalse(f.db.passwordEntryDao().getPasswordEntryById(outside)!!.isDeleted)
            assertNotNull(f.db.categoryDao().getCategoryById(outsideCategory))
        } finally { f.close() }
    }

    @Test fun movingEntryToImplicitFolderMaterializesOnlyDuringAuthorizedWrite() = runBlocking {
        val f = TransferFixture()
        try {
            val repo = DatabaseManagerRepository(f.context)
            val root = "${f.prefix}-implicit"
            category(f, "$root/child")
            val pw = password(f, "move")
            val original = f.db.passwordEntryDao().getPasswordEntryById(pw)!!
            val target = local.copy(folderId = "local-path:$root")
            val result = repo.transfer(local, target, setOf("password:$pw"), false, repo.browse(local).revision)
            assertEquals(result.failures.toString(), 1, result.completed)
            val category = ownCategories(f).single { it.name == root }
            assertEquals(original.copy(categoryId = category.id), f.db.passwordEntryDao().getPasswordEntryById(pw))
            val categoryCount = ownCategories(f).size
            repo.transfer(local, target, setOf("password:$pw"), false, repo.browse(local).revision)
            assertEquals(categoryCount, ownCategories(f).size)
            assertEquals(1, ownCategories(f).count { it.name == root })
            val rejectedRoot = "${f.prefix}-unmaterialized"
            category(f, "$rejectedRoot/child")
            val other = password(f, "unchanged")
            val rejectedTarget = local.copy(folderId = "local-path:$rejectedRoot")
            val stale = repo.browse(local).revision
            val before = f.db.passwordEntryDao().getPasswordEntryById(other)!!
            f.db.passwordEntryDao().updatePasswordEntry(before.copy(username = "later edit"))
            assertTrue(runCatching { repo.transfer(local, rejectedTarget, setOf("password:$other"), false, stale) }.isFailure)
            SessionManager.markLocked()
            assertTrue(runCatching { repo.transfer(local, rejectedTarget, setOf("password:$other"), false) }.isFailure)
            SessionManager.markUnlocked()
            assertTrue(ownCategories(f).none { it.name == rejectedRoot })
            assertEquals(before.copy(username = "later edit"), f.db.passwordEntryDao().getPasswordEntryById(other))
        } finally { SessionManager.markUnlocked(); f.close() }
    }

    @Test fun unsupportedNestedCopiesRejectBeforeCreatingLocalDestinationFolders() = runBlocking {
        val f = TransferFixture()
        try {
            val repo = DatabaseManagerRepository(f.context)
            val root = "${f.prefix}-copy"
            category(f, "$root/child/grandchild")
            val beforeLocal = ownCategories(f)
            val localResult = repo.transfer(local, local, setOf("local-path:$root"), true, repo.browse(local).revision)
            assertEquals(0, localResult.completed)
            assertEquals(1, localResult.failures.size)
            assertEquals(beforeLocal, ownCategories(f))
            val source = f.keepass()
            val sourceLocation = DatabaseManagerLocation(source)
            val parent = repo.createFolder(sourceLocation, "${f.prefix}-native")
            repo.createFolder(sourceLocation.copy(folderId = parent), "child")
            val nativeBefore = repo.browse(sourceLocation)
            val before = ownCategories(f)
            val result = repo.transfer(sourceLocation, local, setOf(parent), true, nativeBefore.revision)
            assertEquals(0, result.completed)
            assertEquals(1, result.failures.size)
            assertEquals(before, ownCategories(f))
            assertEquals(nativeBefore.nodes, repo.browse(sourceLocation).nodes)
            assertEquals(nativeBefore.revision, repo.browse(sourceLocation).revision)
        } finally { f.close() }
    }
}

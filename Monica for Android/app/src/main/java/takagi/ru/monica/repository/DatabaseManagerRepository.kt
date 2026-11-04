package takagi.ru.monica.repository

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import takagi.ru.monica.credentialexchange.*
import takagi.ru.monica.data.*
import takagi.ru.monica.keepass.*
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.utils.KeePassKdbxService
import java.util.UUID

/** Operations are serialized; each pane owns only navigation state, never a writable database copy. */
internal class DatabaseManagerRepository(context: Context, databaseOverride: PasswordDatabase? = null) {
    val context = context.applicationContext
    val db = databaseOverride ?: PasswordDatabase.getDatabase(this.context)
    val security = SecurityManager(this.context)
    val mdbx = Mdbx2Repository(this.context, db.localMdbxDatabaseDao(), security,
        passwordEntryDao = db.passwordEntryDao(), secureItemDao = db.secureItemDao(), customFieldDao = db.customFieldDao())
    val kdbx = KeePassKdbxService(this.context, db.localKeePassDatabaseDao(), security)
    val keepass = KeePassWorkspaceRepository(kdbx)

    suspend fun stores(): List<DatabaseManagerStore> = withContext(Dispatchers.IO) {
        buildList {
            add(DatabaseManagerStore(ImportDestination.Local, "Monica"))
            db.localMdbxDatabaseDao().getAllDatabases().first().filter { it.engineTypeEnum == MdbxEngineType.RUST_MDBX2 }
                .forEach { add(DatabaseManagerStore(ImportDestination(ImportDestinationKind.MDBX, it.id), it.name, it.isUsable)) }
            db.localKeePassDatabaseDao().getAllDatabases().first().forEach {
                add(DatabaseManagerStore(ImportDestination(ImportDestinationKind.KEEPASS, it.id), it.name))
            }
        }
    }

    suspend fun browse(location: DatabaseManagerLocation): DatabaseManagerSnapshot = withContext(Dispatchers.IO) {
        check(SessionManager.isUnlocked.value)
        val source = location.database
        if (source.kind == ImportDestinationKind.MDBX) mdbx.requireRoomMirrorAllowed(source.databaseId)
        val title = stores().firstOrNull { it.key == source }?.title ?: error("Database unavailable")
        when (source.kind) {
            ImportDestinationKind.MDBX -> mdbx.nativeBrowser(source.databaseId).let {
                DatabaseManagerSnapshot(location, title, it.nodes, mdbx.getCurrentHeadCommitId(source.databaseId).orEmpty())
            }
            ImportDestinationKind.KEEPASS -> {
                val native = keepass.openNativeBrowser(source.databaseId).getOrThrow()
                val root = native.rootGroup.identity.groupUuid.toString()
                val nodes = native.groups.filter { it.identity != native.rootGroup.identity }.map {
                    node(it.identity.groupUuid.toString(), it.parentGroup?.groupUuid?.toString()?.takeUnless { id -> id == root }, it.name, true)
                } + native.entries.map {
                    node(it.identity.entryUuid.toString(), it.parentGroup.groupUuid.toString().takeUnless { id -> id == root }, it.title, false, it.kind.name.lowercase())
                }
                // UUID collisions must not cause selecting one row to move a different occurrence.
                check(nodes.map { it.id }.distinct().size == nodes.size) { "Duplicate native identities require repair in KeePass before management." }
                DatabaseManagerSnapshot(location, title, nodes, native.sourceRevision.sha256)
            }
            ImportDestinationKind.LOCAL -> {
                val folders = db.categoryDao().getAllCategories().first().filter { it.mdbxDatabaseId == null && it.bitwardenVaultId == null }
                val folderIds = folders.map { it.id }.toSet()
                fun parent(id: Long?) = id?.takeIf { it in folderIds }?.let { "folder:$it" }
                val nodes = folders.map { node("folder:${it.id}", null, it.name, true) } +
                    db.passwordEntryDao().getAllPasswordEntriesSync().filter { source.contains(it) && !it.isDeleted }.map {
                        node("password:${it.id}", parent(it.categoryId), it.title, false, it.loginType.lowercase())
                    } + db.secureItemDao().getAllItemsIncludingArchived().first().filter { source.contains(it) && !it.isDeleted }.map {
                        node("item:${it.id}", parent(it.categoryId), it.title, false, it.itemType.name.lowercase())
                    } + db.passkeyDao().getAllPasskeysSync().filter(source::contains).map {
                        node("passkey:${it.id}", parent(it.categoryId), it.userDisplayName.ifBlank { it.rpName }, false, "passkey")
                    }
                DatabaseManagerSnapshot(location, title, nodes, managerLocalRevision(this@DatabaseManagerRepository))
            }
            else -> error("Unsupported database")
        }
    }

    suspend fun createFolder(location: DatabaseManagerLocation, name: String): String = withContext(Dispatchers.IO) {
        check(SessionManager.isUnlocked.value) { "The vault is locked." }
        if (location.database.kind == ImportDestinationKind.MDBX) mdbx.requireRoomMirrorAllowed(location.database.databaseId)
        require(name.isNotBlank() && name.length <= 512)
        when (location.database.kind) {
            ImportDestinationKind.MDBX -> mdbx.createFolder(location.database.databaseId, name, location.folderId).folderId
            ImportDestinationKind.KEEPASS -> {
                val native = keepass.openNativeBrowser(location.database.databaseId).getOrThrow()
                keepass.createNativeGroup(location.database.databaseId,
                    location.folderId?.let(UUID::fromString) ?: native.rootGroup.identity.groupUuid, name, native.sourceRevision.sha256)
                    .getOrThrow().identity.groupUuid.toString()
            }
            ImportDestinationKind.LOCAL -> {
                check(location.folderId == null) { "Monica local categories do not support nested folders." }
                "folder:${db.categoryDao().insert(Category(name = name))}"
            }
            else -> error("Unsupported database")
        }
    }

    suspend fun rename(location: DatabaseManagerLocation, row: MdbxStructureNode, name: String) = withContext(Dispatchers.IO) {
        check(SessionManager.isUnlocked.value) { "The vault is locked." }
        if (location.database.kind == ImportDestinationKind.MDBX) mdbx.requireRoomMirrorAllowed(location.database.databaseId)
        require(name.isNotBlank() && name.length <= 512)
        val id = location.database.databaseId
        when (location.database.kind) {
            ImportDestinationKind.MDBX -> if (row.type == MdbxStructureNodeType.FOLDER) mdbx.renameFolder(id, row.id, name)
                else mdbx.renameNativeObject(id, mdbx.nativeBrowser(id).objects.getValue(row.id), name)
            ImportDestinationKind.KEEPASS -> {
                val native = keepass.openNativeBrowser(id).getOrThrow()
                if (row.type == MdbxStructureNodeType.FOLDER) keepass.renameNativeGroup(id, UUID.fromString(row.id), name, native.sourceRevision.sha256).getOrThrow()
                else {
                    val entry = native.entries.single { it.identity.entryUuid.toString() == row.id }
                    val changes = entry.fields.map { KeePassFieldChange(it.name, if (it.name == "Title") name else it.rawValue, it.isProtected) }
                    keepass.replaceNativeEntryFields(id, UUID.fromString(row.id), changes, native.sourceRevision.sha256).getOrThrow()
                }
            }
            ImportDestinationKind.LOCAL -> db.withTransaction {
                val key = row.id.substringAfter(':').toLong()
                when (row.id.substringBefore(':')) {
                    "folder" -> db.categoryDao().update(requireNotNull(db.categoryDao().getCategoryById(key)).copy(name = name))
                    "password" -> db.passwordEntryDao().updatePasswordEntry(requireNotNull(db.passwordEntryDao().getPasswordEntryById(key)).copy(title = name))
                    "item" -> db.secureItemDao().updateItem(requireNotNull(db.secureItemDao().getItemById(key)).copy(title = name))
                    else -> error("Use the entry detail page to rename this item.")
                }
            }
            else -> error("Unsupported database")
        }
        Unit
    }

    suspend fun transfer(source: DatabaseManagerLocation, target: DatabaseManagerLocation, selected: Set<String>, copy: Boolean,
        expectedRevision: String? = null,
        onProgress: suspend (Int, Int) -> Unit = { _, _ -> }): DatabaseManagerReport = transferMutex.withLock {
        withContext(Dispatchers.IO) {
            check(SessionManager.isUnlocked.value)
            val snapshot = browse(source)
            check(expectedRevision == null || expectedRevision == snapshot.revision) { "The source changed. Refresh and select again." }
            val destination = browse(target)
            check(target.folderId == null || target.folderId in destination.index.folders) { "Destination folder missing" }
            val roots = databaseManagerSelectionRoots(snapshot.nodes, selected)
            val failures = mutableListOf<DatabaseManagerFailure>()
            val batch = DatabaseManagerTransferContext()
            var completed = 0
            roots.forEachIndexed { index, row ->
                currentCoroutineContext().ensureActive()
                if (!SessionManager.isUnlocked.value) {
                    failures += DatabaseManagerFailure(row.name, "The vault is locked. The source was retained.")
                    return@forEachIndexed
                }
                try {
                    check(!(source.database == target.database && row.type == MdbxStructureNodeType.FOLDER &&
                        !snapshot.index.canMoveFolder(row.id, target.folderId))) { "A folder cannot be copied or moved into itself or its descendants." }
                    check(copy || source.database != target.database || snapshot.index.parent(row) != target.folderId) { "Already in the destination folder." }
                    val refreshed = browse(source)
                    check(refreshed.nodes.any { it.id == row.id && it.name == row.name && it.parentId == row.parentId }) {
                        "The source changed. Refresh and select again."
                    }
                    transferNode(source, target, refreshed, row, copy, batch)
                    completed++
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { failures += DatabaseManagerFailure(row.name,
                    generateSequence<Throwable>(error) { it.cause }.take(5).mapNotNull { it.message }.distinct().joinToString("\n")) }
                onProgress(index + 1, roots.size)
            }
            DatabaseManagerReport(completed, failures)
        }
    }

    private suspend fun transferNode(source: DatabaseManagerLocation, target: DatabaseManagerLocation,
        snapshot: DatabaseManagerSnapshot, row: MdbxStructureNode, copy: Boolean, batch: DatabaseManagerTransferContext) {
        currentCoroutineContext().ensureActive()
        check(SessionManager.isUnlocked.value) { "The vault is locked; source data was retained." }
        val from = source.database; val to = target.database
        val folder = row.type == MdbxStructureNodeType.FOLDER
        if (folder && !copy && from == to) {
            when (from.kind) {
                ImportDestinationKind.MDBX -> mdbx.moveFolder(from.databaseId, row.id, target.folderId)
                ImportDestinationKind.KEEPASS -> {
                    val native = keepass.openNativeBrowser(from.databaseId).getOrThrow()
                    keepass.moveNativeGroup(from.databaseId, UUID.fromString(row.id),
                        target.folderId?.let(UUID::fromString) ?: native.rootGroup.identity.groupUuid, native.sourceRevision.sha256).getOrThrow()
                }
                else -> error("Local categories cannot be nested.")
            }
            return
        }
        if (folder) {
            val descendants = databaseManagerDescendants(snapshot.nodes, row)
            if (to.kind == ImportDestinationKind.LOCAL) check(target.folderId == null && descendants.count { it.type == MdbxStructureNodeType.FOLDER } == 1) {
                "Nested folders cannot be represented by Monica local categories. Choose MDBX or KeePass."
            }
            if (from.kind == ImportDestinationKind.KEEPASS && to.kind != ImportDestinationKind.KEEPASS) {
                val native = keepass.openNativeBrowser(from.databaseId).getOrThrow()
                val groups = descendants.filter { it.type == MdbxStructureNodeType.FOLDER }.map { node -> native.groups.single { it.identity.groupUuid.toString() == node.id } }
                check(groups.all { it.notes.isBlank() && it.customData.isEmpty() && it.customIconUuid == null && it.tags.isEmpty() && it.defaultAutoTypeSequence.isNullOrEmpty() &&
                    !it.isInRecycleBin && it.times?.expires != true &&
                    it.enableAutoType == app.keemobile.kotpass.constants.GroupOverride.Inherit &&
                    it.enableSearching == app.keemobile.kotpass.constants.GroupOverride.Inherit }) {
                    "This folder contains KeePass-specific metadata. Move it within its original database to retain all properties."
                }
            }
            // Preflight every leaf before creating destination folders.
            val leaves = descendants.filter { it.type == MdbxStructureNodeType.ENTRY }
            if (from.kind != to.kind) leaves.forEach { managerPreflightPortable(this, source, it) }
            val targetId = createFolder(target, row.name)
            if (from.kind == ImportDestinationKind.KEEPASS && to.kind == ImportDestinationKind.KEEPASS) {
                val group = keepass.openNativeBrowser(from.databaseId).getOrThrow().groups.single { it.identity.groupUuid.toString() == row.id }
                kdbx.copyManagerGroupProfile(to.databaseId, UUID.fromString(targetId), group).getOrThrow()
            }
            if (from.kind == ImportDestinationKind.MDBX && to.kind == ImportDestinationKind.MDBX) {
                mdbx.managerFolderProfile(from.databaseId, row.id)?.let { mdbx.managerSetFolderProfile(to.databaseId, targetId, it) }
            }
            val childTarget = target.copy(folderId = targetId)
            // Copy all children first. A partial failure leaves the original hierarchy untouched.
            snapshot.index.children(row.id).forEach { transferNode(source, childTarget, snapshot, it, true, batch) }
            if (!copy) {
                // Delete only after every descendant is copied. Source changes are checked by each backend.
                removeCopiedFolder(source, snapshot, row)
            }
            return
        }
        when {
            from.kind == ImportDestinationKind.MDBX && to.kind == ImportDestinationKind.MDBX -> {
                if (!copy && from == to) mdbx.managerMove(from.databaseId, mdbx.nativeBrowser(from.databaseId).objects.getValue(row.id), target.folderId)
                else {
                    val capture = mdbx.managerCapture(from.databaseId, row.id)
                    try {
                        mdbx.managerCopy(to.databaseId, capture, target.folderId, batch.passwordGroups)
                        mdbx.flushPendingWorkingCopy(to.databaseId)
                        if (!copy) mdbx.managerDeleteVerifiedSource(from.databaseId, capture)
                    } finally { capture.clear() }
                }
            }
            from.kind == ImportDestinationKind.KEEPASS && to.kind == ImportDestinationKind.KEEPASS -> {
                val entry = keepass.openNativeBrowser(from.databaseId).getOrThrow().entries.single { it.identity.entryUuid.toString() == row.id }
                if (from != to) check(entry.fields.none { it.rawValue.contains("{REF:", true) } && entry.history.none { version -> version.fields.any { it.rawValue.contains("{REF:", true) } }) {
                    "This entry references other records. Transfer it within its current database to preserve these references."
                }
                val native = keepass.openNativeBrowser(to.databaseId).getOrThrow()
                val group = target.folderId?.let { key -> native.groups.single { it.identity.groupUuid.toString() == key } }
                check(group == null || native.groups.count { it.legacyPath == group.legacyPath } == 1) { "Ambiguous destination folder path; rename duplicate folders before transfer." }
                if (copy) kdbx.copyNativeEntry(from.databaseId, row.id, to.databaseId, group?.legacyPath).getOrThrow()
                else kdbx.moveNativeEntry(from.databaseId, row.id, to.databaseId, group?.legacyPath).getOrThrow()
            }
            from == ImportDestination.Local && to == from && !copy -> managerMoveLocal(this, row, target.folderId)
            else -> managerTransferPortable(this, source, target, row, copy, batch)
        }
    }

    private suspend fun removeCopiedFolder(source: DatabaseManagerLocation, snapshot: DatabaseManagerSnapshot, folder: MdbxStructureNode) {
        check(SessionManager.isUnlocked.value) { "The vault is locked; both copies were retained." }
        when (source.database.kind) {
            ImportDestinationKind.MDBX -> mdbx.managerDeleteTree(source.database.databaseId, folder.id, snapshot.revision)
            ImportDestinationKind.KEEPASS -> keepass.deleteNativeGroup(source.database.databaseId, UUID.fromString(folder.id), snapshot.revision).getOrThrow()
            ImportDestinationKind.LOCAL -> db.withTransaction {
                check(managerLocalRevision(this@DatabaseManagerRepository) == snapshot.revision) { "The source changed; both copies were retained." }
                val category = requireNotNull(db.categoryDao().getCategoryById(folder.id.substringAfter(':').toLong()))
                db.passwordEntryDao().getAllPasswordEntriesSync().filter { ImportDestination.Local.contains(it) && it.categoryId == category.id }.forEach {
                    db.passwordEntryDao().updatePasswordEntry(it.copy(isDeleted = true, deletedAt = java.util.Date()))
                }
                db.secureItemDao().getAllItemsIncludingArchived().first().filter { ImportDestination.Local.contains(it) && it.categoryId == category.id }.forEach {
                    db.secureItemDao().updateItem(it.copy(isDeleted = true, deletedAt = java.util.Date()))
                }
                // Keep the category while its recoverable items are in trash.
            }
            else -> error("Unsupported database")
        }
    }

    /** Confirmed selections are checked again inside each backend's atomic write. */
    suspend fun delete(location: DatabaseManagerLocation, selected: Set<String>, expectedRevision: String) = transferMutex.withLock {
        withContext(Dispatchers.IO) {
            check(SessionManager.isUnlocked.value) { "The vault is locked." }
            require(selected.isNotEmpty())
            val snapshot = browse(location)
            check(snapshot.revision == expectedRevision) { "The source changed. Refresh and select again." }
            val roots = databaseManagerSelectionRoots(snapshot.nodes, selected)
            when (location.database.kind) {
                ImportDestinationKind.LOCAL -> managerDeleteLocal(this@DatabaseManagerRepository, snapshot, roots)
                ImportDestinationKind.MDBX -> mdbx.managerDeleteSelection(location.database.databaseId, selected, expectedRevision)
                ImportDestinationKind.KEEPASS -> kdbx.deleteNativeManagerSelection(location.database.databaseId,
                    roots.filter { it.type == MdbxStructureNodeType.ENTRY }.map { UUID.fromString(it.id) }.toSet(),
                    roots.filter { it.type == MdbxStructureNodeType.FOLDER }.map { UUID.fromString(it.id) }.toSet(), expectedRevision).getOrThrow()
                else -> error("Unsupported database")
            }
        }
    }

    private fun node(id: String, parent: String?, title: String, folder: Boolean, type: String = "") =
        MdbxStructureNode(id, parent, title, if (folder) MdbxStructureNodeType.FOLDER else MdbxStructureNodeType.ENTRY,
            title, MdbxStructureNodeStatus.UNCHANGED, 0, type)

    companion object { private val transferMutex = Mutex() }
}

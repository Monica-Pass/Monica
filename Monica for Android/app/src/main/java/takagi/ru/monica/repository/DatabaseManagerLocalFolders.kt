package takagi.ru.monica.repository

import androidx.room.withTransaction
import takagi.ru.monica.R
import takagi.ru.monica.data.Category
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.utils.normalizeLocalCategoryPath

/** Local categories store paths, unlike the native backends' parent identities. Browsing never writes. */
internal class DatabaseManagerLocalFolders(categories: List<Category>, private val text: (Int) -> String = Int::toString) {
    val categories = categories.filter { it.mdbxDatabaseId == null && it.bitwardenVaultId == null }
    private val paths = this.categories.associate { it.id to normalize(it.name) }
    private val byPath = this.categories.groupBy { paths.getValue(it.id) }
    private val ids = linkedMapOf<String, String>().apply {
        byPath.forEach { (path, rows) -> put(path, "folder:${rows.minOf { it.id }}") }
        paths.values.forEach { path ->
            var parent = parentPath(path)
            while (parent != null) {
                putIfAbsent(parent, virtualId(parent))
                parent = parentPath(parent)
            }
        }
    }
    val nodes: List<MdbxStructureNode> = this.categories.map { category ->
        folder("folder:${category.id}", paths.getValue(category.id), category.name)
    } + ids.filterValues { it.startsWith(VIRTUAL_PREFIX) }.map { (path, id) -> folder(id, path, path) }

    private fun folder(id: String, path: String, original: String) = MdbxStructureNode(
        id, parentPath(path)?.let(ids::getValue), path.substringAfterLast('/').ifEmpty { original },
        MdbxStructureNodeType.FOLDER, path, MdbxStructureNodeStatus.UNCHANGED, 0, "")

    fun parentForEntry(categoryId: Long?): String? = categoryId?.takeIf(paths::containsKey)?.let { "folder:$it" }

    fun path(id: String): String = when {
        id.startsWith("folder:") -> id.removePrefix("folder:").toLongOrNull()?.let(paths::get) ?: error(text(R.string.entry_message_category_missing))
        id.startsWith(VIRTUAL_PREFIX) -> id.removePrefix(VIRTUAL_PREFIX).also {
            check(it in ids && it.isNotEmpty()) { text(R.string.entry_message_category_missing) }
        }
        else -> error(text(R.string.entry_message_category_path_invalid))
    }

    fun categoryId(id: String): Long? {
        val path = path(id)
        if (id.startsWith("folder:")) return id.removePrefix("folder:").toLong()
        val matches = byPath[path].orEmpty()
        check(matches.size <= 1) { text(R.string.entry_message_category_exists) }
        return matches.singleOrNull()?.id
    }

    fun descendants(id: String): List<Category> {
        val root = path(id)
        check(root.isNotEmpty()) { text(R.string.entry_message_category_path_invalid) }
        val affected = categories.filter { paths.getValue(it.id).let { path -> path == root || path.startsWith("$root/") } }
        check(affected.all { byPath.getValue(paths.getValue(it.id)).size == 1 }) {
            text(R.string.entry_message_category_exists)
        }
        return affected
    }

    fun nestedPath(parentId: String?, name: String): String {
        val child = normalize(name)
        require(child.isNotEmpty()) { text(R.string.entry_message_category_name_required) }
        val parent = parentId?.let(::path)
        if (parent != null) {
            check(parent.isNotEmpty()) { text(R.string.entry_message_category_path_invalid) }
            check(byPath[parent].orEmpty().size <= 1) { text(R.string.entry_message_category_exists) }
        }
        val result = if (parent.isNullOrEmpty()) child else "$parent/$child"
        require(result.length <= 512) { text(R.string.entry_message_category_path_invalid) }
        return result
    }

    fun requireNewPath(path: String) {
        check(path !in ids) { text(R.string.entry_message_category_exists) }
    }

    fun rebase(id: String, newPath: String): List<Category> {
        val oldPath = path(id)
        val affected = descendants(id)
        check(newPath != oldPath && !newPath.startsWith("$oldPath/")) { text(R.string.entry_message_category_move_into_self) }
        val affectedIds = affected.map { it.id }.toSet()
        // Refuse merging with either explicit categories or implicit ancestor paths.
        check(ids.keys.none { existing -> existing == newPath || existing.startsWith("$newPath/") }) {
            text(R.string.entry_message_category_exists)
        }
        return affected.map { category ->
            val renamed = newPath + paths.getValue(category.id).removePrefix(oldPath)
            require(renamed.length <= 512) { text(R.string.entry_message_category_path_invalid) }
            check(categories.none { it.id !in affectedIds && paths.getValue(it.id) == renamed })
            category.copy(name = renamed)
        }
    }

    companion object {
        const val VIRTUAL_PREFIX = "local-path:"
        fun normalize(path: String) = normalizeLocalCategoryPath(path)
        fun parentPath(path: String): String? = path.substringBeforeLast('/', "").ifEmpty { null }
        fun virtualId(path: String) = VIRTUAL_PREFIX + path
    }
}

internal suspend fun DatabaseManagerRepository.localFolders() = DatabaseManagerLocalFolders(db.categoryDao().getDeviceSyncCategories()) { context.getString(it) }

/** Only an explicit write to an implicit ancestor materializes a category. */
internal suspend fun DatabaseManagerRepository.localDestinationCategory(folderId: String?): Long? = db.withTransaction {
    check(SessionManager.isUnlocked.value) { "The vault is locked." }
    if (folderId == null) null else {
        val folders = localFolders()
        folders.categoryId(folderId) ?: db.categoryDao().insert(Category(name = folders.path(folderId)))
    }
}

internal suspend fun DatabaseManagerRepository.rebaseLocalFolder(row: MdbxStructureNode, parentId: String?, name: String) = db.withTransaction {
    check(SessionManager.isUnlocked.value) { "The vault is locked." }
    val folders = localFolders()
    val current = folders.nodes.singleOrNull { it.id == row.id }
    check(current == row) { "The folder changed. Refresh and select again." }
    val newPath = folders.nestedPath(parentId, name)
    if (newPath != folders.path(row.id)) {
        val updates = folders.rebase(row.id, newPath)
        check(updates.none { db.categoryDao().hasExternalEntries(it.id) }) { "This folder is also used by another database." }
        updates.forEach { db.categoryDao().update(it) }
    }
}

/** Saved navigation can still refer to a formerly implicit parent after an explicit item write. */
internal fun DatabaseManagerSnapshot.resolveLocalFolderId(id: String?): String? {
    if (location.database != takagi.ru.monica.credentialexchange.ImportDestination.Local || id == null || id in index.folders) return id
    if (!id.startsWith(DatabaseManagerLocalFolders.VIRTUAL_PREFIX)) return id
    val path = id.removePrefix(DatabaseManagerLocalFolders.VIRTUAL_PREFIX)
    return nodes.singleOrNull { it.type == MdbxStructureNodeType.FOLDER && it.path == path }?.id ?: id
}

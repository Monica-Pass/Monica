package takagi.ru.monica.repository

import androidx.room.withTransaction
import takagi.ru.monica.credentialexchange.ImportDestination
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.security.SessionManager
import java.util.Date

/** Keep recoverable content/attachments; remove keys only after the Room transaction commits. */
internal suspend fun managerDeleteLocal(repo: DatabaseManagerRepository, snapshot: DatabaseManagerSnapshot,
    roots: List<MdbxStructureNode>) {
    val removedPasskeys = mutableListOf<PasskeyEntry>()
    repo.db.withTransaction {
        check(SessionManager.isUnlocked.value) { "The vault is locked." }
        check(managerLocalRevision(repo) == snapshot.revision) { "The source changed. Refresh and select again." }
        val localFolders = repo.localFolders()
        roots.filter { it.type == MdbxStructureNodeType.FOLDER }.forEach { localFolders.descendants(it.id) }
        val affected = roots.flatMap { databaseManagerDescendants(snapshot.nodes, it) }.map { it.id }.toSet()
        val folders = affected.filter { it.startsWith("folder:") }.map { it.substringAfter(':').toLong() }.toSet()
        val passwords = repo.db.passwordEntryDao().getDeviceSyncLocalPasswords()
        val items = repo.db.secureItemDao().getDeviceSyncLocalItems()
        val passkeys = repo.db.passkeyDao().getAllPasskeysSync()
        check(folders.none { repo.db.categoryDao().hasExternalEntries(it) }) {
            "This folder is also used by another database. Move those items before deleting it."
        }
        val now = Date()
        passwords.filter { ImportDestination.Local.contains(it) && ("password:${it.id}" in affected || it.categoryId in folders) }.forEach {
            repo.db.passwordEntryDao().updatePasswordEntry(it.copy(
                isDeleted = true, deletedAt = it.deletedAt ?: now, updatedAt = now,
                categoryId = it.categoryId.takeUnless { id -> id in folders }))
            repo.db.attachmentDao().softDeleteByParent(it.id, now.time, now.time)
        }
        items.filter { ImportDestination.Local.contains(it) && ("item:${it.id}" in affected || it.categoryId in folders) }.forEach {
            repo.db.secureItemDao().updateItem(it.copy(
                isDeleted = true, deletedAt = it.deletedAt ?: now, updatedAt = now,
                categoryId = it.categoryId.takeUnless { id -> id in folders }))
            repo.db.attachmentDao().softDeleteBySecureItem(it.id, now.time, now.time)
        }
        passkeys.filter { ImportDestination.Local.contains(it) && "passkey:${it.id}" in affected }.forEach {
            repo.db.passkeyDao().delete(it)
            removedPasskeys += it
        }
        folders.forEach { id -> repo.db.categoryDao().getCategoryById(id)?.let { repo.db.categoryDao().delete(it) } }
    }
    val passkeys = PasskeyRepository(repo.db.passkeyDao(), context = repo.context)
    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
        removedPasskeys.forEach { passkeys.cleanupDeletedPasskey(it) }
    }
}

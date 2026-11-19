package takagi.ru.monica.timeline

import takagi.ru.monica.data.*
import java.util.Date


internal class JournalPasswordEntryDao(private val raw: PasswordEntryDao, private val journal: TimelineJournal) : PasswordEntryDao by raw {
    override suspend fun removeCategoryFromPasswords(categoryId: Long) : Unit =
        journal.mutateQuery("PASSWORD", """categoryId = :categoryId""", mapOf("categoryId" to categoryId)) { raw.removeCategoryFromPasswords(categoryId) }

    override suspend fun insertPasswordEntry(entry: PasswordEntry) : Long =
        journal.mutate("PASSWORD", listOf(entry.id), inserted = { listOf(it) }) { raw.insertPasswordEntry(entry) }

    override suspend fun insertPasswordEntries(entries: List<PasswordEntry>) : List<Long> =
        journal.mutate("PASSWORD", entries.map { it.id }, inserted = { it }) { raw.insertPasswordEntries(entries) }

    override suspend fun insert(entry: PasswordEntry) : Long =
        journal.mutate("PASSWORD", listOf(entry.id), inserted = { listOf(it) }) { raw.insert(entry) }

    override suspend fun updatePasswordEntry(entry: PasswordEntry) : Unit =
        journal.mutate("PASSWORD", listOf(entry.id)) { raw.updatePasswordEntry(entry) }

    override suspend fun updatePasswordEntries(entries: List<PasswordEntry>) : Unit =
        journal.mutate("PASSWORD", entries.map { it.id }) { raw.updatePasswordEntries(entries) }

    override suspend fun commitLocalPasswordSave(block: suspend () -> Unit) : Unit =
        journal.operation { super<PasswordEntryDao>.commitLocalPasswordSave(block) }

    override suspend fun deletePasswordEntry(entry: PasswordEntry) : Unit =
        journal.mutate("PASSWORD", listOf(entry.id)) { raw.deletePasswordEntry(entry) }

    override suspend fun deletePasswordEntries(entries: List<PasswordEntry>) : Unit =
        journal.mutate("PASSWORD", entries.map { it.id }) { raw.deletePasswordEntries(entries) }

    override suspend fun deletePasswordEntryById(id: Long) : Unit =
        journal.mutateQuery("PASSWORD", """id = :id""", mapOf("id" to id)) { raw.deletePasswordEntryById(id) }

    override suspend fun updateFavoriteStatus(id: Long, isFavorite: Boolean) : Unit =
        journal.mutateQuery("PASSWORD", """id = :id""", mapOf("id" to id)) { raw.updateFavoriteStatus(id, isFavorite) }

    override suspend fun updateUpdatedAt(id: Long, updatedAt: java.util.Date) : Unit =
        journal.mutateQuery("PASSWORD", """id = :id""", mapOf("id" to id)) { raw.updateUpdatedAt(id, updatedAt) }

    override suspend fun updateGroupCoverStatus(id: Long, isGroupCover: Boolean) : Unit =
        journal.mutateQuery("PASSWORD", """id = :id""", mapOf("id" to id)) { raw.updateGroupCoverStatus(id, isGroupCover) }

    override suspend fun clearGroupCover(website: String) : Unit =
        journal.mutateQuery("PASSWORD", """website = :website""", mapOf("website" to website)) { raw.clearGroupCover(website) }

    override suspend fun updateCategoryForPasswords(ids: List<Long>, categoryId: Long?) : Unit =
        journal.mutateQuery("PASSWORD", """id IN (:ids)""", mapOf("ids" to ids)) { raw.updateCategoryForPasswords(ids, categoryId) }

    override suspend fun updateBoundNoteId(id: Long, noteId: Long?) : Unit =
        journal.mutateQuery("PASSWORD", """id = :id""", mapOf("id" to id)) { raw.updateBoundNoteId(id, noteId) }

    override suspend fun clearBoundNoteReferences(noteId: Long) : Unit =
        journal.mutateQuery("PASSWORD", """boundNoteId = :noteId""", mapOf("noteId" to noteId)) { raw.clearBoundNoteReferences(noteId) }

    override suspend fun bindPasswordsToBitwardenFolder(ids: List<Long>, vaultId: Long, folderId: String) : Unit =
        journal.mutateQuery("PASSWORD", """id IN (:ids)
          AND keepassDatabaseId IS NULL
          AND isDeleted = 0
          AND isArchived = 0""", mapOf("ids" to ids)) { raw.bindPasswordsToBitwardenFolder(ids, vaultId, folderId) }

    override suspend fun clearPendingBitwardenBinding(ids: List<Long>) : Unit =
        journal.mutateQuery("PASSWORD", """id IN (:ids)
          AND bitwarden_cipher_id IS NULL
          AND isDeleted = 0
          AND isArchived = 0""", mapOf("ids" to ids)) { raw.clearPendingBitwardenBinding(ids) }

    override suspend fun bindCategoryToBitwarden(categoryId: Long, vaultId: Long, folderId: String) : Unit =
        journal.mutateQuery("PASSWORD", """categoryId = :categoryId
          AND keepassDatabaseId IS NULL
          AND isDeleted = 0
          AND isArchived = 0""", mapOf("categoryId" to categoryId)) { raw.bindCategoryToBitwarden(categoryId, vaultId, folderId) }

    override suspend fun updateKeePassDatabaseForPasswords(ids: List<Long>, databaseId: Long?) : Unit =
        journal.mutateQuery("PASSWORD", """id IN (:ids)""", mapOf("ids" to ids)) { raw.updateKeePassDatabaseForPasswords(ids, databaseId) }

    override suspend fun updateMdbxDatabaseForPasswords(ids: List<Long>, databaseId: Long?, folderId: String?, now: Long) : Unit =
        journal.mutateQuery("PASSWORD", """id IN (:ids)""", mapOf("ids" to ids)) { raw.updateMdbxDatabaseForPasswords(ids, databaseId, folderId, now) }

    override suspend fun updateKeePassGroupForPasswords(ids: List<Long>, databaseId: Long, groupPath: String) : Unit =
        journal.mutateQuery("PASSWORD", """id IN (:ids)""", mapOf("ids" to ids)) { raw.updateKeePassGroupForPasswords(ids, databaseId, groupPath) }

    override suspend fun clearKeePassBindingForDatabase(databaseId: Long) : Unit =
        journal.mutateQuery("PASSWORD", """keepassDatabaseId = :databaseId""", mapOf("databaseId" to databaseId)) { raw.clearKeePassBindingForDatabase(databaseId) }

    override suspend fun deleteByKeePassDatabaseId(databaseId: Long) : Unit =
        journal.mutateQuery("PASSWORD", """keepassDatabaseId = :databaseId""", mapOf("databaseId" to databaseId)) { raw.deleteByKeePassDatabaseId(databaseId) }

    override suspend fun clearBitwardenBindingForPasswords(ids: List<Long>) : Unit =
        journal.mutateQuery("PASSWORD", """id IN (:ids)
          AND isDeleted = 0
          AND isArchived = 0""", mapOf("ids" to ids)) { raw.clearBitwardenBindingForPasswords(ids) }

    override suspend fun setGroupCover(id: Long, website: String) : Unit =
        journal.operation { super<PasswordEntryDao>.setGroupCover(id, website) }

    override suspend fun updateAppAssociationByWebsite(website: String, packageName: String, appName: String, now: Long) : Unit =
        journal.mutateQuery("PASSWORD", """website = :website AND website != ''""", mapOf("website" to website)) { raw.updateAppAssociationByWebsite(website, packageName, appName, now) }

    override suspend fun updateAppAssociationByTitle(title: String, packageName: String, appName: String, now: Long) : Unit =
        journal.mutateQuery("PASSWORD", """title = :title AND title != ''""", mapOf("title" to title)) { raw.updateAppAssociationByTitle(title, packageName, appName, now) }

    override suspend fun clearLocalAccountAuthenticator(boundId: Long, matchesKey: (String) -> Boolean,
        retainsBinding: (SecureItem, Set<Long>) -> Boolean) : Boolean =
        journal.operation { super<PasswordEntryDao>.clearLocalAccountAuthenticator(boundId, matchesKey, retainsBinding) }

    override suspend fun updateAuthenticatorKey(id: Long, authenticatorKey: String) : Unit =
        journal.mutateQuery("PASSWORD", """id = :id""", mapOf("id" to id)) { raw.updateAuthenticatorKey(id, authenticatorKey) }

    override suspend fun compareAndSetAuthenticatorKey(id: Long, expected: String, replacement: String) : Int =
        journal.mutateQuery("PASSWORD", """id = :id AND authenticatorKey = :expected""", mapOf("id" to id, "expected" to expected)) { raw.compareAndSetAuthenticatorKey(id, expected, replacement) }

    override suspend fun updatePasskeyBindings(id: Long, passkeyBindings: String) : Unit =
        journal.mutateQuery("PASSWORD", """id = :id""", mapOf("id" to id)) { raw.updatePasskeyBindings(id, passkeyBindings) }

    override suspend fun updateSortOrder(id: Long, sortOrder: Int) : Unit =
        journal.mutateQuery("PASSWORD", """id = :id""", mapOf("id" to id)) { raw.updateSortOrder(id, sortOrder) }

    override suspend fun updateSortOrders(items: List<Pair<Long, Int>>) : Unit =
        journal.operation { super<PasswordEntryDao>.updateSortOrders(items) }

    override suspend fun deleteAllPasswordEntries() : Unit =
        journal.mutateQuery("PASSWORD", """1""", emptyMap()) { raw.deleteAllPasswordEntries() }

    override suspend fun deleteAllLocalPasswordEntries() : Unit =
        journal.mutateQuery("PASSWORD", """bitwarden_vault_id IS NULL
          AND keepassDatabaseId IS NULL
          AND mdbx_database_id IS NULL""", emptyMap()) { raw.deleteAllLocalPasswordEntries() }

    override suspend fun deleteAllByMdbxDatabaseId(databaseId: Long) : Unit =
        journal.mutateQuery("PASSWORD", """mdbx_database_id = :databaseId""", mapOf("databaseId" to databaseId)) { raw.deleteAllByMdbxDatabaseId(databaseId) }

    override suspend fun archiveById(id: Long, archivedAt: java.util.Date, updatedAt: java.util.Date) : Unit =
        journal.mutateQuery("PASSWORD", """id = :id AND isDeleted = 0""", mapOf("id" to id)) { raw.archiveById(id, archivedAt, updatedAt) }

    override suspend fun archiveByIds(ids: List<Long>, archivedAt: java.util.Date, updatedAt: java.util.Date) : Unit =
        journal.mutateQuery("PASSWORD", """id IN (:ids) AND isDeleted = 0""", mapOf("ids" to ids)) { raw.archiveByIds(ids, archivedAt, updatedAt) }

    override suspend fun unarchiveById(id: Long, updatedAt: java.util.Date) : Unit =
        journal.mutateQuery("PASSWORD", """id = :id AND isDeleted = 0""", mapOf("id" to id)) { raw.unarchiveById(id, updatedAt) }

    override suspend fun unarchiveByIds(ids: List<Long>, updatedAt: java.util.Date) : Unit =
        journal.mutateQuery("PASSWORD", """id IN (:ids) AND isDeleted = 0""", mapOf("ids" to ids)) { raw.unarchiveByIds(ids, updatedAt) }

    override suspend fun softDelete(id: Long, deletedAt: Long) : Unit =
        journal.mutateQuery("PASSWORD", """id = :id""", mapOf("id" to id)) { raw.softDelete(id, deletedAt) }

    override suspend fun restore(id: Long) : Unit =
        journal.mutateQuery("PASSWORD", """id = :id""", mapOf("id" to id)) { raw.restore(id) }

    override suspend fun permanentlyDeleteAll() : Unit =
        journal.mutateQuery("PASSWORD", """isDeleted = 1""", emptyMap()) { raw.permanentlyDeleteAll() }

    override suspend fun deleteExpiredItems(cutoffDate: java.util.Date) : Unit =
        journal.mutateQuery("PASSWORD", """isDeleted = 1 AND deletedAt < :cutoffDate""", mapOf("cutoffDate" to cutoffDate)) { raw.deleteExpiredItems(cutoffDate) }

    override suspend fun update(entry: PasswordEntry) : Unit =
        journal.mutate("PASSWORD", listOf(entry.id)) { raw.update(entry) }

    override suspend fun updateAll(entries: List<PasswordEntry>) : Unit =
        journal.mutate("PASSWORD", entries.map { it.id }) { raw.updateAll(entries) }

    override suspend fun delete(entry: PasswordEntry) : Unit =
        journal.mutate("PASSWORD", listOf(entry.id)) { raw.delete(entry) }

    override suspend fun deleteAllSyncedBitwardenEntries(vaultId: Long) : Unit =
        journal.mutateQuery("PASSWORD", """bitwarden_vault_id = :vaultId
          AND bitwarden_cipher_id IS NOT NULL
          AND isDeleted = 0""", mapOf("vaultId" to vaultId)) { raw.deleteAllSyncedBitwardenEntries(vaultId) }

    override suspend fun deleteBitwardenEntriesNotIn(vaultId: Long, keepIds: List<String>) : Unit =
        journal.mutateQuery("PASSWORD", """bitwarden_vault_id = :vaultId
          AND bitwarden_cipher_id IS NOT NULL
          AND isDeleted = 0
          AND bitwarden_cipher_id NOT IN (:keepIds)
          AND NOT EXISTS (
              SELECT 1
              FROM bitwarden_pending_operations op
              WHERE op.vault_id = :vaultId
                AND op.bitwarden_cipher_id = password_entries.bitwarden_cipher_id
                AND op.operation_type = 'RESTORE'
                AND op.status IN ('PENDING', 'IN_PROGRESS', 'FAILED')
          )""", mapOf("vaultId" to vaultId, "keepIds" to keepIds)) { raw.deleteBitwardenEntriesNotIn(vaultId, keepIds) }

    override suspend fun clearBitwardenLocalModifiedFlag(vaultId: Long) : Unit =
        journal.mutateQuery("PASSWORD", """bitwarden_vault_id = :vaultId""", mapOf("vaultId" to vaultId)) { raw.clearBitwardenLocalModifiedFlag(vaultId) }

    override suspend fun updateBitwardenSyncInfo(entryId: Long, revisionDate: String?) : Unit =
        journal.mutateQuery("PASSWORD", """id = :entryId""", mapOf("entryId" to entryId)) { raw.updateBitwardenSyncInfo(entryId, revisionDate) }

    override suspend fun deleteAllByBitwardenVaultId(vaultId: Long) : Unit =
        journal.mutateQuery("PASSWORD", """bitwarden_vault_id = :vaultId""", mapOf("vaultId" to vaultId)) { raw.deleteAllByBitwardenVaultId(vaultId) }

    override suspend fun markHistoricalPendingBitwardenUploads(vaultId: Long) : Int =
        journal.mutateQuery("PASSWORD", """bitwarden_vault_id = :vaultId
          AND bitwarden_cipher_id IS NULL
          AND bitwarden_local_modified = 0
          AND isDeleted = 0""", mapOf("vaultId" to vaultId)) { raw.markHistoricalPendingBitwardenUploads(vaultId) }

    override suspend fun markAllForBitwarden(vaultId: Long) : Unit =
        journal.mutateQuery("PASSWORD", """bitwarden_vault_id IS NULL AND isDeleted = 0""", emptyMap()) { raw.markAllForBitwarden(vaultId) }

}


internal class JournalSecureItemDao(private val raw: SecureItemDao, private val journal: TimelineJournal) : SecureItemDao by raw {
    override suspend fun removeCategoryFromItems(categoryId: Long) : Unit =
        journal.mutateQuery("SECURE", """categoryId = :categoryId""", mapOf("categoryId" to categoryId)) { raw.removeCategoryFromItems(categoryId) }

    override suspend fun updateItemData(id: Long, itemData: String) : Unit =
        journal.mutateQuery("SECURE", """id = :id""", mapOf("id" to id)) { raw.updateItemData(id, itemData) }

    override suspend fun compareAndSetItemData(id: Long, expected: String, replacement: String) : Int =
        journal.mutateQuery("SECURE", """id = :id AND itemData = :expected""", mapOf("id" to id, "expected" to expected)) { raw.compareAndSetItemData(id, expected, replacement) }

    override suspend fun compareAndSetNoteData(id: Long, expectedItemData: String, expectedNotes: String, itemData: String, notes: String) : Int =
        journal.mutateQuery("SECURE", """id = :id AND itemData = :expectedItemData AND notes = :expectedNotes""", mapOf("id" to id, "expectedItemData" to expectedItemData, "expectedNotes" to expectedNotes)) { raw.compareAndSetNoteData(id, expectedItemData, expectedNotes, itemData, notes) }

    override suspend fun insertItem(item: SecureItem) : Long =
        journal.mutate("SECURE", listOf(item.id), inserted = { listOf(it) }) { raw.insertItem(item) }

    override suspend fun insertItems(items: List<SecureItem>) : List<SecureItem> =
        journal.mutate("SECURE", items.map { it.id }, inserted = { result -> result.map { it.id } }) { raw.insertItems(items) }

    override suspend fun updateItem(item: SecureItem) : Unit =
        journal.mutate("SECURE", listOf(item.id)) { raw.updateItem(item) }

    override suspend fun updateItems(items: List<SecureItem>) : Unit =
        journal.mutate("SECURE", items.map { it.id }) { raw.updateItems(items) }

    override suspend fun deleteItem(item: SecureItem) : Unit =
        journal.mutate("SECURE", listOf(item.id)) { raw.deleteItem(item) }

    override suspend fun deleteItemById(id: Long) : Unit =
        journal.mutateQuery("SECURE", """id = :id""", mapOf("id" to id)) { raw.deleteItemById(id) }

    override suspend fun deleteItemsByIds(ids: List<Long>) : Unit =
        journal.mutateQuery("SECURE", """id IN (:ids)""", mapOf("ids" to ids)) { raw.deleteItemsByIds(ids) }

    override suspend fun updateFavoriteStatus(id: Long, isFavorite: Boolean) : Unit =
        journal.mutateQuery("SECURE", """id = :id""", mapOf("id" to id)) { raw.updateFavoriteStatus(id, isFavorite) }

    override suspend fun updateSortOrder(id: Long, sortOrder: Int) : Unit =
        journal.mutateQuery("SECURE", """id = :id""", mapOf("id" to id)) { raw.updateSortOrder(id, sortOrder) }

    override suspend fun updateSortOrders(items: List<Pair<Long, Int>>) : Unit =
        journal.operation { super<SecureItemDao>.updateSortOrders(items) }

    override suspend fun clearKeePassBindingForDatabase(databaseId: Long) : Unit =
        journal.mutateQuery("SECURE", """keepass_database_id = :databaseId""", mapOf("databaseId" to databaseId)) { raw.clearKeePassBindingForDatabase(databaseId) }

    override suspend fun deleteByKeePassDatabaseId(databaseId: Long) : Unit =
        journal.mutateQuery("SECURE", """keepass_database_id = :databaseId""", mapOf("databaseId" to databaseId)) { raw.deleteByKeePassDatabaseId(databaseId) }

    override suspend fun clearKeePassBindingForIds(ids: List<Long>) : Unit =
        journal.mutateQuery("SECURE", """id IN (:ids)""", mapOf("ids" to ids)) { raw.clearKeePassBindingForIds(ids) }

    override suspend fun deleteAllItemsByType(type: ItemType) : Unit =
        journal.mutateQuery("SECURE", """itemType = :type""", mapOf("type" to type)) { raw.deleteAllItemsByType(type) }

    override suspend fun deleteAllLocalItemsByType(type: ItemType) : Unit =
        journal.mutateQuery("SECURE", """itemType = :type
          AND bitwarden_vault_id IS NULL
          AND keepass_database_id IS NULL""", mapOf("type" to type)) { raw.deleteAllLocalItemsByType(type) }

    override suspend fun deleteAllByMdbxDatabaseId(databaseId: Long) : Unit =
        journal.mutateQuery("SECURE", """mdbx_database_id = :databaseId""", mapOf("databaseId" to databaseId)) { raw.deleteAllByMdbxDatabaseId(databaseId) }

    override suspend fun deleteAllItems() : Unit =
        journal.mutateQuery("SECURE", """1""", emptyMap()) { raw.deleteAllItems() }

    override suspend fun softDelete(id: Long, deletedAt: Long) : Unit =
        journal.mutateQuery("SECURE", """id = :id""", mapOf("id" to id)) { raw.softDelete(id, deletedAt) }

    override suspend fun restore(id: Long) : Unit =
        journal.mutateQuery("SECURE", """id = :id""", mapOf("id" to id)) { raw.restore(id) }

    override suspend fun permanentlyDeleteAll() : Unit =
        journal.mutateQuery("SECURE", """isDeleted = 1""", emptyMap()) { raw.permanentlyDeleteAll() }

    override suspend fun deleteExpiredItems(cutoffDate: java.util.Date) : Unit =
        journal.mutateQuery("SECURE", """isDeleted = 1 AND deletedAt < :cutoffDate""", mapOf("cutoffDate" to cutoffDate)) { raw.deleteExpiredItems(cutoffDate) }

    override suspend fun update(item: SecureItem) : Unit =
        journal.mutate("SECURE", listOf(item.id)) { raw.update(item) }

    override suspend fun updateAll(items: List<SecureItem>) : Unit =
        journal.mutate("SECURE", items.map { it.id }) { raw.updateAll(items) }

    override suspend fun delete(item: SecureItem) : Unit =
        journal.mutate("SECURE", listOf(item.id)) { raw.delete(item) }

    override suspend fun insert(item: SecureItem) : Long =
        journal.mutate("SECURE", listOf(item.id), inserted = { listOf(it) }) { raw.insert(item) }

    override suspend fun markAllForBitwarden(vaultId: Long) : Unit =
        journal.mutateQuery("SECURE", """bitwarden_vault_id IS NULL AND isDeleted = 0""", emptyMap()) { raw.markAllForBitwarden(vaultId) }

    override suspend fun deleteAllByBitwardenVaultId(vaultId: Long) : Unit =
        journal.mutateQuery("SECURE", """bitwarden_vault_id = :vaultId""", mapOf("vaultId" to vaultId)) { raw.deleteAllByBitwardenVaultId(vaultId) }

    override suspend fun deleteAllSyncedBitwardenEntries(vaultId: Long) : Unit =
        journal.mutateQuery("SECURE", """bitwarden_vault_id = :vaultId
          AND bitwarden_cipher_id IS NOT NULL
          AND isDeleted = 0""", mapOf("vaultId" to vaultId)) { raw.deleteAllSyncedBitwardenEntries(vaultId) }

    override suspend fun deleteBitwardenEntriesNotIn(vaultId: Long, keepIds: List<String>) : Unit =
        journal.mutateQuery("SECURE", """bitwarden_vault_id = :vaultId
          AND bitwarden_cipher_id IS NOT NULL
          AND isDeleted = 0
          AND bitwarden_cipher_id NOT IN (:keepIds)
          AND NOT EXISTS (
              SELECT 1
              FROM bitwarden_pending_operations op
              WHERE op.vault_id = :vaultId
                AND op.bitwarden_cipher_id = secure_items.bitwarden_cipher_id
                AND op.operation_type = 'RESTORE'
                AND op.status IN ('PENDING', 'IN_PROGRESS', 'FAILED')
          )""", mapOf("vaultId" to vaultId, "keepIds" to keepIds)) { raw.deleteBitwardenEntriesNotIn(vaultId, keepIds) }

    override suspend fun markSynced(id: Long, revisionDate: String) : Unit =
        journal.mutateQuery("SECURE", """id = :id""", mapOf("id" to id)) { raw.markSynced(id, revisionDate) }

    override suspend fun markLocalModified(id: Long) : Unit =
        journal.mutateQuery("SECURE", """id = :id""", mapOf("id" to id)) { raw.markLocalModified(id) }

}

internal class JournalCustomFieldDao(private val raw: CustomFieldDao, private val journal: TimelineJournal) : CustomFieldDao by raw {
    private suspend fun owners(ids: List<Long>): List<Long> = ids.mapNotNull { raw.getFieldById(it)?.entryId }
    override suspend fun insert(field: CustomField): Long = journal.operation {
        journal.mutate("PASSWORD", listOf(field.entryId) + owners(listOf(field.id))) { raw.insert(field) }
    }
    override suspend fun insertAll(fields: List<CustomField>): List<Long> = journal.operation {
        journal.mutate("PASSWORD", fields.map { it.entryId } + owners(fields.map { it.id })) { raw.insertAll(fields) }
    }
    override suspend fun update(field: CustomField) { journal.operation {
        journal.mutate("PASSWORD", listOf(field.entryId) + owners(listOf(field.id))) { raw.update(field) }
    } }
    override suspend fun updateAll(fields: List<CustomField>) { journal.operation {
        journal.mutate("PASSWORD", fields.map { it.entryId } + owners(fields.map { it.id })) { raw.updateAll(fields) }
    } }
    override suspend fun delete(field: CustomField) = deleteById(field.id)
    override suspend fun deleteById(id: Long) { journal.operation {
        journal.mutate("PASSWORD", owners(listOf(id))) { raw.deleteById(id) }
    } }
    override suspend fun deleteByEntryId(entryId: Long) { journal.mutate("PASSWORD", listOf(entryId)) { raw.deleteByEntryId(entryId) } }
    override suspend fun deleteByEntryIds(entryIds: List<Long>) { journal.mutate("PASSWORD", entryIds) { raw.deleteByEntryIds(entryIds) } }
    override suspend fun deleteAll() { journal.mutateWhere("PASSWORD", "1") { raw.deleteAll() } }
    override suspend fun updateSortOrder(id: Long, sortOrder: Int) { journal.operation {
        journal.mutate("PASSWORD", owners(listOf(id))) { raw.updateSortOrder(id, sortOrder) }
    } }
    override suspend fun updateProtectedStatus(id: Long, isProtected: Boolean) { journal.operation {
        journal.mutate("PASSWORD", owners(listOf(id))) { raw.updateProtectedStatus(id, isProtected) }
    } }
    override suspend fun replaceFieldsForEntry(entryId: Long, newFields: List<CustomField>) {
        journal.mutate("PASSWORD", listOf(entryId)) { raw.replaceFieldsForEntry(entryId, newFields) }
    }
    override suspend fun updateSortOrders(updates: List<Pair<Long, Int>>) { journal.operation {
        journal.mutate("PASSWORD", owners(updates.map { it.first })) { raw.updateSortOrders(updates) }
    } }
}

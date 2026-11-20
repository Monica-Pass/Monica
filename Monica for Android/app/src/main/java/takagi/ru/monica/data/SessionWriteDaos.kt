package takagi.ru.monica.data

import java.util.Date


internal class SessionPasswordEntryDao(private val raw: PasswordEntryDao, private val guard: DatabaseWriteGuard) : PasswordEntryDao by raw {
    override suspend fun removeCategoryFromPasswords(categoryId: Long) : Unit =
        guard.write { raw.removeCategoryFromPasswords(categoryId) }

    override suspend fun insertPasswordEntry(entry: PasswordEntry) : Long =
        guard.write { raw.insertPasswordEntry(entry) }

    override suspend fun insertPasswordEntries(entries: List<PasswordEntry>) : List<Long> =
        guard.write { raw.insertPasswordEntries(entries) }

    override suspend fun insert(entry: PasswordEntry) : Long =
        guard.write { raw.insert(entry) }

    override suspend fun updatePasswordEntry(entry: PasswordEntry) : Unit =
        guard.write { raw.updatePasswordEntry(entry) }

    override suspend fun updatePasswordEntries(entries: List<PasswordEntry>) : Unit =
        guard.write { raw.updatePasswordEntries(entries) }

    override suspend fun commitLocalPasswordSave(block: suspend () -> Unit) : Unit =
        guard.write { super<PasswordEntryDao>.commitLocalPasswordSave(block) }

    override suspend fun deletePasswordEntry(entry: PasswordEntry) : Unit =
        guard.delete("PASSWORD", listOf(entry.id)) { raw.deletePasswordEntry(entry) }

    override suspend fun deletePasswordEntries(entries: List<PasswordEntry>) : Unit =
        guard.delete("PASSWORD", entries.map { it.id }) { raw.deletePasswordEntries(entries) }

    override suspend fun deletePasswordEntryById(id: Long) : Unit =
        guard.deleteQuery("PASSWORD", """id = :id""", mapOf("id" to id)) { raw.deletePasswordEntryById(id) }

    override suspend fun updateFavoriteStatus(id: Long, isFavorite: Boolean) : Unit =
        guard.write { raw.updateFavoriteStatus(id, isFavorite) }

    override suspend fun updateUpdatedAt(id: Long, updatedAt: java.util.Date) : Unit =
        guard.write { raw.updateUpdatedAt(id, updatedAt) }

    override suspend fun updateGroupCoverStatus(id: Long, isGroupCover: Boolean) : Unit =
        guard.write { raw.updateGroupCoverStatus(id, isGroupCover) }

    override suspend fun clearGroupCover(website: String) : Unit =
        guard.write { raw.clearGroupCover(website) }

    override suspend fun updateCategoryForPasswords(ids: List<Long>, categoryId: Long?) : Unit =
        guard.write { raw.updateCategoryForPasswords(ids, categoryId) }

    override suspend fun updateBoundNoteId(id: Long, noteId: Long?) : Unit =
        guard.write { raw.updateBoundNoteId(id, noteId) }

    override suspend fun clearBoundNoteReferences(noteId: Long) : Unit =
        guard.write { raw.clearBoundNoteReferences(noteId) }

    override suspend fun bindPasswordsToBitwardenFolder(ids: List<Long>, vaultId: Long, folderId: String) : Unit =
        guard.write { raw.bindPasswordsToBitwardenFolder(ids, vaultId, folderId) }

    override suspend fun clearPendingBitwardenBinding(ids: List<Long>) : Unit =
        guard.write { raw.clearPendingBitwardenBinding(ids) }

    override suspend fun bindCategoryToBitwarden(categoryId: Long, vaultId: Long, folderId: String) : Unit =
        guard.write { raw.bindCategoryToBitwarden(categoryId, vaultId, folderId) }

    override suspend fun updateKeePassDatabaseForPasswords(ids: List<Long>, databaseId: Long?) : Unit =
        guard.write { raw.updateKeePassDatabaseForPasswords(ids, databaseId) }

    override suspend fun updateMdbxDatabaseForPasswords(ids: List<Long>, databaseId: Long?, folderId: String?, now: Long) : Unit =
        guard.write { raw.updateMdbxDatabaseForPasswords(ids, databaseId, folderId, now) }

    override suspend fun updateKeePassGroupForPasswords(ids: List<Long>, databaseId: Long, groupPath: String) : Unit =
        guard.write { raw.updateKeePassGroupForPasswords(ids, databaseId, groupPath) }

    override suspend fun clearKeePassBindingForDatabase(databaseId: Long) : Unit =
        guard.write { raw.clearKeePassBindingForDatabase(databaseId) }

    override suspend fun deleteByKeePassDatabaseId(databaseId: Long) : Unit =
        guard.deleteQuery("PASSWORD", """keepassDatabaseId = :databaseId""", mapOf("databaseId" to databaseId)) { raw.deleteByKeePassDatabaseId(databaseId) }

    override suspend fun clearBitwardenBindingForPasswords(ids: List<Long>) : Unit =
        guard.write { raw.clearBitwardenBindingForPasswords(ids) }

    override suspend fun setGroupCover(id: Long, website: String) : Unit =
        guard.write { super<PasswordEntryDao>.setGroupCover(id, website) }

    override suspend fun updateAppAssociationByWebsite(website: String, packageName: String, appName: String, now: Long) : Unit =
        guard.write { raw.updateAppAssociationByWebsite(website, packageName, appName, now) }

    override suspend fun updateAppAssociationByTitle(title: String, packageName: String, appName: String, now: Long) : Unit =
        guard.write { raw.updateAppAssociationByTitle(title, packageName, appName, now) }

    override suspend fun clearLocalAccountAuthenticator(boundId: Long, matchesKey: (String) -> Boolean,
        retainsBinding: (SecureItem, Set<Long>) -> Boolean) : Boolean =
        guard.write { super<PasswordEntryDao>.clearLocalAccountAuthenticator(boundId, matchesKey, retainsBinding) }

    override suspend fun updateAuthenticatorKey(id: Long, authenticatorKey: String) : Unit =
        guard.write { raw.updateAuthenticatorKey(id, authenticatorKey) }

    override suspend fun compareAndSetAuthenticatorKey(id: Long, expected: String, replacement: String) : Int =
        guard.write { raw.compareAndSetAuthenticatorKey(id, expected, replacement) }

    override suspend fun updatePasskeyBindings(id: Long, passkeyBindings: String) : Unit =
        guard.write { raw.updatePasskeyBindings(id, passkeyBindings) }

    override suspend fun updateSortOrder(id: Long, sortOrder: Int) : Unit =
        guard.write { raw.updateSortOrder(id, sortOrder) }

    override suspend fun updateSortOrders(items: List<Pair<Long, Int>>) : Unit =
        guard.write { super<PasswordEntryDao>.updateSortOrders(items) }

    override suspend fun deleteAllPasswordEntries() : Unit =
        guard.deleteQuery("PASSWORD", """1""", emptyMap()) { raw.deleteAllPasswordEntries() }

    override suspend fun deleteAllLocalPasswordEntries() : Unit =
        guard.deleteQuery("PASSWORD", """bitwarden_vault_id IS NULL
          AND keepassDatabaseId IS NULL
          AND mdbx_database_id IS NULL""", emptyMap()) { raw.deleteAllLocalPasswordEntries() }

    override suspend fun deleteAllByMdbxDatabaseId(databaseId: Long) : Unit =
        guard.deleteQuery("PASSWORD", """mdbx_database_id = :databaseId""", mapOf("databaseId" to databaseId)) { raw.deleteAllByMdbxDatabaseId(databaseId) }

    override suspend fun archiveById(id: Long, archivedAt: java.util.Date, updatedAt: java.util.Date) : Unit =
        guard.write { raw.archiveById(id, archivedAt, updatedAt) }

    override suspend fun archiveByIds(ids: List<Long>, archivedAt: java.util.Date, updatedAt: java.util.Date) : Unit =
        guard.write { raw.archiveByIds(ids, archivedAt, updatedAt) }

    override suspend fun unarchiveById(id: Long, updatedAt: java.util.Date) : Unit =
        guard.write { raw.unarchiveById(id, updatedAt) }

    override suspend fun unarchiveByIds(ids: List<Long>, updatedAt: java.util.Date) : Unit =
        guard.write { raw.unarchiveByIds(ids, updatedAt) }

    override suspend fun softDelete(id: Long, deletedAt: Long) : Unit =
        guard.write { raw.softDelete(id, deletedAt) }

    override suspend fun restore(id: Long) : Unit =
        guard.write { raw.restore(id) }

    override suspend fun permanentlyDeleteAll() : Unit =
        guard.deleteQuery("PASSWORD", """isDeleted = 1""", emptyMap()) { raw.permanentlyDeleteAll() }

    override suspend fun deleteExpiredItems(cutoffDate: java.util.Date) : Unit =
        guard.deleteQuery("PASSWORD", """isDeleted = 1 AND deletedAt < :cutoffDate""", mapOf("cutoffDate" to cutoffDate)) { raw.deleteExpiredItems(cutoffDate) }

    override suspend fun update(entry: PasswordEntry) : Unit =
        guard.write { raw.update(entry) }

    override suspend fun updateAll(entries: List<PasswordEntry>) : Unit =
        guard.write { raw.updateAll(entries) }

    override suspend fun delete(entry: PasswordEntry) : Unit =
        guard.delete("PASSWORD", listOf(entry.id)) { raw.delete(entry) }

    override suspend fun deleteAllSyncedBitwardenEntries(vaultId: Long) : Unit =
        guard.deleteQuery("PASSWORD", """bitwarden_vault_id = :vaultId
          AND bitwarden_cipher_id IS NOT NULL
          AND isDeleted = 0""", mapOf("vaultId" to vaultId)) { raw.deleteAllSyncedBitwardenEntries(vaultId) }

    override suspend fun deleteBitwardenEntriesNotIn(vaultId: Long, keepIds: List<String>) : Unit =
        guard.deleteQuery("PASSWORD", """bitwarden_vault_id = :vaultId
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
        guard.write { raw.clearBitwardenLocalModifiedFlag(vaultId) }

    override suspend fun updateBitwardenSyncInfo(entryId: Long, revisionDate: String?) : Unit =
        guard.write { raw.updateBitwardenSyncInfo(entryId, revisionDate) }

    override suspend fun deleteAllByBitwardenVaultId(vaultId: Long) : Unit =
        guard.deleteQuery("PASSWORD", """bitwarden_vault_id = :vaultId""", mapOf("vaultId" to vaultId)) { raw.deleteAllByBitwardenVaultId(vaultId) }

    override suspend fun markHistoricalPendingBitwardenUploads(vaultId: Long) : Int =
        guard.write { raw.markHistoricalPendingBitwardenUploads(vaultId) }

    override suspend fun markAllForBitwarden(vaultId: Long) : Unit =
        guard.write { raw.markAllForBitwarden(vaultId) }

}


internal class SessionSecureItemDao(private val raw: SecureItemDao, private val guard: DatabaseWriteGuard) : SecureItemDao by raw {
    override suspend fun removeCategoryFromItems(categoryId: Long) : Unit =
        guard.write { raw.removeCategoryFromItems(categoryId) }

    override suspend fun updateItemData(id: Long, itemData: String) : Unit =
        guard.write { raw.updateItemData(id, itemData) }

    override suspend fun compareAndSetItemData(id: Long, expected: String, replacement: String) : Int =
        guard.write { raw.compareAndSetItemData(id, expected, replacement) }

    override suspend fun compareAndSetNoteData(id: Long, expectedItemData: String, expectedNotes: String, itemData: String, notes: String) : Int =
        guard.write { raw.compareAndSetNoteData(id, expectedItemData, expectedNotes, itemData, notes) }

    override suspend fun insertItem(item: SecureItem) : Long =
        guard.write { raw.insertItem(item) }

    override suspend fun insertItems(items: List<SecureItem>) : List<SecureItem> =
        guard.write { raw.insertItems(items) }

    override suspend fun updateItem(item: SecureItem) : Unit =
        guard.write { raw.updateItem(item) }

    override suspend fun updateItems(items: List<SecureItem>) : Unit =
        guard.write { raw.updateItems(items) }

    override suspend fun deleteItem(item: SecureItem) : Unit =
        guard.delete("SECURE", listOf(item.id)) { raw.deleteItem(item) }

    override suspend fun deleteItemById(id: Long) : Unit =
        guard.deleteQuery("SECURE", """id = :id""", mapOf("id" to id)) { raw.deleteItemById(id) }

    override suspend fun deleteItemsByIds(ids: List<Long>) : Unit =
        guard.deleteQuery("SECURE", """id IN (:ids)""", mapOf("ids" to ids)) { raw.deleteItemsByIds(ids) }

    override suspend fun updateFavoriteStatus(id: Long, isFavorite: Boolean) : Unit =
        guard.write { raw.updateFavoriteStatus(id, isFavorite) }

    override suspend fun updateSortOrder(id: Long, sortOrder: Int) : Unit =
        guard.write { raw.updateSortOrder(id, sortOrder) }

    override suspend fun updateSortOrders(items: List<Pair<Long, Int>>) : Unit =
        guard.write { super<SecureItemDao>.updateSortOrders(items) }

    override suspend fun clearKeePassBindingForDatabase(databaseId: Long) : Unit =
        guard.write { raw.clearKeePassBindingForDatabase(databaseId) }

    override suspend fun deleteByKeePassDatabaseId(databaseId: Long) : Unit =
        guard.deleteQuery("SECURE", """keepass_database_id = :databaseId""", mapOf("databaseId" to databaseId)) { raw.deleteByKeePassDatabaseId(databaseId) }

    override suspend fun clearKeePassBindingForIds(ids: List<Long>) : Unit =
        guard.write { raw.clearKeePassBindingForIds(ids) }

    override suspend fun deleteAllItemsByType(type: ItemType) : Unit =
        guard.deleteQuery("SECURE", """itemType = :type""", mapOf("type" to type)) { raw.deleteAllItemsByType(type) }

    override suspend fun deleteAllLocalItemsByType(type: ItemType) : Unit =
        guard.deleteQuery("SECURE", """itemType = :type
          AND bitwarden_vault_id IS NULL
          AND keepass_database_id IS NULL""", mapOf("type" to type)) { raw.deleteAllLocalItemsByType(type) }

    override suspend fun deleteAllByMdbxDatabaseId(databaseId: Long) : Unit =
        guard.deleteQuery("SECURE", """mdbx_database_id = :databaseId""", mapOf("databaseId" to databaseId)) { raw.deleteAllByMdbxDatabaseId(databaseId) }

    override suspend fun deleteAllItems() : Unit =
        guard.deleteQuery("SECURE", """1""", emptyMap()) { raw.deleteAllItems() }

    override suspend fun softDelete(id: Long, deletedAt: Long) : Unit =
        guard.write { raw.softDelete(id, deletedAt) }

    override suspend fun restore(id: Long) : Unit =
        guard.write { raw.restore(id) }

    override suspend fun permanentlyDeleteAll() : Unit =
        guard.deleteQuery("SECURE", """isDeleted = 1""", emptyMap()) { raw.permanentlyDeleteAll() }

    override suspend fun deleteExpiredItems(cutoffDate: java.util.Date) : Unit =
        guard.deleteQuery("SECURE", """isDeleted = 1 AND deletedAt < :cutoffDate""", mapOf("cutoffDate" to cutoffDate)) { raw.deleteExpiredItems(cutoffDate) }

    override suspend fun update(item: SecureItem) : Unit =
        guard.write { raw.update(item) }

    override suspend fun updateAll(items: List<SecureItem>) : Unit =
        guard.write { raw.updateAll(items) }

    override suspend fun delete(item: SecureItem) : Unit =
        guard.delete("SECURE", listOf(item.id)) { raw.delete(item) }

    override suspend fun insert(item: SecureItem) : Long =
        guard.write { raw.insert(item) }

    override suspend fun markAllForBitwarden(vaultId: Long) : Unit =
        guard.write { raw.markAllForBitwarden(vaultId) }

    override suspend fun deleteAllByBitwardenVaultId(vaultId: Long) : Unit =
        guard.deleteQuery("SECURE", """bitwarden_vault_id = :vaultId""", mapOf("vaultId" to vaultId)) { raw.deleteAllByBitwardenVaultId(vaultId) }

    override suspend fun deleteAllSyncedBitwardenEntries(vaultId: Long) : Unit =
        guard.deleteQuery("SECURE", """bitwarden_vault_id = :vaultId
          AND bitwarden_cipher_id IS NOT NULL
          AND isDeleted = 0""", mapOf("vaultId" to vaultId)) { raw.deleteAllSyncedBitwardenEntries(vaultId) }

    override suspend fun deleteBitwardenEntriesNotIn(vaultId: Long, keepIds: List<String>) : Unit =
        guard.deleteQuery("SECURE", """bitwarden_vault_id = :vaultId
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
        guard.write { raw.markSynced(id, revisionDate) }

    override suspend fun markLocalModified(id: Long) : Unit =
        guard.write { raw.markLocalModified(id) }

}

internal class SessionCustomFieldDao(private val raw: CustomFieldDao, private val guard: DatabaseWriteGuard) : CustomFieldDao by raw {
    override suspend fun insert(field: CustomField): Long = guard.write { raw.insert(field) }
    override suspend fun insertAll(fields: List<CustomField>): List<Long> = guard.write { raw.insertAll(fields) }
    override suspend fun update(field: CustomField) = guard.write { raw.update(field) }
    override suspend fun updateAll(fields: List<CustomField>) = guard.write { raw.updateAll(fields) }
    override suspend fun delete(field: CustomField) = guard.write { raw.delete(field) }
    override suspend fun deleteById(id: Long) = guard.write { raw.deleteById(id) }
    override suspend fun deleteByEntryId(entryId: Long) = guard.write { raw.deleteByEntryId(entryId) }
    override suspend fun deleteByEntryIds(entryIds: List<Long>) = guard.write { raw.deleteByEntryIds(entryIds) }
    override suspend fun deleteAll() = guard.write { raw.deleteAll() }
    override suspend fun updateSortOrder(id: Long, sortOrder: Int) = guard.write { raw.updateSortOrder(id, sortOrder) }
    override suspend fun updateProtectedStatus(id: Long, isProtected: Boolean) = guard.write { raw.updateProtectedStatus(id, isProtected) }
    override suspend fun replaceFieldsForEntry(entryId: Long, newFields: List<CustomField>) = guard.write { raw.replaceFieldsForEntry(entryId, newFields) }
    override suspend fun updateSortOrders(updates: List<Pair<Long, Int>>) = guard.write { raw.updateSortOrders(updates) }
}

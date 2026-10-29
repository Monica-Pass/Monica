package takagi.ru.monica.repository

import takagi.ru.monica.data.explicitPasswordGroupId
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import takagi.ru.monica.data.Category
import takagi.ru.monica.data.CategoryDao
import takagi.ru.monica.data.PasskeyDao
import takagi.ru.monica.data.PasswordArchiveSyncMeta
import takagi.ru.monica.data.PasswordArchiveSyncMetaDao
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.PasswordEntryDao
import takagi.ru.monica.data.PasswordHistoryDao
import takagi.ru.monica.data.PasswordHistoryEntry
import takagi.ru.monica.data.SecureItemDao
import takagi.ru.monica.bitwarden.BitwardenMutationStateHelper
import takagi.ru.monica.repository.MdbxStoredFolderEntry
import takagi.ru.monica.data.bitwarden.BitwardenFolder
import takagi.ru.monica.data.bitwarden.BitwardenFolderDao
import takagi.ru.monica.data.bitwarden.BitwardenSyncRawEntryRecord
import takagi.ru.monica.data.bitwarden.BitwardenSyncRawEntryRecordDao
import takagi.ru.monica.data.model.isExternalSteamMaFileEntry
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Repository for password entries
 */
class PasswordRepository(
    private val passwordEntryDao: PasswordEntryDao,
    private val categoryDao: CategoryDao? = null,
    private val bitwardenFolderDao: BitwardenFolderDao? = null,
    private val secureItemDao: SecureItemDao? = null,
    private val passkeyDao: PasskeyDao? = null,
    private val passwordArchiveSyncMetaDao: PasswordArchiveSyncMetaDao? = null,
    private val passwordHistoryDao: PasswordHistoryDao? = null,
    private val bitwardenSyncRawEntryRecordDao: BitwardenSyncRawEntryRecordDao? = null,
    private val mdbxRepository: MdbxRepository? = null
) {
    internal var monicaKeePassStore: takagi.ru.monica.keepass.KeePassMonicaPasswordStore? = null
    private fun requireRoomIds(ids: List<Long>) {
        require(ids.none(takagi.ru.monica.keepass.KeePassMonicaPasswordStore::isUiId)) { "Original KeePass ownership changes require verified copies" }
    }
    companion object {
        private const val TAG = "PasswordRepository"
    }

    private fun Flow<List<PasswordEntry>>.withoutExternalSteamMaFileEntries(): Flow<List<PasswordEntry>> =
        map { entries -> entries.withoutExternalSteamMaFileEntries() }

    private fun Iterable<PasswordEntry>.withoutExternalSteamMaFileEntries(): List<PasswordEntry> =
        filterNot { it.isExternalSteamMaFileEntry() }
    
    fun getAllPasswordEntries(): Flow<List<PasswordEntry>> {
        // Keep semantic behavior the same but avoid the legacy generated callable index path.
        val room = passwordEntryDao.getActiveEntries().withoutExternalSteamMaFileEntries()
        val native = monicaKeePassStore ?: return room
        return kotlinx.coroutines.flow.combine(room, native.activeRows) { entries, nativeRows ->
            entries.filterNot { it.keepassDatabaseId in nativeRows.keys } + nativeRows.values.flatten().filterNot { it.isArchived || it.isDeleted }
        }
    }

    fun getActiveAuthenticatorEntries(): Flow<List<PasswordEntry>> =
        passwordEntryDao.getActiveAuthenticatorEntries().withoutExternalSteamMaFileEntries()

    fun getActivePasswordTitles(): Flow<List<takagi.ru.monica.data.PasswordTitle>> =
        passwordEntryDao.getActivePasswordTitles()

    suspend fun getAllLocalPasswordEntries(): List<PasswordEntry> {
        return passwordEntryDao.getLocalBackupEntries().withoutExternalSteamMaFileEntries()
    }
    
    fun getPasswordEntriesByCategory(categoryId: Long): Flow<List<PasswordEntry>> {
        return passwordEntryDao.getPasswordEntriesByCategory(categoryId)
            .withoutExternalSteamMaFileEntries()
    }

    fun getUncategorizedPasswordEntries(): Flow<List<PasswordEntry>> {
        return passwordEntryDao.getUncategorizedPasswordEntries()
            .withoutExternalSteamMaFileEntries()
    }

    fun getPasswordEntriesByKeePassDatabase(databaseId: Long): Flow<List<PasswordEntry>> {
        monicaKeePassStore?.let { return it.rows(databaseId).map { rows -> rows.filterNot { row -> row.isArchived || row.isDeleted } } }
        return passwordEntryDao.getPasswordEntriesByKeePassDatabase(databaseId)
            .withoutExternalSteamMaFileEntries()
    }

    fun getPasswordEntriesByKeePassGroup(
        databaseId: Long,
        groupPath: String,
        groupUuid: String? = null
    ): Flow<List<PasswordEntry>> {
        monicaKeePassStore?.let { native ->
            return native.rows(databaseId).map { rows -> rows.filter { row ->
                !row.isArchived && !row.isDeleted && native.matchesScope(row,
                    takagi.ru.monica.keepass.KeePassPasswordNativeScope(databaseId, groupPath, groupUuid))
            } }
        }
        return passwordEntryDao.getPasswordEntriesByKeePassGroup(databaseId, groupPath, groupUuid)
            .withoutExternalSteamMaFileEntries()
    }
    
    fun getPasswordEntriesByBitwardenVault(vaultId: Long): Flow<List<PasswordEntry>> {
        return passwordEntryDao.getByBitwardenVaultIdFlow(vaultId)
            .withoutExternalSteamMaFileEntries()
    }

    fun getPasswordEntriesByBitwardenFolder(vaultId: Long, folderId: String): Flow<List<PasswordEntry>> {
        return passwordEntryDao.getByBitwardenFolderIdFlow(vaultId, folderId)
            .withoutExternalSteamMaFileEntries()
    }
    
    fun getBitwardenFoldersByVaultId(vaultId: Long): Flow<List<BitwardenFolder>> {
        return bitwardenFolderDao?.getFoldersByVaultFlow(vaultId) ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }

    fun getFavoritePasswordEntries(): Flow<List<PasswordEntry>> {
        return passwordEntryDao.getFavoritePasswordEntries()
            .withoutExternalSteamMaFileEntries()
    }

    fun getArchivedEntries(): Flow<List<PasswordEntry>> {
        return passwordEntryDao.getArchivedEntries()
            .withoutExternalSteamMaFileEntries()
    }

    suspend fun getDeletedEntries(): List<PasswordEntry> =
        passwordEntryDao.getDeletedEntriesSync()

    // Category operations
    fun getAllCategories(): Flow<List<Category>> {
        return categoryDao?.getAllCategories() ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }

    suspend fun insertCategory(category: Category): Long {
        return categoryDao?.insert(category) ?: -1
    }

    suspend fun createMdbxFolder(
        databaseId: Long,
        name: String,
        parentFolderId: String? = "root"
    ): MdbxStoredFolderEntry? {
        return mdbxRepository?.createFolder(databaseId, name, parentFolderId)
    }

    suspend fun listMdbxFolders(databaseId: Long): List<MdbxStoredFolderEntry> {
        return mdbxRepository?.listFolders(databaseId).orEmpty()
    }

    suspend fun deleteMdbxFolder(databaseId: Long, folderId: String) {
        val repository = mdbxRepository
            ?: throw IllegalStateException("MDBX repository unavailable")
        repository.deleteFolder(databaseId, folderId)
    }

    suspend fun updateCategory(category: Category) {
        categoryDao?.update(category)
    }

    suspend fun deleteCategory(category: Category) {
        // Clear category references across all local data types first.
        passwordEntryDao.removeCategoryFromPasswords(category.id)
        secureItemDao?.removeCategoryFromItems(category.id)
        passkeyDao?.removeCategoryFromPasskeys(category.id)
        categoryDao?.delete(category)
    }
    
    suspend fun updateCategorySortOrder(id: Long, sortOrder: Int) {
        categoryDao?.updateSortOrder(id, sortOrder)
    }

    suspend fun getCategoryById(id: Long): Category? {
        return categoryDao?.getCategoryById(id)
    }

    suspend fun updateCategoryForPasswords(ids: List<Long>, categoryId: Long?) {
        requireRoomIds(ids)
        passwordEntryDao.updateCategoryForPasswords(ids, categoryId)
    }

    suspend fun updateBoundNoteId(id: Long, noteId: Long?) {
        if (takagi.ru.monica.keepass.KeePassMonicaPasswordStore.isUiId(id)) {
            updatePasswordEntry(requireNotNull(getPasswordEntryById(id)).copy(boundNoteId = noteId)); return
        }
        passwordEntryDao.updateBoundNoteId(id, noteId)
    }

    suspend fun clearBoundNoteReferences(noteId: Long) {
        passwordEntryDao.clearBoundNoteReferences(noteId)
    }

    suspend fun bindPasswordsToBitwardenFolder(ids: List<Long>, vaultId: Long, folderId: String) {
        passwordEntryDao.bindPasswordsToBitwardenFolder(ids, vaultId, folderId)
    }

    suspend fun clearPendingBitwardenBinding(ids: List<Long>) {
        passwordEntryDao.clearPendingBitwardenBinding(ids)
    }

    suspend fun bindCategoryToBitwarden(categoryId: Long, vaultId: Long, folderId: String) {
        passwordEntryDao.bindCategoryToBitwarden(categoryId, vaultId, folderId)
    }
    
    suspend fun updateKeePassDatabaseForPasswords(ids: List<Long>, databaseId: Long?) {
        requireRoomIds(ids)
        passwordEntryDao.updateKeePassDatabaseForPasswords(ids, databaseId)
    }

    suspend fun updateMdbxDatabaseForPasswords(ids: List<Long>, databaseId: Long?, folderId: String? = null) {
        requireRoomIds(ids)
        databaseId?.let { mdbxRepository?.requireRoomMirrorAllowed(it) }
        passwordEntryDao.getPasswordsByIds(ids).forEach(MdbxUnknownEntry::requireEditable)
        if (ids.isEmpty()) return
        val existingEntries = passwordEntryDao.getPasswordsByIds(ids)
        if (databaseId != null) {
            val entriesForMdbx = existingEntries.map { entry ->
                entry.copy(
                    mdbxDatabaseId = databaseId,
                    mdbxFolderId = folderId,
                    passwordGroupId = entry.explicitPasswordGroupId(),
                    replicaGroupId = entry.mdbxPasswordObjectId(),
                    keepassDatabaseId = null,
                    keepassGroupPath = null,
                    keepassEntryUuid = null,
                    keepassGroupUuid = null,
                    bitwardenVaultId = null,
                    bitwardenFolderId = null,
                    bitwardenCipherId = null,
                    bitwardenRevisionDate = null,
                    bitwardenLocalModified = false,
                    updatedAt = Date()
                )
            }
            val previousMdbxEntries = existingEntries.filter { it.mdbxDatabaseId != null }
            commitMirrorThenRoom(
                mirrorCommit = {
                    mdbxRepository?.upsertPasswords(entriesForMdbx)
                    mdbxRepository?.deletePasswords(
                        previousMdbxEntries.filter { it.mdbxDatabaseId != databaseId }
                    )
                },
                roomCommit = { passwordEntryDao.updatePasswordEntries(entriesForMdbx) },
                rollbackMirror = {
                    mdbxRepository?.deletePasswords(entriesForMdbx)
                    mdbxRepository?.upsertPasswords(previousMdbxEntries)
                }
            )
            return
        }
        val previousMdbxEntries = existingEntries.filter { it.mdbxDatabaseId != null }
        commitMirrorThenRoom(
            mirrorCommit = { mdbxRepository?.deletePasswords(previousMdbxEntries) },
            roomCommit = { passwordEntryDao.updateMdbxDatabaseForPasswords(ids, null, folderId) },
            rollbackMirror = { mdbxRepository?.upsertPasswords(previousMdbxEntries) }
        )
    }

    suspend fun updateKeePassGroupForPasswords(ids: List<Long>, databaseId: Long, groupPath: String) {
        requireRoomIds(ids)
        passwordEntryDao.updateKeePassGroupForPasswords(ids, databaseId, groupPath)
    }

    suspend fun clearBitwardenBindingForPasswords(ids: List<Long>) {
        passwordEntryDao.clearBitwardenBindingForPasswords(ids)
    }
    
    fun searchPasswordEntries(query: String): Flow<List<PasswordEntry>> {
        return passwordEntryDao.searchPasswordEntries(query)
            .withoutExternalSteamMaFileEntries()
    }
    
    suspend fun getPasswordEntryById(id: Long): PasswordEntry? {
        if (takagi.ru.monica.keepass.KeePassMonicaPasswordStore.isUiId(id)) return monicaKeePassStore?.get(id)
        return passwordEntryDao.getPasswordEntryById(id)
    }

    suspend fun getPasswordEntryByKeePassUuid(databaseId: Long, entryUuid: String): PasswordEntry? {
        return passwordEntryDao.findByKeePassEntryUuid(databaseId, entryUuid)
    }
    
    suspend fun getPasswordsByIds(ids: List<Long>): List<PasswordEntry> {
        val nativeIds = ids.filter { takagi.ru.monica.keepass.KeePassMonicaPasswordStore.isUiId(it) }
        return passwordEntryDao.getPasswordsByIds(ids - nativeIds.toSet()) + nativeIds.mapNotNull { monicaKeePassStore?.get(it) }
    }

    suspend fun getActivePasswordsByIds(ids: List<Long>): List<PasswordEntry> {
        return getPasswordsByIds(ids).filterNot { it.isDeleted || it.isArchived }
            .withoutExternalSteamMaFileEntries()
    }
    
    suspend fun insertPasswordEntry(entry: PasswordEntry): Long {
        require(!takagi.ru.monica.keepass.KeePassMonicaPasswordStore.isUiId(entry.id)) { "KeePass UI identity cannot be persisted in Room" }
        entry.mdbxDatabaseId?.let { mdbxRepository?.requireRoomMirrorAllowed(it) }
        MdbxUnknownEntry.requireEditable(entry)
        val normalizedEntry = BitwardenMutationStateHelper.normalizePasswordInsert(entry)
        return commitRoomThenMirror(
            roomCommit = {
                val id = passwordEntryDao.insertPasswordEntry(normalizedEntry)
            val persistedEntry = normalizedEntry.copy(
                id = id,
                replicaGroupId = if (normalizedEntry.mdbxDatabaseId != null) {
                    normalizedEntry.copy(id = id).mdbxPasswordObjectId()
                } else {
                    normalizedEntry.replicaGroupId
                }
            )
            if (persistedEntry.replicaGroupId != normalizedEntry.replicaGroupId) {
                passwordEntryDao.updatePasswordEntry(persistedEntry)
            }
                id to persistedEntry
            },
            mirrorCommit = { (_, persistedEntry) ->
                mdbxRepository?.upsertPassword(persistedEntry)
            },
            rollbackRoom = { (id, _) -> passwordEntryDao.deletePasswordEntryById(id) },
            rollbackMirror = { (_, persistedEntry) ->
                mdbxRepository?.deletePassword(persistedEntry)
            }
        ).first
    }

    suspend fun insertPasswordEntries(entries: List<PasswordEntry>): List<Long> {
        requireRoomIds(entries.map { it.id })
        entries.mapNotNull { it.mdbxDatabaseId }.distinct().forEach { mdbxRepository?.requireRoomMirrorAllowed(it) }
        entries.forEach(MdbxUnknownEntry::requireEditable)
        if (entries.isEmpty()) return emptyList()
        val normalizedEntries = entries.map(BitwardenMutationStateHelper::normalizePasswordInsert)
        return commitRoomThenMirror(
            roomCommit = {
                val ids = passwordEntryDao.insertPasswordEntries(normalizedEntries)
                val persistedEntries = normalizedEntries.mapIndexed { index, entry ->
                    val id = ids[index]
                    entry.copy(
                        id = id,
                        replicaGroupId = if (entry.mdbxDatabaseId != null) {
                            entry.copy(id = id).mdbxPasswordObjectId()
                        } else {
                            entry.replicaGroupId
                        }
                    )
                }
                val entriesNeedingReplicaUpdate = persistedEntries.filterIndexed { index, persistedEntry ->
                    persistedEntry.replicaGroupId != normalizedEntries[index].replicaGroupId
                }
                if (entriesNeedingReplicaUpdate.isNotEmpty()) {
                    passwordEntryDao.updatePasswordEntries(entriesNeedingReplicaUpdate)
                }
                ids to persistedEntries
            },
            mirrorCommit = { (_, persistedEntries) ->
                mdbxRepository?.upsertPasswords(persistedEntries.filter { it.mdbxDatabaseId != null })
            },
            rollbackRoom = { (ids, _) ->
                ids.forEach { id -> passwordEntryDao.deletePasswordEntryById(id) }
            },
            rollbackMirror = { (_, persistedEntries) ->
                mdbxRepository?.deletePasswords(persistedEntries.filter { it.mdbxDatabaseId != null })
            }
        ).first
    }
    
    suspend fun updatePasswordEntry(entry: PasswordEntry) {
        if (takagi.ru.monica.keepass.KeePassMonicaPasswordStore.isUiId(entry.id)) {
            requireNotNull(monicaKeePassStore).save(requireNotNull(entry.keepassDatabaseId), listOf(entry), emptyMap())
            return
        }
        entry.mdbxDatabaseId?.let { mdbxRepository?.requireRoomMirrorAllowed(it) }
        MdbxUnknownEntry.requireEditable(entry)
        passwordEntryDao.getPasswordEntryById(entry.id)?.let(MdbxUnknownEntry::requireEditable)
        val existingEntry = if (entry.id != 0L) passwordEntryDao.getPasswordEntryById(entry.id) else null
        val normalizedEntry = BitwardenMutationStateHelper.normalizePasswordUpdate(existingEntry, entry).let { candidate ->
            if (candidate.mdbxDatabaseId != null) {
                candidate.copy(replicaGroupId = candidate.mdbxPasswordObjectId(),
                        passwordGroupId = candidate.explicitPasswordGroupId() ?: existingEntry?.explicitPasswordGroupId())
            } else {
                candidate
            }
        }
        commitMirrorThenRoom(
            mirrorCommit = {
                if (normalizedEntry.mdbxDatabaseId != null) {
                    mdbxRepository?.upsertPassword(normalizedEntry)
                }
                if (
                    existingEntry?.mdbxDatabaseId != null &&
                    existingEntry.mdbxDatabaseId != normalizedEntry.mdbxDatabaseId
                ) {
                    mdbxRepository?.deletePassword(existingEntry)
                }
            },
            roomCommit = { passwordEntryDao.updatePasswordEntry(normalizedEntry) },
            rollbackMirror = {
                if (normalizedEntry.mdbxDatabaseId != null) {
                    mdbxRepository?.deletePassword(normalizedEntry)
                }
                if (existingEntry?.mdbxDatabaseId != null) {
                    mdbxRepository?.upsertPassword(existingEntry)
                }
            }
        )
    }

    suspend fun updatePasswordEntries(entries: List<PasswordEntry>) {
        val nativeEntries = entries.filter { takagi.ru.monica.keepass.KeePassMonicaPasswordStore.isUiId(it.id) }
        if (nativeEntries.isNotEmpty()) {
            check(nativeEntries.all { monicaKeePassStore?.databaseId(it.id) == it.keepassDatabaseId && it.mdbxDatabaseId == null && it.bitwardenVaultId == null }) {
                "Original KeePass ownership changes require verified copies"
            }
            nativeEntries.groupBy { requireNotNull(it.keepassDatabaseId) }.forEach { (databaseId, rows) ->
                requireNotNull(monicaKeePassStore).save(databaseId, rows, emptyMap())
            }
            updatePasswordEntries(entries - nativeEntries.toSet())
            return
        }
        entries.mapNotNull { it.mdbxDatabaseId }.distinct().forEach { mdbxRepository?.requireRoomMirrorAllowed(it) }
        entries.forEach(MdbxUnknownEntry::requireEditable)
        passwordEntryDao.getPasswordsByIds(entries.map { it.id }).forEach(MdbxUnknownEntry::requireEditable)
        if (entries.isEmpty()) return
        val existingEntriesById = passwordEntryDao
            .getPasswordsByIds(entries.map { it.id })
            .associateBy { it.id }
        val normalizedEntries = entries.map { entry ->
            val existingEntry = existingEntriesById[entry.id]
            BitwardenMutationStateHelper.normalizePasswordUpdate(existingEntry, entry).let { candidate ->
                if (candidate.mdbxDatabaseId != null) {
                    candidate.copy(replicaGroupId = candidate.mdbxPasswordObjectId(),
                        passwordGroupId = candidate.explicitPasswordGroupId() ?: existingEntry?.explicitPasswordGroupId())
                } else {
                    candidate
                }
            }
        }
        val previousMdbxEntries = normalizedEntries.mapNotNull { normalizedEntry ->
                val existingEntry = existingEntriesById[normalizedEntry.id]
                existingEntry?.takeIf {
                    it.mdbxDatabaseId != null &&
                        it.mdbxDatabaseId != normalizedEntry.mdbxDatabaseId
                }
            }
        val nextMdbxEntries = normalizedEntries.filter { it.mdbxDatabaseId != null }
        commitMirrorThenRoom(
            mirrorCommit = {
                mdbxRepository?.upsertPasswords(nextMdbxEntries)
                mdbxRepository?.deletePasswords(previousMdbxEntries)
            },
            roomCommit = { passwordEntryDao.updatePasswordEntries(normalizedEntries) },
            rollbackMirror = {
                mdbxRepository?.deletePasswords(nextMdbxEntries)
                mdbxRepository?.upsertPasswords(
                    existingEntriesById.values.filter { it.mdbxDatabaseId != null }
                )
            }
        )
    }

    internal suspend fun applyKeePassProjection(block: suspend () -> Unit) =
        passwordEntryDao.applyKeePassProjection(block)

    suspend fun updatePasswordUpdatedAt(id: Long, updatedAt: java.util.Date) {
        passwordEntryDao.updateUpdatedAt(id, updatedAt)
    }
    
    suspend fun deletePasswordEntry(entry: PasswordEntry) {
        MdbxUnknownEntry.requireEditable(entry)
        passwordEntryDao.getPasswordEntryById(entry.id)?.let(MdbxUnknownEntry::requireEditable)
        commitMirrorThenRoom(
            mirrorCommit = { mdbxRepository?.deletePassword(entry) },
            roomCommit = { passwordEntryDao.deletePasswordEntry(entry) },
            rollbackMirror = { mdbxRepository?.upsertPassword(entry) }
        )
    }

    suspend fun deletePasswordEntries(entries: List<PasswordEntry>) {
        val native = entries.filter { takagi.ru.monica.keepass.KeePassMonicaPasswordStore.isUiId(it.id) }
        if (native.isNotEmpty()) {
            requireNotNull(monicaKeePassStore).delete(native, false)
            deletePasswordEntries(entries - native.toSet())
            return
        }
        entries.forEach(MdbxUnknownEntry::requireEditable)
        passwordEntryDao.getPasswordsByIds(entries.map { it.id }).forEach(MdbxUnknownEntry::requireEditable)
        if (entries.isEmpty()) return
        val mdbxEntries = entries.filter { it.mdbxDatabaseId != null }
        commitMirrorThenRoom(
            mirrorCommit = { mdbxRepository?.deletePasswords(mdbxEntries) },
            roomCommit = { passwordEntryDao.deletePasswordEntries(entries) },
            rollbackMirror = { mdbxRepository?.upsertPasswords(mdbxEntries) }
        )
    }
    
    suspend fun deletePasswordEntryById(id: Long) {
        passwordEntryDao.getPasswordEntryById(id)?.let(MdbxUnknownEntry::requireEditable)
        val entry = passwordEntryDao.getPasswordEntryById(id)
        commitMirrorThenRoom(
            mirrorCommit = { entry?.let { mdbxRepository?.deletePassword(it) } },
            roomCommit = { passwordEntryDao.deletePasswordEntryById(id) },
            rollbackMirror = { entry?.let { mdbxRepository?.upsertPassword(it) } }
        )
    }

    private fun PasswordEntry.mdbxPasswordObjectId(): String =
        replicaGroupId
            ?.takeIf { it.isMdbxPasswordObjectId() }
            ?: id.takeIf { it > 0 }?.let { "password:$it" }
            ?: "password:${UUID.randomUUID()}"

    private fun String.isMdbxPasswordObjectId(): Boolean =
        startsWith("password:") && length > "password:".length

    suspend fun archivePasswordById(id: Long) {
        passwordEntryDao.archiveById(id)
    }

    suspend fun archivePasswordsByIds(ids: List<Long>) {
        if (ids.isEmpty()) return
        passwordEntryDao.archiveByIds(ids)
    }

    suspend fun unarchivePasswordById(id: Long) {
        passwordEntryDao.unarchiveById(id)
    }

    suspend fun unarchivePasswordsByIds(ids: List<Long>) {
        if (ids.isEmpty()) return
        passwordEntryDao.unarchiveByIds(ids)
    }

    suspend fun getArchiveSyncMeta(entryId: Long): PasswordArchiveSyncMeta? {
        return passwordArchiveSyncMetaDao?.getByEntryId(entryId)
    }

    suspend fun upsertArchiveSyncMeta(meta: PasswordArchiveSyncMeta) {
        passwordArchiveSyncMetaDao?.upsert(meta)
    }

    suspend fun deleteArchiveSyncMeta(entryId: Long) {
        passwordArchiveSyncMetaDao?.deleteByEntryId(entryId)
    }

    suspend fun deleteArchiveSyncMeta(entryIds: List<Long>) {
        if (entryIds.isEmpty()) return
        passwordArchiveSyncMetaDao?.deleteByEntryIds(entryIds)
    }

    fun getPasswordHistoryByEntryId(entryId: Long): Flow<List<PasswordHistoryEntry>> {
        return passwordHistoryDao?.getHistoryByEntryId(entryId) ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }

    fun getBitwardenSyncRawRecords(
        vaultId: Long,
        cipherId: String
    ): Flow<List<BitwardenSyncRawEntryRecord>> {
        if (cipherId.isBlank()) return kotlinx.coroutines.flow.flowOf(emptyList())
        return bitwardenSyncRawEntryRecordDao?.getByCipherFlow(vaultId, cipherId)
            ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }

    suspend fun getPasswordHistoryByEntryIdSync(entryId: Long): List<PasswordHistoryEntry> {
        return passwordHistoryDao?.getHistoryByEntryIdSync(entryId) ?: emptyList()
    }

    suspend fun insertPasswordHistory(entry: PasswordHistoryEntry): Long {
        return passwordHistoryDao?.insert(entry) ?: -1L
    }

    suspend fun updatePasswordHistoryPassword(historyId: Long, password: String) {
        passwordHistoryDao?.updatePasswordById(historyId, password)
    }

    suspend fun trimPasswordHistory(entryId: Long, limit: Int) {
        passwordHistoryDao?.trimToLimit(entryId, limit)
    }

    suspend fun deletePasswordHistoryById(id: Long) {
        passwordHistoryDao?.deleteById(id)
    }

    suspend fun clearPasswordHistory(entryId: Long) {
        passwordHistoryDao?.deleteByEntryId(entryId)
    }
    
    suspend fun toggleFavorite(id: Long, isFavorite: Boolean) {
        val existingEntry = getPasswordEntryById(id) ?: return
        updatePasswordEntry(
            existingEntry.copy(
                isFavorite = isFavorite,
                updatedAt = Date()
            )
        )
    }
    
    suspend fun updateGroupCoverStatus(id: Long, isGroupCover: Boolean) {
        if (takagi.ru.monica.keepass.KeePassMonicaPasswordStore.isUiId(id)) {
            updatePasswordEntry(requireNotNull(getPasswordEntryById(id)).copy(isGroupCover = isGroupCover))
            return
        }
        passwordEntryDao.updateGroupCoverStatus(id, isGroupCover)
    }
    
    suspend fun setGroupCover(id: Long, website: String) {
        if (takagi.ru.monica.keepass.KeePassMonicaPasswordStore.isUiId(id)) {
            updatePasswordEntry(requireNotNull(getPasswordEntryById(id)).copy(isGroupCover = true, website = website))
            return
        }
        passwordEntryDao.setGroupCover(id, website)
    }
    
    suspend fun updateSortOrders(items: List<Pair<Long, Int>>) {
        if (items.isEmpty()) return
        val nativeItems = items.filter { takagi.ru.monica.keepass.KeePassMonicaPasswordStore.isUiId(it.first) }
        if (nativeItems.isNotEmpty()) {
            updatePasswordEntries(nativeItems.map { (id, order) -> requireNotNull(getPasswordEntryById(id)).copy(sortOrder = order) })
            updateSortOrders(items - nativeItems.toSet())
            return
        }
        val orderById = items.toMap()
        val previousEntries = passwordEntryDao.getPasswordsByIds(orderById.keys.toList())
        val reorderedEntries = previousEntries.mapNotNull { entry ->
            orderById[entry.id]?.let { sortOrder -> entry.copy(sortOrder = sortOrder) }
        }
        commitMirrorThenRoom(
            mirrorCommit = {
                mirrorSortOrderEntries(reorderedEntries)
            },
            roomCommit = { passwordEntryDao.updateSortOrders(items) },
            rollbackMirror = {
                mirrorSortOrderEntries(previousEntries)
            }
        )
    }

    private suspend fun mirrorSortOrderEntries(entries: List<PasswordEntry>) {
        val repository = mdbxRepository ?: return
        entries
            .filter { it.mdbxDatabaseId != null && !MdbxUnknownEntry.isProjection(it) }
            .groupBy { it.mdbxDatabaseId }
            .values
            .forEach { group ->
                try {
                    repository.upsertPasswords(group)
                } catch (error: MdbxVaultNotFoundException) {
                    Log.w(TAG, "Skipping sort mirror for removed MDBX database ${error.databaseId}")
                }
            }
    }
    
    suspend fun getPasswordEntriesCount(): Int {
        return passwordEntryDao.getPasswordEntriesCount()
    }

    /**
     * 获取本地密码条目数量（排除 KeePass 和 Bitwarden 的数据）
     */
    suspend fun getLocalEntriesCount(): Int {
        return passwordEntryDao.getLocalEntriesCount()
    }

    /**
     * 获取本地已删除密码条目数量（排除 KeePass 和 Bitwarden 的数据）
     */
    suspend fun getLocalDeletedEntriesCount(): Int {
        return passwordEntryDao.getLocalDeletedEntriesCount()
    }
    
    suspend fun deleteAllPasswordEntries() {
        passwordEntryDao.deleteAllPasswordEntries()
    }
    
    /**
     * 检查是否存在重复的密码条目
     */
    suspend fun isDuplicateEntry(title: String, username: String, website: String): Boolean {
        return passwordEntryDao.findDuplicateEntry(title, username, website) != null
    }

    /**
     * 获取重复的密码条目
     */
    suspend fun getDuplicateEntry(title: String, username: String, website: String): PasswordEntry? {
        return passwordEntryDao.findDuplicateEntry(title, username, website)
    }

    /**
     * 仅在 Monica 本地库范围内查重（排除 KeePass / Bitwarden）
     */
    suspend fun getLocalDuplicateEntry(title: String, username: String, website: String): PasswordEntry? {
        return passwordEntryDao.findLocalDuplicateByKey(
            title.lowercase(Locale.ROOT),
            username.lowercase(Locale.ROOT),
            website.lowercase(Locale.ROOT)
        )
    }

    suspend fun getDuplicateCandidates(title: String, username: String, website: String): List<PasswordEntry> {
        return passwordEntryDao.findActiveDuplicateCandidatesByKey(
            title.lowercase(Locale.ROOT),
            username.lowercase(Locale.ROOT),
            website.lowercase(Locale.ROOT)
        )
    }

    suspend fun getLocalDuplicateCandidates(title: String, username: String, website: String): List<PasswordEntry> {
        return passwordEntryDao.findLocalDuplicateCandidatesByKey(
            title.lowercase(Locale.ROOT),
            username.lowercase(Locale.ROOT),
            website.lowercase(Locale.ROOT)
        )
    }

    suspend fun getDuplicateEntryInKeePass(
        databaseId: Long,
        title: String,
        username: String,
        website: String,
        groupPath: String?
    ): PasswordEntry? {
        return passwordEntryDao.findDuplicateEntryInKeePass(databaseId, title, username, website, groupPath)
    }

    suspend fun getPasswordEntriesByKeePassDatabaseSync(databaseId: Long): List<PasswordEntry> {
        return passwordEntryDao.getPasswordEntriesByKeePassDatabaseSync(databaseId)
    }
    
    /**
     * 按包名和用户名查询密码(自动填充保存功能)
     */
    suspend fun findByPackageAndUsername(packageName: String, username: String): PasswordEntry? {
        return passwordEntryDao.findByPackageAndUsername(packageName, username)
            ?.takeUnless { it.isExternalSteamMaFileEntry() }
    }
    
    /**
     * 按网站和用户名查询密码(自动填充保存功能)
     */
    suspend fun findByDomainAndUsername(domain: String, username: String): PasswordEntry? {
        return passwordEntryDao.findByDomainAndUsername(domain, username)
            ?.takeUnless { it.isExternalSteamMaFileEntry() }
    }
    
    /**
     * 按包名查询所有密码
     */
    suspend fun findByPackageName(packageName: String): List<PasswordEntry> {
        return passwordEntryDao.findByPackageName(packageName)
            .withoutExternalSteamMaFileEntries()
    }
    
    /**
     * 按网站域名查询所有密码
     */
    suspend fun findByDomain(domain: String): List<PasswordEntry> {
        return passwordEntryDao.findByDomain(domain)
            .withoutExternalSteamMaFileEntries()
    }
    
    /**
     * 检查是否存在完全相同的密码
     */
    suspend fun findExactMatch(packageName: String, username: String, encryptedPassword: String): PasswordEntry? {
        return passwordEntryDao.findExactMatch(packageName, username, encryptedPassword)
            ?.takeUnless { it.isExternalSteamMaFileEntry() }
    }

    suspend fun updateAppAssociationByWebsite(website: String, packageName: String, appName: String) {
        passwordEntryDao.updateAppAssociationByWebsite(website, packageName, appName)
        mdbxRepository?.upsertPasswords(passwordEntryDao.getActiveMdbxEntriesByWebsite(website))
    }

    suspend fun updateAppAssociationByTitle(title: String, packageName: String, appName: String) {
        passwordEntryDao.updateAppAssociationByTitle(title, packageName, appName)
        mdbxRepository?.upsertPasswords(passwordEntryDao.getActiveMdbxEntriesByTitle(title))
    }

    /**
     * 更新绑定的验证器密钥
     */
    suspend fun updateAuthenticatorKey(id: Long, authenticatorKey: String) {
        val existing = passwordEntryDao.getPasswordEntryById(id) ?: return
        updatePasswordEntry(
            existing.copy(
                authenticatorKey = authenticatorKey,
                updatedAt = Date()
            )
        )
    }

    /**
     * 更新绑定的通行密钥元数据
     */
    suspend fun updatePasskeyBindings(id: Long, passkeyBindings: String) {
        val existing = passwordEntryDao.getPasswordEntryById(id) ?: return
        updatePasswordEntry(
            existing.copy(
                passkeyBindings = passkeyBindings,
                updatedAt = Date()
            )
        )
    }
}

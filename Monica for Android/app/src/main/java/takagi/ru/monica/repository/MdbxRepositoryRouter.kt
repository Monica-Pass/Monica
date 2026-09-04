package takagi.ru.monica.repository

import takagi.ru.monica.attachments.model.Attachment
import takagi.ru.monica.data.LocalMdbxDatabaseDao
import takagi.ru.monica.data.MdbxEngineType
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.SecureItem

internal class MdbxVaultNotFoundException(val databaseId: Long) :
    IllegalStateException("MDBX vault not found: $databaseId")

class MdbxRepositoryRouter(
    private val databaseDao: LocalMdbxDatabaseDao,
    private val legacyRepository: MdbxRepository,
    private val rustRepository: MdbxRepository
) : MdbxRepository {
    override suspend fun requiresStrictMutationConsistency(databaseId: Long): Boolean =
        databaseDao.getDatabaseById(databaseId)?.engineTypeEnum == MdbxEngineType.RUST_MDBX2

    override suspend fun readStoredEntries(databaseId: Long): List<MdbxStoredVaultEntry> =
        repositoryFor(databaseId).readStoredEntries(databaseId)

    override suspend fun readStoredAttachments(databaseId: Long): List<MdbxStoredAttachment> =
        repositoryFor(databaseId).readStoredAttachments(databaseId)

    override suspend fun createFolder(
        databaseId: Long,
        name: String,
        parentFolderId: String?
    ): MdbxStoredFolderEntry = repositoryFor(databaseId).createFolder(databaseId, name, parentFolderId)

    override suspend fun listFolders(databaseId: Long): List<MdbxStoredFolderEntry> =
        repositoryFor(databaseId).listFolders(databaseId)

    override suspend fun renameFolder(
        databaseId: Long,
        folderId: String,
        name: String
    ): MdbxStoredFolderEntry = repositoryFor(databaseId).renameFolder(databaseId, folderId, name)

    override suspend fun moveFolder(
        databaseId: Long,
        folderId: String,
        parentFolderId: String?
    ): MdbxStoredFolderEntry =
        repositoryFor(databaseId).moveFolder(databaseId, folderId, parentFolderId)

    override suspend fun deleteFolder(databaseId: Long, folderId: String) =
        repositoryFor(databaseId).deleteFolder(databaseId, folderId)

    override suspend fun restoreFolder(
        databaseId: Long,
        folderId: String,
        parentFolderId: String?
    ): MdbxStoredFolderEntry =
        repositoryFor(databaseId).restoreFolder(databaseId, folderId, parentFolderId)

    override suspend fun upsertPassword(entry: PasswordEntry) =
        repositoryForEntry(entry.mdbxDatabaseId).upsertPassword(entry)

    override suspend fun deletePassword(entry: PasswordEntry) =
        repositoryForEntry(entry.mdbxDatabaseId).deletePassword(entry)

    override suspend fun upsertPasswords(entries: List<PasswordEntry>) {
        dispatchGrouped(entries, PasswordEntry::mdbxDatabaseId) { repository, values ->
            repository.upsertPasswords(values)
        }
    }

    override suspend fun deletePasswords(entries: List<PasswordEntry>) {
        dispatchGrouped(entries, PasswordEntry::mdbxDatabaseId) { repository, values ->
            repository.deletePasswords(values)
        }
    }

    override suspend fun upsertSecureItem(item: SecureItem) =
        repositoryForEntry(item.mdbxDatabaseId).upsertSecureItem(item)

    override suspend fun deleteSecureItem(item: SecureItem) =
        repositoryForEntry(item.mdbxDatabaseId).deleteSecureItem(item)

    override suspend fun upsertSecureItems(items: List<SecureItem>) {
        dispatchGrouped(items, SecureItem::mdbxDatabaseId) { repository, values ->
            repository.upsertSecureItems(values)
        }
    }

    override suspend fun deleteSecureItems(items: List<SecureItem>) {
        dispatchGrouped(items, SecureItem::mdbxDatabaseId) { repository, values ->
            repository.deleteSecureItems(values)
        }
    }

    override suspend fun upsertPasskey(passkey: PasskeyEntry) =
        repositoryForEntry(passkey.mdbxDatabaseId).upsertPasskey(passkey)

    override suspend fun deletePasskey(passkey: PasskeyEntry) =
        repositoryForEntry(passkey.mdbxDatabaseId).deletePasskey(passkey)

    override suspend fun upsertPasskeys(passkeys: List<PasskeyEntry>) {
        dispatchGrouped(passkeys, PasskeyEntry::mdbxDatabaseId) { repository, values ->
            repository.upsertPasskeys(values)
        }
    }

    override suspend fun deletePasskeys(passkeys: List<PasskeyEntry>) {
        dispatchGrouped(passkeys, PasskeyEntry::mdbxDatabaseId) { repository, values ->
            repository.deletePasskeys(values)
        }
    }

    override suspend fun listSteamMaFileEntries(databaseId: Long): List<MdbxStoredVaultEntry> =
        repositoryFor(databaseId).listSteamMaFileEntries(databaseId)

    override suspend fun upsertSteamMaFileEntry(
        databaseId: Long,
        entryId: String?,
        title: String,
        maFileJson: String
    ): String = repositoryFor(databaseId).upsertSteamMaFileEntry(
        databaseId,
        entryId,
        title,
        maFileJson
    )

    override suspend fun deleteSteamMaFileEntry(databaseId: Long, entryId: String) =
        repositoryFor(databaseId).deleteSteamMaFileEntry(databaseId, entryId)

    override suspend fun getVaultDiagnostics(databaseId: Long): MdbxVaultDiagnostics =
        repositoryFor(databaseId).getVaultDiagnostics(databaseId)

    override suspend fun getPendingSyncCount(databaseId: Long): Int =
        repositoryFor(databaseId).getPendingSyncCount(databaseId)

    override suspend fun planHealthRepair(databaseId: Long): MdbxHealthRepairPlan =
        repositoryFor(databaseId).planHealthRepair(databaseId)

    override suspend fun applyHealthRepair(
        databaseId: Long,
        planToken: String,
        operationId: String,
        decisions: List<MdbxHealthRepairDecision>
    ): MdbxHealthRepairApplyResult = repositoryFor(databaseId).applyHealthRepair(
        databaseId = databaseId,
        planToken = planToken,
        operationId = operationId,
        decisions = decisions
    )

    override suspend fun setProjectTags(databaseId: Long, projectId: String, tags: List<String>) =
        repositoryFor(databaseId).setProjectTags(databaseId, projectId, tags)

    override suspend fun listProjectTags(databaseId: Long, projectId: String): List<String> =
        repositoryFor(databaseId).listProjectTags(databaseId, projectId)

    override suspend fun listAllProjectTags(databaseId: Long): List<MdbxProjectTagSummary> =
        repositoryFor(databaseId).listAllProjectTags(databaseId)

    override suspend fun searchProjects(
        databaseId: Long,
        query: String,
        requiredTags: List<String>
    ): List<MdbxProjectSearchResult> =
        repositoryFor(databaseId).searchProjects(databaseId, query, requiredTags)

    override suspend fun getCurrentHeadCommitId(databaseId: Long): String? =
        repositoryFor(databaseId).getCurrentHeadCommitId(databaseId)

    override suspend fun listDeltaHistory(databaseId: Long): List<MdbxDeltaSummary> =
        repositoryFor(databaseId).listDeltaHistory(databaseId)

    override suspend fun listCommitDiff(databaseId: Long, commitId: String): List<MdbxCommitDiff> =
        repositoryFor(databaseId).listCommitDiff(databaseId, commitId)

    override suspend fun revertCommit(databaseId: Long, commitId: String): Int =
        repositoryFor(databaseId).revertCommit(databaseId, commitId)

    override suspend fun listSnapshots(databaseId: Long): List<MdbxSnapshotSummary> =
        repositoryFor(databaseId).listSnapshots(databaseId)

    override suspend fun createSnapshot(
        databaseId: Long,
        name: String,
        fullSnapshot: Boolean,
        autoPrune: Boolean
    ): MdbxSnapshotSummary = repositoryFor(databaseId).createSnapshot(
        databaseId,
        name,
        fullSnapshot,
        autoPrune
    )

    override suspend fun deleteSnapshot(databaseId: Long, snapshotId: String) =
        repositoryFor(databaseId).deleteSnapshot(databaseId, snapshotId)

    override suspend fun revertToSnapshot(databaseId: Long, snapshotId: String): Int =
        repositoryFor(databaseId).revertToSnapshot(databaseId, snapshotId)

    override suspend fun pruneAutomaticSnapshots(
        databaseId: Long,
        keepCount: Int?,
        maxBytes: Long?
    ): Int = repositoryFor(databaseId).pruneAutomaticSnapshots(databaseId, keepCount, maxBytes)

    override suspend fun getSnapshotStructurePreview(
        databaseId: Long,
        snapshotId: String
    ): MdbxStructurePreview =
        repositoryFor(databaseId).getSnapshotStructurePreview(databaseId, snapshotId)

    override suspend fun exportSyncBundle(databaseId: Long, baseCommitId: String?): MdbxSyncBundle =
        repositoryFor(databaseId).exportSyncBundle(databaseId, baseCommitId)

    override suspend fun importSyncBundle(databaseId: Long, bundle: MdbxSyncBundle): MdbxApplyResult =
        repositoryFor(databaseId).importSyncBundle(databaseId, bundle)

    override suspend fun flushPendingWorkingCopy(databaseId: Long) =
        repositoryFor(databaseId).flushPendingWorkingCopy(databaseId)

    override suspend fun flushWorkingCopy(databaseId: Long) =
        repositoryFor(databaseId).flushWorkingCopy(databaseId)

    override suspend fun listConflicts(databaseId: Long): List<MdbxConflictSummary> =
        repositoryFor(databaseId).listConflicts(databaseId)

    override suspend fun resolveConflict(
        databaseId: Long,
        conflictId: String,
        resolution: MdbxConflictResolution
    ) = repositoryFor(databaseId).resolveConflict(databaseId, conflictId, resolution)

    override suspend fun upsertAttachment(
        databaseId: Long,
        parentEntryId: String,
        attachment: Attachment
    ) = repositoryFor(databaseId).upsertAttachment(databaseId, parentEntryId, attachment)

    override suspend fun upsertExternalAttachmentRef(
        databaseId: Long,
        parentEntryId: String,
        attachment: Attachment,
        externalUri: String
    ) = repositoryFor(databaseId).upsertExternalAttachmentRef(
        databaseId,
        parentEntryId,
        attachment,
        externalUri
    )

    override suspend fun deleteAttachment(
        databaseId: Long,
        parentEntryId: String,
        attachment: Attachment
    ) = repositoryFor(databaseId).deleteAttachment(databaseId, parentEntryId, attachment)

    private suspend fun repositoryFor(databaseId: Long): MdbxRepository {
        val database = databaseDao.getDatabaseById(databaseId)
            ?: throw MdbxVaultNotFoundException(databaseId)
        return when (database.engineTypeEnum) {
            MdbxEngineType.KOTLIN_MDBX1 -> legacyRepository
            MdbxEngineType.RUST_MDBX2 -> rustRepository
        }
    }

    private suspend fun repositoryForEntry(databaseId: Long?): MdbxRepository =
        databaseId?.let { repositoryFor(it) } ?: legacyRepository

    private suspend fun <T> dispatchGrouped(
        values: List<T>,
        databaseId: (T) -> Long?,
        operation: suspend (MdbxRepository, List<T>) -> Unit
    ) {
        values.groupBy(databaseId).forEach { (id, grouped) ->
            operation(repositoryForEntry(id), grouped)
        }
    }
}

package takagi.ru.monica.repository

import takagi.ru.monica.attachments.model.Attachment
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.SecureItem

/**
 * Boundary for user-visible MDBX operations.
 *
 * Implementations must commit the local .mdbx working copy and then publish
 * that working copy to the configured SAF/WebDAV source before reporting
 * success. This keeps a second client able to see new MDBX items with its
 * next manual sync instead of requiring a manual sync on the writer first.
 *
 * Keep schema/version/sync/conflict/snapshot logic behind this facade so UI
 * and ViewModel code do not grow their own MDBX table behavior.
 */
interface MdbxRepository {
    /** Call before committing a persistent app replica of database secrets. */
    suspend fun requireRoomMirrorAllowed(databaseId: Long) = Unit
    suspend fun requiresStrictMutationConsistency(databaseId: Long): Boolean = false

    suspend fun readStoredEntries(databaseId: Long): List<MdbxStoredVaultEntry>
    suspend fun readStoredAttachments(databaseId: Long): List<MdbxStoredAttachment>

    suspend fun createFolder(
        databaseId: Long,
        name: String,
        parentFolderId: String? = "root"
    ): MdbxStoredFolderEntry

    suspend fun listFolders(databaseId: Long): List<MdbxStoredFolderEntry>

    suspend fun renameFolder(
        databaseId: Long,
        folderId: String,
        name: String
    ): MdbxStoredFolderEntry = throw UnsupportedOperationException("MDBX folder rename is unavailable")

    suspend fun moveFolder(
        databaseId: Long,
        folderId: String,
        parentFolderId: String?
    ): MdbxStoredFolderEntry = throw UnsupportedOperationException("MDBX folder move is unavailable")

    suspend fun deleteFolder(databaseId: Long, folderId: String) {
        throw UnsupportedOperationException("MDBX folder deletion is unavailable")
    }

    suspend fun restoreFolder(
        databaseId: Long,
        folderId: String,
        parentFolderId: String?
    ): MdbxStoredFolderEntry = throw UnsupportedOperationException("MDBX folder restore is unavailable")

    suspend fun upsertPassword(entry: PasswordEntry)
    suspend fun deletePassword(entry: PasswordEntry)
    suspend fun upsertPasswords(entries: List<PasswordEntry>) {
        entries.forEach { upsertPassword(it) }
    }
    suspend fun deletePasswords(entries: List<PasswordEntry>) {
        entries.forEach { deletePassword(it) }
    }

    suspend fun upsertSecureItem(item: SecureItem)
    suspend fun deleteSecureItem(item: SecureItem)
    suspend fun upsertSecureItems(items: List<SecureItem>) {
        items.forEach { upsertSecureItem(it) }
    }
    suspend fun deleteSecureItems(items: List<SecureItem>) {
        items.forEach { deleteSecureItem(it) }
    }

    suspend fun upsertPasskey(passkey: PasskeyEntry)
    suspend fun deletePasskey(passkey: PasskeyEntry)
    suspend fun upsertPasskeys(passkeys: List<PasskeyEntry>) {
        passkeys.forEach { upsertPasskey(it) }
    }
    suspend fun deletePasskeys(passkeys: List<PasskeyEntry>) {
        passkeys.forEach { deletePasskey(it) }
    }

    /**
     * Import passwords have been normalized and encrypted exactly once by the restore applier.
     * Their decrypted contents must not be interpreted as another encryption envelope.
     * Acknowledgements are emitted only after native persistence, including partial batches.
     */
    suspend fun upsertImportBatch(
        databaseId: Long,
        passwords: List<PasswordEntry>,
        secureItems: List<SecureItem>,
        passkeys: List<PasskeyEntry>,
        onCommitted: (Set<String>) -> Unit,
    ) {
        require(passwords.all { it.mdbxDatabaseId == databaseId } &&
            secureItems.all { it.mdbxDatabaseId == databaseId } && passkeys.all { it.mdbxDatabaseId == databaseId })
        passwords.forEach { upsertPassword(it); onCommitted(setOf(mdbxPasswordObjectId(it))) }
        secureItems.forEach { upsertSecureItem(it); onCommitted(setOf(mdbxSecureItemObjectId(it))) }
        passkeys.forEach { upsertPasskey(it); onCommitted(setOf("passkey:${it.credentialId}")) }
    }

    suspend fun listSteamMaFileEntries(databaseId: Long): List<MdbxStoredVaultEntry>
    suspend fun upsertSteamMaFileEntry(
        databaseId: Long,
        entryId: String?,
        title: String,
        maFileJson: String
    ): String
    suspend fun upsertSteamMaFileEntryInFolder(
        databaseId: Long, entryId: String?, title: String, maFileJson: String, folderId: String?
    ): String = throw UnsupportedOperationException("Steam folder writes are unavailable")

    suspend fun deleteSteamMaFileEntry(databaseId: Long, entryId: String)

    suspend fun getVaultDiagnostics(databaseId: Long): MdbxVaultDiagnostics
    suspend fun getPendingSyncCount(databaseId: Long): Int

    suspend fun planHealthRepair(databaseId: Long): MdbxHealthRepairPlan =
        throw UnsupportedOperationException("MDBX health repair is only available for MDBX2")

    suspend fun applyHealthRepair(
        databaseId: Long,
        planToken: String,
        operationId: String,
        decisions: List<MdbxHealthRepairDecision>
    ): MdbxHealthRepairApplyResult =
        throw UnsupportedOperationException("MDBX health repair is only available for MDBX2")

    suspend fun setProjectTags(databaseId: Long, projectId: String, tags: List<String>)
    suspend fun listProjectTags(databaseId: Long, projectId: String): List<String>
    suspend fun listAllProjectTags(databaseId: Long): List<MdbxProjectTagSummary>
    suspend fun searchProjects(
        databaseId: Long,
        query: String,
        requiredTags: List<String> = emptyList()
    ): List<MdbxProjectSearchResult>

    suspend fun getCurrentHeadCommitId(databaseId: Long): String?
    suspend fun listDeltaHistory(databaseId: Long): List<MdbxDeltaSummary>
    suspend fun listCommitDiff(databaseId: Long, commitId: String): List<MdbxCommitDiff>
    suspend fun revertCommit(databaseId: Long, commitId: String): Int

    suspend fun listSnapshots(databaseId: Long): List<MdbxSnapshotSummary>
    suspend fun createSnapshot(
        databaseId: Long,
        name: String,
        fullSnapshot: Boolean = false,
        autoPrune: Boolean = false
    ): MdbxSnapshotSummary
    suspend fun deleteSnapshot(databaseId: Long, snapshotId: String)
    suspend fun revertToSnapshot(databaseId: Long, snapshotId: String): Int
    suspend fun pruneAutomaticSnapshots(
        databaseId: Long,
        keepCount: Int? = null,
        maxBytes: Long? = null
    ): Int
    suspend fun getSnapshotStructurePreview(
        databaseId: Long,
        snapshotId: String
    ): MdbxStructurePreview

    suspend fun exportSyncBundle(
        databaseId: Long,
        baseCommitId: String? = null
    ): MdbxSyncBundle
    suspend fun importSyncBundle(databaseId: Long, bundle: MdbxSyncBundle): MdbxApplyResult
    suspend fun flushPendingWorkingCopy(databaseId: Long)
    suspend fun flushWorkingCopy(databaseId: Long)

    suspend fun listConflicts(databaseId: Long): List<MdbxConflictSummary>
    suspend fun resolveConflict(
        databaseId: Long,
        conflictId: String,
        resolution: MdbxConflictResolution
    )

    suspend fun upsertAttachment(
        databaseId: Long,
        parentEntryId: String,
        attachment: Attachment
    )
    suspend fun upsertExternalAttachmentRef(
        databaseId: Long,
        parentEntryId: String,
        attachment: Attachment,
        externalUri: String
    )
    suspend fun deleteAttachment(
        databaseId: Long,
        parentEntryId: String,
        attachment: Attachment
    )
}

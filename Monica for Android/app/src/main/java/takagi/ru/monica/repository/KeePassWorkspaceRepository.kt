package takagi.ru.monica.repository

import android.content.Context
import android.net.Uri
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.data.LocalKeePassDatabaseDao
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.utils.KeePassDatabaseDiagnostics
import takagi.ru.monica.utils.KeePassConflictResolutionResult
import takagi.ru.monica.utils.KeePassCustomFieldData
import takagi.ru.monica.utils.KeePassEntryData
import takagi.ru.monica.utils.KeePassGroupInfo
import takagi.ru.monica.utils.KeePassKdbxService
import takagi.ru.monica.utils.KeePassRemoteSyncResult
import takagi.ru.monica.utils.KeePassRestoreTarget
import takagi.ru.monica.utils.KeePassSecureItemData
import takagi.ru.monica.utils.KeePassWorkspaceSnapshot
import takagi.ru.monica.keepass.KeePassNativeSession
import takagi.ru.monica.keepass.KeePassNativeBrowserSnapshot
import takagi.ru.monica.keepass.KeePassNativeEntryRecord
import takagi.ru.monica.keepass.KeePassNativeGroupRecord
import takagi.ru.monica.keepass.KeePassNativeEntryPresentationUpdate
import takagi.ru.monica.keepass.KeePassNativeCustomIconPoolUpdate
import takagi.ru.monica.keepass.KeePassNativeSearchOptions
import takagi.ru.monica.keepass.KeePassNativeSearchResult
import takagi.ru.monica.keepass.KeePassFieldChange
import takagi.ru.monica.keepass.KeePassDatabaseSettingsSnapshot
import takagi.ru.monica.keepass.KeePassDatabaseSettingsUpdate
import takagi.ru.monica.keepass.KeePassKeyFileChangeMode
import takagi.ru.monica.keepass.KeePassMasterCredentialChangeResult
import takagi.ru.monica.keepass.KeePassProjectionKind
import takagi.ru.monica.keepass.KeePassProjectionRefreshDecision
import takagi.ru.monica.keepass.KeePassConflictDecision
import takagi.ru.monica.keepass.KeePassConflictResolutionSide
import takagi.ru.monica.keepass.KeePassRemoteConflictPreview
import takagi.ru.monica.keepass.KeePassRemoteConflictResolution
import takagi.ru.monica.keepass.KeePassIntegrityReport
import takagi.ru.monica.keepass.KeePassMaintenanceExecution
import takagi.ru.monica.keepass.KeePassMaintenanceOptions
import takagi.ru.monica.keepass.KeePassNativeDeleteMode
import takagi.ru.monica.keepass.KeePassNativeGroupUpdate
import takagi.ru.monica.keepass.KeePassRecoveryRecord
import java.time.Instant
import java.util.UUID

class KeePassWorkspaceRepository(
    private val service: KeePassKdbxService
) {

    constructor(
        context: Context,
        dao: LocalKeePassDatabaseDao,
        securityManager: SecurityManager
    ) : this(KeePassKdbxService(context, dao, securityManager))

    suspend fun loadWorkspace(
        databaseId: Long,
        includeRecycleBinGroups: Boolean = false,
        allowedSecureItemTypes: Set<ItemType>? = null
    ): Result<KeePassWorkspaceSnapshot> {
        return service.loadWorkspace(
            databaseId = databaseId,
            includeRecycleBinGroups = includeRecycleBinGroups,
            allowedSecureItemTypes = allowedSecureItemTypes
        )
    }

    internal suspend fun openNativeSession(databaseId: Long): Result<KeePassNativeSession> {
        return service.openNativeSession(databaseId)
    }

    internal suspend fun openNativeBrowser(databaseId: Long): Result<KeePassNativeBrowserSnapshot> {
        return service.openNativeBrowser(databaseId)
    }

    internal fun isDatabaseReadOnly(databaseId: Long): Boolean =
        service.isDatabaseReadOnly(databaseId)

    internal fun setDatabaseReadOnly(databaseId: Long, readOnly: Boolean) {
        service.setDatabaseReadOnly(databaseId, readOnly)
    }

    internal fun lockDatabase(databaseId: Long) {
        service.lockDatabase(databaseId)
    }

    internal suspend fun readNativeDatabaseSettings(
        databaseId: Long
    ): Result<KeePassDatabaseSettingsSnapshot> =
        service.readNativeDatabaseSettings(databaseId)

    internal suspend fun updateNativeDatabaseSettings(
        databaseId: Long,
        update: KeePassDatabaseSettingsUpdate
    ): Result<KeePassDatabaseSettingsSnapshot> =
        service.updateNativeDatabaseSettings(databaseId, update)

    internal suspend fun changeMasterCredentials(
        databaseId: Long,
        newPassword: String,
        keyFileMode: KeePassKeyFileChangeMode,
        replacementKeyFileUri: Uri?,
        keepInternalKeyFileCopy: Boolean
    ): Result<KeePassMasterCredentialChangeResult> =
        service.changeMasterCredentials(
            databaseId = databaseId,
            newPassword = newPassword,
            keyFileMode = keyFileMode,
            replacementKeyFileUri = replacementKeyFileUri,
            keepInternalKeyFileCopy = keepInternalKeyFileCopy
        )

    internal suspend fun searchNativeEntries(
        databaseId: Long,
        options: KeePassNativeSearchOptions,
        now: Instant = Instant.now()
    ): Result<KeePassNativeSearchResult> {
        return service.searchNativeEntries(databaseId, options, now)
    }

    internal suspend fun replaceNativeEntryFields(
        databaseId: Long,
        entryUuid: UUID,
        fields: List<KeePassFieldChange>,
        expectedRevisionToken: String
    ): Result<KeePassNativeEntryRecord> {
        return service.replaceNativeEntryFields(databaseId, entryUuid, fields, expectedRevisionToken)
    }

    internal suspend fun replaceNativeEntryFieldsAndPresentation(
        databaseId: Long,
        entryUuid: UUID,
        fields: List<KeePassFieldChange>,
        presentation: KeePassNativeEntryPresentationUpdate,
        expectedRevisionToken: String,
    ): Result<KeePassNativeEntryRecord> = service.replaceNativeEntryFieldsAndPresentation(
        databaseId,
        entryUuid,
        fields,
        presentation,
        expectedRevisionToken,
    )

    internal suspend fun saveNativeEntryDraft(
        databaseId: Long,
        entryUuid: UUID?,
        parentGroupUuid: UUID?,
        fields: List<KeePassFieldChange>,
        presentation: KeePassNativeEntryPresentationUpdate?,
        sourceUris: List<Uri>,
        expectedRevisionToken: String,
    ): Result<KeePassNativeEntryRecord> = service.saveNativeEntryDraft(
        databaseId, entryUuid, parentGroupUuid, fields, presentation, sourceUris, expectedRevisionToken,
    )

    internal suspend fun replaceNativeEntryPresentation(
        databaseId: Long,
        entryUuid: UUID,
        update: KeePassNativeEntryPresentationUpdate,
        expectedRevisionToken: String,
    ): Result<KeePassNativeEntryRecord> = service.replaceNativeEntryPresentation(
        databaseId,
        entryUuid,
        update,
        expectedRevisionToken,
    )

    internal suspend fun updateNativeCustomIconPool(
        databaseId: Long,
        update: KeePassNativeCustomIconPoolUpdate,
        expectedRevisionToken: String,
    ): Result<Unit> = service.updateNativeCustomIconPool(
        databaseId,
        update,
        expectedRevisionToken,
    )

    internal suspend fun restoreNativeEntryHistory(
        databaseId: Long,
        entryUuid: UUID,
        historyIndex: Int,
        expectedRevisionToken: String
    ): Result<KeePassNativeEntryRecord> {
        return service.restoreNativeEntryHistory(databaseId, entryUuid, historyIndex, expectedRevisionToken)
    }

    internal suspend fun deleteNativeEntryHistory(
        databaseId: Long,
        entryUuid: UUID,
        historyIndex: Int,
        expectedRevisionToken: String
    ): Result<KeePassNativeEntryRecord> {
        return service.deleteNativeEntryHistory(databaseId, entryUuid, historyIndex, expectedRevisionToken)
    }

    internal suspend fun createNativeGroup(
        databaseId: Long,
        parentGroupUuid: UUID,
        name: String,
        expectedRevisionToken: String,
        properties: KeePassNativeGroupUpdate? = null
    ): Result<KeePassNativeGroupRecord> {
        return service.createNativeGroup(
            databaseId,
            parentGroupUuid,
            name,
            expectedRevisionToken,
            properties,
        )
    }

    internal suspend fun renameNativeGroup(
        databaseId: Long,
        groupUuid: UUID,
        newName: String,
        expectedRevisionToken: String
    ): Result<KeePassNativeGroupRecord> {
        return service.renameNativeGroup(databaseId, groupUuid, newName, expectedRevisionToken)
    }

    internal suspend fun moveNativeGroup(
        databaseId: Long,
        groupUuid: UUID,
        targetParentGroupUuid: UUID,
        expectedRevisionToken: String
    ): Result<KeePassNativeGroupRecord> {
        return service.moveNativeGroup(
            databaseId,
            groupUuid,
            targetParentGroupUuid,
            expectedRevisionToken
        )
    }

    internal suspend fun moveNativeGroups(
        databaseId: Long,
        groupUuids: Set<UUID>,
        targetParentGroupUuid: UUID,
        expectedRevisionToken: String
    ): Result<List<KeePassNativeGroupRecord>> = service.moveNativeGroups(
        databaseId = databaseId,
        groupUuids = groupUuids,
        targetParentGroupUuid = targetParentGroupUuid,
        expectedRevisionToken = expectedRevisionToken,
    )

    internal suspend fun deleteNativeGroup(
        databaseId: Long,
        groupUuid: UUID,
        expectedRevisionToken: String
    ): Result<Unit> {
        return service.deleteNativeGroup(databaseId, groupUuid, expectedRevisionToken)
    }

    internal suspend fun createNativeEntry(
        databaseId: Long,
        parentGroupUuid: UUID,
        fields: List<KeePassFieldChange>,
        expectedRevisionToken: String
    ): Result<KeePassNativeEntryRecord> = service.createNativeEntry(
        databaseId,
        parentGroupUuid,
        fields,
        expectedRevisionToken
    )

    internal suspend fun createNativeEntryWithAttachments(
        databaseId: Long,
        parentGroupUuid: UUID,
        fields: List<KeePassFieldChange>,
        sourceUris: List<Uri>,
        expectedRevisionToken: String
    ): Result<KeePassNativeEntryRecord> = service.createNativeEntryWithAttachmentsFromUris(
        databaseId,
        parentGroupUuid,
        fields,
        sourceUris,
        expectedRevisionToken
    )

    internal suspend fun duplicateNativeEntry(
        databaseId: Long,
        entryUuid: UUID,
        targetGroupUuid: UUID,
        expectedRevisionToken: String
    ): Result<KeePassNativeEntryRecord> = service.duplicateNativeEntry(
        databaseId,
        entryUuid,
        targetGroupUuid,
        expectedRevisionToken
    )

    internal suspend fun saveNativeEntryAsTemplate(
        databaseId: Long,
        entryUuid: UUID,
        titleOverride: String?,
        expectedRevisionToken: String,
    ): Result<KeePassNativeEntryRecord> = service.saveNativeEntryAsTemplate(
        databaseId = databaseId,
        entryUuid = entryUuid,
        titleOverride = titleOverride,
        expectedRevisionToken = expectedRevisionToken,
    )

    internal suspend fun instantiateNativeTemplate(
        databaseId: Long,
        templateEntryUuid: UUID,
        targetGroupUuid: UUID,
        titleOverride: String?,
        expectedRevisionToken: String,
    ): Result<KeePassNativeEntryRecord> = service.instantiateNativeTemplate(
        databaseId = databaseId,
        templateEntryUuid = templateEntryUuid,
        targetGroupUuid = targetGroupUuid,
        titleOverride = titleOverride,
        expectedRevisionToken = expectedRevisionToken,
    )

    internal suspend fun deleteNativeTemplate(
        databaseId: Long,
        templateEntryUuid: UUID,
        expectedRevisionToken: String,
    ): Result<Unit> = service.deleteNativeTemplate(
        databaseId = databaseId,
        templateEntryUuid = templateEntryUuid,
        expectedRevisionToken = expectedRevisionToken,
    )

    internal suspend fun moveNativeEntries(
        databaseId: Long,
        entryUuids: Set<UUID>,
        targetGroupUuid: UUID,
        expectedRevisionToken: String
    ): Result<List<KeePassNativeEntryRecord>> = service.moveNativeEntries(
        databaseId,
        entryUuids,
        targetGroupUuid,
        expectedRevisionToken
    )

    internal suspend fun deleteNativeEntries(
        databaseId: Long,
        entryUuids: Set<UUID>,
        mode: KeePassNativeDeleteMode,
        expectedRevisionToken: String
    ): Result<Unit> = service.deleteNativeEntries(
        databaseId,
        entryUuids,
        mode,
        expectedRevisionToken
    )

    internal suspend fun renameNativeAttachment(
        databaseId: Long,
        entryUuid: UUID,
        attachmentHashHex: String,
        currentName: String?,
        newName: String,
        expectedRevisionToken: String
    ): Result<KeePassNativeEntryRecord> = service.renameNativeAttachment(
        databaseId,
        entryUuid,
        attachmentHashHex,
        currentName,
        newName,
        expectedRevisionToken
    )

    internal suspend fun addNativeAttachment(
        databaseId: Long,
        entryUuid: UUID,
        fileName: String,
        bytes: ByteArray,
        expectedRevisionToken: String
    ): Result<KeePassNativeEntryRecord> = service.addNativeAttachment(
        databaseId,
        entryUuid,
        fileName,
        bytes,
        expectedRevisionToken
    )

    internal suspend fun addNativeAttachmentFromUri(
        databaseId: Long,
        entryUuid: UUID,
        sourceUri: Uri,
        expectedRevisionToken: String
    ): Result<KeePassNativeEntryRecord> = service.addNativeAttachmentFromUri(
        databaseId,
        entryUuid,
        sourceUri,
        expectedRevisionToken
    )

    internal suspend fun addNativeAttachmentsFromUris(
        databaseId: Long,
        entryUuid: UUID,
        sourceUris: List<Uri>,
        expectedRevisionToken: String
    ): Result<KeePassNativeEntryRecord> = service.addNativeAttachmentsFromUris(
        databaseId,
        entryUuid,
        sourceUris,
        expectedRevisionToken
    )

    internal suspend fun deleteNativeAttachment(
        databaseId: Long,
        entryUuid: UUID,
        attachmentHashHex: String,
        currentName: String?,
        expectedRevisionToken: String
    ): Result<KeePassNativeEntryRecord> = service.deleteNativeAttachment(
        databaseId,
        entryUuid,
        attachmentHashHex,
        currentName,
        expectedRevisionToken
    )

    internal suspend fun updateNativeGroupProperties(
        databaseId: Long,
        groupUuid: UUID,
        update: KeePassNativeGroupUpdate,
        expectedRevisionToken: String
    ): Result<KeePassNativeGroupRecord> = service.updateNativeGroupProperties(
        databaseId,
        groupUuid,
        update,
        expectedRevisionToken
    )

    internal suspend fun inspectNativeDatabaseIntegrity(databaseId: Long): Result<KeePassIntegrityReport> =
        service.inspectNativeDatabaseIntegrity(databaseId)

    internal suspend fun repairNativeDatabase(
        databaseId: Long,
        options: KeePassMaintenanceOptions = KeePassMaintenanceOptions()
    ): Result<KeePassMaintenanceExecution> = service.repairNativeDatabase(databaseId, options)

    internal fun listRecoveryCopies(databaseId: Long): List<KeePassRecoveryRecord> =
        service.listRecoveryCopies(databaseId)

    internal suspend fun exportRecoveryCopy(
        record: KeePassRecoveryRecord,
        destinationUri: Uri
    ): Result<Unit> = service.exportRecoveryCopy(record, destinationUri)

    internal suspend fun deleteRecoveryCopy(record: KeePassRecoveryRecord): Result<Unit> =
        service.deleteRecoveryCopy(record)

    internal suspend fun restoreRecoveryCopy(
        databaseId: Long,
        record: KeePassRecoveryRecord
    ): Result<Unit> = service.restoreRecoveryCopy(databaseId, record)

    internal suspend fun saveNativeDatabaseCopy(
        databaseId: Long,
        destinationUri: Uri
    ): Result<Unit> = service.saveNativeDatabaseCopy(databaseId, destinationUri)

    internal suspend fun mergeNativeDatabaseFrom(
        databaseId: Long,
        sourceUri: Uri,
        sourcePassword: String,
        sourceKeyFileUri: Uri?,
        targetGroupUuid: UUID,
        expectedRevisionToken: String
    ): Result<KeePassNativeBrowserSnapshot> = service.mergeNativeDatabaseFrom(
        databaseId,
        sourceUri,
        sourcePassword,
        sourceKeyFileUri,
        targetGroupUuid,
        expectedRevisionToken
    )

    internal suspend fun inspectCurrentRemoteConflict(
        databaseId: Long
    ): Result<KeePassRemoteConflictPreview> = service.inspectCurrentRemoteConflict(databaseId)

    internal suspend fun resolveCurrentRemoteConflict(
        databaseId: Long,
        decision: KeePassConflictDecision,
        expectedLocalRevision: String,
        expectedRemoteRevision: String,
        selections: Map<String, KeePassConflictResolutionSide> = emptyMap()
    ): Result<KeePassRemoteConflictResolution> = service.resolveCurrentRemoteConflict(
        databaseId,
        decision,
        expectedLocalRevision,
        expectedRemoteRevision,
        selections
    )

    internal suspend fun projectionRefreshDecision(
        databaseId: Long,
        kind: KeePassProjectionKind,
        forceRefresh: Boolean = false
    ): Result<KeePassProjectionRefreshDecision> {
        return service.projectionRefreshDecision(databaseId, kind, forceRefresh)
    }

    internal fun markProjectionIndexed(
        databaseId: Long,
        revisionToken: String,
        kinds: Set<KeePassProjectionKind>
    ) {
        service.markProjectionIndexed(databaseId, revisionToken, kinds)
    }

    suspend fun listGroups(
        databaseId: Long,
        includeRecycleBin: Boolean = false
    ): Result<List<KeePassGroupInfo>> {
        return service.listGroups(databaseId, includeRecycleBin)
    }

    suspend fun readPasswordEntries(databaseId: Long): Result<List<KeePassEntryData>> {
        return service.readPasswordEntries(databaseId)
    }

    suspend fun readSecureItems(
        databaseId: Long,
        allowedTypes: Set<ItemType>? = null
    ): Result<List<KeePassSecureItemData>> {
        return service.readSecureItems(databaseId, allowedTypes)
    }

    suspend fun readPasskeyEntries(databaseId: Long): Result<List<PasskeyEntry>> {
        return service.readPasskeyEntries(databaseId)
    }

    suspend fun verifyDatabase(databaseId: Long): Result<Int> {
        return service.verifyDatabase(databaseId)
    }

    suspend fun inspectDatabase(
        databaseId: Long,
        passwordOverride: String? = null,
        keyFileUriOverride: Uri? = null
    ): Result<KeePassDatabaseDiagnostics> {
        return service.inspectDatabase(
            databaseId = databaseId,
            passwordOverride = passwordOverride,
            keyFileUriOverride = keyFileUriOverride
        )
    }

    suspend fun inspectExternalDatabase(
        fileUri: Uri,
        password: String,
        keyFileUri: Uri? = null
    ): Result<KeePassDatabaseDiagnostics> {
        return service.inspectExternalDatabase(
            fileUri = fileUri,
            password = password,
            keyFileUri = keyFileUri
        )
    }

    suspend fun resolveRemoteConflict(
        databaseId: Long,
        remoteBytes: ByteArray
    ): Result<KeePassConflictResolutionResult> {
        return service.resolveRemoteConflict(
            databaseId = databaseId,
            remoteBytes = remoteBytes
        )
    }

    suspend fun syncRemoteDatabase(databaseId: Long): Result<KeePassRemoteSyncResult> {
        return service.syncRemoteDatabase(databaseId)
    }

    suspend fun createGroup(
        databaseId: Long,
        groupName: String,
        parentPath: String? = null
    ): Result<KeePassGroupInfo> {
        return service.createGroup(databaseId, groupName, parentPath)
    }

    suspend fun renameGroup(
        databaseId: Long,
        groupPath: String,
        newName: String
    ): Result<KeePassGroupInfo> {
        return service.renameGroup(databaseId, groupPath, newName)
    }

    suspend fun deleteGroup(
        databaseId: Long,
        groupPath: String
    ): Result<Unit> {
        return service.deleteGroup(databaseId, groupPath)
    }

    suspend fun moveGroup(
        sourceDatabaseId: Long,
        groupPath: String,
        targetDatabaseId: Long,
        targetParentPath: String? = null
    ): Result<KeePassGroupInfo> {
        return service.moveGroup(
            sourceDatabaseId = sourceDatabaseId,
            groupPath = groupPath,
            targetDatabaseId = targetDatabaseId,
            targetParentPath = targetParentPath
        )
    }

    suspend fun addOrUpdatePasswordEntries(
        databaseId: Long,
        entries: List<PasswordEntry>,
        resolvePassword: (PasswordEntry) -> String,
        forceSyncWrite: Boolean = false,
        customFieldsByEntryId: Map<Long, List<KeePassCustomFieldData>> = emptyMap()
    ): Result<Int> {
        return service.addOrUpdatePasswordEntries(
            databaseId = databaseId,
            entries = entries,
            resolvePassword = resolvePassword,
            forceSyncWrite = forceSyncWrite,
            customFieldsByEntryId = customFieldsByEntryId
        )
    }

    suspend fun updatePasswordEntry(
        databaseId: Long,
        entry: PasswordEntry,
        resolvePassword: (PasswordEntry) -> String,
        customFields: List<KeePassCustomFieldData> = emptyList()
    ): Result<Unit> {
        return service.updatePasswordEntry(
            databaseId = databaseId,
            entry = entry,
            resolvePassword = resolvePassword,
            customFields = customFields
        )
    }

    suspend fun deletePasswordEntries(
        databaseId: Long,
        entries: List<PasswordEntry>
    ): Result<Int> {
        return service.deletePasswordEntries(databaseId, entries)
    }

    suspend fun movePasswordEntriesToRecycleBin(
        databaseId: Long,
        entries: List<PasswordEntry>,
        forceSyncWrite: Boolean = false
    ): Result<Int> {
        return service.movePasswordEntriesToRecycleBin(
            databaseId = databaseId,
            entries = entries,
            forceSyncWrite = forceSyncWrite
        )
    }

    suspend fun restorePasswordEntriesFromRecycleBin(
        databaseId: Long,
        entries: List<PasswordEntry>,
        forceSyncWrite: Boolean = false
    ): Result<Map<Long, KeePassRestoreTarget>> {
        return service.restorePasswordEntriesFromRecycleBin(
            databaseId = databaseId,
            entries = entries,
            forceSyncWrite = forceSyncWrite
        )
    }

    suspend fun resolveRestoreGroupPathForPassword(
        databaseId: Long,
        entry: PasswordEntry
    ): Result<String?> {
        return service.resolveRestoreGroupPathForPassword(
            databaseId = databaseId,
            target = entry
        )
    }

    suspend fun resolveRestoreTargetForPassword(
        databaseId: Long,
        entry: PasswordEntry
    ): Result<KeePassRestoreTarget> {
        return service.resolveRestoreTargetForPassword(
            databaseId = databaseId,
            target = entry
        )
    }

    suspend fun addOrUpdateSecureItems(
        databaseId: Long,
        items: List<SecureItem>,
        forceSyncWrite: Boolean = false
    ): Result<Int> {
        return service.addOrUpdateSecureItems(
            databaseId = databaseId,
            items = items,
            forceSyncWrite = forceSyncWrite
        )
    }

    suspend fun addOrUpdatePasskeys(
        databaseId: Long,
        passkeys: List<PasskeyEntry>
    ): Result<Int> {
        return service.addOrUpdatePasskeys(
            databaseId = databaseId,
            passkeys = passkeys
        )
    }

    suspend fun updateSecureItem(
        databaseId: Long,
        item: SecureItem
    ): Result<Unit> {
        return service.updateSecureItem(databaseId, item)
    }

    suspend fun updatePasskey(
        databaseId: Long,
        passkey: PasskeyEntry
    ): Result<Unit> {
        return service.updatePasskey(databaseId, passkey)
    }

    suspend fun deleteSecureItems(
        databaseId: Long,
        items: List<SecureItem>
    ): Result<Int> {
        return service.deleteSecureItems(databaseId, items)
    }

    suspend fun deletePasskeys(
        databaseId: Long,
        passkeys: List<PasskeyEntry>
    ): Result<Int> {
        return service.deletePasskeys(databaseId, passkeys)
    }

    suspend fun moveSecureItemsToRecycleBin(
        databaseId: Long,
        items: List<SecureItem>,
        forceSyncWrite: Boolean = false
    ): Result<Int> {
        return service.moveSecureItemsToRecycleBin(
            databaseId = databaseId,
            items = items,
            forceSyncWrite = forceSyncWrite
        )
    }

    suspend fun restoreSecureItemsFromRecycleBin(
        databaseId: Long,
        items: List<SecureItem>,
        forceSyncWrite: Boolean = false
    ): Result<Map<Long, KeePassRestoreTarget>> {
        return service.restoreSecureItemsFromRecycleBin(
            databaseId = databaseId,
            items = items,
            forceSyncWrite = forceSyncWrite
        )
    }

    suspend fun resolveRestoreGroupPathForSecureItem(
        databaseId: Long,
        item: SecureItem
    ): Result<String?> {
        return service.resolveRestoreGroupPathForSecureItem(
            databaseId = databaseId,
            target = item
        )
    }

    suspend fun resolveRestoreTargetForSecureItem(
        databaseId: Long,
        item: SecureItem
    ): Result<KeePassRestoreTarget> {
        return service.resolveRestoreTargetForSecureItem(
            databaseId = databaseId,
            target = item
        )
    }
}

package takagi.ru.monica.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import takagi.ru.monica.R
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.keemobile.kotpass.models.Meta
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.encode
import app.keemobile.kotpass.database.header.KdfParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import takagi.ru.monica.data.KeePassCipherAlgorithm
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.KeePassDatabaseCreationOptions
import takagi.ru.monica.data.KeePassDatabaseSourceType
import takagi.ru.monica.data.KeePassFormatVersion
import takagi.ru.monica.data.KeePassKdfAlgorithm
import takagi.ru.monica.data.KeePassOpenMode
import takagi.ru.monica.data.KeePassRemoteProviderType
import takagi.ru.monica.data.KeePassStorageLocation
import takagi.ru.monica.data.KeePassSyncStatus
import takagi.ru.monica.data.KeepassRemoteSource
import takagi.ru.monica.data.KeepassRemoteSyncState
import takagi.ru.monica.data.isRemoteSource
import takagi.ru.monica.data.toSourceType
import takagi.ru.monica.data.LocalKeePassDatabase
import takagi.ru.monica.data.LocalKeePassDatabaseDao
import takagi.ru.monica.data.resolvedActiveFilePath
import takagi.ru.monica.data.resolvedActiveStorageLocation
import takagi.ru.monica.data.OperationLogItemType
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.attachments.AttachmentContainer
import takagi.ru.monica.attachments.executor.KeePassAttachmentRef
import takagi.ru.monica.keepass.KeePassCrossDatabaseTransfer
import takagi.ru.monica.keepass.KeePassDxPasskeyCodec
import takagi.ru.monica.keepass.KeePassFieldChange
import takagi.ru.monica.keepass.KeePassNativeBrowserSnapshot
import takagi.ru.monica.keepass.KeePassNativeEntryRecord
import takagi.ru.monica.keepass.KeePassNativeEntryRouteKind
import takagi.ru.monica.keepass.KeePassNativeEntryRoutePolicy
import takagi.ru.monica.keepass.KeePassNativeGroupRecord
import takagi.ru.monica.keepass.KeePassNativeGroupIdentity
import takagi.ru.monica.keepass.KeePassNativeManagerRetainedState
import takagi.ru.monica.keepass.KeePassNativeResolvedRoute
import takagi.ru.monica.keepass.KeePassNativeSearchOptions
import takagi.ru.monica.keepass.KeePassNativeSearchResult
import takagi.ru.monica.keepass.KeePassDatabaseSettingsSnapshot
import takagi.ru.monica.keepass.KeePassDatabaseSettingsUpdate
import takagi.ru.monica.keepass.KeePassKeyFileChangeMode
import takagi.ru.monica.keepass.KeePassMasterCredentialChangeResult
import takagi.ru.monica.keepass.KeePassConflictDecision
import takagi.ru.monica.keepass.KeePassConflictResolutionController
import takagi.ru.monica.keepass.KeePassConflictResolutionSide
import takagi.ru.monica.keepass.KeePassRemoteConflictPreview
import takagi.ru.monica.keepass.KeePassRemoteConflictResolution
import takagi.ru.monica.keepass.KeePassIntegrityReport
import takagi.ru.monica.keepass.KeePassMaintenanceExecution
import takagi.ru.monica.keepass.KeePassMaintenanceOptions
import takagi.ru.monica.keepass.KeePassNativeDeleteMode
import takagi.ru.monica.keepass.KeePassNativeGroupUpdate
import takagi.ru.monica.keepass.KeePassNativeEntryPresentationUpdate
import takagi.ru.monica.keepass.KeePassNativeCustomIconPoolUpdate
import takagi.ru.monica.keepass.KeePassRecoveryRecord
import takagi.ru.monica.keepass.KeePassPasswordMoveRoute
import takagi.ru.monica.keepass.resolveKeePassPasswordMoveRoute
import takagi.ru.monica.repository.KeePassCompatibilityBridge
import takagi.ru.monica.repository.KeePassWorkspaceRepository
import takagi.ru.monica.passkey.PasskeyCredentialIdCodec
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.sync.KEEPASS_REMOTE_SYNC_DEDUPE_KEY
import takagi.ru.monica.sync.SyncDiagnostics
import takagi.ru.monica.sync.SyncEnqueueResult
import takagi.ru.monica.sync.SyncKey
import takagi.ru.monica.sync.SyncMode
import takagi.ru.monica.sync.SyncNetworkPolicy
import takagi.ru.monica.sync.SyncPriority
import takagi.ru.monica.sync.SyncRequest
import takagi.ru.monica.sync.SyncTarget
import takagi.ru.monica.sync.SyncTaskAwaitResult
import takagi.ru.monica.sync.SyncTaskRunner
import takagi.ru.monica.sync.SyncTrigger
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.utils.FieldChange
import takagi.ru.monica.utils.FileSourceEntry
import takagi.ru.monica.utils.GoogleDriveKeePassFileSource
import takagi.ru.monica.utils.GoogleDriveKeePassSupport
import takagi.ru.monica.utils.KeePassCodecSupport
import takagi.ru.monica.utils.KeePassOperationException
import takagi.ru.monica.utils.KeePassGroupInfo
import takagi.ru.monica.utils.KeePassKdbxService
import takagi.ru.monica.utils.KeePassKeyFileStore
import takagi.ru.monica.utils.KeePassFileNameResolver
import takagi.ru.monica.utils.KeePassUriPermissionState
import takagi.ru.monica.utils.keePassUriPermissionState
import takagi.ru.monica.utils.persistKeePassKeyFileReadPermission
import takagi.ru.monica.utils.readKeePassKeyFileBytes
import takagi.ru.monica.utils.OneDriveKeePassFileSource
import takagi.ru.monica.utils.OneDriveKeePassSupport
import takagi.ru.monica.utils.toOneDriveUserMessage
import takagi.ru.monica.utils.OperationLogger
import takagi.ru.monica.utils.RemoteKeePassSyncService
import takagi.ru.monica.utils.WebDavKeePassFileSource
import takagi.ru.monica.utils.WebDavKeePassSupport
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import java.util.UUID

data class KeePassPasswordMoveBatchResult(
    val targetEntryUuidsByPasswordId: Map<Long, String>,
    val failuresByPasswordId: Map<Long, String>
) {
    val successCount: Int get() = targetEntryUuidsByPasswordId.size
    val failedCount: Int get() = failuresByPasswordId.size
}

/**
 * 本地 KeePass 数据库管理 ViewModel
 */
class LocalKeePassViewModel(
    application: Application,
    private val dao: LocalKeePassDatabaseDao,
    private val securityManager: SecurityManager
) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "LocalKeePassViewModel"
        private const val VISIBLE_REMOTE_AUTO_SYNC_THROTTLE_MS = 60_000L
        private const val VISIBLE_REMOTE_AUTO_SYNC_FAILURE_COOLDOWN_MS = 5 * 60_000L
        private const val VISIBLE_REMOTE_AUTO_SYNC_FAILURE_MAX_ATTEMPTS = 3
        private const val VISIBLE_REMOTE_AUTO_SYNC_DEDUPE_KEY = KEEPASS_REMOTE_SYNC_DEDUPE_KEY
    }

    private data class VisibleRemoteAutoSyncFailure(
        val count: Int,
        val nextAllowedAtMillis: Long
    )
    
    private val context: Context get() = getApplication()
    private val strings = AppLocaleStringResolver(application)
    private val visibleRemoteAutoSyncFailures = mutableMapOf<Long, VisibleRemoteAutoSyncFailure>()
    private val visibleRemoteAutoSyncFailureMutex = Mutex()
    
    /** 所有数据库列表 */
    val allDatabases: StateFlow<List<LocalKeePassDatabase>> = dao.getAllDatabases()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    
    /** 内部数据库列表 */
    val internalDatabases: StateFlow<List<LocalKeePassDatabase>> = 
        dao.getDatabasesByLocation(KeePassStorageLocation.INTERNAL)
            .map { databases -> databases.filterNot { it.isRemoteSource() } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    
    /** 外部数据库列表 */
    val externalDatabases: StateFlow<List<LocalKeePassDatabase>> = 
        dao.getDatabasesByLocation(KeePassStorageLocation.EXTERNAL)
            .map { databases -> databases.filterNot { it.isRemoteSource() } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 远端数据库列表 */
    val remoteDatabases: StateFlow<List<LocalKeePassDatabase>> =
        dao.getRemoteDatabases()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    
    /** 操作状态 */
    private val _operationState = MutableStateFlow<OperationState>(OperationState.Idle)
    val operationState: StateFlow<OperationState> = _operationState.asStateFlow()
    
    /** 当前选中的数据库 */
    private val _selectedDatabase = MutableStateFlow<LocalKeePassDatabase?>(null)
    val selectedDatabase: StateFlow<LocalKeePassDatabase?> = _selectedDatabase.asStateFlow()

    private val _activeNativeManagerDatabaseId = MutableStateFlow<Long?>(null)
    val activeNativeManagerDatabaseId: StateFlow<Long?> = _activeNativeManagerDatabaseId.asStateFlow()
    private val _nativeManagerOpenRequests = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val nativeManagerOpenRequests: SharedFlow<Long> = _nativeManagerOpenRequests.asSharedFlow()
    private val nativeManagerRetainedStates = mutableMapOf<Long, KeePassNativeManagerRetainedState>()
    
    /** KeePass 分组缓存，按数据库 ID 组织 */
    private val _groupsByDatabase = MutableStateFlow<Map<Long, List<KeePassGroupInfo>>>(emptyMap())
    private val _verificationStates = MutableStateFlow<Map<Long, VerificationState>>(emptyMap())
    val verificationStates: StateFlow<Map<Long, VerificationState>> = _verificationStates.asStateFlow()
    private val _keyFileAccessStates = MutableStateFlow<Map<Long, KeyFileAccessState>>(emptyMap())
    val keyFileAccessStates: StateFlow<Map<Long, KeyFileAccessState>> = _keyFileAccessStates.asStateFlow()

    private val kdbxService = KeePassKdbxService(context, dao, securityManager)
    private val keyFileStore by lazy { KeePassKeyFileStore(context) }
    private val workspaceRepository = KeePassWorkspaceRepository(kdbxService)
    internal val conflictResolution by lazy {
        KeePassConflictResolutionController(
            scope = viewModelScope,
            inspect = workspaceRepository::inspectCurrentRemoteConflict,
            resolve = workspaceRepository::resolveCurrentRemoteConflict,
            formatError = ::formatOperationError,
            onResolved = { databaseId, resolution ->
                clearVisibleRemoteAutoSyncFailure(databaseId)
                _operationState.value = OperationState.Success(
                    if (resolution.conflictCopyCount > 0) {
                        context.getString(R.string.keepass_conflict_resolved_with_copies, resolution.conflictCopyCount)
                    } else {
                        context.getString(R.string.keepass_conflict_resolved)
                    }
                )
            }
        )
    }
    private val compatibilityBridge = KeePassCompatibilityBridge(workspaceRepository)
    private val verificationMutex = Mutex()
    private val verificationJobs = mutableMapOf<Long, Job>()
    private val _uriPermissionStates = MutableStateFlow<Map<Long, KeePassUriPermissionState>>(emptyMap())
    val uriPermissionStates: StateFlow<Map<Long, KeePassUriPermissionState>> = _uriPermissionStates.asStateFlow()
    private val appDatabase by lazy { PasswordDatabase.getDatabase(context) }
    private val remoteSyncService by lazy {
        RemoteKeePassSyncService(
            databaseDao = dao,
            remoteSourceDao = appDatabase.keepassRemoteSourceDao(),
            syncStateDao = appDatabase.keepassRemoteSyncStateDao(),
            strings = strings,
        )
    }

    init {
        AttachmentContainer.registerKeePassService(kdbxService)
        repairOpaqueExternalDatabaseNames()
    }

    fun openNativeManager(databaseId: Long) {
        nativeManagerRetainedStates.putIfAbsent(
            databaseId,
            KeePassNativeManagerRetainedState(databaseId = databaseId)
        )
        _activeNativeManagerDatabaseId.value = databaseId
        _nativeManagerOpenRequests.tryEmit(databaseId)
    }

    fun closeNativeManager(databaseId: Long, clearRetainedState: Boolean = true) {
        if (_activeNativeManagerDatabaseId.value == databaseId) {
            _activeNativeManagerDatabaseId.value = null
        }
        if (clearRetainedState) nativeManagerRetainedStates.remove(databaseId)
    }

    internal fun isKeePassDatabaseReadOnly(databaseId: Long): Boolean =
        compatibilityBridge.isDatabaseReadOnly(databaseId)

    internal fun setKeePassDatabaseReadOnly(databaseId: Long, readOnly: Boolean) {
        compatibilityBridge.setDatabaseReadOnly(databaseId, readOnly)
    }

    internal suspend fun loadKeePassDatabaseSettings(
        databaseId: Long
    ): Result<KeePassDatabaseSettingsSnapshot> =
        compatibilityBridge.readNativeDatabaseSettings(databaseId)

    internal suspend fun updateKeePassDatabaseSettings(
        databaseId: Long,
        update: KeePassDatabaseSettingsUpdate
    ): Result<KeePassDatabaseSettingsSnapshot> =
        compatibilityBridge.updateNativeDatabaseSettings(databaseId, update)

    internal suspend fun changeKeePassMasterCredentials(
        databaseId: Long,
        newPassword: String,
        keyFileMode: KeePassKeyFileChangeMode,
        replacementKeyFileUri: Uri?,
        keepInternalKeyFileCopy: Boolean
    ): Result<KeePassMasterCredentialChangeResult> =
        compatibilityBridge.changeMasterCredentials(
            databaseId = databaseId,
            newPassword = newPassword,
            keyFileMode = keyFileMode,
            replacementKeyFileUri = replacementKeyFileUri,
            keepInternalKeyFileCopy = keepInternalKeyFileCopy
        ).onSuccess {
            refreshKeyFileAccessState(databaseId)
        }

    internal fun verifyMonicaMasterPassword(password: String): Boolean =
        securityManager.verifyMasterPassword(password)

    fun lockKeePassDatabase(databaseId: Long) {
        compatibilityBridge.lockDatabase(databaseId)
        closeNativeManager(databaseId, clearRetainedState = true)
        _groupsByDatabase.update { current -> current - databaseId }
        _verificationStates.update { current -> current - databaseId }
        _operationState.value = OperationState.Success(strings.get(R.string.legacy_ui_database_locked))
    }

    internal fun nativeManagerRetainedState(databaseId: Long): KeePassNativeManagerRetainedState =
        nativeManagerRetainedStates[databaseId]
            ?: KeePassNativeManagerRetainedState(databaseId = databaseId)

    internal fun retainNativeManagerState(state: KeePassNativeManagerRetainedState) {
        nativeManagerRetainedStates[state.databaseId] = state
    }

    internal suspend fun resolveNativeEntryRoute(
        entry: KeePassNativeEntryRecord
    ): Result<KeePassNativeResolvedRoute> = withContext(Dispatchers.IO) {
        runCatching {
            val databaseId = entry.identity.databaseId
            val entryUuid = entry.identity.entryUuid.toString()
            when (KeePassNativeEntryRoutePolicy.routeFor(entry.kind)) {
                KeePassNativeEntryRouteKind.PASSWORD -> {
                    appDatabase.passwordEntryDao()
                        .findByKeePassEntryUuid(databaseId, entryUuid)
                        ?.let { KeePassNativeResolvedRoute.Password(it.id) }
                        ?: KeePassNativeResolvedRoute.Generic
                }
                KeePassNativeEntryRouteKind.TOTP -> resolveSecureItemRoute(
                    databaseId,
                    entryUuid,
                    ItemType.TOTP
                ) { id -> KeePassNativeResolvedRoute.Totp(id) }
                KeePassNativeEntryRouteKind.NOTE -> resolveSecureItemRoute(
                    databaseId,
                    entryUuid,
                    ItemType.NOTE
                ) { id -> KeePassNativeResolvedRoute.Note(id) }
                KeePassNativeEntryRouteKind.BANK_CARD -> resolveSecureItemRoute(
                    databaseId,
                    entryUuid,
                    ItemType.BANK_CARD
                ) { id -> KeePassNativeResolvedRoute.BankCard(id) }
                KeePassNativeEntryRouteKind.DOCUMENT -> resolveSecureItemRoute(
                    databaseId,
                    entryUuid,
                    ItemType.DOCUMENT
                ) { id -> KeePassNativeResolvedRoute.Document(id) }
                KeePassNativeEntryRouteKind.PASSKEY -> {
                    val rawCredentialId = entry.field("MonicaPasskeyCredentialId")?.displayValue
                        .orEmpty()
                        .ifBlank {
                            entry.field(KeePassDxPasskeyCodec.FIELD_CREDENTIAL_ID)?.displayValue.orEmpty()
                        }
                    val targetCredentialId = PasskeyCredentialIdCodec.normalize(rawCredentialId) ?: rawCredentialId
                    appDatabase.passkeyDao()
                        .getKeePassCompatPasskeysByDatabaseId(databaseId)
                        .firstOrNull { candidate ->
                            val candidateCredentialId = PasskeyCredentialIdCodec.normalize(candidate.credentialId)
                                ?: candidate.credentialId
                            targetCredentialId.isNotBlank() && candidateCredentialId == targetCredentialId
                        }
                        ?.let { KeePassNativeResolvedRoute.Passkey(it.id) }
                        ?: KeePassNativeResolvedRoute.Generic
                }
                KeePassNativeEntryRouteKind.GENERIC -> KeePassNativeResolvedRoute.Generic
            }
        }
    }

    private suspend fun resolveSecureItemRoute(
        databaseId: Long,
        entryUuid: String,
        expectedType: ItemType,
        route: (Long) -> KeePassNativeResolvedRoute
    ): KeePassNativeResolvedRoute {
        val item = appDatabase.secureItemDao().findByKeePassEntryUuid(databaseId, entryUuid)
        return if (item != null && item.itemType == expectedType) route(item.id)
        else KeePassNativeResolvedRoute.Generic
    }

    internal suspend fun openNativeBrowser(databaseId: Long): Result<KeePassNativeBrowserSnapshot> =
        workspaceRepository.openNativeBrowser(databaseId)

    internal suspend fun searchNativeEntries(
        databaseId: Long,
        options: KeePassNativeSearchOptions,
        now: Instant = Instant.now()
    ): Result<KeePassNativeSearchResult> =
        workspaceRepository.searchNativeEntries(databaseId, options, now)

    internal suspend fun replaceNativeEntryFields(
        databaseId: Long,
        entryUuid: UUID,
        fields: List<KeePassFieldChange>,
        expectedRevisionToken: String
    ): Result<KeePassNativeEntryRecord> = workspaceRepository.replaceNativeEntryFields(
        databaseId,
        entryUuid,
        fields,
        expectedRevisionToken
    )

    internal suspend fun replaceNativeEntryFieldsAndPresentation(
        databaseId: Long,
        entryUuid: UUID,
        fields: List<KeePassFieldChange>,
        presentation: KeePassNativeEntryPresentationUpdate,
        expectedRevisionToken: String,
    ): Result<KeePassNativeEntryRecord> = workspaceRepository.replaceNativeEntryFieldsAndPresentation(
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
    ): Result<KeePassNativeEntryRecord> = workspaceRepository.saveNativeEntryDraft(
        databaseId, entryUuid, parentGroupUuid, fields, presentation, sourceUris, expectedRevisionToken,
    )

    internal suspend fun replaceNativeEntryPresentation(
        databaseId: Long,
        entryUuid: UUID,
        update: KeePassNativeEntryPresentationUpdate,
        expectedRevisionToken: String,
    ): Result<KeePassNativeEntryRecord> = workspaceRepository.replaceNativeEntryPresentation(
        databaseId,
        entryUuid,
        update,
        expectedRevisionToken,
    )

    internal suspend fun updateNativeCustomIconPool(
        databaseId: Long,
        update: KeePassNativeCustomIconPoolUpdate,
        expectedRevisionToken: String,
    ): Result<Unit> = workspaceRepository.updateNativeCustomIconPool(
        databaseId,
        update,
        expectedRevisionToken,
    )

    internal suspend fun restoreNativeEntryHistory(
        databaseId: Long,
        entryUuid: UUID,
        historyIndex: Int,
        expectedRevisionToken: String
    ): Result<KeePassNativeEntryRecord> = workspaceRepository.restoreNativeEntryHistory(
        databaseId,
        entryUuid,
        historyIndex,
        expectedRevisionToken
    )

    internal suspend fun deleteNativeEntryHistory(
        databaseId: Long,
        entryUuid: UUID,
        historyIndex: Int,
        expectedRevisionToken: String
    ): Result<KeePassNativeEntryRecord> = workspaceRepository.deleteNativeEntryHistory(
        databaseId,
        entryUuid,
        historyIndex,
        expectedRevisionToken
    )

    internal suspend fun createNativeGroup(
        databaseId: Long,
        parentGroupUuid: UUID,
        name: String,
        expectedRevisionToken: String,
        properties: KeePassNativeGroupUpdate? = null
    ): Result<KeePassNativeGroupRecord> = workspaceRepository.createNativeGroup(
        databaseId,
        parentGroupUuid,
        name,
        expectedRevisionToken,
        properties,
    )

    internal suspend fun renameNativeGroup(
        databaseId: Long,
        groupUuid: UUID,
        newName: String,
        expectedRevisionToken: String
    ): Result<KeePassNativeGroupRecord> = workspaceRepository.renameNativeGroup(
        databaseId,
        groupUuid,
        newName,
        expectedRevisionToken
    )

    internal suspend fun moveNativeGroup(
        databaseId: Long,
        groupUuid: UUID,
        targetParentGroupUuid: UUID,
        expectedRevisionToken: String
    ): Result<KeePassNativeGroupRecord> = workspaceRepository.moveNativeGroup(
        databaseId,
        groupUuid,
        targetParentGroupUuid,
        expectedRevisionToken
    )

    internal suspend fun moveNativeGroups(
        databaseId: Long,
        groupUuids: Set<UUID>,
        targetParentGroupUuid: UUID,
        expectedRevisionToken: String
    ): Result<List<KeePassNativeGroupRecord>> = workspaceRepository.moveNativeGroups(
        databaseId = databaseId,
        groupUuids = groupUuids,
        targetParentGroupUuid = targetParentGroupUuid,
        expectedRevisionToken = expectedRevisionToken,
    )

    internal suspend fun deleteNativeGroup(
        databaseId: Long,
        groupUuid: UUID,
        expectedRevisionToken: String
    ): Result<Unit> = workspaceRepository.deleteNativeGroup(
        databaseId,
        groupUuid,
        expectedRevisionToken
    )

    internal suspend fun createNativeEntry(
        databaseId: Long,
        parentGroupUuid: UUID,
        fields: List<KeePassFieldChange>,
        expectedRevisionToken: String
    ): Result<KeePassNativeEntryRecord> = workspaceRepository.createNativeEntry(
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
    ): Result<KeePassNativeEntryRecord> = workspaceRepository.createNativeEntryWithAttachments(
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
    ): Result<KeePassNativeEntryRecord> = workspaceRepository.duplicateNativeEntry(
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
    ): Result<KeePassNativeEntryRecord> = workspaceRepository.saveNativeEntryAsTemplate(
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
    ): Result<KeePassNativeEntryRecord> = workspaceRepository.instantiateNativeTemplate(
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
    ): Result<Unit> = workspaceRepository.deleteNativeTemplate(
        databaseId = databaseId,
        templateEntryUuid = templateEntryUuid,
        expectedRevisionToken = expectedRevisionToken,
    )

    internal suspend fun moveNativeEntries(
        databaseId: Long,
        entryUuids: Set<UUID>,
        targetGroupUuid: UUID,
        expectedRevisionToken: String
    ): Result<List<KeePassNativeEntryRecord>> = workspaceRepository.moveNativeEntries(
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
    ): Result<Unit> = workspaceRepository.deleteNativeEntries(
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
    ): Result<KeePassNativeEntryRecord> = workspaceRepository.renameNativeAttachment(
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
        sourceUri: Uri,
        expectedRevisionToken: String
    ): Result<KeePassNativeEntryRecord> = workspaceRepository.addNativeAttachmentFromUri(
        databaseId = databaseId,
        entryUuid = entryUuid,
        sourceUri = sourceUri,
        expectedRevisionToken = expectedRevisionToken
    )

    internal suspend fun addNativeAttachments(
        databaseId: Long,
        entryUuid: UUID,
        sourceUris: List<Uri>,
        expectedRevisionToken: String
    ): Result<KeePassNativeEntryRecord> = workspaceRepository.addNativeAttachmentsFromUris(
        databaseId = databaseId,
        entryUuid = entryUuid,
        sourceUris = sourceUris,
        expectedRevisionToken = expectedRevisionToken
    )

    internal suspend fun deleteNativeAttachment(
        databaseId: Long,
        entryUuid: UUID,
        attachmentHashHex: String,
        currentName: String?,
        expectedRevisionToken: String
    ): Result<KeePassNativeEntryRecord> = workspaceRepository.deleteNativeAttachment(
        databaseId,
        entryUuid,
        attachmentHashHex,
        currentName,
        expectedRevisionToken
    )

    internal suspend fun exportNativeAttachment(
        databaseId: Long,
        entryUuid: UUID,
        attachmentHashHex: String,
        currentName: String?,
        destinationUri: Uri
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val encodedReference = KeePassAttachmentRef.from(attachmentHashHex, currentName).encode()
            val bytes = kdbxService.readAttachmentBytes(
                databaseId = databaseId,
                entryUuid = entryUuid.toString(),
                hashHex = encodedReference
            ).getOrThrow()
            context.contentResolver.openOutputStream(destinationUri, "w")?.use { output ->
                output.write(bytes)
                output.flush()
            } ?: throw IOException("Unable to open attachment destination")
        }
    }

    internal suspend fun updateNativeGroupProperties(
        databaseId: Long,
        groupUuid: UUID,
        update: KeePassNativeGroupUpdate,
        expectedRevisionToken: String
    ): Result<KeePassNativeGroupRecord> = workspaceRepository.updateNativeGroupProperties(
        databaseId,
        groupUuid,
        update,
        expectedRevisionToken
    )

    internal suspend fun inspectNativeDatabaseIntegrity(databaseId: Long): Result<KeePassIntegrityReport> =
        workspaceRepository.inspectNativeDatabaseIntegrity(databaseId)

    internal suspend fun repairNativeDatabase(
        databaseId: Long,
        options: KeePassMaintenanceOptions = KeePassMaintenanceOptions()
    ): Result<KeePassMaintenanceExecution> = workspaceRepository.repairNativeDatabase(databaseId, options)

    internal suspend fun listRecoveryCopies(databaseId: Long): List<KeePassRecoveryRecord> =
        withContext(Dispatchers.IO) { workspaceRepository.listRecoveryCopies(databaseId) }

    internal suspend fun exportRecoveryCopy(
        record: KeePassRecoveryRecord,
        destinationUri: Uri
    ): Result<Unit> = workspaceRepository.exportRecoveryCopy(record, destinationUri)

    internal suspend fun deleteRecoveryCopy(record: KeePassRecoveryRecord): Result<Unit> =
        workspaceRepository.deleteRecoveryCopy(record)

    internal suspend fun restoreRecoveryCopy(
        databaseId: Long,
        record: KeePassRecoveryRecord
    ): Result<Unit> = workspaceRepository.restoreRecoveryCopy(databaseId, record)

    internal suspend fun saveNativeDatabaseCopy(
        databaseId: Long,
        destinationUri: Uri
    ): Result<Unit> = workspaceRepository.saveNativeDatabaseCopy(databaseId, destinationUri)

    internal suspend fun mergeNativeDatabaseFrom(
        databaseId: Long,
        sourceUri: Uri,
        sourcePassword: String,
        sourceKeyFileUri: Uri?,
        targetGroupUuid: UUID,
        expectedRevisionToken: String
    ): Result<KeePassNativeBrowserSnapshot> = workspaceRepository.mergeNativeDatabaseFrom(
        databaseId,
        sourceUri,
        sourcePassword,
        sourceKeyFileUri,
        targetGroupUuid,
        expectedRevisionToken
    )

    internal suspend fun inspectCurrentRemoteConflict(
        databaseId: Long
    ): Result<KeePassRemoteConflictPreview> = workspaceRepository.inspectCurrentRemoteConflict(databaseId)

    internal suspend fun resolveCurrentRemoteConflict(
        databaseId: Long,
        decision: KeePassConflictDecision,
        expectedLocalRevision: String,
        expectedRemoteRevision: String,
        selections: Map<String, KeePassConflictResolutionSide> = emptyMap()
    ): Result<KeePassRemoteConflictResolution> = workspaceRepository.resolveCurrentRemoteConflict(
        databaseId,
        decision,
        expectedLocalRevision,
        expectedRemoteRevision,
        selections
    )

    /**
     * Older imports used Uri.lastPathSegment as the display name. Some
     * DocumentsProviders expose an opaque value such as document:1000097490;
     * repair those records once the provider's real DISPLAY_NAME is available.
     */
    private fun repairOpaqueExternalDatabaseNames() {
        viewModelScope.launch(Dispatchers.IO) {
            dao.getAllDatabasesSync()
                .asSequence()
                .filter { database ->
                    database.sourceType == KeePassDatabaseSourceType.LOCAL_DOCUMENT_URI &&
                        KeePassFileNameResolver.isProviderIdentifier(database.name)
                }
                .forEach { database ->
                    val uri = runCatching { Uri.parse(database.filePath) }.getOrNull() ?: return@forEach
                    val resolvedName = KeePassFileNameResolver.databaseNameFromCandidates(
                        displayName = KeePassFileNameResolver.queryDisplayName(
                            context.contentResolver,
                            uri
                        ),
                        uriLastPathSegment = uri.lastPathSegment
                    ) ?: return@forEach
                    if (resolvedName != database.name) {
                        dao.updateDatabase(database.copy(name = resolvedName))
                    }
                }
        }
    }

    data class WebDavDirectoryListing(
        val currentPath: String,
        val entries: List<FileSourceEntry>
    )

    data class OneDriveDirectoryListing(
        val currentPath: String,
        val entries: List<FileSourceEntry>
    )

    data class GoogleDriveDirectoryListing(
        val currentPath: String,
        val currentFolderId: String?,
        val entries: List<FileSourceEntry>
    )

    private data class WebDavAttachResult(
        val databaseId: Long,
        val databaseName: String,
        val entryCount: Int
    )

    private data class OneDriveAttachResult(
        val databaseId: Long,
        val databaseName: String,
        val entryCount: Int
    )

    private data class GoogleDriveAttachResult(
        val databaseId: Long,
        val databaseName: String,
        val entryCount: Int
    )

    private data class RemoteSyncResult(
        val databaseName: String,
        val message: String
    )

    fun getGroups(databaseId: Long): Flow<List<KeePassGroupInfo>> {
        return _groupsByDatabase
            .map { cache -> cache[databaseId].orEmpty() }
            .onStart { refreshGroups(databaseId) }
    }

    /** Overview can observe known folders without opening every database. */
    fun observeCachedGroups(): StateFlow<Map<Long, List<KeePassGroupInfo>>> = _groupsByDatabase.asStateFlow()

    fun getRemoteSyncState(databaseId: Long): Flow<KeepassRemoteSyncState?> {
        return appDatabase.keepassRemoteSyncStateDao().getStateFlow(databaseId)
    }

    fun refreshGroups(databaseId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val groups = workspaceRepository.listGroups(databaseId).getOrDefault(emptyList())
            _groupsByDatabase.update { current -> current + (databaseId to groups) }
        }
    }

    fun pruneVerificationStates(databaseIds: List<Long>) {
        val idSet = databaseIds.toSet()
        _verificationStates.update { current -> current.filterKeys { it in idSet } }
        _keyFileAccessStates.update { current -> current.filterKeys { it in idSet } }
    }

    fun refreshKeyFileAccessState(databaseId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val database = dao.getDatabaseById(databaseId) ?: return@launch
            val hasKeyFile = !database.keyFileInternalPath.isNullOrBlank() ||
                !database.keyFileUri.isNullOrBlank()
            if (!hasKeyFile) {
                _keyFileAccessStates.update { current -> current - databaseId }
                return@launch
            }

            _keyFileAccessStates.update { current ->
                current + (databaseId to KeyFileAccessState.CHECKING)
            }
            val accessResult = runCatching {
                keyFileStore.read(database) ?: error(strings.get(R.string.local_keepass_key_file_access_unavailable))
            }
            _keyFileAccessStates.update { current ->
                current + (
                    databaseId to if (accessResult.isSuccess) {
                        KeyFileAccessState.AVAILABLE
                    } else {
                        KeyFileAccessState.UNAVAILABLE
                    }
                )
            }
            accessResult.exceptionOrNull()?.let { error ->
                _verificationStates.update { current ->
                    current + (
                        databaseId to VerificationState.Failed(
                            error.message ?: context.getString(
                                takagi.ru.monica.R.string.local_keepass_key_file_unavailable_error
                            )
                        )
                    )
                }
            }
        }
    }

    fun keepKeyFileCopy(databaseId: Long, sourceUri: Uri) {
        viewModelScope.launch {
            _operationState.value = OperationState.Loading(strings.get(R.string.keepass_operation_save_key_copy))
            try {
                withContext(Dispatchers.IO) {
                    val database = dao.getDatabaseById(databaseId)
                        ?: throw IllegalArgumentException(strings.get(R.string.keepass_connection_status_missing))
                    workspaceRepository.inspectDatabase(
                        databaseId = databaseId,
                        keyFileUriOverride = sourceUri,
                    ).getOrElse { throw it }

                    context.contentResolver.persistKeePassKeyFileReadPermission(sourceUri)
                    val stored = keyFileStore.copyFromUri(sourceUri, sourceUri.lastPathSegment)
                    dao.updateDatabase(
                        database.copy(
                            keyFileUri = sourceUri.toString(),
                            keyFileInternalPath = stored.relativePath,
                            keyFileName = stored.fileName,
                            keyFileFingerprint = stored.fingerprint,
                            lastAccessedAt = System.currentTimeMillis(),
                        )
                    )
                    KeePassKdbxService.invalidateProcessCache(databaseId)
                }
                refreshKeyFileAccessState(databaseId)
                _operationState.value = OperationState.Success(strings.get(R.string.keepass_operation_key_copy_saved))
            } catch (error: Exception) {
                _operationState.value = OperationState.Error(strings.get(R.string.keepass_operation_key_copy_save_failed, formatOperationError(error)))
            }
        }
    }

    fun exportKeyFileCopy(databaseId: Long, targetUri: Uri) {
        viewModelScope.launch {
            _operationState.value = OperationState.Loading(strings.get(R.string.keepass_operation_export_key))
            try {
                withContext(Dispatchers.IO) {
                    val database = dao.getDatabaseById(databaseId)
                        ?: throw IllegalArgumentException(strings.get(R.string.keepass_connection_status_missing))
                    val relativePath = database.keyFileInternalPath
                        ?.takeIf { it.isNotBlank() }
                        ?: throw IllegalStateException(strings.get(R.string.keepass_operation_key_copy_missing))
                    keyFileStore.exportInternal(relativePath, targetUri)
                    context.contentResolver.persistKeePassKeyFileReadPermission(targetUri)
                    val exportedName = KeePassFileNameResolver.queryDisplayName(
                        context.contentResolver,
                        targetUri,
                    )?.takeIf { it.isNotBlank() }
                    dao.updateDatabase(
                        database.copy(
                            // The exported file becomes a verified external fallback.
                            // This also lets users safely remove the Monica copy later.
                            keyFileUri = targetUri.toString(),
                            keyFileName = exportedName ?: database.keyFileName,
                            lastAccessedAt = System.currentTimeMillis(),
                        )
                    )
                    KeePassKdbxService.invalidateProcessCache(databaseId)
                }
                refreshKeyFileAccessState(databaseId)
                _operationState.value = OperationState.Success(strings.get(R.string.keepass_operation_key_exported))
            } catch (error: Exception) {
                _operationState.value = OperationState.Error(strings.get(R.string.keepass_operation_key_export_failed, formatOperationError(error)))
            }
        }
    }

    fun deleteKeyFileCopy(databaseId: Long) {
        viewModelScope.launch {
            _operationState.value = OperationState.Loading(strings.get(R.string.keepass_operation_delete_key_copy))
            try {
                withContext(Dispatchers.IO) {
                    val database = dao.getDatabaseById(databaseId)
                        ?: throw IllegalArgumentException(strings.get(R.string.keepass_connection_status_missing))
                    val relativePath = database.keyFileInternalPath
                        ?.takeIf { it.isNotBlank() }
                        ?: return@withContext
                    val externalUri = database.keyFileUri
                        ?.takeIf { it.isNotBlank() }
                        ?.let(Uri::parse)
                        ?: throw IllegalStateException(strings.get(R.string.keepass_operation_select_external_key_first))
                    val externalBytes = context.contentResolver.readKeePassKeyFileBytes(
                        uri = externalUri,
                        unavailableMessage = strings.get(R.string.keepass_operation_export_private_key_first),
                    )
                    val internalFingerprint = KeePassKeyFileStore.fingerprint(
                        keyFileStore.readInternal(relativePath)
                    )
                    if (
                        !database.keyFileFingerprint.isNullOrBlank() &&
                        !internalFingerprint.equals(database.keyFileFingerprint, ignoreCase = true)
                    ) {
                        throw IllegalStateException(strings.get(R.string.keepass_operation_private_key_invalid))
                    }
                    val externalFingerprint = KeePassKeyFileStore.fingerprint(externalBytes)
                    if (!externalFingerprint.equals(internalFingerprint, ignoreCase = true)) {
                        throw IllegalStateException(strings.get(R.string.keepass_operation_external_key_mismatch))
                    }

                    dao.updateDatabase(
                        database.copy(
                            keyFileInternalPath = null,
                            keyFileFingerprint = internalFingerprint,
                            lastAccessedAt = System.currentTimeMillis(),
                        )
                    )
                    KeePassKdbxService.invalidateProcessCache(databaseId)
                    val stillReferenced = dao.getAllDatabasesSync().any { other ->
                        other.id != databaseId && other.keyFileInternalPath == relativePath
                    }
                    if (!stillReferenced && !keyFileStore.deleteInternal(relativePath)) {
                        throw IllegalStateException(strings.get(R.string.keepass_operation_key_cleanup_failed))
                    }
                }
                refreshKeyFileAccessState(databaseId)
                _operationState.value = OperationState.Success(strings.get(R.string.keepass_operation_key_copy_deleted))
            } catch (error: Exception) {
                _operationState.value = OperationState.Error(strings.get(R.string.keepass_operation_key_copy_delete_failed, formatOperationError(error)))
            }
        }
    }

    fun verifyDatabaseCredentials(databaseId: Long, force: Boolean = true) {
        synchronized(verificationJobs) {
            val activeJob = verificationJobs[databaseId]
            if (activeJob?.isActive == true) {
                return
            }
            if (activeJob != null) {
                verificationJobs.remove(databaseId)
            }
        }

        val job = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                val existing = _verificationStates.value[databaseId]
                if (!force && existing != null && existing !is VerificationState.Unknown) {
                    return@launch
                }

                _verificationStates.update { current ->
                    current + (databaseId to VerificationState.Verifying)
                }

                val (verifyResult, elapsedMs) = verificationMutex.withLock {
                    val startedAt = SystemClock.elapsedRealtime()
                    val result = workspaceRepository.verifyDatabase(databaseId)
                    result to (SystemClock.elapsedRealtime() - startedAt)
                }
                _verificationStates.update { current ->
                    current + (
                        databaseId to if (verifyResult.isSuccess) {
                            VerificationState.Verified(
                                entryCount = verifyResult.getOrDefault(0),
                                decryptTimeMs = elapsedMs
                            )
                        } else {
                            VerificationState.Failed(verifyResult.exceptionOrNull()?.message ?: strings.get(R.string.keepass_operation_verification_failed))
                        }
                    )
                }
                if (verifyResult.isSuccess) {
                    Log.d(TAG, "KeePass verify success db=$databaseId elapsed=${elapsedMs}ms")
                } else {
                    Log.w(TAG, "KeePass verify failed db=$databaseId elapsed=${elapsedMs}ms")
                }
            } finally {
                synchronized(verificationJobs) {
                    verificationJobs.remove(databaseId)
                }
            }
        }
        synchronized(verificationJobs) {
            verificationJobs[databaseId] = job
        }
        job.start()
    }

    fun uriPermissionState(database: LocalKeePassDatabase): KeePassUriPermissionState {
        if (database.resolvedActiveStorageLocation() == KeePassStorageLocation.INTERNAL) {
            return KeePassUriPermissionState.READ_WRITE
        }
        return runCatching {
            context.contentResolver.keePassUriPermissionState(Uri.parse(database.resolvedActiveFilePath()))
        }.getOrDefault(KeePassUriPermissionState.MISSING)
    }

    fun refreshUriPermissionState(databaseId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val database = dao.getDatabaseById(databaseId) ?: return@launch
            val state = uriPermissionState(database)
            _uriPermissionStates.update { current -> current + (databaseId to state) }
        }
    }

    /** Re-select and validate an external KDBX, restoring its persistent read/write grant. */
    fun reauthorizeExternalDatabase(databaseId: Long, uri: Uri, onSuccess: (() -> Unit)? = null) {
        viewModelScope.launch {
            _operationState.value = OperationState.Loading(strings.get(R.string.keepass_operation_check_permissions))
            try {
                withContext(Dispatchers.IO) {
                    val database = dao.getDatabaseById(databaseId)
                        ?: throw IllegalArgumentException(strings.get(R.string.keepass_connection_status_missing))
                    if (database.resolvedActiveStorageLocation() == KeePassStorageLocation.INTERNAL) {
                        throw IllegalArgumentException(strings.get(R.string.keepass_operation_internal_no_permission_needed))
                    }
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    if (context.contentResolver.keePassUriPermissionState(uri) != KeePassUriPermissionState.READ_WRITE) {
                        throw SecurityException(strings.get(R.string.keepass_operation_write_permission_missing))
                    }
                    val password = database.encryptedPassword?.let { securityManager.decryptData(it) } ?: ""
                    workspaceRepository.inspectExternalDatabase(
                        fileUri = uri,
                        password = password,
                        keyFileUri = database.keyFileUri?.let(Uri::parse)
                    ).getOrThrow()
                    dao.updateDatabase(
                        database.copy(
                            filePath = uri.toString(),
                            storageLocation = KeePassStorageLocation.EXTERNAL,
                            sourceType = KeePassDatabaseSourceType.LOCAL_DOCUMENT_URI,
                            workingCopyPath = null,
                            cacheCopyPath = null,
                            isOfflineAvailable = false,
                            lastAccessedAt = System.currentTimeMillis()
                        )
                    )
                    _uriPermissionStates.update { current ->
                        current + (databaseId to KeePassUriPermissionState.READ_WRITE)
                    }
                }
                _operationState.value = OperationState.Success(strings.get(R.string.keepass_permission_repair_success))
                Toast.makeText(
                    context,
                    context.getString(takagi.ru.monica.R.string.keepass_permission_repair_success),
                    Toast.LENGTH_SHORT
                ).show()
                onSuccess?.invoke()
            } catch (error: Throwable) {
                _operationState.value = OperationState.Error(strings.get(R.string.keepass_operation_permissions_failed, formatOperationError(error)))
                refreshUriPermissionState(databaseId)
            }
        }
    }

    fun reverifyDatabasePassword(databaseId: Long, password: String, keyFileUri: Uri? = null) {
        viewModelScope.launch {
            _operationState.value = OperationState.Loading(strings.get(R.string.keepass_operation_verify_password))
            _verificationStates.update { current ->
                current + (databaseId to VerificationState.Verifying)
            }
            try {
                var verifyElapsedMs = 0L
                withContext(Dispatchers.IO) {
                    val database = dao.getDatabaseById(databaseId) ?: throw Exception(strings.get(R.string.keepass_connection_status_missing))
                    val passwordToUse = if (password.isNotBlank()) {
                        password
                    } else {
                        database.encryptedPassword?.let { securityManager.decryptData(it) } ?: ""
                    }

                    // Read the newly selected key before verification so that we can
                    // compare its content (rather than only its URI) with the
                    // optional Monica-private copy.  The private copy must never
                    // remain the preferred credential after the user selects a
                    // different key file.
                    val selectedKeyFileFingerprint = keyFileUri?.let { uri ->
                        context.contentResolver.readKeePassKeyFileBytes(
                            uri = uri,
                            unavailableMessage = context.getString(
                                takagi.ru.monica.R.string.local_keepass_key_file_unavailable_error
                            ),
                        ).let(KeePassKeyFileStore::fingerprint)
                    }
                    val verifyStart = SystemClock.elapsedRealtime()
                    val verifyResult = workspaceRepository.inspectDatabase(
                        databaseId = databaseId,
                        passwordOverride = passwordToUse,
                        keyFileUriOverride = keyFileUri
                    )
                    verifyElapsedMs = SystemClock.elapsedRealtime() - verifyStart
                    val diagnostics = verifyResult.getOrElse { throw it }
                    val count = diagnostics.entryCount
                    val options = diagnostics.creationOptions
                    val encryptedPassword = securityManager.encryptData(passwordToUse)
                    if (keyFileUri != null) {
                        context.contentResolver.persistKeePassKeyFileReadPermission(keyFileUri)
                    }

                    val previousInternalPath = database.keyFileInternalPath
                        ?.takeIf { it.isNotBlank() }
                    val previousInternalFingerprint = previousInternalPath?.let { relativePath ->
                        // Verify the bytes on disk instead of trusting the cached
                        // metadata.  A damaged or missing copy must be detached
                        // when a replacement key is selected.
                        runCatching {
                            KeePassKeyFileStore.fingerprint(keyFileStore.readInternal(relativePath))
                        }.getOrNull()
                    }
                    val shouldClearPreviousInternalCopy = keyFileUri != null &&
                        previousInternalPath != null &&
                        !selectedKeyFileFingerprint.equals(
                            previousInternalFingerprint,
                            ignoreCase = true,
                        )
                    val keyFileName = keyFileUri?.lastPathSegment
                        ?.substringAfterLast('/')
                        ?.takeIf { it.isNotBlank() }
                    val updatedInternalPath = if (shouldClearPreviousInternalCopy) {
                        null
                    } else {
                        database.keyFileInternalPath
                    }
                    val updatedKeyFileFingerprint = if (keyFileUri != null) {
                        selectedKeyFileFingerprint
                    } else {
                        database.keyFileFingerprint
                    }
                    dao.updateDatabase(
                        database.copy(
                            encryptedPassword = encryptedPassword,
                            keyFileUri = keyFileUri?.toString() ?: database.keyFileUri,
                            keyFileInternalPath = updatedInternalPath,
                            keyFileName = if (keyFileUri != null) keyFileName else database.keyFileName,
                            keyFileFingerprint = updatedKeyFileFingerprint,
                            entryCount = count,
                            kdbxMajorVersion = options.formatVersion.majorVersion,
                            cipherAlgorithm = options.cipherAlgorithm.name,
                            kdfAlgorithm = options.kdfAlgorithm.name,
                            kdfTransformRounds = options.transformRounds,
                            kdfMemoryBytes = options.memoryBytes,
                            kdfParallelism = options.parallelism,
                            lastAccessedAt = System.currentTimeMillis()
                        )
                    )
                    KeePassKdbxService.invalidateProcessCache(databaseId)

                    // The database now points at the newly selected external URI
                    // (or at the existing internal copy when the key was not
                    // changed).  Clean up the superseded private copy only when
                    // no other database record references it.  Cleanup failure
                    // must not invalidate an otherwise successful credential
                    // update; it is safe to leave an orphaned encrypted file.
                    if (shouldClearPreviousInternalCopy) {
                        val obsoleteInternalPath = checkNotNull(previousInternalPath)
                        val stillReferenced = dao.getAllDatabasesSync().any { other ->
                            other.id != databaseId &&
                                other.keyFileInternalPath == obsoleteInternalPath
                        }
                        if (!stillReferenced) {
                            runCatching { keyFileStore.deleteInternal(obsoleteInternalPath) }
                                .onSuccess { deleted ->
                                    if (!deleted) {
                                        Log.w(
                                            TAG,
                                            "Superseded KeePass key-file copy could not be removed",
                                        )
                                    }
                                }
                                .onFailure { error ->
                                    Log.w(
                                        TAG,
                                        "Unable to remove superseded KeePass key-file copy",
                                        error,
                                    )
                                }
                        }
                    }
                    _verificationStates.update { current ->
                        current + (
                            databaseId to VerificationState.Verified(
                                entryCount = count,
                                decryptTimeMs = verifyElapsedMs
                            )
                        )
                    }
                }
                _operationState.value = OperationState.Success(strings.get(R.string.keepass_operation_password_verified, verifyElapsedMs))
                refreshKeyFileAccessState(databaseId)
            } catch (e: Exception) {
                _verificationStates.update { current ->
                    current + (databaseId to VerificationState.Failed(e.message ?: strings.get(R.string.keepass_operation_verification_failed)))
                }
                _operationState.value = OperationState.Error(strings.get(R.string.keepass_operation_verification_detail, formatOperationError(e)))
                refreshKeyFileAccessState(databaseId)
            }
        }
    }

    fun createGroup(
        databaseId: Long,
        groupName: String,
        parentPath: String? = null,
        onResult: (Result<KeePassGroupInfo>) -> Unit = {}
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = workspaceRepository.createGroup(
                databaseId = databaseId,
                groupName = groupName,
                parentPath = parentPath
            )
            if (result.isSuccess) {
                refreshGroups(databaseId)
                val databaseName = dao.getDatabaseById(databaseId)?.name ?: "KeePass DB #$databaseId"
                result.getOrNull()?.let { groupInfo ->
                    logKeepassGroupCreate(
                        databaseId = databaseId,
                        databaseName = databaseName,
                        group = groupInfo,
                        parentPath = parentPath
                    )
                }
            }
            withContext(Dispatchers.Main) {
                onResult(result)
            }
        }
    }

    suspend fun ensureGroupPathAwait(
        databaseId: Long,
        parentPath: String?,
        segments: List<String>
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            var currentParent = parentPath?.takeIf { it.isNotBlank() }
            val groups = workspaceRepository.listGroups(databaseId).getOrThrow().toMutableList()
            segments.map(String::trim).filter(String::isNotBlank).forEach { segment ->
                val expectedPath = takagi.ru.monica.utils.buildKeePassPathKey(currentParent, segment)
                val existing = groups.firstOrNull { group ->
                    group.path.equals(expectedPath, ignoreCase = true)
                }
                val resolved = existing ?: workspaceRepository.createGroup(
                    databaseId = databaseId,
                    groupName = segment,
                    parentPath = currentParent
                ).getOrThrow()
                if (existing == null) groups += resolved
                currentParent = resolved.path
            }
            currentParent.orEmpty()
        }.also { result ->
            if (result.isSuccess) refreshGroups(databaseId)
        }
    }

    fun renameGroup(
        databaseId: Long,
        groupPath: String,
        newName: String,
        onResult: (Result<KeePassGroupInfo>) -> Unit = {}
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = workspaceRepository.renameGroup(
                databaseId = databaseId,
                groupPath = groupPath,
                newName = newName
            )
            if (result.isSuccess) {
                refreshGroups(databaseId)
                val databaseName = dao.getDatabaseById(databaseId)?.name ?: "KeePass DB #$databaseId"
                result.getOrNull()?.let { groupInfo ->
                    logKeepassGroupRename(
                        databaseId = databaseId,
                        databaseName = databaseName,
                        oldPath = groupPath,
                        newGroup = groupInfo
                    )
                }
            }
            withContext(Dispatchers.Main) {
                onResult(result)
            }
        }
    }

    fun deleteGroup(
        databaseId: Long,
        groupPath: String,
        onResult: (Result<Unit>) -> Unit = {}
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = workspaceRepository.deleteGroup(
                databaseId = databaseId,
                groupPath = groupPath
            )
            if (result.isSuccess) {
                refreshGroups(databaseId)
                val databaseName = dao.getDatabaseById(databaseId)?.name ?: "KeePass DB #$databaseId"
                logKeepassGroupDelete(
                    databaseId = databaseId,
                    databaseName = databaseName,
                    groupPath = groupPath
                )
            }
            withContext(Dispatchers.Main) {
                onResult(result)
            }
        }
    }

    fun moveGroup(
        sourceDatabaseId: Long,
        groupPath: String,
        targetDatabaseId: Long,
        targetParentPath: String? = null,
        onResult: (Result<KeePassGroupInfo>) -> Unit = {}
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = workspaceRepository.moveGroup(
                sourceDatabaseId = sourceDatabaseId,
                groupPath = groupPath,
                targetDatabaseId = targetDatabaseId,
                targetParentPath = targetParentPath
            )
            if (result.isSuccess) {
                refreshGroups(sourceDatabaseId)
                if (targetDatabaseId != sourceDatabaseId) {
                    refreshGroups(targetDatabaseId)
                }
                val sourceDatabaseName = dao.getDatabaseById(sourceDatabaseId)?.name ?: "KeePass DB #$sourceDatabaseId"
                val targetDatabaseName = dao.getDatabaseById(targetDatabaseId)?.name ?: "KeePass DB #$targetDatabaseId"
                result.getOrNull()?.let { groupInfo ->
                    logKeepassGroupMove(
                        sourceDatabaseId = sourceDatabaseId,
                        sourceDatabaseName = sourceDatabaseName,
                        sourcePath = groupPath,
                        targetDatabaseId = targetDatabaseId,
                        targetDatabaseName = targetDatabaseName,
                        movedGroup = groupInfo
                    )
                }
            }
            withContext(Dispatchers.Main) {
                onResult(result)
            }
        }
    }
    
    /**
     * 创建新的 KeePass 数据库
     */
    fun createDatabase(
        name: String,
        password: String,
        storageLocation: KeePassStorageLocation,
        externalUri: Uri? = null,
        keyFileUri: Uri? = null,
        creationOptions: KeePassDatabaseCreationOptions = KeePassDatabaseCreationOptions(),
        description: String? = null,
        keepKeyFileCopy: Boolean = false
    ) {
        viewModelScope.launch {
            _operationState.value = OperationState.Loading(strings.get(R.string.keepass_operation_creating))
            
            try {
                var createdDatabaseId: Long? = null
                var createLogDetails: List<FieldChange> = emptyList()
                withContext(Dispatchers.IO) {
                    val encryptedPassword = if (password.isNotBlank()) securityManager.encryptData(password) else null
                    
                    // 密钥文件只需要持久读取权限，部分提供器不会授予写权限。
                    val keyFileBytes = readKeyFileBytes(keyFileUri)
                    val storedKeyFile = keyFileUri?.takeIf { keepKeyFileCopy }?.let { uri ->
                        keyFileStore.copyFromUri(uri, uri.lastPathSegment)
                    }
                    
                    // 生成文件名
                    val fileName = "${name.replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fa5]"), "_")}.kdbx"
                    
                    val filePath: String
                    
                    if (storageLocation == KeePassStorageLocation.INTERNAL) {
                        // 创建内部存储目录
                        val keepassDir = File(context.filesDir, "keepass")
                        if (!keepassDir.exists()) {
                            keepassDir.mkdirs()
                        }
                        
                        // 创建空的 kdbx 文件（实际应该用 KeePass 库创建）
                        val dbFile = File(keepassDir, fileName)
                        createEmptyKdbxFile(
                            file = dbFile,
                            password = password,
                            keyFileBytes = keyFileBytes,
                            options = creationOptions,
                            databaseName = name
                        )
                        
                        filePath = "keepass/$fileName"
                    } else {
                        // 外部存储
                        if (externalUri == null) {
                            throw IllegalArgumentException(strings.get(R.string.keepass_operation_external_location_required))
                        }
                        
                        // 使用 DocumentFile 创建文件
                        val docFile = DocumentFile.fromTreeUri(context, externalUri)
                        val newFile = docFile?.createFile("application/octet-stream", fileName)
                        
                        if (newFile?.uri != null) {
                            context.contentResolver.openOutputStream(newFile.uri)?.use { output ->
                                createEmptyKdbxContent(
                                    password = password,
                                    keyFileBytes = keyFileBytes,
                                    options = creationOptions,
                                    databaseName = name
                                ).let { content ->
                                    output.write(content)
                                }
                            }
                            filePath = newFile.uri.toString()
                        } else {
                            throw Exception(strings.get(R.string.keepass_operation_create_file_failed))
                        }
                    }

                    val normalizedOptions = creationOptions.normalized()
                    // 保存数据库信息
                    val database = LocalKeePassDatabase(
                        name = name,
                        filePath = filePath,
                        keyFileUri = keyFileUri?.toString(),
                        keyFileInternalPath = storedKeyFile?.relativePath,
                        keyFileName = storedKeyFile?.fileName
                            ?: keyFileUri?.lastPathSegment?.substringAfterLast('/'),
                        keyFileFingerprint = storedKeyFile?.fingerprint,
                        storageLocation = storageLocation,
                        encryptedPassword = encryptedPassword,
                        description = description,
                        isDefault = allDatabases.value.isEmpty(),
                        kdbxMajorVersion = normalizedOptions.formatVersion.majorVersion,
                        cipherAlgorithm = normalizedOptions.cipherAlgorithm.name,
                        kdfAlgorithm = normalizedOptions.kdfAlgorithm.name,
                        kdfTransformRounds = normalizedOptions.transformRounds,
                        kdfMemoryBytes = normalizedOptions.memoryBytes,
                        kdfParallelism = normalizedOptions.parallelism
                    )
                    
                    createdDatabaseId = dao.insertDatabase(database)
                    createLogDetails = listOf(
                        FieldChange("存储位置", "", storageLocationLabel(storageLocation)),
                        FieldChange("格式版本", "", normalizedOptions.formatVersion.name),
                        FieldChange("加密算法", "", normalizedOptions.cipherAlgorithm.name),
                        FieldChange("KDF", "", normalizedOptions.kdfAlgorithm.name)
                    )
                }
                
                _operationState.value = OperationState.Success(strings.get(R.string.keepass_operation_created))
                createdDatabaseId?.let { databaseId ->
                    logKeepassDatabaseCreate(
                        databaseId = databaseId,
                        databaseName = name,
                        details = createLogDetails
                    )
                }
            } catch (e: Exception) {
                _operationState.value = OperationState.Error(strings.get(R.string.keepass_webdav_create_failed, formatOperationError(e)))
            }
        }
    }
    
    /**
     * 生成新的密钥文件 (XML 格式)
     */
    fun generateKeyFile(uri: Uri) {
        viewModelScope.launch {
            _operationState.value = OperationState.Loading(strings.get(R.string.keepass_operation_generate_key))
            
            try {
                withContext(Dispatchers.IO) {
                    // 1. 生成 32 字节随机数据
                    val randomBytes = ByteArray(32)
                    java.security.SecureRandom().nextBytes(randomBytes)
                    
                    // 2. Base64 编码
                    val base64Key = android.util.Base64.encodeToString(randomBytes, android.util.Base64.NO_WRAP)
                    
                    // 3. 构建 XML 内容 (KeePass 2.x 格式)
                    val xmlContent = """
                        <?xml version="1.0" encoding="utf-8"?>
                        <KeyFile>
                        	<Meta>
                        		<Version>1.00</Version>
                        	</Meta>
                        	<Key>
                        		<Data>$base64Key</Data>
                        	</Key>
                        </KeyFile>
                    """.trimIndent()
                    
                    // 4. 写入文件
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(xmlContent.toByteArray())
                    } ?: throw Exception(strings.get(R.string.keepass_operation_write_failed))
                    
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    }
                }
                
                _operationState.value = OperationState.Success(strings.get(R.string.keepass_operation_key_generated))
            } catch (e: Exception) {
                _operationState.value = OperationState.Error(strings.get(R.string.keepass_operation_key_generate_failed, formatOperationError(e)))
            }
        }
    }

    /**
     * 导入外部 KeePass 数据库（添加引用，不复制文件）
     */
    fun importExternalDatabase(
        name: String,
        uri: Uri,
        password: String,
        keyFileUri: Uri? = null,
        description: String? = null,
        keepKeyFileCopy: Boolean = false
    ) {
        viewModelScope.launch {
            _operationState.value = OperationState.Loading(strings.get(R.string.keepass_operation_adding))
            
            try {
                var verifyElapsedMs = 0L
                var importLogAction: String? = null
                var importLogDatabaseId = 0L
                var importLogDatabaseName = name
                var importLogChanges: List<FieldChange> = emptyList()
                var importedDatabaseName = name
                withContext(Dispatchers.IO) {
                    importedDatabaseName = KeePassFileNameResolver.chooseImportedDatabaseName(
                        requestedName = name,
                        displayName = KeePassFileNameResolver.queryDisplayName(
                            context.contentResolver,
                            uri
                        ),
                        uriLastPathSegment = uri.lastPathSegment
                    )
                    importLogDatabaseName = importedDatabaseName

                    // 验证文件是否可访问
                    context.contentResolver.openInputStream(uri)?.close()
                        ?: throw Exception(strings.get(R.string.keepass_operation_file_unavailable))

                    val verifyStart = SystemClock.elapsedRealtime()
                    val verifyResult = workspaceRepository.inspectExternalDatabase(
                        fileUri = uri,
                        password = password,
                        keyFileUri = keyFileUri
                    )
                    verifyElapsedMs = SystemClock.elapsedRealtime() - verifyStart
                    val diagnostics = verifyResult.getOrElse { throw it }
                    val entryCount = diagnostics.entryCount
                    val options = diagnostics.creationOptions
                    
                    val encryptedPassword = if (password.isNotBlank()) securityManager.encryptData(password) else null
                    
                    // 获取持久化 URI 权限
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    }.onFailure { error ->
                        Log.w(TAG, "Persistable READ/WRITE permission not granted for imported DB", error)
                    }
                    
                    if (keyFileUri != null) {
                        runCatching {
                            context.contentResolver.takePersistableUriPermission(
                                keyFileUri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        }
                        context.contentResolver.openInputStream(keyFileUri)?.close()
                            ?: throw Exception(strings.get(R.string.local_keepass_key_file_access_unavailable))
                    }
                    val uriPath = uri.toString()
                    val existing = dao.getAllDatabasesSync().firstOrNull { it.filePath == uriPath }
                    val storedKeyFile = keyFileUri?.takeIf { keepKeyFileCopy }?.let { selectedUri ->
                        keyFileStore.copyFromUri(selectedUri, selectedUri.lastPathSegment)
                    }
                    if (existing != null) {
                        val keyFileChanged = keyFileUri != null &&
                            keyFileUri.toString() != existing.keyFileUri
                        val updated = existing.copy(
                            name = importedDatabaseName,
                            keyFileUri = keyFileUri?.toString() ?: existing.keyFileUri,
                            keyFileInternalPath = when {
                                storedKeyFile != null -> storedKeyFile.relativePath
                                keyFileChanged -> null
                                else -> existing.keyFileInternalPath
                            },
                            keyFileName = when {
                                storedKeyFile != null -> storedKeyFile.fileName
                                keyFileChanged -> keyFileUri?.lastPathSegment?.substringAfterLast('/')
                                else -> existing.keyFileName
                            },
                            keyFileFingerprint = when {
                                storedKeyFile != null -> storedKeyFile.fingerprint
                                keyFileChanged -> null
                                else -> existing.keyFileFingerprint
                            },
                            storageLocation = KeePassStorageLocation.EXTERNAL,
                            encryptedPassword = encryptedPassword,
                            description = description ?: existing.description,
                            entryCount = entryCount,
                            kdbxMajorVersion = options.formatVersion.majorVersion,
                            cipherAlgorithm = options.cipherAlgorithm.name,
                            kdfAlgorithm = options.kdfAlgorithm.name,
                            kdfTransformRounds = options.transformRounds,
                            kdfMemoryBytes = options.memoryBytes,
                            kdfParallelism = options.parallelism,
                            lastAccessedAt = System.currentTimeMillis()
                        )
                        dao.updateDatabase(updated)
                        KeePassKdbxService.invalidateProcessCache(existing.id)

                        importLogAction = "update"
                        importLogDatabaseId = updated.id
                        importLogDatabaseName = updated.name
                        importLogChanges = buildList {
                            if (existing.name != updated.name) {
                                add(FieldChange("名称", existing.name, updated.name))
                            }
                            if (existing.description.orEmpty() != updated.description.orEmpty()) {
                                add(FieldChange("描述", existing.description.orEmpty(), updated.description.orEmpty()))
                            }
                            if (existing.entryCount != updated.entryCount) {
                                add(FieldChange("条目数量", existing.entryCount.toString(), updated.entryCount.toString()))
                            }
                            if (existing.keyFileUri != updated.keyFileUri) {
                                add(
                                    FieldChange(
                                        "密钥文件",
                                        if (existing.keyFileUri.isNullOrBlank()) "未设置" else "已设置",
                                        if (updated.keyFileUri.isNullOrBlank()) "未设置" else "已设置"
                                    )
                                )
                            }
                        }
                    } else {
                        val database = LocalKeePassDatabase(
                            name = importedDatabaseName,
                            filePath = uriPath,
                            keyFileUri = keyFileUri?.toString(),
                            keyFileInternalPath = storedKeyFile?.relativePath,
                            keyFileName = storedKeyFile?.fileName
                                ?: keyFileUri?.lastPathSegment?.substringAfterLast('/'),
                            keyFileFingerprint = storedKeyFile?.fingerprint,
                            storageLocation = KeePassStorageLocation.EXTERNAL,
                            encryptedPassword = encryptedPassword,
                            description = description,
                            entryCount = entryCount,
                            kdbxMajorVersion = options.formatVersion.majorVersion,
                            cipherAlgorithm = options.cipherAlgorithm.name,
                            kdfAlgorithm = options.kdfAlgorithm.name,
                            kdfTransformRounds = options.transformRounds,
                            kdfMemoryBytes = options.memoryBytes,
                            kdfParallelism = options.parallelism,
                            isDefault = allDatabases.value.isEmpty()
                        )
                        val newId = dao.insertDatabase(database)
                        KeePassKdbxService.invalidateProcessCache(newId)

                        importLogAction = "create"
                        importLogDatabaseId = newId
                        importLogDatabaseName = database.name
                        importLogChanges = listOf(
                            FieldChange("来源", "", "外部导入"),
                            FieldChange("存储位置", "", storageLocationLabel(KeePassStorageLocation.EXTERNAL)),
                            FieldChange("条目数量", "", entryCount.toString())
                        )
                    }
                }
                
                _operationState.value = OperationState.Success(strings.get(R.string.keepass_operation_added, verifyElapsedMs))
                when (importLogAction) {
                    "create" -> {
                        logKeepassDatabaseCreate(
                            databaseId = importLogDatabaseId,
                            databaseName = importLogDatabaseName,
                            details = importLogChanges
                        )
                    }
                    "update" -> {
                        logKeepassDatabaseUpdate(
                            databaseId = importLogDatabaseId,
                            databaseName = importLogDatabaseName,
                            changes = importLogChanges.ifEmpty {
                                listOf(FieldChange("外部引用", "已存在", "已刷新"))
                            }
                        )
                    }
                }
            } catch (e: Exception) {
                _operationState.value = OperationState.Error(strings.get(R.string.keepass_operation_add_failed, formatOperationError(e)))
            }
        }
    }

    fun addWebDavDatabase(
        name: String,
        serverUrl: String,
        username: String,
        webDavPassword: String,
        remotePath: String,
        databasePassword: String,
        keyFileUri: Uri? = null,
        description: String? = null,
        keepKeyFileCopy: Boolean = false
    ) {
        viewModelScope.launch {
            _operationState.value = OperationState.Loading(strings.get(R.string.keepass_operation_connecting, "WebDAV"))

            try {
                val attachResult = withContext(Dispatchers.IO) {
                    attachWebDavDatabaseBlocking(
                        name = name,
                        serverUrl = serverUrl,
                        username = username,
                        webDavPassword = webDavPassword,
                        remotePath = remotePath,
                        databasePassword = databasePassword,
                        keyFileUri = keyFileUri,
                        description = description,
                        keepKeyFileCopy = keepKeyFileCopy
                    )
                }

                _operationState.value = OperationState.Success(strings.get(R.string.keepass_operation_connected, "WebDAV"))
                logKeepassDatabaseCreate(
                    databaseId = attachResult.databaseId,
                    databaseName = attachResult.databaseName,
                    details = listOf(
                        FieldChange("来源", "", "WebDAV"),
                        FieldChange("打开方式", "", "工作副本"),
                        FieldChange("条目数量", "", attachResult.entryCount.toString())
                    )
                )
            } catch (e: Exception) {
                _operationState.value = OperationState.Error(strings.get(R.string.keepass_operation_connect_failed, formatOperationError(e)))
            }
        }
    }

    fun createWebDavDatabase(
        directoryPath: String?,
        name: String,
        serverUrl: String,
        username: String,
        webDavPassword: String,
        databasePassword: String,
        keyFileUri: Uri? = null,
        creationOptions: KeePassDatabaseCreationOptions = KeePassDatabaseCreationOptions(),
        description: String? = null,
        keepKeyFileCopy: Boolean = false
    ) {
        viewModelScope.launch {
            _operationState.value = OperationState.Loading(strings.get(R.string.keepass_operation_creating_remote, "WebDAV"))

            try {
                val attachResult = withContext(Dispatchers.IO) {
                    val normalizedDirectoryPath = WebDavKeePassFileSource.normalizeOptionalRemotePath(directoryPath)
                    val fileSource = buildWebDavFileSource(
                        serverUrl = serverUrl,
                        username = username,
                        webDavPassword = webDavPassword
                    )
                    fileSource.testConnection().getOrThrow()

                    val displayName = name.trim()
                        .removeSuffix(".kdbx")
                        .ifBlank { throw IllegalArgumentException(strings.get(R.string.keepass_operation_name_required)) }
                    val remoteFileName = if (name.trim().endsWith(".kdbx", ignoreCase = true)) {
                        name.trim()
                    } else {
                        "$displayName.kdbx"
                    }
                    val keyFileBytes = readKeyFileBytes(keyFileUri)
                    val bytes = createEmptyKdbxContent(
                        password = databasePassword,
                        keyFileBytes = keyFileBytes,
                        options = creationOptions,
                        databaseName = displayName
                    )
                    val createdFile = fileSource.createFileInDirectory(
                        parentPath = normalizedDirectoryPath,
                        name = remoteFileName,
                        bytes = bytes
                    )
                    attachWebDavDatabaseBlocking(
                        name = displayName,
                        serverUrl = serverUrl,
                        username = username,
                        webDavPassword = webDavPassword,
                        remotePath = createdFile.path,
                        databasePassword = databasePassword,
                        keyFileUri = keyFileUri,
                        description = description,
                        keepKeyFileCopy = keepKeyFileCopy
                    )
                }

                _operationState.value = OperationState.Success(strings.get(R.string.keepass_operation_remote_created, "WebDAV"))
                logKeepassDatabaseCreate(
                    databaseId = attachResult.databaseId,
                    databaseName = attachResult.databaseName,
                    details = listOf(
                        FieldChange("来源", "", "WebDAV"),
                        FieldChange("创建方式", "", "远端新建"),
                        FieldChange("条目数量", "", attachResult.entryCount.toString())
                    )
                )
            } catch (e: Exception) {
                _operationState.value = OperationState.Error(strings.get(R.string.keepass_webdav_create_failed, formatOperationError(e)))
            }
        }
    }

    fun addOneDriveDatabase(
        name: String,
        accountId: String,
        accountLabel: String,
        remotePath: String,
        databasePassword: String,
        keyFileUri: Uri? = null,
        description: String? = null,
        keepKeyFileCopy: Boolean = false
    ) {
        viewModelScope.launch {
            _operationState.value = OperationState.Loading(strings.get(R.string.keepass_operation_connecting, "OneDrive"))

            try {
                val attachResult = withContext(Dispatchers.IO) {
                    attachOneDriveDatabaseBlocking(
                        name = name,
                        accountId = accountId,
                        accountLabel = accountLabel,
                        remotePath = remotePath,
                        databasePassword = databasePassword,
                        keyFileUri = keyFileUri,
                        description = description,
                        keepKeyFileCopy = keepKeyFileCopy
                    )
                }

                _operationState.value = OperationState.Success(strings.get(R.string.keepass_operation_connected, "OneDrive"))
                logKeepassDatabaseCreate(
                    databaseId = attachResult.databaseId,
                    databaseName = attachResult.databaseName,
                    details = listOf(
                        FieldChange("来源", "", "OneDrive"),
                        FieldChange("打开方式", "", "工作副本"),
                        FieldChange("条目数量", "", attachResult.entryCount.toString())
                    )
                )
            } catch (e: Exception) {
                _operationState.value = OperationState.Error(strings.get(R.string.keepass_operation_connect_failed, formatOperationError(e)))
            }
        }
    }

    fun createOneDriveDatabase(
        directoryPath: String?,
        name: String,
        accountId: String,
        accountLabel: String,
        databasePassword: String,
        keyFileUri: Uri? = null,
        creationOptions: KeePassDatabaseCreationOptions = KeePassDatabaseCreationOptions(),
        description: String? = null,
        keepKeyFileCopy: Boolean = false
    ) {
        viewModelScope.launch {
            _operationState.value = OperationState.Loading(strings.get(R.string.keepass_operation_creating_remote, "OneDrive"))

            try {
                val attachResult = withContext(Dispatchers.IO) {
                    val normalizedDirectoryPath = OneDriveKeePassFileSource.normalizeOptionalRemotePath(directoryPath)
                    val fileSource = OneDriveKeePassFileSource(
                        context = context,
                        accountIdentifier = accountId
                    )
                    fileSource.testConnection().getOrThrow()

                    val displayName = name.trim()
                        .removeSuffix(".kdbx")
                        .ifBlank { throw IllegalArgumentException(strings.get(R.string.keepass_operation_name_required)) }
                    val remoteFileName = if (name.trim().endsWith(".kdbx", ignoreCase = true)) {
                        name.trim()
                    } else {
                        "$displayName.kdbx"
                    }
                    val keyFileBytes = readKeyFileBytes(keyFileUri)
                    val bytes = createEmptyKdbxContent(
                        password = databasePassword,
                        keyFileBytes = keyFileBytes,
                        options = creationOptions,
                        databaseName = displayName
                    )
                    val createdFile = fileSource.createFileInDirectory(
                        parentPath = normalizedDirectoryPath,
                        name = remoteFileName,
                        bytes = bytes
                    )
                    attachOneDriveDatabaseBlocking(
                        name = displayName,
                        accountId = accountId,
                        accountLabel = accountLabel,
                        remotePath = createdFile.path,
                        databasePassword = databasePassword,
                        keyFileUri = keyFileUri,
                        description = description,
                        keepKeyFileCopy = keepKeyFileCopy
                    )
                }

                _operationState.value = OperationState.Success(strings.get(R.string.keepass_operation_remote_created, "OneDrive"))
                logKeepassDatabaseCreate(
                    databaseId = attachResult.databaseId,
                    databaseName = attachResult.databaseName,
                    details = listOf(
                        FieldChange("来源", "", "OneDrive"),
                        FieldChange("创建方式", "", "远端新建"),
                        FieldChange("条目数量", "", attachResult.entryCount.toString())
                    )
                )
            } catch (e: Exception) {
                _operationState.value = OperationState.Error(strings.get(R.string.keepass_webdav_create_failed, formatOperationError(e)))
            }
        }
    }

    fun addGoogleDriveDatabase(
        name: String,
        accountId: String,
        accountLabel: String,
        remotePath: String,
        fileId: String,
        databasePassword: String,
        keyFileUri: Uri? = null,
        description: String? = null
    ) {
        viewModelScope.launch {
            _operationState.value = OperationState.Loading(strings.get(R.string.keepass_operation_connecting, "Google Drive"))

            try {
                val attachResult = withContext(Dispatchers.IO) {
                    attachGoogleDriveDatabaseBlocking(
                        name = name,
                        accountId = accountId,
                        accountLabel = accountLabel,
                        remotePath = remotePath,
                        fileId = fileId,
                        databasePassword = databasePassword,
                        keyFileUri = keyFileUri,
                        description = description
                    )
                }

                _operationState.value = OperationState.Success(strings.get(R.string.keepass_operation_connected, "Google Drive"))
                logKeepassDatabaseCreate(
                    databaseId = attachResult.databaseId,
                    databaseName = attachResult.databaseName,
                    details = listOf(
                        FieldChange("来源", "", "Google Drive"),
                        FieldChange("打开方式", "", "工作副本"),
                        FieldChange("条目数量", "", attachResult.entryCount.toString())
                    )
                )
            } catch (e: Exception) {
                _operationState.value = OperationState.Error(strings.get(R.string.keepass_operation_connect_failed, formatOperationError(e)))
            }
        }
    }

    fun createGoogleDriveDatabase(
        directoryPath: String?,
        folderId: String?,
        name: String,
        accountId: String,
        accountLabel: String,
        databasePassword: String,
        keyFileUri: Uri? = null,
        creationOptions: KeePassDatabaseCreationOptions = KeePassDatabaseCreationOptions(),
        description: String? = null
    ) {
        viewModelScope.launch {
            _operationState.value = OperationState.Loading(strings.get(R.string.keepass_operation_creating_remote, "Google Drive"))

            try {
                val attachResult = withContext(Dispatchers.IO) {
                    val normalizedDirectoryPath = GoogleDriveKeePassFileSource.normalizeOptionalRemotePath(directoryPath)
                    val fileSource = GoogleDriveKeePassFileSource(
                        context = context,
                        accountIdentifier = accountId
                    )
                    fileSource.testConnection().getOrThrow()

                    val displayName = name.trim()
                        .removeSuffix(".kdbx")
                        .ifBlank { throw IllegalArgumentException(strings.get(R.string.keepass_operation_name_required)) }
                    val remoteFileName = if (name.trim().endsWith(".kdbx", ignoreCase = true)) {
                        name.trim()
                    } else {
                        "$displayName.kdbx"
                    }
                    val keyFileBytes = readKeyFileBytes(keyFileUri)
                    val bytes = createEmptyKdbxContent(
                        password = databasePassword,
                        keyFileBytes = keyFileBytes,
                        options = creationOptions,
                        databaseName = displayName
                    )
                    val createdFile = fileSource.createFileInDirectory(
                        parentPath = normalizedDirectoryPath,
                        parentId = folderId,
                        name = remoteFileName,
                        bytes = bytes
                    )
                    attachGoogleDriveDatabaseBlocking(
                        name = displayName,
                        accountId = accountId,
                        accountLabel = accountLabel,
                        remotePath = createdFile.path,
                        fileId = createdFile.id ?: throw IllegalStateException(strings.get(R.string.keepass_operation_google_file_id_missing)),
                        databasePassword = databasePassword,
                        keyFileUri = keyFileUri,
                        description = description
                    )
                }

                _operationState.value = OperationState.Success(strings.get(R.string.keepass_operation_remote_created, "Google Drive"))
                logKeepassDatabaseCreate(
                    databaseId = attachResult.databaseId,
                    databaseName = attachResult.databaseName,
                    details = listOf(
                        FieldChange("来源", "", "Google Drive"),
                        FieldChange("创建方式", "", "远端新建"),
                        FieldChange("条目数量", "", attachResult.entryCount.toString())
                    )
                )
            } catch (e: Exception) {
                _operationState.value = OperationState.Error(strings.get(R.string.keepass_webdav_create_failed, formatOperationError(e)))
            }
        }
    }

    suspend fun testWebDavConnection(
        serverUrl: String,
        username: String,
        webDavPassword: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            buildWebDavFileSource(
                serverUrl = serverUrl,
                username = username,
                webDavPassword = webDavPassword
            ).testConnection().getOrThrow()
        }
    }

    suspend fun listWebDavDirectory(
        serverUrl: String,
        username: String,
        webDavPassword: String,
        currentPath: String?
    ): Result<WebDavDirectoryListing> = withContext(Dispatchers.IO) {
        runCatching {
            val normalizedPath = WebDavKeePassFileSource.normalizeOptionalRemotePath(currentPath)
            val entries = buildWebDavFileSource(
                serverUrl = serverUrl,
                username = username,
                webDavPassword = webDavPassword
            ).listDirectory(normalizedPath)
                .filter { it.isDirectory || it.name.endsWith(".kdbx", ignoreCase = true) }
            WebDavDirectoryListing(
                currentPath = normalizedPath,
                entries = entries
            )
        }
    }

    suspend fun listOneDriveDirectory(
        accountId: String,
        currentPath: String?
    ): Result<OneDriveDirectoryListing> = withContext(Dispatchers.IO) {
        runCatching {
            val normalizedPath = OneDriveKeePassFileSource.normalizeOptionalRemotePath(currentPath)
            val entries = OneDriveKeePassFileSource(
                context = context,
                accountIdentifier = accountId
            ).listDirectory(normalizedPath)
                .filter { it.isDirectory || it.name.endsWith(".kdbx", ignoreCase = true) }
            OneDriveDirectoryListing(
                currentPath = normalizedPath,
                entries = entries
            )
        }
    }

    suspend fun listGoogleDriveDirectory(
        accountId: String,
        currentPath: String?,
        currentFolderId: String?
    ): Result<GoogleDriveDirectoryListing> = withContext(Dispatchers.IO) {
        runCatching {
            val normalizedPath = GoogleDriveKeePassFileSource.normalizeOptionalRemotePath(currentPath)
            val entries = GoogleDriveKeePassFileSource(
                context = context,
                accountIdentifier = accountId
            ).listDirectory(
                directoryPath = normalizedPath,
                directoryId = currentFolderId
            ).filter { it.isDirectory || it.name.endsWith(".kdbx", ignoreCase = true) }
            GoogleDriveDirectoryListing(
                currentPath = normalizedPath,
                currentFolderId = currentFolderId,
                entries = entries
            )
        }
    }

    suspend fun createWebDavFolder(
        serverUrl: String,
        username: String,
        webDavPassword: String,
        currentPath: String?,
        folderName: String
    ): Result<WebDavDirectoryListing> = withContext(Dispatchers.IO) {
        runCatching {
            val normalizedPath = WebDavKeePassFileSource.normalizeOptionalRemotePath(currentPath)
            val fileSource = buildWebDavFileSource(
                serverUrl = serverUrl,
                username = username,
                webDavPassword = webDavPassword
            )
            fileSource.createDirectory(normalizedPath, folderName)
            val entries = fileSource.listDirectory(normalizedPath)
                .filter { it.isDirectory || it.name.endsWith(".kdbx", ignoreCase = true) }
            WebDavDirectoryListing(
                currentPath = normalizedPath,
                entries = entries
            )
        }
    }

    suspend fun createOneDriveFolder(
        accountId: String,
        currentPath: String?,
        folderName: String
    ): Result<OneDriveDirectoryListing> = withContext(Dispatchers.IO) {
        runCatching {
            val normalizedPath = OneDriveKeePassFileSource.normalizeOptionalRemotePath(currentPath)
            val fileSource = OneDriveKeePassFileSource(
                context = context,
                accountIdentifier = accountId
            )
            fileSource.createDirectory(normalizedPath, folderName)
            val entries = fileSource.listDirectory(normalizedPath)
                .filter { it.isDirectory || it.name.endsWith(".kdbx", ignoreCase = true) }
            OneDriveDirectoryListing(
                currentPath = normalizedPath,
                entries = entries
            )
        }
    }

    suspend fun createGoogleDriveFolder(
        accountId: String,
        currentPath: String?,
        currentFolderId: String?,
        folderName: String
    ): Result<GoogleDriveDirectoryListing> = withContext(Dispatchers.IO) {
        runCatching {
            val normalizedPath = GoogleDriveKeePassFileSource.normalizeOptionalRemotePath(currentPath)
            val fileSource = GoogleDriveKeePassFileSource(
                context = context,
                accountIdentifier = accountId
            )
            val createdFolder = fileSource.createDirectory(
                parentPath = normalizedPath,
                parentId = currentFolderId,
                name = folderName
            )
            val entries = fileSource.listDirectory(
                directoryPath = normalizedPath,
                directoryId = currentFolderId
            ).filter { it.isDirectory || it.name.endsWith(".kdbx", ignoreCase = true) }
            GoogleDriveDirectoryListing(
                currentPath = normalizedPath,
                currentFolderId = currentFolderId,
                entries = entries
            )
        }
    }

    fun syncRemoteDatabase(databaseId: Long, silent: Boolean = false) {
        val taskId = SyncDiagnostics.nextTaskId("kp-sync")
        val targetLog = "keepass:$databaseId"
        val triggerLog = if (silent) "REMOTE_SYNC_SILENT" else "REMOTE_SYNC_MANUAL"
        SyncDiagnostics.queued(taskId, targetLog, triggerLog, detail = "silent=$silent")
        viewModelScope.launch {
            if (!silent && openRemoteConflictIfNeeded(databaseId)) {
                SyncDiagnostics.skipped(taskId, targetLog, triggerLog, "conflict_review")
                return@launch
            }
            if (!silent) {
                clearVisibleRemoteAutoSyncFailure(databaseId)
            }
            if (!silent) {
                _operationState.value = OperationState.Loading(strings.get(R.string.keepass_operation_syncing))
            }

            val syncTarget = SyncTarget.KeePassDatabase(databaseId)
            val result = SyncTaskRunner.requestAndAwait(
                request = SyncRequest(
                    requestId = taskId,
                    target = syncTarget,
                    trigger = if (silent) SyncTrigger.RETRY else SyncTrigger.MANUAL,
                    createdAtMillis = System.currentTimeMillis(),
                    priority = if (silent) SyncPriority.REPAIR else SyncPriority.MANUAL,
                    mode = if (silent) SyncMode.SILENT else SyncMode.FOREGROUND,
                    dedupeKey = SyncKey(VISIBLE_REMOTE_AUTO_SYNC_DEDUPE_KEY),
                    throttleKey = syncTarget.stableKey,
                    networkPolicy = SyncNetworkPolicy.REQUIRED
                )
            ) {
                val startedAt = SyncDiagnostics.start(taskId, targetLog, triggerLog, detail = "silent=$silent")
                try {
                    val syncResult = withContext(Dispatchers.IO) {
                        syncRemoteDatabaseInternal(databaseId)
                    }
                    SyncDiagnostics.success(taskId, targetLog, triggerLog, startedAt)
                    syncResult
                } catch (error: Exception) {
                    withContext(Dispatchers.IO) {
                        handleSyncRemoteFailure(databaseId, error)
                    }
                    SyncDiagnostics.failed(taskId, targetLog, triggerLog, startedAt, error)
                    throw error
                }
            }

            when (result) {
                is SyncTaskAwaitResult.Completed -> {
                    val syncResult = result.value
                    clearVisibleRemoteAutoSyncFailure(databaseId)
                    if (!silent) {
                        _operationState.value = OperationState.Success(syncResult.message)
                        logKeepassDatabaseUpdate(
                            databaseId = databaseId,
                            databaseName = syncResult.databaseName,
                            changes = listOf(
                                FieldChange("远端同步", "待同步", syncResult.message)
                            )
                        )
                    } else {
                        Log.i(TAG, "Silent remote sync success: databaseId=$databaseId, message=${syncResult.message}")
                    }
                }
                is SyncTaskAwaitResult.Merged -> {
                    SyncDiagnostics.skipped(
                        taskId = taskId,
                        target = targetLog,
                        trigger = triggerLog,
                        reason = "merged",
                        detail = "running=${result.status.runningRequestId.orEmpty()}"
                    )
                    if (!silent) {
                        _operationState.value = OperationState.Success(strings.get(R.string.keepass_operation_sync_running))
                    }
                }
                is SyncTaskAwaitResult.Skipped -> {
                    SyncDiagnostics.skipped(taskId, targetLog, triggerLog, result.reason)
                    if (!silent) {
                        _operationState.value = OperationState.Success(strings.get(R.string.keepass_operation_sync_skipped, result.reason))
                    }
                }
                is SyncTaskAwaitResult.Blocked -> {
                    SyncDiagnostics.blocked(
                        taskId = taskId,
                        target = targetLog,
                        trigger = triggerLog,
                        reason = result.error.redactedMessage ?: result.error.kind.name
                    )
                    val message = result.error.redactedMessage ?: result.error.kind.name
                    if (!silent) {
                        _operationState.value = OperationState.Error(strings.get(R.string.legacy_ui_sync_failed_detail, message))
                    } else {
                        Log.w(TAG, "Silent remote sync blocked: databaseId=$databaseId, reason=$message")
                    }
                }
                is SyncTaskAwaitResult.Canceled -> {
                    val message = result.reason ?: "sync canceled"
                    SyncDiagnostics.skipped(taskId, targetLog, triggerLog, message)
                    if (!silent) {
                        _operationState.value = OperationState.Error(strings.get(R.string.legacy_ui_sync_failed_detail, message))
                    } else {
                        Log.w(TAG, "Silent remote sync canceled: databaseId=$databaseId, reason=$message")
                    }
                }
                is SyncTaskAwaitResult.Failed -> {
                    if (!silent) {
                        _operationState.value = OperationState.Error(strings.get(R.string.legacy_ui_sync_failed_detail, formatOperationError(result.error)))
                    } else {
                        Log.w(TAG, "Silent remote sync failed: databaseId=$databaseId, reason=${result.error.message}")
                    }
                }
            }
            if (!silent && (result is SyncTaskAwaitResult.Failed || result is SyncTaskAwaitResult.Blocked)) {
                openRemoteConflictIfNeeded(databaseId)
            }
        }
    }

    private suspend fun openRemoteConflictIfNeeded(databaseId: Long): Boolean {
        val database = dao.getDatabaseById(databaseId) ?: return false
        if (!database.isRemoteSource() || database.lastSyncStatus != KeePassSyncStatus.CONFLICT) return false
        _operationState.value = OperationState.Idle
        conflictResolution.open(database.id, database.name)
        return true
    }

    fun autoSyncVisibleRemoteDatabase(databaseId: Long) {
        val taskId = SyncDiagnostics.nextTaskId("kp-visible")
        val targetLog = "keepass:$databaseId"
        val triggerLog = "VISIBLE_REMOTE_AUTO_SYNC"
        SyncDiagnostics.queued(taskId, targetLog, triggerLog)
        viewModelScope.launch(Dispatchers.IO) {
            val database = dao.getDatabaseById(databaseId)
            if (database == null || !database.isRemoteSource()) {
                SyncDiagnostics.skipped(taskId, targetLog, triggerLog, "not_remote_or_missing")
                return@launch
            }
            if (database.lastSyncStatus == KeePassSyncStatus.CONFLICT) {
                SyncDiagnostics.blocked(taskId, targetLog, triggerLog, "conflict")
                return@launch
            }
            val now = System.currentTimeMillis()
            val failureGate = visibleRemoteAutoSyncFailureMutex.withLock {
                visibleRemoteAutoSyncFailures[databaseId]
            }
            if (failureGate != null) {
                val remainingMs = failureGate.nextAllowedAtMillis - now
                if (failureGate.count >= VISIBLE_REMOTE_AUTO_SYNC_FAILURE_MAX_ATTEMPTS) {
                    SyncDiagnostics.skipped(
                        taskId = taskId,
                        target = targetLog,
                        trigger = triggerLog,
                        reason = "failure_limit",
                        detail = "count=${failureGate.count} remainingMs=$remainingMs"
                    )
                    return@launch
                }
                if (remainingMs > 0L && database.lastSyncStatus == KeePassSyncStatus.FAILED) {
                    SyncDiagnostics.skipped(
                        taskId = taskId,
                        target = targetLog,
                        trigger = triggerLog,
                        reason = "failed_status_cooldown",
                        detail = "count=${failureGate.count} remainingMs=$remainingMs"
                    )
                    return@launch
                }
            }

            val syncTarget = SyncTarget.KeePassDatabase(databaseId)
            val shouldBypassThrottle = database.lastSyncStatus == KeePassSyncStatus.PENDING_UPLOAD ||
                database.lastSyncStatus == KeePassSyncStatus.REMOTE_CHANGED
            val throttleMs = if (shouldBypassThrottle) 0L else VISIBLE_REMOTE_AUTO_SYNC_THROTTLE_MS
            val result = SyncTaskRunner.request(
                request = SyncRequest(
                    requestId = taskId,
                    target = syncTarget,
                    trigger = SyncTrigger.PAGE_VISIBLE,
                    createdAtMillis = System.currentTimeMillis(),
                    priority = SyncPriority.PAGE_VISIBLE,
                    mode = SyncMode.SILENT,
                    dedupeKey = SyncKey(VISIBLE_REMOTE_AUTO_SYNC_DEDUPE_KEY),
                    throttleKey = syncTarget.stableKey,
                    networkPolicy = SyncNetworkPolicy.REQUIRED,
                    throttleMs = throttleMs
                )
            ) {
                val startedAt = SyncDiagnostics.start(
                    taskId = taskId,
                    target = targetLog,
                    trigger = triggerLog,
                    detail = "status=${database.lastSyncStatus} throttleMs=$throttleMs"
                )
                try {
                    val latestDatabase = dao.getDatabaseById(databaseId)
                    if (latestDatabase == null || !latestDatabase.isRemoteSource()) {
                        SyncDiagnostics.skipped(taskId, targetLog, triggerLog, "not_remote_or_missing", startedAt)
                        return@request
                    }
                    if (latestDatabase.lastSyncStatus == KeePassSyncStatus.CONFLICT) {
                        SyncDiagnostics.blocked(taskId, targetLog, triggerLog, "conflict", startedAt)
                        throw IllegalStateException("KeePass remote sync blocked by conflict")
                    }
                    withContext(Dispatchers.IO) {
                        syncRemoteDatabaseInternal(databaseId)
                    }
                    clearVisibleRemoteAutoSyncFailure(databaseId)
                    SyncDiagnostics.success(taskId, targetLog, triggerLog, startedAt)
                } catch (error: Exception) {
                    handleSyncRemoteFailure(databaseId, error)
                    recordVisibleRemoteAutoSyncFailure(databaseId)
                    Log.w(TAG, "Visible KeePass remote auto-sync failed: databaseId=$databaseId", error)
                    SyncDiagnostics.failed(taskId, targetLog, triggerLog, startedAt, error)
                    throw error
                }
            }
            when (result) {
                is SyncEnqueueResult.Accepted -> Unit
                is SyncEnqueueResult.Merged -> {
                    SyncDiagnostics.skipped(
                        taskId = taskId,
                        target = targetLog,
                        trigger = triggerLog,
                        reason = "merged",
                        detail = "running=${result.existingStatus.runningRequestId.orEmpty()}"
                    )
                }
                is SyncEnqueueResult.Skipped -> {
                    SyncDiagnostics.skipped(taskId, targetLog, triggerLog, result.reason)
                }
                is SyncEnqueueResult.Blocked -> {
                    SyncDiagnostics.blocked(
                        taskId = taskId,
                        target = targetLog,
                        trigger = triggerLog,
                        reason = result.error.kind.name.lowercase()
                    )
                }
            }
        }
    }

    private suspend fun clearVisibleRemoteAutoSyncFailure(databaseId: Long) {
        visibleRemoteAutoSyncFailureMutex.withLock {
            visibleRemoteAutoSyncFailures.remove(databaseId)
        }
    }

    private suspend fun recordVisibleRemoteAutoSyncFailure(databaseId: Long) {
        val nextAllowedAtMillis = System.currentTimeMillis() + VISIBLE_REMOTE_AUTO_SYNC_FAILURE_COOLDOWN_MS
        visibleRemoteAutoSyncFailureMutex.withLock {
            val previous = visibleRemoteAutoSyncFailures[databaseId]
            visibleRemoteAutoSyncFailures[databaseId] = VisibleRemoteAutoSyncFailure(
                count = ((previous?.count ?: 0) + 1).coerceAtMost(Int.MAX_VALUE),
                nextAllowedAtMillis = nextAllowedAtMillis
            )
        }
    }

    private suspend fun syncRemoteDatabaseInternal(databaseId: Long): RemoteSyncResult {
        val result = kdbxService.syncRemoteDatabase(databaseId).getOrElse { throw it }
        return RemoteSyncResult(result.databaseName, result.message)
    }
    private suspend fun handleSyncRemoteFailure(databaseId: Long, error: Exception) {
        val database = dao.getDatabaseById(databaseId)
        if (database != null && database.isRemoteSource()) {
            val workingHash = database.workingCopyPath
                ?.let { path -> File(context.filesDir, path) }
                ?.takeIf { it.exists() }
                ?.readBytes()
                ?.let(GoogleDriveKeePassSupport::sha256Hex)
            val syncState = appDatabase.keepassRemoteSyncStateDao().getState(databaseId)
            val hasLocalChanges = syncState?.hasLocalChanges == true ||
                database.lastSyncStatus == KeePassSyncStatus.PENDING_UPLOAD ||
                (workingHash != null && syncState?.baseHash != null && syncState.baseHash != workingHash)
            if (hasLocalChanges && workingHash != null && database.lastSyncStatus != KeePassSyncStatus.CONFLICT) {
                remoteSyncService.markLocalChanges(databaseId, workingHash)
            }
            if (database.lastSyncStatus != KeePassSyncStatus.CONFLICT) {
                remoteSyncService.markSyncFailure(
                    databaseId = databaseId,
                    failureCode = when (database.sourceType) {
                        KeePassDatabaseSourceType.REMOTE_GOOGLE_DRIVE -> "GDRIVE_MANUAL_SYNC_FAILED"
                        KeePassDatabaseSourceType.REMOTE_ONEDRIVE -> "ONEDRIVE_MANUAL_SYNC_FAILED"
                        else -> "WEBDAV_MANUAL_SYNC_FAILED"
                    },
                    failureMessage = error.message ?: strings.get(R.string.keepass_operation_remote_sync_failed)
                )
            }
        }
    }
    
    /**
     * 复制外部数据库到内部存储
     */
    fun copyToInternal(databaseId: Long) {
        viewModelScope.launch {
            _operationState.value = OperationState.Loading(strings.get(R.string.keepass_operation_copying_internal))
            
            try {
                var copiedDatabaseId: Long? = null
                var copiedDatabaseName = ""
                var sourceDatabaseName = ""
                withContext(Dispatchers.IO) {
                    val database = dao.getDatabaseById(databaseId)
                        ?: throw Exception(strings.get(R.string.keepass_connection_status_missing))
                    sourceDatabaseName = database.name
                    
                    if (database.storageLocation == KeePassStorageLocation.INTERNAL) {
                        throw Exception(strings.get(R.string.keepass_operation_already_internal))
                    }
                    
                    val externalUri = Uri.parse(database.filePath)
                    
                    // 创建内部目录
                    val keepassDir = File(context.filesDir, "keepass")
                    if (!keepassDir.exists()) {
                        keepassDir.mkdirs()
                    }
                    
                    // 复制文件
                    val fileName = "${database.name.replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fa5]"), "_")}.kdbx"
                    val internalFile = File(keepassDir, fileName)
                    
                    context.contentResolver.openInputStream(externalUri)?.use { input ->
                        FileOutputStream(internalFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    
                    // 创建新的内部数据库记录
                    val newDatabase = database.copy(
                        id = 0,
                        name = strings.get(R.string.keepass_operation_internal_name, database.name),
                        filePath = "keepass/$fileName",
                        storageLocation = KeePassStorageLocation.INTERNAL,
                        createdAt = System.currentTimeMillis()
                    )
                    
                    copiedDatabaseId = dao.insertDatabase(newDatabase)
                    copiedDatabaseName = newDatabase.name
                }
                
                _operationState.value = OperationState.Success(strings.get(R.string.keepass_operation_copied_internal))
                copiedDatabaseId?.let { newDatabaseId ->
                    logKeepassDatabaseCreate(
                        databaseId = newDatabaseId,
                        databaseName = copiedDatabaseName,
                        details = listOf(
                            FieldChange("来源", "", sourceDatabaseName),
                            FieldChange("存储位置", "", storageLocationLabel(KeePassStorageLocation.INTERNAL))
                        )
                    )
                }
            } catch (e: Exception) {
                _operationState.value = OperationState.Error(strings.get(R.string.keepass_operation_copy_failed, formatOperationError(e)))
            }
        }
    }
    
    /**
     * 导出内部数据库到外部存储
     */
    fun exportToExternal(databaseId: Long, destinationUri: Uri) {
        viewModelScope.launch {
            _operationState.value = OperationState.Loading(strings.get(R.string.exporting))
            
            try {
                withContext(Dispatchers.IO) {
                    val database = dao.getDatabaseById(databaseId)
                        ?: throw Exception(strings.get(R.string.keepass_connection_status_missing))
                    
                    if (database.storageLocation != KeePassStorageLocation.INTERNAL) {
                        throw Exception(strings.get(R.string.keepass_operation_export_internal_only))
                    }
                    
                    val internalFile = File(context.filesDir, database.filePath)
                    if (!internalFile.exists()) {
                        throw Exception(strings.get(R.string.keepass_operation_database_file_missing))
                    }
                    
                    // 导出到目标位置
                    val output = context.contentResolver.openOutputStream(destinationUri)
                        ?: throw IOException(strings.get(R.string.keepass_operation_target_unavailable))
                    output.use { outputStream ->
                        internalFile.inputStream().use { input ->
                            input.copyTo(outputStream)
                        }
                    }
                }
                
                _operationState.value = OperationState.Success(strings.get(R.string.export_data_success))
            } catch (e: Exception) {
                _operationState.value = OperationState.Error(strings.get(R.string.keepass_operation_export_failed, formatOperationError(e)))
            }
        }
    }
    
    /**
     * 转移数据库位置（内部 <-> 外部）
     * 与导入/导出不同，这会改变数据库的实际存储位置
     */
    fun transferDatabase(
        databaseId: Long,
        targetLocation: KeePassStorageLocation,
        targetUri: Uri? = null
    ) {
        viewModelScope.launch {
            _operationState.value = OperationState.Loading(
                if (targetLocation == KeePassStorageLocation.EXTERNAL) 
                    strings.get(R.string.keepass_operation_moving_external)
                else 
                    strings.get(R.string.keepass_operation_moving_internal)
            )
            
            try {
                var transferDatabaseName = ""
                var transferChanges: List<FieldChange> = emptyList()
                withContext(Dispatchers.IO) {
                    val database = dao.getDatabaseById(databaseId)
                        ?: throw Exception(strings.get(R.string.keepass_connection_status_missing))
                    transferDatabaseName = database.name
                    
                    if (database.storageLocation == targetLocation) {
                        throw Exception(strings.get(R.string.keepass_operation_already_at_destination))
                    }
                    
                    val newPath: String
                    
                    if (targetLocation == KeePassStorageLocation.EXTERNAL) {
                        // 内部 -> 外部
                        if (targetUri == null) {
                            throw Exception(strings.get(R.string.keepass_operation_destination_required))
                        }
                        
                        val internalFile = File(context.filesDir, database.filePath)
                        if (!internalFile.exists()) {
                            throw Exception(strings.get(R.string.keepass_operation_source_missing))
                        }
                        
                        // 复制到外部
                        context.contentResolver.openOutputStream(targetUri)?.use { output ->
                            internalFile.inputStream().use { input ->
                                input.copyTo(output)
                            }
                        }
                        
                        // 获取持久化权限
                        context.contentResolver.takePersistableUriPermission(
                            targetUri,
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                        
                        // 删除内部文件
                        internalFile.delete()
                        
                        newPath = targetUri.toString()
                    } else {
                        // 外部 -> 内部
                        val externalUri = Uri.parse(database.filePath)
                        
                        // 创建内部目录
                        val keepassDir = File(context.filesDir, "keepass")
                        if (!keepassDir.exists()) {
                            keepassDir.mkdirs()
                        }
                        
                        val fileName = "${database.name.replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fa5]"), "_")}.kdbx"
                        val internalFile = File(keepassDir, fileName)
                        
                        // 复制到内部
                        context.contentResolver.openInputStream(externalUri)?.use { input ->
                            FileOutputStream(internalFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        
                        newPath = "keepass/$fileName"
                    }
                    
                    // 更新数据库记录
                    dao.updateStorageLocation(
                        databaseId,
                        targetLocation,
                        targetLocation.toSourceType(),
                        newPath
                    )
                    transferChanges = listOf(
                        FieldChange(
                            "存储位置",
                            storageLocationLabel(database.storageLocation),
                            storageLocationLabel(targetLocation)
                        ),
                        FieldChange(
                            "存储路径",
                            storagePathLabel(database.filePath),
                            storagePathLabel(newPath)
                        )
                    )
                }
                
                _operationState.value = OperationState.Success(strings.get(R.string.keepass_operation_moved))
                logKeepassDatabaseUpdate(
                    databaseId = databaseId,
                    databaseName = transferDatabaseName,
                    changes = transferChanges
                )
            } catch (e: Exception) {
                _operationState.value = OperationState.Error(strings.get(R.string.keepass_operation_move_failed, formatOperationError(e)))
            }
        }
    }
    
    /**
     * 删除数据库
     */
    fun deleteDatabase(databaseId: Long, deleteFile: Boolean = false) {
        viewModelScope.launch {
            _operationState.value = OperationState.Loading(strings.get(R.string.keepass_operation_deleting))
            
            try {
                var deletedDatabaseName = ""
                withContext(Dispatchers.IO) {
                    val database = dao.getDatabaseById(databaseId)
                        ?: throw Exception(strings.get(R.string.keepass_connection_status_missing))
                    deletedDatabaseName = database.name
                    
                    if (deleteFile) {
                        if (!database.isRemoteSource() && database.storageLocation == KeePassStorageLocation.INTERNAL) {
                            val file = File(context.filesDir, database.filePath)
                            if (file.exists()) {
                                file.delete()
                            }
                        }
                        // 外部文件不删除，只移除引用
                    }

                    appDatabase.passwordEntryDao().deleteByKeePassDatabaseId(databaseId)
                    appDatabase.secureItemDao().deleteByKeePassDatabaseId(databaseId)
                    appDatabase.passkeyDao().deleteByKeePassDatabaseId(databaseId)
                    appDatabase.keepassGroupSyncConfigDao().deleteByDatabaseId(databaseId)
                    if (database.sourceType == KeePassDatabaseSourceType.REMOTE_WEBDAV) {
                        cleanupRemoteLocalCopies(database.workingCopyPath, database.cacheCopyPath)
                        appDatabase.keepassRemoteSyncStateDao().deleteState(databaseId)
                        database.sourceId?.let { sourceId ->
                            appDatabase.keepassRemoteSourceDao().deleteSourceById(sourceId)
                        }
                    }
                    KeePassKdbxService.invalidateProcessCache(databaseId)
                    
                    dao.deleteDatabaseById(databaseId)
                    database.keyFileInternalPath?.takeIf { it.isNotBlank() }?.let { relativePath ->
                        val stillReferenced = dao.getAllDatabasesSync().any { other ->
                            other.keyFileInternalPath == relativePath
                        }
                        if (!stillReferenced) {
                            keyFileStore.deleteInternal(relativePath)
                        }
                    }
                }

                _groupsByDatabase.update { current -> current - databaseId }
                _verificationStates.update { current -> current - databaseId }
                _selectedDatabase.update { current -> current?.takeUnless { it.id == databaseId } }
                
                _operationState.value = OperationState.Success(strings.get(R.string.deleted))
                logKeepassDatabaseDelete(
                    databaseId = databaseId,
                    databaseName = deletedDatabaseName,
                    detail = if (deleteFile) "删除记录与本地文件" else "删除记录"
                )
            } catch (e: Exception) {
                _operationState.value = OperationState.Error(strings.get(R.string.delete_failed, formatOperationError(e)))
            }
        }
    }
    
    /**
     * 更新数据库密码
     */
    fun updatePassword(databaseId: Long, newPassword: String) {
        viewModelScope.launch {
            try {
                var verifyElapsedMs = 0L
                var databaseName = "KeePass DB #$databaseId"
                withContext(Dispatchers.IO) {
                    val database = dao.getDatabaseById(databaseId)
                        ?: throw Exception(strings.get(R.string.keepass_connection_status_missing))
                    databaseName = database.name
                    val verifyStart = SystemClock.elapsedRealtime()
                    val verifyResult = workspaceRepository.inspectDatabase(
                        databaseId = databaseId,
                        passwordOverride = newPassword
                    )
                    verifyElapsedMs = SystemClock.elapsedRealtime() - verifyStart
                    val diagnostics = verifyResult.getOrElse { throw it }
                    val entryCount = diagnostics.entryCount
                    val options = diagnostics.creationOptions
                    val encryptedPassword = securityManager.encryptData(newPassword)
                    dao.updateDatabase(
                        database.copy(
                            encryptedPassword = encryptedPassword,
                            entryCount = entryCount,
                            kdbxMajorVersion = options.formatVersion.majorVersion,
                            cipherAlgorithm = options.cipherAlgorithm.name,
                            kdfAlgorithm = options.kdfAlgorithm.name,
                            kdfTransformRounds = options.transformRounds,
                            kdfMemoryBytes = options.memoryBytes,
                            kdfParallelism = options.parallelism,
                            lastAccessedAt = System.currentTimeMillis()
                        )
                    )
                    _verificationStates.update { current ->
                        current + (
                            databaseId to VerificationState.Verified(
                                entryCount = entryCount,
                                decryptTimeMs = verifyElapsedMs
                            )
                        )
                    }
                }
                
                _operationState.value = OperationState.Success(strings.get(R.string.keepass_operation_password_updated, verifyElapsedMs))
                logKeepassDatabaseUpdate(
                    databaseId = databaseId,
                    databaseName = databaseName,
                    changes = listOf(
                        FieldChange("主密码", "已设置", "已更新")
                    )
                )
            } catch (e: Exception) {
                _operationState.value = OperationState.Error(strings.get(R.string.keepass_operation_update_failed, formatOperationError(e)))
            }
        }
    }
    
    /**
     * 设为默认数据库
     */
    fun setAsDefault(databaseId: Long) {
        viewModelScope.launch {
            try {
                var defaultDatabaseName: String? = null
                withContext(Dispatchers.IO) {
                    defaultDatabaseName = dao.getDatabaseById(databaseId)?.name
                    dao.clearDefaultDatabase()
                    dao.setDefaultDatabase(databaseId)
                }
                defaultDatabaseName?.let { databaseName ->
                    logKeepassDatabaseUpdate(
                        databaseId = databaseId,
                        databaseName = databaseName,
                        changes = listOf(
                            FieldChange("默认数据库", "否", "是")
                        )
                    )
                }
            } catch (e: Exception) {
                _operationState.value = OperationState.Error(strings.get(R.string.keepass_operation_setting_failed, formatOperationError(e)))
            }
        }
    }
    
    /**
     * 将密码条目添加到 KeePass 数据库的 .kdbx 文件中
     * @param databaseId 目标 KeePass 数据库 ID
     * @param entries 要添加的密码条目列表（已解密的密码）
     * @return Result 表示操作结果
     */
    suspend fun addPasswordEntriesToKdbx(
        databaseId: Long,
        entries: List<PasswordEntry>,
        decryptPassword: (String) -> String,
        sourceEntries: List<PasswordEntry>? = null,
        onItemProcessed: ((Int, Int) -> Unit)? = null
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val total = entries.size
            if (total <= 0) {
                onItemProcessed?.invoke(0, 0)
                return@withContext Result.success(0)
            }

            onItemProcessed?.invoke(0, total)
            if (total > 1) {
                onItemProcessed?.invoke(1, total)
            }

            val targetEntries = entries.map { entry ->
                KeePassCrossDatabaseTransfer.bindPasswordToTarget(
                    entry = entry,
                    databaseId = databaseId,
                    groupPath = entry.keepassGroupPath,
                    forceNewEntryUuid = entry.id <= 0
                )
            }
            val result = compatibilityBridge.upsertLegacyPasswordEntries(
                databaseId = databaseId,
                entries = targetEntries,
                resolvePassword = { entry ->
                    try {
                        decryptPassword(entry.password)
                    } catch (e: Exception) {
                        entry.password
                    }
                }
            )

            if (result.isSuccess) {
                try {
                    copyPasswordAttachmentsToKdbx(
                        sources = sourceEntries ?: entries,
                        targets = targetEntries,
                        targetDatabaseId = databaseId,
                        targetParentId = null
                    )
                } catch (e: Exception) {
                    rollbackKeePassTargets(databaseId, targetEntries)
                    throw e
                }
                onItemProcessed?.invoke(total, total)
            }
            result
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun movePasswordEntriesToKdbx(
        databaseId: Long,
        groupPath: String?,
        groupUuid: String? = null,
        entries: List<PasswordEntry>,
        decryptPassword: (String) -> String,
        onItemProcessed: ((Int, Int) -> Unit)? = null
    ): Result<KeePassPasswordMoveBatchResult> = withContext(Dispatchers.IO) {
        try {
            val total = entries.size
            if (total <= 0) {
                onItemProcessed?.invoke(0, 0)
                return@withContext Result.success(
                    KeePassPasswordMoveBatchResult(emptyMap(), emptyMap())
                )
            }

            var processed = 0
            val targetEntryUuidsByPasswordId = linkedMapOf<Long, String>()
            val failuresByPasswordId = linkedMapOf<Long, String>()
            onItemProcessed?.invoke(0, total)

            val resolvePassword: (PasswordEntry) -> String = { item ->
                try {
                    decryptPassword(item.password)
                } catch (_: Exception) {
                    item.password
                }
            }

            fun normalizeForTarget(entry: PasswordEntry, forceNewEntryUuid: Boolean = false): PasswordEntry {
                return KeePassCrossDatabaseTransfer.bindPasswordToTarget(
                    entry = entry,
                    databaseId = databaseId,
                    groupPath = groupPath,
                    forceNewEntryUuid = forceNewEntryUuid
                )
            }

            fun reportProcessed(delta: Int) {
                if (delta <= 0) return
                processed = (processed + delta).coerceAtMost(total)
                onItemProcessed?.invoke(processed, total)
            }

            fun recordFailure(entry: PasswordEntry, error: Throwable) {
                failuresByPasswordId[entry.id] = error.message
                    ?.takeIf { it.isNotBlank() }
                    ?: "KeePass move failed"
            }

            fun recordTargets(sources: List<PasswordEntry>, targets: List<PasswordEntry>) {
                sources.zip(targets).forEach { (source, target) ->
                    val targetUuid = target.keepassEntryUuid?.takeIf { it.isNotBlank() }
                    if (targetUuid == null) {
                        failuresByPasswordId[source.id] = "KeePass target entry uuid is missing"
                    } else {
                        targetEntryUuidsByPasswordId[source.id] = targetUuid
                    }
                }
            }

            val externalEntries = entries.filter { it.keepassDatabaseId == null }
            if (externalEntries.isNotEmpty()) {
                if (processed <= 0 && total > 1) {
                    onItemProcessed?.invoke(1, total)
                }
                val targetEntries = externalEntries.map { normalizeForTarget(it) }
                var targetWritten = false
                try {
                    compatibilityBridge.upsertLegacyPasswordEntries(
                        databaseId = databaseId,
                        entries = targetEntries,
                        resolvePassword = resolvePassword,
                        forceSyncWrite = true
                    ).getOrThrow()
                    targetWritten = true
                    copyPasswordAttachmentsToKdbx(
                        sources = externalEntries,
                        targets = targetEntries,
                        targetDatabaseId = databaseId,
                        targetParentId = { source -> source.id }
                    )
                    recordTargets(externalEntries, targetEntries)
                } catch (e: Exception) {
                    if (targetWritten) {
                        rollbackKeePassTargets(databaseId, targetEntries)
                    }
                    externalEntries.forEach { entry -> recordFailure(entry, e) }
                }
                reportProcessed(externalEntries.size)
            }

            val sameDatabaseEntries = entries.filter { it.keepassDatabaseId == databaseId }
            if (sameDatabaseEntries.isNotEmpty()) {
                if (processed <= 0 && total > 1) {
                    onItemProcessed?.invoke(1, total)
                }
                val nativeEntries = sameDatabaseEntries.filter {
                    resolveKeePassPasswordMoveRoute(it, databaseId) ==
                        KeePassPasswordMoveRoute.NATIVE_RELOCATE
                }
                if (nativeEntries.isNotEmpty()) {
                    try {
                        val browser = workspaceRepository.openNativeBrowser(databaseId).getOrThrow()
                        val requestedGroupUuid = groupUuid
                            ?.takeIf { it.isNotBlank() }
                            ?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
                        val targetGroup = requestedGroupUuid
                            ?.let { uuid ->
                                browser.groupsByIdentity[
                                    KeePassNativeGroupIdentity(databaseId, uuid)
                                ]?.singleOrNull()
                            }
                            ?: if (groupPath.isNullOrBlank()) {
                                browser.rootGroup
                            } else {
                                browser.groupsByLegacyPath[groupPath].orEmpty().singleOrNull()
                                    ?: throw IllegalArgumentException(
                                        "KeePass target group is missing or ambiguous: $groupPath"
                                    )
                            }
                        val entryUuidsByPasswordId = nativeEntries.associate { entry ->
                            val rawUuid = requireNotNull(entry.keepassEntryUuid) {
                                "KeePass source entry uuid is missing for password ${entry.id}"
                            }
                            entry.id to UUID.fromString(rawUuid)
                        }
                        val movedEntries = workspaceRepository.moveNativeEntries(
                            databaseId = databaseId,
                            entryUuids = entryUuidsByPasswordId.values.toSet(),
                            targetGroupUuid = targetGroup.identity.groupUuid,
                            expectedRevisionToken = browser.sourceRevision.sha256,
                        ).getOrThrow()
                        val movedUuids = movedEntries.mapTo(hashSetOf()) { it.identity.entryUuid }
                        nativeEntries.forEach { entry ->
                            val movedUuid = entryUuidsByPasswordId.getValue(entry.id)
                            if (movedUuid in movedUuids) {
                                targetEntryUuidsByPasswordId[entry.id] = movedUuid.toString()
                            } else {
                                failuresByPasswordId[entry.id] =
                                    "KeePass entry was not found after the batch move"
                            }
                        }
                    } catch (error: Exception) {
                        Log.e(
                            TAG,
                            "Native KeePass batch move failed db=$databaseId entries=${nativeEntries.size}",
                            error,
                        )
                        nativeEntries.forEach { entry -> recordFailure(entry, error) }
                    }
                    reportProcessed(nativeEntries.size)
                }

                val legacyEntries = sameDatabaseEntries - nativeEntries.toSet()
                if (legacyEntries.isNotEmpty()) {
                    val targetEntries = legacyEntries.map { normalizeForTarget(it) }
                    try {
                        compatibilityBridge.upsertLegacyPasswordEntries(
                            databaseId = databaseId,
                            entries = targetEntries,
                            resolvePassword = resolvePassword
                        ).getOrThrow()
                        recordTargets(legacyEntries, targetEntries)
                    } catch (e: Exception) {
                        legacyEntries.forEach { entry -> recordFailure(entry, e) }
                    }
                    reportProcessed(legacyEntries.size)
                }
            }

            val crossDatabaseEntriesBySource = entries
                .filter { it.keepassDatabaseId != null && it.keepassDatabaseId != databaseId }
                .groupBy { it.keepassDatabaseId!! }
            if (crossDatabaseEntriesBySource.isNotEmpty()) {
                if (processed <= 0 && total > 1) {
                    onItemProcessed?.invoke(1, total)
                }
                crossDatabaseEntriesBySource.forEach { (sourceDatabaseId, sourceEntries) ->
                    val nativeEntries = sourceEntries.filter {
                        resolveKeePassPasswordMoveRoute(it, databaseId) ==
                            KeePassPasswordMoveRoute.NATIVE_CROSS_DATABASE
                    }
                    nativeEntries.forEach { entry ->
                        val entryUuid = requireNotNull(entry.keepassEntryUuid)
                        val result = kdbxService.moveNativeEntry(
                            sourceDatabaseId = sourceDatabaseId,
                            sourceEntryUuid = entryUuid,
                            targetDatabaseId = databaseId,
                            targetGroupPath = groupPath
                        )
                        result.onSuccess { transfer ->
                            targetEntryUuidsByPasswordId[entry.id] = transfer.targetEntryUuid
                        }.onFailure { error ->
                            recordFailure(entry, error)
                        }
                        reportProcessed(1)
                    }

                    val legacyEntries = sourceEntries - nativeEntries.toSet()
                    if (legacyEntries.isNotEmpty()) {
                        val targetEntries = legacyEntries.map { entry ->
                            normalizeForTarget(entry, forceNewEntryUuid = true)
                        }
                        var targetWritten = false
                        var targetComplete = false
                        try {
                            compatibilityBridge.upsertLegacyPasswordEntries(
                                databaseId = databaseId,
                                entries = targetEntries,
                                resolvePassword = resolvePassword,
                                forceSyncWrite = true
                            ).getOrThrow()
                            targetWritten = true
                            copyPasswordAttachmentsToKdbx(
                                sources = legacyEntries,
                                targets = targetEntries,
                                targetDatabaseId = databaseId,
                                targetParentId = { source -> source.id }
                            )
                            targetComplete = true
                            compatibilityBridge.deleteLegacyPasswordEntries(
                                databaseId = sourceDatabaseId,
                                entries = legacyEntries
                            ).getOrThrow()
                            recordTargets(legacyEntries, targetEntries)
                        } catch (e: Exception) {
                            if (targetWritten && !targetComplete) {
                                rollbackKeePassTargets(databaseId, targetEntries)
                            }
                            legacyEntries.forEach { entry -> recordFailure(entry, e) }
                        }
                        reportProcessed(legacyEntries.size)
                    }
                }
            }

            Result.success(
                KeePassPasswordMoveBatchResult(
                    targetEntryUuidsByPasswordId = targetEntryUuidsByPasswordId,
                    failuresByPasswordId = failuresByPasswordId
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun movePasswordEntriesToMonicaLocal(
        entries: List<PasswordEntry>
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val keepassEntries = entries.filter { it.keepassDatabaseId != null }
            if (keepassEntries.isEmpty()) {
                return@withContext Result.success(0)
            }

            keepassEntries
                .groupBy { it.keepassDatabaseId }
                .forEach { (databaseId, databaseEntries) ->
                    val resolvedDatabaseId = databaseId ?: return@forEach
                    materializeKeePassAttachmentsForLocal(databaseEntries)
                    compatibilityBridge.deleteLegacyPasswordEntries(
                        databaseId = resolvedDatabaseId,
                        entries = databaseEntries
                    ).getOrThrow()
                }

            // 迁移附件：把 KEEPASS 附件改写为 LOCAL（kdbx 条目已删，池里的 binary 也会被释放；
            // 但我们在 Monica 侧为每个 KEEPASS 附件保留了本地 GCM 密文缓存 + wrappedCek，
            // 因此只需把 source 切到 LOCAL、清 keepass_binary_ref 即可继续访问）
            val attachmentRepository = takagi.ru.monica.attachments.AttachmentContainer
                .repository(context)
            keepassEntries.forEach { entry ->
                runCatching {
                    attachmentRepository.convertSourceToLocal(
                        passwordId = entry.id,
                        fromSource = takagi.ru.monica.attachments.model.AttachmentSource.KEEPASS
                    )
                }.onFailure { e ->
                    Log.w(TAG, "Attachment source rewrite failed for entry ${entry.id}: ${e.message}")
                }
            }

            Result.success(keepassEntries.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun copyPasswordAttachmentsToKdbx(
        sources: List<PasswordEntry>,
        targets: List<PasswordEntry>,
        targetDatabaseId: Long,
        targetParentId: ((PasswordEntry) -> Long?)? = null
    ) {
        if (sources.isEmpty() || targets.isEmpty()) return
        val facade = AttachmentContainer.facade(context)
        sources.zip(targets).forEach { (source, target) ->
            val targetUuid = target.keepassEntryUuid?.takeIf { it.isNotBlank() } ?: return@forEach
            facade.copyAttachmentsToKeePassEntry(
                sourcePasswordId = source.id,
                targetPasswordId = targetParentId?.invoke(source),
                targetDatabaseId = targetDatabaseId,
                targetEntryUuid = targetUuid,
                sourceKeepassDatabaseId = source.keepassDatabaseId,
                sourceKeepassEntryUuid = source.keepassEntryUuid
            )
        }
    }

    private suspend fun materializeKeePassAttachmentsForLocal(entries: List<PasswordEntry>) {
        if (entries.isEmpty()) return
        val facade = AttachmentContainer.facade(context)
        val repository = AttachmentContainer.repository(context)
        entries.forEach { entry ->
            val databaseId = entry.keepassDatabaseId ?: return@forEach
            val entryUuid = entry.keepassEntryUuid
            if (entryUuid.isNullOrBlank()) {
                val hasKeePassAttachments = repository
                    .listByParentAndSource(
                        passwordId = entry.id,
                        source = takagi.ru.monica.attachments.model.AttachmentSource.KEEPASS
                    )
                    .isNotEmpty()
                if (hasKeePassAttachments) {
                    throw IllegalStateException("KeePass attachment transfer requires entry uuid")
                }
                return@forEach
            }
            facade.materializeKeePassAttachmentsForLocal(
                passwordId = entry.id,
                databaseId = databaseId,
                entryUuid = entryUuid
            )
        }
    }

    private suspend fun rollbackKeePassTargets(
        databaseId: Long,
        targets: List<PasswordEntry>
    ) {
        if (targets.isEmpty()) return
        runCatching {
            compatibilityBridge.deleteLegacyPasswordEntries(
                databaseId = databaseId,
                entries = targets
            )
        }.onFailure { error ->
            Log.w(TAG, "Failed to rollback KeePass target after attachment transfer failure: ${error.message}")
        }
    }
    
    /**
     * 清除操作状态
     */
    fun clearOperationState() {
        _operationState.value = OperationState.Idle
    }

    private fun logKeepassDatabaseCreate(
        databaseId: Long,
        databaseName: String,
        details: List<FieldChange> = emptyList()
    ) {
        OperationLogger.logCreate(
            itemType = OperationLogItemType.KEEPASS_DATABASE,
            itemId = databaseId,
            itemTitle = databaseName,
            details = details
        )
    }

    private fun logKeepassDatabaseUpdate(
        databaseId: Long,
        databaseName: String,
        changes: List<FieldChange>
    ) {
        OperationLogger.logUpdate(
            itemType = OperationLogItemType.KEEPASS_DATABASE,
            itemId = databaseId,
            itemTitle = databaseName,
            changes = changes
        )
    }

    private fun logKeepassDatabaseDelete(
        databaseId: Long,
        databaseName: String,
        detail: String? = null
    ) {
        OperationLogger.logDelete(
            itemType = OperationLogItemType.KEEPASS_DATABASE,
            itemId = databaseId,
            itemTitle = databaseName,
            detail = detail
        )
    }

    private fun logKeepassGroupCreate(
        databaseId: Long,
        databaseName: String,
        group: KeePassGroupInfo,
        parentPath: String?
    ) {
        OperationLogger.logCreate(
            itemType = OperationLogItemType.KEEPASS_GROUP,
            itemId = buildKeepassGroupItemId(databaseId, group.path),
            itemTitle = "$databaseName · ${group.displayPath}",
            details = listOf(
                FieldChange("数据库", "", databaseName),
                FieldChange("父级分组", "", parentPath?.takeIf { it.isNotBlank() } ?: "根目录")
            )
        )
    }

    private fun logKeepassGroupRename(
        databaseId: Long,
        databaseName: String,
        oldPath: String,
        newGroup: KeePassGroupInfo
    ) {
        val oldName = oldPath.substringAfterLast('/')
        OperationLogger.logUpdate(
            itemType = OperationLogItemType.KEEPASS_GROUP,
            itemId = buildKeepassGroupItemId(databaseId, newGroup.path),
            itemTitle = "$databaseName · ${newGroup.displayPath}",
            changes = buildList {
                add(FieldChange("名称", oldName, newGroup.name))
                if (oldPath != newGroup.path) {
                    add(FieldChange("路径", oldPath, newGroup.path))
                }
            }
        )
    }

    private fun logKeepassGroupDelete(
        databaseId: Long,
        databaseName: String,
        groupPath: String
    ) {
        OperationLogger.logDelete(
            itemType = OperationLogItemType.KEEPASS_GROUP,
            itemId = buildKeepassGroupItemId(databaseId, groupPath),
            itemTitle = "$databaseName · $groupPath",
            detail = "删除分组"
        )
    }

    private fun logKeepassGroupMove(
        sourceDatabaseId: Long,
        sourceDatabaseName: String,
        sourcePath: String,
        targetDatabaseId: Long,
        targetDatabaseName: String,
        movedGroup: KeePassGroupInfo
    ) {
        OperationLogger.logUpdate(
            itemType = OperationLogItemType.KEEPASS_GROUP,
            itemId = buildKeepassGroupItemId(targetDatabaseId, movedGroup.path),
            itemTitle = "$targetDatabaseName · ${movedGroup.displayPath}",
            changes = buildList {
                if (sourceDatabaseId != targetDatabaseId) {
                    add(FieldChange("数据库", sourceDatabaseName, targetDatabaseName))
                }
                add(FieldChange("路径", sourcePath, movedGroup.path))
            }
        )
    }

    private fun buildKeepassGroupItemId(databaseId: Long, groupPath: String): Long {
        return "${databaseId}:$groupPath".hashCode().toLong() and 0x7FFFFFFFL
    }

    private fun storageLocationLabel(location: KeePassStorageLocation): String {
        return when (location) {
            KeePassStorageLocation.INTERNAL -> strings.get(R.string.keepass_operation_internal)
            KeePassStorageLocation.EXTERNAL -> strings.get(R.string.keepass_operation_external)
        }
    }

    private fun storagePathLabel(path: String): String {
        return if (path.startsWith("content://")) {
            strings.get(R.string.keepass_operation_external_uri)
        } else {
            path
        }
    }

    private fun buildWebDavFileSource(
        serverUrl: String,
        username: String,
        webDavPassword: String,
        remotePath: String? = null
    ): WebDavKeePassFileSource {
        return WebDavKeePassFileSource(
            serverUrl = serverUrl.trim().trimEnd('/'),
            username = username.trim(),
            password = webDavPassword,
            remotePath = remotePath,
            strings = strings,
        )
    }

    private suspend fun readKeyFileBytes(keyFileUri: Uri?): ByteArray? {
        if (keyFileUri == null) {
            return null
        }
        return context.contentResolver.readKeePassKeyFileBytes(
            uri = keyFileUri,
            unavailableMessage = context.getString(
                takagi.ru.monica.R.string.local_keepass_key_file_unavailable_error
            )
        )
    }

    private suspend fun attachOneDriveDatabaseBlocking(
        name: String,
        accountId: String,
        accountLabel: String,
        remotePath: String,
        databasePassword: String,
        keyFileUri: Uri?,
        description: String?,
        keepKeyFileCopy: Boolean
    ): OneDriveAttachResult {
        val normalizedRemotePath = OneDriveKeePassFileSource.normalizeRemotePath(remotePath, strings = strings)
        val displayName = name.ifBlank {
            OneDriveKeePassSupport.displayNameFromRemotePath(normalizedRemotePath, strings = strings)
                .removeSuffix(".kdbx")
        }
        val remoteSourceDao = appDatabase.keepassRemoteSourceDao()
        val syncStateDao = appDatabase.keepassRemoteSyncStateDao()

        readKeyFileBytes(keyFileUri)

        val existingSource = remoteSourceDao
            .getAllSourcesSync()
            .firstOrNull {
                it.providerType == KeePassRemoteProviderType.ONEDRIVE &&
                    it.tokenRef == accountId &&
                    it.remotePath == normalizedRemotePath
            }
        if (existingSource != null) {
            val duplicate = dao.getAllDatabasesSync().firstOrNull { it.sourceId == existingSource.id }
            if (duplicate != null) {
                throw IllegalArgumentException(strings.get(R.string.keepass_operation_already_connected, "OneDrive"))
            }
        }

        var createdRemoteSourceId: Long? = null
        var createdWorkingCopyPath: String? = null
        var createdCacheCopyPath: String? = null

        try {
            val sourceToSave = (existingSource ?: KeepassRemoteSource(
                providerType = KeePassRemoteProviderType.ONEDRIVE,
                displayName = displayName,
                remotePath = normalizedRemotePath,
                remoteParentPath = OneDriveKeePassFileSource.parentPathOf(normalizedRemotePath, strings = strings),
                accountId = accountLabel,
                tokenRef = accountId,
                autoSyncEnabled = true,
                allowMeteredNetwork = true
            )).copy(
                displayName = displayName,
                remotePath = normalizedRemotePath,
                remoteParentPath = OneDriveKeePassFileSource.parentPathOf(normalizedRemotePath, strings = strings),
                accountId = accountLabel,
                tokenRef = accountId,
                autoSyncEnabled = true,
                allowMeteredNetwork = true,
                updatedAt = System.currentTimeMillis()
            )

            val remoteSourceId = if (existingSource == null) {
                remoteSourceDao.insertSource(sourceToSave).also { createdRemoteSourceId = it }
            } else {
                remoteSourceDao.updateSource(sourceToSave)
                existingSource.id
            }

            val remoteSource = remoteSourceDao.getSourceById(remoteSourceId)
                ?: throw IllegalStateException(strings.get(R.string.keepass_operation_source_create_failed))
            val fileSource = OneDriveKeePassSupport.createFileSource(context, remoteSource)
            fileSource.testConnection().getOrThrow()

            val remoteBytes = fileSource.read()
            val remoteStat = runCatching { fileSource.stat() }.getOrDefault(takagi.ru.monica.utils.FileSourceStat())
            if ((remoteSource.itemId.isNullOrBlank() || remoteSource.driveId.isNullOrBlank()) &&
                (!remoteStat.remoteId.isNullOrBlank() || !remoteStat.driveId.isNullOrBlank())
            ) {
                remoteSourceDao.updateSource(
                    remoteSource.copy(
                        itemId = remoteStat.remoteId ?: remoteSource.itemId,
                        driveId = remoteStat.driveId ?: remoteSource.driveId,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
            val mirrorPaths = OneDriveKeePassSupport.buildLocalMirrorPaths(
                sourceId = remoteSourceId,
                remotePath = normalizedRemotePath,
                strings = strings,
            )
            createdWorkingCopyPath = mirrorPaths.workingCopyPath
            createdCacheCopyPath = mirrorPaths.cacheCopyPath
            OneDriveKeePassSupport.writeRelativeFile(context, mirrorPaths.workingCopyPath, remoteBytes)
            OneDriveKeePassSupport.writeRelativeFile(context, mirrorPaths.cacheCopyPath, remoteBytes)

            // Delay the private copy until duplicate/network checks have passed,
            // so failed attachment attempts do not leave needless secret files.
            val storedKeyFile = keyFileUri?.takeIf { keepKeyFileCopy }?.let { uri ->
                keyFileStore.copyFromUri(uri, uri.lastPathSegment)
            }

            val encryptedPassword = if (databasePassword.isNotBlank()) {
                securityManager.encryptData(databasePassword)
            } else {
                null
            }

            val localDatabase = LocalKeePassDatabase(
                name = displayName,
                filePath = normalizedRemotePath,
                keyFileUri = keyFileUri?.toString(),
                keyFileInternalPath = storedKeyFile?.relativePath,
                keyFileName = storedKeyFile?.fileName
                    ?: keyFileUri?.lastPathSegment?.substringAfterLast('/'),
                keyFileFingerprint = storedKeyFile?.fingerprint,
                storageLocation = KeePassStorageLocation.INTERNAL,
                sourceType = KeePassDatabaseSourceType.REMOTE_ONEDRIVE,
                sourceId = remoteSourceId,
                openMode = KeePassOpenMode.WORKING_COPY,
                workingCopyPath = mirrorPaths.workingCopyPath,
                cacheCopyPath = mirrorPaths.cacheCopyPath,
                isOfflineAvailable = true,
                encryptedPassword = encryptedPassword,
                description = description,
                isDefault = allDatabases.value.isEmpty(),
                lastSyncStatus = KeePassSyncStatus.SYNCING
            )
            val databaseId = dao.insertDatabase(localDatabase)

            try {
                val diagnostics = workspaceRepository.inspectDatabase(
                    databaseId = databaseId,
                    passwordOverride = databasePassword,
                    keyFileUriOverride = keyFileUri
                ).getOrElse { throw it }
                val now = System.currentTimeMillis()
                dao.updateDatabase(
                    localDatabase.copy(
                        id = databaseId,
                        entryCount = diagnostics.entryCount,
                        kdbxMajorVersion = diagnostics.creationOptions.formatVersion.majorVersion,
                        cipherAlgorithm = diagnostics.creationOptions.cipherAlgorithm.name,
                        kdfAlgorithm = diagnostics.creationOptions.kdfAlgorithm.name,
                        kdfTransformRounds = diagnostics.creationOptions.transformRounds,
                        kdfMemoryBytes = diagnostics.creationOptions.memoryBytes,
                        kdfParallelism = diagnostics.creationOptions.parallelism,
                        lastAccessedAt = now,
                        lastSyncedAt = now,
                        lastSyncStatus = KeePassSyncStatus.IN_SYNC,
                        lastSyncError = null
                    )
                )
                remoteSyncService.markSynchronized(
                    databaseId = databaseId,
                    versionToken = remoteStat.versionToken,
                    etag = remoteStat.etag,
                    baseHash = OneDriveKeePassSupport.sha256Hex(remoteBytes),
                    workingHash = OneDriveKeePassSupport.sha256Hex(remoteBytes)
                )
                KeePassKdbxService.invalidateProcessCache(databaseId)
                return OneDriveAttachResult(
                    databaseId = databaseId,
                    databaseName = displayName,
                    entryCount = diagnostics.entryCount
                )
            } catch (error: Exception) {
                dao.deleteDatabaseById(databaseId)
                syncStateDao.deleteState(databaseId)
                if (createdRemoteSourceId != null) {
                    remoteSourceDao.deleteSourceById(remoteSourceId)
                }
                cleanupRemoteLocalCopies(mirrorPaths.workingCopyPath, mirrorPaths.cacheCopyPath)
                throw error
            }
        } catch (error: Exception) {
            if (createdRemoteSourceId != null) {
                remoteSourceDao.deleteSourceById(createdRemoteSourceId!!)
            }
            cleanupRemoteLocalCopies(createdWorkingCopyPath, createdCacheCopyPath)
            throw error
        }
    }

    private suspend fun attachGoogleDriveDatabaseBlocking(
        name: String,
        accountId: String,
        accountLabel: String,
        remotePath: String,
        fileId: String,
        databasePassword: String,
        keyFileUri: Uri?,
        description: String?
    ): GoogleDriveAttachResult {
        val normalizedRemotePath = GoogleDriveKeePassFileSource.normalizeRemotePath(remotePath, strings = strings)
        val normalizedFileId = fileId.trim().ifBlank {
            throw IllegalArgumentException(strings.get(R.string.keepass_operation_google_file_id_missing))
        }
        val displayName = name.ifBlank {
            GoogleDriveKeePassSupport.displayNameFromRemotePath(normalizedRemotePath, strings = strings)
                .removeSuffix(".kdbx")
        }
        val remoteSourceDao = appDatabase.keepassRemoteSourceDao()
        val syncStateDao = appDatabase.keepassRemoteSyncStateDao()

        readKeyFileBytes(keyFileUri)

        val existingSource = remoteSourceDao
            .getAllSourcesSync()
            .firstOrNull {
                it.providerType == KeePassRemoteProviderType.GOOGLE_DRIVE &&
                    it.tokenRef == accountId &&
                    (it.itemId == normalizedFileId || it.remotePath == normalizedRemotePath)
            }
        if (existingSource != null) {
            val duplicate = dao.getAllDatabasesSync().firstOrNull { it.sourceId == existingSource.id }
            if (duplicate != null) {
                throw IllegalArgumentException(strings.get(R.string.keepass_operation_already_connected, "Google Drive"))
            }
        }

        var createdRemoteSourceId: Long? = null
        var createdWorkingCopyPath: String? = null
        var createdCacheCopyPath: String? = null

        try {
            val sourceToSave = (existingSource ?: KeepassRemoteSource(
                providerType = KeePassRemoteProviderType.GOOGLE_DRIVE,
                displayName = displayName,
                remotePath = normalizedRemotePath,
                remoteParentPath = GoogleDriveKeePassFileSource.parentPathOf(normalizedRemotePath, strings = strings),
                accountId = accountLabel,
                itemId = normalizedFileId,
                tokenRef = accountId,
                autoSyncEnabled = true,
                allowMeteredNetwork = true
            )).copy(
                displayName = displayName,
                remotePath = normalizedRemotePath,
                remoteParentPath = GoogleDriveKeePassFileSource.parentPathOf(normalizedRemotePath, strings = strings),
                accountId = accountLabel,
                itemId = normalizedFileId,
                tokenRef = accountId,
                autoSyncEnabled = true,
                allowMeteredNetwork = true,
                updatedAt = System.currentTimeMillis()
            )

            val remoteSourceId = if (existingSource == null) {
                remoteSourceDao.insertSource(sourceToSave).also { createdRemoteSourceId = it }
            } else {
                remoteSourceDao.updateSource(sourceToSave)
                existingSource.id
            }

            val remoteSource = remoteSourceDao.getSourceById(remoteSourceId)
                ?: throw IllegalStateException(strings.get(R.string.keepass_operation_source_create_failed))
            val fileSource = GoogleDriveKeePassSupport.createFileSource(context, remoteSource)
            fileSource.testConnection().getOrThrow()

            val remoteBytes = fileSource.read()
            val remoteStat = runCatching { fileSource.stat() }.getOrDefault(takagi.ru.monica.utils.FileSourceStat())
            val mirrorPaths = GoogleDriveKeePassSupport.buildLocalMirrorPaths(
                sourceId = remoteSourceId,
                remotePath = normalizedRemotePath,
                strings = strings,
            )
            createdWorkingCopyPath = mirrorPaths.workingCopyPath
            createdCacheCopyPath = mirrorPaths.cacheCopyPath
            GoogleDriveKeePassSupport.writeRelativeFile(context, mirrorPaths.workingCopyPath, remoteBytes)
            GoogleDriveKeePassSupport.writeRelativeFile(context, mirrorPaths.cacheCopyPath, remoteBytes)

            val encryptedPassword = if (databasePassword.isNotBlank()) {
                securityManager.encryptData(databasePassword)
            } else {
                null
            }

            val localDatabase = LocalKeePassDatabase(
                name = displayName,
                filePath = normalizedRemotePath,
                keyFileUri = keyFileUri?.toString(),
                storageLocation = KeePassStorageLocation.INTERNAL,
                sourceType = KeePassDatabaseSourceType.REMOTE_GOOGLE_DRIVE,
                sourceId = remoteSourceId,
                openMode = KeePassOpenMode.WORKING_COPY,
                workingCopyPath = mirrorPaths.workingCopyPath,
                cacheCopyPath = mirrorPaths.cacheCopyPath,
                isOfflineAvailable = true,
                encryptedPassword = encryptedPassword,
                description = description,
                isDefault = allDatabases.value.isEmpty(),
                lastSyncStatus = KeePassSyncStatus.SYNCING
            )
            val databaseId = dao.insertDatabase(localDatabase)

            try {
                val diagnostics = workspaceRepository.inspectDatabase(
                    databaseId = databaseId,
                    passwordOverride = databasePassword,
                    keyFileUriOverride = keyFileUri
                ).getOrElse { throw it }
                val now = System.currentTimeMillis()
                dao.updateDatabase(
                    localDatabase.copy(
                        id = databaseId,
                        entryCount = diagnostics.entryCount,
                        kdbxMajorVersion = diagnostics.creationOptions.formatVersion.majorVersion,
                        cipherAlgorithm = diagnostics.creationOptions.cipherAlgorithm.name,
                        kdfAlgorithm = diagnostics.creationOptions.kdfAlgorithm.name,
                        kdfTransformRounds = diagnostics.creationOptions.transformRounds,
                        kdfMemoryBytes = diagnostics.creationOptions.memoryBytes,
                        kdfParallelism = diagnostics.creationOptions.parallelism,
                        lastAccessedAt = now,
                        lastSyncedAt = now,
                        lastSyncStatus = KeePassSyncStatus.IN_SYNC,
                        lastSyncError = null
                    )
                )
                remoteSyncService.markSynchronized(
                    databaseId = databaseId,
                    versionToken = remoteStat.versionToken,
                    etag = remoteStat.etag,
                    baseHash = GoogleDriveKeePassSupport.sha256Hex(remoteBytes),
                    workingHash = GoogleDriveKeePassSupport.sha256Hex(remoteBytes)
                )
                KeePassKdbxService.invalidateProcessCache(databaseId)
                return GoogleDriveAttachResult(
                    databaseId = databaseId,
                    databaseName = displayName,
                    entryCount = diagnostics.entryCount
                )
            } catch (error: Exception) {
                dao.deleteDatabaseById(databaseId)
                syncStateDao.deleteState(databaseId)
                if (createdRemoteSourceId != null) {
                    remoteSourceDao.deleteSourceById(remoteSourceId)
                }
                cleanupRemoteLocalCopies(mirrorPaths.workingCopyPath, mirrorPaths.cacheCopyPath)
                throw error
            }
        } catch (error: Exception) {
            if (createdRemoteSourceId != null) {
                remoteSourceDao.deleteSourceById(createdRemoteSourceId!!)
            }
            cleanupRemoteLocalCopies(createdWorkingCopyPath, createdCacheCopyPath)
            throw error
        }
    }

    private suspend fun attachWebDavDatabaseBlocking(
        name: String,
        serverUrl: String,
        username: String,
        webDavPassword: String,
        remotePath: String,
        databasePassword: String,
        keyFileUri: Uri?,
        description: String?,
        keepKeyFileCopy: Boolean
    ): WebDavAttachResult {
        val normalizedBaseUrl = serverUrl.trim().trimEnd('/')
        val normalizedRemotePath = WebDavKeePassFileSource.normalizeRemotePath(remotePath, strings = strings)
        val displayName = name.ifBlank {
            WebDavKeePassSupport.displayNameFromRemotePath(normalizedRemotePath, strings = strings)
                .removeSuffix(".kdbx")
        }
        val remoteSourceDao = appDatabase.keepassRemoteSourceDao()
        val syncStateDao = appDatabase.keepassRemoteSyncStateDao()

        readKeyFileBytes(keyFileUri)

        val existingSource = remoteSourceDao
            .getAllSourcesSync()
            .firstOrNull {
                it.providerType == KeePassRemoteProviderType.WEBDAV &&
                    it.baseUrl == normalizedBaseUrl &&
                    it.remotePath == normalizedRemotePath
            }
        if (existingSource != null) {
            val duplicate = dao.getAllDatabasesSync().firstOrNull { it.sourceId == existingSource.id }
            if (duplicate != null) {
                throw IllegalArgumentException(strings.get(R.string.keepass_operation_already_connected, "WebDAV"))
            }
        }

        var createdRemoteSourceId: Long? = null
        var createdWorkingCopyPath: String? = null
        var createdCacheCopyPath: String? = null

        try {
            val sourceToSave = (existingSource ?: KeepassRemoteSource(
                providerType = KeePassRemoteProviderType.WEBDAV,
                displayName = displayName,
                remotePath = normalizedRemotePath,
                remoteParentPath = WebDavKeePassFileSource.parentPathOf(normalizedRemotePath, strings = strings),
                baseUrl = normalizedBaseUrl,
                usernameEncrypted = securityManager.encryptData(username.trim()),
                passwordEncrypted = securityManager.encryptData(webDavPassword),
                autoSyncEnabled = true,
                allowMeteredNetwork = true
            )).copy(
                displayName = displayName,
                remotePath = normalizedRemotePath,
                remoteParentPath = WebDavKeePassFileSource.parentPathOf(normalizedRemotePath, strings = strings),
                baseUrl = normalizedBaseUrl,
                usernameEncrypted = securityManager.encryptData(username.trim()),
                passwordEncrypted = securityManager.encryptData(webDavPassword),
                autoSyncEnabled = true,
                allowMeteredNetwork = true,
                updatedAt = System.currentTimeMillis()
            )

            val remoteSourceId = if (existingSource == null) {
                remoteSourceDao.insertSource(sourceToSave).also { createdRemoteSourceId = it }
            } else {
                remoteSourceDao.updateSource(sourceToSave)
                existingSource.id
            }

            val remoteSource = remoteSourceDao.getSourceById(remoteSourceId)
                ?: throw IllegalStateException(strings.get(R.string.keepass_operation_source_create_failed))
            val fileSource = WebDavKeePassSupport.createFileSource(remoteSource, securityManager, strings = strings)
            fileSource.testConnection().getOrThrow()

            val remoteBytes = fileSource.read()
            val remoteStat = runCatching { fileSource.stat() }.getOrDefault(takagi.ru.monica.utils.FileSourceStat())
            val mirrorPaths = WebDavKeePassSupport.buildLocalMirrorPaths(
                sourceId = remoteSourceId,
                remotePath = normalizedRemotePath,
                strings = strings,
            )
            createdWorkingCopyPath = mirrorPaths.workingCopyPath
            createdCacheCopyPath = mirrorPaths.cacheCopyPath
            WebDavKeePassSupport.writeRelativeFile(context, mirrorPaths.workingCopyPath, remoteBytes)
            WebDavKeePassSupport.writeRelativeFile(context, mirrorPaths.cacheCopyPath, remoteBytes)

            // Delay the private copy until duplicate/network checks have passed,
            // so failed attachment attempts do not leave needless secret files.
            val storedKeyFile = keyFileUri?.takeIf { keepKeyFileCopy }?.let { uri ->
                keyFileStore.copyFromUri(uri, uri.lastPathSegment)
            }

            val encryptedPassword = if (databasePassword.isNotBlank()) {
                securityManager.encryptData(databasePassword)
            } else {
                null
            }

            val localDatabase = LocalKeePassDatabase(
                name = displayName,
                filePath = normalizedRemotePath,
                keyFileUri = keyFileUri?.toString(),
                keyFileInternalPath = storedKeyFile?.relativePath,
                keyFileName = storedKeyFile?.fileName
                    ?: keyFileUri?.lastPathSegment?.substringAfterLast('/'),
                keyFileFingerprint = storedKeyFile?.fingerprint,
                storageLocation = KeePassStorageLocation.INTERNAL,
                sourceType = KeePassDatabaseSourceType.REMOTE_WEBDAV,
                sourceId = remoteSourceId,
                openMode = KeePassOpenMode.WORKING_COPY,
                workingCopyPath = mirrorPaths.workingCopyPath,
                cacheCopyPath = mirrorPaths.cacheCopyPath,
                isOfflineAvailable = true,
                encryptedPassword = encryptedPassword,
                description = description,
                isDefault = allDatabases.value.isEmpty(),
                lastSyncStatus = KeePassSyncStatus.SYNCING
            )
            val databaseId = dao.insertDatabase(localDatabase)

            try {
                val diagnostics = workspaceRepository.inspectDatabase(
                    databaseId = databaseId,
                    passwordOverride = databasePassword,
                    keyFileUriOverride = keyFileUri
                ).getOrElse { throw it }
                val now = System.currentTimeMillis()
                dao.updateDatabase(
                    localDatabase.copy(
                        id = databaseId,
                        entryCount = diagnostics.entryCount,
                        kdbxMajorVersion = diagnostics.creationOptions.formatVersion.majorVersion,
                        cipherAlgorithm = diagnostics.creationOptions.cipherAlgorithm.name,
                        kdfAlgorithm = diagnostics.creationOptions.kdfAlgorithm.name,
                        kdfTransformRounds = diagnostics.creationOptions.transformRounds,
                        kdfMemoryBytes = diagnostics.creationOptions.memoryBytes,
                        kdfParallelism = diagnostics.creationOptions.parallelism,
                        lastAccessedAt = now,
                        lastSyncedAt = now,
                        lastSyncStatus = KeePassSyncStatus.IN_SYNC,
                        lastSyncError = null
                    )
                )
                remoteSyncService.markSynchronized(
                    databaseId = databaseId,
                    versionToken = remoteStat.versionToken,
                    etag = remoteStat.etag,
                    baseHash = WebDavKeePassSupport.sha256Hex(remoteBytes),
                    workingHash = WebDavKeePassSupport.sha256Hex(remoteBytes)
                )
                KeePassKdbxService.invalidateProcessCache(databaseId)
                return WebDavAttachResult(
                    databaseId = databaseId,
                    databaseName = displayName,
                    entryCount = diagnostics.entryCount
                )
            } catch (error: Exception) {
                dao.deleteDatabaseById(databaseId)
                syncStateDao.deleteState(databaseId)
                if (createdRemoteSourceId != null) {
                    remoteSourceDao.deleteSourceById(remoteSourceId)
                }
                cleanupRemoteLocalCopies(mirrorPaths.workingCopyPath, mirrorPaths.cacheCopyPath)
                throw error
            }
        } catch (error: Exception) {
            if (createdRemoteSourceId != null) {
                remoteSourceDao.deleteSourceById(createdRemoteSourceId!!)
            }
            cleanupRemoteLocalCopies(createdWorkingCopyPath, createdCacheCopyPath)
            throw error
        }
    }

    private fun cleanupRemoteLocalCopies(
        workingCopyPath: String?,
        cacheCopyPath: String?
    ) {
        OneDriveKeePassSupport.deleteRelativeFile(context, workingCopyPath)
        OneDriveKeePassSupport.deleteRelativeFile(context, cacheCopyPath)
    }

    private fun formatOperationError(error: Throwable): String {
        return if (error is KeePassOperationException) {
            if (error.code == takagi.ru.monica.utils.KeePassErrorCode.ONEDRIVE_REDIRECT_CONFLICT) {
                error.message
            } else {
                "[${error.code.name}] ${error.message}"
            }
        } else {
            error.toOneDriveUserMessage(AppLocaleStringResolver(context), error.message ?: strings.get(R.string.import_data_unknown_error))
        }
    }
    
    // === 私有辅助方法 ===
    
    /**
     * 使用 kotpass 库创建真正的 KDBX 格式数据库文件
     */
    private fun createEmptyKdbxFile(
        file: File,
        password: String,
        keyFileBytes: ByteArray? = null,
        options: KeePassDatabaseCreationOptions,
        databaseName: String
    ) {
        // 创建凭据：空密码 + 密钥文件时优先使用 key-only，兼容 KeePassXC 习惯
        val credentials = buildKdbxCredentials(password, keyFileBytes)

        // 创建元数据
        val meta = Meta(
            generator = "Monica Password Manager",
            name = databaseName.ifBlank { file.nameWithoutExtension }
        )

        val database = createConfiguredDatabase(
            credentials = credentials,
            meta = meta,
            options = options
        )

        // 写入文件
        FileOutputStream(file).use { output ->
            database.encode(output, cipherProviders = KeePassCodecSupport.cipherProviders)
        }
    }
    
    /**
     * 使用 kotpass 库创建真正的 KDBX 格式数据库内容
     */
    private fun createEmptyKdbxContent(
        password: String,
        keyFileBytes: ByteArray? = null,
        options: KeePassDatabaseCreationOptions,
        databaseName: String
    ): ByteArray {
        // 创建凭据：空密码 + 密钥文件时优先使用 key-only，兼容 KeePassXC 习惯
        val credentials = buildKdbxCredentials(password, keyFileBytes)

        // 创建元数据
        val meta = Meta(
            generator = "Monica Password Manager",
            name = databaseName.ifBlank { "Monica Database" }
        )

        val database = createConfiguredDatabase(
            credentials = credentials,
            meta = meta,
            options = options
        )

        // 返回字节数组
        return java.io.ByteArrayOutputStream().use { output ->
            database.encode(output, cipherProviders = KeePassCodecSupport.cipherProviders)
            output.toByteArray()
        }
    }

    private fun createConfiguredDatabase(
        credentials: Credentials,
        meta: Meta,
        options: KeePassDatabaseCreationOptions
    ): KeePassDatabase {
        val normalized = options.normalized()
        return when (normalized.formatVersion) {
            KeePassFormatVersion.KDBX3 -> {
                val base = KeePassDatabase.Ver3x.create(
                    rootName = "Root",
                    meta = meta,
                    credentials = credentials
                )
                base.copy(
                    header = base.header.copy(
                        cipherId = KeePassCodecSupport.resolveCipherUuid(normalized.cipherAlgorithm),
                        transformRounds = normalized.transformRounds.toULong()
                    )
                )
            }
            KeePassFormatVersion.KDBX4 -> {
                val base = KeePassDatabase.Ver4x.create(
                    rootName = "Root",
                    meta = meta,
                    credentials = credentials
                )
                val saltOrSeed = when (val existing = base.header.kdfParameters) {
                    is KdfParameters.Aes -> existing.seed
                    is KdfParameters.Argon2 -> existing.salt
                }
                val kdfParameters = when (normalized.kdfAlgorithm) {
                    KeePassKdfAlgorithm.AES_KDF -> KdfParameters.Aes(
                        rounds = normalized.transformRounds.toULong(),
                        seed = saltOrSeed
                    )
                    KeePassKdfAlgorithm.ARGON2D -> KdfParameters.Argon2(
                        variant = KdfParameters.Argon2.Variant.Argon2d,
                        salt = saltOrSeed,
                        parallelism = normalized.parallelism.toUInt(),
                        memory = normalized.memoryBytes.toULong(),
                        iterations = normalized.transformRounds.toULong(),
                        version = 0x13U,
                        secretKey = null,
                        associatedData = null
                    )
                    KeePassKdfAlgorithm.ARGON2ID -> KdfParameters.Argon2(
                        variant = KdfParameters.Argon2.Variant.Argon2id,
                        salt = saltOrSeed,
                        parallelism = normalized.parallelism.toUInt(),
                        memory = normalized.memoryBytes.toULong(),
                        iterations = normalized.transformRounds.toULong(),
                        version = 0x13U,
                        secretKey = null,
                        associatedData = null
                    )
                }
                base.copy(
                    header = base.header.copy(
                        cipherId = KeePassCodecSupport.resolveCipherUuid(normalized.cipherAlgorithm),
                        kdfParameters = kdfParameters
                    )
                )
            }
        }
    }

    private fun buildKdbxCredentials(password: String, keyFileBytes: ByteArray?): Credentials {
        if (keyFileBytes == null) {
            return Credentials.from(EncryptedValue.fromString(password))
        }
        return if (password.isBlank()) {
            Credentials.from(keyFileBytes)
        } else {
            Credentials.from(EncryptedValue.fromString(password), keyFileBytes)
        }
    }
    
    /**
     * 操作状态
     */
    sealed class OperationState {
        object Idle : OperationState()
        data class Loading(val message: String) : OperationState()
        data class Success(val message: String) : OperationState()
        data class Error(val message: String) : OperationState()
    }

    sealed class VerificationState {
        object Unknown : VerificationState()
        object Verifying : VerificationState()
        data class Verified(
            val entryCount: Int,
            val decryptTimeMs: Long
        ) : VerificationState()
        data class Failed(val message: String) : VerificationState()
    }

    enum class KeyFileAccessState {
        CHECKING,
        AVAILABLE,
        UNAVAILABLE
    }
}

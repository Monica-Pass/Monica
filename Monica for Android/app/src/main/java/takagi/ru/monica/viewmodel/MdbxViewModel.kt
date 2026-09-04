package takagi.ru.monica.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import takagi.ru.monica.R
import takagi.ru.monica.attachments.data.AttachmentDao
import takagi.ru.monica.attachments.model.Attachment
import takagi.ru.monica.attachments.model.AttachmentDownloadState
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.attachments.model.AttachmentSource
import takagi.ru.monica.attachments.storage.AttachmentStorage
import takagi.ru.monica.data.CustomField
import takagi.ru.monica.data.CustomFieldDao
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.LocalMdbxDatabaseDao
import takagi.ru.monica.data.MdbxCapability
import takagi.ru.monica.data.MdbxRemoteSource
import takagi.ru.monica.data.MdbxRemoteSourceDao
import takagi.ru.monica.data.MdbxEngineType
import takagi.ru.monica.data.MdbxSourceType
import takagi.ru.monica.data.MdbxStorageLocation
import takagi.ru.monica.data.MdbxSyncStatus
import takagi.ru.monica.data.MdbxSyncStateStore
import takagi.ru.monica.data.MdbxTigaMode
import takagi.ru.monica.data.MdbxUnlockMethod
import takagi.ru.monica.data.resolvedActiveFilePath
import takagi.ru.monica.data.supports
import takagi.ru.monica.data.isRemoteSource
import takagi.ru.monica.data.PasskeyDao
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.passkey.PasskeyPrivateKeyStore
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.PasswordEntryDao
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.SecureItemDao
import takagi.ru.monica.mdbx.MdbxDiagLogger
import takagi.ru.monica.repository.MdbxConflictResolution
import takagi.ru.monica.repository.MdbxConflictSummary
import takagi.ru.monica.repository.MdbxCommitDiff
import takagi.ru.monica.repository.MdbxDeltaSummary
import takagi.ru.monica.repository.MdbxApplyResult
import takagi.ru.monica.repository.MdbxBenchmarkResult
import takagi.ru.monica.repository.MdbxHealthRepairApplyResult
import takagi.ru.monica.repository.MdbxHealthRepairBlocker
import takagi.ru.monica.repository.MdbxHealthRepairChoice
import takagi.ru.monica.repository.MdbxHealthRepairDecision
import takagi.ru.monica.repository.MdbxHealthRepairItem
import takagi.ru.monica.repository.MdbxHealthRepairPlan
import takagi.ru.monica.repository.MdbxHealthRepairStatus
import takagi.ru.monica.repository.MdbxSnapshotSummary
import takagi.ru.monica.repository.MdbxStoredAttachment
import takagi.ru.monica.repository.MdbxStoredVaultEntry
import takagi.ru.monica.repository.MdbxAttachmentCekPayload
import takagi.ru.monica.repository.MdbxStructurePreview
import takagi.ru.monica.repository.MdbxSyncBundle
import takagi.ru.monica.repository.MdbxVaultCredential
import takagi.ru.monica.repository.MdbxVaultCrypto
import takagi.ru.monica.repository.MdbxVaultDiagnostics
import takagi.ru.monica.repository.MdbxVaultStore
import takagi.ru.monica.repository.Mdbx2Repository
import takagi.ru.monica.repository.Mdbx2RemoteSyncCoordinator
import takagi.ru.monica.repository.Mdbx2RepositorySyncSessionProvider
import takagi.ru.monica.repository.Mdbx2VaultSessionExecutor
import takagi.ru.monica.repository.MdbxMigrationLifecycle
import takagi.ru.monica.repository.MdbxMigrationPlan
import takagi.ru.monica.repository.MdbxMigrationPlanner
import takagi.ru.monica.repository.MdbxMigrationPreview
import takagi.ru.monica.repository.MdbxMigrationVerification
import takagi.ru.monica.repository.MdbxRepository
import takagi.ru.monica.repository.MdbxRepositoryRouter
import takagi.ru.monica.repository.toPreview
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.sync.SyncDiagnostics
import takagi.ru.monica.sync.SyncMode
import takagi.ru.monica.sync.SyncNetworkPolicy
import takagi.ru.monica.sync.SyncPriority
import takagi.ru.monica.sync.SyncRequest
import takagi.ru.monica.sync.SyncTarget
import takagi.ru.monica.sync.SyncTaskAwaitResult
import takagi.ru.monica.sync.SyncTaskRunner
import takagi.ru.monica.sync.SyncTrigger
import takagi.ru.monica.utils.FileSourceEntry
import takagi.ru.monica.utils.WebDavKeePassFileSource
import takagi.ru.monica.utils.OneDriveAuthManager
import takagi.ru.monica.utils.OneDriveKeePassFileSource
import takagi.ru.monica.utils.OneDriveMdbxFileSource
import takagi.ru.monica.utils.WebDavMdbxFileSource
import takagi.ru.monica.utils.MdbxRemoteTransport
import takagi.ru.monica.utils.MdbxRemoteSyncPaths
import takagi.ru.monica.utils.WebDavMdbxRemoteTransport
import takagi.ru.monica.utils.OneDriveMdbxRemoteTransport
import takagi.ru.monica.util.TotpDataResolver
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.text.Normalizer
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.measureTimeMillis
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal sealed interface MdbxSnapshotCreationPlan {
    data class Create(val fullSnapshot: Boolean) : MdbxSnapshotCreationPlan
    data object ConfirmFullSnapshot : MdbxSnapshotCreationPlan
}

internal fun planMdbxSnapshotCreation(
    requestedFullSnapshot: Boolean,
    engineRequiresFullSnapshot: Boolean,
    currentHeadCommitId: String?,
    latestSnapshotBaseCommitId: String?
): MdbxSnapshotCreationPlan {
    if (requestedFullSnapshot) {
        return MdbxSnapshotCreationPlan.Create(fullSnapshot = true)
    }
    val unchangedSinceLatestSnapshot =
        !currentHeadCommitId.isNullOrBlank() &&
            currentHeadCommitId == latestSnapshotBaseCommitId
    if (unchangedSinceLatestSnapshot) {
        return MdbxSnapshotCreationPlan.ConfirmFullSnapshot
    }
    return MdbxSnapshotCreationPlan.Create(
        fullSnapshot = engineRequiresFullSnapshot
    )
}

class MdbxViewModel(
    application: Application,
    private val databaseDao: LocalMdbxDatabaseDao,
    private val remoteSourceDao: MdbxRemoteSourceDao,
    private val passwordEntryDao: PasswordEntryDao,
    private val secureItemDao: SecureItemDao,
    private val passkeyDao: PasskeyDao,
    private val attachmentDao: AttachmentDao,
    private val customFieldDao: CustomFieldDao,
    private val securityManager: SecurityManager
) : AndroidViewModel(application) {

    private val context: Context get() = getApplication()
    private val roomDatabase by lazy { PasswordDatabase.getDatabase(context.applicationContext) }
    private val attachmentStorage by lazy { AttachmentStorage(context.applicationContext) }
    private val legacyVaultStore = MdbxVaultStore(
        context.applicationContext,
        databaseDao,
        securityManager,
        remoteSourceDao,
        passwordEntryDao,
        secureItemDao,
        customFieldDao
    )
    private val mdbx2Repository = Mdbx2Repository(
        context = context.applicationContext,
        databaseDao = databaseDao,
        securityManager = securityManager,
        passwordEntryDao = passwordEntryDao,
        secureItemDao = secureItemDao,
        customFieldDao = customFieldDao
    )
    private val vaultStore: MdbxRepository = MdbxRepositoryRouter(
        databaseDao = databaseDao,
        legacyRepository = legacyVaultStore,
        rustRepository = mdbx2Repository
    )

    private val mdbx2SyncStateStore by lazy {
        MdbxSyncStateStore(roomDatabase.mdbxSyncStateDao())
    }
    private val mdbx2RemoteSyncCoordinator by lazy {
        Mdbx2RemoteSyncCoordinator(
            rootDirectory = File(context.filesDir, "mdbx2-sync"),
            sessions = Mdbx2RepositorySyncSessionProvider(mdbx2Repository),
            stateStore = mdbx2SyncStateStore
        )
    }

    private val _allDatabasesLoaded = MutableStateFlow(false)
    val allDatabasesLoaded: StateFlow<Boolean> = _allDatabasesLoaded.asStateFlow()

    val allDatabases: StateFlow<List<LocalMdbxDatabase>> = databaseDao.getAllDatabases()
        .onEach { _allDatabasesLoaded.value = true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch(Dispatchers.IO) {
            normalizeExistingRemoteVaultNames()
        }
    }

    private val _operationState = MutableStateFlow<OperationState>(OperationState.Idle)
    val operationState: StateFlow<OperationState> = _operationState.asStateFlow()

    private val _migrationState = MutableStateFlow<MdbxMigrationState>(MdbxMigrationState.Hidden)
    val migrationState: StateFlow<MdbxMigrationState> = _migrationState.asStateFlow()

    private val _conflictCounts = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val conflictCounts: StateFlow<Map<Long, Int>> = _conflictCounts.asStateFlow()

    private val _pendingSyncCounts = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val pendingSyncCounts: StateFlow<Map<Long, Int>> = _pendingSyncCounts.asStateFlow()

    private val _vaultDiagnostics = MutableStateFlow<Map<Long, MdbxVaultDiagnostics>>(emptyMap())
    val vaultDiagnostics: StateFlow<Map<Long, MdbxVaultDiagnostics>> =
        _vaultDiagnostics.asStateFlow()

    private val _conflictDialogState =
        MutableStateFlow<MdbxConflictDialogState>(MdbxConflictDialogState.Hidden)
    val conflictDialogState: StateFlow<MdbxConflictDialogState> =
        _conflictDialogState.asStateFlow()

    private val _deltaDialogState =
        MutableStateFlow<MdbxDeltaDialogState>(MdbxDeltaDialogState.Hidden)
    val deltaDialogState: StateFlow<MdbxDeltaDialogState> =
        _deltaDialogState.asStateFlow()

    private val _advancedDialogState =
        MutableStateFlow<MdbxAdvancedDialogState>(MdbxAdvancedDialogState.Hidden)
    val advancedDialogState: StateFlow<MdbxAdvancedDialogState> =
        _advancedDialogState.asStateFlow()

    private val _healthRepairState =
        MutableStateFlow<MdbxHealthRepairState>(MdbxHealthRepairState.Hidden)
    val healthRepairState: StateFlow<MdbxHealthRepairState> =
        _healthRepairState.asStateFlow()
    private var healthRepairJob: Job? = null

    private val activeVaultPrefs =
        context.applicationContext.getSharedPreferences(ACTIVE_VAULT_PREFS_NAME, Context.MODE_PRIVATE)
    private val _activeMdbxDatabaseId = MutableStateFlow(
        activeVaultPrefs.getLong(ACTIVE_VAULT_ID_KEY, NO_ACTIVE_VAULT_ID)
            .takeIf { it > 0L }
    )
    val activeMdbxDatabaseId: StateFlow<Long?> = _activeMdbxDatabaseId.asStateFlow()
    private var activePreloadJob: Job? = null
    private var activePreloadDatabaseId: Long? = null
    private val activePreloadCompletedAt = ConcurrentHashMap<Long, Long>()
    private val deltaHistoryCache = ConcurrentHashMap<Long, CachedDeltaHistory>()
    private val structurePreviewCache =
        ConcurrentHashMap<SnapshotStructureCacheKey, MdbxStructurePreview>()

    private companion object {
        const val ACTIVE_VAULT_PREFS_NAME = "mdbx_active_vault"
        const val ACTIVE_VAULT_ID_KEY = "last_active_mdbx_database_id"
        const val NO_ACTIVE_VAULT_ID = -1L
        const val ACTIVE_PRELOAD_MIN_INTERVAL_MS = 2_000L
        const val VISIBLE_MDBX_AUTO_SYNC_THROTTLE_MS = 15_000L
        const val MDBX2_FORMAT_VERSION = "MDBX-2"
    }

    private data class CachedDeltaHistory(
        val deltas: List<MdbxDeltaSummary>,
        val snapshots: List<MdbxSnapshotSummary>
    )

    private data class SnapshotStructureCacheKey(
        val databaseId: Long,
        val snapshotId: String
    )

    fun activateMdbxDatabase(databaseId: Long) {
        if (_activeMdbxDatabaseId.value != databaseId) {
            _activeMdbxDatabaseId.value = databaseId
            activeVaultPrefs.edit().putLong(ACTIVE_VAULT_ID_KEY, databaseId).apply()
        }
        viewModelScope.launch(Dispatchers.IO) {
            databaseDao.updateLastAccessedTime(databaseId)
        }
        preloadActiveMdbxDatabase(databaseId)
    }

    fun forgetActiveMdbxDatabaseIf(databaseId: Long) {
        if (_activeMdbxDatabaseId.value == databaseId) {
            _activeMdbxDatabaseId.value = null
            activeVaultPrefs.edit().remove(ACTIVE_VAULT_ID_KEY).apply()
            activePreloadJob?.cancel()
            activePreloadJob = null
            activePreloadDatabaseId = null
            activePreloadCompletedAt.remove(databaseId)
        }
    }

    fun preloadActiveMdbxDatabase(databaseId: Long) {
        val runningJob = activePreloadJob
        if (runningJob?.isActive == true && activePreloadDatabaseId == databaseId) {
            return
        }
        val now = System.currentTimeMillis()
        val hasCachedPreloadState =
            _vaultDiagnostics.value.containsKey(databaseId) && deltaHistoryCache.containsKey(databaseId)
        val lastCompletedAt = activePreloadCompletedAt[databaseId] ?: 0L
        if (hasCachedPreloadState && now - lastCompletedAt < ACTIVE_PRELOAD_MIN_INTERVAL_MS) {
            return
        }
        activePreloadJob?.cancel()
        activePreloadDatabaseId = databaseId
        activePreloadJob = viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            MdbxDiagLogger.append("[MDBX][activePreload] start databaseId=$databaseId")
            try {
                var deltas: List<MdbxDeltaSummary> = emptyList()
                var snapshots: List<MdbxSnapshotSummary> = emptyList()
                val diagnostic = withContext(Dispatchers.IO) {
                    val database = databaseDao.getDatabaseById(databaseId)
                        ?: return@withContext null
                    if (database.engineTypeEnum == MdbxEngineType.RUST_MDBX2) {
                        importEntriesFromVault(database.id)
                    }
                    val diagnostic = vaultStore.getVaultDiagnostics(database.id)
                    deltas = vaultStore.listDeltaHistory(database.id)
                    snapshots = vaultStore.listSnapshots(database.id)
                    diagnostic
                }
                if (diagnostic == null) {
                    forgetActiveMdbxDatabaseIf(databaseId)
                    MdbxDiagLogger.append("[MDBX][activePreload] missing databaseId=$databaseId")
                    return@launch
                }
                if (_activeMdbxDatabaseId.value != databaseId) {
                    MdbxDiagLogger.append("[MDBX][activePreload] discarded databaseId=$databaseId active=${_activeMdbxDatabaseId.value ?: "-"}")
                    return@launch
                }
                applyVaultDiagnostic(databaseId, diagnostic)
                updateDeltaHistoryCache(databaseId, deltas, snapshots)
                activePreloadCompletedAt[databaseId] = System.currentTimeMillis()
                MdbxDiagLogger.append(
                    "[MDBX][activePreload] success databaseId=$databaseId deltas=${deltas.size} snapshots=${snapshots.size} elapsedMs=${System.currentTimeMillis() - startedAt}"
                )
            } catch (e: Exception) {
                MdbxDiagLogger.append(
                    "[MDBX][activePreload] failure databaseId=$databaseId error=${e::class.java.simpleName}:${e.message}"
                )
            } finally {
                if (activePreloadDatabaseId == databaseId) {
                    activePreloadDatabaseId = null
                }
            }
        }
    }

    // --- WebDAV connection ---

    suspend fun testWebDavConnection(
        serverUrl: String,
        username: String,
        password: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val source = WebDavMdbxFileSource(serverUrl, username, password)
        source.testConnection()
    }

    suspend fun listWebDavDirectory(
        serverUrl: String,
        username: String,
        password: String,
        path: String? = null
    ): Result<List<FileSourceEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            val source = WebDavMdbxFileSource(serverUrl, username, password)
            source.listDirectory(path)
        }
    }

    suspend fun readSelectedKeyFile(uri: Uri): Result<MdbxKeyFileSelection> =
        withContext(Dispatchers.IO) {
            runCatching {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalArgumentException("Unable to read selected MDBX key file")
                MdbxKeyFileSelection(
                    uri = uri.toString(),
                    name = queryDisplayName(uri) ?: uri.lastPathSegment ?: "mdbx.key",
                    fingerprint = MdbxVaultCrypto.fingerprint(bytes),
                    bytes = bytes
                )
            }
        }

    suspend fun writeGeneratedKeyFile(targetUri: Uri): Result<MdbxKeyFileSelection> =
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = MdbxVaultCrypto.generateKeyFileBytes()
                context.contentResolver.openOutputStream(targetUri, "wt")?.use { output ->
                    output.write(bytes)
                } ?: throw IllegalArgumentException("Unable to write MDBX key file")
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        targetUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }
                MdbxKeyFileSelection(
                    uri = targetUri.toString(),
                    name = queryDisplayName(targetUri) ?: "monica-mdbx.key",
                    fingerprint = MdbxVaultCrypto.fingerprint(bytes),
                    bytes = bytes
                )
            }
        }

    // --- Vault lifecycle ---

    fun createLocalVault(
        name: String,
        masterPassword: String,
        unlockMethod: MdbxUnlockMethod,
        keyFile: MdbxKeyFileSelection?,
        tigaMode: MdbxTigaMode,
        description: String?,
        customDirectoryUri: Uri? = null,
        engineType: MdbxEngineType = MdbxEngineType.RUST_MDBX2
    ) {
        viewModelScope.launch {
            _operationState.value = OperationState.Loading("Creating local MDBX vault...")
            val requestedName = name.trim()
            MdbxDiagLogger.append(
                "[MDBX][createLocalVault] start name=${requestedName.ifBlank { "<blank>" }} customDir=${customDirectoryUri != null} uri=${customDirectoryUri ?: "-"} unlock=${unlockMethod.name} tiga=${tigaMode.name} engine=${engineType.name}"
            )

            try {
                withContext(Dispatchers.IO) {
                    val displayName = name.trim().ifBlank {
                        throw IllegalArgumentException("Vault name cannot be empty")
                    }
                    val deviceKeyBytes = if (
                        engineType == MdbxEngineType.RUST_MDBX2 &&
                        unlockMethod == MdbxUnlockMethod.DEVICE_KEY
                    ) {
                        MdbxVaultCrypto.generateDeviceKeyBytes()
                    } else {
                        null
                    }
                    val credential = buildCredential(
                        unlockMethod = unlockMethod,
                        masterPassword = masterPassword,
                        keyFile = keyFile,
                        deviceKeyBytes = deviceKeyBytes
                    )
                    val encryptedCredential = when {
                        engineType == MdbxEngineType.RUST_MDBX2 &&
                            unlockMethod == MdbxUnlockMethod.DEVICE_KEY ->
                            MdbxVaultCrypto.encodeDeviceKey(
                                bytes = deviceKeyBytes ?: error("MDBX device key was not generated"),
                                encrypt = securityManager::encryptData
                            )
                        credential.requiresPassword() -> masterPassword
                            .let(::normalizeMdbxPassword)
                            .let(securityManager::encryptData)
                        else -> null
                    }
                    val customDirVault = customDirectoryUri
                        ?.let { uri ->
                        createVaultFileInCustomDir(
                            treeUri = uri,
                            displayName = displayName,
                            tigaMode = tigaMode.name,
                            credential = credential,
                            engineType = engineType
                        )
                    }
                    val localVaultFile = customDirVault?.localCopy ?: run {
                        when (engineType) {
                            MdbxEngineType.KOTLIN_MDBX1 -> legacyVaultStore.createInitializedVaultFile(
                                displayName = displayName,
                                tigaMode = tigaMode.name,
                                unlockMethod = unlockMethod,
                                credential = credential
                            )
                            MdbxEngineType.RUST_MDBX2 -> mdbx2Repository.createInitializedVaultFile(
                                tigaMode = tigaMode,
                                credential = credential
                            )
                        }
                    }
                    val storageLocation = if (customDirVault != null) {
                        MdbxStorageLocation.EXTERNAL
                    } else {
                        MdbxStorageLocation.INTERNAL
                    }
                    val sourceType = if (customDirVault != null) {
                        MdbxSourceType.LOCAL_EXTERNAL
                    } else {
                        MdbxSourceType.LOCAL_INTERNAL
                    }
                    val filePath = customDirVault?.externalUri?.toString() ?: localVaultFile.absolutePath
                    try {
                        databaseDao.insertDatabase(
                            LocalMdbxDatabase(
                            name = displayName,
                            filePath = filePath,
                            storageLocation = storageLocation.name,
                            sourceType = sourceType.name,
                            sourceId = null,
                            engineType = engineType.name,
                            tigaMode = tigaMode.name,
                            encryptedPassword = encryptedCredential,
                            unlockMethod = unlockMethod.storedValue,
                            kdfProfile = if (engineType == MdbxEngineType.RUST_MDBX2) {
                                "argon2id-mdbx2"
                            } else {
                                "pbkdf2-sha256"
                            },
                            keyFileName = keyFile?.name,
                            keyFileUri = keyFile?.uri,
                            keyFileFingerprint = keyFile?.fingerprint,
                            description = description,
                            lastSyncedAt = customDirVault?.let { System.currentTimeMillis() },
                            workingCopyPath = localVaultFile.absolutePath,
                            cacheCopyPath = localVaultFile.absolutePath,
                            externalTreeUri = customDirVault?.externalTreeUri,
                            isOfflineAvailable = true,
                            lastSyncStatus = if (customDirVault == null) {
                                MdbxSyncStatus.LOCAL_ONLY.name
                            } else {
                                MdbxSyncStatus.IN_SYNC.name
                            }
                            )
                        )
                    } catch (error: Throwable) {
                        if (engineType == MdbxEngineType.RUST_MDBX2) {
                            val cleaned = mdbx2Repository.deleteOwnedVaultFile(localVaultFile)
                            customDirVault?.externalDocument?.let { externalDocument ->
                                runCatching {
                                    mdbx2Repository.deleteCreatedExternalDocument(externalDocument)
                                }
                            }
                            MdbxDiagLogger.append(
                                "[MDBX2][create] room_insert_failed cleanup=$cleaned cause=${error::class.java.simpleName}"
                            )
                        }
                        throw error
                    }
                    MdbxDiagLogger.append(
                        "[MDBX][createLocalVault] inserted sourceType=${sourceType.name} storage=${storageLocation.name}"
                    )
                }

                _operationState.value = OperationState.Success(
                    "Local MDBX vault \"$name\" created"
                )
                MdbxDiagLogger.append(
                    "[MDBX][createLocalVault] success"
                )
            } catch (e: Exception) {
                MdbxDiagLogger.append(
                    "[MDBX][createLocalVault] failure error=${e::class.java.simpleName}"
                )
                _operationState.value = OperationState.Error(
                    "Failed to create local vault: ${e.message ?: "unknown error"}"
                )
            }
        }
    }

    fun prepareMdbx2Migration(databaseId: Long) {
        viewModelScope.launch {
            _migrationState.value = MdbxMigrationState.Preparing(databaseId)
            var sensitivePlan: MdbxMigrationPlan? = null
            try {
                val currentPlan = withContext(Dispatchers.IO) { buildMigrationPlan(databaseId) }
                sensitivePlan = currentPlan
                _migrationState.value = MdbxMigrationState.Ready(currentPlan.toPreview())
            } catch (error: Throwable) {
                _migrationState.value = MdbxMigrationState.Error(
                    sourceDatabaseId = databaseId,
                    message = error.message ?: "Unable to inspect the source vault"
                )
            } finally {
                sensitivePlan?.clearAttachmentBlobs()
            }
        }
    }

    fun startMdbx2Migration(
        sourceDatabaseId: Long,
        targetName: String,
        targetPassword: String
    ) {
        if (_migrationState.value is MdbxMigrationState.Running) return
        viewModelScope.launch {
            var sensitivePlan: MdbxMigrationPlan? = null
            var preview: MdbxMigrationPreview? = null
            try {
                val source = withContext(Dispatchers.IO) {
                    databaseDao.getDatabaseById(sourceDatabaseId)
                        ?: throw IllegalStateException("Source vault not found")
                }
                val normalizedTargetName = targetName.trim().ifBlank {
                    throw IllegalArgumentException("Target vault name cannot be empty")
                }
                val sourceFingerprint = withContext(Dispatchers.IO) { fingerprintSourceVault(source) }
                _migrationState.value = MdbxMigrationState.Running(
                    sourceDatabaseId,
                    normalizedTargetName,
                    MdbxMigrationStage.PREFLIGHT,
                    0,
                    1
                )
                val currentPlan = withContext(Dispatchers.IO) { buildMigrationPlan(sourceDatabaseId) }
                sensitivePlan = currentPlan
                preview = currentPlan.toPreview()
                check(currentPlan.isEligible) { "Source vault did not pass migration preflight" }

                val result = withContext(Dispatchers.IO) {
                    MdbxMigrationLifecycle.withIsolatedTarget(
                        createTarget = {
                            createMdbx2MigrationTarget(
                                source = source,
                                name = normalizedTargetName,
                                password = targetPassword,
                                folderCount = currentPlan.folders.size
                            )
                        },
                        cleanupTarget = { target -> deleteVaultPersistence(target) },
                        migrateAndVerify = { target ->
                            _migrationState.value = MdbxMigrationState.Running(
                                sourceDatabaseId,
                                normalizedTargetName,
                                MdbxMigrationStage.FOLDERS,
                                0,
                                currentPlan.folders.size
                            )
                            val targetFolderIds = mdbx2Repository.createMigrationFolders(
                                target.id,
                                currentPlan.folders
                            )

                            _migrationState.value = MdbxMigrationState.Running(
                                sourceDatabaseId,
                                normalizedTargetName,
                                MdbxMigrationStage.ENTRIES,
                                0,
                                currentPlan.entries.size
                            )
                            mdbx2Repository.importMigrationEntries(
                                target.id,
                                currentPlan.entries,
                                targetFolderIds
                            )

                            _migrationState.value = MdbxMigrationState.Running(
                                sourceDatabaseId,
                                normalizedTargetName,
                                MdbxMigrationStage.ATTACHMENTS,
                                0,
                                currentPlan.attachments.size
                            )
                            mdbx2Repository.importMigrationAttachments(
                                target.id,
                                currentPlan.attachments
                            ) { completed, total ->
                                _migrationState.value = MdbxMigrationState.Running(
                                    sourceDatabaseId,
                                    normalizedTargetName,
                                    MdbxMigrationStage.ATTACHMENTS,
                                    completed,
                                    total
                                )
                            }

                            _migrationState.value = MdbxMigrationState.Running(
                                sourceDatabaseId,
                                normalizedTargetName,
                                MdbxMigrationStage.VERIFYING,
                                0,
                                1
                            )
                            val verification = mdbx2Repository.verifyMigration(
                                target.id,
                                currentPlan,
                                targetFolderIds
                            )
                            checkSourceVaultUnchanged(source, sourceFingerprint)

                            _migrationState.value = MdbxMigrationState.Running(
                                sourceDatabaseId,
                                normalizedTargetName,
                                MdbxMigrationStage.IMPORTING,
                                0,
                                1
                            )
                            importEntriesFromVault(target.id)
                            databaseDao.updateProjectCount(target.id, verification.folderCount)
                            target to verification
                        }
                    )
                }
                invalidateMdbxViewCaches(result.first.id)
                _migrationState.value = MdbxMigrationState.Success(
                    sourceDatabaseId = sourceDatabaseId,
                    targetDatabaseId = result.first.id,
                    targetName = result.first.name,
                    verification = result.second
                )
            } catch (error: Throwable) {
                _migrationState.value = MdbxMigrationState.Error(
                    sourceDatabaseId = sourceDatabaseId,
                    message = error.message ?: "MDBX2 migration failed",
                    preview = preview
                )
            } finally {
                sensitivePlan?.clearAttachmentBlobs()
            }
        }
    }

    fun dismissMdbxMigration() {
        if (_migrationState.value !is MdbxMigrationState.Running) {
            _migrationState.value = MdbxMigrationState.Hidden
        }
    }

    private suspend fun buildMigrationPlan(databaseId: Long): MdbxMigrationPlan {
        val source = databaseDao.getDatabaseById(databaseId)
            ?: throw IllegalStateException("Source vault not found")
        return MdbxMigrationPlanner.build(
            source = source,
            folders = vaultStore.listFolders(databaseId),
            entries = vaultStore.readStoredEntries(databaseId),
            attachments = vaultStore.readStoredAttachments(databaseId)
        )
    }

    private suspend fun createMdbx2MigrationTarget(
        source: LocalMdbxDatabase,
        name: String,
        password: String,
        folderCount: Int
    ): LocalMdbxDatabase {
        val vaultFile = mdbx2Repository.createInitializedVaultFile(source.tigaModeEnum, password)
        val target = LocalMdbxDatabase(
            name = name,
            filePath = vaultFile.absolutePath,
            storageLocation = MdbxStorageLocation.INTERNAL.name,
            sourceType = MdbxSourceType.LOCAL_INTERNAL.name,
            engineType = MdbxEngineType.RUST_MDBX2.name,
            tigaMode = source.tigaModeEnum.name,
            encryptedPassword = securityManager.encryptData(normalizeMdbxPassword(password)),
            unlockMethod = MdbxUnlockMethod.MASTER_PASSWORD.storedValue,
            kdfProfile = "argon2id-mdbx2",
            description = "Migrated from MDBX1: ${source.name}",
            projectCount = folderCount,
            workingCopyPath = vaultFile.absolutePath,
            cacheCopyPath = vaultFile.absolutePath,
            isOfflineAvailable = true,
            lastSyncStatus = MdbxSyncStatus.LOCAL_ONLY.name
        )
        return try {
            val targetId = databaseDao.insertDatabase(target)
            check(targetId > 0L) { "Unable to register the MDBX2 target vault" }
            target.copy(id = targetId)
        } catch (error: Throwable) {
            val cleaned = mdbx2Repository.deleteOwnedVaultFile(vaultFile)
            if (!cleaned) {
                error.addSuppressed(IllegalStateException("Unable to clean the target vault file"))
            }
            throw error
        }
    }

    private fun fingerprintSourceVault(source: LocalMdbxDatabase): ByteArray? {
        val file = source.workingCopyPath?.let(::File)
            ?: source.filePath.takeIf {
                source.sourceTypeEnum == MdbxSourceType.LOCAL_INTERNAL
            }?.let(::File)
        if (file?.isFile != true) return null
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }

    private fun checkSourceVaultUnchanged(source: LocalMdbxDatabase, before: ByteArray?) {
        if (before == null) return
        val after = fingerprintSourceVault(source)
        check(after != null && before.contentEquals(after)) {
            "Source vault changed during migration; the target was discarded"
        }
    }

    private fun MdbxMigrationPlan.clearAttachmentBlobs() {
        attachments.forEach { it.attachment.blob.fill(0) }
    }

    fun importLocalVault(
        sourceUri: Uri,
        name: String?,
        masterPassword: String,
        unlockMethod: MdbxUnlockMethod,
        keyFile: MdbxKeyFileSelection?,
        tigaMode: MdbxTigaMode,
        description: String?
    ) {
        viewModelScope.launch {
            _operationState.value = OperationState.Loading("Opening local MDBX vault...")

            try {
                withContext(Dispatchers.IO) {
                    val sourceName = queryDisplayName(sourceUri) ?: "imported-${UUID.randomUUID()}.mdbx"
                    val displayName = name?.trim()?.takeIf { it.isNotBlank() }
                        ?: remoteVaultDisplayName(sourceName)
                    val fileName = if (sourceName.endsWith(".mdbx", ignoreCase = true)) {
                        sourceName
                    } else {
                        "$displayName.mdbx"
                    }

                    // Take persistent URI permissions (read + write)
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            sourceUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    }.onFailure { error ->
                        android.util.Log.w("MdbxViewModel", "Persistable permission not granted", error)
                    }

                    val mdbx2Candidate = mdbx2Repository.copyExternalDocumentToOwnedFile(sourceUri)
                    val isMdbx2 = runCatching {
                        mdbx2Repository.inspectVaultFormat(mdbx2Candidate)
                    }.getOrNull().equals(MDBX2_FORMAT_VERSION, ignoreCase = true)
                    if (isMdbx2) {
                        importMdbx2LocalVault(
                            sourceUri = sourceUri,
                            displayName = displayName,
                            workingCopy = mdbx2Candidate,
                            masterPassword = masterPassword,
                            unlockMethod = unlockMethod,
                            keyFile = keyFile,
                            tigaMode = tigaMode,
                            description = description
                        )
                        return@withContext
                    }
                    mdbx2Repository.deleteOwnedVaultFile(mdbx2Candidate)

                    // Verify source file is readable
                    val sourceBytes = context.contentResolver.openInputStream(sourceUri)?.use { input ->
                        input.readBytes()
                    } ?: throw IllegalArgumentException("Unable to read selected MDBX file")

                    // Write working copy and verify
                    val vaultDir = File(context.filesDir, "mdbx")
                    check(vaultDir.mkdirs() || vaultDir.exists()) {
                        "Unable to create MDBX directory"
                    }
                    val workingCopy = File(vaultDir, "${UUID.randomUUID()}-$fileName")
                    workingCopy.writeBytes(sourceBytes)
                    if (workingCopy.length() != sourceBytes.size.toLong()) {
                        workingCopy.delete()
                        throw IllegalArgumentException(
                            "File copy verification failed: source=${sourceBytes.size} bytes, copy=${workingCopy.length()} bytes"
                        )
                    }

                    // Validate and detect actual Tiga mode from existing vault
                    try {
                        legacyVaultStore.validateExistingVaultFile(workingCopy)
                    } catch (e: Exception) {
                        workingCopy.delete()
                        throw e
                    }
                    val detectedMode = legacyVaultStore.readTigaModeFromVaultFile(workingCopy)
                    val detectedUnlockMethod = legacyVaultStore.readUnlockMethodFromVaultFile(workingCopy)
                    val credential = buildCredential(detectedUnlockMethod, masterPassword, keyFile)
                    legacyVaultStore.validateVaultCredentialFile(workingCopy, credential)
                    legacyVaultStore.prepareVaultForOfficialMdbx1(workingCopy, credential, detectedMode)

                    val encryptedMasterPassword =
                        masterPassword.takeIf { credential.requiresPassword() }
                            ?.let { securityManager.encryptData(normalizeMdbxPassword(it)) }
                    databaseDao.insertDatabase(
                        LocalMdbxDatabase(
                            name = displayName,
                            filePath = sourceUri.toString(),
                            storageLocation = MdbxStorageLocation.EXTERNAL.name,
                            sourceType = MdbxSourceType.LOCAL_EXTERNAL.name,
                            sourceId = null,
                            tigaMode = detectedMode.name,
                            encryptedPassword = encryptedMasterPassword,
                            unlockMethod = detectedUnlockMethod.storedValue,
                            kdfProfile = "pbkdf2-sha256",
                            keyFileName = keyFile?.name,
                            keyFileUri = keyFile?.uri,
                            keyFileFingerprint = keyFile?.fingerprint,
                            description = description,
                            lastSyncedAt = System.currentTimeMillis(),
                            workingCopyPath = workingCopy.absolutePath,
                            cacheCopyPath = workingCopy.absolutePath,
                            isOfflineAvailable = true,
                            lastSyncStatus = MdbxSyncStatus.IN_SYNC.name
                        )
                    ).also { databaseId ->
                        vaultStore.flushWorkingCopy(databaseId)
                        importEntriesFromVault(databaseId)
                    }
                }

                _operationState.value = OperationState.Success("Local MDBX vault opened")
            } catch (e: Exception) {
                _operationState.value = OperationState.Error(
                    "Failed to open local vault: ${e.message ?: "unknown error"}"
                )
            }
        }
    }

    fun createWebDavVault(
        name: String,
        masterPassword: String,
        unlockMethod: MdbxUnlockMethod,
        keyFile: MdbxKeyFileSelection?,
        tigaMode: MdbxTigaMode,
        serverUrl: String,
        username: String,
        webDavPassword: String,
        remoteDirectoryPath: String?,
        description: String?,
        engineType: MdbxEngineType = MdbxEngineType.RUST_MDBX2
    ) {
        viewModelScope.launch {
            _operationState.value = OperationState.Loading("Creating MDBX vault on WebDAV...")

            try {
                withContext(Dispatchers.IO) {
                    if (engineType == MdbxEngineType.RUST_MDBX2) {
                        createMdbx2WebDavVault(
                            name = name,
                            masterPassword = masterPassword,
                            tigaMode = tigaMode,
                            serverUrl = serverUrl,
                            username = username,
                            webDavPassword = webDavPassword,
                            remoteDirectoryPath = remoteDirectoryPath,
                            description = description
                        )
                        return@withContext
                    }
                    val normalizedDir = WebDavKeePassFileSource.normalizeOptionalRemotePath(
                        remoteDirectoryPath
                    )
                    val fileSource = WebDavMdbxFileSource(serverUrl, username, webDavPassword)

                    fileSource.testConnection().getOrThrow()

                    val displayName = name.trim().ifBlank {
                        throw IllegalArgumentException("Vault name cannot be empty")
                    }
                    val credential = buildCredential(unlockMethod, masterPassword, keyFile)
                    val remoteFileName = if (displayName.endsWith(".mdbx", ignoreCase = true)) {
                        displayName
                    } else {
                        "$displayName.mdbx"
                    }

                    val localVaultFile = legacyVaultStore.createInitializedVaultFile(
                        displayName = displayName,
                        tigaMode = tigaMode.name,
                        unlockMethod = unlockMethod,
                        credential = credential
                    )

                    fileSource.writeFile(
                        parentPath = normalizedDir.ifBlank { null },
                        name = remoteFileName,
                        bytes = localVaultFile.readBytes()
                    )

                    val remotePath = WebDavKeePassFileSource.buildChildPath(
                        normalizedDir, remoteFileName
                    )

                    // Encrypt credentials
                    val encryptedUsername = securityManager.encryptData(username)
                    val encryptedPassword = securityManager.encryptData(webDavPassword)

                    // Create remote source record
                    val sourceId = remoteSourceDao.insertSource(
                        MdbxRemoteSource(
                            displayName = displayName,
                            remotePath = remotePath,
                            remoteParentPath = normalizedDir.ifBlank { null },
                            baseUrl = serverUrl.trim().trimEnd('/'),
                            usernameEncrypted = encryptedUsername,
                            passwordEncrypted = encryptedPassword
                        )
                    )

                    // Encrypt master password
                    val encryptedMasterPassword =
                        masterPassword.takeIf { credential.requiresPassword() }
                            ?.let { securityManager.encryptData(normalizeMdbxPassword(it)) }

                    // Create database record
                    databaseDao.insertDatabase(
                        LocalMdbxDatabase(
                            name = displayName,
                            filePath = remotePath,
                            storageLocation = MdbxStorageLocation.REMOTE_WEBDAV.name,
                            sourceType = MdbxSourceType.REMOTE_WEBDAV.name,
                            sourceId = sourceId,
                            tigaMode = tigaMode.name,
                            encryptedPassword = encryptedMasterPassword,
                            unlockMethod = unlockMethod.storedValue,
                            kdfProfile = "pbkdf2-sha256",
                            keyFileName = keyFile?.name,
                            keyFileUri = keyFile?.uri,
                            keyFileFingerprint = keyFile?.fingerprint,
                            description = description,
                            lastSyncedAt = System.currentTimeMillis(),
                            workingCopyPath = localVaultFile.absolutePath,
                            cacheCopyPath = localVaultFile.absolutePath,
                            isOfflineAvailable = true,
                            lastSyncStatus = MdbxSyncStatus.IN_SYNC.name
                        )
                    ).also { databaseId ->
                        importEntriesFromVault(databaseId)
                    }
                }

                _operationState.value = OperationState.Success(
                    "MDBX vault \"$name\" created on WebDAV"
                )
            } catch (e: Exception) {
                _operationState.value = OperationState.Error(
                    "Failed to create vault: ${e.message ?: "unknown error"}"
                )
            }
        }
    }

    fun connectToExistingWebDavVault(
        masterPassword: String,
        unlockMethod: MdbxUnlockMethod,
        keyFile: MdbxKeyFileSelection?,
        tigaMode: MdbxTigaMode,
        serverUrl: String,
        username: String,
        webDavPassword: String,
        remoteFilePath: String,
        description: String?,
        engineType: MdbxEngineType = MdbxEngineType.KOTLIN_MDBX1
    ) {
        val displayName = remoteVaultDisplayName(remoteFilePath)
        viewModelScope.launch {
            _operationState.value = OperationState.Loading("Connecting to remote MDBX vault...")

            try {
                withContext(Dispatchers.IO) {
                    if (engineType == MdbxEngineType.RUST_MDBX2) {
                        connectToMdbx2WebDavVault(
                            name = displayName,
                            masterPassword = masterPassword,
                            serverUrl = serverUrl,
                            username = username,
                            webDavPassword = webDavPassword,
                            remoteFilePath = remoteFilePath,
                            description = description
                        )
                        return@withContext
                    }
                    val fileSource = WebDavMdbxFileSource(serverUrl, username, webDavPassword)
                    fileSource.testConnection().getOrThrow()

                    val remoteBytes = fileSource.readFile(remoteFilePath)

                    val vaultDir = File(context.filesDir, "mdbx")
                    check(vaultDir.mkdirs() || vaultDir.exists()) {
                        "Unable to create MDBX directory"
                    }
                    val localFile = File(vaultDir, "remote_${UUID.randomUUID()}.mdbx")
                    localFile.writeBytes(remoteBytes)

                    legacyVaultStore.validateExistingVaultFile(localFile)
                    val detectedMode = legacyVaultStore.readTigaModeFromVaultFile(localFile)
                    val detectedUnlockMethod = legacyVaultStore.readUnlockMethodFromVaultFile(localFile)
                    val credential = buildCredential(detectedUnlockMethod, masterPassword, keyFile)
                    legacyVaultStore.validateVaultCredentialFile(localFile, credential)
                    legacyVaultStore.prepareVaultForOfficialMdbx1(localFile, credential, detectedMode)

                    val remoteParentPath = WebDavKeePassFileSource.parentPathOf(remoteFilePath)

                    val encryptedUsername = securityManager.encryptData(username)
                    val encryptedPassword = securityManager.encryptData(webDavPassword)

                    val sourceId = remoteSourceDao.insertSource(
                        MdbxRemoteSource(
                            displayName = displayName,
                            remotePath = remoteFilePath,
                            remoteParentPath = remoteParentPath,
                            baseUrl = serverUrl.trim().trimEnd('/'),
                            usernameEncrypted = encryptedUsername,
                            passwordEncrypted = encryptedPassword
                        )
                    )

                    val encryptedMasterPassword =
                        masterPassword.takeIf { credential.requiresPassword() }
                            ?.let { securityManager.encryptData(normalizeMdbxPassword(it)) }
                    databaseDao.insertDatabase(
                        LocalMdbxDatabase(
                            name = displayName,
                            filePath = remoteFilePath,
                            storageLocation = MdbxStorageLocation.REMOTE_WEBDAV.name,
                            sourceType = MdbxSourceType.REMOTE_WEBDAV.name,
                            sourceId = sourceId,
                            tigaMode = detectedMode.name,
                            encryptedPassword = encryptedMasterPassword,
                            unlockMethod = detectedUnlockMethod.storedValue,
                            kdfProfile = "pbkdf2-sha256",
                            keyFileName = keyFile?.name,
                            keyFileUri = keyFile?.uri,
                            keyFileFingerprint = keyFile?.fingerprint,
                            description = description,
                            lastSyncedAt = System.currentTimeMillis(),
                            workingCopyPath = localFile.absolutePath,
                            cacheCopyPath = localFile.absolutePath,
                            isOfflineAvailable = true,
                            lastSyncStatus = MdbxSyncStatus.IN_SYNC.name
                        )
                    ).also { databaseId ->
                        vaultStore.flushWorkingCopy(databaseId)
                        importEntriesFromVault(databaseId)
                    }
                }

                _operationState.value = OperationState.Success(
                    "Connected to remote MDBX vault \"$displayName\""
                )
            } catch (e: Exception) {
                _operationState.value = OperationState.Error(
                    "Failed to connect to remote vault: ${e.message ?: "unknown error"}"
                )
            }
        }
    }

    private fun remoteVaultDisplayName(remoteFilePath: String): String {
        val fileName = remoteFilePath
            .trim()
            .trimEnd('/')
            .substringAfterLast('/')
        return if (fileName.endsWith(".mdbx", ignoreCase = true)) {
            fileName.dropLast(5)
        } else {
            fileName
        }.ifBlank { "MDBX Vault" }
    }

    private suspend fun normalizeExistingRemoteVaultNames() {
        databaseDao.getAllDatabasesSnapshot()
            .filter { database ->
                database.sourceTypeEnum == MdbxSourceType.REMOTE_WEBDAV ||
                    database.sourceTypeEnum == MdbxSourceType.REMOTE_ONEDRIVE
            }
            .forEach { database ->
                val displayName = remoteVaultDisplayName(database.filePath)
                if (database.name != displayName) {
                    databaseDao.updateDatabase(database.copy(name = displayName))
                }
                database.sourceId?.let { sourceId ->
                    remoteSourceDao.getSourceById(sourceId)?.let { source ->
                        if (source.displayName != displayName) {
                            remoteSourceDao.updateSource(
                                source.copy(
                                    displayName = displayName,
                                    updatedAt = System.currentTimeMillis()
                                )
                            )
                        }
                    }
                }
            }
    }

    data class OneDriveMdbxDirectoryListing(
        val currentPath: String,
        val entries: List<FileSourceEntry>
    )

    suspend fun listOneDriveMdbxDirectory(
        accountId: String,
        currentPath: String?
    ): Result<OneDriveMdbxDirectoryListing> = withContext(Dispatchers.IO) {
        runCatching {
            val normalizedPath = OneDriveKeePassFileSource.normalizeOptionalRemotePath(currentPath)
            val entries = OneDriveMdbxFileSource(context, accountId).listDirectory(normalizedPath)
            OneDriveMdbxDirectoryListing(
                currentPath = normalizedPath,
                entries = entries
            )
        }
    }

    fun createOneDriveVault(
        name: String,
        masterPassword: String,
        unlockMethod: MdbxUnlockMethod,
        keyFile: MdbxKeyFileSelection?,
        tigaMode: MdbxTigaMode,
        accountId: String,
        accountLabel: String,
        directoryPath: String?,
        description: String?,
        engineType: MdbxEngineType = MdbxEngineType.RUST_MDBX2
    ) {
        viewModelScope.launch {
            _operationState.value = OperationState.Loading("Creating MDBX vault on OneDrive...")

            try {
                withContext(Dispatchers.IO) {
                    if (engineType == MdbxEngineType.RUST_MDBX2) {
                        createMdbx2OneDriveVault(
                            name = name,
                            masterPassword = masterPassword,
                            tigaMode = tigaMode,
                            accountId = accountId,
                            directoryPath = directoryPath,
                            description = description
                        )
                        return@withContext
                    }
                    val normalizedDir = OneDriveKeePassFileSource.normalizeOptionalRemotePath(directoryPath)
                    val fileSource = OneDriveMdbxFileSource(context, accountId)

                    fileSource.testConnection().getOrThrow()

                    val displayName = name.trim().ifBlank {
                        throw IllegalArgumentException("Vault name cannot be empty")
                    }
                    val credential = buildCredential(unlockMethod, masterPassword, keyFile)
                    val remoteFileName = if (displayName.endsWith(".mdbx", ignoreCase = true)) {
                        displayName
                    } else {
                        "$displayName.mdbx"
                    }

                    val localVaultFile = legacyVaultStore.createInitializedVaultFile(
                        displayName = displayName,
                        tigaMode = tigaMode.name,
                        unlockMethod = unlockMethod,
                        credential = credential
                    )

                    fileSource.writeFile(
                        parentPath = normalizedDir.ifBlank { null },
                        name = remoteFileName,
                        bytes = localVaultFile.readBytes()
                    )

                    val remotePath = OneDriveKeePassFileSource.buildChildPath(normalizedDir, remoteFileName)

                    val encryptedAccountId = securityManager.encryptData(accountId)
                    val accessTokenSession = OneDriveAuthManager(context).acquireAccessToken(accountId)
                    val encryptedAccessToken = securityManager.encryptData(
                        accessTokenSession.accessToken ?: throw IllegalStateException("OneDrive access token unavailable")
                    )

                    val sourceId = remoteSourceDao.insertSource(
                        MdbxRemoteSource(
                            displayName = displayName,
                            remotePath = remotePath,
                            remoteParentPath = normalizedDir.ifBlank { null },
                            baseUrl = null,
                            usernameEncrypted = encryptedAccountId,
                            passwordEncrypted = encryptedAccessToken
                        )
                    )

                    val encryptedMasterPassword =
                        masterPassword.takeIf { credential.requiresPassword() }
                            ?.let { securityManager.encryptData(normalizeMdbxPassword(it)) }

                    databaseDao.insertDatabase(
                        LocalMdbxDatabase(
                            name = displayName,
                            filePath = remotePath,
                            storageLocation = MdbxStorageLocation.REMOTE_WEBDAV.name,
                            sourceType = MdbxSourceType.REMOTE_ONEDRIVE.name,
                            sourceId = sourceId,
                            tigaMode = tigaMode.name,
                            encryptedPassword = encryptedMasterPassword,
                            unlockMethod = unlockMethod.storedValue,
                            kdfProfile = "pbkdf2-sha256",
                            keyFileName = keyFile?.name,
                            keyFileUri = keyFile?.uri,
                            keyFileFingerprint = keyFile?.fingerprint,
                            description = description,
                            lastSyncedAt = System.currentTimeMillis(),
                            workingCopyPath = localVaultFile.absolutePath,
                            cacheCopyPath = localVaultFile.absolutePath,
                            isOfflineAvailable = true,
                            lastSyncStatus = MdbxSyncStatus.IN_SYNC.name
                        )
                    ).also { databaseId ->
                        importEntriesFromVault(databaseId)
                    }
                }

                _operationState.value = OperationState.Success(
                    "MDBX vault \"$name\" created on OneDrive"
                )
            } catch (e: Exception) {
                _operationState.value = OperationState.Error(
                    "Failed to create vault on OneDrive: ${e.message ?: "unknown error"}"
                )
            }
        }
    }

    fun connectToOneDriveVault(
        masterPassword: String,
        unlockMethod: MdbxUnlockMethod,
        keyFile: MdbxKeyFileSelection?,
        tigaMode: MdbxTigaMode,
        accountId: String,
        accountLabel: String,
        remoteFilePath: String,
        description: String?,
        engineType: MdbxEngineType = MdbxEngineType.KOTLIN_MDBX1
    ) {
        val displayName = remoteVaultDisplayName(remoteFilePath)
        viewModelScope.launch {
            _operationState.value = OperationState.Loading("Connecting to OneDrive MDBX vault...")

            try {
                withContext(Dispatchers.IO) {
                    if (engineType == MdbxEngineType.RUST_MDBX2) {
                        connectToMdbx2OneDriveVault(
                            name = displayName,
                            masterPassword = masterPassword,
                            accountId = accountId,
                            remoteFilePath = remoteFilePath,
                            description = description
                        )
                        return@withContext
                    }
                    val fileSource = OneDriveMdbxFileSource(context, accountId)
                    fileSource.testConnection().getOrThrow()

                    val remoteBytes = fileSource.readFile(remoteFilePath)

                    val vaultDir = File(context.filesDir, "mdbx")
                    check(vaultDir.mkdirs() || vaultDir.exists()) {
                        "Unable to create MDBX directory"
                    }
                    val localFile = File(vaultDir, "onedrive_${UUID.randomUUID()}.mdbx")
                    localFile.writeBytes(remoteBytes)

                    legacyVaultStore.validateExistingVaultFile(localFile)
                    val detectedMode = legacyVaultStore.readTigaModeFromVaultFile(localFile)
                    val detectedUnlockMethod = legacyVaultStore.readUnlockMethodFromVaultFile(localFile)
                    val credential = buildCredential(detectedUnlockMethod, masterPassword, keyFile)
                    legacyVaultStore.validateVaultCredentialFile(localFile, credential)
                    legacyVaultStore.prepareVaultForOfficialMdbx1(localFile, credential, detectedMode)

                    val remoteParentPath = OneDriveKeePassFileSource.parentPathOf(remoteFilePath)

                    val encryptedAccountId = securityManager.encryptData(accountId)
                    val accessTokenSession = OneDriveAuthManager(context).acquireAccessToken(accountId)
                    val encryptedAccessToken = securityManager.encryptData(
                        accessTokenSession.accessToken ?: throw IllegalStateException("OneDrive access token unavailable")
                    )

                    val sourceId = remoteSourceDao.insertSource(
                        MdbxRemoteSource(
                            displayName = displayName,
                            remotePath = remoteFilePath,
                            remoteParentPath = remoteParentPath,
                            baseUrl = null,
                            usernameEncrypted = encryptedAccountId,
                            passwordEncrypted = encryptedAccessToken
                        )
                    )

                    val encryptedMasterPassword =
                        masterPassword.takeIf { credential.requiresPassword() }
                            ?.let { securityManager.encryptData(normalizeMdbxPassword(it)) }
                    databaseDao.insertDatabase(
                        LocalMdbxDatabase(
                            name = displayName,
                            filePath = remoteFilePath,
                            storageLocation = MdbxStorageLocation.REMOTE_WEBDAV.name,
                            sourceType = MdbxSourceType.REMOTE_ONEDRIVE.name,
                            sourceId = sourceId,
                            tigaMode = detectedMode.name,
                            encryptedPassword = encryptedMasterPassword,
                            unlockMethod = detectedUnlockMethod.storedValue,
                            kdfProfile = "pbkdf2-sha256",
                            keyFileName = keyFile?.name,
                            keyFileUri = keyFile?.uri,
                            keyFileFingerprint = keyFile?.fingerprint,
                            description = description,
                            lastSyncedAt = System.currentTimeMillis(),
                            workingCopyPath = localFile.absolutePath,
                            cacheCopyPath = localFile.absolutePath,
                            isOfflineAvailable = true,
                            lastSyncStatus = MdbxSyncStatus.IN_SYNC.name
                        )
                    ).also { databaseId ->
                        vaultStore.flushWorkingCopy(databaseId)
                        importEntriesFromVault(databaseId)
                    }
                }

                _operationState.value = OperationState.Success(
                    "Connected to OneDrive MDBX vault \"$displayName\""
                )
            } catch (e: Exception) {
                _operationState.value = OperationState.Error(
                    "Failed to connect to OneDrive vault: ${e.message ?: "unknown error"}"
                )
            }
        }
    }

    private suspend fun createMdbx2WebDavVault(
        name: String,
        masterPassword: String,
        tigaMode: MdbxTigaMode,
        serverUrl: String,
        username: String,
        webDavPassword: String,
        remoteDirectoryPath: String?,
        description: String?
    ) {
        require(masterPassword.isNotBlank()) { "MDBX2 requires a master password" }
        val normalizedDir = WebDavKeePassFileSource.normalizeOptionalRemotePath(remoteDirectoryPath)
        val displayName = name.trim().ifBlank { throw IllegalArgumentException("Vault name cannot be empty") }
        val remoteFileName = if (displayName.endsWith(".mdbx", ignoreCase = true)) {
            displayName
        } else {
            "$displayName.mdbx"
        }
        val remotePath = MdbxRemoteSyncPaths.normalizePath(
            WebDavKeePassFileSource.buildChildPath(normalizedDir, remoteFileName)
        )
        val transport = WebDavMdbxRemoteTransport(serverUrl, username, webDavPassword)
        transport.testConnection()
        val localVaultFile = mdbx2Repository.createInitializedVaultFile(tigaMode, masterPassword)
        val sourceId = remoteSourceDao.insertSource(
            MdbxRemoteSource(
                displayName = displayName,
                remotePath = remotePath,
                remoteParentPath = normalizedDir.ifBlank { null },
                baseUrl = serverUrl.trim().trimEnd('/'),
                usernameEncrypted = securityManager.encryptData(username),
                passwordEncrypted = securityManager.encryptData(webDavPassword)
            )
        )
        val encryptedMasterPassword = securityManager.encryptData(
            Mdbx2VaultSessionExecutor.normalizePassword(masterPassword)
        )
        val databaseId = databaseDao.insertDatabase(
            LocalMdbxDatabase(
                name = displayName,
                filePath = remotePath,
                storageLocation = MdbxStorageLocation.REMOTE_WEBDAV.name,
                sourceType = MdbxSourceType.REMOTE_WEBDAV.name,
                sourceId = sourceId,
                engineType = MdbxEngineType.RUST_MDBX2.name,
                tigaMode = tigaMode.name,
                encryptedPassword = encryptedMasterPassword,
                unlockMethod = MdbxUnlockMethod.MASTER_PASSWORD.storedValue,
                kdfProfile = "argon2id",
                description = description,
                workingCopyPath = localVaultFile.absolutePath,
                cacheCopyPath = localVaultFile.absolutePath,
                isOfflineAvailable = true,
                lastSyncStatus = MdbxSyncStatus.SYNCING.name
            )
        )
        try {
            mdbx2RemoteSyncCoordinator.publishBootstrap(databaseId, remotePath, transport)
            importEntriesFromVault(databaseId)
            databaseDao.updateSyncStatus(databaseId, MdbxSyncStatus.IN_SYNC.name, null)
        } catch (error: Throwable) {
            cleanupFailedMdbx2RemoteDatabase(databaseId, sourceId, localVaultFile)
            throw error
        }
    }

    private suspend fun connectToMdbx2WebDavVault(
        name: String,
        masterPassword: String,
        serverUrl: String,
        username: String,
        webDavPassword: String,
        remoteFilePath: String,
        description: String?
    ) {
        require(masterPassword.isNotBlank()) { "MDBX2 requires a master password" }
        val displayName = name.trim().ifBlank { throw IllegalArgumentException("Vault name cannot be empty") }
        val normalizedRemotePath = MdbxRemoteSyncPaths.normalizePath(remoteFilePath)
        val transport = WebDavMdbxRemoteTransport(serverUrl, username, webDavPassword)
        transport.testConnection()
        val localVaultFile = File(
            File(context.filesDir, "mdbx2").also { check(it.exists() || it.mkdirs()) },
            "remote_${UUID.randomUUID()}.mdbx"
        )
        mdbx2RemoteSyncCoordinator.downloadBootstrapTo(normalizedRemotePath, transport, localVaultFile)
        val sourceId = remoteSourceDao.insertSource(
            MdbxRemoteSource(
                displayName = displayName,
                remotePath = normalizedRemotePath,
                remoteParentPath = normalizedRemotePath.substringBeforeLast('/', "").ifBlank { null },
                baseUrl = serverUrl.trim().trimEnd('/'),
                usernameEncrypted = securityManager.encryptData(username),
                passwordEncrypted = securityManager.encryptData(webDavPassword)
            )
        )
        val databaseId = databaseDao.insertDatabase(
            LocalMdbxDatabase(
                name = displayName,
                filePath = normalizedRemotePath,
                storageLocation = MdbxStorageLocation.REMOTE_WEBDAV.name,
                sourceType = MdbxSourceType.REMOTE_WEBDAV.name,
                sourceId = sourceId,
                engineType = MdbxEngineType.RUST_MDBX2.name,
                encryptedPassword = securityManager.encryptData(
                    Mdbx2VaultSessionExecutor.normalizePassword(masterPassword)
                ),
                unlockMethod = MdbxUnlockMethod.MASTER_PASSWORD.storedValue,
                kdfProfile = "argon2id",
                description = description,
                workingCopyPath = localVaultFile.absolutePath,
                cacheCopyPath = localVaultFile.absolutePath,
                isOfflineAvailable = true,
                lastSyncStatus = MdbxSyncStatus.SYNCING.name
            )
        )
        try {
            // Opening the file through the Rust session validates both the
            // password and the authenticated vault identity before we commit
            // the bootstrap cursor.
            mdbx2Repository.withVaultForSync(databaseId) { _, vault -> vault.info() }
            mdbx2RemoteSyncCoordinator.registerDownloadedBootstrap(databaseId, normalizedRemotePath)
            mdbx2RemoteSyncCoordinator.synchronize(databaseId, normalizedRemotePath, transport)
            importEntriesFromVault(databaseId)
            databaseDao.updateSyncStatus(databaseId, MdbxSyncStatus.IN_SYNC.name, null)
        } catch (error: Throwable) {
            cleanupFailedMdbx2RemoteDatabase(databaseId, sourceId, localVaultFile)
            throw error
        }
    }

    private suspend fun createMdbx2OneDriveVault(
        name: String,
        masterPassword: String,
        tigaMode: MdbxTigaMode,
        accountId: String,
        directoryPath: String?,
        description: String?
    ) {
        require(masterPassword.isNotBlank()) { "MDBX2 requires a master password" }
        val normalizedDir = OneDriveKeePassFileSource.normalizeOptionalRemotePath(directoryPath)
        val displayName = name.trim().ifBlank { throw IllegalArgumentException("Vault name cannot be empty") }
        val remoteFileName = if (displayName.endsWith(".mdbx", ignoreCase = true)) displayName else "$displayName.mdbx"
        val remotePath = MdbxRemoteSyncPaths.normalizePath(
            OneDriveKeePassFileSource.buildChildPath(normalizedDir, remoteFileName)
        )
        val transport = OneDriveMdbxRemoteTransport(context, accountId)
        transport.testConnection()
        val localVaultFile = mdbx2Repository.createInitializedVaultFile(tigaMode, masterPassword)
        val accessToken = OneDriveAuthManager(context).acquireAccessToken(accountId).accessToken
            ?: throw IllegalStateException("OneDrive access token unavailable")
        val sourceId = remoteSourceDao.insertSource(
            MdbxRemoteSource(
                displayName = displayName,
                remotePath = remotePath,
                remoteParentPath = normalizedDir.ifBlank { null },
                baseUrl = null,
                usernameEncrypted = securityManager.encryptData(accountId),
                passwordEncrypted = securityManager.encryptData(accessToken)
            )
        )
        val databaseId = databaseDao.insertDatabase(
            LocalMdbxDatabase(
                name = displayName,
                filePath = remotePath,
                storageLocation = MdbxStorageLocation.REMOTE_WEBDAV.name,
                sourceType = MdbxSourceType.REMOTE_ONEDRIVE.name,
                sourceId = sourceId,
                engineType = MdbxEngineType.RUST_MDBX2.name,
                tigaMode = tigaMode.name,
                encryptedPassword = securityManager.encryptData(
                    Mdbx2VaultSessionExecutor.normalizePassword(masterPassword)
                ),
                unlockMethod = MdbxUnlockMethod.MASTER_PASSWORD.storedValue,
                kdfProfile = "argon2id",
                description = description,
                workingCopyPath = localVaultFile.absolutePath,
                cacheCopyPath = localVaultFile.absolutePath,
                isOfflineAvailable = true,
                lastSyncStatus = MdbxSyncStatus.SYNCING.name
            )
        )
        try {
            mdbx2RemoteSyncCoordinator.publishBootstrap(databaseId, remotePath, transport)
            importEntriesFromVault(databaseId)
            databaseDao.updateSyncStatus(databaseId, MdbxSyncStatus.IN_SYNC.name, null)
        } catch (error: Throwable) {
            cleanupFailedMdbx2RemoteDatabase(databaseId, sourceId, localVaultFile)
            throw error
        }
    }

    private suspend fun connectToMdbx2OneDriveVault(
        name: String,
        masterPassword: String,
        accountId: String,
        remoteFilePath: String,
        description: String?
    ) {
        require(masterPassword.isNotBlank()) { "MDBX2 requires a master password" }
        val displayName = name.trim().ifBlank { throw IllegalArgumentException("Vault name cannot be empty") }
        val normalizedRemotePath = MdbxRemoteSyncPaths.normalizePath(remoteFilePath)
        val transport = OneDriveMdbxRemoteTransport(context, accountId)
        transport.testConnection()
        val localVaultFile = File(
            File(context.filesDir, "mdbx2").also { check(it.exists() || it.mkdirs()) },
            "onedrive_${UUID.randomUUID()}.mdbx"
        )
        mdbx2RemoteSyncCoordinator.downloadBootstrapTo(normalizedRemotePath, transport, localVaultFile)
        val accessToken = OneDriveAuthManager(context).acquireAccessToken(accountId).accessToken
            ?: throw IllegalStateException("OneDrive access token unavailable")
        val sourceId = remoteSourceDao.insertSource(
            MdbxRemoteSource(
                displayName = displayName,
                remotePath = normalizedRemotePath,
                remoteParentPath = normalizedRemotePath.substringBeforeLast('/', "").ifBlank { null },
                baseUrl = null,
                usernameEncrypted = securityManager.encryptData(accountId),
                passwordEncrypted = securityManager.encryptData(accessToken)
            )
        )
        val databaseId = databaseDao.insertDatabase(
            LocalMdbxDatabase(
                name = displayName,
                filePath = normalizedRemotePath,
                storageLocation = MdbxStorageLocation.REMOTE_WEBDAV.name,
                sourceType = MdbxSourceType.REMOTE_ONEDRIVE.name,
                sourceId = sourceId,
                engineType = MdbxEngineType.RUST_MDBX2.name,
                encryptedPassword = securityManager.encryptData(
                    Mdbx2VaultSessionExecutor.normalizePassword(masterPassword)
                ),
                unlockMethod = MdbxUnlockMethod.MASTER_PASSWORD.storedValue,
                kdfProfile = "argon2id",
                description = description,
                workingCopyPath = localVaultFile.absolutePath,
                cacheCopyPath = localVaultFile.absolutePath,
                isOfflineAvailable = true,
                lastSyncStatus = MdbxSyncStatus.SYNCING.name
            )
        )
        try {
            mdbx2Repository.withVaultForSync(databaseId) { _, vault -> vault.info() }
            mdbx2RemoteSyncCoordinator.registerDownloadedBootstrap(databaseId, normalizedRemotePath)
            mdbx2RemoteSyncCoordinator.synchronize(databaseId, normalizedRemotePath, transport)
            importEntriesFromVault(databaseId)
            databaseDao.updateSyncStatus(databaseId, MdbxSyncStatus.IN_SYNC.name, null)
        } catch (error: Throwable) {
            cleanupFailedMdbx2RemoteDatabase(databaseId, sourceId, localVaultFile)
            throw error
        }
    }

    private suspend fun cleanupFailedMdbx2RemoteDatabase(
        databaseId: Long,
        sourceId: Long,
        localVaultFile: File
    ) {
        runCatching { mdbx2RemoteSyncCoordinator.clearLocalState(databaseId) }
        runCatching { databaseDao.deleteDatabaseById(databaseId) }
        runCatching { remoteSourceDao.deleteSourceById(sourceId) }
        runCatching { mdbx2Repository.deleteOwnedVaultFile(localVaultFile) }
    }

    /**
     * Push the working copy of an EXTERNAL vault back to its source URI,
     * so changes are visible in the user's synced folder.
     */
    fun syncExternalVault(databaseId: Long) {
        viewModelScope.launch {
            val database = withContext(Dispatchers.IO) { databaseDao.getDatabaseById(databaseId) }
            val requiredCapability = if (database?.sourceTypeEnum == MdbxSourceType.LOCAL_EXTERNAL) {
                MdbxCapability.EXTERNAL_STORAGE
            } else {
                MdbxCapability.REMOTE_SYNC
            }
            if (!requireCapability(databaseId, requiredCapability, "External sync")) {
                return@launch
            }
            _operationState.value = OperationState.Loading("Syncing vault to external location...")
            val result = runMdbxSyncThroughCoordinator(
                databaseId = databaseId,
                requestIdPrefix = "mdbx-external",
                trigger = SyncTrigger.MANUAL,
                priority = SyncPriority.MANUAL,
                mode = SyncMode.FOREGROUND
            )
            applyManualMdbxSyncResult(
                result = result,
                successMessage = "Vault synced to external location",
                failurePrefix = "Failed to sync vault"
            )
        }
    }

    fun syncVault(databaseId: Long) {
        viewModelScope.launch {
            if (!requireCapability(databaseId, MdbxCapability.REMOTE_SYNC, "Sync")) {
                return@launch
            }
            _operationState.value = OperationState.Loading("Syncing MDBX vault...")
            val result = runMdbxSyncThroughCoordinator(
                databaseId = databaseId,
                requestIdPrefix = "mdbx-manual",
                trigger = SyncTrigger.MANUAL,
                priority = SyncPriority.MANUAL,
                mode = SyncMode.FOREGROUND
            )
            applyManualMdbxSyncResult(
                result = result,
                successMessage = "MDBX vault synced",
                failurePrefix = "Failed to sync vault"
            )
        }
    }

    fun autoSyncVisibleVault(databaseId: Long) {
        viewModelScope.launch {
            if (_operationState.value is OperationState.Loading) return@launch
            val database = withContext(Dispatchers.IO) {
                databaseDao.getDatabaseById(databaseId)
            }
            if (database != null && !database.supports(MdbxCapability.REMOTE_SYNC)) {
                refreshSingleVaultState(databaseId)
                return@launch
            }
            val shouldSync = database != null &&
                database.lastSyncStatus != MdbxSyncStatus.PENDING_UPLOAD.name &&
                database.sourceTypeEnum != MdbxSourceType.LOCAL_INTERNAL
            if (!shouldSync) {
                refreshSingleVaultState(databaseId)
                return@launch
            }
            val result = runMdbxSyncThroughCoordinator(
                databaseId = databaseId,
                requestIdPrefix = "mdbx-visible",
                trigger = SyncTrigger.PAGE_VISIBLE,
                priority = SyncPriority.PAGE_VISIBLE,
                mode = SyncMode.SILENT,
                throttleMs = VISIBLE_MDBX_AUTO_SYNC_THROTTLE_MS
            )
            if (result !is SyncTaskAwaitResult.Completed) {
                refreshSingleVaultState(databaseId)
            }
        }
    }

    private suspend fun runMdbxSyncThroughCoordinator(
        databaseId: Long,
        requestIdPrefix: String,
        trigger: SyncTrigger,
        priority: SyncPriority,
        mode: SyncMode,
        throttleMs: Long = 0L
    ): SyncTaskAwaitResult<Unit> {
        return runMdbxTaskThroughCoordinator(
            databaseId = databaseId,
            requestIdPrefix = requestIdPrefix,
            trigger = trigger,
            priority = priority,
            mode = mode,
            throttleMs = throttleMs,
            operationName = "sync"
        ) {
            refreshVaultFromSource(databaseId)
            refreshSingleVaultState(databaseId)
        }
    }

    private suspend fun runMdbxPendingUploadThroughCoordinator(
        databaseId: Long,
        requestIdPrefix: String,
        trigger: SyncTrigger,
        priority: SyncPriority,
        mode: SyncMode
    ): SyncTaskAwaitResult<Unit> {
        return runMdbxTaskThroughCoordinator(
            databaseId = databaseId,
            requestIdPrefix = requestIdPrefix,
            trigger = trigger,
            priority = priority,
            mode = mode,
            operationName = "pending_upload"
        ) { database ->
            if (database.engineTypeEnum == MdbxEngineType.RUST_MDBX2 && database.isRemoteSource()) {
                refreshVaultFromSource(database.id)
            } else {
                vaultStore.flushPendingWorkingCopy(database.id)
            }
            refreshSingleVaultState(database.id)
        }
    }

    private suspend fun runMdbxTaskThroughCoordinator(
        databaseId: Long,
        requestIdPrefix: String,
        trigger: SyncTrigger,
        priority: SyncPriority,
        mode: SyncMode,
        throttleMs: Long = 0L,
        operationName: String,
        block: suspend (LocalMdbxDatabase) -> Unit
    ): SyncTaskAwaitResult<Unit> {
        val target = SyncTarget.MdbxVault(databaseId)
        val taskId = SyncDiagnostics.nextTaskId(requestIdPrefix)
        val targetLog = "mdbx:$databaseId"
        val triggerLog = trigger.name
        val database = withContext(Dispatchers.IO) {
            databaseDao.getDatabaseById(databaseId)
        }
        if (database == null) {
            SyncDiagnostics.skipped(
                taskId = taskId,
                target = targetLog,
                trigger = triggerLog,
                reason = "missing_vault"
            )
            return SyncTaskAwaitResult.Skipped("missing_vault")
        }

        val detail = "operation=$operationName source=${database.sourceType} status=${database.lastSyncStatus} throttleMs=$throttleMs"
        SyncDiagnostics.queued(taskId, targetLog, triggerLog, detail)
        val request = SyncRequest(
            requestId = taskId,
            target = target,
            trigger = trigger,
            createdAtMillis = System.currentTimeMillis(),
            priority = priority,
            mode = mode,
            throttleKey = target.stableKey,
            networkPolicy = database.mdbxSyncNetworkPolicy(),
            throttleMs = throttleMs
        )
        val result = SyncTaskRunner.requestAndAwait(request) {
            val startedAt = SyncDiagnostics.start(taskId, targetLog, triggerLog, detail)
            try {
                withContext(Dispatchers.IO) {
                    block(database)
                }
                SyncDiagnostics.success(taskId, targetLog, triggerLog, startedAt)
            } catch (error: Exception) {
                runCatching { refreshSingleVaultState(databaseId) }
                SyncDiagnostics.failed(taskId, targetLog, triggerLog, startedAt, error)
                throw error
            }
        }
        when (result) {
            is SyncTaskAwaitResult.Completed -> Unit
            is SyncTaskAwaitResult.Merged -> SyncDiagnostics.skipped(
                taskId = taskId,
                target = targetLog,
                trigger = triggerLog,
                reason = "merged"
            )
            is SyncTaskAwaitResult.Skipped -> SyncDiagnostics.skipped(
                taskId = taskId,
                target = targetLog,
                trigger = triggerLog,
                reason = result.reason
            )
            is SyncTaskAwaitResult.Blocked -> SyncDiagnostics.blocked(
                taskId = taskId,
                target = targetLog,
                trigger = triggerLog,
                reason = result.error.redactedMessage ?: result.error.kind.name
            )
            is SyncTaskAwaitResult.Canceled -> SyncDiagnostics.skipped(
                taskId = taskId,
                target = targetLog,
                trigger = triggerLog,
                reason = result.reason ?: "canceled"
            )
            is SyncTaskAwaitResult.Failed -> Unit
        }
        return result
    }

    private fun applyManualMdbxSyncResult(
        result: SyncTaskAwaitResult<Unit>,
        successMessage: String,
        failurePrefix: String
    ) {
        _operationState.value = when (result) {
            is SyncTaskAwaitResult.Completed -> OperationState.Success(successMessage)
            is SyncTaskAwaitResult.Merged -> OperationState.Success("MDBX vault sync already running")
            is SyncTaskAwaitResult.Skipped -> OperationState.Success("MDBX vault sync skipped: ${result.reason}")
            is SyncTaskAwaitResult.Blocked -> OperationState.Error(
                "$failurePrefix: ${result.error.redactedMessage ?: result.error.kind.name}"
            )
            is SyncTaskAwaitResult.Canceled -> OperationState.Error(
                "$failurePrefix: ${result.reason ?: "sync canceled"}"
            )
            is SyncTaskAwaitResult.Failed -> OperationState.Error(
                "$failurePrefix: ${result.error.message ?: "unknown error"}"
            )
        }
    }

    private fun LocalMdbxDatabase.mdbxSyncNetworkPolicy(): SyncNetworkPolicy {
        return when (sourceTypeEnum) {
            MdbxSourceType.REMOTE_WEBDAV,
            MdbxSourceType.REMOTE_ONEDRIVE -> SyncNetworkPolicy.REQUIRED
            MdbxSourceType.LOCAL_INTERNAL,
            MdbxSourceType.LOCAL_EXTERNAL -> SyncNetworkPolicy.ALLOWED
        }
    }

    fun flushPendingVaultUploads() {
        viewModelScope.launch {
            _operationState.value = OperationState.Loading("Uploading pending MDBX vault changes...")
            try {
                val pendingIds = withContext(Dispatchers.IO) {
                    databaseDao.getAllDatabasesSnapshot()
                        .filter {
                            it.lastSyncStatus == MdbxSyncStatus.PENDING_UPLOAD.name &&
                                it.supports(MdbxCapability.REMOTE_SYNC)
                        }
                        .map { database -> database.id }
                }
                var uploadedCount = 0
                val skippedReasons = mutableListOf<String>()
                val failureMessages = mutableListOf<String>()
                pendingIds.forEach { databaseId ->
                    when (val result = runMdbxPendingUploadThroughCoordinator(
                        databaseId = databaseId,
                        requestIdPrefix = "mdbx-pending-upload",
                        trigger = SyncTrigger.MANUAL,
                        priority = SyncPriority.MANUAL,
                        mode = SyncMode.FOREGROUND
                    )) {
                        is SyncTaskAwaitResult.Completed -> uploadedCount += 1
                        is SyncTaskAwaitResult.Merged -> skippedReasons += "already running"
                        is SyncTaskAwaitResult.Skipped -> skippedReasons += result.reason
                        is SyncTaskAwaitResult.Blocked -> failureMessages +=
                            (result.error.redactedMessage ?: result.error.kind.name)
                        is SyncTaskAwaitResult.Canceled -> failureMessages +=
                            (result.reason ?: "sync canceled")
                        is SyncTaskAwaitResult.Failed -> failureMessages +=
                            (result.error.message ?: "unknown error")
                    }
                }
                _operationState.value = if (failureMessages.isEmpty()) {
                    val skippedSuffix = if (skippedReasons.isEmpty()) {
                        ""
                    } else {
                        ", skipped ${skippedReasons.size}"
                    }
                    OperationState.Success(
                        "Uploaded $uploadedCount pending MDBX vault(s)$skippedSuffix"
                    )
                } else {
                    OperationState.Error(
                        "Failed to upload pending MDBX vaults: ${failureMessages.joinToString("; ")}"
                    )
                }
            } catch (e: Exception) {
                _operationState.value = OperationState.Error(
                    "Failed to upload pending MDBX vaults: ${e.message ?: "unknown error"}"
                )
            }
        }
    }

    fun showAdvancedTools(database: LocalMdbxDatabase) {
        viewModelScope.launch {
            val cachedDiagnostics = _vaultDiagnostics.value[database.id]
            _advancedDialogState.value = MdbxAdvancedDialogState.Visible(
                databaseId = database.id,
                databaseName = database.name,
                diagnostics = cachedDiagnostics,
                isLoading = cachedDiagnostics == null
            )
            val refreshedDiagnostic = withContext(Dispatchers.IO) {
                vaultStore.getVaultDiagnostics(database.id)
            }
            applyVaultDiagnostic(database.id, refreshedDiagnostic)
            val current = _advancedDialogState.value as? MdbxAdvancedDialogState.Visible
            if (current?.databaseId == database.id) {
                _advancedDialogState.value = current.copy(
                    diagnostics = refreshedDiagnostic,
                    isLoading = false
                )
            }
        }
    }

    fun requestHealthRepair(database: LocalMdbxDatabase) {
        if (database.engineTypeEnum != MdbxEngineType.RUST_MDBX2) {
            _operationState.value = OperationState.Error("一键处理仅适用于 MDBX2 数据库")
            return
        }
        if (_healthRepairState.value is MdbxHealthRepairState.Applying) return
        healthRepairJob?.cancel()
        healthRepairJob = viewModelScope.launch {
            _healthRepairState.value = MdbxHealthRepairState.Planning(
                databaseId = database.id,
                databaseName = database.name
            )
            try {
                val plan = withContext(Dispatchers.IO) {
                    vaultStore.planHealthRepair(database.id)
                }
                when {
                    plan.blockers.isNotEmpty() -> {
                        _healthRepairState.value = MdbxHealthRepairState.Blocked(
                            databaseId = database.id,
                            databaseName = database.name,
                            blockers = plan.blockers
                        )
                    }
                    plan.repairableItemCount == 0 -> {
                        _healthRepairState.value = MdbxHealthRepairState.Hidden
                        _operationState.value = OperationState.Success("当前没有可自动处理的健康异常")
                        refreshSingleVaultState(database.id)
                    }
                    !plan.canApply -> {
                        _healthRepairState.value = MdbxHealthRepairState.Failed(
                            databaseId = database.id,
                            databaseName = database.name,
                            message = "当前异常无法生成安全处理计划，请重新检查数据库状态"
                        )
                    }
                    plan.conflictItems.isEmpty() -> {
                        applyHealthRepairPlan(
                            databaseId = database.id,
                            databaseName = database.name,
                            plan = plan,
                            decisions = emptyMap()
                        )
                    }
                    else -> {
                        _healthRepairState.value = MdbxHealthRepairState.Reviewing(
                            databaseId = database.id,
                            databaseName = database.name,
                            plan = plan
                        )
                    }
                }
            } catch (error: Throwable) {
                _healthRepairState.value = MdbxHealthRepairState.Failed(
                    databaseId = database.id,
                    databaseName = database.name,
                    message = error.toHealthRepairUserMessage()
                )
            }
        }
    }

    fun chooseHealthRepairConflict(choice: MdbxHealthRepairChoice) {
        require(choice != MdbxHealthRepairChoice.CANCEL) {
            "Use dismissHealthRepair() to cancel the whole repair"
        }
        val current = _healthRepairState.value as? MdbxHealthRepairState.Reviewing ?: return
        val item = current.currentItem ?: return
        val decisions = current.decisions + (item.repairId to choice)
        val nextIndex = current.currentIndex + 1
        if (nextIndex < current.plan.conflictItems.size) {
            _healthRepairState.value = current.copy(
                decisions = decisions,
                currentIndex = nextIndex
            )
            return
        }
        healthRepairJob = viewModelScope.launch {
            applyHealthRepairPlan(
                databaseId = current.databaseId,
                databaseName = current.databaseName,
                plan = current.plan,
                decisions = decisions
            )
        }
    }

    fun dismissHealthRepair() {
        if (_healthRepairState.value is MdbxHealthRepairState.Applying) return
        healthRepairJob?.cancel()
        healthRepairJob = null
        _healthRepairState.value = MdbxHealthRepairState.Hidden
    }

    fun verifyMasterPassword(password: String): Boolean =
        securityManager.verifyMasterPassword(password)

    private suspend fun applyHealthRepairPlan(
        databaseId: Long,
        databaseName: String,
        plan: MdbxHealthRepairPlan,
        decisions: Map<String, MdbxHealthRepairChoice>
    ) {
        _healthRepairState.value = MdbxHealthRepairState.Applying(
            databaseId = databaseId,
            databaseName = databaseName,
            itemCount = plan.repairableItemCount
        )
        try {
            val orderedDecisions = plan.conflictItems.map { item ->
                MdbxHealthRepairDecision(
                    repairId = item.repairId,
                    choice = decisions[item.repairId]
                        ?: error("缺少 ${item.objectType} ${item.objectId} 的处理选择")
                )
            }
            val result = withContext(Dispatchers.IO) {
                vaultStore.applyHealthRepair(
                    databaseId = databaseId,
                    planToken = plan.token,
                    operationId = UUID.randomUUID().toString(),
                    decisions = orderedDecisions
                )
            }
            if (result.status == MdbxHealthRepairStatus.APPLIED) {
                runCatching { importEntriesFromVault(databaseId) }
                    .onFailure { error ->
                        MdbxDiagLogger.append(
                            "[MDBX2][health-repair] repaired database but failed to refresh local entries " +
                                "databaseId=$databaseId cause=${error::class.java.simpleName}"
                        )
                    }
                invalidateMdbxViewCaches(databaseId)
            }
            refreshSingleVaultState(databaseId)
            _healthRepairState.value = MdbxHealthRepairState.Hidden
            _operationState.value = OperationState.Success(result.healthRepairResultMessage())
        } catch (error: Throwable) {
            _healthRepairState.value = MdbxHealthRepairState.Failed(
                databaseId = databaseId,
                databaseName = databaseName,
                message = error.toHealthRepairUserMessage()
            )
        }
    }

    fun exportSyncBundle(databaseId: Long, baseCommitId: String? = null) {
        viewModelScope.launch {
            if (!requireCapability(databaseId, MdbxCapability.SYNC_BUNDLES, "Sync bundle export")) {
                return@launch
            }
            val current = _advancedDialogState.value as? MdbxAdvancedDialogState.Visible
            _advancedDialogState.value = current?.copy(isLoading = true, message = null)
                ?: MdbxAdvancedDialogState.Hidden
            try {
                val bundle = withContext(Dispatchers.IO) {
                    vaultStore.exportSyncBundle(databaseId, baseCommitId)
                }
                val exportJson = syncBundleToExportJson(bundle)
                val latest = _advancedDialogState.value as? MdbxAdvancedDialogState.Visible
                if (latest?.databaseId == databaseId) {
                    _advancedDialogState.value = latest.copy(
                        exportedBundleJson = exportJson,
                        lastExportedBundle = bundle,
                        isLoading = false,
                        message = "Exported ${bundle.commitCount} MDBX commit(s)"
                    )
                }
                _operationState.value = OperationState.Success(
                    "Exported ${bundle.commitCount} MDBX commit(s)"
                )
            } catch (e: Exception) {
                val latest = _advancedDialogState.value as? MdbxAdvancedDialogState.Visible
                if (latest?.databaseId == databaseId) {
                    _advancedDialogState.value = latest.copy(isLoading = false)
                }
                _operationState.value = OperationState.Error(
                    "Failed to export MDBX sync bundle: ${e.message ?: "unknown error"}"
                )
            }
        }
    }

    fun importSyncBundleFromJson(databaseId: Long, bundleJson: String) {
        viewModelScope.launch {
            if (!requireCapability(databaseId, MdbxCapability.SYNC_BUNDLES, "Sync bundle import")) {
                return@launch
            }
            val current = _advancedDialogState.value as? MdbxAdvancedDialogState.Visible
            _advancedDialogState.value = current?.copy(isLoading = true, message = null)
                ?: MdbxAdvancedDialogState.Hidden
            try {
                val result = withContext(Dispatchers.IO) {
                    val bundle = parseSyncBundleExportJson(bundleJson)
                    val applyResult = vaultStore.importSyncBundle(databaseId, bundle)
                    importEntriesFromVault(databaseId)
                    applyResult
                }
                val refreshedDiagnostic = withContext(Dispatchers.IO) {
                    vaultStore.getVaultDiagnostics(databaseId)
                }
                applyVaultDiagnostic(databaseId, refreshedDiagnostic)
                val latest = _advancedDialogState.value as? MdbxAdvancedDialogState.Visible
                if (latest?.databaseId == databaseId) {
                    _advancedDialogState.value = latest.copy(
                        diagnostics = refreshedDiagnostic,
                        lastImportResult = result,
                        isLoading = false,
                        message = "Imported ${result.appliedObjectCount} object(s), ${result.conflictCount} conflict(s)"
                    )
                }
                _operationState.value = OperationState.Success(
                    "Imported MDBX bundle: ${result.appliedObjectCount} applied, ${result.conflictCount} conflict(s)"
                )
            } catch (e: Exception) {
                val latest = _advancedDialogState.value as? MdbxAdvancedDialogState.Visible
                if (latest?.databaseId == databaseId) {
                    _advancedDialogState.value = latest.copy(isLoading = false)
                }
                _operationState.value = OperationState.Error(
                    "Failed to import MDBX sync bundle: ${e.message ?: "unknown error"}"
                )
            }
        }
    }

    fun flushPendingVaultUpload(databaseId: Long) {
        viewModelScope.launch {
            if (!requireCapability(databaseId, MdbxCapability.REMOTE_SYNC, "Pending upload")) {
                return@launch
            }
            val current = _advancedDialogState.value as? MdbxAdvancedDialogState.Visible
            _advancedDialogState.value = current?.copy(isLoading = true, message = null)
                ?: MdbxAdvancedDialogState.Hidden
            _operationState.value = OperationState.Loading("Uploading pending MDBX vault changes...")
            val result = runMdbxPendingUploadThroughCoordinator(
                databaseId = databaseId,
                requestIdPrefix = "mdbx-pending-upload",
                trigger = SyncTrigger.MANUAL,
                priority = SyncPriority.MANUAL,
                mode = SyncMode.FOREGROUND
            )
            if (result is SyncTaskAwaitResult.Completed) {
                try {
                    val refreshedDiagnostic = withContext(Dispatchers.IO) {
                        vaultStore.getVaultDiagnostics(databaseId)
                    }
                    applyVaultDiagnostic(databaseId, refreshedDiagnostic)
                    val latest = _advancedDialogState.value as? MdbxAdvancedDialogState.Visible
                    if (latest?.databaseId == databaseId) {
                        _advancedDialogState.value = latest.copy(
                            diagnostics = refreshedDiagnostic,
                            isLoading = false,
                            message = "Pending MDBX upload flushed"
                        )
                    }
                    _operationState.value = OperationState.Success("Pending MDBX upload flushed")
                } catch (e: Exception) {
                    val latest = _advancedDialogState.value as? MdbxAdvancedDialogState.Visible
                    if (latest?.databaseId == databaseId) {
                        _advancedDialogState.value = latest.copy(isLoading = false)
                    }
                    _operationState.value = OperationState.Error(
                        "Failed to refresh MDBX diagnostics: ${e.message ?: "unknown error"}"
                    )
                }
            } else {
                val latest = _advancedDialogState.value as? MdbxAdvancedDialogState.Visible
                if (latest?.databaseId == databaseId) {
                    _advancedDialogState.value = latest.copy(
                        isLoading = false,
                        message = pendingUploadResultMessage(result)
                    )
                }
                _operationState.value = pendingUploadOperationState(result)
            }
        }
    }

    private fun pendingUploadOperationState(
        result: SyncTaskAwaitResult<Unit>
    ): OperationState {
        return when (result) {
            is SyncTaskAwaitResult.Completed -> OperationState.Success("Pending MDBX upload flushed")
            is SyncTaskAwaitResult.Merged -> OperationState.Success("Pending MDBX upload already running")
            is SyncTaskAwaitResult.Skipped -> OperationState.Success(
                "Pending MDBX upload skipped: ${result.reason}"
            )
            is SyncTaskAwaitResult.Blocked -> OperationState.Error(
                "Failed to upload pending MDBX vault: ${result.error.redactedMessage ?: result.error.kind.name}"
            )
            is SyncTaskAwaitResult.Canceled -> OperationState.Error(
                "Failed to upload pending MDBX vault: ${result.reason ?: "sync canceled"}"
            )
            is SyncTaskAwaitResult.Failed -> OperationState.Error(
                "Failed to upload pending MDBX vault: ${result.error.message ?: "unknown error"}"
            )
        }
    }

    private fun pendingUploadResultMessage(
        result: SyncTaskAwaitResult<Unit>
    ): String {
        return when (val state = pendingUploadOperationState(result)) {
            is OperationState.Success -> state.message
            is OperationState.Error -> state.message
            else -> "Pending MDBX upload did not complete"
        }
    }

    fun runBenchmark(databaseId: Long, operationCount: Int = 10) {
        viewModelScope.launch {
            if (!requireCapability(databaseId, MdbxCapability.BENCHMARK, "Benchmark")) {
                return@launch
            }
            val current = _advancedDialogState.value as? MdbxAdvancedDialogState.Visible
            _advancedDialogState.value = current?.copy(isLoading = true, message = null)
                ?: MdbxAdvancedDialogState.Hidden
            try {
                val result = withContext(Dispatchers.IO) {
                    val database = databaseDao.getDatabaseById(databaseId)
                        ?: error("MDBX database not found")
                    when (database.engineTypeEnum) {
                        MdbxEngineType.KOTLIN_MDBX1 -> legacyVaultStore.runBenchmark(
                            databaseId = databaseId,
                            operationCount = operationCount.coerceIn(1, 500)
                        )
                        MdbxEngineType.RUST_MDBX2 -> mdbx2Repository.runBenchmark(
                            databaseId = databaseId,
                            operationCount = operationCount.coerceIn(1, 500)
                        )
                    }
                }
                val refreshedDiagnostic = withContext(Dispatchers.IO) {
                    vaultStore.getVaultDiagnostics(databaseId)
                }
                applyVaultDiagnostic(databaseId, refreshedDiagnostic)
                val latest = _advancedDialogState.value as? MdbxAdvancedDialogState.Visible
                if (latest?.databaseId == databaseId) {
                    _advancedDialogState.value = latest.copy(
                        diagnostics = refreshedDiagnostic,
                        lastBenchmarkResult = result,
                        isLoading = false,
                        message = "Benchmark: ${result.operationCount} commit(s) in ${result.elapsedMs} ms"
                    )
                }
                _operationState.value = OperationState.Success(
                    "MDBX benchmark finished in ${result.elapsedMs} ms"
                )
            } catch (e: Exception) {
                val latest = _advancedDialogState.value as? MdbxAdvancedDialogState.Visible
                if (latest?.databaseId == databaseId) {
                    _advancedDialogState.value = latest.copy(isLoading = false)
                }
                _operationState.value = OperationState.Error(
                    "Failed to run MDBX benchmark: ${e.message ?: "unknown error"}"
                )
            }
        }
    }

    private suspend fun refreshSingleVaultState(databaseId: Long) = withContext(Dispatchers.IO) {
        val diagnostic = vaultStore.getVaultDiagnostics(databaseId)
        applyVaultDiagnostic(databaseId, diagnostic)
    }

    private fun applyVaultDiagnostic(databaseId: Long, diagnostic: MdbxVaultDiagnostics) {
        _vaultDiagnostics.value = _vaultDiagnostics.value + (databaseId to diagnostic)
        _conflictCounts.value =
            _conflictCounts.value + (databaseId to diagnostic.unresolvedConflictCount)
        _pendingSyncCounts.value =
            _pendingSyncCounts.value + (databaseId to diagnostic.pendingSyncCount)
    }

    private fun normalizeMdbxPassword(password: String): String =
        Normalizer.normalize(password, Normalizer.Form.NFC)

    private suspend fun requireCapability(
        databaseId: Long,
        capability: MdbxCapability,
        action: String
    ): Boolean {
        val database = withContext(Dispatchers.IO) { databaseDao.getDatabaseById(databaseId) }
        if (database == null) {
            _operationState.value = OperationState.Error("MDBX vault not found")
            return false
        }
        return reportUnsupportedCapability(database, capability, action)
    }

    private fun reportUnsupportedCapability(
        database: LocalMdbxDatabase,
        capability: MdbxCapability,
        action: String
    ): Boolean {
        if (database.supports(capability)) return true
        _operationState.value = OperationState.Error(
            "$action is not available for ${database.engineTypeEnum.name} vaults"
        )
        return false
    }

    fun deleteVault(databaseId: Long) {
        viewModelScope.launch {
            _operationState.value = OperationState.Loading("Deleting vault...")
            try {
                withContext(Dispatchers.IO) {
                    val database = databaseDao.getDatabaseById(databaseId)
                        ?: throw IllegalStateException("Vault not found")
                    deleteVaultPersistence(database)
                }
                invalidateMdbxViewCaches(databaseId)
                forgetActiveMdbxDatabaseIf(databaseId)
                _conflictCounts.value = _conflictCounts.value - databaseId
                _pendingSyncCounts.value = _pendingSyncCounts.value - databaseId
                _vaultDiagnostics.value = _vaultDiagnostics.value - databaseId
                if ((_conflictDialogState.value as? MdbxConflictDialogState.Visible)
                        ?.databaseId == databaseId
                ) {
                    _conflictDialogState.value = MdbxConflictDialogState.Hidden
                }
                if ((_advancedDialogState.value as? MdbxAdvancedDialogState.Visible)
                        ?.databaseId == databaseId
                ) {
                    _advancedDialogState.value = MdbxAdvancedDialogState.Hidden
                }
                _operationState.value = OperationState.Success("Vault deleted")
            } catch (e: Exception) {
                _operationState.value = OperationState.Error(
                    "Failed to delete vault: ${e.message ?: "unknown error"}"
                )
            }
        }
    }

    fun pruneMissingLocalVaults() {
        viewModelScope.launch {
            val removedIds = withContext(Dispatchers.IO) {
                databaseDao.getAllDatabasesSnapshot()
                    .filter { database ->
                        val shouldPrune =
                            database.sourceTypeEnum != MdbxSourceType.REMOTE_WEBDAV &&
                                !database.hasAccessibleLocalSource()
                        if (shouldPrune) {
                            MdbxDiagLogger.append(
                                "[MDBX][pruneMissingLocalVaults] removing id=${database.id} sourceType=${database.sourceType} engine=${database.engineType}"
                            )
                        }
                        shouldPrune
                    }
                    .map { database ->
                        deleteVaultPersistence(database)
                        database.id
                    }
            }
            if (removedIds.isNotEmpty()) {
                val removedSet = removedIds.toSet()
                invalidateMdbxViewCaches(removedSet)
                if (_activeMdbxDatabaseId.value in removedSet) {
                    _activeMdbxDatabaseId.value = null
                    activeVaultPrefs.edit().remove(ACTIVE_VAULT_ID_KEY).apply()
                    activePreloadJob?.cancel()
                    activePreloadJob = null
                }
                _conflictCounts.value = _conflictCounts.value - removedSet
                _pendingSyncCounts.value = _pendingSyncCounts.value - removedSet
                _vaultDiagnostics.value = _vaultDiagnostics.value - removedSet
                val visibleConflict = _conflictDialogState.value as? MdbxConflictDialogState.Visible
                if (visibleConflict?.databaseId in removedSet) {
                    _conflictDialogState.value = MdbxConflictDialogState.Hidden
                }
                val visibleDelta = _deltaDialogState.value as? MdbxDeltaDialogState.Visible
                if (visibleDelta?.databaseId in removedSet) {
                    _deltaDialogState.value = MdbxDeltaDialogState.Hidden
                }
                val visibleAdvanced = _advancedDialogState.value as? MdbxAdvancedDialogState.Visible
                if (visibleAdvanced?.databaseId in removedSet) {
                    _advancedDialogState.value = MdbxAdvancedDialogState.Hidden
                }
            }
        }
    }

    fun refreshConflictCounts(databases: List<LocalMdbxDatabase>) {
        refreshVaultDiagnostics(databases)
    }

    fun refreshVaultDiagnostics(databases: List<LocalMdbxDatabase>) {
        viewModelScope.launch {
            val diagnostics = withContext(Dispatchers.IO) {
                databases.associate { database ->
                    database.id to vaultStore.getVaultDiagnostics(database.id)
                }
            }
            _vaultDiagnostics.value = diagnostics
            _conflictCounts.value = diagnostics.mapValues { (_, diagnostic) ->
                diagnostic.unresolvedConflictCount
            }
            _pendingSyncCounts.value = diagnostics.mapValues { (_, diagnostic) ->
                diagnostic.pendingSyncCount
            }
        }
    }

    fun showConflicts(database: LocalMdbxDatabase) {
        if (!reportUnsupportedCapability(database, MdbxCapability.CONFLICTS, "Conflict management")) {
            return
        }
        viewModelScope.launch {
            _conflictDialogState.value = MdbxConflictDialogState.Visible(
                databaseId = database.id,
                databaseName = database.name,
                isLoading = true
            )
            val conflicts = withContext(Dispatchers.IO) {
                vaultStore.listConflicts(database.id)
            }
            _conflictDialogState.value = MdbxConflictDialogState.Visible(
                databaseId = database.id,
                databaseName = database.name,
                conflicts = conflicts,
                isLoading = false
            )
        }
    }

    fun showDeltaHistory(database: LocalMdbxDatabase) {
        if (!reportUnsupportedCapability(database, MdbxCapability.DELTA_HISTORY, "History")) {
            return
        }
        viewModelScope.launch {
            val current = _deltaDialogState.value as? MdbxDeltaDialogState.Visible
            val sameDatabaseState = current?.takeIf { it.databaseId == database.id }
            val cached = deltaHistoryCache[database.id]
            _deltaDialogState.value = MdbxDeltaDialogState.Visible(
                databaseId = database.id,
                databaseName = database.name,
                deltas = sameDatabaseState?.deltas ?: cached?.deltas.orEmpty(),
                snapshots = sameDatabaseState?.snapshots ?: cached?.snapshots.orEmpty(),
                selectedDiffCommitId = null,
                diffItems = emptyList(),
                isDiffLoading = false,
                isSnapshotLoading = false,
                selectedStructureSnapshotId = null,
                structurePreview = null,
                isStructureLoading = false,
                isLoading = true
            )
            var deltas: List<MdbxDeltaSummary> = emptyList()
            var snapshots: List<MdbxSnapshotSummary> = emptyList()
            val deltaMs = withContext(Dispatchers.IO) {
                measureTimeMillis {
                    deltas = vaultStore.listDeltaHistory(database.id)
                }
            }
            val snapshotMs = withContext(Dispatchers.IO) {
                measureTimeMillis {
                    snapshots = vaultStore.listSnapshots(database.id)
                }
            }
            MdbxDiagLogger.append(
                "[MDBX][perf][showDeltaHistory] databaseId=${database.id} deltas=${deltas.size} snapshots=${snapshots.size} deltaMs=$deltaMs snapshotMs=$snapshotMs cached=${cached != null} keptVisible=${sameDatabaseState != null}"
            )
            updateDeltaHistoryCache(
                databaseId = database.id,
                deltas = deltas,
                snapshots = snapshots
            )
            val refreshedState = (_deltaDialogState.value as? MdbxDeltaDialogState.Visible)
                ?.takeIf { it.databaseId == database.id }
                ?.copy(
                    databaseName = database.name,
                    deltas = deltas,
                    snapshots = snapshots,
                    isLoading = false
                )
                ?.let { clearSelectedStructureIfInvalid(it, snapshots) }
            if (refreshedState != null) {
                _deltaDialogState.value = refreshedState
            }
        }
    }

    private fun invalidateMdbxViewCaches(databaseId: Long) {
        activePreloadCompletedAt.remove(databaseId)
        deltaHistoryCache.remove(databaseId)
        structurePreviewCache.keys.removeIf { it.databaseId == databaseId }
    }

    private fun invalidateMdbxViewCaches(databaseIds: Iterable<Long>) {
        databaseIds.forEach(::invalidateMdbxViewCaches)
    }

    private fun updateDeltaHistoryCache(
        databaseId: Long,
        deltas: List<MdbxDeltaSummary>,
        snapshots: List<MdbxSnapshotSummary>
    ) {
        deltaHistoryCache[databaseId] = CachedDeltaHistory(
            deltas = deltas,
            snapshots = snapshots
        )
    }

    private fun updateStructurePreviewCache(
        databaseId: Long,
        snapshotId: String,
        preview: MdbxStructurePreview
    ) {
        structurePreviewCache[SnapshotStructureCacheKey(databaseId, snapshotId)] = preview
    }

    private fun cachedStructurePreview(
        databaseId: Long,
        snapshotId: String
    ): MdbxStructurePreview? =
        structurePreviewCache[SnapshotStructureCacheKey(databaseId, snapshotId)]

    private fun clearSelectedStructureIfInvalid(
        state: MdbxDeltaDialogState.Visible,
        snapshots: List<MdbxSnapshotSummary>
    ): MdbxDeltaDialogState.Visible {
        val selectedSnapshotId = state.selectedStructureSnapshotId ?: return state
        return if (snapshots.any { it.snapshotId == selectedSnapshotId }) {
            state
        } else {
            state.copy(
                selectedStructureSnapshotId = null,
                structurePreview = null,
                isStructureLoading = false
            )
        }
    }

    fun showCommitDiff(databaseId: Long, commitId: String) {
        viewModelScope.launch {
            if (!requireCapability(databaseId, MdbxCapability.DELTA_HISTORY, "Commit history")) {
                return@launch
            }
            val current = _deltaDialogState.value as? MdbxDeltaDialogState.Visible
                ?: return@launch
            _deltaDialogState.value = current.copy(
                selectedDiffCommitId = commitId,
                diffItems = emptyList(),
                isDiffLoading = true,
                diffError = null
            )
            runCatching {
                withContext(Dispatchers.IO) {
                    vaultStore.listCommitDiff(databaseId, commitId)
                }
            }.fold(
                onSuccess = { diffItems ->
                    val latest = _deltaDialogState.value as? MdbxDeltaDialogState.Visible
                        ?: return@fold
                    _deltaDialogState.value = latest.copy(
                        selectedDiffCommitId = commitId,
                        diffItems = diffItems,
                        isDiffLoading = false,
                        diffError = null
                    )
                },
                onFailure = { error ->
                    val latest = _deltaDialogState.value as? MdbxDeltaDialogState.Visible
                        ?: return@fold
                    _deltaDialogState.value = latest.copy(
                        selectedDiffCommitId = commitId,
                        diffItems = emptyList(),
                        isDiffLoading = false,
                        diffError = error.toCommitDiffUserMessage()
                    )
                }
            )
        }
    }

    fun closeCommitDiff() {
        val current = _deltaDialogState.value as? MdbxDeltaDialogState.Visible ?: return
        _deltaDialogState.value = current.copy(
            selectedDiffCommitId = null,
            diffItems = emptyList(),
            isDiffLoading = false,
            diffError = null
        )
    }

    fun showSnapshotStructure(databaseId: Long, snapshotId: String) {
        viewModelScope.launch {
            if (!requireCapability(databaseId, MdbxCapability.SNAPSHOTS, "Snapshot preview")) {
                return@launch
            }
            val current = _deltaDialogState.value as? MdbxDeltaDialogState.Visible
                ?: return@launch
            val cachedPreview = cachedStructurePreview(databaseId, snapshotId)
            _deltaDialogState.value = current.copy(
                selectedStructureSnapshotId = snapshotId,
                structurePreview = current.structurePreview
                    ?.takeIf { current.selectedStructureSnapshotId == snapshotId }
                    ?: cachedPreview,
                isStructureLoading = true,
                selectedDiffCommitId = null,
                diffItems = emptyList(),
                isDiffLoading = false
            )
            try {
                var loadedPreview: MdbxStructurePreview? = null
                val elapsedMs = withContext(Dispatchers.IO) {
                    measureTimeMillis {
                        loadedPreview = vaultStore.getSnapshotStructurePreview(databaseId, snapshotId)
                    }
                }
                val preview = loadedPreview
                    ?: throw IllegalStateException("MDBX snapshot structure did not load")
                MdbxDiagLogger.append(
                    "[MDBX][perf][showSnapshotStructure] databaseId=$databaseId snapshotId=${snapshotId.take(8)} currentNodes=${preview.currentNodes.size} snapshotNodes=${preview.snapshotNodes.size} elapsedMs=$elapsedMs cached=${cachedPreview != null}"
                )
                val latest = _deltaDialogState.value as? MdbxDeltaDialogState.Visible
                    ?: return@launch
                updateStructurePreviewCache(databaseId, snapshotId, preview)
                _deltaDialogState.value = latest.copy(
                    selectedStructureSnapshotId = snapshotId,
                    structurePreview = preview,
                    isStructureLoading = false
                )
            } catch (e: Exception) {
                val latest = _deltaDialogState.value as? MdbxDeltaDialogState.Visible
                _deltaDialogState.value = latest?.copy(
                    selectedStructureSnapshotId = null,
                    structurePreview = null,
                    isStructureLoading = false
                ) ?: MdbxDeltaDialogState.Hidden
                _operationState.value = OperationState.Error(
                    "Failed to load MDBX snapshot structure: ${e.message ?: "unknown error"}"
                )
            }
        }
    }

    fun closeSnapshotStructure() {
        val current = _deltaDialogState.value as? MdbxDeltaDialogState.Visible ?: return
        _deltaDialogState.value = current.copy(
            selectedStructureSnapshotId = null,
            structurePreview = null,
            isStructureLoading = false
        )
    }

    fun revertCommit(databaseId: Long, commitId: String) {
        viewModelScope.launch {
            if (!requireCapability(databaseId, MdbxCapability.DELTA_HISTORY, "Commit revert")) {
                return@launch
            }
            val current = _deltaDialogState.value as? MdbxDeltaDialogState.Visible
            _deltaDialogState.value = current?.copy(isLoading = true)
                ?: MdbxDeltaDialogState.Hidden
            try {
                invalidateMdbxViewCaches(databaseId)
                val revertedCount = withContext(Dispatchers.IO) {
                    val count = vaultStore.revertCommit(databaseId, commitId)
                    importEntriesFromVault(
                        databaseId,
                        orphanPolicy = MdbxImportOrphanPolicy.APPLY_REMOTE_STATE
                    )
                    count
                }
                val refreshedDeltas = withContext(Dispatchers.IO) {
                    vaultStore.listDeltaHistory(databaseId)
                }
                val refreshedSnapshots = withContext(Dispatchers.IO) {
                    vaultStore.listSnapshots(databaseId)
                }
                val refreshedDiagnostic = withContext(Dispatchers.IO) {
                    vaultStore.getVaultDiagnostics(databaseId)
                }
                applyVaultDiagnostic(databaseId, refreshedDiagnostic)
                updateDeltaHistoryCache(databaseId, refreshedDeltas, refreshedSnapshots)
                val refreshedState = current?.copy(
                    deltas = refreshedDeltas,
                    snapshots = refreshedSnapshots,
                    selectedDiffCommitId = null,
                    diffItems = emptyList(),
                    isLoading = false,
                    isDiffLoading = false,
                    diffError = null
                )?.let { clearSelectedStructureIfInvalid(it, refreshedSnapshots) }
                _deltaDialogState.value = refreshedState ?: MdbxDeltaDialogState.Hidden
                _operationState.value = OperationState.Success(
                    "Reverted $revertedCount MDBX object(s)"
                )
            } catch (e: Exception) {
                _deltaDialogState.value = current?.copy(isLoading = false, isDiffLoading = false)
                    ?: MdbxDeltaDialogState.Hidden
                _operationState.value = OperationState.Error(
                    "Failed to revert MDBX commit: ${e.message ?: "unknown error"}"
                )
            }
        }
    }

    fun createSnapshot(
        databaseId: Long,
        name: String,
        fullSnapshot: Boolean,
        onResult: ((Result<MdbxSnapshotSummary>) -> Unit)? = null
    ) {
        viewModelScope.launch {
            if (!requireCapability(databaseId, MdbxCapability.SNAPSHOTS, "Snapshot creation")) {
                onResult?.invoke(
                    Result.failure(
                        IllegalStateException(
                            getApplication<Application>().getString(R.string.mdbx_snapshot_unavailable)
                        )
                    )
                )
                return@launch
            }
            val current = _deltaDialogState.value as? MdbxDeltaDialogState.Visible
            _deltaDialogState.value = current?.copy(isSnapshotLoading = true)
                ?: MdbxDeltaDialogState.Hidden
            try {
                invalidateMdbxViewCaches(databaseId)
                val snapshot = withContext(Dispatchers.IO) {
                    vaultStore.createSnapshot(
                        databaseId = databaseId,
                        name = name,
                        fullSnapshot = fullSnapshot,
                        autoPrune = false
                    )
                }
                refreshDeltaDialogAfterSnapshotMutation(databaseId, current)
                _operationState.value = OperationState.Success(
                    "Created MDBX snapshot ${snapshot.name}"
                )
                onResult?.invoke(Result.success(snapshot))
            } catch (e: Exception) {
                _deltaDialogState.value = current?.copy(isSnapshotLoading = false)
                    ?: MdbxDeltaDialogState.Hidden
                _operationState.value = OperationState.Error(
                    "Failed to create MDBX snapshot: ${e.message ?: "unknown error"}"
                )
                onResult?.invoke(Result.failure(e))
            }
        }
    }

    fun requestSnapshotCreation(
        databaseId: Long,
        name: String,
        requestedFullSnapshot: Boolean,
        onOutcome: (MdbxSnapshotCreateOutcome) -> Unit
    ) {
        viewModelScope.launch {
            if (!requireCapability(databaseId, MdbxCapability.SNAPSHOTS, "Snapshot creation")) {
                onOutcome(
                    MdbxSnapshotCreateOutcome.Failed(
                        IllegalStateException(
                            getApplication<Application>().getString(R.string.mdbx_snapshot_unavailable)
                        )
                    )
                )
                return@launch
            }
            val current = _deltaDialogState.value as? MdbxDeltaDialogState.Visible
            _deltaDialogState.value = current?.copy(isSnapshotLoading = true)
                ?: MdbxDeltaDialogState.Hidden
            try {
                val plan = withContext(Dispatchers.IO) {
                    val database = databaseDao.getDatabaseById(databaseId)
                        ?: throw IllegalStateException("MDBX vault not found: $databaseId")
                    val latestSnapshotBaseCommitId = vaultStore.listSnapshots(databaseId)
                        .firstOrNull()
                        ?.baseCommitId
                    val currentHeadCommitId = vaultStore.getCurrentHeadCommitId(databaseId)
                    planMdbxSnapshotCreation(
                        requestedFullSnapshot = requestedFullSnapshot,
                        engineRequiresFullSnapshot =
                            database.engineTypeEnum == MdbxEngineType.RUST_MDBX2,
                        currentHeadCommitId = currentHeadCommitId,
                        latestSnapshotBaseCommitId = latestSnapshotBaseCommitId
                    )
                }
                if (plan == MdbxSnapshotCreationPlan.ConfirmFullSnapshot) {
                    _deltaDialogState.value = current?.copy(isSnapshotLoading = false)
                        ?: MdbxDeltaDialogState.Hidden
                    onOutcome(MdbxSnapshotCreateOutcome.NoChanges)
                    return@launch
                }
                val fullSnapshot = (plan as MdbxSnapshotCreationPlan.Create).fullSnapshot
                invalidateMdbxViewCaches(databaseId)
                val snapshot = withContext(Dispatchers.IO) {
                    vaultStore.createSnapshot(
                        databaseId = databaseId,
                        name = name,
                        fullSnapshot = fullSnapshot,
                        autoPrune = false
                    )
                }
                refreshDeltaDialogAfterSnapshotMutation(databaseId, current)
                _operationState.value = OperationState.Success(
                    "Created MDBX snapshot ${snapshot.name}"
                )
                onOutcome(MdbxSnapshotCreateOutcome.Created(snapshot))
            } catch (e: Exception) {
                _deltaDialogState.value = current?.copy(isSnapshotLoading = false)
                    ?: MdbxDeltaDialogState.Hidden
                _operationState.value = OperationState.Error(
                    "Failed to create MDBX snapshot: ${e.message ?: "unknown error"}"
                )
                onOutcome(MdbxSnapshotCreateOutcome.Failed(e))
            }
        }
    }

    fun createQuickSnapshot(
        databaseId: Long,
        onResult: ((Result<MdbxSnapshotSummary>) -> Unit)? = null
    ) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val name = getApplication<Application>().getString(
            R.string.mdbx_quick_snapshot_name,
            timestamp
        )
        createSnapshot(
            databaseId = databaseId,
            name = name,
            fullSnapshot = true,
            onResult = onResult
        )
    }

    fun deleteSnapshot(databaseId: Long, snapshotId: String) {
        viewModelScope.launch {
            if (!requireCapability(databaseId, MdbxCapability.SNAPSHOTS, "Snapshot deletion")) {
                return@launch
            }
            val current = _deltaDialogState.value as? MdbxDeltaDialogState.Visible
            _deltaDialogState.value = current?.copy(isSnapshotLoading = true)
                ?: MdbxDeltaDialogState.Hidden
            try {
                invalidateMdbxViewCaches(databaseId)
                withContext(Dispatchers.IO) {
                    vaultStore.deleteSnapshot(databaseId, snapshotId)
                }
                refreshDeltaDialogAfterSnapshotMutation(databaseId, current)
                _operationState.value = OperationState.Success("Deleted MDBX snapshot")
            } catch (e: Exception) {
                _deltaDialogState.value = current?.copy(isSnapshotLoading = false)
                    ?: MdbxDeltaDialogState.Hidden
                _operationState.value = OperationState.Error(
                    "Failed to delete MDBX snapshot: ${e.message ?: "unknown error"}"
                )
            }
        }
    }

    fun revertToSnapshot(databaseId: Long, snapshotId: String) {
        viewModelScope.launch {
            if (!requireCapability(databaseId, MdbxCapability.SNAPSHOTS, "Snapshot restore")) {
                return@launch
            }
            val current = _deltaDialogState.value as? MdbxDeltaDialogState.Visible
            _deltaDialogState.value = current?.copy(isSnapshotLoading = true, isLoading = true)
                ?: MdbxDeltaDialogState.Hidden
            try {
                invalidateMdbxViewCaches(databaseId)
                val restoredCount = withContext(Dispatchers.IO) {
                    val count = vaultStore.revertToSnapshot(databaseId, snapshotId)
                    importEntriesFromVault(databaseId, orphanPolicy = MdbxImportOrphanPolicy.APPLY_REMOTE_STATE)
                    count
                }
                refreshDeltaDialogAfterSnapshotMutation(databaseId, current)
                _operationState.value = OperationState.Success(
                    "Restored $restoredCount MDBX object(s) from snapshot"
                )
            } catch (e: Exception) {
                _deltaDialogState.value = current?.copy(
                    isSnapshotLoading = false,
                    isLoading = false
                ) ?: MdbxDeltaDialogState.Hidden
                _operationState.value = OperationState.Error(
                    "Failed to restore MDBX snapshot: ${e.message ?: "unknown error"}"
                )
            }
        }
    }

    fun pruneAutomaticSnapshots(databaseId: Long) {
        viewModelScope.launch {
            if (!requireCapability(databaseId, MdbxCapability.SNAPSHOTS, "Snapshot cleanup")) {
                return@launch
            }
            val current = _deltaDialogState.value as? MdbxDeltaDialogState.Visible
            _deltaDialogState.value = current?.copy(isSnapshotLoading = true)
                ?: MdbxDeltaDialogState.Hidden
            try {
                invalidateMdbxViewCaches(databaseId)
                val deletedCount = withContext(Dispatchers.IO) {
                    vaultStore.pruneAutomaticSnapshots(databaseId, keepCount = 0)
                }
                refreshDeltaDialogAfterSnapshotMutation(databaseId, current)
                _operationState.value = OperationState.Success(
                    "已清理 $deletedCount 个自动快照"
                )
            } catch (e: Exception) {
                _deltaDialogState.value = current?.copy(isSnapshotLoading = false)
                    ?: MdbxDeltaDialogState.Hidden
                _operationState.value = OperationState.Error(
                    "Failed to prune MDBX snapshots: ${e.message ?: "unknown error"}"
                )
            }
        }
    }

    private suspend fun refreshDeltaDialogAfterSnapshotMutation(
        databaseId: Long,
        previousState: MdbxDeltaDialogState.Visible?
    ) {
        val refreshedDeltas = withContext(Dispatchers.IO) {
            vaultStore.listDeltaHistory(databaseId)
        }
        val refreshedSnapshots = withContext(Dispatchers.IO) {
            vaultStore.listSnapshots(databaseId)
        }
        val refreshedDiagnostic = withContext(Dispatchers.IO) {
            vaultStore.getVaultDiagnostics(databaseId)
        }
        applyVaultDiagnostic(databaseId, refreshedDiagnostic)
        updateDeltaHistoryCache(databaseId, refreshedDeltas, refreshedSnapshots)
        val refreshedState = previousState?.copy(
            deltas = refreshedDeltas,
            snapshots = refreshedSnapshots,
            selectedDiffCommitId = null,
            diffItems = emptyList(),
            isLoading = false,
            isDiffLoading = false,
            isSnapshotLoading = false
        )?.let { clearSelectedStructureIfInvalid(it, refreshedSnapshots) }
        _deltaDialogState.value = refreshedState ?: MdbxDeltaDialogState.Hidden
    }

    fun resolveConflict(
        databaseId: Long,
        conflictId: String,
        resolution: MdbxConflictResolution
    ) {
        viewModelScope.launch {
            if (!requireCapability(databaseId, MdbxCapability.CONFLICTS, "Conflict resolution")) {
                return@launch
            }
            val current = _conflictDialogState.value as? MdbxConflictDialogState.Visible
            _conflictDialogState.value = current?.copy(isLoading = true)
                ?: MdbxConflictDialogState.Hidden
            try {
                withContext(Dispatchers.IO) {
                    vaultStore.resolveConflict(databaseId, conflictId, resolution)
                    importEntriesFromVault(databaseId)
                }
                val refreshedConflicts = withContext(Dispatchers.IO) {
                    vaultStore.listConflicts(databaseId)
                }
                val refreshedDiagnostic = withContext(Dispatchers.IO) {
                    vaultStore.getVaultDiagnostics(databaseId)
                }
                applyVaultDiagnostic(databaseId, refreshedDiagnostic)
                _conflictDialogState.value = current?.copy(
                    conflicts = refreshedConflicts,
                    isLoading = false
                ) ?: MdbxConflictDialogState.Hidden
            } catch (e: Exception) {
                _conflictDialogState.value = current?.copy(isLoading = false)
                    ?: MdbxConflictDialogState.Hidden
                _operationState.value = OperationState.Error(
                    "Failed to resolve conflict: ${e.message ?: "unknown error"}"
                )
            }
        }
    }

    fun dismissConflictDialog() {
        _conflictDialogState.value = MdbxConflictDialogState.Hidden
    }

    fun dismissDeltaDialog() {
        _deltaDialogState.value = MdbxDeltaDialogState.Hidden
    }

    fun dismissAdvancedTools() {
        _advancedDialogState.value = MdbxAdvancedDialogState.Hidden
    }

    fun setAsDefault(databaseId: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                databaseDao.clearDefaultDatabase()
                databaseDao.setDefaultDatabase(databaseId)
            }
        }
    }

    fun clearOperationState() {
        _operationState.value = OperationState.Idle
    }

    private fun syncBundleToExportJson(bundle: MdbxSyncBundle): String =
        JSONObject()
            .put("format", "monica-mdbx-sync-bundle-export-v1")
            .put("bundle_id", bundle.bundleId)
            .put("base_commit_id", bundle.baseCommitId)
            .put("head_commit_id", bundle.headCommitId)
            .put("commit_count", bundle.commitCount)
            .put("payload_json", bundle.payloadJson)
            .put("payload_hash", bundle.payloadHash)
            .put("created_at", bundle.createdAt)
            .toString(2)

    private fun parseSyncBundleExportJson(rawJson: String): MdbxSyncBundle {
        val json = JSONObject(rawJson.trim())
        val format = json.optString("format")
        require(format == "monica-mdbx-sync-bundle-export-v1") {
            "Unsupported MDBX sync bundle export format"
        }
        val payloadJson = json.getString("payload_json")
        return MdbxSyncBundle(
            bundleId = json.getString("bundle_id"),
            baseCommitId = json.optString("base_commit_id").takeIf { it.isNotBlank() },
            headCommitId = json.getString("head_commit_id"),
            commitCount = json.optInt("commit_count"),
            payloadJson = payloadJson,
            payloadHash = json.getString("payload_hash"),
            createdAt = json.getString("created_at")
        )
    }

    private data class CustomDirectoryVault(
        val localCopy: File,
        val externalUri: Uri,
        val externalTreeUri: String? = null,
        val externalDocument: takagi.ru.monica.repository.Mdbx2ExternalDocument? = null
    )

    private suspend fun importMdbx2LocalVault(
        sourceUri: Uri,
        displayName: String,
        workingCopy: File,
        masterPassword: String,
        unlockMethod: MdbxUnlockMethod,
        keyFile: MdbxKeyFileSelection?,
        tigaMode: MdbxTigaMode,
        description: String?
    ) {
        require(unlockMethod != MdbxUnlockMethod.DEVICE_KEY) {
            "A device-key MDBX2 vault must be opened on its original device"
        }
        val credential = buildCredential(unlockMethod, masterPassword, keyFile)
        mdbx2Repository.validateVaultFile(workingCopy, credential)
        val encryptedPassword = credential.password
            ?.let(::normalizeMdbxPassword)
            ?.let(securityManager::encryptData)
        var databaseId: Long? = null
        try {
            databaseId = databaseDao.insertDatabase(
                LocalMdbxDatabase(
                    name = displayName,
                    filePath = sourceUri.toString(),
                    storageLocation = MdbxStorageLocation.EXTERNAL.name,
                    sourceType = MdbxSourceType.LOCAL_EXTERNAL.name,
                    sourceId = null,
                    engineType = MdbxEngineType.RUST_MDBX2.name,
                    tigaMode = tigaMode.name,
                    encryptedPassword = encryptedPassword,
                    unlockMethod = unlockMethod.storedValue,
                    kdfProfile = "argon2id-mdbx2",
                    keyFileName = keyFile?.name,
                    keyFileUri = keyFile?.uri,
                    keyFileFingerprint = keyFile?.fingerprint,
                    description = description,
                    lastSyncedAt = System.currentTimeMillis(),
                    workingCopyPath = workingCopy.absolutePath,
                    cacheCopyPath = workingCopy.absolutePath,
                    externalTreeUri = null,
                    isOfflineAvailable = true,
                    lastSyncStatus = MdbxSyncStatus.IN_SYNC.name
                )
            )
            importEntriesFromVault(databaseId)
        } catch (error: Throwable) {
            databaseId?.let { id ->
                runCatching { clearImportedEntries(id) }
                runCatching { databaseDao.deleteDatabaseById(id) }
            }
            runCatching { mdbx2Repository.deleteOwnedVaultFile(workingCopy) }
            throw error
        }
    }

    private suspend fun createVaultFileInCustomDir(
        treeUri: Uri,
        displayName: String,
        tigaMode: String,
        credential: MdbxVaultCredential,
        engineType: MdbxEngineType
    ): CustomDirectoryVault {
        MdbxDiagLogger.append(
            "[MDBX][createVaultFileInCustomDir] start name=$displayName treeUri=$treeUri tiga=$tigaMode unlock=${credential.unlockMethod.name}"
        )
        val documentFile = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalArgumentException("Cannot access selected directory")

        val fileName = if (displayName.endsWith(".mdbx", ignoreCase = true)) {
            displayName
        } else {
            "$displayName.mdbx"
        }

        val localVaultFile = when (engineType) {
            MdbxEngineType.KOTLIN_MDBX1 -> legacyVaultStore.createInitializedVaultFile(
                displayName = displayName,
                tigaMode = tigaMode,
                unlockMethod = credential.unlockMethod,
                credential = credential
            )
            MdbxEngineType.RUST_MDBX2 -> mdbx2Repository.createInitializedVaultFile(
                tigaMode = MdbxTigaMode.fromName(tigaMode),
                credential = credential
            )
        }

        var legacyExternalUri: Uri? = null
        val externalDocument = if (engineType == MdbxEngineType.RUST_MDBX2) {
            runCatching {
                mdbx2Repository.createExternalDocument(treeUri, fileName, localVaultFile)
            }.getOrElse { error ->
                mdbx2Repository.deleteOwnedVaultFile(localVaultFile)
                throw error
            }
        } else {
            val createdFile = documentFile.createFile("application/octet-stream", fileName)
                ?: throw IllegalArgumentException("Failed to create file in selected directory")
            legacyExternalUri = createdFile.uri
            context.contentResolver.openOutputStream(createdFile.uri)?.use { output ->
                localVaultFile.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IllegalArgumentException("Cannot write to selected directory")
            null
        }

        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }

        MdbxDiagLogger.append(
            "[MDBX][createVaultFileInCustomDir] success"
        )

        return CustomDirectoryVault(
            localCopy = localVaultFile,
            externalUri = externalDocument?.fileUri
                ?: legacyExternalUri
                ?: throw IllegalStateException("External MDBX file was not created"),
            externalTreeUri = treeUri.toString(),
            externalDocument = externalDocument
        )
    }

    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
            }
        }.getOrNull()
    }

    private fun buildCredential(
        unlockMethod: MdbxUnlockMethod,
        masterPassword: String,
        keyFile: MdbxKeyFileSelection?,
        deviceKeyBytes: ByteArray? = null
    ): MdbxVaultCredential =
        MdbxVaultCredential(
            unlockMethod = unlockMethod,
            password = masterPassword.takeIf {
                unlockMethod == MdbxUnlockMethod.MASTER_PASSWORD ||
                    unlockMethod == MdbxUnlockMethod.MASTER_PASSWORD_AND_KEY_FILE
            }?.let(::normalizeMdbxPassword),
            keyFileBytes = keyFile?.bytes.takeIf {
                unlockMethod == MdbxUnlockMethod.KEY_FILE ||
                    unlockMethod == MdbxUnlockMethod.MASTER_PASSWORD_AND_KEY_FILE
            },
            deviceKeyBytes = deviceKeyBytes.takeIf {
                unlockMethod == MdbxUnlockMethod.DEVICE_KEY
            },
            keyFileName = keyFile?.name,
            keyFileFingerprint = keyFile?.fingerprint
        )

    private enum class MdbxImportOrphanPolicy {
        RESCUE_LOCAL_ACTIVE,
        APPLY_REMOTE_STATE
    }

    private suspend fun importEntriesFromVault(
        databaseId: Long,
        orphanPolicy: MdbxImportOrphanPolicy = MdbxImportOrphanPolicy.RESCUE_LOCAL_ACTIVE
    ) = withContext(Dispatchers.IO) {
        invalidateMdbxViewCaches(databaseId)
        val database = databaseDao.getDatabaseById(databaseId)
            ?: throw IllegalStateException("Vault not found")
        var entries: List<MdbxStoredVaultEntry> = emptyList()
        val readMs = measureTimeMillis {
            entries = vaultStore.readStoredEntries(databaseId)
        }
        val payloadByEntryId = mutableMapOf<String, JSONObject>()
        val importedPasswordIds = mutableMapOf<String, Long>()
        val importedSecureItemIds = mutableMapOf<String, Long>()
        val remotePasswordRoomIdsByEntryId = entries
            .filter { !it.deleted && it.entryType == "login" }
            .mapNotNull { stored ->
                runCatching { JSONObject(stored.payloadJson) }
                    .getOrNull()
                    ?.optLong("room_id", 0L)
                    ?.takeIf { it > 0L }
                    ?.let { roomId -> stored.entryId to roomId }
            }
            .toMap()
        val existingPasswordsByEntryId = normalizeLegacyMdbxPasswordRows(
            databaseId = databaseId,
            remoteRoomIdsByEntryId = remotePasswordRoomIdsByEntryId
        )
            .dedupeMdbxPasswordRowsByEntryId()
            .mapNotNull { entry -> entry.replicaGroupId?.let { it to entry } }
            .toMap()
        val existingSecureItemsByEntryId = secureItemDao.getByMdbxDatabaseIdSync(databaseId)
            .dedupeMdbxSecureItemRowsByEntryId()
            .mapNotNull { item -> item.mdbxPrimaryImportEntryId()?.let { entryId -> entryId to item } }
            .toMap()
        val existingPasskeysByEntryId = passkeyDao.getByMdbxDatabaseId(databaseId)
            .mapNotNull { passkey ->
                passkey.credentialId.takeIf { it.isNotBlank() }?.let { credentialId ->
                    "passkey:$credentialId" to passkey
                }
            }
            .toMap()
        val activePasswordEntryIds = mutableSetOf<String>()
        val activeSecureItemEntryIds = mutableSetOf<String>()
        val activePasskeyEntryIds = mutableSetOf<String>()
        val deletedPasswordEntryIds = entries
            .filter { it.deleted && it.entryType == "login" }
            .map { it.entryId }
            .toSet()
        val deletedSecureItemEntryIds = entries
            .filter { it.deleted && it.entryType in mdbxSecureItemEntryTypes }
            .map { it.entryId }
            .toSet()
        val deletedPasskeyEntryIds = entries
            .filter { it.deleted && it.entryType == "passkey" }
            .map { it.entryId }
            .toSet()
        val vaultActivePasswordEntryIds = entries
            .filter { !it.deleted && it.entryType == "login" }
            .map { it.entryId }
        val vaultActiveSecureItemEntryIds = entries
            .filter { !it.deleted && it.entryType in mdbxSecureItemEntryTypes }
            .map { it.entryId }
        MdbxDiagLogger.append(
            "[MDBX][import-scan] databaseId=$databaseId entries=${entries.size} activePasswords=${vaultActivePasswordEntryIds.size} deletedPasswords=${deletedPasswordEntryIds.size} activeSecureItems=${vaultActiveSecureItemEntryIds.size} deletedSecureItems=${deletedSecureItemEntryIds.size} existingPasswordRows=${existingPasswordsByEntryId.size} existingSecureItemRows=${existingSecureItemsByEntryId.size} activePasswordEntryIds=${summarizeDiagValues(vaultActivePasswordEntryIds)} deletedPasswordEntryIds=${summarizeDiagValues(deletedPasswordEntryIds)} existingPasswordEntryIds=${summarizeDiagValues(existingPasswordsByEntryId.keys)} lastSyncedAt=${database.lastSyncedAt ?: "-"}"
        )
        val reconcileMs = measureTimeMillis {
        }
        val importMs = measureTimeMillis {
            entries.filterNot { it.deleted }.forEach { stored ->
                val payload = runCatching { JSONObject(stored.payloadJson) }.getOrNull()
                    ?: return@forEach
                payloadByEntryId[stored.entryId] = payload
                if (stored.entryType == "login") {
                    activePasswordEntryIds += stored.entryId
                    val passwordId = importPasswordEntry(
                        databaseId = databaseId,
                        stored = stored,
                        payload = payload,
                        existing = existingPasswordsByEntryId[stored.entryId]
                    )
                    importedPasswordIds[stored.entryId] = passwordId
                }
            }

            entries.filterNot { it.deleted }.forEach { stored ->
                val payload = payloadByEntryId[stored.entryId] ?: return@forEach
                when (stored.entryType) {
                    "note", "totp", "card", "document-ref", "billing-address", "payment-account" -> {
                        activeSecureItemEntryIds += stored.entryId
                        importSecureItem(
                            databaseId = databaseId,
                            stored = stored,
                            payload = payload,
                            importedPasswordIds = importedPasswordIds,
                            existing = existingSecureItemsByEntryId[stored.entryId]
                        )
                            ?.let { secureItemId -> importedSecureItemIds[stored.entryId] = secureItemId }
                    }
                    "passkey" -> {
                        activePasskeyEntryIds += stored.entryId
                        importPasskey(
                            databaseId = databaseId,
                            stored = stored,
                            payload = payload,
                            existing = existingPasskeysByEntryId[stored.entryId]
                        )
                    }
                }
            }
            restoreImportedBindings(payloadByEntryId, importedPasswordIds, importedSecureItemIds)
            existingPasswordsByEntryId
                .filterKeys { it !in activePasswordEntryIds }
                .values
                .let { orphanedRows ->
                    val (remoteDeletedRows, missingRemoteRows) = orphanedRows.partition {
                        it.replicaGroupId in deletedPasswordEntryIds
                    }
                    if (orphanedRows.isNotEmpty()) {
                        MdbxDiagLogger.append(
                            "[MDBX][orphan-classify] type=password databaseId=$databaseId orphanCount=${orphanedRows.size} remoteDeletedCount=${remoteDeletedRows.size} missingRemoteCount=${missingRemoteRows.size} remoteDeleted=${summarizeDiagValues(remoteDeletedRows.map { it.mdbxPasswordDiagLabel() })} missingRemote=${summarizeDiagValues(missingRemoteRows.map { it.mdbxPasswordDiagLabel() })} deletedEntryIds=${summarizeDiagValues(deletedPasswordEntryIds)} activeEntryIds=${summarizeDiagValues(activePasswordEntryIds)} lastSyncedAt=${database.lastSyncedAt ?: "-"}"
                        )
                    }
                    if (orphanPolicy == MdbxImportOrphanPolicy.RESCUE_LOCAL_ACTIVE) {
                        rescueMissingRemoteMdbxPasswordRows(
                            database = database,
                            rows = missingRemoteRows
                        )
                        rescueRemoteDeletedMdbxPasswordRows(
                            database = database,
                            rows = remoteDeletedRows
                        )
                    } else {
                        applyRemoteStateToOrphanedMdbxPasswordRows(
                            database = database,
                            rows = missingRemoteRows + remoteDeletedRows,
                            reason = "snapshot_revert"
                        )
                    }
                }
            existingSecureItemsByEntryId
                .filterKeys { it !in activeSecureItemEntryIds }
                .values
                .let { orphanedItems ->
                    val (remoteDeletedItems, missingRemoteItems) = orphanedItems.partition {
                        it.mdbxPrimaryImportEntryId() in deletedSecureItemEntryIds
                    }
                    if (orphanedItems.isNotEmpty()) {
                        MdbxDiagLogger.append(
                            "[MDBX][orphan-classify] type=secure_item databaseId=$databaseId orphanCount=${orphanedItems.size} remoteDeletedCount=${remoteDeletedItems.size} missingRemoteCount=${missingRemoteItems.size} remoteDeleted=${summarizeDiagValues(remoteDeletedItems.map { it.mdbxSecureItemDiagLabel() })} missingRemote=${summarizeDiagValues(missingRemoteItems.map { it.mdbxSecureItemDiagLabel() })} deletedEntryIds=${summarizeDiagValues(deletedSecureItemEntryIds)} activeEntryIds=${summarizeDiagValues(activeSecureItemEntryIds)} lastSyncedAt=${database.lastSyncedAt ?: "-"}"
                        )
                    }
                    if (orphanPolicy == MdbxImportOrphanPolicy.RESCUE_LOCAL_ACTIVE) {
                        rescueMissingRemoteMdbxSecureItemRows(
                            database = database,
                            items = missingRemoteItems
                        )
                        rescueRemoteDeletedMdbxSecureItemRows(
                            database = database,
                            items = remoteDeletedItems
                        )
                    } else {
                        applyRemoteStateToOrphanedMdbxSecureItemRows(
                            database = database,
                            items = missingRemoteItems + remoteDeletedItems,
                            reason = "snapshot_revert"
                        )
                    }
                }
            existingPasskeysByEntryId
                .filterKeys { it !in activePasskeyEntryIds }
                .values
                .let { orphanedPasskeys ->
                    val (remoteDeletedPasskeys, missingRemotePasskeys) = orphanedPasskeys.partition {
                        "passkey:${it.credentialId}" in deletedPasskeyEntryIds
                    }
                    if (orphanedPasskeys.isNotEmpty()) {
                        MdbxDiagLogger.append(
                            "[MDBX][orphan-classify] type=passkey databaseId=$databaseId orphanCount=${orphanedPasskeys.size} remoteDeletedCount=${remoteDeletedPasskeys.size} missingRemoteCount=${missingRemotePasskeys.size} remoteDeleted=${summarizeDiagValues(remoteDeletedPasskeys.map { it.mdbxPasskeyDiagLabel() })} missingRemote=${summarizeDiagValues(missingRemotePasskeys.map { it.mdbxPasskeyDiagLabel() })} deletedEntryIds=${summarizeDiagValues(deletedPasskeyEntryIds)} activeEntryIds=${summarizeDiagValues(activePasskeyEntryIds)} lastSyncedAt=${database.lastSyncedAt ?: "-"}"
                        )
                    }
                    if (orphanPolicy == MdbxImportOrphanPolicy.RESCUE_LOCAL_ACTIVE) {
                        rescueMissingRemoteMdbxPasskeyRows(
                            database = database,
                            passkeys = missingRemotePasskeys
                        )
                        rescueRemoteDeletedMdbxPasskeyRows(
                            database = database,
                            passkeys = remoteDeletedPasskeys
                        )
                    } else {
                        applyRemoteStateToOrphanedMdbxPasskeys(
                            database = database,
                            passkeys = missingRemotePasskeys + remoteDeletedPasskeys,
                            reason = "snapshot_revert"
                        )
                    }
                }
        }
        val attachmentMs = measureTimeMillis {
            importAttachmentsFromVault(
                databaseId = databaseId,
                importedPasswordIds = importedPasswordIds,
                importedSecureItemIds = importedSecureItemIds
            )
        }
        MdbxDiagLogger.append(
            "[MDBX][perf][importEntriesFromVault] databaseId=$databaseId entries=${entries.size} active=${entries.count { !it.deleted }} passwords=${importedPasswordIds.size} secureItems=${importedSecureItemIds.size} readMs=$readMs reconcileMs=$reconcileMs importMs=$importMs attachmentMs=$attachmentMs"
        )
    }

    private val mdbxSecureItemEntryTypes = setOf("note", "totp", "card", "document-ref", "billing-address", "payment-account")

    private data class CustomFieldFingerprint(
        val title: String,
        val value: String,
        val isProtected: Boolean,
        val sortOrder: Int
    )

    private data class AttachmentFingerprint(
        val fileName: String,
        val mimeType: String,
        val sizeBytes: Long,
        val sha256Hex: String?,
        val createdAt: Long,
        val updatedAt: Long
    )

    private fun summarizeDiagValues(values: Iterable<Any?>, limit: Int = 20): String {
        val list = values.map { it?.toString() ?: "-" }
        if (list.size <= limit) return list.toString()
        return "${list.take(limit)}...(+${list.size - limit})"
    }

    private fun PasswordEntry.mdbxPasswordDiagLabel(): String =
        "room=$id entry=${replicaGroupId ?: "-"} deleted=$isDeleted updatedAt=${updatedAt.time} deletedAt=${deletedAt?.time ?: "-"}"

    private fun SecureItem.mdbxSecureItemDiagLabel(): String =
        "room=$id type=$itemType entry=${mdbxPrimaryImportEntryId() ?: "-"} deleted=$isDeleted updatedAt=${updatedAt.time} deletedAt=${deletedAt?.time ?: "-"}"

    private fun PasskeyEntry.mdbxPasskeyDiagLabel(): String =
        "room=$id entry=passkey:$credentialId rp=$rpId lastUsedAt=$lastUsedAt createdAt=$createdAt"

    private suspend fun applyRemoteStateToOrphanedMdbxPasswordRows(
        database: LocalMdbxDatabase,
        rows: Collection<PasswordEntry>,
        reason: String
    ) {
        val now = Date()
        val rowsToMarkDeleted = rows
            .filterNot { it.isDeleted }
            .map {
                it.copy(
                    isDeleted = true,
                    deletedAt = now,
                    isArchived = false,
                    archivedAt = null,
                    updatedAt = now
                )
            }
        if (rowsToMarkDeleted.isEmpty()) return
        passwordEntryDao.updatePasswordEntries(rowsToMarkDeleted)
        MdbxDiagLogger.append(
            "[MDBX][orphan-remote-state] type=password reason=$reason databaseId=${database.id} count=${rowsToMarkDeleted.size} rows=${summarizeDiagValues(rowsToMarkDeleted.map { it.mdbxPasswordDiagLabel() })} lastSyncedAt=${database.lastSyncedAt ?: "-"}"
        )
    }

    private suspend fun applyRemoteStateToOrphanedMdbxSecureItemRows(
        database: LocalMdbxDatabase,
        items: Collection<SecureItem>,
        reason: String
    ) {
        val now = Date()
        val itemsToMarkDeleted = items
            .filterNot { it.isDeleted }
            .map {
                it.copy(
                    isDeleted = true,
                    deletedAt = now,
                    updatedAt = now
                )
            }
        if (itemsToMarkDeleted.isEmpty()) return
        secureItemDao.updateAll(itemsToMarkDeleted)
        MdbxDiagLogger.append(
            "[MDBX][orphan-remote-state] type=secure_item reason=$reason databaseId=${database.id} count=${itemsToMarkDeleted.size} rows=${summarizeDiagValues(itemsToMarkDeleted.map { it.mdbxSecureItemDiagLabel() })} lastSyncedAt=${database.lastSyncedAt ?: "-"}"
        )
    }

    private suspend fun applyRemoteStateToOrphanedMdbxPasskeys(
        database: LocalMdbxDatabase,
        passkeys: Collection<PasskeyEntry>,
        reason: String
    ) {
        val recordIds = passkeys.map { it.id }.filter { it > 0L }
        if (recordIds.isEmpty()) return
        passkeyDao.deleteByRecordIds(recordIds)
        MdbxDiagLogger.append(
            "[MDBX][orphan-remote-state] type=passkey reason=$reason databaseId=${database.id} count=${recordIds.size} rows=${summarizeDiagValues(passkeys.map { it.mdbxPasskeyDiagLabel() })} lastSyncedAt=${database.lastSyncedAt ?: "-"}"
        )
    }

    private suspend fun rescueMissingRemoteMdbxPasswordRows(
        database: LocalMdbxDatabase,
        rows: Collection<PasswordEntry>
    ) {
        val rowsToRescue = rows
            .filterNot { it.isDeleted }
            .map { it.withNormalizedMdbxPasswordEntryId() }
            .toList()
        if (rowsToRescue.isEmpty()) return

        try {
            passwordEntryDao.updatePasswordEntries(rowsToRescue)
            vaultStore.upsertPasswords(rowsToRescue)
            MdbxDiagLogger.append(
                "[MDBX][orphan-rescue] type=password reason=missing_remote_entry databaseId=${database.id} count=${rowsToRescue.size} ids=${rowsToRescue.map { it.id }} entryIds=${rowsToRescue.map { it.replicaGroupId ?: "-" }} lastSyncedAt=${database.lastSyncedAt ?: "-"}"
            )
        } catch (e: Exception) {
            MdbxDiagLogger.append(
                "[MDBX][orphan-rescue] failure type=password reason=missing_remote_entry databaseId=${database.id} count=${rowsToRescue.size} ids=${rowsToRescue.map { it.id }} entryIds=${rowsToRescue.map { it.replicaGroupId ?: "-" }} error=${e::class.java.simpleName}:${e.message}"
            )
            throw IllegalStateException(
                "Active local MDBX password rows are missing from the vault and could not be written back; refusing to delete them during sync.",
                e
            )
        }
    }

    private suspend fun rescueMissingRemoteMdbxSecureItemRows(
        database: LocalMdbxDatabase,
        items: Collection<SecureItem>
    ) {
        val itemsToRescue = items
            .filterNot { it.isDeleted }
            .toList()
        if (itemsToRescue.isEmpty()) return

        try {
            vaultStore.upsertSecureItems(itemsToRescue)
            MdbxDiagLogger.append(
                "[MDBX][orphan-rescue] type=secure_item reason=missing_remote_entry databaseId=${database.id} count=${itemsToRescue.size} ids=${itemsToRescue.map { it.id }} entryIds=${itemsToRescue.map { it.mdbxPrimaryImportEntryId() ?: "-" }} lastSyncedAt=${database.lastSyncedAt ?: "-"}"
            )
        } catch (e: Exception) {
            MdbxDiagLogger.append(
                "[MDBX][orphan-rescue] failure type=secure_item reason=missing_remote_entry databaseId=${database.id} count=${itemsToRescue.size} ids=${itemsToRescue.map { it.id }} entryIds=${itemsToRescue.map { it.mdbxPrimaryImportEntryId() ?: "-" }} error=${e::class.java.simpleName}:${e.message}"
            )
            throw IllegalStateException(
                "Active local MDBX secure-item rows are missing from the vault and could not be written back; refusing to delete them during sync.",
                e
            )
        }
    }

    private suspend fun rescueRemoteDeletedMdbxPasswordRows(
        database: LocalMdbxDatabase,
        rows: Collection<PasswordEntry>
    ) {
        val rowsToRescue = rows
            .filterNot { it.isDeleted }
            .map { it.withNormalizedMdbxPasswordEntryId() }
            .toList()
        if (rowsToRescue.isEmpty()) return

        try {
            passwordEntryDao.updatePasswordEntries(rowsToRescue)
            vaultStore.upsertPasswords(rowsToRescue)
            MdbxDiagLogger.append(
                "[MDBX][orphan-rescue] type=password reason=remote_deleted_local_active databaseId=${database.id} count=${rowsToRescue.size} rows=${summarizeDiagValues(rowsToRescue.map { it.mdbxPasswordDiagLabel() })} lastSyncedAt=${database.lastSyncedAt ?: "-"}"
            )
        } catch (e: Exception) {
            MdbxDiagLogger.append(
                "[MDBX][orphan-rescue] failure type=password reason=remote_deleted_local_active databaseId=${database.id} count=${rowsToRescue.size} rows=${summarizeDiagValues(rowsToRescue.map { it.mdbxPasswordDiagLabel() })} error=${e::class.java.simpleName}:${e.message}"
            )
            throw IllegalStateException(
                "Active local MDBX password rows have remote tombstones and could not be written back; refusing to delete them during sync.",
                e
            )
        }
    }

    private suspend fun rescueRemoteDeletedMdbxSecureItemRows(
        database: LocalMdbxDatabase,
        items: Collection<SecureItem>
    ) {
        val itemsToRescue = items
            .filterNot { it.isDeleted }
            .toList()
        if (itemsToRescue.isEmpty()) return

        try {
            vaultStore.upsertSecureItems(itemsToRescue)
            MdbxDiagLogger.append(
                "[MDBX][orphan-rescue] type=secure_item reason=remote_deleted_local_active databaseId=${database.id} count=${itemsToRescue.size} rows=${summarizeDiagValues(itemsToRescue.map { it.mdbxSecureItemDiagLabel() })} lastSyncedAt=${database.lastSyncedAt ?: "-"}"
            )
        } catch (e: Exception) {
            MdbxDiagLogger.append(
                "[MDBX][orphan-rescue] failure type=secure_item reason=remote_deleted_local_active databaseId=${database.id} count=${itemsToRescue.size} rows=${summarizeDiagValues(itemsToRescue.map { it.mdbxSecureItemDiagLabel() })} error=${e::class.java.simpleName}:${e.message}"
            )
            throw IllegalStateException(
                "Active local MDBX secure-item rows have remote tombstones and could not be written back; refusing to delete them during sync.",
                e
            )
        }
    }

    private suspend fun rescueMissingRemoteMdbxPasskeyRows(
        database: LocalMdbxDatabase,
        passkeys: Collection<PasskeyEntry>
    ) {
        val passkeysToRescue = passkeys.toList()
        if (passkeysToRescue.isEmpty()) return

        try {
            vaultStore.upsertPasskeys(passkeysToRescue)
            MdbxDiagLogger.append(
                "[MDBX][orphan-rescue] type=passkey reason=missing_remote_entry databaseId=${database.id} count=${passkeysToRescue.size} rows=${summarizeDiagValues(passkeysToRescue.map { it.mdbxPasskeyDiagLabel() })} lastSyncedAt=${database.lastSyncedAt ?: "-"}"
            )
        } catch (e: Exception) {
            MdbxDiagLogger.append(
                "[MDBX][orphan-rescue] failure type=passkey reason=missing_remote_entry databaseId=${database.id} count=${passkeysToRescue.size} rows=${summarizeDiagValues(passkeysToRescue.map { it.mdbxPasskeyDiagLabel() })} error=${e::class.java.simpleName}:${e.message}"
            )
            throw IllegalStateException(
                "Active local MDBX passkey rows are missing from the vault and could not be written back; refusing to delete them during sync.",
                e
            )
        }
    }

    private suspend fun rescueRemoteDeletedMdbxPasskeyRows(
        database: LocalMdbxDatabase,
        passkeys: Collection<PasskeyEntry>
    ) {
        val passkeysToRescue = passkeys.toList()
        if (passkeysToRescue.isEmpty()) return

        try {
            vaultStore.upsertPasskeys(passkeysToRescue)
            MdbxDiagLogger.append(
                "[MDBX][orphan-rescue] type=passkey reason=remote_deleted_local_active databaseId=${database.id} count=${passkeysToRescue.size} rows=${summarizeDiagValues(passkeysToRescue.map { it.mdbxPasskeyDiagLabel() })} lastSyncedAt=${database.lastSyncedAt ?: "-"}"
            )
        } catch (e: Exception) {
            MdbxDiagLogger.append(
                "[MDBX][orphan-rescue] failure type=passkey reason=remote_deleted_local_active databaseId=${database.id} count=${passkeysToRescue.size} rows=${summarizeDiagValues(passkeysToRescue.map { it.mdbxPasskeyDiagLabel() })} error=${e::class.java.simpleName}:${e.message}"
            )
            throw IllegalStateException(
                "Active local MDBX passkey rows have remote tombstones and could not be written back; refusing to delete them during sync.",
                e
            )
        }
    }

    private suspend fun clearImportedEntries(databaseId: Long) {
        passwordEntryDao.deleteAllByMdbxDatabaseId(databaseId)
        secureItemDao.deleteAllByMdbxDatabaseId(databaseId)
        passkeyDao.deleteAllByMdbxDatabaseId(databaseId)
    }

    private suspend fun deleteVaultPersistence(database: LocalMdbxDatabase) {
        val databaseId = database.id
        if (database.engineTypeEnum == MdbxEngineType.RUST_MDBX2) {
            runCatching { mdbx2RemoteSyncCoordinator.clearLocalState(databaseId) }
        }
        val attachmentPaths = (
            attachmentDao.selectLocalPathsByMdbxDatabaseId(databaseId) +
                attachmentDao.selectSecureItemLocalPathsByMdbxDatabaseId(databaseId)
            )
            .filter(String::isNotBlank)
            .distinct()
        val passkeyKeyReferences = passkeyDao.getByMdbxDatabaseId(databaseId)
            .map { it.privateKeyAlias }
            .filter(String::isNotBlank)
            .distinct()
        roomDatabase.withTransaction {
            clearImportedEntries(databaseId)
            databaseDao.deleteDatabaseById(databaseId)
            database.sourceId?.let { remoteSourceDao.deleteSourceById(it) }
        }

        val failedAttachmentDeletes = attachmentPaths.filter { path ->
            attachmentDao.countByLocalPath(path) == 0 && !attachmentStorage.delete(path)
        }
        passkeyKeyReferences.forEach { reference ->
            if (passkeyDao.countByPrivateKeyAlias(reference) == 0) {
                PasskeyPrivateKeyStore.removeIfProtectedReference(context, reference)
            }
        }
        val vaultFileDeleted = database.engineTypeEnum != MdbxEngineType.RUST_MDBX2 ||
            mdbx2Repository.deleteOwnedVaultFile(File(database.resolvedActiveFilePath()))
        check(failedAttachmentDeletes.isEmpty() && vaultFileDeleted) {
            "Vault metadata was removed, but some owned local files could not be deleted"
        }
    }

    private suspend fun normalizeLegacyMdbxPasswordRows(
        databaseId: Long,
        remoteRoomIdsByEntryId: Map<String, Long>
    ): List<PasswordEntry> {
        val rows = passwordEntryDao.getByMdbxDatabaseIdSync(databaseId)
        if (rows.isEmpty()) return rows

        val normalizedRows = rows.map { it.withNormalizedMdbxPasswordEntryId() }
        val rowsNeedingReplicaUpdate = normalizedRows.filterIndexed { index, row ->
            row.replicaGroupId != rows[index].replicaGroupId
        }
        if (rowsNeedingReplicaUpdate.isNotEmpty()) {
            passwordEntryDao.updatePasswordEntries(rowsNeedingReplicaUpdate)
            MdbxDiagLogger.append(
                "[MDBX][legacy-normalize] type=password databaseId=$databaseId count=${rowsNeedingReplicaUpdate.size} rows=${summarizeDiagValues(rowsNeedingReplicaUpdate.map { it.mdbxPasswordDiagLabel() })}"
            )
        }

        val duplicateRowsToDelete = normalizedRows
            .mapNotNull { row -> row.replicaGroupId?.takeIf(String::isNotBlank)?.let { it to row } }
            .groupBy({ it.first }, { it.second })
            .flatMap { (entryId, groupedRows) ->
                if (groupedRows.size <= 1) {
                    emptyList()
                } else {
                    val keeper = remoteRoomIdsByEntryId[entryId]
                        ?.let { roomId -> groupedRows.firstOrNull { it.id == roomId } }
                        ?: groupedRows.maxWithOrNull(
                            compareBy<PasswordEntry> { it.updatedAt.time }
                                .thenBy { it.id }
                        )
                        ?: return@flatMap emptyList()
                    groupedRows.filterNot { it.id == keeper.id }
                }
            }
        val duplicateIdsToDelete = duplicateRowsToDelete.map { it.id }.toSet()
        if (duplicateIdsToDelete.isNotEmpty()) {
            duplicateIdsToDelete.forEach { passwordEntryDao.deletePasswordEntryById(it) }
            MdbxDiagLogger.append(
                "[MDBX][duplicate-local-delete] type=password databaseId=$databaseId count=${duplicateIdsToDelete.size} rows=${summarizeDiagValues(duplicateRowsToDelete.map { it.mdbxPasswordDiagLabel() })}"
            )
        }

        return normalizedRows.filterNot { it.id in duplicateIdsToDelete }
    }

    private fun List<PasswordEntry>.dedupeMdbxPasswordRowsByEntryId(): List<PasswordEntry> {
        if (isEmpty()) return this
        val keepIds = mutableSetOf<Long>()
        groupBy { it.replicaGroupId?.takeIf(String::isNotBlank) }.forEach { (entryId, rows) ->
            if (entryId == null) {
                keepIds += rows.map { it.id }
                return@forEach
            }
            val keeper = rows.maxByOrNull { it.updatedAt.time } ?: return@forEach
            keepIds += keeper.id
            if (rows.size > 1) {
                MdbxDiagLogger.append(
                    "[MDBX][duplicate-preserve] type=password entryId=$entryId keeper=${keeper.mdbxPasswordDiagLabel()} duplicates=${summarizeDiagValues(rows.filterNot { it.id == keeper.id }.map { it.mdbxPasswordDiagLabel() })}"
                )
            }
        }
        return filter { it.id in keepIds }
    }

    private fun PasswordEntry.withNormalizedMdbxPasswordEntryId(): PasswordEntry {
        val normalizedEntryId = replicaGroupId
            ?.takeIf { it.isMdbxPasswordObjectId() }
            ?: id.takeIf { it > 0L }?.let { "password:$it" }
            ?: return this
        return if (replicaGroupId == normalizedEntryId) this else copy(replicaGroupId = normalizedEntryId)
    }

    private fun String.isMdbxPasswordObjectId(): Boolean =
        startsWith("password:") && length > "password:".length

    private fun List<SecureItem>.dedupeMdbxSecureItemRowsByEntryId(): List<SecureItem> {
        if (isEmpty()) return this
        val keepIds = mutableSetOf<Long>()
        groupBy { it.mdbxPrimaryImportEntryId() }.forEach { (entryId, rows) ->
            if (entryId == null) {
                keepIds += rows.map { it.id }
                return@forEach
            }
            val keeper = rows.maxByOrNull { it.updatedAt.time } ?: return@forEach
            keepIds += keeper.id
            if (rows.size > 1) {
                MdbxDiagLogger.append(
                    "[MDBX][duplicate-preserve] type=secure_item entryId=$entryId keeper=${keeper.mdbxSecureItemDiagLabel()} duplicates=${summarizeDiagValues(rows.filterNot { it.id == keeper.id }.map { it.mdbxSecureItemDiagLabel() })}"
                )
            }
        }
        return filter { it.id in keepIds }
    }

    private fun SecureItem.mdbxPrimaryImportEntryId(): String? =
        replicaGroupId?.takeIf(String::isNotBlank) ?: mdbxLegacyEntryId()

    private fun SecureItem.mdbxLegacyEntryId(): String? {
        val prefix = when (itemType) {
            ItemType.NOTE -> "note"
            ItemType.TOTP -> "totp"
            ItemType.BANK_CARD -> "card"
            ItemType.DOCUMENT -> "document-ref"
            ItemType.BILLING_ADDRESS -> "billing-address"
            ItemType.PAYMENT_ACCOUNT -> "payment-account"
            ItemType.PASSWORD -> "password"
        }
        return id.takeIf { it > 0 }?.let { "$prefix:$it" }
    }

    private fun LocalMdbxDatabase.hasAccessibleLocalSource(): Boolean {
        return when (sourceTypeEnum) {
            MdbxSourceType.LOCAL_INTERNAL -> {
                val activePath = workingCopyPath?.takeIf { it.isNotBlank() } ?: filePath
                hasReadableFile(activePath)
            }
            MdbxSourceType.LOCAL_EXTERNAL -> {
                hasReadableDocumentUri(filePath) ||
                    hasReadableFile(workingCopyPath)
            }
            MdbxSourceType.REMOTE_WEBDAV -> true
            MdbxSourceType.REMOTE_ONEDRIVE -> true
        }
    }

    private fun hasReadableFile(path: String?): Boolean {
        val normalizedPath = path?.takeIf { it.isNotBlank() } ?: return false
        val file = File(normalizedPath)
        return file.isFile && file.canRead()
    }

    private fun hasReadableDocumentUri(uriString: String): Boolean {
        return runCatching {
            val uri = Uri.parse(uriString)
            context.contentResolver.openInputStream(uri)?.use { input ->
                input.read(ByteArray(1))
            } != null
        }.getOrDefault(false)
    }

    private suspend fun importPasswordEntry(
        databaseId: Long,
        stored: MdbxStoredVaultEntry,
        payload: JSONObject,
        existing: PasswordEntry?
    ): Long {
        val plainPassword = payload.optString("password_plain")
            .takeIf { it.isNotEmpty() }
            ?: payload.optString("password").takeIf { it.isNotEmpty() }?.let { value ->
                runCatching { securityManager.decryptData(value) }.getOrDefault(value)
            }
            ?: ""
        val entry = PasswordEntry(
            id = existing?.id ?: 0L,
            title = stored.title,
            website = payload.optString("website"),
            username = payload.optString("username"),
            password = securityManager.encryptData(plainPassword),
            notes = payload.optString("notes"),
            appPackageName = payload.optStringPreservingExisting(
                primaryKey = "app_package_name",
                legacyKey = "appPackageName",
                existingValue = existing?.appPackageName.orEmpty()
            ),
            appName = payload.optStringPreservingExisting(
                primaryKey = "app_name",
                legacyKey = "appName",
                existingValue = existing?.appName.orEmpty()
            ),
            categoryId = existing?.categoryId,
            mdbxDatabaseId = databaseId,
            mdbxFolderId = payload.optMdbxFolderId(),
            replicaGroupId = stored.entryId,
            authenticatorKey = encodeMdbxSensitiveValueForLocalStorage(
                value = payload.optString("authenticator_key"),
                itemType = ItemType.TOTP
            ),
            passkeyBindings = payload.optString("passkey_bindings"),
            boundNoteId = if (payload.optString("bound_note_entry_id").isNotBlank()) {
                existing?.boundNoteId
            } else {
                null
            },
            loginType = payload.optString("login_type", "PASSWORD"),
            createdAt = existing?.createdAt ?: Date(),
            updatedAt = existing?.updatedAt ?: Date(),
            isFavorite = existing?.isFavorite ?: false,
            sortOrder = if (payload.has("sort_order")) {
                payload.optInt("sort_order", existing?.sortOrder ?: 0)
            } else {
                existing?.sortOrder ?: 0
            },
            isGroupCover = existing?.isGroupCover ?: false
        )
        val localPasswordId = if (existing != null) {
            if (!existing.matchesMdbxImport(entry, plainPassword)) {
                passwordEntryDao.updatePasswordEntry(entry)
            }
            existing.id
        } else {
            passwordEntryDao.insertPasswordEntry(entry)
        }

        restoreCustomFields(localPasswordId, payload)
        return localPasswordId
    }

    private fun PasswordEntry.matchesMdbxImport(
        imported: PasswordEntry,
        importedPlainPassword: String
    ): Boolean {
        val existingPlainPassword = decryptMonicaCiphertextOrRaw(password)
        val existingAuthenticatorKey = decryptMonicaCiphertextOrRaw(authenticatorKey)
        val importedAuthenticatorKey = decryptMonicaCiphertextOrRaw(imported.authenticatorKey)
        return copy(password = "", authenticatorKey = "") ==
            imported.copy(password = "", authenticatorKey = "") &&
            existingPlainPassword == importedPlainPassword &&
            existingAuthenticatorKey == importedAuthenticatorKey
    }

    private fun JSONObject.optStringPreservingExisting(
        primaryKey: String,
        legacyKey: String,
        existingValue: String
    ): String {
        return when {
            has(primaryKey) && !isNull(primaryKey) -> optString(primaryKey)
            has(legacyKey) && !isNull(legacyKey) -> optString(legacyKey)
            else -> existingValue
        }
    }

    private suspend fun restoreCustomFields(entryId: Long, payload: JSONObject) {
        val fields = payload.optJSONArray("custom_fields")
            ?: payload.optJSONArray("customFields")
            ?: return
        val restored = buildList {
            for (index in 0 until fields.length()) {
                val item = fields.optJSONObject(index) ?: continue
                val title = item.optString("title")
                    .ifBlank { item.optString("label") }
                    .trim()
                if (title.isBlank()) continue
                add(
                    CustomField(
                        id = 0L,
                        entryId = entryId,
                        title = title,
                        value = item.optString("value"),
                        isProtected = item.optBoolean("is_protected", item.optBoolean("isProtected", false)),
                        sortOrder = if (item.has("sort_order")) {
                            item.optInt("sort_order", index)
                        } else {
                            item.optInt("sortOrder", index)
                        }
                    )
                )
            }
        }
        if (!customFieldDao.getFieldsByEntryIdSync(entryId).matchesImportedCustomFields(restored)) {
            customFieldDao.replaceFieldsForEntry(entryId, restored)
        }
    }

    private fun List<CustomField>.matchesImportedCustomFields(imported: List<CustomField>): Boolean {
        return toCustomFieldFingerprints() == imported.toCustomFieldFingerprints()
    }

    private fun List<CustomField>.toCustomFieldFingerprints(): List<CustomFieldFingerprint> {
        return mapIndexed { index, field ->
            CustomFieldFingerprint(
                title = field.title,
                value = field.value,
                isProtected = field.isProtected,
                sortOrder = index
            )
        }
    }

    private suspend fun importSecureItem(
        databaseId: Long,
        stored: MdbxStoredVaultEntry,
        payload: JSONObject,
        importedPasswordIds: Map<String, Long>,
        existing: SecureItem?
    ): Long? {
        val itemType = when (stored.entryType) {
            "note" -> ItemType.NOTE
            "totp" -> ItemType.TOTP
            "card" -> ItemType.BANK_CARD
            "document-ref" -> ItemType.DOCUMENT
            "billing-address" -> ItemType.BILLING_ADDRESS
            "payment-account" -> ItemType.PAYMENT_ACCOUNT
            else -> return null
        }
        val itemData = if (itemType == ItemType.TOTP) {
            remapImportedTotpBinding(payload.optString("item_data"), payload, importedPasswordIds)
        } else {
            payload.optString("item_data")
        }
        val storedItemData = encodeMdbxSensitiveValueForLocalStorage(
            value = itemData,
            itemType = itemType
        )
        val item = SecureItem(
            id = existing?.id ?: 0L,
            itemType = itemType,
            title = stored.title,
            notes = payload.optString("notes"),
            itemData = storedItemData,
            imagePaths = payload.optString("image_paths"),
            categoryId = existing?.categoryId,
            mdbxDatabaseId = databaseId,
            mdbxFolderId = payload.optMdbxFolderId(),
            replicaGroupId = stored.entryId,
            syncStatus = existing?.syncStatus ?: "NONE",
            createdAt = existing?.createdAt ?: Date(),
            updatedAt = existing?.updatedAt ?: Date(),
            isFavorite = existing?.isFavorite ?: false,
            sortOrder = if (payload.has("sort_order")) {
                payload.optInt("sort_order", existing?.sortOrder ?: 0)
            } else {
                existing?.sortOrder ?: 0
            }
        )
        if (existing != null) {
            if (!existing.matchesMdbxImport(item)) {
                secureItemDao.updateItem(item)
            }
            return existing.id
        }
        return secureItemDao.insertItem(item)
    }

    private fun SecureItem.matchesMdbxImport(imported: SecureItem): Boolean {
        return copy(itemData = "") == imported.copy(itemData = "") &&
            decryptMonicaCiphertextOrRaw(itemData) == decryptMonicaCiphertextOrRaw(imported.itemData)
    }

    private fun remapImportedTotpBinding(
        itemData: String,
        payload: JSONObject,
        importedPasswordIds: Map<String, Long>
    ): String {
        val boundPasswordEntryId = payload.optString("bound_password_entry_id")
            .takeIf { it.isNotBlank() }
            ?: return itemData
        val localPasswordId = importedPasswordIds[boundPasswordEntryId] ?: return itemData
        return runCatching {
            val decoded = TotpDataResolver.parseStoredItemData(
                itemData = itemData,
                decryptIfNeeded = securityManager::decryptDataIfMonicaCiphertext
            ) ?: return@runCatching itemData
            val remappedJson = Json.encodeToString(decoded.copy(boundPasswordId = localPasswordId))
            if (securityManager.looksLikeMonicaCiphertext(itemData)) {
                securityManager.encryptDataLegacyCompat(remappedJson)
            } else {
                remappedJson
            }
        }.getOrDefault(itemData)
    }

    private fun encodeMdbxSensitiveValueForLocalStorage(
        value: String,
        itemType: ItemType
    ): String {
        if (value.isBlank()) return value
        if (
            itemType != ItemType.TOTP &&
            itemType != ItemType.BANK_CARD &&
            itemType != ItemType.DOCUMENT &&
            itemType != ItemType.BILLING_ADDRESS &&
            itemType != ItemType.PAYMENT_ACCOUNT
        ) {
            return value
        }
        if (securityManager.looksLikeMonicaCiphertext(value)) {
            return value
        }
        return securityManager.encryptDataLegacyCompat(value)
    }

    private suspend fun restoreImportedBindings(
        payloadByEntryId: Map<String, JSONObject>,
        importedPasswordIds: Map<String, Long>,
        importedSecureItemIds: Map<String, Long>
    ) {
        importedPasswordIds.forEach { (entryId, localPasswordId) ->
            val payload = payloadByEntryId[entryId] ?: return@forEach
            val boundNoteEntryId = payload.optString("bound_note_entry_id")
                .takeIf { it.isNotBlank() }
                ?: return@forEach
            val localNoteId = importedSecureItemIds[boundNoteEntryId] ?: return@forEach
            val password = passwordEntryDao.getPasswordEntryById(localPasswordId) ?: return@forEach
            if (password.boundNoteId != localNoteId) {
                passwordEntryDao.updatePasswordEntry(password.copy(boundNoteId = localNoteId))
            }
        }
    }

    private suspend fun importPasskey(
        databaseId: Long,
        stored: MdbxStoredVaultEntry,
        payload: JSONObject,
        existing: PasskeyEntry?
    ) {
        val credentialId = payload.optString("credential_id")
        if (credentialId.isBlank()) return
        val passkey = PasskeyPrivateKeyStore.protectPasskey(context, PasskeyEntry(
            id = existing?.id ?: 0L,
            credentialId = credentialId,
            rpId = payload.optString("rp_id"),
            rpName = payload.optString("rp_name").ifBlank { stored.title },
            userId = payload.optString("user_id"),
            userName = payload.optString("user_name"),
            userDisplayName = payload.optString("user_display_name"),
            publicKeyAlgorithm = payload.optInt("public_key_algorithm", -7),
            publicKey = payload.optString("public_key"),
            privateKeyAlias = payload.optString("private_key_alias"),
            transports = payload.optString("transports", "internal"),
            aaguid = payload.optString("aaguid"),
            signCount = payload.optLong("sign_count", 0L),
            notes = payload.optString("notes"),
            passkeyMode = payload.optString("passkey_mode", PasskeyEntry.MODE_LEGACY),
            mdbxDatabaseId = databaseId,
            mdbxFolderId = payload.optMdbxFolderId(),
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            lastUsedAt = existing?.lastUsedAt ?: System.currentTimeMillis(),
            useCount = existing?.useCount ?: 0,
            iconUrl = existing?.iconUrl,
            isDiscoverable = existing?.isDiscoverable ?: true,
            isUserVerificationRequired = existing?.isUserVerificationRequired ?: true,
            isBackedUp = existing?.isBackedUp ?: false,
            boundPasswordId = existing?.boundPasswordId,
            categoryId = existing?.categoryId,
            syncStatus = existing?.syncStatus ?: "NONE"
        ))
        if (existing != null) {
            if (existing != passkey) {
                passkeyDao.update(passkey)
            }
        } else {
            passkeyDao.insert(passkey)
        }
    }

    private fun decryptMonicaCiphertextOrRaw(value: String): String {
        return runCatching { securityManager.decryptDataIfMonicaCiphertext(value) }.getOrDefault(value)
    }

    private fun JSONObject.optMdbxFolderId(): String? {
        return optString("mdbx_folder_id")
            .trim()
            .takeIf { it.isNotBlank() && !it.equals("root", ignoreCase = true) }
    }

    private suspend fun importAttachmentsFromVault(
        databaseId: Long,
        importedPasswordIds: Map<String, Long>,
        importedSecureItemIds: Map<String, Long>
    ) {
        if (importedPasswordIds.isEmpty() && importedSecureItemIds.isEmpty()) return
        val attachments = vaultStore.readStoredAttachments(databaseId)
        val dir = File(context.filesDir, "secure_attachments")
        dir.mkdirs()
        val ownerByEntryId = buildMap {
            importedPasswordIds.forEach { (entryId, roomId) ->
                put(entryId, AttachmentOwner.password(roomId))
            }
            importedSecureItemIds.forEach { (entryId, roomId) ->
                put(entryId, AttachmentOwner.secureItem(roomId))
            }
        }
        val activeAttachmentsByOwner = attachments
            .filterNot { it.deleted }
            .filter { !it.wrappedCek.isNullOrBlank() }
            .mapNotNull { stored ->
                val entryId = stored.entryId ?: stored.projectId
                ownerByEntryId[entryId]?.let { owner -> owner to stored }
            }
            .groupBy(keySelector = { it.first }, valueTransform = { it.second })

        ownerByEntryId.values.toSet().forEach { owner ->
            val remoteAttachments = activeAttachmentsByOwner[owner].orEmpty()
            val localAttachments = when (owner.kind) {
                AttachmentOwner.Kind.PASSWORD -> attachmentDao.getActiveByParent(owner.id)
                AttachmentOwner.Kind.SECURE_ITEM -> attachmentDao.getActiveBySecureItem(owner.id)
            }
            if (localAttachments.matchesMdbxAttachments(remoteAttachments)) {
                return@forEach
            }

            when (owner.kind) {
                AttachmentOwner.Kind.PASSWORD -> attachmentDao.purgeByParent(owner.id)
                AttachmentOwner.Kind.SECURE_ITEM -> attachmentDao.purgeBySecureItem(owner.id)
            }
            remoteAttachments.forEach remoteLoop@{ stored ->
                val wrappedCek = stored.wrappedCek ?: return@remoteLoop
                val localWrappedCek = runCatching {
                    MdbxAttachmentCekPayload.toLocalWrappedCek(
                        storedValue = wrappedCek,
                        wrapBase64 = securityManager::encryptData
                    )
                }.getOrNull() ?: return@remoteLoop
                val relativePath = "${UUID.randomUUID()}.enc"
                File(dir, relativePath).writeBytes(stored.blob)
                attachmentDao.insert(
                    Attachment(
                        id = 0L,
                        parentPasswordId = owner.passwordId,
                        parentSecureItemId = owner.secureItemId,
                        source = AttachmentSource.LOCAL.name,
                        fileName = stored.fileName,
                        mimeType = stored.mimeType.ifBlank { "application/octet-stream" },
                        sizeBytes = stored.originalSize,
                        sha256Hex = stored.contentHash,
                        wrappedCek = localWrappedCek,
                        localPath = relativePath,
                        bitwardenAttachmentId = null,
                        bitwardenUrl = null,
                        bitwardenFileKeyEnc = null,
                        keepassBinaryRef = null,
                        downloadState = AttachmentDownloadState.DOWNLOADED.name,
                        createdAt = stored.createdAtMillis,
                        updatedAt = stored.updatedAtMillis,
                        isDeleted = false,
                        deletedAt = null
                    )
                )
            }
        }
    }

    private fun List<Attachment>.matchesMdbxAttachments(remoteAttachments: List<MdbxStoredAttachment>): Boolean {
        return map { it.toMdbxAttachmentFingerprint() }.sortedWith(attachmentFingerprintComparator) ==
            remoteAttachments.map { it.toAttachmentFingerprint() }.sortedWith(attachmentFingerprintComparator)
    }

    private val attachmentFingerprintComparator = compareBy<AttachmentFingerprint>(
        { it.fileName },
        { it.mimeType },
        { it.sizeBytes },
        { it.sha256Hex.orEmpty() },
        { it.createdAt },
        { it.updatedAt }
    )

    private fun Attachment.toMdbxAttachmentFingerprint(): AttachmentFingerprint =
        AttachmentFingerprint(
            fileName = fileName,
            mimeType = mimeType.ifBlank { "application/octet-stream" },
            sizeBytes = sizeBytes,
            sha256Hex = sha256Hex,
            createdAt = createdAt,
            updatedAt = updatedAt
        )

    private fun MdbxStoredAttachment.toAttachmentFingerprint(): AttachmentFingerprint =
        AttachmentFingerprint(
            fileName = fileName,
            mimeType = mimeType.ifBlank { "application/octet-stream" },
            sizeBytes = originalSize,
            sha256Hex = contentHash,
            createdAt = createdAtMillis,
            updatedAt = updatedAtMillis
        )

    private suspend fun refreshVaultFromSource(databaseId: Long) {
        val database = databaseDao.getDatabaseById(databaseId)
            ?: throw IllegalStateException("Vault not found")

        if (database.engineTypeEnum == MdbxEngineType.RUST_MDBX2) {
            when {
                database.sourceTypeEnum == MdbxSourceType.LOCAL_EXTERNAL -> {
                    mdbx2Repository.refreshExternalWorkingCopy(databaseId)
                    importEntriesFromVault(databaseId)
                    return
                }
                database.isRemoteSource() -> {
                    synchronizeMdbx2Remote(database)
                    return
                }
            }
        }

        val workingCopy = database.workingCopyPath?.let { File(it) }
            ?: File(database.filePath).takeIf { database.storageLocationEnum == MdbxStorageLocation.INTERNAL }
            ?: throw IllegalStateException("Working copy not found")

        when (database.sourceTypeEnum) {
            MdbxSourceType.LOCAL_INTERNAL -> {
                if (!workingCopy.exists()) {
                    throw IllegalStateException("Local working copy missing: ${workingCopy.absolutePath}")
                }
            }
            MdbxSourceType.LOCAL_EXTERNAL -> {
                val sourceUri = Uri.parse(database.filePath)
                val sourceBytes = context.contentResolver.openInputStream(sourceUri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("Cannot read external vault")
                workingCopy.parentFile?.mkdirs()
                if (!workingCopy.exists()) {
                    workingCopy.writeBytes(sourceBytes)
                    legacyVaultStore.validateExistingVaultFile(workingCopy)
                } else {
                    val incomingCopy = writeIncomingTempCopy(databaseId, sourceBytes)
                    try {
                        legacyVaultStore.applyIncomingVaultFile(databaseId, incomingCopy)
                    } finally {
                        incomingCopy.delete()
                    }
                }
            }
            MdbxSourceType.REMOTE_WEBDAV -> {
                val source = database.sourceId?.let { remoteSourceDao.getSourceById(it) }
                    ?: throw IllegalStateException("MDBX remote source not found")
                val sourceBytes = readRemoteVaultBytes(source)
                workingCopy.parentFile?.mkdirs()
                if (!workingCopy.exists()) {
                    workingCopy.writeBytes(sourceBytes)
                    legacyVaultStore.validateExistingVaultFile(workingCopy)
                } else {
                    val incomingCopy = writeIncomingTempCopy(databaseId, sourceBytes)
                    try {
                        legacyVaultStore.applyIncomingVaultFile(databaseId, incomingCopy)
                    } finally {
                        incomingCopy.delete()
                    }
                }
            }
            MdbxSourceType.REMOTE_ONEDRIVE -> {
                val source = database.sourceId?.let { remoteSourceDao.getSourceById(it) }
                    ?: throw IllegalStateException("MDBX OneDrive source not found")
                val sourceBytes = readOneDriveVaultBytes(source)
                workingCopy.parentFile?.mkdirs()
                if (!workingCopy.exists()) {
                    workingCopy.writeBytes(sourceBytes)
                    legacyVaultStore.validateExistingVaultFile(workingCopy)
                } else {
                    val incomingCopy = writeIncomingTempCopy(databaseId, sourceBytes)
                    try {
                        legacyVaultStore.applyIncomingVaultFile(databaseId, incomingCopy)
                    } finally {
                        incomingCopy.delete()
                    }
                }
            }
        }

        importEntriesFromVault(databaseId)
        databaseDao.updateDatabase(
            database.copy(
                lastSyncedAt = System.currentTimeMillis(),
                lastSyncStatus = MdbxSyncStatus.IN_SYNC.name,
                lastSyncError = null,
                workingCopyPath = workingCopy.absolutePath,
                cacheCopyPath = workingCopy.absolutePath,
                isOfflineAvailable = true
            )
        )
    }

    private suspend fun synchronizeMdbx2Remote(database: LocalMdbxDatabase) {
        val source = database.sourceId?.let { remoteSourceDao.getSourceById(it) }
            ?: throw IllegalStateException("MDBX2 remote source not found")
        val remotePath = source.remotePath.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("MDBX2 remote path missing")
        val transport = createMdbx2Transport(database, source)
        val state = mdbx2SyncStateStore.read(database.id)
        require(state.vaultId != null && state.bootstrapCheckpoint != null) {
            "MDBX2 remote sync is not initialized; reconnect the vault to register its bootstrap"
        }
        val report = mdbx2RemoteSyncCoordinator.synchronize(
            databaseId = database.id,
            remoteVaultPath = remotePath,
            transport = transport
        )
        importEntriesFromVault(database.id)
        val latest = databaseDao.getDatabaseById(database.id) ?: database
        val status = when {
            report.conflicts > 0 -> MdbxSyncStatus.CONFLICT
            report.blockedStreams > 0 -> MdbxSyncStatus.REMOTE_CHANGED
            else -> MdbxSyncStatus.IN_SYNC
        }
        databaseDao.updateDatabase(
            latest.copy(
                lastSyncedAt = System.currentTimeMillis(),
                lastSyncStatus = status.name,
                lastSyncError = null,
                isOfflineAvailable = true
            )
        )
    }

    private suspend fun createMdbx2Transport(
        database: LocalMdbxDatabase,
        source: MdbxRemoteSource
    ): MdbxRemoteTransport {
        val remotePath = MdbxRemoteSyncPaths.normalizePath(source.remotePath)
        require(remotePath.isNotBlank()) { "MDBX2 remote path missing" }
        return when (database.sourceTypeEnum) {
            MdbxSourceType.REMOTE_WEBDAV -> {
                val baseUrl = source.baseUrl?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("MDBX WebDAV base URL missing")
                val username = source.usernameEncrypted?.let(securityManager::decryptData)
                    ?: throw IllegalStateException("MDBX WebDAV username missing")
                val password = source.passwordEncrypted?.let(securityManager::decryptData)
                    ?: throw IllegalStateException("MDBX WebDAV password missing")
                WebDavMdbxRemoteTransport(baseUrl, username, password)
            }
            MdbxSourceType.REMOTE_ONEDRIVE -> {
                val accountId = source.usernameEncrypted?.let(securityManager::decryptData)
                    ?: throw IllegalStateException("MDBX OneDrive account ID missing")
                OneDriveMdbxRemoteTransport(context, accountId)
            }
            else -> throw IllegalArgumentException("MDBX2 remote transport requires a remote source")
        }
    }

    private fun writeIncomingTempCopy(databaseId: Long, bytes: ByteArray): File {
        val dir = File(context.cacheDir, "mdbx-incoming").apply { mkdirs() }
        return File(dir, "incoming-$databaseId-${UUID.randomUUID()}.mdbx").apply {
            writeBytes(bytes)
        }
    }

    private suspend fun readRemoteVaultBytes(source: MdbxRemoteSource): ByteArray {
        val baseUrl = source.baseUrl?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("MDBX remote source base URL missing")
        val username = source.usernameEncrypted?.let { securityManager.decryptData(it) }
            ?: throw IllegalStateException("MDBX remote username missing")
        val password = source.passwordEncrypted?.let { securityManager.decryptData(it) }
            ?: throw IllegalStateException("MDBX remote password missing")
        val remotePath = source.remotePath.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("MDBX remote path missing")
        val fileSource = WebDavMdbxFileSource(baseUrl, username, password)
        return fileSource.readFile(remotePath)
    }

    private suspend fun readOneDriveVaultBytes(source: MdbxRemoteSource): ByteArray {
        val accountId = source.usernameEncrypted?.let { securityManager.decryptData(it) }
            ?: throw IllegalStateException("MDBX OneDrive account ID missing")
        val remotePath = source.remotePath.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("MDBX OneDrive remote path missing")
        val fileSource = OneDriveMdbxFileSource(context, accountId)
        return fileSource.readFile(remotePath)
    }

    // ---

    sealed class OperationState {
        data object Idle : OperationState()
        data class Loading(val message: String) : OperationState()
        data class Success(val message: String) : OperationState()
        data class Error(val message: String) : OperationState()
    }

    enum class MdbxMigrationStage {
        PREFLIGHT,
        FOLDERS,
        ENTRIES,
        ATTACHMENTS,
        VERIFYING,
        IMPORTING
    }

    sealed class MdbxMigrationState {
        data object Hidden : MdbxMigrationState()
        data class Preparing(val sourceDatabaseId: Long) : MdbxMigrationState()
        data class Ready(val preview: MdbxMigrationPreview) : MdbxMigrationState()
        data class Running(
            val sourceDatabaseId: Long,
            val targetName: String,
            val stage: MdbxMigrationStage,
            val completed: Int,
            val total: Int
        ) : MdbxMigrationState()
        data class Success(
            val sourceDatabaseId: Long,
            val targetDatabaseId: Long,
            val targetName: String,
            val verification: MdbxMigrationVerification
        ) : MdbxMigrationState()
        data class Error(
            val sourceDatabaseId: Long,
            val message: String,
            val preview: MdbxMigrationPreview? = null
        ) : MdbxMigrationState()
    }

    sealed class MdbxConflictDialogState {
        data object Hidden : MdbxConflictDialogState()
        data class Visible(
            val databaseId: Long,
            val databaseName: String,
            val conflicts: List<MdbxConflictSummary> = emptyList(),
            val isLoading: Boolean = false
        ) : MdbxConflictDialogState()
    }

    sealed class MdbxDeltaDialogState {
        data object Hidden : MdbxDeltaDialogState()
        data class Visible(
            val databaseId: Long,
            val databaseName: String,
            val deltas: List<MdbxDeltaSummary> = emptyList(),
            val snapshots: List<MdbxSnapshotSummary> = emptyList(),
            val isLoading: Boolean = false,
            val selectedDiffCommitId: String? = null,
            val diffItems: List<MdbxCommitDiff> = emptyList(),
            val isDiffLoading: Boolean = false,
            val diffError: String? = null,
            val isSnapshotLoading: Boolean = false,
            val selectedStructureSnapshotId: String? = null,
            val structurePreview: MdbxStructurePreview? = null,
            val isStructureLoading: Boolean = false
        ) : MdbxDeltaDialogState()
    }

    sealed class MdbxHealthRepairState {
        data object Hidden : MdbxHealthRepairState()

        data class Planning(
            val databaseId: Long,
            val databaseName: String
        ) : MdbxHealthRepairState()

        data class Reviewing(
            val databaseId: Long,
            val databaseName: String,
            val plan: MdbxHealthRepairPlan,
            val decisions: Map<String, MdbxHealthRepairChoice> = emptyMap(),
            val currentIndex: Int = 0
        ) : MdbxHealthRepairState() {
            val currentItem: MdbxHealthRepairItem?
                get() = plan.conflictItems.getOrNull(currentIndex)

            val completedConflictCount: Int
                get() = decisions.size
        }

        data class Applying(
            val databaseId: Long,
            val databaseName: String,
            val itemCount: Int
        ) : MdbxHealthRepairState()

        data class Blocked(
            val databaseId: Long,
            val databaseName: String,
            val blockers: List<MdbxHealthRepairBlocker>
        ) : MdbxHealthRepairState()

        data class Failed(
            val databaseId: Long,
            val databaseName: String,
            val message: String
        ) : MdbxHealthRepairState()
    }

    sealed interface MdbxSnapshotCreateOutcome {
        data class Created(val snapshot: MdbxSnapshotSummary) : MdbxSnapshotCreateOutcome
        data object NoChanges : MdbxSnapshotCreateOutcome
        data class Failed(val error: Throwable) : MdbxSnapshotCreateOutcome
    }

    sealed class MdbxAdvancedDialogState {
        data object Hidden : MdbxAdvancedDialogState()
        data class Visible(
            val databaseId: Long,
            val databaseName: String,
            val diagnostics: MdbxVaultDiagnostics? = null,
            val exportedBundleJson: String? = null,
            val lastExportedBundle: MdbxSyncBundle? = null,
            val lastImportResult: MdbxApplyResult? = null,
            val lastBenchmarkResult: MdbxBenchmarkResult? = null,
            val message: String? = null,
            val isLoading: Boolean = false
        ) : MdbxAdvancedDialogState()
    }
}

private fun Throwable.toCommitDiffUserMessage(): String {
    val diagnostic = generateSequence(this) { it.cause }
        .mapNotNull(Throwable::message)
        .joinToString(" ")
    return if (
        diagnostic.contains("commit diff objects", ignoreCase = true) ||
        diagnostic.contains("resource limit", ignoreCase = true)
    ) {
        "这次提交包含的对象过多，当前版本无法一次展开全部详情。提交记录本身仍然有效。"
    } else {
        "无法读取提交详情：${message ?: "未知错误"}"
    }
}

private fun Throwable.toHealthRepairUserMessage(): String {
    val diagnostic = generateSequence(this) { it.cause }
        .mapNotNull(Throwable::message)
        .joinToString(" ")
    return when {
        diagnostic.contains("plan", ignoreCase = true) &&
            (diagnostic.contains("changed", ignoreCase = true) ||
                diagnostic.contains("token", ignoreCase = true) ||
                diagnostic.contains("stale", ignoreCase = true)) ->
            "数据库状态已经变化，请重新检查后再次处理"
        diagnostic.contains("block", ignoreCase = true) ->
            "存在无法安全自动处理的完整性异常，请先按诊断建议处理"
        else -> "无法完成数据库处理：${message ?: "未知错误"}"
    }
}

private fun MdbxHealthRepairApplyResult.healthRepairResultMessage(): String = when (status) {
    MdbxHealthRepairStatus.APPLIED -> when {
        healthy -> "已安全处理 $repairedCount 项异常，并创建处理前快照"
        remainingIssues.isNotEmpty() ->
            "已处理 $repairedCount 项异常，仍有 ${remainingIssues.size} 项需要继续检查"
        else -> "已处理 $repairedCount 项异常"
    }
    MdbxHealthRepairStatus.CANCELLED -> "已取消数据库处理，未写入任何修改"
    MdbxHealthRepairStatus.NO_CHANGES -> "数据库状态没有需要写入的变化"
}

data class MdbxKeyFileSelection(
    val uri: String?,
    val name: String,
    val fingerprint: String,
    val bytes: ByteArray
) {
    val shortFingerprint: String
        get() = fingerprint.take(12)
}

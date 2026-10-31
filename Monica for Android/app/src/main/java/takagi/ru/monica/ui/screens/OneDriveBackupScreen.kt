package takagi.ru.monica.ui.screens

import takagi.ru.monica.utils.AppLocaleStringResolver

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import android.widget.Toast
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import takagi.ru.monica.R
import takagi.ru.monica.data.BackupPreferences
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.repository.SecureItemRepository
import takagi.ru.monica.sync.SyncBackupProvider
import takagi.ru.monica.sync.SyncDiagnostics
import takagi.ru.monica.sync.SyncMode
import takagi.ru.monica.sync.SyncNetworkPolicy
import takagi.ru.monica.sync.SyncPriority
import takagi.ru.monica.sync.SyncRequest
import takagi.ru.monica.sync.SyncTarget
import takagi.ru.monica.sync.SyncTaskAwaitResult
import takagi.ru.monica.sync.SyncTaskRunner
import takagi.ru.monica.sync.SyncTrigger
import takagi.ru.monica.ui.components.SelectiveBackupCard
import takagi.ru.monica.utils.BackupContentScope
import takagi.ru.monica.utils.BackupFile
import takagi.ru.monica.utils.BackupRestoreApplier
import takagi.ru.monica.utils.OneDriveAccountSession
import takagi.ru.monica.utils.OneDriveAuthManager
import takagi.ru.monica.utils.OneDriveBackupConfig
import takagi.ru.monica.utils.OneDriveBackupHelper
import takagi.ru.monica.utils.OneDriveKeePassFileSource
import takagi.ru.monica.utils.RestoreResult
import takagi.ru.monica.utils.WebDavHelper
import takagi.ru.monica.utils.FileSourceEntry
import takagi.ru.monica.utils.isOneDriveAuthTemporarilyUnavailable
import takagi.ru.monica.utils.toOneDriveUserMessage
import java.io.File

private enum class OneDriveBackupConnectionState {
    NotConnected,
    Connecting,
    Connected,
    Failed,
}

private enum class OneDriveRestoreMode {
    MergeLocal,
    ReplaceLocal,
}

private const val ONEDRIVE_BACKUP_LOG_TAG = "OneDriveBackupScreen"

/** Route-owned state and callbacks; kept outside composition and recycled history rows. */
private class OneDriveBackupScreenState(
    private val context: Context,
    private val activity: Activity?,
    private val coroutineScope: kotlinx.coroutines.CoroutineScope,
    private val backupHelper: OneDriveBackupHelper,
    private val authManager: OneDriveAuthManager,
    private val webDavHelper: WebDavHelper,
    private val passwordRepository: PasswordRepository,
    private val secureItemRepository: SecureItemRepository,
    folderPickerState: androidx.compose.runtime.MutableState<Boolean>,
) {
    var showFolderPicker by folderPickerState
    var session by mutableStateOf<OneDriveAccountSession?>(null)
    var savedConfig by mutableStateOf<OneDriveBackupConfig?>(null)
    var currentPath by mutableStateOf("")
    var browserEntries by mutableStateOf<List<FileSourceEntry>>(emptyList())
    var backupList by mutableStateOf<List<BackupFile>>(emptyList())
    var browserError by mutableStateOf<String?>(null)
    var signingIn by mutableStateOf(false)
    var loadingEntries by mutableStateOf(false)
    var loadingBackups by mutableStateOf(false)
    var creatingBackup by mutableStateOf(false)
    var creatingFolder by mutableStateOf(false)
    var connectionState by mutableStateOf(OneDriveBackupConnectionState.NotConnected)
    var restoringBackupPath by mutableStateOf<String?>(null)
    var showCreateFolderDialog by mutableStateOf(false)
    var showDeleteDialogFor by mutableStateOf<BackupFile?>(null)
    var showRestoreDialogFor by mutableStateOf<BackupFile?>(null)
    var restoreMode by mutableStateOf(OneDriveRestoreMode.MergeLocal)
    var restoreGlobalDedup by mutableStateOf(false)
    var showDecryptPasswordDialogFor by mutableStateOf<BackupFile?>(null)
    var decryptPassword by mutableStateOf("")
    var encryptionEnabled by mutableStateOf(false)
    var encryptionPassword by mutableStateOf("")
    var encryptionPasswordVisible by mutableStateOf(false)
    var backupPreferences by mutableStateOf(BackupPreferences())
    var passwordCount by mutableStateOf(0)
    var authenticatorCount by mutableStateOf(0)
    var documentCount by mutableStateOf(0)
    var bankCardCount by mutableStateOf(0)
    var noteCount by mutableStateOf(0)
    var trashCount by mutableStateOf(0)
    var localKeePassCount by mutableStateOf(0)
    var passkeyCount by mutableStateOf(0)

    fun currentSessionBackupConfig(): OneDriveBackupConfig? {
        val activeSession = session ?: return null
        return savedConfig?.takeIf { config -> config.accountId == activeSession.accountId }
    }

    suspend fun refreshBackups() {
        if (currentSessionBackupConfig() == null) {
            Log.i(
                ONEDRIVE_BACKUP_LOG_TAG,
                "Skip refreshBackups: no config for current session, state=$connectionState, " +
                    "session=${session.debugRef()}, savedConfig=${savedConfig.debugRef()}"
            )
            backupList = emptyList()
            return
        }
        Log.d(
            ONEDRIVE_BACKUP_LOG_TAG,
            "Refreshing OneDrive backups, state=$connectionState, session=${session.debugRef()}"
        )
        loadingBackups = true
        backupHelper.listBackups().fold(
            onSuccess = {
                backupList = it
                Log.d(ONEDRIVE_BACKUP_LOG_TAG, "OneDrive backup list loaded, count=${it.size}")
            },
            onFailure = {
                backupList = emptyList()
                browserError = it.toOneDriveUserMessage(AppLocaleStringResolver(context), context.getString(R.string.keepass_onedrive_load_files_failed))
                connectionState = OneDriveBackupConnectionState.Failed
                Log.w(
                    ONEDRIVE_BACKUP_LOG_TAG,
                    "OneDrive backup list failed, temporaryAuth=${it.isOneDriveAuthTemporarilyUnavailable()}",
                    it
                )
            }
        )
        loadingBackups = false
    }

    suspend fun loadDirectory(targetPath: String) {
        val activeSession = session ?: return
        Log.d(
            ONEDRIVE_BACKUP_LOG_TAG,
            "Loading OneDrive directory, targetIsRoot=${targetPath.isBlank()}, session=${activeSession.debugRef()}"
        )
        loadingEntries = true
        browserError = null
        runCatching {
            backupHelper.loadBackupDirectory(activeSession.accountId, targetPath)
        }.onSuccess { directory ->
            if (session?.accountId != activeSession.accountId) return@onSuccess
            browserEntries = directory.folders
            // The configured folder's response already includes every backup file.
            // Browsing a different folder must not replace the saved backup history.
            if (currentSessionBackupConfig()?.folderPath ==
                OneDriveKeePassFileSource.normalizeOptionalRemotePath(targetPath)
            ) {
                backupList = directory.backups
            }
            currentPath = targetPath
            connectionState = OneDriveBackupConnectionState.Connected
            Log.d(
                ONEDRIVE_BACKUP_LOG_TAG,
                "OneDrive directory loaded, folders=${browserEntries.size}, targetIsRoot=${targetPath.isBlank()}"
            )
        }.onFailure { error ->
            if (session?.accountId != activeSession.accountId) return@onFailure
            browserEntries = emptyList()
            if (currentSessionBackupConfig()?.folderPath ==
                OneDriveKeePassFileSource.normalizeOptionalRemotePath(targetPath)
            ) {
                backupList = emptyList()
            }
            browserError = error.toOneDriveUserMessage(AppLocaleStringResolver(context), context.getString(R.string.keepass_onedrive_load_files_failed))
            connectionState = OneDriveBackupConnectionState.Failed
            Log.w(
                ONEDRIVE_BACKUP_LOG_TAG,
                "OneDrive directory load failed, temporaryAuth=${error.isOneDriveAuthTemporarilyUnavailable()}",
                error
            )
        }
        loadingEntries = false
    }

    suspend fun loadCounts() {
        val database = PasswordDatabase.getDatabase(context)
        passwordCount = passwordRepository.getLocalEntriesCount()
        authenticatorCount = secureItemRepository.getLocalItemCountByType(takagi.ru.monica.data.ItemType.TOTP)
        documentCount = secureItemRepository.getLocalItemCountByType(takagi.ru.monica.data.ItemType.DOCUMENT)
        bankCardCount = secureItemRepository.getLocalItemCountByType(takagi.ru.monica.data.ItemType.BANK_CARD)
        noteCount = secureItemRepository.getLocalItemCountByType(takagi.ru.monica.data.ItemType.NOTE)
        trashCount = passwordRepository.getLocalDeletedEntriesCount() + secureItemRepository.getLocalDeletedItemCount()
        passkeyCount = withContext(Dispatchers.IO) { database.passkeyDao().getLocalPasskeyCount() }
        localKeePassCount = withContext(Dispatchers.IO) { database.localKeePassDatabaseDao().getAllDatabasesSync().size }
    }

    fun saveCurrentFolderAsBackupDirectory() {
        val activeSession = session ?: return
        backupHelper.saveConfig(activeSession, currentPath)
        savedConfig = backupHelper.getConfig()
        Log.i(
            ONEDRIVE_BACKUP_LOG_TAG,
            "Saved OneDrive backup directory, pathIsRoot=${currentPath.isBlank()}, session=${activeSession.debugRef()}"
        )
        coroutineScope.launch {
            refreshBackups()
        }
        Toast.makeText(context, context.getString(R.string.onedrive_backup_directory_saved), Toast.LENGTH_SHORT).show()
    }

    fun signInOrSwitchAccount() {
        Log.i(
            ONEDRIVE_BACKUP_LOG_TAG,
            "OneDrive sign-in/switch clicked, state=$connectionState, signingIn=$signingIn, " +
                "loadingEntries=$loadingEntries, loadingBackups=$loadingBackups, " +
                "session=${session.debugRef()}, savedConfig=${savedConfig.debugRef()}"
        )
        if (activity == null) {
            browserError = context.getString(R.string.keepass_onedrive_activity_missing)
            connectionState = OneDriveBackupConnectionState.Failed
            return
        }
        connectionState = OneDriveBackupConnectionState.Connecting
        browserError = null
        signingIn = true
        loadingEntries = false
        loadingBackups = false
        coroutineScope.launch {
            runCatching { authManager.signIn(activity) }
                .onSuccess { result ->
                    session = result
                    savedConfig = backupHelper.getConfig()
                    connectionState = OneDriveBackupConnectionState.Connected
                    backupList = emptyList()
                    val configuredPath = savedConfig
                        ?.takeIf { it.accountId == result.accountId }
                        ?.folderPath
                        .orEmpty()
                    loadDirectory(configuredPath)
                    if (currentSessionBackupConfig() == null) showFolderPicker = true
                }
                .onFailure { error ->
                    browserEntries = emptyList()
                    backupList = emptyList()
                    connectionState = OneDriveBackupConnectionState.Failed
                    browserError = error.toOneDriveUserMessage(AppLocaleStringResolver(context),
                        context.getString(R.string.keepass_onedrive_sign_in_failed)
                    )
                    Log.w(ONEDRIVE_BACKUP_LOG_TAG, "OneDrive interactive sign-in failed", error)
                }
            signingIn = false
        }
    }

    suspend fun performRestore(backup: BackupFile, decryptPasswordValue: String?) {
        restoringBackupPath = backup.path
        var temporary: takagi.ru.monica.utils.BackupTemporaryFiles.Workspace? = null
        var parsed: RestoreResult? = null
        try {
            val workspace = withContext(Dispatchers.IO) {
                takagi.ru.monica.utils.BackupTemporaryFiles.create(context.cacheDir).also { temporary = it }
            }
            val downloadedFile = workspace.file("download.zip")
            val result = backupHelper.downloadBackup(backup, downloadedFile)
                .mapCatching {
                    webDavHelper.restoreFromBackupFile(
                        backupFile = it,
                        decryptPassword = decryptPasswordValue,
                        overwrite = restoreMode == OneDriveRestoreMode.ReplaceLocal,
                        restoreMonicaConfig = true
                    ).getOrThrow().also { restored -> parsed = restored }
                }

            result.fold(
                onSuccess = { restoreResult ->
                    val stats = BackupRestoreApplier.applyRestoreResult(
                        context = context,
                        restoreResult = restoreResult,
                        passwordRepository = passwordRepository,
                        secureItemRepository = secureItemRepository,
                        localOnlyDedup = when (restoreMode) {
                            OneDriveRestoreMode.MergeLocal -> !restoreGlobalDedup
                            OneDriveRestoreMode.ReplaceLocal -> true
                        },
                        logTag = "OneDriveBackup"
                    )
                    Toast.makeText(
                        context,
                        buildRestoreSummary(context, restoreResult, stats),
                        Toast.LENGTH_LONG
                    ).show()
                    refreshBackups()
                },
                onFailure = { error ->
                    when (error) {
                        is WebDavHelper.PasswordRequiredException -> {
                            decryptPassword = decryptPasswordValue.orEmpty()
                            showDecryptPasswordDialogFor = backup
                        }
                        else -> {
                            Toast.makeText(
                                context,
                                context.getString(R.string.webdav_restore_failed, error.toOneDriveUserMessage(AppLocaleStringResolver(context), context.getString(R.string.import_data_unknown_error))),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            )
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                try { parsed?.content?.portableAttachments?.close() }
                finally { temporary?.close() }
            }
            restoringBackupPath = null
        }
    }

    fun createBackup() {
        if (currentSessionBackupConfig() == null) {
            Log.w(
                ONEDRIVE_BACKUP_LOG_TAG,
                "Create backup blocked: no config for current session, " +
                    "state=$connectionState, session=${session.debugRef()}, savedConfig=${savedConfig.debugRef()}"
            )
            Toast.makeText(context, context.getString(R.string.onedrive_backup_directory_required), Toast.LENGTH_SHORT).show()
            return
        }
        if (!backupPreferences.hasAnyEnabled()) {
            Toast.makeText(context, context.getString(R.string.backup_validation_error), Toast.LENGTH_SHORT).show()
            return
        }
        creatingBackup = true
        coroutineScope.launch {
            val backupTarget = SyncTarget.Backup(SyncBackupProvider.ONEDRIVE)
            val taskId = SyncDiagnostics.nextTaskId("backup-onedrive-screen")
            val targetLog = backupTarget.stableKey.value
            val triggerLog = "ONEDRIVE_SCREEN_MANUAL"
            try {
                val syncResult = SyncTaskRunner.requestAndAwait(
                    request = SyncRequest(
                        requestId = taskId,
                        target = backupTarget,
                        trigger = SyncTrigger.MANUAL,
                        createdAtMillis = System.currentTimeMillis(),
                        priority = SyncPriority.MANUAL,
                        mode = SyncMode.FOREGROUND,
                        networkPolicy = SyncNetworkPolicy.REQUIRED
                    )
                ) {
                    SyncDiagnostics.queued(taskId, targetLog, triggerLog)
                    val startedAt = SyncDiagnostics.start(taskId, targetLog, triggerLog)
                    try {
                        val localPasswords = passwordRepository.getAllLocalPasswordEntries()
                        val securityManager = takagi.ru.monica.security.SecurityManager(context)
                        val failedPasswordTitles = mutableListOf<String>()
                        val decryptedPasswords = localPasswords.map { entry ->
                            runCatching {
                                entry.copy(password = securityManager.decryptData(entry.password))
                            }.getOrElse { error ->
                                Log.w(
                                    ONEDRIVE_BACKUP_LOG_TAG,
                                    "Failed to decrypt password for OneDrive backup: ${entry.title} (${error.message})"
                                )
                                failedPasswordTitles += entry.title.ifBlank { entry.website.ifBlank { entry.username } }
                                entry.copy(password = "")
                            }
                        }
                        if (failedPasswordTitles.isNotEmpty()) {
                            throw IllegalStateException(
                                context.getString(R.string.legacy_ui_backup_decrypt_failed, failedPasswordTitles.size)
                            )
                        }
                        val localSecureItems = secureItemRepository.getAllLocalItems()
                        Log.i(
                            ONEDRIVE_BACKUP_LOG_TAG,
                            "Creating OneDrive backup zip: passwords=${localPasswords.size}, " +
                                "secureItems=${localSecureItems.size}, scope=${BackupContentScope.MONICA_LOCAL_ONLY}"
                        )
                        val (file, report) = webDavHelper.createBackupZip(
                            passwords = decryptedPasswords,
                            secureItems = localSecureItems,
                            preferences = backupPreferences,
                            contentScope = BackupContentScope.MONICA_LOCAL_ONLY
                        ).getOrThrow()

                        if (!report.success || report.failedItems.isNotEmpty()) {
                            file.delete()
                            throw IllegalStateException(
                                context.getString(R.string.webdav_create_backup_failed)
                            )
                        }

                        Log.i(
                            ONEDRIVE_BACKUP_LOG_TAG,
                            "OneDrive backup zip ready: sizeBytes=${file.length()}, " +
                                "passwords=${report.successItems.passwords}/${report.totalItems.passwords}, " +
                                "totp=${report.successItems.totp}/${report.totalItems.totp}, " +
                                "notes=${report.successItems.notes}/${report.totalItems.notes}, " +
                                "warnings=${report.warnings.size}, failures=${report.failedItems.size}"
                        )
                        try {
                            backupHelper.uploadBackup(file, isPermanent = true).getOrThrow()
                        } finally {
                            file.delete()
                        }
                        SyncDiagnostics.success(
                            taskId = taskId,
                            target = targetLog,
                            trigger = triggerLog,
                            startedAt = startedAt,
                            detail = "passwords=${decryptedPasswords.size} secureItems=${localSecureItems.size} warnings=${report.warnings.size} failures=${report.failedItems.size}"
                        )
                        report
                    } catch (error: Exception) {
                        SyncDiagnostics.failed(taskId, targetLog, triggerLog, startedAt, error)
                        throw error
                    }
                }

                when (syncResult) {
                    is SyncTaskAwaitResult.Completed -> {
                        Toast.makeText(
                            context,
                            if (syncResult.value.hasIssues()) {
                                syncResult.value.getSummary(context)
                            } else {
                                context.getString(R.string.webdav_backup_success)
                            },
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    is SyncTaskAwaitResult.Merged -> {
                        SyncDiagnostics.skipped(
                            taskId = taskId,
                            target = targetLog,
                            trigger = triggerLog,
                            reason = "merged_with_running_backup",
                            detail = "running=${syncResult.status.runningRequestId.orEmpty()}"
                        )
                        Toast.makeText(
                            context,
                            context.getString(R.string.webdav_backup_in_progress),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    is SyncTaskAwaitResult.Skipped -> {
                        SyncDiagnostics.skipped(taskId, targetLog, triggerLog, syncResult.reason)
                        Toast.makeText(
                            context,
                            context.getString(R.string.webdav_backup_failed, syncResult.reason),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    is SyncTaskAwaitResult.Blocked -> {
                        val reason = syncResult.error.redactedMessage ?: syncResult.error.kind.name
                        SyncDiagnostics.blocked(taskId, targetLog, triggerLog, reason)
                        Toast.makeText(
                            context,
                            context.getString(R.string.webdav_backup_failed, reason),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    is SyncTaskAwaitResult.Canceled -> {
                        val reason = syncResult.reason ?: "backup canceled"
                        SyncDiagnostics.skipped(taskId, targetLog, triggerLog, reason)
                        Toast.makeText(
                            context,
                            context.getString(R.string.webdav_backup_failed, reason),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    is SyncTaskAwaitResult.Failed -> {
                        Toast.makeText(
                            context,
                            context.getString(
                                R.string.webdav_backup_failed,
                                syncResult.error.toOneDriveUserMessage(AppLocaleStringResolver(context), context.getString(R.string.webdav_create_backup_failed))
                            ),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                if (syncResult is SyncTaskAwaitResult.Completed) {
                    refreshBackups()
                }
            } catch (error: Exception) {
                Log.e(ONEDRIVE_BACKUP_LOG_TAG, "OneDrive backup creation failed", error)
                Toast.makeText(
                    context,
                    context.getString(
                        R.string.webdav_backup_failed,
                        error.toOneDriveUserMessage(AppLocaleStringResolver(context), context.getString(R.string.webdav_create_backup_failed))
                    ),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                creatingBackup = false
            }
        }
    }

    fun openFolderPicker() {
        showFolderPicker = true
        coroutineScope.launch { loadDirectory(currentSessionBackupConfig()?.folderPath.orEmpty()) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OneDriveBackupScreen(
    passwordRepository: PasswordRepository,
    secureItemRepository: SecureItemRepository,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val coroutineScope = rememberCoroutineScope()
    val backupHelper = remember { OneDriveBackupHelper(context) }
    val authManager = remember { OneDriveAuthManager(context) }
    val webDavHelper = remember { WebDavHelper(context) }

    val folderPickerState = rememberSaveable { mutableStateOf(false) }
    val screenState = remember {
        OneDriveBackupScreenState(context, activity, coroutineScope, backupHelper, authManager, webDavHelper,
            passwordRepository, secureItemRepository, folderPickerState)
    }

    with(screenState) {
        val oneDriveReady = session != null && connectionState == OneDriveBackupConnectionState.Connected

        val savedConfigForCurrentSession = currentSessionBackupConfig()
        val backupReady = oneDriveReady && savedConfigForCurrentSession != null

        LaunchedEffect(Unit) {
            savedConfig = backupHelper.getConfig()
            backupPreferences = webDavHelper.getBackupPreferences().copy(includeWebDavConfig = false)
            val encryptionConfig = webDavHelper.getEncryptionConfig()
            encryptionEnabled = encryptionConfig.enabled
            encryptionPassword = encryptionConfig.password
            loadCounts()

            Log.d(
                ONEDRIVE_BACKUP_LOG_TAG,
                "Initializing OneDrive backup screen, savedConfig=${savedConfig.debugRef()}"
            )
            val configuredSessionResult = runCatching { backupHelper.getConfiguredSession() }
            val configuredSession = configuredSessionResult.getOrNull()
            val cachedSession = if (configuredSession == null) {
                runCatching { authManager.getCachedSession() }.getOrNull()
            } else null

            session = configuredSession ?: cachedSession
            if (configuredSessionResult.isFailure) {
                val error = configuredSessionResult.exceptionOrNull()
                Log.w(
                    ONEDRIVE_BACKUP_LOG_TAG,
                    "Configured OneDrive session refresh failed, " +
                        "temporaryAuth=${error?.isOneDriveAuthTemporarilyUnavailable() == true}, " +
                        "cachedSession=${cachedSession.debugRef()}",
                    error
                )
                browserError = configuredSessionResult.exceptionOrNull()
                    ?.toOneDriveUserMessage(AppLocaleStringResolver(context), context.getString(R.string.keepass_onedrive_load_files_failed))
                browserEntries = emptyList()
                backupList = emptyList()
                loadingEntries = false
                loadingBackups = false
                connectionState = OneDriveBackupConnectionState.Failed
                return@LaunchedEffect
            }

            Log.d(
                ONEDRIVE_BACKUP_LOG_TAG,
                "Initial OneDrive session resolved, configured=${configuredSession.debugRef()}, " +
                    "cached=${cachedSession.debugRef()}, active=${session.debugRef()}, " +
                    "configMatches=${currentSessionBackupConfig() != null}"
            )
            if (session != null) {
                connectionState = OneDriveBackupConnectionState.Connected
                val initialPath = currentSessionBackupConfig()?.folderPath.orEmpty()
                loadDirectory(initialPath)
            }
        }

        val connectionLabel = when (connectionState) {
            OneDriveBackupConnectionState.NotConnected -> stringResource(R.string.keepass_webdav_status_not_connected)
            OneDriveBackupConnectionState.Connecting -> stringResource(R.string.keepass_webdav_status_connecting)
            OneDriveBackupConnectionState.Connected -> stringResource(R.string.keepass_webdav_status_connected)
            OneDriveBackupConnectionState.Failed -> stringResource(R.string.keepass_webdav_status_failed)
        }
        CloudBackupPage(
            title = stringResource(R.string.onedrive_backup_title),
            configured = savedConfigForCurrentSession != null,
            backups = backupList,
            loading = loadingBackups,
            refreshEnabled = !signingIn && !creatingBackup && session != null && restoringBackupPath == null,
            onRefresh = {
                coroutineScope.launch {
                    if (connectionState != OneDriveBackupConnectionState.Connected) {
                        loadDirectory(currentSessionBackupConfig()?.folderPath.orEmpty())
                    }
                    if (connectionState == OneDriveBackupConnectionState.Connected) refreshBackups()
                }
            },
            onNavigateBack = onNavigateBack,
            errorMessage = browserError,
            primaryAction = {
                when {
                    savedConfigForCurrentSession != null -> CloudBackupPrimaryButton(
                        label = stringResource(if (creatingBackup) R.string.webdav_backup_in_progress else R.string.webdav_create_new_backup),
                        onClick = ::createBackup, busy = creatingBackup,
                        enabled = backupReady && !loadingBackups && restoringBackupPath == null,
                    )
                    session == null || connectionState == OneDriveBackupConnectionState.Failed -> CloudBackupPrimaryButton(
                        label = stringResource(R.string.keepass_onedrive_sign_in_action), onClick = ::signInOrSwitchAccount,
                        busy = signingIn, icon = Icons.Default.Login,
                    )
                    else -> CloudBackupPrimaryButton(
                        label = stringResource(R.string.onedrive_backup_browser_title), onClick = ::openFolderPicker,
                        enabled = !loadingEntries, icon = Icons.Default.FolderOpen,
                    )
                }
            },
            location = {
                CloudBackupLocationCard(
                    title = session?.displayName?.ifBlank { session?.username.orEmpty() } ?: "OneDrive",
                    subtitle = savedConfigForCurrentSession?.folderPath?.toOneDriveDisplayPath()
                        ?: stringResource(R.string.onedrive_backup_directory_required),
                    supporting = listOfNotNull(session?.username?.takeIf(String::isNotBlank), connectionLabel).joinToString(" · "),
                    actionLabel = stringResource(R.string.onedrive_backup_browser_title),
                    onClick = if (session != null && !creatingBackup && restoringBackupPath == null) ::openFolderPicker else null,
                    busy = signingIn,
                )
            },
            connection = {
                CloudBackupNotice(stringResource(R.string.onedrive_backup_description), icon = Icons.Default.Backup)
                if (session != null) DatabaseManagementActionGroup(listOf(
                    DatabaseManagementAction(Icons.Default.ManageAccounts, stringResource(R.string.keepass_onedrive_switch_account),
                        ::signInOrSwitchAccount, enabled = !signingIn),
                ))
            },
            settings = {
                CloudBackupEncryptionSettings(
                    enabled = encryptionEnabled, password = encryptionPassword, passwordVisible = encryptionPasswordVisible,
                    onEnabledChange = {
                        encryptionEnabled = it
                        webDavHelper.setEncryptionConfig(it, if (it) encryptionPassword else "")
                    },
                    onPasswordChange = { encryptionPassword = it; webDavHelper.setEncryptionConfig(encryptionEnabled, it) },
                    onVisibilityChange = { encryptionPasswordVisible = !encryptionPasswordVisible },
                )
                SelectiveBackupCard(
                    preferences = backupPreferences,
                    onPreferencesChange = {
                        val normalized = it.copy(includeWebDavConfig = false)
                        backupPreferences = normalized
                        webDavHelper.saveBackupPreferences(normalized)
                    },
                    passwordCount = passwordCount, authenticatorCount = authenticatorCount, documentCount = documentCount,
                    bankCardCount = bankCardCount, noteCount = noteCount, trashCount = trashCount, passkeyCount = passkeyCount,
                    localKeePassCount = localKeePassCount, isWebDavConfigured = false,
                )
                CloudBackupConnectionSettings(
                    title = stringResource(R.string.onedrive_backup_config_title),
                    fields = listOf(
                        stringResource(R.string.onedrive_backup_directory_label) to savedConfigForCurrentSession?.folderPath.orEmpty().toOneDriveDisplayPath(),
                        stringResource(R.string.username) to session?.username.orEmpty(),
                    ),
                    actions = listOf(
                    DatabaseManagementAction(Icons.Default.FolderOpen, stringResource(R.string.onedrive_backup_browser_title),
                        ::openFolderPicker, enabled = !creatingBackup && !signingIn && restoringBackupPath == null),
                    DatabaseManagementAction(Icons.Default.ManageAccounts, stringResource(R.string.keepass_onedrive_switch_account),
                        ::signInOrSwitchAccount, enabled = !creatingBackup && !signingIn && restoringBackupPath == null),
                    DatabaseManagementAction(Icons.Default.LinkOff, stringResource(R.string.clear), {
                        backupHelper.clearConfig()
                        savedConfig = null
                        backupList = emptyList()
                    }, enabled = !creatingBackup && restoringBackupPath == null, warning = true),
                ))
            },
            backupRow = { index, backup ->
                CloudBackupFileRow(
                    backup = backup, sizeLabel = backupHelper.formatFileSize(backup.size), index = index, count = backupList.size,
                    busy = restoringBackupPath == backup.path, enabled = !loadingBackups && !creatingBackup && restoringBackupPath == null,
                    onRestore = {
                        restoreMode = OneDriveRestoreMode.MergeLocal
                        restoreGlobalDedup = false
                        showRestoreDialogFor = backup
                    },
                    onDelete = { showDeleteDialogFor = backup },
                    onTogglePermanent = {
                        coroutineScope.launch {
                            val result = if (backup.isPermanent) {
                                backupHelper.unmarkPermanent(backup)
                            } else {
                                backupHelper.markBackupAsPermanent(backup)
                            }
                            result.fold(
                                onSuccess = {
                                    refreshBackups()
                                },
                                onFailure = { error ->
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.webdav_operation_failed, error.toOneDriveUserMessage(AppLocaleStringResolver(context), "")),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            )
                        }
                    },
                )
            },
        )

        if (showFolderPicker && session != null) {
            CloudBackupFolderSheet(
                account = session?.username.orEmpty(), currentPath = currentPath, entries = browserEntries,
                loading = loadingEntries || creatingFolder, enabled = oneDriveReady && !signingIn,
                errorMessage = browserError,
                onNavigateUp = { coroutineScope.launch { loadDirectory(OneDriveKeePassFileSource.parentPathOf(currentPath, strings = AppLocaleStringResolver(context))) } },
                onRefresh = { coroutineScope.launch { loadDirectory(currentPath) } },
                onCreateFolder = { showCreateFolderDialog = true },
                onEntryClick = { entry -> coroutineScope.launch { loadDirectory(entry.path) } },
                onSave = { saveCurrentFolderAsBackupDirectory(); showFolderPicker = false },
                onDismiss = { showFolderPicker = false },
            )
        }

        if (showCreateFolderDialog) {
            var folderName by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showCreateFolderDialog = false },
                title = { Text(stringResource(R.string.keepass_onedrive_create_folder_title)) },
                text = {
                    OutlinedTextField(
                        shape = DatabaseManagementFieldShape,
                        value = folderName,
                        onValueChange = { folderName = it },
                        label = { Text(stringResource(R.string.folder_name)) },
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val activeSession = session ?: return@TextButton
                            showCreateFolderDialog = false
                            creatingFolder = true
                            coroutineScope.launch {
                                runCatching {
                                    backupHelper.createFolder(activeSession.accountId, currentPath, folderName)
                                }.onSuccess {
                                    loadDirectory(currentPath)
                                }.onFailure { error ->
                                    Toast.makeText(context, error.toOneDriveUserMessage(AppLocaleStringResolver(context), context.getString(R.string.webdav_operation_failed, "")), Toast.LENGTH_LONG).show()
                                }
                                creatingFolder = false
                            }
                        },
                        enabled = folderName.isNotBlank()
                    ) {
                        Text(stringResource(R.string.create))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCreateFolderDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        showDeleteDialogFor?.let { backup ->
            AlertDialog(
                onDismissRequest = { showDeleteDialogFor = null },
                title = { Text(stringResource(R.string.delete_backup)) },
                text = { Text(stringResource(R.string.delete_backup_confirm, backup.name)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDeleteDialogFor = null
                            coroutineScope.launch {
                                backupHelper.deleteBackup(backup).fold(
                                    onSuccess = {
                                        Toast.makeText(context, context.getString(R.string.backup_deleted), Toast.LENGTH_SHORT).show()
                                        refreshBackups()
                                    },
                                    onFailure = { error ->
                                        Toast.makeText(context, context.getString(R.string.delete_failed, error.toOneDriveUserMessage(AppLocaleStringResolver(context), "")), Toast.LENGTH_LONG).show()
                                    }
                                )
                            }
                        }
                    ) {
                        Text(stringResource(R.string.delete))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialogFor = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        showRestoreDialogFor?.let { backup ->
            CloudBackupRestoreSheet(
                backup = backup, sizeLabel = backupHelper.formatFileSize(backup.size),
                merge = restoreMode == OneDriveRestoreMode.MergeLocal, globalDedup = restoreGlobalDedup,
                onMergeChange = { restoreMode = if (it) OneDriveRestoreMode.MergeLocal else OneDriveRestoreMode.ReplaceLocal },
                onGlobalDedupChange = { restoreGlobalDedup = it },
                onDismiss = { showRestoreDialogFor = null },
                onRestore = {
                    showRestoreDialogFor = null
                    if (backup.isEncrypted()) {
                        decryptPassword = ""
                        showDecryptPasswordDialogFor = backup
                    } else {
                        coroutineScope.launch { performRestore(backup, null) }
                    }
                },
            )
        }

        showDecryptPasswordDialogFor?.let { backup ->
            AlertDialog(
                onDismissRequest = { showDecryptPasswordDialogFor = null },
                title = { Text(stringResource(R.string.webdav_enter_decrypt_password)) },
                text = {
                    OutlinedTextField(
                        shape = DatabaseManagementFieldShape,
                        value = decryptPassword,
                        onValueChange = { decryptPassword = it },
                        label = { Text(stringResource(R.string.password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDecryptPasswordDialogFor = null
                            coroutineScope.launch { performRestore(backup, decryptPassword) }
                        },
                        enabled = decryptPassword.isNotBlank()
                    ) {
                        Text(stringResource(R.string.confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDecryptPasswordDialogFor = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }
    }
}

private fun buildRestoreSummary(
    context: Context,
    restoreResult: RestoreResult,
    stats: takagi.ru.monica.utils.RestoreApplyStats
): String {
    val report = restoreResult.report
    if (report.hasIssues()) {
        return report.getSummary(context)
    }
    val summaryParts = mutableListOf<String>()
    summaryParts += context.getString(R.string.webdav_restore_summary_part_passwords, stats.passwordImported)
    summaryParts += context.getString(R.string.webdav_restore_summary_part_other_data, stats.secureItemImported)
    if (stats.passkeyImported > 0) {
        summaryParts += context.getString(R.string.legacy_ui_restore_passkey_count, stats.passkeyImported)
    }
    return context.getString(
        R.string.webdav_restore_summary_success,
        summaryParts.joinToString(", ")
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun OneDriveAccountSession?.debugRef(): String {
    val accountId = this?.accountId?.takeIf { it.isNotBlank() } ?: return "none"
    return "account#${accountId.hashCode().toString(16)}"
}

private fun OneDriveBackupConfig?.debugRef(): String {
    val accountId = this?.accountId?.takeIf { it.isNotBlank() } ?: return "none"
    return "config#${accountId.hashCode().toString(16)}"
}

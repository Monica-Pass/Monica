package takagi.ru.monica.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.testTag
import takagi.ru.monica.ui.components.MonicaExpandableContent
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import takagi.ru.monica.transfer.*
import takagi.ru.monica.R
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.repository.SecureItemRepository
import takagi.ru.monica.utils.BackupFile
import takagi.ru.monica.utils.BackupRetentionConfig
import takagi.ru.monica.utils.BackupRetentionPolicy
import takagi.ru.monica.utils.BackupContentScope
import takagi.ru.monica.utils.BackupRestoreApplier
import takagi.ru.monica.utils.RestoreResult
import takagi.ru.monica.utils.WebDavHelper
import takagi.ru.monica.webdav.WebDavUntrustedCertificateException
import takagi.ru.monica.utils.AutoBackupManager
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
import takagi.ru.monica.ui.components.PasswordEntryPickerBottomSheet
import takagi.ru.monica.utils.ChangeTriggeredBackupScheduler
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import android.text.format.DateUtils
import takagi.ru.monica.ui.components.OutlinedTextField

private fun matchesAnyKeyword(message: String, vararg keywords: String): Boolean {
    val normalized = message.lowercase(Locale.ROOT)
    return keywords.any { keyword -> normalized.contains(keyword.lowercase(Locale.ROOT)) }
}

private fun monicaConfigEntryDisplayName(context: Context, entry: String): String {
    val normalized = entry.substringAfterLast('/').lowercase(Locale.ROOT)
    return when (normalized) {
        "webdav_connection.json", "webdav_config.json" -> context.getString(R.string.legacy_ui_config_webdav)
        "page_adjustment_settings.json" -> context.getString(R.string.legacy_ui_config_page_adjustment)
        "autofill_blocked_fields.json" -> context.getString(R.string.legacy_ui_config_autofill_blocked_fields)
        "autofill_save_blocked_targets.json" -> context.getString(R.string.legacy_ui_config_autofill_save_blocklist)
        "autofill_blacklist.json" -> context.getString(R.string.legacy_ui_config_autofill_blocklist)
        "bitwarden_vaults.json" -> context.getString(R.string.legacy_ui_config_bitwarden)
        "security_questions.json" -> context.getString(R.string.legacy_ui_config_security_questions)
        "common_account.json" -> context.getString(R.string.legacy_ui_config_account_templates)
        "monica_config.json" -> context.getString(R.string.legacy_ui_config_monica)
        else -> entry.substringAfterLast('/')
    }
}

private enum class RestoreMode {
    MERGE_LOCAL,
    REPLACE_LOCAL,
}

/** Route-owned state and callbacks; kept outside composition and recycled history rows. */
private class WebDavBackupScreenState(
    private val context: Context,
    private val coroutineScope: kotlinx.coroutines.CoroutineScope,
    private val webDavHelper: WebDavHelper,
    private val autoBackupManager: AutoBackupManager,
    private val passwordRepository: PasswordRepository,
    private val secureItemRepository: SecureItemRepository,
) {
    var serverUrl by mutableStateOf("")
    var username by mutableStateOf("")
    var password by mutableStateOf("")
    var passwordVisible by mutableStateOf(false)
    var isConfigured by mutableStateOf(false)
    var isEditingConnection by mutableStateOf(false)
    var isTesting by mutableStateOf(false)
    var backupList by mutableStateOf<List<BackupFile>>(emptyList())
    var isLoading by mutableStateOf(false)
    var errorMessage by mutableStateOf("")
    var pendingUntrustedCertificate by mutableStateOf<WebDavUntrustedCertificateException?>(null)
    var autoBackupEnabled by mutableStateOf(false)
    var lastBackupTime by mutableStateOf(0L)
    var showSyncSettings by mutableStateOf(false)
    var changeTriggeredConfig by mutableStateOf(WebDavHelper.ChangeTriggeredBackupConfig())
    var backupRetentionConfig by mutableStateOf(BackupRetentionConfig())
    var encryptionEnabled by mutableStateOf(false)
    var encryptionPassword by mutableStateOf("")
    var encryptionPasswordVisible by mutableStateOf(false)
    var backupPreferences by mutableStateOf(takagi.ru.monica.data.BackupPreferences())
    var passwordCount by mutableStateOf(0)
    var authenticatorCount by mutableStateOf(0)
    var documentCount by mutableStateOf(0)
    var bankCardCount by mutableStateOf(0)
    var noteCount by mutableStateOf(0)
    var trashCount by mutableStateOf(0)
    var localKeePassCount by mutableStateOf(0)
    var passkeyCount by mutableStateOf(0)
    var isBackupInProgress by mutableStateOf(false)
    var showPasswordPicker by mutableStateOf(false)
    var restoreBackup by mutableStateOf<BackupFile?>(null)
    var deleteBackup by mutableStateOf<BackupFile?>(null)
    var restoreBusy by mutableStateOf(false)
    var backupProblem by mutableStateOf<takagi.ru.monica.data.BackupReport?>(null)
    var partialBackupSaved by mutableStateOf(false)
    private var listRefreshJob: kotlinx.coroutines.Job? = null

    fun connectWebDav() {
        if (serverUrl.isBlank()) {
            errorMessage = context.getString(R.string.webdav_fill_all_fields)
            return
        }

        isTesting = true
        errorMessage = ""
        webDavHelper.configure(serverUrl, username, password)

        coroutineScope.launch {
            webDavHelper.testConnection().fold(
                onSuccess = {
                    isConfigured = true
                    isEditingConnection = false
                    isTesting = false
                    Toast.makeText(
                        context,
                        context.getString(R.string.webdav_connection_success),
                        Toast.LENGTH_SHORT
                    ).show()
                    // 加载备份列表
                    loadBackups(webDavHelper) { list, error ->
                        backupList = list
                        error?.let { errorMessage = it }
                    }
                },
                onFailure = { e ->
                    isTesting = false
                    val certificateError = e.findWebDavCertificateError()
                    if (certificateError != null) {
                        pendingUntrustedCertificate = certificateError
                        return@fold
                    }
                    // 提供更友好的错误信息
                    val message = e.message.orEmpty()
                    val userFriendlyMessage = when {
                        matchesAnyKeyword(message, "network is unreachable", "unreachable") ->
                            context.getString(R.string.webdav_network_unreachable)
                        matchesAnyKeyword(message, "timeout", "timed out") ->
                            context.getString(R.string.webdav_connection_timeout)
                        matchesAnyKeyword(message, "authentication failed", "unauthorized", "forbidden", "401", "403") ->
                            context.getString(R.string.webdav_auth_failed)
                        matchesAnyKeyword(message, "path not found", "not found", "404") ->
                            context.getString(R.string.webdav_path_not_found)
                        else -> e.message ?: context.getString(R.string.webdav_connection_failed, "")
                    }
                    errorMessage = userFriendlyMessage
                    Toast.makeText(
                        context,
                        context.getString(R.string.webdav_connection_failed, userFriendlyMessage),
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }

    fun createBackup(skippedPasskeys: List<takagi.ru.monica.data.FailedItem> = emptyList()) {
        // 防止重复点击
        if (isBackupInProgress) {
            Toast.makeText(
                context,
                context.getString(R.string.webdav_backup_in_progress),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        // 验证：检查是否至少选择了一种内容类型
        if (!backupPreferences.hasAnyEnabled()) {
            Toast.makeText(
                context,
                context.getString(R.string.backup_validation_error),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        backupProblem = null
        partialBackupSaved = false
        isBackupInProgress = true
        isLoading = true
        errorMessage = ""
        val started = startWebDavBackup(context.applicationContext, backupPreferences.copy(), skippedPasskeys.toList(),
            passwordRepository, secureItemRepository)
        if (!started) {
            isBackupInProgress = false
            isLoading = false
            errorMessage = context.getString(R.string.transfer_task_busy)
        }
    }

    fun triggerAutomaticBackup() {
        coroutineScope.launch {
            try {
                val enqueued = autoBackupManager.triggerBackupNow()
                if (!enqueued) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.webdav_error_rate_limited_toast),
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
                Toast.makeText(
                    context,
                    context.getString(R.string.webdav_backup_in_progress),
                    Toast.LENGTH_SHORT
                ).show()

                // 延迟2秒后更新上次备份时间和刷新备份列表
                kotlinx.coroutines.delay(2000)
                lastBackupTime = webDavHelper.getLastBackupTime()

                // 刷新备份列表
                isLoading = true
                loadBackups(webDavHelper) { list, error ->
                    backupList = list
                    isLoading = false
                    error?.let { errorMessage = it }
                }
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    context.getString(R.string.webdav_backup_trigger_failed, e.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    fun refreshBackupList() {
        // Entry and task-completion effects may request the same list in one frame.
        if (listRefreshJob?.isActive == true) return
        isLoading = true
        errorMessage = ""
        lastBackupTime = webDavHelper.getLastBackupTime()
        listRefreshJob = coroutineScope.launch {
            try {
                webDavHelper.listBackups().fold(
                    onSuccess = { backupList = it },
                    // A failed refresh must not make an already loaded list look empty.
                    onFailure = { errorMessage = it.message ?: context.getString(R.string.webdav_operation_failed, "") }
                )
            } finally {
                isLoading = false
            }
        }
    }

    fun editConnection() {
        webDavHelper.getCurrentConfig()?.let { config ->
            serverUrl = config.serverUrl
            username = config.username
            password = webDavHelper.getCurrentPasswordForEdit()
            passwordVisible = false
            isEditingConnection = true
            isConfigured = false
        }
    }

    fun cancelConnectionEdit() {
        isEditingConnection = false
        isConfigured = webDavHelper.isConfigured()
        passwordVisible = false
        errorMessage = ""
    }

    fun clearConnection() {
        webDavHelper.clearConfig()
        isConfigured = false
        isEditingConnection = false
        serverUrl = ""
        username = ""
        password = ""
        backupList = emptyList()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebDavBackupScreen(
    passwordRepository: PasswordRepository,
    secureItemRepository: SecureItemRepository,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val webDavHelper = remember { WebDavHelper(context) }
    val autoBackupManager = remember { AutoBackupManager(context) }
    val pickerSecurityManager = remember { takagi.ru.monica.security.SecurityManager(context) }
    val passwordEntriesForPicker by passwordRepository.getAllPasswordEntries().collectAsState(initial = emptyList())
    val screenState = remember {
        WebDavBackupScreenState(context, coroutineScope, webDavHelper, autoBackupManager, passwordRepository, secureItemRepository)
    }

    val dataJob by DatabaseExportJobs.state.collectAsState()
    val backupJob = dataJob?.takeIf { it.kind == DataTaskKind.WEBDAV_BACKUP }
    LaunchedEffect(Unit) { DatabaseExportJobs.recoverInterrupted(context.applicationContext) }
    LaunchedEffect(dataJob?.id, dataJob?.status) {
        screenState.isBackupInProgress = dataJob?.status == ExportJobStatus.RUNNING
        if (backupJob != null) {
            screenState.isLoading = backupJob.status == ExportJobStatus.RUNNING
            screenState.backupProblem = backupJob.backupReport?.takeIf { it.failedItems.isNotEmpty() || it.skippedItems.isNotEmpty() }
            screenState.partialBackupSaved = backupJob.status == ExportJobStatus.SUCCEEDED
        }
    }
    // One refresh owner for both initial entry and backup completion, including re-entry
    // after a finished task. Progress-only updates must not trigger directory requests.
    LaunchedEffect(backupJob?.id, backupJob?.status) {
        if (webDavHelper.isConfigured() && backupJob?.status != ExportJobStatus.RUNNING) {
            screenState.refreshBackupList()
        }
    }
    with(screenState) {
        // 启动时检查是否已有配置
        LaunchedEffect(Unit) {
            if (webDavHelper.isConfigured()) {
                isConfigured = true
            }

            // 加载自动备份状态
            autoBackupEnabled = webDavHelper.isAutoBackupEnabled()
            lastBackupTime = webDavHelper.getLastBackupTime()
            changeTriggeredConfig = webDavHelper.getChangeTriggeredBackupConfig()
            backupRetentionConfig = webDavHelper.getBackupRetentionConfig()

            // 加载加密配置
            val encryptionConfig = webDavHelper.getEncryptionConfig()
            encryptionEnabled = encryptionConfig.enabled
            encryptionPassword = encryptionConfig.password

            // 加载备份偏好设置
            backupPreferences = webDavHelper.getBackupPreferences()

            // WebDAV 主备份只备份 Monica 本地库；外部来源有各自的同步/备份入口。
            passwordCount = passwordRepository.getLocalEntriesCount()
            authenticatorCount = secureItemRepository.getLocalItemCountByType(takagi.ru.monica.data.ItemType.TOTP)
            documentCount = secureItemRepository.getLocalItemCountByType(takagi.ru.monica.data.ItemType.DOCUMENT)
            bankCardCount = secureItemRepository.getLocalItemCountByType(takagi.ru.monica.data.ItemType.BANK_CARD)
            noteCount = secureItemRepository.getLocalItemCountByType(takagi.ru.monica.data.ItemType.NOTE)

            // 获取本地回收站数量（排除 KeePass 和 Bitwarden 的数据）
            val database = takagi.ru.monica.data.PasswordDatabase.getDatabase(context)
            val deletedPasswordCount = passwordRepository.getLocalDeletedEntriesCount()
            val deletedSecureItemCount = secureItemRepository.getLocalDeletedItemCount()
            trashCount = deletedPasswordCount + deletedSecureItemCount

            // 获取本地 KeePass 数据库数量
            try {
                val keepassDao = database.localKeePassDatabaseDao()
                localKeePassCount = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    keepassDao.getAllDatabasesSync().size
                }
            } catch (e: Exception) {
                localKeePassCount = 0
            }

            // 获取本地 Passkey 数量（排除 KeePass 和 Bitwarden 的数据）
            try {
                val passkeyDao = database.passkeyDao()
                passkeyCount = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    passkeyDao.getLocalPasskeyCount()
                }
            } catch (e: Exception) {
                passkeyCount = 0
            }
        }

        val pendingCleanupNames = remember(backupList, backupRetentionConfig) {
            BackupRetentionPolicy.backupsToDelete(backupList, backupRetentionConfig).mapTo(mutableSetOf()) { it.name }
        }
        CloudBackupPage(
            title = stringResource(R.string.webdav_backup),
            configured = isConfigured,
            backups = backupList,
            loading = isLoading,
            refreshEnabled = !isBackupInProgress && !restoreBusy,
            onRefresh = ::refreshBackupList,
            onNavigateBack = onNavigateBack,
            onCancelConnection = if (isEditingConnection) ::cancelConnectionEdit else null,
            errorMessage = errorMessage,
            primaryAction = {
                if (isConfigured) {
                    CloudBackupPrimaryButton(
                        label = stringResource(if (isBackupInProgress) R.string.webdav_backup_in_progress else R.string.webdav_create_new_backup),
                        onClick = { createBackup() },
                        enabled = !isLoading && !restoreBusy && !isBackupInProgress,
                        busy = isBackupInProgress,
                    )
                } else {
                    CloudBackupPrimaryButton(
                        label = stringResource(R.string.webdav_test_connection),
                        onClick = ::connectWebDav,
                        enabled = serverUrl.isNotBlank(),
                        busy = isTesting,
                        icon = Icons.Default.CloudDone,
                    )
                }
            },
            location = {
                dataJob?.takeIf { it.status == ExportJobStatus.RUNNING }?.let {
                    TransferProgressCard(it.progress, background = true)
                    Spacer(Modifier.height(12.dp))
                }
                backupJob?.takeIf { it.status != ExportJobStatus.RUNNING }?.let {
                    BackgroundTaskResultCard(it)
                    Spacer(Modifier.height(12.dp))
                }
                val config = if (isConfigured) webDavHelper.getCurrentConfig() else null
                CloudBackupLocationCard(
                    title = config?.serverUrl?.substringAfter("://")?.substringBefore("/") ?: "WebDAV",
                    subtitle = config?.let { listOf(it.serverUrl, it.username).filter(String::isNotBlank).joinToString("\n") }
                        ?: stringResource(R.string.webdav_backup_description),
                    supporting = if (isConfigured && lastBackupTime > 0) {
                        stringResource(R.string.webdav_last_backup) + " " + DateUtils.getRelativeTimeSpanString(
                            lastBackupTime, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
                    } else null,
                    actionLabel = stringResource(R.string.webdav_reconfigure),
                    onClick = if (isConfigured && !isBackupInProgress && !restoreBusy) ::editConnection else null,
                    busy = isTesting,
                )
            },
            connection = {
                FilledTonalButton(onClick = { showPasswordPicker = true }, enabled = !isTesting,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Default.Key, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.webdav_fill_from_password))
                }
                OutlinedTextField(
                    shape = DatabaseManagementFieldShape,
                    value = serverUrl, onValueChange = { serverUrl = it; isConfigured = false },
                    label = { Text(stringResource(R.string.webdav_server_url)) },
                    placeholder = { Text("https://example.com/webdav") },
                    leadingIcon = { Icon(Icons.Default.Cloud, null) },
                    modifier = Modifier.fillMaxWidth().testTag("cloud_backup_server_url"),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    singleLine = true, enabled = !isTesting,
                )
                OutlinedTextField(
                    shape = DatabaseManagementFieldShape,
                    value = username, onValueChange = { username = it; isConfigured = false },
                    label = { Text(stringResource(R.string.webdav_username_optional)) },
                    leadingIcon = { Icon(Icons.Default.Person, null) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), singleLine = true, enabled = !isTesting,
                )
                OutlinedTextField(
                    shape = DatabaseManagementFieldShape,
                    value = password, onValueChange = { password = it; isConfigured = false },
                    label = { Text(stringResource(R.string.webdav_password_optional)) },
                    leadingIcon = { Icon(Icons.Default.Lock, null) },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                stringResource(if (passwordVisible) R.string.hide_password else R.string.show_password))
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("cloud_backup_connection_password"),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    singleLine = true, enabled = !isTesting,
                )
            },
            settings = {
                CloudBackupEncryptionSettings(
                    enabled = encryptionEnabled, password = encryptionPassword, passwordVisible = encryptionPasswordVisible,
                    onEnabledChange = { enabled ->
                        encryptionEnabled = enabled
                        if (!enabled) webDavHelper.setEncryptionConfig(false, encryptionPassword)
                        else if (encryptionPassword.isNotEmpty()) webDavHelper.setEncryptionConfig(true, encryptionPassword)
                    },
                    onPasswordChange = { encryptionPassword = it; webDavHelper.setEncryptionConfig(true, it) },
                    onVisibilityChange = { encryptionPasswordVisible = !encryptionPasswordVisible },
                )
                takagi.ru.monica.ui.components.SelectiveBackupCard(
                    preferences = backupPreferences,
                    onPreferencesChange = { backupPreferences = it; webDavHelper.saveBackupPreferences(it) },
                    passwordCount = passwordCount, authenticatorCount = authenticatorCount,
                    documentCount = documentCount, bankCardCount = bankCardCount, noteCount = noteCount,
                    trashCount = trashCount, passkeyCount = passkeyCount, localKeePassCount = localKeePassCount,
                    isWebDavConfigured = isConfigured,
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    DatabaseManagementCard(modifier = Modifier.fillMaxWidth(), shape = settingsSectionItemShape(0, 3)) {
                        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Schedule, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(R.string.webdav_auto_backup), style = MaterialTheme.typography.bodyLarge)
                                Text(stringResource(R.string.webdav_auto_backup_description), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = autoBackupEnabled, onCheckedChange = { enabled ->
                                autoBackupEnabled = enabled
                                webDavHelper.configureAutoBackup(enabled)
                                if (!enabled) ChangeTriggeredBackupScheduler.cancel(context)
                                Toast.makeText(context, context.getString(if (enabled) R.string.webdav_auto_backup_enabled
                                    else R.string.webdav_auto_backup_disabled), Toast.LENGTH_SHORT).show()
                            })
                        }
                    }
                    DatabaseManagementActionRow(DatabaseManagementAction(Icons.Default.Tune,
                        stringResource(R.string.webdav_sync_settings), {
                            changeTriggeredConfig = webDavHelper.getChangeTriggeredBackupConfig()
                            backupRetentionConfig = webDavHelper.getBackupRetentionConfig()
                            showSyncSettings = true
                        }), 1, 3)
                    DatabaseManagementActionRow(DatabaseManagementAction(Icons.Default.PlayArrow,
                        stringResource(R.string.webdav_backup_now), ::triggerAutomaticBackup,
                        enabled = !isLoading && !isBackupInProgress && !restoreBusy, showChevron = false), 2, 3)
                }
                CloudBackupConnectionSettings(
                    title = stringResource(R.string.webdav_config),
                    fields = webDavHelper.getCurrentConfig()?.let { config -> listOf(
                        stringResource(R.string.webdav_server_url) to config.serverUrl,
                        stringResource(R.string.username) to config.username,
                    ) }.orEmpty(),
                    actions = listOf(
                    DatabaseManagementAction(Icons.Default.Edit, stringResource(R.string.webdav_reconfigure), ::editConnection,
                        enabled = !isBackupInProgress && !restoreBusy),
                    DatabaseManagementAction(Icons.Default.LinkOff, stringResource(R.string.webdav_clear_config), ::clearConnection,
                        enabled = !isBackupInProgress && !restoreBusy, warning = true),
                ))
            },
            backupRow = { index, backup ->
                CloudBackupFileRow(
                    backup = backup, sizeLabel = webDavHelper.formatFileSize(backup.size), index = index, count = backupList.size,
                    expiring = if (backupRetentionConfig.enabled) backup.name in pendingCleanupNames else backup.isExpiring,
                    busy = restoreBusy && restoreBackup?.path == backup.path,
                    enabled = !isLoading && !restoreBusy && !isBackupInProgress,
                    onRestore = { restoreBackup = backup },
                    onDelete = { deleteBackup = backup },
                    onTogglePermanent = {
                        coroutineScope.launch {
                            val result = if (backup.isPermanent) {
                                webDavHelper.unmarkPermanent(backup)
                            } else {
                                webDavHelper.markBackupAsPermanent(backup)
                            }

                            result.onSuccess {
                                Toast.makeText(
                                    context,
                                    if (backup.isPermanent) {
                                        context.getString(R.string.webdav_unmark_permanent_success)
                                    } else {
                                        context.getString(R.string.webdav_mark_permanent_success)
                                    },
                                    Toast.LENGTH_SHORT
                                ).show()
                                refreshBackupList()
                            }.onFailure { e ->
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.webdav_operation_failed, e.message),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    },
                )
            },
        )

        // Keep operation state outside the lazy history: scrolling or switching tabs must not cancel a restore.
        restoreBackup?.let { backup ->
            key(backup.path) {
                WebDavRestoreDialogs(
                    backup = backup, webDavHelper = webDavHelper,
                    passwordRepository = passwordRepository, secureItemRepository = secureItemRepository,
                    onDismiss = { restoreBackup = null }, onBusyChanged = { restoreBusy = it },
                    onRestoreSuccess = { Toast.makeText(context, context.getString(R.string.webdav_restore_success), Toast.LENGTH_SHORT).show() },
                )
            }
        }
        deleteBackup?.let { backup ->
            AlertDialog(
                onDismissRequest = { deleteBackup = null },
                title = { Text(stringResource(R.string.delete_backup)) },
                text = { Text(stringResource(R.string.delete_backup_confirm, backup.name)) },
                confirmButton = {
                    TextButton(onClick = {
                        deleteBackup = null
                        coroutineScope.launch {
                            webDavHelper.deleteBackup(backup).fold(
                                onSuccess = {
                                    backupList = backupList - backup
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.backup_deleted),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                onFailure = { e ->
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.delete_failed, e.message),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            )
                        }
                    }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { deleteBackup = null }) { Text(stringResource(R.string.cancel)) } },
            )
        }

        if (showPasswordPicker) {
            PasswordEntryPickerBottomSheet(
                visible = true,
                title = stringResource(R.string.webdav_fill_from_password),
                passwords = passwordEntriesForPicker.filter { !it.isDeleted && !it.isArchived },
                onDismiss = { showPasswordPicker = false },
                onSelect = { entry ->
                    val resolvedServerUrl = entry.website.trim()
                    val resolvedUsername = runCatching { pickerSecurityManager.decryptData(entry.username) }
                        .getOrNull()
                        ?.trim()
                        .takeUnless { it.isNullOrBlank() }
                        ?: entry.username.trim()
                    val resolvedPassword = runCatching { pickerSecurityManager.decryptData(entry.password) }
                        .getOrNull()
                        ?.trim()
                        .takeUnless { it.isNullOrBlank() }
                        ?: entry.password.trim()

                    if (resolvedServerUrl.isNotBlank()) {
                        serverUrl = resolvedServerUrl
                    }
                    username = resolvedUsername
                    password = resolvedPassword
                    isConfigured = false
                    errorMessage = ""
                    showPasswordPicker = false
                    Toast.makeText(
                        context,
                        context.getString(R.string.webdav_fill_from_password_applied),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
        }

        backupProblem?.let { report ->
            takagi.ru.monica.ui.components.BackupProblemDialog(
                report = report,
                partialSaved = partialBackupSaved,
                onDismiss = { backupProblem = null },
                onRetry = { createBackup() },
                onSkipPasskeys = { createBackup(it) },
            )
        }
        pendingUntrustedCertificate?.let { certificate ->
            WebDavCertificateDialog(
                certificate = certificate,
                onDismiss = { pendingUntrustedCertificate = null },
                onContinue = {
                        pendingUntrustedCertificate = null
                        isTesting = true
                        coroutineScope.launch {
                            webDavHelper.configure(serverUrl, username, password)
                            webDavHelper.testConnection().fold(
                                onSuccess = {
                                    isTesting = false
                                    isConfigured = true
                                    loadBackups(webDavHelper) { list, error ->
                                        backupList = list
                                        error?.let { errorMessage = it }
                                    }
                                },
                                onFailure = { retryError ->
                                    isTesting = false
                                    errorMessage = retryError.message ?: context.getString(R.string.webdav_connection_failed, "")
                                }
                            )
                        }
                }
            )
        }

        if (showSyncSettings) {
            WebDavSyncSettingsDialog(
                config = changeTriggeredConfig,
                retentionConfig = backupRetentionConfig,
                autoBackupEnabled = autoBackupEnabled,
                onDismiss = { showSyncSettings = false },
                onConfirm = { updated, updatedRetention ->
                    changeTriggeredConfig = updated
                    backupRetentionConfig = updatedRetention
                    webDavHelper.setChangeTriggeredBackupConfig(updated)
                    webDavHelper.setBackupRetentionConfig(updatedRetention)
                    if (!updated.enabled) {
                        ChangeTriggeredBackupScheduler.cancel(context)
                    }
                    showSyncSettings = false
                    Toast.makeText(
                        context,
                        context.getString(R.string.webdav_sync_settings_saved),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
        }
    }
}

/**
 * 同步时机和备份数量设置。
 *
 * 编辑副本而不是直接写 SharedPreferences：用户取消时不应留下半套配置。
 */
@Composable
internal fun WebDavSyncSettingsDialog(
    config: WebDavHelper.ChangeTriggeredBackupConfig,
    retentionConfig: BackupRetentionConfig,
    autoBackupEnabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (WebDavHelper.ChangeTriggeredBackupConfig, BackupRetentionConfig) -> Unit
) {
    var draft by remember(config) { mutableStateOf(config) }
    var retentionEnabled by rememberSaveable(retentionConfig.enabled) {
        mutableStateOf(retentionConfig.enabled)
    }
    var retentionCount by rememberSaveable(retentionConfig.maxBackups) {
        mutableStateOf(retentionConfig.maxBackups.toString())
    }
    val parsedRetentionCount = retentionCount.toIntOrNull()
    val retentionCountValid = parsedRetentionCount != null &&
        parsedRetentionCount in BackupRetentionConfig.MAX_BACKUPS_RANGE

    CloudBackupSheet(
        title = stringResource(R.string.webdav_sync_settings), onDismiss = onDismiss,
        actions = {
            CloudBackupPrimaryButton(
                label = stringResource(R.string.save),
                icon = Icons.Default.Done,
                enabled = !retentionEnabled || retentionCountValid,
                onClick = {
                    onConfirm(draft, BackupRetentionConfig(
                        enabled = retentionEnabled,
                        maxBackups = parsedRetentionCount
                            ?.takeIf { it in BackupRetentionConfig.MAX_BACKUPS_RANGE }
                            ?: retentionConfig.maxBackups,
                    ))
                },
            )
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
        },
    ) {
        CloudBackupOptionSwitch(
            title = stringResource(R.string.webdav_change_triggered_title),
            description = stringResource(R.string.webdav_change_triggered_description),
            checked = draft.enabled, enabled = autoBackupEnabled,
            onCheckedChange = { draft = draft.copy(enabled = it) },
        )

        if (!autoBackupEnabled) {
            Text(
                text = stringResource(R.string.webdav_change_triggered_requires_auto_backup),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        MonicaExpandableContent(draft.enabled && autoBackupEnabled) {
            Column {
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.webdav_change_triggered_quiet),
                        modifier = Modifier.weight(1f).padding(end = 12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = stringResource(
                            R.string.webdav_change_triggered_quiet_value,
                            draft.quietMinutes
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
                Slider(
                    value = draft.quietMinutes.toFloat(),
                    onValueChange = { draft = draft.copy(quietMinutes = it.toInt()) },
                    valueRange = WebDavHelper.QUIET_MINUTES_RANGE.first.toFloat()..
                        WebDavHelper.QUIET_MINUTES_RANGE.last.toFloat()
                )
                Text(
                    text = stringResource(R.string.webdav_change_triggered_quiet_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.webdav_change_triggered_min_interval),
                        modifier = Modifier.weight(1f).padding(end = 12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = if (draft.minIntervalMinutes == 0) {
                            stringResource(R.string.webdav_change_triggered_min_interval_off)
                        } else {
                            stringResource(
                                R.string.webdav_change_triggered_min_interval_value,
                                draft.minIntervalMinutes
                            )
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
                Slider(
                    value = draft.minIntervalMinutes.toFloat(),
                    onValueChange = { draft = draft.copy(minIntervalMinutes = it.toInt()) },
                    valueRange = WebDavHelper.MIN_INTERVAL_MINUTES_RANGE.first.toFloat()..
                        WebDavHelper.MIN_INTERVAL_MINUTES_RANGE.last.toFloat()
                )
                Text(
                    text = stringResource(R.string.webdav_change_triggered_min_interval_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        CloudBackupOptionSwitch(
            title = stringResource(R.string.webdav_retention_title),
            description = stringResource(R.string.webdav_retention_description),
            checked = retentionEnabled, onCheckedChange = { retentionEnabled = it },
        )
        MonicaExpandableContent(retentionEnabled) {
            OutlinedTextField(
                shape = DatabaseManagementFieldShape,
                value = retentionCount,
                onValueChange = { value ->
                    if (value.length <= 4 && value.all { it in '0'..'9' }) {
                        retentionCount = value
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                label = { Text(stringResource(R.string.webdav_retention_count)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                isError = !retentionCountValid,
                supportingText = {
                    Text(stringResource(
                        R.string.webdav_retention_count_hint,
                        BackupRetentionConfig.MAX_BACKUPS_RANGE.first,
                        BackupRetentionConfig.MAX_BACKUPS_RANGE.last
                    ))
                }
            )
        }
        Text(
            text = stringResource(R.string.webdav_retention_permanent_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!retentionEnabled) {
            Text(
                text = stringResource(
                    R.string.webdav_retention_disabled_hint,
                    BackupRetentionPolicy.DEFAULT_RETENTION_DAYS,
                    BackupRetentionPolicy.DEFAULT_MIN_TEMPORARY_BACKUPS_TO_KEEP
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WebDavRestoreDialogs(
    backup: BackupFile,
    webDavHelper: WebDavHelper,
    passwordRepository: PasswordRepository,
    secureItemRepository: SecureItemRepository,
    onRestoreSuccess: () -> Unit,
    onDismiss: () -> Unit,
    onBusyChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var showRestoreDialog by remember { mutableStateOf(true) }
    var isRestoring by remember { mutableStateOf(false) }
    var selectedRestoreMode by remember { mutableStateOf(RestoreMode.MERGE_LOCAL) }
    var restoreGlobalDedup by remember { mutableStateOf(false) }
    var showMonicaConfigOverwriteDialog by remember { mutableStateOf(false) }
    var pendingMonicaConfigEntries by remember { mutableStateOf<List<String>>(emptyList()) }
    var pendingDecryptPassword by remember { mutableStateOf<String?>(null) }
    var showRestartRequiredDialog by remember { mutableStateOf(false) }
    var restartCountdownSeconds by remember { mutableStateOf(5) }

    // New state variables for smart decryption
    var showPasswordInputDialog by remember { mutableStateOf(false) }
    var tempPassword by remember { mutableStateOf("") }

    fun resolveRestoreOverwrite(): Boolean = selectedRestoreMode == RestoreMode.REPLACE_LOCAL

    fun resolveLocalOnlyDedup(): Boolean = when (selectedRestoreMode) {
        RestoreMode.MERGE_LOCAL -> !restoreGlobalDedup
        RestoreMode.REPLACE_LOCAL -> true
    }

    suspend fun handleRestoreResult(
        result: Result<RestoreResult>,
        localOnlyDedup: Boolean,
        decryptPassword: String?,
    ) {
        if (result.isSuccess) {
            val restoreResult = result.getOrNull() ?: return
            val report = restoreResult.report
            val stats = BackupRestoreApplier.applyRestoreResult(
                context = context,
                restoreResult = restoreResult,
                passwordRepository = passwordRepository,
                secureItemRepository = secureItemRepository,
                localOnlyDedup = localOnlyDedup,
                logTag = "WebDavBackup"
            )

            isRestoring = false
            pendingDecryptPassword = null
            pendingMonicaConfigEntries = emptyList()
            showMonicaConfigOverwriteDialog = false
            // P0修复：显示详细报告
            val message = if (report.hasIssues()) {
                // 有问题，显示详细报告
                report.getSummary(context)
            } else {
                // 无问题，显示简洁消息
                buildString {
                    val summaryParts = mutableListOf<String>()
                    summaryParts += context.getString(R.string.webdav_restore_summary_part_passwords, stats.passwordImported)
                    summaryParts += context.getString(R.string.webdav_restore_summary_part_other_data, stats.secureItemImported)
                    if (stats.passkeyImported > 0) {
                        summaryParts += context.getString(R.string.legacy_ui_restore_passkey_count, stats.passkeyImported)
                    }
                    if (stats.steamAccountImported > 0) {
                        summaryParts += "Steam maFile ${stats.steamAccountImported}"
                    }
                    append(
                        context.getString(
                            R.string.webdav_restore_summary_success,
                            summaryParts.joinToString(", ")
                        )
                    )

                    val issuesParts = mutableListOf<String>()
                    if (stats.passwordSkipped > 0) {
                        issuesParts += context.getString(
                            R.string.webdav_restore_summary_part_duplicate_passwords,
                            stats.passwordSkipped
                        )
                    }
                    if (stats.secureItemSkipped > 0) {
                        issuesParts += context.getString(
                            R.string.webdav_restore_summary_part_duplicate_data,
                            stats.secureItemSkipped
                        )
                    }
                    if (stats.passwordFailed > 0) {
                        issuesParts += context.getString(
                            R.string.webdav_restore_summary_part_password_failed,
                            stats.passwordFailed
                        )
                    }
                    if (stats.secureItemFailed > 0) {
                        issuesParts += context.getString(
                            R.string.webdav_restore_summary_part_data_failed,
                            stats.secureItemFailed
                        )
                    }
                    if (stats.passkeySkipped > 0) {
                        issuesParts += context.getString(R.string.legacy_ui_restore_passkey_duplicate_count, stats.passkeySkipped)
                    }
                    if (stats.passkeyFailed > 0) {
                        issuesParts += context.getString(R.string.legacy_ui_restore_passkey_failed_count, stats.passkeyFailed)
                    }

                    if (issuesParts.isNotEmpty()) {
                        append(
                            "\n" + context.getString(
                                R.string.webdav_restore_summary_issues_prefix,
                                issuesParts.joinToString(", ")
                            )
                        )
                    }

                    // 如果有导入失败，显示详细信息
                    if (stats.passwordFailed > 0 || stats.secureItemFailed > 0) {
                        append("\n\n${context.getString(R.string.webdav_restore_summary_failed_details)}")
                        stats.failedPasswordDetails.take(5).forEach { append("\n• $it") }
                        stats.failedSecureItemDetails.take(5).forEach { append("\n• $it") }
                        if (stats.passwordFailed + stats.secureItemFailed > 10) {
                            append("\n${context.getString(R.string.webdav_restore_summary_more_logs)}")
                        }
                    }
                }
            }
            Toast.makeText(
                context,
                message,
                Toast.LENGTH_LONG
            ).show()

            if (restoreResult.restartRecommended) {
                restartCountdownSeconds = 5
                showRestartRequiredDialog = true
            }
            onRestoreSuccess()
        } else {
            isRestoring = false
            val exception = result.exceptionOrNull()
            if (exception is WebDavHelper.PasswordRequiredException) {
                tempPassword = decryptPassword.orEmpty()
                showPasswordInputDialog = true
            } else if (exception is WebDavHelper.MonicaConfigDecisionRequiredException) {
                pendingDecryptPassword = decryptPassword
                pendingMonicaConfigEntries = exception.configEntries
                showMonicaConfigOverwriteDialog = true
            } else {
                val error = exception?.message ?: context.getString(R.string.import_data_unknown_error)
                Toast.makeText(
                    context,
                    context.getString(R.string.webdav_restore_failed, error),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    fun startRestore(
        decryptPassword: String? = null,
        restoreMonicaConfig: Boolean? = null,
    ) {
        isRestoring = true
        coroutineScope.launch {
            var parsed: RestoreResult? = null
            try {
                val result = webDavHelper.downloadAndRestoreBackup(
                    backupFile = backup,
                    decryptPassword = decryptPassword,
                    overwrite = resolveRestoreOverwrite(),
                    restoreMonicaConfig = restoreMonicaConfig,
                )
                parsed = result.getOrNull()
                handleRestoreResult(
                    result = result,
                    localOnlyDedup = resolveLocalOnlyDedup(),
                    decryptPassword = decryptPassword,
                )
            } catch (e: Exception) {
                isRestoring = false
                Toast.makeText(
                    context,
                    context.getString(R.string.webdav_restore_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.IO) {
                    parsed?.content?.portableAttachments?.close()
                }
            }
        }
    }

    LaunchedEffect(showRestartRequiredDialog) {
        if (showRestartRequiredDialog) {
            while (restartCountdownSeconds > 0) {
                delay(1000)
                restartCountdownSeconds -= 1
            }
        }
    }

    val restartCountdownProgress by animateFloatAsState(
        targetValue = ((5 - restartCountdownSeconds).coerceAtLeast(0) / 5f),
        animationSpec = tween(durationMillis = 350),
        label = "restore_restart_progress",
    )
    val restartCountdownRingProgress by animateFloatAsState(
        targetValue = (restartCountdownSeconds.coerceAtLeast(0) / 5f).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 350),
        label = "restore_restart_ring",
    )

    LaunchedEffect(isRestoring) { onBusyChanged(isRestoring) }
    DisposableEffect(Unit) { onDispose { onBusyChanged(false) } }
    LaunchedEffect(showRestoreDialog, isRestoring, showPasswordInputDialog,
        showMonicaConfigOverwriteDialog, showRestartRequiredDialog) {
        if (!showRestoreDialog && !isRestoring && !showPasswordInputDialog &&
            !showMonicaConfigOverwriteDialog && !showRestartRequiredDialog) onDismiss()
    }
    if (showRestoreDialog) {
        CloudBackupRestoreSheet(
            backup = backup, sizeLabel = webDavHelper.formatFileSize(backup.size),
            merge = selectedRestoreMode == RestoreMode.MERGE_LOCAL, globalDedup = restoreGlobalDedup,
            onMergeChange = { selectedRestoreMode = if (it) RestoreMode.MERGE_LOCAL else RestoreMode.REPLACE_LOCAL },
            onGlobalDedupChange = { restoreGlobalDedup = it },
            onDismiss = { showRestoreDialog = false },
            onRestore = {
                showRestoreDialog = false
                startRestore(decryptPassword = null, restoreMonicaConfig = null)
            },
        )
    }

    // 密码输入对话框
    if (showPasswordInputDialog) {
        AlertDialog(
            onDismissRequest = { showPasswordInputDialog = false },
            title = { Text(stringResource(R.string.webdav_enter_decrypt_password)) },
            text = {
                Column {
                    Text(stringResource(R.string.webdav_restore_encrypted_hint))
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        shape = DatabaseManagementFieldShape,
                        value = tempPassword,
                        onValueChange = { tempPassword = it },
                        label = { Text(stringResource(R.string.password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPasswordInputDialog = false
                        pendingDecryptPassword = tempPassword
                        startRestore(
                            decryptPassword = tempPassword,
                            restoreMonicaConfig = null,
                        )
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPasswordInputDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showMonicaConfigOverwriteDialog) {
        val displayEntries = pendingMonicaConfigEntries
            .map { monicaConfigEntryDisplayName(context, it) }
            .distinct()
        AlertDialog(
            onDismissRequest = {
                if (!isRestoring) {
                    showMonicaConfigOverwriteDialog = false
                }
            },
            title = { Text(stringResource(R.string.webdav_restore_config_detected_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(
                            R.string.webdav_restore_config_detected_desc,
                            displayEntries.size,
                        )
                    )
                    displayEntries.take(4).forEach { entry ->
                        Text(
                            text = "• $entry",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (displayEntries.size > 4) {
                        Text(
                            text = stringResource(
                                R.string.webdav_restore_config_detected_more,
                                displayEntries.size - 4,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !isRestoring,
                    onClick = {
                        showMonicaConfigOverwriteDialog = false
                        startRestore(
                            decryptPassword = pendingDecryptPassword,
                            restoreMonicaConfig = true,
                        )
                    }
                ) {
                    Text(stringResource(R.string.webdav_restore_config_overwrite_action))
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isRestoring,
                    onClick = {
                        showMonicaConfigOverwriteDialog = false
                        startRestore(
                            decryptPassword = pendingDecryptPassword,
                            restoreMonicaConfig = false,
                        )
                    }
                ) {
                    Text(stringResource(R.string.webdav_restore_config_keep_local_action))
                }
            }
        )
    }

    if (showRestartRequiredDialog) {
        AlertDialog(
            onDismissRequest = {
                if (restartCountdownSeconds <= 0) {
                    showRestartRequiredDialog = false
                }
            },
            title = { Text(stringResource(R.string.webdav_restore_restart_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(
                            R.string.webdav_restore_restart_desc,
                            backup.name,
                        )
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            progress = { restartCountdownRingProgress },
                            strokeWidth = 4.dp,
                        )
                        Text(
                            text = if (restartCountdownSeconds > 0) {
                                context.getString(
                                    R.string.webdav_restore_restart_countdown,
                                    restartCountdownSeconds,
                                )
                            } else {
                                context.getString(R.string.webdav_restore_restart_ready)
                            },
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    LinearProgressIndicator(
                        progress = { restartCountdownProgress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(R.string.webdav_restore_restart_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = restartCountdownSeconds <= 0,
                    onClick = { showRestartRequiredDialog = false }
                ) {
                    Text(
                        if (restartCountdownSeconds > 0) {
                            context.getString(
                                R.string.webdav_restore_restart_wait_action,
                                restartCountdownSeconds,
                            )
                        } else {
                            context.getString(R.string.confirm)
                        }
                    )
                }
            },
        )
    }

}

private suspend fun loadBackups(
    webDavHelper: WebDavHelper,
    onResult: (List<BackupFile>, String?) -> Unit
) {
    webDavHelper.listBackups().fold(
        onSuccess = { list ->
            onResult(list, null)
        },
        onFailure = { e ->
            onResult(emptyList(), e.message)
        }
    )
}


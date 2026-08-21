package takagi.ru.monica.ui.screens

import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
// Ensure Bookmark icons are available (using wildcards in original line 9 covers it if they are in filled, else explicit import)
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BookmarkRemove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import takagi.ru.monica.R
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.repository.SecureItemRepository
import takagi.ru.monica.utils.BackupFile
import takagi.ru.monica.utils.BackupContent
import takagi.ru.monica.utils.BackupContentScope
import takagi.ru.monica.utils.BackupRestoreApplier
import takagi.ru.monica.utils.RestoreResult
import takagi.ru.monica.utils.WebDavHelper
import takagi.ru.monica.utils.AutoBackupManager
import takagi.ru.monica.webdav.WebDavTlsMode
import takagi.ru.monica.utils.CustomFieldBackupEntry
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.CustomField
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
import takagi.ru.monica.util.DataExportImportManager
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import java.text.DateFormat
import android.text.format.DateUtils
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import takagi.ru.monica.ui.components.OutlinedTextField

private fun matchesAnyKeyword(message: String, vararg keywords: String): Boolean {
    val normalized = message.lowercase(Locale.ROOT)
    return keywords.any { keyword -> normalized.contains(keyword.lowercase(Locale.ROOT)) }
}

private fun monicaConfigEntryDisplayName(entry: String): String {
    val normalized = entry.substringAfterLast('/').lowercase(Locale.ROOT)
    return when (normalized) {
        "webdav_connection.json", "webdav_config.json" -> "WebDAV连接配置"
        "page_adjustment_settings.json" -> "页面调整设置"
        "autofill_blocked_fields.json" -> "自动填充屏蔽字段"
        "autofill_save_blocked_targets.json" -> "自动填充不保存名单"
        "autofill_blacklist.json" -> "自动填充黑名单"
        "bitwarden_vaults.json" -> "Bitwarden Vault配置"
        "security_questions.json" -> "密保问题"
        "common_account.json" -> "常用账号模板"
        "monica_config.json" -> "Monica聚合配置"
        else -> entry.substringAfterLast('/')
    }
}

private enum class RestoreMode {
    MERGE_LOCAL,
    REPLACE_LOCAL,
}

@Composable
private fun RestoreModeOptionCard(
    title: String,
    description: String,
    icon: ImageVector,
    selected: Boolean,
    accentColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton,
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        ),
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) accentColor else MaterialTheme.colorScheme.outlineVariant,
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = if (selected) accentColor.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = MaterialTheme.shapes.medium,
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            RadioButton(
                selected = selected,
                onClick = null,
            )
        }
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
    val scrollState = rememberScrollState()
    
    var serverUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    
    var isConfigured by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }
    var backupList by remember { mutableStateOf<List<BackupFile>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    
    // 自动备份状态
    var autoBackupEnabled by remember { mutableStateOf(false) }
    var lastBackupTime by remember { mutableStateOf(0L) }
    
    // 加密设置状态
    var encryptionEnabled by remember { mutableStateOf(false) }
    var encryptionPassword by remember { mutableStateOf("") }
    var encryptionPasswordVisible by remember { mutableStateOf(false) }

    // HTTPS 证书校验档位
    var tlsMode by remember { mutableStateOf(WebDavTlsMode.DEFAULT) }
    // 待用户二次确认的放宽档位；null 表示无待确认项
    var pendingTlsMode by remember { mutableStateOf<WebDavTlsMode?>(null) }
    
    // 选择性备份状态
    var backupPreferences by remember { mutableStateOf(takagi.ru.monica.data.BackupPreferences()) }
    var passwordCount by remember { mutableStateOf(0) }
    var authenticatorCount by remember { mutableStateOf(0) }
    var documentCount by remember { mutableStateOf(0) }
    var bankCardCount by remember { mutableStateOf(0) }
    var noteCount by remember { mutableStateOf(0) }
    var trashCount by remember { mutableStateOf(0) }
    var localKeePassCount by remember { mutableStateOf(0) }
    var passkeyCount by remember { mutableStateOf(0) }
    
    // 备份进行中状态（防止重复点击）
    var isBackupInProgress by remember { mutableStateOf(false) }
    
    var showPasswordPicker by remember { mutableStateOf(false) }
    
    val webDavHelper = remember { WebDavHelper(context) }
    val autoBackupManager = remember { AutoBackupManager(context) }
    val pickerSecurityManager = remember { takagi.ru.monica.security.SecurityManager(context) }
    val passwordEntriesForPicker by passwordRepository.getAllPasswordEntries().collectAsState(initial = emptyList())
    
    // 启动时检查是否已有配置
    LaunchedEffect(Unit) {
        if (webDavHelper.isConfigured()) {
            isConfigured = true
            // 自动加载备份列表
            isLoading = true
            val result = webDavHelper.listBackups()
            isLoading = false
            if (result.isSuccess) {
                backupList = result.getOrNull() ?: emptyList()
            }
        }
        
        // 加载自动备份状态
        autoBackupEnabled = webDavHelper.isAutoBackupEnabled()
        lastBackupTime = webDavHelper.getLastBackupTime()
        
        // 加载加密配置
        val encryptionConfig = webDavHelper.getEncryptionConfig()
        encryptionEnabled = encryptionConfig.enabled
        encryptionPassword = encryptionConfig.password

        // 加载 HTTPS 证书校验档位
        tlsMode = webDavHelper.getTlsMode()
        
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
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.webdav_backup)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 配置信息卡片 (如果已配置)
            if (isConfigured) {
                webDavHelper.getCurrentConfig()?.let { config ->
                    WebDavConfigSummaryCard(
                        config = config,
                        onEdit = {
                            isConfigured = false
                            serverUrl = config.serverUrl
                            username = config.username
                            password = webDavHelper.getCurrentPasswordForEdit()
                            passwordVisible = false
                        },
                        onClear = {
                            webDavHelper.clearConfig()
                            isConfigured = false
                            serverUrl = ""
                            username = ""
                            password = ""
                            backupList = emptyList()
                        }
                    )
                }
            }
            
            // 加密设置卡片 (仅在配置成功后显示)
            if (isConfigured) {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.webdav_enable_encryption),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = stringResource(R.string.webdav_encryption_description),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = encryptionEnabled,
                                onCheckedChange = { enabled ->
                                    encryptionEnabled = enabled
                                    if (!enabled) {
                                        // 如果关闭加密，不需要密码
                                        webDavHelper.setEncryptionConfig(false, encryptionPassword)
                                    } else {
                                        // 如果开启加密，且已有密码，则保存
                                        if (encryptionPassword.isNotEmpty()) {
                                            webDavHelper.setEncryptionConfig(true, encryptionPassword)
                                        }
                                    }
                                }
                            )
                        }
                        
                        if (encryptionEnabled) {
                            OutlinedTextField(
                                value = encryptionPassword,
                                onValueChange = { 
                                    encryptionPassword = it
                                    webDavHelper.setEncryptionConfig(true, it)
                                },
                                label = { Text(stringResource(R.string.webdav_encryption_password)) },
                                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                                visualTransformation = if (encryptionPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { encryptionPasswordVisible = !encryptionPasswordVisible }) {
                                        Icon(
                                            if (encryptionPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                            contentDescription = null
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    imeAction = ImeAction.Done
                                )
                            )
                        }
                    }
                }
            }
            
            // 配置卡片
            if (!isConfigured) {
                Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.webdav_config),
                        style = MaterialTheme.typography.titleMedium
                    )

                    FilledTonalButton(
                        onClick = { showPasswordPicker = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isTesting && !isConfigured
                    ) {
                        Icon(Icons.Default.Key, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.webdav_fill_from_password))
                    }
                    
                    // 服务器地址
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { 
                            serverUrl = it
                            isConfigured = false
                        },
                        label = { Text(stringResource(R.string.webdav_server_url)) },
                        placeholder = { Text("https://example.com/webdav") },
                        leadingIcon = { Icon(Icons.Default.CloudUpload, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Next
                        ),
                        singleLine = true,
                        enabled = !isConfigured
                    )
                    
                    // 用户名
                    OutlinedTextField(
                        value = username,
                        onValueChange = { 
                            username = it
                            isConfigured = false
                        },
                        label = { Text(stringResource(R.string.webdav_username_optional)) },
                        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        singleLine = true,
                        enabled = !isConfigured
                    )
                    
                    // 密码
                    OutlinedTextField(
                        value = password,
                        onValueChange = { 
                            password = it
                            isConfigured = false
                        },
                        label = { Text(stringResource(R.string.webdav_password_optional)) },
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = if (passwordVisible) stringResource(R.string.hide_password) else stringResource(R.string.show_password)
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        enabled = !isConfigured
                    )
                    
                    // 测试连接按钮
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (!isConfigured) {
                            Button(
                                onClick = {
                                    if (serverUrl.isBlank()) {
                                        errorMessage = context.getString(R.string.webdav_fill_all_fields)
                                        return@Button
                                    }
                                    
                                    isTesting = true
                                    errorMessage = ""
                                    webDavHelper.configure(serverUrl, username, password)
                                    
                                    coroutineScope.launch {
                                        webDavHelper.testConnection().fold(
                                            onSuccess = {
                                                isConfigured = true
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
                                },
                                modifier = Modifier.weight(1f),
                                enabled = !isTesting && serverUrl.isNotBlank()
                            ) {
                                if (isTesting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Text(stringResource(R.string.webdav_test_connection))
                            }
                        } else {
                            // 已配置状态显示重新配置和清除配置按钮
                            OutlinedButton(
                                onClick = {
                                    isConfigured = false
                                    backupList = emptyList()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.webdav_reconfigure))
                            }
                            
                            OutlinedButton(
                                onClick = {
                                    webDavHelper.clearConfig()
                                    isConfigured = false
                                    serverUrl = ""
                                    username = ""
                                    password = ""
                                    backupList = emptyList()
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.webdav_config_cleared),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.webdav_clear_config))
                            }
                        }
                    }
                    
                    // 错误信息
                    if (errorMessage.isNotEmpty()) {
                        Text(
                            text = errorMessage,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            }

            // HTTPS 证书校验设置卡片
            // 未配置时紧跟在「测试连接」下方，便于握手失败的用户就地放宽档位；
            // 已配置时作为连接级设置继续可见。
            WebDavTlsModeCard(
                selectedMode = tlsMode,
                onModeSelected = { mode ->
                    if (mode == tlsMode) return@WebDavTlsModeCard
                    if (mode.isRelaxed) {
                        // 放宽档位一律需要二次确认
                        pendingTlsMode = mode
                    } else {
                        tlsMode = mode
                        webDavHelper.setTlsMode(mode)
                        Toast.makeText(
                            context,
                            context.getString(R.string.webdav_tls_changed),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            )
            
            // 自动备份设置卡片 (仅在配置成功后显示)
            if (isConfigured) {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.webdav_auto_backup),
                            style = MaterialTheme.typography.titleMedium
                        )
                        
                        // 自动备份开关
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.webdav_auto_backup),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = stringResource(R.string.webdav_auto_backup_description),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            
                            Switch(
                                checked = autoBackupEnabled,
                                onCheckedChange = { enabled ->
                                    autoBackupEnabled = enabled
                                    webDavHelper.configureAutoBackup(enabled)
                                    
                                    Toast.makeText(
                                        context,
                                        if (enabled) {
                                            context.getString(R.string.webdav_auto_backup_enabled)
                                        } else {
                                            context.getString(R.string.webdav_auto_backup_disabled)
                                        },
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            )
                        }
                        
                        // 显示上次备份时间
                        if (lastBackupTime > 0) {
                            val relativeTime = DateUtils.getRelativeTimeSpanString(
                                lastBackupTime,
                                System.currentTimeMillis(),
                                DateUtils.MINUTE_IN_MILLIS,
                                DateUtils.FORMAT_ABBREV_RELATIVE
                            )
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Schedule,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.webdav_last_backup) + " " + relativeTime,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        
                        // 立即备份按钮
                        OutlinedButton(
                            onClick = {
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
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isLoading && isConfigured
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.webdav_backup_now))
                        }
                    }
                }
                
                // 选择性备份设置卡片
                takagi.ru.monica.ui.components.SelectiveBackupCard(
                    preferences = backupPreferences,
                    onPreferencesChange = { newPreferences ->
                        backupPreferences = newPreferences
                        webDavHelper.saveBackupPreferences(newPreferences)
                    },
                    passwordCount = passwordCount,
                    authenticatorCount = authenticatorCount,
                    documentCount = documentCount,
                    bankCardCount = bankCardCount,
                    noteCount = noteCount,
                    trashCount = trashCount,
                    passkeyCount = passkeyCount,
                    localKeePassCount = localKeePassCount,
                    isWebDavConfigured = isConfigured
                )
            }
            
            // 备份列表(仅在配置成功后显示)
            if (isConfigured) {
                Spacer(modifier = Modifier.height(8.dp))
                
                // 创建备份按钮
                Button(
                    onClick = {
                        // 防止重复点击
                        if (isBackupInProgress) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.webdav_backup_in_progress),
                                Toast.LENGTH_SHORT
                            ).show()
                            return@Button
                        }
                        
                        // 验证：检查是否至少选择了一种内容类型
                        if (!backupPreferences.hasAnyEnabled()) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.backup_validation_error),
                                Toast.LENGTH_SHORT
                            ).show()
                            return@Button
                        }
                        
                        isBackupInProgress = true
                        isLoading = true
                        errorMessage = ""
                        coroutineScope.launch {
                            val backupTarget = SyncTarget.Backup(SyncBackupProvider.WEBDAV)
                            val taskId = SyncDiagnostics.nextTaskId("backup-webdav-screen")
                            val targetLog = backupTarget.stableKey.value
                            val triggerLog = "WEBDAV_SCREEN_MANUAL"
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
                                        // 获取 Monica 本地密码数据
                                        val localPasswords = passwordRepository.getAllLocalPasswordEntries()

                                        // WebDAV 是跨端备份；如果 Android 本机密钥不可用，不能把设备密文写进备份。
                                        val securityManager = takagi.ru.monica.security.SecurityManager(context)
                                        var failedPasswordDecryptCount = 0
                                        val decryptedPasswords = localPasswords.map { entry ->
                                            try {
                                                entry.copy(password = securityManager.decryptData(entry.password))
                                            } catch (e: Exception) {
                                                android.util.Log.w("WebDavBackupScreen", "无法解密密码条目: ${e.message}")
                                                failedPasswordDecryptCount++
                                                entry.copy(password = "")
                                            }
                                        }
                                        if (failedPasswordDecryptCount > 0) {
                                            throw IllegalStateException(
                                                "有 $failedPasswordDecryptCount 条密码无法解密，已取消备份。请先用主密码解锁 Monica 后重新备份。"
                                            )
                                        }

                                        // 获取 Monica 本地其他数据(TOTP、银行卡、证件、笔记)
                                        val localSecureItems = secureItemRepository.getAllLocalItems()

                                        // 创建并上传永久备份
                                        val report = webDavHelper.createAndUploadBackup(
                                            passwords = decryptedPasswords,
                                            secureItems = localSecureItems,
                                            preferences = backupPreferences,
                                            isPermanent = true, // Manual backups are permanent
                                            isManualTrigger = true,
                                            contentScope = BackupContentScope.MONICA_LOCAL_ONLY
                                        ).getOrThrow()

                                        SyncDiagnostics.success(
                                            taskId = taskId,
                                            target = targetLog,
                                            trigger = triggerLog,
                                            startedAt = startedAt,
                                            detail = "passwords=${decryptedPasswords.size} secureItems=${localSecureItems.size} hasIssues=${report.hasIssues()}"
                                        )
                                        report
                                    } catch (error: Exception) {
                                        SyncDiagnostics.failed(taskId, targetLog, triggerLog, startedAt, error)
                                        throw error
                                    }
                                }

                                when (syncResult) {
                                    is SyncTaskAwaitResult.Completed -> {
                                        lastBackupTime = webDavHelper.getLastBackupTime()

                                        val report = syncResult.value
                                        val message = if (report.hasIssues()) {
                                            report.getSummary()
                                        } else {
                                            context.getString(R.string.webdav_backup_success)
                                        }

                                        Toast.makeText(
                                            context,
                                            message,
                                            Toast.LENGTH_LONG
                                        ).show()

                                        loadBackups(webDavHelper) { list, error ->
                                            backupList = list
                                            error?.let { errorMessage = it }
                                        }
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
                                        errorMessage = reason
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.webdav_backup_failed, reason),
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                    is SyncTaskAwaitResult.Canceled -> {
                                        val reason = syncResult.reason ?: "backup canceled"
                                        SyncDiagnostics.skipped(taskId, targetLog, triggerLog, reason)
                                        errorMessage = reason
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.webdav_backup_failed, reason),
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                    is SyncTaskAwaitResult.Failed -> {
                                        val error = syncResult.error.message
                                            ?: context.getString(R.string.webdav_create_backup_failed)
                                        errorMessage = error
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.webdav_backup_failed, error),
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            } catch (e: Exception) {
                                errorMessage = e.message ?: context.getString(R.string.webdav_create_backup_failed)
                                Toast.makeText(
                                    context,
                                    context.getString(
                                        R.string.webdav_backup_failed,
                                        e.message ?: context.getString(R.string.import_data_unknown_error)
                                    ),
                                    Toast.LENGTH_LONG
                                ).show()
                            } finally {
                                isLoading = false
                                isBackupInProgress = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading && !isBackupInProgress
                ) {
                    if (isBackupInProgress) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.webdav_backup_in_progress))
                    } else {
                        Icon(Icons.Default.CloudUpload, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.webdav_create_new_backup))
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.webdav_backup_list),
                                style = MaterialTheme.typography.titleMedium
                            )
                            
                            IconButton(
                                onClick = {
                                    isLoading = true
                                    coroutineScope.launch {
                                        loadBackups(webDavHelper) { list, error ->
                                            backupList = list
                                            isLoading = false
                                            error?.let { errorMessage = it }
                                        }
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
                            }
                        }
                        
                        if (isLoading) {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        } else if (backupList.isEmpty()) {
                            Text(
                                text = stringResource(R.string.webdav_no_backups),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 16.dp)
                            )
                        } else {
                            backupList.forEach { backup ->
                                BackupItem(
                                    backup = backup,
                                    webDavHelper = webDavHelper,
                                    passwordRepository = passwordRepository,
                                    secureItemRepository = secureItemRepository,
                                    onDeleted = {
                                        backupList = backupList - backup
                                    },
                                    onRestoreSuccess = {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.webdav_restore_success),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    },
                                    onStatusChanged = {
                                        // Refresh list when status changes
                                        // (Simplest way to update UI for now)
                                        isLoading = true
                                        coroutineScope.launch {
                                            loadBackups(webDavHelper) { list, error ->
                                                backupList = list
                                                isLoading = false
                                                error?.let { errorMessage = it }
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
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

    // 放宽证书校验前的二次确认
    pendingTlsMode?.let { candidate ->
        WebDavTlsRelaxConfirmDialog(
            mode = candidate,
            onDismiss = { pendingTlsMode = null },
            onConfirm = {
                pendingTlsMode = null
                tlsMode = candidate
                webDavHelper.setTlsMode(candidate)
                Toast.makeText(
                    context,
                    context.getString(R.string.webdav_tls_changed),
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackupItem(
    backup: BackupFile,
    webDavHelper: WebDavHelper,
    passwordRepository: PasswordRepository,
    secureItemRepository: SecureItemRepository,
    onDeleted: () -> Unit,
    onRestoreSuccess: () -> Unit,
    onStatusChanged: () -> Unit // Callback for status change
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var isRestoring by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
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
                report.getSummary()
            } else {
                // 无问题，显示简洁消息
                buildString {
                    val summaryParts = mutableListOf<String>()
                    summaryParts += context.getString(R.string.webdav_restore_summary_part_passwords, stats.passwordImported)
                    summaryParts += context.getString(R.string.webdav_restore_summary_part_other_data, stats.secureItemImported)
                    if (stats.passkeyImported > 0) {
                        summaryParts += "通行密钥 ${stats.passkeyImported}"
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
                        issuesParts += "重复通行密钥 ${stats.passkeySkipped}"
                    }
                    if (stats.passkeyFailed > 0) {
                        issuesParts += "通行密钥失败 ${stats.passkeyFailed}"
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
            try {
                val result = webDavHelper.downloadAndRestoreBackup(
                    backupFile = backup,
                    decryptPassword = decryptPassword,
                    overwrite = resolveRestoreOverwrite(),
                    restoreMonicaConfig = restoreMonicaConfig,
                )
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
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.CloudDownload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = backup.name,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "${dateFormat.format(backup.modified)} • ${webDavHelper.formatFileSize(backup.size)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                // Tags
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (backup.isPermanent) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = stringResource(R.string.webdav_tag_permanent),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    if (backup.isExpiring) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = stringResource(R.string.webdav_tag_expiring),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
            
            // 恢复按钮
            IconButton(
                onClick = {
                    selectedRestoreMode = RestoreMode.MERGE_LOCAL
                    restoreGlobalDedup = false
                    showRestoreDialog = true
                },
                enabled = !isRestoring
            ) {
                if (isRestoring) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = stringResource(R.string.webdav_restore_backup_title),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            
            // More Menu
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    // Mark/Unmark Permanent
                     DropdownMenuItem(
                        text = {
                            Text(
                                if (backup.isPermanent) {
                                    stringResource(R.string.webdav_unmark_permanent)
                                } else {
                                    stringResource(R.string.webdav_mark_permanent)
                                }
                            )
                        },
                        onClick = {
                            menuExpanded = false
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
                                    onStatusChanged()
                                }.onFailure { e ->
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.webdav_operation_failed, e.message),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        },
                        leadingIcon = { 
                            Icon(
                                if (backup.isPermanent) Icons.Default.BookmarkRemove else Icons.Default.BookmarkAdd, 
                                contentDescription = null
                            ) 
                        }
                    )
                    
                    // Delete
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menuExpanded = false
                            showDeleteDialog = true
                        },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
                    )
                }
            }
        }
    }
    
    // 恢复确认对话框
    if (showRestoreDialog) {
        BasicAlertDialog(
            onDismissRequest = { showRestoreDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.94f),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 6.dp,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.webdav_restore_backup_title),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.webdav_restore_mode_title),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = MaterialTheme.shapes.medium,
                            ) {
                                Box(
                                    modifier = Modifier.size(36.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CloudDownload,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = backup.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${dateFormat.format(backup.modified)} · ${webDavHelper.formatFileSize(backup.size)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        RestoreModeOptionCard(
                            title = stringResource(R.string.webdav_restore_mode_merge_title),
                            description = stringResource(R.string.webdav_restore_mode_merge_desc),
                            icon = Icons.Default.Sync,
                            selected = selectedRestoreMode == RestoreMode.MERGE_LOCAL,
                            accentColor = MaterialTheme.colorScheme.primary,
                            onClick = { selectedRestoreMode = RestoreMode.MERGE_LOCAL },
                        )

                        RestoreModeOptionCard(
                            title = stringResource(R.string.webdav_restore_mode_replace_title),
                            description = stringResource(R.string.webdav_restore_mode_replace_desc),
                            icon = Icons.Default.DeleteSweep,
                            selected = selectedRestoreMode == RestoreMode.REPLACE_LOCAL,
                            accentColor = MaterialTheme.colorScheme.error,
                            onClick = { selectedRestoreMode = RestoreMode.REPLACE_LOCAL },
                        )
                    }

                    if (selectedRestoreMode == RestoreMode.MERGE_LOCAL) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = MaterialTheme.shapes.large,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.webdav_restore_mode_global_dedup),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    )
                                    Text(
                                        text = stringResource(R.string.webdav_restore_mode_local_safe_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    )
                                    Text(
                                        text = stringResource(R.string.webdav_restore_mode_global_dedup_desc),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.78f),
                                    )
                                }
                                Switch(
                                    checked = restoreGlobalDedup,
                                    onCheckedChange = { restoreGlobalDedup = it },
                                )
                            }
                        }
                    } else {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.large,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                Text(
                                    text = stringResource(R.string.webdav_restore_mode_replace_warning),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { showRestoreDialog = false }) {
                            Text(context.getString(R.string.cancel))
                        }
                        TextButton(
                            onClick = {
                                showRestoreDialog = false
                                startRestore(
                                    decryptPassword = null,
                                    restoreMonicaConfig = null,
                                )
                            }
                        ) {
                            Text(stringResource(R.string.webdav_restore_action))
                        }
                    }
                }
            }
        }
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
            .map(::monicaConfigEntryDisplayName)
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
    
    // 删除确认对话框
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(context.getString(R.string.delete_backup)) },
            text = { Text(context.getString(R.string.delete_backup_confirm, backup.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        coroutineScope.launch {
                            webDavHelper.deleteBackup(backup).fold(
                                onSuccess = {
                                    onDeleted()
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
                    }
                ) {
                    Text(context.getString(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(context.getString(R.string.cancel))
                }
            }
        )
    }
}

/**
 * WebDAV 配置信息卡片
 */
@Composable
fun WebDavConfigSummaryCard(
    config: WebDavHelper.WebDavConfig,
    onEdit: () -> Unit,
    onClear: () -> Unit
) {
    val context = LocalContext.current
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.webdav_config),
                    style = MaterialTheme.typography.titleMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = onEdit) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = stringResource(R.string.webdav_reconfigure),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    IconButton(onClick = onClear) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.webdav_clear_config),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
            
            Divider()
            
            ConfigInfoRow(
                label = stringResource(R.string.webdav_server_url),
                value = config.serverUrl,
                icon = Icons.Default.CloudUpload
            )
            
            ConfigInfoRow(
                label = stringResource(R.string.username),
                value = config.username,
                icon = Icons.Default.Person
            )
        }
    }
}

/**
 * 配置信息行组件
 */
@Composable
fun ConfigInfoRow(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    val context = LocalContext.current
    val clipboardManager = remember { 
        context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager 
    }
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(20.dp)
            )
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        
        IconButton(
            onClick = {
                val clip = android.content.ClipData.newPlainText(label, value)
                clipboardManager.setPrimaryClip(clip)
                Toast.makeText(
                    context,
                    context.getString(R.string.copied_field_name, label),
                    Toast.LENGTH_SHORT
                ).show()
            }
        ) {
            Icon(
                Icons.Default.ContentCopy,
                contentDescription = "${stringResource(R.string.copy)} $label",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(20.dp)
            )
        }
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

/** 档位标题的字符串资源。 */
@Composable
private fun tlsModeTitle(mode: WebDavTlsMode): String = stringResource(
    when (mode) {
        WebDavTlsMode.SYSTEM_DEFAULT -> R.string.webdav_tls_mode_system_title
        WebDavTlsMode.ALLOW_SELF_SIGNED -> R.string.webdav_tls_mode_self_signed_title
        WebDavTlsMode.ALLOW_UNTRUSTED -> R.string.webdav_tls_mode_untrusted_title
    }
)

/** 档位说明的字符串资源。 */
@Composable
private fun tlsModeDescription(mode: WebDavTlsMode): String = stringResource(
    when (mode) {
        WebDavTlsMode.SYSTEM_DEFAULT -> R.string.webdav_tls_mode_system_desc
        WebDavTlsMode.ALLOW_SELF_SIGNED -> R.string.webdav_tls_mode_self_signed_desc
        WebDavTlsMode.ALLOW_UNTRUSTED -> R.string.webdav_tls_mode_untrusted_desc
    }
)

/** 档位图标；风险递增对应盾牌图标的三种状态。 */
private fun tlsModeIcon(mode: WebDavTlsMode): ImageVector = when (mode) {
    WebDavTlsMode.SYSTEM_DEFAULT -> Icons.Default.VerifiedUser
    WebDavTlsMode.ALLOW_SELF_SIGNED -> Icons.Default.GppMaybe
    WebDavTlsMode.ALLOW_UNTRUSTED -> Icons.Default.GppBad
}

/**
 * HTTPS 证书校验档位选择卡片。
 *
 * 复用本页 [RestoreModeOptionCard] 的单选卡片视觉：选中项换用
 * `secondaryContainer` 背景 + 2dp 强调色描边 + 尾部 RadioButton。
 * 强调色按风险分级：默认档用 primary，自签名用 tertiary，放行档用 error。
 */
@Composable
private fun WebDavTlsModeCard(
    selectedMode: WebDavTlsMode,
    onModeSelected: (WebDavTlsMode) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.webdav_tls_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = stringResource(R.string.webdav_tls_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WebDavTlsMode.entries.forEach { mode ->
                    RestoreModeOptionCard(
                        title = tlsModeTitle(mode),
                        description = tlsModeDescription(mode),
                        icon = tlsModeIcon(mode),
                        selected = selectedMode == mode,
                        accentColor = when (mode) {
                            WebDavTlsMode.SYSTEM_DEFAULT -> MaterialTheme.colorScheme.primary
                            WebDavTlsMode.ALLOW_SELF_SIGNED -> MaterialTheme.colorScheme.tertiary
                            WebDavTlsMode.ALLOW_UNTRUSTED -> MaterialTheme.colorScheme.error
                        },
                        onClick = { onModeSelected(mode) },
                    )
                }
            }

            // 仅在偏离平台默认时提示风险，避免对绝大多数用户产生噪音。
            if (selectedMode.isRelaxed) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.large,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = stringResource(R.string.webdav_tls_relaxed_banner),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 放宽证书校验前的二次确认弹窗。
 *
 * 两个放宽档位的风险量级不同，因此文案分开：自签名仍有链校验与主机名校验，
 * 放行档则完全没有身份认证。确认按钮在放行档上使用 error 色以示警。
 */
@Composable
private fun WebDavTlsRelaxConfirmDialog(
    mode: WebDavTlsMode,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val isUntrusted = mode == WebDavTlsMode.ALLOW_UNTRUSTED
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = tlsModeIcon(mode),
                contentDescription = null,
                tint = if (isUntrusted) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.tertiary
                },
            )
        },
        title = { Text(stringResource(R.string.webdav_tls_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = tlsModeTitle(mode),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(
                        if (isUntrusted) {
                            R.string.webdav_tls_confirm_untrusted
                        } else {
                            R.string.webdav_tls_confirm_self_signed
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = if (isUntrusted) {
                    ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                } else {
                    ButtonDefaults.textButtonColors()
                }
            ) {
                Text(stringResource(R.string.webdav_tls_confirm_accept))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}



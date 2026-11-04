package takagi.ru.monica.bitwarden.ui

import androidx.compose.ui.res.stringResource
import takagi.ru.monica.R
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.animation.animateContentSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import takagi.ru.monica.bitwarden.sync.VaultSyncStatus
import takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel
import takagi.ru.monica.data.bitwarden.BitwardenVault
import java.text.SimpleDateFormat
import java.util.*
import takagi.ru.monica.ui.components.OutlinedTextField

/**
 * Bitwarden 设置界面
 * 
 * 功能：
 * - 查看已连接的 Vault
 * - 添加新的 Vault
 * - 管理同步设置
 * - 锁定/解锁 Vault
 * - 登出 Vault
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BitwardenSettingsScreen(
    viewModel: BitwardenViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToLogin: () -> Unit,
    onNavigateToVault: (Long) -> Unit,
    onNavigateToAccount: (Long) -> Unit,
    onNavigateToSyncQueue: () -> Unit = {}
) {
    val vaults by viewModel.vaults.collectAsState()
    val activeVault by viewModel.activeVault.collectAsState()
    val unlockStateByVault by viewModel.unlockStateByVault.collectAsState()
    val syncStatusByVault by viewModel.syncStatusByVault.collectAsState()
    val syncState by viewModel.syncState.collectAsState()
    val issueReports by viewModel.syncIssueReports.collectAsState()
    var issueVaultId by remember { mutableStateOf<Long?>(null) }
    val isNeverLockEnabled by viewModel.isNeverLockEnabledFlow.collectAsState()
    val isAutoSyncEnabled by viewModel.isAutoSyncEnabledFlow.collectAsState()
    val isSyncOnWifiOnly by viewModel.isSyncOnWifiOnlyFlow.collectAsState()
    val queueSummary by viewModel.syncQueueSummary.collectAsState()
    val context = LocalContext.current
    val hasUnlockedVault = vaults.any { vault ->
        (unlockStateByVault[vault.id] ?: BitwardenViewModel.UnlockState.Locked) ==
            BitwardenViewModel.UnlockState.Unlocked || viewModel.isVaultUnlocked(vault.id)
    }
    val isAnyVaultSyncing = syncStatusByVault.values.any { it.isRunning } ||
        syncState is BitwardenViewModel.SyncState.Syncing
    
    // 对话框状态
    var showLogoutConfirmDialog by remember { mutableStateOf(false) }
    var vaultToLogout by remember { mutableStateOf<BitwardenVault?>(null) }
    var showUnlockDialog by remember { mutableStateOf(false) }
    var vaultToUnlock by remember { mutableStateOf<BitwardenVault?>(null) }
    var showNeverLockUnlockDialog by remember { mutableStateOf(false) }
    var neverLockRequest by remember { mutableStateOf<Pair<Long, String>?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, activeVault?.id, activeVault?.accountKey, activeVault?.email, activeVault?.serverUrl, activeVault?.userId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                neverLockRequest = null
                showNeverLockUnlockDialog = false
                showUnlockDialog = false
                vaultToUnlock = null
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); neverLockRequest = null; showNeverLockUnlockDialog = false }
    }
    
    // 监听事件
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is BitwardenViewModel.BitwardenEvent.NavigateToLogin -> {
                    onNavigateToLogin()
                }
                is BitwardenViewModel.BitwardenEvent.ShowSuccess -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
                is BitwardenViewModel.BitwardenEvent.ShowError -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
                else -> {}
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.legacy_ui_bitwarden_settings)) },
                expandedHeight = if (LocalDensity.current.fontScale > 1.2f) {
                    with(LocalDensity.current) { MaterialTheme.typography.titleLarge.lineHeight.toDp() * 2 + 16.dp }
                } else 64.dp,
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (hasUnlockedVault) {
                        // 同步按钮
                        IconButton(
                            onClick = {
                                if (isAnyVaultSyncing) {
                                    Toast.makeText(context, context.getString(R.string.legacy_ui_sync_wait), Toast.LENGTH_SHORT).show()
                                }
                                viewModel.syncUnlockedVaults()
                            }
                        ) {
                            if (isAnyVaultSyncing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Default.Sync, contentDescription = stringResource(R.string.legacy_ui_sync))
                            }
                        }
                    }
                    IconButton(onClick = onNavigateToLogin) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.legacy_ui_add_vault))
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 已连接的 Vault
            item {
                BitwardenSectionTitle(stringResource(R.string.legacy_ui_connected_vaults))
            }
            
            if (vaults.isEmpty()) {
                item {
                    EmptyVaultCard(onAddClick = onNavigateToLogin)
                }
            } else {
                items(vaults, key = { it.id }) { vault ->
                    val vaultUnlockState = unlockStateByVault[vault.id]
                        ?: if (viewModel.isVaultUnlocked(vault.id)) {
                            BitwardenViewModel.UnlockState.Unlocked
                        } else {
                            BitwardenViewModel.UnlockState.Locked
                        }
                    val vaultSyncStatus = syncStatusByVault[vault.id]
                    val issueReport = issueReports[vault.id]
                    val hasIssues = vaultUnlockState == BitwardenViewModel.UnlockState.Unlocked && issueReport != null &&
                        (issueReport.items.isNotEmpty() || issueReport.accountFailure != null)
                    VaultCard(
                        vault = vault,
                        isActive = vault.id == activeVault?.id,
                        unlockState = vaultUnlockState,
                        syncStatus = vaultSyncStatus,
                        onSelect = { 
                            viewModel.setActiveVault(vault)
                            onNavigateToVault(vault.id)
                        },
                        onLock = { viewModel.lock(vault.id) },
                        onUnlock = {
                            vaultToUnlock = vault
                            showUnlockDialog = true
                        },
                        onSync = { viewModel.requestManualSync(vault.id) },
                        onLogout = {
                            vaultToLogout = vault
                            showLogoutConfirmDialog = true
                        },
                        onAccount = { onNavigateToAccount(vault.id) },
                        issueCount = if (hasIssues) issueReport?.items?.size else null,
                        onIssues = { issueVaultId = vault.id }
                    )

                }
            }
            
            // 同步设置
            item {
                BitwardenSectionTitle(stringResource(R.string.legacy_ui_sync_settings))
            }
            
            item {
                SyncSettingsCard(
                    pendingCount = queueSummary.pending,
                    failedCount = queueSummary.failed,
                    onOpenSyncQueue = onNavigateToSyncQueue,
                    isAutoSyncEnabled = isAutoSyncEnabled,
                    onAutoSyncChanged = { viewModel.isAutoSyncEnabled = it },
                    isSyncOnWifiOnly = isSyncOnWifiOnly,
                    onSyncOnWifiOnlyChanged = { viewModel.isSyncOnWifiOnly = it },
                    canEnableNeverLock = activeVault != null,
                    isNeverLockEnabled = isNeverLockEnabled,
                    onNeverLockChanged = { enabled ->
                        neverLockRequest = null
                        if (!enabled) {
                            viewModel.isNeverLockEnabled = false
                            return@SyncSettingsCard
                        }

                        val vault = activeVault
                        if (vault == null) {
                            return@SyncSettingsCard
                        }

                        if (viewModel.isVaultUnlocked(vault.id)) {
                            viewModel.isNeverLockEnabled = true
                        } else {
                            neverLockRequest = vault.id to UUID.randomUUID().toString()
                            showNeverLockUnlockDialog = true
                        }
                    }
                )
            }
            
            // 关于
            item {
                BitwardenSectionTitle(stringResource(R.string.about))
            }
            
            item {
                AboutCard()
            }
            
            // 底部间距
            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
    
    // 登出确认对话框
    if (showLogoutConfirmDialog && vaultToLogout != null) {
        AlertDialog(
            onDismissRequest = { 
                showLogoutConfirmDialog = false
                vaultToLogout = null
            },
            icon = {
                Icon(
                    Icons.Outlined.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text(stringResource(R.string.legacy_ui_logout_confirm)) },
            text = {
                Column {
                    Text(stringResource(R.string.legacy_ui_logout_account, vaultToLogout!!.email))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.legacy_ui_bitwarden_logout_hint) + "\n\n" + stringResource(R.string.bw_send_logout_drafts),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.logout(vaultToLogout!!.id)
                        showLogoutConfirmDialog = false
                        vaultToLogout = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.legacy_ui_logout))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showLogoutConfirmDialog = false
                        vaultToLogout = null
                    }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
    
    val issueVault = vaults.firstOrNull { it.id == issueVaultId }
    val visibleReport = issueVaultId?.let { issueReports[it] }
    if (issueVault != null && visibleReport != null && viewModel.isVaultUnlocked(issueVault.id)) {
        BitwardenSyncIssuesSheet(account = issueVault.displayName ?: issueVault.email, report = visibleReport,
            onDismiss = { issueVaultId = null }, onRetry = { viewModel.requestManualSync(issueVault.id) })
    } else if (issueVaultId != null) {
        LaunchedEffect(issueVaultId, visibleReport) { issueVaultId = null }
    }

    // 解锁对话框
    if (showUnlockDialog && vaultToUnlock != null) {
        UnlockVaultDialog(
            email = vaultToUnlock!!.email,
            onUnlock = { password ->
                viewModel.unlock(vaultToUnlock!!.id, password)
                showUnlockDialog = false
                vaultToUnlock = null
            },
            onDismiss = {
                showUnlockDialog = false
                vaultToUnlock = null
            }
        )
    }

    if (showNeverLockUnlockDialog) {
        val request = neverLockRequest
        val vault = vaults.firstOrNull { it.id == request?.first }
        if (vault != null && request != null) {
            UnlockVaultDialog(
                email = vault.email,
                onUnlock = { password ->
                    showNeverLockUnlockDialog = false
                    viewModel.unlock(vault.id, password) { success ->
                        if (neverLockRequest == request) {
                            val current = viewModel.activeVault.value
                            if (success && current != null && current.id == request.first && current.accountKey == vault.accountKey &&
                                current.email == vault.email && current.serverUrl == vault.serverUrl && current.userId == vault.userId &&
                                lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                                viewModel.isVaultUnlocked(request.first)) viewModel.isNeverLockEnabled = true
                            neverLockRequest = null
                        }
                    }
                },
                onDismiss = { showNeverLockUnlockDialog = false; neverLockRequest = null }
            )
        }
    }
}

/**
 * Vault 卡片
 */
@Composable
fun VaultCard(
    vault: BitwardenVault,
    isActive: Boolean,
    unlockState: BitwardenViewModel.UnlockState,
    syncStatus: VaultSyncStatus?,
    onSelect: () -> Unit,
    onLock: () -> Unit,
    onUnlock: () -> Unit,
    onSync: () -> Unit,
    onLogout: () -> Unit,
    onAccount: () -> Unit = {},
    issueCount: Int? = null,
    onIssues: () -> Unit = {}
) {
    var expanded by remember(vault.id) { mutableStateOf(false) }
    val isUnlocked = unlockState == BitwardenViewModel.UnlockState.Unlocked
    val isUnlocking = unlockState == BitwardenViewModel.UnlockState.Unlocking
    val isSyncing = syncStatus?.isRunning == true
    val lastSyncTime = maxOf(vault.lastSyncAt ?: 0L, syncStatus?.lastSuccessAt ?: 0L)
    val status = when {
        isSyncing -> stringResource(R.string.legacy_ui_syncing)
        syncStatus?.queuedReason != null -> stringResource(R.string.sync_status_pending_short)
        syncStatus?.blockedReason != null -> formatSyncBlockReason(syncStatus.blockedReason)
        lastSyncTime > 0 -> stringResource(R.string.legacy_ui_last_sync, formatTime(lastSyncTime))
        else -> null
    }
    Column(Modifier.fillMaxWidth().animateContentSize().testTag("bitwarden_vault_${vault.id}"),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(shape = bitwardenGroupShape(last = !expanded),
            color = if (isActive) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (isUnlocking) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                else Icon(if (isUnlocked) Icons.Outlined.LockOpen else Icons.Outlined.Lock, null,
                    tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(vault.displayName?.takeIf { it.isNotBlank() } ?: vault.email, style = MaterialTheme.typography.titleMedium)
                    if (!vault.displayName.isNullOrBlank()) Text(vault.email, style = MaterialTheme.typography.bodyMedium)
                    Text(vault.serverUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(listOfNotNull(if (isActive) stringResource(R.string.legacy_ui_current) else null,
                        stringResource(if (isUnlocked) R.string.ime_state_unlocked else R.string.ime_state_locked), status).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    stringResource(if (expanded) R.string.collapse else R.string.expand))
            }
        }
        if (expanded) {
            BitwardenSettingRow(stringResource(if (isActive) R.string.legacy_ui_current else R.string.bitwarden_settings_select_account),
                Icons.Outlined.CheckCircle, first = false, last = false, enabled = !isActive, onClick = onSelect)
            if (isUnlocked) BitwardenSettingRow(stringResource(if (isSyncing) R.string.legacy_ui_syncing else R.string.legacy_ui_sync),
                Icons.Outlined.Sync, first = false, last = false, enabled = !isSyncing, onClick = onSync)
            if (isUnlocked && issueCount != null) BitwardenSettingRow(stringResource(R.string.bitwarden_sync_issues_button, issueCount),
                Icons.Outlined.ErrorOutline, first = false, last = false, onClick = onIssues)
            BitwardenSettingRow(stringResource(R.string.bitwarden_account_security), Icons.Outlined.ManageAccounts,
                first = false, last = false, onClick = onAccount)
            BitwardenSettingRow(stringResource(if (isUnlocked) R.string.legacy_ui_lock else R.string.unlock),
                if (isUnlocked) Icons.Outlined.Lock else Icons.Outlined.LockOpen, first = false, last = false,
                enabled = !isUnlocking, onClick = if (isUnlocked) onLock else onUnlock)
            BitwardenSettingRow(stringResource(R.string.legacy_ui_logout), Icons.Outlined.Logout,
                first = false, destructive = true, onClick = onLogout)
        }
    }
}

/**
 * 空 Vault 提示卡片
 */
@Composable
fun EmptyVaultCard(onAddClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = bitwardenGroupShape(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Outlined.CloudOff,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.outline
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = stringResource(R.string.legacy_ui_bitwarden_disconnected),
                style = MaterialTheme.typography.titleMedium
            )
            
            Text(
                text = stringResource(R.string.legacy_ui_bitwarden_login_to_sync),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Button(onClick = onAddClick) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.legacy_ui_bitwarden_add_account))
            }
        }
    }
}

/**
 * 同步设置卡片
 */
@Composable
fun SyncSettingsCard(
    isAutoSyncEnabled: Boolean,
    onAutoSyncChanged: (Boolean) -> Unit,
    isSyncOnWifiOnly: Boolean,
    onSyncOnWifiOnlyChanged: (Boolean) -> Unit,
    isNeverLockEnabled: Boolean = false,
    onNeverLockChanged: (Boolean) -> Unit = {},
    canEnableNeverLock: Boolean = true,
    pendingCount: Int = 0,
    failedCount: Int = 0,
    onOpenSyncQueue: () -> Unit = {}
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SyncQueueEntryCard(pendingCount, failedCount, onOpenSyncQueue, last = false)
        BitwardenSettingRow(stringResource(R.string.legacy_ui_auto_sync), Icons.Outlined.Sync,
            supporting = stringResource(R.string.legacy_ui_auto_sync_hint), first = false, last = false,
            checked = isAutoSyncEnabled, onCheckedChange = onAutoSyncChanged)
        BitwardenSettingRow(stringResource(R.string.legacy_ui_sync_wifi_only), Icons.Outlined.Wifi,
            supporting = stringResource(R.string.legacy_ui_sync_wifi_only_hint), first = false,
            checked = isSyncOnWifiOnly, onCheckedChange = onSyncOnWifiOnlyChanged, enabled = isAutoSyncEnabled)
        Spacer(Modifier.height(8.dp))
        BitwardenSectionTitle(stringResource(R.string.bitwarden_settings_security))
        BitwardenSettingRow(stringResource(R.string.legacy_ui_never_lock), Icons.Outlined.LockOpen,
            supporting = stringResource(R.string.legacy_ui_never_lock_hint),
            checked = isNeverLockEnabled, onCheckedChange = onNeverLockChanged, enabled = isNeverLockEnabled || canEnableNeverLock,
            modifier = Modifier.testTag("bitwarden_never_lock"))
        if (isNeverLockEnabled) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Info, null, Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.legacy_ui_never_lock_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * 关于卡片
 */
@Composable
fun AboutCard() {
    Card(modifier = Modifier.fillMaxWidth(), shape = bitwardenGroupShape()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.legacy_ui_bitwarden_integration),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                text = stringResource(R.string.legacy_ui_bitwarden_integration_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                text = stringResource(R.string.legacy_ui_supported_servers),
                style = MaterialTheme.typography.labelMedium
            )
            Text(
                text = stringResource(R.string.legacy_ui_bitwarden_servers),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 解锁 Vault 对话框
 */
@Composable
fun UnlockVaultDialog(
    email: String,
    onUnlock: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Outlined.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text(stringResource(R.string.legacy_ui_unlock_vault)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.legacy_ui_unlock_account_hint, email),
                    style = MaterialTheme.typography.bodyMedium
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.master_password)) },
                    visualTransformation = if (showPassword) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = null
                            )
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onUnlock(password) },
                enabled = password.isNotBlank()
            ) {
                Text(stringResource(R.string.unlock))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/**
 * 格式化时间
 */
@Composable
private fun formatTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    
    return when {
        diff < 60_000 -> stringResource(R.string.time_just_now)
        diff < 3600_000 -> stringResource(R.string.legacy_ui_minutes_ago, diff / 60_000)
        diff < 86400_000 -> stringResource(R.string.legacy_ui_hours_ago, diff / 3600_000)
        else -> {
            val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            sdf.format(Date(timestamp))
        }
    }
}

@Composable
private fun formatSyncBlockReason(reason: takagi.ru.monica.bitwarden.sync.SyncBlockReason): String {
    return when (reason) {
        takagi.ru.monica.bitwarden.sync.SyncBlockReason.AUTO_SYNC_DISABLED -> stringResource(R.string.legacy_ui_auto_sync_disabled)
        takagi.ru.monica.bitwarden.sync.SyncBlockReason.NETWORK_UNAVAILABLE -> stringResource(R.string.legacy_ui_network_unavailable)
        takagi.ru.monica.bitwarden.sync.SyncBlockReason.WIFI_REQUIRED -> stringResource(R.string.legacy_ui_waiting_wifi)
        takagi.ru.monica.bitwarden.sync.SyncBlockReason.VAULT_LOCKED -> stringResource(R.string.legacy_ui_vault_locked)
        takagi.ru.monica.bitwarden.sync.SyncBlockReason.AUTH_REQUIRED -> stringResource(R.string.legacy_ui_reauthenticate)
    }
}

/**
 * 同步队列入口卡片
 */
@Composable
internal fun SyncQueueEntryCard(pendingCount: Int, failedCount: Int, onClick: () -> Unit, last: Boolean = true) {
    val counts = listOfNotNull(
        if (pendingCount > 0) stringResource(R.string.legacy_ui_pending_count, pendingCount) else null,
        if (failedCount > 0) stringResource(R.string.legacy_ui_failed_count, failedCount) else null
    ).joinToString(" · ")
    BitwardenSettingRow(stringResource(R.string.legacy_ui_sync_queue), Icons.Outlined.SyncProblem,
        supporting = counts.ifBlank { stringResource(R.string.bitwarden_settings_queue_hint) },
        onClick = onClick, last = last, modifier = Modifier.testTag("bitwarden_sync_queue_entry"))
}

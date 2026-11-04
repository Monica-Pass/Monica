package takagi.ru.monica.bitwarden.ui

import androidx.compose.ui.res.stringResource
import takagi.ru.monica.R
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
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
    val unlockState by viewModel.unlockState.collectAsState()
    val unlockStateByVault by viewModel.unlockStateByVault.collectAsState()
    val syncStatusByVault by viewModel.syncStatusByVault.collectAsState()
    val syncState by viewModel.syncState.collectAsState()
    val isNeverLockEnabled by viewModel.isNeverLockEnabledFlow.collectAsState()
    val isAutoSyncEnabled by viewModel.isAutoSyncEnabledFlow.collectAsState()
    val isSyncOnWifiOnly by viewModel.isSyncOnWifiOnlyFlow.collectAsState()
    val pendingCount by viewModel.pendingSyncCount.collectAsState()
    val failedCount by viewModel.failedSyncCount.collectAsState()
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
    var pendingEnableNeverLock by remember { mutableStateOf(false) }
    
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

    LaunchedEffect(unlockState, showNeverLockUnlockDialog) {
        if (pendingEnableNeverLock && unlockState == BitwardenViewModel.UnlockState.Unlocked) {
            viewModel.isNeverLockEnabled = true
            pendingEnableNeverLock = false
        }

        if (pendingEnableNeverLock && !showNeverLockUnlockDialog &&
            unlockState == BitwardenViewModel.UnlockState.Locked
        ) {
            pendingEnableNeverLock = false
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.legacy_ui_bitwarden_settings)) },
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
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNavigateToLogin
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.legacy_ui_add_vault))
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 已连接的 Vault
            item {
                Text(
                    text = stringResource(R.string.legacy_ui_connected_vaults),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
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
                        }
                    )
                    TextButton(onClick = { onNavigateToAccount(vault.id) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.bitwarden_account_security))
                    }
                }
            }
            
            // 同步设置
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.legacy_ui_sync_settings),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            
            item {
                SyncSettingsCard(
                    isAutoSyncEnabled = isAutoSyncEnabled,
                    onAutoSyncChanged = { viewModel.isAutoSyncEnabled = it },
                    isSyncOnWifiOnly = isSyncOnWifiOnly,
                    onSyncOnWifiOnlyChanged = { viewModel.isSyncOnWifiOnly = it },
                    isNeverLockEnabled = isNeverLockEnabled,
                    onNeverLockChanged = { enabled ->
                        if (!enabled) {
                            viewModel.isNeverLockEnabled = false
                            return@SyncSettingsCard
                        }

                        val vault = activeVault
                        if (vault == null) {
                            return@SyncSettingsCard
                        }

                        if (unlockState == BitwardenViewModel.UnlockState.Unlocked) {
                            viewModel.isNeverLockEnabled = true
                        } else {
                            pendingEnableNeverLock = true
                            showNeverLockUnlockDialog = true
                        }
                    }
                )
            }
            
            // 同步队列 (仅在有任务或失败时显示)
            if (pendingCount > 0 || failedCount > 0) {
                item {
                    SyncQueueEntryCard(
                        pendingCount = pendingCount,
                        failedCount = failedCount
                    )
                }
            }
            
            // 关于
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.about),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            
            item {
                AboutCard()
            }
            
            // 底部间距
            item {
                Spacer(modifier = Modifier.height(80.dp))
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
                        text = stringResource(R.string.legacy_ui_bitwarden_logout_hint),
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
        val vault = activeVault
        UnlockVaultDialog(
            email = vault?.email ?: "",
            onUnlock = { password ->
                vault?.let { viewModel.unlock(it.id, password) }
                showNeverLockUnlockDialog = false
            },
            onDismiss = {
                showNeverLockUnlockDialog = false
                pendingEnableNeverLock = false
            }
        )
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
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val rotationAngle by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "expand_rotation"
    )
    val isUnlocked = unlockState == BitwardenViewModel.UnlockState.Unlocked
    val isUnlocking = unlockState == BitwardenViewModel.UnlockState.Unlocking
    val isSyncing = syncStatus?.isRunning == true
    val lastSyncTime = vault.lastSyncAt ?: syncStatus?.lastSuccessAt ?: 0L
    val secondaryStatus = when {
        isSyncing -> stringResource(R.string.legacy_ui_syncing)
        syncStatus?.queuedReason != null -> stringResource(R.string.sync_status_pending_short)
        syncStatus?.blockedReason != null -> formatSyncBlockReason(syncStatus.blockedReason)
        else -> null
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (isActive) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            )
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Column {
            // 主要内容
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect() }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 状态图标
                Box(
                    modifier = Modifier
                        .size(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isUnlocking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    } else if (isUnlocked) {
                        Icon(
                            Icons.Outlined.LockOpen,
                            contentDescription = stringResource(R.string.ime_state_unlocked),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    } else {
                        Icon(
                            Icons.Outlined.Lock,
                            contentDescription = stringResource(R.string.ime_state_locked),
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.width(12.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = vault.email,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    Text(
                        text = vault.serverUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    if (secondaryStatus != null) {
                        Text(
                            text = secondaryStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else if (lastSyncTime > 0) {
                        Text(
                            text = stringResource(R.string.legacy_ui_last_sync, formatTime(lastSyncTime)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                // 展开按钮
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        Icons.Default.ExpandMore,
                        contentDescription = if (expanded) stringResource(R.string.collapse) else stringResource(R.string.expand),
                        modifier = Modifier.rotate(rotationAngle)
                    )
                }
            }
            
            // 展开的操作按钮
            AnimatedVisibility(visible = expanded) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isUnlocked) {
                        OutlinedButton(
                            onClick = onLock,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 52.dp)
                                .defaultMinSize(minWidth = 0.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.legacy_ui_lock), maxLines = 1, overflow = TextOverflow.Clip)
                        }
                    } else {
                        Button(
                            onClick = onUnlock,
                            enabled = !isUnlocking,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 52.dp)
                                .defaultMinSize(minWidth = 0.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Outlined.LockOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (isUnlocking) stringResource(R.string.legacy_ui_unlocking) else stringResource(R.string.unlock), maxLines = 1, overflow = TextOverflow.Clip)
                        }
                    }

                    if (isUnlocked) {
                        OutlinedButton(
                            onClick = {
                                if (isSyncing) {
                                    Toast.makeText(context, context.getString(R.string.legacy_ui_sync_wait), Toast.LENGTH_SHORT).show()
                                }
                                onSync()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 52.dp)
                                .defaultMinSize(minWidth = 0.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                        ) {
                            if (isSyncing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Outlined.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.legacy_ui_sync), maxLines = 1, overflow = TextOverflow.Clip)
                        }
                    } else {
                        OutlinedButton(
                            onClick = onSelect,
                            enabled = !isActive,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 52.dp)
                                .defaultMinSize(minWidth = 0.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Outlined.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (isActive) stringResource(R.string.legacy_ui_current) else stringResource(R.string.select), maxLines = 1, overflow = TextOverflow.Clip)
                        }
                    }
                    
                    // 登出按钮
                    OutlinedButton(
                        onClick = onLogout,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 52.dp)
                            .defaultMinSize(minWidth = 0.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Outlined.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.legacy_ui_logout), maxLines = 1, overflow = TextOverflow.Clip)
                    }
                }
            }
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
    onNeverLockChanged: (Boolean) -> Unit = {}
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.legacy_ui_auto_sync),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = stringResource(R.string.legacy_ui_auto_sync_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = isAutoSyncEnabled,
                    onCheckedChange = onAutoSyncChanged
                )
            }
            
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.legacy_ui_sync_wifi_only),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = stringResource(R.string.legacy_ui_sync_wifi_only_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = isSyncOnWifiOnly,
                    onCheckedChange = onSyncOnWifiOnlyChanged,
                    enabled = isAutoSyncEnabled
                )
            }
            
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.legacy_ui_never_lock),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = stringResource(R.string.legacy_ui_never_lock_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isNeverLockEnabled) {
                        Text(
                            text = stringResource(R.string.legacy_ui_never_lock_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Switch(
                    checked = isNeverLockEnabled,
                    onCheckedChange = onNeverLockChanged
                )
            }
        }
    }
}

/**
 * 关于卡片
 */
@Composable
fun AboutCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
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
private fun SyncQueueEntryCard(
    pendingCount: Int,
    failedCount: Int
) {
    Card(
        modifier = Modifier
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.Sync,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.legacy_ui_sync_queue),
                            style = MaterialTheme.typography.titleSmall
                        )
                        
                        if (pendingCount > 0 || failedCount > 0) {
                            Text(
                                text = buildString {
                                    if (pendingCount > 0) append(stringResource(R.string.legacy_ui_pending_count, pendingCount))
                                    if (pendingCount > 0 && failedCount > 0) append(" · ")
                                    if (failedCount > 0) append(stringResource(R.string.legacy_ui_failed_count, failedCount))
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (failedCount > 0) 
                                    MaterialTheme.colorScheme.error 
                                else 
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                           Text(
                                text = stringResource(R.string.sync_status_synced_short),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            ) 
                        }
                    }

                    if (pendingCount > 0) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                    } else if (failedCount > 0) {
                         LinearProgressIndicator(
                            progress = { 1f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = MaterialTheme.colorScheme.error,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        )
                    }
                }
            }
        }
    }
}

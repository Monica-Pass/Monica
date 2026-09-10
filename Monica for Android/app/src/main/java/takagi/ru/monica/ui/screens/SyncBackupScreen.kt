package takagi.ru.monica.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import takagi.ru.monica.R

/**
 * 同步与备份页面 - 整合导入导出和云同步功能
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun SyncBackupScreen(
    onNavigateBack: () -> Unit,
    onNavigateToExportData: () -> Unit = {},
    onNavigateToImportData: () -> Unit = {},
    onNavigateToWebDav: () -> Unit = {},
    onNavigateToOneDrive: () -> Unit = {},
    onNavigateToDedupEngine: () -> Unit = {},
    onNavigateToLocalKeePass: () -> Unit = {},  // 本地 KeePass 数据库管理
    onNavigateToMdbx: () -> Unit = {},
    onNavigateToBitwarden: () -> Unit = {},  // Bitwarden 集成入口
    isPlusActivated: Boolean = false
) {
    val scrollState = rememberScrollState()

    // 准备共享元素 Modifier
    val sharedTransitionScope = takagi.ru.monica.ui.LocalSharedTransitionScope.current
    val animatedVisibilityScope = takagi.ru.monica.ui.LocalAnimatedVisibilityScope.current
    
    var sharedModifier: Modifier = Modifier
    if (false && sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope!!) {
            sharedModifier = Modifier.sharedBounds(
                sharedContentState = rememberSharedContentState(key = "sync_settings_card"),
                animatedVisibilityScope = animatedVisibilityScope!!,
                resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds
            )
        }
    }
    
    Scaffold(
        modifier = sharedModifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sync_backup_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.go_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
        ) {
            // 顶部说明卡片
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Sync,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text(
                            stringResource(R.string.sync_backup_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            stringResource(R.string.sync_backup_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }
            }
            
            // 常用同步区块（高频）
            SyncBackupSection(title = stringResource(R.string.sync_backup_common_sync)) {
                SyncBackupItem(
                    icon = Icons.Default.Cloud,
                    title = stringResource(R.string.webdav_backup),
                    description = stringResource(R.string.webdav_backup_description),
                    onClick = onNavigateToWebDav,
                    enabled = true,
                    badge = null
                )

                HorizontalDivider(
                    modifier = Modifier.padding(start = 72.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                SyncBackupItem(
                    icon = Icons.Default.CloudSync,
                    title = stringResource(R.string.onedrive_backup_title),
                    description = stringResource(R.string.onedrive_backup_description),
                    onClick = onNavigateToOneDrive,
                    enabled = true,
                    badge = null
                )

                HorizontalDivider(
                    modifier = Modifier.padding(start = 72.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                SyncBackupItem(
                    icon = Icons.Default.CloudSync,
                    title = stringResource(R.string.sync_backup_bitwarden_sync_title),
                    description = stringResource(R.string.sync_backup_bitwarden_sync_desc),
                    onClick = onNavigateToBitwarden,
                    enabled = isPlusActivated,
                    badge = if (!isPlusActivated) "Plus" else null
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            SyncBackupSection(title = stringResource(R.string.sync_backup_database_tools)) {
                SyncBackupItem(
                    icon = Icons.Default.Storage,
                    title = stringResource(R.string.dedup_engine_title),
                    description = stringResource(R.string.dedup_engine_entry_desc),
                    onClick = onNavigateToDedupEngine
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // KeePass 相关区块（中低频）
            SyncBackupSection(title = stringResource(R.string.sync_backup_keepass_tools)) {
                SyncBackupItem(
                    icon = Icons.Default.Key,
                    title = stringResource(R.string.local_keepass_database),
                    description = stringResource(R.string.local_keepass_database_description),
                    onClick = onNavigateToLocalKeePass
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            SyncBackupSection(title = "MDBX") {
                SyncBackupItem(
                    icon = Icons.Default.Storage,
                    title = stringResource(R.string.mdbx_ui_manager_entry_title),
                    description = stringResource(R.string.mdbx_ui_manager_entry_description),
                    onClick = onNavigateToMdbx
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 导入导出区块（低频）
            SyncBackupSection(title = stringResource(R.string.sync_backup_import_export_low_freq)) {
                SyncBackupItem(
                    icon = Icons.Default.Download,
                    title = stringResource(R.string.export_data),
                    description = stringResource(R.string.export_data_description),
                    onClick = onNavigateToExportData
                )

                HorizontalDivider(
                    modifier = Modifier.padding(start = 72.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                SyncBackupItem(
                    icon = Icons.Default.Upload,
                    title = stringResource(R.string.import_data),
                    description = stringResource(R.string.import_data_description),
                    onClick = onNavigateToImportData
                )
            }
            
            // 提示卡片
            if (!isPlusActivated) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            stringResource(R.string.sync_backup_plus_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/**
 * 功能分类区块
 */
@Composable
private fun SyncBackupSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = 12.dp)
        )
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        ) {
            Column {
                content()
            }
        }
    }
}

/**
 * 功能项
 */
@Composable
private fun SyncBackupItem(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    badge: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 图标
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = if (enabled) 
                MaterialTheme.colorScheme.primaryContainer
            else 
                MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.size(48.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = if (enabled)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }
        
        Spacer(modifier = Modifier.width(16.dp))
        
        // 文字内容
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled)
                        MaterialTheme.colorScheme.onSurface
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
                
                // Plus 徽章
                if (badge != null) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.tertiaryContainer
                    ) {
                        Text(
                            badge,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }
            
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled)
                    MaterialTheme.colorScheme.onSurfaceVariant
                else
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
        
        // 箭头
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = if (enabled)
                MaterialTheme.colorScheme.onSurfaceVariant
            else
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
        )
    }
}



package takagi.ru.monica.bitwarden.ui

import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.res.stringResource
import takagi.ru.monica.R
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import takagi.ru.monica.bitwarden.sync.SyncItemType
import takagi.ru.monica.bitwarden.sync.SyncOperation
import takagi.ru.monica.bitwarden.sync.SyncStatus

/**
 * 同步队列项数据
 */
data class SyncQueueItem(
    val id: String,
    val itemName: String,
    val itemType: SyncItemType,
    val operation: SyncOperation,
    val status: SyncStatus,
    val errorMessage: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val retryCount: Int = 0
)

/**
 * 同步队列页面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncQueueScreen(
    queueItems: List<SyncQueueItem>,
    onNavigateBack: () -> Unit,
    onRetryItem: (SyncQueueItem) -> Unit,
    onDeleteItem: (SyncQueueItem) -> Unit,
    onRetryAll: () -> Unit,
    onClearCompleted: () -> Unit
) {
    val pendingItems = queueItems.filter { it.status == SyncStatus.PENDING }
    val processingItems = queueItems.filter { it.status == SyncStatus.SYNCING }
    val failedItems = queueItems.filter { it.status == SyncStatus.FAILED }
    val completedItems = queueItems.filter { it.status == SyncStatus.SYNCED }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.legacy_ui_sync_queue)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (failedItems.isNotEmpty()) {
                        TextButton(onClick = onRetryAll) {
                            Text(stringResource(R.string.legacy_ui_retry_all))
                        }
                    }
                    if (completedItems.isNotEmpty()) {
                        IconButton(onClick = onClearCompleted) {
                            Icon(Icons.Outlined.ClearAll, contentDescription = stringResource(R.string.legacy_ui_clear_completed))
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        if (queueItems.isEmpty()) {
            // 空状态
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.legacy_ui_sync_queue_empty),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = stringResource(R.string.legacy_ui_sync_queue_all_synced),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 处理中
                if (processingItems.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = stringResource(R.string.legacy_ui_processing),
                            count = processingItems.size,
                            icon = Icons.Outlined.Sync,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    items(processingItems, key = { it.id }) { item ->
                        SyncQueueItemCard(
                            item = item,
                            onRetry = { onRetryItem(item) },
                            onDelete = { onDeleteItem(item) }
                        )
                    }
                }
                
                // 待处理
                if (pendingItems.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = stringResource(R.string.legacy_ui_pending),
                            count = pendingItems.size,
                            icon = Icons.Outlined.Pending,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    items(pendingItems, key = { it.id }) { item ->
                        SyncQueueItemCard(
                            item = item,
                            onRetry = { onRetryItem(item) },
                            onDelete = { onDeleteItem(item) }
                        )
                    }
                }
                
                // 失败
                if (failedItems.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = stringResource(R.string.sync_status_failed_badge),
                            count = failedItems.size,
                            icon = Icons.Outlined.Error,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    items(failedItems, key = { it.id }) { item ->
                        SyncQueueItemCard(
                            item = item,
                            onRetry = { onRetryItem(item) },
                            onDelete = { onDeleteItem(item) }
                        )
                    }
                }
                
                // 已完成
                if (completedItems.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = stringResource(R.string.legacy_ui_completed),
                            count = completedItems.size,
                            icon = Icons.Outlined.CheckCircle,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                    items(completedItems, key = { it.id }) { item ->
                        SyncQueueItemCard(
                            item = item,
                            onRetry = { onRetryItem(item) },
                            onDelete = { onDeleteItem(item) }
                        )
                    }
                }
                
                item {
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    count: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: androidx.compose.ui.graphics.Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = color
        )
        Spacer(modifier = Modifier.width(8.dp))
        Surface(
            shape = MaterialTheme.shapes.small,
            color = color.copy(alpha = 0.1f)
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = color,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun SyncQueueItemCard(
    item: SyncQueueItem,
    onRetry: () -> Unit,
    onDelete: () -> Unit
) {
    val statusColor by animateColorAsState(
        targetValue = when (item.status) {
            SyncStatus.NONE -> MaterialTheme.colorScheme.outline
            SyncStatus.PENDING -> MaterialTheme.colorScheme.secondary
            SyncStatus.SYNCING -> MaterialTheme.colorScheme.primary
            SyncStatus.SYNCED -> MaterialTheme.colorScheme.tertiary
            SyncStatus.FAILED -> MaterialTheme.colorScheme.error
            SyncStatus.CONFLICT -> MaterialTheme.colorScheme.error
        },
        label = "status_color"
    )
    
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 状态指示条
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(48.dp)
                    .background(
                        color = statusColor,
                        shape = MaterialTheme.shapes.small
                    )
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            // 类型图标
            Icon(
                getTypeIcon(item.itemType),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            // 内容
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.itemName,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    // 操作类型标签
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = getOperationLabel(item.operation),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = getTypeLabel(item.itemType),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    if (item.retryCount > 0) {
                        Text(
                            text = stringResource(R.string.legacy_ui_retry_count, item.retryCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    if (item.errorMessage != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = item.errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            
            // 操作按钮
            if (item.status == SyncStatus.FAILED) {
                IconButton(onClick = onRetry) {
                    Icon(
                        Icons.Outlined.Refresh,
                        contentDescription = stringResource(R.string.retry),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            
            if (item.status != SyncStatus.SYNCING) {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.remove),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            if (item.status == SyncStatus.SYNCING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
            }
        }
    }
}

private fun getTypeIcon(type: SyncItemType): androidx.compose.ui.graphics.vector.ImageVector {
    return when (type) {
        SyncItemType.PASSWORD -> Icons.Outlined.Key
        SyncItemType.TOTP -> Icons.Outlined.QrCode2
        SyncItemType.CARD -> Icons.Outlined.CreditCard
        SyncItemType.NOTE -> Icons.Outlined.Notes
        SyncItemType.IDENTITY -> Icons.Outlined.Badge
        SyncItemType.PASSKEY -> Icons.Outlined.Fingerprint
        SyncItemType.SSH_KEY -> Icons.Outlined.Key
        SyncItemType.FOLDER -> Icons.Outlined.Folder
    }
}

@Composable
private fun getTypeLabel(type: SyncItemType): String {
    return when (type) {
        SyncItemType.PASSWORD -> stringResource(R.string.password)
        SyncItemType.TOTP -> stringResource(R.string.nav_authenticator)
        SyncItemType.CARD -> stringResource(R.string.nav_bank_cards)
        SyncItemType.NOTE -> stringResource(R.string.legacy_ui_secure_note)
        SyncItemType.IDENTITY -> stringResource(R.string.legacy_ui_identity_document)
        SyncItemType.PASSKEY -> stringResource(R.string.passkey)
        SyncItemType.SSH_KEY -> stringResource(R.string.entry_type_ssh_key)
        SyncItemType.FOLDER -> stringResource(R.string.folder_generic)
    }
}

@Composable
private fun getOperationLabel(operation: SyncOperation): String {
    return when (operation) {
        SyncOperation.CREATE -> stringResource(R.string.create)
        SyncOperation.UPDATE -> stringResource(R.string.legacy_ui_update)
        SyncOperation.DELETE -> stringResource(R.string.delete)
        SyncOperation.MOVE_FOLDER -> stringResource(R.string.move)
    }
}

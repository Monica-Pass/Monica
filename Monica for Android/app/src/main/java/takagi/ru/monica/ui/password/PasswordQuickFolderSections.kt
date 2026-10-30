package takagi.ru.monica.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import takagi.ru.monica.R
import takagi.ru.monica.data.KeePassSyncPhase
import takagi.ru.monica.data.KeePassSyncStatus
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.MdbxSyncStatus
import takagi.ru.monica.sync.SyncErrorKind
import takagi.ru.monica.sync.SyncPhase
import takagi.ru.monica.sync.SyncTarget
import takagi.ru.monica.sync.SyncTaskRunner
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import takagi.ru.monica.ui.components.QuickStatusTransferBar
import takagi.ru.monica.ui.components.QuickStatusTransferPhase
import takagi.ru.monica.ui.components.QuickStatusTransferState
import takagi.ru.monica.ui.components.QuickStatusDeleteBar
import takagi.ru.monica.ui.components.QuickStatusDeletePhase
import takagi.ru.monica.ui.components.QuickStatusDeleteState
import takagi.ru.monica.ui.password.PasswordBatchDeleteGlobalProgressState
import takagi.ru.monica.ui.password.PasswordBatchDeletePhase
import takagi.ru.monica.ui.password.PasswordBatchTransferGlobalProgressState
import takagi.ru.monica.ui.password.PasswordBatchTransferPhase
import takagi.ru.monica.viewmodel.CategoryFilter

internal fun LocalMdbxDatabase.mdbxPathShouldFlushPendingUpload(): Boolean =
    lastSyncStatus == MdbxSyncStatus.PENDING_UPLOAD.name

internal data class MdbxPathSyncState(
    val databaseId: Long,
    val pendingCount: Int,
    val isSyncing: Boolean,
    val lastSyncStatus: String? = null,
    val onSync: () -> Unit
)

internal data class QuickStatusBitwardenSyncState(
    val vaultName: String,
    val isRunning: Boolean
)

internal data class QuickStatusKeePassSyncState(
    val databaseId: Long,
    val databaseName: String,
    val status: KeePassSyncStatus,
    val phase: KeePassSyncPhase?,
    val coordinatorPhase: SyncPhase? = null,
    val coordinatorErrorKind: SyncErrorKind? = null,
    val coordinatorErrorMessage: String? = null,
    val onSync: () -> Unit
) {
    val isRunning: Boolean
        get() = coordinatorPhase == SyncPhase.RUNNING ||
            status == KeePassSyncStatus.SYNCING ||
            phase == KeePassSyncPhase.UPLOADING ||
            phase == KeePassSyncPhase.DOWNLOADING ||
            phase == KeePassSyncPhase.COMPARING

    val hasCoordinatorError: Boolean
        get() = coordinatorPhase == SyncPhase.BLOCKED ||
            coordinatorPhase == SyncPhase.FAILED ||
            coordinatorPhase == SyncPhase.CONFLICT
}

internal fun LocalMdbxDatabase.mdbxPathPendingSyncCount(cachedCount: Int? = null): Int {
    return when (runCatching { MdbxSyncStatus.valueOf(lastSyncStatus) }.getOrNull()) {
        // Diagnostics may finish after a successful sync. The current persisted
        // status takes precedence over their older cached count.
        MdbxSyncStatus.LOCAL_ONLY,
        MdbxSyncStatus.IN_SYNC -> 0
        MdbxSyncStatus.PENDING_UPLOAD,
        MdbxSyncStatus.CONFLICT -> (cachedCount ?: 1).coerceAtLeast(1)
        // A failed check or a changed remote is not evidence of a local edit.
        MdbxSyncStatus.REMOTE_CHANGED,
        MdbxSyncStatus.FAILED -> (cachedCount ?: 0).coerceAtLeast(0)
        MdbxSyncStatus.SYNCING -> (cachedCount ?: 0).coerceAtLeast(0)
        null -> 0
    }
}

@Composable
internal fun PasswordQuickFolderBreadcrumbBanner(
    breadcrumbs: List<PasswordQuickFolderBreadcrumb>,
    currentFilter: CategoryFilter,
    onNavigate: (CategoryFilter) -> Unit,
    mdbxSyncState: MdbxPathSyncState? = null,
    transferState: PasswordBatchTransferGlobalProgressState? = null,
    onTransferStatusClick: (() -> Unit)? = null,
    deleteState: PasswordBatchDeleteGlobalProgressState? = null,
    onDeleteStatusClick: (() -> Unit)? = null,
    bitwardenSyncState: QuickStatusBitwardenSyncState? = null,
    keePassSyncState: QuickStatusKeePassSyncState? = null,
    animateHeight: Boolean = true
) {
    val sourceLabel = buildQuickStatusSourceLabel(breadcrumbs = breadcrumbs)
    val statusMode = when {
        transferState?.phase == PasswordBatchTransferPhase.RUNNING -> PasswordQuickStatusMode.TRANSFER_RUNNING
        transferState?.phase == PasswordBatchTransferPhase.SUCCESS -> PasswordQuickStatusMode.TRANSFER_SUCCESS
        deleteState?.phase == PasswordBatchDeletePhase.RUNNING -> PasswordQuickStatusMode.DELETE_RUNNING
        deleteState != null -> PasswordQuickStatusMode.DELETE_SUCCESS
        keePassSyncState != null -> PasswordQuickStatusMode.KEEPASS_SYNC
        bitwardenSyncState != null -> PasswordQuickStatusMode.BITWARDEN_SYNC
        else -> PasswordQuickStatusMode.BREADCRUMB
    }
    AnimatedContent(
        targetState = statusMode,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .then(if (animateHeight) Modifier.animateContentSize(
                animationSpec = tween(260, easing = FastOutSlowInEasing),
                alignment = Alignment.TopStart
            ) else Modifier),
        transitionSpec = {
            fadeIn(animationSpec = tween(durationMillis = 180))
                .togetherWith(fadeOut(animationSpec = tween(durationMillis = 140)))
                // The surrounding container owns height, including same-mode text changes.
                .using(null)
        },
        label = "password-quick-status-bar"
    ) { activeMode ->
        val activeTransferState = transferState
        val activeDeleteState = deleteState
        val activeBitwardenSyncState = bitwardenSyncState
        val activeKeePassSyncState = keePassSyncState
        if (
            (activeMode == PasswordQuickStatusMode.TRANSFER_RUNNING ||
                activeMode == PasswordQuickStatusMode.TRANSFER_SUCCESS) &&
            activeTransferState != null
        ) {
            QuickStatusTransferBar(
                state = activeTransferState.toQuickStatusTransferState(sourceLabel = sourceLabel),
                modifier = Modifier.clickable(enabled = onTransferStatusClick != null) {
                    onTransferStatusClick?.invoke()
                }
            )
        } else if (
            (activeMode == PasswordQuickStatusMode.DELETE_RUNNING ||
                activeMode == PasswordQuickStatusMode.DELETE_SUCCESS) &&
            activeDeleteState != null
        ) {
            QuickStatusDeleteBar(
                state = activeDeleteState.toQuickStatusDeleteState(),
                modifier = Modifier.clickable(enabled = onDeleteStatusClick != null) {
                    onDeleteStatusClick?.invoke()
                }
            )
        } else if (
            activeMode == PasswordQuickStatusMode.KEEPASS_SYNC &&
            activeKeePassSyncState != null
        ) {
            QuickStatusKeePassSyncBar(state = activeKeePassSyncState)
        } else if (
            activeMode == PasswordQuickStatusMode.BITWARDEN_SYNC &&
            activeBitwardenSyncState != null
        ) {
            QuickStatusBitwardenSyncBar(state = activeBitwardenSyncState)
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (mdbxSyncState != null) {
                    MdbxPathSyncActions(state = mdbxSyncState)
                }

                PasswordQuickFolderBreadcrumbPath(
                    breadcrumbs = breadcrumbs,
                    currentFilter = currentFilter,
                    onNavigate = onNavigate,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

private enum class PasswordQuickStatusMode {
    BREADCRUMB,
    TRANSFER_RUNNING,
    TRANSFER_SUCCESS,
    DELETE_RUNNING,
    DELETE_SUCCESS,
    KEEPASS_SYNC,
    BITWARDEN_SYNC
}

@Composable
private fun QuickStatusKeePassSyncBar(
    state: QuickStatusKeePassSyncState,
    modifier: Modifier = Modifier
) {
    val spin by rememberInfiniteTransition(label = "keepass-quick-status-spin")
        .animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "keepass-quick-status-rotation"
        )
    val rotation = if (state.isRunning) spin else 0f
    val containerColor = when {
        state.hasCoordinatorError -> MaterialTheme.colorScheme.errorContainer
        state.status == KeePassSyncStatus.CONFLICT ||
            state.status == KeePassSyncStatus.FAILED ||
            state.status == KeePassSyncStatus.REMOTE_CHANGED -> MaterialTheme.colorScheme.errorContainer
        state.status == KeePassSyncStatus.PENDING_UPLOAD -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val contentColor = when {
        state.hasCoordinatorError -> MaterialTheme.colorScheme.onErrorContainer
        state.status == KeePassSyncStatus.CONFLICT ||
            state.status == KeePassSyncStatus.FAILED ||
            state.status == KeePassSyncStatus.REMOTE_CHANGED -> MaterialTheme.colorScheme.onErrorContainer
        state.status == KeePassSyncStatus.PENDING_UPLOAD -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier
                .weight(1f)
                .height(36.dp),
            shape = RoundedCornerShape(14.dp),
            color = containerColor.copy(alpha = 0.9f),
            contentColor = contentColor,
            tonalElevation = 2.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = keepassQuickSyncStatusLabel(state),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = state.databaseName.ifBlank { "KeePass" },
                    style = MaterialTheme.typography.labelMedium,
                    color = contentColor.copy(alpha = 0.82f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Surface(
            shape = CircleShape,
            color = containerColor,
            contentColor = contentColor,
            tonalElevation = 2.dp,
            modifier = Modifier.size(36.dp)
        ) {
            IconButton(
                onClick = state.onSync,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Sync,
                    contentDescription = stringResource(R.string.legacy_ui_sync_keepass),
                    modifier = Modifier
                        .size(19.dp)
                        .graphicsLayer { rotationZ = rotation }
                )
            }
        }
    }
}

@Composable
internal fun keepassQuickSyncStatusLabel(state: QuickStatusKeePassSyncState): String {
    when (state.coordinatorPhase) {
        SyncPhase.RUNNING -> return stringResource(R.string.sync_status_syncing_short)
        SyncPhase.BLOCKED -> return when (state.coordinatorErrorKind) {
            SyncErrorKind.NETWORK_UNAVAILABLE -> stringResource(R.string.legacy_ui_network_unavailable)
            SyncErrorKind.WIFI_REQUIRED -> stringResource(R.string.legacy_ui_wifi_required)
            SyncErrorKind.TARGET_LOCKED -> stringResource(R.string.legacy_ui_database_locked)
            else -> stringResource(R.string.legacy_ui_sync_blocked)
        }
        SyncPhase.FAILED -> return when (state.coordinatorErrorKind) {
            SyncErrorKind.CONFLICT -> stringResource(R.string.sync_conflict)
            SyncErrorKind.NETWORK_UNAVAILABLE -> stringResource(R.string.legacy_ui_network_connection_failed)
            SyncErrorKind.AUTH_REQUIRED -> stringResource(R.string.legacy_ui_login_expired)
            SyncErrorKind.PERMISSION_DENIED -> stringResource(R.string.legacy_ui_write_permission_missing)
            else -> stringResource(R.string.sync_status_failed_short)
        }
        SyncPhase.CONFLICT -> return stringResource(R.string.sync_conflict)
        SyncPhase.CANCELED -> return stringResource(R.string.legacy_ui_sync_cancelled)
        else -> Unit
    }
    if (state.isRunning) return stringResource(R.string.sync_status_syncing_short)
    return when (state.status) {
        KeePassSyncStatus.PENDING_UPLOAD -> stringResource(R.string.legacy_ui_upload_pending)
        KeePassSyncStatus.REMOTE_CHANGED -> stringResource(R.string.legacy_ui_remote_updated)
        KeePassSyncStatus.CONFLICT -> stringResource(R.string.sync_conflict)
        KeePassSyncStatus.FAILED -> stringResource(R.string.sync_status_failed_short)
        KeePassSyncStatus.SYNCING -> stringResource(R.string.sync_status_syncing_short)
        KeePassSyncStatus.IN_SYNC -> stringResource(R.string.sync_status_synced_short)
        KeePassSyncStatus.LOCAL_ONLY -> stringResource(R.string.legacy_ui_local_database)
    }
}

@Composable
private fun QuickStatusBitwardenSyncBar(
    state: QuickStatusBitwardenSyncState,
    modifier: Modifier = Modifier
) {
    val spin by rememberInfiniteTransition(label = "bitwarden-quick-status-spin")
        .animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "bitwarden-quick-status-rotation"
        )
    val rotation = if (state.isRunning) spin else 0f
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier
                .weight(1f)
                .height(36.dp),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.88f),
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            tonalElevation = 2.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (state.isRunning) stringResource(R.string.sync_status_syncing_short) else stringResource(R.string.sync_status_pending_short),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = state.vaultName.ifBlank { "Bitwarden" },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            tonalElevation = 2.dp,
            modifier = Modifier.size(36.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Sync,
                    contentDescription = null,
                    modifier = Modifier
                        .size(19.dp)
                        .graphicsLayer { rotationZ = rotation }
                )
            }
        }
    }
}

private fun PasswordBatchTransferGlobalProgressState.toQuickStatusTransferState(
    sourceLabel: String
): QuickStatusTransferState = QuickStatusTransferState(
    action = action,
    sourceLabel = sourceLabel,
    targetLabel = targetLabel,
    processed = processed,
    total = total,
    phase = when (phase) {
        PasswordBatchTransferPhase.RUNNING -> QuickStatusTransferPhase.RUNNING
        PasswordBatchTransferPhase.SUCCESS -> QuickStatusTransferPhase.SUCCESS
    },
    successCount = successCount
)

private fun PasswordBatchDeleteGlobalProgressState.toQuickStatusDeleteState(): QuickStatusDeleteState =
    QuickStatusDeleteState(
        processed = processed,
        total = total,
        phase = when (phase) {
            PasswordBatchDeletePhase.RUNNING -> QuickStatusDeletePhase.RUNNING
            PasswordBatchDeletePhase.SUCCESS -> QuickStatusDeletePhase.SUCCESS
            PasswordBatchDeletePhase.FAILURE -> QuickStatusDeletePhase.FAILURE
        },
        successCount = successCount
    )

@Composable
private fun buildQuickStatusSourceLabel(
    breadcrumbs: List<PasswordQuickFolderBreadcrumb>
): String {
    val titles = breadcrumbs.map { it.title }.filter { it.isNotBlank() }
    return titles.joinToString(separator = "/").ifBlank { stringResource(R.string.legacy_ui_current_location) }
}

@Composable
internal fun PasswordQuickFolderBreadcrumbPath(
    breadcrumbs: List<PasswordQuickFolderBreadcrumb>,
    currentFilter: CategoryFilter,
    onNavigate: (CategoryFilter) -> Unit,
    modifier: Modifier = Modifier,
    horizontalContentPadding: Dp = 8.dp,
) {
    Box(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = horizontalContentPadding, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            breadcrumbs.forEachIndexed { index, crumb ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            color = if (crumb.isCurrent) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.68f)
                            }
                        )
                        .clickable(enabled = !crumb.isCurrent) {
                            if (currentFilter != crumb.targetFilter) {
                                onNavigate(crumb.targetFilter)
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = crumb.title,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (crumb.isCurrent) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        }
                    )
                }
                if (index != breadcrumbs.lastIndex) {
                    Text(
                        text = ">",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
internal fun MdbxPathSyncActions(state: MdbxPathSyncState) {
    val backgroundSyncing by remember(state.databaseId) {
        val target = SyncTarget.MdbxVault(state.databaseId)
        SyncTaskRunner.statuses.map { statuses ->
            statuses.values.any { it.target == target && it.phase == SyncPhase.RUNNING }
        }.distinctUntilChanged()
    }.collectAsState(initial = false)
    val isSyncing = state.isSyncing || backgroundSyncing
    val syncingDescription = if (isSyncing) stringResource(R.string.keepass_remote_sync_status_syncing) else ""
    val statusLabel = when (state.lastSyncStatus) {
        MdbxSyncStatus.FAILED.name -> R.string.keepass_remote_sync_status_failed
        MdbxSyncStatus.CONFLICT.name -> R.string.keepass_remote_sync_status_conflict
        MdbxSyncStatus.REMOTE_CHANGED.name -> R.string.keepass_remote_sync_status_remote_changed
        else -> null
    }
    Row(
        modifier = Modifier
            .height(36.dp)
            .animateContentSize(animationSpec = tween(durationMillis = 220)),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AnimatedVisibility(
            visible = state.pendingCount > 0 || statusLabel != null,
            enter = expandHorizontally(
                expandFrom = Alignment.End,
                animationSpec = tween(durationMillis = 220)
            ) + fadeIn(animationSpec = tween(durationMillis = 180)),
            exit = shrinkHorizontally(
                shrinkTowards = Alignment.End,
                animationSpec = tween(durationMillis = 180)
            ) + fadeOut(animationSpec = tween(durationMillis = 140))
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                tonalElevation = 1.dp,
                modifier = Modifier
                    .width(104.dp)
                    .height(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = if (statusLabel != null) stringResource(statusLabel)
                            else stringResource(R.string.legacy_ui_unsynced_count, state.pendingCount),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        val spin by rememberInfiniteTransition(label = "mdbx-path-sync-spin")
            .animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 900, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "mdbx-path-sync-rotation"
            )
        val rotation = if (isSyncing) spin else 0f

        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            tonalElevation = 2.dp,
            modifier = Modifier.size(36.dp)
        ) {
            IconButton(
                onClick = state.onSync,
                modifier = Modifier.size(36.dp).semantics { stateDescription = syncingDescription }
            ) {
                Icon(
                    imageVector = Icons.Default.Sync,
                    contentDescription = stringResource(R.string.legacy_ui_sync_mdbx),
                    modifier = Modifier
                        .size(19.dp)
                        .graphicsLayer { rotationZ = rotation }
                )
            }
        }
    }
}

@Composable
internal fun PasswordQuickFolderShortcutsSection(
    shortcuts: List<PasswordQuickFolderShortcut>,
    currentFilter: CategoryFilter,
    useM3CardStyle: Boolean,
    onNavigate: (CategoryFilter) -> Unit
) {
    if (useM3CardStyle) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            shortcuts.forEach { shortcut ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (currentFilter != shortcut.targetFilter) {
                                onNavigate(shortcut.targetFilter)
                            }
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = if (shortcut.isBack) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f)
                        }
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (shortcut.isBack) {
                                Icons.AutoMirrored.Filled.KeyboardArrowLeft
                            } else {
                                Icons.Default.Folder
                            },
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = if (shortcut.isBack) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = shortcut.title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (shortcut.subtitle.isNotBlank()) {
                                Text(
                                    text = shortcut.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (!shortcut.isBack) {
                                Text(
                                    text = stringResource(
                                        R.string.password_list_quick_folder_count,
                                        shortcut.passwordCount ?: 0
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Icon(
                            imageVector = if (shortcut.isBack) {
                                Icons.AutoMirrored.Filled.KeyboardArrowLeft
                            } else {
                                Icons.AutoMirrored.Filled.KeyboardArrowRight
                            },
                            contentDescription = null,
                            tint = if (shortcut.isBack) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 2.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            shortcuts.forEach { shortcut ->
                Card(
                    modifier = Modifier
                        .size(width = 182.dp, height = 74.dp)
                        .clickable {
                            if (currentFilter != shortcut.targetFilter) {
                                onNavigate(shortcut.targetFilter)
                            }
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = if (shortcut.isBack) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f)
                        }
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (shortcut.isBack) {
                                Icons.AutoMirrored.Filled.KeyboardArrowLeft
                            } else {
                                Icons.Default.Folder
                            },
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint = if (shortcut.isBack) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.Center
                        ) {
                            if (!shortcut.isBack) {
                                Text(
                                    text = stringResource(
                                        R.string.password_list_quick_folder_count,
                                        shortcut.passwordCount ?: 0
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = shortcut.title,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (shortcut.subtitle.isNotBlank()) {
                                Text(
                                    text = shortcut.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

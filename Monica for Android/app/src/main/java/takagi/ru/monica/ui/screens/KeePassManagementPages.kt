package takagi.ru.monica.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.data.KeePassDatabaseSourceType
import takagi.ru.monica.data.KeePassSyncStatus
import takagi.ru.monica.data.LocalKeePassDatabase
import takagi.ru.monica.data.isRemoteSource
import takagi.ru.monica.ui.components.MonicaTileGrid
import takagi.ru.monica.viewmodel.LocalKeePassViewModel

internal enum class KeePassManagementSource {
    LOCAL, WEBDAV, ONEDRIVE, GOOGLE_DRIVE;

    fun contains(database: LocalKeePassDatabase): Boolean = when (this) {
        LOCAL -> !database.isRemoteSource()
        WEBDAV -> database.sourceType == KeePassDatabaseSourceType.REMOTE_WEBDAV
        ONEDRIVE -> database.sourceType == KeePassDatabaseSourceType.REMOTE_ONEDRIVE
        GOOGLE_DRIVE -> database.sourceType == KeePassDatabaseSourceType.REMOTE_GOOGLE_DRIVE
    }

    val icon: ImageVector get() = when (this) {
        LOCAL -> Icons.Default.Storage
        WEBDAV -> Icons.Default.CloudSync
        ONEDRIVE -> Icons.Default.Cloud
        GOOGLE_DRIVE -> Icons.Default.CloudQueue
    }
}

@Composable
internal fun KeePassManagementSource.title(): String = when (this) {
    KeePassManagementSource.LOCAL -> stringResource(R.string.mdbx_ui_local_databases)
    KeePassManagementSource.WEBDAV -> "WebDAV"
    KeePassManagementSource.ONEDRIVE -> "OneDrive"
    KeePassManagementSource.GOOGLE_DRIVE -> "Google Drive"
}

@Composable
internal fun KeePassManagementHub(
    databases: List<LocalKeePassDatabase>,
    googleDriveEnabled: Boolean,
    onSourceSelected: (KeePassManagementSource) -> Unit
) {
    val counts = remember(databases) {
        KeePassManagementSource.entries.associateWith { source -> databases.count(source::contains) }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("keepass_hub"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Text(stringResource(R.string.mdbx_ui_database_count, databases.size),
                modifier = Modifier.padding(horizontal = 4.dp),
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        }
        item {
            DatabaseManagementActionGroup(
                title = stringResource(R.string.storage_location),
                actions = KeePassManagementSource.entries.filter {
                    it != KeePassManagementSource.GOOGLE_DRIVE || googleDriveEnabled || counts.getValue(it) > 0
                }.map { source ->
                    DatabaseManagementAction(source.icon, source.title(),
                        { onSourceSelected(source) }, stringResource(R.string.mdbx_ui_database_count, counts.getValue(source)))
                }
            )
        }
    }
}

@Composable
internal fun KeePassSourceManagementPage(
    source: KeePassManagementSource,
    databases: List<LocalKeePassDatabase>,
    verificationStates: Map<Long, LocalKeePassViewModel.VerificationState>,
    onCreateClick: () -> Unit,
    onOpenClick: () -> Unit,
    onOpenDatabase: (LocalKeePassDatabase) -> Unit,
    onResolveConflict: (LocalKeePassDatabase) -> Unit,
    gridState: LazyGridState = rememberLazyGridState(),
    sourceActionsEnabled: Boolean = true,
    onReorder: suspend (List<Long>) -> Unit = {},
) {
    val reserveConflictActionSlot = remember(databases) {
        databases.any { it.isRemoteSource() && it.lastSyncStatus == KeePassSyncStatus.CONFLICT }
    }
    val tileMetrics = rememberDatabaseManagementTileMetrics()
    Column(Modifier.fillMaxSize()) {
        if (databases.isEmpty()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
            ) {
                DatabaseManagementIconBadge(source.icon)
                Text(stringResource(R.string.no_keepass_database), style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center)
                Text(stringResource(R.string.no_keepass_database_description), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        } else {
            DatabaseManagementReorderableGrid(
                databases, LocalKeePassDatabase::id, gridState, "keepass_database_grid", onReorder,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { database, modifier, dragging ->
                KeePassDatabaseTile(database,
                    verificationStates[database.id] ?: LocalKeePassViewModel.VerificationState.Unknown,
                    onClick = { if (!dragging) onOpenDatabase(database) },
                    onResolveConflict = { if (!dragging) onResolveConflict(database) },
                    reserveConflictActionSlot = reserveConflictActionSlot, metrics = tileMetrics,
                    modifier = modifier)
            }
        }
        DatabaseManagementCreateOpenActions(onCreateClick, onOpenClick,
            testTagPrefix = "keepass", enabled = sourceActionsEnabled)
    }
}

@Composable
internal fun keePassSourceLabel(source: KeePassDatabaseSourceType): String = stringResource(when (source) {
    KeePassDatabaseSourceType.LOCAL_INTERNAL -> R.string.internal_storage
    KeePassDatabaseSourceType.LOCAL_DOCUMENT_URI -> R.string.external_storage
    KeePassDatabaseSourceType.REMOTE_WEBDAV -> R.string.keepass_webdav_database_badge
    KeePassDatabaseSourceType.REMOTE_ONEDRIVE -> R.string.keepass_onedrive_database_badge
    KeePassDatabaseSourceType.REMOTE_GOOGLE_DRIVE -> R.string.keepass_gdrive_database_badge
})

@Composable
internal fun keePassVerificationLabel(state: LocalKeePassViewModel.VerificationState): String = stringResource(when (state) {
    is LocalKeePassViewModel.VerificationState.Verified -> R.string.local_keepass_status_verified
    is LocalKeePassViewModel.VerificationState.Verifying -> R.string.local_keepass_status_verifying
    is LocalKeePassViewModel.VerificationState.Failed -> R.string.local_keepass_status_unverified
    else -> R.string.local_keepass_status_unknown
})

@Composable
private fun KeePassDatabaseTile(
    database: LocalKeePassDatabase,
    verificationState: LocalKeePassViewModel.VerificationState,
    onClick: () -> Unit,
    onResolveConflict: () -> Unit,
    reserveConflictActionSlot: Boolean,
    metrics: DatabaseManagementTileMetrics,
    modifier: Modifier = Modifier,
) {
    val remote = database.isRemoteSource()
    val conflict = remote && database.lastSyncStatus == KeePassSyncStatus.CONFLICT
    val failed = verificationState is LocalKeePassViewModel.VerificationState.Failed
    val warning = failed || (remote && database.lastSyncStatus in setOf(KeePassSyncStatus.CONFLICT, KeePassSyncStatus.FAILED, KeePassSyncStatus.REMOTE_CHANGED))
    DatabaseManagementCard(onClick = onClick,
        modifier = modifier.fillMaxWidth().testTag("keepass_database_${database.id}"),
        colors = CardDefaults.cardColors(containerColor = if (database.isDefault)
            MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)) {
        val status = if (remote) remoteSyncStatusLabel(database.lastSyncStatus) else keePassVerificationLabel(verificationState)
        DatabaseManagementTileContent(
            name = database.name,
            sourceLabel = keePassSourceLabel(database.sourceType),
            status = if (remote && failed) "$status\n${keePassVerificationLabel(verificationState)}" else status,
            warning = warning,
            metrics = metrics,
            reserveActionSlot = reserveConflictActionSlot,
            actionLabel = stringResource(R.string.keepass_conflict_review),
            onAction = if (conflict) onResolveConflict else null,
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Shield, contentDescription = null, modifier = Modifier.size(28.dp),
                    tint = if (database.isDefault) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary)
                if (database.isDefault) Icon(Icons.Default.Star, contentDescription = stringResource(R.string.default_label), Modifier.size(16.dp))
            }
        }
    }
}

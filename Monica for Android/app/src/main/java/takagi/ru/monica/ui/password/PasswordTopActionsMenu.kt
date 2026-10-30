package takagi.ru.monica.ui.password

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.DashboardCustomize
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpOffset
import takagi.ru.monica.R

internal val PasswordTopActionsMenuOffset = DpOffset(x = 48.dp, y = 6.dp)
private val PasswordTopActionsMenuShape = RoundedCornerShape(20.dp)

private fun passwordTopActionsMenuLayoutModifier(modifier: Modifier): Modifier {
    return modifier.widthIn(min = 220.dp, max = 260.dp)
}

@Composable
internal fun PasswordTopActionsDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    showNearbySync: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!expanded) return

    MaterialTheme(
        shapes = MaterialTheme.shapes.copy(
            extraSmall = RoundedCornerShape(20.dp),
            small = RoundedCornerShape(20.dp)
        )
    ) {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            offset = PasswordTopActionsMenuOffset,
            shape = PasswordTopActionsMenuShape,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 10.dp,
            tonalElevation = 0.dp,
            modifier = passwordTopActionsMenuLayoutModifier(modifier)
                .shadow(10.dp, PasswordTopActionsMenuShape)
                .clip(PasswordTopActionsMenuShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f),
                    shape = PasswordTopActionsMenuShape
                )
        ) {
            if (showNearbySync) NearbySyncTopActionsMenuItem(onDismissMenu = onDismissRequest)
            content()
        }
    }
}

@Composable
internal fun PasskeyScanTopActionsMenuItem(
    onDismissMenu: () -> Unit,
    onScanFidoQr: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(R.string.passkey_scan_qr_menu_title)) },
        leadingIcon = { Icon(Icons.Default.QrCodeScanner, contentDescription = null) },
        onClick = {
            onDismissMenu()
            onScanFidoQr()
        },
    )
}

@Composable
internal fun CommonPasswordTopActionsMenuItems(
    onDismissMenu: () -> Unit,
    onShowDisplayOptions: () -> Unit,
    onOpenCommonAccountTemplates: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenArchive: () -> Unit,
    showDisplayOptionsEntry: Boolean = true,
    showArchiveEntry: Boolean = true,
    showSettingsEntry: Boolean = false,
    onOpenSettings: (() -> Unit)? = null,
    onScanFidoQr: (() -> Unit)? = null,
) {
    if (showDisplayOptionsEntry) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.display_options_menu_title)) },
            leadingIcon = { Icon(Icons.Default.DashboardCustomize, contentDescription = null) },
            onClick = {
                onDismissMenu()
                onShowDisplayOptions()
            }
        )
    }
    if (onScanFidoQr != null) {
        PasskeyScanTopActionsMenuItem(onDismissMenu = onDismissMenu, onScanFidoQr = onScanFidoQr)
    }
    DropdownMenuItem(
        text = { Text(stringResource(R.string.common_account_title)) },
        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
        onClick = {
            onDismissMenu()
            onOpenCommonAccountTemplates()
        }
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.timeline_title)) },
        leadingIcon = { Icon(Icons.Default.History, contentDescription = null) },
        onClick = {
            onDismissMenu()
            onOpenHistory()
        }
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.timeline_trash_title)) },
        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
        onClick = {
            onDismissMenu()
            onOpenTrash()
        }
    )
    if (showArchiveEntry) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.archive_page_title)) },
            leadingIcon = { Icon(Icons.Default.Archive, contentDescription = null) },
            onClick = {
                onDismissMenu()
                onOpenArchive()
            }
        )
    }
    if (showSettingsEntry && onOpenSettings != null) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.nav_settings)) },
            leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
            onClick = {
                onDismissMenu()
                onOpenSettings()
            }
        )
    }
}

@Composable
internal fun KeepassRefreshTopActionsMenuItem(
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Text(stringResource(R.string.kp_file_reload))
        },
        leadingIcon = { Icon(Icons.Default.Sync, contentDescription = null) },
        onClick = onClick
    )
}

@Composable
internal fun KeepassNativeManagerTopActionsMenuItem(
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(R.string.keepass_native_open_manager)) },
        leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
        onClick = onClick,
    )
}

@Composable
internal fun MdbxSyncTopActionsMenuItem(
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(R.string.legacy_ui_sync_mdbx)) },
        leadingIcon = { Icon(Icons.Default.Sync, contentDescription = null) },
        onClick = onClick
    )
}

@Composable
internal fun MdbxCreateSnapshotTopActionsMenuItem(
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(R.string.mdbx_create_snapshot_menu)) },
        leadingIcon = { Icon(Icons.Default.Restore, contentDescription = null) },
        onClick = onClick
    )
}

@Composable
internal fun MdbxCommitHistoryTopActionsMenuItem(
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(R.string.mdbx_commit_history_menu)) },
        leadingIcon = { Icon(Icons.Default.History, contentDescription = null) },
        onClick = onClick
    )
}

@Composable
internal fun BitwardenSyncTopActionsMenuItem(
    isSyncing: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Text(
                if (isSyncing) {
                    "${stringResource(R.string.sync_status_syncing_short)}..."
                } else {
                    stringResource(R.string.sync_bitwarden_database_menu)
                }
            )
        },
        leadingIcon = {
            if (isSyncing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Icon(Icons.Default.Sync, contentDescription = null)
            }
        },
        enabled = enabled,
        onClick = onClick
    )
}

@Composable
internal fun BitwardenReunlockTopActionsMenuItem(
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(R.string.reunlock_current_database_menu)) },
        leadingIcon = { Icon(Icons.Default.LockOpen, contentDescription = null) },
        onClick = onClick
    )
}

@Composable
internal fun BitwardenLockTopActionsMenuItem(
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(R.string.lock_current_database_menu)) },
        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
        onClick = onClick
    )
}

@Composable
internal fun BitwardenClearCacheTopActionsMenuItem(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(R.string.clear_bitwarden_cache_menu)) },
        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
        enabled = enabled,
        onClick = onClick
    )
}

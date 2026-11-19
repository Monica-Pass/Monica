package takagi.ru.monica.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import takagi.ru.monica.R
import takagi.ru.monica.lansync.*
import takagi.ru.monica.ui.components.GroupedItemDefaults
import takagi.ru.monica.ui.components.SettingsPanelRow
import takagi.ru.monica.ui.components.SettingsContentRow
import takagi.ru.monica.ui.gestures.SwipeActions
import java.text.DateFormat
import java.util.Date

@Composable
internal fun NearbyDeletionReviewHost() {
    val context = LocalContext.current
    val coordinator = remember(context) { DeviceSyncCoordinator.get(context) }
    val state by coordinator.state.collectAsStateWithLifecycle()
    val unlocked by takagi.ru.monica.security.SessionManager.isUnlocked.collectAsStateWithLifecycle()
    state.deletionReview?.takeIf { unlocked && state.appVisible && state.phase != "locked" }?.let { review ->
        NearbyDeletionReviewDialog(review, coordinator::cancelSync) { include ->
            coordinator.confirmReceivedDeletions(review, include)
        }
    }
}

@Composable
internal fun NearbyDeletionReviewDialog(review: DeviceDeletionReview, cancel: () -> Unit, decide: (Boolean) -> Unit) {
    AlertDialog(onDismissRequest = cancel, modifier = Modifier.testTag("nearby-deletion-review"),
        icon = { Icon(Icons.Default.DeleteOutline, null) },
        title = { Text(stringResource(R.string.nearby_deletion_review_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.nearby_deletion_review_body, review.deviceName, review.count))
                OutlinedButton({ decide(false) }, Modifier.fillMaxWidth().testTag("nearby-deletions-keep")) {
                    Text(stringResource(R.string.nearby_deletions_keep))
                }
            }
        },
        confirmButton = { TextButton({ decide(true) }, Modifier.testTag("nearby-deletions-include")) {
            Text(stringResource(R.string.nearby_deletions_include), color = MaterialTheme.colorScheme.error)
        } },
        dismissButton = { TextButton(cancel) { Text(stringResource(R.string.nearby_cancel_sync)) } })
}

@Composable
internal fun NearbyPairedDevices(peers: List<DeviceSyncPeerState>, forget: (Set<String>) -> Unit) {
    var selectedIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var forgetting by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val available = peers.map { it.deviceId }.toSet()
    val selected = selectedIds.toSet().intersect(available)
    val pending = forgetting.toSet().intersect(available)
    val selecting = selected.isNotEmpty()
    LaunchedEffect(available) {
        selectedIds = selectedIds.filter { it in available }
        forgetting = forgetting.filter { it in available }
    }
    fun toggle(id: String) { selectedIds = if (id in selected) selectedIds - id else selectedIds + id }
    BackHandler(selecting) { selectedIds = emptyList() }
    if (pending.isNotEmpty()) AlertDialog(onDismissRequest = { forgetting = emptyList() },
        modifier = Modifier.testTag("nearby-forget-confirm"),
        icon = { Icon(Icons.Default.LinkOff, null) },
        title = { Text(stringResource(R.string.nearby_forget_count, pending.size)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(peers.filter { it.deviceId in pending }.joinToString("\n") { it.name })
            Text(stringResource(R.string.nearby_forget_body))
        } },
        confirmButton = { TextButton({
            forget(pending); selectedIds = selectedIds.filterNot { it in pending }; forgetting = emptyList()
        }, Modifier.testTag("nearby-forget-accept")) {
            Text(stringResource(R.string.device_sync_forget), color = MaterialTheme.colorScheme.error)
        } },
        dismissButton = { TextButton({ forgetting = emptyList() }) { Text(stringResource(R.string.cancel)) } })
    Column(Modifier.fillMaxWidth().testTag("nearby-devices"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (selecting) stringResource(R.string.nearby_selected_devices, selected.size)
                else stringResource(R.string.device_sync_devices), Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            if (selecting) {
                IconButton({ selectedIds = if (selected.size == peers.size) emptyList() else peers.map { it.deviceId } }) {
                    Icon(Icons.Default.SelectAll, stringResource(R.string.nearby_select_all))
                }
                IconButton({ forgetting = selected.toList() }, Modifier.testTag("nearby-forget-selected")) {
                    Icon(Icons.Default.DeleteOutline, stringResource(R.string.nearby_delete_selected))
                }
                IconButton({ selectedIds = emptyList() }) { Icon(Icons.Default.Close, stringResource(R.string.cancel)) }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(GroupedItemDefaults.Spacing)) {
            if (peers.isEmpty()) SettingsContentRow(Icons.Default.Devices, stringResource(R.string.nearby_no_devices_title), stringResource(R.string.device_sync_no_devices))
            peers.forEachIndexed { index, peer -> key(peer.deviceId) {
                val shape = GroupedItemDefaults.shape(index, peers.size)
                SwipeActions(onSwipeLeft = { forgetting = listOf(peer.deviceId) }, onSwipeRight = { toggle(peer.deviceId) },
                    cardShape = shape, isSwiped = peer.deviceId in pending,
                    leftActionLabel = stringResource(R.string.device_sync_forget), leftActionIcon = Icons.Default.LinkOff) {
                    Surface(shape = shape, color = if (peer.deviceId in selected) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceContainer) {
                        Row(Modifier.fillMaxWidth().testTag("nearby-peer-${peer.deviceId}")
                            .semantics { this.selected = peer.deviceId in selected }
                            .combinedClickable(role = Role.Checkbox, onLongClick = { toggle(peer.deviceId) },
                                onClick = { if (selecting) toggle(peer.deviceId) })
                            .heightIn(min = 76.dp).padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Default.Devices, null, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(peer.name, style = MaterialTheme.typography.bodyLarge)
                                Text(nearbyPeerStatus(peer), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (selecting) Checkbox(peer.deviceId in selected, { toggle(peer.deviceId) })
                            else IconButton({ forgetting = listOf(peer.deviceId) }) {
                                Icon(Icons.Default.LinkOff, stringResource(R.string.device_sync_forget))
                            }
                        }
                    }
                }
            } }
        }
        if (peers.isNotEmpty()) Text(stringResource(R.string.nearby_device_gestures), Modifier.padding(horizontal = 12.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun nearbyPeerStatus(peer: DeviceSyncPeerState): String {
    val label = stringResource(when (peer.status) {
        "syncing" -> R.string.device_sync_syncing
        "online" -> R.string.device_sync_online
        "scheduled" -> R.string.device_sync_scheduled
        "app_closed" -> R.string.nearby_status_waiting_apps
        "manual" -> R.string.nearby_wait_manual
        "paused" -> R.string.device_sync_paused
        "locked" -> R.string.device_sync_locked
        "preparation_failed" -> R.string.device_sync_preparation_failed_status
        "first_sync_pending" -> R.string.device_sync_first_pending
        "waiting" -> if (peer.lastSyncedAt == 0L) R.string.device_sync_first_pending else R.string.device_sync_waiting
        else -> if (peer.lastSyncedAt == 0L) R.string.device_sync_first_pending else R.string.device_sync_offline
    })
    return label + if (peer.lastSyncedAt > 0) "\n" + stringResource(R.string.device_sync_last,
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(peer.lastSyncedAt))) else ""
}

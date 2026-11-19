package takagi.ru.monica.ui.screens

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import takagi.ru.monica.BuildConfig
import takagi.ru.monica.R
import takagi.ru.monica.lansync.*
import takagi.ru.monica.ui.components.SettingsPanelGroup
import takagi.ru.monica.ui.components.SettingsPanelRow
import takagi.ru.monica.ui.components.SettingsContentRow
import java.text.DateFormat
import java.util.Date

internal fun nearbyStageLabel(context: Context, state: DeviceSyncState): String = context.getString(when {
    state.phase == "cancelling" -> R.string.nearby_status_cancelling
    state.phase == "cancelled" -> R.string.nearby_status_cancelled
    state.error != null -> if (state.failureStage == "preparing") R.string.nearby_status_prepare_failed else R.string.nearby_status_failed
    !state.appVisible -> R.string.nearby_status_background
    state.phase == "waiting_for_app" -> R.string.nearby_status_waiting_apps
    state.deletionReview != null -> R.string.nearby_deletion_review_title
    state.passkeyReview != null -> R.string.nearby_status_review
    state.phase == "locked" -> R.string.device_sync_locked
    else -> when (state.progress?.stage ?: state.phase) {
        "preparing" -> R.string.nearby_status_preparing
        "connecting" -> R.string.nearby_status_connecting
        "authenticating", "checking" -> R.string.nearby_status_checking
        "sending", "receiving", "transferring" -> R.string.nearby_status_transferring
        "received", "applying", "remembering" -> R.string.nearby_status_applying
        "confirming" -> R.string.nearby_status_confirming
        "completed" -> R.string.nearby_status_completed
        "passkey_review" -> R.string.nearby_status_review
        else -> when {
            state.pairing || state.manualSync -> R.string.nearby_status_waiting
            !state.enabled -> R.string.nearby_wait_manual
            state.peers.isEmpty() -> R.string.nearby_no_devices_title
            else -> R.string.nearby_status_ready
        }
    }
})

internal fun nearbyNextStep(context: Context, state: DeviceSyncState): String = context.getString(when {
    state.phase == "cancelling" -> R.string.nearby_hint_cancelling
    state.phase == "cancelled" -> R.string.nearby_hint_cancelled
    state.manualSync && state.retryInSeconds > 0 && state.error == null -> R.string.nearby_hint_retrying
    state.manualSync && state.phase in setOf("idle", "waiting", "connecting") && state.progress == null && state.activeConnections == 0 && state.error == null -> R.string.nearby_hint_waiting_peer
    !state.appVisible -> R.string.nearby_hint_background
    state.enabled && !state.nearbyDiscoveryAvailable -> R.string.nearby_hint_presence_unavailable
    state.phase == "waiting_for_app" -> R.string.nearby_hint_waiting_apps
    state.error == "sync_database_unavailable" -> R.string.nearby_hint_database
    state.error in setOf("folder_trash_unavailable", "folder_attachment_trash_unavailable") -> R.string.device_sync_folder_trash_unsupported
    state.error in setOf("invalid_asset", "asset_unavailable", "invalid_asset_identity") -> R.string.device_sync_asset_failed
    state.error in setOf("sync_direction_conflict", "sync_direction_unsupported") -> R.string.device_sync_direction_hint
    state.error in setOf("unsupported_protocol", "native_unavailable", "device_sync_unavailable") -> R.string.nearby_hint_update
    state.error in setOf("pairing_rejected", "authentication_failed", "different_device", "pairing_expired") -> R.string.nearby_hint_pair
    state.error != null && state.failureStage == "preparing" -> R.string.nearby_hint_prepare_failed
    state.error in setOf("connection_failed", "listen_failed", "discovery_unavailable") -> R.string.nearby_hint_connect
    state.error == "peer_merge_failed" -> R.string.nearby_hint_peer_apply
    state.error in setOf("invalid_password_payload", "invalid_vault_payload", "invalid_passkey_material") -> R.string.nearby_hint_payload
    state.error != null && state.failureStage in setOf("received", "applying", "remembering") -> R.string.nearby_hint_apply
    state.error != null -> R.string.nearby_hint_generic
    state.phase == "locked" -> R.string.nearby_hint_locked
    state.deletionReview != null -> R.string.nearby_deletion_review_hint
    state.passkeyReview != null || state.phase == "passkey_review" -> R.string.nearby_hint_review
    state.phase == "received" && DeviceSyncEditGate.isEditing -> R.string.nearby_hint_editing
    state.progress?.stage == "preparing" || state.phase == "preparing" -> R.string.nearby_hint_preparing
    state.activeConnections > 0 || state.phase in setOf("transferring", "applying", "received", "confirming") -> R.string.nearby_hint_transfer
    state.peers.isEmpty() -> R.string.device_sync_no_devices
    !state.enabled && !state.manualSync -> R.string.nearby_manual_both_hint
    else -> R.string.nearby_hint_connect
})

private fun nearbyTime(context: Context, time: Long): String = if (time <= 0) context.getString(R.string.nearby_diagnostic_none)
    else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(time))

private fun nearbyProgressText(context: Context, progress: DeviceSyncProgress?): String = when {
    progress == null -> ""
    progress.totalBytes > 0 -> context.getString(R.string.nearby_progress_bytes,
        Formatter.formatShortFileSize(context, progress.bytes), Formatter.formatShortFileSize(context, progress.totalBytes))
    progress.total > 0 -> context.getString(R.string.nearby_progress_items, progress.completed, progress.total)
    else -> ""
}

/** Progress is per current phase; an unknown total never becomes a fabricated percentage. */
internal fun nearbyProgressPercent(state: DeviceSyncState): Int? {
    if (state.error != null || state.phase in setOf("cancelling", "cancelled")) return null
    if (state.phase == "completed") return 100
    val progress = state.progress ?: return null
    val total = if (progress.totalBytes > 0) progress.totalBytes else progress.total
    val completed = if (progress.totalBytes > 0) progress.bytes else progress.completed
    return if (total > 0) ((completed.toDouble() / total).coerceIn(0.0, 1.0) * 100).toInt() else null
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NearbySyncIndicator(state: DeviceSyncState) {
    val stopped = state.phase in setOf("cancelling", "cancelled", "completed", "paused", "locked", "background")
    val active = !stopped && state.error == null &&
        (state.manualSync || state.pairing || state.activeConnections > 0 || state.progress != null)
    val percentage = nearbyProgressPercent(state)
    val label = when {
        percentage != null -> "$percentage%"
        state.error != null -> "!"
        active -> "…"
        else -> stringResource(R.string.nearby_indicator_idle)
    }
    val indicatorColor = MaterialTheme.colorScheme.primary
    // Custom themes may override primary without changing onPrimary. Choose
    // the higher-contrast foreground against the indicator's actual fill.
    val labelColor = if (indicatorColor.luminance() > 0.179f) Color.Black else Color.White
    Box(Modifier.size(96.dp).testTag("nearby-sync-indicator"), contentAlignment = Alignment.Center) {
        if (active) LoadingIndicator(Modifier.size(88.dp).clearAndSetSemantics {}, color = indicatorColor)
        else LoadingIndicator(progress = { 0f }, modifier = Modifier.size(88.dp).clearAndSetSemantics {},
            color = indicatorColor, polygons = LoadingIndicatorDefaults.IndeterminateIndicatorPolygons)
        Text(label, Modifier.testTag("nearby-sync-percentage"), style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp),
            color = labelColor, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Fixed scaled text slots and an always-present indicator keep every state the same size. */
@Composable
internal fun NearbySyncStatus(state: DeviceSyncState, diagnostics: () -> Unit, cancelPairing: () -> Unit,
    sync: () -> Unit = {}, cancelSync: () -> Unit = {}) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val titleStyle = MaterialTheme.typography.titleMedium
    val hintStyle = MaterialTheme.typography.bodySmall
    val titleHeight = with(density) { titleStyle.lineHeight.toDp() * 2 } + 4.dp
    val hintHeight = with(density) { hintStyle.lineHeight.toDp() * 3 } + 4.dp
    val countHeight = with(density) { MaterialTheme.typography.labelSmall.lineHeight.toDp() } + 4.dp
    val cancelling = state.phase == "cancelling"
    Surface(Modifier.fillMaxWidth().testTag("nearby-status"), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                NearbySyncIndicator(state)
                Column(Modifier.weight(1f)) {
                    Text(nearbyStageLabel(context, state), Modifier.height(titleHeight).testTag("nearby-status-title"), style = titleStyle,
                        color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(nearbyNextStep(context, state), Modifier.height(hintHeight), style = hintStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, minLines = 3, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(nearbyProgressText(context, state.progress.takeUnless { state.error != null || cancelling }).ifEmpty { " " },
                Modifier.fillMaxWidth().height(countHeight), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Button(if (state.manualSync) cancelSync else sync, Modifier.fillMaxWidth(),
                enabled = !cancelling && (state.manualSync || state.peers.isNotEmpty() && !state.pairing)) {
                Icon(if (state.manualSync || cancelling) Icons.Default.Close else Icons.Default.Sync, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (cancelling) R.string.nearby_status_cancelling else if (state.manualSync) R.string.nearby_cancel_sync else R.string.device_sync_now),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                TextButton(diagnostics, Modifier.align(Alignment.Center).testTag("nearby-diagnostics-button")) {
                    Text(stringResource(R.string.nearby_view_diagnostics), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (state.pairing) IconButton(cancelPairing, Modifier.align(Alignment.CenterEnd)) {
                    Icon(Icons.Default.Close, stringResource(R.string.nearby_cancel_pairing))
                }
            }
        }
    }
}

/** Reads only transport type: no SSID, IP address or nearby-device identity. */
@Composable
private fun rememberNearbyNetwork(): State<Int> {
    val context = LocalContext.current
    return produceState(R.string.nearby_network_unknown, context) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        fun refresh() {
            value = runCatching {
                val caps = manager.getNetworkCapabilities(manager.activeNetwork)
                when {
                    caps == null -> R.string.nearby_network_offline
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> R.string.nearby_network_vpn
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> R.string.nearby_network_wifi
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> R.string.nearby_network_ethernet
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> R.string.nearby_network_cellular
                    else -> R.string.nearby_network_unknown
                }
            }.getOrDefault(R.string.nearby_network_unknown)
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refresh()
            override fun onLost(network: Network) = refresh()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = refresh()
        }
        val registered = runCatching { manager.registerDefaultNetworkCallback(callback) }.isSuccess
        refresh()
        awaitDispose { if (registered) runCatching { manager.unregisterNetworkCallback(callback) } }
    }
}

internal fun nearbyDiagnosticRows(context: Context, state: DeviceSyncState, database: String, network: String): List<Pair<String, String>> {
    fun label(id: Int) = context.getString(id)
    val direction = context.getString(when (state.mode) {
        DeviceSyncMode.SEND_ONLY -> R.string.device_sync_send_only
        DeviceSyncMode.RECEIVE_ONLY -> R.string.device_sync_receive_only
        DeviceSyncMode.BIDIRECTIONAL -> R.string.device_sync_bidirectional
    })
    return listOf(
        label(R.string.nearby_diagnostic_stage) to nearbyStageLabel(context, state),
        label(R.string.nearby_database) to "$database · $direction",
        label(R.string.device_sync_automatic) to label(if (state.enabled) R.string.nearby_automatic_hint else R.string.nearby_manual_hint),
        label(R.string.nearby_diagnostic_network) to network,
        label(R.string.nearby_diagnostic_presence) to context.getString(R.string.nearby_diagnostic_presence_value, state.nearbyAppCount),
        label(R.string.nearby_diagnostic_connection) to context.getString(R.string.nearby_diagnostic_connection_value,
            state.peers.size, state.activeConnections, state.listeningPeers, state.connectionAttempts),
        label(R.string.nearby_diagnostic_discovery) to context.getString(R.string.nearby_diagnostic_discovery_value,
            nearbyTime(context, state.lastDiscoveryAt), state.discoveredPeers),
        label(R.string.nearby_diagnostic_attempt) to nearbyTime(context, state.lastAttemptAt),
        label(R.string.nearby_diagnostic_success) to nearbyTime(context, state.peers.maxOfOrNull { it.lastSyncedAt } ?: 0)
    )
}

private fun nearbyTechnicalDiagnostics(context: Context, state: DeviceSyncState): String = buildString {
    appendLine(context.getString(R.string.device_sync_build_diagnostic, BuildConfig.VERSION_NAME, BuildConfig.GIT_SHA,
        android.os.Build.VERSION.SDK_INT, android.os.Process.myUid() / 100000))
    appendLine(context.getString(R.string.device_sync_direction_diagnostic, state.mode.wireName))
    append(context.getString(R.string.device_sync_diagnostic_format,
        if (state.error != null) state.failureStage else state.phase, state.error ?: "-",
        if (state.error != null) state.failureKind.ifEmpty { "-" } else "-", state.connectionAttempts,
        if (state.error != null) state.failureOperation.ifEmpty { "-" } else "-"))
}

internal fun nearbyDiagnostics(context: Context, state: DeviceSyncState, database: String, network: String): String = buildString {
    appendLine(nearbyTechnicalDiagnostics(context, state))
    nearbyDiagnosticRows(context, state, database, network).forEach { (label, value) -> appendLine("$label: $value") }
    appendLine(nearbyNextStep(context, state))
    if (state.retryInSeconds > 0) appendLine(context.getString(R.string.nearby_diagnostic_retry, state.retryInSeconds))
    nearbyProgressText(context, state.progress).takeIf { it.isNotEmpty() }?.let { appendLine(it) }
}

@Composable
internal fun ColumnScope.NearbySyncDetails(state: DeviceSyncState, database: String, copy: (String) -> Unit,
    suppressPasskeyNotice: (Boolean) -> Unit = {}, refreshPasskeys: () -> Unit = {}, viewPasskeys: () -> Unit = {}) {
    val context = LocalContext.current
    val network by rememberNearbyNetwork()
    Text(stringResource(R.string.nearby_details), style = MaterialTheme.typography.headlineSmall)
    Text(nearbyStageLabel(context, state), style = MaterialTheme.typography.titleMedium)
    Text(nearbyNextStep(context, state))
    if (state.error != null) Text(stringResource(R.string.nearby_diagnostic_failure, state.error, state.failureOperation.ifEmpty { "-" }),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    if (state.failureRecordName.isNotEmpty() && state.error != null) Text(stringResource(R.string.device_sync_failed_record, state.failureRecordName))
    if (state.retryInSeconds > 0) Text(stringResource(R.string.nearby_diagnostic_retry, state.retryInSeconds))
    val diagnostics = nearbyDiagnostics(context, state, database, stringResource(network))
    Button({ copy(diagnostics) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.device_sync_copy_diagnostics)) }
    SettingsPanelGroup(stringResource(R.string.device_sync_passkey_title)) {
        SettingsPanelRow(Icons.Default.Info, Setting.NEARBY_PASSKEY_VIEW,
            stringResource(when {
                state.checkingPasskeys -> R.string.nearby_passkey_checking
                state.passkeyDiagnosticFailed -> R.string.nearby_passkey_check_failed
                state.passkeyDiagnosticIssues == null -> R.string.nearby_passkey_not_checked
                state.passkeyDiagnosticIssues.isEmpty() -> R.string.nearby_passkey_none
                else -> R.string.nearby_passkey_count
            }, state.passkeyDiagnosticIssues?.size ?: 0),
            enabled = !state.checkingPasskeys && !state.passkeyDiagnosticIssues.isNullOrEmpty(), onClick = viewPasskeys)
        SettingsPanelRow(Icons.Default.Info, Setting.NEARBY_PASSKEY_PROMPT,
            stringResource(if (state.passkeyNoticeSuppressed) R.string.nearby_passkey_prompt_off else R.string.nearby_passkey_prompt_on),
            checked = !state.passkeyNoticeSuppressed, onCheckedChange = { suppressPasskeyNotice(!it) })
    }
    Text(stringResource(R.string.nearby_passkey_rules), style = MaterialTheme.typography.bodySmall)
    OutlinedButton(refreshPasskeys, Modifier.fillMaxWidth(), enabled = !state.checkingPasskeys) {
        Text(stringResource(R.string.nearby_passkey_refresh))
    }
    SettingsPanelGroup("") {
        nearbyDiagnosticRows(context, state, database, stringResource(network)).forEach { (label, value) ->
            SettingsContentRow(Icons.Default.Info, label, value)
        }
    }
    Text(stringResource(R.string.nearby_network_note), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.device_sync_scope), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.nearby_diagnostic_technical), style = MaterialTheme.typography.titleSmall)
    Text(nearbyTechnicalDiagnostics(context, state), Modifier.testTag("nearby-diagnostic-text"), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.nearby_diagnostic_safe), style = MaterialTheme.typography.bodySmall)
}

package takagi.ru.monica.bitwarden.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.sync.*
import takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel
import takagi.ru.monica.data.bitwarden.BitwardenSyncQueueRecord
import takagi.ru.monica.security.SessionManager

/** Real local work and latest attempt evidence; an empty list is not a remote freshness check. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BitwardenSyncQueueScreen(viewModel: BitwardenViewModel, onNavigateBack: () -> Unit) {
    val vaults by viewModel.vaults.collectAsState()
    val snapshots by viewModel.syncQueueByVault.collectAsState()
    val statuses by viewModel.syncStatusByVault.collectAsState()
    val reports by viewModel.syncIssueReports.collectAsState()
    val unlocked by SessionManager.isUnlocked.collectAsState()
    val lockGeneration by SessionManager.lockGeneration.collectAsState()
    var issueVaultId by remember(lockGeneration, unlocked) { mutableStateOf<Long?>(null) }

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.legacy_ui_sync_queue)) },
            navigationIcon = { IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
            } })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("bitwarden_real_sync_queue"),
            contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item { Text(stringResource(R.string.bitwarden_queue_help),
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 12.dp)) }
            if (!unlocked) item { Text(stringResource(R.string.bitwarden_queue_locked)) }
            else if (vaults.isEmpty()) item { Text(stringResource(R.string.legacy_ui_bitwarden_disconnected)) }
            else vaults.forEach { vault ->
                val snapshot = snapshots[vault.id]
                val current = snapshot != null && viewModel.isSyncQueueCurrent(snapshot)
                val status = statuses[vault.id]
                val report = reports[vault.id].takeIf { current }
                val rows = snapshot?.items.orEmpty().takeIf { current }.orEmpty()
                val hasIssues = report != null && (report.items.isNotEmpty() || report.accountFailure != null)
                val summary = summarizeBitwardenQueue(listOfNotNull(snapshot).filter { current },
                    if (report == null) emptyMap() else mapOf(vault.id to report))
                item(key = "account:${vault.id}") {
                    BitwardenSectionTitle(vault.displayName?.takeIf { it.isNotBlank() } ?: vault.email)
                    Text(vault.email, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
                }
                if (!current) item(key = "locked:${vault.id}") {
                    BitwardenSettingRow(stringResource(R.string.bitwarden_queue_locked), Icons.Outlined.Lock,
                        modifier = Modifier.testTag("bitwarden_queue_locked_${vault.id}"))
                } else {
                    item(key = "status:${vault.id}") {
                        BitwardenSettingRow(queueStatusLabel(status), Icons.Outlined.Sync,
                            first = true, last = false,
                            supporting = listOf(stringResource(R.string.legacy_ui_pending_count, summary.pending),
                                stringResource(R.string.legacy_ui_failed_count, summary.failed)).joinToString(" · "),
                            modifier = Modifier.testTag("bitwarden_queue_status_${vault.id}"))
                    }
                    item(key = "retry:${vault.id}") {
                        BitwardenSettingRow(stringResource(R.string.legacy_ui_sync), Icons.Outlined.Refresh,
                            first = false, last = !hasIssues,
                            enabled = status?.isRunning != true && report?.running != true,
                            onClick = { if (viewModel.isSyncQueueCurrent(snapshot!!)) viewModel.requestManualSync(vault.id) },
                            modifier = Modifier.testTag("bitwarden_queue_retry_${vault.id}"))
                    }
                    if (hasIssues && report != null) {
                        item(key = "issues:${vault.id}") {
                            BitwardenSettingRow(stringResource(R.string.bitwarden_sync_issues_button, report.items.size),
                                Icons.Outlined.SyncProblem, first = false, last = true,
                                onClick = { if (viewModel.isSyncQueueCurrent(snapshot!!)) issueVaultId = vault.id },
                                modifier = Modifier.testTag("bitwarden_queue_issues_${vault.id}"))
                        }
                    }
                    item(key = "pending:${vault.id}") {
                        BitwardenSectionTitle(stringResource(R.string.legacy_ui_pending))
                        when {
                            snapshot!!.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                            snapshot.failed -> Text(stringResource(R.string.bitwarden_queue_loading_error))
                            rows.isEmpty() -> Text(stringResource(R.string.bitwarden_queue_empty_local),
                                modifier = Modifier.padding(16.dp))
                        }
                    }
                    itemsIndexed(rows, key = { _, row -> "${vault.id}:${row.queueKey}" }) { index, row ->
                        QueueRecordRow(row, first = index == 0, last = index == rows.lastIndex)
                    }
                }
                item(key = "gap:${vault.id}") { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
    val selected = issueVaultId?.let { id -> snapshots[id]?.takeIf(viewModel::isSyncQueueCurrent) }
    val selectedVault = selected?.let { s -> vaults.firstOrNull { it.id == s.vaultId } }
    val selectedReport = selected?.let { reports[it.vaultId] }
    if (selected != null && selectedVault != null && selectedReport != null && unlocked) {
        BitwardenSyncIssuesSheet(selectedVault.email, selectedReport,
            onDismiss = { issueVaultId = null }, onRetry = {
                if (viewModel.isSyncQueueCurrent(selected)) viewModel.requestManualSync(selected.vaultId)
            })
    }
}

@Composable
private fun QueueRecordRow(row: BitwardenSyncQueueRecord, first: Boolean, last: Boolean) {
    val kind = when (row.kind) {
        "ATTACHMENT" -> R.string.bitwarden_sync_issue_kind_attachment
        "SEND" -> R.string.bitwarden_sync_issue_kind_send
        "PASSKEY" -> R.string.bitwarden_sync_issue_kind_passkey
        "FOLDER" -> R.string.bitwarden_sync_issue_kind_folder
        else -> R.string.bitwarden_sync_issue_kind_cipher
    }
    val operation = when (row.operation) {
        "CREATE" -> R.string.bitwarden_sync_issue_phase_create
        "DELETE" -> R.string.bitwarden_sync_issue_phase_delete
        "RESTORE" -> R.string.bitwarden_sync_issue_phase_restore
        "CONFIRM" -> R.string.bitwarden_queue_awaiting_confirmation
        else -> R.string.bitwarden_sync_issue_phase_update
    }
    val state = when (row.status) {
        "FAILED" -> R.string.sync_status_failed_short
        "IN_PROGRESS" -> R.string.sync_status_syncing_short
        else -> R.string.sync_status_pending_short
    }
    BitwardenSettingRow(row.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.bitwarden_sync_issues_no_title),
        Icons.Outlined.CloudUpload, first = first, last = last,
        supporting = stringResource(kind) + " · " + stringResource(operation) + " · " + stringResource(state) +
            "\n" + stringResource(R.string.bitwarden_sync_issues_id, row.itemId),
        modifier = Modifier.testTag("bitwarden_queue_item_${row.queueKey}"))
}

@Composable
private fun queueStatusLabel(status: VaultSyncStatus?): String = stringResource(when {
    status?.isRunning == true -> R.string.sync_status_syncing_short
    status?.blockedReason != null -> when (status.blockedReason) {
        SyncBlockReason.AUTO_SYNC_DISABLED -> R.string.legacy_ui_auto_sync_disabled
        SyncBlockReason.NETWORK_UNAVAILABLE -> R.string.legacy_ui_network_unavailable
        SyncBlockReason.WIFI_REQUIRED -> R.string.legacy_ui_waiting_wifi
        SyncBlockReason.VAULT_LOCKED -> R.string.legacy_ui_vault_locked
        SyncBlockReason.AUTH_REQUIRED -> R.string.legacy_ui_reauthenticate
        null -> R.string.legacy_ui_sync_blocked
    }
    status?.queuedReason != null -> R.string.sync_status_pending_short
    status?.lastError != null -> R.string.sync_status_failed_short
    else -> R.string.bitwarden_queue_idle
})

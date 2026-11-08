package takagi.ru.monica.bitwarden.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.service.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.utils.ScreenshotProtectionUtil
import takagi.ru.monica.utils.screenshotActivity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BitwardenSyncIssuesSheet(
    account: String,
    report: BitwardenSyncIssueReport,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    val protection = remember(context.applicationContext) {
        SettingsManager(context.applicationContext).settingsFlow
            .map { it.screenshotProtectionEnabled }.distinctUntilChanged()
    }
    val protected by protection.collectAsState(initial = true)
    val activity = context.screenshotActivity()
    val forced = activity?.window?.let(ScreenshotProtectionUtil::hasForcedProtection) ?: true
    var query by remember { mutableStateOf("") }
    val items = report.items.filter {
        query.isBlank() || it.itemId.contains(query, ignoreCase = true) ||
            it.title?.contains(query, ignoreCase = true) == true
    }
    ModalBottomSheet(onDismissRequest = onDismiss,
        properties = ModalBottomSheetProperties(securePolicy =
            if (protected || forced) SecureFlagPolicy.SecureOn else SecureFlagPolicy.SecureOff),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().testTag("bitwarden_sync_issues"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(stringResource(R.string.bitwarden_sync_issues_title), style = MaterialTheme.typography.titleLarge)
                Text(account, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.bitwarden_sync_issues_help), style = MaterialTheme.typography.bodySmall)
            }
            item {
                if (report.running) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.bitwarden_sync_issues_running))
                } else {
                    FilledTonalButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.legacy_ui_sync))
                    }
                }
                report.accountFailure?.let {
                    Text(stringResource(it.label()), color = MaterialTheme.colorScheme.error)
                }
            }
            if (report.items.isNotEmpty()) item {
                OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.bitwarden_sync_issues_search)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth().testTag("bitwarden_sync_issues_search"))
            }
            if (items.isEmpty()) item {
                Text(stringResource(if (report.items.isEmpty()) R.string.bitwarden_sync_issues_empty
                    else R.string.bitwarden_sync_issues_no_matches))
            }
            items(items) { issue ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(issue.title ?: stringResource(R.string.bitwarden_sync_issues_no_title),
                            style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(issue.kind.label()) + " · " + stringResource(issue.phase.label()),
                            style = MaterialTheme.typography.labelLarge)
                        SelectionContainer {
                            Text(stringResource(R.string.bitwarden_sync_issues_id, issue.itemId),
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Text(stringResource(issue.reason.label()), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

private fun SyncIssueKind.label() = when (this) {
    SyncIssueKind.CIPHER -> R.string.bitwarden_sync_issue_kind_cipher
    SyncIssueKind.FOLDER -> R.string.bitwarden_sync_issue_kind_folder
    SyncIssueKind.SEND -> R.string.bitwarden_sync_issue_kind_send
    SyncIssueKind.ATTACHMENT -> R.string.bitwarden_sync_issue_kind_attachment
    SyncIssueKind.PASSKEY -> R.string.bitwarden_sync_issue_kind_passkey
}
private fun SyncIssuePhase.label() = when (this) {
    SyncIssuePhase.DOWNLOAD -> R.string.bitwarden_sync_issue_phase_download
    SyncIssuePhase.CREATE -> R.string.bitwarden_sync_issue_phase_create
    SyncIssuePhase.UPDATE -> R.string.bitwarden_sync_issue_phase_update
    SyncIssuePhase.DELETE -> R.string.bitwarden_sync_issue_phase_delete
    SyncIssuePhase.RESTORE -> R.string.bitwarden_sync_issue_phase_restore
}
private fun SyncIssueReason.label() = when (this) {
    SyncIssueReason.AUTHENTICATION -> R.string.bitwarden_sync_issue_reason_authentication
    SyncIssueReason.NETWORK -> R.string.bitwarden_sync_issue_reason_network
    SyncIssueReason.CONFLICT -> R.string.bitwarden_sync_issue_reason_conflict
    SyncIssueReason.UNSUPPORTED -> R.string.bitwarden_sync_issue_reason_unsupported
    SyncIssueReason.DECRYPTION -> R.string.bitwarden_sync_issue_reason_decryption
    SyncIssueReason.REJECTED -> R.string.bitwarden_sync_issue_reason_rejected
    SyncIssueReason.ATTACHMENT -> R.string.bitwarden_sync_issue_reason_attachment
    SyncIssueReason.PENDING_PARENT -> R.string.bitwarden_sync_issue_reason_pending_parent
}

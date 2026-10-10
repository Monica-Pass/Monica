package takagi.ru.monica.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.passkey.PasskeyMoveIssueReason
import takagi.ru.monica.passkey.PasskeyMoveReport

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PasskeyMoveResultDialog(report: PasskeyMoveReport, action: UnifiedMoveAction = UnifiedMoveAction.MOVE, onDismiss: () -> Unit) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * .85f).dp)
                    .testTag("passkey_move_result").padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(if (action == UnifiedMoveAction.COPY) R.string.passkey_copy_result_title else R.string.passkey_move_result_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(if (action == UnifiedMoveAction.COPY) R.string.passkey_copy_result_summary else R.string.passkey_move_result_summary, report.movedCount, report.issues.size),
                    style = MaterialTheme.typography.bodyMedium)
                LazyColumn(Modifier.weight(1f, fill = false).testTag("passkey_move_issues"),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    itemsIndexed(report.issues) { index, issue ->
                        Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            shape = RoundedCornerShape(
                                topStart = if (index == 0) 20.dp else 4.dp,
                                topEnd = if (index == 0) 20.dp else 4.dp,
                                bottomStart = if (index == report.issues.lastIndex) 20.dp else 4.dp,
                                bottomEnd = if (index == report.issues.lastIndex) 20.dp else 4.dp,
                            )) {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(issue.account.ifBlank { stringResource(R.string.passkey_move_unnamed_account) },
                                    style = MaterialTheme.typography.titleSmall)
                                if (issue.relyingParty.isNotBlank()) Text(issue.relyingParty,
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(stringResource(issue.reason.messageRes()), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                TextButton(onClick = onDismiss,
                    modifier = Modifier.align(androidx.compose.ui.Alignment.End).testTag("passkey_move_close")) {
                    Text(stringResource(R.string.close))
                }
            }
        }
    }
}

internal fun PasskeyMoveIssueReason.messageRes(): Int = when (this) {
    PasskeyMoveIssueReason.KEY_UNAVAILABLE -> R.string.passkey_transfer_key_unavailable
    PasskeyMoveIssueReason.TRANSFER_RESTRICTED -> R.string.passkey_transfer_restricted
    PasskeyMoveIssueReason.SAME_DATABASE_COPY -> R.string.passkey_transfer_same_database_copy
    PasskeyMoveIssueReason.BOUND_PASSWORD -> R.string.passkey_move_bound_reason
    PasskeyMoveIssueReason.REFERENCE_ONLY -> R.string.passkey_move_reference_reason
    PasskeyMoveIssueReason.BITWARDEN_UNSUPPORTED -> R.string.passkey_move_bitwarden_reason
    PasskeyMoveIssueReason.KEEPASS_CONFLICT -> R.string.passkey_move_conflict_reason
    PasskeyMoveIssueReason.UPDATE_FAILED -> R.string.passkey_move_update_reason
    PasskeyMoveIssueReason.SOURCE_CLEANUP_FAILED -> R.string.passkey_move_cleanup_reason
}

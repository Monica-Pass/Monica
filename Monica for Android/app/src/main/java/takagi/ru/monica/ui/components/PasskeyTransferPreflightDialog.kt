package takagi.ru.monica.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.passkey.PasskeyTransferPlan

@Composable
internal fun PasskeyTransferPreflightDialog(
    plan: PasskeyTransferPlan, action: UnifiedMoveAction, onCancel: () -> Unit, onSkip: () -> Unit
) {
    val copying = action == UnifiedMoveAction.COPY
    AlertDialog(
        modifier = Modifier.testTag("passkey_transfer_preflight"),
        onDismissRequest = onCancel,
        title = { Text(stringResource(if (copying) R.string.passkey_copy_blocked_title else R.string.passkey_move_blocked_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.passkey_transfer_preflight_summary, plan.issues.size, plan.eligible.size))
                LazyColumn(Modifier.heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(plan.issues) { issue ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(issue.account.ifBlank { issue.relyingParty }, style = MaterialTheme.typography.titleSmall)
                            if (issue.relyingParty.isNotBlank()) Text(issue.relyingParty, style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(issue.reason.messageRes()), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.testTag("passkey_transfer_cancel")) {
                Text(stringResource(if (copying) R.string.passkey_cancel_copy else R.string.passkey_cancel_move))
            }
        },
        confirmButton = {
            TextButton(onClick = onSkip, enabled = plan.eligible.isNotEmpty(), modifier = Modifier.testTag("passkey_transfer_skip")) {
                Text(stringResource(R.string.passkey_transfer_skip, plan.issues.size))
            }
        }
    )
}

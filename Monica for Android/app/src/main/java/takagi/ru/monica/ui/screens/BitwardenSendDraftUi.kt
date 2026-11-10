package takagi.ru.monica.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.service.BitwardenSendDraftStore
import takagi.ru.monica.bitwarden.service.SendAuthAction
import takagi.ru.monica.data.bitwarden.BitwardenSend
import takagi.ru.monica.ui.components.OutlinedTextField
import takagi.ru.monica.ui.components.TemplateFormSection

@Composable
internal fun SendAuthenticationFields(
    existing: BitwardenSend?, mode: SendAuthAction, password: String,
    recipients: List<String>, removePassword: Boolean, premium: Boolean, enabled: Boolean,
    onMode: (SendAuthAction) -> Unit, onPassword: (String) -> Unit,
    onRecipients: (List<String>) -> Unit, onRemovePassword: (Boolean) -> Unit,
) {
    TemplateFormSection(stringResource(R.string.bw_send_auth_title)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (existing?.hasPassword == true) {
                Text(stringResource(R.string.bw_send_auth_password_set))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.send_remove_password), Modifier.weight(1f))
                    Switch(removePassword, onCheckedChange = onRemovePassword, enabled = enabled,
                        modifier = Modifier.testTag("send_auth_remove_password"))
                }
            } else {
                val choices = buildList {
                    if (existing != null) add(SendAuthAction.KEEP to R.string.bw_send_auth_unchanged)
                    add(SendAuthAction.NONE to R.string.bw_send_auth_none)
                    add(SendAuthAction.PASSWORD to R.string.bw_send_auth_password)
                    add(SendAuthAction.EMAIL to R.string.bw_send_auth_email)
                }
                choices.forEach { (choice, label) ->
                    OutlinedButton(onClick = { onMode(choice) },
                        enabled = enabled && (choice != SendAuthAction.EMAIL || premium),
                        modifier = Modifier.fillMaxWidth().testTag("send_auth_${choice.name.lowercase()}")) {
                        RadioButton(selected = mode == choice, onClick = null,
                            enabled = enabled && (choice != SendAuthAction.EMAIL || premium))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(label), Modifier.weight(1f))
                    }
                }
                if (!premium) Text(stringResource(R.string.bw_send_auth_premium),
                    style = MaterialTheme.typography.bodySmall)
                if (mode == SendAuthAction.PASSWORD) {
                    OutlinedTextField(value = password, onValueChange = onPassword, enabled = enabled,
                        label = { Text(stringResource(R.string.send_new_password)) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().testTag("send_auth_password_value"))
                }
                if (mode == SendAuthAction.EMAIL) {
                    Text(stringResource(R.string.bw_send_auth_recipients_notice), style = MaterialTheme.typography.bodySmall)
                    recipients.forEachIndexed { index, email ->
                        OutlinedTextField(value = email, enabled = enabled,
                            onValueChange = { value -> onRecipients(recipients.toMutableList().also { it[index] = value }) },
                            label = { Text(stringResource(R.string.bw_send_auth_email_label, index + 1)) }, singleLine = true,
                            trailingIcon = {
                                IconButton(onClick = { onRecipients(recipients.filterIndexed { position, _ -> position != index }) },
                                    enabled = enabled && recipients.size > 1) {
                                    Icon(Icons.Default.Close, stringResource(R.string.bw_send_auth_remove_email))
                                }
                            }, modifier = Modifier.fillMaxWidth().testTag("send_auth_recipient_$index"))
                    }
                    TextButton(onClick = { onRecipients(recipients + "") }, enabled = enabled && recipients.size < 100) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.bw_send_auth_add_email))
                    }
                }
            }
        }
    }
}

@Composable
internal fun SendLocalDraftList(
    drafts: List<BitwardenSendDraftStore.Draft>, unavailable: Boolean, busy: Boolean,
    onResume: (BitwardenSendDraftStore.Draft) -> Unit,
    onDiscard: (BitwardenSendDraftStore.Draft) -> Unit,
    canDiscard: (BitwardenSendDraftStore.Draft) -> Boolean = { true },
) {
    var discard by remember { mutableStateOf<BitwardenSendDraftStore.Draft?>(null) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.bw_send_draft_local), style = MaterialTheme.typography.bodySmall)
        if (unavailable) Text(stringResource(R.string.bw_send_draft_unavailable))
        if (drafts.isEmpty()) Text(stringResource(R.string.bw_send_draft_empty))
        drafts.forEach { saved ->
            TemplateFormSection(saved.draft.title.ifBlank { stringResource(R.string.untitled) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (saved.submissionUncertain) Text(stringResource(R.string.bw_send_draft_uncertain))
                    saved.file?.let { Text(it.name, style = MaterialTheme.typography.bodySmall) }
                    Button(onClick = { onResume(saved) }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().testTag("send_draft_resume_${saved.id}")) {
                        Text(stringResource(if (saved.submissionUncertain) R.string.bw_send_draft_reconcile else R.string.bw_send_draft_resume))
                    }
                    OutlinedButton(onClick = { discard = saved }, enabled = !busy && canDiscard(saved),
                        modifier = Modifier.fillMaxWidth().testTag("send_draft_discard_${saved.id}")) {
                        Text(stringResource(R.string.bw_send_draft_discard))
                    }
                }
            }
        }
    }
    discard?.let { selected ->
        AlertDialog(onDismissRequest = { discard = null },
            title = { Text(stringResource(R.string.bw_send_draft_discard)) },
            text = { Text(stringResource(R.string.bw_send_draft_discard_confirm)) },
            dismissButton = { TextButton(onClick = { discard = null }) { Text(stringResource(R.string.cancel)) } },
            confirmButton = { TextButton(onClick = { discard = null; if (canDiscard(selected)) onDiscard(selected) }, enabled = !busy && canDiscard(selected)) {
                Text(stringResource(R.string.bw_send_draft_discard))
            } })
    }
}

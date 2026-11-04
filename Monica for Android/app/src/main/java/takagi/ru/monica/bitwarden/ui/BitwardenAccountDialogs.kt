package takagi.ru.monica.bitwarden.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.launch
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.api.BitwardenTlsConfig
import takagi.ru.monica.bitwarden.service.BitwardenRegistrationTicket
import takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel
import takagi.ru.monica.ui.components.OutlinedTextField

@Composable
internal fun BitwardenRegistrationDialog(
    viewModel: BitwardenViewModel,
    serverUrl: String,
    initialEmail: String,
    tlsConfig: BitwardenTlsConfig?,
    onRegistered: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf(initialEmail) }
    var name by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf("") }
    var ticket by remember { mutableStateOf<BitwardenRegistrationTicket?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var complete by remember { mutableStateOf(false) }
    val defaultError = stringResource(R.string.bitwarden_account_operation_failed)

    fun submit() {
        if (busy) return
        scope.launch {
            busy = true
            error = null
            try {
                val current = ticket
                when {
                    current == null -> viewModel.startAccountRegistration(serverUrl, email, name, tlsConfig)
                        .onSuccess { ticket = it; email = it.email }.onFailure { error = it.message ?: defaultError }
                    !current.readyForPassword -> viewModel.verifyAccountRegistration(current, token)
                        .onSuccess { ticket = it; token = "" }.onFailure { error = it.message ?: defaultError }
                    else -> viewModel.finishAccountRegistration(current, password, confirmation, hint)
                        .onSuccess { complete = true; password = ""; confirmation = ""; hint = ""; token = ""; ticket = null }
                        .onFailure { error = it.message ?: defaultError }
                }
            } finally { busy = false }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("bitwarden_registration_dialog"),
        title = { Text(stringResource(R.string.bitwarden_account_create)) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(serverUrl, style = MaterialTheme.typography.bodySmall)
                if (complete) {
                    Text(stringResource(R.string.bitwarden_account_registration_success))
                } else {
                    OutlinedTextField(value = email, onValueChange = { email = it }, enabled = !busy && ticket == null,
                        label = { Text(stringResource(R.string.email)) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("bitwarden_registration_email"))
                    if (ticket == null) {
                        OutlinedTextField(value = name, onValueChange = { name = it }, enabled = !busy,
                            label = { Text(stringResource(R.string.name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    } else if (ticket?.readyForPassword != true) {
                        Text(stringResource(R.string.bitwarden_account_verification_pending))
                        OutlinedTextField(value = token, onValueChange = { token = it }, enabled = !busy,
                            label = { Text(stringResource(R.string.bitwarden_account_verification_input)) },
                            modifier = Modifier.fillMaxWidth().testTag("bitwarden_registration_token"))
                    } else {
                        Text(stringResource(R.string.bitwarden_account_password_requirements), style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(value = password, onValueChange = { password = it }, enabled = !busy,
                            label = { Text(stringResource(R.string.master_password)) }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth().testTag("bitwarden_registration_password"))
                        OutlinedTextField(value = confirmation, onValueChange = { confirmation = it }, enabled = !busy,
                            label = { Text(stringResource(R.string.confirm_master_password)) }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth().testTag("bitwarden_registration_confirmation"))
                        OutlinedTextField(value = hint, onValueChange = { hint = it }, enabled = !busy,
                            label = { Text(stringResource(R.string.bitwarden_account_password_hint)) },
                            modifier = Modifier.fillMaxWidth())
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            Button(onClick = { if (complete) onRegistered(email) else submit() }, enabled = !busy,
                modifier = Modifier.testTag("bitwarden_registration_submit")) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(stringResource(when {
                    complete -> R.string.confirm
                    ticket == null -> R.string.bitwarden_account_send_verification
                    ticket?.readyForPassword != true -> R.string.legacy_ui_verify
                    else -> R.string.bitwarden_account_create
                }))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
internal fun BitwardenPasswordHintDialog(
    viewModel: BitwardenViewModel,
    serverUrl: String,
    initialEmail: String,
    tlsConfig: BitwardenTlsConfig?,
    onDismiss: () -> Unit,
) {
    var email by remember { mutableStateOf(initialEmail) }
    var busy by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val defaultError = stringResource(R.string.bitwarden_account_operation_failed)
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("bitwarden_password_hint_dialog"),
        title = { Text(stringResource(R.string.bitwarden_account_request_hint)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(serverUrl, style = MaterialTheme.typography.bodySmall)
                if (sent) Text(stringResource(R.string.bitwarden_account_hint_sent))
                else OutlinedTextField(value = email, onValueChange = { email = it }, enabled = !busy,
                    label = { Text(stringResource(R.string.email)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("bitwarden_password_hint_email"))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (sent) onDismiss() else scope.launch {
                    busy = true; error = null
                    try { viewModel.requestAccountPasswordHint(serverUrl, email, tlsConfig)
                        .onSuccess { sent = true }.onFailure { error = it.message ?: defaultError } }
                    finally { busy = false }
                }
            }, enabled = !busy && email.isNotBlank(), modifier = Modifier.testTag("bitwarden_password_hint_submit")) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(stringResource(if (sent) R.string.confirm else R.string.bitwarden_account_request_hint))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

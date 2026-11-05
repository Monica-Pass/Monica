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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.UUID
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.api.BitwardenEnvironment
import takagi.ru.monica.bitwarden.api.BitwardenEnvironmentDraft
import takagi.ru.monica.bitwarden.api.BitwardenEnvironmentResolver
import takagi.ru.monica.bitwarden.service.BitwardenRegistrationTicket
import takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel
import takagi.ru.monica.ui.components.OutlinedTextField

@Composable
internal fun BitwardenRegistrationDialog(
    viewModel: BitwardenViewModel,
    environmentDraft: BitwardenEnvironmentDraft,
    initialEmail: String,
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

    val operation = remember(viewModel, environmentDraft) { AccountDialogOperation(viewModel::retireAccountOperationEnvironment) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun clearProof() { token = ""; password = ""; confirmation = ""; hint = ""; ticket = null; busy = false }
    fun restart() { operation.reset(); clearProof(); error = null }
    val dismiss = accountDialogLifetime(operation, onDismiss,
        onPause = { token = ""; password = ""; confirmation = ""; hint = ""; busy = false; error = null }) {
        clearProof(); email = ""; name = ""
    }
    val currentRegistered by rememberUpdatedState(onRegistered)

    fun submit() {
        if (busy || !operation.current(operation.revision, lifecycle)) return
        val revision = operation.revision
        val capturedEmail = email
        val capturedName = name
        val capturedToken = token
        val capturedPassword = password
        val capturedConfirmation = confirmation
        val capturedHint = hint
        val currentTicket = ticket
        busy = true
        error = null
        operation.job = scope.launch {
            try {
                val environment = operation.environment ?: withContext(Dispatchers.IO) {
                    BitwardenEnvironmentResolver.resolve(environmentDraft, UUID.randomUUID().toString()).value
                }
                if (!operation.current(revision, lifecycle)) return@launch
                if (environment == null) { error = defaultError; return@launch }
                operation.environment = environment
                when {
                    currentTicket == null -> {
                        val result = viewModel.startAccountRegistration(environment, capturedEmail, capturedName)
                        if (operation.current(revision, lifecycle)) result
                            .onSuccess { ticket = it; email = it.email }.onFailure { error = it.message ?: defaultError }
                    }
                    !currentTicket.readyForPassword -> {
                        val result = viewModel.verifyAccountRegistration(currentTicket, capturedToken)
                        if (operation.current(revision, lifecycle)) result
                            .onSuccess { ticket = it; token = "" }.onFailure { error = it.message ?: defaultError }
                    }
                    else -> {
                        val result = viewModel.finishAccountRegistration(currentTicket, capturedPassword, capturedConfirmation, capturedHint)
                        if (operation.current(revision, lifecycle)) result.onSuccess {
                            operation.job = null
                            operation.reset()
                            clearProof()
                            complete = true
                        }.onFailure { error = it.message ?: defaultError }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (operation.current(revision, lifecycle)) error = defaultError }
            finally { if (operation.revision == revision) busy = false }
        }
    }

    AlertDialog(
        onDismissRequest = dismiss,
        modifier = Modifier.testTag("bitwarden_registration_dialog"),
        title = { Text(stringResource(R.string.bitwarden_account_create)) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(accountEnvironmentBase(environmentDraft), style = MaterialTheme.typography.bodySmall)
                if (complete) {
                    Text(stringResource(R.string.bitwarden_account_registration_success))
                } else {
                    OutlinedTextField(value = email, onValueChange = { restart(); email = it }, saveTextState = false,
                        label = { Text(stringResource(R.string.email)) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("bitwarden_registration_email"))
                    if (ticket == null) {
                        OutlinedTextField(value = name, onValueChange = { restart(); name = it }, enabled = !busy, saveTextState = false,
                            label = { Text(stringResource(R.string.name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    } else if (ticket?.readyForPassword != true) {
                        Text(stringResource(R.string.bitwarden_account_verification_pending))
                        OutlinedTextField(saveTextState = false, value = token, onValueChange = { token = it }, enabled = !busy,
                            label = { Text(stringResource(R.string.bitwarden_account_verification_input)) },
                            modifier = Modifier.fillMaxWidth().testTag("bitwarden_registration_token"))
                    } else {
                        Text(stringResource(R.string.bitwarden_account_password_requirements), style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(saveTextState = false, value = password, onValueChange = { password = it }, enabled = !busy,
                            label = { Text(stringResource(R.string.master_password)) }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth().testTag("bitwarden_registration_password"))
                        OutlinedTextField(saveTextState = false, value = confirmation, onValueChange = { confirmation = it }, enabled = !busy,
                            label = { Text(stringResource(R.string.confirm_master_password)) }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth().testTag("bitwarden_registration_confirmation"))
                        OutlinedTextField(saveTextState = false, value = hint, onValueChange = { hint = it }, enabled = !busy,
                            label = { Text(stringResource(R.string.bitwarden_account_password_hint)) },
                            modifier = Modifier.fillMaxWidth())
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            Button(onClick = { if (operation.current(operation.revision, lifecycle)) { if (complete) {
                val registeredEmail = email
                operation.dispose(); clearProof(); email = ""; name = ""
                currentRegistered(registeredEmail)
            } else submit() } }, enabled = !busy,
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
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
internal fun BitwardenPasswordHintDialog(
    viewModel: BitwardenViewModel,
    environmentDraft: BitwardenEnvironmentDraft,
    initialEmail: String,
    onDismiss: () -> Unit,
) {
    var email by remember { mutableStateOf(initialEmail) }
    var busy by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val defaultError = stringResource(R.string.bitwarden_account_operation_failed)
    val operation = remember(viewModel, environmentDraft) { AccountDialogOperation(viewModel::retireAccountOperationEnvironment) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val dismiss = accountDialogLifetime(operation, onDismiss, onPause = { busy = false; error = null }) {
        email = ""; busy = false; sent = false; error = null
    }
    fun submitHint() {
        if (busy || !operation.current(operation.revision, lifecycle)) return
        operation.reset()
        val revision = operation.revision
        val capturedEmail = email
        busy = true; error = null
        operation.job = scope.launch {
            try {
                val environment = withContext(Dispatchers.IO) {
                    BitwardenEnvironmentResolver.resolve(environmentDraft, UUID.randomUUID().toString()).value
                }
                if (!operation.current(revision, lifecycle)) return@launch
                if (environment == null) { error = defaultError; return@launch }
                operation.environment = environment
                val result = viewModel.requestAccountPasswordHint(environment, capturedEmail)
                if (operation.current(revision, lifecycle)) result.onSuccess {
                    operation.job = null; operation.reset(); busy = false; sent = true
                }.onFailure { error = it.message ?: defaultError }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (operation.current(revision, lifecycle)) error = defaultError }
            finally { if (operation.revision == revision) busy = false }
        }
    }
    AlertDialog(onDismissRequest = dismiss, modifier = Modifier.testTag("bitwarden_password_hint_dialog"),
        title = { Text(stringResource(R.string.bitwarden_account_request_hint)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(accountEnvironmentBase(environmentDraft), style = MaterialTheme.typography.bodySmall)
                if (sent) Text(stringResource(R.string.bitwarden_account_hint_sent))
                else OutlinedTextField(value = email, onValueChange = { operation.reset(); busy = false; error = null; email = it }, saveTextState = false,
                    label = { Text(stringResource(R.string.email)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("bitwarden_password_hint_email"))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (sent) dismiss() else submitHint()
            }, enabled = !busy && email.isNotBlank(), modifier = Modifier.testTag("bitwarden_password_hint_submit")) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(stringResource(if (sent) R.string.confirm else R.string.bitwarden_account_request_hint))
            }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** One immutable environment per operation; revocation also closes its owned transports. */
private class AccountDialogOperation(private val retire: (BitwardenEnvironment) -> Unit) {
    var active = true
    var revision = 0L
    val appGeneration = SessionManager.lockGeneration.value
    var environment: BitwardenEnvironment? = null
    var job: Job? = null
    fun current(captured: Long, lifecycle: Lifecycle) = active && revision == captured &&
        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && SessionManager.isUnlocked.value &&
        SessionManager.lockGeneration.value == appGeneration
    fun pause() { revision++; job?.cancel(); job = null }
    fun reset() { pause(); environment?.let(retire); environment = null }
    fun dispose() { active = false; reset() }
    override fun toString() = "AccountDialogOperation(redacted)"
}

@Composable
private fun accountDialogLifetime(operation: AccountDialogOperation, onDismiss: () -> Unit,
    onPause: () -> Unit, clear: () -> Unit): () -> Unit {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentDismiss by rememberUpdatedState(onDismiss)
    val currentClear by rememberUpdatedState(clear)
    val currentPause by rememberUpdatedState(onPause)
    val appGeneration by SessionManager.lockGeneration.collectAsState()
    val dismiss = { if (operation.active) { operation.dispose(); currentClear(); currentDismiss() } }
    DisposableEffect(operation, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && operation.active) { operation.pause(); currentPause() }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); operation.dispose(); currentClear() }
    }
    LaunchedEffect(appGeneration) { if (appGeneration != operation.appGeneration) dismiss() }
    return dismiss
}

private fun accountEnvironmentBase(draft: BitwardenEnvironmentDraft): String = when (draft.preset) {
    takagi.ru.monica.bitwarden.api.BitwardenEnvironmentPreset.US -> "https://vault.bitwarden.com/"
    takagi.ru.monica.bitwarden.api.BitwardenEnvironmentPreset.EU -> "https://vault.bitwarden.eu/"
    takagi.ru.monica.bitwarden.api.BitwardenEnvironmentPreset.CUSTOM -> draft.baseUrl
}

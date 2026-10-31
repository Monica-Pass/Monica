package takagi.ru.monica.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import takagi.ru.monica.R
import takagi.ru.monica.ui.components.SettingsSubpageTopBar

/** No vault repositories are opened until recovery has been verified by the caller. */
@Composable
internal fun SecureStorageRecoveryScreen(
    onRetry: () -> Unit,
    onCopyDiagnostic: () -> Unit,
    onExit: () -> Unit,
    onRecover: (suspend (String) -> Boolean)? = null
) {
    var password by remember { mutableStateOf("") }
    var confirmationPassword by remember { mutableStateOf<String?>(null) }
    var recovering by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var recoveryJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                password = ""
                confirmationPassword = null
                recoveryJob?.cancel()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            password = ""
            confirmationPassword = null
            recoveryJob?.cancel()
        }
    }
    if (confirmationPassword != null && onRecover != null) {
        AlertDialog(
            onDismissRequest = { confirmationPassword = null },
            title = { Text(stringResource(R.string.local_recovery_confirm_title)) },
            text = { Text(stringResource(R.string.local_recovery_confirm_description)) },
            confirmButton = {
                TextButton(onClick = confirm@{
                    val attempt = confirmationPassword ?: return@confirm
                    if (recovering) return@confirm
                    confirmationPassword = null
                    recovering = true
                    failed = false
                    recoveryJob = scope.launch {
                        try { failed = !onRecover(attempt) }
                        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { failed = true }
                        finally { recovering = false; recoveryJob = null }
                    }
                }, modifier = Modifier.testTag("secure_startup_recovery_confirm")) {
                    Text(stringResource(R.string.local_recovery_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmationPassword = null },
                    modifier = Modifier.testTag("secure_startup_recovery_cancel")) {
                    Text(stringResource(R.string.cancel))
                }
            },
            properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)
        )
    }
    Scaffold(topBar = { SettingsSubpageTopBar(stringResource(R.string.secure_startup_title), onExit) },
        bottomBar = {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (onRecover != null) {
                    Button(onClick = request@{
                        if (recovering || confirmationPassword != null || password.isEmpty()) return@request
                        confirmationPassword = password
                        password = ""
                        focusManager.clearFocus()
                        keyboard?.hide()
                        failed = false
                    }, enabled = password.isNotEmpty() && !recovering && confirmationPassword == null,
                        modifier = Modifier.fillMaxWidth().testTag("secure_startup_recover")) {
                        if (recovering) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(stringResource(R.string.local_recovery_action))
                    }
                }
                Button(onClick = onRetry, enabled = !recovering, modifier = Modifier.fillMaxWidth().testTag("secure_startup_retry")) {
                    Text(stringResource(R.string.secure_startup_retry))
                }
                FilledTonalButton(onClick = onCopyDiagnostic, modifier = Modifier.fillMaxWidth().testTag("secure_startup_diagnostic")) {
                    Text(stringResource(R.string.secure_startup_diagnostic))
                }
            }
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp).padding(bottom = 16.dp).testTag("secure_startup_blocked"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = settingsSectionItemShape(0, 1), color = MaterialTheme.colorScheme.secondaryContainer) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(stringResource(R.string.secure_startup_preserved), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.secure_startup_description), modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (onRecover != null) {
                Text(stringResource(R.string.local_recovery_description), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(value = password, onValueChange = { password = it; failed = false },
                    label = { Text(stringResource(R.string.local_recovery_password)) },
                    modifier = Modifier.fillMaxWidth().testTag("secure_startup_password"),
                    enabled = !recovering, singleLine = true, isError = failed,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    shape = MaterialTheme.shapes.large)
                if (failed) Text(stringResource(R.string.local_recovery_failed),
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("secure_startup_recovery_error"))
                Text(stringResource(R.string.local_recovery_limits), style = MaterialTheme.typography.bodySmall)
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                listOf(R.string.secure_startup_restart, R.string.secure_startup_environment, R.string.secure_startup_no_reset)
                    .forEachIndexed { index, text ->
                        Surface(shape = settingsSectionItemShape(index, 3), color = MaterialTheme.colorScheme.surfaceContainer) {
                            Text(stringResource(text), modifier = Modifier.fillMaxWidth().padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
            }
        }
    }
}

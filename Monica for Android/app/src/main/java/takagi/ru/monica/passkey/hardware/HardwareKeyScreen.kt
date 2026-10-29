package takagi.ru.monica.passkey.hardware

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R

data class HardwareKeyUiState(
    val info: DeviceInfo? = null, val transport: String = "", val busy: Boolean = false,
    val error: Int? = null, val status: Int? = null, val request: HardwareWebAuthn? = null,
    val providerMode: Boolean = false, val pin: String = "", val pinDialog: Boolean = false,
    val currentPin: String = "", val newPin: String = "", val confirmPin: String = "",
    val credentials: HardwareCredentials.Snapshot? = null,
    val assertions: List<FidoCommands.GetAssertionResponse> = emptyList()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HardwareKeyScreen(
    state: HardwareKeyUiState, onBack: () -> Unit, onConnectUsb: () -> Unit, onDisconnect: () -> Unit,
    onPin: (String) -> Unit, onPinDialog: (Boolean) -> Unit, onPinFields: (String, String, String) -> Unit,
    onSavePin: () -> Unit, onReadCredentials: () -> Unit, onPerform: () -> Unit,
    onChooseAssertion: (FidoCommands.GetAssertionResponse) -> Unit
) {
    Scaffold(bottomBar = {
        if (state.providerMode) Surface(color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Button(onClick = onPerform, enabled = !state.busy && state.request != null && state.info != null &&
                    (!state.info.clientPinSet || state.pin.isNotEmpty()), modifier = Modifier.fillMaxWidth().testTag("hardware-perform")) {
                    Text(stringResource(if (state.request?.create == true) R.string.hardware_key_create else R.string.hardware_key_authenticate))
                }
                TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.hardware_key_cancel)) }
            }
        }
    }, topBar = {
        TopAppBar(title = { Text(stringResource(R.string.hardware_key_title)) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.hardware_key_back)) }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.request?.let { request ->
                KeyGroup {
                    ListItem(headlineContent = { Text(request.rpId) }, supportingContent = { Text(request.userName) }, leadingContent = { Icon(Icons.Default.Language, null) })
                    Text(stringResource(if (request.create) R.string.hardware_key_create_note else R.string.hardware_key_get_note), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            KeyGroup {
                ListItem(headlineContent = { Text(stringResource(if (state.info == null) R.string.hardware_key_connect else R.string.hardware_key_connected)) },
                    supportingContent = { Text(if (state.info == null) stringResource(R.string.hardware_key_connect_hint) else state.transport.uppercase()) },
                    leadingContent = { Icon(Icons.Default.Usb, null) })
                Row(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = onConnectUsb, enabled = !state.busy) { Text(stringResource(R.string.hardware_key_find_usb)) }
                    if (state.info != null || state.busy) TextButton(onClick = onDisconnect) { Text(stringResource(R.string.hardware_key_disconnect)) }
                }
                if (state.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                    Text(stringResource(R.string.hardware_key_wait), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            state.error?.let { Text(stringResource(it), Modifier.testTag("hardware-error").padding(12.dp), color = MaterialTheme.colorScheme.error) }
            state.status?.let { Text(stringResource(it), Modifier.padding(12.dp), color = MaterialTheme.colorScheme.primary) }
            state.info?.let { info ->
                KeyGroup {
                    InfoRow(R.string.hardware_key_protocols, info.versions.joinToString(", "))
                    InfoRow(R.string.hardware_key_aaguid, info.aaguid?.toString().orEmpty())
                    info.firmwareVersion?.let { InfoRow(R.string.hardware_key_firmware, it.toString()) }
                    InfoRow(R.string.hardware_key_pin_status, stringResource(when (info.options["clientPin"]) { true -> R.string.hardware_key_pin_set; false -> R.string.hardware_key_pin_unset; else -> R.string.hardware_key_unsupported }))
                    InfoRow(R.string.hardware_key_resident, stringResource(if (info.options["rk"] == true) R.string.hardware_key_supported else R.string.hardware_key_unsupported))
                    InfoRow(R.string.hardware_key_uv, stringResource(if (info.supportsBuiltInUv) R.string.hardware_key_supported else R.string.hardware_key_unsupported))
                }
                if (info.clientPinSet && (state.providerMode || info.supportsCredMgmt || info.supportsCredMgmtPreview)) {
                    OutlinedTextField(value = state.pin, onValueChange = onPin, label = { Text(stringResource(R.string.hardware_key_pin)) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().testTag("hardware-pin"))
                }
                if (info.options.containsKey("clientPin") && info.pinUvAuthProtocols.any { it in 1..2 }) {
                    FilledTonalButton(onClick = { onPinDialog(true) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(if (info.clientPinSet) R.string.hardware_key_change_pin else R.string.hardware_key_set_pin))
                    }
                }
                if (!state.providerMode && (info.supportsCredMgmt || info.supportsCredMgmtPreview)) {
                    OutlinedButton(onClick = onReadCredentials, enabled = !state.busy && state.pin.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.hardware_key_read_credentials))
                    }
                }
            }
            if (!state.providerMode) KeyGroup {
                ListItem(headlineContent = { Text(stringResource(R.string.hardware_key_create)) }, leadingContent = { Icon(Icons.Default.Key, null) },
                    supportingContent = { Text(stringResource(R.string.hardware_key_instructions)) })
                Text(stringResource(R.string.hardware_key_no_import), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
            }
            state.credentials?.let { snapshot ->
                Text(stringResource(R.string.hardware_key_count, snapshot.existing, snapshot.remaining), style = MaterialTheme.typography.titleMedium)
                KeyGroup { snapshot.accounts.forEach { account -> ListItem(headlineContent = { Text(account.rpId) }, supportingContent = { Text(account.userName) }, leadingContent = { Icon(Icons.Default.Key, null) }) } }
            }
            if (state.assertions.isNotEmpty()) {
                Text(stringResource(R.string.hardware_key_choose_account), style = MaterialTheme.typography.titleMedium)
                state.assertions.forEach { assertion ->
                    OutlinedButton(onClick = { onChooseAssertion(assertion) }, modifier = Modifier.fillMaxWidth()) {
                        Text(assertion.user?.displayName ?: assertion.user?.name ?: stringResource(R.string.hardware_key_unknown_account))
                    }
                }
            }
        }
    }
    if (state.pinDialog) AlertDialog(onDismissRequest = { if (!state.busy) onPinDialog(false) }, title = {
        Text(stringResource(if (state.info?.clientPinSet == true) R.string.hardware_key_change_pin else R.string.hardware_key_set_pin))
    }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.hardware_key_pin_policy, maxOf(4, state.info?.minPinLength ?: 4)))
            if (state.info?.clientPinSet == true) PinField(state.currentPin, R.string.hardware_key_current_pin, state.busy) { onPinFields(it, state.newPin, state.confirmPin) }
            PinField(state.newPin, R.string.hardware_key_new_pin, state.busy) { onPinFields(state.currentPin, it, state.confirmPin) }
            PinField(state.confirmPin, R.string.hardware_key_confirm_pin, state.busy) { onPinFields(state.currentPin, state.newPin, it) }
            state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = onSavePin, enabled = !state.busy && state.newPin.isNotEmpty() && state.newPin == state.confirmPin &&
        (state.info?.clientPinSet != true || state.currentPin.isNotEmpty())) { Text(stringResource(R.string.hardware_key_save_pin)) } },
        dismissButton = { TextButton(onClick = { onPinDialog(false) }, enabled = !state.busy) { Text(stringResource(R.string.hardware_key_cancel)) } })
}

@Composable
private fun KeyGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(content = content)
    }
}
@Composable
private fun InfoRow(label: Int, value: String) { ListItem(headlineContent = { Text(stringResource(label), style = MaterialTheme.typography.labelLarge) }, supportingContent = { Text(value) }) }
@Composable
private fun PinField(value: String, label: Int, busy: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(stringResource(label)) }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), enabled = !busy, modifier = Modifier.fillMaxWidth())
}

package takagi.ru.monica.passkey.hardware

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    var showRegistrationHelp by remember { mutableStateOf(false) }
    Scaffold(bottomBar = {
        if (state.providerMode) Surface(color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Button(onClick = onPerform, enabled = !state.busy && state.request != null && state.info != null &&
                    (!state.info.clientPinSet || state.pin.isNotEmpty()), modifier = Modifier.fillMaxWidth().testTag("hardware-perform")) {
                    Text(stringResource(if (state.request?.create == true) R.string.hardware_key_create else R.string.hardware_key_authenticate))
                }
                TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.hardware_key_cancel)) }
            }
        }
    }, topBar = {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Default.Key, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.hardware_key_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(if (state.info != null) stringResource(R.string.hardware_key_connected_via, state.transport.uppercase())
                    else stringResource(R.string.hardware_key_connect_short), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.info != null) IconButton(onClick = onDisconnect) {
                Icon(Icons.Default.LinkOff, stringResource(R.string.hardware_key_disconnect))
            }
            IconButton(onClick = onBack) { Icon(Icons.Default.Close, stringResource(R.string.hardware_key_back)) }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.request?.let { request ->
                KeyGroup {
                    SectionTitle(if (request.create) R.string.hardware_key_create else R.string.hardware_key_authenticate, Icons.Default.Language)
                    Text(request.rpId, style = MaterialTheme.typography.titleMedium)
                    Text(request.userName, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(if (request.create) R.string.hardware_key_create_note else R.string.hardware_key_get_note), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (state.info == null || state.busy) KeyGroup {
                SectionTitle(R.string.hardware_key_connect, Icons.Default.Usb)
                Text(stringResource(R.string.hardware_key_connect_hint), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onConnectUsb, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Usb, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.hardware_key_find_usb))
                    }
                    if (state.busy) TextButton(onClick = onDisconnect) { Text(stringResource(R.string.hardware_key_disconnect)) }
                }
                if (state.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.hardware_key_wait), style = MaterialTheme.typography.bodyMedium)
                }
            }
            state.error?.let { Text(stringResource(it), Modifier.testTag("hardware-error").padding(12.dp), color = MaterialTheme.colorScheme.error) }
            state.status?.let { Text(stringResource(it), Modifier.padding(12.dp), color = MaterialTheme.colorScheme.primary) }
            state.info?.let { info ->
                KeyGroup(Modifier.testTag("hardware-info-card")) {
                    SectionTitle(R.string.hardware_key_information, Icons.Default.Info)
                    InfoRow(R.string.hardware_key_aaguid, info.aaguid?.toString().orEmpty(), monospace = true)
                    InfoRow(R.string.hardware_key_protocols, info.versions.joinToString(", "))
                    info.firmwareVersion?.let { InfoRow(R.string.hardware_key_firmware, it.toString()) }
                    InfoRow(R.string.hardware_key_pin_status, stringResource(when (info.options["clientPin"]) { true -> R.string.hardware_key_pin_set; false -> R.string.hardware_key_pin_unset; else -> R.string.hardware_key_unsupported }))
                    InfoRow(R.string.hardware_key_resident, stringResource(if (info.options["rk"] == true) R.string.hardware_key_supported else R.string.hardware_key_unsupported))
                    InfoRow(R.string.hardware_key_uv, stringResource(if (info.supportsBuiltInUv) R.string.hardware_key_supported else R.string.hardware_key_unsupported))
                }
                val canManagePin = info.options.containsKey("clientPin") && info.pinUvAuthProtocols.any { it in 1..2 }
                val needsPin = info.clientPinSet && (state.providerMode || info.supportsCredMgmt || info.supportsCredMgmtPreview)
                if (canManagePin || needsPin) KeyGroup(Modifier.testTag("hardware-pin-card")) {
                    SectionTitle(R.string.hardware_key_pin_section, Icons.Default.Lock)
                    Text(stringResource(if (info.clientPinSet) R.string.hardware_key_pin_ready_hint else R.string.hardware_key_pin_setup_hint),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (needsPin) {
                        OutlinedTextField(value = state.pin, onValueChange = onPin, label = { Text(stringResource(R.string.hardware_key_pin)) }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth().testTag("hardware-pin"))
                    }
                    if (canManagePin) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onPinDialog(true) }, enabled = !state.busy && !info.clientPinSet, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Lock, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.hardware_key_set_pin))
                        }
                        FilledTonalButton(onClick = { onPinDialog(true) }, enabled = !state.busy && info.clientPinSet, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.hardware_key_change_pin))
                        }
                    }
                }
                if (!state.providerMode && (info.supportsCredMgmt || info.supportsCredMgmtPreview)) KeyGroup {
                    SectionTitle(R.string.hardware_key_credentials_section, Icons.Default.Key)
                    OutlinedButton(onClick = onReadCredentials, enabled = !state.busy && state.pin.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.hardware_key_read_credentials))
                    }
                }
            }
            if (!state.providerMode) KeyGroup {
                SectionTitle(R.string.hardware_key_registration_section, Icons.Default.Add)
                Text(stringResource(R.string.hardware_key_registration_hint), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { showRegistrationHelp = true }, contentPadding = PaddingValues(0.dp)) {
                    Text(stringResource(R.string.hardware_key_registration_help))
                    Spacer(Modifier.width(8.dp)); Icon(Icons.Default.ChevronRight, null)
                }
            }
            state.credentials?.let { snapshot ->
                Text(stringResource(R.string.hardware_key_count, snapshot.existing, snapshot.remaining), style = MaterialTheme.typography.titleMedium)
                KeyGroup { snapshot.accounts.forEach { account -> ListItem(headlineContent = { Text(account.rpId) }, supportingContent = { Text(account.userName) }, leadingContent = { Icon(Icons.Default.Key, null) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent)) } }
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
    if (showRegistrationHelp) AlertDialog(onDismissRequest = { showRegistrationHelp = false },
        title = { Text(stringResource(R.string.hardware_key_registration_help)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.hardware_key_instructions))
            Text(stringResource(R.string.hardware_key_no_import), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }, confirmButton = { TextButton(onClick = { showRegistrationHelp = false }) { Text(stringResource(R.string.hardware_key_back)) } })
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
private fun KeyGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}
@Composable
private fun InfoRow(label: Int, value: String, monospace: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
        Text(stringResource(label), Modifier.weight(0.43f), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(0.57f), style = if (monospace) MaterialTheme.typography.bodySmall.copy(letterSpacing = 0.sp) else MaterialTheme.typography.bodyMedium,
            fontFamily = if (monospace) FontFamily.Monospace else null)
    }
}
@Composable
private fun SectionTitle(label: Int, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(label), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}
@Composable
private fun PinField(value: String, label: Int, busy: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(stringResource(label)) }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), enabled = !busy, modifier = Modifier.fillMaxWidth())
}

package takagi.ru.monica.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.withContext
import takagi.ru.monica.R
import takagi.ru.monica.data.model.TemplateCredentialDraft
import takagi.ru.monica.data.model.PasswordContentBlocks
import takagi.ru.monica.data.model.WifiSecurity
import takagi.ru.monica.utils.GpgKeyGenerator
import takagi.ru.monica.utils.SshKeyGenerator

/** The same complete type-specific form is used by standalone entries and embedded content. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TemplateCredentialFields(draft: TemplateCredentialDraft, onChange: (TemplateCredentialDraft) -> Unit,
    onScanWifi: (() -> Unit)? = null, enabled: Boolean = true, tagPrefix: String = "template_field_", ssidInIdentity: Boolean = false,
    showValidationErrors: Boolean = false) {
    val latest by rememberUpdatedState(draft)
    val change by rememberUpdatedState(onChange)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf<String?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val key = importing
        if (uri != null && key != null) scope.launch {
            busy = true
            try {
                val text = withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                        val bytes = input.readBytesLimited(GpgKeyGenerator.MAX_IMPORT_BYTES)
                        require(bytes.size <= GpgKeyGenerator.MAX_IMPORT_BYTES)
                        Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                    }
                }
                if (latest.type == "GPG_KEY") {
                    val parsed = withContext(Dispatchers.Default) { GpgKeyGenerator.parse(text) }
                    change(latest.copy(values = latest.values + mapOf("publicKey" to parsed.publicKey,
                        "privateKey" to parsed.privateKey, "fingerprint" to parsed.fingerprint, "userId" to parsed.userId)))
                } else change(latest.change(key, text))
                failed = false
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
            } catch (_: Exception) { failed = true } finally { busy = false }
        }
    }
    val keys = when (draft.type) {
        "WIFI" -> if (ssidInIdentity) listOf("password") else listOf("ssid", "password")
        else -> PasswordContentBlocks.editableKeys(PasswordContentBlocks.Kind.valueOf(draft.type)).filter { it != "notes" }
    }
    val credentialFields = keys.mapIndexed { index, key ->
        val field: @Composable () -> Unit = {
            var reveal by remember(key) { mutableStateOf(false) }
            val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
            DisposableEffect(lifecycle, key) {
                val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) reveal = false }
                lifecycle.lifecycle.addObserver(observer)
                onDispose { lifecycle.lifecycle.removeObserver(observer) }
            }
            val secret = key in setOf("key", "token", "privateKey", "privateKeyOpenSsh", "password")
            val label = when (key) {
                "ssid" -> R.string.wifi_ssid_required
                "password" -> R.string.wifi_password_label
                else -> fieldLabel(key)
            }
            var choosing by remember(key) { mutableStateOf(false) }
            val choices = when (key) {
                "provider" -> listOf("github", "gitlab")
                "algorithm" -> listOf("ED25519", "RSA")
                "keySize" -> if (draft.value("algorithm").equals("RSA", true)) listOf("2048", "3072", "4096") else emptyList()
                else -> emptyList()
            }
            ExposedDropdownMenuBox(choosing, { if (enabled && choices.isNotEmpty()) choosing = it }) {
            val missingApiKey = showValidationErrors && draft.type == "API_KEY" && key == "key" && draft.value(key).isBlank()
            OutlinedTextField(draft.value(key), { onChange(draft.change(key, it)) }, saveTextState = false,
                enabled = enabled && !busy, label = { Text(stringResource(label)) },
                isError = missingApiKey,
                supportingText = if (missingApiKey) { { Text(stringResource(R.string.api_key_required)) } } else null,
                shape = entryGroupShape(index, keys.size), maxLines = if (key.contains("Key")) 5 else 2,
                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable).testTag("$tagPrefix$key"),
                visualTransformation = if (secret && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
                trailingIcon = {
                    Row {
                        if (key.contains("Key")) IconButton(enabled = enabled && !busy, onClick = { importing = key; importer.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Default.FileOpen, stringResource(R.string.content_block_import))
                        }
                        if (secret) IconButton(onClick = { reveal = !reveal }) {
                            Icon(if (reveal) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                stringResource(if (reveal) R.string.hide_password else R.string.show_password))
                        }
                    }
                })
                ExposedDropdownMenu(choosing, { choosing = false }) {
                    choices.forEach { value -> DropdownMenuItem(text = { Text(value) }, onClick = {
                        var updated = draft.change(key, value)
                        if (key == "algorithm") updated = updated.change("keySize", if (value == "RSA") "3072" else "256")
                        onChange(updated); choosing = false
                    }) }
                }
            }
        }
        field
    }
    val wifiFields = if (draft.type == "WIFI") listOf<@Composable () -> Unit>(
        {
            var choosing by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(choosing, { if (enabled) choosing = it }) {
                val security = WifiSecurity.entries.firstOrNull { it.name == draft.value("security") }
                OutlinedTextField(security?.let { stringResource(it.labelRes()) } ?: draft.value("security"), {}, readOnly = true, saveTextState = false,
                    label = { Text(stringResource(R.string.wifi_security_label)) },
                    shape = entryGroupShape(0, 2), enabled = enabled,
                    modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).testTag("wifi_security"),
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(choosing) })
                ExposedDropdownMenu(choosing, { choosing = false }) {
                    WifiSecurity.entries.forEach { security -> DropdownMenuItem(text = { Text(stringResource(security.labelRes())) },
                        onClick = { onChange(draft.change("security", security.name)); choosing = false }) }
                }
            }
        },
        {
            Surface(modifier = Modifier.fillMaxWidth().testTag("wifi_hidden_network"),
                shape = connectedFieldShape(entryGroupShape(1, 2)), color = MaterialTheme.colorScheme.surfaceContainer) {
                ListItem(headlineContent = { Text(stringResource(R.string.wifi_hidden_network)) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer), trailingContent = {
                    Switch(draft.value("hiddenNetwork") == "true", { onChange(draft.change("hiddenNetwork", it.toString())) }, enabled = enabled)
                })
            }
        },
    ) else emptyList()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val separateSsid = draft.type == "WIFI" && !ssidInIdentity && onScanWifi != null
        if (separateSsid) ConnectedFieldGroup(credentialFields.first())
        if (draft.type == "WIFI" && onScanWifi != null) FilledTonalButton(
            onClick = onScanWifi, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.wifi_scan_qr))
        }
        ConnectedFieldGroup(*(credentialFields.drop(if (separateSsid) 1 else 0) + wifiFields).toTypedArray())
        if (draft.type == "SSH_KEY") FilledTonalButton(enabled = enabled && !busy, onClick = {
            scope.launch {
                busy = true
                try {
                    val data = withContext(Dispatchers.Default) { SshKeyGenerator.generate(
                        if (latest.value("algorithm").equals("RSA", true)) SshKeyGenerator.Request.Rsa(latest.value("keySize").toIntOrNull() ?: 3072)
                        else SshKeyGenerator.Request.Ed25519, latest.value("comment")) }
                    change(latest.copy(values = latest.values + mapOf("algorithm" to data.algorithm, "keySize" to data.keySize.toString(),
                        "publicKeyOpenSsh" to data.publicKeyOpenSsh, "privateKeyOpenSsh" to data.privateKeyOpenSsh,
                        "fingerprintSha256" to data.fingerprintSha256, "format" to data.format)))
                    failed = false
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
                } catch (_: Exception) { failed = true } finally { busy = false }
            }
        }) { Text(stringResource(R.string.ssh_key_generate)) }
        if (draft.type == "GPG_KEY") {
            val generator = remember { takagi.ru.monica.viewmodel.GpgEditorViewModel() }
            DisposableEffect(generator) { onDispose { generator.viewModelScope.cancel(); generator.key = null; generator.passphrase = "" } }
            var options by remember { mutableStateOf(false) }
            FilledTonalButton(onClick = { options = !options }, modifier = Modifier.testTag("gpg_open_generation")) { Text(stringResource(R.string.gpg_options)) }
            if (options) {
                takagi.ru.monica.ui.screens.GpgGenerationForm(generator, showHeading = false)
                FilledTonalButton(enabled = enabled && !generator.busy, onClick = generator::generate, modifier = Modifier.testTag("gpg_generate")) { Text(stringResource(R.string.ssh_key_generate)) }
            }
            LaunchedEffect(generator.key) { generator.key?.let { parsed ->
                change(latest.copy(values = latest.values + mapOf("publicKey" to parsed.publicKey, "privateKey" to parsed.privateKey,
                    "fingerprint" to parsed.fingerprint, "userId" to parsed.userId)))
                generator.key = null; options = false
            } }
            if (generator.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (generator.failed) Text(stringResource(R.string.content_block_save_error), color = MaterialTheme.colorScheme.error)
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (failed) Text(stringResource(R.string.content_block_save_error), color = MaterialTheme.colorScheme.error)
    }
}

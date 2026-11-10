package takagi.ru.monica.ui.screens

import androidx.compose.material3.Scaffold
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.text.selection.SelectionContainer
import takagi.ru.monica.ui.components.EntryTypeChip
import takagi.ru.monica.ui.components.EntryTypeChipOption
import takagi.ru.monica.ui.components.MultiStorageTargetSelectorCard
import takagi.ru.monica.ui.components.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import takagi.ru.monica.R
import takagi.ru.monica.data.*
import takagi.ru.monica.data.model.*
import takagi.ru.monica.ui.components.MultiStorageTargetPickerBottomSheet
import takagi.ru.monica.utils.ClipboardUtils
import takagi.ru.monica.utils.GpgKeyGenerator
import takagi.ru.monica.viewmodel.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GpgKeyScreen(
    passwords: PasswordViewModel,
    onBack: () -> Unit,
    passwordId: Long? = null,
    initialTarget: StorageTarget = StorageTarget.MonicaLocal(null),
    onSaved: ((Long?) -> Unit)? = null,
    onSelectType: ((EntryTypeChipOption) -> Unit)? = null,
    getKeePassGroups: (Long) -> kotlinx.coroutines.flow.Flow<List<takagi.ru.monica.utils.KeePassGroupInfo>> = { flowOf(emptyList()) },
    editor: GpgEditorViewModel = viewModel(key = "gpg:${passwordId ?: "new"}"),
) {
    val seed = remember(passwordId, editor) {
        editor.key?.takeIf { passwordId == null }?.let { generated ->
            AddEditPasswordInitialDraft(title = editor.title.ifBlank { generated.userId },
                template = TemplateCredentialDraft("GPG_KEY", mapOf(
                    "publicKey" to generated.publicKey, "privateKey" to generated.privateKey,
                    "fingerprint" to generated.fingerprint, "userId" to generated.userId)))
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(takagi.ru.monica.ui.components.LocalTemplateTargets provides
        (takagi.ru.monica.ui.components.LocalTemplateTargets.current ?: listOf(initialTarget))) {
        AddEditPasswordScreen(viewModel = passwords, passwordId = passwordId, initialLoginType = "GPG_KEY", initialDraft = seed,
            onNavigateBack = onBack, onSaveCompleted = onSaved,
            onSwitchToWifi = { onSelectType?.invoke(EntryTypeChipOption.WIFI) },
            onSwitchToSshKey = { onSelectType?.invoke(EntryTypeChipOption.SSH_KEY) },
            onSwitchToApiToken = { onSelectType?.invoke(EntryTypeChipOption.API_TOKEN) })
    }
}

@Composable
internal fun GpgGenerationForm(editor: GpgEditorViewModel, showHeading: Boolean = true) {
    var passphraseVisible by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showHeading) Text(stringResource(R.string.gpg_options), style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(editor.name, { editor.name = it }, enabled = !editor.busy, singleLine = true,
            shape = RoundedCornerShape(12.dp), leadingIcon = { Icon(Icons.Default.Person, null) },
            isError = editor.nameInvalid,
            supportingText = { if (editor.nameInvalid) Text(stringResource(R.string.gpg_name_invalid)) },
            label = { Text(stringResource(R.string.gpg_name)) }, modifier = Modifier.fillMaxWidth().testTag("gpg_name"))
        OutlinedTextField(editor.email, { editor.email = it }, enabled = !editor.busy, singleLine = true,
            shape = RoundedCornerShape(12.dp), leadingIcon = { Icon(Icons.Default.Email, null) },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Email),
            isError = editor.emailInvalid,
            supportingText = { if (editor.emailInvalid) Text(stringResource(R.string.gpg_email_invalid)) },
            label = { Text(stringResource(R.string.gpg_email)) }, modifier = Modifier.fillMaxWidth().testTag("gpg_email"))
        GpgOptionDropdown(stringResource(R.string.ssh_key_algorithm_label), "RSA ${editor.bits}",
            listOf(3072, 4096).map { it to "RSA $it" }, !editor.busy) { editor.bits = it }
        val expiry = listOf(365, 730, 0).map { it to if (it == 0) stringResource(R.string.gpg_no_expiry) else stringResource(R.string.gpg_days, it) }
        GpgOptionDropdown(stringResource(R.string.gpg_expiry), expiry.first { it.first == editor.days }.second,
            expiry, !editor.busy) { editor.days = it }
        OutlinedTextField(editor.passphrase, { editor.passphrase = it }, enabled = !editor.busy, singleLine = true,
            saveTextState = false,
            shape = RoundedCornerShape(12.dp), leadingIcon = { Icon(Icons.Default.Lock, null) },
            label = { Text(stringResource(R.string.gpg_passphrase)) }, visualTransformation = if (passphraseVisible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { IconButton(onClick = { passphraseVisible = !passphraseVisible }) {
                Icon(if (passphraseVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    stringResource(if (passphraseVisible) R.string.hide_password else R.string.show_password))
            } },
            modifier = Modifier.fillMaxWidth().testTag("gpg_passphrase"))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GpgOptionDropdown(label: String, value: String, options: List<Pair<Int, String>>, enabled: Boolean, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(value, {}, readOnly = true, enabled = enabled, label = { Text(label) },
            shape = RoundedCornerShape(12.dp), trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable))
        ExposedDropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (id, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { expanded = false; onSelect(id) }) }
        }
    }
}

internal enum class GpgExportKind { PUBLIC, PRIVATE, PAIR }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GpgKeyResult(key: GpgKeyGenerator.Key, privateVisible: Boolean, onReveal: () -> Unit,
    onCopy: (String, Boolean) -> Unit, onExport: (GpgExportKind) -> Unit,
    onCreateEntry: (() -> Unit)? = null) {
    var actions by remember(key.fingerprint) { mutableStateOf(false) }
    Box {
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.testTag("gpg_result").clickable { actions = true }) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(key.userId, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { actions = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more_options)) }
            }
            SelectionContainer { Text(key.fingerprint.chunked(4).joinToString(" "), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("gpg_fingerprint")) }
            if (privateVisible) SelectionContainer { Text(key.privateKey, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("gpg_private")) }
        }
    }
    DropdownMenu(expanded = actions, onDismissRequest = { actions = false },
        modifier = Modifier.widthIn(max = 340.dp).testTag("gpg_actions")) {
            DropdownMenuItem(leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) }, text = { Column { Text(stringResource(R.string.gpg_copy_public)); Text(key.publicKey.lineSequence().firstOrNull().orEmpty(), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }, onClick = { actions = false; onCopy(key.publicKey, false) })
            DropdownMenuItem(leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) }, text = { Column { Text(stringResource(R.string.gpg_copy_fingerprint)); Text(key.fingerprint, maxLines = 2, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }, onClick = { actions = false; onCopy(key.fingerprint, false) })
            if (key.privateKey.isNotBlank()) DropdownMenuItem(leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) }, text = { Text(stringResource(R.string.gpg_copy_private)) }, onClick = { actions = false; onCopy(key.privateKey, true) })
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            DropdownMenuItem(leadingIcon = { Icon(Icons.Default.Save, contentDescription = null) }, text = { Text(stringResource(R.string.gpg_export_public)) }, onClick = { actions = false; onExport(GpgExportKind.PUBLIC) })
            if (key.privateKey.isNotBlank()) {
                DropdownMenuItem(leadingIcon = { Icon(Icons.Default.Save, contentDescription = null) }, text = { Text(stringResource(R.string.gpg_export_private)) }, onClick = { actions = false; onExport(GpgExportKind.PRIVATE) })
                DropdownMenuItem(leadingIcon = { Icon(Icons.Default.Save, contentDescription = null) }, text = { Text(stringResource(R.string.gpg_export_pair)) }, onClick = { actions = false; onExport(GpgExportKind.PAIR) })
            }
            onCreateEntry?.let { create ->
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                DropdownMenuItem(leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) }, text = { Text(stringResource(R.string.gpg_create_entry)) }, onClick = { actions = false; create() })
            }
        }
    }
}

@Composable
internal fun GpgGeneratedResult(key: GpgKeyGenerator.Key, onCreateEntry: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var visible by remember(key.fingerprint) { mutableStateOf(false) }
    var exportText by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pgp-keys")) { uri ->
        val value = exportText
        exportText = ""
        if (uri != null) scope.launch {
            try { withContext(Dispatchers.IO) { require(value.isNotBlank()); context.contentResolver.openOutputStream(uri)?.use { it.write(value.toByteArray(Charsets.UTF_8)) } ?: error("Cannot export key") } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
        }
    }
    Column {
        if (failed) Text(stringResource(R.string.gpg_export_error), color = MaterialTheme.colorScheme.error)
        GpgKeyResult(key, visible, { visible = !visible }, { value, secret -> ClipboardUtils.copyToClipboard(context, value, "GPG key", sensitive = secret) }, { kind ->
            exportText = when (kind) { GpgExportKind.PUBLIC -> key.publicKey; GpgExportKind.PRIVATE -> key.privateKey; GpgExportKind.PAIR -> key.publicKey.trimEnd() + "\n" + key.privateKey.trimStart() }
            export.launch("gpg-key.asc")
        }, onCreateEntry)
    }
}

@Composable
internal fun GpgDetailContent(fields: List<CustomField>, privateKey: String, modifier: Modifier = Modifier, embedded: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf<GpgKeyGenerator.Key?>(null) }
    var failed by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    var exportText by remember { mutableStateOf("") }
    LaunchedEffect(fields, privateKey) {
        key = null
        visible = false
        failed = false
        try {
            key = withContext(Dispatchers.Default) {
                val public = GpgKeyGenerator.parse(GpgEntryFields.publicKey(fields.associate { it.title to it.value }))
                if (privateKey.isBlank()) public else GpgKeyGenerator.parse(privateKey).also {
                    require(it.fingerprint == public.fingerprint)
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pgp-keys")) { uri ->
        val value = exportText
        exportText = ""
        if (uri != null) scope.launch {
            try { withContext(Dispatchers.IO) {
                require(value.isNotBlank()) { "Key is no longer available" }
                context.contentResolver.openOutputStream(uri)?.use { it.write(value.toByteArray(Charsets.UTF_8)) }
                    ?: error("Cannot export key")
            } } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
        }
    }
    Column(modifier.then(if (embedded) Modifier.fillMaxWidth() else Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp))) {
        Text(stringResource(R.string.gpg_title), style = MaterialTheme.typography.titleLarge)
        if (failed) Text(stringResource(R.string.gpg_error), color = MaterialTheme.colorScheme.error)
        key?.let { data ->
            GpgKeyResult(data, visible, { visible = !visible }, { text, secret ->
                ClipboardUtils.copyToClipboard(context, text, "GPG key", sensitive = secret)
            }, { kind ->
                exportText = when (kind) {
                    GpgExportKind.PUBLIC -> data.publicKey
                    GpgExportKind.PRIVATE -> data.privateKey
                    GpgExportKind.PAIR -> data.publicKey.trimEnd() + "\n" + data.privateKey.trimStart()
                }
                export.launch("gpg-key.asc")
            })
        }
    }
}

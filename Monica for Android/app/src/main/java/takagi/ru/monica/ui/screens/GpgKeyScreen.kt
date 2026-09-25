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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { PasswordDatabase.getDatabase(context) }
    val categories by passwords.categories.collectAsState(initial = emptyList())
    val keepass by db.localKeePassDatabaseDao().getAllDatabases().collectAsState(initial = emptyList())
    val mdbx by db.localMdbxDatabaseDao().getAvailableDatabases().collectAsState(initial = emptyList())
    val bitwarden by db.bitwardenVaultDao().getAllVaultsFlow().collectAsState(initial = emptyList())
    var entry by remember(passwordId) { mutableStateOf<PasswordEntry?>(null) }
    var extraFields by remember(passwordId) { mutableStateOf(emptyList<CustomFieldDraft>()) }
    var loaded by remember(passwordId) { mutableStateOf(passwordId == null) }
    var targets by remember { mutableStateOf(listOf(initialTarget)) }
    var pickTarget by remember { mutableStateOf(false) }
    var isFavorite by remember(passwordId) { mutableStateOf(false) }
    var notes by remember(passwordId) { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var showGeneration by remember { mutableStateOf(false) }
    var generationStarted by remember { mutableStateOf(false) }
    var privateVisible by remember { mutableStateOf(false) }
    var exportText by remember { mutableStateOf("") }
    LaunchedEffect(editor.key?.fingerprint) { privateVisible = false }
    LaunchedEffect(passwordId) {
        if (passwordId == null) return@LaunchedEffect
        try {
            val existing = passwords.getPasswordEntryById(passwordId) ?: error("Missing entry")
            val fields = passwords.getCustomFieldsByEntryIdSync(passwordId)
            val data = withContext(Dispatchers.Default) {
                val public = GpgKeyGenerator.parse(GpgEntryFields.publicKey(fields.associate { it.title to it.value }))
                if (existing.password.isBlank()) public else GpgKeyGenerator.parse(existing.password).also {
                    require(it.fingerprint == public.fingerprint)
                }
            }
            isFavorite = existing.isFavorite
            notes = existing.notes
            entry = existing
            targets = listOf(existing.toStorageTarget())
            extraFields = fields.filterNot { GpgEntryFields.owns(it.title) }.map(CustomFieldDraft::fromCustomField)
            if (editor.key == null) { editor.key = data; editor.title = existing.title }
            loaded = true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { editor.fail(GpgEditorViewModel.Failure.LOAD) }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) editor.perform {
            withContext(Dispatchers.IO) {
                val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= GpgKeyGenerator.MAX_IMPORT_BYTES)
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                    ?: error("Cannot open key")
                require(bytes.size <= GpgKeyGenerator.MAX_IMPORT_BYTES)
                GpgKeyGenerator.parse(bytes)
            }
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pgp-keys")) { uri ->
        val value = exportText
        exportText = ""
        if (uri != null) scope.launch {
            try { withContext(Dispatchers.IO) {
                require(value.isNotBlank()) { "Key is no longer available" }
                context.contentResolver.openOutputStream(uri)?.use { it.write(value.toByteArray(Charsets.UTF_8)) }
                    ?: error("Cannot export key")
            } } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { editor.fail(GpgEditorViewModel.Failure.EXPORT) }
        }
    }
    val canSave = loaded && !saving && !editor.busy && editor.key != null && editor.title.isNotBlank() && targets.isNotEmpty()
    val save: () -> Unit = {
        if (canSave) editor.key?.let { key ->
            saving = true
            val base = (entry ?: PasswordEntry(title = "", website = "", username = "", password = "")).copy(
                isFavorite = isFavorite, notes = notes, title = editor.title.trim(), username = key.userId, password = key.privateKey, loginType = GpgEntryFields.TYPE)
            passwords.savePasswordsAcrossTargets(originalIds = listOfNotNull(passwordId), commonEntry = base,
                passwords = listOf(key.privateKey), targets = targets,
                customFields = extraFields + GpgEntryFields.encode(key)) { id ->
                saving = false
                if (id != null) { onSaved?.invoke(id); onBack() } else editor.fail(GpgEditorViewModel.Failure.SAVE)
            }
        }
    }
    Scaffold(topBar = {
        Column {
        TopAppBar(title = {},
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, stringResource(R.string.back)) } },
            actions = {
                EntryTypeChip(current = EntryTypeChipOption.GPG_KEY, showGpg = true, showApiKey = true,
                    enabled = passwordId == null && onSelectType != null,
                    onSelect = { onSelectType?.invoke(it) })
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = { isFavorite = !isFavorite }, enabled = loaded && !saving,
                    modifier = Modifier.testTag("gpg_favorite").semantics { selected = isFavorite }) {
                    Icon(if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = stringResource(R.string.favorite),
                        tint = if (isFavorite) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                }
            })
        Text(stringResource(if (passwordId == null) R.string.gpg_add_title else R.string.gpg_edit_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp)
                .testTag("gpg_heading"))
        }
    }, floatingActionButton = {
        FloatingActionButton(onClick = save, modifier = Modifier.testTag("gpg_save"),
            containerColor = if (canSave) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant) {
            Icon(Icons.Default.Check, stringResource(R.string.save))
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (editor.failed && !showGeneration) Text(stringResource(editor.failure.message), color = MaterialTheme.colorScheme.error)
            MultiStorageTargetSelectorCard(selectedTargets = targets,
                existingTargetKeys = entry?.let { setOf(it.toStorageTarget().stableKey) }.orEmpty(),
                categories = categories, keepassDatabases = keepass, mdbxDatabases = mdbx, bitwardenVaults = bitwarden,
                bitwardenFolderDao = db.bitwardenFolderDao(), getMdbxFolders = passwords::getMdbxFolders,
                isEditing = passwordId != null, onAddTargetClick = { pickTarget = true },
                onRemoveTarget = { target -> if (target != entry?.toStorageTarget()) targets = targets - target })
            OutlinedTextField(editor.title, { editor.title = it }, label = { Text(stringResource(R.string.title_required)) },
                leadingIcon = { Icon(Icons.Default.Key, null) }, singleLine = true, shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().testTag("gpg_title"), enabled = !saving)
            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Key, null)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(stringResource(R.string.gpg_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(if (editor.key == null) R.string.gpg_empty else R.string.gpg_ready),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { generationStarted = false; showGeneration = true }, enabled = loaded && !saving,
                        modifier = Modifier.testTag("gpg_open_generation")) { Icon(Icons.Default.Refresh, stringResource(R.string.gpg_generate)) }
                    IconButton(onClick = { importer.launch(arrayOf("*/*")) }, enabled = loaded && !saving && !editor.busy) {
                        Icon(Icons.Default.FileUpload, stringResource(R.string.gpg_import))
                    }
                }
            }
            editor.key?.let { key ->
                GpgKeyResult(key, privateVisible, { privateVisible = !privateVisible }, { text, secret ->
                    ClipboardUtils.copyToClipboard(context, text, "GPG key", sensitive = secret)
                }, { kind ->
                    exportText = when (kind) {
                        GpgExportKind.PUBLIC -> key.publicKey
                        GpgExportKind.PRIVATE -> key.privateKey
                        GpgExportKind.PAIR -> key.publicKey.trimEnd() + "\n" + key.privateKey.trimStart()
                    }
                    exporter.launch("gpg-key.asc")
                })
            }
            OutlinedTextField(notes, { notes = it }, label = { Text(stringResource(R.string.notes)) },
                leadingIcon = { Icon(Icons.Default.Notes, null) }, enabled = loaded && !saving,
                modifier = Modifier.fillMaxWidth().testTag("gpg_notes"), shape = RoundedCornerShape(12.dp))
        }
    }
    if (showGeneration) {
        ModalBottomSheet(onDismissRequest = { showGeneration = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.gpg_generate), style = MaterialTheme.typography.titleLarge)
                GpgGenerationForm(editor, showHeading = false)
                if (editor.failed) Text(stringResource(editor.failure.message), color = MaterialTheme.colorScheme.error)
                if (editor.busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("gpg_progress"))
                Button(onClick = { generationStarted = true; editor.generate() }, enabled = !editor.busy,
                    modifier = Modifier.fillMaxWidth().testTag("gpg_generate")) { Text(stringResource(R.string.gpg_generate)) }
            }
        }
        LaunchedEffect(editor.key, editor.busy) { if (generationStarted && editor.key != null && !editor.busy && !editor.failed) showGeneration = false }
    }
    MultiStorageTargetPickerBottomSheet(visible = pickTarget, selectedTargets = targets,
        lockedTargetKeys = entry?.let { setOf(it.toStorageTarget().stableKey) }.orEmpty(),
        categories = categories, keepassDatabases = keepass, mdbxDatabases = mdbx, bitwardenVaults = bitwarden,
        getBitwardenFolders = { db.bitwardenFolderDao().getFoldersByVaultFlow(it) },
        getKeePassGroups = getKeePassGroups, getMdbxFolders = passwords::getMdbxFolders,
        onDismiss = { pickTarget = false },
        onSelectedTargetsChange = { targets = it })
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
            modifier = Modifier.fillMaxWidth().menuAnchor())
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
internal fun GpgDetailContent(fields: List<CustomField>, privateKey: String, modifier: Modifier = Modifier) {
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
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
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

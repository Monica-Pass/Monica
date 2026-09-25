package takagi.ru.monica.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOf
import takagi.ru.monica.R
import takagi.ru.monica.data.CustomField
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.model.ApiKeyEntryFields
import takagi.ru.monica.data.model.StorageTarget
import takagi.ru.monica.data.model.toStorageTarget
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.ui.components.EntryTypeChip
import takagi.ru.monica.ui.components.EntryTypeChipOption
import takagi.ru.monica.ui.components.MultiStorageTargetPickerBottomSheet
import takagi.ru.monica.ui.components.MultiStorageTargetSelectorCard
import takagi.ru.monica.ui.components.OutlinedTextField
import takagi.ru.monica.utils.ClipboardUtils
import takagi.ru.monica.utils.KeePassGroupInfo
import takagi.ru.monica.viewmodel.ApiKeyEditorViewModel
import takagi.ru.monica.viewmodel.PasswordViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiKeyScreen(
    passwords: PasswordViewModel,
    onBack: () -> Unit,
    passwordId: Long? = null,
    initialTarget: StorageTarget = StorageTarget.MonicaLocal(null),
    onSaved: ((Long?) -> Unit)? = null,
    onSelectType: ((EntryTypeChipOption) -> Unit)? = null,
    getKeePassGroups: (Long) -> Flow<List<KeePassGroupInfo>> = { flowOf(emptyList()) },
    editor: ApiKeyEditorViewModel = viewModel(key = "api-key:${passwordId ?: "new"}"),
) {
    val context = LocalContext.current
    val database = remember(context) { PasswordDatabase.getDatabase(context) }
    val categories by passwords.categories.collectAsState(initial = emptyList())
    val keepass by database.localKeePassDatabaseDao().getAllDatabases().collectAsState(initial = emptyList())
    val mdbx by database.localMdbxDatabaseDao().getAvailableDatabases().collectAsState(initial = emptyList())
    val bitwarden by database.bitwardenVaultDao().getAllVaultsFlow().collectAsState(initial = emptyList())
    var pickingTarget by remember { mutableStateOf(false) }
    var keyVisible by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) keyVisible = false
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val enabled = editor.loaded && !editor.saving
    val draft = editor.draft
    val lockedTargets = editor.original?.let { setOf(it.toStorageTarget().stableKey) }.orEmpty()
    LaunchedEffect(editor, passwordId) { editor.initialize(passwords, passwordId, initialTarget) }
    LaunchedEffect(editor.loaded) { keyVisible = false }
    BackHandler(enabled = editor.saving) { }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            Column {
                TopAppBar(title = {}, navigationIcon = {
                    IconButton(onClick = onBack, enabled = !editor.saving) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                }, actions = {
                    EntryTypeChip(current = EntryTypeChipOption.API_KEY, showApiKey = true, showGpg = true,
                        enabled = passwordId == null && enabled && onSelectType != null,
                        onSelect = { onSelectType?.invoke(it) })
                    IconButton(onClick = { editor.favorite = !editor.favorite }, enabled = enabled,
                        modifier = Modifier.testTag("api_key_favorite").semantics { selected = editor.favorite }) {
                        Icon(if (editor.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            stringResource(R.string.favorite),
                            tint = if (editor.favorite) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                    }
                })
                Text(stringResource(if (passwordId == null) R.string.api_key_add else R.string.api_key_edit),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp)
                        .testTag("api_key_heading"))
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                editor.save(passwords) { onSaved?.invoke(it); onBack() }
            }, modifier = Modifier.testTag("api_key_save"),
                containerColor = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant) {
                if (editor.saving) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Check, stringResource(R.string.save))
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            editor.failure?.let { failure ->
                Text(stringResource(when (failure) {
                    ApiKeyEditorViewModel.Failure.LOAD -> R.string.api_key_load_error
                    ApiKeyEditorViewModel.Failure.SAVE -> R.string.api_key_save_error
                    ApiKeyEditorViewModel.Failure.LOCKED -> R.string.api_key_locked
                }), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("api_key_error"))
            }
            if (!editor.loaded && editor.failure == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            MultiStorageTargetSelectorCard(selectedTargets = editor.targets, existingTargetKeys = lockedTargets,
                categories = categories, keepassDatabases = keepass, mdbxDatabases = mdbx, bitwardenVaults = bitwarden,
                bitwardenFolderDao = database.bitwardenFolderDao(), getMdbxFolders = passwords::getMdbxFolders,
                isEditing = passwordId != null, onAddTargetClick = { if (enabled) pickingTarget = true },
                onRemoveTarget = { if (enabled && it.stableKey !in lockedTargets) editor.targets = editor.targets - it })
            ApiKeyTextField(draft.provider, { editor.draft = draft.copy(provider = it) },
                R.string.api_key_provider, "api_key_provider", Icons.Default.Business, enabled,
                error = editor.validationAttempted && draft.provider.isBlank(),
                placeholder = stringResource(R.string.api_key_provider_hint))
            ApiKeyTextField(draft.website, { editor.draft = draft.copy(website = it) },
                R.string.api_key_website, "api_key_website", Icons.Default.Language, enabled,
                error = editor.validationAttempted && !ApiKeyEntryFields.isValidOptionalUrl(draft.website),
                url = true, placeholder = "https://example.com")
            OutlinedTextField(value = draft.key, onValueChange = { editor.draft = draft.copy(key = it) },
                saveTextState = false, label = { Text(stringResource(R.string.api_key_secret_required)) },
                leadingIcon = { Icon(Icons.Default.VpnKey, null) }, enabled = enabled, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("api_key_secret"), shape = RoundedCornerShape(12.dp),
                isError = editor.validationAttempted && draft.key.isBlank(),
                supportingText = if (editor.validationAttempted && draft.key.isBlank()) {
                    { Text(stringResource(R.string.api_key_required)) }
                } else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { IconButton(onClick = { keyVisible = !keyVisible }, enabled = enabled,
                    modifier = Modifier.testTag("api_key_reveal")) {
                    Icon(if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        stringResource(if (keyVisible) R.string.api_key_hide else R.string.api_key_show))
                } })
            ApiKeyTextField(draft.apiUrl, { editor.draft = draft.copy(apiUrl = it) },
                R.string.api_key_url, "api_key_url", Icons.Default.Link, enabled,
                error = editor.validationAttempted && !ApiKeyEntryFields.isValidOptionalUrl(draft.apiUrl),
                url = true, placeholder = "https://api.example.com/v1")
            OutlinedTextField(value = draft.notes, onValueChange = { editor.draft = draft.copy(notes = it) },
                saveTextState = false, label = { Text(stringResource(R.string.notes)) },
                leadingIcon = { Icon(Icons.Default.Notes, null) }, enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag("api_key_notes"), shape = RoundedCornerShape(12.dp),
                minLines = 2)
        }
    }
    MultiStorageTargetPickerBottomSheet(visible = pickingTarget, selectedTargets = editor.targets,
        lockedTargetKeys = lockedTargets, categories = categories, keepassDatabases = keepass,
        mdbxDatabases = mdbx, bitwardenVaults = bitwarden,
        getBitwardenFolders = { database.bitwardenFolderDao().getFoldersByVaultFlow(it) },
        getKeePassGroups = getKeePassGroups, getMdbxFolders = passwords::getMdbxFolders,
        onDismiss = { pickingTarget = false }, onSelectedTargetsChange = { if (enabled) editor.targets = it })
}

@Composable
private fun ApiKeyTextField(
    value: String, onChange: (String) -> Unit, label: Int, tag: String, icon: ImageVector,
    enabled: Boolean, error: Boolean, placeholder: String, url: Boolean = false,
) {
    OutlinedTextField(value = value, onValueChange = onChange, saveTextState = false,
        label = { Text(stringResource(label)) }, placeholder = { Text(placeholder) },
        leadingIcon = { Icon(icon, null) }, enabled = enabled, singleLine = true,
        shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().testTag(tag),
        isError = error, supportingText = if (error) {
            { Text(stringResource(if (url) R.string.api_key_invalid_url else R.string.api_key_required)) }
        } else null,
        keyboardOptions = KeyboardOptions(keyboardType = if (url) KeyboardType.Uri else KeyboardType.Text,
            autoCorrectEnabled = !url))
}

@Composable
internal fun ApiKeyDetailContent(
    entry: PasswordEntry,
    secret: String?,
    fields: List<CustomField>,
    modifier: Modifier = Modifier,
) {
    val apiUrl = fields.firstOrNull { it.title == ApiKeyEntryFields.API_URL }?.value.orEmpty()
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(16.dp).padding(bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(entry.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("api_key_detail_title"))
        Text(stringResource(R.string.api_key_title), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary)
        if (entry.website.isNotBlank()) ApiKeyDetailField(stringResource(R.string.api_key_website_label),
            entry.website, Icons.Default.Language, "api_key_detail_website", openUrl = true)
        if (secret == null) Text(stringResource(R.string.api_key_load_error), color = MaterialTheme.colorScheme.error)
        else ApiKeyDetailField(stringResource(R.string.api_key_title), secret, Icons.Default.VpnKey,
            "api_key_detail_secret", sensitive = true)
        if (apiUrl.isNotBlank()) ApiKeyDetailField(stringResource(R.string.api_key_url_label),
            apiUrl, Icons.Default.Link, "api_key_detail_url")
        if (entry.notes.isNotBlank()) ApiKeyDetailField(stringResource(R.string.notes), entry.notes,
            Icons.Default.Notes, "api_key_detail_notes")
        fields.filterNot { ApiKeyEntryFields.owns(it.title) }.forEach { field ->
            ApiKeyDetailField(field.title, field.value, Icons.Default.TextFields, "api_key_extra_${field.id}",
                sensitive = field.isProtected)
        }
    }
}

@Composable
private fun ApiKeyDetailField(
    label: String, value: String, icon: ImageVector, tag: String,
    sensitive: Boolean = false, openUrl: Boolean = false,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var visible by remember(value) { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) visible = false
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { SessionManager.isUnlocked.drop(1).collect { if (!it) visible = false } }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Text(label, style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                if (sensitive) IconButton(onClick = { visible = !visible }, modifier = Modifier.testTag("${tag}_reveal")) {
                    Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        stringResource(if (visible) R.string.api_key_hide else R.string.api_key_show))
                }
                IconButton(onClick = { ClipboardUtils.copyToClipboard(context, value, label, sensitive = sensitive) },
                    modifier = Modifier.testTag("${tag}_copy")) {
                    Icon(Icons.Default.ContentCopy, stringResource(R.string.copy))
                }
            }
            if (sensitive && !visible) Text("••••••••••••••••", modifier = Modifier.testTag(tag))
            else SelectionContainer {
                Text(value, fontFamily = if (sensitive) FontFamily.Monospace else FontFamily.Default,
                    style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(tag))
            }
            if (openUrl && ApiKeyEntryFields.isValidOptionalUrl(value)) TextButton(onClick = {
                runCatching { uriHandler.openUri(value) }
            }) { Text(stringResource(R.string.api_key_open_website)) }
        }
    }
}

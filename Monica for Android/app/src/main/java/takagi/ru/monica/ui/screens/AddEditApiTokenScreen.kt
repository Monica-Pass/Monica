package takagi.ru.monica.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import takagi.ru.monica.R
import takagi.ru.monica.data.ApiTokenPayload
import takagi.ru.monica.data.ApiTokenMetadata
import takagi.ru.monica.ui.components.CustomFieldEditorSection
import takagi.ru.monica.ui.components.PasswordEditorSection
import takagi.ru.monica.data.MdbxEngineType
import takagi.ru.monica.data.NativeApiTokenSummary
import takagi.ru.monica.ui.components.EntryTypeChip
import takagi.ru.monica.ui.components.EntryTypeChipOption
import takagi.ru.monica.ui.components.OutlinedTextField
import takagi.ru.monica.ui.icons.MonicaIcons
import takagi.ru.monica.viewmodel.MdbxViewModel
import takagi.ru.monica.viewmodel.NativeApiTokenEditorViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditApiTokenScreen(
    mdbxViewModel: MdbxViewModel,
    initialDatabaseId: Long? = null,
    entryId: String? = null,
    initialFolderId: String? = null,
    onNavigateBack: () -> Unit,
    onSaved: (NativeApiTokenSummary) -> Unit,
    onSwitchType: (EntryTypeChipOption, Long?, String?) -> Unit,
    onManageDatabases: () -> Unit,
) {
    val model: NativeApiTokenEditorViewModel = viewModel(
        key = "api-token-editor:$initialDatabaseId:$entryId",
        factory = viewModelFactory { initializer {
            NativeApiTokenEditorViewModel(mdbxViewModel, initialDatabaseId, entryId, initialFolderId)
        } },
    )
    val state by model.state.collectAsStateWithLifecycle()
    val allDatabases by mdbxViewModel.availableDatabases.collectAsStateWithLifecycle()
    val databasesLoaded by mdbxViewModel.allDatabasesLoaded.collectAsStateWithLifecycle()
    val databases = remember(allDatabases) { allDatabases.filter { it.engineTypeEnum == MdbxEngineType.RUST_MDBX2 } }
    val editing = entryId != null
    val selectableDatabases = if (editing) databases.filter { it.id == initialDatabaseId } else databases
    LaunchedEffect(databasesLoaded, databases.map { it.id }, state.databaseId) {
        if (!editing && databasesLoaded && databases.none { it.id == state.databaseId }) {
            (databases.firstOrNull { it.isDefault } ?: databases.firstOrNull())?.let {
                model.selectDatabase(it.id, null, initial = true)
            }
        }
    }
    LaunchedEffect(state.saved) { state.saved?.let(onSaved) }
    var showDiscard by remember { mutableStateOf(false) }
    var pendingType by remember { mutableStateOf<EntryTypeChipOption?>(null) }
    var revealToken by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var fieldVisibilityEpoch by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { revealToken = false; showImport = false; fieldVisibilityEpoch++ }
            if (event == Lifecycle.Event.ON_RESUME && editing && model.state.value.original == null) model.load()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    fun leave(type: EntryTypeChipOption? = null) {
        if (state.saving) return
        pendingType = type
        if (state.changed) showDiscard = true
        else if (type != null) onSwitchType(type, state.databaseId, state.folderId) else onNavigateBack()
    }
    BackHandler { leave() }
    val fields = remember(state.payload) { ApiTokenPayload.decodeDraft(state.payload) }
    val provider = ApiTokenPayload.text(fields, "provider")
    val token = ApiTokenPayload.text(fields, "token")
    val supported = (!editing || state.original?.payload?.let(ApiTokenPayload::decode) != null) &&
        (state.original?.extras?.payload?.let(ApiTokenMetadata::isValid) ?: true)
    val customFields = remember(state.metadata) { ApiTokenMetadata.customFields(state.metadata) }
    val notes = remember(state.metadata, fields) { ApiTokenMetadata.notes(state.metadata, ApiTokenPayload.text(fields, "note")) }
    val canSave = supported && state.canSave && databases.any { it.id == state.databaseId } && (!editing || state.original != null)
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = { TopAppBar(
            title = { Text(stringResource(if (editing) R.string.api_token_edit else R.string.api_token_add),
                style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = { leave() }) {
                Icon(MonicaIcons.Navigation.back, stringResource(R.string.back))
            } },
            actions = {
                EntryTypeChip(current = EntryTypeChipOption.API_TOKEN,
                    showApiKey = true,
                    enabled = !editing && !state.saving,
                    onSelect = { if (it != EntryTypeChipOption.API_TOKEN) leave(it) })
                Spacer(Modifier.width(4.dp))
                IconToggleButton(
                    checked = state.isFavorite,
                    onCheckedChange = model::setFavorite,
                    enabled = supported && !state.loading && !state.saving && (!editing || state.original != null),
                    modifier = Modifier.testTag("api_token_favorite"),
                ) {
                    Icon(
                        if (state.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = stringResource(if (state.isFavorite) R.string.remove_from_favorites else R.string.add_to_favorites),
                        tint = if (state.isFavorite) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent,
                scrolledContainerColor = Color.Transparent),
        ) },
        floatingActionButton = {
            FloatingActionButton(onClick = { if (canSave) model.save() },
                modifier = Modifier.testTag("api_token_save").semantics { if (!canSave) disabled() },
                contentColor = if (canSave) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                containerColor = if (canSave) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest) {
                if (state.saving) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Check, stringResource(R.string.save))
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (databasesLoaded) ApiTokenStorageSelector(mdbxViewModel, selectableDatabases, state.databaseId, state.folderId,
                editing, !state.saving && !state.loading,
                onSelect = { id, folder -> model.selectDatabase(id, folder) }, onManageDatabases = onManageDatabases)
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.failed) ApiTokenError(if (editing && state.original == null) model::load else null)
            if (!state.loading && state.original != null && !supported) {
                Text(stringResource(R.string.api_token_unknown_schema))
            }
            if (!state.loading && supported && (!editing || state.original != null)) {
                PasswordEditorSection(stringResource(R.string.api_token_credentials)) {
                    OutlinedTextField(state.title, model::changeTitle,
                        saveTextState = false,
                        modifier = Modifier.fillMaxWidth().testTag("api_token_name"),
                        enabled = !state.saving, singleLine = true,
                        label = { Text(stringResource(R.string.api_token_name)) },
                        leadingIcon = { Icon(Icons.Default.Label, null) },
                        isError = state.title.isNotEmpty() && !ApiTokenPayload.isValidStorageName(state.title.trim()),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), shape = RoundedCornerShape(12.dp))
                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded, { if (!state.saving) expanded = it }) {
                        val providerLabel = when (provider) { "github" -> "GitHub"; "gitlab" -> "GitLab"; else -> provider }
                        OutlinedTextField(providerLabel, { model.changeField("provider", it); expanded = false },
                            enabled = !state.saving, singleLine = true,
                            saveTextState = false,
                            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                                .testTag("api_token_provider"),
                            label = { Text(stringResource(R.string.api_token_provider)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                            shape = RoundedCornerShape(12.dp))
                        ExposedDropdownMenu(expanded, { expanded = false }) {
                            listOf("gitlab" to "GitLab", "github" to "GitHub").forEach { (value, label) ->
                                DropdownMenuItem(text = { Text(label) }, onClick = { model.selectProvider(value); expanded = false })
                            }
                        }
                    }
                    OutlinedTextField(ApiTokenPayload.text(fields, "api_base"), { model.changeField("api_base", it) },
                        saveTextState = false,
                        modifier = Modifier.fillMaxWidth().testTag("api_token_api_base"),
                        enabled = !state.saving, singleLine = true,
                        label = { Text(stringResource(R.string.api_token_api_base)) },
                        leadingIcon = { Icon(Icons.Default.Link, null) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                        shape = RoundedCornerShape(12.dp))
                    OutlinedTextField(token, { model.changeField("token", it) },
                        saveTextState = false,
                        modifier = Modifier.fillMaxWidth().testTag("api_token_secret"),
                        enabled = !state.saving, singleLine = true,
                        label = { Text(stringResource(R.string.entry_type_api_token)) },
                        leadingIcon = { Icon(Icons.Default.Key, null) },
                        trailingIcon = { IconButton(onClick = { revealToken = !revealToken }) {
                            Icon(if (revealToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                stringResource(if (revealToken) R.string.hide else R.string.show))
                        } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                        visualTransformation = if (revealToken) VisualTransformation.None else PasswordVisualTransformation(),
                        shape = RoundedCornerShape(12.dp))
                    if (token.isNotBlank() && !ApiTokenPayload.isValidForStorage(state.payload)) {
                        Text(stringResource(R.string.api_token_validation), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                }
                PasswordEditorSection(stringResource(R.string.notes)) {
                    OutlinedTextField(notes, model::changeNotes,
                        saveTextState = false,
                        modifier = Modifier.fillMaxWidth().testTag("api_token_note"), enabled = !state.saving,
                        label = { Text(stringResource(R.string.api_token_note)) }, minLines = 2, maxLines = 5,
                        shape = RoundedCornerShape(12.dp))
                }
                key(fieldVisibilityEpoch) {
                    PasswordEditorSection(stringResource(R.string.custom_fields)) {
                        CustomFieldEditorSection(customFields,
                            onFieldsChange = { if (!state.saving) model.changeCustomFields(it) },
                            modifier = Modifier.testTag("api_token_custom_fields"), saveTextState = false)
                        if (!ApiTokenMetadata.isValid(state.metadata)) {
                            Text(stringResource(R.string.api_token_fields_limit), color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                TextButton(onClick = { showImport = true }, enabled = !state.saving) {
                    Icon(Icons.Default.DataObject, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.api_token_import_json))
                }
            }
            Spacer(Modifier.height(96.dp))
        }
    }
    if (showDiscard) AlertDialog(onDismissRequest = { showDiscard = false },
        title = { Text(stringResource(R.string.api_token_discard_title)) },
        text = { Text(stringResource(R.string.api_token_discard_message)) },
        dismissButton = { TextButton(onClick = { showDiscard = false }) { Text(stringResource(R.string.cancel)) } },
        confirmButton = { TextButton(onClick = {
            showDiscard = false
            pendingType?.let { onSwitchType(it, state.databaseId, state.folderId) } ?: onNavigateBack()
        }) { Text(stringResource(R.string.api_token_discard)) } })
    if (showImport) {
        var imported by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { showImport = false },
            title = { Text(stringResource(R.string.api_token_import_json)) },
            text = { OutlinedTextField(imported, { imported = it },
                saveTextState = false,
                modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                label = { Text(stringResource(R.string.api_token_complete_payload)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation(), minLines = 3, maxLines = 8) },
            confirmButton = { TextButton(enabled = ApiTokenPayload.decode(imported) != null, onClick = {
                if (model.importPayload(imported)) { imported = ""; showImport = false }
            }) { Text(stringResource(R.string.api_token_parse)) } },
            dismissButton = { TextButton(onClick = { showImport = false }) { Text(stringResource(R.string.cancel)) } })
    }
}

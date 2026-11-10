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
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import takagi.ru.monica.data.model.EmbeddedWalletContent
import takagi.ru.monica.attachments.EmbeddedWalletAccess
import takagi.ru.monica.ui.components.PasswordContentSection

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
    takagi.ru.monica.nfc.card.NfcCardBlockingEditor()
    val context = LocalContext.current
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
    val templateNavigation = takagi.ru.monica.ui.components.LocalTemplateNavigation.current
    fun switchTemplate(type: EntryTypeChipOption) {
        val targets = state.databaseId?.let { listOf(takagi.ru.monica.data.model.StorageTarget.Mdbx(it, state.folderId)) }.orEmpty()
        if (templateNavigation != null) templateNavigation(type, targets) else onSwitchType(type, state.databaseId, state.folderId)
    }
    fun leave(type: EntryTypeChipOption? = null) {
        if (state.saving) return
        pendingType = type
        if (state.changed) showDiscard = true
        else if (type != null) switchTemplate(type) else onNavigateBack()
    }
    BackHandler { leave() }
    val fields = remember(state.payload) { ApiTokenPayload.decodeDraft(state.payload) }
    val provider = ApiTokenPayload.text(fields, "provider")
    val token = ApiTokenPayload.text(fields, "token")
    val supported = (!editing || state.original?.payload?.let(ApiTokenPayload::decode) != null) &&
        (state.original?.extras?.payload?.let(ApiTokenMetadata::isValid) ?: true)
    val customFields = remember(state.metadata) { ApiTokenMetadata.customFields(state.metadata) }
    val notes = remember(state.metadata, fields) { ApiTokenMetadata.notes(state.metadata, ApiTokenPayload.text(fields, "note")) }
    var showContentMenu by remember { mutableStateOf(false) }
    var showFields by remember { mutableStateOf(false) }
    var showAttachments by remember { mutableStateOf(false) }
    var walletPicker by remember { mutableStateOf<EmbeddedWalletContent.Kind?>(null) }
    var editingWallet by remember { mutableStateOf<EmbeddedWalletContent.Snapshot?>(null) }
    val room = remember(context) { takagi.ru.monica.data.PasswordDatabase.getDatabase(context) }
    val security = takagi.ru.monica.ui.rememberUiSecurityManager()
    val walletRepository = remember(room, security) { takagi.ru.monica.repository.SecureItemRepository(room.secureItemDao(), decryptSensitiveValue = security::decryptDataIfMonicaCiphertext) }
    val banks = androidx.lifecycle.viewmodel.compose.viewModel<takagi.ru.monica.viewmodel.BankCardViewModel>(key = "native-token-wallet-cards") {
        takagi.ru.monica.viewmodel.BankCardViewModel(walletRepository, context, room.localKeePassDatabaseDao(), security,
            strings = takagi.ru.monica.utils.AppLocaleStringResolver(context))
    }
    val notesModel = androidx.lifecycle.viewmodel.compose.viewModel<takagi.ru.monica.viewmodel.NoteViewModel>(key = "native-token-wallet-notes") {
        takagi.ru.monica.viewmodel.NoteViewModel(walletRepository, context = context, localKeePassDatabaseDao = room.localKeePassDatabaseDao(), securityManager = security,
            strings = takagi.ru.monica.utils.AppLocaleStringResolver(context))
    }
    val copiedWallets = remember(customFields) { customFields.filter { EmbeddedWalletContent.isMetadata(it.title) }.mapNotNull {
        (EmbeddedWalletContent.read(it.value) as? EmbeddedWalletContent.ReadResult.Available)?.snapshot
    } }
    val managedAssetNames = copiedWallets.flatMap { it.assets }.map { it.name }.toSet() +
        ApiTokenMetadata.customFields(state.original?.extras?.payload ?: ApiTokenMetadata.empty()).flatMap {
            (EmbeddedWalletContent.read(it.value) as? EmbeddedWalletContent.ReadResult.Available)?.snapshot?.assets.orEmpty()
        }.map { it.name }
    val storedAttachments = state.original?.attachments.orEmpty().filterNot { it.id in state.removedAttachmentIds || it.fileName in managedAssetNames }
    val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::addAttachment) }
    editingWallet?.let { wallet ->
        val access = remember(wallet.encode(), state.original) { state.original?.let { original ->
            EmbeddedWalletAccess.openNative(context, wallet) { name ->
                mdbxViewModel.readNativeApiTokenAttachment(original, original.attachments.single { it.fileName == name }.id)
            }
        } }
        takagi.ru.monica.ui.components.EmbeddedWalletEditorDialog(wallet, model.walletDraft(wallet.id), null, banks, notesModel,
            nativeAccess = access, onDismiss = { editingWallet = null },
            onSaved = { model.installWallet(it); editingWallet = null })
    }
    walletPicker?.let { kind ->
        val cards by banks.parsedCards.collectAsStateWithLifecycle()
        val notesItems by notesModel.allNotes.collectAsStateWithLifecycle()
        takagi.ru.monica.ui.components.EmbeddedWalletPicker(
            if (kind == EmbeddedWalletContent.Kind.BANK_CARD) cards.map { it.item } else notesItems.filterNot { it.isDeleted },
            stringResource(if (kind == EmbeddedWalletContent.Kind.BANK_CARD) R.string.embedded_copy_card else R.string.embedded_copy_note),
            onSelect = { model.copyWallet(it); walletPicker = null }, onDismiss = { walletPicker = null }, mdbxDatabases = allDatabases)
    }
    val unlocked by takagi.ru.monica.security.SessionManager.isUnlocked.collectAsStateWithLifecycle()
    LaunchedEffect(unlocked) { if (!unlocked) { editingWallet = null; walletPicker = null; showContentMenu = false } }
    var showIcon by remember { mutableStateOf(false) }
    var editingBlock by remember { mutableStateOf<takagi.ru.monica.data.model.PasswordContentBlocks.Block?>(null) }
    val tokenEmoji = customFields.firstOrNull { it.title == "monica.icon.emoji" }?.value.orEmpty()
    val blocks = takagi.ru.monica.data.model.PasswordContentBlocks.read(customFields)
    val visibleCustomFields = customFields.filterNot { takagi.ru.monica.data.model.PasswordContentBlocks.owns(it.title) || it.title == "monica.icon.emoji" || EmbeddedWalletContent.isMetadata(it.title) }
    if (showIcon) takagi.ru.monica.ui.components.EmojiIconInputDialog(tokenEmoji, onConfirm = { value ->
        val retained = customFields.filterNot { it.title == "monica.icon.emoji" }
        model.changeCustomFields(retained + takagi.ru.monica.data.CustomFieldDraft(
            id = takagi.ru.monica.data.CustomFieldDraft.nextTempId(retained.map { it.id }), title = "monica.icon.emoji", value = value))
        showIcon = false
    }, onDismissRequest = { showIcon = false })
    editingBlock?.let { block -> takagi.ru.monica.ui.components.PasswordContentBlockEditor(block,
        onSave = { updated -> model.changeCustomFields(takagi.ru.monica.data.model.PasswordContentBlocks.put(customFields, updated)); editingBlock = null },
        onDismiss = { editingBlock = null }) }
    if (showContentMenu) takagi.ru.monica.ui.components.PasswordContentMenu(
        sections = listOf(PasswordContentSection.CUSTOM_FIELDS, PasswordContentSection.ATTACHMENTS, PasswordContentSection.PAYMENT, PasswordContentSection.NOTES),
        onAdd = {
            when (it) {
                PasswordContentSection.ATTACHMENTS -> showAttachments = true
                PasswordContentSection.PAYMENT -> walletPicker = EmbeddedWalletContent.Kind.BANK_CARD
                PasswordContentSection.NOTES -> walletPicker = EmbeddedWalletContent.Kind.NOTE
                else -> showFields = true
            }
            showContentMenu = false
        }, onDismiss = { showContentMenu = false },
        onAddBlock = { editingBlock = takagi.ru.monica.data.model.PasswordContentBlocks.create(it); showContentMenu = false })
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
                    showApiKey = true, showGpg = true,
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
            .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (databasesLoaded) ApiTokenStorageSelector(mdbxViewModel, selectableDatabases, state.databaseId, state.folderId,
                editing, !state.saving && !state.loading,
                onSelect = { id, folder -> model.selectDatabase(id, folder) }, onManageDatabases = onManageDatabases)
            if (state.loading || state.preparing) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.failed) {
                ApiTokenError(if (editing && state.original == null) model::load else null)
                if (state.original != null || !editing) Text(stringResource(R.string.native_token_assets_error), color = MaterialTheme.colorScheme.error)
            }
            if (!state.loading && state.original != null && !supported) {
                Text(stringResource(R.string.api_token_unknown_schema))
            }
            if (!state.loading && supported && (!editing || state.original != null)) {
                takagi.ru.monica.ui.components.TemplateFormSection("") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        FilledTonalIconButton(onClick = { showIcon = true }, modifier = Modifier.size(56.dp)) {
                            if (tokenEmoji.isNotBlank()) takagi.ru.monica.ui.icons.EmojiIconText(tokenEmoji, 26.dp)
                            else Icon(Icons.Default.Image, stringResource(R.string.custom_icon_button))
                        }
                        OutlinedTextField(state.title, model::changeTitle, saveTextState = false,
                            modifier = Modifier.weight(1f).testTag("api_token_name"), enabled = !state.saving,
                            label = { Text(stringResource(R.string.title_required)) }, singleLine = true,
                            shape = RoundedCornerShape(24.dp))
                    }
                }
                takagi.ru.monica.ui.components.TemplateFormSection("") {
                    val credential = takagi.ru.monica.data.model.TemplateCredentialDraft("API_TOKEN",
                        listOf("provider", "api_base", "token").associateWith { ApiTokenPayload.text(fields, it) })
                    takagi.ru.monica.ui.components.TemplateCredentialFields(credential, { updated ->
                        updated.values.forEach { (key, value) ->
                            if (value != credential.value(key)) {
                                if (key == "provider") model.selectProvider(value) else model.changeField(key, value)
                            }
                        }
                    }, enabled = !state.saving)
                    if (token.isNotBlank() && !ApiTokenPayload.isValidForStorage(state.payload)) {
                        Text(stringResource(R.string.api_token_validation), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                }
                takagi.ru.monica.ui.components.TemplateFormSection(stringResource(R.string.notes)) {
                    OutlinedTextField(notes, model::changeNotes,
                        saveTextState = false,
                        modifier = Modifier.fillMaxWidth().testTag("api_token_note"), enabled = !state.saving,
                        label = { Text(stringResource(R.string.api_token_note)) }, minLines = 2, maxLines = 5,
                        shape = RoundedCornerShape(12.dp))
                }
                key(fieldVisibilityEpoch) {
                    if (showFields || visibleCustomFields.isNotEmpty()) takagi.ru.monica.ui.components.TemplateFormSection(stringResource(R.string.custom_fields)) {
                        CustomFieldEditorSection(visibleCustomFields,
                            onFieldsChange = { if (!state.saving) model.changeCustomFields(customFields.filter { field -> field !in visibleCustomFields } + it) },
                            modifier = Modifier.testTag("api_token_custom_fields"), saveTextState = false)
                        if (!ApiTokenMetadata.isValid(state.metadata)) {
                            Text(stringResource(R.string.api_token_fields_limit), color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                blocks.forEach { stored ->
                    takagi.ru.monica.ui.components.PasswordContentBlockCard(stored, onClick = { editingBlock = stored.block })
                }
                copiedWallets.forEach { wallet ->
                    key(wallet.id) {
                        var artwork by remember(wallet.encode()) { mutableStateOf<android.graphics.Bitmap?>(null) }
                        val draft = model.walletDraft(wallet.id)
                        LaunchedEffect(wallet.encode(), draft, state.original) {
                            val asset = wallet.assets.firstOrNull { it.role == EmbeddedWalletContent.AssetRole.CARD_FACE }
                            if (asset != null) try {
                                artwork = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    if (draft != null) draft.assets.open(asset.name).use { android.graphics.BitmapFactory.decodeStream(it) }
                                    else state.original?.let { original ->
                                        val bytes = mdbxViewModel.readNativeApiTokenAttachment(original, original.attachments.single { it.fileName == asset.name }.id)
                                        try { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } finally { bytes.fill(0) }
                                    }
                                }
                            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                            catch (_: Exception) { artwork = null }
                        }
                        takagi.ru.monica.ui.components.EmbeddedWalletPreview(wallet, artwork) { if (!state.saving && !state.preparing) editingWallet = wallet }
                    }
                }
                if (showAttachments || storedAttachments.isNotEmpty() || state.pendingAttachments.isNotEmpty()) {
                    takagi.ru.monica.ui.components.TemplateFormSection(stringResource(R.string.attachments)) {
                        Text(stringResource(R.string.native_token_assets_limit), style = MaterialTheme.typography.bodySmall)
                        takagi.ru.monica.attachments.ui.NativeApiTokenAttachmentList(storedAttachments,
                            onRead = { asset, output ->
                                val bytes = mdbxViewModel.readNativeApiTokenAttachment(requireNotNull(state.original), asset.id)
                                try { output.write(bytes) } finally { bytes.fill(0) }
                            }, onRemove = model::removeAttachment, enabled = !state.saving && !state.preparing)
                        takagi.ru.monica.attachments.ui.NativeApiTokenAttachmentList(state.pendingAttachments,
                            onRemove = model::removeAttachment, enabled = !state.saving && !state.preparing)
                        TextButton(onClick = { attachmentPicker.launch(arrayOf("*/*")) }, enabled = !state.saving && !state.preparing,
                            modifier = Modifier.testTag("native_token_add_attachment")) {
                            Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.attachments))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    takagi.ru.monica.ui.components.PasswordContentAddButton(onClick = { showContentMenu = true }, enabled = !state.saving)
                }
                if (!ApiTokenMetadata.isValid(state.metadata)) Text(stringResource(R.string.api_token_fields_limit), color = MaterialTheme.colorScheme.error)
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
            pendingType?.let { switchTemplate(it) } ?: onNavigateBack()
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

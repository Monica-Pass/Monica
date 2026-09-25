package takagi.ru.monica.ui.screens

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.data.model.OtpType
import takagi.ru.monica.data.model.TotpData
import takagi.ru.monica.keepass.KeePassFieldChange
import takagi.ru.monica.keepass.KeePassTotpCodec
import takagi.ru.monica.keepass.KeePassCustomIconEditor
import takagi.ru.monica.keepass.KeePassAutoTypeDraft
import takagi.ru.monica.keepass.KeePassAutoTypeDraftError
import takagi.ru.monica.keepass.KeePassAutoTypeEditor
import takagi.ru.monica.keepass.KeePassTemplateEngine
import takagi.ru.monica.keepass.KeePassAutoTypeRuleDraft
import takagi.ru.monica.keepass.KeePassNativeCustomIconPayload
import takagi.ru.monica.keepass.KeePassNativeEntryPresentationUpdate
import takagi.ru.monica.keepass.KeePassNativeEntryIdentity
import takagi.ru.monica.keepass.KeePassNativeEntryRecord
import takagi.ru.monica.keepass.KeePassNativeGroupIdentity
import takagi.ru.monica.ui.components.CustomFieldEditCard
import takagi.ru.monica.ui.components.CustomFieldSectionHeader
import takagi.ru.monica.util.PasswordGenerator
import java.util.Locale
import java.util.UUID
import app.keemobile.kotpass.models.CustomIcon
import app.keemobile.kotpass.constants.PredefinedIcon
import app.keemobile.kotpass.constants.AutoTypeObfuscation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NativeEntryEditorScreen(
    entry: KeePassNativeEntryRecord?,
    parentGroup: KeePassNativeGroupIdentity?,
    templateMode: Boolean = false,
    customIcons: Map<UUID, CustomIcon> = emptyMap(),
    customIconReferences: Map<UUID, Int> = emptyMap(),
    revisionToken: String,
    savingEnabled: Boolean,
    onBack: () -> Unit,
    onDeleteCustomIcon: (UUID, (String?) -> Unit) -> Unit = { _, result ->
        result("Custom icon deletion is unavailable")
    },
    onRenameCustomIcon: (UUID, String, (String?) -> Unit) -> Unit = { _, _, result ->
        result("Custom icon rename is unavailable")
    },
    onSave: (
        List<KeePassFieldChange>,
        KeePassNativeEntryPresentationUpdate?,
        List<Uri>,
        (String?) -> Unit,
    ) -> Unit,
) {
    val context = LocalContext.current
    val entryIdentity = entry?.identity
    val initialDraft = remember(entryIdentity, parentGroup, revisionToken) {
        val source = entry?.let { current ->
            buildNativeEntryEditorDraft(
                current.fields
                    .filterNot { field -> field.name.equals(KeePassTemplateEngine.TEMPLATE_MARKER_FIELD, ignoreCase = true) }
                    .map { field ->
                    KeePassFieldChange(
                        name = field.name,
                        value = field.rawValue,
                        protected = field.isProtected,
                    )
                },
            )
        } ?: newNativeEntryEditorDraft()
        ensureNativeEntryEditorStandardFields(source)
    }
    val fields: SnapshotStateList<NativeEntryEditorField> = remember(
        entryIdentity,
        parentGroup,
        revisionToken,
    ) {
        mutableStateListOf<NativeEntryEditorField>().also { it.addAll(initialDraft.fields) }
    }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(error) { if (error != null) listState.animateScrollToItem(0) }
    var revealedIds by remember(entryIdentity, parentGroup) { mutableStateOf(emptySet<Long>()) }
    var selectedCustomIconUuid by remember(entryIdentity, parentGroup, revisionToken) {
        mutableStateOf(entry?.customIconUuid)
    }
    var selectedPredefinedIcon by remember(entryIdentity, parentGroup, revisionToken) {
        mutableStateOf(entry?.icon)
    }
    var predefinedIconChanged by remember(entryIdentity, parentGroup, revisionToken) {
        mutableStateOf(false)
    }
    var clearCustomIcon by remember(entryIdentity, parentGroup, revisionToken) {
        mutableStateOf(false)
    }
    var pendingCustomIcon by remember(entryIdentity, parentGroup, revisionToken) {
        mutableStateOf<KeePassNativeCustomIconPayload?>(null)
    }
    var pendingIconBytes by remember(entryIdentity, parentGroup, revisionToken) {
        mutableStateOf<ByteArray?>(null)
    }
    var pendingIconName by remember(entryIdentity, parentGroup, revisionToken) {
        mutableStateOf("")
    }
    var showCustomIconPicker by remember { mutableStateOf(false) }
    var showPredefinedIconPicker by remember { mutableStateOf(false) }
    var showCustomIconNameDialog by remember { mutableStateOf(false) }
    var iconError by remember { mutableStateOf<String?>(null) }
    var deletingIconUuid by remember { mutableStateOf<UUID?>(null) }
    var renamingIconUuid by remember { mutableStateOf<UUID?>(null) }
    var renamingIconName by remember { mutableStateOf("") }
    val initialAutoType = remember(entryIdentity, parentGroup, revisionToken) {
        KeePassAutoTypeEditor.from(entry?.autoType)
    }
    var autoTypeEnabled by remember(entryIdentity, parentGroup, revisionToken) {
        mutableStateOf(initialAutoType.enabled)
    }
    var autoTypeObfuscation by remember(entryIdentity, parentGroup, revisionToken) {
        mutableStateOf(initialAutoType.obfuscation)
    }
    var autoTypeDefaultSequence by remember(entryIdentity, parentGroup, revisionToken) {
        mutableStateOf(initialAutoType.defaultSequence)
    }
    val autoTypeRules: SnapshotStateList<KeePassAutoTypeRuleDraft> = remember(
        entryIdentity,
        parentGroup,
        revisionToken,
    ) {
        mutableStateListOf<KeePassAutoTypeRuleDraft>().also { it.addAll(initialAutoType.rules) }
    }
    var autoTypeChanged by remember(entryIdentity, parentGroup, revisionToken) {
        mutableStateOf(false)
    }
    val properties = remember(entryIdentity, parentGroup, revisionToken) { NativeEntryPropertiesState(entry) }
    val originalTotpFields = remember(entryIdentity, parentGroup, revisionToken) {
        val rawFields = entry?.fields.orEmpty().map { KeePassFieldChange(it.name, it.rawValue, it.isProtected) }
        if (editableNativeTotpData(rawFields) != null) rawFields.filter { isNativeTotpFieldName(it.name) } else emptyList()
    }
    val initialTotp = remember(entryIdentity, parentGroup, revisionToken) {
        editableNativeTotpData(
            entry?.fields.orEmpty().map { field ->
                KeePassFieldChange(field.name, field.rawValue, field.isProtected)
            },
        )
    }
    var totpEnabled by remember(entryIdentity, parentGroup, revisionToken) {
        mutableStateOf(initialTotp != null)
    }
    var totpSecret by remember(entryIdentity, parentGroup, revisionToken) {
        mutableStateOf(initialTotp?.secret.orEmpty())
    }
    var totpIssuer by remember(entryIdentity, parentGroup, revisionToken) {
        mutableStateOf(initialTotp?.issuer.orEmpty())
    }
    var totpAccount by remember(entryIdentity, parentGroup, revisionToken) {
        mutableStateOf(initialTotp?.accountName.orEmpty())
    }
    var totpPeriod by remember(entryIdentity, parentGroup, revisionToken) {
        mutableStateOf((initialTotp?.period ?: 30).toString())
    }
    var totpDigits by remember(entryIdentity, parentGroup, revisionToken) {
        mutableStateOf((initialTotp?.digits ?: 6).toString())
    }
    var totpAlgorithm by remember(entryIdentity, parentGroup, revisionToken) {
        mutableStateOf(initialTotp?.algorithm ?: "SHA1")
    }
    var otpType by remember(entryIdentity, parentGroup, revisionToken) {
        mutableStateOf(initialTotp?.otpType?.takeIf { it == OtpType.HOTP } ?: OtpType.TOTP)
    }
    var hotpCounter by remember(entryIdentity, parentGroup, revisionToken) {
        mutableStateOf((initialTotp?.counter ?: 0L).toString())
    }
    var totpChanged by remember(entryIdentity, parentGroup, revisionToken) {
        mutableStateOf(false)
    }
    val pendingAttachmentUris: SnapshotStateList<Uri> = remember(entryIdentity, parentGroup, revisionToken) {
        mutableStateListOf()
    }

    val iconReadFailedMessage = stringResource(R.string.keepass_native_custom_icon_read_failed)
    val iconInvalidMessage = stringResource(R.string.keepass_native_custom_icon_invalid)

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("Unable to read selected image")
        }.getOrNull()
        if (bytes == null) {
            iconError = iconReadFailedMessage
        } else if (KeePassCustomIconEditor.validateImageBytes(bytes).isFailure) {
            iconError = iconInvalidMessage
        } else {
            val normalizedBytes = bytes
            pendingIconBytes = normalizedBytes
            pendingIconName = uri.lastPathSegment
                ?.substringAfterLast('/')
                ?.substringBeforeLast('.')
                ?.takeIf { it.isNotBlank() }
                ?: "Monica icon"
            showCustomIconNameDialog = true
        }
    }

    val fieldNameRequiredMessage = stringResource(R.string.keepass_native_field_name_required)
    val duplicateFieldNameMessage = stringResource(R.string.keepass_native_field_name_duplicate)
    val autoTypeWindowRequiredMessage = stringResource(R.string.keepass_native_auto_type_window_required)
    val autoTypeWindowDuplicateMessage = stringResource(R.string.keepass_native_auto_type_window_duplicate)
    val totpSecretRequiredMessage = stringResource(R.string.keepass_native_totp_secret_required)
    val totpSecretInvalidMessage = stringResource(R.string.keepass_entry_otp_secret_invalid)
    val totpParametersInvalidMessage = stringResource(R.string.keepass_native_totp_parameters_invalid)

    val attachmentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> ->
        uris.forEach { uri ->
            if (pendingAttachmentUris.none { existing -> existing == uri }) {
                pendingAttachmentUris += uri
            }
        }
    }

    fun updateField(id: Long, transform: (NativeEntryEditorField) -> NativeEntryEditorField) {
        val index = fields.indexOfFirst { it.id == id }
        if (index >= 0) fields[index] = transform(fields[index])
    }

    fun validationMessage(): String? {
        val fieldError = validateNativeEntryEditorDraft(NativeEntryEditorDraft(fields.toList()))
        if (fieldError != null) return when (fieldError) {
            NativeEntryDraftError.FIELD_NAME_REQUIRED -> fieldNameRequiredMessage
            NativeEntryDraftError.DUPLICATE_FIELD_NAME -> duplicateFieldNameMessage
        }
        if (totpChanged && totpEnabled && totpSecret.isBlank()) return totpSecretRequiredMessage
        if (totpChanged && totpEnabled && !KeePassTotpCodec.isValidSecret(KeePassTotpCodec.normalizeSecret(totpSecret))) {
            return totpSecretInvalidMessage
        }
        if (totpChanged && totpEnabled && (
                totpPeriod.toIntOrNull()?.takeIf { it > 0 } == null ||
                    totpDigits.toIntOrNull()?.takeIf { it in 1..10 } == null ||
                    (otpType == OtpType.HOTP && hotpCounter.toLongOrNull()?.takeIf { it >= 0L } == null)
                )
        ) {
            return totpParametersInvalidMessage
        }
        if (!autoTypeChanged) return null
        return when (KeePassAutoTypeEditor.validate(
            KeePassAutoTypeDraft(
                enabled = autoTypeEnabled,
                obfuscation = autoTypeObfuscation,
                defaultSequence = autoTypeDefaultSequence,
                rules = autoTypeRules.toList(),
            ),
        )) {
            KeePassAutoTypeDraftError.WINDOW_REQUIRED -> autoTypeWindowRequiredMessage
            KeePassAutoTypeDraftError.DUPLICATE_WINDOW -> autoTypeWindowDuplicateMessage
            null -> null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            templateMode && entry == null -> stringResource(R.string.keepass_native_create_template)
                            templateMode -> stringResource(R.string.keepass_native_edit_template)
                            entry == null -> stringResource(R.string.keepass_native_create_entry)
                            else -> stringResource(R.string.keepass_native_edit_entry)
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !saving) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.go_back),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            error = validationMessage()
                            if (error == null) {
                                saving = true
                                val presentation = if (
                                    predefinedIconChanged ||
                                        selectedCustomIconUuid != entry?.customIconUuid ||
                                        clearCustomIcon ||
                                        pendingCustomIcon != null ||
                                        autoTypeChanged || properties.tagsChanged || properties.expiryChanged
                                ) {
                                    KeePassNativeEntryPresentationUpdate(
                                        predefinedIcon = selectedPredefinedIcon.takeIf { predefinedIconChanged },
                                        customIconUuid = selectedCustomIconUuid,
                                        clearCustomIcon = clearCustomIcon,
                                        customIcon = pendingCustomIcon,
                                        removeCustomIconUuid = entry?.customIconUuid
                                            ?.takeIf { selectedCustomIconUuid != entry.customIconUuid },
                                        autoType = if (autoTypeChanged) {
                                            KeePassAutoTypeDraft(
                                                enabled = autoTypeEnabled,
                                                obfuscation = autoTypeObfuscation,
                                                defaultSequence = autoTypeDefaultSequence,
                                                rules = autoTypeRules.toList(),
                                            ).toPatch()
                                        } else null,
                                        tags = if (properties.tagsChanged) parseNativeEntryTags(properties.tagsText) else null,
                                        expires = properties.expires.takeIf { properties.expiryChanged },
                                        expiryTime = properties.expiryTime.takeIf { properties.expiryChanged },
                                    )
                                } else {
                                    null
                                }
                                val editedFields = NativeEntryEditorDraft(fields.toList()).toFieldChanges()
                                val title = fields.firstOrNull { it.slot == NativeEntryStandardSlot.TITLE }
                                    ?.value.orEmpty()
                                val persistedFields = if (totpChanged) {
                                    mergeNativeTotpFields(
                                        fields = editedFields,
                                        data = if (totpEnabled) {
                                            TotpData(
                                                secret = totpSecret,
                                                issuer = totpIssuer,
                                                accountName = totpAccount,
                                                period = totpPeriod.toInt(),
                                                digits = totpDigits.toInt(),
                                                algorithm = totpAlgorithm,
                                                otpType = otpType,
                                                counter = hotpCounter.toLongOrNull() ?: 0L,
                                            )
                                        } else null,
                                        title = title,
                                    )
                                } else {
                                    editedFields + originalTotpFields
                                }
                                onSave(
                                    persistedFields,
                                    presentation,
                                    pendingAttachmentUris.toList(),
                                ) { failure ->
                                    error = failure
                                    saving = false
                                }
                            }
                        },
                        enabled = savingEnabled && !saving,
                    ) {
                        if (saving) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Check, contentDescription = stringResource(R.string.save))
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding).testTag("native_entry_editor"),
            contentPadding = PaddingValues(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { NativeEditorIntroCard(isNew = entry == null) }
            item {
                NativeCustomIconEditorCard(
                    selectedIcon = selectedCustomIconUuid?.let(customIcons::get),
                    selectedIconUuid = selectedCustomIconUuid,
                    pendingIconBytes = pendingIconBytes ?: pendingCustomIcon?.bytes,
                    pendingIconName = pendingCustomIcon?.name ?: pendingIconName,
                    predefinedIcon = selectedPredefinedIcon,
                    availableIcons = customIcons,
                    iconReferences = customIconReferences,
                    onOpenPicker = { showCustomIconPicker = true },
                    onUpload = { imagePickerLauncher.launch("image/*") },
                    onClear = {
                        selectedCustomIconUuid = null
                        pendingCustomIcon = null
                        pendingIconBytes = null
                        clearCustomIcon = true
                    },
                    onOpenPredefined = { showPredefinedIconPicker = true },
                )
            }
            iconError?.let { message ->
                item {
                    NativeEditorErrorCard(message = message, onDismiss = { iconError = null })
                }
            }
            error?.let { message ->
                item {
                    NativeEditorErrorCard(
                        message = message,
                        onDismiss = { error = null },
                    )
                }
            }
            item {
                NativeEntryCredentialEditorCard(
                    fields = fields,
                    revealedIds = revealedIds,
                    onReveal = { id ->
                        revealedIds = if (id in revealedIds) revealedIds - id else revealedIds + id
                    },
                    onValueChange = { id, value -> updateField(id) { it.copy(value = value) } },
                    onGeneratePassword = {
                        val password = PasswordGenerator.generatePassword(
                            length = 20,
                            includeUppercase = true,
                            includeLowercase = true,
                            includeNumbers = true,
                            includeSymbols = true,
                            excludeSimilar = true,
                            excludeAmbiguous = true,
                        )
                        fields.firstOrNull { it.slot == NativeEntryStandardSlot.PASSWORD }?.let { field ->
                            updateField(field.id) { it.copy(value = password) }
                        }
                    },
                )
            }
            item {
                NativeEntryPropertiesEditorCard(
                    tagsText = properties.tagsText,
                    expires = properties.expires,
                    expiryTime = properties.expiryTime,
                    enabled = savingEnabled && !saving,
                    onTagsChange = { properties.tagsText = it; properties.tagsChanged = true },
                    onExpiresChange = { properties.expires = it; properties.expiryChanged = true },
                    onExpiryTimeChange = { properties.expiryTime = it; properties.expiryChanged = true },
                )
            }
            item {
                NativeTotpEditorCard(
                    enabled = totpEnabled,
                    secret = totpSecret,
                    issuer = totpIssuer,
                    accountName = totpAccount,
                    period = totpPeriod,
                    digits = totpDigits,
                    algorithm = totpAlgorithm,
                    otpType = otpType,
                    hotpCounter = hotpCounter,
                    onEnabledChange = { totpEnabled = it; totpChanged = true },
                    onSecretChange = { totpSecret = it; totpChanged = true },
                    onIssuerChange = { totpIssuer = it; totpChanged = true },
                    onAccountNameChange = { totpAccount = it; totpChanged = true },
                    onPeriodChange = { totpPeriod = it.filter(Char::isDigit); totpChanged = true },
                    onDigitsChange = { totpDigits = it.filter(Char::isDigit); totpChanged = true },
                    onAlgorithmChange = { totpAlgorithm = it; totpChanged = true },
                    onOtpTypeChange = { otpType = it; totpChanged = true },
                    onHotpCounterChange = { hotpCounter = it.filter(Char::isDigit); totpChanged = true },
                )
            }
            item {
                NativePendingAttachmentsCard(
                    pendingUris = pendingAttachmentUris,
                    onAdd = { attachmentPickerLauncher.launch(arrayOf("*/*")) },
                    onRemove = { uri -> pendingAttachmentUris.remove(uri) },
                )
            }
            item {
                NativeAutoTypeEditorCard(
                    enabled = autoTypeEnabled,
                    obfuscation = autoTypeObfuscation,
                    defaultSequence = autoTypeDefaultSequence,
                    rules = autoTypeRules,
                    onEnabledChange = { autoTypeEnabled = it; autoTypeChanged = true },
                    onObfuscationChange = { autoTypeObfuscation = it; autoTypeChanged = true },
                    onDefaultSequenceChange = { autoTypeDefaultSequence = it; autoTypeChanged = true },
                    onRuleChange = { id, transform ->
                        val index = autoTypeRules.indexOfFirst { it.id == id }
                        if (index >= 0) autoTypeRules[index] = transform(autoTypeRules[index])
                        autoTypeChanged = true
                    },
                    onAddRule = {
                        autoTypeRules += KeePassAutoTypeEditor.newRule(autoTypeRules.toList())
                        autoTypeChanged = true
                    },
                    onRemoveRule = { id ->
                        autoTypeRules.removeAll { it.id == id }
                        autoTypeChanged = true
                    },
                )
            }
            item {
                CustomFieldSectionHeader(
                    onAddClick = { if (!saving) fields += newNativeCustomField(fields.toList()) },
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
            }
            val customFields = fields.filter { it.slot == null }
            if (customFields.isEmpty()) {
                item { NativeCustomFieldsEmptyCard() }
            } else {
                items(customFields, key = { it.id }) { field ->
                    CustomFieldEditCard(
                        index = customFields.indexOf(field),
                        field = field.toCustomFieldDraft(),
                        onFieldChange = { updated ->
                            updateField(field.id) {
                                it.copy(
                                    name = updated.title,
                                    value = updated.value,
                                    protected = updated.isProtected,
                                )
                            }
                        },
                        onDelete = { fields.removeAll { it.id == field.id } },
                    )
                }
            }
        }
    }

    if (showCustomIconPicker) {
        NativeCustomIconPickerDialog(
            icons = customIcons,
            iconReferences = customIconReferences,
            selectedUuid = selectedCustomIconUuid,
            onSelect = { uuid ->
                selectedCustomIconUuid = uuid
                pendingCustomIcon = null
                clearCustomIcon = false
                showCustomIconPicker = false
            },
            deletingUuid = deletingIconUuid,
                     onDelete = { uuid ->
                deletingIconUuid = uuid
                onDeleteCustomIcon(uuid) { failure ->
                    deletingIconUuid = null
                    if (failure != null) iconError = failure
                 }
             },
             onRename = { uuid, name ->
                 renamingIconUuid = uuid
                 renamingIconName = name
             },
             onDismiss = { showCustomIconPicker = false },
        )
    }
    if (showPredefinedIconPicker) {
        NativePredefinedIconPickerDialog(
            selectedIcon = selectedPredefinedIcon,
            onSelect = { selected ->
                selectedPredefinedIcon = selected
                predefinedIconChanged = true
                if (selectedCustomIconUuid != null || pendingCustomIcon != null) {
                    selectedCustomIconUuid = null
                    pendingCustomIcon = null
                    clearCustomIcon = true
                }
                showPredefinedIconPicker = false
            },
            onDismiss = { showPredefinedIconPicker = false },
        )
    }
    if (showCustomIconNameDialog) {
        KeePassCustomIconNameDialog(
            name = pendingIconName,
            onNameChange = { pendingIconName = it },
            onConfirm = {
                val bytes = pendingIconBytes
                if (bytes == null || pendingIconName.isBlank()) {
                    iconError = "Icon name cannot be empty"
                    showCustomIconNameDialog = false
                } else {
                    pendingCustomIcon = KeePassNativeCustomIconPayload(
                        bytes = bytes,
                        name = pendingIconName.trim(),
                    )
                    pendingIconBytes = null
                    selectedCustomIconUuid = null
                    clearCustomIcon = false
                    showCustomIconNameDialog = false
                }
            },
            onDismiss = {
                pendingIconBytes = null
                showCustomIconNameDialog = false
            },
        )
    }
    if (renamingIconUuid != null) {
        KeePassCustomIconNameDialog(
            name = renamingIconName,
            onNameChange = { renamingIconName = it },
            onConfirm = {
                renamingIconUuid?.let { uuid ->
                    onRenameCustomIcon(uuid, renamingIconName.trim()) { failure ->
                        if (failure == null) {
                            renamingIconUuid = null
                            showCustomIconPicker = false
                        } else {
                            iconError = failure
                        }
                    }
                }
            },
            onDismiss = { renamingIconUuid = null },
        )
    }
}

@Composable
private fun NativeTotpEditorCard(
    enabled: Boolean,
    secret: String,
    issuer: String,
    accountName: String,
    period: String,
    digits: String,
    algorithm: String,
    otpType: OtpType,
    hotpCounter: String,
    onEnabledChange: (Boolean) -> Unit,
    onSecretChange: (String) -> Unit,
    onIssuerChange: (String) -> Unit,
    onAccountNameChange: (String) -> Unit,
    onPeriodChange: (String) -> Unit,
    onDigitsChange: (String) -> Unit,
    onAlgorithmChange: (String) -> Unit,
    onOtpTypeChange: (OtpType) -> Unit,
    onHotpCounterChange: (String) -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.keepass_native_totp_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.keepass_native_totp_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
            if (enabled) {
                OutlinedTextField(
                    value = secret,
                    onValueChange = onSecretChange,
                    label = { Text(stringResource(R.string.totp_secret)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = issuer,
                        onValueChange = onIssuerChange,
                        label = { Text(stringResource(R.string.issuer)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = accountName,
                        onValueChange = onAccountNameChange,
                        label = { Text(stringResource(R.string.account_name)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = period,
                        onValueChange = onPeriodChange,
                        label = { Text(stringResource(R.string.time_period_seconds)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = digits,
                        onValueChange = onDigitsChange,
                        label = { Text(stringResource(R.string.code_digits)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NativeTotpChoiceButton(
                        label = stringResource(R.string.otp_type_totp),
                        selected = otpType == OtpType.TOTP,
                        onClick = { onOtpTypeChange(OtpType.TOTP) },
                        modifier = Modifier.weight(1f),
                    )
                    NativeTotpChoiceButton(
                        label = stringResource(R.string.otp_type_hotp),
                        selected = otpType == OtpType.HOTP,
                        onClick = { onOtpTypeChange(OtpType.HOTP) },
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = algorithm,
                    onValueChange = { value -> onAlgorithmChange(value.uppercase()) },
                    label = { Text(stringResource(R.string.passkey_detail_algorithm)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (otpType == OtpType.HOTP) {
                    OutlinedTextField(
                        value = hotpCounter,
                        onValueChange = onHotpCounterChange,
                        label = { Text(stringResource(R.string.hotp_counter_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun NativeTotpChoiceButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        if (selected) Icon(Icons.Default.Check, contentDescription = null)
        Spacer(Modifier.size(if (selected) 6.dp else 0.dp))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun NativePendingAttachmentsCard(
    pendingUris: List<Uri>,
    onAdd: () -> Unit,
    onRemove: (Uri) -> Unit,
) {
    val context = LocalContext.current
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.attachments), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onAdd) {
                    Icon(Icons.Default.AttachFile, contentDescription = stringResource(R.string.attachments_add))
                }
            }
            if (pendingUris.isEmpty()) {
                Text(
                    stringResource(R.string.keepass_native_pending_attachments_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                pendingUris.forEach { uri ->
                    val label = remember(uri) { nativeAttachmentDisplayName(context, uri) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { onRemove(uri) }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.attachment_delete))
                        }
                    }
                }
            }
        }
    }
}

private fun nativeAttachmentDisplayName(context: Context, uri: Uri): String {
    val queried = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
    return queried?.takeIf(String::isNotBlank)
        ?: uri.lastPathSegment?.substringAfterLast('/')
        ?: uri.toString()
}

@Composable
private fun NativeAutoTypeEditorCard(
    enabled: Boolean,
    obfuscation: AutoTypeObfuscation,
    defaultSequence: String,
    rules: List<KeePassAutoTypeRuleDraft>,
    onEnabledChange: (Boolean) -> Unit,
    onObfuscationChange: (AutoTypeObfuscation) -> Unit,
    onDefaultSequenceChange: (String) -> Unit,
    onRuleChange: (Long, (KeePassAutoTypeRuleDraft) -> KeePassAutoTypeRuleDraft) -> Unit,
    onAddRule: () -> Unit,
    onRemoveRule: (Long) -> Unit,
) {
    var obfuscationMenuExpanded by remember { mutableStateOf(false) }
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.keepass_native_entry_auto_type_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(R.string.keepass_native_entry_auto_type_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
            OutlinedTextField(
                value = defaultSequence,
                onValueChange = onDefaultSequenceChange,
                label = { Text(stringResource(R.string.keepass_native_auto_type_default_sequence)) },
                supportingText = { Text(stringResource(R.string.keepass_native_auto_type_tokens_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                minLines = 1,
            )
            Box {
                OutlinedButton(
                    onClick = { obfuscationMenuExpanded = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            R.string.keepass_native_auto_type_obfuscation,
                            if (obfuscation == AutoTypeObfuscation.UseClipboard) {
                                stringResource(R.string.keepass_native_auto_type_clipboard)
                            } else {
                                stringResource(R.string.keepass_native_auto_type_none)
                            },
                        ),
                    )
                }
                DropdownMenu(
                    expanded = obfuscationMenuExpanded,
                    onDismissRequest = { obfuscationMenuExpanded = false },
                ) {
                    AutoTypeObfuscation.entries.forEach { value ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (value == AutoTypeObfuscation.UseClipboard) {
                                        stringResource(R.string.keepass_native_auto_type_clipboard)
                                    } else {
                                        stringResource(R.string.keepass_native_auto_type_none)
                                    },
                                )
                            },
                            leadingIcon = {
                                if (value == obfuscation) Icon(Icons.Default.Check, contentDescription = null)
                            },
                            onClick = {
                                onObfuscationChange(value)
                                obfuscationMenuExpanded = false
                            },
                        )
                    }
                }
            }
            if (rules.isNotEmpty()) HorizontalDivider()
            rules.forEach { rule ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = rule.window,
                            onValueChange = { value -> onRuleChange(rule.id) { it.copy(window = value) } },
                            label = { Text(stringResource(R.string.keepass_native_auto_type_window)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = rule.sequence,
                            onValueChange = { value -> onRuleChange(rule.id) { it.copy(sequence = value) } },
                            label = { Text(stringResource(R.string.keepass_native_auto_type_sequence)) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 1,
                        )
                    }
                    IconButton(onClick = { onRemoveRule(rule.id) }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.keepass_native_auto_type_remove_rule),
                        )
                    }
                }
            }
            OutlinedButton(onClick = onAddRule, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.keepass_native_auto_type_add_rule))
            }
        }
    }
}

@Composable
private fun NativeCustomIconEditorCard(
    selectedIcon: CustomIcon?,
    selectedIconUuid: UUID?,
    pendingIconBytes: ByteArray?,
    pendingIconName: String,
    predefinedIcon: PredefinedIcon?,
    availableIcons: Map<UUID, CustomIcon>,
    iconReferences: Map<UUID, Int>,
    onOpenPicker: () -> Unit,
    onUpload: () -> Unit,
    onClear: () -> Unit,
    onOpenPredefined: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.keepass_native_custom_icon_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    val previewBytes = pendingIconBytes ?: selectedIcon?.data
                    val bitmap = previewBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = pendingIconName.takeIf { pendingIconBytes != null }
                                ?: selectedIcon?.name,
                            modifier = Modifier.padding(8.dp),
                        )
                    } else {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.padding(14.dp))
                    }
                }
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        pendingIconName.takeIf { pendingIconBytes != null }
                            ?: selectedIcon?.name
                            ?: stringResource(R.string.keepass_native_custom_icon_none),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        if (pendingIconBytes != null) {
                            stringResource(R.string.keepass_native_custom_icon_hint)
                        } else if (selectedIconUuid != null) {
                            stringResource(R.string.keepass_native_custom_icon_stored)
                        } else {
                            stringResource(R.string.keepass_native_custom_icon_hint)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (selectedIconUuid != null || pendingIconBytes != null) {
                    IconButton(onClick = onClear) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.keepass_native_custom_icon_clear),
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenPicker, modifier = Modifier.weight(1f), enabled = availableIcons.isNotEmpty()) {
                    Text(stringResource(R.string.keepass_native_custom_icon_choose))
                }
                Button(onClick = onUpload, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.keepass_native_custom_icon_upload))
                }
            }
            OutlinedButton(
                onClick = onOpenPredefined,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        R.string.keepass_native_custom_icon_builtin,
                        predefinedIcon?.name ?: "Key",
                    ),
                )
            }
        }
    }
}

@Composable
internal fun NativePredefinedIconPickerDialog(
    selectedIcon: PredefinedIcon?,
    onSelect: (PredefinedIcon) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.keepass_native_custom_icon_builtin_picker)) },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(64.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                gridItems(predefinedIconPickerItems(selectedIcon), key = { it.name }) { icon ->
                    Surface(
                        modifier = Modifier
                            .size(64.dp)
                            .clickable { onSelect(icon) },
                        shape = RoundedCornerShape(16.dp),
                        color = if (icon == selectedIcon) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = keepassPredefinedIconVector(icon),
                                contentDescription = icon.name,
                                modifier = Modifier.size(28.dp),
                            )
                            if (icon == selectedIcon) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(5.dp)
                                        .size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

internal fun keepassPredefinedIconVector(icon: PredefinedIcon): ImageVector {
    val name = icon.name.lowercase(Locale.ROOT)
    return when {
        "folderopen" in name || ("folder" in name && "open" in name) -> Icons.Default.FolderOpen
        "folder" in name || "directory" in name -> Icons.Default.Folder
        "world" in name || "internet" in name || "url" in name -> Icons.Default.Language
        "warning" in name || "danger" in name -> Icons.Default.Warning
        "network" in name || "server" in name -> Icons.Default.Dns
        "database" in name || "storage" in name -> Icons.Default.Storage
        "computer" in name || "desktop" in name -> Icons.Default.Computer
        "mobile" in name || "phone" in name -> Icons.Default.Smartphone
        "mail" in name || "email" in name -> Icons.Default.Email
        "identity" in name || "badge" in name || "user" in name -> Icons.Default.Badge
        "credit" in name || "card" in name -> Icons.Default.CreditCard
        "money" in name || "bank" in name || "finance" in name -> Icons.Default.AccountBalance
        "cart" in name || "shopping" in name -> Icons.Default.ShoppingCart
        "camera" in name || "photo" in name || "picture" in name -> Icons.Default.PhotoCamera
        "clock" in name || "time" in name || "calendar" in name -> Icons.Default.Schedule
        "setting" in name || "gear" in name || "tools" in name -> Icons.Default.Settings
        "part" in name || "plugin" in name || "extension" in name -> Icons.Default.Extension
        "note" in name || "text" in name || "document" in name -> Icons.Default.Description
        "home" in name -> Icons.Default.Home
        "star" in name || "favorite" in name -> Icons.Default.Star
        "cloud" in name -> Icons.Default.Cloud
        "work" in name || "briefcase" in name -> Icons.Default.Work
        "lock" in name || "secure" in name -> Icons.Default.Lock
        else -> Icons.Default.VpnKey
    }
}

/**
 * The KeePass format has more predefined IDs than the Material icon set used
 * by Monica. Several IDs therefore render to the same fallback vector. Keep a
 * single representative in the picker, but retain an existing selected ID so
 * editing an older entry never makes its icon appear unavailable.
 */
internal fun predefinedIconPickerItems(selectedIcon: PredefinedIcon?): List<PredefinedIcon> {
    val visible = PredefinedIcon.values()
        .toList()
        .distinctBy { keepassPredefinedIconVector(it).name }
    return if (selectedIcon != null && selectedIcon !in visible) {
        listOf(selectedIcon) + visible
    } else {
        visible
    }
}

@Composable
internal fun KeePassCustomIconNameDialog(
    name: String,
    onNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.keepass_native_custom_icon_name_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                singleLine = true,
                label = { Text(stringResource(R.string.keepass_native_custom_icon_name_label)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
internal fun NativeCustomIconPickerDialog(
    icons: Map<UUID, CustomIcon>,
    iconReferences: Map<UUID, Int>,
    selectedUuid: UUID?,
    onSelect: (UUID) -> Unit,
    deletingUuid: UUID?,
    onDelete: ((UUID) -> Unit)?,
    onRename: ((UUID, String) -> Unit)?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.keepass_native_custom_icon_picker_title)) },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    KeePassCustomIconEditor.list(icons, iconReferences),
                    key = { it.uuid },
                ) { item ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(item.uuid) },
                        shape = RoundedCornerShape(14.dp),
                        color = if (item.uuid == selectedUuid) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        },
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val bitmap = BitmapFactory.decodeByteArray(
                                item.icon.data,
                                0,
                                item.icon.data.size,
                            )
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = item.icon.name,
                                    modifier = Modifier.size(32.dp),
                                )
                            } else {
                                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(32.dp))
                            }
                            Spacer(Modifier.size(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.icon.name ?: stringResource(R.string.keepass_native_custom_icon_none))
                                Text(
                                    if (item.isReferenced) {
                                        stringResource(
                                            R.string.keepass_native_custom_icon_in_use,
                                            item.referenceCount,
                                        )
                                    } else {
                                        stringResource(R.string.keepass_native_custom_icon_unused)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (onDelete != null && !item.isReferenced && item.uuid != selectedUuid) {
                                IconButton(
                                    onClick = { onDelete(item.uuid) },
                                    enabled = deletingUuid != item.uuid,
                                ) {
                                    if (deletingUuid == item.uuid) {
                                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    } else {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = stringResource(R.string.keepass_native_custom_icon_clear),
                                        )
                                    }
                                }
                            }
                            if (onRename != null) {
                                IconButton(onClick = { onRename(item.uuid, item.icon.name.orEmpty()) }) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = stringResource(R.string.keepass_native_custom_icon_rename),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun NativeEditorIntroCard(isNew: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.VpnKey, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    if (isNew) stringResource(R.string.keepass_native_create_entry_hint_title)
                    else stringResource(R.string.keepass_native_edit_entry_hint_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.keepass_native_editor_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                )
            }
        }
    }
}

@Composable
private fun NativeEditorErrorCard(message: String, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 10.dp, end = 6.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.VisibilityOff, contentDescription = stringResource(R.string.cancel))
            }
        }
    }
}

@Composable
private fun NativeEntryCredentialEditorCard(
    fields: List<NativeEntryEditorField>,
    revealedIds: Set<Long>,
    onReveal: (Long) -> Unit,
    onValueChange: (Long, String) -> Unit,
    onGeneratePassword: () -> Unit,
) {
    val labels = mapOf(
        NativeEntryStandardSlot.TITLE to stringResource(R.string.title),
        NativeEntryStandardSlot.USERNAME to stringResource(R.string.username),
        NativeEntryStandardSlot.PASSWORD to stringResource(R.string.password),
        NativeEntryStandardSlot.URL to stringResource(R.string.website),
        NativeEntryStandardSlot.NOTES to stringResource(R.string.notes),
    )
    val icons: Map<NativeEntryStandardSlot, ImageVector> = mapOf(
        NativeEntryStandardSlot.TITLE to Icons.Default.Edit,
        NativeEntryStandardSlot.USERNAME to Icons.Default.Person,
        NativeEntryStandardSlot.PASSWORD to Icons.Default.VpnKey,
        NativeEntryStandardSlot.URL to Icons.Default.Link,
        NativeEntryStandardSlot.NOTES to Icons.Outlined.Notes,
    )
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.keepass_native_credentials_section),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            NativeEntryStandardSlot.entries.forEach { slot ->
                val field = fields.firstOrNull { it.slot == slot } ?: return@forEach
                NativeStandardEditorField(
                    field = field,
                    slot = slot,
                    label = labels.getValue(slot),
                    icon = icons.getValue(slot),
                    revealed = field.id in revealedIds,
                    onReveal = { onReveal(field.id) },
                    onValueChange = { onValueChange(field.id, it) },
                    onGenerate = if (slot == NativeEntryStandardSlot.PASSWORD) onGeneratePassword else null,
                )
            }
        }
    }
}

@Composable
private fun NativeStandardEditorField(
    field: NativeEntryEditorField,
    slot: NativeEntryStandardSlot,
    label: String,
    icon: ImageVector,
    revealed: Boolean,
    onReveal: () -> Unit,
    onValueChange: (String) -> Unit,
    onGenerate: (() -> Unit)?,
) {
    val protected = slot == NativeEntryStandardSlot.PASSWORD || field.protected
    OutlinedTextField(
        value = field.value,
        onValueChange = onValueChange,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                onGenerate?.let { generate ->
                    IconButton(onClick = generate) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = stringResource(R.string.keepass_native_generate_password))
                    }
                }
                if (protected) {
                    IconButton(onClick = onReveal) {
                        Icon(
                            if (revealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (revealed) {
                                stringResource(R.string.custom_field_hide_content)
                            } else {
                                stringResource(R.string.custom_field_show_content)
                            },
                        )
                    }
                }
            }
        },
        visualTransformation = if (protected && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (slot == NativeEntryStandardSlot.URL) KeyboardType.Uri else KeyboardType.Text,
            imeAction = if (slot == NativeEntryStandardSlot.NOTES) ImeAction.Default else ImeAction.Next,
        ),
        minLines = if (slot == NativeEntryStandardSlot.NOTES) 3 else 1,
        maxLines = if (slot == NativeEntryStandardSlot.NOTES) 6 else 1,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    )
}

@Composable
private fun NativeCustomFieldsEmptyCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                stringResource(R.string.keepass_native_custom_fields_hint),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

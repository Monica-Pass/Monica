package takagi.ru.monica.ui.screens


import takagi.ru.monica.data.model.CredentialExchangeMetadata

import takagi.ru.monica.data.passwordProjectKey
import takagi.ru.monica.data.model.PasswordUrlRules
import takagi.ru.monica.ui.components.EditorActionMenu
import takagi.ru.monica.ui.components.EditorMenuAction
import takagi.ru.monica.ui.components.UrlMatchModeDialog
import takagi.ru.monica.ui.components.urlMatchModeLabel
import takagi.ru.monica.data.model.ProjectCredentialGroup
import takagi.ru.monica.ui.components.ProjectCredentialEditor
import takagi.ru.monica.ui.components.CredentialPasswordActions
import takagi.ru.monica.ui.components.CredentialNoteField
import androidx.compose.animation.animateContentSize
import kotlinx.serialization.json.jsonObject
import takagi.ru.monica.data.model.PasswordContentBlocks
import takagi.ru.monica.ui.components.PasswordContentBlockCard
import takagi.ru.monica.ui.components.PasswordContentBlockEditor
import takagi.ru.monica.ui.components.PasswordContentBlockDetail
import takagi.ru.monica.data.model.EmbeddedWalletContent
import takagi.ru.monica.attachments.EmbeddedWalletCopyService
import takagi.ru.monica.attachments.EmbeddedWalletDraftStore

import androidx.compose.foundation.background

import takagi.ru.monica.ui.components.MonicaExpandableCard
import takagi.ru.monica.ui.components.MonicaExpandableContent
import takagi.ru.monica.ui.components.localizedName

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import sh.calvin.reorderable.rememberReorderableLazyListState
import takagi.ru.monica.ui.components.entryContentItemKey
import takagi.ru.monica.ui.components.reorderableContentItem
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import takagi.ru.monica.data.model.EntryContentFields
import takagi.ru.monica.ui.components.EntrySupplementalSpecs
import takagi.ru.monica.ui.components.EntrySupplementalFields
import takagi.ru.monica.ui.components.PasswordContentAddButton
import takagi.ru.monica.ui.components.PasswordContentMenu
import takagi.ru.monica.ui.components.PasswordContentSection
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import takagi.ru.monica.R
import takagi.ru.monica.data.AppSettings
import takagi.ru.monica.data.CustomFieldDraft
import takagi.ru.monica.data.GeneratorPreferences
import takagi.ru.monica.data.GeneratorPreferencesManager
import takagi.ru.monica.data.toSymbolPasswordGeneratorOptions
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.LinkedAppBinding
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.PresetCustomField
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.addOrReplaceLinkedAppBinding
import takagi.ru.monica.data.parseLinkedAppBindings
import takagi.ru.monica.data.removeLinkedAppBinding
import takagi.ru.monica.data.bitwarden.BitwardenFolder
import takagi.ru.monica.data.model.StorageTarget
import takagi.ru.monica.data.model.normalizedStorageTargets
import takagi.ru.monica.data.model.storageScopeKey
import takagi.ru.monica.data.model.toStorageTarget
import takagi.ru.monica.data.model.withStorageTargetSelected
import takagi.ru.monica.data.model.withoutStorageTarget
import takagi.ru.monica.data.model.BankCardData
import takagi.ru.monica.data.model.BillingAddress
import takagi.ru.monica.data.model.OtpType
import takagi.ru.monica.data.model.TotpData
import takagi.ru.monica.data.model.isEmpty
import takagi.ru.monica.data.model.LOGIN_TYPE_BARCODE
import takagi.ru.monica.data.model.isBarcodeEntry
import takagi.ru.monica.data.model.isSshKeyEntry
import takagi.ru.monica.attachments.facade.AttachmentFacade
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.components.AppSelectorDialog
import takagi.ru.monica.ui.components.CustomIconActionDialog
import takagi.ru.monica.ui.components.EmojiIconInputDialog
import takagi.ru.monica.ui.components.InstalledIconPickerBottomSheet
import takagi.ru.monica.ui.components.CustomFieldEditorSection
import takagi.ru.monica.ui.components.CustomFieldEditCard
import takagi.ru.monica.ui.components.CustomFieldSectionHeader
import takagi.ru.monica.ui.components.EntryTypeChip
import takagi.ru.monica.ui.components.EntryTypeChipOption
import takagi.ru.monica.ui.components.InlineTotpPreviewCard
import takagi.ru.monica.ui.components.MultiStorageTargetPickerBottomSheet
import takagi.ru.monica.ui.components.MultiStorageTargetSelectorCard
import takagi.ru.monica.ui.components.MonicaExpressiveFilterChip
import takagi.ru.monica.ui.components.MonicaModalBottomSheet
import takagi.ru.monica.ui.components.NotePickerBottomSheet
import takagi.ru.monica.ui.components.PasswordEntryPickerBottomSheet
import takagi.ru.monica.ui.components.PasswordCredentialPickerSheet
import takagi.ru.monica.ui.components.PasswordCredentialEditorBar
import takagi.ru.monica.ui.components.PasswordStrengthIndicator
import takagi.ru.monica.ui.components.keepassBlockReasonLabel
import takagi.ru.monica.ui.components.SimpleIconPickerBottomSheet
import takagi.ru.monica.ui.icons.MonicaIcons
import takagi.ru.monica.ui.icons.PASSWORD_ICON_TYPE_NONE
import takagi.ru.monica.ui.icons.PASSWORD_ICON_TYPE_SIMPLE
import takagi.ru.monica.ui.icons.PASSWORD_ICON_TYPE_UPLOADED
import takagi.ru.monica.ui.icons.PASSWORD_ICON_TYPE_EMOJI
import takagi.ru.monica.ui.icons.EmojiIconText
import takagi.ru.monica.ui.icons.PasswordCustomIconStore
import takagi.ru.monica.ui.icons.SimpleIconCatalog
import takagi.ru.monica.ui.icons.SimpleIconOption
import takagi.ru.monica.ui.icons.rememberAutoMatchedSimpleIcon
import takagi.ru.monica.ui.icons.rememberSimpleIconBitmap
import takagi.ru.monica.ui.icons.rememberUploadedPasswordIcon
import takagi.ru.monica.ui.password.UsernameSuggestionPanel
import takagi.ru.monica.ui.password.UsernameSuggestionState
import takagi.ru.monica.ui.password.buildUsernameSuggestionState
import takagi.ru.monica.util.OtpParametersDraft
import takagi.ru.monica.ui.components.OtpTypeSelector
import takagi.ru.monica.ui.components.OtpParameterFields
import takagi.ru.monica.util.TotpDataResolver
import takagi.ru.monica.util.PasswordGenerator as AdvancedPasswordGenerator
import takagi.ru.monica.utils.PasswordWebsiteCodec
import takagi.ru.monica.utils.PasswordStrengthAnalyzer
import takagi.ru.monica.utils.ClipboardUtils
import takagi.ru.monica.utils.decodeKeePassPathForDisplay
import takagi.ru.monica.viewmodel.BankCardViewModel
import takagi.ru.monica.viewmodel.CategoryFilter
import takagi.ru.monica.viewmodel.NoteViewModel
import takagi.ru.monica.viewmodel.PasswordViewModel
import takagi.ru.monica.viewmodel.PasswordCredentialDraft
import takagi.ru.monica.viewmodel.buildEditedPasswordCredentialSavePlan
import takagi.ru.monica.viewmodel.mergePasswordCredentialCustomFields
import takagi.ru.monica.viewmodel.TotpViewModel

import takagi.ru.monica.viewmodel.LocalKeePassViewModel
import takagi.ru.monica.viewmodel.MdbxViewModel
import takagi.ru.monica.data.LocalKeePassDatabase
import takagi.ru.monica.data.KeePassOperationBlockReason
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.data.writeOperationAvailability
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.autofill_ng.ui.rememberFavicon
import takagi.ru.monica.domain.provider.PasswordSource
import takagi.ru.monica.ui.model.SecretValueState
import takagi.ru.monica.ui.model.plainValueOrEmpty
import java.io.File
import java.util.Locale
import takagi.ru.monica.ui.components.OutlinedTextField
import takagi.ru.monica.ui.components.SuggestedOutlinedTextField
import takagi.ru.monica.data.CommonSuggestionField

private const val MONICA_USERNAME_ALIAS_FIELD_TITLE = "__monica_username_alias"
private const val MONICA_USERNAME_ALIAS_META_FIELD_TITLE = "__monica_username_alias_meta"
private const val MONICA_USERNAME_ALIAS_META_VALUE = "migrated_v1"
private const val ICON_PICKER_PAGE_SIZE = 120

private enum class MultiCredentialEditorSection {
    COMMON,
    CREDENTIAL
}

@Stable
private class CredentialMetadataDraft {
    var credentialNote by mutableStateOf("")
    var notes by mutableStateOf("")
    var boundNoteId by mutableStateOf<Long?>(null)
    val emails = mutableStateListOf("")
    val phones = mutableStateListOf("")
    var addressLine by mutableStateOf("")
    var city by mutableStateOf("")
    var state by mutableStateOf("")
    var zipCode by mutableStateOf("")
    var country by mutableStateOf("")
    var creditCardNumber by mutableStateOf("")
    var creditCardHolder by mutableStateOf("")
    var creditCardExpiry by mutableStateOf("")
    var creditCardCVV by mutableStateOf("")
    val credentialCustomFields = mutableStateListOf<CustomFieldDraft>()

    fun replaceEmails(values: List<String>) {
        emails.clear()
        emails.addAll(values.ifEmpty { listOf("") })
    }

    fun replacePhones(values: List<String>) {
        phones.clear()
        phones.addAll(values.ifEmpty { listOf("") })
    }
}

private data class CommonAccountFillOption(
    val id: String,
    val type: String,
    val content: String
)

private data class PasswordTotpBindingCandidate(
    val item: SecureItem,
    val data: TotpData
)

private enum class PasswordTotpPickerSourceFilter {
    ALL,
    LOCAL,
    KEEPASS,
    MDBX,
    BITWARDEN
}

private enum class PasswordFillMode {
    GENERATOR,
    COMMON_ACCOUNT
}

data class AddEditPasswordInitialDraft(
    val title: String = "",
    val website: String = "",
    val username: String = "",
    val password: String = "",
    val appPackageName: String = "",
    val appName: String = "",
    val template: takagi.ru.monica.data.model.TemplateCredentialDraft? = null,
)

private data class KeePassOperationBlockUiState(
    val databaseName: String,
    val reason: KeePassOperationBlockReason
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditPasswordScreen(
    viewModel: PasswordViewModel,
    totpViewModel: TotpViewModel? = null,
    bankCardViewModel: BankCardViewModel? = null,
    noteViewModel: NoteViewModel? = null,
    localKeePassViewModel: LocalKeePassViewModel? = null,
    localMdbxViewModel: MdbxViewModel? = null,
    mdbxDatabasesFallback: List<takagi.ru.monica.data.LocalMdbxDatabase> = emptyList(),
    passwordId: Long?,
    initialDraft: AddEditPasswordInitialDraft? = null,
    autofillPasswordUpdate: String? = null,
    forceShowAppBinding: Boolean = false,
    initialCategoryId: Long? = null,
    initialStorageExplicit: Boolean = false,
    initialKeePassDatabaseId: Long? = null,
    initialKeePassGroupPath: String? = null,
    initialMdbxDatabaseId: Long? = null,
    initialMdbxFolderId: String? = null,
    initialBitwardenVaultId: Long? = null,
    initialBitwardenFolderId: String? = null,
    pendingQrResult: String? = null,
    initialLoginType: String? = null,
    onConsumePendingQrResult: () -> Unit = {},
    onScanAuthenticatorQrCode: (() -> Unit)? = null,
    onSaveCompleted: ((Long?) -> Unit)? = null,
    onSwitchToApiToken: ((StorageTarget.Mdbx?) -> Unit)? = null,
    onSwitchToWifi: ((Long?) -> Unit)? = null,
    onSwitchToSshKey: ((Long?) -> Unit)? = null,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val syncEdit = takagi.ru.monica.lansync.DeviceSyncEditGate.hold()
        onDispose { syncEdit.close() }
    }
    val database = remember(context) { PasswordDatabase.getDatabase(context) }
    val security = takagi.ru.monica.ui.rememberUiSecurityManager()
    val repository = remember(database, security) { takagi.ru.monica.repository.SecureItemRepository(database.secureItemDao(), decryptSensitiveValue = security::decryptDataIfMonicaCiphertext) }
    viewModel.monicaKeePassStore?.let(repository::bindMonicaKeePassStore)
    val banks = bankCardViewModel ?: androidx.lifecycle.viewmodel.compose.viewModel(key = "shared-template-banks") {
        BankCardViewModel(repository, context, database.localKeePassDatabaseDao(), security, strings = takagi.ru.monica.utils.AppLocaleStringResolver(context))
    }
    val notesModel = noteViewModel ?: androidx.lifecycle.viewmodel.compose.viewModel(key = "shared-template-notes") {
        NoteViewModel(repository, context = context, localKeePassDatabaseDao = database.localKeePassDatabaseDao(), securityManager = security, strings = takagi.ru.monica.utils.AppLocaleStringResolver(context))
    }
    var selectedTemplate by remember(passwordId, initialLoginType) { mutableStateOf(initialLoginType) }
    // Once the app locks, dispose the editor and its memory-only secrets. Unlocking
    // must open a fresh screen rather than revive a stale sensitive draft.
    var sessionRevoked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        var wasUnlocked = takagi.ru.monica.security.SessionManager.isUnlocked.value
        takagi.ru.monica.security.SessionManager.isUnlocked.collect { unlocked ->
            if (wasUnlocked && !unlocked) sessionRevoked = true
            wasUnlocked = unlocked
        }
    }
    if (sessionRevoked) return
    val creationFilter by viewModel.categoryFilter.collectAsState()
    val inheritedCreationTargets = takagi.ru.monica.ui.components.LocalTemplateTargets.current
    val creationTargets = remember(passwordId, initialStorageExplicit, initialCategoryId,
        initialKeePassDatabaseId, initialKeePassGroupPath, initialMdbxDatabaseId, initialMdbxFolderId,
        initialBitwardenVaultId, initialBitwardenFolderId, inheritedCreationTargets) {
        passwordCreationTargets(creationFilter, inheritedCreationTargets, initialStorageExplicit,
            initialCategoryId, initialKeePassDatabaseId, initialKeePassGroupPath, initialMdbxDatabaseId,
            initialMdbxFolderId, initialBitwardenVaultId, initialBitwardenFolderId)
    }
    if ((passwordId == null || passwordId <= 0) && MdbxPasswordCreationRoute(
            creationTargets, localMdbxViewModel, onNavigateBack)) return
    val nativeCreationTarget = if (passwordId == null) creationTargets.singleOrNull() as? StorageTarget.KeePass else null
    val nativeDatabaseId = passwordId?.let { viewModel.nativeKeePassDatabaseId(kotlin.math.abs(it)) } ?: nativeCreationTarget?.databaseId
    val nativeLockVersions by takagi.ru.monica.utils.KeePassKdbxService.nativeLockVersions.collectAsState()
    val originalNativeLockVersion = remember(passwordId, nativeDatabaseId) { nativeLockVersions[nativeDatabaseId] ?: 0L }
    LaunchedEffect(nativeDatabaseId, nativeLockVersions[nativeDatabaseId]) {
        if (nativeDatabaseId != null && (nativeLockVersions[nativeDatabaseId] ?: 0L) != originalNativeLockVersion) {
            sessionRevoked = true
            onNavigateBack()
        }
    }
    DisposableEffect(passwordId, nativeCreationTarget?.databaseId) {
        val nativeRead = if (nativeCreationTarget != null) viewModel.holdNativeKeePassRead(nativeCreationTarget.databaseId)
            else passwordId?.let { viewModel.holdNativeKeePassEntryRead(it) }
        onDispose {
            nativeRead?.close()
            nativeCreationTarget?.let { viewModel.refreshKeePassOverviewAfterNativeRead(it.databaseId) }
        }
    }
    key(passwordId, selectedTemplate) {
    PasswordEntryEditor(
        viewModel = viewModel,
        totpViewModel = totpViewModel,
        bankCardViewModel = banks,
        noteViewModel = notesModel,
        localKeePassViewModel = localKeePassViewModel,
        localMdbxViewModel = localMdbxViewModel,
        mdbxDatabasesFallback = mdbxDatabasesFallback,
        passwordId = passwordId,
        initialDraft = initialDraft,
        autofillPasswordUpdate = autofillPasswordUpdate,
        forceShowAppBinding = forceShowAppBinding,
        initialCategoryId = initialCategoryId,
        initialStorageExplicit = initialStorageExplicit,
        initialKeePassDatabaseId = initialKeePassDatabaseId,
        initialKeePassGroupPath = initialKeePassGroupPath,
        initialMdbxDatabaseId = initialMdbxDatabaseId,
        initialMdbxFolderId = initialMdbxFolderId,
        initialBitwardenVaultId = initialBitwardenVaultId,
        initialBitwardenFolderId = initialBitwardenFolderId,
        pendingQrResult = pendingQrResult,
        initialLoginType = selectedTemplate,
        onConsumePendingQrResult = onConsumePendingQrResult,
        onScanAuthenticatorQrCode = onScanAuthenticatorQrCode,
        onSaveCompleted = onSaveCompleted,
        onSwitchToApiToken = onSwitchToApiToken,
        onSwitchToWifi = onSwitchToWifi,
        onSwitchToSshKey = onSwitchToSshKey,
        onNavigateBack = onNavigateBack,
        creationTargets = creationTargets,
        onSwitchToGpg = { selectedTemplate = "GPG_KEY" },
        onSwitchToApiKey = { selectedTemplate = "API_KEY" }
    )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PasswordEntryEditor(
    viewModel: PasswordViewModel,
    totpViewModel: TotpViewModel? = null,
    bankCardViewModel: BankCardViewModel? = null,
    noteViewModel: NoteViewModel? = null,
    localKeePassViewModel: LocalKeePassViewModel? = null,
    localMdbxViewModel: MdbxViewModel? = null,
    mdbxDatabasesFallback: List<takagi.ru.monica.data.LocalMdbxDatabase> = emptyList(),
    passwordId: Long?,
    initialDraft: AddEditPasswordInitialDraft? = null,
    autofillPasswordUpdate: String? = null,
    forceShowAppBinding: Boolean = false,
    initialCategoryId: Long? = null,
    initialStorageExplicit: Boolean = false,
    initialKeePassDatabaseId: Long? = null,
    initialKeePassGroupPath: String? = null,
    initialMdbxDatabaseId: Long? = null,
    initialMdbxFolderId: String? = null,
    initialBitwardenVaultId: Long? = null,
    initialBitwardenFolderId: String? = null,
    pendingQrResult: String? = null,
    initialLoginType: String? = null,
    onConsumePendingQrResult: () -> Unit = {},
    onScanAuthenticatorQrCode: (() -> Unit)? = null,
    onSaveCompleted: ((Long?) -> Unit)? = null,
    onSwitchToApiToken: ((StorageTarget.Mdbx?) -> Unit)? = null,
    onSwitchToWifi: ((Long?) -> Unit)? = null,
    onSwitchToSshKey: ((Long?) -> Unit)? = null,
    creationTargets: List<StorageTarget>,
    onSwitchToGpg: () -> Unit,
    onSwitchToApiKey: (StorageTarget) -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findPasswordEditorActivity() }
    val isEditing = passwordId != null && passwordId > 0
    val coroutineScope = rememberCoroutineScope()
    val database = remember { PasswordDatabase.getDatabase(context) }
    val securityManager = takagi.ru.monica.ui.rememberUiSecurityManager()

    // 获取设置以读取进度条样式
    val settingsManager = remember { takagi.ru.monica.utils.SettingsManager(context) }
    val loadedSettings by settingsManager.settingsFlow.collectAsState(initial = null)
    val settings = loadedSettings ?: run {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    // Snapshot the presentation preference for this edit session, including configuration changes.
    val requestedContentSections = rememberSaveable(passwordId, saver = takagi.ru.monica.utils.StringListSaver) {
        mutableStateListOf<String>()
    }
    var showContentMenu by remember { mutableStateOf(false) }
    var editingContentSection by rememberSaveable { mutableStateOf<String?>(null) }
    val editorListState = rememberLazyListState()
    val generatorPreferencesManager = remember {
        GeneratorPreferencesManager(context.applicationContext)
    }
    val generatorPreferences by generatorPreferencesManager.preferencesFlow.collectAsState(
        initial = GeneratorPreferences()
    )
    val generatorPasswordOptions = remember(generatorPreferences) {
        generatorPreferences.toSymbolPasswordGeneratorOptions()
    }
    
    // 获取预设自定义字段列表
    val presetCustomFields by settingsManager.presetCustomFieldsFlow.collectAsState(initial = emptyList())
    
    // 常用账号信息
    val commonAccountPreferences = remember(context, securityManager) { takagi.ru.monica.data.CommonAccountPreferences(context, securityManager) }
    val commonAccountInfo by commonAccountPreferences.commonAccountInfo.collectAsState(
        initial = takagi.ru.monica.data.CommonAccountInfo()
    )
    val commonAccountTemplates by commonAccountPreferences.templatesFlow.collectAsState(initial = emptyList())
    
    // 是否显示常用账号选择器
    var showCommonAccountSelector by remember { mutableStateOf(false) }
    var blockedKeePassOperation by remember { mutableStateOf<KeePassOperationBlockUiState?>(null) }
    var commonAccountSelectorField by remember { mutableStateOf("") } // "email", "phone", "username", "password"
    var commonAccountSelectorTargetIndex by remember { mutableStateOf(-1) }
    var isUsernameFieldFocused by remember { mutableStateOf(false) }
    var isSeparatedUsernameFieldFocused by remember { mutableStateOf(false) }
    val usernameSuggestionBringIntoViewRequester = remember { BringIntoViewRequester() }
    val separatedUsernameSuggestionBringIntoViewRequester = remember { BringIntoViewRequester() }
    val passwordSuggestionBringIntoViewRequester = remember { BringIntoViewRequester() }
    var focusedPasswordFieldIndex by remember { mutableStateOf<Int?>(null) }
    var focusedCredentialUsernameIndex by remember { mutableStateOf<Int?>(null) }
    var pendingCredentialFocusIndex by remember { mutableStateOf<Int?>(null) }
    val credentialUsernameFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    val credentialBringIntoViewRequesters = remember { mutableMapOf<Int, BringIntoViewRequester>() }

    var title by rememberSaveable { mutableStateOf("") }
    var website by rememberSaveable { mutableStateOf("") }
    val websiteUrls = rememberSaveable(saver = takagi.ru.monica.utils.StringListSaver) {
        mutableStateListOf("")
    }
    val websiteMatchModes = rememberSaveable(saver = takagi.ru.monica.utils.StringListSaver) {
        mutableStateListOf(PasswordUrlRules.Mode.DEFAULT.name)
    }
    var urlRulesLoadFailed by remember { mutableStateOf(false) }
    fun currentUrlRules() = websiteUrls.mapIndexed { index, url ->
        PasswordUrlRules.Rule(url, PasswordUrlRules.Mode.valueOf(websiteMatchModes.getOrElse(index) { "DEFAULT" }))
    }
    var username by rememberSaveable { mutableStateOf("") }
    // CHANGE: Support multiple passwords
    val passwords = rememberSaveable(saver = takagi.ru.monica.utils.StringListSaver) { mutableStateListOf("") }
    val credentialUsernames = rememberSaveable(saver = takagi.ru.monica.utils.StringListSaver) {
        mutableStateListOf("")
    }
    var originalIds by remember { mutableStateOf<List<Long>>(emptyList()) }
    var allProjectOriginalIds by remember { mutableStateOf<List<Long>>(emptyList()) }
    var primaryProjectGroup by remember { mutableStateOf(ProjectCredentialGroup.Group()) }
    val extraCredentialGroups = remember { mutableStateListOf<ProjectCredentialGroup.Group>() }
    var hasProjectCredentialMetadata by remember { mutableStateOf(false) }
    var projectCredentialLoadFailed by remember { mutableStateOf(false) }

    var unreadablePasswordIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var hasOwnershipConflict by remember { mutableStateOf(false) }

    // 新建密码阶段按凭据维护附件草稿。单凭据时始终只使用第 1 组，进入批量模式后
    // 每个凭据页继续使用原附件组件，但不会把附件错误地挂到其他独立条目。
    val credentialAttachmentDrafts = remember {
        mutableStateListOf<SnapshotStateList<takagi.ru.monica.attachments.ui.AttachmentPendingDraft>>(
            mutableStateListOf()
        )
    }
    val credentialMetadataDrafts = remember {
        mutableStateListOf(CredentialMetadataDraft())
    }
    
    var authenticatorParametersJson by rememberSaveable { mutableStateOf(OtpParametersDraft().encode()) }
    val authenticatorParameters = remember(authenticatorParametersJson) { OtpParametersDraft.decode(authenticatorParametersJson) }
    var showInlineOtpSettings by rememberSaveable { mutableStateOf(false) }
    var showAuthenticatorParameters by rememberSaveable { mutableStateOf(false) }
    var authenticatorSecret by rememberSaveable { mutableStateOf("") }
    var selectedAuthenticatorOtpTypeName by rememberSaveable { mutableStateOf(OtpType.TOTP.name) }
    var passkeyBindings by rememberSaveable { mutableStateOf("") }
    var originalAuthenticatorKey by rememberSaveable { mutableStateOf("") }
    var existingSshKeyData by rememberSaveable { mutableStateOf("") }
    var existingTotpId by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedExistingTotpTitle by rememberSaveable { mutableStateOf("") }
    var authenticatorPayloadOverride by rememberSaveable { mutableStateOf<String?>(null) }
    var authenticatorEditedByUser by rememberSaveable(passwordId) { mutableStateOf(false) }
    var notes by rememberSaveable { mutableStateOf("") }
    var boundNoteId by rememberSaveable { mutableStateOf<Long?>(null) }
    var isFavorite by rememberSaveable { mutableStateOf(false) }
    // 每个密码条目维护独立可见状态，避免一个小眼睛影响全部条目
    val passwordVisibilityStates = remember { mutableStateMapOf<Int, Boolean>() }
    var projectGeneratorTarget by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var showPasswordGenerator by remember { mutableStateOf(false) }
    var currentPasswordIndexForGenerator by remember { mutableStateOf(-1) }
    var selectedCredentialEditorIndex by rememberSaveable { mutableStateOf(0) }
    var multiCredentialEditorSectionName by rememberSaveable {
        mutableStateOf(MultiCredentialEditorSection.COMMON.name)
    }
    var showCredentialPicker by remember { mutableStateOf(false) }
    var selectedAuthenticatorCredentialIndex by rememberSaveable { mutableStateOf(0) }
    val credentialAuthenticatorSecrets = rememberSaveable(saver = takagi.ru.monica.utils.StringListSaver) {
        mutableStateListOf("")
    }
    val credentialAuthenticatorOtpTypes = rememberSaveable(saver = takagi.ru.monica.utils.StringListSaver) {
        mutableStateListOf(OtpType.TOTP.name)
    }
    val credentialAuthenticatorParameters = rememberSaveable(saver = takagi.ru.monica.utils.StringListSaver) {
        mutableStateListOf(OtpParametersDraft().encode())
    }
    val credentialAuthenticatorPayloads = rememberSaveable(saver = takagi.ru.monica.utils.StringListSaver) {
        mutableStateListOf("")
    }
    val credentialExistingTotpIds = rememberSaveable(saver = takagi.ru.monica.utils.StringListSaver) {
        mutableStateListOf("")
    }
    val credentialExistingTotpTitles = rememberSaveable(saver = takagi.ru.monica.utils.StringListSaver) {
        mutableStateListOf("")
    }
    val credentialAuthenticatorEditedFlags = rememberSaveable(saver = takagi.ru.monica.utils.StringListSaver) {
        mutableStateListOf("0")
    }
    val credentialOriginalAuthenticatorKeys = rememberSaveable(saver = takagi.ru.monica.utils.StringListSaver) {
        mutableStateListOf("")
    }
    
    // 防止重复点击保存按钮
    var isSaving by remember { mutableStateOf(false) }

    var appPackageName by rememberSaveable { mutableStateOf("") }
    var appName by rememberSaveable { mutableStateOf("") }

    fun replaceWebsiteUrlsFromRaw(rawValue: String) {
        websiteUrls.clear()
        websiteUrls.addAll(parsePasswordWebsiteUrls(rawValue))
        websiteMatchModes.clear()
        websiteMatchModes.addAll(websiteUrls.map { PasswordUrlRules.Mode.DEFAULT.name })
        website = encodePasswordWebsiteUrls(websiteUrls)
    }

    fun syncWebsiteFromUrlRows() {
        website = encodePasswordWebsiteUrls(websiteUrls)
    }

    // 绑定选项状态
    var bindTitle by rememberSaveable { mutableStateOf(false) }
    var bindWebsite by rememberSaveable { mutableStateOf(false) }

    // 新增字段状态 - 支持多个邮箱和电话
    val emails = rememberSaveable(saver = takagi.ru.monica.utils.StringListSaver) { mutableStateListOf("") }
    val phones = rememberSaveable(saver = takagi.ru.monica.utils.StringListSaver) { mutableStateListOf("") }
    var addressLine by rememberSaveable { mutableStateOf("") }
    var city by rememberSaveable { mutableStateOf("") }
    var state by rememberSaveable { mutableStateOf("") }
    var zipCode by rememberSaveable { mutableStateOf("") }
    var country by rememberSaveable { mutableStateOf("") }

    fun applyCommonBillingAddress(
        address: BillingAddress,
        target: CredentialMetadataDraft? = null
    ) {
        val normalizedAddressLine = listOf(address.streetAddress, address.apartment)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(", ")
        if (target != null) {
            target.addressLine = normalizedAddressLine
            target.city = address.city
            target.state = address.stateProvince
            target.zipCode = address.postalCode
            target.country = address.country
        } else {
            addressLine = normalizedAddressLine
            city = address.city
            state = address.stateProvince
            zipCode = address.postalCode
            country = address.country
        }
    }

    var creditCardNumber by rememberSaveable { mutableStateOf("") }
    var creditCardHolder by rememberSaveable { mutableStateOf("") }
    var creditCardExpiry by rememberSaveable { mutableStateOf("") }
    var creditCardCVV by rememberSaveable { mutableStateOf("") }

    var categoryId by rememberSaveable { mutableStateOf<Long?>(null) }
    val categories by viewModel.categories.collectAsState()
    val currentFilter by viewModel.categoryFilter.collectAsState()
    
    // KeePass 数据库选择
    var keepassDatabaseId by rememberSaveable { mutableStateOf<Long?>(null) }
    var keepassGroupPath by rememberSaveable { mutableStateOf<String?>(null) }
    var editingKeePassEntryUuid by rememberSaveable { mutableStateOf<String?>(null) }
    val keepassDatabases by (localKeePassViewModel?.allDatabases ?: kotlinx.coroutines.flow.flowOf(emptyList())).collectAsState(initial = emptyList())

    // MDBX 数据库选择
    var mdbxDatabaseId by rememberSaveable { mutableStateOf<Long?>(null) }
    var mdbxFolderId by rememberSaveable { mutableStateOf<String?>(null) }
    val mdbxDatabases by (localMdbxViewModel?.allDatabases
        ?: database.localMdbxDatabaseDao().getAvailableDatabases()
    ).collectAsState(initial = mdbxDatabasesFallback)

    // Bitwarden Vault 选择
    var bitwardenVaultId by rememberSaveable { mutableStateOf<Long?>(null) }
    var bitwardenFolderId by rememberSaveable { mutableStateOf<String?>(null) }
    val bitwardenRepository = remember { BitwardenRepository.getInstance(context) }
    val bitwardenVaults by bitwardenRepository.getAllVaultsFlow().collectAsState(initial = emptyList())
    val selectedStorageTargets = remember { mutableStateListOf<StorageTarget>() }
    var existingReplicaTargetKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var currentReplicaGroupId by rememberSaveable { mutableStateOf<String?>(null) }
    var showStorageTargetSheet by remember { mutableStateOf(false) }
    
    // SSO 登录方式字段
    val defaultLoginType = initialLoginType?.uppercase(Locale.ROOT)?.takeIf { it == LOGIN_TYPE_BARCODE || it in takagi.ru.monica.data.model.TemplateCredentialDraft.types } ?: "PASSWORD"
    var loginType by rememberSaveable { mutableStateOf(defaultLoginType) }
    var templateDraft by remember { mutableStateOf(takagi.ru.monica.data.model.TemplateCredentialDraft.load(
        PasswordEntry(title = "", website = "", username = "", password = "", loginType = defaultLoginType), emptyList())) }
    var templateLoadFailed by remember { mutableStateOf(false) }
    var preservedWifiMetadata by remember { mutableStateOf("") }
    var ssoProvider by rememberSaveable { mutableStateOf("") }
    var ssoRefEntryId by rememberSaveable { mutableStateOf<Long?>(null) }
    var barcodePayload by rememberSaveable { mutableStateOf("") }
    val isPasswordCredentialMode = loginType.equals("PASSWORD", ignoreCase = true) &&
        !loginType.equals(LOGIN_TYPE_BARCODE, ignoreCase = true)
    val canAddIndependentCredential = isPasswordCredentialMode && extraCredentialGroups.isEmpty() && !hasProjectCredentialMetadata &&
        (!isEditing || originalIds.size == 1)
    val usesCredentialCards = isPasswordCredentialMode &&
        (!isEditing || (originalIds.size == 1 && credentialUsernames.size > 1))
    val isMultiCredentialMode = usesCredentialCards && credentialUsernames.size > 1
    val usesProjectCredentialSave = hasProjectCredentialMetadata || extraCredentialGroups.isNotEmpty() ||
        (!isMultiCredentialMode && primaryProjectGroup.note.isNotBlank())
    val multiCredentialEditorSection = remember(multiCredentialEditorSectionName) {
        runCatching { MultiCredentialEditorSection.valueOf(multiCredentialEditorSectionName) }
            .getOrDefault(MultiCredentialEditorSection.COMMON)
    }
    val showCommonEditorContent = !isMultiCredentialMode ||
        multiCredentialEditorSection == MultiCredentialEditorSection.COMMON
    val showCredentialEditorContent = !isMultiCredentialMode ||
        multiCredentialEditorSection == MultiCredentialEditorSection.CREDENTIAL
    val activeCredentialMetadata = credentialMetadataDrafts[
        selectedCredentialEditorIndex.coerceIn(0, credentialMetadataDrafts.lastIndex)
    ]
    val credentialScopedNotes = if (isMultiCredentialMode) activeCredentialMetadata.notes else notes
    val credentialScopedBoundNoteId = if (isMultiCredentialMode) {
        activeCredentialMetadata.boundNoteId
    } else {
        boundNoteId
    }
    val credentialScopedEmails = if (isMultiCredentialMode) activeCredentialMetadata.emails else emails
    val credentialScopedPhones = if (isMultiCredentialMode) activeCredentialMetadata.phones else phones
    val credentialScopedAddressLine = if (isMultiCredentialMode) activeCredentialMetadata.addressLine else addressLine
    val credentialScopedCity = if (isMultiCredentialMode) activeCredentialMetadata.city else city
    val credentialScopedState = if (isMultiCredentialMode) activeCredentialMetadata.state else state
    val credentialScopedZipCode = if (isMultiCredentialMode) activeCredentialMetadata.zipCode else zipCode
    val credentialScopedCountry = if (isMultiCredentialMode) activeCredentialMetadata.country else country
    val credentialScopedCreditCardNumber = if (isMultiCredentialMode) {
        activeCredentialMetadata.creditCardNumber
    } else {
        creditCardNumber
    }
    val credentialScopedCreditCardHolder = if (isMultiCredentialMode) {
        activeCredentialMetadata.creditCardHolder
    } else {
        creditCardHolder
    }
    val credentialScopedCreditCardExpiry = if (isMultiCredentialMode) {
        activeCredentialMetadata.creditCardExpiry
    } else {
        creditCardExpiry
    }
    val credentialScopedCreditCardCVV = if (isMultiCredentialMode) {
        activeCredentialMetadata.creditCardCVV
    } else {
        creditCardCVV
    }
    
    // 获取所有密码条目用于SSO关联选择；只需要元数据，避免打开编辑页时解密全量密码。
    val allPasswordsForRef by viewModel.allPasswordsForUi.collectAsState(initial = emptyList())
    val allNotes by (noteViewModel?.allNotes ?: flowOf(emptyList())).collectAsState(initial = emptyList())
    val selectableNotes = remember(allNotes) { allNotes.filter { !it.isDeleted } }
    val selectedBoundNote = remember(credentialScopedBoundNoteId, selectableNotes) {
        credentialScopedBoundNoteId?.let { noteId -> selectableNotes.firstOrNull { it.id == noteId } }
    }
    var showBoundNotePicker by remember { mutableStateOf(false) }
    var showAuthenticatorPicker by remember { mutableStateOf(false) }
    val allTotpItemsForBinding by (totpViewModel?.allTotpItems ?: flowOf(emptyList()))
        .collectAsState(initial = emptyList())
    val selectableTotpBindings = remember(showAuthenticatorPicker, allTotpItemsForBinding, totpViewModel) {
        if (!showAuthenticatorPicker || totpViewModel == null) {
            emptyList()
        } else {
            allTotpItemsForBinding.mapNotNull { item ->
                if (item.id <= 0 || item.itemType != ItemType.TOTP || item.isDeleted) return@mapNotNull null
                val data = totpViewModel.parseTotpDataForDisplay(item) ?: return@mapNotNull null
                PasswordTotpBindingCandidate(item = item, data = data)
            }
        }
    }
    
    // 自定义字段状态
    val customFields = remember { mutableStateListOf<CustomFieldDraft>() }
    var customFieldsExpanded by remember { mutableStateOf(false) }
    var separatedUsername by rememberSaveable { mutableStateOf("") }
    val inlineGeneratedPasswords = remember { mutableStateMapOf<Int, String>() }
    val selectedAuthenticatorOtpType = remember(selectedAuthenticatorOtpTypeName) {
        runCatching { OtpType.valueOf(selectedAuthenticatorOtpTypeName) }.getOrDefault(OtpType.TOTP)
    }
    val authenticatorAccountName = if (isMultiCredentialMode) {
        credentialUsernames.getOrNull(selectedCredentialEditorIndex).orEmpty()
    } else {
        username
    }
    val authenticatorKey = remember(
        authenticatorSecret,
        selectedAuthenticatorOtpType,
        title,
        authenticatorAccountName,
        authenticatorPayloadOverride,
        authenticatorParameters
    ) {
        authenticatorPayloadOverride
            ?.takeIf { it.isNotBlank() }
            ?: buildPasswordScreenAuthenticatorPayload(
                secret = authenticatorSecret,
                otpType = selectedAuthenticatorOtpType,
                issuer = title,
                accountName = authenticatorAccountName,
                parameters = authenticatorParameters
            )
    }
    val authenticatorPreviewTotpData = remember(authenticatorKey, title, authenticatorAccountName) {
        buildPasswordScreenInlinePreviewTotpData(
            rawKey = authenticatorKey,
            issuer = title,
            accountName = authenticatorAccountName
        )
    }
    val authenticatorPreviewVisible = authenticatorPreviewTotpData != null
    val authenticatorPreviewCurrentSeconds by produceState(
        initialValue = System.currentTimeMillis() / 1000,
        key1 = authenticatorPreviewTotpData?.otpType
    ) {
        value = System.currentTimeMillis() / 1000
        while (true) {
            value = System.currentTimeMillis() / 1000
            kotlinx.coroutines.delay(1000)
        }
    }
    val authenticatorPreviewProgressTimeMillis by produceState(
        initialValue = System.currentTimeMillis(),
        key1 = authenticatorPreviewTotpData?.otpType,
        key2 = settings.validatorSmoothProgress
    ) {
        value = System.currentTimeMillis()
        while (true) {
            val now = System.currentTimeMillis()
            value = now
            val waitMillis = if (settings.validatorSmoothProgress) {
                50L
            } else {
                (1000L - (now % 1000L)).coerceAtLeast(16L)
            }
            kotlinx.coroutines.delay(waitMillis)
        }
    }

    // 自定义图标状态
    var customIconType by rememberSaveable(passwordId) { mutableStateOf(PASSWORD_ICON_TYPE_NONE) }
    var customIconValue by rememberSaveable(passwordId) { mutableStateOf<String?>(null) }
    var customIconUpdatedAt by rememberSaveable(passwordId) { mutableStateOf(0L) }
    var originalCustomIconType by rememberSaveable(passwordId) { mutableStateOf(PASSWORD_ICON_TYPE_NONE) }
    var originalCustomIconValue by rememberSaveable(passwordId) { mutableStateOf<String?>(null) }
    var customIconEditedByUser by rememberSaveable(passwordId) { mutableStateOf(false) }
    var hasSavedSuccessfully by rememberSaveable(passwordId) { mutableStateOf(false) }

    var showCustomIconDialog by remember { mutableStateOf(false) }
    var showSimpleIconPicker by remember { mutableStateOf(false) }
    var showEmojiIconDialog by rememberSaveable { mutableStateOf(false) }
    var showInstalledIconPicker by rememberSaveable { mutableStateOf(false) }
    var customIconSearchQuery by rememberSaveable { mutableStateOf("") }

    // 折叠面板状态
    var personalInfoExpanded by remember { mutableStateOf(false) }
    var addressInfoExpanded by remember { mutableStateOf(false) }
    var paymentInfoExpanded by remember { mutableStateOf(false) }

    val usernameLabel = stringResource(R.string.autofill_username)
    val selectedSimpleIconBitmap = rememberSimpleIconBitmap(
        slug = if (customIconType == PASSWORD_ICON_TYPE_SIMPLE) customIconValue else null,
        tintColor = MaterialTheme.colorScheme.primary,
        enabled = settings.iconCardsEnabled
    )
    val selectedUploadedIconBitmap = rememberUploadedPasswordIcon(
        value = if (customIconType == PASSWORD_ICON_TYPE_UPLOADED) customIconValue else null
    )
    val selectedEmojiIcon = customIconValue.takeIf { customIconType == PASSWORD_ICON_TYPE_EMOJI }
    val keePassIcon = takagi.ru.monica.ui.icons.rememberKeePassCustomIcon(
        keepassDatabaseId, editingKeePassEntryUuid,
        enabled = settings.iconCardsEnabled && customIconType == PASSWORD_ICON_TYPE_NONE)
    val linkedAppBindings = remember(appPackageName, appName) {
        parseLinkedAppBindings(appPackageName, appName)
    }
    var showAppSelectorFromWebsite by remember { mutableStateOf(false) }
    val primaryAppPackageName = linkedAppBindings.firstOrNull()?.packageName.orEmpty()
    val autoMatchedSimpleIcon = rememberAutoMatchedSimpleIcon(
        website = website,
        title = title,
        appPackageName = primaryAppPackageName,
        tintColor = MaterialTheme.colorScheme.primary,
        enabled = settings.iconCardsEnabled && customIconType == PASSWORD_ICON_TYPE_NONE && keePassIcon == null
    )
    val fallbackWebsiteFavicon = rememberFavicon(
        url = website,
        enabled = settings.iconCardsEnabled &&
            keePassIcon == null &&
            customIconType == PASSWORD_ICON_TYPE_NONE &&
            autoMatchedSimpleIcon.resolved &&
            autoMatchedSimpleIcon.slug == null
    )
    
    // 字段可见性设置
    val fieldVisibility = settings.passwordFieldVisibility
    val commonAccountTypeEmail = stringResource(R.string.common_account_type_email)
    val commonAccountTypeAccount = stringResource(R.string.common_account_type_account)
    val commonAccountTypePhone = stringResource(R.string.common_account_type_phone)
    val commonAccountTypePassword = stringResource(R.string.common_account_type_password)
    val commonAccountTypeName = stringResource(R.string.common_account_type_name)
    val fieldAccountLabel = stringResource(R.string.field_account)
    val fieldEmailLabel = stringResource(R.string.field_email)
    val fieldPhoneLabel = stringResource(R.string.field_phone)
    val isBarcodeMode = loginType.equals(LOGIN_TYPE_BARCODE, ignoreCase = true)
    val contentMode = !isBarcodeMode
    fun contentSectionEnabled(section: PasswordContentSection, classicVisible: Boolean) =
        if (contentMode) section.name in requestedContentSections else classicVisible

    fun applyAuthenticatorParameters(parameters: OtpParametersDraft) {
        authenticatorEditedByUser = true
        existingTotpId = null
        selectedExistingTotpTitle = ""
        authenticatorPayloadOverride = null
        authenticatorParametersJson = parameters.encode()
    }

    fun applyAuthenticatorInput(rawValue: String) {
        authenticatorEditedByUser = true
        val trimmed = rawValue.trim()
        existingTotpId = null
        selectedExistingTotpTitle = ""
        val parsed = if (trimmed.contains("://")) {
            TotpDataResolver.fromAuthenticatorKey(
                rawKey = trimmed,
                fallbackIssuer = title,
                fallbackAccountName = authenticatorAccountName
            )
        } else {
            null
        }
        if (parsed != null) {
            authenticatorSecret = parsed.secret
            selectedAuthenticatorOtpTypeName = parsed.otpType.name
            authenticatorParametersJson = OtpParametersDraft.from(parsed).encode()
            authenticatorPayloadOverride = trimmed
        } else {
            authenticatorSecret = rawValue
            authenticatorPayloadOverride = null
        }
    }

    fun applyScannedAuthenticator(rawValue: String) {
        when (val scanResult = takagi.ru.monica.util.TotpUriParser.parseScannedContent(rawValue)) {
            is takagi.ru.monica.util.TotpScanParseResult.Single -> {
                authenticatorEditedByUser = true
                existingTotpId = null
                selectedExistingTotpTitle = ""
                val imported = scanResult.item.totpData
                authenticatorSecret = imported.secret
                selectedAuthenticatorOtpTypeName = imported.otpType.name
                authenticatorParametersJson = OtpParametersDraft.from(imported).encode()
                authenticatorPayloadOverride = TotpDataResolver.toBitwardenPayload(
                    title = scanResult.item.label,
                    data = imported
                ).takeIf { it.isNotBlank() && it != imported.secret }
                if (title.isBlank()) {
                    title = scanResult.item.suggestedTitle
                }
                if (authenticatorAccountName.isBlank()) {
                    if (!isMultiCredentialMode) {
                        username = imported.accountName
                    } else {
                        credentialUsernames[selectedCredentialEditorIndex] = imported.accountName
                    }
                }
            }
            is takagi.ru.monica.util.TotpScanParseResult.Multiple -> {
                scanResult.items.firstOrNull()?.let { first ->
                    authenticatorEditedByUser = true
                    existingTotpId = null
                    selectedExistingTotpTitle = ""
                    val imported = first.totpData
                    authenticatorSecret = imported.secret
                    selectedAuthenticatorOtpTypeName = imported.otpType.name
                    authenticatorParametersJson = OtpParametersDraft.from(imported).encode()
                    authenticatorPayloadOverride = TotpDataResolver.toBitwardenPayload(
                        title = first.label,
                        data = imported
                    ).takeIf { it.isNotBlank() && it != imported.secret }
                    if (title.isBlank()) {
                        title = first.label
                            .substringBefore(":")
                            .ifBlank { imported.issuer }
                            .ifBlank { title }
                    }
                    if (authenticatorAccountName.isBlank()) {
                        if (!isMultiCredentialMode) {
                            username = imported.accountName
                        } else {
                            credentialUsernames[selectedCredentialEditorIndex] = imported.accountName
                        }
                    }
                }
                Toast.makeText(
                    context,
                    context.getString(R.string.qr_migration_multiple_fill_first, scanResult.items.size),
                    Toast.LENGTH_SHORT
                ).show()
            }
            is takagi.ru.monica.util.TotpScanParseResult.MigrationFailure -> {
                authenticatorPayloadOverride = null
                Toast.makeText(
                    context,
                    context.getString(
                        takagi.ru.monica.ui.components.migrationFailureMessageRes(scanResult.reason)
                    ),
                    Toast.LENGTH_LONG
                ).show()
            }
            takagi.ru.monica.util.TotpScanParseResult.UnsupportedPhoneFactor -> {
                authenticatorPayloadOverride = null
                Toast.makeText(
                    context,
                    context.getString(R.string.qr_phonefactor_not_supported),
                    Toast.LENGTH_SHORT
                ).show()
            }
            takagi.ru.monica.util.TotpScanParseResult.InvalidFormat -> {
                authenticatorPayloadOverride = null
                Toast.makeText(
                    context,
                    context.getString(R.string.qr_invalid_authenticator),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    fun applyExistingAuthenticator(candidate: PasswordTotpBindingCandidate) {
        authenticatorEditedByUser = true
        val normalized = TotpDataResolver.normalizeTotpData(candidate.data)
        val payload = TotpDataResolver.toBitwardenPayload(
            title = candidate.item.title.ifBlank { normalized.issuer.ifBlank { title } },
            data = normalized
        )
        existingTotpId = candidate.item.id
        selectedExistingTotpTitle = candidate.item.title
            .ifBlank { normalized.issuer }
            .ifBlank { normalized.accountName }
        authenticatorSecret = normalized.secret
        selectedAuthenticatorOtpTypeName = normalized.otpType.name
        authenticatorParametersJson = OtpParametersDraft.from(normalized).encode()
        authenticatorPayloadOverride = payload.takeIf { it.isNotBlank() && it != normalized.secret }
        if (title.isBlank()) {
            title = normalized.issuer.ifBlank { candidate.item.title }.ifBlank { title }
        }
        if (authenticatorAccountName.isBlank()) {
            if (!isMultiCredentialMode) {
                username = normalized.accountName
            } else {
                credentialUsernames[selectedCredentialEditorIndex] = normalized.accountName
            }
        }
    }

    LaunchedEffect(pendingQrResult) {
        pendingQrResult?.let { qrValue ->
            if (templateDraft?.type == "WIFI") {
                takagi.ru.monica.utils.WifiQrParser.parse(qrValue)?.let { parsed ->
                    val draft = requireNotNull(templateDraft)
                    if (title.isBlank() || title == draft.value("ssid")) title = parsed.ssid
                    templateDraft = draft.copy(values = draft.values + mapOf("ssid" to parsed.ssid,
                        "password" to parsed.password, "security" to parsed.security.name, "hiddenNetwork" to parsed.hidden.toString()))
                }
            } else if (isBarcodeMode) {
                barcodePayload = qrValue
                if (title.isBlank()) {
                    title = context.getString(R.string.entry_type_barcode)
                }
            } else {
                applyScannedAuthenticator(qrValue)
            }
            onConsumePendingQrResult()
        }
    }

    fun syncLegacyStorageState(targets: List<StorageTarget>) {
        when (val primaryTarget = targets.firstOrNull()) {
            is StorageTarget.MonicaLocal -> {
                categoryId = primaryTarget.categoryId
                keepassDatabaseId = null
                keepassGroupPath = null
                mdbxDatabaseId = null
                mdbxFolderId = null
                bitwardenVaultId = null
                bitwardenFolderId = null
            }
            is StorageTarget.KeePass -> {
                categoryId = null
                keepassDatabaseId = primaryTarget.databaseId
                keepassGroupPath = primaryTarget.groupPath
                mdbxDatabaseId = null
                mdbxFolderId = null
                bitwardenVaultId = null
                bitwardenFolderId = null
            }
            is StorageTarget.Bitwarden -> {
                categoryId = null
                keepassDatabaseId = null
                keepassGroupPath = null
                mdbxDatabaseId = null
                mdbxFolderId = null
                bitwardenVaultId = primaryTarget.vaultId
                bitwardenFolderId = primaryTarget.folderId
            }
            is StorageTarget.Mdbx -> {
                categoryId = null
                keepassDatabaseId = null
                keepassGroupPath = null
                mdbxDatabaseId = primaryTarget.databaseId
                mdbxFolderId = primaryTarget.folderId
                bitwardenVaultId = null
                bitwardenFolderId = null
            }
            null -> {
                categoryId = null
                keepassDatabaseId = null
                keepassGroupPath = null
                mdbxDatabaseId = null
                mdbxFolderId = null
                bitwardenVaultId = null
                bitwardenFolderId = null
            }
        }
    }

    fun setSelectedStorageTargets(targets: List<StorageTarget>) {
        val normalizedTargets = targets.normalizedStorageTargets()
        selectedStorageTargets.clear()
        selectedStorageTargets.addAll(normalizedTargets)
        syncLegacyStorageState(normalizedTargets)
    }

    fun addSelectedStorageTarget(target: StorageTarget) {
        if (selectedStorageTargets.any { it.stableKey == target.stableKey }) return
        setSelectedStorageTargets(selectedStorageTargets.withStorageTargetSelected(target))
    }

    fun removeSelectedStorageTarget(target: StorageTarget) {
        setSelectedStorageTargets(selectedStorageTargets.withoutStorageTarget(target))
    }

    fun buildStorageTargetsForSave(): List<StorageTarget> {
        return selectedStorageTargets.toList().normalizedStorageTargets()
    }

    fun normalizeCommonTemplateType(raw: String): String {
        val value = raw.trim()
        val normalized = value.lowercase(Locale.ROOT)
        return when {
            normalized == commonAccountTypeEmail.lowercase(Locale.ROOT) ||
                normalized == "email" || normalized == "邮箱" -> commonAccountTypeEmail
            normalized == commonAccountTypeAccount.lowercase(Locale.ROOT) ||
                normalized == "account" || normalized == "账号" -> commonAccountTypeAccount
            normalized == commonAccountTypePhone.lowercase(Locale.ROOT) ||
                normalized == "phone" || normalized == "手机号" || normalized == "电话" -> commonAccountTypePhone
            normalized == commonAccountTypePassword.lowercase(Locale.ROOT) ||
                normalized == "password" || normalized == "密码" -> commonAccountTypePassword
            normalized == commonAccountTypeName.lowercase(Locale.ROOT) ||
                normalized == "name" || normalized == "姓名" -> commonAccountTypeName
            else -> commonAccountTypeAccount
        }
    }

    val accountTemplates = remember(commonAccountTemplates, commonAccountTypeAccount, commonAccountTypeEmail, commonAccountTypePassword) {
        commonAccountTemplates.filter {
            normalizeCommonTemplateType(it.type) == commonAccountTypeAccount && it.content.isNotBlank()
        }
    }
    val emailTemplates = remember(commonAccountTemplates, commonAccountTypeAccount, commonAccountTypeEmail, commonAccountTypePassword) {
        commonAccountTemplates.filter {
            normalizeCommonTemplateType(it.type) == commonAccountTypeEmail && it.content.isNotBlank()
        }
    }
    val phoneTypeTemplates = remember(commonAccountTemplates, commonAccountTypePhone) {
        commonAccountTemplates.filter {
            normalizeCommonTemplateType(it.type) == commonAccountTypePhone && it.content.isNotBlank()
        }
    }
    val passwordTemplates = remember(commonAccountTemplates, commonAccountTypeAccount, commonAccountTypeEmail, commonAccountTypePhone, commonAccountTypePassword) {
        commonAccountTemplates.filter {
            normalizeCommonTemplateType(it.type) == commonAccountTypePassword && it.content.isNotBlank()
        }
    }
    val phoneTemplates = remember(phoneTypeTemplates, accountTemplates, fieldPhoneLabel) {
        val normalizedPhoneLabel = fieldPhoneLabel.trim().lowercase(Locale.ROOT)
        val legacyPhoneLikeFromAccount = accountTemplates.filter { template ->
            val normalizedTitle = template.title.trim().lowercase(Locale.ROOT)
            val digits = template.content.filter { it.isDigit() }
            val trimmedContent = template.content.trim()
            val looksLikePhoneByTitle =
                normalizedTitle.contains(normalizedPhoneLabel) ||
                    normalizedTitle.contains("phone") ||
                    normalizedTitle.contains("手机号") ||
                    normalizedTitle.contains("电话")
            val looksLikePhoneByPattern =
                trimmedContent.matches(Regex("^[+()\\-\\s\\d]{7,}$")) && digits.length >= 7
            looksLikePhoneByTitle || looksLikePhoneByPattern
        }
        (phoneTypeTemplates + legacyPhoneLikeFromAccount)
            .distinctBy { it.content.trim().lowercase(Locale.ROOT) }
    }

    val canSelectEmailTemplate = !isEditing && !commonAccountInfo.autoFillEnabled &&
        emailTemplates.isNotEmpty()
    val canSelectPhoneTemplate = !isEditing && !commonAccountInfo.autoFillEnabled &&
        phoneTemplates.isNotEmpty()
    val generatorSessionUnlocked by takagi.ru.monica.security.SessionManager.isUnlocked.collectAsState()
    val generatorVerification by (localKeePassViewModel?.verificationStates
        ?: kotlinx.coroutines.flow.flowOf(emptyMap<Long, LocalKeePassViewModel.VerificationState>())).collectAsState(initial = emptyMap())
    val generatorOpen = showPasswordGenerator || (showCommonAccountSelector && commonAccountSelectorField == "username")
    val generatorField = if (showPasswordGenerator) "password" else "username"
    val generatorSources = buildSet {
        add("local")
        keepassDatabases.filter {
            generatorVerification[it.id] !is LocalKeePassViewModel.VerificationState.Failed &&
                (!it.encryptedPassword.isNullOrBlank() || !it.keyFileUri.isNullOrBlank() ||
                    !it.keyFileInternalPath.isNullOrBlank() || generatorVerification[it.id] is LocalKeePassViewModel.VerificationState.Verified)
        }.forEach { add("keepass:${it.id}") }
        bitwardenVaults.filterNot { it.isLocked }.forEach { add("bitwarden:${it.id}") }
        mdbxDatabases.forEach { add("mdbx:${it.id}") }
    }
    // Keyed state prevents stale suggestions remaining visible after locking a source or closing the sheet.
    var generatorValues by remember(generatorOpen, generatorField, generatorSessionUnlocked, generatorSources) {
        mutableStateOf<List<String>>(emptyList())
    }
    LaunchedEffect(generatorOpen, generatorField, generatorSessionUnlocked, generatorSources, allPasswordsForRef) {
        if (!generatorOpen || !generatorSessionUnlocked) return@LaunchedEffect
        generatorValues = viewModel.getGeneratorSuggestionValues(generatorField == "username") { entry ->
            takagi.ru.monica.ui.password.generatorEntryAccessible(entry, generatorSources)
        }
    }
    fun buildCommonAccountOptions(field: String): List<CommonAccountFillOption> {
        val options = buildList {
            when (field) {
                "username" -> {
                    accountTemplates.forEach { template ->
                        add(
                            CommonAccountFillOption(
                                id = template.id,
                                type = commonAccountTypeAccount,
                                content = template.content
                            )
                        )
                    }
                    emailTemplates.forEach { template ->
                        add(
                            CommonAccountFillOption(
                                id = "email_as_account_${template.id}",
                                type = commonAccountTypeEmail,
                                content = template.content
                            )
                        )
                    }
                    phoneTemplates.forEach { template ->
                        add(
                            CommonAccountFillOption(
                                id = "phone_as_account_${template.id}",
                                type = commonAccountTypePhone,
                                content = template.content
                            )
                        )
                    }
                }
                "email" -> {
                    emailTemplates.forEach { template ->
                        add(
                            CommonAccountFillOption(
                                id = template.id,
                                type = commonAccountTypeEmail,
                                content = template.content
                            )
                        )
                    }
                }
                "phone" -> {
                    phoneTemplates.forEach { template ->
                        add(
                            CommonAccountFillOption(
                                id = template.id,
                                type = commonAccountTypePhone,
                                content = template.content
                            )
                        )
                    }
                }
                "password" -> {
                    passwordTemplates.forEach { template ->
                        add(
                            CommonAccountFillOption(
                                id = template.id,
                                type = commonAccountTypePassword,
                                content = template.content
                            )
                        )
                    }
                }
            }
        }

        val presets = options.filter { it.content.isNotBlank() }.distinctBy { it.content }
        if (field != "username" && field != "password") return presets
        val frequent = takagi.ru.monica.ui.password.generatorFrequentValues(
            if (generatorOpen && generatorSessionUnlocked && field == generatorField) generatorValues else emptyList(),
            presets.mapTo(hashSetOf()) { it.content }
        )
        return presets + frequent.mapIndexed { index, value ->
            CommonAccountFillOption("frequent_${field}_$index", context.getString(R.string.cg_vault_frequent), value)
        }
    }

    val currentEntryIdForUsernameSuggestion = remember(passwordId) {
        passwordId?.let { if (it < 0) -it else it }
    }
    val usernameSuggestionState = remember(
        username,
        isUsernameFieldFocused,
        currentEntryIdForUsernameSuggestion,
        allPasswordsForRef
    ) {
        if (!isUsernameFieldFocused) {
            UsernameSuggestionState.Hidden
        } else {
            buildUsernameSuggestionState(
                query = username,
                currentEntryId = currentEntryIdForUsernameSuggestion,
                passwordEntries = allPasswordsForRef
            )
        }
    }
    val separatedUsernameSuggestionState = remember(
        separatedUsername,
        settings.separateUsernameAccountEnabled,
        isSeparatedUsernameFieldFocused,
        currentEntryIdForUsernameSuggestion,
        allPasswordsForRef
    ) {
        if (!settings.separateUsernameAccountEnabled || !isSeparatedUsernameFieldFocused) {
            UsernameSuggestionState.Hidden
        } else {
            buildUsernameSuggestionState(
                query = separatedUsername,
                currentEntryId = currentEntryIdForUsernameSuggestion,
                passwordEntries = allPasswordsForRef
            )
        }
    }
    val credentialUsernameSuggestionState = remember(
        focusedCredentialUsernameIndex,
        credentialUsernames.toList(),
        allPasswordsForRef,
        isMultiCredentialMode
    ) {
        val index = focusedCredentialUsernameIndex
        if (!isMultiCredentialMode || index == null || index !in credentialUsernames.indices) {
            UsernameSuggestionState.Hidden
        } else {
            buildUsernameSuggestionState(
                query = credentialUsernames[index],
                currentEntryId = null,
                passwordEntries = allPasswordsForRef
            )
        }
    }

    val usernameSuggestionVisible = usernameSuggestionState !is UsernameSuggestionState.Hidden
    val separatedUsernameSuggestionVisible =
        separatedUsernameSuggestionState !is UsernameSuggestionState.Hidden
    val credentialUsernameSuggestionVisible =
        credentialUsernameSuggestionState !is UsernameSuggestionState.Hidden
    val focusedPasswordSuggestionVisible = focusedPasswordFieldIndex?.let { index ->
        index in passwords.indices &&
            passwords[index].isBlank() &&
            !inlineGeneratedPasswords[index].isNullOrBlank()
    } ?: false

    LaunchedEffect(usernameSuggestionVisible) {
        if (usernameSuggestionVisible) {
            kotlinx.coroutines.delay(80)
            usernameSuggestionBringIntoViewRequester.bringIntoView()
        }
    }
    LaunchedEffect(separatedUsernameSuggestionVisible) {
        if (separatedUsernameSuggestionVisible) {
            kotlinx.coroutines.delay(80)
            separatedUsernameSuggestionBringIntoViewRequester.bringIntoView()
        }
    }
    LaunchedEffect(credentialUsernameSuggestionVisible, focusedCredentialUsernameIndex) {
        if (credentialUsernameSuggestionVisible) {
            kotlinx.coroutines.delay(80)
            focusedCredentialUsernameIndex
                ?.let(credentialBringIntoViewRequesters::get)
                ?.bringIntoView()
        }
    }
    LaunchedEffect(focusedPasswordSuggestionVisible) {
        if (focusedPasswordSuggestionVisible) {
            kotlinx.coroutines.delay(80)
            passwordSuggestionBringIntoViewRequester.bringIntoView()
        }
    }

    fun generateInlinePasswordSuggestion(): String {
        return AdvancedPasswordGenerator.generatePassword(
            length = generatorPasswordOptions.length,
            includeUppercase = generatorPasswordOptions.includeUppercase,
            includeLowercase = generatorPasswordOptions.includeLowercase,
            includeNumbers = generatorPasswordOptions.includeNumbers,
            includeSymbols = generatorPasswordOptions.includeSymbols,
            allowedSymbols = generatorPasswordOptions.allowedSymbols,
            excludeSimilar = generatorPasswordOptions.excludeSimilar,
            excludeAmbiguous = generatorPasswordOptions.excludeAmbiguous,
            uppercaseMin = generatorPasswordOptions.uppercaseMin,
            lowercaseMin = generatorPasswordOptions.lowercaseMin,
            numbersMin = generatorPasswordOptions.numbersMin,
            symbolsMin = generatorPasswordOptions.symbolsMin
        )
    }

    fun ensureInlinePasswordSuggestion(index: Int) {
        if (inlineGeneratedPasswords[index].isNullOrBlank()) {
            inlineGeneratedPasswords[index] = generateInlinePasswordSuggestion()
        }
    }

    fun <T> shiftIndexedStateMapAfterRemoval(stateMap: MutableMap<Int, T>, removedIndex: Int) {
        if (stateMap.isEmpty()) return
        val shiftedEntries = stateMap.entries
            .asSequence()
            .filter { it.key != removedIndex }
            .map { entry ->
                val shiftedIndex = if (entry.key > removedIndex) entry.key - 1 else entry.key
                shiftedIndex to entry.value
            }
            .toList()
        stateMap.clear()
        shiftedEntries.forEach { (shiftedIndex, value) ->
            stateMap[shiftedIndex] = value
        }
    }

    fun resetPasswordFieldTransientState() {
        inlineGeneratedPasswords.clear()
        passwordVisibilityStates.clear()
        focusedPasswordFieldIndex = null
        showPasswordGenerator = false
        currentPasswordIndexForGenerator = -1
    }

    fun removePasswordFieldAt(index: Int) {
        if (index !in passwords.indices) return
        if (!isMultiCredentialMode && index in primaryProjectGroup.passwords.indices) {
            primaryProjectGroup = primaryProjectGroup.copy(passwords = primaryProjectGroup.passwords.filterIndexed { i, _ -> i != index })
        }
        passwords.removeAt(index)
        shiftIndexedStateMapAfterRemoval(inlineGeneratedPasswords, index)
        shiftIndexedStateMapAfterRemoval(passwordVisibilityStates, index)

        val focusedIndex = focusedPasswordFieldIndex
        focusedPasswordFieldIndex = when {
            focusedIndex == null -> null
            focusedIndex == index -> null
            focusedIndex > index -> focusedIndex - 1
            else -> focusedIndex
        }

        currentPasswordIndexForGenerator = when {
            currentPasswordIndexForGenerator == index -> -1
            currentPasswordIndexForGenerator > index -> currentPasswordIndexForGenerator - 1
            else -> currentPasswordIndexForGenerator
        }
        focusedCredentialUsernameIndex = when {
            focusedCredentialUsernameIndex == index -> null
            focusedCredentialUsernameIndex != null && focusedCredentialUsernameIndex!! > index ->
                focusedCredentialUsernameIndex!! - 1
            else -> focusedCredentialUsernameIndex
        }
    }

    fun ensureCredentialScopedDraftCount(targetCount: Int = credentialUsernames.size) {
        while (credentialAuthenticatorSecrets.size < targetCount) credentialAuthenticatorSecrets.add("")
        while (credentialAuthenticatorOtpTypes.size < targetCount) {
            credentialAuthenticatorOtpTypes.add(OtpType.TOTP.name)
        }
        while (credentialAuthenticatorParameters.size < targetCount) credentialAuthenticatorParameters.add(OtpParametersDraft().encode())
        while (credentialAuthenticatorPayloads.size < targetCount) credentialAuthenticatorPayloads.add("")
        while (credentialExistingTotpIds.size < targetCount) credentialExistingTotpIds.add("")
        while (credentialExistingTotpTitles.size < targetCount) credentialExistingTotpTitles.add("")
        while (credentialAuthenticatorEditedFlags.size < targetCount) credentialAuthenticatorEditedFlags.add("0")
        while (credentialOriginalAuthenticatorKeys.size < targetCount) credentialOriginalAuthenticatorKeys.add("")
        while (credentialAttachmentDrafts.size < targetCount) credentialAttachmentDrafts.add(mutableStateListOf())
        while (credentialMetadataDrafts.size < targetCount) credentialMetadataDrafts.add(CredentialMetadataDraft())

        while (credentialAuthenticatorSecrets.size > targetCount) credentialAuthenticatorSecrets.removeAt(
            credentialAuthenticatorSecrets.lastIndex
        )
        while (credentialAuthenticatorOtpTypes.size > targetCount) credentialAuthenticatorOtpTypes.removeAt(
            credentialAuthenticatorOtpTypes.lastIndex
        )
        while (credentialAuthenticatorParameters.size > targetCount) credentialAuthenticatorParameters.removeAt(credentialAuthenticatorParameters.lastIndex)
        while (credentialAuthenticatorPayloads.size > targetCount) credentialAuthenticatorPayloads.removeAt(
            credentialAuthenticatorPayloads.lastIndex
        )
        while (credentialExistingTotpIds.size > targetCount) credentialExistingTotpIds.removeAt(
            credentialExistingTotpIds.lastIndex
        )
        while (credentialExistingTotpTitles.size > targetCount) credentialExistingTotpTitles.removeAt(
            credentialExistingTotpTitles.lastIndex
        )
        while (credentialAuthenticatorEditedFlags.size > targetCount) credentialAuthenticatorEditedFlags.removeAt(
            credentialAuthenticatorEditedFlags.lastIndex
        )
        while (credentialOriginalAuthenticatorKeys.size > targetCount) credentialOriginalAuthenticatorKeys.removeAt(
            credentialOriginalAuthenticatorKeys.lastIndex
        )
        while (credentialAttachmentDrafts.size > targetCount) credentialAttachmentDrafts.removeAt(
            credentialAttachmentDrafts.lastIndex
        )
        while (credentialMetadataDrafts.size > targetCount) credentialMetadataDrafts.removeAt(
            credentialMetadataDrafts.lastIndex
        )
    }

    fun persistCurrentAuthenticatorDraft(index: Int = selectedAuthenticatorCredentialIndex) {
        if (index !in credentialUsernames.indices) return
        ensureCredentialScopedDraftCount()
        credentialAuthenticatorSecrets[index] = authenticatorSecret
        credentialAuthenticatorOtpTypes[index] = selectedAuthenticatorOtpTypeName
        credentialAuthenticatorParameters[index] = authenticatorParametersJson
        credentialAuthenticatorPayloads[index] = authenticatorPayloadOverride.orEmpty()
        credentialExistingTotpIds[index] = existingTotpId?.toString().orEmpty()
        credentialExistingTotpTitles[index] = selectedExistingTotpTitle
        credentialAuthenticatorEditedFlags[index] = if (authenticatorEditedByUser) "1" else "0"
        credentialOriginalAuthenticatorKeys[index] = originalAuthenticatorKey
    }

    fun loadCredentialAuthenticatorDraft(index: Int) {
        if (index !in credentialUsernames.indices) return
        ensureCredentialScopedDraftCount()
        selectedAuthenticatorCredentialIndex = index
        authenticatorSecret = credentialAuthenticatorSecrets[index]
        selectedAuthenticatorOtpTypeName = credentialAuthenticatorOtpTypes[index]
        authenticatorParametersJson = credentialAuthenticatorParameters[index]
        authenticatorPayloadOverride = credentialAuthenticatorPayloads[index].takeIf { it.isNotEmpty() }
        existingTotpId = credentialExistingTotpIds[index].toLongOrNull()
        selectedExistingTotpTitle = credentialExistingTotpTitles[index]
        authenticatorEditedByUser = credentialAuthenticatorEditedFlags[index] == "1"
        originalAuthenticatorKey = credentialOriginalAuthenticatorKeys[index]
    }

    fun addCredentialField(usernameValue: String = "", passwordValue: String = "") {
        ensureCredentialScopedDraftCount()
        credentialUsernames.add(usernameValue)
        passwords.add(passwordValue)
        credentialAuthenticatorSecrets.add("")
        credentialAuthenticatorOtpTypes.add(OtpType.TOTP.name)
        credentialAuthenticatorParameters.add(OtpParametersDraft().encode())
        credentialAuthenticatorPayloads.add("")
        credentialExistingTotpIds.add("")
        credentialExistingTotpTitles.add("")
        credentialAuthenticatorEditedFlags.add("0")
        credentialOriginalAuthenticatorKeys.add("")
        credentialAttachmentDrafts.add(mutableStateListOf())
        credentialMetadataDrafts.add(CredentialMetadataDraft())
    }

    fun moveSingleEntryMetadataToFirstCredential() {
        ensureCredentialScopedDraftCount(1)
        val firstCredential = credentialMetadataDrafts.first()
        firstCredential.notes = notes
        firstCredential.boundNoteId = boundNoteId
        firstCredential.replaceEmails(emails.toList())
        firstCredential.replacePhones(phones.toList())
        firstCredential.addressLine = addressLine
        firstCredential.city = city
        firstCredential.state = state
        firstCredential.zipCode = zipCode
        firstCredential.country = country
        firstCredential.creditCardNumber = creditCardNumber
        firstCredential.creditCardHolder = creditCardHolder
        firstCredential.creditCardExpiry = creditCardExpiry
        firstCredential.creditCardCVV = creditCardCVV
        firstCredential.credentialCustomFields.clear()

        notes = ""
        boundNoteId = null
        emails.clear()
        emails.add("")
        phones.clear()
        phones.add("")
        addressLine = ""
        city = ""
        state = ""
        zipCode = ""
        country = ""
        creditCardNumber = ""
        creditCardHolder = ""
        creditCardExpiry = ""
        creditCardCVV = ""
    }

    fun restoreFirstCredentialMetadataToSingleEntry() {
        val firstCredential = credentialMetadataDrafts.firstOrNull() ?: return
        notes = firstCredential.notes
        boundNoteId = firstCredential.boundNoteId
        emails.clear()
        emails.addAll(firstCredential.emails.ifEmpty { listOf("") })
        phones.clear()
        phones.addAll(firstCredential.phones.ifEmpty { listOf("") })
        addressLine = firstCredential.addressLine
        city = firstCredential.city
        state = firstCredential.state
        zipCode = firstCredential.zipCode
        country = firstCredential.country
        creditCardNumber = firstCredential.creditCardNumber
        creditCardHolder = firstCredential.creditCardHolder
        creditCardExpiry = firstCredential.creditCardExpiry
        creditCardCVV = firstCredential.creditCardCVV

        val mergedCustomFields = mergePasswordCredentialCustomFields(
            commonFields = customFields.toList(),
            credentialFields = firstCredential.credentialCustomFields.toList()
        )
        customFields.clear()
        customFields.addAll(mergedCustomFields)
        firstCredential.credentialCustomFields.clear()
    }

    fun removeCredentialFieldAt(index: Int) {
        if (credentialUsernames.size <= 1 || index !in credentialUsernames.indices || index !in passwords.indices) {
            return
        }
        persistCurrentAuthenticatorDraft()
        credentialUsernames.removeAt(index)
        removePasswordFieldAt(index)
        credentialAuthenticatorSecrets.removeAt(index)
        credentialAuthenticatorOtpTypes.removeAt(index)
        credentialAuthenticatorParameters.removeAt(index)
        credentialAuthenticatorPayloads.removeAt(index)
        credentialExistingTotpIds.removeAt(index)
        credentialExistingTotpTitles.removeAt(index)
        credentialAuthenticatorEditedFlags.removeAt(index)
        credentialOriginalAuthenticatorKeys.removeAt(index)
        credentialAttachmentDrafts.removeAt(index)
        credentialMetadataDrafts.removeAt(index)
        credentialUsernameFocusRequesters.clear()
        credentialBringIntoViewRequesters.clear()

        selectedCredentialEditorIndex = index.coerceAtMost(credentialUsernames.lastIndex)
        if (credentialUsernames.size == 1) {
            restoreFirstCredentialMetadataToSingleEntry()
            username = credentialUsernames.first()
            multiCredentialEditorSectionName = MultiCredentialEditorSection.COMMON.name
        } else {
            multiCredentialEditorSectionName = MultiCredentialEditorSection.CREDENTIAL.name
        }
        loadCredentialAuthenticatorDraft(selectedCredentialEditorIndex)
    }

    fun showCommonCredentialEditor() {
        if (isMultiCredentialMode) persistCurrentAuthenticatorDraft()
        focusedCredentialUsernameIndex = null
        focusedPasswordFieldIndex = null
        showCredentialPicker = false
        multiCredentialEditorSectionName = MultiCredentialEditorSection.COMMON.name
    }

    fun showCredentialEditor(index: Int) {
        if (index !in credentialUsernames.indices) return
        if (isMultiCredentialMode) persistCurrentAuthenticatorDraft()
        selectedCredentialEditorIndex = index
        loadCredentialAuthenticatorDraft(index)
        showCredentialPicker = false
        multiCredentialEditorSectionName = MultiCredentialEditorSection.CREDENTIAL.name
    }

    fun addAndSelectCredential() {
        if (!canAddIndependentCredential) return
        if (credentialUsernames.size == 1) {
            moveSingleEntryMetadataToFirstCredential()
            credentialUsernames[0] = username
            selectedCredentialEditorIndex = 0
            selectedAuthenticatorCredentialIndex = 0
        }
        persistCurrentAuthenticatorDraft()
        addCredentialField()
        val newIndex = credentialUsernames.lastIndex
        selectedCredentialEditorIndex = newIndex
        loadCredentialAuthenticatorDraft(newIndex)
        multiCredentialEditorSectionName = MultiCredentialEditorSection.CREDENTIAL.name
        showCredentialPicker = false
        pendingCredentialFocusIndex = newIndex
    }

    fun credentialAuthenticatorKeyAt(index: Int): String {
        if (index !in credentialUsernames.indices) return ""
        ensureCredentialScopedDraftCount()
        credentialAuthenticatorPayloads[index].takeIf { it.isNotBlank() }?.let { return it }
        val secret = credentialAuthenticatorSecrets[index].trim()
        if (secret.isEmpty()) return ""
        val otpType = runCatching { OtpType.valueOf(credentialAuthenticatorOtpTypes[index]) }
            .getOrDefault(OtpType.TOTP)
        return buildPasswordScreenAuthenticatorPayload(
            secret = secret,
            otpType = otpType,
            issuer = title,
            accountName = credentialUsernames[index],
            parameters = OtpParametersDraft.decode(credentialAuthenticatorParameters[index])
        )
    }

    fun isPasswordFieldVisible(index: Int): Boolean = passwordVisibilityStates[index] == true

    fun togglePasswordFieldVisibility(index: Int) {
        passwordVisibilityStates[index] = !isPasswordFieldVisible(index)
    }

    fun applyCommonAccountSelection(field: String, content: String) {
        val value = content.trim()
        if (value.isEmpty()) return
        when (field) {
            "username" -> {
                val targetIndex = commonAccountSelectorTargetIndex.takeIf {
                    it in credentialUsernames.indices && (isMultiCredentialMode || !isEditing)
                }
                if (targetIndex != null) {
                    credentialUsernames[targetIndex] = value
                } else {
                    username = value
                }
            }
            "email" -> {
                val targetIndex = commonAccountSelectorTargetIndex.takeIf { it in credentialScopedEmails.indices }
                if (targetIndex != null) {
                    credentialScopedEmails[targetIndex] = value
                } else if (credentialScopedEmails.size == 1 && credentialScopedEmails[0].isEmpty()) {
                    credentialScopedEmails[0] = value
                } else {
                    credentialScopedEmails.add(value)
                }
            }
            "phone" -> {
                val targetIndex = commonAccountSelectorTargetIndex.takeIf { it in credentialScopedPhones.indices }
                if (targetIndex != null) {
                    credentialScopedPhones[targetIndex] = value
                } else if (credentialScopedPhones.size == 1 && credentialScopedPhones[0].isEmpty()) {
                    credentialScopedPhones[0] = value
                } else {
                    credentialScopedPhones.add(value)
                }
            }
            "password" -> {
                val targetIndex = commonAccountSelectorTargetIndex.takeIf { it in passwords.indices }
                if (targetIndex != null) {
                    passwords[targetIndex] = value
                } else if (passwords.size == 1 && passwords[0].isEmpty()) {
                    passwords[0] = value
                } else if (isEditing) {
                    passwords.add(value)
                }
            }
        }
    }
    
    fun normalizedIconFileName(value: String?): String? = value?.takeIf { it.isNotBlank() }?.let { File(it).name }
    fun isOriginalUploadedIconFile(value: String?): Boolean {
        val current = normalizedIconFileName(value)
        val original = normalizedIconFileName(originalCustomIconValue)
        return originalCustomIconType == PASSWORD_ICON_TYPE_UPLOADED &&
            !original.isNullOrBlank() &&
            current == original
    }
    
    val contentAttachmentFacade = remember(context) { takagi.ru.monica.attachments.AttachmentContainer.facade(context) }
    val existingContentAttachments by remember(passwordId, contentAttachmentFacade) {
        passwordId?.takeIf { it > 0 }?.let(contentAttachmentFacade::observeByPassword)
            ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    val walletDrafts = remember { EmbeddedWalletDraftStore(context) }
    DisposableEffect(walletDrafts) { onDispose { walletDrafts.close() } }
    var editingWallet by remember { mutableStateOf<EmbeddedWalletContent.Snapshot?>(null) }
    var showEmbeddedCardPicker by remember { mutableStateOf(false) }
    var showEmbeddedDocumentPicker by remember { mutableStateOf(false) }
    var copyingWallet by remember { mutableStateOf(false) }
    var walletCopyError by remember { mutableStateOf<String?>(null) }
    var retryWalletCopy by remember { mutableStateOf<(() -> Unit)?>(null) }
    var retryWalletSave by remember { mutableStateOf<(() -> Unit)?>(null) }
    val contentExtraFields = if (isMultiCredentialMode) activeCredentialMetadata.credentialCustomFields else customFields
    var editingBlock by remember(passwordId, selectedCredentialEditorIndex) { mutableStateOf<PasswordContentBlocks.Block?>(null) }
    var viewingBlock by remember(passwordId, selectedCredentialEditorIndex) { mutableStateOf<PasswordContentBlocks.Stored?>(null) }
    val blockFields = if (isMultiCredentialMode && showCommonEditorContent) emptyList() else contentExtraFields.toList()
    val storedBlocks = remember(blockFields) { PasswordContentBlocks.read(blockFields) }
    fun replaceBlockFields(updated: List<CustomFieldDraft>) { contentExtraFields.clear(); contentExtraFields.addAll(updated) }

    fun copiedContent(kind: EmbeddedWalletContent.Kind) =
        (EmbeddedWalletContent.read(contentExtraFields.firstOrNull { it.title == EmbeddedWalletContent.fieldName(kind) }?.value)
            as? EmbeddedWalletContent.ReadResult.Available)?.snapshot
    fun copyWalletItem(item: takagi.ru.monica.data.SecureItem) {
        if (copyingWallet) return
        val targetFields = contentExtraFields
        val targetCredential = if (isMultiCredentialMode) activeCredentialMetadata else null
        copyingWallet = true
        coroutineScope.launch {
            var prepared: EmbeddedWalletCopyService.Prepared? = null
            try {
                val decoded = withContext(Dispatchers.IO) { item.copy(
                    itemData = securityManager.decryptDataIfMonicaCiphertext(item.itemData),
                    notes = securityManager.decryptDataIfMonicaCiphertext(item.notes)) }
                prepared = EmbeddedWalletCopyService(context).prepare(decoded)
                val updated = EmbeddedWalletContent.put(targetFields.toList(), prepared.snapshot)
                walletDrafts.add(prepared)
                targetFields.clear(); targetFields.addAll(updated)
                if (item.itemType == takagi.ru.monica.data.ItemType.BANK_CARD) {
                    val card = requireNotNull(takagi.ru.monica.data.model.CardWalletDataCodec.parseBankCardData(prepared.snapshot.itemData.toString()))
                    val expiry = takagi.ru.monica.ui.components.EntryPaymentFormat.joinExpiry(card.expiryMonth, card.expiryYear)
                    if (targetCredential != null) {
                        targetCredential.creditCardNumber = card.cardNumber; targetCredential.creditCardHolder = card.cardholderName
                        targetCredential.creditCardExpiry = expiry; targetCredential.creditCardCVV = card.cvv
                    } else {
                        creditCardNumber = card.cardNumber; creditCardHolder = card.cardholderName
                        creditCardExpiry = expiry; creditCardCVV = card.cvv
                    }
                }
                if (item.itemType == takagi.ru.monica.data.ItemType.NOTE) {
                    if (targetCredential != null) targetCredential.boundNoteId = null else boundNoteId = null
                }
                if (editingWallet != null) editingWallet = prepared.snapshot
                retryWalletCopy = null
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                prepared?.close(); throw cancelled
            } catch (_: Exception) {
                prepared?.close()
                walletCopyError = item.title
                retryWalletCopy = { copyWalletItem(item) }
            } finally { copyingWallet = false }
        }
    }
    if (copyingWallet) AlertDialog(onDismissRequest = {},
        title = { Text(stringResource(R.string.embedded_copy_working)) },
        text = { LinearProgressIndicator(Modifier.fillMaxWidth()) }, confirmButton = {})
    walletCopyError?.let { failedTitle -> AlertDialog(onDismissRequest = { walletCopyError = null },
        title = { Text(stringResource(R.string.embedded_copy_failed)) },
        text = { Text(stringResource(R.string.embedded_copy_failed_message, failedTitle)) },
        confirmButton = { TextButton(onClick = { walletCopyError = null; (retryWalletSave ?: retryWalletCopy)?.invoke() }) { Text(stringResource(R.string.retry)) } },
        dismissButton = { TextButton(onClick = { walletCopyError = null }) { Text(stringResource(R.string.cancel)) } }) }

    var walletParent by remember(passwordId) { mutableStateOf<takagi.ru.monica.data.PasswordEntry?>(null) }
    LaunchedEffect(passwordId) { walletParent = passwordId?.let { viewModel.getPasswordEntryById(it) } }
    editingWallet?.let { snapshot ->
        val destination = contentExtraFields
        takagi.ru.monica.ui.components.EmbeddedWalletEditorDialog(snapshot, walletDrafts.draft(snapshot.id), walletParent,
            bankCardViewModel, noteViewModel, onCopyDocument = { showEmbeddedDocumentPicker = true }, onCopyCard = if (bankCardViewModel != null) ({ showEmbeddedCardPicker = true }) else null, onDismiss = { editingWallet = null }, onSaved = { prepared ->
                val updated = EmbeddedWalletContent.put(destination.toList(), prepared.snapshot)
                walletDrafts.add(prepared)
                destination.clear(); destination.addAll(updated)
                if (prepared.snapshot.kind == EmbeddedWalletContent.Kind.BANK_CARD) {
                    val card = requireNotNull(takagi.ru.monica.data.model.CardWalletDataCodec.parseBankCardData(prepared.snapshot.itemData.toString()))
                    val expiry = takagi.ru.monica.ui.components.EntryPaymentFormat.joinExpiry(card.expiryMonth, card.expiryYear)
                    if (isMultiCredentialMode) {
                        activeCredentialMetadata.creditCardNumber = card.cardNumber; activeCredentialMetadata.creditCardHolder = card.cardholderName
                        activeCredentialMetadata.creditCardExpiry = expiry; activeCredentialMetadata.creditCardCVV = card.cvv
                    } else {
                        creditCardNumber = card.cardNumber; creditCardHolder = card.cardholderName
                        creditCardExpiry = expiry; creditCardCVV = card.cvv
                    }
                }
                if (prepared.snapshot.kind == EmbeddedWalletContent.Kind.ADDRESS) {
                    val address = requireNotNull(takagi.ru.monica.data.model.CardWalletDataCodec.parseBillingAddressData(prepared.snapshot.itemData.toString()))
                    credentialScopedEmails.clear(); credentialScopedEmails.addAll(address.email.split('|'))
                    credentialScopedPhones.clear(); credentialScopedPhones.addAll(address.phone.split('|'))
                    if (isMultiCredentialMode) {
                        activeCredentialMetadata.addressLine = address.streetAddress; activeCredentialMetadata.city = address.city
                        activeCredentialMetadata.state = address.stateProvince; activeCredentialMetadata.zipCode = address.postalCode; activeCredentialMetadata.country = address.country
                    } else {
                        addressLine = address.streetAddress; city = address.city; state = address.stateProvince
                        zipCode = address.postalCode; country = address.country
                    }
                }
                if (prepared.snapshot.kind == EmbeddedWalletContent.Kind.NOTE) {
                    if (isMultiCredentialMode) activeCredentialMetadata.boundNoteId = null else boundNoteId = null
                }
                editingWallet = null
            })
    }
    fun addressProjection() = takagi.ru.monica.data.model.PasswordWalletProjection.address(
        PasswordEntry(title = title, username = username, password = "", website = "",
            email = credentialScopedEmails.joinToString("|"), phone = credentialScopedPhones.joinToString("|"),
            addressLine = credentialScopedAddressLine, city = credentialScopedCity, state = credentialScopedState,
            zipCode = credentialScopedZipCode, country = credentialScopedCountry), contentExtraFields.toList(), context.getString(R.string.billing_address))
    fun openAddressEditor() {
        try { editingWallet = addressProjection() }
        catch (_: Exception) { walletCopyError = context.getString(R.string.billing_address) }
    }
    fun openPaymentEditor() {
        editingWallet = copiedContent(EmbeddedWalletContent.Kind.BANK_CARD) ?: run {
            val (month, year) = takagi.ru.monica.ui.components.EntryPaymentFormat.splitExpiry(credentialScopedCreditCardExpiry)
            val card = takagi.ru.monica.data.model.BankCardData(cardNumber = credentialScopedCreditCardNumber,
                cardholderName = credentialScopedCreditCardHolder, expiryMonth = month, expiryYear = year, cvv = credentialScopedCreditCardCVV)
            EmbeddedWalletContent.create(takagi.ru.monica.data.SecureItem(itemType = takagi.ru.monica.data.ItemType.BANK_CARD,
                title = context.getString(R.string.payment_info), itemData = kotlinx.serialization.json.JsonObject(
                    kotlinx.serialization.json.Json.parseToJsonElement(takagi.ru.monica.data.model.CardWalletDataCodec.encodeBankCardData(card)).jsonObject +
                    EntrySupplementalSpecs.forSection("PAYMENT").mapNotNull { spec ->
                        EntryContentFields.value(contentExtraFields, "PAYMENT", spec.key).takeIf { it.isNotBlank() }
                            ?.let { spec.key to kotlinx.serialization.json.JsonPrimitive(it) }
                    }.toMap()).toString()))
        }
    }
    fun openDocumentEditor() {
        val stored = EmbeddedWalletContent.read(contentExtraFields.firstOrNull {
            it.title == EmbeddedWalletContent.fieldName(EmbeddedWalletContent.Kind.DOCUMENT)
        }?.value)
        when (stored) {
            is EmbeddedWalletContent.ReadResult.Available -> editingWallet = stored.snapshot
            is EmbeddedWalletContent.ReadResult.Unavailable -> walletCopyError = context.getString(R.string.password_content_document)
            EmbeddedWalletContent.ReadResult.Missing -> editingWallet = EmbeddedWalletContent.create(
                takagi.ru.monica.data.SecureItem(itemType = takagi.ru.monica.data.ItemType.DOCUMENT,
                    title = context.getString(R.string.password_content_document),
                    itemData = takagi.ru.monica.data.model.CardWalletDataCodec.encodeDocumentData(
                        takagi.ru.monica.data.model.DocumentData(takagi.ru.monica.data.model.DocumentType.ID_CARD, "", ""))))
        }
    }
    if (showEmbeddedDocumentPicker) {
        val documentRepository = remember(database, securityManager) {
            takagi.ru.monica.repository.SecureItemRepository(database.secureItemDao(), decryptSensitiveValue = securityManager::decryptDataIfMonicaCiphertext)
        }
        val documentsModel: takagi.ru.monica.viewmodel.DocumentViewModel = androidx.lifecycle.viewmodel.compose.viewModel {
            takagi.ru.monica.viewmodel.DocumentViewModel(documentRepository, context, database.localKeePassDatabaseDao(), securityManager,
                strings = takagi.ru.monica.utils.AppLocaleStringResolver(context))
        }
        val documents by documentsModel.allDocuments.collectAsState(initial = emptyList())
        takagi.ru.monica.ui.components.EmbeddedWalletPicker(documents, stringResource(R.string.embedded_copy_document),
            onSelect = { copyWalletItem(it); showEmbeddedDocumentPicker = false },
            onDismiss = { showEmbeddedDocumentPicker = false },
            keepassDatabases = keepassDatabases, mdbxDatabases = mdbxDatabases, bitwardenVaults = bitwardenVaults)
    }
    if (showEmbeddedCardPicker && bankCardViewModel != null) {
        val cards by bankCardViewModel.parsedCards.collectAsState(initial = emptyList())
        takagi.ru.monica.ui.components.EmbeddedWalletPicker(cards.map { it.item }, stringResource(R.string.embedded_copy_card),
            onSelect = { copyWalletItem(it); showEmbeddedCardPicker = false },
            onDismiss = { showEmbeddedCardPicker = false },
            keepassDatabases = keepassDatabases, mdbxDatabases = mdbxDatabases, bitwardenVaults = bitwardenVaults)
    }
    fun hasExtraSection(section: String) = contentExtraFields.any { field ->
        EntrySupplementalSpecs.forSection(section).any { EntryContentFields.key(section, it.key) == field.title }
    }

    // 判断字段是否应该显示：设置开启 或 条目已有该字段数据
    fun shouldShowSecurityVerification() =
        !isBarcodeMode && (contentMode || contentSectionEnabled(PasswordContentSection.AUTHENTICATOR, fieldVisibility.securityVerification) || authenticatorSecret.isNotEmpty())
    fun shouldShowCategoryAndNotes() =
        contentSectionEnabled(PasswordContentSection.NOTES, fieldVisibility.categoryAndNotes || noteViewModel != null) ||
            credentialScopedNotes.isNotEmpty() || credentialScopedBoundNoteId != null || copiedContent(EmbeddedWalletContent.Kind.NOTE) != null
    fun shouldShowPersonalInfo() =
        !isBarcodeMode && (contentSectionEnabled(PasswordContentSection.CONTACT, fieldVisibility.personalInfo) ||
            credentialScopedEmails.any { it.isNotEmpty() } ||
            credentialScopedPhones.any { it.isNotEmpty() } || hasExtraSection("CONTACT"))
    // 地址信息仅看开关 + 当前条目已有数据；
    // 不再因为「常用账号」里存过账单地址而强制展示，否则用户关了开关仍会看到面板。
    fun shouldShowAddressInfo() =
        !isBarcodeMode && (contentSectionEnabled(PasswordContentSection.ADDRESS, fieldVisibility.addressInfo) ||
            credentialScopedAddressLine.isNotEmpty() || credentialScopedCity.isNotEmpty() ||
            credentialScopedState.isNotEmpty() || credentialScopedZipCode.isNotEmpty() ||
            credentialScopedCountry.isNotEmpty() || hasExtraSection("ADDRESS"))
    fun shouldShowPaymentInfo() =
        !isBarcodeMode && (copiedContent(EmbeddedWalletContent.Kind.BANK_CARD) != null || contentSectionEnabled(PasswordContentSection.PAYMENT, fieldVisibility.paymentInfo) ||
            credentialScopedCreditCardNumber.isNotEmpty() ||
            credentialScopedCreditCardHolder.isNotEmpty() ||
            credentialScopedCreditCardExpiry.isNotEmpty() ||
            credentialScopedCreditCardCVV.isNotEmpty() || hasExtraSection("PAYMENT"))
    
    fun visibleContentSections(): List<PasswordContentSection> {
        val ordered = (takagi.ru.monica.data.model.PasswordWalletProjection.walletOrder(requestedContentSections).mapNotNull { name -> PasswordContentSection.entries.firstOrNull { it.name == name } } + PasswordContentSection.entries).distinct()
        return ordered.filter { section -> when (section) {
            PasswordContentSection.DOCUMENT -> !isBarcodeMode && (section.name in requestedContentSections || contentExtraFields.any {
                it.title == EmbeddedWalletContent.fieldName(EmbeddedWalletContent.Kind.DOCUMENT) })
            PasswordContentSection.NOTES -> shouldShowCategoryAndNotes()
            PasswordContentSection.AUTHENTICATOR -> shouldShowSecurityVerification()
            PasswordContentSection.PAYMENT -> shouldShowPaymentInfo()
            PasswordContentSection.CONTACT -> false
            PasswordContentSection.ADDRESS -> shouldShowAddressInfo() || shouldShowPersonalInfo() || copiedContent(EmbeddedWalletContent.Kind.ADDRESS) != null
            PasswordContentSection.CUSTOM_FIELDS -> section.name in requestedContentSections || contentExtraFields.any { it.title != PasswordUrlRules.FIELD && it.title != ProjectCredentialGroup.FIELD && it.title != EntryContentFields.ORDER && !EmbeddedWalletContent.isMetadata(it.title) && !PasswordContentBlocks.owns(it.title) && !takagi.ru.monica.data.model.TemplateCredentialDraft.ownsField(it.title) && !CredentialExchangeMetadata.ownsField(it.title) && EntrySupplementalSpecs.spec(it.title) == null }
            PasswordContentSection.ATTACHMENTS -> section.name in requestedContentSections || existingContentAttachments.isNotEmpty() || credentialAttachmentDrafts.any { it.isNotEmpty() }
        } }
    }
    fun visibleContentTokens(): List<String> = takagi.ru.monica.data.model.PasswordWalletProjection.walletOrder(listOf("AUTHENTICATOR") + requestedContentSections +
        EntryContentFields.order(contentExtraFields) + visibleContentSections().map { it.name } + storedBlocks.map { it.token } + extraCredentialGroups.map { ProjectCredentialGroup.token(it.id) })
        .filter { key -> key in visibleContentSections().map { it.name } || storedBlocks.any { it.token == key } || extraCredentialGroups.any { ProjectCredentialGroup.token(it.id) == key } }
    fun moveContentToken(token: String, offset: Int) {
        val ordered = visibleContentTokens().toMutableList()
        val index = ordered.indexOf(token)
        val destination = index + offset
        // The default inline authenticator stays beside credentials; all optional content can interleave.
        if (token == "AUTHENTICATOR" || index < 0 || destination !in ordered.indices || ordered[destination] == "AUTHENTICATOR") return
        ordered.removeAt(index); ordered.add(destination, token)
        requestedContentSections.clear(); requestedContentSections.addAll(ordered)
    }
    fun moveContent(section: PasswordContentSection, offset: Int) = moveContentToken(section.name, offset)
    fun removeContent(section: PasswordContentSection) {
        when (section) {
            PasswordContentSection.DOCUMENT -> Unit
            PasswordContentSection.NOTES -> if (isMultiCredentialMode) { activeCredentialMetadata.notes = ""; activeCredentialMetadata.boundNoteId = null }
                else { notes = ""; boundNoteId = null }
            PasswordContentSection.AUTHENTICATOR -> { authenticatorSecret = ""; authenticatorEditedByUser = true }
            PasswordContentSection.CONTACT -> { credentialScopedEmails.clear(); credentialScopedEmails.add(""); credentialScopedPhones.clear(); credentialScopedPhones.add("") }
            PasswordContentSection.ADDRESS -> if (isMultiCredentialMode) {
                activeCredentialMetadata.addressLine = ""; activeCredentialMetadata.city = ""; activeCredentialMetadata.state = ""; activeCredentialMetadata.zipCode = ""; activeCredentialMetadata.country = ""
            } else { addressLine = ""; city = ""; state = ""; zipCode = ""; country = "" }
            PasswordContentSection.PAYMENT -> if (isMultiCredentialMode) {
                activeCredentialMetadata.creditCardNumber = ""; activeCredentialMetadata.creditCardHolder = ""; activeCredentialMetadata.creditCardExpiry = ""; activeCredentialMetadata.creditCardCVV = ""
            } else { creditCardNumber = ""; creditCardHolder = ""; creditCardExpiry = ""; creditCardCVV = "" }
            PasswordContentSection.CUSTOM_FIELDS -> contentExtraFields.removeAll { it.title != PasswordUrlRules.FIELD && it.title != ProjectCredentialGroup.FIELD && it.title != EntryContentFields.ORDER && !EmbeddedWalletContent.isMetadata(it.title) && !PasswordContentBlocks.owns(it.title) && !takagi.ru.monica.data.model.TemplateCredentialDraft.ownsField(it.title) && !CredentialExchangeMetadata.ownsField(it.title) && EntrySupplementalSpecs.spec(it.title) == null }
            PasswordContentSection.ATTACHMENTS -> credentialAttachmentDrafts.getOrNull(if (isMultiCredentialMode) selectedCredentialEditorIndex else 0)?.clear()
        }
        contentExtraFields.removeAll { field -> EntrySupplementalSpecs.forSection(section.name).any { EntryContentFields.key(section.name, it.key) == field.title } }
        val copiedKind = when (section) {
            PasswordContentSection.DOCUMENT -> EmbeddedWalletContent.Kind.DOCUMENT
            PasswordContentSection.PAYMENT -> EmbeddedWalletContent.Kind.BANK_CARD
            PasswordContentSection.NOTES -> EmbeddedWalletContent.Kind.NOTE
            PasswordContentSection.ADDRESS -> EmbeddedWalletContent.Kind.ADDRESS
            else -> null
        }
        copiedKind?.let { kind -> contentExtraFields.removeAll { it.title == EmbeddedWalletContent.fieldName(kind) } }
        if (section == PasswordContentSection.ADDRESS) {
            credentialScopedEmails.clear(); credentialScopedEmails.add("")
            credentialScopedPhones.clear(); credentialScopedPhones.add("")
            contentExtraFields.removeAll { it.title.startsWith("monica.content.contact.") || it.title.startsWith("monica.content.address.") }
            requestedContentSections.remove("CONTACT")
        }
        requestedContentSections.remove(section.name)
        val orderIndex = customFields.indexOfFirst { it.title == EntryContentFields.ORDER }
        if (orderIndex >= 0) customFields[orderIndex] = customFields[orderIndex].copy(value = EntryContentFields.order(customFields).filterNot { it == section.name }.joinToString(","))
        editingContentSection = null
    }
    LaunchedEffect(editingContentSection, contentMode, requestedContentSections.toList()) {
        val selected = editingContentSection ?: return@LaunchedEffect
        if (!contentMode) return@LaunchedEffect
        // The editor dialog belongs to its lazy item: compose the selected card before opening it.
        androidx.compose.runtime.withFrameNanos { }
        val visible = visibleContentTokens().filter { showCredentialEditorContent || it == PasswordContentSection.CUSTOM_FIELDS.name }
        val position = visible.indexOf(selected)
        if (position >= 0) {
            val count = editorListState.layoutInfo.totalItemsCount
            val index = count - 1 - visible.size + position
            if (index in 0 until count) editorListState.scrollToItem(index)
        }
    }

    val contentDragState = remember { takagi.ru.monica.ui.components.DirectDragState() }
    val reorderHaptics = takagi.ru.monica.ui.haptic.rememberHapticFeedback()
    val contentReorderState = rememberReorderableLazyListState(editorListState,
        scroller = takagi.ru.monica.ui.components.rememberDirectReorderScroller(editorListState)) { from, to ->
        val tokens = visibleContentTokens()
        val fromIndex = tokens.indexOfFirst { entryContentItemKey(it) == from.key }
        val toIndex = tokens.indexOfFirst { entryContentItemKey(it) == to.key }
        if (fromIndex >= 0 && toIndex >= 0 && fromIndex != toIndex && tokens[toIndex] != "AUTHENTICATOR") {
            moveContentToken(tokens[fromIndex], toIndex - fromIndex)
            reorderHaptics.performLightClick()
        }
    }
    fun LazyListScope.contentItem(key: String, content: @Composable LazyItemScope.() -> Unit) {
        reorderableContentItem(key, contentReorderState, enabled = contentMode, dragState = contentDragState,
            groupKeys = visibleContentTokens().filterNot { it == "AUTHENTICATOR" }.map(::entryContentItemKey), content = content)
    }

    val contentActions: (PasswordContentSection) -> takagi.ru.monica.ui.components.EntryContentActions = { section ->
        val visible = visibleContentTokens().filterNot { it == "AUTHENTICATOR" }
        val index = visible.indexOf(section.name)
        takagi.ru.monica.ui.components.EntryContentActions(
            moveUp = if (index > 0) ({ moveContent(section, -1) }) else null,
            moveDown = if (index >= 0 && index < visible.lastIndex) ({ moveContent(section, 1) }) else null,
            // Existing attachments retain their individual storage-aware delete controls.
            remove = if (section == PasswordContentSection.ATTACHMENTS && existingContentAttachments.isNotEmpty()) null else ({ removeContent(section) }),
            groupIndex = index.coerceAtLeast(0), groupCount = visible.size.coerceAtLeast(1))
    }

    editingBlock?.let { block -> PasswordContentBlockEditor(block,
        onSave = { saved ->
            val currentOrder = visibleContentTokens()
            val updated = PasswordContentBlocks.put(contentExtraFields.toList(), saved)
            replaceBlockFields(updated)
            val token = PasswordContentBlocks.token(saved.id)
            requestedContentSections.clear(); requestedContentSections.addAll((currentOrder + token).distinct())
            editingBlock = null
        }, onDismiss = { editingBlock = null }, qrValues = {
            takagi.ru.monica.data.model.PasswordQrTemplate.Values(mapOf(
                "ACCOUNT" to authenticatorAccountName,
                "PASSWORD" to passwords.getOrNull(if (isMultiCredentialMode) selectedCredentialEditorIndex else 0).orEmpty(),
                "TITLE" to title, "URL" to website,
                "EMAIL" to credentialScopedEmails.firstOrNull().orEmpty(), "PHONE" to credentialScopedPhones.firstOrNull().orEmpty(),
                "NOTES" to credentialScopedNotes),
                contentExtraFields.filterNot { it.title.startsWith("monica.") }.map { it.title to it.value })
        }) }
    viewingBlock?.let { PasswordContentBlockDetail(it, onDismiss = { viewingBlock = null }) }

    // 新建条目时的自动填充标记（只执行一次）
    var hasAutoFilled by rememberSaveable { mutableStateOf(false) }
    var initialDraftApplied by remember(passwordId) { mutableStateOf(false) }

    fun applyUploadedIcon(fileName: String) {
        customIconEditedByUser = true
        if (customIconType == PASSWORD_ICON_TYPE_UPLOADED && customIconValue != fileName) {
            val previous = normalizedIconFileName(customIconValue)
            if (!previous.isNullOrBlank() && !isOriginalUploadedIconFile(previous)) {
                PasswordCustomIconStore.deleteIconFile(context, previous)
            }
        }
        customIconType = PASSWORD_ICON_TYPE_UPLOADED
        customIconValue = fileName
        customIconUpdatedAt = System.currentTimeMillis()
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            val imported = PasswordCustomIconStore.importAndCompress(context, uri)
            imported.onSuccess { fileName ->
                applyUploadedIcon(fileName)
                Toast.makeText(context, context.getString(R.string.custom_icon_upload_success), Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                Toast.makeText(
                    context,
                    context.getString(R.string.custom_icon_upload_failed, error.message ?: ""),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
    
    DisposableEffect(activity, passwordId) {
        onDispose {
            // A configuration change restores this draft in the replacement activity.
            if (!hasSavedSuccessfully && activity?.isChangingConfigurations != true) {
                val currentUploaded = if (customIconType == PASSWORD_ICON_TYPE_UPLOADED) {
                    normalizedIconFileName(customIconValue)
                } else {
                    null
                }
                if (!currentUploaded.isNullOrBlank() && !isOriginalUploadedIconFile(currentUploaded)) {
                    PasswordCustomIconStore.deleteIconFile(context, currentUploaded)
                }
            }
        }
    }

    // 旧版默认账号信息迁移到模板，避免新页面出现“默认XXX”遗留项
    LaunchedEffect(commonAccountTypeAccount, commonAccountTypeEmail, commonAccountTypePhone, fieldAccountLabel, fieldEmailLabel, fieldPhoneLabel) {
        commonAccountPreferences.migrateLegacyDefaultsToTemplatesIfNeeded(
            accountType = commonAccountTypeAccount,
            emailType = commonAccountTypeEmail,
            phoneType = commonAccountTypePhone,
            accountTitle = fieldAccountLabel,
            emailTitle = fieldEmailLabel,
            phoneTitle = fieldPhoneLabel
        )
    }
    
    // 新建条目时自动填充常用账号信息
    LaunchedEffect(commonAccountInfo, isEditing, hasAutoFilled) {
        if (!isEditing && !hasAutoFilled && commonAccountInfo.autoFillEnabled && commonAccountInfo.hasAnyInfo()) {
            hasAutoFilled = true
            if (credentialUsernames.firstOrNull().isNullOrEmpty() && commonAccountInfo.username.isNotEmpty()) {
                if (credentialUsernames.isEmpty()) credentialUsernames.add("")
                credentialUsernames[0] = commonAccountInfo.username
                if (username.isEmpty()) username = commonAccountInfo.username
            }
            if (emails.size == 1 && emails[0].isEmpty() && commonAccountInfo.email.isNotEmpty()) {
                emails[0] = commonAccountInfo.email
            }
            if (phones.size == 1 && phones[0].isEmpty() && commonAccountInfo.phone.isNotEmpty()) {
                phones[0] = commonAccountInfo.phone
            }
            if (
                addressLine.isBlank() &&
                city.isBlank() &&
                state.isBlank() &&
                zipCode.isBlank() &&
                country.isBlank() &&
                !commonAccountInfo.billingAddress.isEmpty()
            ) {
                applyCommonBillingAddress(commonAccountInfo.billingAddress)
            }
        }
    }
    
    var previousCredentialCardMode by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(usesCredentialCards) {
        val previousMode = previousCredentialCardMode
        if (previousMode != null && previousMode != usesCredentialCards && !isEditing && !isBarcodeMode) {
            if (usesCredentialCards) {
                if (credentialUsernames.isEmpty()) credentialUsernames.add("")
                credentialUsernames[0] = username
            } else {
                username = credentialUsernames.firstOrNull().orEmpty()
            }
        }
        previousCredentialCardMode = usesCredentialCards
    }

    LaunchedEffect(credentialUsernames.size) {
        ensureCredentialScopedDraftCount()
        selectedCredentialEditorIndex = selectedCredentialEditorIndex.coerceIn(
            0,
            credentialUsernames.lastIndex.coerceAtLeast(0)
        )
        if (credentialUsernames.size <= 1) {
            showCredentialPicker = false
            multiCredentialEditorSectionName = MultiCredentialEditorSection.COMMON.name
        }
    }

    // 新建条目时初始化预设自定义字段（只执行一次）
    var hasLoadedPresets by rememberSaveable { mutableStateOf(false) }
    
    LaunchedEffect(presetCustomFields, isEditing, hasLoadedPresets) {
        if (!isEditing && !hasLoadedPresets && presetCustomFields.isNotEmpty()) {
            hasLoadedPresets = true
            // 将预设字段添加到自定义字段列表（按order排序）
            val presetDrafts = presetCustomFields
                .filter { it.fieldName.trim().isNotEmpty() }
                .sortedBy { it.order }
                .map { preset -> CustomFieldDraft.fromPreset(preset) }
            customFields.addAll(presetDrafts)
            // 如果有预设字段，默认展开自定义字段区域
            if (presetDrafts.isNotEmpty()) {
                customFieldsExpanded = true
            }
        }
    }

    // Load existing password data (including siblings)
    LaunchedEffect(passwordId) {
        resetPasswordFieldTransientState()
        if (passwordId != null) {
            coroutineScope.launch {
                val actualId = if (passwordId < 0) -passwordId else passwordId
                withContext(Dispatchers.IO) {
                    viewModel.getRawPasswordEntryById(actualId, forEdit = true)
                }?.let { rawEntry ->
                    hasOwnershipConflict = viewModel.hasOwnershipConflict(rawEntry)
                    val secretState = withContext(Dispatchers.Default) {
                        viewModel.inspectSecretState(rawEntry)
                    }
                    val entry = rawEntry.copy(
                        password = secretState.plainValueOrEmpty(),
                        username = if (autofillPasswordUpdate != null) {
                            takagi.ru.monica.autofill_ng.AutofillSecretResolver.decryptPasswordOrNull(securityManager, rawEntry.username) ?: rawEntry.username
                        } else rawEntry.username,
                    )
                    title = entry.title
                    replaceWebsiteUrlsFromRaw(entry.website)
                    username = entry.username
                    credentialUsernames.clear()
                    credentialUsernames.add(entry.username)
                    notes = entry.notes
                    boundNoteId = entry.boundNoteId
                    appPackageName = entry.appPackageName
                    appName = entry.appName
                    
                    // Load emails (stored as pipe-separated)
                    emails.clear()
                    if (entry.email.isNotEmpty()) {
                        emails.addAll(entry.email.split("|").filter { it.isNotBlank() })
                    }
                    if (emails.isEmpty()) emails.add("")
                    
                    // Load phones (stored as pipe-separated)
                    phones.clear()
                    if (entry.phone.isNotEmpty()) {
                        phones.addAll(entry.phone.split("|").filter { it.isNotBlank() })
                    }
                    if (phones.isEmpty()) phones.add("")
                    addressLine = entry.addressLine
                    city = entry.city
                    state = entry.state
                    zipCode = entry.zipCode
                    country = entry.country
                    creditCardNumber = entry.creditCardNumber
                    creditCardHolder = entry.creditCardHolder
                    creditCardExpiry = entry.creditCardExpiry
                    creditCardCVV = entry.creditCardCVV
                    categoryId = entry.categoryId
                    keepassDatabaseId = entry.keepassDatabaseId
                    keepassGroupPath = entry.keepassGroupPath
                    editingKeePassEntryUuid = entry.keepassEntryUuid
                    mdbxDatabaseId = entry.mdbxDatabaseId
                    mdbxFolderId = entry.mdbxFolderId
                    bitwardenVaultId = entry.bitwardenVaultId
                    bitwardenFolderId = entry.bitwardenFolderId
                    currentReplicaGroupId = entry.replicaGroupId
                    if (!authenticatorEditedByUser) {
                        val resolvedAuthenticatorKey = withContext(Dispatchers.Default) {
                            runCatching {
                                securityManager.decryptDataIfMonicaCiphertext(entry.authenticatorKey)
                            }.getOrDefault(entry.authenticatorKey)
                        }
                        val authenticatorDraft = withContext(Dispatchers.Default) {
                            resolvePasswordScreenAuthenticatorDraft(
                                rawKey = resolvedAuthenticatorKey,
                                fallbackIssuer = entry.title,
                                fallbackAccountName = entry.username
                            )
                        }
                        authenticatorSecret = authenticatorDraft.secret
                        selectedAuthenticatorOtpTypeName = authenticatorDraft.otpType.name
                        authenticatorParametersJson = authenticatorDraft.parameters.encode()
                        originalAuthenticatorKey = resolvedAuthenticatorKey
                        authenticatorPayloadOverride = resolvedAuthenticatorKey
                            .takeIf { it.isNotBlank() && it != authenticatorDraft.secret }
                        existingTotpId = null
                        selectedExistingTotpTitle = ""
                    }
                    passkeyBindings = entry.passkeyBindings
                    existingSshKeyData = entry.sshKeyData
                    preservedWifiMetadata = entry.wifiMetadata
                    if (secretState is SecretValueState.Unreadable || secretState is SecretValueState.Hidden) templateLoadFailed = true
                    try {
                        templateDraft = takagi.ru.monica.data.model.TemplateCredentialDraft.load(entry,
                            viewModel.getCustomFieldsByEntryIdSync(actualId).map(CustomFieldDraft::fromCustomField))
                    } catch (cancelled: CancellationException) { throw cancelled
                    } catch (_: Exception) { templateLoadFailed = true }
                    
                    // 加载SSO登录方式字段
                    loginType = when {
                        templateDraft != null -> templateDraft!!.type
                        entry.isBarcodeEntry() -> LOGIN_TYPE_BARCODE
                        entry.loginType.equals("SSO", ignoreCase = true) -> "SSO"
                        else -> "PASSWORD"
                    }
                    barcodePayload = if (entry.isBarcodeEntry()) entry.password else ""
                    ssoProvider = entry.ssoProvider
                    ssoRefEntryId = entry.ssoRefEntryId
                    if (!customIconEditedByUser) {
                        customIconType = entry.customIconType
                        customIconValue = normalizedIconFileName(entry.customIconValue)
                        customIconUpdatedAt = entry.customIconUpdatedAt
                    }
                    originalCustomIconType = entry.customIconType
                    originalCustomIconValue = normalizedIconFileName(entry.customIconValue)

                    if (isEditing) {
                        isFavorite = entry.isFavorite
                        
                        // Fetch all passwords in the group
                        val allEntries = withContext(Dispatchers.IO) {
                            viewModel.getRawActivePasswordEntries()
                        }
                        val key = withContext(Dispatchers.Default) {
                            buildPasswordSiblingGroupKey(rawEntry)
                        }
                        val siblings = withContext(Dispatchers.Default) {
                            allEntries.filter { item: PasswordEntry ->
                                val itKey = buildPasswordSiblingGroupKey(item)
                                itKey == key
                            }.sortedBy { it.id }
                        }
                        
                        allProjectOriginalIds = siblings.map { it.id }
                        val projectFields = withContext(Dispatchers.IO) {
                            viewModel.getCustomFieldsByEntryIds(siblings.map { it.id }).mapValues { (_, fields) -> fields.map(CustomFieldDraft::fromCustomField) }
                        }
                        hasProjectCredentialMetadata = projectFields.values.any { fields -> fields.any { it.title == ProjectCredentialGroup.FIELD } }
                        val restoredGroups = if (hasProjectCredentialMetadata) try {
                            ProjectCredentialGroup.restore(siblings.map { sibling ->
                                val state = viewModel.inspectSecretState(sibling)
                                check(state is SecretValueState.Available || state == SecretValueState.Empty)
                                sibling.copy(password = state.plainValueOrEmpty(), authenticatorKey = securityManager.decryptDataIfMonicaCiphertext(sibling.authenticatorKey),
                                    username = if (autofillPasswordUpdate != null) takagi.ru.monica.autofill_ng.AutofillSecretResolver.decryptPasswordOrNull(securityManager, sibling.username) ?: sibling.username else sibling.username)
                            }, projectFields)
                        } catch (_: Exception) { projectCredentialLoadFailed = true; emptyList() } else emptyList()
                        if (restoredGroups.isNotEmpty()) {
                            primaryProjectGroup = restoredGroups.first()
                            extraCredentialGroups.clear(); extraCredentialGroups.addAll(restoredGroups.drop(1))
                            username = primaryProjectGroup.username
                            credentialUsernames.clear(); credentialUsernames.add(username)
                            applyAuthenticatorInput(primaryProjectGroup.otp)
                        }
                        val primarySiblings = if (restoredGroups.isNotEmpty()) siblings.filter { sibling ->
                            primaryProjectGroup.passwords.any { it.originalEntryId == sibling.id }
                        }.sortedBy { sibling -> primaryProjectGroup.passwords.indexOfFirst { it.originalEntryId == sibling.id } } else siblings
                        passwords.clear()
                        if (primarySiblings.isNotEmpty()) {
                            val siblingSecretStates = withContext(Dispatchers.Default) {
                                primarySiblings.map { sibling ->
                                    val secretState = viewModel.inspectSecretState(sibling)
                                    sibling to secretState
                                }
                            }
                            val unreadableIds = mutableSetOf<Long>()
                            passwords.addAll(
                                siblingSecretStates.map { (sibling, secretState) ->
                                    if (
                                        secretState is SecretValueState.Unreadable &&
                                        secretState.source is PasswordSource.Bitwarden
                                    ) {
                                        unreadableIds += sibling.id
                                    }
                                    secretState.plainValueOrEmpty()
                                }
                            )
                            originalIds = primarySiblings.map { s: PasswordEntry -> s.id }
                            if (!hasProjectCredentialMetadata) primaryProjectGroup = primaryProjectGroup.copy(
                                username = username, passwords = primarySiblings.mapIndexed { index, sibling ->
                                    ProjectCredentialGroup.Password(value = passwords[index], originalEntryId = sibling.id)
                                })
                            unreadablePasswordIds = unreadableIds
                        } else {
                            passwords.add(entry.password)
                            originalIds = listOf(entry.id)
                            unreadablePasswordIds = if (
                                secretState is SecretValueState.Unreadable &&
                                secretState.source is PasswordSource.Bitwarden
                            ) {
                                setOf(entry.id)
                            } else {
                                emptySet()
                            }
                        }

                        val currentTarget = rawEntry.toStorageTarget()
                        val replicaTargets = if (!entry.replicaGroupId.isNullOrBlank()) {
                            allEntries
                                .filter {
                                    it.replicaGroupId == entry.replicaGroupId &&
                                        !it.isDeleted &&
                                        !it.isArchived
                                }
                                .map(PasswordEntry::toStorageTarget)
                                .distinctBy(StorageTarget::stableKey)
                        } else {
                            listOf(currentTarget)
                        }
                        val selectedTargets = buildList {
                            add(currentTarget)
                            addAll(
                                replicaTargets.filter {
                                    it.stableKey != currentTarget.stableKey
                                }.sortedBy(StorageTarget::stableKey)
                            )
                        }
                        setSelectedStorageTargets(selectedTargets)
                        existingReplicaTargetKeys = selectedTargets
                            .map(StorageTarget::stableKey)
                            .toSet()
                        
                        // 加载自定义字段
                        val existingFields = withContext(Dispatchers.IO) {
                            viewModel.getCustomFieldsByEntryIdSync(originalIds.firstOrNull() ?: actualId)
                        }
                        customFields.clear()
                        
                        // 将现有字段转换为Draft
                        val existingDrafts = existingFields.map { field ->
                            CustomFieldDraft.fromCustomField(field)
                        }.toMutableList()

                        val hasAliasMeta = existingDrafts.any {
                            it.title == MONICA_USERNAME_ALIAS_META_FIELD_TITLE &&
                                it.value == MONICA_USERNAME_ALIAS_META_VALUE
                        }
                        val aliasDraft = existingDrafts.firstOrNull {
                            it.title == MONICA_USERNAME_ALIAS_FIELD_TITLE ||
                                (hasAliasMeta && it.title == usernameLabel)
                        }
                        if (aliasDraft != null) {
                            separatedUsername = aliasDraft.value
                        }

                        // 内部转换字段始终不在普通自定义字段列表中显示
                        existingDrafts.removeAll {
                            it.title == MONICA_USERNAME_ALIAS_FIELD_TITLE ||
                                it.title == MONICA_USERNAME_ALIAS_META_FIELD_TITLE
                        }
                        
                        // 获取预设字段并标记
                        // 检查现有字段是否匹配预设（按标题匹配）
                        val currentPresets = presetCustomFields
                            .filter { it.fieldName.trim().isNotEmpty() }
                            .sortedBy { it.order }
                        val existingTitles = existingDrafts.map { it.title.lowercase() }.toSet()
                        
                        // 为匹配预设的现有字段添加预设标记
                        existingDrafts.replaceAll { draft ->
                            val matchingPreset = currentPresets.find { 
                                it.fieldName.lowercase() == draft.title.lowercase() 
                            }
                            if (matchingPreset != null) {
                                draft.copy(
                                    isPreset = true,
                                    isRequired = matchingPreset.isRequired,
                                    presetId = matchingPreset.id,
                                    placeholder = matchingPreset.placeholder
                                )
                            } else {
                                draft
                            }
                        }
                        
                        // 添加未在现有字段中出现的预设字段
                        currentPresets.forEach { preset ->
                            if (preset.fieldName.lowercase() !in existingTitles) {
                                existingDrafts.add(CustomFieldDraft.fromPreset(preset))
                            }
                        }
                        
                        if (existingDrafts.any { it.title == PasswordUrlRules.FIELD }) {
                            val rules = PasswordUrlRules.read(existingDrafts)
                            urlRulesLoadFailed = rules == null
                            if (rules != null) {
                                websiteUrls.clear(); websiteUrls.addAll(rules.map { it.url })
                                websiteMatchModes.clear(); websiteMatchModes.addAll(rules.map { it.mode.name })
                                syncWebsiteFromUrlRows()
                            }
                        }
                        customFields.addAll(existingDrafts)
                        if (requestedContentSections.isEmpty()) requestedContentSections.addAll(EntryContentFields.order(existingDrafts))
                        if (existingDrafts.isNotEmpty()) {
                            customFieldsExpanded = true
                        }
                        // Apply only the explicitly reviewed credential, after restoring all
                        // project groups and storage metadata. Saving still uses the ordinary editor.
                        if (!initialDraftApplied && !autofillPasswordUpdate.isNullOrBlank() &&
                            !hasOwnershipConflict && !projectCredentialLoadFailed && !templateLoadFailed) {
                            val selectedIndex = originalIds.indexOf(actualId)
                            if (selectedIndex in passwords.indices && actualId !in unreadablePasswordIds) {
                                passwords[selectedIndex] = autofillPasswordUpdate
                            } else {
                                extraCredentialGroups.replaceAll { group ->
                                    group.copy(passwords = group.passwords.map { value ->
                                        if (value.originalEntryId == actualId) value.copy(value = autofillPasswordUpdate) else value
                                    })
                                }
                            }
                            initialDraftApplied = true
                        }
                        Unit
                    } else {
                        passwords.add("")
                        Unit
                    }
                } ?: run {
                     // Fallback if entry not found or new
                     hasOwnershipConflict = false
                     unreadablePasswordIds = emptySet()
                     if (passwords.isEmpty()) passwords.add("")
                }
            }
        } else {
              // A reused editor must never carry an ID from the previous edit
              // session into a new item. saveGroupedPasswordsInternal treats
              // every ID as an update target.
              originalIds = emptyList()
              hasOwnershipConflict = false
              unreadablePasswordIds = emptySet()
              currentReplicaGroupId = null
              existingReplicaTargetKeys = emptySet()
              existingSshKeyData = ""
              if (passwords.isEmpty()) passwords.add("")
              if (credentialUsernames.isEmpty()) credentialUsernames.add("")
             if (!initialDraftApplied && initialDraft != null) {
                 if (title.isBlank()) title = initialDraft.title
                 if (website.isBlank()) replaceWebsiteUrlsFromRaw(initialDraft.website)
                 if (username.isBlank()) username = initialDraft.username
                 if (credentialUsernames.firstOrNull().isNullOrBlank()) {
                     credentialUsernames[0] = initialDraft.username
                 }
                 if (appPackageName.isBlank()) appPackageName = initialDraft.appPackageName
                 if (appName.isBlank()) appName = initialDraft.appName
                 if (initialDraft.password.isNotBlank()) {
                     passwords.clear()
                     passwords.add(initialDraft.password)
                 }
                 initialDraft.template?.takeIf { it.type == defaultLoginType }?.let { templateDraft = it }
                 initialDraftApplied = true
             }
             username = credentialUsernames.firstOrNull().orEmpty()
        }
    }

    val canSave = !urlRulesLoadFailed && !templateLoadFailed && (templateDraft?.valid() != false) && title.isNotEmpty() &&
        (!isBarcodeMode || barcodePayload.isNotBlank()) &&
        !isSaving &&
        !hasOwnershipConflict
    fun findBlockedKeePassOperation(): KeePassOperationBlockUiState? {
        selectedStorageTargets.forEach { target ->
            val keepassTarget = target as? StorageTarget.KeePass ?: return@forEach
            val database = keepassDatabases.firstOrNull { it.id == keepassTarget.databaseId }
            if (database == null) {
                return KeePassOperationBlockUiState(
                    databaseName = context.getString(R.string.create_target_keepass),
                    reason = KeePassOperationBlockReason.MISSING_DATABASE
                )
            }
            val availability = database.writeOperationAvailability()
            if (!availability.canOperate) {
                return KeePassOperationBlockUiState(
                    databaseName = database.name,
                    reason = availability.reason ?: KeePassOperationBlockReason.NEEDS_REFRESH
                )
            }
        }
        return null
    }

    val handleSave: () -> Unit = handleSave@{
        if (canSave && !isSaving) {
            try { templateDraft?.validateKeyMaterial() }
            catch (_: Exception) { Toast.makeText(context, R.string.content_block_save_error, Toast.LENGTH_LONG).show(); return@handleSave }
            if (hasOwnershipConflict) {
                Toast.makeText(
                    context,
                    context.getString(R.string.password_owner_conflict_display),
                    Toast.LENGTH_SHORT
                ).show()
                return@handleSave
            }
            findBlockedKeePassOperation()?.let { blocked ->
                blockedKeePassOperation = blocked
                return@handleSave
            }
            if (usesCredentialCards) {
                ensureCredentialScopedDraftCount()
                if (!isMultiCredentialMode) {
                    credentialUsernames[0] = username
                    selectedAuthenticatorCredentialIndex = 0
                }
                persistCurrentAuthenticatorDraft()
            }
            val invalidCredential = if (usesCredentialCards) credentialUsernames.indices.firstOrNull { index ->
                credentialAuthenticatorSecrets[index].isNotBlank() && credentialAuthenticatorEditedFlags[index] == "1" &&
                    !OtpParametersDraft.decode(credentialAuthenticatorParameters[index]).isValid(
                        runCatching { OtpType.valueOf(credentialAuthenticatorOtpTypes[index]) }.getOrDefault(OtpType.TOTP))
            } else null
            val invalidCurrent = authenticatorSecret.isNotBlank() && authenticatorEditedByUser &&
                !authenticatorParameters.isValid(selectedAuthenticatorOtpType)
            if (!isBarcodeMode && (invalidCredential != null || invalidCurrent)) {
                invalidCredential?.let { index ->
                    selectedCredentialEditorIndex = index
                    loadCredentialAuthenticatorDraft(index)
                    if (isMultiCredentialMode) multiCredentialEditorSectionName = MultiCredentialEditorSection.CREDENTIAL.name
                }
                showAuthenticatorParameters = true
                showInlineOtpSettings = true
                Toast.makeText(context, R.string.otp_parameters_invalid, Toast.LENGTH_SHORT).show()
                return@handleSave
            }
            if (projectCredentialLoadFailed) {
                Toast.makeText(context, R.string.project_credential_unreadable, Toast.LENGTH_LONG).show()
                return@handleSave
            }
            if (copyingWallet) return@handleSave
            retryWalletSave?.let { it(); return@handleSave }
            val deferredEmbeddedSave = takagi.ru.monica.data.model.DeferredEmbeddedContentSave(walletDrafts.pendingIds())
            isSaving = true // 防止重复点击
            val normalizedPasswords = if (templateDraft != null) {
                listOf(templateDraft!!.secret(passwords.firstOrNull().orEmpty()))
            } else if (isBarcodeMode) {
                listOf(barcodePayload)
            } else {
                passwords.map { it.trim() }
            }
            // Capture values before async call
            val currentAuthKey = authenticatorKey
            val currentTitle = title
            val currentUsername = username
            val currentAppPackageName = appPackageName
            val currentAppName = appName
            val currentWebsite = website
            val currentBindWebsite = bindWebsite
            val currentBindTitle = bindTitle
            val storageTargetsForSave = buildStorageTargetsForSave()

            // Create common entry without password
            val commonEntry = PasswordEntry(
                id = 0, // Will be ignored by saveGroupedPasswords logic for new items
                title = title,
                website = if (isBarcodeMode) "" else website,
                username = if (isBarcodeMode) "" else username,
                password = "", // Placeholder
                notes = notes,
                isFavorite = isFavorite,
                appPackageName = appPackageName,
                appName = appName,
                email = emails.filter { it.isNotBlank() }.joinToString("|"),
                phone = phones.filter { it.isNotBlank() }.joinToString("|"),
                addressLine = addressLine,
                city = city,
                state = state,
                zipCode = zipCode,
                country = country,
                creditCardNumber = creditCardNumber,
                creditCardHolder = creditCardHolder,
                creditCardExpiry = creditCardExpiry,
                creditCardCVV = creditCardCVV,
                categoryId = categoryId,
                boundNoteId = boundNoteId,
                keepassDatabaseId = keepassDatabaseId,
                keepassGroupPath = keepassGroupPath,
                mdbxDatabaseId = mdbxDatabaseId,
                mdbxFolderId = mdbxFolderId,
                bitwardenVaultId = bitwardenVaultId,  // ✅ 保存到 Bitwarden Vault
                bitwardenFolderId = bitwardenFolderId,
                authenticatorKey = if (isBarcodeMode) "" else currentAuthKey,  // ✅ 保存验证器密钥
                passkeyBindings = if (isBarcodeMode) "" else passkeyBindings,
                sshKeyData = templateDraft?.ssh() ?: existingSshKeyData,
                wifiMetadata = templateDraft?.wifi() ?: preservedWifiMetadata,
                loginType = if (isBarcodeMode) LOGIN_TYPE_BARCODE else loginType,
                ssoProvider = if (isBarcodeMode) "" else ssoProvider,
                ssoRefEntryId = if (isBarcodeMode) null else ssoRefEntryId,
                customIconType = customIconType,
                customIconValue = normalizedIconFileName(customIconValue),
                customIconUpdatedAt = customIconUpdatedAt
            )

            // 快照自定义字段，并追加“用户名分离”内部转换字段（带标记）
            val urlFields = if (!isBarcodeMode && templateDraft == null) PasswordUrlRules.put(customFields.toList(), currentUrlRules()) else customFields.toList()
            val templateFields = templateDraft?.fields(urlFields) ?: urlFields
            val currentCustomFields = (if (contentMode) EntryContentFields.withOrder(templateFields, requestedContentSections.toList()) else templateFields).toMutableList().apply {
                removeAll {
                    it.title == MONICA_USERNAME_ALIAS_FIELD_TITLE ||
                        it.title == MONICA_USERNAME_ALIAS_META_FIELD_TITLE
                }
                val normalizedSeparatedUsername = separatedUsername.trim().takeIf {
                    !usesCredentialCards || credentialUsernames.size == 1 || isEditing
                }.orEmpty()
                if (normalizedSeparatedUsername.isNotEmpty()) {
                    add(
                        CustomFieldDraft(
                            id = CustomFieldDraft.nextTempId(),
                            title = MONICA_USERNAME_ALIAS_FIELD_TITLE,
                            value = normalizedSeparatedUsername
                        )
                    )
                    add(
                        CustomFieldDraft(
                            id = CustomFieldDraft.nextTempId(),
                            title = MONICA_USERNAME_ALIAS_META_FIELD_TITLE,
                            value = MONICA_USERNAME_ALIAS_META_VALUE
                        )
                    )
                }
            }

            val finishSave = finishSave@{
                    primaryPasswordId: Long?,
                    totpPasswordId: Long?,
                    savedPasswordIds: List<Long>,
                    totpAccountName: String,
                    attachmentDraftOwners: List<Pair<Long, SnapshotStateList<takagi.ru.monica.attachments.ui.AttachmentPendingDraft>>>,
                    failedIconCopies: List<String>,
                    saveCurrentAuthenticator: Boolean ->
                if (primaryPasswordId == null) {
                    failedIconCopies.forEach { PasswordCustomIconStore.deleteIconFile(context, it) }
                    isSaving = false
                    Toast.makeText(context, context.getString(R.string.save_failed), Toast.LENGTH_SHORT).show()
                    return@finishSave
                }
                val firstPasswordId = totpPasswordId ?: primaryPasswordId
                // Save TOTP if authenticatorKey is provided
                if (saveCurrentAuthenticator && currentAuthKey.isNotEmpty() && totpViewModel != null) {
                    val resolvedAuthTotp = TotpDataResolver.fromAuthenticatorKey(
                        rawKey = currentAuthKey,
                        fallbackIssuer = currentTitle,
                        fallbackAccountName = totpAccountName
                    ) ?: TotpData(
                        secret = currentAuthKey,
                        issuer = currentTitle,
                        accountName = totpAccountName
                    )
                    val totpData = resolvedAuthTotp.copy(
                        issuer = resolvedAuthTotp.issuer.ifBlank { currentTitle },
                        accountName = resolvedAuthTotp.accountName.ifBlank { totpAccountName },
                        boundPasswordId = firstPasswordId
                    )
                    totpViewModel.savePasswordBoundTotps(
                        passwordIds = savedPasswordIds.ifEmpty { listOf(firstPasswordId) },
                        title = currentTitle,
                        notes = "",
                        totpData = totpData,
                        preferredTotpId = existingTotpId
                    )
                } else if (
                    saveCurrentAuthenticator &&
                    currentAuthKey.isEmpty() &&
                    originalAuthenticatorKey.isNotEmpty() &&
                    totpViewModel != null
                ) {
                    totpViewModel.unbindTotpFromPassword(firstPasswordId, originalAuthenticatorKey)
                }

                if (currentAppPackageName.isNotEmpty()) {
                    if (currentBindWebsite && currentWebsite.isNotEmpty()) {
                        viewModel.updateAppAssociationByWebsite(currentWebsite, currentAppPackageName, currentAppName)
                    }
                    if (currentBindTitle && currentTitle.isNotEmpty()) {
                        viewModel.updateAppAssociationByTitle(currentTitle, currentAppPackageName, currentAppName)
                    }
                }
                val originalUploaded = if (originalCustomIconType == PASSWORD_ICON_TYPE_UPLOADED) {
                    normalizedIconFileName(originalCustomIconValue)
                } else null
                val currentUploaded = if (customIconType == PASSWORD_ICON_TYPE_UPLOADED) {
                    normalizedIconFileName(customIconValue)
                } else null
                if (!originalUploaded.isNullOrBlank() && originalUploaded != currentUploaded) {
                    PasswordCustomIconStore.deleteIconFile(context, originalUploaded)
                }
                val pendingAttachmentOwners = attachmentDraftOwners.filter { (_, drafts) -> drafts.isNotEmpty() }
                val persistContent: () -> Unit = {
                    isSaving = true
                    coroutineScope.launch {
                        try {
                            for (targetId in savedPasswordIds.ifEmpty { listOf(primaryPasswordId) }) {
                                val target = requireNotNull(viewModel.getPasswordEntryById(targetId))
                                val commit = deferredEmbeddedSave.commit(targetId)
                                val snapshots = commit?.fields.orEmpty().mapNotNull { field ->
                                    if (!EmbeddedWalletContent.isMetadata(field.title)) null else
                                        (EmbeddedWalletContent.read(field.value) as? EmbeddedWalletContent.ReadResult.Available)?.snapshot
                                }
                                walletDrafts.persist(target, snapshots, settings.isPlusActivated)
                                if (commit != null) viewModel.publishEmbeddedContent(targetId, commit)
                            }
                        pendingAttachmentOwners.forEach { (attachmentOwnerId, pendingDrafts) ->
                            val savedEntry = viewModel.getPasswordEntryById(attachmentOwnerId)
                            val draftKeePassContext = savedEntry?.let { entry ->
                                val databaseId = entry.keepassDatabaseId
                                val entryUuid = entry.keepassEntryUuid?.takeIf { it.isNotBlank() }
                                if (databaseId != null && entryUuid != null) {
                                    AttachmentFacade.KeePassContext(databaseId = databaseId, entryUuid = entryUuid)
                                } else null
                            }
                            takagi.ru.monica.attachments.ui.flushPendingDraftsTo(
                                context = context,
                                passwordId = attachmentOwnerId,
                                pendingDrafts = pendingDrafts,
                                isPlusActivated = settings.isPlusActivated,
                                attachmentSource = if (draftKeePassContext != null) {
                                    takagi.ru.monica.attachments.model.AttachmentSource.KEEPASS
                                } else {
                                    takagi.ru.monica.attachments.model.AttachmentSource.LOCAL
                                },
                                keepassContext = draftKeePassContext
                            )
                        }
                            check(pendingAttachmentOwners.all { it.second.isEmpty() })
                            hasSavedSuccessfully = true
                            retryWalletSave = null
                            onSaveCompleted?.invoke(primaryPasswordId)
                            onNavigateBack()
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
                        } catch (_: Exception) {
                            walletCopyError = currentTitle
                        } finally { isSaving = false }
                    }
                }
                retryWalletSave = persistContent
                persistContent()
                return@finishSave

            }

            fun saveCredentialAuthenticator(
                credentialIndex: Int,
                rawKey: String,
                firstPasswordId: Long,
                savedPasswordIds: List<Long>,
                allowExistingUnbind: Boolean
            ) {
                val scopedTotpViewModel = totpViewModel ?: return
                val accountName = credentialUsernames.getOrNull(credentialIndex).orEmpty()
                if (rawKey.isNotBlank()) {
                    val resolvedAuthTotp = TotpDataResolver.fromAuthenticatorKey(
                        rawKey = rawKey,
                        fallbackIssuer = currentTitle,
                        fallbackAccountName = accountName
                    ) ?: TotpData(
                        secret = rawKey,
                        issuer = currentTitle,
                        accountName = accountName
                    )
                    scopedTotpViewModel.savePasswordBoundTotps(
                        passwordIds = savedPasswordIds.ifEmpty { listOf(firstPasswordId) },
                        title = currentTitle,
                        notes = "",
                        totpData = resolvedAuthTotp.copy(
                            issuer = resolvedAuthTotp.issuer.ifBlank { currentTitle },
                            accountName = resolvedAuthTotp.accountName.ifBlank { accountName },
                            boundPasswordId = firstPasswordId
                        ),
                        preferredTotpId = credentialExistingTotpIds
                            .getOrNull(credentialIndex)
                            ?.toLongOrNull()
                    )
                } else if (allowExistingUnbind) {
                    credentialOriginalAuthenticatorKeys
                        .getOrNull(credentialIndex)
                        .orEmpty()
                        .takeIf { it.isNotBlank() }
                        ?.let { originalKey ->
                            scopedTotpViewModel.unbindTotpFromPassword(firstPasswordId, originalKey)
                        }
                }
            }

            // A single account may still contain several legacy password rows.
            // Preserve all of them through the grouped save path.
            val usesIndependentCredentialSave = !usesProjectCredentialSave &&
                usesCredentialCards && (isMultiCredentialMode || passwords.size <= 1)
            if (usesIndependentCredentialSave) {
                coroutineScope.launch {
                    val copiedIconFiles = mutableListOf<String>()
                    val credentialIconValues = try {
                        credentialUsernames.indices.map { index ->
                            if (index == 0 || customIconType != PASSWORD_ICON_TYPE_UPLOADED || customIconValue.isNullOrBlank()) {
                                normalizedIconFileName(customIconValue)
                            } else {
                                PasswordCustomIconStore.duplicateIconFile(context, customIconValue)
                                    .getOrThrow()
                                    .also(copiedIconFiles::add)
                            }
                        }
                    } catch (error: Throwable) {
                        copiedIconFiles.forEach { file -> PasswordCustomIconStore.deleteIconFile(context, file) }
                        if (error is CancellationException) throw error
                        isSaving = false
                        Toast.makeText(context, context.getString(R.string.save_failed), Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    val credentialAuthKeys = credentialUsernames.indices.map(::credentialAuthenticatorKeyAt)
                    val credentialDrafts = credentialUsernames.indices.map { index ->
                        val metadata = credentialMetadataDrafts[index]
                        val credentialNote = if (index == 0) primaryProjectGroup.note else metadata.credentialNote
                        val ownFields = if (isMultiCredentialMode) {
                            EntryContentFields.withOrder(metadata.credentialCustomFields.toList(), requestedContentSections.toList())
                        } else emptyList()
                        val credentialFields = if (credentialNote.isNotBlank()) {
                            val noteMetadata = ProjectCredentialGroup.rows(listOf(ProjectCredentialGroup.Group(note = credentialNote)))
                                .single().metadata
                            ProjectCredentialGroup.put(ownFields, noteMetadata)
                        } else ownFields
                        PasswordCredentialDraft(
                            username = credentialUsernames[index],
                            password = passwords.getOrNull(index).orEmpty(),
                            authenticatorKey = credentialAuthKeys[index],
                            customIconValue = credentialIconValues[index],
                            customIconUpdatedAt = customIconUpdatedAt + index,
                            notes = if (isMultiCredentialMode) metadata.notes else notes,
                            boundNoteId = if (isMultiCredentialMode) metadata.boundNoteId else boundNoteId,
                            email = if (isMultiCredentialMode) {
                                metadata.emails.filter { it.isNotBlank() }.joinToString("|")
                            } else {
                                emails.filter { it.isNotBlank() }.joinToString("|")
                            },
                            phone = if (isMultiCredentialMode) {
                                metadata.phones.filter { it.isNotBlank() }.joinToString("|")
                            } else {
                                phones.filter { it.isNotBlank() }.joinToString("|")
                            },
                            addressLine = if (isMultiCredentialMode) metadata.addressLine else addressLine,
                            city = if (isMultiCredentialMode) metadata.city else city,
                            state = if (isMultiCredentialMode) metadata.state else state,
                            zipCode = if (isMultiCredentialMode) metadata.zipCode else zipCode,
                            country = if (isMultiCredentialMode) metadata.country else country,
                            creditCardNumber = if (isMultiCredentialMode) {
                                metadata.creditCardNumber
                            } else {
                                creditCardNumber
                            },
                            creditCardHolder = if (isMultiCredentialMode) {
                                metadata.creditCardHolder
                            } else {
                                creditCardHolder
                            },
                            creditCardExpiry = if (isMultiCredentialMode) {
                                metadata.creditCardExpiry
                            } else {
                                creditCardExpiry
                            },
                            creditCardCVV = if (isMultiCredentialMode) metadata.creditCardCVV else creditCardCVV,
                            passkeyBindings = if (!isMultiCredentialMode || index == 0) passkeyBindings else "",
                            sshKeyData = if (!isMultiCredentialMode || index == 0) existingSshKeyData else "",
                            customFields = PasswordUrlRules.put(credentialFields, currentUrlRules())
                        )
                    }
                    val editedCredentialSavePlan = if (isEditing) {
                        buildEditedPasswordCredentialSavePlan(
                            originalIds = originalIds,
                            credentials = credentialDrafts
                        )
                    } else {
                        null
                    }
                    if (isEditing) {
                        if (editedCredentialSavePlan == null) {
                            copiedIconFiles.forEach { file -> PasswordCustomIconStore.deleteIconFile(context, file) }
                            isSaving = false
                            Toast.makeText(context, context.getString(R.string.save_failed), Toast.LENGTH_SHORT).show()
                            return@launch
                        }

                        val existingCredential = editedCredentialSavePlan.existingCredential
                        val newCredentialCustomFields = currentCustomFields.filterNot { field ->
                            field.title == MONICA_USERNAME_ALIAS_FIELD_TITLE ||
                                field.title == MONICA_USERNAME_ALIAS_META_FIELD_TITLE
                        }
                        val existingCredentialCustomFields = mergePasswordCredentialCustomFields(
                            commonFields = currentCustomFields,
                            credentialFields = existingCredential.customFields
                        )
                        viewModel.savePasswordsAcrossTargets(
                            embeddedContentSave = deferredEmbeddedSave,
                            originalIds = originalIds,
                            commonEntry = commonEntry.copy(
                                username = existingCredential.username,
                                password = "",
                                authenticatorKey = existingCredential.authenticatorKey,
                                customIconValue = existingCredential.customIconValue,
                                customIconUpdatedAt = existingCredential.customIconUpdatedAt
                                    ?: commonEntry.customIconUpdatedAt,
                                notes = existingCredential.notes,
                                boundNoteId = existingCredential.boundNoteId,
                                email = existingCredential.email,
                                phone = existingCredential.phone,
                                addressLine = existingCredential.addressLine,
                                city = existingCredential.city,
                                state = existingCredential.state,
                                zipCode = existingCredential.zipCode,
                                country = existingCredential.country,
                                creditCardNumber = existingCredential.creditCardNumber,
                                creditCardHolder = existingCredential.creditCardHolder,
                                creditCardExpiry = existingCredential.creditCardExpiry,
                                creditCardCVV = existingCredential.creditCardCVV,
                                passkeyBindings = existingCredential.passkeyBindings,
                                sshKeyData = existingCredential.sshKeyData,
                                replicaGroupId = currentReplicaGroupId
                            ),
                            passwords = listOf(existingCredential.password),
                            targets = storageTargetsForSave,
                            customFields = existingCredentialCustomFields,
                            onCompleteWithIds = existingSave@{ firstPasswordId, savedPasswordIds ->
                                if (firstPasswordId == null) {
                                    copiedIconFiles.forEach { file ->
                                        PasswordCustomIconStore.deleteIconFile(context, file)
                                    }
                                    isSaving = false
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.save_failed),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    return@existingSave
                                }

                                saveCredentialAuthenticator(
                                    credentialIndex = 0,
                                    rawKey = existingCredential.authenticatorKey,
                                    firstPasswordId = firstPasswordId,
                                    savedPasswordIds = savedPasswordIds,
                                    allowExistingUnbind = true
                                )

                                viewModel.saveCredentialsAcrossTargets(
                            embeddedContentSave = deferredEmbeddedSave,
                                    commonEntry = commonEntry.copy(
                                        username = "",
                                        password = "",
                                        authenticatorKey = "",
                                        replicaGroupId = null
                                    ),
                                    credentials = editedCredentialSavePlan.newCredentials,
                                    targets = storageTargetsForSave,
                                    customFields = newCredentialCustomFields,
                                    onComplete = newCredentialsSave@{ savedCredentials ->
                                        if (savedCredentials.size != editedCredentialSavePlan.newCredentials.size) {
                                            copiedIconFiles.forEach { file ->
                                                PasswordCustomIconStore.deleteIconFile(context, file)
                                            }
                                            isSaving = false
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.save_failed),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                            return@newCredentialsSave
                                        }

                                        savedCredentials.forEach { savedCredential ->
                                            val newCredentialIndex = savedCredential.credentialIndex
                                            val sourceCredentialIndex = newCredentialIndex + 1
                                            saveCredentialAuthenticator(
                                                credentialIndex = sourceCredentialIndex,
                                                rawKey = credentialAuthKeys
                                                    .getOrNull(sourceCredentialIndex)
                                                    .orEmpty(),
                                                firstPasswordId = savedCredential.firstPasswordId,
                                                savedPasswordIds = savedCredential.savedPasswordIds,
                                                allowExistingUnbind = false
                                            )
                                        }
                                        val attachmentOwners = savedCredentials.mapNotNull { savedCredential ->
                                            val sourceCredentialIndex = savedCredential.credentialIndex + 1
                                            val drafts = credentialAttachmentDrafts.getOrNull(sourceCredentialIndex)
                                                ?: return@mapNotNull null
                                            savedCredential.firstPasswordId to drafts
                                        }
                                        finishSave(
                                            firstPasswordId,
                                            firstPasswordId,
                                            (savedPasswordIds + savedCredentials.flatMap { it.savedPasswordIds }).distinct(),
                                            existingCredential.username,
                                            attachmentOwners,
                                            copiedIconFiles,
                                            false
                                        )
                                    }
                                )
                            }
                        )
                        return@launch
                    }
                    viewModel.saveCredentialsAcrossTargets(
                            embeddedContentSave = deferredEmbeddedSave,
                        commonEntry = commonEntry.copy(
                            username = "",
                            password = "",
                            authenticatorKey = "",
                            replicaGroupId = null
                        ),
                        credentials = credentialDrafts,
                        targets = storageTargetsForSave,
                        customFields = currentCustomFields,
                        onComplete = { savedCredentials ->
                            val firstCredential = savedCredentials.firstOrNull()
                            if (totpViewModel != null) {
                                savedCredentials.forEach { savedCredential ->
                                    val index = savedCredential.credentialIndex
                                    val rawKey = credentialAuthKeys.getOrNull(index).orEmpty()
                                    if (rawKey.isBlank()) return@forEach
                                    val accountName = credentialUsernames.getOrNull(index).orEmpty()
                                    val resolvedAuthTotp = TotpDataResolver.fromAuthenticatorKey(
                                        rawKey = rawKey,
                                        fallbackIssuer = currentTitle,
                                        fallbackAccountName = accountName
                                    ) ?: TotpData(
                                        secret = rawKey,
                                        issuer = currentTitle,
                                        accountName = accountName
                                    )
                                    totpViewModel.savePasswordBoundTotps(
                                        passwordIds = savedCredential.savedPasswordIds.ifEmpty {
                                            listOf(savedCredential.firstPasswordId)
                                        },
                                        title = currentTitle,
                                        notes = "",
                                        totpData = resolvedAuthTotp.copy(
                                            issuer = resolvedAuthTotp.issuer.ifBlank { currentTitle },
                                            accountName = resolvedAuthTotp.accountName.ifBlank { accountName },
                                            boundPasswordId = savedCredential.firstPasswordId
                                        ),
                                        preferredTotpId = credentialExistingTotpIds
                                            .getOrNull(index)
                                            ?.toLongOrNull()
                                    )
                                }
                            }
                            val attachmentOwners = savedCredentials.mapNotNull { savedCredential ->
                                val drafts = credentialAttachmentDrafts.getOrNull(savedCredential.credentialIndex)
                                    ?: return@mapNotNull null
                                savedCredential.firstPasswordId to drafts
                            }
                            finishSave(
                                firstCredential?.firstPasswordId,
                                firstCredential?.firstPasswordId,
                                savedCredentials.flatMap { it.savedPasswordIds }.distinct(),
                                credentialUsernames.firstOrNull().orEmpty(),
                                attachmentOwners,
                                copiedIconFiles,
                                false
                            )
                        }
                    )
                }
            } else {
                viewModel.savePasswordsAcrossTargets(
                            embeddedContentSave = deferredEmbeddedSave,
                    originalIds = (if (hasProjectCredentialMetadata) allProjectOriginalIds else originalIds).takeIf { isEditing }.orEmpty(),
                    projectCredentials = if (usesProjectCredentialSave) {
                        listOf(primaryProjectGroup.copy(username = currentUsername, otp = currentAuthKey,
                            passwords = passwords.mapIndexed { index, value ->
                                (primaryProjectGroup.passwords.getOrNull(index) ?: ProjectCredentialGroup.Password()).copy(value = value)
                            })) + extraCredentialGroups.sortedBy { visibleContentTokens().indexOf(ProjectCredentialGroup.token(it.id)) }
                    } else null,
                    commonEntry = commonEntry.copy(
                        replicaGroupId = currentReplicaGroupId.takeIf { isEditing }
                    ),
                    passwords = normalizedPasswords,
                    targets = storageTargetsForSave,
                    customFields = currentCustomFields,
                    onCompleteWithIds = { firstPasswordId, savedPasswordIds ->
                        finishSave(
                            firstPasswordId,
                            firstPasswordId,
                            savedPasswordIds,
                            currentUsername,
                            if (isEditing) {
                                emptyList()
                            } else {
                                listOfNotNull(
                                    firstPasswordId?.let { ownerId ->
                                        ownerId to credentialAttachmentDrafts.first()
                                    }
                                )
                            },
                            emptyList(),
                            true
                        )
                    }
                )
            }
        }
    }

    blockedKeePassOperation?.let { blocked ->
        val reason = keepassBlockReasonLabel(blocked.reason)
        AlertDialog(
            onDismissRequest = { blockedKeePassOperation = null },
            title = { Text(stringResource(R.string.keepass_operation_unavailable_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.keepass_operation_unavailable_message,
                        blocked.databaseName,
                        reason
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { blockedKeePassOperation = null }) {
                    Text(stringResource(R.string.keepass_operation_refresh_hint))
                }
            }
        )
    }

    LaunchedEffect(isEditing, creationTargets) {
        if (!isEditing) {
            existingReplicaTargetKeys = emptySet()
            currentReplicaGroupId = null
            setSelectedStorageTargets(creationTargets)
        }
    }

    val topBarTitle = stringResource(
        when {
            templateDraft != null && isEditing -> R.string.edit
            templateDraft != null -> R.string.add
            isBarcodeMode && isEditing -> R.string.edit_barcode_title
            isBarcodeMode -> R.string.add_barcode_title
            isEditing -> R.string.edit_password_title
            else -> R.string.add_password_title
        }
    )

    if (isMultiCredentialMode && showCredentialPicker) {
        PasswordCredentialPickerSheet(
            usernames = credentialUsernames,
            selectedIndex = selectedCredentialEditorIndex,
            commonSelected = multiCredentialEditorSection == MultiCredentialEditorSection.COMMON,
            canAdd = canAddIndependentCredential,
            canRemoveSelected = !isEditing || selectedCredentialEditorIndex > 0,
            onSelect = ::showCredentialEditor,
            onSelectCommon = ::showCommonCredentialEditor,
            onAdd = ::addAndSelectCredential,
            onRemoveSelected = {
                showCredentialPicker = false
                if (!isEditing || selectedCredentialEditorIndex > 0) {
                    removeCredentialFieldAt(selectedCredentialEditorIndex)
                }
            },
            onDismiss = { showCredentialPicker = false },
        )
    }

    val templateNavigation = takagi.ru.monica.ui.components.LocalTemplateNavigation.current
    val toolbarDensity = androidx.compose.ui.platform.LocalDensity.current
    val minimumTitleWidth = androidx.compose.ui.text.rememberTextMeasurer().measure(
        topBarTitle, style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp), maxLines = 1).size.width
    val typeControlMaxWidth = (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp -
        120.dp - with(toolbarDensity) { minimumTitleWidth.toDp() }).coerceIn(64.dp, 220.dp)
    val entryTypeControl: @Composable () -> Unit = {
                        if (onSwitchToWifi != null) {
                            EntryTypeChip(
                                modifier = Modifier.widthIn(max = typeControlMaxWidth),
                                showApiToken = !isEditing && onSwitchToApiToken != null,
                                showGpg = !isEditing,
                                showApiKey = !isEditing,
                                current = if (templateDraft != null) EntryTypeChipOption.valueOf(templateDraft!!.type) else if (isBarcodeMode) {
                                    EntryTypeChipOption.BARCODE
                                } else {
                                    EntryTypeChipOption.PASSWORD
                                },
                                onSelect = { option ->
                                    if (option == (templateDraft?.let { EntryTypeChipOption.valueOf(it.type) } ?: if (isBarcodeMode) EntryTypeChipOption.BARCODE else EntryTypeChipOption.PASSWORD)) Unit
                                    else if (templateNavigation != null && option != EntryTypeChipOption.PASSWORD && option != EntryTypeChipOption.BARCODE) templateNavigation(option, selectedStorageTargets.toList())
                                    else when (option) {
                                        EntryTypeChipOption.API_KEY -> onSwitchToApiKey(
                                            selectedStorageTargets.firstOrNull() ?: StorageTarget.MonicaLocal(null))
                                        EntryTypeChipOption.GPG_KEY -> onSwitchToGpg()
                                        EntryTypeChipOption.API_TOKEN -> onSwitchToApiToken?.invoke(
                                            selectedStorageTargets.filterIsInstance<StorageTarget.Mdbx>().firstOrNull())
                                        EntryTypeChipOption.WIFI ->
                                            onSwitchToWifi(if (isEditing) passwordId else null)
                                        EntryTypeChipOption.SSH_KEY ->
                                            onSwitchToSshKey?.invoke(if (isEditing) passwordId else null)
                                        EntryTypeChipOption.BARCODE -> {
                                            loginType = LOGIN_TYPE_BARCODE; templateDraft = null
                                        }
                                        EntryTypeChipOption.PASSWORD -> {
                                            loginType = "PASSWORD"; templateDraft = null
                                        }
                                    }
                                },
                                enabled = !isEditing
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
    }
    val editorActions: @Composable RowScope.() -> Unit = {
        entryTypeControl()
                        IconButton(onClick = { isFavorite = !isFavorite }, modifier = Modifier.testTag("password_editor_favorite").semantics { selected = isFavorite }) {
                            Icon(
                                if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = stringResource(R.string.favorite),
                                tint = if (isFavorite) MaterialTheme.colorScheme.primary else LocalContentColor.current
                            )
                        }
    }
    val editorNavigation: @Composable () -> Unit = {
        IconButton(onClick = onNavigateBack) {
            Icon(MonicaIcons.Navigation.back, contentDescription = stringResource(R.string.back))
        }
    }
    val primaryCredentialNoteField: @Composable () -> Unit = {
        val noteIndex = if (isMultiCredentialMode) selectedCredentialEditorIndex else 0
        CredentialNoteField(
            value = if (noteIndex == 0) primaryProjectGroup.note else activeCredentialMetadata.credentialNote,
            onValueChange = {
                if (noteIndex == 0) primaryProjectGroup = primaryProjectGroup.copy(note = it)
                else activeCredentialMetadata.credentialNote = it
            }, modifier = Modifier.testTag("password_editor_credential_note"))
    }
    val primaryAuthenticatorField: @Composable () -> Unit = {
                    Column {
                        Column {
                            CompositionLocalProvider(takagi.ru.monica.ui.components.LocalFilledEntryForm provides contentMode) {
                                OutlinedTextField(
                                    value = authenticatorSecret,
                                    onValueChange = ::applyAuthenticatorInput,
                                    label = { Text(stringResource(R.string.authenticator_key_optional)) },
                                    placeholder = { Text(stringResource(R.string.authenticator_key_hint)) },
                                    leadingIcon = { Icon(Icons.Default.VpnKey, null) },
                                    trailingIcon = {
                                        Row {
                                            if (onScanAuthenticatorQrCode != null) IconButton(onClick = onScanAuthenticatorQrCode) {
                                                Icon(Icons.Default.QrCodeScanner, stringResource(R.string.scan_qr_code))
                                            }
                                            takagi.ru.monica.ui.components.EditorAdvancedMenuButton(
                                                onOpen = { showInlineOtpSettings = true }, modifier = Modifier.testTag("password_otp_settings"))
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().testTag("password_otp_secret"),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                    supportingText = if (selectedAuthenticatorOtpType == OtpType.STEAM) {
                                        { Text(stringResource(R.string.steam_uses_5_chars)) }
                                    } else null,
                                    shape = if (contentMode && loginType.equals("PASSWORD", ignoreCase = true)) RoundedCornerShape(4.dp) else RoundedCornerShape(if (contentMode) 24.dp else 12.dp)
                                )
                            }

                            if (contentMode && loginType.equals("PASSWORD", ignoreCase = true)) {
                                Spacer(Modifier.height(2.dp))
                                primaryCredentialNoteField()
                            }

                            if (showInlineOtpSettings) androidx.compose.ui.window.Dialog(
                                onDismissRequest = { showInlineOtpSettings = false },
                            ) {
                              Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp).testTag("content_detail_scroll").imePadding(),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)) {
                                    item {
                                        Column {
                                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                Text(stringResource(R.string.authenticator_key_optional), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                                IconButton(onClick = { showInlineOtpSettings = false }, modifier = Modifier.testTag("content_detail_back")) {
                                                    Icon(Icons.Default.Check, stringResource(R.string.save))
                                                }
                                            }
                            OtpTypeSelector(
                                type = selectedAuthenticatorOtpType,
                                onChange = { type ->
                                    selectedAuthenticatorOtpTypeName = type.name
                                    applyAuthenticatorParameters(authenticatorParameters.selectType(type))
                                },
                                modifier = Modifier.padding(top = 10.dp),
                            )
                            Spacer(Modifier.height(12.dp))
                            OtpParameterFields(
                                type = selectedAuthenticatorOtpType, draft = authenticatorParameters,
                                onChange = ::applyAuthenticatorParameters, includeAdvanced = false,
                            )
                            TextButton(modifier = Modifier.testTag("password_otp_advanced"),
                                onClick = { showAuthenticatorParameters = !showAuthenticatorParameters }) {
                                Text(stringResource(R.string.advanced_options))
                                Icon(if (showAuthenticatorParameters) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                            }
                            MonicaExpandableContent(expanded = showAuthenticatorParameters) {
                                OtpParameterFields(
                                    type = selectedAuthenticatorOtpType, draft = authenticatorParameters,
                                    onChange = ::applyAuthenticatorParameters, includeRequired = false,
                                )
                            }

                            if (totpViewModel != null) {
                                OutlinedButton(
                                    onClick = { showAuthenticatorPicker = true },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 10.dp),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.Security, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        if (authenticatorSecret.isNotBlank()) {
                                            stringResource(R.string.bound_authenticator_change)
                                        } else {
                                            stringResource(R.string.bind_authenticator)
                                        }
                                    )
                                }

                                if (selectedExistingTotpTitle.isNotBlank()) {
                                    Text(
                                        text = stringResource(
                                            R.string.selected_authenticator_format,
                                            selectedExistingTotpTitle
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 6.dp)
                                    )
                                }
                            }

                                        }
                                    }
                                }
                            }
                            }

                            MonicaExpandableContent(
                                expanded = authenticatorPreviewVisible
                            ) {
                                authenticatorPreviewTotpData?.let { previewData ->
                                    InlineTotpPreviewCard(
                                        totpData = previewData,
                                        currentSeconds = authenticatorPreviewCurrentSeconds,
                                        progressTimeMillis = authenticatorPreviewProgressTimeMillis,
                                        timeOffset = settings.totpTimeOffset,
                                        smoothProgress = settings.validatorSmoothProgress,
                                        modifier = Modifier.padding(top = 10.dp).testTag("password_otp_inline_preview"),
                                        showHeader = false,
                                        showProgress = true
                                    )
                                }
                            }
                        }
                    }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    androidx.compose.foundation.text.BasicText(
                        topBarTitle,
                        style = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        maxLines = 1,
                        autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(
                            minFontSize = 16.sp, maxFontSize = MaterialTheme.typography.titleLarge.fontSize),
                        modifier = Modifier.testTag("password_editor_heading"),
                    )
                },
                navigationIcon = editorNavigation, actions = editorActions,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent, titleContentColor = MaterialTheme.colorScheme.onSurface),
            )
        },
        floatingActionButton = {
            if (isMultiCredentialMode) {
                PasswordCredentialEditorBar(
                    commonSelected = multiCredentialEditorSection == MultiCredentialEditorSection.COMMON,
                    selectedIndex = selectedCredentialEditorIndex,
                    credentialCount = credentialUsernames.size,
                    canAdd = canAddIndependentCredential,
                    canSave = canSave,
                    isSaving = isSaving,
                    onOpenPicker = { showCredentialPicker = true },
                    onAdd = ::addAndSelectCredential,
                    onSave = handleSave,
                )
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (canAddIndependentCredential) {
                        SmallFloatingActionButton(
                            onClick = ::addAndSelectCredential,
                            modifier = Modifier.size(48.dp),
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = stringResource(R.string.add_credential)
                            )
                        }
                    }

                    if (contentMode) {
                        ExtendedFloatingActionButton(
                            onClick = { if (canSave) handleSave() },
                            modifier = Modifier.testTag("password_editor_save"),
                            text = { Text(stringResource(R.string.save)) },
                            icon = {
                                if (isSaving) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Default.Save, contentDescription = stringResource(R.string.save))
                            },
                            containerColor = if (canSave) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        )
                    } else {
                    FloatingActionButton(
                        onClick = handleSave,
                        containerColor = if (canSave) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        contentColor = if (canSave) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = stringResource(R.string.save)
                            )
                        }
                    }
                    }
                }
            }
        }
    ) { paddingValues ->
        val listContentPadding = PaddingValues(bottom = 120.dp)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()
        ) {
            CompositionLocalProvider(takagi.ru.monica.ui.components.LocalEntryContentActions provides contentActions) {
            LazyColumn(
                state = editorListState,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(if (contentMode) "password_content_editor" else "password_classic_editor")
                    .padding(horizontal = if (contentMode) 12.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (contentMode) 2.dp else 16.dp),
            contentPadding = listContentPadding
        ) {
            // Vault/Storage Selector - 保管库选择器（类似Bitwarden）
            if (showCommonEditorContent) {
                item {
                    MultiStorageTargetSelectorCard(
                        selectedTargets = selectedStorageTargets,
                        existingTargetKeys = existingReplicaTargetKeys,
                        categories = categories,
                        keepassDatabases = keepassDatabases,
                        mdbxDatabases = mdbxDatabases,
                        bitwardenVaults = bitwardenVaults,
                        bitwardenFolderDao = database.bitwardenFolderDao(),
                        getMdbxFolders = viewModel::getMdbxFolders,
                        isEditing = isEditing,
                        onAddTargetClick = { showStorageTargetSheet = true },
                        onRemoveTarget = ::removeSelectedStorageTarget,
                        compact = false
                    )
                }
            }

            if (showCommonEditorContent && hasOwnershipConflict) {
                item {
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = stringResource(R.string.password_owner_conflict_display),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
            
            // Credentials Card
            item {
                InfoCard(
                    flat = contentMode,
                    template = isBarcodeMode,
                    title = stringResource(
                        if (isBarcodeMode) R.string.entry_type_barcode else R.string.section_credentials
                    )
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(if (contentMode) 2.dp else 12.dp)) {
                        if (showCommonEditorContent) {
                        // Title
                        if (settings.iconCardsEnabled) {
                            Row(Modifier.fillMaxWidth().padding(bottom = if (contentMode) 8.dp else 0.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalIconButton(
                                    onClick = { showCustomIconDialog = true },
                                    modifier = Modifier.size(56.dp).testTag("entry_icon_picker")
                                ) {
                                    when {
                                        selectedEmojiIcon != null -> {
                                            EmojiIconText(
                                                emoji = selectedEmojiIcon,
                                                size = 26.dp,
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        }
                                        selectedSimpleIconBitmap != null -> {
                                            Image(
                                                bitmap = selectedSimpleIconBitmap,
                                                contentDescription = stringResource(R.string.custom_icon_button),
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                        selectedUploadedIconBitmap != null -> {
                                            Image(
                                                bitmap = selectedUploadedIconBitmap,
                                                contentDescription = stringResource(R.string.custom_icon_button),
                                                modifier = Modifier
                                                    .size(24.dp)
                                                    .clip(CircleShape)
                                            )
                                        }
                                        keePassIcon != null -> {
                                            takagi.ru.monica.ui.icons.KeePassCustomIconImage(keePassIcon, Modifier.size(24.dp))
                                        }
                                        autoMatchedSimpleIcon.bitmap != null -> {
                                            Image(
                                                bitmap = autoMatchedSimpleIcon.bitmap,
                                                contentDescription = stringResource(R.string.custom_icon_button),
                                                modifier = Modifier
                                                    .size(24.dp)
                                                    .clip(CircleShape)
                                            )
                                        }
                                        fallbackWebsiteFavicon != null -> {
                                            Image(
                                                bitmap = fallbackWebsiteFavicon,
                                                contentDescription = stringResource(R.string.custom_icon_button),
                                                modifier = Modifier
                                                    .size(24.dp)
                                                    .clip(CircleShape)
                                            )
                                        }
                                        else -> {
                                            Icon(
                                                imageVector = Icons.Default.Image,
                                                contentDescription = stringResource(R.string.custom_icon_button)
                                            )
                                        }
                                    }
                                }
                                OutlinedTextField(
                                    value = templateDraft?.takeIf { it.type == "WIFI" }?.value("ssid") ?: title,
                                    onValueChange = { value ->
                                        val wifi = templateDraft?.takeIf { it.type == "WIFI" }
                                        if (wifi == null) title = value else {
                                            if (title.isBlank() || title == wifi.value("ssid")) title = value
                                            templateDraft = wifi.change("ssid", value)
                                        }
                                    },
                                    label = { Text(stringResource(if (templateDraft?.type == "WIFI") R.string.wifi_ssid_required else R.string.title_required)) },
                                    modifier = Modifier.weight(1f).testTag("password_editor_title"),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                    shape = RoundedCornerShape(if (contentMode) 24.dp else 12.dp)
                                )
                            }
                        } else {
                            OutlinedTextField(
                                value = templateDraft?.takeIf { it.type == "WIFI" }?.value("ssid") ?: title,
                                onValueChange = { value ->
                                        val wifi = templateDraft?.takeIf { it.type == "WIFI" }
                                        if (wifi == null) title = value else {
                                            if (title.isBlank() || title == wifi.value("ssid")) title = value
                                            templateDraft = wifi.change("ssid", value)
                                        }
                                    },
                                label = { Text(stringResource(if (templateDraft?.type == "WIFI") R.string.wifi_ssid_required else R.string.title_required)) },
                                leadingIcon = { Icon(Icons.Default.Label, null) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                shape = if (contentMode) RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 4.dp, bottomEnd = 4.dp) else RoundedCornerShape(12.dp)
                            )
                        }

                        templateDraft?.let { draft ->
                            if (draft.type == "API_KEY" || draft.type == "GPG_KEY") {
                                SuggestedOutlinedTextField(website,
                                    { replaceWebsiteUrlsFromRaw(it) }, saveTextState = false,
                                    suggestionField = CommonSuggestionField.WEBSITE,
                                    label = { Text(stringResource(R.string.website_url)) },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                                    shape = RoundedCornerShape(if (contentMode) 24.dp else 12.dp),
                                    modifier = Modifier.fillMaxWidth().testTag("template_website"))
                                Spacer(Modifier.height(10.dp))
                            }
                            takagi.ru.monica.ui.components.TemplateCredentialFields(draft,
                                onChange = { updated ->
                                    if (draft.type == "WIFI" && (title.isBlank() || title == draft.value("ssid"))) title = updated.value("ssid")
                                    templateDraft = updated
                                }, onScanWifi = onScanAuthenticatorQrCode, enabled = !isSaving, ssidInIdentity = true)
                        }
                        if (templateLoadFailed) Text(stringResource(R.string.content_block_preserved), color = MaterialTheme.colorScheme.error)
                        if (isBarcodeMode) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                FilledTonalButton(
                                    onClick = { onScanAuthenticatorQrCode?.invoke() },
                                    enabled = onScanAuthenticatorQrCode != null,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(64.dp),
                                    shape = RoundedCornerShape(18.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.QrCodeScanner,
                                        contentDescription = null
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(stringResource(R.string.scan_qr_code))
                                }

                                OutlinedTextField(
                                    value = barcodePayload,
                                    onValueChange = { barcodePayload = it },
                                    label = { Text(stringResource(R.string.barcode_manual_input_label)) },
                                    placeholder = { Text(stringResource(R.string.barcode_manual_input_hint)) },
                                    trailingIcon = {
                                        if (barcodePayload.isNotBlank()) {
                                            IconButton(onClick = { barcodePayload = "" }) {
                                                Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.clear))
                                            }
                                        }
                                    },
                                    supportingText = {
                                        Text(stringResource(R.string.barcode_manual_input_support))
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 120.dp),
                                    minLines = 3,
                                    maxLines = 6,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Text,
                                        imeAction = ImeAction.Default
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                )

                                if (barcodePayload.isNotBlank()) {
                                    ElevatedCard(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.elevatedCardColors(
                                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                                        )
                                    ) {
                                        Text(
                                            text = barcodePayload,
                                            modifier = Modifier.padding(14.dp),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.78f),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                PasswordAppBindingButton(
                                    hasBindings = linkedAppBindings.isNotEmpty(),
                                    onClick = { showAppSelectorFromWebsite = true },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                PasswordAppBindingChips(
                                    linkedAppBindings = linkedAppBindings,
                                    onOpenSelector = { showAppSelectorFromWebsite = true },
                                    onRemoveBinding = { packageName ->
                                        val updated = removeLinkedAppBinding(
                                            appPackageName,
                                            appName,
                                            packageName
                                        )
                                        appPackageName = updated.first
                                        appName = updated.second
                                    }
                                )
                            }
                        } else if (templateDraft == null) {
                            // Website URLs + App Binding (inline)
                            Column(verticalArrangement = Arrangement.spacedBy(if (contentMode) 2.dp else 10.dp)) {
                            if (urlRulesLoadFailed) Text(stringResource(R.string.url_match_unreadable), color = MaterialTheme.colorScheme.error)
                            websiteUrls.forEachIndexed { index, url ->
                                key("website_url_$index") {
                                    var urlMenuExpanded by remember { mutableStateOf(false) }
                                    var showUrlMatchDialog by remember { mutableStateOf(false) }
                                    val matchMode = PasswordUrlRules.Mode.valueOf(websiteMatchModes.getOrElse(index) { "DEFAULT" })
                                    val urlShape = if (contentMode) RoundedCornerShape(
                                        topStart = if (settings.iconCardsEnabled && index == 0) 24.dp else 4.dp,
                                        topEnd = if (settings.iconCardsEnabled && index == 0) 24.dp else 4.dp,
                                        bottomStart = if (index == websiteUrls.lastIndex) 24.dp else 4.dp,
                                        bottomEnd = if (index == websiteUrls.lastIndex) 24.dp else 4.dp
                                    ) else RoundedCornerShape(12.dp)
                                    val filledUrl = takagi.ru.monica.ui.components.LocalFilledEntryForm.current
                                    if (showUrlMatchDialog) UrlMatchModeDialog(url, matchMode,
                                        onDismiss = { showUrlMatchDialog = false }, onConfirm = { mode ->
                                            websiteMatchModes[index] = mode.name
                                            showUrlMatchDialog = false
                                        })
                                    SuggestedOutlinedTextField(
                                        suggestionField = CommonSuggestionField.WEBSITE,
                                        // Material supporting text sits outside the input's painted container.
                                        // Extend the same grouped surface around the URL and its match policy.
                                        containerModifier = if (filledUrl) Modifier.clip(urlShape)
                                            .background(MaterialTheme.colorScheme.surfaceContainer) else Modifier,
                                        value = url,
                                        onValueChange = { value ->
                                            websiteUrls[index] = value
                                            syncWebsiteFromUrlRows()
                                        },
                                        label = {
                                            Text(
                                                if (websiteUrls.size == 1) {
                                                    stringResource(R.string.website_url)
                                                } else {
                                                    "${stringResource(R.string.website_url)} ${index + 1}"
                                                }
                                            )
                                        },
                                        leadingIcon = { Icon(Icons.Default.Language, null) },
                                        supportingText = if (matchMode != PasswordUrlRules.Mode.DEFAULT) ({
                                            Text(urlMatchModeLabel(matchMode),
                                                modifier = Modifier.padding(start = if (filledUrl) 36.dp else 0.dp,
                                                    bottom = if (filledUrl) 8.dp else 0.dp))
                                        }) else null,
                                        trailingIcon = {
                                            Box {
                                                IconButton(onClick = { urlMenuExpanded = true }, modifier = Modifier.testTag("url_actions_$index")) {
                                                    Icon(Icons.Default.MoreVert, stringResource(R.string.legacy_ui_url_menu))
                                                }
                                                EditorActionMenu(urlMenuExpanded, { urlMenuExpanded = false }, buildList {
                                                    add(EditorMenuAction(stringResource(R.string.url_match_title), Icons.Default.Rule,
                                                        onClick = { showUrlMatchDialog = true }, supporting = urlMatchModeLabel(matchMode), tag = "url_match_action_$index"))
                                                    if (index > 0) add(EditorMenuAction(stringResource(R.string.move_up), Icons.Default.KeyboardArrowUp, onClick = {
                                                        java.util.Collections.swap(websiteUrls, index, index - 1)
                                                        java.util.Collections.swap(websiteMatchModes, index, index - 1)
                                                        syncWebsiteFromUrlRows()
                                                    }, tag = "url_up_$index"))
                                                    if (index < websiteUrls.lastIndex) add(EditorMenuAction(stringResource(R.string.move_down), Icons.Default.KeyboardArrowDown, onClick = {
                                                        java.util.Collections.swap(websiteUrls, index, index + 1)
                                                        java.util.Collections.swap(websiteMatchModes, index, index + 1)
                                                        syncWebsiteFromUrlRows()
                                                    }, tag = "url_down_$index"))
                                                    add(EditorMenuAction(stringResource(R.string.delete), Icons.Default.Delete, destructive = true, onClick = {
                                                        if (websiteUrls.size == 1) {
                                                            websiteUrls[0] = ""; websiteMatchModes[0] = PasswordUrlRules.Mode.DEFAULT.name
                                                        } else {
                                                            websiteUrls.removeAt(index); websiteMatchModes.removeAt(index)
                                                        }
                                                        syncWebsiteFromUrlRows()
                                                    }, tag = "url_delete_$index"))
                                                })
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth().testTag("url_row_$index"),
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Uri,
                                            imeAction = ImeAction.Next
                                        ),
                                        shape = urlShape
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                TextButton(
                                    onClick = {
                                        websiteUrls.add("")
                                        websiteMatchModes.add(PasswordUrlRules.Mode.DEFAULT.name)
                                        syncWebsiteFromUrlRows()
                                    },
                                    modifier = Modifier.weight(1f).testTag("url_add"),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = stringResource(R.string.add_url),
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                PasswordAppBindingButton(
                                    hasBindings = linkedAppBindings.isNotEmpty(),
                                    onClick = { showAppSelectorFromWebsite = true },
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            PasswordAppBindingChips(
                                linkedAppBindings = linkedAppBindings,
                                onOpenSelector = { showAppSelectorFromWebsite = true },
                                onRemoveBinding = { packageName ->
                                    val updated = removeLinkedAppBinding(
                                        appPackageName,
                                        appName,
                                        packageName
                                    )
                                    appPackageName = updated.first
                                    appName = updated.second
                                }
                            )
                        }
                        }
                        }
                        // 单凭据继续使用原页面；批量模式只显示当前菜单选中的账号。
                        if (showCredentialEditorContent && !isBarcodeMode && templateDraft == null) {
                            val activeCredentialIndex = if (isMultiCredentialMode) {
                                selectedCredentialEditorIndex.coerceIn(0, credentialUsernames.lastIndex)
                            } else {
                                0
                            }
                            val activeUsername = if (isMultiCredentialMode) {
                                credentialUsernames[activeCredentialIndex]
                            } else {
                                username
                            }
                            val activeUsernameFocusRequester = credentialUsernameFocusRequesters.getOrPut(
                                activeCredentialIndex
                            ) { FocusRequester() }
                            val activeUsernameBringIntoViewRequester = credentialBringIntoViewRequesters.getOrPut(
                                activeCredentialIndex
                            ) { BringIntoViewRequester() }
                            val activeUsernameSuggestionState = if (isMultiCredentialMode) {
                                credentialUsernameSuggestionState
                            } else {
                                usernameSuggestionState
                            }
                            val activeUsernameSuggestionVisible = if (isMultiCredentialMode) {
                                credentialUsernameSuggestionVisible
                            } else {
                                usernameSuggestionVisible
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = activeUsername,
                                    onValueChange = { value ->
                                        if (isMultiCredentialMode) {
                                            credentialUsernames[activeCredentialIndex] = value
                                        } else {
                                            username = value
                                        }
                                    },
                                    label = { Text(stringResource(R.string.username)) },
                                    leadingIcon = { Icon(Icons.Default.Person, null) },
                                    trailingIcon = {
                                        Row {

                                                IconButton(
                                                    onClick = {
                                                        commonAccountSelectorField = "username"
                                                        commonAccountSelectorTargetIndex = if (isMultiCredentialMode) {
                                                            activeCredentialIndex
                                                        } else {
                                                            -1
                                                        }
                                                        showCommonAccountSelector = true
                                                    }
                                                ) {
                                                    Icon(
                                                        Icons.Default.AutoAwesome,
                                                        contentDescription = stringResource(R.string.cg_username_title),
                                                        modifier = Modifier.size(20.dp),
                                                        tint = MaterialTheme.colorScheme.primary
                                                    )
                                                }

                                            if (activeUsername.isNotEmpty()) {
                                                IconButton(onClick = {
                                                    ClipboardUtils.copyToClipboard(
                                                        context = context,
                                                        text = activeUsername,
                                                        label = context.getString(R.string.username),
                                                        sensitive = true
                                                    )
                                                    Toast.makeText(
                                                        context,
                                                        context.getString(R.string.username_copied),
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }) {
                                                    Icon(
                                                        Icons.Default.ContentCopy,
                                                        contentDescription = stringResource(R.string.copy),
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .focusRequester(activeUsernameFocusRequester)
                                        .bringIntoViewRequester(activeUsernameBringIntoViewRequester)
                                        .onFocusChanged { focusState ->
                                            if (isMultiCredentialMode) {
                                                focusedCredentialUsernameIndex = if (focusState.isFocused) {
                                                    activeCredentialIndex
                                                } else {
                                                    focusedCredentialUsernameIndex.takeUnless {
                                                        it == activeCredentialIndex
                                                    }
                                                }
                                            } else {
                                                isUsernameFieldFocused = focusState.isFocused
                                            }
                                        },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                    shape = if (contentMode) if (loginType.equals("SSO", ignoreCase = true) || activeUsernameSuggestionVisible) RoundedCornerShape(24.dp) else RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 4.dp, bottomEnd = 4.dp) else RoundedCornerShape(12.dp)
                                )
                            }

                            MonicaExpandableContent(
                                expanded = activeUsernameSuggestionVisible
                            ) {
                                UsernameSuggestionPanel(
                                    state = activeUsernameSuggestionState,
                                    onApplySuggestion = { suggestion ->
                                        if (isMultiCredentialMode) {
                                            credentialUsernames[activeCredentialIndex] = suggestion
                                        } else {
                                            username = suggestion
                                        }
                                    },
                                    modifier = Modifier.bringIntoViewRequester(
                                        if (isMultiCredentialMode) {
                                            activeUsernameBringIntoViewRequester
                                        } else {
                                            usernameSuggestionBringIntoViewRequester
                                        }
                                    )
                                )
                            }

                            LaunchedEffect(pendingCredentialFocusIndex, activeCredentialIndex) {
                                if (
                                    isMultiCredentialMode &&
                                    pendingCredentialFocusIndex == activeCredentialIndex
                                ) {
                                    kotlinx.coroutines.delay(80)
                                    activeUsernameFocusRequester.requestFocus()
                                    activeUsernameBringIntoViewRequester.bringIntoView()
                                    pendingCredentialFocusIndex = null
                                }
                            }

                            MonicaExpandableContent(
                                expanded = settings.separateUsernameAccountEnabled && !isMultiCredentialMode
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                                    OutlinedTextField(
                                        value = separatedUsername,
                                        onValueChange = { separatedUsername = it },
                                        label = { Text(stringResource(R.string.autofill_username)) },
                                        leadingIcon = { Icon(Icons.Default.Badge, null) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .onFocusChanged { focusState ->
                                                isSeparatedUsernameFieldFocused = focusState.isFocused
                                            },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                        shape = if (contentMode) RoundedCornerShape(4.dp) else RoundedCornerShape(12.dp)
                                    )

                                    MonicaExpandableContent(
                                        expanded = separatedUsernameSuggestionVisible
                                    ) {
                                        UsernameSuggestionPanel(
                                            state = separatedUsernameSuggestionState,
                                            onApplySuggestion = { separatedUsername = it },
                                            modifier = Modifier.bringIntoViewRequester(
                                                separatedUsernameSuggestionBringIntoViewRequester
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        // 登录方式只用于密码条目；二维码和批量凭据保留各自的条目类型。
                        if (showCommonEditorContent && !isMultiCredentialMode && !isBarcodeMode && loginType.equals("SSO", ignoreCase = true)) {
                            LoginTypeSelector(
                                loginType = loginType,
                                ssoProvider = ssoProvider,
                                ssoRefEntryId = ssoRefEntryId,
                                allPasswords = allPasswordsForRef,
                                                                onSsoProviderChange = { ssoProvider = it },
                                onSsoRefEntryIdChange = { ssoRefEntryId = it }
                            )
                        }

                        // Passwords (仅在账号密码模式下显示)
                        MonicaExpandableContent(
                            expanded = showCredentialEditorContent &&
                                loginType.equals("PASSWORD", ignoreCase = true) &&
                                !isBarcodeMode
                        ) {
                            Column(modifier = Modifier.testTag("password_editor_credentials").animateContentSize(),
                                verticalArrangement = Arrangement.spacedBy(if (contentMode) 2.dp else 12.dp)) {
                                val visiblePasswordIndices = if (passwords.isEmpty()) emptyList() else if (isMultiCredentialMode) {
                                    listOf(selectedCredentialEditorIndex.coerceIn(0, passwords.lastIndex))
                                } else {
                                    passwords.indices.toList()
                                }
                                visiblePasswordIndices.forEach { index ->
                                    val pwd = passwords[index]
                                    val previousIndex = visiblePasswordIndices.getOrNull(visiblePasswordIndices.indexOf(index) - 1)
                                    val startsPasswordGroup = if (previousIndex == null) {
                                        if (settings.separateUsernameAccountEnabled && !isMultiCredentialMode) separatedUsernameSuggestionVisible
                                        else if (isMultiCredentialMode) credentialUsernameSuggestionVisible else usernameSuggestionVisible
                                    } else focusedPasswordFieldIndex == previousIndex && passwords[previousIndex].isBlank() && !inlineGeneratedPasswords[previousIndex].isNullOrBlank()
                                    val endsPasswordGroup = (index == visiblePasswordIndices.last() && !contentMode) ||
                                        (focusedPasswordFieldIndex == index && pwd.isBlank() && !inlineGeneratedPasswords[index].isNullOrBlank()) ||
                                        (isEditing && originalIds.getOrNull(index) in unreadablePasswordIds) || hasOwnershipConflict
                                    val isPasswordVisible = isPasswordFieldVisible(index)
                                    val isUnreadablePassword =
                                        isEditing && originalIds.getOrNull(index) in unreadablePasswordIds
                                    val passwordShape = if (contentMode) RoundedCornerShape(
                                                    topStart = if (startsPasswordGroup) 24.dp else 4.dp,
                                                    topEnd = if (startsPasswordGroup) 24.dp else 4.dp,
                                                    bottomStart = if (endsPasswordGroup) 24.dp else 4.dp,
                                                    bottomEnd = if (endsPasswordGroup) 24.dp else 4.dp
                                                ) else RoundedCornerShape(12.dp)
                                    Column {
                                      Column(modifier = if (contentMode) Modifier.fillMaxWidth()
                                          .testTag("password_editor_card_$index")
                                          .clip(passwordShape)
                                          .background(if (focusedPasswordFieldIndex == index) MaterialTheme.colorScheme.surfaceContainerHigh
                                              else MaterialTheme.colorScheme.surfaceContainer) else Modifier) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            OutlinedTextField(
                                                value = pwd,
                                                onValueChange = { passwords[index] = it },
                                                label = {
                                                    Text(
                                                        if (!isMultiCredentialMode && passwords.size > 1) {
                                                            stringResource(R.string.password) + " ${index + 1}"
                                                        } else {
                                                            stringResource(R.string.password)
                                                        }
                                                    )
                                                },
                                                leadingIcon = { Icon(Icons.Default.Lock, null) },
                                                visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                                trailingIcon = {
                                                    CredentialPasswordActions(isPasswordVisible,
                                                        onToggleVisibility = { togglePasswordFieldVisibility(index) },
                                                        onGenerate = {
                                                            currentPasswordIndexForGenerator = index
                                                            showPasswordGenerator = true
                                                        },
                                                        onRemove = if (!isMultiCredentialMode && passwords.size > 1) ({
                                                            removePasswordFieldAt(index)
                                                        }) else null,
                                                        tag = "password_editor_$index", removeTag = "password_editor_remove_$index")
                                                },
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .testTag("password_editor_value_$index")
                                                    .onFocusChanged { focusState ->
                                                        if (focusState.isFocused) {
                                                            focusedPasswordFieldIndex = index
                                                            if (passwords[index].isBlank()) {
                                                                ensureInlinePasswordSuggestion(index)
                                                            }
                                                        } else if (focusedPasswordFieldIndex == index) {
                                                            focusedPasswordFieldIndex = null
                                                        }
                                                    },
                                                singleLine = true,
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                                                shape = passwordShape,
                                                supportingText = if (isUnreadablePassword) {
                                                    {
                                                        Text(stringResource(R.string.bitwarden_password_unreadable_inline))
                                                    }
                                                } else if (hasOwnershipConflict) {
                                                    {
                                                        Text(stringResource(R.string.password_owner_conflict_inline))
                                                    }
                                                } else {
                                                    null
                                                }
                                            )
                                        }

                                        if (contentMode) {
                                            androidx.compose.animation.AnimatedVisibility(
                                                visible = pwd.isNotEmpty() && !isUnreadablePassword,
                                                enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
                                                exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut(),
                                            ) {
                                                Box(Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp, bottom = 12.dp)) {
                                                    takagi.ru.monica.ui.components.PasswordStrengthBadge(
                                                        strength = PasswordStrengthAnalyzer.calculateStrength(pwd),
                                                        modifier = Modifier.testTag("password_strength_badge_$index"))
                                                }
                                            }
                                        }
                                      }

                                        val inlinePasswordSuggestion = inlineGeneratedPasswords[index]
                                        val passwordSuggestionVisible = focusedPasswordFieldIndex == index &&
                                            pwd.isBlank() &&
                                            !inlinePasswordSuggestion.isNullOrBlank()
                                        MonicaExpandableContent(
                                            expanded = passwordSuggestionVisible
                                        ) {
                                            inlinePasswordSuggestion?.let { suggestion ->
                                                InlineGeneratedPasswordSuggestionCard(
                                                    password = suggestion,
                                                    onApply = {
                                                        passwords[index] = suggestion
                                                        inlineGeneratedPasswords.remove(index)
                                                    },
                                                    modifier = Modifier.bringIntoViewRequester(
                                                        passwordSuggestionBringIntoViewRequester
                                                    )
                                                )
                                            }
                                        }
                                        
                                        // Strength Indicator for EACH password or just hide it to avoid clutter?
                                        // User didn't specify. But showing it is good.
                                        if (!contentMode && pwd.isNotEmpty()) {
                                            val strength = PasswordStrengthAnalyzer.calculateStrength(pwd)
                                            PasswordStrengthIndicator(
                                                strength = strength,
                                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)
                                            )
                                        }
                                    }
                                }

                                if (contentMode && shouldShowSecurityVerification()) {
                                    primaryAuthenticatorField()
                                }

                                if (contentMode && !shouldShowSecurityVerification()) {
                                    primaryCredentialNoteField()
                                }

                                // 旧的“一个条目多个密码”编辑能力继续保留；批量凭据每页固定一个密码。
                                if (!isMultiCredentialMode) {
                                    TextButton(
                                        onClick = { passwords.add("") },
                                        modifier = Modifier.fillMaxWidth().testTag("password_editor_add_password"),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Icon(Icons.Default.Add, null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(stringResource(R.string.add_password))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (contentMode) item("content_group_spacing") { Spacer(Modifier.height(12.dp)) }
            val sectionOrder = if (contentMode) visibleContentTokens() else PasswordContentSection.entries.map { it.name }
            sectionOrder.forEach { token ->
                val extraGroup = extraCredentialGroups.firstOrNull { ProjectCredentialGroup.token(it.id) == token }
                val storedBlock = storedBlocks.firstOrNull { it.token == token }
                if (extraGroup != null) {
                    contentItem(key = token) {
                        ProjectCredentialEditor(extraGroup,
                            onChange = { updated -> val index = extraCredentialGroups.indexOfFirst { it.id == updated.id }; if (index >= 0) extraCredentialGroups[index] = updated },
                            onRemove = { extraCredentialGroups.removeAll { it.id == extraGroup.id }; requestedContentSections.remove(token) },
                            onGenerate = { passwordId -> projectGeneratorTarget = extraGroup.id to passwordId }, settings = settings)
                    }
                } else if (storedBlock != null) {
                    contentItem(key = token) {
                        val order = visibleContentTokens().filterNot { it == "AUTHENTICATOR" }
                        val position = order.indexOf(token)
                        PasswordContentBlockCard(storedBlock, takagi.ru.monica.ui.components.EntryContentActions(
                            moveUp = if (position > 0) ({ moveContentToken(token, -1) }) else null,
                            moveDown = if (position < order.lastIndex) ({ moveContentToken(token, 1) }) else null,
                            remove = storedBlock.block?.let { { replaceBlockFields(PasswordContentBlocks.remove(contentExtraFields.toList(), token)); requestedContentSections.remove(token) } },
                            groupIndex = position.coerceAtLeast(0), groupCount = order.size.coerceAtLeast(1)),
                            onClick = { if (storedBlock.block != null) editingBlock = storedBlock.block else viewingBlock = storedBlock })
                    }
                } else {
                val section = PasswordContentSection.entries.firstOrNull { it.name == token } ?: return@forEach
                when (section) {
                    PasswordContentSection.AUTHENTICATOR -> {
            // Security Card (TOTP) - 根据设置和数据决定是否显示
            if (showCredentialEditorContent && shouldShowSecurityVerification() &&
                !(contentMode && loginType.equals("PASSWORD", ignoreCase = true))) {
                item(key = "content_authenticator") {
                    primaryAuthenticatorField()
                }
            }


                    }
                    PasswordContentSection.NOTES -> {
            // Organization Card - 根据设置和数据决定是否显示
            if (showCredentialEditorContent && shouldShowCategoryAndNotes()) {
                contentItem(key = "content_notes") {
                    takagi.ru.monica.ui.components.EntryEditorSection(
                        templateMode = isBarcodeMode,
                        contentMode = contentMode, section = PasswordContentSection.NOTES,
                        summary = credentialScopedNotes.take(100),
                        open = editingContentSection == "NOTES",
                        onOpenChange = { editingContentSection = if (it) "NOTES" else null },
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val selectedNotePreview = remember(selectedBoundNote) {
                                selectedBoundNote?.let { note ->
                                    takagi.ru.monica.notes.domain.NoteContentCodec
                                        .decodeFromItem(note)
                                        .content
                                        .replace("\n", " ")
                                        .trim()
                                }.orEmpty()
                            }

                            OutlinedTextField(
                                value = credentialScopedNotes,
                                onValueChange = { value ->
                                    if (isMultiCredentialMode) {
                                        activeCredentialMetadata.notes = value
                                    } else {
                                        notes = value
                                    }
                                },
                                label = { Text(stringResource(if (contentMode) R.string.password_content_notes else R.string.notes)) },
                                leadingIcon = { Icon(Icons.Default.Edit, null) },
                                modifier = Modifier.fillMaxWidth().testTag("password_content_notes")
                                    .then(if (contentMode) Modifier.heightIn(min = 160.dp, max = 320.dp) else Modifier.height(88.dp)),
                                minLines = if (contentMode) 5 else 1,
                                maxLines = if (contentMode) 12 else 4,
                                shape = RoundedCornerShape(12.dp)
                            )

                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                            Text(
                                text = stringResource(R.string.embedded_copy_note),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            FilledTonalButton(onClick = {
                                editingWallet = EmbeddedWalletContent.create(takagi.ru.monica.data.SecureItem(
                                    itemType = takagi.ru.monica.data.ItemType.NOTE, title = "",
                                    itemData = takagi.ru.monica.notes.domain.NoteContentCodec.encode("", isMarkdown = true).first))
                            }) { Text(stringResource(R.string.add)) }
                            val embeddedNote = copiedContent(EmbeddedWalletContent.Kind.NOTE)
                            if (embeddedNote != null) {
                                takagi.ru.monica.ui.components.EmbeddedWalletPreview(embeddedNote) { editingWallet = embeddedNote }
                                OutlinedButton(onClick = { showBoundNotePicker = true }) { Text(stringResource(R.string.embedded_copy_note)) }
                            } else if (selectedBoundNote != null) {
                                ElevatedCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.elevatedCardColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                                    )
                                ) {
                                    Column(
                                        modifier = Modifier.padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Description,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                                            )
                                            Column(
                                                modifier = Modifier.weight(1f),
                                                verticalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Text(
                                                    text = selectedBoundNote.title.ifBlank {
                                                        stringResource(R.string.untitled)
                                                    },
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.Medium,
                                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                                )
                                                if (selectedNotePreview.isNotBlank()) {
                                                    Text(
                                                        text = selectedNotePreview,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            FilledTonalButton(onClick = { showBoundNotePicker = true }) {
                                                Text(stringResource(R.string.change_bound_note))
                                            }
                                            TextButton(onClick = {
                                                if (isMultiCredentialMode) {
                                                    activeCredentialMetadata.boundNoteId = null
                                                } else {
                                                    boundNoteId = null
                                                }
                                            }) {
                                                Text(stringResource(R.string.unbind_note))
                                            }
                                        }
                                    }
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { showBoundNotePicker = true },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.Link, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.embedded_copy_note))
                                }
                            }
                        }
                    }
                }
            }  // 分类与备注 if 结束
            

                    }
                    PasswordContentSection.CUSTOM_FIELDS -> {
            if (contentMode) {
                val fields = if (isMultiCredentialMode && showCredentialEditorContent) activeCredentialMetadata.credentialCustomFields else customFields
                val visibleFields = fields.filterNot { it.title == PasswordUrlRules.FIELD || it.title == ProjectCredentialGroup.FIELD || it.title == EntryContentFields.ORDER || EmbeddedWalletContent.isMetadata(it.title) || PasswordContentBlocks.owns(it.title) || takagi.ru.monica.data.model.TemplateCredentialDraft.ownsField(it.title) || CredentialExchangeMetadata.ownsField(it.title) || EntrySupplementalSpecs.spec(it.title) != null }
                if (contentSectionEnabled(PasswordContentSection.CUSTOM_FIELDS, true) || visibleFields.isNotEmpty()) contentItem("content_custom_fields") {
                    takagi.ru.monica.ui.components.EntryContentPanel(PasswordContentSection.CUSTOM_FIELDS,
                        visibleFields.joinToString(" · ") { it.title }, editingContentSection == "CUSTOM_FIELDS",
                        { editingContentSection = if (it) "CUSTOM_FIELDS" else null }) {
                        visibleFields.forEachIndexed { index, field ->
                            androidx.compose.runtime.key(field.id) {
                                CustomFieldEditCard(index = index, field = field,
                                    onFieldChange = { updated -> val i = fields.indexOfFirst { it.id == field.id }; if (i >= 0) fields[i] = updated },
                                    onDelete = { fields.remove(field) })
                            }
                        }
                        takagi.ru.monica.ui.components.EntryContentFieldButton(visibleFields) { updated ->
                            val managed = fields.filter { it.title == PasswordUrlRules.FIELD || it.title == ProjectCredentialGroup.FIELD || it.title == EntryContentFields.ORDER || EmbeddedWalletContent.isMetadata(it.title) || PasswordContentBlocks.owns(it.title) || takagi.ru.monica.data.model.TemplateCredentialDraft.ownsField(it.title) || CredentialExchangeMetadata.ownsField(it.title) || EntrySupplementalSpecs.spec(it.title) != null }
                            fields.clear(); fields.addAll(managed + updated)
                        }
                    }
                }
            } else {
            if (!isBarcodeMode && showCommonEditorContent &&
                (contentSectionEnabled(PasswordContentSection.CUSTOM_FIELDS, true) || customFields.any { !CredentialExchangeMetadata.ownsField(it.title) })) {
                // 自定义字段区域标题 (带添加按钮)
                item {
                    if (contentMode) {
                        takagi.ru.monica.ui.components.EntryContentFieldButton(customFields.toList()) { updated ->
                            customFields.clear()
                            customFields.addAll(updated)
                        }
                    } else {
                    CustomFieldSectionHeader(
                        onAddClick = {
                            customFields.add(CustomFieldDraft(
                                id = CustomFieldDraft.nextTempId(),
                                title = "",
                                value = "",
                                isProtected = false
                            ))
                        }
                    )
                    }

                }

                // 自定义字段编辑卡片 (独立卡片样式)
                items(customFields.size, key = { "custom_field_${customFields[it].id}" }) { index ->
                    val field = customFields[index]
                    if (!CredentialExchangeMetadata.ownsField(field.title) && field.title != EntryContentFields.ORDER && EntrySupplementalSpecs.spec(field.title) == null) CustomFieldEditCard(
                        groupShape = if (contentMode) takagi.ru.monica.ui.components.entryGroupShape(index, customFields.size) else null,
                        index = index,
                        field = field,
                        onFieldChange = { updated ->
                            customFields[index] = updated
                        },
                        onDelete = {
                            customFields.removeAt(index)
                        }
                    )
                }

            }

            if (!isBarcodeMode && isMultiCredentialMode && showCredentialEditorContent &&
                (contentSectionEnabled(PasswordContentSection.CUSTOM_FIELDS, true) || activeCredentialMetadata.credentialCustomFields.any { !CredentialExchangeMetadata.ownsField(it.title) })) {
                item {
                    if (contentMode) {
                        takagi.ru.monica.ui.components.EntryContentFieldButton(activeCredentialMetadata.credentialCustomFields.toList()) { updated ->
                            activeCredentialMetadata.credentialCustomFields.clear()
                            activeCredentialMetadata.credentialCustomFields.addAll(updated)
                        }
                    } else {
                    CustomFieldSectionHeader(
                        onAddClick = {
                            activeCredentialMetadata.credentialCustomFields.add(
                                CustomFieldDraft(
                                    id = CustomFieldDraft.nextTempId(),
                                    title = "",
                                    value = "",
                                    isProtected = false
                                )
                            )
                        }
                    )
                    }

                }

                items(activeCredentialMetadata.credentialCustomFields.size) { index ->
                    val field = activeCredentialMetadata.credentialCustomFields[index]
                    if (!CredentialExchangeMetadata.ownsField(field.title) && field.title != EntryContentFields.ORDER && EntrySupplementalSpecs.spec(field.title) == null) CustomFieldEditCard(
                        groupShape = if (contentMode) takagi.ru.monica.ui.components.entryGroupShape(index, activeCredentialMetadata.credentialCustomFields.size) else null,
                        index = index,
                        field = field,
                        onFieldChange = { updated ->
                            activeCredentialMetadata.credentialCustomFields[index] = updated
                        },
                        onDelete = {
                            activeCredentialMetadata.credentialCustomFields.removeAt(index)
                        }
                    )
                }
            }


            }

                    }
                    PasswordContentSection.ATTACHMENTS -> {
            if (!isBarcodeMode && showCredentialEditorContent &&
                (contentSectionEnabled(PasswordContentSection.ATTACHMENTS, true) || existingContentAttachments.isNotEmpty() ||
                    credentialAttachmentDrafts.any { it.isNotEmpty() })) {
                // 附件区块：批量模式下每个凭据页维护自己的附件草稿。
                contentItem(key = "content_attachments") {
                    val activeCredentialIndex = if (isMultiCredentialMode) {
                        selectedCredentialEditorIndex.coerceIn(
                            0,
                            credentialAttachmentDrafts.lastIndex
                        )
                    } else {
                        0
                    }
                    val editsExistingCredential = isEditing && activeCredentialIndex == 0
                    val editKeePassContext = if (
                        editsExistingCredential &&
                        keepassDatabaseId != null
                    ) {
                        editingKeePassEntryUuid?.takeIf { it.isNotBlank() }?.let { entryUuid ->
                            AttachmentFacade.KeePassContext(
                                databaseId = keepassDatabaseId!!,
                                entryUuid = entryUuid
                            )
                        }
                    } else {
                        null
                    }
                    takagi.ru.monica.ui.components.EntryEditorSection(
                        templateMode = isBarcodeMode,
                        contentMode = contentMode, section = PasswordContentSection.ATTACHMENTS, summary = "",
                        open = editingContentSection == "ATTACHMENTS",
                        onOpenChange = { editingContentSection = if (it) "ATTACHMENTS" else null }) {
                    takagi.ru.monica.attachments.ui.AttachmentsEditSection(
                        passwordId = if (editsExistingCredential) passwordId ?: -1L else -1L,
                        isPlusActivated = settings.isPlusActivated,
                        attachmentSource = if (editKeePassContext != null) {
                            takagi.ru.monica.attachments.model.AttachmentSource.KEEPASS
                        } else {
                            takagi.ru.monica.attachments.model.AttachmentSource.LOCAL
                        },
                        keepassContext = editKeePassContext,
                        pendingDrafts = if (editsExistingCredential) {
                            null
                        } else {
                            credentialAttachmentDrafts[activeCredentialIndex]
                        }
                    )
                    }
                }
            }


                    }
                    PasswordContentSection.CONTACT -> {
            // Collapsible: Personal Info - 根据设置和数据决定是否显示
            if (showCredentialEditorContent && shouldShowPersonalInfo()) {
                contentItem(key = "content_contact") {
                    takagi.ru.monica.ui.components.EntryEditorSection(
                        templateMode = isBarcodeMode,
                        contentMode = contentMode, section = PasswordContentSection.CONTACT,
                        summary = (credentialScopedEmails + credentialScopedPhones).filter { it.isNotBlank() }.joinToString(" · "),
                        open = editingContentSection == "CONTACT",
                        onOpenChange = { editingContentSection = if (it) "CONTACT" else null },
                        collapsible = true, expanded = personalInfoExpanded, onExpandedChange = { personalInfoExpanded = it },
                    ) {
                        if (!contentMode && hasExtraSection("CONTACT")) {
                            EntrySupplementalFields("CONTACT", contentExtraFields.toList()) { updated ->
                                contentExtraFields.clear(); contentExtraFields.addAll(updated)
                            }
                        }
                        if (contentMode) {
                            takagi.ru.monica.ui.components.EntryContactFields(credentialScopedEmails.toList(), credentialScopedPhones.toList(),
                                onEmails = { credentialScopedEmails.clear(); credentialScopedEmails.addAll(it) },
                                onPhones = { credentialScopedPhones.clear(); credentialScopedPhones.addAll(it) })
                            EntrySupplementalFields("CONTACT", contentExtraFields.toList()) { updated ->
                                contentExtraFields.clear(); contentExtraFields.addAll(updated)
                            }

                        } else {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            // Multiple Email Fields
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.field_email),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                        }
                        credentialScopedEmails.forEachIndexed { index, emailValue ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = emailValue,
                                    onValueChange = { credentialScopedEmails[index] = it },
                                    label = { Text("${stringResource(R.string.field_email)} ${index + 1}") },
                                    leadingIcon = { Icon(MonicaIcons.General.email, null) },
                                    modifier = Modifier.weight(1f),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    isError = emailValue.isNotEmpty() && !takagi.ru.monica.utils.FieldValidation.isValidEmail(emailValue),
                                    trailingIcon = if (canSelectEmailTemplate) {
                                        {
                                            IconButton(
                                                onClick = {
                                                    commonAccountSelectorField = "email"
                                                    commonAccountSelectorTargetIndex = index
                                                    showCommonAccountSelector = true
                                                }
                                            ) {
                                                Icon(
                                                    Icons.Default.PersonAdd,
                                                    contentDescription = stringResource(R.string.fill_common_account),
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    } else null
                                )
                                if (credentialScopedEmails.size > 1) {
                                    IconButton(
                                        onClick = { credentialScopedEmails.removeAt(index) }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = stringResource(R.string.delete),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                        TextButton(
                            onClick = { credentialScopedEmails.add("") },
                            modifier = Modifier.align(Alignment.Start)
                        ) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.add_email))
                        }
                        
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        
                        // Multiple Phone Fields
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.field_phone),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        credentialScopedPhones.forEachIndexed { index, phoneValue ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = phoneValue,
                                    onValueChange = {
                                        if (it.all { char -> char.isDigit() } && it.length <= 15) {
                                            credentialScopedPhones[index] = it
                                        }
                                    },
                                    label = { Text("${stringResource(R.string.field_phone)} ${index + 1}") },
                                    leadingIcon = { Icon(MonicaIcons.General.phone, null) },
                                    modifier = Modifier.weight(1f),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    trailingIcon = if (canSelectPhoneTemplate) {
                                        {
                                            IconButton(
                                                onClick = {
                                                    commonAccountSelectorField = "phone"
                                                    commonAccountSelectorTargetIndex = index
                                                    showCommonAccountSelector = true
                                                }
                                            ) {
                                                Icon(
                                                    Icons.Default.PersonAdd,
                                                    contentDescription = stringResource(R.string.fill_common_account),
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    } else null
                                )
                                if (credentialScopedPhones.size > 1) {
                                    IconButton(
                                        onClick = { credentialScopedPhones.removeAt(index) }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = stringResource(R.string.delete),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                        TextButton(
                            onClick = { credentialScopedPhones.add("") },
                            modifier = Modifier.align(Alignment.Start)
                        ) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.add_phone))
                        }
                    }

                    }
                }
            }
            }  // Personal Info if 结束


                    }
                    PasswordContentSection.DOCUMENT -> {
                        if (showCredentialEditorContent) contentItem(key = "content_document") {
                            val snapshot = copiedContent(EmbeddedWalletContent.Kind.DOCUMENT)
                            if (snapshot != null) takagi.ru.monica.ui.components.EmbeddedWalletPreview(snapshot) { openDocumentEditor() }
                            else takagi.ru.monica.ui.components.EntryContentPanel(
                                PasswordContentSection.DOCUMENT, "", open = false,
                                onOpenChange = { if (it) openDocumentEditor() }) { }
                        }
                    }
                    PasswordContentSection.ADDRESS -> {
                        if (showCredentialEditorContent) contentItem(key = "content_address") {
                            val snapshot = runCatching { addressProjection() }.getOrNull()
                            if (snapshot != null) takagi.ru.monica.ui.components.EntryContentPanel(
                                PasswordContentSection.ADDRESS, listOf(credentialScopedCity, credentialScopedCountry).filter { it.isNotBlank() }.joinToString(" · "),
                                open = false, onOpenChange = { if (it) openAddressEditor() }) { }
                            else Text(stringResource(R.string.content_block_preserved))
                        }
                    }
                    PasswordContentSection.PAYMENT -> {
            // Collapsible: Payment Info
            if (showCredentialEditorContent && shouldShowPaymentInfo()) {
            contentItem(key = "content_payment") {
                takagi.ru.monica.ui.components.EntryEditorSection(
                        templateMode = isBarcodeMode,
                        contentMode = contentMode, section = PasswordContentSection.PAYMENT,
                        summary = credentialScopedCreditCardNumber.takeLast(4).let { if (it.isBlank()) "" else "•••• $it" },
                        open = editingContentSection == "PAYMENT",
                        onOpenChange = { if (it && contentMode) openPaymentEditor() else editingContentSection = if (it) "PAYMENT" else null },
                        collapsible = true, expanded = paymentInfoExpanded, onExpandedChange = { paymentInfoExpanded = it },
                    ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Import Button
                        if (bankCardViewModel != null) {
                            var showBankCardDialog by remember { mutableStateOf(false) }
                            OutlinedButton(
                                onClick = { showBankCardDialog = true },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.CreditCard, null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.import_from_bank_card))
                            }
                            
                            // Bank Card Selection Logic (retained)
                            if (showBankCardDialog) {
                                val bankCards by bankCardViewModel.parsedCards.collectAsState(initial = emptyList())
                                takagi.ru.monica.ui.components.EmbeddedWalletPicker(
                                    bankCards.map { it.item }, stringResource(R.string.embedded_copy_card),
                                    onSelect = { item -> copyWalletItem(item); showBankCardDialog = false },
                                    onDismiss = { showBankCardDialog = false },
            keepassDatabases = keepassDatabases, mdbxDatabases = mdbxDatabases, bitwardenVaults = bitwardenVaults)

                            }
                        }

copiedContent(EmbeddedWalletContent.Kind.BANK_CARD)?.let { copied ->
    takagi.ru.monica.ui.components.EmbeddedWalletPreview(copied) { editingWallet = copied }
}
if (!contentMode && hasExtraSection("PAYMENT")) {
                            EntrySupplementalFields("PAYMENT", contentExtraFields.toList()) { updated ->
                                contentExtraFields.clear(); contentExtraFields.addAll(updated)
                            }
                        }
                        if (copiedContent(EmbeddedWalletContent.Kind.BANK_CARD) == null) {
                        if (contentMode) {
    FilledTonalButton(onClick = {
        val (month, year) = takagi.ru.monica.ui.components.EntryPaymentFormat.splitExpiry(credentialScopedCreditCardExpiry)
        val card = takagi.ru.monica.data.model.BankCardData(cardNumber = credentialScopedCreditCardNumber,
            cardholderName = credentialScopedCreditCardHolder, expiryMonth = month, expiryYear = year, cvv = credentialScopedCreditCardCVV)
        editingWallet = EmbeddedWalletContent.create(takagi.ru.monica.data.SecureItem(itemType = takagi.ru.monica.data.ItemType.BANK_CARD,
            title = context.getString(R.string.payment_info), itemData = kotlinx.serialization.json.JsonObject(
                kotlinx.serialization.json.Json.parseToJsonElement(takagi.ru.monica.data.model.CardWalletDataCodec.encodeBankCardData(card)).jsonObject +
                    EntrySupplementalSpecs.forSection("PAYMENT").mapNotNull { spec ->
                        EntryContentFields.value(contentExtraFields, "PAYMENT", spec.key).takeIf { it.isNotBlank() }
                            ?.let { spec.key to kotlinx.serialization.json.JsonPrimitive(it) }
                    }.toMap()).toString()))
    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.edit)) }

} else {
                        OutlinedTextField(
                            value = credentialScopedCreditCardNumber,
                            onValueChange = { value ->
                                if (value.all { char -> char.isDigit() } && value.length <= 19) {
                                    if (isMultiCredentialMode) {
                                        activeCredentialMetadata.creditCardNumber = value
                                    } else {
                                        creditCardNumber = value
                                    }
                                }
                            },
                            label = { Text(stringResource(R.string.field_card_number)) },
                            leadingIcon = { Icon(MonicaIcons.Data.creditCard, null) },
                            modifier = Modifier.testTag("password_content_card_number").fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            visualTransformation = if (credentialScopedCreditCardNumber.isNotEmpty()) {
                                VisualTransformation { text ->
                                    val offsetMapping = object : OffsetMapping {
                                        override fun originalToTransformed(offset: Int) = if (offset <= 0) 0 else offset + (offset - 1) / 4
                                        override fun transformedToOriginal(offset: Int) = if (offset <= 0) 0 else offset - offset / 5
                                    }
                                    TransformedText(AnnotatedString(takagi.ru.monica.utils.FieldValidation.formatCreditCard(text.text)), offsetMapping)
                                }
                            } else VisualTransformation.None
                        )

                        OutlinedTextField(
                            value = credentialScopedCreditCardHolder,
                            onValueChange = { value ->
                                if (isMultiCredentialMode) {
                                    activeCredentialMetadata.creditCardHolder = value
                                } else {
                                    creditCardHolder = value
                                }
                            },
                            label = { Text(stringResource(R.string.field_cardholder)) },
                            leadingIcon = { Icon(Icons.Default.Person, null) },
                            modifier = Modifier.testTag("password_content_card_holder").fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            takagi.ru.monica.ui.components.PaymentExpiryTextField(
                                value = credentialScopedCreditCardExpiry,
                                onValueChange = { formatted ->
                                    if (isMultiCredentialMode) {
                                        activeCredentialMetadata.creditCardExpiry = formatted
                                    } else {
                                        creditCardExpiry = formatted
                                    }
                                },
                                modifier = Modifier.testTag("password_content_card_expiry").weight(1f),
                                maxYearDigits = 2,
                            )
                            OutlinedTextField(
                                value = credentialScopedCreditCardCVV,
                                onValueChange = { value ->
                                    if (value.all { char -> char.isDigit() } && value.length <= 4) {
                                        if (isMultiCredentialMode) {
                                            activeCredentialMetadata.creditCardCVV = value
                                        } else {
                                            creditCardCVV = value
                                        }
                                    }
                                },
                                label = { Text(stringResource(R.string.field_cvv)) },
                                modifier = Modifier.testTag("password_content_card_cvv").weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                visualTransformation = PasswordVisualTransformation()
                            )
                        }
}
                        }

                    }
                }
            }
            }  // Payment Info if 结束
                    }
                }
            }
            } // End mixed built-in/block content sequence.
            if (contentMode) item("content_editor_add") {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.End) {
                    PasswordContentAddButton(onClick = { showContentMenu = true }, enabled = !isSaving)
                }
            }

        }
        }
    }

    }

    if (contentMode && showContentMenu) {
        PasswordContentMenu(
            sections = if (isMultiCredentialMode && showCommonEditorContent) listOf(PasswordContentSection.CUSTOM_FIELDS)
                else PasswordContentSection.entries.filterNot { it == PasswordContentSection.AUTHENTICATOR || it == PasswordContentSection.CONTACT },
            onAdd = { section ->
                if (section.name !in requestedContentSections) requestedContentSections.add(section.name)
                when (section) {
                    PasswordContentSection.PAYMENT -> paymentInfoExpanded = true
                    PasswordContentSection.CONTACT -> personalInfoExpanded = true
                    PasswordContentSection.ADDRESS -> addressInfoExpanded = true
                    else -> Unit
                }
                showContentMenu = false
                if (section == PasswordContentSection.PAYMENT) openPaymentEditor() else if (section == PasswordContentSection.ADDRESS) openAddressEditor() else if (section == PasswordContentSection.DOCUMENT) openDocumentEditor() else editingContentSection = section.name
            },
            onDismiss = { showContentMenu = false },
            onAddCredential = if (!isMultiCredentialMode && !isBarcodeMode && templateDraft == null) ({
                val group = ProjectCredentialGroup.Group()
                extraCredentialGroups.add(group)
                requestedContentSections.add(ProjectCredentialGroup.token(group.id))
                showContentMenu = false
            }) else null,
            onAddBlock = if (isMultiCredentialMode && showCommonEditorContent) null else ({ kind ->
                showContentMenu = false; editingBlock = PasswordContentBlocks.create(kind)
            }),
        )
    }

    if (showAppSelectorFromWebsite) {
        AppSelectorDialog(
            onDismiss = { showAppSelectorFromWebsite = false },
            onAppSelected = { packageName, name ->
                val updated = addOrReplaceLinkedAppBinding(
                    appPackageName,
                    appName,
                    packageName,
                    name
                )
                appPackageName = updated.first
                appName = updated.second
                showAppSelectorFromWebsite = false
            }
        )
    }

    projectGeneratorTarget?.let { (groupId, passwordId) ->
        takagi.ru.monica.ui.components.CredentialGeneratorSheet(
            username = passwordId == null,
            suggestions = buildCommonAccountOptions(if (passwordId == null) "username" else "password").map {
                takagi.ru.monica.ui.components.GeneratorSuggestion(it.type, it.content)
            },
            preferences = generatorPreferences,
            onDismiss = { projectGeneratorTarget = null },
            onApply = { generated ->
                val index = extraCredentialGroups.indexOfFirst { it.id == groupId }
                if (index >= 0) {
                    val group = extraCredentialGroups[index]
                    extraCredentialGroups[index] = if (passwordId == null) group.copy(username = generated)
                        else group.copy(passwords = group.passwords.map { if (it.id == passwordId) it.copy(value = generated) else it })
                }
                projectGeneratorTarget = null
            })
    }

    if (showPasswordGenerator) {
        PasswordGeneratorDialog(
            commonPasswordOptions = buildCommonAccountOptions("password"),
            generatorPreferences = generatorPreferences,
            onDismiss = { showPasswordGenerator = false },
            onPasswordGenerated = { generatedPassword ->
                if (currentPasswordIndexForGenerator >= 0 && currentPasswordIndexForGenerator < passwords.size) {
                    passwords[currentPasswordIndexForGenerator] = generatedPassword
                }
                showPasswordGenerator = false
            }
        )
    }

    PasswordCustomIconPickers(
        showCustomIconDialog = showCustomIconDialog,
        showSimpleIconPicker = showSimpleIconPicker,
        customIconSearchQuery = customIconSearchQuery,
        customIconType = customIconType,
        customIconValue = customIconValue,
        iconCardsEnabled = settings.iconCardsEnabled,
        isOriginalUploadedIconFile = ::isOriginalUploadedIconFile,
        normalizedIconFileName = ::normalizedIconFileName,
        onCustomIconDialogChange = { showCustomIconDialog = it },
        onSimpleIconPickerChange = { showSimpleIconPicker = it },
        onCustomIconSearchQueryChange = { customIconSearchQuery = it },
        onUploadImage = { imagePickerLauncher.launch("image/*") },
        onSubscribedIconSelected = ::applyUploadedIcon,
        onPickInstalledIcon = {
            showCustomIconDialog = false
            showInstalledIconPicker = true
        },
        showEmojiIconDialog = showEmojiIconDialog,
        onEmojiDialogChange = { showEmojiIconDialog = it },
        onEmojiSelected = { emoji ->
            customIconEditedByUser = true
            customIconType = PASSWORD_ICON_TYPE_EMOJI
            customIconValue = emoji
            customIconUpdatedAt = System.currentTimeMillis()
        },
        onIconCleared = {
            customIconEditedByUser = true
            customIconType = PASSWORD_ICON_TYPE_NONE
            customIconValue = null
            customIconUpdatedAt = System.currentTimeMillis()
        },
        onSimpleIconSelected = { option ->
            customIconEditedByUser = true
            customIconType = PASSWORD_ICON_TYPE_SIMPLE
            customIconValue = option.slug
            customIconUpdatedAt = System.currentTimeMillis()
        }
    )

    if (showInstalledIconPicker) {
        InstalledIconPickerBottomSheet(
            onIconSelected = { fileName ->
                applyUploadedIcon(fileName)
                showInstalledIconPicker = false
            },
            onDismissRequest = { showInstalledIconPicker = false },
        )
    }

    if (showCommonAccountSelector) {
        CommonAccountSelectorSheet(
            selectorField = commonAccountSelectorField,
            selectorOptions = buildCommonAccountOptions(commonAccountSelectorField),
            commonAccountTypeEmail = commonAccountTypeEmail,
            commonAccountTypePassword = commonAccountTypePassword,
            commonAccountTypePhone = commonAccountTypePhone,
            onDismiss = {
                showCommonAccountSelector = false
                commonAccountSelectorTargetIndex = -1
            },
            onApply = { content ->
                applyCommonAccountSelection(commonAccountSelectorField, content)
            }
        )
    }

    PasswordTotpBindingPickerBottomSheet(
        visible = showAuthenticatorPicker,
        candidates = selectableTotpBindings,
        selectedItemId = existingTotpId,
        onSelect = { candidate ->
            applyExistingAuthenticator(candidate)
            showAuthenticatorPicker = false
        },
        onDismiss = { showAuthenticatorPicker = false }
    )

    MultiStorageTargetPickerBottomSheet(
        visible = showStorageTargetSheet,
        selectedTargets = selectedStorageTargets.toList(),
        lockedTargetKeys = existingReplicaTargetKeys,
        categories = categories,
        keepassDatabases = keepassDatabases,
        mdbxDatabases = mdbxDatabases,
        bitwardenVaults = bitwardenVaults,
        getBitwardenFolders = { vaultId -> database.bitwardenFolderDao().getFoldersByVaultFlow(vaultId) },
        getKeePassGroups = localKeePassViewModel?.let { keepassVm -> keepassVm::getGroups }
            ?: { flowOf(emptyList<takagi.ru.monica.utils.KeePassGroupInfo>()) },
        getMdbxFolders = viewModel::getMdbxFolders,
        onDismiss = { showStorageTargetSheet = false },
        onSelectedTargetsChange = ::setSelectedStorageTargets
    )

    if (showBoundNotePicker) takagi.ru.monica.ui.components.EmbeddedWalletPicker(
        selectableNotes, stringResource(R.string.embedded_copy_note),
        onSelect = { note -> copyWalletItem(note); showBoundNotePicker = false },
        onDismiss = { showBoundNotePicker = false },
            keepassDatabases = keepassDatabases, mdbxDatabases = mdbxDatabases, bitwardenVaults = bitwardenVaults)

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PasswordTotpBindingPickerBottomSheet(
    visible: Boolean,
    candidates: List<PasswordTotpBindingCandidate>,
    selectedItemId: Long?,
    onSelect: (PasswordTotpBindingCandidate) -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val passwordDatabase = remember(context) { PasswordDatabase.getDatabase(context) }
    val keepassDatabases by passwordDatabase.localKeePassDatabaseDao().getAllDatabases().collectAsState(initial = emptyList())
    val mdbxDatabases by passwordDatabase.localMdbxDatabaseDao().getAvailableDatabases().collectAsState(initial = emptyList())
    val bitwardenVaults by passwordDatabase.bitwardenVaultDao().getAllVaultsFlow().collectAsState(initial = emptyList())
    var foldersByVault by remember { mutableStateOf<Map<Long, List<BitwardenFolder>>>(emptyMap()) }
    var searchQuery by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(bitwardenVaults) {
        foldersByVault = withContext(Dispatchers.IO) {
            bitwardenVaults.associate { vault ->
                vault.id to passwordDatabase.bitwardenFolderDao().getFoldersByVault(vault.id)
            }
        }
    }

    var sourceFilter by rememberSaveable { mutableStateOf(PasswordTotpPickerSourceFilter.ALL) }
    var selectedKeePassDatabaseId by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedMdbxDatabaseId by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedVaultId by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedFolderId by rememberSaveable { mutableStateOf<String?>(null) }
    var keepassMenuExpanded by remember { mutableStateOf(false) }
    var mdbxMenuExpanded by remember { mutableStateOf(false) }
    var vaultMenuExpanded by remember { mutableStateOf(false) }
    var folderMenuExpanded by remember { mutableStateOf(false) }

    val keepassNameById = remember(keepassDatabases) {
        keepassDatabases.associate { it.id to it.name }
    }
    val mdbxNameById = remember(mdbxDatabases) {
        mdbxDatabases.associate { it.id to it.name }
    }
    val vaultLabelById = remember(bitwardenVaults) {
        bitwardenVaults.associate { vault ->
            val label = vault.displayName?.takeIf { it.isNotBlank() } ?: vault.email
            vault.id to label
        }
    }
    val selectedVaultFolders = remember(selectedVaultId, foldersByVault) {
        selectedVaultId?.let { foldersByVault[it] }.orEmpty()
    }
    val folderNameById = remember(selectedVaultFolders) {
        selectedVaultFolders.associate { it.bitwardenFolderId to it.name }
    }

    val filteredCandidates = remember(
        candidates,
        searchQuery,
        sourceFilter,
        selectedKeePassDatabaseId,
        selectedMdbxDatabaseId,
        selectedVaultId,
        selectedFolderId
    ) {
        val query = searchQuery.trim()
        candidates.filter { candidate ->
            val item = candidate.item
            val matchesQuery = query.isBlank() || listOf(
                item.title,
                candidate.data.issuer,
                candidate.data.accountName
            ).any { value -> value.contains(query, ignoreCase = true) }
            val matchesSource = when (sourceFilter) {
                PasswordTotpPickerSourceFilter.ALL -> true
                PasswordTotpPickerSourceFilter.LOCAL ->
                    item.bitwardenVaultId == null && item.keepassDatabaseId == null && item.mdbxDatabaseId == null
                PasswordTotpPickerSourceFilter.KEEPASS -> {
                    val keepassId = item.keepassDatabaseId
                    keepassId != null && (selectedKeePassDatabaseId == null || keepassId == selectedKeePassDatabaseId)
                }
                PasswordTotpPickerSourceFilter.MDBX -> {
                    val mdbxId = item.mdbxDatabaseId
                    mdbxId != null && (selectedMdbxDatabaseId == null || mdbxId == selectedMdbxDatabaseId)
                }
                PasswordTotpPickerSourceFilter.BITWARDEN -> {
                    val vaultId = item.bitwardenVaultId
                    val folderId = item.bitwardenFolderId
                    vaultId != null &&
                        (selectedVaultId == null || vaultId == selectedVaultId) &&
                        (selectedFolderId == null || folderId == selectedFolderId)
                }
            }
            matchesQuery && matchesSource
        }
    }

    MonicaModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.select_authenticator_to_bind),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.password_picker_results_count, filteredCandidates.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.search_authenticator)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    errorIndicatorColor = Color.Transparent
                )
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = sourceFilter == PasswordTotpPickerSourceFilter.ALL,
                    onClick = {
                        sourceFilter = PasswordTotpPickerSourceFilter.ALL
                        selectedKeePassDatabaseId = null
                        selectedMdbxDatabaseId = null
                        selectedVaultId = null
                        selectedFolderId = null
                    },
                    label = { Text(stringResource(R.string.filter_all)) }
                )
                FilterChip(
                    selected = sourceFilter == PasswordTotpPickerSourceFilter.LOCAL,
                    onClick = {
                        sourceFilter = PasswordTotpPickerSourceFilter.LOCAL
                        selectedKeePassDatabaseId = null
                        selectedMdbxDatabaseId = null
                        selectedVaultId = null
                        selectedFolderId = null
                    },
                    label = { Text(stringResource(R.string.filter_local_only)) }
                )
                FilterChip(
                    selected = sourceFilter == PasswordTotpPickerSourceFilter.KEEPASS,
                    onClick = {
                        sourceFilter = PasswordTotpPickerSourceFilter.KEEPASS
                        selectedMdbxDatabaseId = null
                        selectedVaultId = null
                        selectedFolderId = null
                    },
                    label = { Text(stringResource(R.string.filter_keepass)) }
                )
                FilterChip(
                    selected = sourceFilter == PasswordTotpPickerSourceFilter.MDBX,
                    onClick = {
                        sourceFilter = PasswordTotpPickerSourceFilter.MDBX
                        selectedKeePassDatabaseId = null
                        selectedVaultId = null
                        selectedFolderId = null
                    },
                    label = { Text("MDBX") }
                )
                FilterChip(
                    selected = sourceFilter == PasswordTotpPickerSourceFilter.BITWARDEN,
                    onClick = {
                        sourceFilter = PasswordTotpPickerSourceFilter.BITWARDEN
                        selectedKeePassDatabaseId = null
                        selectedMdbxDatabaseId = null
                    },
                    label = { Text(stringResource(R.string.filter_bitwarden)) }
                )
            }

            if (sourceFilter == PasswordTotpPickerSourceFilter.KEEPASS) {
                ExposedDropdownMenuBox(
                    expanded = keepassMenuExpanded,
                    onExpandedChange = { keepassMenuExpanded = !keepassMenuExpanded }
                ) {
                    OutlinedTextField(
                        readOnly = true,
                        value = selectedKeePassDatabaseId?.let { keepassNameById[it] }
                            ?: stringResource(R.string.password_picker_all_databases),
                        onValueChange = {},
                        label = { Text(stringResource(R.string.password_picker_filter_database)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = keepassMenuExpanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                        singleLine = true
                    )
                    ExposedDropdownMenu(
                        expanded = keepassMenuExpanded,
                        onDismissRequest = { keepassMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.password_picker_all_databases)) },
                            onClick = {
                                selectedKeePassDatabaseId = null
                                keepassMenuExpanded = false
                            }
                        )
                        keepassDatabases.forEach { databaseItem ->
                            DropdownMenuItem(
                                text = { Text(databaseItem.name) },
                                onClick = {
                                    selectedKeePassDatabaseId = databaseItem.id
                                    keepassMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            if (sourceFilter == PasswordTotpPickerSourceFilter.MDBX) {
                ExposedDropdownMenuBox(
                    expanded = mdbxMenuExpanded,
                    onExpandedChange = { mdbxMenuExpanded = !mdbxMenuExpanded }
                ) {
                    OutlinedTextField(
                        readOnly = true,
                        value = selectedMdbxDatabaseId?.let { mdbxNameById[it] }
                            ?: stringResource(R.string.password_picker_all_databases),
                        onValueChange = {},
                        label = { Text(stringResource(R.string.password_picker_filter_database)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = mdbxMenuExpanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                        singleLine = true
                    )
                    ExposedDropdownMenu(
                        expanded = mdbxMenuExpanded,
                        onDismissRequest = { mdbxMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.password_picker_all_databases)) },
                            onClick = {
                                selectedMdbxDatabaseId = null
                                mdbxMenuExpanded = false
                            }
                        )
                        mdbxDatabases.forEach { databaseItem ->
                            DropdownMenuItem(
                                text = { Text(databaseItem.name) },
                                onClick = {
                                    selectedMdbxDatabaseId = databaseItem.id
                                    mdbxMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            if (sourceFilter == PasswordTotpPickerSourceFilter.BITWARDEN) {
                ExposedDropdownMenuBox(
                    expanded = vaultMenuExpanded,
                    onExpandedChange = { vaultMenuExpanded = !vaultMenuExpanded }
                ) {
                    OutlinedTextField(
                        readOnly = true,
                        value = selectedVaultId?.let { vaultLabelById[it] }
                            ?: stringResource(R.string.password_picker_all_vaults),
                        onValueChange = {},
                        label = { Text(stringResource(R.string.password_picker_filter_vault)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = vaultMenuExpanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                        singleLine = true
                    )
                    ExposedDropdownMenu(
                        expanded = vaultMenuExpanded,
                        onDismissRequest = { vaultMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.password_picker_all_vaults)) },
                            onClick = {
                                selectedVaultId = null
                                selectedFolderId = null
                                vaultMenuExpanded = false
                            }
                        )
                        bitwardenVaults.forEach { vault ->
                            val label = vaultLabelById[vault.id].orEmpty()
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    selectedVaultId = vault.id
                                    selectedFolderId = null
                                    vaultMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                if (selectedVaultId != null) {
                    ExposedDropdownMenuBox(
                        expanded = folderMenuExpanded,
                        onExpandedChange = { folderMenuExpanded = !folderMenuExpanded }
                    ) {
                        OutlinedTextField(
                            readOnly = true,
                            value = selectedFolderId?.let { folderNameById[it] }
                                ?: stringResource(R.string.password_picker_all_folders),
                            onValueChange = {},
                            label = { Text(stringResource(R.string.password_picker_filter_folder)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = folderMenuExpanded) },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth(),
                            singleLine = true
                        )
                        ExposedDropdownMenu(
                            expanded = folderMenuExpanded,
                            onDismissRequest = { folderMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.password_picker_all_folders)) },
                                onClick = {
                                    selectedFolderId = null
                                    folderMenuExpanded = false
                                }
                            )
                            selectedVaultFolders.forEach { folder ->
                                DropdownMenuItem(
                                    text = { Text(folder.name) },
                                    onClick = {
                                        selectedFolderId = folder.bitwardenFolderId
                                        folderMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            if (filteredCandidates.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.no_results),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredCandidates, key = { it.item.id }) { candidate ->
                        val item = candidate.item
                        val data = candidate.data
                        val selected = item.id == selectedItemId
                        val sourceLabel = when {
                            item.bitwardenVaultId != null -> {
                                val vaultText = vaultLabelById[item.bitwardenVaultId].orEmpty()
                                val folderText = item.bitwardenFolderId?.let { folderId ->
                                    foldersByVault[item.bitwardenVaultId]?.firstOrNull { it.bitwardenFolderId == folderId }?.name
                                } ?: stringResource(R.string.category_none)
                                "${stringResource(R.string.filter_bitwarden)} · $vaultText · $folderText"
                            }
                            item.keepassDatabaseId != null -> {
                                val dbName = keepassNameById[item.keepassDatabaseId]
                                    ?: item.keepassDatabaseId.toString()
                                "${stringResource(R.string.filter_keepass)} · $dbName"
                            }
                            item.mdbxDatabaseId != null -> {
                                val dbName = mdbxNameById[item.mdbxDatabaseId]
                                    ?: item.mdbxDatabaseId.toString()
                                "MDBX · $dbName"
                            }
                            else -> stringResource(R.string.filter_local_only)
                        }
                        val supporting = listOf(data.issuer, data.accountName)
                            .filter { it.isNotBlank() }
                            .distinct()
                            .joinToString(" · ")

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        sheetState.hide()
                                        onSelect(candidate)
                                    }
                                },
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (selected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerLow
                                }
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = when {
                                        item.bitwardenVaultId != null -> Icons.Default.Cloud
                                        item.keepassDatabaseId != null -> Icons.Default.Storage
                                        item.mdbxDatabaseId != null -> Icons.Default.Folder
                                        else -> Icons.Default.PhoneAndroid
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = if (selected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    }
                                )

                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = item.title
                                            .ifBlank { data.issuer }
                                            .ifBlank { data.accountName },
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (supporting.isNotBlank()) {
                                        Text(
                                            text = supporting,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Text(
                                        text = sourceLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                if (selected) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InlineGeneratedPasswordSuggestionCard(
    password: String,
    onApply: () -> Unit,
    modifier: Modifier = Modifier
) {
    InlinePrimarySuggestionCard(
        label = password,
        leadingIcon = Icons.Default.Key,
        onClick = onApply,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PasswordCustomIconPickers(
    showCustomIconDialog: Boolean,
    showSimpleIconPicker: Boolean,
    showEmojiIconDialog: Boolean,
    customIconSearchQuery: String,
    customIconType: String,
    customIconValue: String?,
    iconCardsEnabled: Boolean,
    isOriginalUploadedIconFile: (String?) -> Boolean,
    normalizedIconFileName: (String?) -> String?,
    onCustomIconDialogChange: (Boolean) -> Unit,
    onSimpleIconPickerChange: (Boolean) -> Unit,
    onEmojiDialogChange: (Boolean) -> Unit,
    onEmojiSelected: (String) -> Unit,
    onCustomIconSearchQueryChange: (String) -> Unit,
    onUploadImage: () -> Unit,
    onSubscribedIconSelected: (String) -> Unit,
    onPickInstalledIcon: () -> Unit,
    onIconCleared: () -> Unit,
    onSimpleIconSelected: (SimpleIconOption) -> Unit
) {
    val context = LocalContext.current
    if (showCustomIconDialog) {
        CustomIconActionDialog(
            showClearAction = customIconType != PASSWORD_ICON_TYPE_NONE,
            onPickFromLibrary = {
                onCustomIconSearchQueryChange("")
                onCustomIconDialogChange(false)
                onSimpleIconPickerChange(true)
            },
            onUploadImage = {
                onCustomIconDialogChange(false)
                onUploadImage()
            },
            onPickEmoji = {
                onCustomIconDialogChange(false)
                onEmojiDialogChange(true)
            },
            onPickInstalledIcon = onPickInstalledIcon,
            onSubscribedIconSelected = onSubscribedIconSelected,
            onClearIcon = {
                val currentUploaded = if (customIconType == PASSWORD_ICON_TYPE_UPLOADED) {
                    normalizedIconFileName(customIconValue)
                } else {
                    null
                }
                if (!currentUploaded.isNullOrBlank() && !isOriginalUploadedIconFile(currentUploaded)) {
                    PasswordCustomIconStore.deleteIconFile(context, currentUploaded)
                }
                onIconCleared()
                onCustomIconDialogChange(false)
            },
            onDismissRequest = { onCustomIconDialogChange(false) }
        )
    }

    if (showEmojiIconDialog) {
        EmojiIconInputDialog(
            initialEmoji = customIconValue.takeIf { customIconType == PASSWORD_ICON_TYPE_EMOJI },
            onConfirm = {
                val currentUploaded = if (customIconType == PASSWORD_ICON_TYPE_UPLOADED) {
                    normalizedIconFileName(customIconValue)
                } else {
                    null
                }
                if (!currentUploaded.isNullOrBlank() && !isOriginalUploadedIconFile(currentUploaded)) {
                    PasswordCustomIconStore.deleteIconFile(context, currentUploaded)
                }
                onEmojiSelected(it)
                onEmojiDialogChange(false)
            },
            onDismissRequest = { onEmojiDialogChange(false) }
        )
    }

    if (showSimpleIconPicker) {
        var iconVisibleCount by rememberSaveable { mutableStateOf(ICON_PICKER_PAGE_SIZE) }
        val iconOptions = remember(context, customIconSearchQuery) {
            SimpleIconCatalog.search(context, customIconSearchQuery)
        }
        LaunchedEffect(customIconSearchQuery, showSimpleIconPicker) {
            if (showSimpleIconPicker) {
                iconVisibleCount = ICON_PICKER_PAGE_SIZE
            }
        }
        val visibleOptions = remember(iconOptions, iconVisibleCount) {
            iconOptions.take(iconVisibleCount.coerceAtMost(iconOptions.size))
        }
        SimpleIconPickerBottomSheet(
            searchQuery = customIconSearchQuery,
            onSearchQueryChange = {
                onCustomIconSearchQueryChange(it)
                iconVisibleCount = ICON_PICKER_PAGE_SIZE
            },
            iconOptions = iconOptions,
            visibleOptions = visibleOptions,
            hasMore = visibleOptions.size < iconOptions.size,
            remainingCount = iconOptions.size - visibleOptions.size,
            iconCardsEnabled = iconCardsEnabled,
            selectedSlug = if (customIconType == PASSWORD_ICON_TYPE_SIMPLE) customIconValue else null,
            onSelectOption = { option ->
                val currentUploaded = if (customIconType == PASSWORD_ICON_TYPE_UPLOADED) {
                    normalizedIconFileName(customIconValue)
                } else {
                    null
                }
                if (!currentUploaded.isNullOrBlank() && !isOriginalUploadedIconFile(currentUploaded)) {
                    PasswordCustomIconStore.deleteIconFile(context, currentUploaded)
                }
                onSimpleIconSelected(option)
                onSimpleIconPickerChange(false)
            },
            onLoadMore = {
                iconVisibleCount = (iconVisibleCount + ICON_PICKER_PAGE_SIZE)
                    .coerceAtMost(iconOptions.size)
            },
            onDismissRequest = { onSimpleIconPickerChange(false) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommonAccountSelectorSheet(
    selectorField: String,
    selectorOptions: List<CommonAccountFillOption>,
    commonAccountTypeEmail: String,
    commonAccountTypePassword: String,
    commonAccountTypePhone: String,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit
) {
    if (selectorField == "username") {
        takagi.ru.monica.ui.components.CredentialGeneratorSheet(
            username = true,
            suggestions = selectorOptions.map { takagi.ru.monica.ui.components.GeneratorSuggestion(it.type, it.content) },
            onDismiss = onDismiss,
            onApply = { value -> onApply(value); onDismiss() },
        )
        return
    }
    val coroutineScope = rememberCoroutineScope()
    val allFilterLabel = stringResource(R.string.filter_all)
    val selectorFieldLabel = when (selectorField) {
        "username" -> stringResource(R.string.field_account)
        "email" -> stringResource(R.string.field_email)
        "phone" -> stringResource(R.string.field_phone)
        "password" -> stringResource(R.string.password)
        else -> ""
    }
    val availableTypeFilters = remember(selectorOptions, allFilterLabel) {
        buildList {
            add(allFilterLabel)
            addAll(selectorOptions.map { it.type }.distinct())
        }
    }
    var selectedTypeFilter by remember(selectorField) {
        mutableStateOf(allFilterLabel)
    }
    val filteredSelectorOptions = remember(selectorOptions, selectedTypeFilter, allFilterLabel) {
        if (selectedTypeFilter == allFilterLabel) {
            selectorOptions
        } else {
            selectorOptions.filter { it.type == selectedTypeFilter }
        }
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun dismiss(afterDismiss: (() -> Unit)? = null) {
        coroutineScope.launch {
            if (sheetState.isVisible) {
                sheetState.hide()
            }
            onDismiss()
            afterDismiss?.invoke()
        }
    }

    ModalBottomSheet(
        onDismissRequest = { dismiss() },
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.fill_common_account),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = selectorFieldLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { dismiss() }) {
                    Text(stringResource(R.string.close))
                }
            }

            if (selectorOptions.isEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Text(
                        text = stringResource(R.string.no_results),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    availableTypeFilters.forEach { typeFilter ->
                        FilterChip(
                            selected = selectedTypeFilter == typeFilter,
                            onClick = { selectedTypeFilter = typeFilter },
                            label = { Text(typeFilter) }
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredSelectorOptions, key = { it.id }) { option ->
                        CommonAccountSelectorOptionRow(
                            option = option,
                            commonAccountTypeEmail = commonAccountTypeEmail,
                            commonAccountTypePassword = commonAccountTypePassword,
                            commonAccountTypePhone = commonAccountTypePhone,
                            onClick = {
                                dismiss {
                                    onApply(option.content)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CommonAccountSelectorOptionRow(
    option: CommonAccountFillOption,
    commonAccountTypeEmail: String,
    commonAccountTypePassword: String,
    commonAccountTypePhone: String,
    onClick: () -> Unit
) {
    val typeIcon = when (option.type) {
        commonAccountTypeEmail -> MonicaIcons.General.email
        commonAccountTypePassword -> Icons.Default.Lock
        commonAccountTypePhone -> MonicaIcons.General.phone
        else -> Icons.Default.Person
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Icon(
                            imageVector = typeIcon,
                            contentDescription = null,
                            modifier = Modifier.padding(6.dp).size(16.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    Text(
                        text = option.type,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                }
                SuggestionChip(
                    onClick = { },
                    enabled = false,
                    label = { Text(option.type) }
                )
            }
            Text(
                text = option.content,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun InlinePrimarySuggestionCard(
    label: String,
    leadingIcon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer
) {
    Surface(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        color = containerColor,
        contentColor = contentColor,
        tonalElevation = 1.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun buildPasswordScreenInlinePreviewTotpData(
    rawKey: String,
    issuer: String,
    accountName: String
): TotpData? {
    return TotpDataResolver.fromAuthenticatorKey(
        rawKey = rawKey,
        fallbackIssuer = issuer,
        fallbackAccountName = accountName
    )
}

private tailrec fun Context.findPasswordEditorActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findPasswordEditorActivity()
    else -> null
}

/**
 * Common Card Container for grouping fields
 */
@Composable
private fun InfoCard(
    title: String,
    template: Boolean = false,
    flat: Boolean = false,
    content: @Composable () -> Unit
) {
    if (template) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        CompositionLocalProvider(takagi.ru.monica.ui.components.LocalFilledEntryForm provides true,
            takagi.ru.monica.ui.components.LocalStandaloneTemplateFields provides true) { content() }
    }
    else if (flat) Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary)
        CompositionLocalProvider(takagi.ru.monica.ui.components.LocalFilledEntryForm provides true) {
            content()
        }
    } else takagi.ru.monica.ui.components.PasswordEditorSection(title, content)
}

@Composable
private fun PasswordGeneratorDialog(
    commonPasswordOptions: List<CommonAccountFillOption>,
    generatorPreferences: GeneratorPreferences,
    onDismiss: () -> Unit,
    onPasswordGenerated: (String) -> Unit
) {
    takagi.ru.monica.ui.components.CredentialGeneratorSheet(
        username = false,
        suggestions = commonPasswordOptions.map { takagi.ru.monica.ui.components.GeneratorSuggestion(it.type, it.content) },
        preferences = generatorPreferences,
        onDismiss = onDismiss,
        onApply = onPasswordGenerated,
    )
}

@Composable
private fun PasswordFillOptionRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    showDivider: Boolean
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onChange(!checked) }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium
            )
            Checkbox(
                checked = checked,
                onCheckedChange = onChange
            )
        }
        if (showDivider) {
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
            )
        }
    }
}

private fun maskSensitiveContent(content: String): String {
    val value = content.trim()
    if (value.isEmpty()) return ""
    if (value.length <= 2) return "•".repeat(value.length)
    return value.first() + "•".repeat((value.length - 2).coerceAtLeast(0)) + value.last()
}

@Composable
private fun PasswordAppBindingButton(
    hasBindings: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Apps,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (hasBindings) {
                MaterialTheme.colorScheme.primary
            } else {
                LocalContentColor.current
            }
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = stringResource(R.string.bind_app),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PasswordAppBindingChips(
    linkedAppBindings: List<LinkedAppBinding>,
    onOpenSelector: () -> Unit,
    onRemoveBinding: (String) -> Unit
) {
    if (linkedAppBindings.isEmpty()) return

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        linkedAppBindings.forEach { binding ->
            InputChip(
                selected = true,
                onClick = onOpenSelector,
                label = {
                    Text(
                        text = binding.appName.ifBlank { binding.packageName },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Apps,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                },
                trailingIcon = {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.clear_app_selection),
                        modifier = Modifier
                            .size(16.dp)
                            .clickable { onRemoveBinding(binding.packageName) }
                    )
                }
            )
        }
    }
}

/**
 * 登录方式选择器组件
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoginTypeSelector(
    loginType: String,
    ssoProvider: String,
    ssoRefEntryId: Long?,
    allPasswords: List<PasswordEntry>,
    onSsoProviderChange: (String) -> Unit,
    onSsoRefEntryIdChange: (Long?) -> Unit
) {
    val context = LocalContext.current
    var showProviderMenu by remember { mutableStateOf(false) }
    var showRefEntryPicker by remember { mutableStateOf(false) }
    
    // 获取引用的条目信息
    val refEntry = remember(ssoRefEntryId, allPasswords) {
        allPasswords.find { it.id == ssoRefEntryId }
    }
    
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 登录方式标签
        Text(
            text = context.getString(R.string.login_type_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        // SSO 详细设置
        MonicaExpandableContent(
            expanded = loginType.equals("SSO", ignoreCase = true)
        ) {
            Column(
                modifier = Modifier.padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 提供商选择
                ExposedDropdownMenuBox(
                    expanded = showProviderMenu,
                    onExpandedChange = { showProviderMenu = it }
                ) {
                    val providerDisplayName = if (ssoProvider.isNotEmpty()) {
                        takagi.ru.monica.data.SsoProvider.fromName(ssoProvider).localizedName()
                    } else {
                        context.getString(R.string.sso_provider_select)
                    }
                    
                    OutlinedTextField(
                        value = providerDisplayName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(context.getString(R.string.sso_provider_label)) },
                        leadingIcon = { 
                            Icon(
                                imageVector = getSsoProviderIcon(ssoProvider),
                                contentDescription = null
                            )
                        },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showProviderMenu) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    
                    ExposedDropdownMenu(
                        expanded = showProviderMenu,
                        onDismissRequest = { showProviderMenu = false }
                    ) {
                        takagi.ru.monica.data.SsoProvider.entries.forEach { provider ->
                            DropdownMenuItem(
                                text = { Text(provider.localizedName()) },
                                leadingIcon = { Icon(imageVector = getSsoProviderIcon(provider.name), contentDescription = null) },
                                trailingIcon = if (ssoProvider == provider.name) {
                                    { Icon(Icons.Default.Check, null) }
                                } else null,
                                onClick = {
                                    onSsoProviderChange(provider.name)
                                    showProviderMenu = false
                                }
                            )
                        }
                    }
                }
                
                // 关联账号选择
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showRefEntryPicker = true }
                ) {
                    OutlinedTextField(
                        value = refEntry?.let { "${it.title} (${it.username})" } 
                            ?: context.getString(R.string.sso_ref_entry_none),
                        onValueChange = {},
                        readOnly = true,
                        enabled = false,
                        label = { Text(context.getString(R.string.sso_ref_entry_label)) },
                        leadingIcon = { Icon(Icons.Default.Link, null) },
                        trailingIcon = {
                            Row {
                                if (ssoRefEntryId != null) {
                                    IconButton(onClick = { onSsoRefEntryIdChange(null) }) {
                                        Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.clear))
                                    }
                                }
                                Icon(
                                    Icons.Default.Search, 
                                    contentDescription = stringResource(R.string.select),
                                    modifier = Modifier.padding(end = 12.dp)
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            disabledTextColor = MaterialTheme.colorScheme.onSurface,
                            disabledBorderColor = MaterialTheme.colorScheme.outline,
                            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            disabledLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
                
                // 提示文字
                val displayProvider = if (ssoProvider.isNotEmpty()) {
                    takagi.ru.monica.data.SsoProvider.fromName(ssoProvider).localizedName()
                } else {
                    context.getString(R.string.sso_provider_select)
                }

                Text(
                    text = context.getString(R.string.sso_description, displayProvider),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    
    // 关联账号选择对话框
    PasswordEntryPickerBottomSheet(
        visible = showRefEntryPicker,
        title = stringResource(R.string.sso_ref_entry_picker_title),
        passwords = allPasswords.filter {
            it.loginType.equals("PASSWORD", ignoreCase = true) &&
                it.id != ssoRefEntryId &&
                !it.isDeleted &&
                !it.isArchived
        },
        selectedEntryId = ssoRefEntryId,
        onSelect = { entry ->
            onSsoRefEntryIdChange(entry.id)
            showRefEntryPicker = false
        },
        onDismiss = { showRefEntryPicker = false }
    )
}

/**
 * 获取SSO提供商图标
 */
@Composable
private fun getSsoProviderIcon(providerName: String): ImageVector {
    return when (providerName) {
        "GOOGLE" -> Icons.Default.Public
        "APPLE" -> Icons.Default.PhoneIphone
        "FACEBOOK" -> Icons.Default.Facebook
        "MICROSOFT" -> Icons.Default.Computer
        "GITHUB" -> Icons.Default.Code
        "TWITTER" -> Icons.Default.Public
        "WECHAT" -> Icons.Default.Chat
        "QQ" -> Icons.Default.Chat
        "WEIBO" -> Icons.Default.Public
        else -> Icons.Default.Login
    }
}

private fun buildPasswordSiblingGroupKey(entry: PasswordEntry): String = entry.passwordProjectKey()

private fun parsePasswordWebsiteUrls(rawValue: String): List<String> {
    return PasswordWebsiteCodec.parse(rawValue)
}

private fun encodePasswordWebsiteUrls(urls: List<String>): String {
    return PasswordWebsiteCodec.encode(urls)
}

private fun normalizeWebsiteForSiblingGroupKey(value: String): String {
    return PasswordWebsiteCodec.normalizeForKey(value)
}

package takagi.ru.monica.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
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
import takagi.ru.monica.ui.components.buildMultiStorageTarget
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
    var returnLoginType by remember(passwordId) { mutableStateOf(initialLoginType) }
    var gpgMode by remember(passwordId) { mutableStateOf(initialLoginType == "GPG_KEY") }
    var apiKeyMode by remember(passwordId) { mutableStateOf(initialLoginType == takagi.ru.monica.data.model.ApiKeyEntryFields.TYPE) }
    var keyTarget by remember(passwordId) {
        mutableStateOf(takagi.ru.monica.ui.components.buildMultiStorageTarget(initialCategoryId,
            initialKeePassDatabaseId, initialKeePassGroupPath, initialMdbxDatabaseId,
            initialBitwardenVaultId, initialBitwardenFolderId, initialMdbxFolderId))
    }
    var entryTypeLoaded by remember(passwordId) { mutableStateOf(passwordId == null || passwordId <= 0) }
    LaunchedEffect(passwordId) {
        if (passwordId != null && passwordId > 0) {
            val fields = viewModel.getCustomFieldsByEntryIdSync(passwordId)
            gpgMode = fields.any { it.title == takagi.ru.monica.data.model.GpgEntryFields.MARKER && it.value == "GPG_KEY" }
            apiKeyMode = takagi.ru.monica.data.model.ApiKeyEntryFields.isApiKey(fields.associate { it.title to it.value }) ||
                viewModel.getPasswordEntryById(passwordId)?.isApiKeyEntry() == true
        }
        entryTypeLoaded = true
    }
    if (!entryTypeLoaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (apiKeyMode) {
        ApiKeyScreen(passwords = viewModel, passwordId = passwordId?.takeIf { it > 0 },
            initialTarget = keyTarget, onBack = onNavigateBack, onSaved = onSaveCompleted,
            onSelectType = { option ->
                when (option) {
                    EntryTypeChipOption.API_KEY -> Unit
                    EntryTypeChipOption.GPG_KEY -> { apiKeyMode = false; gpgMode = true }
                    EntryTypeChipOption.PASSWORD -> { returnLoginType = "PASSWORD"; apiKeyMode = false }
                    EntryTypeChipOption.BARCODE -> { returnLoginType = LOGIN_TYPE_BARCODE; apiKeyMode = false }
                    EntryTypeChipOption.WIFI -> onSwitchToWifi?.invoke((keyTarget as? StorageTarget.MonicaLocal)?.categoryId)
                    EntryTypeChipOption.SSH_KEY -> onSwitchToSshKey?.invoke((keyTarget as? StorageTarget.MonicaLocal)?.categoryId)
                    EntryTypeChipOption.API_TOKEN -> onSwitchToApiToken?.invoke(keyTarget as? StorageTarget.Mdbx)
                }
            },
            getKeePassGroups = localKeePassViewModel?.let { vm -> vm::getGroups } ?: { flowOf(emptyList()) })
        return
    }
    if (gpgMode) {
        GpgKeyScreen(passwords = viewModel, passwordId = passwordId?.takeIf { it > 0 },
            initialTarget = takagi.ru.monica.ui.components.buildMultiStorageTarget(initialCategoryId,
                initialKeePassDatabaseId, initialKeePassGroupPath, initialMdbxDatabaseId,
                initialBitwardenVaultId, initialBitwardenFolderId, initialMdbxFolderId),
            onBack = onNavigateBack, onSaved = onSaveCompleted,
            onSelectType = { option ->
                when (option) {
                    EntryTypeChipOption.API_KEY -> { gpgMode = false; apiKeyMode = true }
                    EntryTypeChipOption.GPG_KEY -> Unit
                    EntryTypeChipOption.PASSWORD -> { returnLoginType = "PASSWORD"; gpgMode = false }
                    EntryTypeChipOption.WIFI -> onSwitchToWifi?.invoke(null)
                    EntryTypeChipOption.SSH_KEY -> onSwitchToSshKey?.invoke(null)
                    EntryTypeChipOption.API_TOKEN -> onSwitchToApiToken?.invoke(null)
                    EntryTypeChipOption.BARCODE -> { returnLoginType = LOGIN_TYPE_BARCODE; gpgMode = false }
                }
            },
            getKeePassGroups = localKeePassViewModel?.let { vm -> vm::getGroups }
                ?: { flowOf(emptyList()) })
        return
    }
    PasswordEntryEditor(
        viewModel = viewModel,
        totpViewModel = totpViewModel,
        bankCardViewModel = bankCardViewModel,
        noteViewModel = noteViewModel,
        localKeePassViewModel = localKeePassViewModel,
        localMdbxViewModel = localMdbxViewModel,
        mdbxDatabasesFallback = mdbxDatabasesFallback,
        passwordId = passwordId,
        initialDraft = initialDraft,
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
        initialLoginType = returnLoginType,
        onConsumePendingQrResult = onConsumePendingQrResult,
        onScanAuthenticatorQrCode = onScanAuthenticatorQrCode,
        onSaveCompleted = onSaveCompleted,
        onSwitchToApiToken = onSwitchToApiToken,
        onSwitchToWifi = onSwitchToWifi,
        onSwitchToSshKey = onSwitchToSshKey,
        onNavigateBack = onNavigateBack,
        onSwitchToGpg = { gpgMode = true },
        onSwitchToApiKey = { target -> keyTarget = target; apiKeyMode = true }
    )
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
    val settings by settingsManager.settingsFlow.collectAsState(initial = takagi.ru.monica.data.AppSettings())
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
    var username by rememberSaveable { mutableStateOf("") }
    // CHANGE: Support multiple passwords
    val passwords = rememberSaveable(saver = takagi.ru.monica.utils.StringListSaver) { mutableStateListOf("") }
    val credentialUsernames = rememberSaveable(saver = takagi.ru.monica.utils.StringListSaver) {
        mutableStateListOf("")
    }
    var originalIds by remember { mutableStateOf<List<Long>>(emptyList()) }
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
    val hasExplicitInitialStorage = initialStorageExplicit || initialCategoryId != null ||
        initialKeePassDatabaseId != null ||
        initialKeePassGroupPath != null ||
        initialMdbxDatabaseId != null ||
        initialMdbxFolderId != null ||
        initialBitwardenVaultId != null ||
        initialBitwardenFolderId != null
    val selectedStorageTargets = remember { mutableStateListOf<StorageTarget>() }
    var existingReplicaTargetKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var currentReplicaGroupId by rememberSaveable { mutableStateOf<String?>(null) }
    var showStorageTargetSheet by remember { mutableStateOf(false) }
    
    // SSO 登录方式字段
    val defaultLoginType = if (
        passwordId == null &&
        initialLoginType.equals(LOGIN_TYPE_BARCODE, ignoreCase = true)
    ) {
        LOGIN_TYPE_BARCODE
    } else {
        "PASSWORD"
    }
    var loginType by rememberSaveable { mutableStateOf(defaultLoginType) }
    var ssoProvider by rememberSaveable { mutableStateOf("") }
    var ssoRefEntryId by rememberSaveable { mutableStateOf<Long?>(null) }
    var barcodePayload by rememberSaveable { mutableStateOf("") }
    val isPasswordCredentialMode = loginType.equals("PASSWORD", ignoreCase = true) &&
        !loginType.equals(LOGIN_TYPE_BARCODE, ignoreCase = true)
    val canAddIndependentCredential = isPasswordCredentialMode &&
        (!isEditing || originalIds.size == 1)
    val usesCredentialCards = isPasswordCredentialMode &&
        (!isEditing || (originalIds.size == 1 && credentialUsernames.size > 1))
    val isMultiCredentialMode = usesCredentialCards && credentialUsernames.size > 1
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
        authenticatorPayloadOverride
    ) {
        authenticatorPayloadOverride
            ?.takeIf { it.isNotBlank() }
            ?: buildPasswordScreenAuthenticatorPayload(
                secret = authenticatorSecret,
                otpType = selectedAuthenticatorOtpType,
                issuer = title,
                accountName = authenticatorAccountName
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
        enabled = settings.iconCardsEnabled && customIconType == PASSWORD_ICON_TYPE_NONE
    )
    val fallbackWebsiteFavicon = rememberFavicon(
        url = website,
        enabled = settings.iconCardsEnabled &&
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
    var authenticatorTypeExpanded by remember { mutableStateOf(false) }
    val isBarcodeMode = loginType.equals(LOGIN_TYPE_BARCODE, ignoreCase = true)

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
            selectedAuthenticatorOtpTypeName = parsed.otpType.toPasswordScreenOtpType().name
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
                selectedAuthenticatorOtpTypeName = imported.otpType.toPasswordScreenOtpType().name
                authenticatorPayloadOverride = TotpDataResolver.toBitwardenPayload(
                    title = scanResult.item.label,
                    data = imported
                ).takeIf { it.isNotBlank() && it != imported.secret }
                if (title.isBlank()) {
                    title = scanResult.item.label
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
            is takagi.ru.monica.util.TotpScanParseResult.Multiple -> {
                scanResult.items.firstOrNull()?.let { first ->
                    authenticatorEditedByUser = true
                    existingTotpId = null
                    selectedExistingTotpTitle = ""
                    val imported = first.totpData
                    authenticatorSecret = imported.secret
                    selectedAuthenticatorOtpTypeName = imported.otpType.toPasswordScreenOtpType().name
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
        selectedAuthenticatorOtpTypeName = normalized.otpType.toPasswordScreenOtpType().name
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
            if (isBarcodeMode) {
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

    val canSelectUsernameTemplate = !isEditing && !commonAccountInfo.autoFillEnabled &&
        (accountTemplates.isNotEmpty() || emailTemplates.isNotEmpty() || phoneTemplates.isNotEmpty())
    val canSelectEmailTemplate = !isEditing && !commonAccountInfo.autoFillEnabled &&
        emailTemplates.isNotEmpty()
    val canSelectPhoneTemplate = !isEditing && !commonAccountInfo.autoFillEnabled &&
        phoneTemplates.isNotEmpty()
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

        return options
            .filter { it.content.isNotBlank() }
            .distinctBy { "${it.type.trim().lowercase(Locale.ROOT)}|${it.content.trim().lowercase(Locale.ROOT)}" }
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
            accountName = credentialUsernames[index]
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
    
    // 判断字段是否应该显示：设置开启 或 条目已有该字段数据
    fun shouldShowSecurityVerification() =
        !isBarcodeMode && (fieldVisibility.securityVerification || authenticatorSecret.isNotEmpty())
    fun shouldShowCategoryAndNotes() =
        fieldVisibility.categoryAndNotes ||
            credentialScopedNotes.isNotEmpty() ||
            credentialScopedBoundNoteId != null ||
            noteViewModel != null
    fun shouldShowPersonalInfo() =
        !isBarcodeMode && (fieldVisibility.personalInfo ||
            credentialScopedEmails.any { it.isNotEmpty() } ||
            credentialScopedPhones.any { it.isNotEmpty() })
    // 地址信息仅看开关 + 当前条目已有数据；
    // 不再因为「常用账号」里存过账单地址而强制展示，否则用户关了开关仍会看到面板。
    fun shouldShowAddressInfo() =
        !isBarcodeMode && (fieldVisibility.addressInfo ||
            credentialScopedAddressLine.isNotEmpty() || credentialScopedCity.isNotEmpty() ||
            credentialScopedState.isNotEmpty() || credentialScopedZipCode.isNotEmpty() ||
            credentialScopedCountry.isNotEmpty())
    fun shouldShowPaymentInfo() =
        !isBarcodeMode && (fieldVisibility.paymentInfo ||
            credentialScopedCreditCardNumber.isNotEmpty() ||
            credentialScopedCreditCardHolder.isNotEmpty() ||
            credentialScopedCreditCardExpiry.isNotEmpty() ||
            credentialScopedCreditCardCVV.isNotEmpty())
    
    // 新建条目时的自动填充标记（只执行一次）
    var hasAutoFilled by rememberSaveable { mutableStateOf(false) }
    var initialDraftApplied by rememberSaveable(passwordId) { mutableStateOf(false) }

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
                    viewModel.getRawPasswordEntryById(actualId)
                }?.let { rawEntry ->
                    // WIFI 条目走独立编辑页；这里立即重定向，不继续解包普通密码 UI 状态。
                    if (rawEntry.isWifiEntry() && onSwitchToWifi != null) {
                        onSwitchToWifi(actualId)
                        return@launch
                    }
                    // SSH 密钥条目走独立编辑页
                    if (rawEntry.isSshKeyEntry() && onSwitchToSshKey != null) {
                        onSwitchToSshKey(actualId)
                        return@launch
                    }
                    hasOwnershipConflict = viewModel.hasOwnershipConflict(rawEntry)
                    val secretState = withContext(Dispatchers.Default) {
                        viewModel.inspectSecretState(rawEntry)
                    }
                    val entry = rawEntry.copy(
                        password = secretState.plainValueOrEmpty()
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
                        selectedAuthenticatorOtpTypeName = authenticatorDraft.otpType.toPasswordScreenOtpType().name
                        originalAuthenticatorKey = resolvedAuthenticatorKey
                        authenticatorPayloadOverride = resolvedAuthenticatorKey
                            .takeIf { it.isNotBlank() && it != authenticatorDraft.secret }
                        existingTotpId = null
                        selectedExistingTotpTitle = ""
                    }
                    passkeyBindings = entry.passkeyBindings
                    existingSshKeyData = entry.sshKeyData
                    
                    // 加载SSO登录方式字段
                    loginType = when {
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
                            buildPasswordSiblingGroupKey(entry)
                        }
                        val siblings = withContext(Dispatchers.Default) {
                            allEntries.filter { item: PasswordEntry ->
                                val itKey = buildPasswordSiblingGroupKey(item)
                                itKey == key
                            }.sortedBy { it.id }
                        }
                        
                        passwords.clear()
                        if (siblings.isNotEmpty()) {
                            val siblingSecretStates = withContext(Dispatchers.Default) {
                                siblings.map { sibling ->
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
                            originalIds = siblings.map { s: PasswordEntry -> s.id }
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
                            viewModel.getCustomFieldsByEntryIdSync(actualId)
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
                        
                        customFields.addAll(existingDrafts)
                        if (existingDrafts.isNotEmpty()) {
                            customFieldsExpanded = true
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
                 initialDraftApplied = true
             }
             username = credentialUsernames.firstOrNull().orEmpty()
        }
    }

    val canSave = title.isNotEmpty() &&
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
        if (title.isNotEmpty() && !isSaving) {
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
            isSaving = true // 防止重复点击
            val normalizedPasswords = if (isBarcodeMode) {
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
                sshKeyData = existingSshKeyData,
                loginType = if (isBarcodeMode) LOGIN_TYPE_BARCODE else loginType,
                ssoProvider = if (isBarcodeMode) "" else ssoProvider,
                ssoRefEntryId = if (isBarcodeMode) null else ssoRefEntryId,
                customIconType = customIconType,
                customIconValue = normalizedIconFileName(customIconValue),
                customIconUpdatedAt = customIconUpdatedAt
            )

            // 快照自定义字段，并追加“用户名分离”内部转换字段（带标记）
            val currentCustomFields = customFields.toMutableList().apply {
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
                hasSavedSuccessfully = true

                val pendingAttachmentOwners = attachmentDraftOwners.filter { (_, drafts) -> drafts.isNotEmpty() }
                if (pendingAttachmentOwners.isNotEmpty()) {
                    coroutineScope.launch {
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
                        onSaveCompleted?.invoke(primaryPasswordId)
                        onNavigateBack()
                    }
                    return@finishSave
                }
                onSaveCompleted?.invoke(primaryPasswordId)
                onNavigateBack()
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

            val usesIndependentCredentialSave = usesCredentialCards
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
                            customFields = if (isMultiCredentialMode) {
                                metadata.credentialCustomFields.toList()
                            } else {
                                emptyList()
                            }
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
                                            savedPasswordIds,
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
                                firstCredential?.savedPasswordIds.orEmpty(),
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
                    originalIds = originalIds.takeIf { isEditing }.orEmpty(),
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

    LaunchedEffect(
        isEditing,
        currentFilter,
        hasExplicitInitialStorage,
        initialCategoryId,
        initialStorageExplicit,
        initialKeePassDatabaseId,
        initialKeePassGroupPath,
        initialMdbxDatabaseId,
        initialMdbxFolderId,
        initialBitwardenVaultId,
        initialBitwardenFolderId
    ) {
        if (isEditing) return@LaunchedEffect
        existingReplicaTargetKeys = emptySet()
        currentReplicaGroupId = null
        if (hasExplicitInitialStorage) {
            setSelectedStorageTargets(
                listOf(
                    buildMultiStorageTarget(
                        categoryId = initialCategoryId,
                        keepassDatabaseId = initialKeePassDatabaseId,
                        keepassGroupPath = initialKeePassGroupPath,
                        mdbxDatabaseId = initialMdbxDatabaseId,
                        mdbxFolderId = initialMdbxFolderId,
                        bitwardenVaultId = initialBitwardenVaultId,
                        bitwardenFolderId = initialBitwardenFolderId
                    )
                )
            )
            return@LaunchedEffect
        }
        val defaultTarget = when (val filter = currentFilter) {
            is CategoryFilter.Custom -> StorageTarget.MonicaLocal(filter.categoryId)
            is CategoryFilter.KeePassDatabase -> StorageTarget.KeePass(filter.databaseId, null)
            is CategoryFilter.KeePassGroupFilter -> StorageTarget.KeePass(filter.databaseId, filter.groupPath)
            is CategoryFilter.KeePassDatabaseStarred -> StorageTarget.KeePass(filter.databaseId, null)
            is CategoryFilter.KeePassDatabaseUncategorized -> StorageTarget.KeePass(filter.databaseId, null)
            is CategoryFilter.MdbxDatabase -> StorageTarget.Mdbx(filter.databaseId)
            is CategoryFilter.MdbxFolderFilter -> StorageTarget.Mdbx(filter.databaseId, filter.folderId)
            is CategoryFilter.BitwardenVault -> StorageTarget.Bitwarden(filter.vaultId, null)
            is CategoryFilter.BitwardenFolderFilter -> StorageTarget.Bitwarden(filter.vaultId, filter.folderId)
            is CategoryFilter.BitwardenVaultStarred -> StorageTarget.Bitwarden(filter.vaultId, null)
            is CategoryFilter.BitwardenVaultUncategorized -> StorageTarget.Bitwarden(filter.vaultId, null)
            else -> StorageTarget.MonicaLocal(null)
        }
        setSelectedStorageTargets(listOf(defaultTarget))
    }

    val topBarTitle = stringResource(
        when {
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

    Scaffold(
        topBar = {
            TopAppBar(
                    title = {
                        Text(
                            topBarTitle,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(MonicaIcons.Navigation.back, contentDescription = stringResource(R.string.back))
                        }
                    },
                    actions = {
                        if (onSwitchToWifi != null) {
                            EntryTypeChip(
                                showApiToken = !isEditing && onSwitchToApiToken != null,
                                showGpg = !isEditing,
                                showApiKey = !isEditing,
                                current = if (isBarcodeMode) {
                                    EntryTypeChipOption.BARCODE
                                } else {
                                    EntryTypeChipOption.PASSWORD
                                },
                                onSelect = { option ->
                                    when (option) {
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
                                            loginType = LOGIN_TYPE_BARCODE
                                        }
                                        EntryTypeChipOption.PASSWORD -> {
                                            loginType = "PASSWORD"
                                        }
                                    }
                                },
                                enabled = !isEditing
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        IconButton(onClick = { isFavorite = !isFavorite }) {
                            Icon(
                                if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = stringResource(R.string.favorite),
                                tint = if (isFavorite) MaterialTheme.colorScheme.primary else LocalContentColor.current
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    )
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
    ) { paddingValues ->
        val listContentPadding = PaddingValues(bottom = 120.dp)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
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
                        onRemoveTarget = ::removeSelectedStorageTarget
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
                    title = stringResource(
                        if (isBarcodeMode) R.string.entry_type_barcode else R.string.section_credentials
                    )
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (showCommonEditorContent) {
                        // Title
                        if (settings.iconCardsEnabled) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                FilledTonalIconButton(
                                    onClick = { showCustomIconDialog = true },
                                    modifier = Modifier.size(48.dp)
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
                                    value = title,
                                    onValueChange = { title = it },
                                    label = { Text(stringResource(R.string.title_required)) },
                                    leadingIcon = { Icon(Icons.Default.Label, null) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                    shape = RoundedCornerShape(12.dp)
                                )
                            }
                        } else {
                            OutlinedTextField(
                                value = title,
                                onValueChange = { title = it },
                                label = { Text(stringResource(R.string.title_required)) },
                                leadingIcon = { Icon(Icons.Default.Label, null) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                shape = RoundedCornerShape(12.dp)
                            )
                        }

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
                        } else {
                            // Website URLs + App Binding (inline)
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            websiteUrls.forEachIndexed { index, url ->
                                key("website_url_$index") {
                                    var urlMenuExpanded by remember { mutableStateOf(false) }
                                    OutlinedTextField(
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
                                        trailingIcon = {
                                            Box {
                                                IconButton(onClick = { urlMenuExpanded = true }) {
                                                    Icon(
                                                        imageVector = Icons.Default.MoreVert,
                                                        contentDescription = stringResource(R.string.legacy_ui_url_menu)
                                                    )
                                                }
                                                DropdownMenu(
                                                    expanded = urlMenuExpanded,
                                                    onDismissRequest = { urlMenuExpanded = false }
                                                ) {
                                                    if (index > 0) {
                                                        DropdownMenuItem(
                                                            text = { Text(stringResource(R.string.move_up)) },
                                                            leadingIcon = { Icon(Icons.Default.KeyboardArrowUp, null) },
                                                            onClick = {
                                                                val previous = websiteUrls[index - 1]
                                                                websiteUrls[index - 1] = websiteUrls[index]
                                                                websiteUrls[index] = previous
                                                                syncWebsiteFromUrlRows()
                                                                urlMenuExpanded = false
                                                            }
                                                        )
                                                    }
                                                    if (index < websiteUrls.lastIndex) {
                                                        DropdownMenuItem(
                                                            text = { Text(stringResource(R.string.move_down)) },
                                                            leadingIcon = { Icon(Icons.Default.KeyboardArrowDown, null) },
                                                            onClick = {
                                                                val next = websiteUrls[index + 1]
                                                                websiteUrls[index + 1] = websiteUrls[index]
                                                                websiteUrls[index] = next
                                                                syncWebsiteFromUrlRows()
                                                                urlMenuExpanded = false
                                                            }
                                                        )
                                                    }
                                                    DropdownMenuItem(
                                                        text = { Text(stringResource(R.string.delete)) },
                                                        leadingIcon = { Icon(Icons.Default.Delete, null) },
                                                        onClick = {
                                                            if (websiteUrls.size == 1) {
                                                                websiteUrls[0] = ""
                                                            } else {
                                                                websiteUrls.removeAt(index)
                                                            }
                                                            syncWebsiteFromUrlRows()
                                                            urlMenuExpanded = false
                                                        }
                                                    )
                                                }
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Uri,
                                            imeAction = ImeAction.Next
                                        ),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        websiteUrls.add("")
                                        syncWebsiteFromUrlRows()
                                    },
                                    modifier = Modifier.weight(1f),
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
                        if (showCredentialEditorContent && !isBarcodeMode) {
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
                                    label = { Text(stringResource(R.string.field_account)) },
                                    leadingIcon = { Icon(Icons.Default.Person, null) },
                                    trailingIcon = {
                                        Row {
                                            if (canSelectUsernameTemplate) {
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
                                                        Icons.Default.PersonAdd,
                                                        contentDescription = stringResource(R.string.fill_common_account),
                                                        modifier = Modifier.size(20.dp),
                                                        tint = MaterialTheme.colorScheme.primary
                                                    )
                                                }
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
                                    shape = RoundedCornerShape(12.dp)
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
                                        shape = RoundedCornerShape(12.dp)
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
                        if (showCommonEditorContent && !isMultiCredentialMode && !isBarcodeMode) {
                            LoginTypeSelector(
                                loginType = loginType,
                                ssoProvider = ssoProvider,
                                ssoRefEntryId = ssoRefEntryId,
                                allPasswords = allPasswordsForRef,
                                onLoginTypeChange = { loginType = it },
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
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                val visiblePasswordIndices = if (isMultiCredentialMode) {
                                    listOf(selectedCredentialEditorIndex.coerceIn(0, passwords.lastIndex))
                                } else {
                                    passwords.indices.toList()
                                }
                                visiblePasswordIndices.forEach { index ->
                                    val pwd = passwords[index]
                                    val isPasswordVisible = isPasswordFieldVisible(index)
                                    val isUnreadablePassword =
                                        isEditing && originalIds.getOrNull(index) in unreadablePasswordIds
                                    Column {
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
                                                    Row {
                                                        IconButton(onClick = { 
                                                            showPasswordGenerator = true 
                                                            currentPasswordIndexForGenerator = index
                                                        }) {
                                                            Icon(
                                                                Icons.Default.Key,
                                                                contentDescription = stringResource(R.string.password_fill_title)
                                                            )
                                                        }
                                                        IconButton(onClick = { togglePasswordFieldVisibility(index) }) {
                                                            Icon(
                                                                imageVector = if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                                contentDescription = null
                                                            )
                                                        }
                                                        // Allow removing only if more than 1
                                                        if (!isMultiCredentialMode && passwords.size > 1) {
                                                            IconButton(onClick = { removePasswordFieldAt(index) }) {
                                                                Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                                            }
                                                        }
                                                    }
                                                },
                                                modifier = Modifier
                                                    .weight(1f)
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
                                                shape = RoundedCornerShape(12.dp),
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
                                        if (pwd.isNotEmpty()) {
                                            val strength = PasswordStrengthAnalyzer.calculateStrength(pwd)
                                            PasswordStrengthIndicator(
                                                strength = strength,
                                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)
                                            )
                                        }
                                    }
                                }

                                // 旧的“一个条目多个密码”编辑能力继续保留；批量凭据每页固定一个密码。
                                if (!isMultiCredentialMode) {
                                    OutlinedButton(
                                        onClick = { passwords.add("") },
                                        modifier = Modifier.fillMaxWidth(),
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

            // Security Card (TOTP) - 根据设置和数据决定是否显示
            if (showCredentialEditorContent && shouldShowSecurityVerification()) {
                item {
                    InfoCard(title = stringResource(R.string.section_security_verification)) {
                        Column {
                            OutlinedTextField(
                                value = authenticatorSecret,
                                onValueChange = ::applyAuthenticatorInput,
                                label = { Text(stringResource(R.string.authenticator_key_optional)) },
                                placeholder = { Text(stringResource(R.string.authenticator_key_hint)) },
                                leadingIcon = { Icon(Icons.Default.VpnKey, null) },
                                trailingIcon = {
                                    if (onScanAuthenticatorQrCode != null) {
                                        IconButton(onClick = onScanAuthenticatorQrCode) {
                                            Icon(
                                                imageVector = Icons.Default.QrCodeScanner,
                                                contentDescription = stringResource(R.string.scan_qr_code)
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                supportingText = {
                                    if (selectedAuthenticatorOtpType == OtpType.STEAM) {
                                        Text(stringResource(R.string.steam_uses_5_chars))
                                    }
                                },
                                shape = RoundedCornerShape(12.dp)
                            )

                            ExposedDropdownMenuBox(
                                expanded = authenticatorTypeExpanded,
                                onExpandedChange = { authenticatorTypeExpanded = it }
                            ) {
                                OutlinedTextField(
                                    value = when (selectedAuthenticatorOtpType) {
                                        OtpType.STEAM -> stringResource(R.string.otp_type_steam)
                                        else -> stringResource(R.string.otp_type_totp)
                                    },
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text(stringResource(R.string.otp_type)) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (selectedAuthenticatorOtpType == OtpType.STEAM) {
                                                Icons.Default.Games
                                            } else {
                                                Icons.Default.Shield
                                            },
                                            contentDescription = null
                                        )
                                    },
                                    trailingIcon = {
                                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = authenticatorTypeExpanded)
                                    },
                                    modifier = Modifier
                                        .menuAnchor()
                                        .fillMaxWidth()
                                        .padding(top = 10.dp),
                                    shape = RoundedCornerShape(12.dp)
                                )

                                ExposedDropdownMenu(
                                    expanded = authenticatorTypeExpanded,
                                    onDismissRequest = { authenticatorTypeExpanded = false }
                                ) {
                                    listOf(
                                        OtpType.TOTP to R.string.otp_type_totp,
                                        OtpType.STEAM to R.string.otp_type_steam
                                    ).forEach { (type, labelRes) ->
                                        DropdownMenuItem(
                                            text = { Text(stringResource(labelRes)) },
                                            onClick = {
                                                authenticatorEditedByUser = true
                                                selectedAuthenticatorOtpTypeName = type.name
                                                existingTotpId = null
                                                selectedExistingTotpTitle = ""
                                                authenticatorPayloadOverride = null
                                                authenticatorTypeExpanded = false
                                            }
                                        )
                                    }
                                }
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
                                        modifier = Modifier.padding(top = 10.dp),
                                        showHeader = false,
                                        showProgress = false
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Organization Card - 根据设置和数据决定是否显示
            if (showCredentialEditorContent && shouldShowCategoryAndNotes()) {
                item {
                    InfoCard(title = stringResource(R.string.notes)) {
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
                                label = { Text(stringResource(R.string.notes)) },
                                leadingIcon = { Icon(Icons.Default.Edit, null) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(88.dp),
                                maxLines = 4,
                                shape = RoundedCornerShape(12.dp)
                            )

                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                            Text(
                                text = stringResource(R.string.password_bound_note_title),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            if (selectedBoundNote != null) {
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
                                    Text(stringResource(R.string.bind_note))
                                }
                            }
                        }
                    }
                }
            }  // 分类与备注 if 结束
            
            if (!isBarcodeMode && showCommonEditorContent) {
                // 自定义字段区域标题 (带添加按钮)
                item {
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

                // 自定义字段编辑卡片 (独立卡片样式)
                items(customFields.size) { index ->
                    val field = customFields[index]
                    CustomFieldEditCard(
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

            if (!isBarcodeMode && isMultiCredentialMode && showCredentialEditorContent) {
                item {
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

                items(activeCredentialMetadata.credentialCustomFields.size) { index ->
                    val field = activeCredentialMetadata.credentialCustomFields[index]
                    CustomFieldEditCard(
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

            if (!isBarcodeMode && showCredentialEditorContent) {
                // 附件区块：批量模式下每个凭据页维护自己的附件草稿。
                item {
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

            // Collapsible: Personal Info - 根据设置和数据决定是否显示
            if (showCredentialEditorContent && shouldShowPersonalInfo()) {
                item {
                    MonicaExpandableCard(
                        title = stringResource(R.string.personal_info),
                        icon = Icons.Default.Person,
                        expanded = personalInfoExpanded,
                        onExpandedChange = { personalInfoExpanded = it }
                    ) {
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
            }  // Personal Info if 结束

            // Collapsible: Address Info - 根据设置和数据决定是否显示
            if (showCredentialEditorContent && shouldShowAddressInfo()) {
                item {
                    MonicaExpandableCard(
                        title = stringResource(R.string.address_info),
                        icon = Icons.Default.Home,
                        expanded = addressInfoExpanded,
                        onExpandedChange = { addressInfoExpanded = it }
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (!commonAccountInfo.billingAddress.isEmpty()) {
                                OutlinedButton(
                                    onClick = {
                                        applyCommonBillingAddress(
                                            commonAccountInfo.billingAddress,
                                            target = activeCredentialMetadata.takeIf { isMultiCredentialMode }
                                        )
                                        addressInfoExpanded = true
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.common_account_billing_filled),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.AccountCircle, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.common_account_billing_use_saved))
                                }
                            }
                            OutlinedTextField(
                                value = credentialScopedAddressLine,
                                onValueChange = { value ->
                                    if (isMultiCredentialMode) {
                                        activeCredentialMetadata.addressLine = value
                                    } else {
                                        addressLine = value
                                    }
                                },
                                label = { Text(stringResource(R.string.field_address)) },
                                leadingIcon = { Icon(Icons.Default.Home, null) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = credentialScopedCity,
                                onValueChange = { value ->
                                    if (isMultiCredentialMode) activeCredentialMetadata.city = value else city = value
                                },
                                label = { Text(stringResource(R.string.field_city)) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp)
                            )
                            OutlinedTextField(
                                value = credentialScopedState,
                                onValueChange = { value ->
                                    if (isMultiCredentialMode) activeCredentialMetadata.state = value else state = value
                                },
                                label = { Text(stringResource(R.string.field_state)) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = credentialScopedZipCode,
                                onValueChange = { value ->
                                    if (value.all { char -> char.isDigit() } && value.length <= 6) {
                                        if (isMultiCredentialMode) {
                                            activeCredentialMetadata.zipCode = value
                                        } else {
                                            zipCode = value
                                        }
                                    }
                                },
                                label = { Text(stringResource(R.string.field_postal_code)) },
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp)
                            )
                            OutlinedTextField(
                                value = credentialScopedCountry,
                                onValueChange = { value ->
                                    if (isMultiCredentialMode) activeCredentialMetadata.country = value else country = value
                                },
                                label = { Text(stringResource(R.string.field_country)) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp)
                            )
                        }
                    }
                }
            }
            }  // Address Info if 结束

            // Collapsible: Payment Info
            if (showCredentialEditorContent && shouldShowPaymentInfo()) {
            item {
                MonicaExpandableCard(
                    title = stringResource(R.string.payment_info),
                    icon = Icons.Default.CreditCard,
                    expanded = paymentInfoExpanded,
                    onExpandedChange = { paymentInfoExpanded = it }
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
                                AlertDialog(
                                    onDismissRequest = { showBankCardDialog = false },
                                    title = { Text(stringResource(R.string.select_bank_card)) },
                                    text = {
                                        if (bankCards.isEmpty()) Text(stringResource(R.string.no_bank_card_data))
                                        else LazyColumn {
                                            items(bankCards) { parsedCard ->
                                                val item = parsedCard.item
                                                val cardData = parsedCard.cardData
                                                ListItem(
                                                    headlineContent = { Text(item.title) },
                                                    supportingContent = { Text(stringResource(R.string.tail_number_last4, cardData.cardNumber.takeLast(4))) },
                                                    leadingContent = { Icon(Icons.Default.CreditCard, null) },
                                                    modifier = Modifier.clickable {
                                                        val month = cardData.expiryMonth.padStart(2, '0')
                                                        val year = if (cardData.expiryYear.length == 4) cardData.expiryYear.takeLast(2) else cardData.expiryYear
                                                        if (isMultiCredentialMode) {
                                                            activeCredentialMetadata.creditCardNumber = cardData.cardNumber
                                                            activeCredentialMetadata.creditCardHolder = cardData.cardholderName
                                                            activeCredentialMetadata.creditCardCVV = cardData.cvv
                                                            activeCredentialMetadata.creditCardExpiry = "$month/$year"
                                                        } else {
                                                            creditCardNumber = cardData.cardNumber
                                                            creditCardHolder = cardData.cardholderName
                                                            creditCardCVV = cardData.cvv
                                                            creditCardExpiry = "$month/$year"
                                                        }
                                                        showBankCardDialog = false
                                                        Toast.makeText(context, context.getString(R.string.imported), Toast.LENGTH_SHORT).show()
                                                    }
                                                )
                                            }
                                        }
                                    },
                                    confirmButton = { TextButton(onClick = { showBankCardDialog = false }) { Text(stringResource(R.string.cancel)) } }
                                )
                            }
                        }

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
                            modifier = Modifier.fillMaxWidth(),
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
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = credentialScopedCreditCardExpiry,
                                onValueChange = {
                                    val digits = it.filter { char -> char.isDigit() }
                                    val formatted = when {
                                        digits.length <= 2 -> digits
                                        digits.length <= 4 -> "${digits.substring(0, 2)}/${digits.substring(2)}"
                                        else -> "${digits.substring(0, 2)}/${digits.substring(2, 4)}"
                                    }
                                    if (isMultiCredentialMode) {
                                        activeCredentialMetadata.creditCardExpiry = formatted
                                    } else {
                                        creditCardExpiry = formatted
                                    }
                                },
                                label = { Text(stringResource(R.string.field_expiry)) },
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp)
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
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                visualTransformation = PasswordVisualTransformation()
                            )
                        }
                    }
                }
            }
            }  // Payment Info if 结束
        }
        }
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

    NotePickerBottomSheet(
        visible = showBoundNotePicker,
        title = stringResource(R.string.note_picker_title),
        notes = selectableNotes,
        selectedNoteId = credentialScopedBoundNoteId,
        onSelect = { note ->
            if (isMultiCredentialMode) {
                activeCredentialMetadata.boundNoteId = note.id
            } else {
                boundNoteId = note.id
            }
            showBoundNotePicker = false
        },
        onDismiss = { showBoundNotePicker = false }
    )

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

private data class PasswordScreenAuthenticatorDraft(
    val secret: String,
    val otpType: OtpType
)

private fun resolvePasswordScreenAuthenticatorDraft(
    rawKey: String,
    fallbackIssuer: String = "",
    fallbackAccountName: String = ""
): PasswordScreenAuthenticatorDraft {
    val resolved = TotpDataResolver.fromAuthenticatorKey(
        rawKey = rawKey,
        fallbackIssuer = fallbackIssuer,
        fallbackAccountName = fallbackAccountName
    )
    return if (resolved != null) {
        PasswordScreenAuthenticatorDraft(
            secret = resolved.secret,
            otpType = resolved.otpType
        )
    } else {
        PasswordScreenAuthenticatorDraft(
            secret = rawKey.trim(),
            otpType = OtpType.TOTP
        )
    }
}

private fun buildPasswordScreenAuthenticatorPayload(
    secret: String,
    otpType: OtpType,
    issuer: String,
    accountName: String
): String {
    val normalizedSecret = TotpDataResolver.normalizeBase32Secret(secret)
    if (normalizedSecret.isBlank()) return ""
    if (otpType == OtpType.TOTP) return normalizedSecret

    return TotpDataResolver.toBitwardenPayload(
        title = issuer,
        data = TotpData(
            secret = normalizedSecret,
            issuer = issuer.trim(),
            accountName = accountName.trim(),
            otpType = otpType,
            digits = if (otpType == OtpType.STEAM) 5 else 6,
            period = 30
        )
    )
}

private fun OtpType.toPasswordScreenOtpType(): OtpType {
    return if (this == OtpType.STEAM) OtpType.STEAM else OtpType.TOTP
}

/**
 * Common Card Container for grouping fields
 */
@Composable
private fun InfoCard(
    title: String,
    content: @Composable () -> Unit
) {
    takagi.ru.monica.ui.components.PasswordEditorSection(title, content)
}

@Composable
private fun PasswordGeneratorDialog(
    commonPasswordOptions: List<CommonAccountFillOption>,
    generatorPreferences: GeneratorPreferences,
    onDismiss: () -> Unit,
    onPasswordGenerated: (String) -> Unit
) {
    val defaultOptions = remember(generatorPreferences) {
        generatorPreferences.toSymbolPasswordGeneratorOptions()
    }
    val configuration = LocalConfiguration.current
    val contentViewportHeight = remember(configuration.screenHeightDp) {
        (configuration.screenHeightDp.dp * 0.46f).coerceIn(280.dp, 420.dp)
    }
    val generatorScrollState = rememberScrollState()
    var length by remember(defaultOptions) { mutableIntStateOf(defaultOptions.length) }
    var includeUppercase by remember(defaultOptions) { mutableStateOf(defaultOptions.includeUppercase) }
    var includeLowercase by remember(defaultOptions) { mutableStateOf(defaultOptions.includeLowercase) }
    var includeNumbers by remember(defaultOptions) { mutableStateOf(defaultOptions.includeNumbers) }
    var includeSymbols by remember(defaultOptions) { mutableStateOf(defaultOptions.includeSymbols) }
    var excludeSimilar by remember(defaultOptions) { mutableStateOf(defaultOptions.excludeSimilar) }
    var generatedPassword by remember { mutableStateOf("") }
    var fillMode by remember { mutableStateOf(PasswordFillMode.GENERATOR) }
    
    // Helper to generate
    fun generate() {
        try {
            generatedPassword = AdvancedPasswordGenerator.generatePassword(
                length = length,
                includeUppercase = includeUppercase,
                includeLowercase = includeLowercase,
                includeNumbers = includeNumbers,
                includeSymbols = includeSymbols,
                allowedSymbols = defaultOptions.allowedSymbols,
                excludeSimilar = excludeSimilar,
                excludeAmbiguous = defaultOptions.excludeAmbiguous,
                uppercaseMin = defaultOptions.uppercaseMin,
                lowercaseMin = defaultOptions.lowercaseMin,
                numbersMin = defaultOptions.numbersMin,
                symbolsMin = defaultOptions.symbolsMin
            )
        } catch (e: Exception) {}
    }

    LaunchedEffect(defaultOptions) { generate() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .navigationBarsPadding()
                .imePadding(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Key,
                                    contentDescription = null,
                                    modifier = Modifier.padding(8.dp).size(16.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            Text(
                                text = stringResource(R.string.password_fill_title),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            text = stringResource(R.string.password_fill_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.close)
                        )
                    }
                }

                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SegmentedButton(
                        selected = fillMode == PasswordFillMode.GENERATOR,
                        onClick = { fillMode = PasswordFillMode.GENERATOR },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                    ) {
                        Text(stringResource(R.string.password_fill_mode_generator))
                    }
                    SegmentedButton(
                        selected = fillMode == PasswordFillMode.COMMON_ACCOUNT,
                        onClick = { fillMode = PasswordFillMode.COMMON_ACCOUNT },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                    ) {
                        Text(stringResource(R.string.password_fill_mode_common))
                    }
                }

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(contentViewportHeight),
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Transparent
                ) {
                    when (fillMode) {
                        PasswordFillMode.GENERATOR -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(generatorScrollState),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedTextField(
                                    value = generatedPassword,
                                    onValueChange = { },
                                    readOnly = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    textStyle = MaterialTheme.typography.titleMedium.copy(
                                        letterSpacing = 0.2.sp
                                    ),
                                    trailingIcon = {
                                        FilledTonalIconButton(onClick = { generate() }) {
                                            Icon(
                                                Icons.Default.Refresh,
                                                contentDescription = stringResource(R.string.generate_password)
                                            )
                                        }
                                    },
                                    shape = RoundedCornerShape(14.dp)
                                )

                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.length_value, length),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Slider(
                                            value = length.toFloat(),
                                            onValueChange = { length = it.toInt(); generate() },
                                            valueRange = 8f..32f,
                                            steps = 24
                                        )
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainer
                                ) {
                                    Column {
                                        PasswordFillOptionRow(
                                            title = stringResource(R.string.uppercase_az),
                                            checked = includeUppercase,
                                            onChange = { includeUppercase = it; generate() },
                                            showDivider = true
                                        )
                                        PasswordFillOptionRow(
                                            title = stringResource(R.string.lowercase_az),
                                            checked = includeLowercase,
                                            onChange = { includeLowercase = it; generate() },
                                            showDivider = true
                                        )
                                        PasswordFillOptionRow(
                                            title = stringResource(R.string.numbers_09),
                                            checked = includeNumbers,
                                            onChange = { includeNumbers = it; generate() },
                                            showDivider = true
                                        )
                                        PasswordFillOptionRow(
                                            title = stringResource(R.string.symbols),
                                            checked = includeSymbols,
                                            onChange = { includeSymbols = it; generate() },
                                            showDivider = true
                                        )
                                        PasswordFillOptionRow(
                                            title = stringResource(R.string.exclude_similar),
                                            checked = excludeSimilar,
                                            onChange = { excludeSimilar = it; generate() },
                                            showDivider = false
                                        )
                                    }
                                }
                            }
                        }

                        PasswordFillMode.COMMON_ACCOUNT -> {
                            if (commonPasswordOptions.isEmpty()) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.password_fill_no_templates),
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = stringResource(R.string.password_fill_no_templates_hint),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(commonPasswordOptions, key = { it.id }) { option ->
                                        OutlinedCard(
                                            onClick = { onPasswordGenerated(option.content) },
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(14.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Surface(
                                                    shape = RoundedCornerShape(10.dp),
                                                    color = MaterialTheme.colorScheme.secondaryContainer
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Lock,
                                                        contentDescription = null,
                                                        modifier = Modifier.padding(6.dp).size(16.dp),
                                                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                                                    )
                                                }
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = option.type,
                                                        style = MaterialTheme.typography.titleSmall,
                                                        fontWeight = FontWeight.Medium
                                                    )
                                                    Text(
                                                        text = maskSensitiveContent(option.content),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                                Text(
                                                    text = stringResource(R.string.use_password),
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                    if (fillMode == PasswordFillMode.GENERATOR) {
                        FilledTonalButton(onClick = { onPasswordGenerated(generatedPassword) }) {
                            Text(stringResource(R.string.use_password))
                        }
                    }
                }
            }
        }
    }
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
    OutlinedButton(
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
    onLoginTypeChange: (String) -> Unit,
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
        
        // 登录方式切换
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth()
        ) {
            SegmentedButton(
                selected = loginType.equals("PASSWORD", ignoreCase = true),
                onClick = { onLoginTypeChange("PASSWORD") },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                icon = { 
                    if (loginType.equals("PASSWORD", ignoreCase = true)) {
                        Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp))
                    }
                }
            ) {
                Text(context.getString(R.string.login_type_password))
            }
            SegmentedButton(
                selected = loginType.equals("SSO", ignoreCase = true),
                onClick = { onLoginTypeChange("SSO") },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                icon = { 
                    if (loginType.equals("SSO", ignoreCase = true)) {
                        Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp))
                    }
                }
            ) {
                Text(context.getString(R.string.login_type_sso))
            }
        }
        
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

private fun buildPasswordSiblingGroupKey(entry: PasswordEntry): String {
    entry.replicaGroupId
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { replicaGroupId ->
            return "replica:$replicaGroupId|target:${entry.toStorageTarget().stableKey}"
        }
    val sourceKey = when {
        !entry.bitwardenCipherId.isNullOrBlank() ->
            "bw:${entry.bitwardenVaultId}:${entry.bitwardenCipherId}"
        entry.bitwardenVaultId != null ->
            "bw-local:${entry.bitwardenVaultId}:${entry.bitwardenFolderId.orEmpty()}"
        entry.keepassDatabaseId != null ->
            "kp:${entry.keepassDatabaseId}:${entry.keepassGroupPath.orEmpty()}"
        else -> "local:${entry.categoryId ?: "root"}"
    }

    val title = entry.title.trim().lowercase(Locale.ROOT)
    val username = entry.username.trim().lowercase(Locale.ROOT)
    val website = normalizeWebsiteForSiblingGroupKey(entry.website)

    return "$sourceKey|$title|$website|$username"
}

private fun parsePasswordWebsiteUrls(rawValue: String): List<String> {
    return PasswordWebsiteCodec.parse(rawValue)
}

private fun encodePasswordWebsiteUrls(urls: List<String>): String {
    return PasswordWebsiteCodec.encode(urls)
}

private fun normalizeWebsiteForSiblingGroupKey(value: String): String {
    return PasswordWebsiteCodec.normalizeForKey(value)
}

package takagi.ru.monica.ui.screens

import takagi.ru.monica.ui.components.localizedName

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import takagi.ru.monica.R
import takagi.ru.monica.ui.components.MonicaExpandableContent
import takagi.ru.monica.ui.components.MonicaExpansionChevron
import takagi.ru.monica.ui.components.animateMonicaContentSize
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.input.ImeAction
import androidx.fragment.app.FragmentActivity
import takagi.ru.monica.utils.BiometricHelper
import takagi.ru.monica.utils.ClipboardUtils
import takagi.ru.monica.utils.PasskeySupportCatalog
import takagi.ru.monica.utils.PasswordWebsiteCodec
import takagi.ru.monica.utils.PasswordStrengthAnalyzer
import takagi.ru.monica.utils.decodeKeePassPathForDisplay

import takagi.ru.monica.ui.components.ActionStrip
import takagi.ru.monica.ui.components.ActionStripItem
import takagi.ru.monica.ui.icons.MonicaIcons
import takagi.ru.monica.data.AppSettings
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.linkedAppBindings
import takagi.ru.monica.data.primaryLinkedAppPackageName
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordHistoryEntry
import takagi.ru.monica.data.UnmatchedIconHandlingStrategy
import takagi.ru.monica.notes.domain.NoteContentCodec
import takagi.ru.monica.ui.components.M3IdentityVerifyDialog
import takagi.ru.monica.ui.components.NotePickerBottomSheet
import takagi.ru.monica.utils.FieldValidation
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.domain.provider.PasswordSource
import takagi.ru.monica.viewmodel.CategoryFilter
import takagi.ru.monica.viewmodel.BitwardenRecoveryResult
import takagi.ru.monica.viewmodel.BitwardenSyncRawHistoryItem
import takagi.ru.monica.viewmodel.BitwardenSyncSnapshotFieldPreview
import takagi.ru.monica.viewmodel.BitwardenSyncSnapshotPreview
import takagi.ru.monica.viewmodel.BitwardenSyncSnapshotPreviewStatus
import takagi.ru.monica.viewmodel.NoteViewModel
import takagi.ru.monica.viewmodel.PasswordViewModel
import takagi.ru.monica.viewmodel.PasskeyViewModel
import takagi.ru.monica.util.TotpGenerator
import takagi.ru.monica.data.model.TotpData
import takagi.ru.monica.data.model.PasskeyBinding
import takagi.ru.monica.data.model.PasskeyBindingCodec
import takagi.ru.monica.ui.model.SecretValueState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import takagi.ru.monica.ui.components.InfoField
import takagi.ru.monica.ui.components.InfoFieldWithCopy
import takagi.ru.monica.ui.components.PasswordField
import takagi.ru.monica.ui.components.PasswordFieldActionMenuHost
import takagi.ru.monica.ui.components.rememberPasswordFieldActionMenuState
import takagi.ru.monica.data.CustomField
import takagi.ru.monica.data.LoginType
import takagi.ru.monica.data.SsoProvider
import takagi.ru.monica.data.bitwarden.BitwardenFolder
import takagi.ru.monica.attachments.AttachmentContainer
import takagi.ru.monica.attachments.facade.AttachmentFacade
import takagi.ru.monica.ui.icons.UnmatchedIconFallback
import takagi.ru.monica.ui.icons.rememberAutoMatchedSimpleIcon
import takagi.ru.monica.ui.icons.rememberSimpleIconBitmap
import takagi.ru.monica.ui.icons.rememberUploadedPasswordIcon
import takagi.ru.monica.ui.icons.shouldShowFallbackSlot
import takagi.ru.monica.autofill_ng.ui.rememberAppIcon
import takagi.ru.monica.autofill_ng.ui.rememberFavicon
import takagi.ru.monica.ui.password.BitwardenSyncSnapshotSection
import takagi.ru.monica.ui.password.getPasswordInfoKey
import kotlinx.coroutines.flow.flowOf
import java.text.DateFormat
import java.util.Locale

private const val MONICA_USERNAME_ALIAS_FIELD_TITLE = "__monica_username_alias"
private const val MONICA_USERNAME_ALIAS_META_FIELD_TITLE = "__monica_username_alias_meta"
private const val MONICA_USERNAME_ALIAS_META_VALUE = "migrated_v1"
private const val MONICA_MANUAL_STACK_GROUP_FIELD_TITLE = "__monica_manual_stack_group"
private const val MONICA_NO_STACK_FIELD_TITLE = "__monica_no_stack"

private data class PasswordStorageInfo(
    val entryId: Long,
    val containerKey: String,
    val locationKey: String,
    val source: String,
    val database: String,
    val folderPath: String
)

private fun resolvePasswordDetailGroupPasswords(
    entry: PasswordEntry,
    allPasswords: List<PasswordEntry>
): List<PasswordEntry> {
    val replicaGroupId = entry.replicaGroupId?.takeIf { it.isNotBlank() }
    val key = getPasswordInfoKey(entry)
    val currentAll = if (allPasswords.isEmpty()) {
        listOf(entry)
    } else {
        allPasswords.map { candidate ->
            if (candidate.id == entry.id) entry else candidate
        }
    }
    return currentAll.filter {
        if (replicaGroupId != null) {
            it.replicaGroupId == replicaGroupId
        } else {
            getPasswordInfoKey(it) == key
        }
    }.sortedWith(
        compareBy<PasswordEntry> {
            when {
                it.isLocalOnlyEntry() -> 0
                it.isKeePassEntry() -> 1
                it.isMdbxEntry() -> 2
                else -> 3
            }
        }.thenBy { it.keepassDatabaseId ?: Long.MAX_VALUE }
            .thenBy { it.mdbxDatabaseId ?: Long.MAX_VALUE }
            .thenBy { it.bitwardenVaultId ?: Long.MAX_VALUE }
            .thenBy { it.id }
    )
}

/**
 * 密码详情页 (Password Detail Screen)
 * 
 * ## Material Design 3 动态主题支持
 * - 所有颜色均使用 MaterialTheme.colorScheme 语义化颜色
 * - 支持 Dynamic Color (Material You) - 根据用户壁纸自动适配
 * - 严禁硬编码任何 Hex 颜色值
 * 
 * ## UI 结构
 * - Scaffold 背景: MaterialTheme.colorScheme.surface
 * - 头部大图标: 居中显示密码/网站图标
 * - 2FA 卡片: primaryContainer + onPrimaryContainer
 * - 信息字段: surfaceContainerHigh + primary 标签
 * 
 * @param viewModel PasswordViewModel 实例
 * @param passwordId 密码条目 ID
 * @param onNavigateBack 返回导航回调
 * @param onEditPassword 编辑密码回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordDetailScreen(
    viewModel: PasswordViewModel,
    passkeyViewModel: PasskeyViewModel? = null,
    noteViewModel: NoteViewModel? = null,
    passwordId: Long,
    biometricEnabled: Boolean,
    iconCardsEnabled: Boolean = false,
    unmatchedIconHandlingStrategy: UnmatchedIconHandlingStrategy = UnmatchedIconHandlingStrategy.DEFAULT_ICON,
    @Suppress("UNUSED_PARAMETER") enableSharedBounds: Boolean = true,
    onNavigateBack: () -> Unit,
    onOpenBoundNote: (Long) -> Unit = {},
    onOpenPassword: (Long) -> Unit = {},
    onCreateSend: ((title: String, text: String) -> Unit)? = null,
    onEditPassword: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val settingsManager = remember { SettingsManager(context) }
    val settings by settingsManager.settingsFlow.collectAsState(initial = AppSettings())
    val database = remember { PasswordDatabase.getDatabase(context.applicationContext) }
    val categories by viewModel.categories.collectAsState(initial = emptyList())
    val keepassDatabases by database.localKeePassDatabaseDao().getAllDatabases().collectAsState(initial = emptyList())
    val mdbxDatabases by database.localMdbxDatabaseDao().getAvailableDatabases().collectAsState(initial = emptyList())
    val bitwardenVaults by database.bitwardenVaultDao().getAllVaultsFlow().collectAsState(initial = emptyList())
    var isLeavingDetail by remember { mutableStateOf(false) }
    fun requestNavigateBack() {
        if (isLeavingDetail) return
        isLeavingDetail = true
        onNavigateBack()
    }
    BackHandler(onBack = ::requestNavigateBack)
    
    // 密码条目状态
    var passwordEntry by remember { mutableStateOf<PasswordEntry?>(null) }
    var groupPasswords by remember { mutableStateOf<List<PasswordEntry>>(emptyList()) }
    var displayPasswords by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    var initialDetailDataLoaded by remember { mutableStateOf(false) }
    var bitwardenFoldersByVault by remember {
        mutableStateOf<Map<Long, List<BitwardenFolder>>>(emptyMap())
    }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showArchiveDialog by remember { mutableStateOf(false) }
    var itemToDelete by remember { mutableStateOf<PasswordEntry?>(null) } // For specific password deletion
    var historyItemToDelete by remember { mutableStateOf<PasswordHistoryEntry?>(null) }
    var showClearHistoryDialog by remember { mutableStateOf(false) }
    
    // Verification State
    var showMasterPasswordDialog by remember { mutableStateOf(false) }
    var masterPasswordInput by remember { mutableStateOf("") }
    var passwordVerificationError by remember { mutableStateOf(false) }
    
    // 自定义字段状态
    var customFields by remember { mutableStateOf<List<CustomField>>(emptyList()) }
    val passwordHistory by viewModel.getPasswordHistoryFlow(passwordId).collectAsState(initial = emptyList())
    val passwordHistoryVisibility = remember { mutableStateMapOf<Long, Boolean>() }
    val bitwardenSyncRawHistoryFlow = remember(
        viewModel,
        passwordEntry?.bitwardenVaultId,
        passwordEntry?.bitwardenCipherId
    ) {
        val vaultId = passwordEntry?.bitwardenVaultId
        val cipherId = passwordEntry?.bitwardenCipherId
        if (vaultId != null && !cipherId.isNullOrBlank()) {
            viewModel.getBitwardenSyncRawHistoryFlow(vaultId, cipherId)
        } else {
            flowOf(emptyList())
        }
    }
    val bitwardenSyncRawHistory by bitwardenSyncRawHistoryFlow.collectAsState(initial = emptyList())
    val customFieldVisibility = remember { mutableStateMapOf<Long, Boolean>() }
    val usernameAliasFallbackTitle = stringResource(R.string.autofill_username)
    val hasAliasMeta = customFields.any {
        it.title == MONICA_USERNAME_ALIAS_META_FIELD_TITLE && it.value == MONICA_USERNAME_ALIAS_META_VALUE
    }
    val separatedUsername = customFields.firstOrNull {
        it.title == MONICA_USERNAME_ALIAS_FIELD_TITLE ||
            (hasAliasMeta && it.title == usernameAliasFallbackTitle)
    }?.value?.trim().orEmpty()
    val displayCustomFields = remember(
        customFields,
        settings.separateUsernameAccountEnabled,
        hasAliasMeta,
        usernameAliasFallbackTitle
    ) {
        customFields
            .asSequence()
            .filterNot {
                it.title == MONICA_MANUAL_STACK_GROUP_FIELD_TITLE ||
                    it.title == MONICA_NO_STACK_FIELD_TITLE
            }
            .filterNot { it.title == MONICA_USERNAME_ALIAS_META_FIELD_TITLE }
            .filterNot {
                settings.separateUsernameAccountEnabled &&
                    (it.title == MONICA_USERNAME_ALIAS_FIELD_TITLE ||
                        (hasAliasMeta && it.title == usernameAliasFallbackTitle))
            }
            .map { field ->
                if (!settings.separateUsernameAccountEnabled &&
                    field.title == MONICA_USERNAME_ALIAS_FIELD_TITLE
                ) {
                    field.copy(title = usernameAliasFallbackTitle)
                } else {
                    field
                }
            }
            .toList()
    }
    val currentBitwardenSnapshotPreview = remember(
        passwordEntry,
        displayPasswords,
        displayCustomFields
    ) {
        passwordEntry?.takeIf {
            it.bitwardenVaultId != null && !it.bitwardenCipherId.isNullOrBlank()
        }?.let { entry ->
            BitwardenSyncSnapshotPreview(
                status = BitwardenSyncSnapshotPreviewStatus.READY,
                cipherType = entry.bitwardenCipherType,
                title = entry.title,
                username = entry.username,
                password = displayPasswords[entry.id].orEmpty(),
                totp = entry.authenticatorKey,
                websites = listOfNotNull(entry.website.takeIf { it.isNotBlank() }),
                notes = entry.notes,
                customFields = displayCustomFields.map { field ->
                    BitwardenSyncSnapshotFieldPreview(
                        name = field.title,
                        value = field.value,
                        hidden = field.isProtected
                    )
                }
            )
        }
    }
    
    // Helper function for deletion
    fun executeDeletion() {
        if (itemToDelete != null) {
            // Delete specific password
            viewModel.deletePasswordEntry(itemToDelete!!)
        } else {
            // Fallback: Delete current main entry
            passwordEntry?.let { entry ->
                viewModel.deletePasswordEntry(entry)
                requestNavigateBack()
            }
        }
        showDeleteDialog = false
        itemToDelete = null
    }

    // Biometric Helper
    val biometricHelper = remember { BiometricHelper(context) }
    
    // Verification Logic calling Biometric or falling back to Password Dialog
    fun startVerificationForDeletion() {
        if (biometricEnabled && biometricHelper.isBiometricAvailable()) {
            (context as? FragmentActivity)?.let { activity ->
                biometricHelper.authenticate(
                    activity = activity,
                    title = context.getString(R.string.verify_identity_to_delete),
                    onSuccess = { executeDeletion() },
                    onError = {
                        // If error is not user cancellation, show password dialog
                        masterPasswordInput = ""
                        passwordVerificationError = false
                         showMasterPasswordDialog = true
                    },
                    onFailed = {
                        // Authentication failed (e.g. wrong finger), show password dialog
                        masterPasswordInput = ""
                        passwordVerificationError = false
                        showMasterPasswordDialog = true
                    }
                )
            } ?: run {
                masterPasswordInput = ""
                passwordVerificationError = false
                showMasterPasswordDialog = true
            }
        } else {
            masterPasswordInput = ""
            passwordVerificationError = false
            showMasterPasswordDialog = true
        }
    }
    
    // 获取关联的TOTP数据
    val linkedTotp by viewModel.getLinkedTotpFlow(passwordId).collectAsState(initial = null)
    val boundPasskeys by (passkeyViewModel?.getPasskeysByBoundPasswordId(passwordId)
        ?: kotlinx.coroutines.flow.flowOf(emptyList()))
        .collectAsState(initial = emptyList())
    val passkeySupportCatalog = remember(context.applicationContext) {
        PasskeySupportCatalog(context.applicationContext)
    }
    var passkeySigninDomains by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(passkeySupportCatalog) {
        passkeySigninDomains = passkeySupportCatalog.getSigninDomains()
    }
    val availableNotes by (noteViewModel?.allNotes ?: flowOf(emptyList()))
        .collectAsState(initial = emptyList())
    val boundNoteId = passwordEntry?.boundNoteId
    val boundNoteFlow = remember(noteViewModel, boundNoteId) {
        if (noteViewModel != null && boundNoteId != null && boundNoteId > 0L) {
            noteViewModel.observeNoteById(boundNoteId)
        } else {
            flowOf(null)
        }
    }
    val boundNote by boundNoteFlow.collectAsState(initial = null)
    var showBoundNotePicker by remember { mutableStateOf(false) }
    var totpCode by remember { mutableStateOf("") }
    var totpProgress by remember { mutableStateOf(1f) }
    
    // 定时更新TOTP验证码
    LaunchedEffect(linkedTotp, settings.validatorSmoothProgress) {
        if (linkedTotp != null) {
            var lastCodeSecond = Long.MIN_VALUE
            while (isActive) {
                linkedTotp?.let { totp ->
                    val nowSecond = System.currentTimeMillis() / 1000L
                    if (nowSecond != lastCodeSecond) {
                        totpCode = TotpGenerator.generateOtp(totp)
                        lastCodeSecond = nowSecond
                    }
                    totpProgress = TotpGenerator.getProgress(totp.period)
                }
                delay(if (settings.validatorSmoothProgress) 100 else 1_000)
            }
        }
    }
    
    
    // 折叠面板状态
    var personalInfoExpanded by remember { mutableStateOf(true) }
    var addressInfoExpanded by remember { mutableStateOf(true) }
    var paymentInfoExpanded by remember { mutableStateOf(true) }
    
    // 密码可见性
    var passwordVisible by remember { mutableStateOf(false) }
    var cvvVisible by remember { mutableStateOf(false) }
    var isResyncingUnreadablePassword by remember { mutableStateOf(false) }
    var unavailablePasswordSources by remember { mutableStateOf<Map<Long, PasswordSource>>(emptyMap()) }

    // 加载密码详情
    // We need to observe all passwords to detect updates/siblings
    // Use allPasswordsForUi (no plaintext passwords) to avoid triggering full re-decryption on every list change
    val allPasswords by viewModel.allPasswordsForUi.collectAsState(initial = emptyList())

    // Initial load: fetch entry directly from DB without waiting for allPasswords Flow
    LaunchedEffect(passwordId) {
        if (isLeavingDetail) return@LaunchedEffect
        initialDetailDataLoaded = false
        passwordEntry = null
        groupPasswords = emptyList()
        displayPasswords = emptyMap()
        unavailablePasswordSources = emptyMap()
        customFields = emptyList()
        val entry = withContext(Dispatchers.IO) {
            viewModel.getRawPasswordEntryById(passwordId)
        } ?: return@LaunchedEffect

        if (isLeavingDetail) return@LaunchedEffect
        groupPasswords = listOf(entry)
        passwordEntry = entry
        personalInfoExpanded = hasPersonalInfo(entry)
        addressInfoExpanded = hasAddressInfo(entry)
        paymentInfoExpanded = hasPaymentInfo(entry)

        if (entry.keepassDatabaseId != null && !entry.keepassEntryUuid.isNullOrBlank()) {
            launch(Dispatchers.IO) {
                runCatching {
                    AttachmentContainer.keepassReconciler(context).reconcile(
                        passwordId = entry.id,
                        databaseId = entry.keepassDatabaseId,
                        entryUuid = entry.keepassEntryUuid
                    )
                }.onFailure { error ->
                    android.util.Log.w(
                        "PasswordDetailScreen",
                        "KeePass attachment metadata reconcile failed: ${error::class.simpleName}"
                    )
                }
            }
        }

        val resolvedGroupPasswords = withContext(Dispatchers.Default) {
            resolvePasswordDetailGroupPasswords(entry, allPasswords)
        }
        val detailPasswords = resolvedGroupPasswords.ifEmpty { listOf(entry) }
        val (resolvedDisplayPasswords, resolvedUnavailableSources) = withContext(Dispatchers.IO) {
            val passwordValues = mutableMapOf<Long, String>()
            val unavailableSources = mutableMapOf<Long, PasswordSource>()
            detailPasswords.forEach { current ->
                val rawCurrent = viewModel.getRawPasswordEntryById(current.id) ?: current
                when (val secretState = viewModel.inspectSecretState(rawCurrent)) {
                    is SecretValueState.Available -> {
                        passwordValues[current.id] = secretState.value
                    }

                    SecretValueState.Empty,
                    SecretValueState.Hidden -> {
                        passwordValues[current.id] = ""
                    }

                    is SecretValueState.Unreadable -> {
                        unavailableSources[current.id] = secretState.source
                    }
                }
            }
            passwordValues to unavailableSources
        }

        // 加载自定义字段 (添加错误处理)
        val resolvedCustomFields = try {
            withContext(Dispatchers.IO) {
                viewModel.getCustomFieldsByEntryIdSync(passwordId)
            }
        } catch (_: CancellationException) {
            throw CancellationException()
        } catch (e: Exception) {
            android.util.Log.e("PasswordDetailScreen", "Error loading custom fields", e)
            emptyList<CustomField>()
        }

        if (isLeavingDetail) return@LaunchedEffect

        groupPasswords = resolvedGroupPasswords
        displayPasswords = resolvedDisplayPasswords
        unavailablePasswordSources = resolvedUnavailableSources
        customFields = resolvedCustomFields
        initialDetailDataLoaded = true
    }

    // Lightweight observer: update passwordEntry metadata when allPasswords changes (e.g. after edit)
    LaunchedEffect(allPasswords, passwordEntry?.id) {
        if (allPasswords.isEmpty()) return@LaunchedEffect
        if (passwordEntry == null) return@LaunchedEffect
        if (!initialDetailDataLoaded) return@LaunchedEffect
        val currentEntry = passwordEntry ?: return@LaunchedEffect
        val updatedEntryFromList = allPasswords.find { it.id == passwordId } ?: return@LaunchedEffect
        val updatedEntry = if (
            updatedEntryFromList.password.isBlank() &&
            currentEntry.password.isNotBlank()
        ) {
            updatedEntryFromList.copy(password = currentEntry.password)
        } else {
            updatedEntryFromList
        }
        val resolvedGroupPasswords = withContext(Dispatchers.Default) {
            resolvePasswordDetailGroupPasswords(updatedEntry, allPasswords)
        }
        if (isLeavingDetail) return@LaunchedEffect
        passwordEntry = updatedEntry
        groupPasswords = resolvedGroupPasswords
        val detailPasswords = resolvedGroupPasswords.ifEmpty { listOf(updatedEntry) }
        val (resolvedDisplayPasswords, resolvedUnavailableSources) = withContext(Dispatchers.IO) {
            val passwordValues = mutableMapOf<Long, String>()
            val unavailableSources = mutableMapOf<Long, PasswordSource>()
            detailPasswords.forEach { current ->
                val rawCurrent = viewModel.getRawPasswordEntryById(current.id) ?: current
                when (val secretState = viewModel.inspectSecretState(rawCurrent)) {
                    is SecretValueState.Available -> {
                        passwordValues[current.id] = secretState.value
                    }

                    SecretValueState.Empty,
                    SecretValueState.Hidden -> {
                        passwordValues[current.id] = ""
                    }

                    is SecretValueState.Unreadable -> {
                        unavailableSources[current.id] = secretState.source
                    }
                }
            }
            passwordValues to unavailableSources
        }
        if (isLeavingDetail) return@LaunchedEffect
        displayPasswords = resolvedDisplayPasswords
        unavailablePasswordSources = resolvedUnavailableSources
    }

    LaunchedEffect(groupPasswords) {
        val vaultIds = groupPasswords.mapNotNull { it.bitwardenVaultId }.distinct()
        if (vaultIds.isEmpty()) {
            bitwardenFoldersByVault = emptyMap()
        } else {
            bitwardenFoldersByVault = withContext(Dispatchers.IO) {
                vaultIds.associateWith { vaultId ->
                    database.bitwardenFolderDao().getFoldersByVault(vaultId)
                }
            }
        }
    }
    
    Scaffold(
        modifier = Modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(passwordEntry?.title ?: stringResource(R.string.password_details)) },
                navigationIcon = {
                    IconButton(onClick = ::requestNavigateBack) {
                        Icon(
                            imageVector = MonicaIcons.Navigation.back,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {}, // Moved to ActionStrip
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        floatingActionButton = {
            ActionStrip(
                actions = listOf(
                    ActionStripItem(
                        icon = if (passwordEntry?.isFavorite == true) MonicaIcons.Status.favorite else MonicaIcons.Status.favoriteBorder,
                        contentDescription = stringResource(R.string.favorite),
                        onClick = {
                            passwordEntry?.let { entry ->
                                viewModel.toggleFavorite(entry.id, !entry.isFavorite)
                                passwordEntry = entry.copy(isFavorite = !entry.isFavorite)
                            }
                        },
                        tint = if (passwordEntry?.isFavorite == true) MaterialTheme.colorScheme.primary else null
                    ),
                    ActionStripItem(
                        icon = MonicaIcons.Action.edit,
                        contentDescription = stringResource(R.string.edit),
                        onClick = { onEditPassword(passwordId) }
                    ),
                    ActionStripItem(
                        icon = if (passwordEntry?.isArchived == true) Icons.Default.Unarchive else Icons.Default.Archive,
                        contentDescription = if (passwordEntry?.isArchived == true) {
                            stringResource(R.string.unarchive_action)
                        } else {
                            stringResource(R.string.archive_action)
                        },
                        onClick = {
                            passwordEntry?.let { entry ->
                                if (entry.isArchived) {
                                    viewModel.unarchivePassword(entry.id)
                                    passwordEntry = entry.copy(isArchived = false, archivedAt = null)
                                } else {
                                    showArchiveDialog = true
                                }
                            }
                        }
                    ),
                    ActionStripItem(
                        icon = MonicaIcons.Action.delete,
                        contentDescription = stringResource(R.string.delete),
                        onClick = { showDeleteDialog = true },
                        tint = MaterialTheme.colorScheme.error
                    )
                ),
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    ) { paddingValues ->
        passwordEntry?.let { entry ->
            if (entry.isGpgKeyEntry() || customFields.any {
                    it.title == takagi.ru.monica.data.model.GpgEntryFields.MARKER && it.value == "GPG_KEY"
                }) {
                GpgDetailContent(customFields, displayPasswords[entry.id].orEmpty(), Modifier.padding(paddingValues))
                return@let
            }
            if (entry.isApiKeyEntry() || takagi.ru.monica.data.model.ApiKeyEntryFields.isApiKey(
                    customFields.associate { it.title to it.value })) {
                ApiKeyDetailContent(entry, displayPasswords[entry.id], customFields, Modifier.padding(paddingValues))
                return@let
            }
            val storageInfoEntries = remember(
                groupPasswords,
                entry,
                categories,
                keepassDatabases,
                mdbxDatabases,
                bitwardenVaults,
                bitwardenFoldersByVault,
                settings,
                context
            ) {
                val entries = groupPasswords.ifEmpty { listOf(entry) }
                val infos = entries.map { candidate ->
                    buildPasswordStorageInfo(
                        entry = candidate,
                        categories = categories,
                        keepassDatabases = keepassDatabases,
                        mdbxDatabases = mdbxDatabases,
                        bitwardenVaults = bitwardenVaults,
                        bitwardenFoldersByVault = bitwardenFoldersByVault,
                        localSourceLabel = context.getString(R.string.database_source_local),
                        keepassSourceLabel = context.getString(R.string.database_source_keepass),
                        mdbxSourceLabel = "MDBX",
                        bitwardenSourceLabel = context.getString(R.string.filter_bitwarden),
                        passwordOwnerConflictShortLabel = context.getString(R.string.password_owner_conflict_short),
                        passwordOwnerConflictDatabaseLabel = context.getString(R.string.password_owner_conflict_database),
                        passwordOwnerConflictDisplayLabel = context.getString(R.string.password_owner_conflict_display),
                        rootLabel = context.getString(R.string.folder_no_folder_root)
                    )
                }
                val currentInfo = infos.firstOrNull { it.entryId == entry.id }
                    ?: buildPasswordStorageInfo(
                        entry = entry,
                        categories = categories,
                        keepassDatabases = keepassDatabases,
                        mdbxDatabases = mdbxDatabases,
                        bitwardenVaults = bitwardenVaults,
                        bitwardenFoldersByVault = bitwardenFoldersByVault,
                        localSourceLabel = context.getString(R.string.database_source_local),
                        keepassSourceLabel = context.getString(R.string.database_source_keepass),
                        mdbxSourceLabel = "MDBX",
                        bitwardenSourceLabel = context.getString(R.string.filter_bitwarden),
                        passwordOwnerConflictShortLabel = context.getString(R.string.password_owner_conflict_short),
                        passwordOwnerConflictDatabaseLabel = context.getString(R.string.password_owner_conflict_database),
                        passwordOwnerConflictDisplayLabel = context.getString(R.string.password_owner_conflict_display),
                        rootLabel = context.getString(R.string.folder_no_folder_root)
                    )
                listOf(currentInfo) + infos
                    .filterNot { it.entryId == entry.id }
                    .filterNot { it.locationKey == currentInfo.locationKey }
                    .distinctBy { it.locationKey }
            }
            val dateFormatter = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
            val createdAtText = remember(entry.createdAt) { dateFormatter.format(entry.createdAt) }
            val updatedAtText = remember(entry.updatedAt) { dateFormatter.format(entry.updatedAt) }
            val shouldShowBasicInfo = if (settings.separateUsernameAccountEnabled) {
                entry.username.isNotEmpty() || separatedUsername.isNotEmpty()
            } else {
                entry.username.isNotEmpty()
            }
            val refEntry = remember(entry.ssoRefEntryId, allPasswords) {
                entry.ssoRefEntryId?.let { refId -> allPasswords.find { it.id == refId } }
            }
            val detailPasswords = remember(entry, groupPasswords) {
                groupPasswords.ifEmpty { listOf(entry) }
            }
            val shouldShowPasswordCard = remember(detailPasswords, unavailablePasswordSources) {
                detailPasswords.any { passwordEntry ->
                    passwordEntry.password.isNotBlank() || unavailablePasswordSources[passwordEntry.id] != null
                }
            }
            val websiteTargets = remember(entry.website) { normalizeWebsiteUrls(entry.website) }
            val bindingSummaries = remember(entry.passkeyBindings, boundPasskeys) {
                val fromField = PasskeyBindingCodec.decodeList(entry.passkeyBindings)
                    .map { binding ->
                        listOf(
                            binding.rpName.ifBlank { binding.rpId },
                            binding.userDisplayName.ifBlank { binding.userName }
                        ).filter { it.isNotBlank() }.joinToString(" · ")
                    }
                    .filter { it.isNotBlank() }

                if (fromField.isNotEmpty()) {
                    fromField
                } else {
                    boundPasskeys.map { passkey ->
                        listOf(
                            passkey.rpName,
                            passkey.userDisplayName.ifBlank { passkey.userName },
                            passkey.rpId
                        ).filter { it.isNotBlank() }.joinToString(" · ")
                    }.filter { it.isNotBlank() }
                }
            }
            val shouldShowBitwardenSnapshotSection =
                entry.bitwardenVaultId != null && !entry.bitwardenCipherId.isNullOrBlank()

            LaunchedEffect(entry.id, entry.passkeyBindings, boundPasskeys) {
                if (entry.passkeyBindings.isBlank() && boundPasskeys.isNotEmpty()) {
                    val bindings = boundPasskeys.map { passkey ->
                        PasskeyBinding(
                            credentialId = passkey.credentialId,
                            rpId = passkey.rpId,
                            rpName = passkey.rpName,
                            userName = passkey.userName,
                            userDisplayName = passkey.userDisplayName
                        )
                    }
                    val encoded = PasskeyBindingCodec.encodeList(bindings)
                    viewModel.updatePasskeyBindings(entry.id, encoded)
                }
            }

            LazyColumn(
                modifier = modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item("header") {
                    HeaderSection(
                        entry = entry,
                        iconCardsEnabled = iconCardsEnabled,
                        unmatchedIconHandlingStrategy = unmatchedIconHandlingStrategy
                    )
                }

                if (shouldShowBasicInfo) {
                    item("basic_info") {
                        BasicInfoCard(
                            entry = entry,
                            context = context,
                            separateUsernameAccountEnabled = settings.separateUsernameAccountEnabled,
                            separatedUsername = separatedUsername,
                            onCreateSend = onCreateSend
                        )
                    }
                }

                if (entry.isSsoLogin()) {
                    item("sso_login") {
                        SsoLoginCard(
                            entry = entry,
                            refEntry = refEntry,
                            context = context
                        )
                    }
                }

                if (shouldShowPasswordCard) {
                    item("passwords") {
                        PasswordListCard(
                            passwords = detailPasswords,
                            displayPasswords = displayPasswords,
                            unavailablePasswordSources = unavailablePasswordSources,
                            onResyncUnreadable = { targetEntry ->
                                if (isResyncingUnreadablePassword) return@PasswordListCard
                                coroutineScope.launch {
                                    isResyncingUnreadablePassword = true
                                    val result = viewModel.recoverUnreadableBitwardenEntry(targetEntry.id)
                                    val message = when (result) {
                                        BitwardenRecoveryResult.Success ->
                                            context.getString(R.string.bitwarden_password_resync_success)
                                        is BitwardenRecoveryResult.Error ->
                                            context.getString(R.string.bitwarden_password_resync_failed, result.message)
                                        is BitwardenRecoveryResult.EmptyVaultBlocked ->
                                            context.getString(R.string.bitwarden_password_resync_blocked, result.reason)
                                    }
                                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                    isResyncingUnreadablePassword = false
                                }
                            },
                            isResyncingUnreadable = isResyncingUnreadablePassword,
                            showSecurityAnalysis = settings.passwordDetailSecurityAnalysisEnabled,
                            onDelete = { targetEntry ->
                                itemToDelete = targetEntry
                                showDeleteDialog = true
                            },
                            context = context,
                            onCreateSend = onCreateSend
                        )
                    }
                }

                if (settings.passwordDetailSecurityAnalysisEnabled) {
                    item("security_analysis") {
                        PasswordDetailSecurityAnalysisCard(
                            hasTwoFactor = linkedTotp != null || entry.authenticatorKey.isNotBlank(),
                            hasBoundPasskey = boundPasskeys.isNotEmpty() || entry.passkeyBindings.isNotBlank(),
                            passkeyAvailable = isPasskeyAvailableForEntry(
                                entry = entry,
                                signinDomains = passkeySigninDomains,
                                catalog = passkeySupportCatalog
                            )
                        )
                    }
                }

                if (websiteTargets.isNotEmpty()) {
                    item("websites") {
                        WebsiteCard(
                            websites = websiteTargets,
                            context = context
                        )
                    }
                }

                if (displayCustomFields.isNotEmpty()) {
                    item("custom_fields") {
                        CustomFieldsCard(
                            fields = displayCustomFields,
                            visibilityState = customFieldVisibility,
                            context = context,
                            onCreateSend = onCreateSend
                        )
                    }
                }

                item("attachments") {
                    // 构造附件下载/预览所需上下文
                    val bwVault = entry.bitwardenVaultId?.let { vaultId ->
                        bitwardenVaults.firstOrNull { it.id == vaultId }
                    }
                    val bwContext = if (bwVault != null) {
                        remember(bwVault, entry.bitwardenCipherId) {
                            viewModel.getAttachmentBitwardenContext(bwVault, entry.bitwardenCipherId)
                        }
                    } else null
                    val kpContext = if (entry.keepassDatabaseId != null && !entry.keepassEntryUuid.isNullOrBlank()) {
                        remember(entry.keepassDatabaseId, entry.keepassEntryUuid) {
                            AttachmentFacade.KeePassContext(
                                databaseId = entry.keepassDatabaseId,
                                entryUuid = entry.keepassEntryUuid!!
                            )
                        }
                    } else null
                    takagi.ru.monica.attachments.ui.AttachmentsDetailSection(
                        passwordId = entry.id,
                        bitwardenContext = bwContext,
                        keepassContext = kpContext
                    )
                }

                if (noteViewModel != null) {
                    item("bound_note") {
                        BoundNoteCard(
                            boundNote = boundNote,
                            hasBoundNoteReference = entry.boundNoteId != null,
                            onOpenBoundNote = {
                                boundNote?.id?.let(onOpenBoundNote)
                            },
                            onChangeBoundNote = { showBoundNotePicker = true },
                            onClearBoundNote = {
                                viewModel.updateBoundNoteId(entry.id, null)
                                passwordEntry = entry.copy(boundNoteId = null)
                            }
                        )
                    }
                }

                item("storage_info") {
                    StorageInfoCard(
                        currentInfo = storageInfoEntries.first(),
                        otherInfos = storageInfoEntries.drop(1),
                        onOpenPassword = onOpenPassword,
                        onEditPassword = onEditPassword
                    )
                }

                item("time_info") {
                    TimeInfoCard(
                        createdAt = createdAtText,
                        updatedAt = updatedAtText
                    )
                }

                if (entry.appPackageName.isNotEmpty() || entry.appName.isNotEmpty() || linkedTotp != null) {
                    item("totp") {
                        TotpCard(
                            entry = entry,
                            totpData = linkedTotp,
                            code = totpCode,
                            progress = totpProgress,
                            context = context
                        )
                    }
                }

                if (boundPasskeys.isNotEmpty() || bindingSummaries.isNotEmpty()) {
                    item("passkeys") {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(
                                text = stringResource(R.string.passkey_bound_label),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 12.dp)
                            )
                            PasskeyBoundCard(
                                passkeys = boundPasskeys,
                                bindingSummaries = bindingSummaries
                            )
                        }
                    }
                }

                if (hasPersonalInfo(entry)) {
                    item("personal_info") {
                        CollapsibleSection(
                            title = stringResource(R.string.personal_info),
                            icon = MonicaIcons.General.person,
                            expanded = personalInfoExpanded,
                            onToggle = { personalInfoExpanded = !personalInfoExpanded }
                        ) {
                            PersonalInfoContent(entry = entry, context = context, onCreateSend = onCreateSend)
                        }
                    }
                }

                if (hasAddressInfo(entry)) {
                    item("address_info") {
                        CollapsibleSection(
                            title = stringResource(R.string.address_info),
                            icon = Icons.Default.Home,
                            expanded = addressInfoExpanded,
                            onToggle = { addressInfoExpanded = !addressInfoExpanded }
                        ) {
                            AddressInfoContent(entry = entry)
                        }
                    }
                }

                if (hasPaymentInfo(entry)) {
                    item("payment_info") {
                        CollapsibleSection(
                            title = stringResource(R.string.payment_info),
                            icon = MonicaIcons.Data.creditCard,
                            expanded = paymentInfoExpanded,
                            onToggle = { paymentInfoExpanded = !paymentInfoExpanded }
                        ) {
                            PaymentInfoContent(
                                entry = entry,
                                cvvVisible = cvvVisible,
                                onToggleCvvVisibility = { cvvVisible = !cvvVisible },
                                context = context,
                                onCreateSend = onCreateSend
                            )
                        }
                    }
                }

                if (entry.notes.isNotEmpty()) {
                    item("notes") {
                        NotesCard(notes = entry.notes)
                    }
                }

                item("password_history") {
                    MonicaExpandableContent(expanded = passwordHistory.isNotEmpty()) {
                        PasswordHistorySection(
                            history = passwordHistory,
                            visibilityState = passwordHistoryVisibility,
                            context = context,
                            onDeleteHistoryItem = { item ->
                                historyItemToDelete = item
                            },
                            onClearHistory = {
                                showClearHistoryDialog = true
                            }
                        )
                    }
                }

                item("bitwarden_snapshot") {
                    MonicaExpandableContent(expanded = shouldShowBitwardenSnapshotSection) {
                        BitwardenSyncSnapshotSection(
                            currentPreview = currentBitwardenSnapshotPreview,
                            history = bitwardenSyncRawHistory,
                            context = context
                        )
                    }
                }
                
                item("bottom_spacer") {
                    Spacer(modifier = Modifier.height(80.dp))
                }
            }
        }
    }
    
    // 删除确认对话框
    if (showArchiveDialog) {
        AlertDialog(
            onDismissRequest = { showArchiveDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = passwordEntry
                        showArchiveDialog = false
                        if (target != null) {
                            viewModel.archivePassword(target.id)
                            requestNavigateBack()
                        }
                    }
                ) {
                    Text(
                        text = stringResource(R.string.archive_action),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showArchiveDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            title = { Text(stringResource(R.string.archive_password_confirmation_title)) },
            text = { Text(stringResource(R.string.archive_password_confirmation_message)) },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        startVerificationForDeletion()
                    }
                ) {
                    Text(
                        text = stringResource(R.string.delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            title = { Text(stringResource(R.string.delete_password)) },
            text = { Text(stringResource(R.string.delete_password_confirmation)) },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    historyItemToDelete?.let { historyItem ->
        HistoryPasswordConfirmDialog(
            title = stringResource(R.string.delete_password_history_entry_title),
            message = stringResource(R.string.delete_password_history_entry_message),
            confirmText = stringResource(R.string.delete),
            onDismiss = { historyItemToDelete = null },
            onConfirm = {
                passwordHistoryVisibility.remove(historyItem.id)
                viewModel.deletePasswordHistoryEntry(historyItem.id)
                historyItemToDelete = null
            }
        )
    }

    if (showClearHistoryDialog) {
        HistoryPasswordConfirmDialog(
            title = stringResource(R.string.clear_password_history_title),
            message = stringResource(R.string.clear_password_history_message),
            confirmText = stringResource(R.string.clear_password_history),
            onDismiss = { showClearHistoryDialog = false },
            onConfirm = {
                passwordHistoryVisibility.clear()
                viewModel.clearPasswordHistory(passwordId)
                showClearHistoryDialog = false
            }
        )
    }

    if (showMasterPasswordDialog) {
        val activity = context as? FragmentActivity
        val skipIdentityVerification = settings.disablePasswordVerification
        val retryBiometricAction = if (
            !skipIdentityVerification &&
            activity != null &&
            biometricEnabled &&
            biometricHelper.isBiometricAvailable()
        ) {
            {
                biometricHelper.authenticate(
                    activity = activity,
                    title = context.getString(R.string.verify_identity_to_delete),
                    onSuccess = {
                        showMasterPasswordDialog = false
                        passwordVerificationError = false
                        executeDeletion()
                    },
                    onError = {
                        // keep dialog open and let user choose password retry
                    },
                    onFailed = {
                        // keep dialog open
                    }
                )
            }
        } else {
            null
        }
        M3IdentityVerifyDialog(
            title = stringResource(R.string.verify_identity),
            message = stringResource(R.string.verify_identity_to_delete),
            passwordValue = masterPasswordInput,
            onPasswordChange = {
                masterPasswordInput = it
                passwordVerificationError = false
            },
            onDismiss = {
                showMasterPasswordDialog = false 
                masterPasswordInput = ""
                passwordVerificationError = false
            },
            onConfirm = {
                if (skipIdentityVerification || viewModel.verifyMasterPassword(masterPasswordInput)) {
                    showMasterPasswordDialog = false
                    masterPasswordInput = ""
                    passwordVerificationError = false
                    executeDeletion()
                } else {
                    passwordVerificationError = true
                }
            },
            confirmText = stringResource(R.string.delete),
            destructiveConfirm = true,
            requireIdentityVerification = !skipIdentityVerification,
            isPasswordError = passwordVerificationError,
            passwordErrorText = stringResource(R.string.current_password_incorrect),
            onBiometricClick = retryBiometricAction,
            biometricHintText = if (retryBiometricAction == null) {
                context.getString(R.string.biometric_not_available)
            } else {
                null
            }
        )
    }

    if (noteViewModel != null) {
        NotePickerBottomSheet(
            visible = showBoundNotePicker,
            title = stringResource(R.string.note_picker_title),
            notes = availableNotes.filter { !it.isDeleted },
            selectedNoteId = boundNoteId,
            onSelect = { note ->
                viewModel.updateBoundNoteId(passwordId, note.id)
                passwordEntry = passwordEntry?.copy(boundNoteId = note.id)
                showBoundNotePicker = false
            },
            onDismiss = { showBoundNotePicker = false }
        )
    }
}

@Composable
private fun PasswordHistorySection(
    history: List<PasswordHistoryEntry>,
    visibilityState: MutableMap<Long, Boolean>,
    context: Context,
    onDeleteHistoryItem: (PasswordHistoryEntry) -> Unit,
    onClearHistory: () -> Unit
) {
    val dateFormatter = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    var sectionMenuExpanded by remember { mutableStateOf(false) }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth(),
        shape = RoundedCornerShape(28.dp, 28.dp, 20.dp, 20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier
                .animateMonicaContentSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.password_history_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.password_history_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Box {
                    IconButton(onClick = { sectionMenuExpanded = true }) {
                        Icon(
                            imageVector = MonicaIcons.Action.more,
                            contentDescription = stringResource(R.string.more_options),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(
                        expanded = sectionMenuExpanded,
                        onDismissRequest = { sectionMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.clear_password_history)) },
                            onClick = {
                                sectionMenuExpanded = false
                                onClearHistory()
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = MonicaIcons.Action.delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        )
                    }
                }
            }

            history.forEachIndexed { index, item ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = if (index == 0) {
                        RoundedCornerShape(24.dp, 18.dp, 24.dp, 18.dp)
                    } else {
                        RoundedCornerShape(18.dp)
                    },
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    tonalElevation = if (index == 0) 2.dp else 1.dp
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (index == 0) {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.primaryContainer
                                    ) {
                                        Text(
                                            text = stringResource(R.string.password_history_latest),
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }
                                Text(
                                    text = stringResource(
                                        R.string.password_history_last_used,
                                        dateFormatter.format(item.lastUsedAt)
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                tonalElevation = 1.dp
                            ) {
                                IconButton(onClick = { onDeleteHistoryItem(item) }) {
                                    Icon(
                                        imageVector = MonicaIcons.Action.delete,
                                        contentDescription = stringResource(R.string.delete),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }

                        HistoryPasswordValue(
                            value = item.password,
                            visible = visibilityState[item.id] == true,
                            onToggleVisibility = {
                                val current = visibilityState[item.id] == true
                                visibilityState[item.id] = !current
                            },
                            context = context
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryPasswordValue(
    value: String,
    visible: Boolean,
    onToggleVisibility: () -> Unit,
    context: Context
) {
    val hasReadableValue = value.isNotBlank()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.password),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            if (hasReadableValue) {
                Text(
                    text = if (visible) value else "••••••••",
                    maxLines = if (visible) Int.MAX_VALUE else 1,
                    style = if (visible) {
                        MaterialTheme.typography.bodyLarge.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    } else {
                        MaterialTheme.typography.bodyLarge.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            letterSpacing = 3.sp
                        )
                    },
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Text(
                    text = stringResource(R.string.password_history_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (hasReadableValue) {
                    IconButton(onClick = onToggleVisibility) {
                        Icon(
                            imageVector = if (visible) MonicaIcons.Security.visibilityOff else MonicaIcons.Security.visibility,
                            contentDescription = if (visible) stringResource(R.string.hide) else stringResource(R.string.show),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = {
                            ClipboardUtils.copyToClipboard(
                                context = context,
                                text = value,
                                label = context.getString(R.string.password),
                                sensitive = true
                            )
                            Toast.makeText(
                                context,
                                context.getString(R.string.password_copied),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    ) {
                        Icon(
                            imageVector = MonicaIcons.Action.copy,
                            contentDescription = stringResource(R.string.copy),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryPasswordConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(32.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 8.dp,
        icon = {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.88f),
                tonalElevation = 2.dp
            ) {
                Box(
                    modifier = Modifier.size(60.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = MonicaIcons.Action.delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        },
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.password_history_description),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    tonalElevation = 1.dp
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = 24.sp
                        )
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = stringResource(R.string.delete_password_confirmation),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = onConfirm,
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Text(confirmText)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(18.dp)
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

// ============================================
// 🎯 头部区域组件 - 左对齐
// ============================================
@Composable
private fun HeaderSection(
    entry: PasswordEntry,
    iconCardsEnabled: Boolean,
    unmatchedIconHandlingStrategy: UnmatchedIconHandlingStrategy
) {
    val textBlock: @Composable ColumnScope.() -> Unit = {
        Text(
            text = entry.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (entry.website.isNotEmpty()) {
            Text(
                text = entry.website,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (iconCardsEnabled) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PasswordDetailIcon(
                entry = entry,
                unmatchedIconHandlingStrategy = unmatchedIconHandlingStrategy
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                content = textBlock
            )
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = textBlock
        )
    }
}

@Composable
private fun PasswordDetailIcon(
    entry: PasswordEntry,
    unmatchedIconHandlingStrategy: UnmatchedIconHandlingStrategy
) {
    val simpleIcon = if (entry.customIconType == takagi.ru.monica.ui.icons.PASSWORD_ICON_TYPE_SIMPLE) {
        rememberSimpleIconBitmap(
            slug = entry.customIconValue,
            tintColor = MaterialTheme.colorScheme.primary,
            enabled = true
        )
    } else null
    val uploadedIcon = if (entry.customIconType == takagi.ru.monica.ui.icons.PASSWORD_ICON_TYPE_UPLOADED) {
        rememberUploadedPasswordIcon(entry.customIconValue)
    } else null
    val emojiIcon = entry.customIconValue.takeIf {
        entry.customIconType == takagi.ru.monica.ui.icons.PASSWORD_ICON_TYPE_EMOJI
    }
    val primaryAppPackageName = entry.primaryLinkedAppPackageName()
    val appIcon = if (
        primaryAppPackageName.isNotBlank() &&
        !takagi.ru.monica.autofill_ng.ui.isWebAddress(entry.website)
    ) {
        rememberAppIcon(primaryAppPackageName)
    } else null
    val autoMatchedSimpleIcon = rememberAutoMatchedSimpleIcon(
        website = entry.website,
        title = entry.title,
        appPackageName = primaryAppPackageName,
        tintColor = MaterialTheme.colorScheme.primary,
        enabled = entry.customIconType == takagi.ru.monica.ui.icons.PASSWORD_ICON_TYPE_NONE
    )
    val primaryWebsite = remember(entry.website) { normalizeWebsiteUrl(entry.website) }
    val favicon = if (!primaryWebsite.isNullOrBlank()) {
        rememberFavicon(
            url = primaryWebsite,
            enabled = autoMatchedSimpleIcon.resolved && autoMatchedSimpleIcon.slug == null
        )
    } else null

    when {
        emojiIcon != null -> {
            takagi.ru.monica.ui.icons.EmojiIconText(
                emoji = emojiIcon,
                size = 34.dp,
                modifier = Modifier.size(52.dp)
            )
        }
        simpleIcon != null -> {
            Image(
                bitmap = simpleIcon,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(52.dp).padding(2.dp)
            )
        }
        uploadedIcon != null -> {
            Image(
                bitmap = uploadedIcon,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(52.dp).padding(2.dp)
            )
        }
        autoMatchedSimpleIcon.bitmap != null -> {
            Image(
                bitmap = autoMatchedSimpleIcon.bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(52.dp).padding(2.dp)
            )
        }
        favicon != null -> {
            Image(
                bitmap = favicon,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(52.dp).padding(2.dp)
            )
        }
        appIcon != null -> {
            Image(
                bitmap = appIcon,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(52.dp).padding(2.dp)
            )
        }
        shouldShowFallbackSlot(unmatchedIconHandlingStrategy) -> {
            UnmatchedIconFallback(
                strategy = unmatchedIconHandlingStrategy,
                primaryText = entry.website,
                secondaryText = entry.title,
                defaultIcon = Icons.Default.Key,
                iconSize = 52.dp
            )
        }
    }
}

@Composable
private fun WebsiteCard(
    websites: List<String>,
    context: Context
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Language,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.website_url),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            websites.forEach { website ->
                Surface(
                    onClick = { openWebsiteInBrowser(context, website) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = website,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Icon(
                            imageVector = Icons.Default.OpenInNew,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PasswordDetailSecurityAnalysisCard(
    hasTwoFactor: Boolean,
    hasBoundPasskey: Boolean,
    passkeyAvailable: Boolean
) {
    val colorScheme = MaterialTheme.colorScheme

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.security_analysis),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            SecurityStatusChip(
                icon = Icons.Default.Key,
                label = stringResource(R.string.inactive_passkeys_short),
                value = when {
                    hasBoundPasskey -> stringResource(R.string.passkey_bound_label)
                    passkeyAvailable -> stringResource(R.string.inactive_passkeys)
                    else -> stringResource(R.string.biometric_not_available)
                },
                color = when {
                    hasBoundPasskey -> colorScheme.primary
                    passkeyAvailable -> colorScheme.tertiary
                    else -> colorScheme.onSurfaceVariant
                },
                modifier = Modifier.fillMaxWidth()
            )

            SecurityStatusChip(
                icon = Icons.Default.Lock,
                label = "2FA",
                value = if (hasTwoFactor) {
                    stringResource(R.string.supports_twofa)
                } else {
                    stringResource(R.string.no_twofa)
                },
                color = if (hasTwoFactor) Color(0xFF22C55E) else colorScheme.error,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun SecurityStatusChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.13f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(18.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                color = color,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun isPasskeyAvailableForEntry(
    entry: PasswordEntry,
    signinDomains: List<String>,
    catalog: PasskeySupportCatalog
): Boolean {
    return normalizeWebsiteUrls(entry.website).any { website ->
        val host = runCatching {
            val uri = Uri.parse(if (website.contains("://")) website else "https://$website")
            uri.host ?: website.substringBefore('/').substringBefore(':')
        }.getOrDefault(website)
        catalog.findMatchingDomain(host, signinDomains) != null
    }
}

@Composable
private fun StorageInfoCard(
    currentInfo: PasswordStorageInfo,
    otherInfos: List<PasswordStorageInfo>,
    onOpenPassword: (Long) -> Unit,
    onEditPassword: (Long) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.password_detail_storage_info),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onEditPassword(currentInfo.entryId) },
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.password_detail_current_location),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                DetailInfoRow(
                    label = stringResource(R.string.database_source_label),
                    value = currentInfo.source
                )
                DetailInfoRow(
                    label = stringResource(R.string.password_picker_filter_database),
                    value = currentInfo.database
                )
                DetailInfoRow(
                    label = stringResource(R.string.password_picker_filter_folder),
                    value = currentInfo.folderPath
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Text(
                        text = stringResource(R.string.edit),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            if (otherInfos.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = stringResource(R.string.password_detail_other_locations),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )
                otherInfos.forEach { info ->
                    OtherStorageLocationRow(
                        info = info,
                        onClick = { onOpenPassword(info.entryId) }
                    )
                }
            }
        }
    }
}

@Composable
private fun OtherStorageLocationRow(
    info: PasswordStorageInfo,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "${info.source} · ${info.database}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = info.folderPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = stringResource(R.string.password_detail_open_location),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun TimeInfoCard(
    createdAt: String,
    updatedAt: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.password_detail_time_info),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            DetailInfoRow(
                label = stringResource(R.string.created_at),
                value = createdAt
            )
            DetailInfoRow(
                label = stringResource(R.string.password_detail_last_modified),
                value = updatedAt
            )
        }
    }
}

@Composable
private fun DetailInfoRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

// ============================================
// 🔐 基本信息卡片
// ============================================
@Composable
private fun BasicInfoCard(
    entry: PasswordEntry,
    context: Context,
    separateUsernameAccountEnabled: Boolean,
    separatedUsername: String,
    onCreateSend: ((title: String, text: String) -> Unit)?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (separateUsernameAccountEnabled) {
                if (entry.username.isNotEmpty()) {
                    InfoFieldWithCopy(
                        label = stringResource(R.string.field_account),
                        value = entry.username,
                        context = context,
                        onCreateSend = onCreateSend
                    )
                }
                if (separatedUsername.isNotEmpty()) {
                    InfoFieldWithCopy(
                        label = stringResource(R.string.autofill_username),
                        value = separatedUsername,
                        context = context,
                        onCreateSend = onCreateSend
                    )
                }
            } else {
                if (entry.username.isNotEmpty()) {
                    InfoFieldWithCopy(
                        label = stringResource(R.string.field_account),
                        value = entry.username,
                        context = context,
                        onCreateSend = onCreateSend
                    )
                }
            }
        }
    }
}

// ============================================
// 🔗 SSO 第三方登录卡片
// ============================================
@Composable
private fun SsoLoginCard(
    entry: PasswordEntry,
    refEntry: PasswordEntry?,
    context: Context
) {
    val ssoProvider = entry.getSsoProviderEnum() ?: SsoProvider.OTHER
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 标题行
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = getSsoProviderIcon(ssoProvider),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = stringResource(R.string.sso_login_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            
            // SSO 提供商
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.use_sso),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                
                // Provider chip
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = getSsoProviderIcon(ssoProvider),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = ssoProvider.localizedName(),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                
                Text(
                    text = stringResource(R.string.sso_login_btn),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            
            // 关联账号信息
            if (refEntry != null) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.2f),
                    thickness = 1.dp
                )
                
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.sso_ref_account),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                    )
                    
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 1.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 图标
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = getSsoProviderIcon(ssoProvider),
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                            
                            // 账号信息
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = refEntry.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (refEntry.username.isNotEmpty()) {
                                    Text(
                                        text = refEntry.username,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
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

/**
 * 获取 SSO 提供商图标
 */
@Composable
private fun getSsoProviderIcon(provider: SsoProvider): androidx.compose.ui.graphics.vector.ImageVector {
    return when (provider) {
        SsoProvider.GOOGLE -> Icons.Default.Email
        SsoProvider.APPLE -> Icons.Default.Phone
        SsoProvider.FACEBOOK -> Icons.Default.Person
        SsoProvider.MICROSOFT -> Icons.Default.Settings
        SsoProvider.GITHUB -> Icons.Default.Build
        SsoProvider.TWITTER -> Icons.Default.Send
        SsoProvider.WECHAT -> Icons.Default.Chat
        SsoProvider.QQ -> Icons.Default.Group
        SsoProvider.WEIBO -> Icons.Default.Public
        SsoProvider.OTHER -> Icons.Default.Lock
    }
}

// ============================================
// 🔑 2FA / TOTP 高亮卡片
// ============================================
// ============================================
// 🔑 2FA / TOTP 高亮卡片
// ============================================
@Composable
private fun TotpCard(
    entry: PasswordEntry,
    totpData: TotpData?,
    code: String,
    progress: Float,
    context: Context
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = MonicaIcons.Security.key,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = if (totpData != null) stringResource(R.string.dynamic_verification_code) else stringResource(R.string.linked_app),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            
            // 如果只有TOTP数据，显示验证码
            if (totpData != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = code.chunked(3).joinToString(" "),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            letterSpacing = 4.sp
                        )
                    }
                    
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("2FA Code", code)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, context.getString(R.string.copied, context.getString(R.string.verification_code)), Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(
                            imageVector = MonicaIcons.Action.copy,
                            contentDescription = stringResource(R.string.copy),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                
                // 进度条
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.3f),
                )
            }
            
            // 显示关联应用信息
            val linkedApps = entry.linkedAppBindings()
            if (linkedApps.isNotEmpty()) {
                if (totpData != null) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f),
                        thickness = 1.dp
                    )
                }
                
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    linkedApps.forEach { app ->
                        Text(
                            text = app.appName.ifBlank { app.packageName },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = app.packageName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PasskeyBoundCard(
    passkeys: List<takagi.ru.monica.data.PasskeyEntry>,
    bindingSummaries: List<String> = emptyList()
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Key,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.passkey_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            if (passkeys.isNotEmpty()) {
                passkeys.forEach { passkey ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = listOf(
                                passkey.rpName,
                                passkey.userDisplayName.ifBlank { passkey.userName },
                                passkey.rpId
                            ).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (passkey.isKeePassCompatible()) {
                            PasskeyFormatBadge(text = stringResource(R.string.passkey_format_keepass))
                            Text(
                                text = stringResource(R.string.passkey_format_keepass_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                bindingSummaries.forEach { summary ->
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun PasskeyFormatBadge(
    text: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

// ============================================
// 📧 个人信息内容
// ============================================
@Composable
private fun PersonalInfoContent(
    entry: PasswordEntry,
    context: Context,
    onCreateSend: ((title: String, text: String) -> Unit)?
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val emails = entry.email.split("|").filter { it.isNotBlank() }
        if (emails.size > 1) {
            emails.forEachIndexed { index, email ->
                InfoFieldWithCopy(
                    label = "${stringResource(R.string.email)}${index + 1}",
                    value = email,
                    context = context,
                    onCreateSend = onCreateSend
                )
            }
        } else if (emails.isNotEmpty()) {
            InfoFieldWithCopy(
                label = stringResource(R.string.email),
                value = emails[0],
                context = context,
                onCreateSend = onCreateSend
            )
        }
        
        val phones = entry.phone.split("|").filter { it.isNotBlank() }
        if (phones.size > 1) {
            phones.forEachIndexed { index, phone ->
                InfoFieldWithCopy(
                    label = "${stringResource(R.string.phone)}${index + 1}",
                    value = FieldValidation.formatPhone(phone),
                    context = context,
                    onCreateSend = onCreateSend
                )
            }
        } else if (phones.isNotEmpty()) {
            InfoFieldWithCopy(
                label = stringResource(R.string.phone),
                value = FieldValidation.formatPhone(phones[0]),
                context = context,
                onCreateSend = onCreateSend
            )
        }
    }
}

// ============================================
// 🏠 地址信息内容
// ============================================
@Composable
private fun AddressInfoContent(entry: PasswordEntry) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (entry.addressLine.isNotEmpty()) {
            InfoField(
                label = stringResource(R.string.address_line),
                value = entry.addressLine
            )
        }
        
        // 城市和省份
        if (entry.city.isNotEmpty() || entry.state.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (entry.city.isNotEmpty()) {
                    Box(modifier = Modifier.weight(1f)) {
                        InfoField(
                            label = stringResource(R.string.city),
                            value = entry.city
                        )
                    }
                }
                if (entry.state.isNotEmpty()) {
                    Box(modifier = Modifier.weight(1f)) {
                        InfoField(
                            label = stringResource(R.string.state),
                            value = entry.state
                        )
                    }
                }
            }
        }
        
        // 邮编和国家
        if (entry.zipCode.isNotEmpty() || entry.country.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (entry.zipCode.isNotEmpty()) {
                    Box(modifier = Modifier.weight(1f)) {
                        InfoField(
                            label = stringResource(R.string.zip_code),
                            value = entry.zipCode
                        )
                    }
                }
                if (entry.country.isNotEmpty()) {
                    Box(modifier = Modifier.weight(1f)) {
                        InfoField(
                            label = stringResource(R.string.country),
                            value = entry.country
                        )
                    }
                }
            }
        }
    }
}

// ============================================
// 💳 支付信息内容
// ============================================
@Composable
private fun PaymentInfoContent(
    entry: PasswordEntry,
    cvvVisible: Boolean,
    onToggleCvvVisibility: () -> Unit,
    context: Context,
    onCreateSend: ((title: String, text: String) -> Unit)?
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (entry.creditCardNumber.isNotEmpty()) {
            InfoFieldWithCopy(
                label = stringResource(R.string.credit_card_number),
                value = FieldValidation.maskCreditCard(entry.creditCardNumber),
                copyValue = entry.creditCardNumber,
                context = context,
                onCreateSend = onCreateSend
            )
        }
        
        if (entry.creditCardHolder.isNotEmpty()) {
            InfoField(
                label = stringResource(R.string.card_holder),
                value = entry.creditCardHolder
            )
        }
        
        // 有效期和 CVV
        if (entry.creditCardExpiry.isNotEmpty() || entry.creditCardCVV.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (entry.creditCardExpiry.isNotEmpty()) {
                    Box(modifier = Modifier.weight(1f)) {
                        InfoField(
                            label = stringResource(R.string.expiry_date),
                            value = entry.creditCardExpiry
                        )
                    }
                }
                if (entry.creditCardCVV.isNotEmpty()) {
                    Box(modifier = Modifier.weight(1f)) {
                        PasswordField(
                            label = stringResource(R.string.cvv),
                            value = entry.creditCardCVV,
                            visible = cvvVisible,
                            onToggleVisibility = onToggleCvvVisibility,
                            context = context,
                            onCreateSend = onCreateSend
                        )
                    }
                }
            }
        }
        
        // 安全提示
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = MonicaIcons.Security.lock,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
            )
            Text(
                text = stringResource(R.string.credit_card_encrypted),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

// ============================================
// 📝 备注卡片
// ============================================
@Composable
private fun NotesCard(notes: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.notes),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            SelectionContainer {
                Text(
                    text = notes,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun BoundNoteCard(
    boundNote: takagi.ru.monica.data.SecureItem?,
    hasBoundNoteReference: Boolean,
    onOpenBoundNote: () -> Unit,
    onChangeBoundNote: () -> Unit,
    onClearBoundNote: () -> Unit
) {
    val notePreview = remember(boundNote) {
        boundNote?.let { note ->
            val decoded = NoteContentCodec.decodeFromItem(note)
            NoteContentCodec.toPlainPreview(
                content = decoded.content,
                isMarkdown = decoded.isMarkdown
            ).replace("\n", " ").trim()
        }.orEmpty()
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.password_bound_note_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }

            if (boundNote != null) {
                Surface(
                    onClick = onOpenBoundNote,
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = boundNote.title.ifBlank { stringResource(R.string.untitled) },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        if (notePreview.isNotBlank()) {
                            Text(
                                text = notePreview,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.82f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onOpenBoundNote) {
                        Text(stringResource(R.string.open_bound_note))
                    }
                    OutlinedButton(onClick = onChangeBoundNote) {
                        Text(stringResource(R.string.change_bound_note))
                    }
                    TextButton(onClick = onClearBoundNote) {
                        Text(stringResource(R.string.unbind_note))
                    }
                }
            } else if (hasBoundNoteReference) {
                Text(
                    text = stringResource(R.string.bound_note_missing_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onChangeBoundNote) {
                        Text(stringResource(R.string.change_bound_note))
                    }
                    TextButton(onClick = onClearBoundNote) {
                        Text(stringResource(R.string.unbind_note))
                    }
                }
            } else {
                Text(
                    text = stringResource(R.string.password_bound_note_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FilledTonalButton(onClick = onChangeBoundNote) {
                    Text(stringResource(R.string.bind_note))
                }
            }
        }
    }
}

@Composable
private fun CustomFieldsCard(
    fields: List<CustomField>,
    visibilityState: MutableMap<Long, Boolean>,
    context: Context,
    onCreateSend: ((title: String, text: String) -> Unit)?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.EditNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.custom_field_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }

            fields.forEachIndexed { index, field ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }

                val label = field.title.ifBlank { stringResource(R.string.custom_field_new_field) }
                val isVisible = visibilityState[field.id] ?: false

                if (field.isProtected) {
                    PasswordField(
                        label = label,
                        value = field.value,
                        visible = isVisible,
                        onToggleVisibility = {
                            visibilityState[field.id] = !isVisible
                        },
                        context = context,
                        onCreateSend = onCreateSend
                    )
                } else {
                    InfoFieldWithCopy(
                        label = label,
                        value = field.value,
                        context = context,
                        onCreateSend = onCreateSend
                    )
                }
            }
        }
    }
}

// ============================================
// 🔧 可折叠区块组件
// ============================================
@Composable
private fun CollapsibleSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 标题栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                MonicaExpansionChevron(
                    expanded = expanded,
                    contentDescription = if (expanded) 
                        stringResource(R.string.collapse) 
                    else 
                        stringResource(R.string.expand),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // 内容区域 (带动画)
            MonicaExpandableContent(expanded = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                ) {
                    content()
                }
            }
        }
    }
}



// ============================================
// 🔧 辅助函数
// ============================================

private fun openWebsiteInBrowser(context: Context, website: String) {
    val target = normalizeWebsiteUrl(website) ?: return
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }.onFailure {
        Toast.makeText(
            context,
            context.getString(R.string.cannot_open_browser),
            Toast.LENGTH_SHORT
        ).show()
    }
}

internal fun normalizeWebsiteUrls(input: String): List<String> {
    return PasswordWebsiteCodec.normalizeForDisplay(input)
}

internal fun normalizeWebsiteUrl(input: String): String? {
    return PasswordWebsiteCodec.normalizeSingleOrNull(input)
}

private fun buildPasswordStorageInfo(
    entry: PasswordEntry,
    categories: List<takagi.ru.monica.data.Category>,
    keepassDatabases: List<takagi.ru.monica.data.LocalKeePassDatabase>,
    mdbxDatabases: List<takagi.ru.monica.data.LocalMdbxDatabase>,
    bitwardenVaults: List<takagi.ru.monica.data.bitwarden.BitwardenVault>,
    bitwardenFoldersByVault: Map<Long, List<BitwardenFolder>>,
    localSourceLabel: String,
    keepassSourceLabel: String,
    mdbxSourceLabel: String,
    bitwardenSourceLabel: String,
    passwordOwnerConflictShortLabel: String,
    passwordOwnerConflictDatabaseLabel: String,
    passwordOwnerConflictDisplayLabel: String,
    rootLabel: String
): PasswordStorageInfo {
    val containerKey = when {
        entry.hasOwnershipConflict() -> "conflict:${entry.id}"
        entry.bitwardenVaultId != null -> "bw:${entry.bitwardenVaultId}"
        entry.keepassDatabaseId != null -> "kp:${entry.keepassDatabaseId}"
        entry.mdbxDatabaseId != null -> "mdbx:${entry.mdbxDatabaseId}"
        else -> "local"
    }
    val categoryPath = categories.firstOrNull { it.id == entry.categoryId }?.name
    val keepassDatabaseName = keepassDatabases.firstOrNull { it.id == entry.keepassDatabaseId }?.name
    val mdbxDatabaseName = mdbxDatabases.firstOrNull { it.id == entry.mdbxDatabaseId }?.name
    val bitwardenVaultName = bitwardenVaults
        .firstOrNull { it.id == entry.bitwardenVaultId }
        ?.let { vault -> vault.displayName?.takeIf { it.isNotBlank() } ?: vault.email }
    val bitwardenFolderName = entry.bitwardenVaultId
        ?.let { vaultId ->
            bitwardenFoldersByVault[vaultId]
                ?.firstOrNull { it.bitwardenFolderId == entry.bitwardenFolderId }
                ?.name
        }

    val sourceName = when {
        entry.hasOwnershipConflict() -> passwordOwnerConflictShortLabel
        entry.isBitwardenEntry() -> bitwardenSourceLabel
        entry.isKeePassEntry() -> keepassSourceLabel
        entry.isMdbxEntry() -> mdbxSourceLabel
        else -> localSourceLabel
    }
    val databaseName = when {
        entry.hasOwnershipConflict() -> passwordOwnerConflictDatabaseLabel
        entry.isBitwardenEntry() -> bitwardenVaultName ?: bitwardenSourceLabel
        entry.isKeePassEntry() -> keepassDatabaseName ?: keepassSourceLabel
        entry.isMdbxEntry() -> mdbxDatabaseName ?: mdbxSourceLabel
        else -> localSourceLabel
    }
    val folderPath = when {
        entry.hasOwnershipConflict() -> passwordOwnerConflictDisplayLabel
        entry.isBitwardenEntry() -> bitwardenFolderName
            ?: entry.bitwardenFolderId
            ?: rootLabel
        entry.isKeePassEntry() -> entry.keepassGroupPath
            ?.takeIf { it.isNotBlank() }
            ?.let(::decodeKeePassPathForDisplay)
            ?: rootLabel
        entry.isMdbxEntry() -> categoryPath
            ?.takeIf { it.isNotBlank() }
            ?: rootLabel
        else -> categoryPath
            ?.takeIf { it.isNotBlank() }
            ?: rootLabel
    }

    return PasswordStorageInfo(
        entryId = entry.id,
        containerKey = containerKey,
        locationKey = "$containerKey|$folderPath",
        source = sourceName,
        database = databaseName,
        folderPath = folderPath
    )
}

/**
 * 检查是否有个人信息
 */
private fun hasPersonalInfo(entry: PasswordEntry): Boolean {
    return entry.email.isNotEmpty() || entry.phone.isNotEmpty()
}

/**
 * 检查是否有地址信息
 */
private fun hasAddressInfo(entry: PasswordEntry): Boolean {
    return entry.addressLine.isNotEmpty() ||
           entry.city.isNotEmpty() ||
           entry.state.isNotEmpty() ||
           entry.zipCode.isNotEmpty() ||
           entry.country.isNotEmpty()
}

/**
 * 检查是否有支付信息
 */
private fun hasPaymentInfo(entry: PasswordEntry): Boolean {
    return entry.creditCardNumber.isNotEmpty() ||
           entry.creditCardHolder.isNotEmpty() ||
           entry.creditCardExpiry.isNotEmpty() ||
           entry.creditCardCVV.isNotEmpty()
}

@Composable
private fun PasswordListCard(
    passwords: List<PasswordEntry>,
    displayPasswords: Map<Long, String>,
    unavailablePasswordSources: Map<Long, PasswordSource>,
    onResyncUnreadable: (PasswordEntry) -> Unit,
    isResyncingUnreadable: Boolean,
    showSecurityAnalysis: Boolean,
    onDelete: (PasswordEntry) -> Unit,
    context: Context,
    onCreateSend: ((title: String, text: String) -> Unit)?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.password),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )

            passwords.forEachIndexed { index, entry ->
                PasswordItemRow(
                    entry = entry,
                    displayPassword = displayPasswords[entry.id].orEmpty(),
                    unavailableSource = unavailablePasswordSources[entry.id],
                    onResyncUnreadable = { onResyncUnreadable(entry) },
                    isResyncingUnreadable = isResyncingUnreadable,
                    showSecurityAnalysis = showSecurityAnalysis,
                    index = index + 1,
                    showIndex = passwords.size > 1,
                    onDelete = { onDelete(entry) },
                    context = context,
                    canDelete = passwords.size > 1,
                    onCreateSend = onCreateSend
                )
                if (index < passwords.size - 1) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
internal fun PasswordItemRow(
    entry: PasswordEntry,
    displayPassword: String,
    unavailableSource: PasswordSource?,
    onResyncUnreadable: () -> Unit,
    isResyncingUnreadable: Boolean,
    showSecurityAnalysis: Boolean,
    index: Int,
    showIndex: Boolean,
    onDelete: () -> Unit,
    context: Context,
    canDelete: Boolean,
    onCreateSend: ((title: String, text: String) -> Unit)?
) {
    var visible by remember { mutableStateOf(false) }
    val actionMenuState = rememberPasswordFieldActionMenuState()
    val isUnavailable = unavailableSource != null
    val recoverableBitwardenSource = (unavailableSource as? PasswordSource.Bitwarden)
        ?.takeIf { it.vaultId != null && !it.cipherId.isNullOrBlank() }
    val isBitwardenUnreadable = recoverableBitwardenSource != null
    val unavailableMessage = when {
        isBitwardenUnreadable -> stringResource(R.string.bitwarden_password_unreadable_display)
        unavailableSource is PasswordSource.Conflict -> stringResource(R.string.password_owner_conflict_display)
        unavailableSource != null -> stringResource(R.string.password_unreadable_display)
        else -> stringResource(R.string.password_unreadable_display)
    }
    val unavailableCopyMessage = when {
        isBitwardenUnreadable -> stringResource(R.string.bitwarden_password_unreadable_copy)
        unavailableSource is PasswordSource.Conflict -> stringResource(R.string.password_owner_conflict_copy)
        unavailableSource != null -> stringResource(R.string.password_unreadable_copy)
        else -> stringResource(R.string.password_unreadable_copy)
    }
    val hasPasswordValue = displayPassword.isNotBlank()
    val strength = remember(displayPassword) {
        PasswordStrengthAnalyzer.calculateStrength(displayPassword)
    }
    val strengthLevel = remember(strength) {
        PasswordStrengthAnalyzer.getStrengthLevel(strength)
    }
    val strengthText = PasswordStrengthAnalyzer.getStrengthLevelText(strengthLevel, context)
    val strengthColor = when (strengthLevel) {
        PasswordStrengthAnalyzer.StrengthLevel.VERY_WEAK,
        PasswordStrengthAnalyzer.StrengthLevel.WEAK -> MaterialTheme.colorScheme.error
        PasswordStrengthAnalyzer.StrengthLevel.FAIR -> MaterialTheme.colorScheme.tertiary
        PasswordStrengthAnalyzer.StrengthLevel.STRONG -> MaterialTheme.colorScheme.secondary
        PasswordStrengthAnalyzer.StrengthLevel.VERY_STRONG -> MaterialTheme.colorScheme.primary
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (showIndex) stringResource(R.string.password) + " $index" else stringResource(R.string.password),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Row {
                if (hasPasswordValue) {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(
                            if (visible) MonicaIcons.Security.visibilityOff else MonicaIcons.Security.visibility,
                            contentDescription = stringResource(if (visible) R.string.hide else R.string.show),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                
                IconButton(onClick = {
                    if (isUnavailable || !hasPasswordValue) {
                        Toast.makeText(
                            context,
                            if (isUnavailable) unavailableCopyMessage else context.getString(R.string.permission_status_unavailable),
                            Toast.LENGTH_SHORT
                        ).show()
                        return@IconButton
                    }
                    ClipboardUtils.copyToClipboard(
                        context = context,
                        text = displayPassword,
                        label = context.getString(R.string.password),
                        sensitive = true
                    )
                    Toast.makeText(context, context.getString(R.string.password_copied), Toast.LENGTH_SHORT).show()
                }) {
                    Icon(MonicaIcons.Action.copy, contentDescription = null, modifier = Modifier.size(20.dp))
                }
                
                if (canDelete) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            MonicaIcons.Action.delete, 
                            contentDescription = null, 
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
        
        Box(Modifier.fillMaxWidth().animateMonicaContentSize()) {
            if (hasPasswordValue && !isUnavailable) {
                PasswordFieldActionMenuHost(
                    state = actionMenuState,
                    label = if (showIndex) stringResource(R.string.password) + " $index" else stringResource(R.string.password),
                    value = displayPassword,
                    displayValue = if (visible) displayPassword else "•".repeat(8),
                    context = context,
                    includeVisibilityToggle = true,
                    isVisible = visible,
                    onToggleVisibility = { visible = !visible },
                    onCreateSend = onCreateSend
                )
            }
            Text(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .clickable(enabled = hasPasswordValue && !isUnavailable) { actionMenuState.open() }
                .padding(vertical = 6.dp),
            text = when {
                isUnavailable -> unavailableMessage
                !hasPasswordValue -> stringResource(R.string.permission_status_unavailable)
                visible -> displayPassword
                else -> "•".repeat(8)
            },
            style = MaterialTheme.typography.bodyLarge.copy(
                fontFamily = if (visible && hasPasswordValue) androidx.compose.ui.text.font.FontFamily.Monospace else androidx.compose.ui.text.font.FontFamily.Default
            ),
            maxLines = if (visible || isUnavailable || !hasPasswordValue) Int.MAX_VALUE else 1,
            color = MaterialTheme.colorScheme.onSurface
            )
        }

        if (showSecurityAnalysis && hasPasswordValue && !isUnavailable) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = strengthColor.copy(alpha = 0.16f),
                contentColor = strengthColor
            ) {
                Text(
                    text = strengthText,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        if (isBitwardenUnreadable) {
            TextButton(
                onClick = onResyncUnreadable,
                enabled = !isResyncingUnreadable,
                contentPadding = PaddingValues(0.dp)
            ) {
                if (isResyncingUnreadable) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(stringResource(R.string.bitwarden_password_resync_action))
            }
        }
    }
}

// ============================================
// 🗑️ 多选删除确认对话框
// ============================================
@Composable
private fun MultiDeleteConfirmDialog(
    passwords: List<PasswordEntry>,
    selectedIds: Set<Long>,
    onSelectionChange: (Long, Boolean) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val allSelected = selectedIds.size == passwords.size && passwords.isNotEmpty()
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.multi_del_batch_delete)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                // 全选控制
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectAll(!allSelected) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    androidx.compose.material3.Checkbox(
                        checked = allSelected,
                        onCheckedChange = { onSelectAll(it) }
                    )
                    Text(
                        text = stringResource(R.string.multi_del_select_all),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                HorizontalDivider()
                
                // 密码列表
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp) // 限制最大高度
                ) {
                    items(passwords.size) { index ->
                        val password = passwords[index]
                        val isSelected = selectedIds.contains(password.id)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectionChange(password.id, !isSelected) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            androidx.compose.material3.Checkbox(
                                checked = isSelected,
                                onCheckedChange = { onSelectionChange(password.id, it) }
                            )
                            Column {
                                Text(
                                    text = if (password.username.isNotEmpty()) password.username else password.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "•".repeat(8),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                
                if (selectedIds.isEmpty()) {
                    Text(
                        text = stringResource(R.string.multi_del_select_items),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = selectedIds.isNotEmpty(),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(stringResource(R.string.delete) + if (selectedIds.isNotEmpty()) " (${selectedIds.size})" else "")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant
    )
}






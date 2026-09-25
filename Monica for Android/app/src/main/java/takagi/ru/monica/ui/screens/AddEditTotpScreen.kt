package takagi.ru.monica.ui.screens

import takagi.ru.monica.ui.components.MonicaExpandableCard
import takagi.ru.monica.ui.components.MonicaExpandableContent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import takagi.ru.monica.R
import takagi.ru.monica.autofill_ng.ui.rememberAppIcon
import takagi.ru.monica.autofill_ng.ui.rememberFavicon
import takagi.ru.monica.data.AppSettings
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.data.Category
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.LocalKeePassDatabase
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.data.model.OtpType
import takagi.ru.monica.data.model.StorageTarget
import takagi.ru.monica.data.model.TotpData
import takagi.ru.monica.data.model.normalizedStorageTargets
import takagi.ru.monica.data.model.toStorageTarget
import takagi.ru.monica.data.model.withStorageTargetSelected
import takagi.ru.monica.data.model.withoutStorageTarget
import takagi.ru.monica.ui.components.AppSelectorField
import takagi.ru.monica.ui.components.CustomIconActionDialog
import takagi.ru.monica.ui.components.InlineTotpPreviewCard
import takagi.ru.monica.ui.components.MultiStorageTargetPickerBottomSheet
import takagi.ru.monica.ui.components.MultiStorageTargetSelectorCard
import takagi.ru.monica.ui.components.MonicaExpressiveFilterChip
import takagi.ru.monica.ui.components.PasswordEntryPickerBottomSheet
import takagi.ru.monica.ui.components.SimpleIconPickerBottomSheet
import takagi.ru.monica.ui.components.TotpMigrationReviewDialog
import takagi.ru.monica.ui.components.buildMultiStorageTarget
import takagi.ru.monica.ui.components.migrationFailureMessageRes
import takagi.ru.monica.ui.icons.MonicaIcons
import takagi.ru.monica.ui.icons.PASSWORD_ICON_TYPE_NONE
import takagi.ru.monica.ui.icons.PASSWORD_ICON_TYPE_SIMPLE
import takagi.ru.monica.ui.icons.PASSWORD_ICON_TYPE_UPLOADED
import takagi.ru.monica.ui.icons.PasswordCustomIconStore
import takagi.ru.monica.ui.icons.SimpleIconCatalog
import takagi.ru.monica.ui.icons.rememberAutoMatchedSimpleIcon
import takagi.ru.monica.ui.icons.rememberSimpleIconBitmap
import takagi.ru.monica.ui.icons.rememberUploadedPasswordIcon
import takagi.ru.monica.util.TotpDataResolver
import takagi.ru.monica.util.TotpParseResult
import takagi.ru.monica.util.TotpScanParseResult
import takagi.ru.monica.util.TotpUriParser
import takagi.ru.monica.viewmodel.LocalKeePassViewModel
import takagi.ru.monica.viewmodel.PasswordViewModel
import takagi.ru.monica.viewmodel.TotpViewModel
import takagi.ru.monica.viewmodel.TotpMigrationSaveResult
import takagi.ru.monica.utils.RememberedStorageTarget
import takagi.ru.monica.utils.SettingsManager
import java.io.File
import takagi.ru.monica.ui.components.OutlinedTextField

private const val ICON_PICKER_PAGE_SIZE = 120

/**
 * 添加/编辑TOTP验证器页面 (Refactored to M3E)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditTotpScreen(
    totpId: Long?,
    initialData: TotpData?,
    initialTitle: String,
    initialNotes: String,
    initialCategoryId: Long? = null,
    initialStorageExplicit: Boolean = false,
    initialKeePassDatabaseId: Long? = null,
    initialKeePassGroupPath: String? = null,
    initialMdbxDatabaseId: Long? = null,
    initialBitwardenVaultId: Long? = null,
    initialBitwardenFolderId: String? = null,
    initialReplicaGroupId: String? = null,
    initialIsFavorite: Boolean = false,
    categories: List<Category> = emptyList(),
    passwordViewModel: PasswordViewModel? = null,
    totpViewModel: TotpViewModel? = null,
    localKeePassViewModel: LocalKeePassViewModel? = null,
    onSave: (
        title: String,
        notes: String,
        totpData: TotpData,
        isFavorite: Boolean,
        targets: List<StorageTarget>,
        onComplete: (Boolean) -> Unit
    ) -> Unit,
    onBatchImport: (
        items: List<TotpParseResult>,
        targets: List<StorageTarget>,
        onComplete: (TotpMigrationSaveResult) -> Unit
    ) -> Unit,
    onNavigateBack: () -> Unit,
    onScanQrCode: () -> Unit,
    pendingQrResult: String? = null,
    onConsumePendingQrResult: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val resolvedInitialData = remember(initialData) {
        initialData?.let(TotpDataResolver::normalizeTotpData)
    }
    var title by rememberSaveable { mutableStateOf(initialTitle) }
    var notes by rememberSaveable { mutableStateOf(initialNotes) }
    var secret by rememberSaveable { mutableStateOf(resolvedInitialData?.secret ?: "") }
    var issuer by rememberSaveable { mutableStateOf(resolvedInitialData?.issuer ?: "") }
    var accountName by rememberSaveable { mutableStateOf(resolvedInitialData?.accountName ?: "") }
    var period by rememberSaveable { mutableStateOf(resolvedInitialData?.period?.toString() ?: "30") }
    var digits by rememberSaveable { mutableStateOf(resolvedInitialData?.digits?.toString() ?: "6") }
    var algorithm by rememberSaveable { mutableStateOf(resolvedInitialData?.algorithm ?: "SHA1") }
    var selectedOtpType by rememberSaveable { mutableStateOf(resolvedInitialData?.otpType ?: OtpType.TOTP) }
    var counter by rememberSaveable { mutableStateOf(resolvedInitialData?.counter?.toString() ?: "0") }
    var pin by rememberSaveable { mutableStateOf(resolvedInitialData?.pin ?: "") }
    var isFavorite by rememberSaveable(totpId) { mutableStateOf(initialIsFavorite) }
    var selectedCategoryId by rememberSaveable { mutableStateOf(initialCategoryId) }
    var keepassGroupPath by rememberSaveable { mutableStateOf(initialKeePassGroupPath) }
    
    var link by rememberSaveable { mutableStateOf(resolvedInitialData?.link ?: "") }
    var associatedApp by rememberSaveable { mutableStateOf(resolvedInitialData?.associatedApp ?: "") }
    var associatedAppName by rememberSaveable { mutableStateOf("") }
    var boundPasswordId by remember { mutableStateOf(resolvedInitialData?.boundPasswordId) }
    var customIconType by rememberSaveable { mutableStateOf(resolvedInitialData?.customIconType ?: PASSWORD_ICON_TYPE_NONE) }
    var customIconValue by rememberSaveable {
        mutableStateOf(resolvedInitialData?.customIconValue?.takeIf { it.isNotBlank() }?.let { File(it).name })
    }
    var customIconUpdatedAt by rememberSaveable { mutableStateOf(resolvedInitialData?.customIconUpdatedAt ?: 0L) }
    var originalCustomIconType by remember { mutableStateOf(resolvedInitialData?.customIconType ?: PASSWORD_ICON_TYPE_NONE) }
    var originalCustomIconValue by remember {
        mutableStateOf(resolvedInitialData?.customIconValue?.takeIf { it.isNotBlank() }?.let { File(it).name })
    }
    var hasSavedSuccessfully by remember { mutableStateOf(false) }
    var showCustomIconDialog by remember { mutableStateOf(false) }
    var showSimpleIconPicker by remember { mutableStateOf(false) }
    var customIconSearchQuery by rememberSaveable { mutableStateOf("") }
    
    // KeePass Database Selection
    var keepassDatabaseId by rememberSaveable {
        mutableStateOf(resolvedInitialData?.keepassDatabaseId ?: initialKeePassDatabaseId)
    }
    val keepassDatabases by (localKeePassViewModel?.allDatabases ?: kotlinx.coroutines.flow.flowOf(emptyList())).collectAsState(initial = emptyList())
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val settingsManager = remember { SettingsManager(context) }
    val settings by settingsManager.settingsFlow.collectAsState(initial = AppSettings())
    val rememberedStorageTarget by settingsManager
        .rememberedStorageTargetFlow(SettingsManager.StorageTargetScope.TOTP)
        .collectAsState(initial = null as RememberedStorageTarget?)
    var bitwardenVaultId by rememberSaveable { mutableStateOf(initialBitwardenVaultId) }
    var bitwardenFolderId by rememberSaveable { mutableStateOf(initialBitwardenFolderId) }
    var mdbxDatabaseId by rememberSaveable { mutableStateOf(initialMdbxDatabaseId) }
    var hasAppliedInitialStorage by rememberSaveable { mutableStateOf(false) }
    val selectedStorageTargets = remember { mutableStateListOf<StorageTarget>() }
    var existingReplicaTargetKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var currentReplicaGroupId by rememberSaveable(totpId) { mutableStateOf(initialReplicaGroupId) }
    var showStorageTargetSheet by remember { mutableStateOf(false) }
    var hasLoadedExistingReplicaTargets by rememberSaveable(totpId) { mutableStateOf(false) }
    val bitwardenRepository = remember { BitwardenRepository.getInstance(context) }
    val bitwardenVaults by bitwardenRepository.getAllVaultsFlow().collectAsState(initial = emptyList())
    val database = remember { PasswordDatabase.getDatabase(context) }
    val mdbxDatabases by database.localMdbxDatabaseDao().getAvailableDatabases().collectAsState(initial = emptyList())
    val allTotpItems by (totpViewModel?.totpItems ?: kotlinx.coroutines.flow.flowOf(emptyList())).collectAsState(initial = emptyList())

    fun syncLegacyStorageState(targets: List<StorageTarget>) {
        when (val primaryTarget = targets.firstOrNull()) {
            is StorageTarget.MonicaLocal -> {
                selectedCategoryId = primaryTarget.categoryId
                keepassDatabaseId = null
                keepassGroupPath = null
                mdbxDatabaseId = null
                bitwardenVaultId = null
                bitwardenFolderId = null
            }
            is StorageTarget.KeePass -> {
                selectedCategoryId = null
                keepassDatabaseId = primaryTarget.databaseId
                keepassGroupPath = primaryTarget.groupPath
                mdbxDatabaseId = null
                bitwardenVaultId = null
                bitwardenFolderId = null
            }
            is StorageTarget.Mdbx -> {
                selectedCategoryId = null
                keepassDatabaseId = null
                keepassGroupPath = null
                mdbxDatabaseId = primaryTarget.databaseId
                bitwardenVaultId = null
                bitwardenFolderId = null
            }
            is StorageTarget.Bitwarden -> {
                selectedCategoryId = null
                keepassDatabaseId = null
                keepassGroupPath = null
                mdbxDatabaseId = null
                bitwardenVaultId = primaryTarget.vaultId
                bitwardenFolderId = primaryTarget.folderId
            }
            null -> {
                selectedCategoryId = null
                keepassDatabaseId = null
                keepassGroupPath = null
                mdbxDatabaseId = null
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

    fun normalizedIconFileName(value: String?): String? = value?.takeIf { it.isNotBlank() }?.let { File(it).name }
    fun isOriginalUploadedIconFile(value: String?): Boolean {
        val current = normalizedIconFileName(value)
        val original = normalizedIconFileName(originalCustomIconValue)
        return originalCustomIconType == PASSWORD_ICON_TYPE_UPLOADED &&
            !original.isNullOrBlank() &&
            current == original
    }

    val selectedSimpleIconBitmap = rememberSimpleIconBitmap(
        slug = if (customIconType == PASSWORD_ICON_TYPE_SIMPLE) customIconValue else null,
        tintColor = MaterialTheme.colorScheme.primary,
        enabled = settings.iconCardsEnabled
    )
    val selectedUploadedIconBitmap = rememberUploadedPasswordIcon(
        value = if (customIconType == PASSWORD_ICON_TYPE_UPLOADED) customIconValue else null
    )
    val autoMatchedSimpleIcon = rememberAutoMatchedSimpleIcon(
        website = link,
        title = issuer.ifBlank { title },
        appPackageName = associatedApp.ifBlank { null },
        tintColor = MaterialTheme.colorScheme.primary,
        enabled = settings.iconCardsEnabled && customIconType == PASSWORD_ICON_TYPE_NONE
    )
    val fallbackWebsiteFavicon = rememberFavicon(
        url = link,
        enabled = settings.iconCardsEnabled &&
            customIconType == PASSWORD_ICON_TYPE_NONE &&
            autoMatchedSimpleIcon.resolved &&
            autoMatchedSimpleIcon.slug == null
    )
    val associatedAppIcon = if (associatedApp.isNotBlank()) rememberAppIcon(associatedApp) else null

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            val imported = PasswordCustomIconStore.importAndCompress(context, uri)
            imported.onSuccess { fileName ->
                if (customIconType == PASSWORD_ICON_TYPE_UPLOADED && customIconValue != fileName) {
                    val previous = normalizedIconFileName(customIconValue)
                    if (!previous.isNullOrBlank() && !isOriginalUploadedIconFile(previous)) {
                        PasswordCustomIconStore.deleteIconFile(context, previous)
                    }
                }
                customIconType = PASSWORD_ICON_TYPE_UPLOADED
                customIconValue = fileName
                customIconUpdatedAt = System.currentTimeMillis()
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

    DisposableEffect(Unit) {
        onDispose {
            if (!hasSavedSuccessfully) {
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

    // Resolve App Name if associatedApp is set but name is unknown
    LaunchedEffect(associatedApp) {
        if (associatedApp.isNotEmpty() && associatedAppName.isEmpty()) {
            withContext(Dispatchers.IO) {
                try {
                    val pm = context.packageManager
                    val info = pm.getApplicationInfo(associatedApp, 0)
                    associatedAppName = pm.getApplicationLabel(info).toString()
                } catch (e: Exception) {
                    associatedAppName = associatedApp // Fallback
                }
            }
        }
    }
    
    var showAdvanced by remember { mutableStateOf(false) }
    var showAssociation by remember { mutableStateOf(false) }
    var expandedOtpType by remember { mutableStateOf(false) }
    var showPasswordSelectionDialog by remember { mutableStateOf(false) }
    var showImportUriDialog by remember { mutableStateOf(false) }
    var otpUriInput by rememberSaveable { mutableStateOf("") }
    var pendingMigrationItems by remember { mutableStateOf<List<TotpParseResult>?>(null) }
    var isMigrationSaving by remember { mutableStateOf(false) }
    
    // 防止重复点击保存按钮
    var isSaving by remember { mutableStateOf(false) }
    
    // 根据OTP类型自动调整digits
    LaunchedEffect(selectedOtpType) {
        when (selectedOtpType) {
            OtpType.STEAM -> digits = "5"
            OtpType.TOTP, OtpType.HOTP, OtpType.YANDEX, OtpType.MOTP -> {
                if (digits == "5") digits = "6"
            }
        }
    }
    
    val isEditing = totpId != null && totpId > 0
    LaunchedEffect(
        isEditing,
        hasAppliedInitialStorage,
        initialCategoryId,
        initialStorageExplicit,
        initialKeePassDatabaseId,
        initialKeePassGroupPath,
        initialMdbxDatabaseId,
        initialBitwardenVaultId,
        initialBitwardenFolderId,
        rememberedStorageTarget
    ) {
        if (isEditing || hasAppliedInitialStorage) return@LaunchedEffect
        val remembered = rememberedStorageTarget
        val explicitGroupPath = initialKeePassGroupPath?.takeIf { it.isNotBlank() }
        val explicitFolderId = initialBitwardenFolderId?.takeIf { it.isNotBlank() }
        val hasExplicitInitialStorage = initialStorageExplicit || initialCategoryId != null ||
            initialKeePassDatabaseId != null ||
            explicitGroupPath != null ||
            initialMdbxDatabaseId != null ||
            initialBitwardenVaultId != null ||
            explicitFolderId != null
        if (!hasExplicitInitialStorage && remembered == null) {
            setSelectedStorageTargets(listOf(StorageTarget.MonicaLocal(null)))
            hasAppliedInitialStorage = true
            return@LaunchedEffect
        }
        selectedCategoryId = if (hasExplicitInitialStorage) initialCategoryId else remembered?.categoryId
        keepassDatabaseId = if (hasExplicitInitialStorage) initialKeePassDatabaseId else remembered?.keepassDatabaseId
        keepassGroupPath = if (hasExplicitInitialStorage) explicitGroupPath else remembered?.keepassGroupPath
        mdbxDatabaseId = if (hasExplicitInitialStorage) initialMdbxDatabaseId else remembered?.mdbxDatabaseId
        bitwardenVaultId = if (hasExplicitInitialStorage) initialBitwardenVaultId else remembered?.bitwardenVaultId
        bitwardenFolderId = if (hasExplicitInitialStorage) explicitFolderId else remembered?.bitwardenFolderId
        setSelectedStorageTargets(
            listOf(
                buildMultiStorageTarget(
                    categoryId = selectedCategoryId,
                    keepassDatabaseId = keepassDatabaseId,
                    keepassGroupPath = keepassGroupPath,
                    mdbxDatabaseId = mdbxDatabaseId,
                    bitwardenVaultId = bitwardenVaultId,
                    bitwardenFolderId = bitwardenFolderId
                )
            )
        )
        hasAppliedInitialStorage = true
    }

    LaunchedEffect(
        isEditing,
        totpId,
        currentReplicaGroupId,
        allTotpItems,
        hasLoadedExistingReplicaTargets,
        selectedCategoryId,
        keepassDatabaseId,
        keepassGroupPath,
        mdbxDatabaseId,
        bitwardenVaultId,
        bitwardenFolderId
    ) {
        if (!isEditing || hasLoadedExistingReplicaTargets) return@LaunchedEffect
        if (totpId == null || totpId <= 0) return@LaunchedEffect
        if (totpViewModel != null && allTotpItems.none { it.id == totpId }) return@LaunchedEffect

        val storedTotpItems = allTotpItems.filter { it.id > 0 && it.itemType == ItemType.TOTP && !it.isDeleted }
        val currentItem = storedTotpItems.firstOrNull { it.id == totpId }
        val fallbackTarget = currentItem?.toStorageTarget() ?: buildMultiStorageTarget(
            categoryId = selectedCategoryId,
            keepassDatabaseId = keepassDatabaseId,
            keepassGroupPath = keepassGroupPath,
            mdbxDatabaseId = mdbxDatabaseId,
            bitwardenVaultId = bitwardenVaultId,
            bitwardenFolderId = bitwardenFolderId
        )
        val resolvedReplicaGroupId = currentItem?.replicaGroupId ?: currentReplicaGroupId
        currentReplicaGroupId = resolvedReplicaGroupId
        val selectedTargets = if (!resolvedReplicaGroupId.isNullOrBlank()) {
            storedTotpItems
                .filter { it.replicaGroupId == resolvedReplicaGroupId }
                .map { it.toStorageTarget() }
                .distinctBy(StorageTarget::stableKey)
                .ifEmpty { listOf(fallbackTarget) }
        } else {
            listOf(fallbackTarget)
        }
        setSelectedStorageTargets(selectedTargets)
        existingReplicaTargetKeys = selectedTargets.map(StorageTarget::stableKey).toSet()
        hasLoadedExistingReplicaTargets = true
    }

    fun resolveImportedTitle(item: TotpParseResult): String {
        return item.label.takeIf { it.isNotBlank() }
            ?: item.totpData.issuer.takeIf { it.isNotBlank() }
            ?: item.totpData.accountName.takeIf { it.isNotBlank() }
            ?: ""
    }

    fun applyImportedTotp(item: TotpParseResult) {
        val imported = item.totpData
        secret = imported.secret.trim().uppercase()
        issuer = imported.issuer
        accountName = imported.accountName
        period = imported.period.toString()
        digits = imported.digits.toString()
        algorithm = imported.algorithm
        selectedOtpType = imported.otpType
        counter = imported.counter.toString()
        pin = imported.pin

        val importedTitle = resolveImportedTitle(item)
        if (title.isBlank() && importedTitle.isNotBlank()) {
            title = importedTitle
        }
    }

    fun importTotpFromUri(raw: String) {
        val value = raw.trim()
        if (value.isBlank()) {
            Toast.makeText(context, context.getString(R.string.totp_import_uri_empty), Toast.LENGTH_SHORT).show()
            return
        }

        when (val scanResult = TotpUriParser.parseScannedContent(value)) {
            is TotpScanParseResult.Single -> {
                applyImportedTotp(scanResult.item)
                otpUriInput = ""
                showImportUriDialog = false
            }
            is TotpScanParseResult.Multiple -> {
                pendingMigrationItems = scanResult.items
                otpUriInput = ""
                showImportUriDialog = false
            }
            is TotpScanParseResult.MigrationFailure -> {
                Toast.makeText(
                    context,
                    context.getString(migrationFailureMessageRes(scanResult.reason)),
                    Toast.LENGTH_LONG
                ).show()
            }
            TotpScanParseResult.UnsupportedPhoneFactor -> {
                Toast.makeText(
                    context,
                    context.getString(R.string.qr_phonefactor_not_supported),
                    Toast.LENGTH_SHORT
                ).show()
            }
            TotpScanParseResult.InvalidFormat -> {
                Toast.makeText(
                    context,
                    context.getString(R.string.qr_invalid_authenticator),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    LaunchedEffect(pendingQrResult) {
        val qrValue = pendingQrResult ?: return@LaunchedEffect
        onConsumePendingQrResult()
        importTotpFromUri(qrValue)
    }

    val canSave = title.isNotBlank() && secret.isNotBlank()
    val previewTotpData = remember(secret, issuer, accountName, period, digits, algorithm, selectedOtpType, counter, pin) {
        buildInlinePreviewTotpData(
            secret = secret,
            issuer = issuer,
            accountName = accountName,
            period = period,
            digits = digits,
            algorithm = algorithm,
            otpType = selectedOtpType,
            counter = counter,
            pin = pin
        )
    }
    val inlinePreviewVisible = previewTotpData != null
    val inlinePreviewCurrentSeconds by produceState(initialValue = System.currentTimeMillis() / 1000, key1 = previewTotpData?.otpType) {
        value = System.currentTimeMillis() / 1000
        while (true) {
            value = System.currentTimeMillis() / 1000
            kotlinx.coroutines.delay(1000)
        }
    }
    val inlinePreviewProgressTimeMillis by produceState(
        initialValue = System.currentTimeMillis(),
        key1 = previewTotpData?.otpType,
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
    val save: () -> Unit = saveAction@{
        if (!canSave || isSaving) return@saveAction
        isSaving = true // 防止重复点击
        val effectiveTargets = selectedStorageTargets.toList().ifEmpty {
            listOf(
                buildMultiStorageTarget(
                    categoryId = selectedCategoryId,
                    keepassDatabaseId = keepassDatabaseId,
                    keepassGroupPath = keepassGroupPath,
                    mdbxDatabaseId = mdbxDatabaseId,
                    bitwardenVaultId = bitwardenVaultId,
                    bitwardenFolderId = bitwardenFolderId
                )
            )
        }
        val primaryTarget = effectiveTargets.first()
        val totpData = TotpData(
            secret = secret.trim(),
            issuer = issuer.trim(),
            accountName = accountName.trim(),
            period = period.toIntOrNull() ?: 30,
            digits = digits.toIntOrNull() ?: 6,
            algorithm = algorithm.trim().uppercase().ifBlank { "SHA1" },
            otpType = selectedOtpType,
            counter = counter.toLongOrNull() ?: 0L,
            pin = pin.trim(),
            link = link.trim(),
            associatedApp = associatedApp.trim(),
            customIconType = customIconType,
            customIconValue = normalizedIconFileName(customIconValue),
            customIconUpdatedAt = customIconUpdatedAt,
            boundPasswordId = boundPasswordId,
            categoryId = selectedCategoryId,
            keepassDatabaseId = keepassDatabaseId
        )
        val originalUploaded = if (originalCustomIconType == PASSWORD_ICON_TYPE_UPLOADED) {
            normalizedIconFileName(originalCustomIconValue)
        } else {
            null
        }
        val currentUploaded = if (customIconType == PASSWORD_ICON_TYPE_UPLOADED) {
            normalizedIconFileName(customIconValue)
        } else {
            null
        }
        if (!originalUploaded.isNullOrBlank() && originalUploaded != currentUploaded) {
            PasswordCustomIconStore.deleteIconFile(context, originalUploaded)
        }
        onSave(title, notes, totpData, isFavorite, effectiveTargets) { saved ->
            if (!saved) {
                isSaving = false
                Toast.makeText(context, context.getString(R.string.save_failed), Toast.LENGTH_SHORT).show()
                return@onSave
            }
            isSaving = false
            hasSavedSuccessfully = true
            coroutineScope.launch {
                settingsManager.updateRememberedStorageTarget(
                    scope = SettingsManager.StorageTargetScope.TOTP,
                    target = RememberedStorageTarget(
                        categoryId = (primaryTarget as? StorageTarget.MonicaLocal)?.categoryId,
                        keepassDatabaseId = (primaryTarget as? StorageTarget.KeePass)?.databaseId,
                        keepassGroupPath = (primaryTarget as? StorageTarget.KeePass)?.groupPath,
                        mdbxDatabaseId = (primaryTarget as? StorageTarget.Mdbx)?.databaseId,
                        bitwardenVaultId = (primaryTarget as? StorageTarget.Bitwarden)?.vaultId,
                        bitwardenFolderId = (primaryTarget as? StorageTarget.Bitwarden)?.folderId
                    )
                )
            }
        }
    }
    
    val topBarTitle = stringResource(if (isEditing) R.string.edit_totp_title else R.string.add_totp_title)

    Scaffold(
        topBar = {
            TopAppBar(
                    title = { Text(topBarTitle) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    },
                    actions = {
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
            FloatingActionButton(
                onClick = save,
                containerColor = if (canSave && !isSaving) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (canSave && !isSaving) {
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
    ) { paddingValues ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .imePadding()
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                // Vault Selector
                item {
                    MultiStorageTargetSelectorCard(
                        selectedTargets = selectedStorageTargets,
                        existingTargetKeys = existingReplicaTargetKeys,
                        categories = categories,
                        keepassDatabases = keepassDatabases,
                        mdbxDatabases = mdbxDatabases,
                        bitwardenVaults = bitwardenVaults,
                        bitwardenFolderDao = database.bitwardenFolderDao(),
                        isEditing = isEditing,
                        onAddTargetClick = { showStorageTargetSheet = true },
                        onRemoveTarget = ::removeSelectedStorageTarget
                    )
                }

            // Basic Info Card
            item {
                InfoCard(title = stringResource(R.string.section_authenticator_info)) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                                        associatedAppIcon != null -> {
                                            Image(
                                                bitmap = associatedAppIcon,
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
                                    label = { Text(stringResource(R.string.totp_name_required)) },
                                    placeholder = { Text(stringResource(R.string.totp_name_example)) },
                                    leadingIcon = { Icon(Icons.Default.Label, contentDescription = null) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    isError = title.isBlank(),
                                    shape = RoundedCornerShape(12.dp)
                                )
                            }
                        } else {
                            OutlinedTextField(
                                value = title,
                                onValueChange = { title = it },
                                label = { Text(stringResource(R.string.totp_name_required)) },
                                placeholder = { Text(stringResource(R.string.totp_name_example)) },
                                leadingIcon = { Icon(Icons.Default.Label, contentDescription = null) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                isError = title.isBlank(),
                                shape = RoundedCornerShape(12.dp)
                            )
                        }
                        
                        if (title.isBlank()) {
                            Text(
                                text = stringResource(R.string.enter_name),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }

                        // Secret Key + Scan
                        Column {
                            OutlinedTextField(
                                value = secret,
                                onValueChange = { secret = it.uppercase() },
                                label = { Text(stringResource(R.string.secret_key_required)) },
                                placeholder = { Text(stringResource(R.string.secret_key_example)) },
                                leadingIcon = { Icon(Icons.Default.VpnKey, contentDescription = null) },
                                modifier = Modifier.fillMaxWidth(),
                                isError = secret.isBlank(),
                                shape = RoundedCornerShape(12.dp)
                            )
                            
                            Text(
                                text = stringResource(R.string.secret_key_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (secret.isBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                            )

                            MonicaExpandableContent(
                                expanded = inlinePreviewVisible
                            ) {
                                previewTotpData?.let { previewData ->
                                    InlineTotpPreviewCard(
                                        totpData = previewData,
                                        currentSeconds = inlinePreviewCurrentSeconds,
                                        progressTimeMillis = inlinePreviewProgressTimeMillis,
                                        timeOffset = settings.totpTimeOffset,
                                        smoothProgress = settings.validatorSmoothProgress,
                                        modifier = Modifier.padding(top = 10.dp),
                                        showHeader = false,
                                        showProgress = false
                                    )
                                }
                            }

                            if (!isEditing) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    FilledTonalButton(
                                        onClick = onScanQrCode,
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.QrCodeScanner,
                                            contentDescription = null
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(text = stringResource(R.string.scan_qr_code))
                                    }
                                    OutlinedButton(
                                        onClick = { showImportUriDialog = true },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Link,
                                            contentDescription = null
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(text = stringResource(R.string.totp_import_uri_action))
                                    }
                                }
                            }
                        }
                        
                        // Issuer
                        OutlinedTextField(
                            value = issuer,
                            onValueChange = { issuer = it },
                            label = { Text(stringResource(R.string.issuer)) },
                            placeholder = { Text(stringResource(R.string.issuer_example)) },
                            leadingIcon = { Icon(Icons.Default.Business, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )

                        // Account Name
                        OutlinedTextField(
                            value = accountName,
                            onValueChange = { accountName = it },
                            label = { Text(stringResource(R.string.account_name)) },
                            placeholder = { Text(stringResource(R.string.account_name_example)) },
                            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }
            }

            // Notes Card
            item {
                InfoCard(title = stringResource(R.string.section_notes)) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Notes
                        OutlinedTextField(
                            value = notes,
                            onValueChange = { notes = it },
                            label = { Text(stringResource(R.string.notes)) },
                            placeholder = { Text(stringResource(R.string.notes_optional)) },
                            leadingIcon = { Icon(Icons.Default.Notes, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth().height(100.dp),
                            minLines = 2,
                            maxLines = 4,
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }
            }

            // Advanced Options
            item {
                MonicaExpandableCard(
                    title = stringResource(R.string.advanced_options),
                    icon = Icons.Default.Settings,
                    expanded = showAdvanced,
                    onExpandedChange = { showAdvanced = it }
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // OTP Type
                        ExposedDropdownMenuBox(
                            expanded = expandedOtpType,
                            onExpandedChange = { expandedOtpType = it }
                        ) {
                            OutlinedTextField(
                                value = when (selectedOtpType) {
                                    OtpType.TOTP -> stringResource(R.string.otp_type_totp)
                                    OtpType.HOTP -> stringResource(R.string.otp_type_hotp)
                                    OtpType.STEAM -> stringResource(R.string.otp_type_steam)
                                    OtpType.YANDEX -> stringResource(R.string.otp_type_yandex)
                                    OtpType.MOTP -> stringResource(R.string.otp_type_motp)
                                },
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(stringResource(R.string.otp_type)) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedOtpType) },
                                leadingIcon = { Icon(Icons.Default.Category, contentDescription = null) },
                                modifier = Modifier.menuAnchor().fillMaxWidth(),
                                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                                shape = RoundedCornerShape(12.dp)
                            )
                            
                            ExposedDropdownMenu(
                                expanded = expandedOtpType,
                                onDismissRequest = { expandedOtpType = false }
                            ) {
                                val types = listOf(
                                    Triple(OtpType.TOTP, R.string.otp_type_totp, R.string.otp_type_description_totp),
                                    Triple(OtpType.HOTP, R.string.otp_type_hotp, R.string.otp_type_description_hotp),
                                    Triple(OtpType.STEAM, R.string.otp_type_steam, R.string.otp_type_description_steam),
                                    Triple(OtpType.YANDEX, R.string.otp_type_yandex, R.string.otp_type_description_yandex),
                                    Triple(OtpType.MOTP, R.string.otp_type_motp, R.string.otp_type_description_motp)
                                )
                                
                                types.forEach { (type, nameRes, descRes) ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(stringResource(nameRes))
                                                Text(
                                                    stringResource(descRes),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        },
                                        onClick = {
                                            selectedOtpType = type
                                            expandedOtpType = false
                                        }
                                    )
                                }
                            }
                        }

                        // HOTP Counter
                        if (selectedOtpType == OtpType.HOTP) {
                            OutlinedTextField(
                                value = counter,
                                onValueChange = { counter = it.filter { char -> char.isDigit() } },
                                label = { Text(stringResource(R.string.initial_counter)) },
                                leadingIcon = { Icon(Icons.Default.Numbers, contentDescription = null) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                supportingText = { Text(stringResource(R.string.hotp_counter_hint)) },
                                shape = RoundedCornerShape(12.dp)
                            )
                        }

                        // mOTP PIN
                        if (selectedOtpType == OtpType.MOTP) {
                            OutlinedTextField(
                                value = pin,
                                onValueChange = { if (it.length <= 4 && it.all { char -> char.isDigit() }) pin = it },
                                label = { Text(stringResource(R.string.pin_code)) },
                                leadingIcon = { Icon(Icons.Default.Pin, contentDescription = null) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                supportingText = { Text(stringResource(R.string.motp_pin_hint)) },
                                isError = pin.isEmpty(),
                                shape = RoundedCornerShape(12.dp)
                            )
                        }

                        // Period
                        if (selectedOtpType != OtpType.HOTP) {
                            OutlinedTextField(
                                value = period,
                                onValueChange = { period = it.filter { char -> char.isDigit() } },
                                label = { Text(stringResource(R.string.time_period_seconds)) },
                                leadingIcon = { Icon(Icons.Default.Timer, contentDescription = null) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                supportingText = { Text(stringResource(R.string.usually_30_seconds)) },
                                shape = RoundedCornerShape(12.dp)
                            )
                        }

                        // Digits
                        OutlinedTextField(
                            value = digits,
                            onValueChange = { 
                                val newValue = it.filter { char -> char.isDigit() }
                                if (newValue.isEmpty() || newValue.toInt() in 5..8) {
                                    digits = newValue
                                }
                            },
                            label = { Text(stringResource(R.string.code_digits)) },
                            leadingIcon = { Icon(Icons.Default.Dialpad, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = selectedOtpType != OtpType.STEAM,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            supportingText = { 
                                Text(
                                    if (selectedOtpType == OtpType.STEAM) 
                                        stringResource(R.string.steam_uses_5_chars)
                                    else 
                                        stringResource(R.string.usually_6_digits)
                                )
                            },
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }
            }

            // Association Options
            item {
                MonicaExpandableCard(
                    title = stringResource(R.string.association_options),
                    icon = Icons.Default.Link,
                    expanded = showAssociation,
                    onExpandedChange = { showAssociation = it }
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = link,
                            onValueChange = { link = it },
                            label = { Text(stringResource(R.string.associated_link)) },
                            placeholder = { Text(stringResource(R.string.link_example)) },
                            leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )

                        AppSelectorField(
                            selectedPackageName = associatedApp,
                            selectedAppName = associatedAppName,
                            onAppSelected = { packageName, name ->
                                associatedApp = packageName
                                associatedAppName = name
                            }
                        )

                        if (passwordViewModel != null) {
                            OutlinedButton(
                                onClick = { showPasswordSelectionDialog = true },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Link, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(if (boundPasswordId != null) stringResource(R.string.bound_password_change) else stringResource(R.string.bind_password))
                            }
                            
                            if (boundPasswordId != null) {
                                TextButton(
                                    onClick = { 
                                        boundPasswordId = null
                                        link = ""
                                        associatedApp = ""
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.unbind), color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
            }
        }
    }

    if (showImportUriDialog) {
        AlertDialog(
            onDismissRequest = { showImportUriDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Link,
                    contentDescription = null
                )
            },
            title = {
                Text(text = stringResource(R.string.totp_import_uri_dialog_title))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.totp_import_uri_dialog_supporting),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = otpUriInput,
                        onValueChange = { otpUriInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.totp_import_uri_dialog_label)) },
                        placeholder = { Text(stringResource(R.string.totp_import_uri_dialog_hint)) },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Done
                        ),
                        minLines = 2,
                        maxLines = 4,
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { importTotpFromUri(otpUriInput) }) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportUriDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    pendingMigrationItems?.let { migrationItems ->
        TotpMigrationReviewDialog(
            items = migrationItems,
            existingTitleFor = { data ->
                totpViewModel
                    ?.findTotpByData(data, selectedStorageTargets)
                    ?.title
            },
            isSaving = isMigrationSaving,
            onDismiss = {
                pendingMigrationItems = null
                isMigrationSaving = false
            },
            onImport = { selectedItems ->
                isMigrationSaving = true
                onBatchImport(selectedItems, selectedStorageTargets.toList()) { result ->
                    isMigrationSaving = false
                    Toast.makeText(
                        context,
                        context.getString(
                            R.string.qr_authenticator_migration_result,
                            result.importedCount,
                            result.duplicateCount,
                            result.failedCount
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                    if (result.importedCount > 0) {
                        pendingMigrationItems = null
                        onNavigateBack()
                    }
                }
            }
        )
    }

    if (showCustomIconDialog) {
        CustomIconActionDialog(
            showEmojiAction = false,
            showClearAction = customIconType != PASSWORD_ICON_TYPE_NONE,
            onPickFromLibrary = {
                customIconSearchQuery = ""
                showCustomIconDialog = false
                showSimpleIconPicker = true
            },
            onUploadImage = {
                showCustomIconDialog = false
                imagePickerLauncher.launch("image/*")
            },
            onClearIcon = {
                val currentUploaded = if (customIconType == PASSWORD_ICON_TYPE_UPLOADED) {
                    normalizedIconFileName(customIconValue)
                } else {
                    null
                }
                if (!currentUploaded.isNullOrBlank() && !isOriginalUploadedIconFile(currentUploaded)) {
                    PasswordCustomIconStore.deleteIconFile(context, currentUploaded)
                }
                customIconType = PASSWORD_ICON_TYPE_NONE
                customIconValue = null
                customIconUpdatedAt = System.currentTimeMillis()
                showCustomIconDialog = false
            },
            onDismissRequest = { showCustomIconDialog = false }
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
                customIconSearchQuery = it
                iconVisibleCount = ICON_PICKER_PAGE_SIZE
            },
            iconOptions = iconOptions,
            visibleOptions = visibleOptions,
            hasMore = visibleOptions.size < iconOptions.size,
            remainingCount = iconOptions.size - visibleOptions.size,
            iconCardsEnabled = settings.iconCardsEnabled,
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
                customIconType = PASSWORD_ICON_TYPE_SIMPLE
                customIconValue = option.slug
                customIconUpdatedAt = System.currentTimeMillis()
                showSimpleIconPicker = false
            },
            onLoadMore = {
                iconVisibleCount = (iconVisibleCount + ICON_PICKER_PAGE_SIZE)
                    .coerceAtMost(iconOptions.size)
            },
            onDismissRequest = { showSimpleIconPicker = false }
        )
    }

    MultiStorageTargetPickerBottomSheet(
        visible = showStorageTargetSheet,
        selectedTargets = selectedStorageTargets.toList(),
        lockedTargetKeys = existingReplicaTargetKeys,
        categories = categories,
        keepassDatabases = keepassDatabases,
        mdbxDatabases = mdbxDatabases,
        bitwardenVaults = bitwardenVaults,
        getBitwardenFolders = { vaultId -> database.bitwardenFolderDao().getFoldersByVaultFlow(vaultId) },
        getKeePassGroups = { databaseId ->
            localKeePassViewModel?.getGroups(databaseId) ?: kotlinx.coroutines.flow.flowOf(emptyList())
        },
        onDismiss = { showStorageTargetSheet = false },
        onSelectedTargetsChange = ::setSelectedStorageTargets
    )

    if (showPasswordSelectionDialog && passwordViewModel != null) {
        val passwords by passwordViewModel.allPasswords.collectAsState(initial = emptyList())
        PasswordEntryPickerBottomSheet(
            visible = true,
            title = stringResource(R.string.select_password_to_bind),
            passwords = passwords.filter { !it.isDeleted && !it.isArchived },
            selectedEntryId = boundPasswordId,
            onDismiss = { showPasswordSelectionDialog = false },
            onSelect = { password ->
                boundPasswordId = password.id
                link = password.website
                associatedApp = password.appPackageName
                associatedAppName = password.appName
                showPasswordSelectionDialog = false
            }
        )
    }
}

// ------------------------------------------------------------------------------------------------
// Reusing Components from AddEditPasswordScreen (duplicated primarily to avoid tight coupling 
// if a common component lib isn't established yet, but mirroring style)
// ------------------------------------------------------------------------------------------------

@Composable
private fun InfoCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            content()
        }
    }
}

private fun buildInlinePreviewTotpData(
    secret: String,
    issuer: String,
    accountName: String,
    period: String,
    digits: String,
    algorithm: String,
    otpType: OtpType,
    counter: String,
    pin: String
): TotpData? {
    val normalizedSecret = secret
        .uppercase()
        .filter { it in 'A'..'Z' || it in '2'..'7' }
    if (normalizedSecret.isBlank()) return null

    val resolvedPeriod = when (otpType) {
        OtpType.HOTP -> 30
        else -> period.toIntOrNull()?.takeIf { it > 0 } ?: 30
    }
    val resolvedDigits = when (otpType) {
        OtpType.STEAM -> 5
        else -> digits.toIntOrNull()?.takeIf { it in 5..8 } ?: 6
    }
    val resolvedCounter = counter.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
    val resolvedPin = pin.trim()

    return normalizeInlinePreviewTotpData(
        TotpData(
            secret = normalizedSecret,
            issuer = issuer.trim(),
            accountName = accountName.trim(),
            period = resolvedPeriod,
            digits = resolvedDigits,
            algorithm = algorithm.trim().uppercase().ifBlank { "SHA1" },
            otpType = otpType,
            counter = resolvedCounter,
            pin = resolvedPin
        )
    )
}

private fun normalizeInlinePreviewTotpData(data: TotpData): TotpData {
    val safePeriod = data.period.takeIf { it > 0 } ?: 30
    val safeDigits = when (data.otpType) {
        OtpType.STEAM -> 5
        else -> data.digits.coerceIn(5, 8)
    }
    return if (safePeriod == data.period && safeDigits == data.digits) {
        data
    } else {
        data.copy(period = safePeriod, digits = safeDigits)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VaultSelector(
    keepassDatabases: List<LocalKeePassDatabase>,
    selectedDatabaseId: Long?,
    onDatabaseSelected: (Long?) -> Unit
) {
    // 如果没有 KeePass 数据库，不显示选择器
    if (keepassDatabases.isEmpty()) return
    
    var showBottomSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    
    val selectedDatabase = keepassDatabases.find { it.id == selectedDatabaseId }
    val displayName = selectedDatabase?.name ?: stringResource(R.string.vault_monica_only)
    val isKeePass = selectedDatabase != null
    
    // M3E 风格的卡片选择器
    Surface(
        onClick = { showBottomSheet = true },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = if (isKeePass) 
            MaterialTheme.colorScheme.primaryContainer 
        else 
            MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // M3E 风格图标容器
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (isKeePass) 
                    MaterialTheme.colorScheme.primary 
                else 
                    MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(48.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Icon(
                        imageVector = if (isKeePass) Icons.Default.Key else Icons.Default.Shield,
                        contentDescription = null,
                        tint = if (isKeePass)
                            MaterialTheme.colorScheme.onPrimary
                        else
                            MaterialTheme.colorScheme.onSecondary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            
            // 文字区域
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isKeePass)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = if (isKeePass) 
                        stringResource(R.string.vault_sync_hint)
                    else 
                        stringResource(R.string.vault_monica_only_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isKeePass)
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    else
                        MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                )
            }
            
            // 展开图标
            Icon(
                imageVector = Icons.Default.UnfoldMore,
                contentDescription = null,
                tint = if (isKeePass)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
    
    // M3E 风格的 BottomSheet 选择器
    if (showBottomSheet) {
        ModalBottomSheet(
            onDismissRequest = { showBottomSheet = false },
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 标题
                Text(
                    text = stringResource(R.string.vault_select_storage),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                // Monica 本地存储选项
                VaultOptionItem(
                    title = stringResource(R.string.vault_monica_only),
                    subtitle = stringResource(R.string.vault_monica_only_desc),
                    icon = Icons.Default.Shield,
                    isSelected = selectedDatabaseId == null,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    iconColor = MaterialTheme.colorScheme.secondary,
                    onClick = {
                        onDatabaseSelected(null)
                        showBottomSheet = false
                    }
                )
                
                // KeePass 数据库选项
                keepassDatabases.forEach { database ->
                    val isSelected = selectedDatabaseId == database.id
                    val storageText = if (database.storageLocation == takagi.ru.monica.data.KeePassStorageLocation.EXTERNAL)
                        stringResource(R.string.external_storage)
                    else
                        stringResource(R.string.internal_storage)
                    
                    VaultOptionItem(
                        title = database.name,
                        subtitle = "$storageText · ${stringResource(R.string.vault_sync_hint)}",
                        icon = Icons.Default.Key,
                        isSelected = isSelected,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        iconColor = MaterialTheme.colorScheme.primary,
                        onClick = {
                            onDatabaseSelected(database.id)
                            showBottomSheet = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun VaultOptionItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    isSelected: Boolean,
    containerColor: Color,
    contentColor: Color,
    iconColor: Color,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = if (isSelected) containerColor else MaterialTheme.colorScheme.surfaceContainerLow,
        border = if (isSelected) null else androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant
        ),
        tonalElevation = if (isSelected) 4.dp else 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 图标
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (isSelected) iconColor else MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.size(44.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isSelected) 
                            MaterialTheme.colorScheme.surface 
                        else 
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            
            // 文字
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (isSelected) contentColor else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isSelected) 
                        contentColor.copy(alpha = 0.7f) 
                    else 
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // 选中指示
            if (isSelected) {
                Surface(
                    shape = CircleShape,
                    color = iconColor,
                    modifier = Modifier.size(24.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}


package takagi.ru.monica.ui.screens

import android.widget.Toast
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import takagi.ru.monica.R
import takagi.ru.monica.attachments.AttachmentContainer
import takagi.ru.monica.attachments.facade.AttachmentFacade
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.attachments.model.AttachmentError
import takagi.ru.monica.attachments.model.AttachmentSource
import takagi.ru.monica.attachments.ui.AttachmentPendingDraft
import takagi.ru.monica.attachments.ui.AttachmentsEditSection
import takagi.ru.monica.attachments.ui.flushPendingDraftsTo
import takagi.ru.monica.bitwarden.BitwardenVaultPremiumStore
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.data.AppSettings
import takagi.ru.monica.data.CommonAccountPreferences
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.data.model.BankCardData
import takagi.ru.monica.data.model.BillingAddress
import takagi.ru.monica.data.model.CardBrandDetector
import takagi.ru.monica.data.model.CardWalletDataCodec
import takagi.ru.monica.data.model.CardType
import takagi.ru.monica.data.model.CardFaceConfig
import takagi.ru.monica.data.model.StorageTarget
import takagi.ru.monica.data.model.normalizedStorageTargets
import takagi.ru.monica.data.model.formatForDisplay
import takagi.ru.monica.data.model.isEmpty
import takagi.ru.monica.data.model.toStorageTarget
import takagi.ru.monica.data.model.withStorageTargetSelected
import takagi.ru.monica.data.model.withoutStorageTarget
import takagi.ru.monica.data.CustomFieldDraft
import takagi.ru.monica.keepass.KeePassSecureItemPhotoAttachments
import takagi.ru.monica.ui.components.CommonNameSuggestion
import takagi.ru.monica.ui.components.CommonNameSuggestionState
import takagi.ru.monica.ui.components.CommonNameSuggestionSource
import takagi.ru.monica.ui.components.CommonNameSuggestionSheet
import takagi.ru.monica.ui.components.CustomFieldEditorSection
import takagi.ru.monica.ui.components.DualPhotoPicker
import takagi.ru.monica.ui.components.MultiStorageTargetPickerBottomSheet
import takagi.ru.monica.ui.components.MultiStorageTargetSelectorCard
import takagi.ru.monica.ui.components.MonicaExpressiveFilterChip
import takagi.ru.monica.ui.components.buildMultiStorageTarget
import takagi.ru.monica.ui.components.rememberCommonNameSuggestionState
import takagi.ru.monica.ui.cardwallet.CardBrandIcon
import takagi.ru.monica.ui.cardwallet.BankCardFaceArtwork
import takagi.ru.monica.ui.cardwallet.CardFaceEditorEntry
import takagi.ru.monica.ui.cardwallet.bankCardFacePreviewData
import takagi.ru.monica.ui.cardwallet.CardFaceCustomizer
import takagi.ru.monica.ui.cardwallet.CardFaceEditResult
import takagi.ru.monica.ui.cardwallet.CardFaceImageProcessor
import takagi.ru.monica.ui.cardwallet.rememberCardFaceBitmap
import takagi.ru.monica.ui.cardwallet.resolveCardWalletInitialStorageTarget
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.utils.RememberedStorageTarget
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.viewmodel.BankCardViewModel
import takagi.ru.monica.viewmodel.LocalKeePassViewModel
import takagi.ru.monica.ui.components.OutlinedTextField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditBankCardScreen(
    viewModel: BankCardViewModel,
    cardId: Long? = null,
    onNavigateBack: () -> Unit,
    initialCategoryId: Long? = null,
    initialKeePassDatabaseId: Long? = null,
    initialKeePassGroupPath: String? = null,
    initialMdbxDatabaseId: Long? = null,
    initialMdbxFolderId: String? = null,
    initialBitwardenVaultId: Long? = null,
    initialBitwardenFolderId: String? = null,
    showTypeSwitcher: Boolean = false,
    onSwitchToDocument: (() -> Unit)? = null,
    showTopBar: Boolean = true,
    showFab: Boolean = true,
    onFavoriteStateChanged: ((Boolean) -> Unit)? = null,
    onCanSaveChanged: ((Boolean) -> Unit)? = null,
    onSaveActionChanged: (((() -> Unit)) -> Unit)? = null,
    onToggleFavoriteActionChanged: (((() -> Unit)) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val database = remember { PasswordDatabase.getDatabase(context) }
    val securityManager = remember { SecurityManager(context) }
    val bitwardenRepository = remember { BitwardenRepository.getInstance(context) }
    val localKeePassViewModel: LocalKeePassViewModel = viewModel {
        LocalKeePassViewModel(
            context.applicationContext as android.app.Application,
            database.localKeePassDatabaseDao(),
            securityManager
        )
    }
    val settingsManager = remember { SettingsManager(context) }
    val appSettings by settingsManager.settingsFlow.collectAsState(initial = AppSettings())
    val commonAccountPreferences = remember { CommonAccountPreferences(context) }
    val commonBillingAddress by commonAccountPreferences.billingAddress.collectAsState(initial = BillingAddress())
    val hasCommonBillingAddress = !commonBillingAddress.isEmpty()
    
    var title by rememberSaveable { mutableStateOf("") }
    var cardNumber by rememberSaveable { mutableStateOf("") }
    var cardholderName by rememberSaveable { mutableStateOf("") }
    var expiryMonth by rememberSaveable { mutableStateOf("") }
    var expiryYear by rememberSaveable { mutableStateOf("") }
    var cvv by rememberSaveable { mutableStateOf("") }
    var bankName by rememberSaveable { mutableStateOf("") }
    var cardType by rememberSaveable { mutableStateOf(CardType.DEBIT) }
    var brand by rememberSaveable { mutableStateOf("") }
    var nickname by rememberSaveable { mutableStateOf("") }
    var validFromMonth by rememberSaveable { mutableStateOf("") }
    var validFromYear by rememberSaveable { mutableStateOf("") }
    var pin by rememberSaveable { mutableStateOf("") }
    var iban by rememberSaveable { mutableStateOf("") }
    var swiftBic by rememberSaveable { mutableStateOf("") }
    var routingNumber by rememberSaveable { mutableStateOf("") }
    var accountNumber by rememberSaveable { mutableStateOf("") }
    var branchCode by rememberSaveable { mutableStateOf("") }
    var currency by rememberSaveable { mutableStateOf("") }
    var customerServicePhone by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    var isFavorite by rememberSaveable { mutableStateOf(false) }
    var showCardTypeMenu by remember { mutableStateOf(false) }
    var showCardNumber by remember { mutableStateOf(false) }
    var showCvv by remember { mutableStateOf(false) }
    var showCommonNamePicker by rememberSaveable { mutableStateOf(false) }
    var isCardholderNameFocused by remember { mutableStateOf(false) }
    var hasBillingAddress by remember { mutableStateOf(false) }
    var billingAddress by remember { mutableStateOf(BillingAddress()) }
    var showBillingAddressDialog by remember { mutableStateOf(false) }
    var customFields by remember { mutableStateOf<List<CustomFieldDraft>>(emptyList()) }
    var cardFaceConfig by remember { mutableStateOf<CardFaceConfig?>(null) }
    var originalCardFaceConfig by remember { mutableStateOf<CardFaceConfig?>(null) }
    var pendingCardFaceBytes by remember { mutableStateOf<ByteArray?>(null) }
    var pendingCardFacePreview by remember { mutableStateOf<Bitmap?>(null) }
    var showCardFaceCustomizer by remember { mutableStateOf(false) }
    val pendingAttachmentDrafts = remember { mutableStateListOf<AttachmentPendingDraft>() }
    var existingCardItem by remember(cardId) { mutableStateOf<SecureItem?>(null) }
    var shouldLoadCommonNameAnalysis by rememberSaveable { mutableStateOf(false) }
    
    // 防止重复点击保存按钮
    var isSaving by remember { mutableStateOf(false) }
    var workingCardId by remember(cardId) { mutableStateOf(cardId) }
    
    // 图片路径管理
    var frontImageFileName by rememberSaveable { mutableStateOf<String?>(null) }
    var backImageFileName by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedCategoryId by rememberSaveable { mutableStateOf<Long?>(null) }
    var keepassDatabaseId by rememberSaveable { mutableStateOf<Long?>(null) }
    var keepassGroupPath by rememberSaveable { mutableStateOf<String?>(null) }
    var mdbxDatabaseId by rememberSaveable { mutableStateOf(initialMdbxDatabaseId) }
    var mdbxFolderId by rememberSaveable { mutableStateOf(initialMdbxFolderId) }
    var bitwardenVaultId by rememberSaveable { mutableStateOf<Long?>(null) }
    var bitwardenFolderId by rememberSaveable { mutableStateOf<String?>(null) }
    var hasAppliedInitialStorage by rememberSaveable { mutableStateOf(false) }
    val selectedStorageTargets = remember { mutableStateListOf<StorageTarget>() }
    var existingReplicaTargetKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var currentReplicaGroupId by rememberSaveable { mutableStateOf<String?>(null) }
    var showStorageTargetSheet by remember { mutableStateOf(false) }
    var hasLoadedExistingCardFields by rememberSaveable(cardId) { mutableStateOf(false) }
    val detectedCardBrand = remember(cardNumber, brand) {
        CardBrandDetector.detect(
            number = cardNumber,
            storedBrand = brand
        )
    }
    val commonNameSuggestions = rememberCommonNameSuggestionState(
        database = database,
        includeAnalyzedItems = shouldLoadCommonNameAnalysis || showCommonNamePicker
    )
    val commonNameType = stringResource(R.string.common_account_type_name)
    val inlineCardholderSuggestion = remember(commonNameSuggestions) {
        commonNameSuggestions.firstInlineSuggestion()
    }
    val inlineCardholderSuggestionVisible = isCardholderNameFocused &&
        cardholderName.isBlank() &&
        inlineCardholderSuggestion != null
    val showCommonNameAction = !shouldLoadCommonNameAnalysis ||
        commonNameSuggestions.hasAny ||
        cardholderName.isNotBlank()
    val categories by database.categoryDao().getAllCategories().collectAsState(initial = emptyList())
    val keepassDatabases by database.localKeePassDatabaseDao().getAllDatabases().collectAsState(initial = emptyList())
    val mdbxDatabases by database.localMdbxDatabaseDao().getAllDatabases().collectAsState(initial = emptyList())
    val bitwardenVaults by database.bitwardenVaultDao().getAllVaultsFlow().collectAsState(initial = emptyList())
    val allCardsFlow = remember(cardId, viewModel) {
        if (cardId != null) viewModel.allCards else flowOf(emptyList())
    }
    val allCards by allCardsFlow.collectAsState(initial = emptyList())
    val attachmentBitwardenVault = remember(existingCardItem?.bitwardenVaultId, bitwardenVaults) {
        existingCardItem?.bitwardenVaultId?.let { vaultId ->
            bitwardenVaults.firstOrNull { it.id == vaultId }
        }
    }
    val attachmentBitwardenContext = remember(
        attachmentBitwardenVault,
        existingCardItem?.bitwardenCipherId
    ) {
        attachmentBitwardenVault?.let { vault ->
            viewModel.getAttachmentBitwardenContext(vault, existingCardItem?.bitwardenCipherId)
        }
    }
    val attachmentKeePassContext = remember(
        existingCardItem?.keepassDatabaseId,
        existingCardItem?.keepassEntryUuid
    ) {
        val databaseId = existingCardItem?.keepassDatabaseId
        val entryUuid = existingCardItem?.keepassEntryUuid?.takeIf { it.isNotBlank() }
        if (databaseId != null && entryUuid != null) {
            AttachmentFacade.KeePassContext(databaseId = databaseId, entryUuid = entryUuid)
        } else {
            null
        }
    }
    fun syncLegacyStorageState(targets: List<StorageTarget>) {
        when (val primaryTarget = targets.firstOrNull()) {
            is StorageTarget.MonicaLocal -> {
                selectedCategoryId = primaryTarget.categoryId
                keepassDatabaseId = null
                keepassGroupPath = null
                mdbxDatabaseId = null
                mdbxFolderId = null
                bitwardenVaultId = null
                bitwardenFolderId = null
            }
            is StorageTarget.KeePass -> {
                selectedCategoryId = null
                keepassDatabaseId = primaryTarget.databaseId
                keepassGroupPath = primaryTarget.groupPath
                mdbxDatabaseId = null
                mdbxFolderId = null
                bitwardenVaultId = null
                bitwardenFolderId = null
            }
            is StorageTarget.Mdbx -> {
                selectedCategoryId = null
                keepassDatabaseId = null
                keepassGroupPath = null
                mdbxDatabaseId = primaryTarget.databaseId
                mdbxFolderId = primaryTarget.folderId
                bitwardenVaultId = null
                bitwardenFolderId = null
            }
            is StorageTarget.Bitwarden -> {
                selectedCategoryId = null
                keepassDatabaseId = null
                keepassGroupPath = null
                mdbxDatabaseId = null
                mdbxFolderId = null
                bitwardenVaultId = primaryTarget.vaultId
                bitwardenFolderId = primaryTarget.folderId
            }
            null -> {
                selectedCategoryId = null
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

    LaunchedEffect(
        cardId,
        hasAppliedInitialStorage,
        initialCategoryId,
        initialKeePassDatabaseId,
        initialKeePassGroupPath,
        initialMdbxDatabaseId,
        initialMdbxFolderId,
        initialBitwardenVaultId,
        initialBitwardenFolderId
    ) {
        if (cardId != null || hasAppliedInitialStorage) return@LaunchedEffect
        val explicitGroupPath = initialKeePassGroupPath?.takeIf { it.isNotBlank() }
        val explicitMdbxFolderId = initialMdbxFolderId?.takeIf { it.isNotBlank() }
        val explicitFolderId = initialBitwardenFolderId?.takeIf { it.isNotBlank() }
        val hasExplicitInitialStorage = initialCategoryId != null ||
            initialKeePassDatabaseId != null ||
            explicitGroupPath != null ||
            initialMdbxDatabaseId != null ||
            explicitMdbxFolderId != null ||
            initialBitwardenVaultId != null ||
            explicitFolderId != null
        val explicitTarget = if (hasExplicitInitialStorage) {
            buildMultiStorageTarget(
                categoryId = initialCategoryId,
                keepassDatabaseId = initialKeePassDatabaseId,
                keepassGroupPath = explicitGroupPath,
                mdbxDatabaseId = initialMdbxDatabaseId,
                mdbxFolderId = explicitMdbxFolderId,
                bitwardenVaultId = initialBitwardenVaultId,
                bitwardenFolderId = explicitFolderId
            )
        } else {
            null
        }
        val filterState = settingsManager
            .categoryFilterStateFlow(SettingsManager.CategoryFilterScope.CARD_WALLET)
            .first()
        val rememberedTarget = settingsManager
            .rememberedStorageTargetFlow(SettingsManager.StorageTargetScope.BANK_CARD)
            .first()
        setSelectedStorageTargets(
            listOf(
                resolveCardWalletInitialStorageTarget(
                    explicitTarget = explicitTarget,
                    filterState = filterState,
                    rememberedTarget = rememberedTarget
                )
            )
        )
        hasAppliedInitialStorage = true
    }
    
    // 如果是编辑模式，加载现有数据
    // 如果是添加模式，重置表单字段（防止保留上次添加的数据）
    LaunchedEffect(cardId) {
        if (cardId != null) {
            if (hasLoadedExistingCardFields) return@LaunchedEffect
            withContext(Dispatchers.IO) {
                viewModel.getCardById(cardId)
            }?.let { item ->
                existingCardItem = item
                val parsedImagePaths = withContext(Dispatchers.Default) {
                    parseSecureItemImagePaths(item.imagePaths)
                }
                val parsedCardData = withContext(Dispatchers.Default) {
                    viewModel.parseCardData(item.itemData)
                }
                title = item.title
                notes = item.notes
                isFavorite = item.isFavorite
                selectedCategoryId = item.categoryId
                keepassDatabaseId = item.keepassDatabaseId
                keepassGroupPath = item.keepassGroupPath
                bitwardenVaultId = item.bitwardenVaultId
                bitwardenFolderId = item.bitwardenFolderId
                currentReplicaGroupId = item.replicaGroupId
                frontImageFileName = parsedImagePaths.first
                backImageFileName = parsedImagePaths.second

                parsedCardData?.let { data ->
                    cardNumber = data.cardNumber
                    cardholderName = data.cardholderName
                    expiryMonth = data.expiryMonth
                    expiryYear = data.expiryYear
                    cvv = data.cvv
                    bankName = data.bankName
                    cardType = data.cardType
                    brand = data.brand
                    nickname = data.nickname
                    validFromMonth = data.validFromMonth
                    validFromYear = data.validFromYear
                    pin = data.pin
                    iban = data.iban
                    swiftBic = data.swiftBic
                    routingNumber = data.routingNumber
                    accountNumber = data.accountNumber
                    branchCode = data.branchCode
                    currency = data.currency
                    customerServicePhone = data.customerServicePhone
                    customFields = CardWalletDataCodec.customFieldsToDrafts(data.customFields)
                    cardFaceConfig = data.cardFace
                    originalCardFaceConfig = data.cardFace
                    if (data.billingAddress.isNotBlank()) {
                        billingAddress = CardWalletDataCodec.parseBillingAddress(data.billingAddress)
                        hasBillingAddress = !billingAddress.isEmpty()
                    } else {
                        billingAddress = BillingAddress()
                        hasBillingAddress = false
                    }
                }

                setSelectedStorageTargets(listOf(item.toStorageTarget()))
                hasLoadedExistingCardFields = true
            }
        } else {
            existingCardItem = null
            hasLoadedExistingCardFields = false
            currentReplicaGroupId = null
            existingReplicaTargetKeys = emptySet()
            // 添加模式：重置表单字段
            title = ""
            cardNumber = ""
            cardholderName = ""
            expiryMonth = ""
            expiryYear = ""
            cvv = ""
            bankName = ""
            cardType = CardType.DEBIT
            brand = ""
            nickname = ""
            validFromMonth = ""
            validFromYear = ""
            pin = ""
            iban = ""
            swiftBic = ""
            routingNumber = ""
            accountNumber = ""
            branchCode = ""
            currency = ""
            customerServicePhone = ""
            notes = ""
            isFavorite = false
            hasBillingAddress = false
            billingAddress = BillingAddress()
            customFields = emptyList()
            cardFaceConfig = null
            originalCardFaceConfig = null
            pendingCardFaceBytes?.fill(0)
            pendingCardFaceBytes = null
            pendingCardFacePreview = null
            frontImageFileName = null
            backImageFileName = null
        }
    }

    LaunchedEffect(
        existingCardItem?.id,
        existingCardItem?.keepassDatabaseId,
        existingCardItem?.keepassEntryUuid
    ) {
        val item = existingCardItem ?: return@LaunchedEffect
        if (item.keepassDatabaseId == null || item.keepassEntryUuid.isNullOrBlank()) {
            return@LaunchedEffect
        }
        launch(Dispatchers.IO) {
            runCatching {
                AttachmentContainer.keepassReconciler(context).reconcile(
                    owner = AttachmentOwner.secureItem(item.id),
                    databaseId = item.keepassDatabaseId,
                    entryUuid = item.keepassEntryUuid,
                    excludedFileNames = KeePassSecureItemPhotoAttachments.managedFileNames(ItemType.BANK_CARD)
                )
            }.onFailure { error ->
                android.util.Log.w(
                    "AddEditBankCardScreen",
                    "KeePass attachment metadata reconcile failed: ${error::class.simpleName}"
                )
            }
        }
    }

    LaunchedEffect(cardId, allCards, currentReplicaGroupId, hasLoadedExistingCardFields) {
        if (cardId == null || !hasLoadedExistingCardFields) return@LaunchedEffect
        val currentItem = viewModel.getCardById(cardId) ?: return@LaunchedEffect
        val selectedTargets = if (!currentReplicaGroupId.isNullOrBlank()) {
            allCards
                .filter { replica ->
                    replica.replicaGroupId == currentReplicaGroupId && !replica.isDeleted
                }
                .map { it.toStorageTarget() }
                .distinctBy(StorageTarget::stableKey)
                .ifEmpty { listOf(currentItem.toStorageTarget()) }
        } else {
            listOf(currentItem.toStorageTarget())
        }
        setSelectedStorageTargets(selectedTargets)
        existingReplicaTargetKeys = selectedTargets.map(StorageTarget::stableKey).toSet()
    }

    val isExistingCardReady = cardId == null || hasLoadedExistingCardFields
    val displayedCardFaceBitmap = rememberCardFaceBitmap(
        item = existingCardItem,
        imageAttachmentName = cardFaceConfig?.imageAttachmentName,
        overrideBitmap = pendingCardFacePreview,
        maxDimension = 1200
    )
    val unsupportedBitwardenCardFaceTarget = selectedStorageTargets
        .filterIsInstance<StorageTarget.Bitwarden>()
        .firstOrNull { target ->
            bitwardenVaults.firstOrNull { it.id == target.vaultId }?.let { vault ->
                !BitwardenVaultPremiumStore.isPremium(context, vault.id)
            } ?: true
        }

    fun currentCardData(): BankCardData {
        val billingAddressJson = if (hasBillingAddress) {
            CardWalletDataCodec.encodeBillingAddress(billingAddress)
        } else {
            ""
        }
        return BankCardData(
            cardNumber = cardNumber,
            cardholderName = cardholderName,
            expiryMonth = expiryMonth,
            expiryYear = expiryYear,
            cvv = cvv,
            bankName = bankName,
            cardType = cardType,
            billingAddress = billingAddressJson,
            brand = brand,
            nickname = nickname,
            validFromMonth = validFromMonth,
            validFromYear = validFromYear,
            pin = pin,
            iban = iban,
            swiftBic = swiftBic,
            routingNumber = routingNumber,
            accountNumber = accountNumber,
            branchCode = branchCode,
            currency = currency,
            customerServicePhone = customerServicePhone,
            customFields = CardWalletDataCodec.draftsToCustomFields(customFields),
            cardFace = cardFaceConfig
        )
    }
    val canSave = isExistingCardReady && cardNumber.isNotBlank() && !isSaving
    val save: () -> Unit = saveAction@{
        if (!isExistingCardReady || isSaving || cardNumber.isBlank()) return@saveAction
        isSaving = true // 防止重复点击
        val availableMdbxDatabaseIds = mdbxDatabases.map { it.id }.toSet()
        val effectiveTargets = selectedStorageTargets
            .toList()
            .filterNot { target ->
                target is StorageTarget.Mdbx && target.databaseId !in availableMdbxDatabaseIds
            }
            .ifEmpty {
            listOf(
                buildMultiStorageTarget(
                    categoryId = selectedCategoryId,
                    keepassDatabaseId = keepassDatabaseId,
                    keepassGroupPath = keepassGroupPath,
                    mdbxDatabaseId = mdbxDatabaseId,
                    mdbxFolderId = mdbxFolderId,
                    bitwardenVaultId = bitwardenVaultId,
                    bitwardenFolderId = bitwardenFolderId
                )
            )
        }
            .filterNot { target ->
                target is StorageTarget.Mdbx && target.databaseId !in availableMdbxDatabaseIds
            }
            .normalizedStorageTargets()
        val primaryTarget = effectiveTargets.first()
        val syncVaultIds = effectiveTargets
            .filterIsInstance<StorageTarget.Bitwarden>()
            .map { it.vaultId }
            .distinct()

        if (pendingCardFaceBytes != null && unsupportedBitwardenCardFaceTarget != null) {
            isSaving = false
            Toast.makeText(
                context,
                context.getString(R.string.card_face_bitwarden_premium_required),
                Toast.LENGTH_LONG
            ).show()
            return@saveAction
        }

        val cardData = currentCardData()

        val imagePathsList = listOf(
            frontImageFileName ?: "",
            backImageFileName ?: ""
        )
        val imagePathsJson = Json.encodeToString(imagePathsList)

        val shouldFlushAttachmentDrafts = pendingAttachmentDrafts.isNotEmpty()
        viewModel.saveCardAcrossTargets(
            id = workingCardId,
            title = title.ifBlank { context.getString(R.string.bank_card_default_title) },
            cardData = cardData,
            notes = notes,
            isFavorite = isFavorite,
            imagePaths = imagePathsJson,
            targets = effectiveTargets,
            cardFaceImageBytes = pendingCardFaceBytes,
            onPrimaryCreated = { workingCardId = it },
            onPrimarySaved = if (shouldFlushAttachmentDrafts) {
                { newId ->
                    val savedItem = viewModel.getCardById(newId)
                    val savedKeePassContext = savedItem?.let { item ->
                        val databaseId = item.keepassDatabaseId
                        val entryUuid = item.keepassEntryUuid?.takeIf { it.isNotBlank() }
                        if (databaseId != null && entryUuid != null) {
                            AttachmentFacade.KeePassContext(databaseId, entryUuid)
                        } else {
                            null
                        }
                    }
                    val savedVault = savedItem?.bitwardenVaultId?.let { vaultId ->
                        bitwardenVaults.firstOrNull { it.id == vaultId }
                    }
                    val savedBitwardenContext = savedVault?.let { vault ->
                        viewModel.getAttachmentBitwardenContext(vault, savedItem.bitwardenCipherId)
                    }
                    if (shouldFlushAttachmentDrafts) {
                        flushPendingDraftsTo(
                            context = context,
                            owner = AttachmentOwner.secureItem(newId),
                            pendingDrafts = pendingAttachmentDrafts,
                            isPlusActivated = appSettings.isPlusActivated,
                            attachmentSource = when {
                                savedBitwardenContext != null -> AttachmentSource.BITWARDEN
                                savedKeePassContext != null -> AttachmentSource.KEEPASS
                                else -> AttachmentSource.LOCAL
                            },
                            bitwardenContext = savedBitwardenContext,
                            bitwardenPremium = savedVault?.let {
                                BitwardenVaultPremiumStore.isPremium(context, it.id)
                            } ?: true,
                            keepassContext = savedKeePassContext
                        )
                    }

                }
            } else {
                {}
            },
            onComplete = { result ->
                isSaving = false
                if (result.isSuccess) {
                    pendingCardFaceBytes?.fill(0)
                    pendingCardFaceBytes = null
                    onNavigateBack()
                } else {
                    val message = if (result.exceptionOrNull() is AttachmentError.PremiumRequired)
                        R.string.card_face_bitwarden_premium_required else R.string.card_face_save_failed
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            }
        )
        coroutineScope.launch {
            settingsManager.updateRememberedStorageTarget(
                scope = SettingsManager.StorageTargetScope.BANK_CARD,
                target = RememberedStorageTarget(
                    categoryId = (primaryTarget as? StorageTarget.MonicaLocal)?.categoryId,
                    keepassDatabaseId = (primaryTarget as? StorageTarget.KeePass)?.databaseId,
                    keepassGroupPath = (primaryTarget as? StorageTarget.KeePass)?.groupPath,
                    mdbxDatabaseId = (primaryTarget as? StorageTarget.Mdbx)?.databaseId,
                    mdbxFolderId = (primaryTarget as? StorageTarget.Mdbx)?.folderId,
                    bitwardenVaultId = (primaryTarget as? StorageTarget.Bitwarden)?.vaultId,
                    bitwardenFolderId = (primaryTarget as? StorageTarget.Bitwarden)?.folderId
                )
            )
        }

    }
    val toggleFavoriteAction: () -> Unit = {
        val updated = !isFavorite
        isFavorite = updated
        onFavoriteStateChanged?.invoke(updated)
    }

    SideEffect {
        onFavoriteStateChanged?.invoke(isFavorite)
        onCanSaveChanged?.invoke(canSave)
        onSaveActionChanged?.invoke(save)
        onToggleFavoriteActionChanged?.invoke(toggleFavoriteAction)
    }
    val screenContent: @Composable (PaddingValues) -> Unit = { paddingValues ->
        if (!isExistingCardReady) {
            BankCardEditLoadingPlaceholder(
                modifier = modifier,
                paddingValues = paddingValues
            )
        } else {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                MultiStorageTargetSelectorCard(
                    selectedTargets = selectedStorageTargets,
                    existingTargetKeys = existingReplicaTargetKeys,
                    categories = categories,
                    keepassDatabases = keepassDatabases,
                    mdbxDatabases = mdbxDatabases,
                    bitwardenVaults = bitwardenVaults,
                    bitwardenFolderDao = database.bitwardenFolderDao(),
                    isEditing = cardId != null,
                    onAddTargetClick = { showStorageTargetSheet = true },
                    onRemoveTarget = ::removeSelectedStorageTarget
                )

                CardFaceEditorEntry(
                    config = cardFaceConfig,
                    bitmap = displayedCardFaceBitmap,
                    previewData = bankCardFacePreviewData(
                        title.ifBlank { stringResource(R.string.bank_card_default_title) },
                        currentCardData()
                    ),
                    onClick = { showCardFaceCustomizer = true },
                    enabled = !isSaving
                )

                // Basic Information
                InfoCard(title = stringResource(R.string.section_basic_info)) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Card Name
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text(stringResource(R.string.card_name)) },
                        placeholder = { Text(stringResource(R.string.card_name_example)) },
                        leadingIcon = { Icon(Icons.Default.Label, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    
                    // Bank Name
                    OutlinedTextField(
                        value = bankName,
                        onValueChange = { bankName = it },
                        label = { Text(stringResource(R.string.bank_name)) },
                        placeholder = { Text(stringResource(R.string.bank_name_example)) },
                        leadingIcon = { Icon(Icons.Default.AccountBalance, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    
                    // Card Type
                    ExposedDropdownMenuBox(
                        expanded = showCardTypeMenu,
                        onExpandedChange = { showCardTypeMenu = it }
                    ) {
                        OutlinedTextField(
                            value = when (cardType) {
                                CardType.CREDIT -> stringResource(R.string.credit_card)
                                CardType.DEBIT -> stringResource(R.string.debit_card)
                                CardType.PREPAID -> stringResource(R.string.prepaid_card)
                            },
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.card_type)) },
                            leadingIcon = { Icon(Icons.Default.CreditCard, contentDescription = null) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showCardTypeMenu) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                        )
                        
                        ExposedDropdownMenu(
                            expanded = showCardTypeMenu,
                            onDismissRequest = { showCardTypeMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.debit_card)) },
                                onClick = {
                                    cardType = CardType.DEBIT
                                    showCardTypeMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.credit_card)) },
                                onClick = {
                                    cardType = CardType.CREDIT
                                    showCardTypeMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.prepaid_card)) },
                                onClick = {
                                    cardType = CardType.PREPAID
                                    showCardTypeMenu = false
                                }
                            )
                        }
                    }
                    
                    // Card Number
                    OutlinedTextField(
                        value = cardNumber,
                        onValueChange = { 
                            // Only allow digits and spaces
                            cardNumber = it.filter { char -> char.isDigit() || char == ' ' }
                        },
                        label = { Text(stringResource(R.string.card_number_required)) },
                        placeholder = { Text("1234 5678 9012 3456") },
                        leadingIcon = {
                            CardBrandIcon(
                                brand = detectedCardBrand,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(width = 32.dp, height = 20.dp)
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = { showCardNumber = !showCardNumber }) {
                                Icon(
                                    if (showCardNumber) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = stringResource(if (showCardNumber) R.string.hide_password else R.string.show_password)
                                )
                            }
                        },
                        visualTransformation = if (showCardNumber) {
                            androidx.compose.ui.text.input.VisualTransformation.None
                        } else {
                            androidx.compose.ui.text.input.PasswordVisualTransformation()
                        },
                        shape = RoundedCornerShape(12.dp)
                    )
                    
                    // Cardholder Name
                    OutlinedTextField(
                        value = cardholderName,
                        onValueChange = { cardholderName = it },
                        label = { Text(stringResource(R.string.cardholder_name)) },
                        placeholder = { Text("ZHANG SAN") },
                        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                        trailingIcon = {
                            if (showCommonNameAction) {
                                IconButton(onClick = {
                                    shouldLoadCommonNameAnalysis = true
                                    showCommonNamePicker = true
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.PersonAdd,
                                        contentDescription = stringResource(R.string.common_name_fill_title),
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { focusState ->
                                isCardholderNameFocused = focusState.isFocused
                                if (focusState.isFocused) {
                                    shouldLoadCommonNameAnalysis = true
                                }
                            },
                        singleLine = true,
                        keyboardActions = KeyboardActions(
                            onDone = { isCardholderNameFocused = false }
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )

                    AnimatedVisibility(
                        visible = inlineCardholderSuggestionVisible,
                        enter = slideInVertically(
                            animationSpec = tween(
                                durationMillis = 240,
                                easing = FastOutSlowInEasing
                            ),
                            initialOffsetY = { -it / 2 }
                        ) +
                            fadeIn(animationSpec = tween(180)) +
                            expandVertically(
                                expandFrom = Alignment.Top,
                                animationSpec = tween(
                                    durationMillis = 240,
                                    easing = FastOutSlowInEasing
                                )
                            ),
                        exit = slideOutVertically(
                            animationSpec = tween(
                                durationMillis = 160,
                                easing = FastOutSlowInEasing
                            ),
                            targetOffsetY = { -it / 4 }
                        ) +
                            fadeOut(animationSpec = tween(120)) +
                            shrinkVertically(
                                shrinkTowards = Alignment.Top,
                                animationSpec = tween(
                                    durationMillis = 160,
                                    easing = FastOutSlowInEasing
                                )
                            )
                    ) {
                        inlineCardholderSuggestion?.let { suggestion ->
                            InlineCommonNameSuggestionCard(
                                suggestion = suggestion,
                                onApply = {
                                    cardholderName = suggestion.name
                                }
                            )
                        }
                    }
                    
                    // Expiry
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = expiryMonth,
                            onValueChange = { 
                                if (it.length <= 2 && it.all { char -> char.isDigit() }) {
                                    expiryMonth = it
                                }
                            },
                            label = { Text(stringResource(R.string.month)) },
                            placeholder = { Text("12") },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )
                        
                        OutlinedTextField(
                            value = expiryYear,
                            onValueChange = { 
                                if (it.length <= 4 && it.all { char -> char.isDigit() }) {
                                    expiryYear = it
                                }
                            },
                            label = { Text(stringResource(R.string.year)) },
                            placeholder = { Text("2025") },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                    
                    // CVV
                    OutlinedTextField(
                        value = cvv,
                        onValueChange = { 
                            if (it.length <= 4 && it.all { char -> char.isDigit() }) {
                                cvv = it
                            }
                        },
                        label = { Text(stringResource(R.string.cvv)) },
                        placeholder = { Text("123") },
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = { showCvv = !showCvv }) {
                                Icon(
                                    if (showCvv) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = stringResource(if (showCvv) R.string.hide_password else R.string.show_password)
                                )
                            }
                        },
                        visualTransformation = if (showCvv) {
                            androidx.compose.ui.text.input.VisualTransformation.None
                        } else {
                            androidx.compose.ui.text.input.PasswordVisualTransformation()
                        },
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }

            // Billing Address Card
            InfoCard(title = stringResource(R.string.billing_address)) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (hasBillingAddress && !billingAddress.isEmpty()) {
                        Text(
                            text = billingAddress.formatForDisplay(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { showBillingAddressDialog = true },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = stringResource(R.string.edit)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.edit_billing_address))
                            }

                            TextButton(
                                onClick = {
                                    billingAddress = BillingAddress()
                                    hasBillingAddress = false
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.billing_address_removed),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.delete)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.remove_billing_address))
                            }
                        }

                        if (hasCommonBillingAddress) {
                            OutlinedButton(
                                onClick = {
                                    billingAddress = commonBillingAddress
                                    hasBillingAddress = true
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.common_account_billing_filled),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AccountCircle,
                                    contentDescription = null
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.common_account_billing_use_saved))
                            }
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.billing_address_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        OutlinedButton(
                            onClick = { showBillingAddressDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = stringResource(R.string.add_billing_address)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.add_billing_address))
                        }

                        if (hasCommonBillingAddress) {
                            OutlinedButton(
                                onClick = {
                                    billingAddress = commonBillingAddress
                                    hasBillingAddress = true
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.common_account_billing_filled),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AccountCircle,
                                    contentDescription = null
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.common_account_billing_use_saved))
                            }
                        }
                    }
                }
            }

            InfoCard(title = stringResource(R.string.extended_fields_title)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = brand,
                        onValueChange = { brand = it },
                        label = { Text(stringResource(R.string.bank_card_brand_label)) },
                        leadingIcon = { Icon(Icons.Default.Style, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = nickname,
                        onValueChange = { nickname = it },
                        label = { Text(stringResource(R.string.bank_card_nickname_label)) },
                        leadingIcon = { Icon(Icons.Default.Label, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = validFromMonth,
                            onValueChange = { if (it.length <= 2 && it.all(Char::isDigit)) validFromMonth = it },
                            label = { Text(stringResource(R.string.bank_card_valid_from_month)) },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )
                        OutlinedTextField(
                            value = validFromYear,
                            onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) validFromYear = it },
                            label = { Text(stringResource(R.string.bank_card_valid_from_year)) },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                    OutlinedTextField(
                        value = iban,
                        onValueChange = { iban = it },
                        label = { Text("IBAN") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = swiftBic,
                        onValueChange = { swiftBic = it },
                        label = { Text("SWIFT / BIC") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = accountNumber,
                            onValueChange = { accountNumber = it },
                            label = { Text(stringResource(R.string.bank_card_account_number_label)) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )
                        OutlinedTextField(
                            value = routingNumber,
                            onValueChange = { routingNumber = it },
                            label = { Text(stringResource(R.string.bank_card_routing_number_label)) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = branchCode,
                            onValueChange = { branchCode = it },
                            label = { Text(stringResource(R.string.bank_card_branch_code_label)) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )
                        OutlinedTextField(
                            value = currency,
                            onValueChange = { currency = it },
                            label = { Text(stringResource(R.string.bank_card_currency_label)) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                    OutlinedTextField(
                        value = customerServicePhone,
                        onValueChange = { customerServicePhone = it },
                        label = { Text(stringResource(R.string.bank_card_customer_service_phone_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { pin = it },
                        label = { Text(stringResource(R.string.bank_card_pin_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }

            InfoCard(title = stringResource(R.string.custom_field_title)) {
                CustomFieldEditorSection(
                    fields = customFields,
                    onFieldsChange = { customFields = it },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            
            // Photos Card
            InfoCard(title = stringResource(R.string.section_photos)) {
                DualPhotoPicker(
                    frontImageFileName = frontImageFileName,
                    backImageFileName = backImageFileName,
                    onFrontImageSelected = { fileName -> frontImageFileName = fileName },
                    onFrontImageRemoved = { frontImageFileName = null },
                    onBackImageSelected = { fileName -> backImageFileName = fileName },
                    onBackImageRemoved = { backImageFileName = null },
                    frontLabel = stringResource(R.string.bank_card_photo_front_label),
                    backLabel = stringResource(R.string.bank_card_photo_back_label),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            val draftAttachmentTarget = selectedStorageTargets.firstOrNull()
            AttachmentsEditSection(
                owner = existingCardItem?.let { AttachmentOwner.secureItem(it.id) },
                isPlusActivated = appSettings.isPlusActivated,
                attachmentSource = when {
                    existingCardItem?.bitwardenVaultId != null -> AttachmentSource.BITWARDEN
                    existingCardItem?.keepassDatabaseId != null -> AttachmentSource.KEEPASS
                    cardId == null && draftAttachmentTarget is StorageTarget.KeePass -> AttachmentSource.KEEPASS
                    else -> AttachmentSource.LOCAL
                },
                bitwardenContext = attachmentBitwardenContext,
                bitwardenPremium = attachmentBitwardenVault?.let {
                    BitwardenVaultPremiumStore.isPremium(context, it.id)
                } ?: true,
                keepassContext = attachmentKeePassContext,
                pendingDrafts = if (cardId == null) pendingAttachmentDrafts else null,
                hideManagedCardFaces = true,
                excludedFileNames = KeePassSecureItemPhotoAttachments.managedFileNames(ItemType.BANK_CARD) +
                    listOfNotNull(
                        cardFaceConfig?.imageAttachmentName,
                        originalCardFaceConfig?.imageAttachmentName
                    )
            )

            // Notes Card
            InfoCard(title = stringResource(R.string.section_notes)) {
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(stringResource(R.string.notes)) },
                    placeholder = { Text(stringResource(R.string.notes_placeholder)) },
                    leadingIcon = { Icon(Icons.Default.Notes, contentDescription = null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    minLines = 3,
                    maxLines = 5,
                    shape = RoundedCornerShape(12.dp)
                )
            }
        }
    }
    }

    if (showTopBar || showFab) {
        Scaffold(
            topBar = {
                if (showTopBar) {
                    Column {
                        TopAppBar(
                            title = { Text(stringResource(R.string.bank_card_default_title)) },
                            navigationIcon = {
                                IconButton(onClick = onNavigateBack) {
                                    Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                                }
                            },
                            actions = {
                                IconButton(onClick = toggleFavoriteAction) {
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
                        if (showTypeSwitcher && cardId == null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = true,
                                    onClick = {},
                                    label = { Text(stringResource(R.string.quick_action_add_card)) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.CreditCard,
                                            contentDescription = null
                                        )
                                    }
                                )
                                FilterChip(
                                    selected = false,
                                    enabled = onSwitchToDocument != null,
                                    onClick = { onSwitchToDocument?.invoke() },
                                    label = { Text(stringResource(R.string.quick_action_add_document)) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Badge,
                                            contentDescription = null
                                        )
                                    }
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                }
            },
            floatingActionButton = {
                if (showFab) {
                    FloatingActionButton(
                        onClick = save,
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
                            Icon(Icons.Default.Check, contentDescription = stringResource(R.string.save))
                        }
                    }
                }
            }
        ) { paddingValues ->
            screenContent(paddingValues)
        }
    } else {
        screenContent(PaddingValues(0.dp))
    }

    if (showCardFaceCustomizer) {
        CardFaceCustomizer(
            title = title.ifBlank { stringResource(R.string.bank_card_default_title) },
            cardData = currentCardData(),
            initialConfig = cardFaceConfig,
            initialBitmap = displayedCardFaceBitmap,
            initialImageBytes = pendingCardFaceBytes,
            imageSelectionAllowed = unsupportedBitwardenCardFaceTarget == null,
            imageSelectionWarning = unsupportedBitwardenCardFaceTarget?.let {
                stringResource(R.string.card_face_bitwarden_premium_required)
            },
            onDismiss = { showCardFaceCustomizer = false },
            onApply = { result: CardFaceEditResult ->
                pendingCardFaceBytes?.fill(0)
                pendingCardFaceBytes = result.imageBytes
                pendingCardFacePreview = result.previewBitmap
                cardFaceConfig = result.config
                showCardFaceCustomizer = false
            }
        )
    }

    if (showCommonNamePicker) {
        CommonNameSuggestionSheet(
            suggestionState = commonNameSuggestions,
            currentName = cardholderName,
            onDismiss = { showCommonNamePicker = false },
            onSelectName = { selectedName ->
                cardholderName = selectedName
                showCommonNamePicker = false
            },
            onSaveCurrentName = { currentName ->
                coroutineScope.launch {
                    commonAccountPreferences.addTemplate(
                        type = commonNameType,
                        content = currentName
                    )
                }
            }
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
        getKeePassGroups = localKeePassViewModel::getGroups,
        onDismiss = { showStorageTargetSheet = false },
        onSelectedTargetsChange = ::setSelectedStorageTargets
    )

    if (showBillingAddressDialog) {
        var streetAddress by remember { mutableStateOf(billingAddress.streetAddress) }
        var apartment by remember { mutableStateOf(billingAddress.apartment) }
        var city by remember { mutableStateOf(billingAddress.city) }
        var stateProvince by remember { mutableStateOf(billingAddress.stateProvince) }
        var postalCode by remember { mutableStateOf(billingAddress.postalCode) }
        var country by remember { mutableStateOf(billingAddress.country) }

        AlertDialog(
            onDismissRequest = { showBillingAddressDialog = false },
            title = { Text(stringResource(R.string.billing_address)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (hasCommonBillingAddress) {
                        OutlinedButton(
                            onClick = {
                                streetAddress = commonBillingAddress.streetAddress
                                apartment = commonBillingAddress.apartment
                                city = commonBillingAddress.city
                                stateProvince = commonBillingAddress.stateProvince
                                postalCode = commonBillingAddress.postalCode
                                country = commonBillingAddress.country
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AccountCircle,
                                contentDescription = null
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.common_account_billing_use_saved))
                        }
                    }
                    OutlinedTextField(
                        value = streetAddress,
                        onValueChange = { streetAddress = it },
                        label = { Text(stringResource(R.string.street_address)) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = apartment,
                        onValueChange = { apartment = it },
                        label = { Text(stringResource(R.string.apartment)) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = city,
                        onValueChange = { city = it },
                        label = { Text(stringResource(R.string.city)) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = stateProvince,
                        onValueChange = { stateProvince = it },
                        label = { Text(stringResource(R.string.state_province)) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = postalCode,
                        onValueChange = { postalCode = it },
                        label = { Text(stringResource(R.string.postal_code)) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = country,
                        onValueChange = { country = it },
                        label = { Text(stringResource(R.string.country)) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val updatedAddress = BillingAddress(
                            streetAddress = streetAddress.trim(),
                            apartment = apartment.trim(),
                            city = city.trim(),
                            stateProvince = stateProvince.trim(),
                            postalCode = postalCode.trim(),
                            country = country.trim()
                        )
                        val hasAddress = !updatedAddress.isEmpty()
                        billingAddress = updatedAddress
                        hasBillingAddress = hasAddress
                        showBillingAddressDialog = false
                        val message = if (hasAddress) {
                            R.string.billing_address_saved
                        } else {
                            R.string.billing_address_removed
                        }
                        Toast.makeText(
                            context,
                            context.getString(message),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showBillingAddressDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

private fun parseSecureItemImagePaths(imagePaths: String): Pair<String?, String?> {
    if (imagePaths.isBlank()) return null to null
    return runCatching {
        val paths = Json.decodeFromString<List<String>>(imagePaths)
        paths.getOrNull(0)?.takeIf { it.isNotBlank() } to
            paths.getOrNull(1)?.takeIf { it.isNotBlank() }
    }.getOrDefault(null to null)
}

@Composable
private fun BankCardEditLoadingPlaceholder(
    modifier: Modifier = Modifier,
    paddingValues: PaddingValues
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        LoadingPlaceholderCard(lines = 2)
        LoadingPlaceholderCard(lines = 4)
        LoadingPlaceholderCard(lines = 3)
        LoadingPlaceholderCard(lines = 2)
    }
}

@Composable
private fun LoadingPlaceholderCard(lines: Int) {
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LoadingPlaceholderBar(widthFraction = 0.38f, height = 18.dp)
            repeat(lines) { index ->
                LoadingPlaceholderBar(
                    widthFraction = when (index % 3) {
                        0 -> 0.88f
                        1 -> 0.72f
                        else -> 0.52f
                    },
                    height = 44.dp
                )
            }
        }
    }
}

@Composable
private fun LoadingPlaceholderBar(
    widthFraction: Float,
    height: androidx.compose.ui.unit.Dp
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(height),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.72f),
        content = {}
    )
}

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
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            content()
        }
    }
}

private fun CommonNameSuggestionState.firstInlineSuggestion(): CommonNameSuggestion? {
    return templateSuggestions.firstOrNull() ?: analyzedSuggestions.firstOrNull()
}

@Composable
private fun InlineCommonNameSuggestionCard(
    suggestion: CommonNameSuggestion,
    onApply: () -> Unit
) {
    val icon = when (suggestion.source) {
        CommonNameSuggestionSource.TEMPLATE -> Icons.Default.Person
        CommonNameSuggestionSource.ANALYZED -> Icons.Default.AutoAwesome
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        MonicaExpressiveFilterChip(
            selected = true,
            onClick = onApply,
            label = suggestion.name,
            leadingIcon = icon,
            modifier = Modifier.heightIn(min = 44.dp)
        )
    }
}

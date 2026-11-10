package takagi.ru.monica.ui.screens

import takagi.ru.monica.ui.components.WalletSplitNameFields
import takagi.ru.monica.ui.components.WalletContactGroup
import takagi.ru.monica.ui.components.WalletProfileField
import takagi.ru.monica.ui.components.MonicaExpandableContent
import android.widget.Toast
import android.graphics.Bitmap
import takagi.ru.monica.ui.components.MonicaExposedChoiceMenu
import takagi.ru.monica.ui.components.MonicaMenuChoice
import androidx.compose.foundation.background
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import kotlinx.serialization.json.jsonObject
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
import takagi.ru.monica.ui.components.*
import takagi.ru.monica.ui.components.OutlinedTextField
import takagi.ru.monica.ui.components.SuggestedOutlinedTextField
import takagi.ru.monica.data.CommonSuggestionField

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddEditBankCardScreen(
    viewModel: BankCardViewModel,
    cardId: Long? = null,
    onNavigateBack: () -> Unit,
    initialCategoryId: Long? = null,
    initialStorageExplicit: Boolean = false,
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
    modifier: Modifier = Modifier,
    embeddedDraft: takagi.ru.monica.data.model.EmbeddedWalletContent.Snapshot? = null,
    embeddedBitmap: Bitmap? = null,
    embeddedImageLoader: (suspend (String) -> Bitmap?)? = null,
    embeddedAttachmentsContent: (@Composable () -> Unit)? = null,
    onEmbeddedCopy: (() -> Unit)? = null,
    onEmbeddedSave: (suspend (takagi.ru.monica.attachments.EmbeddedWalletEditorResult) -> Unit)? = null
) {
    val context = LocalContext.current
    takagi.ru.monica.lansync.DeviceSyncEditorLease()
    val coroutineScope = rememberCoroutineScope()
    val database = remember { PasswordDatabase.getDatabase(context) }
    val securityManager = takagi.ru.monica.ui.rememberUiSecurityManager()
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
    val commonAccountPreferences = remember(context, securityManager) { CommonAccountPreferences(context, securityManager) }
    val commonBillingAddress by commonAccountPreferences.billingAddress.collectAsState(initial = BillingAddress())
    val hasCommonBillingAddress = !commonBillingAddress.isEmpty()

    var nfcHasScanned by rememberSaveable { mutableStateOf(false) }
    var nfcTypeConfirmed by rememberSaveable { mutableStateOf(true) }
    var newDraftInitialized by rememberSaveable { mutableStateOf(false) }
    var title by rememberSaveable { mutableStateOf("") }
    var cardNumber by takagi.ru.monica.nfc.card.rememberNfcCardField { nfcHasScanned }
    var cardholderName by takagi.ru.monica.nfc.card.rememberNfcCardField { nfcHasScanned }
    var cardholderFirstName by takagi.ru.monica.nfc.card.rememberNfcCardField { nfcHasScanned }
    var cardholderMiddleName by takagi.ru.monica.nfc.card.rememberNfcCardField { nfcHasScanned }
    var cardholderLastName by takagi.ru.monica.nfc.card.rememberNfcCardField { nfcHasScanned }
    var expiryMonth by takagi.ru.monica.nfc.card.rememberNfcCardField { nfcHasScanned }
    var expiryYear by takagi.ru.monica.nfc.card.rememberNfcCardField { nfcHasScanned }
    var cvv by rememberSaveable { mutableStateOf("") }
    var bankName by rememberSaveable { mutableStateOf("") }
    var bankBranch by rememberSaveable { mutableStateOf("") }
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
    val editorSections = rememberItemEditorSections()
    var editorSectionOrder by rememberSaveable(cardId) { mutableStateOf(emptyList<String>()) }
    var isFavorite by rememberSaveable { mutableStateOf(false) }
    var showCardTypeMenu by remember { mutableStateOf(false) }
    var showCardNumber by remember { mutableStateOf(false) }
    var showCvv by remember { mutableStateOf(false) }
    var showCommonNamePicker by rememberSaveable { mutableStateOf(false) }
    var isCardholderNameFocused by remember { mutableStateOf(false) }
    var hasBillingAddress by remember { mutableStateOf(false) }
    var billingAddress by remember { mutableStateOf(BillingAddress()) }
    var customFields by rememberSaveable(stateSaver = takagi.ru.monica.ui.components.EntryFieldDraftSaver) { mutableStateOf<List<CustomFieldDraft>>(emptyList()) }
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
    val mdbxDatabases by database.localMdbxDatabaseDao().getAvailableDatabases().collectAsState(initial = emptyList())
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
    val attachmentBitwardenContext = takagi.ru.monica.attachments.ui.rememberAttachmentBitwardenContext(
        attachmentBitwardenVault, existingCardItem?.bitwardenCipherId
    )
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
        initialStorageExplicit,
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
        val hasExplicitInitialStorage = initialStorageExplicit || initialCategoryId != null ||
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
    LaunchedEffect(cardId, embeddedDraft?.id) {
        if (cardId != null || embeddedDraft != null) {
            if (hasLoadedExistingCardFields) return@LaunchedEffect
            withContext(Dispatchers.IO) {
                embeddedDraft?.displayItem()?.copy(imagePaths = Json.encodeToString(listOf(
                    embeddedDraft.assets.firstOrNull { it.role == takagi.ru.monica.data.model.EmbeddedWalletContent.AssetRole.FRONT }?.name.orEmpty(),
                    embeddedDraft.assets.firstOrNull { it.role == takagi.ru.monica.data.model.EmbeddedWalletContent.AssetRole.BACK }?.name.orEmpty())))
                    ?: viewModel.getCardById(requireNotNull(cardId))
            }?.let { item ->
                existingCardItem = if (embeddedDraft == null) item else null
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
                cardholderFirstName = data.cardholderFirstName
                cardholderMiddleName = data.cardholderMiddleName
                cardholderLastName = data.cardholderLastName
                    expiryMonth = data.expiryMonth
                    expiryYear = data.expiryYear
                    cvv = data.cvv
                    bankName = data.bankName
                    bankBranch = data.bankBranch
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
                    if (editorSectionOrder.isEmpty()) editorSectionOrder = data.editorSectionOrder
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
        } else if (!newDraftInitialized) {
            newDraftInitialized = true
            existingCardItem = null
            hasLoadedExistingCardFields = false
            currentReplicaGroupId = null
            existingReplicaTargetKeys = emptySet()
            // 添加模式：重置表单字段
            title = ""
            cardNumber = ""
            cardholderName = ""
            cardholderFirstName = ""
            cardholderMiddleName = ""
            cardholderLastName = ""
            expiryMonth = ""
            expiryYear = ""
            cvv = ""
            bankName = ""
            bankBranch = ""
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
            editorSectionOrder = emptyList()
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

    val nfcFields = takagi.ru.monica.nfc.card.NfcCardFields(cardNumber, expiryMonth, expiryYear,
        cardholderName.ifBlank { listOf(cardholderFirstName, cardholderMiddleName, cardholderLastName).filter(String::isNotBlank).joinToString(" ") },
        hasNameParts = listOf(cardholderFirstName, cardholderMiddleName, cardholderLastName).any(String::isNotBlank))
    takagi.ru.monica.nfc.card.NfcCardEditor(
        kind = takagi.ru.monica.nfc.card.NfcCardEditorKind.BANK,
        eligible = cardId == null && embeddedDraft == null && newDraftInitialized,
        busy = isSaving,
        fields = nfcFields,
        onFill = { scanned ->
            val merged = nfcFields.merge(scanned)
            nfcHasScanned = true
            nfcTypeConfirmed = false
            cardNumber = merged.number
            expiryMonth = merged.month
            expiryYear = merged.year
            if (!scanned.holder.isNullOrBlank()) {
                cardholderName = merged.holder
                cardholderFirstName = ""; cardholderMiddleName = ""; cardholderLastName = ""
            }
        },
    )
    val isExistingCardReady = cardId == null || hasLoadedExistingCardFields
    val displayedCardFaceBitmap = rememberCardFaceBitmap(
        item = existingCardItem,
        imageAttachmentName = cardFaceConfig?.imageAttachmentName,
        overrideBitmap = pendingCardFacePreview ?: embeddedBitmap,
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
            cardholderFirstName = cardholderFirstName,
            cardholderMiddleName = cardholderMiddleName,
            cardholderLastName = cardholderLastName,
            expiryMonth = expiryMonth,
            expiryYear = expiryYear,
            cvv = cvv,
            bankName = bankName,
            bankBranch = bankBranch,
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
            editorSectionOrder = editorSectionOrder,
            cardFace = cardFaceConfig
        )
    }
    val canSave = nfcTypeConfirmed && isExistingCardReady && (cardNumber.isNotBlank() || embeddedDraft != null) && !isSaving
    val save: () -> Unit = saveAction@{
        if (!nfcTypeConfirmed || !isExistingCardReady || isSaving || (cardNumber.isBlank() && embeddedDraft == null)) return@saveAction
        if (embeddedDraft != null && onEmbeddedSave != null) {
            isSaving = true
            coroutineScope.launch {
                try {
                    val data = Json.parseToJsonElement(Json.encodeToString(currentCardData())).jsonObject
                    onEmbeddedSave(takagi.ru.monica.attachments.EmbeddedWalletEditorResult(
                        embeddedDraft.edited(title, notes, data).withFavorite(isFavorite), listOf(frontImageFileName.orEmpty(), backImageFileName.orEmpty()),
                        pendingAttachmentDrafts.toList(), pendingCardFaceBytes))
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
                } catch (_: Exception) { Toast.makeText(context, R.string.embedded_copy_failed, Toast.LENGTH_LONG).show()
                } finally { isSaving = false }
            }
            return@saveAction
        }
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
    val contentItems = listOf(
        WalletEditorSection("billing", R.string.billing_address, Icons.Default.Home, editorSections.visible("billing", hasBillingAddress)) {
            TemplateFormSection("") {
                ConnectedFieldGroup(
                    {
                        SuggestedOutlinedTextField(billingAddress.streetAddress, { billingAddress = billingAddress.copy(streetAddress = it); hasBillingAddress = !billingAddress.isEmpty() }, suggestionField = CommonSuggestionField.STREET,
                            label = { Text(stringResource(R.string.street_address)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("wallet_billing_streetAddress"))
                    },
                    {
                        SuggestedOutlinedTextField(billingAddress.apartment, { billingAddress = billingAddress.copy(apartment = it); hasBillingAddress = !billingAddress.isEmpty() }, suggestionField = CommonSuggestionField.APARTMENT,
                            label = { Text(stringResource(R.string.apartment)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("wallet_billing_apartment"))
                    },
                    {
                        SuggestedOutlinedTextField(billingAddress.city, { billingAddress = billingAddress.copy(city = it); hasBillingAddress = !billingAddress.isEmpty() }, suggestionField = CommonSuggestionField.CITY,
                            label = { Text(stringResource(R.string.city)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("wallet_billing_city"))
                    },
                    {
                        SuggestedOutlinedTextField(billingAddress.stateProvince, { billingAddress = billingAddress.copy(stateProvince = it); hasBillingAddress = !billingAddress.isEmpty() }, suggestionField = CommonSuggestionField.REGION,
                            label = { Text(stringResource(R.string.state_province)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("wallet_billing_stateProvince"))
                    },
                    {
                        SuggestedOutlinedTextField(billingAddress.postalCode, { billingAddress = billingAddress.copy(postalCode = it); hasBillingAddress = !billingAddress.isEmpty() }, suggestionField = CommonSuggestionField.POSTAL_CODE,
                            label = { Text(stringResource(R.string.postal_code)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("wallet_billing_postalCode"))
                    },
                    {
                        SuggestedOutlinedTextField(billingAddress.country, { billingAddress = billingAddress.copy(country = it); hasBillingAddress = !billingAddress.isEmpty() }, suggestionField = CommonSuggestionField.COUNTRY,
                            label = { Text(stringResource(R.string.country)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("wallet_billing_country"))
                    },
                )
            }
            if (hasCommonBillingAddress) WalletEditorAction(stringResource(R.string.common_account_billing_use_saved), Icons.Default.AccountCircle, 0, 1) {
                billingAddress = commonBillingAddress
                hasBillingAddress = true
            }
            if (hasBillingAddress) WalletEditorAction(stringResource(R.string.remove_billing_address), Icons.Default.Delete, 0, 1) {
                billingAddress = BillingAddress()
                hasBillingAddress = false
            }
        },
        WalletEditorSection("extended", R.string.extended_fields_title, Icons.Default.Tune, editorSections.visible("extended", listOf(brand, nickname, validFromMonth, validFromYear, pin, iban, swiftBic, routingNumber, accountNumber, branchCode, currency, customerServicePhone).any { it.isNotBlank() })) {
            InfoCard(title = stringResource(R.string.extended_fields_title)) {
                takagi.ru.monica.ui.components.EntryOptionalFields(
                    specs = takagi.ru.monica.ui.components.EntrySupplementalSpecs.payment.filterNot {
                        it.key in setOf("bankName", "bankBranch", "cardType", "billingAddress")
                    },
                    values = mapOf(
                        "brand" to brand,
                        "nickname" to nickname,
                        "validFromMonth" to validFromMonth,
                        "validFromYear" to validFromYear,
                        "pin" to pin,
                        "iban" to iban,
                        "swiftBic" to swiftBic,
                        "routingNumber" to routingNumber,
                        "accountNumber" to accountNumber,
                        "branchCode" to branchCode,
                        "currency" to currency,
                        "customerServicePhone" to customerServicePhone
                    ),
                    onValue = { spec, value -> when (spec.key) {
                        "brand" -> brand = value
                        "nickname" -> nickname = value
                        "validFromMonth" -> validFromMonth = value
                        "validFromYear" -> validFromYear = value
                        "pin" -> pin = value
                        "iban" -> iban = value
                        "swiftBic" -> swiftBic = value
                        "routingNumber" -> routingNumber = value
                        "accountNumber" -> accountNumber = value
                        "branchCode" -> branchCode = value
                        "currency" -> currency = value
                        "customerServicePhone" -> customerServicePhone = value
                    } },
                )
            }
        },
        WalletEditorSection("name_parts", R.string.wallet_cardholder_name_parts, Icons.Default.Person, editorSections.visible("name_parts", listOf(cardholderFirstName, cardholderMiddleName, cardholderLastName).any { it.isNotBlank() })) {
            WalletSplitNameFields(cardholderFirstName, cardholderMiddleName, cardholderLastName,
                { cardholderFirstName = it }, { cardholderMiddleName = it }, { cardholderLastName = it })
        },
        WalletEditorSection("custom", R.string.custom_field_title, Icons.Default.TextFields, editorSections.visible("custom", customFields.isNotEmpty())) {
            WalletCustomFields(customFields) { customFields = it }
        },
        WalletEditorSection("photos", R.string.section_photos, Icons.Default.PhotoCamera, editorSections.visible("photos", frontImageFileName != null || backImageFileName != null)) {
            DualPhotoPicker(
                frontImageFileName = frontImageFileName,
                backImageFileName = backImageFileName,
                onFrontImageSelected = { fileName -> frontImageFileName = fileName },
                onFrontImageRemoved = { frontImageFileName = null },
                onBackImageSelected = { fileName -> backImageFileName = fileName },
                onBackImageRemoved = { backImageFileName = null },
                frontLabel = stringResource(R.string.bank_card_photo_front_label),
                backLabel = stringResource(R.string.bank_card_photo_back_label),
                imageLoader = embeddedImageLoader,
                modifier = Modifier.fillMaxWidth()
            )
        },
        WalletEditorSection("attachments", R.string.attachments, Icons.Default.AttachFile, editorSections.visible("attachments", existingCardItem != null || pendingAttachmentDrafts.isNotEmpty() || embeddedAttachmentsContent != null)) {
            val draftAttachmentTarget = selectedStorageTargets.firstOrNull()
            embeddedAttachmentsContent?.invoke()
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
        },
        WalletEditorSection("notes", R.string.notes, Icons.Default.Notes, editorSections.visible("notes", notes.isNotBlank())) {
            InfoCard(title = stringResource(R.string.section_notes)) {
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(stringResource(R.string.notes)) },
                    placeholder = { Text(stringResource(R.string.notes_placeholder)) },
                    leadingIcon = { Icon(Icons.Default.Notes, contentDescription = null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp),
                    minLines = 3,
                    maxLines = 5,
                    shape = RoundedCornerShape(12.dp)
                )
            }
        }
    )
    val screenContent: @Composable (PaddingValues) -> Unit = { paddingValues ->
        if (!isExistingCardReady) {
            BankCardEditLoadingPlaceholder(
                modifier = modifier,
                paddingValues = paddingValues
            )
        } else {
            WalletEditorContent(
                sections = editorSections, items = contentItems, order = editorSectionOrder,
                onOrder = { editorSectionOrder = it }, enabled = !isSaving,
                modifier = modifier.fillMaxSize().testTag(if (embeddedDraft == null) "bank_item_editor" else "embedded_bank_editor")
                    .padding(paddingValues).consumeWindowInsets(paddingValues).imePadding(),
                primary = {
                if (embeddedDraft == null) MultiStorageTargetSelectorCard(
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

                if (cardId == null && embeddedDraft == null) {
                    takagi.ru.monica.nfc.card.NfcCardScanButton(enabled = !isSaving)
                    if (nfcHasScanned) {
                        Text(stringResource(R.string.nfc_card_type_hint), style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("nfc_card_type_hint"))
                        Text(stringResource(R.string.nfc_card_type_select), style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(CardType.DEBIT to R.string.debit_card, CardType.CREDIT to R.string.credit_card,
                                CardType.PREPAID to R.string.prepaid_card).forEach { (type, label) ->
                                FilterChip(selected = nfcTypeConfirmed && cardType == type,
                                    onClick = { cardType = type; nfcTypeConfirmed = true }, enabled = !isSaving,
                                    label = { Text(stringResource(label)) }, modifier = Modifier.testTag("nfc_card_type_${type.name}"))
                            }
                        }
                    }
                }

                onEmbeddedCopy?.let { copy ->
                    FilledTonalButton(onClick = copy, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.CreditCard, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.embedded_copy_card))
                    }
                }
            ItemEditorIdentity(title, { title = it }, stringResource(R.string.card_name), Icons.Default.CreditCard)

                if (embeddedDraft != null) {
                    takagi.ru.monica.ui.cardwallet.CardFaceArtwork(
                        previewData = bankCardFacePreviewData(title.ifBlank { stringResource(R.string.bank_card_default_title) }, currentCardData()),
                        bitmap = displayedCardFaceBitmap,
                        displayMode = cardFaceConfig?.displayMode ?: takagi.ru.monica.data.model.CardFaceDisplayMode.ALL,
                        modifier = Modifier.fillMaxWidth().aspectRatio(CardFaceImageProcessor.CARD_ASPECT_RATIO)
                            .clip(RoundedCornerShape(24.dp)).clickable(enabled = !isSaving) { showCardFaceCustomizer = true })
                } else CardFaceEditorEntry(
                    compact = true,
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
                    ConnectedFieldGroup(
                        {
                            // Bank Name
                            SuggestedOutlinedTextField(
                                suggestionField = CommonSuggestionField.BANK_NAME,
                                value = bankName,
                                onValueChange = { bankName = it },
                                label = { Text(stringResource(R.string.bank_name)) },
                                placeholder = { Text(stringResource(R.string.bank_name_example)) },
                                leadingIcon = { Icon(Icons.Default.AccountBalance, contentDescription = null) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp)
                            )
                        },
                        {
                            SuggestedOutlinedTextField(
                                suggestionField = CommonSuggestionField.BANK_BRANCH,
                                value = bankBranch,
                                onValueChange = { bankBranch = it },
                                label = { Text(stringResource(R.string.bank_branch)) },
                                placeholder = { Text(stringResource(R.string.bank_branch_example)) },
                                leadingIcon = { Icon(Icons.Default.AccountBalance, contentDescription = null) },
                                modifier = Modifier.fillMaxWidth().testTag("bank_branch"),
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp)
                            )
                        },
                        {
                            // Card Type
                            ExposedDropdownMenuBox(
                                expanded = showCardTypeMenu,
                                onExpandedChange = { showCardTypeMenu = it }
                            ) {
                                OutlinedTextField(
                                    value = if (!nfcTypeConfirmed) stringResource(R.string.nfc_card_type_select) else when (cardType) {
                                        CardType.CREDIT -> stringResource(R.string.credit_card)
                                        CardType.DEBIT -> stringResource(R.string.debit_card)
                                        CardType.PREPAID -> stringResource(R.string.prepaid_card)
                                    },
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text(stringResource(R.string.card_type)) },
                                    leadingIcon = { Icon(Icons.Default.CreditCard, contentDescription = null) },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showCardTypeMenu) },
                                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                                )

                                MonicaExposedChoiceMenu(
                                    expanded = showCardTypeMenu,
                                    onDismissRequest = { showCardTypeMenu = false },
                                    selectedValue = cardType,
                                    onSelect = { cardType = it; nfcTypeConfirmed = true },
                                    choices = listOf(
                                        MonicaMenuChoice(CardType.DEBIT, stringResource(R.string.debit_card)),
                                        MonicaMenuChoice(CardType.CREDIT, stringResource(R.string.credit_card)),
                                        MonicaMenuChoice(CardType.PREPAID, stringResource(R.string.prepaid_card)),
                                    )
                                )
                            }
                        },
                        {
                            takagi.ru.monica.ui.components.EntryPaymentFields(
                                cardNumber, cardholderName,
                                takagi.ru.monica.ui.components.EntryPaymentFormat.joinExpiry(expiryMonth, expiryYear), cvv,
                                onNumber = { cardNumber = it },
                                onHolder = { cardholderName = it },
                                onExpiry = { input ->
                                    val (month, year) = takagi.ru.monica.ui.components.EntryPaymentFormat.splitExpiry(input)
                                    expiryMonth = month
                                    expiryYear = year
                                },
                                onCvv = { cvv = it },
                                saveTextState = !nfcHasScanned,
                                onPickHolder = if (showCommonNameAction) {{
                                    shouldLoadCommonNameAnalysis = true
                                    showCommonNamePicker = true
                                }} else null,
                            )
                        },
                    )
                }


                },
                addContent = { ItemEditorAddContent(editorSections, options = listOf(
                    ItemEditorContentOption("name_parts", R.string.wallet_cardholder_name_parts, Icons.Default.Person, editorSections.visible("name_parts", listOf(cardholderFirstName, cardholderMiddleName, cardholderLastName).any { it.isNotBlank() })),
                    ItemEditorContentOption("billing", R.string.billing_address, Icons.Default.Home, editorSections.visible("billing", hasBillingAddress)),
                    ItemEditorContentOption("extended", R.string.extended_fields_title, Icons.Default.Tune, editorSections.visible("extended", listOf(brand, nickname, validFromMonth, validFromYear, pin, iban, swiftBic, routingNumber, accountNumber, branchCode, currency, customerServicePhone).any { it.isNotBlank() })),
                    ItemEditorContentOption("photos", R.string.section_photos, Icons.Default.PhotoCamera, editorSections.visible("photos", frontImageFileName != null || backImageFileName != null)),
                    ItemEditorContentOption("attachments", R.string.attachments, Icons.Default.AttachFile, editorSections.visible("attachments", existingCardItem != null || pendingAttachmentDrafts.isNotEmpty() || embeddedAttachmentsContent != null)),
                    ItemEditorContentOption("notes", R.string.notes, Icons.Default.Notes, editorSections.visible("notes", notes.isNotBlank()))
                ), fields = customFields, onFieldsChange = { customFields = it },
                enabled = !isSaving) },
            )
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
    CompositionLocalProvider(LocalEntryContentStyle provides true) {
        TemplateFormSection(if (LocalWalletContentEditor.current) "" else title) { content() }
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

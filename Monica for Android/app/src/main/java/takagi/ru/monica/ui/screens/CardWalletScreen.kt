package takagi.ru.monica.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Velocity
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.sync.isUserVisibleSyncInProgress
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.bitwarden.sync.VaultSyncStatus
import takagi.ru.monica.bitwarden.sync.syncForUserVisibleRequest
import takagi.ru.monica.bitwarden.ui.BitwardenAutoSyncEffect
import takagi.ru.monica.data.AppSettings
import takagi.ru.monica.data.Category
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.isKeePassOwned
import takagi.ru.monica.data.isLocalOnlyItem
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.data.model.StorageTarget
import takagi.ru.monica.repository.KeePassCompatibilityBridge
import takagi.ru.monica.repository.KeePassWorkspaceRepository
import takagi.ru.monica.ui.cardwallet.WalletListItem
import takagi.ru.monica.ui.cardwallet.WalletListItemType
import takagi.ru.monica.ui.cardwallet.bitwardenVaultIdForWalletSync
import takagi.ru.monica.ui.cardwallet.isBitwardenWalletScope
import takagi.ru.monica.ui.cardwallet.mergeVisibleWalletOrder
import takagi.ru.monica.ui.cardwallet.orderItemsByIds
import takagi.ru.monica.ui.cardwallet.toBillingAddressWalletListItem
import takagi.ru.monica.ui.cardwallet.toBankCardWalletListItem
import takagi.ru.monica.ui.cardwallet.toDocumentWalletListItem
import takagi.ru.monica.ui.components.BankCardCard
import takagi.ru.monica.ui.components.BillingAddressCard
import takagi.ru.monica.ui.components.CreateCategoryDialog
import takagi.ru.monica.ui.components.CreateDialogTarget
import takagi.ru.monica.ui.components.DocumentCard
import takagi.ru.monica.ui.components.EmptyState
import takagi.ru.monica.ui.components.ExpressiveTopBar
import takagi.ru.monica.ui.components.LoadingIndicator
import takagi.ru.monica.ui.components.M3IdentityVerifyDialog
import takagi.ru.monica.ui.PasswordListCategoryChipMenuBottomActions
import takagi.ru.monica.ui.category.CategoryManagementTrailingContent
import takagi.ru.monica.ui.category.CategoryManagementCreateDialog
import takagi.ru.monica.ui.category.rememberCategoryManagementState
import takagi.ru.monica.ui.components.PullActionVisualState
import takagi.ru.monica.ui.common.pull.calculateDampedPullOffset
import takagi.ru.monica.ui.common.state.InitialListRenderState
import takagi.ru.monica.ui.common.state.resolveMergedListRenderState
import takagi.ru.monica.ui.components.UnifiedCategoryFilterChipMenu
import takagi.ru.monica.ui.components.UnifiedCategoryFilterChipMenuDropdown
import takagi.ru.monica.ui.components.UnifiedCategoryFilterChipMenuOffset
import takagi.ru.monica.ui.components.UnifiedCategoryFilterSelection
import takagi.ru.monica.ui.components.UnifiedTypeQuickFilter
import takagi.ru.monica.ui.components.UnifiedMoveAction
import takagi.ru.monica.ui.components.UnifiedMoveCategoryTarget
import takagi.ru.monica.ui.components.UnifiedMoveToCategoryBottomSheet
import takagi.ru.monica.ui.components.toUnifiedMoveInitialSource
import takagi.ru.monica.ui.gestures.SwipeActions
import takagi.ru.monica.ui.password.PasswordTopActionsDropdownMenu
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.sync.SyncDiagnostics
import takagi.ru.monica.sync.SyncItemKind
import takagi.ru.monica.sync.SyncMode
import takagi.ru.monica.sync.SyncPriority
import takagi.ru.monica.sync.SyncRequest
import takagi.ru.monica.sync.SyncTarget
import takagi.ru.monica.sync.SyncTaskRunner
import takagi.ru.monica.sync.SyncTrigger
import takagi.ru.monica.utils.BiometricHelper
import takagi.ru.monica.utils.KeePassGroupInfo
import takagi.ru.monica.utils.decodeKeePassPathForDisplay
import takagi.ru.monica.utils.planLocalCategoryMove
import takagi.ru.monica.utils.SavedCategoryFilterState
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.viewmodel.BankCardViewModel
import takagi.ru.monica.viewmodel.BillingAddressViewModel
import takagi.ru.monica.viewmodel.DocumentViewModel
import takagi.ru.monica.viewmodel.PasswordViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

enum class CardWalletTab {
    ALL,
    BANK_CARDS,
    DOCUMENTS,
    BILLING_ADDRESSES
}

private data class CardWalletListFilterKey(
    val tab: CardWalletTab,
    val query: String,
    val categoryFilter: UnifiedCategoryFilterSelection
)

private fun cardWalletTypeQuickFilters(
    currentTab: CardWalletTab,
    onTabSelected: (CardWalletTab) -> Unit
): List<UnifiedTypeQuickFilter> = listOf(
    UnifiedTypeQuickFilter(
        labelRes = R.string.filter_all,
        icon = Icons.Default.FilterList,
        isSelected = currentTab == CardWalletTab.ALL,
        onSelect = { onTabSelected(CardWalletTab.ALL) }
    ),
    UnifiedTypeQuickFilter(
        labelRes = R.string.nav_bank_cards_short,
        icon = Icons.Default.CreditCard,
        isSelected = currentTab == CardWalletTab.BANK_CARDS,
        onSelect = { onTabSelected(CardWalletTab.BANK_CARDS) }
    ),
    UnifiedTypeQuickFilter(
        labelRes = R.string.nav_documents_short,
        icon = Icons.Default.Description,
        isSelected = currentTab == CardWalletTab.DOCUMENTS,
        onSelect = { onTabSelected(CardWalletTab.DOCUMENTS) }
    ),
    UnifiedTypeQuickFilter(
        labelRes = R.string.billing_address,
        icon = Icons.Default.Home,
        isSelected = currentTab == CardWalletTab.BILLING_ADDRESSES,
        onSelect = { onTabSelected(CardWalletTab.BILLING_ADDRESSES) }
    )
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CardWalletScreen(
    bankCardViewModel: BankCardViewModel,
    documentViewModel: DocumentViewModel,
    billingAddressViewModel: BillingAddressViewModel,
    passwordViewModel: PasswordViewModel,
    onCardClick: (Long) -> Unit,
    onDocumentClick: (Long) -> Unit,
    onBillingAddressClick: (Long) -> Unit,
    currentTab: CardWalletTab,
    onTabSelected: (CardWalletTab) -> Unit,
    onSelectionModeChange: (Boolean, Int, () -> Unit, () -> Unit, () -> Unit, () -> Unit) -> Unit,
    onBankCardSelectionModeChange: (Boolean, Int, () -> Unit, () -> Unit, () -> Unit, () -> Unit, () -> Unit) -> Unit,
    showStandaloneSettingsEntry: Boolean = false,
    onOpenStandaloneSettings: () -> Unit = {},
    onBitwardenScopeChanged: (Long?) -> Unit = {},
    bitwardenViewModel: takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val securityManager = remember { SecurityManager(context) }
    val biometricHelper = remember { BiometricHelper(context) }
    val settingsManager = remember { SettingsManager(context) }
    val appSettings by settingsManager.settingsFlow.collectAsState(
        initial = AppSettings(biometricEnabled = false)
    )
    val savedCategoryFilterState by settingsManager
        .categoryFilterStateFlow(SettingsManager.CategoryFilterScope.CARD_WALLET)
        .collectAsState(initial = null)
    val database = remember { PasswordDatabase.getDatabase(context) }
    val categories by database.categoryDao().getAllCategories().collectAsState(initial = emptyList<Category>())
    val keepassDatabases by database.localKeePassDatabaseDao().getAllDatabases().collectAsState(initial = emptyList())
    val mdbxDatabases by database.localMdbxDatabaseDao().getAllDatabases().collectAsState(initial = emptyList())
    val bitwardenRepository = remember { BitwardenRepository.getInstance(context) }
    val keepassBridge = remember {
        KeePassCompatibilityBridge(
            KeePassWorkspaceRepository(
                context,
                database.localKeePassDatabaseDao(),
                securityManager
            )
        )
    }
    val keepassGroupFlows = remember {
        mutableMapOf<Long, MutableStateFlow<List<KeePassGroupInfo>>>()
    }
    val getKeePassGroups: (Long) -> Flow<List<KeePassGroupInfo>> = remember {
        { databaseId ->
            val flow = keepassGroupFlows.getOrPut(databaseId) {
                MutableStateFlow(emptyList())
            }
            if (flow.value.isEmpty()) {
                scope.launch {
                    flow.value = keepassBridge.listLegacyGroups(databaseId).getOrDefault(emptyList())
                }
            }
            flow
        }
    }

    val parsedCardsState by bankCardViewModel.parsedCardsState.collectAsState()
    val parsedDocumentsState by documentViewModel.parsedDocumentsState.collectAsState()
    val parsedBillingAddressesState by billingAddressViewModel.parsedBillingAddressesState.collectAsState()
    val parsedCards = parsedCardsState.items
    val parsedDocuments = parsedDocumentsState.items
    val parsedBillingAddresses = parsedBillingAddressesState.items
    val walletItemsReady = parsedCardsState.isReady &&
        parsedDocumentsState.isReady &&
        parsedBillingAddressesState.isReady
    val cards = remember(parsedCards) { parsedCards.map { it.item } }
    val documents = remember(parsedDocuments) { parsedDocuments.map { it.item } }
    val billingAddresses = remember(parsedBillingAddresses) { parsedBillingAddresses.map { it.item } }
    val walletItems = remember(parsedCards, parsedDocuments, parsedBillingAddresses) {
        parsedCards.map { it.item.toBankCardWalletListItem(it.cardData) } +
            parsedDocuments.map { it.item.toDocumentWalletListItem(it.documentData) } +
            parsedBillingAddresses.map { it.item.toBillingAddressWalletListItem(it.addressData) }
    }
    val bitwardenVaults by database.bitwardenVaultDao().getAllVaultsFlow().collectAsState(initial = emptyList())

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isSearchExpanded by rememberSaveable { mutableStateOf(false) }
    var showTopActionsMenu by remember { mutableStateOf(false) }
    var showHistoryPage by rememberSaveable { mutableStateOf(false) }
    var showCategoryFilterDialog by remember { mutableStateOf(false) }
    var categoryPillBoundsInWindow by remember { mutableStateOf<Rect?>(null) }
    var selectedCategoryFilter by rememberSaveable(stateSaver = cardWalletCategoryFilterSaver) {
        mutableStateOf<UnifiedCategoryFilterSelection>(UnifiedCategoryFilterSelection.All)
    }
    var hasRestoredCategoryFilter by rememberSaveable { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var isSelectionMode by remember { mutableStateOf(false) }
    var itemToDelete by remember { mutableStateOf<SecureItem?>(null) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    var showVerifyDialog by remember { mutableStateOf(false) }
    var verifyPassword by remember { mutableStateOf("") }
    var verifyPasswordError by remember { mutableStateOf(false) }
    var verifyDeleteIds by remember { mutableStateOf(setOf<Long>()) }
    var showBatchMoveCategoryDialog by remember { mutableStateOf(false) }
    val categoryMgmt = takagi.ru.monica.ui.category.rememberCategoryManagementState()
    val listState = rememberLazyListState()
    val isBitwardenDatabaseView = selectedCategoryFilter.isBitwardenWalletScope()
    val selectedBitwardenVaultId = selectedCategoryFilter.bitwardenVaultIdForWalletSync()
    val bitwardenSyncStatusByVault: Map<Long, VaultSyncStatus> = if (bitwardenViewModel != null) {
        bitwardenViewModel.syncStatusByVault.collectAsState().value
    } else {
        emptyMap()
    }
    val isTopBarSyncing = selectedBitwardenVaultId?.let { vaultId ->
        bitwardenSyncStatusByVault[vaultId].isUserVisibleSyncInProgress()
    } == true
    var currentOffset by remember { mutableStateOf(0f) }
    val searchTriggerDistance = remember(density, isBitwardenDatabaseView) {
        with(density) { (if (isBitwardenDatabaseView) 40.dp else 72.dp).toPx() }
    }
    val syncTriggerDistance = remember(density) { with(density) { 72.dp.toPx() } }
    val maxDragDistance = remember(density) { with(density) { 100.dp.toPx() } }
    val syncHoldMillis = 500L
    var isSettlingBack by remember { mutableStateOf(false) }
    var hasVibrated by remember { mutableStateOf(false) }
    var hasSyncStageVibrated by remember { mutableStateOf(false) }
    var syncHintArmed by remember { mutableStateOf(false) }
    var isBitwardenSyncing by remember { mutableStateOf(false) }
    var lockPullUntilSyncFinished by remember { mutableStateOf(false) }
    var canRunBitwardenSync by remember { mutableStateOf(false) }
    var showSyncFeedback by remember { mutableStateOf(false) }
    var syncFeedbackMessage by remember { mutableStateOf("") }
    var syncFeedbackIsSuccess by remember { mutableStateOf(false) }
    val collapseAnimatable = remember { Animatable(0f) }
    val vibrator = remember {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        }
    }
    // Keep compatibility scans off the critical first-render path. The parsed
    // streams are already collected above; wait until all three have emitted
    // before scheduling the background refresh.
    LaunchedEffect(walletItemsReady) {
        if (!walletItemsReady) return@LaunchedEffect
        delay(1_200L)
        SyncTaskRunner.request(
            request = SyncRequest(
                requestId = SyncDiagnostics.nextTaskId("kp-card-wallet"),
                target = SyncTarget.KeePassCompatibilityIndex(
                    databaseId = null,
                    itemTypes = setOf(SyncItemKind.BANK_CARD, SyncItemKind.DOCUMENT)
                ),
                trigger = SyncTrigger.PAGE_VISIBLE,
                createdAtMillis = System.currentTimeMillis(),
                priority = SyncPriority.PAGE_VISIBLE,
                mode = SyncMode.SILENT,
                throttleMs = 30_000L
            )
        ) {
            bankCardViewModel.syncAllKeePassCardsNow()
            documentViewModel.syncAllKeePassDocumentsNow()
        }
    }

    LaunchedEffect(savedCategoryFilterState, hasRestoredCategoryFilter) {
        if (hasRestoredCategoryFilter) return@LaunchedEffect
        // If state was already restored from SaveableStateRegistry, do not override it.
        if (selectedCategoryFilter != UnifiedCategoryFilterSelection.All) {
            hasRestoredCategoryFilter = true
            return@LaunchedEffect
        }
        val persisted = savedCategoryFilterState ?: return@LaunchedEffect
        selectedCategoryFilter = decodeCardWalletCategoryFilter(persisted)
        hasRestoredCategoryFilter = true
    }

    LaunchedEffect(selectedCategoryFilter, hasRestoredCategoryFilter) {
        if (!hasRestoredCategoryFilter) return@LaunchedEffect
        settingsManager.updateCategoryFilterState(
            scope = SettingsManager.CategoryFilterScope.CARD_WALLET,
            state = encodeCardWalletCategoryFilter(selectedCategoryFilter)
        )
    }

    LaunchedEffect(selectedBitwardenVaultId) {
        onBitwardenScopeChanged(selectedBitwardenVaultId)
    }
    BitwardenAutoSyncEffect(
        viewModel = bitwardenViewModel,
        selectedVaultId = selectedBitwardenVaultId,
        isAllView = selectedCategoryFilter is UnifiedCategoryFilterSelection.All,
        enabled = hasRestoredCategoryFilter && walletItemsReady
    )
    DisposableEffect(Unit) {
        onDispose {
            onBitwardenScopeChanged(null)
        }
    }

    suspend fun resolveSyncableVaultId(): Long? {
        val vaultId = selectedBitwardenVaultId ?: run {
            canRunBitwardenSync = false
            return null
        }
        val unlocked = bitwardenRepository.isVaultUnlocked(vaultId)
        canRunBitwardenSync = unlocked
        return if (unlocked) vaultId else null
    }

    fun vibratePullThreshold(isSyncStage: Boolean) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            if (isSyncStage && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                vibrator?.vibrate(
                    android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_DOUBLE_CLICK)
                )
            } else {
                vibrator?.vibrate(android.os.VibrationEffect.createWaveform(takagi.ru.monica.util.VibrationPatterns.TICK, -1))
            }
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(if (isSyncStage) 36 else 20)
        }
    }

    fun updatePullThresholdHaptics(oldOffset: Float, newOffset: Float) {
        if (oldOffset < searchTriggerDistance && newOffset >= searchTriggerDistance && !hasVibrated) {
            hasVibrated = true
            vibratePullThreshold(isSyncStage = false)
        } else if (newOffset < searchTriggerDistance) {
            hasVibrated = false
        }

        if (!isBitwardenDatabaseView) {
            hasSyncStageVibrated = false
            return
        }

        if (oldOffset < syncTriggerDistance && newOffset >= syncTriggerDistance && !hasSyncStageVibrated) {
            hasSyncStageVibrated = true
            vibratePullThreshold(isSyncStage = true)
        } else if (newOffset < syncTriggerDistance) {
            hasSyncStageVibrated = false
        }
    }

    fun interruptCollapseAnimation() {
        if (!collapseAnimatable.isRunning && !isSettlingBack) return
        isSettlingBack = false
        scope.launch {
            collapseAnimatable.stop()
            collapseAnimatable.snapTo(currentOffset)
        }
    }

    suspend fun collapsePullOffsetSmoothly() {
        if (currentOffset <= 0.5f) {
            currentOffset = 0f
            isSettlingBack = false
            return
        }
        if (collapseAnimatable.isRunning) return
        isSettlingBack = true
        collapseAnimatable.snapTo(currentOffset)
        try {
            collapseAnimatable.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = 140,
                    easing = androidx.compose.animation.core.FastOutLinearInEasing
                )
            ) {
                currentOffset = value
            }
        } finally {
            currentOffset = 0f
            collapseAnimatable.snapTo(0f)
            isSettlingBack = false
        }
    }

    fun onPullRelease(): Boolean {
        if (isBitwardenDatabaseView && syncHintArmed && !isBitwardenSyncing) {
            syncHintArmed = false
            isBitwardenSyncing = true
            lockPullUntilSyncFinished = true
            currentOffset = syncTriggerDistance
            scope.launch {
                val vaultId = resolveSyncableVaultId()
                if (vaultId == null) {
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.pull_sync_requires_bitwarden_login),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    isBitwardenSyncing = false
                    lockPullUntilSyncFinished = false
                    hasVibrated = false
                    hasSyncStageVibrated = false
                    collapsePullOffsetSmoothly()
                    return@launch
                }

                val syncResult = bitwardenRepository.syncForUserVisibleRequest(
                    vaultId = vaultId,
                    requestIdPrefix = "bw-card-wallet-vault"
                )
                when (syncResult) {
                    is BitwardenRepository.SyncResult.Success -> {
                        syncFeedbackIsSuccess = true
                        syncFeedbackMessage = context.getString(R.string.pull_sync_success)
                        showSyncFeedback = true
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.pull_sync_success),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    is BitwardenRepository.SyncResult.Error -> {
                        syncFeedbackIsSuccess = false
                        syncFeedbackMessage = context.getString(R.string.sync_status_failed_full)
                        showSyncFeedback = true
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.sync_status_failed_full) + ": " + syncResult.message,
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    is BitwardenRepository.SyncResult.EmptyVaultBlocked -> {
                        syncFeedbackIsSuccess = false
                        syncFeedbackMessage = context.getString(R.string.sync_status_failed_full)
                        showSyncFeedback = true
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.sync_status_failed_full) + ": " + syncResult.reason,
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                isBitwardenSyncing = false
                lockPullUntilSyncFinished = false
                hasVibrated = false
                hasSyncStageVibrated = false
                collapsePullOffsetSmoothly()
                kotlinx.coroutines.delay(1400)
                showSyncFeedback = false
            }
            return true
        }

        if (currentOffset >= searchTriggerDistance) {
            isSearchExpanded = true
            hasVibrated = false
        }
        return false
    }

    LaunchedEffect(isBitwardenDatabaseView) {
        if (isBitwardenDatabaseView) {
            resolveSyncableVaultId()
        } else {
            interruptCollapseAnimation()
            canRunBitwardenSync = false
            syncHintArmed = false
            isBitwardenSyncing = false
            lockPullUntilSyncFinished = false
            showSyncFeedback = false
            currentOffset = 0f
            hasVibrated = false
            hasSyncStageVibrated = false
        }
    }

    LaunchedEffect(currentOffset >= syncTriggerDistance, isBitwardenDatabaseView, isBitwardenSyncing) {
        if (isBitwardenDatabaseView && currentOffset >= syncTriggerDistance && !isBitwardenSyncing) {
            resolveSyncableVaultId()
        }
    }

    LaunchedEffect(currentOffset, isBitwardenDatabaseView, canRunBitwardenSync, isBitwardenSyncing) {
        if (isBitwardenDatabaseView && currentOffset >= syncTriggerDistance && canRunBitwardenSync && !isBitwardenSyncing) {
            kotlinx.coroutines.delay(syncHoldMillis)
            if (isBitwardenDatabaseView && currentOffset >= syncTriggerDistance && canRunBitwardenSync && !isBitwardenSyncing) {
                syncHintArmed = true
            }
        } else {
            syncHintArmed = false
        }
    }

    LaunchedEffect(isSearchExpanded) {
        if (isSearchExpanded) {
            if (!lockPullUntilSyncFinished && currentOffset > 0.5f) {
                collapsePullOffsetSmoothly()
            } else {
                interruptCollapseAnimation()
                currentOffset = 0f
                isSettlingBack = false
            }
            hasVibrated = false
            hasSyncStageVibrated = false
            syncHintArmed = false
        }
    }

    val allItems = remember(cards, documents, billingAddresses) {
        (cards + documents + billingAddresses).sortedWith(
            compareByDescending<SecureItem> { it.isFavorite }
                .thenBy { it.sortOrder }
                .thenByDescending { it.updatedAt.time }
        )
    }
    val allWalletItems = remember(walletItems) {
        walletItems.sortedWith(
            compareByDescending<WalletListItem> { it.item.isFavorite }
                .thenBy { it.item.sortOrder }
                .thenBy { it.id }
                .thenByDescending { it.item.updatedAt.time }
        )
    }

    val nestedScrollConnection = remember(isBitwardenDatabaseView) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (lockPullUntilSyncFinished) {
                    return available
                }
                if (currentOffset > 0 && available.y < 0) {
                    interruptCollapseAnimation()
                    val newOffset = (currentOffset + available.y).coerceAtLeast(0f)
                    val consumed = currentOffset - newOffset
                    currentOffset = newOffset
                    return Offset(0f, -consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (lockPullUntilSyncFinished) {
                    return available
                }
                if (available.y > 0 && source == NestedScrollSource.UserInput) {
                    interruptCollapseAnimation()
                    val newOffset = calculateDampedPullOffset(
                        currentOffset = currentOffset,
                        dragDelta = available.y,
                        maxDragDistance = maxDragDistance
                    )
                    val oldOffset = currentOffset
                    currentOffset = newOffset
                    updatePullThresholdHaptics(oldOffset = oldOffset, newOffset = newOffset)
                    return available
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                val syncStarted = onPullRelease()
                if (!syncStarted && !lockPullUntilSyncFinished) {
                    collapsePullOffsetSmoothly()
                }
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (!lockPullUntilSyncFinished && currentOffset > 0f) {
                    val syncStarted = onPullRelease()
                    if (!syncStarted && !lockPullUntilSyncFinished) {
                        collapsePullOffsetSmoothly()
                    }
                }
                return Velocity.Zero
            }
        }
    }

    val performDelete: (Set<Long>) -> Unit = { ids ->
        val itemsToDelete = allItems.filter { it.id in ids }
        val affectedVaultIds = itemsToDelete.mapNotNull { it.bitwardenVaultId }.toSet()
        itemsToDelete.forEach { item ->
            when (item.itemType) {
                ItemType.BANK_CARD -> bankCardViewModel.deleteCard(item.id)
                ItemType.DOCUMENT -> documentViewModel.deleteDocument(item.id)
                ItemType.BILLING_ADDRESS -> billingAddressViewModel.deleteAddress(item.id)
                else -> Unit
            }
        }
        affectedVaultIds.forEach(bitwardenRepository::requestLocalMutationSync)
        isSelectionMode = false
        selectedIds = emptySet()
    }

    val requestDeleteVerification: (Set<Long>) -> Unit = requestDeleteVerification@{ ids ->
        if (ids.isEmpty()) return@requestDeleteVerification
        verifyDeleteIds = ids
        verifyPassword = ""
        verifyPasswordError = false
        showVerifyDialog = true
    }

    fun performBatchMove(target: UnifiedMoveCategoryTarget, action: UnifiedMoveAction) {
        scope.launch {
            val targetCategoryId: Long? = when (target) {
                UnifiedMoveCategoryTarget.Uncategorized -> null
                is UnifiedMoveCategoryTarget.MonicaCategory -> target.categoryId
                else -> null
            }
            val targetKeepassDatabaseId: Long? = when (target) {
                is UnifiedMoveCategoryTarget.KeePassDatabaseTarget -> target.databaseId
                is UnifiedMoveCategoryTarget.KeePassGroupTarget -> target.databaseId
                else -> null
            }
            val targetKeepassGroupPath: String? = when (target) {
                is UnifiedMoveCategoryTarget.KeePassGroupTarget -> target.groupPath
                else -> null
            }
            val targetBitwardenVaultId: Long? = when (target) {
                is UnifiedMoveCategoryTarget.BitwardenVaultTarget -> target.vaultId
                is UnifiedMoveCategoryTarget.BitwardenFolderTarget -> target.vaultId
                else -> null
            }
            val targetBitwardenFolderId: String? = when (target) {
                is UnifiedMoveCategoryTarget.BitwardenFolderTarget -> target.folderId
                else -> null
            }
            val targetMdbxDatabaseId: Long? = when (target) {
                is UnifiedMoveCategoryTarget.MdbxDatabaseTarget -> target.databaseId
                is UnifiedMoveCategoryTarget.MdbxFolderTarget -> target.databaseId
                else -> null
            }
            val targetMdbxFolderId: String? = when (target) {
                is UnifiedMoveCategoryTarget.MdbxFolderTarget -> target.folderId
                else -> null
            }
            val isMonicaLocalTarget = target == UnifiedMoveCategoryTarget.Uncategorized ||
                target is UnifiedMoveCategoryTarget.MonicaCategory

            val selectedItems = allItems.filter { selectedIds.contains(it.id) }
            val effectiveAction = if (action == UnifiedMoveAction.MOVE && selectedItems.any { it.isKeePassOwned() }) {
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.keepass_copy_only_hint),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                UnifiedMoveAction.COPY
            } else {
                action
            }
            var successCount = 0
            var failedCount = 0

            selectedItems.forEach { item ->
                when {
                    effectiveAction == UnifiedMoveAction.COPY && item.itemType == ItemType.BANK_CARD && isMonicaLocalTarget -> {
                        if (bankCardViewModel.copyCardToMonicaLocal(item, targetCategoryId) != null) successCount++ else failedCount++
                    }
                    effectiveAction == UnifiedMoveAction.COPY && item.itemType == ItemType.BANK_CARD -> {
                        val cardData = bankCardViewModel.parseCardData(item.itemData) ?: run {
                            failedCount++
                            return@forEach
                        }
                        bankCardViewModel.addCard(
                            title = item.title,
                            cardData = cardData,
                            notes = item.notes,
                            isFavorite = item.isFavorite,
                            imagePaths = item.imagePaths,
                            categoryId = targetCategoryId,
                            keepassDatabaseId = targetKeepassDatabaseId,
                            keepassGroupPath = targetKeepassGroupPath,
                            bitwardenVaultId = targetBitwardenVaultId,
                            bitwardenFolderId = targetBitwardenFolderId,
                            mdbxDatabaseId = targetMdbxDatabaseId,
                            mdbxFolderId = targetMdbxFolderId
                        )
                        successCount++
                    }
                    effectiveAction == UnifiedMoveAction.COPY && item.itemType == ItemType.DOCUMENT && isMonicaLocalTarget -> {
                        if (documentViewModel.copyDocumentToMonicaLocal(item, targetCategoryId) != null) successCount++ else failedCount++
                    }
                    effectiveAction == UnifiedMoveAction.COPY && item.itemType == ItemType.DOCUMENT -> {
                        val documentData = documentViewModel.parseDocumentData(item.itemData) ?: run {
                            failedCount++
                            return@forEach
                        }
                        documentViewModel.addDocument(
                            title = item.title,
                            documentData = documentData,
                            notes = item.notes,
                            isFavorite = item.isFavorite,
                            imagePaths = item.imagePaths,
                            categoryId = targetCategoryId,
                            keepassDatabaseId = targetKeepassDatabaseId,
                            keepassGroupPath = targetKeepassGroupPath,
                            bitwardenVaultId = targetBitwardenVaultId,
                            bitwardenFolderId = targetBitwardenFolderId,
                            mdbxDatabaseId = targetMdbxDatabaseId,
                            mdbxFolderId = targetMdbxFolderId
                        )
                        successCount++
                    }
                    effectiveAction == UnifiedMoveAction.COPY && item.itemType == ItemType.BILLING_ADDRESS && isMonicaLocalTarget -> {
                        if (billingAddressViewModel.copyAddressToMonicaLocal(item, targetCategoryId) != null) successCount++ else failedCount++
                    }
                    effectiveAction == UnifiedMoveAction.COPY && item.itemType == ItemType.BILLING_ADDRESS && targetMdbxDatabaseId != null -> {
                        if (
                            billingAddressViewModel.copyAddressToStorage(
                                item = item,
                                categoryId = null,
                                mdbxDatabaseId = targetMdbxDatabaseId,
                                mdbxFolderId = targetMdbxFolderId
                            ) != null
                        ) {
                            successCount++
                        } else {
                            failedCount++
                        }
                    }
                    effectiveAction == UnifiedMoveAction.COPY && item.itemType == ItemType.BILLING_ADDRESS -> {
                        failedCount++
                    }
                    item.itemType == ItemType.BANK_CARD && isMonicaLocalTarget -> {
                        val moved = if (item.isLocalOnlyItem()) {
                            bankCardViewModel.moveCardToStorage(
                                id = item.id,
                                categoryId = targetCategoryId,
                                keepassDatabaseId = null,
                                keepassGroupPath = null,
                                bitwardenVaultId = null,
                                bitwardenFolderId = null,
                                mdbxDatabaseId = null,
                                mdbxFolderId = null
                            )
                        } else {
                            bankCardViewModel.moveCardToMonicaLocal(item, targetCategoryId).isSuccess
                        }
                        if (moved) successCount++ else failedCount++
                    }
                    item.itemType == ItemType.BANK_CARD -> {
                        val moved = bankCardViewModel.moveCardToStorage(
                            id = item.id,
                            categoryId = targetCategoryId,
                            keepassDatabaseId = targetKeepassDatabaseId,
                            keepassGroupPath = targetKeepassGroupPath,
                            bitwardenVaultId = targetBitwardenVaultId,
                            bitwardenFolderId = targetBitwardenFolderId,
                            mdbxDatabaseId = targetMdbxDatabaseId,
                            mdbxFolderId = targetMdbxFolderId
                        )
                        if (moved) successCount++ else failedCount++
                    }
                    item.itemType == ItemType.DOCUMENT && isMonicaLocalTarget -> {
                        val moved = if (item.isLocalOnlyItem()) {
                            documentViewModel.moveDocumentToStorage(
                                id = item.id,
                                categoryId = targetCategoryId,
                                keepassDatabaseId = null,
                                keepassGroupPath = null,
                                bitwardenVaultId = null,
                                bitwardenFolderId = null,
                                mdbxDatabaseId = null,
                                mdbxFolderId = null
                            )
                        } else {
                            documentViewModel.moveDocumentToMonicaLocal(item, targetCategoryId).isSuccess
                        }
                        if (moved) successCount++ else failedCount++
                    }
                    item.itemType == ItemType.DOCUMENT -> {
                        val moved = documentViewModel.moveDocumentToStorage(
                            id = item.id,
                            categoryId = targetCategoryId,
                            keepassDatabaseId = targetKeepassDatabaseId,
                            keepassGroupPath = targetKeepassGroupPath,
                            bitwardenVaultId = targetBitwardenVaultId,
                            bitwardenFolderId = targetBitwardenFolderId,
                            mdbxDatabaseId = targetMdbxDatabaseId,
                            mdbxFolderId = targetMdbxFolderId
                        )
                        if (moved) successCount++ else failedCount++
                    }
                    item.itemType == ItemType.BILLING_ADDRESS && isMonicaLocalTarget -> {
                        val moved = billingAddressViewModel.moveAddressToStorage(
                            id = item.id,
                            categoryId = targetCategoryId,
                            mdbxDatabaseId = null,
                            mdbxFolderId = null
                        )
                        if (moved) successCount++ else failedCount++
                    }
                    item.itemType == ItemType.BILLING_ADDRESS && targetMdbxDatabaseId != null -> {
                        val moved = billingAddressViewModel.moveAddressToStorage(
                            id = item.id,
                            categoryId = null,
                            mdbxDatabaseId = targetMdbxDatabaseId,
                            mdbxFolderId = targetMdbxFolderId
                        )
                        if (moved) successCount++ else failedCount++
                    }
                    item.itemType == ItemType.BILLING_ADDRESS -> {
                        failedCount++
                    }
                }
            }

            buildSet {
                selectedItems.mapNotNullTo(this) { it.bitwardenVaultId }
                targetBitwardenVaultId?.let { add(it) }
            }.forEach(bitwardenRepository::requestLocalMutationSync)

            val baseMessage = context.getString(R.string.selected_items, successCount)
            val toastMessage = if (failedCount > 0) "$baseMessage，失败$failedCount" else baseMessage
            android.widget.Toast.makeText(
                context,
                toastMessage,
                android.widget.Toast.LENGTH_SHORT
            ).show()
            showBatchMoveCategoryDialog = false
            isSelectionMode = false
            selectedIds = emptySet()
        }
    }

    val filteredItems = remember(allWalletItems, currentTab, searchQuery, selectedCategoryFilter) {
        val query = searchQuery.trim()
        allWalletItems
            .asSequence()
            .filter { walletItem ->
                when (currentTab) {
                    CardWalletTab.ALL -> true
                    CardWalletTab.BANK_CARDS -> walletItem.type == WalletListItemType.BANK_CARD
                    CardWalletTab.DOCUMENTS -> walletItem.type == WalletListItemType.DOCUMENT
                    CardWalletTab.BILLING_ADDRESSES -> walletItem.type == WalletListItemType.BILLING_ADDRESS
                }
            }
            .filter { walletItem ->
                walletItem.matchesCategoryFilter(selectedCategoryFilter)
            }
            .filter { walletItem ->
                walletItem.matchesSearchQuery(query)
            }
            .toList()
    }
    val initialRenderState = resolveMergedListRenderState(
        isReady = walletItemsReady,
        itemCount = filteredItems.size,
    )

    LaunchedEffect(filteredItems) {
        if (selectedIds.isEmpty()) return@LaunchedEffect
        val validIds = filteredItems.map { it.id }.toSet()
        selectedIds = selectedIds.intersect(validIds)
        if (selectedIds.isEmpty()) {
            isSelectionMode = false
        }
    }

    val exitSelection = {
        isSelectionMode = false
        selectedIds = emptySet()
    }
    val selectAll = {
        selectedIds = if (selectedIds.size == filteredItems.size) {
            emptySet()
        } else {
            filteredItems.map { it.id }.toSet()
        }
    }
    val deleteSelected = {
        if (selectedIds.isNotEmpty()) {
            showBatchDeleteDialog = true
        }
    }
    val moveSelected = {
        if (selectedIds.isNotEmpty()) {
            showBatchMoveCategoryDialog = true
        }
    }
    val favoriteSelected = {
        val selectedItems = allItems.filter { it.id in selectedIds }
        if (selectedItems.isNotEmpty()) {
            val shouldFavorite = selectedItems.any { !it.isFavorite }
            selectedItems.forEach { item ->
                if (item.isFavorite == shouldFavorite) return@forEach
                when (item.itemType) {
                    ItemType.BANK_CARD -> bankCardViewModel.toggleFavorite(item.id)
                    ItemType.DOCUMENT -> documentViewModel.toggleFavorite(item.id)
                    ItemType.BILLING_ADDRESS -> billingAddressViewModel.toggleFavorite(item.id)
                    else -> Unit
                }
            }
        }
    }

    LaunchedEffect(isSelectionMode, selectedIds, filteredItems) {
        onBankCardSelectionModeChange(
            isSelectionMode,
            selectedIds.size,
            exitSelection,
            selectAll,
            deleteSelected,
            favoriteSelected,
            moveSelected
        )
        onSelectionModeChange(
            isSelectionMode,
            selectedIds.size,
            exitSelection,
            selectAll,
            moveSelected,
            deleteSelected
        )
    }

    val topBarTitle = when (val filter = selectedCategoryFilter) {
        UnifiedCategoryFilterSelection.All -> stringResource(R.string.nav_card_wallet)
        UnifiedCategoryFilterSelection.Local -> stringResource(R.string.filter_monica)
        UnifiedCategoryFilterSelection.Starred -> stringResource(R.string.filter_starred)
        UnifiedCategoryFilterSelection.Uncategorized -> stringResource(R.string.filter_uncategorized)
        UnifiedCategoryFilterSelection.LocalStarred ->
            "${stringResource(R.string.filter_monica)} · ${stringResource(R.string.filter_starred)}"
        UnifiedCategoryFilterSelection.LocalUncategorized ->
            "${stringResource(R.string.filter_monica)} · ${stringResource(R.string.filter_uncategorized)}"
        is UnifiedCategoryFilterSelection.Custom ->
            categories.find { it.id == filter.categoryId }?.name ?: stringResource(R.string.unknown_category)
        is UnifiedCategoryFilterSelection.KeePassDatabaseFilter ->
            keepassDatabases.find { it.id == filter.databaseId }?.name ?: stringResource(R.string.filter_keepass)
        is UnifiedCategoryFilterSelection.KeePassGroupFilter ->
            decodeKeePassPathForDisplay(filter.groupPath)
        is UnifiedCategoryFilterSelection.KeePassDatabaseStarredFilter -> {
            val name = keepassDatabases.find { it.id == filter.databaseId }?.name ?: stringResource(R.string.filter_keepass)
            "$name · ${stringResource(R.string.filter_starred)}"
        }
        is UnifiedCategoryFilterSelection.KeePassDatabaseUncategorizedFilter -> {
            val name = keepassDatabases.find { it.id == filter.databaseId }?.name ?: stringResource(R.string.filter_keepass)
            "$name · ${stringResource(R.string.filter_uncategorized)}"
        }
        is UnifiedCategoryFilterSelection.MdbxDatabaseFilter ->
            mdbxDatabases.find { it.id == filter.databaseId }?.name ?: "MDBX"
        is UnifiedCategoryFilterSelection.MdbxFolderFilter ->
            mdbxDatabases.find { it.id == filter.databaseId }?.name ?: "MDBX"
        is UnifiedCategoryFilterSelection.BitwardenVaultFilter ->
            stringResource(R.string.filter_bitwarden)
        is UnifiedCategoryFilterSelection.BitwardenFolderFilter ->
            stringResource(R.string.filter_bitwarden)
        is UnifiedCategoryFilterSelection.BitwardenVaultStarredFilter -> {
            "${stringResource(R.string.filter_bitwarden)} · ${stringResource(R.string.filter_starred)}"
        }
        is UnifiedCategoryFilterSelection.BitwardenVaultUncategorizedFilter -> {
            "${stringResource(R.string.filter_bitwarden)} · ${stringResource(R.string.filter_uncategorized)}"
        }
    }

    if (showHistoryPage) {
        TimelineScreen(
            showBackButton = true,
            onNavigateBack = { showHistoryPage = false }
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
    ) {
        ExpressiveTopBar(
            title = topBarTitle,
            searchQuery = searchQuery,
            onSearchQueryChange = { searchQuery = it },
            isSearchExpanded = isSearchExpanded,
            onSearchExpandedChange = { expanded ->
                isSearchExpanded = expanded
                if (!expanded) {
                    searchQuery = ""
                }
            },
            searchHint = stringResource(R.string.topbar_search_hint),
            onActionPillBoundsChanged = { bounds -> categoryPillBoundsInWindow = bounds },
            actions = {
                if (appSettings.categorySelectionUiMode == takagi.ru.monica.data.CategorySelectionUiMode.CHIP_MENU) {
                    IconButton(onClick = { showCategoryFilterDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = stringResource(R.string.category)
                        )
                    }
                } else {
                    IconButton(onClick = { showCategoryFilterDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = stringResource(R.string.category)
                        )
                    }
                }
                IconButton(onClick = { isSearchExpanded = true }) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = stringResource(R.string.search)
                    )
                }
                Box {
                    IconButton(onClick = { showTopActionsMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.more_options)
                        )
                    }
                    if (appSettings.categorySelectionUiMode == takagi.ru.monica.data.CategorySelectionUiMode.CHIP_MENU) {
                        UnifiedCategoryFilterChipMenuDropdown(
                            expanded = showCategoryFilterDialog,
                            onDismissRequest = { showCategoryFilterDialog = false },
                            offset = UnifiedCategoryFilterChipMenuOffset
                        ) {
                            UnifiedCategoryFilterChipMenu(
                                visible = true,
                                onDismiss = { showCategoryFilterDialog = false },
                                selected = selectedCategoryFilter,
                                onSelect = { selection -> selectedCategoryFilter = selection },
                                categories = categories,
                                keepassDatabases = keepassDatabases,
                                mdbxDatabases = mdbxDatabases,
                                bitwardenVaults = bitwardenVaults,
                                getBitwardenFolders = { vaultId -> database.bitwardenFolderDao().getFoldersByVaultFlow(vaultId) },
                                getKeePassGroups = getKeePassGroups,
                                categoryEditMode = categoryMgmt.categoryEditMode,
                                onRequestCategoryAction = { categoryMgmt.categoryActionTarget = it },
                                typeQuickFilters = cardWalletTypeQuickFilters(
                                    currentTab = currentTab,
                                    onTabSelected = { tab ->
                                        onTabSelected(tab)
                                        showCategoryFilterDialog = false
                                    }
                                ),
                                trailingContent = {
                                    CategoryManagementTrailingContent(
                                        state = categoryMgmt,
                                        categories = categories,
                                        keepassDatabases = keepassDatabases,
                                        bitwardenVaults = bitwardenVaults,
                                        getBitwardenFolders = { vaultId -> database.bitwardenFolderDao().getFoldersByVaultFlow(vaultId) },
                                        getKeePassGroups = getKeePassGroups,
                                        passwordViewModel = passwordViewModel,
                                        onDismissFilterSheet = { showCategoryFilterDialog = false }
                                    )
                                }
                            )
                        }
                    }
                    PasswordTopActionsDropdownMenu(
                        expanded = showTopActionsMenu,
                        onDismissRequest = { showTopActionsMenu = false }
                    ) {
                            if (showStandaloneSettingsEntry) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.nav_settings)) },
                                    leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                    onClick = {
                                        showTopActionsMenu = false
                                        onOpenStandaloneSettings()
                                    }
                                )
                            }
                            // CHIP_MENU 模式下类型筛选已并入快捷筛选，避免重复入口
                            if (appSettings.categorySelectionUiMode != takagi.ru.monica.data.CategorySelectionUiMode.CHIP_MENU) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.filter_all)) },
                                leadingIcon = { Icon(Icons.Default.FilterList, contentDescription = null) },
                                trailingIcon = {
                                    if (currentTab == CardWalletTab.ALL) {
                                        Icon(Icons.Default.Check, contentDescription = null)
                                    }
                                },
                                onClick = {
                                    showTopActionsMenu = false
                                    onTabSelected(CardWalletTab.ALL)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.nav_bank_cards_short)) },
                                leadingIcon = { Icon(Icons.Default.CreditCard, contentDescription = null) },
                                trailingIcon = {
                                    if (currentTab == CardWalletTab.BANK_CARDS) {
                                        Icon(Icons.Default.Check, contentDescription = null)
                                    }
                                },
                                onClick = {
                                    showTopActionsMenu = false
                                    onTabSelected(CardWalletTab.BANK_CARDS)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.nav_documents_short)) },
                                leadingIcon = { Icon(Icons.Default.Description, contentDescription = null) },
                                trailingIcon = {
                                    if (currentTab == CardWalletTab.DOCUMENTS) {
                                        Icon(Icons.Default.Check, contentDescription = null)
                                    }
                                },
                                onClick = {
                                    showTopActionsMenu = false
                                    onTabSelected(CardWalletTab.DOCUMENTS)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.billing_address)) },
                                leadingIcon = { Icon(Icons.Default.Home, contentDescription = null) },
                                trailingIcon = {
                                    if (currentTab == CardWalletTab.BILLING_ADDRESSES) {
                                        Icon(Icons.Default.Check, contentDescription = null)
                                    }
                                },
                                onClick = {
                                    showTopActionsMenu = false
                                    onTabSelected(CardWalletTab.BILLING_ADDRESSES)
                                }
                            )
                            }
                            if (selectedBitwardenVaultId != null) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (isTopBarSyncing) {
                                                "${stringResource(R.string.sync_status_syncing_short)}..."
                                            } else {
                                                stringResource(R.string.sync_bitwarden_database_menu)
                                            }
                                        )
                                    },
                                    leadingIcon = {
                                        if (isTopBarSyncing) {
                                            androidx.compose.material3.CircularProgressIndicator(
                                                modifier = Modifier.size(18.dp),
                                                strokeWidth = 2.dp
                                            )
                                        } else {
                                            Icon(Icons.Default.Sync, contentDescription = null)
                                        }
                                    },
                                    enabled = !isTopBarSyncing,
                                    onClick = {
                                        if (isTopBarSyncing) return@DropdownMenuItem
                                        val vaultId = selectedBitwardenVaultId ?: return@DropdownMenuItem
                                        showTopActionsMenu = false
                                        bitwardenViewModel?.requestManualSync(vaultId)
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.timeline_and_trash_title)) },
                                leadingIcon = { Icon(Icons.Default.History, contentDescription = null) },
                                onClick = {
                                    showTopActionsMenu = false
                                    isSelectionMode = false
                                    selectedIds = emptySet()
                                    showHistoryPage = true
                                }
                            )
                        }
                    }
            }
        )

        val contentPullOffset = if (isBitwardenDatabaseView) {
            (currentOffset * 0.28f).toInt()
        } else {
            currentOffset.toInt()
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    initialRenderState == InitialListRenderState.Loading -> LoadingIndicator()
                    initialRenderState == InitialListRenderState.Empty -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .offset { IntOffset(0, contentPullOffset) }
                                .pointerInput(isSearchExpanded) {
                                    detectVerticalDragGestures(
                                        onVerticalDrag = { _, dragAmount ->
                                            if (dragAmount > 0f) {
                                                val newOffset = calculateDampedPullOffset(
                                                    currentOffset = currentOffset,
                                                    dragDelta = dragAmount,
                                                    maxDragDistance = maxDragDistance
                                                )
                                                val oldOffset = currentOffset
                                                currentOffset = newOffset
                                                updatePullThresholdHaptics(oldOffset = oldOffset, newOffset = newOffset)
                                            }
                                        },
                                        onDragEnd = {
                                            val syncStarted = onPullRelease()
                                            if (!syncStarted && !lockPullUntilSyncFinished) {
                                                scope.launch { collapsePullOffsetSmoothly() }
                                            }
                                        },
                                        onDragCancel = {
                                            if (!lockPullUntilSyncFinished) {
                                                scope.launch { collapsePullOffsetSmoothly() }
                                            }
                                        }
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            when (currentTab) {
                                CardWalletTab.BANK_CARDS -> EmptyState(
                                    icon = Icons.Default.CreditCard,
                                    title = stringResource(R.string.no_bank_cards_title),
                                    description = stringResource(R.string.no_bank_cards_description)
                                )

                                CardWalletTab.DOCUMENTS -> EmptyState(
                                    icon = Icons.Default.Description,
                                    title = stringResource(R.string.no_documents_title),
                                    description = stringResource(R.string.no_documents_description)
                                )

                                CardWalletTab.BILLING_ADDRESSES -> EmptyState(
                                    icon = Icons.Default.Home,
                                    title = stringResource(R.string.billing_address),
                                    description = stringResource(R.string.billing_address_empty)
                                )

                                CardWalletTab.ALL -> EmptyState(
                                    icon = Icons.Default.CreditCard,
                                    title = stringResource(R.string.nav_card_wallet),
                                    description = if (searchQuery.isBlank()) {
                                        stringResource(R.string.no_bank_cards_description)
                                    } else {
                                        stringResource(R.string.passkey_no_search_results_hint)
                                    }
                                )
                            }
                        }
                    }

                    else -> {
                        val walletFilterKey = CardWalletListFilterKey(
                            tab = currentTab,
                            query = searchQuery.trim(),
                            categoryFilter = selectedCategoryFilter
                        )
                        var localFilteredItems by remember { mutableStateOf(filteredItems) }
                        var appliedWalletFilterKey by remember {
                            mutableStateOf<CardWalletListFilterKey?>(null)
                        }
                        var walletIsDragging by remember { mutableStateOf(false) }
                        var walletDragBaseOrderIds by remember { mutableStateOf<List<Long>?>(null) }
                        var pendingWalletOrderIds by remember { mutableStateOf<List<Long>?>(null) }
                        // Lists are immutable snapshots. Use identity tokens as effect keys so
                        // scrolling does not repeatedly compare every wallet item by value.
                        val filteredItemsToken = System.identityHashCode(filteredItems)
                        val allWalletItemsToken = System.identityHashCode(allWalletItems)
                        val pendingWalletOrderToken = pendingWalletOrderIds?.let {
                            System.identityHashCode(it)
                        } ?: 0

                        val reorderableLazyListState = rememberReorderableLazyListState(listState) { from, to ->
                            if (isSelectionMode) {
                                localFilteredItems = localFilteredItems.toMutableList().apply {
                                    add(to.index, removeAt(from.index))
                                }
                            }
                        }

                        LaunchedEffect(
                            filteredItemsToken,
                            allWalletItemsToken,
                            walletFilterKey,
                            walletIsDragging,
                            pendingWalletOrderToken
                        ) {
                            if (appliedWalletFilterKey != walletFilterKey) {
                                localFilteredItems = filteredItems
                                appliedWalletFilterKey = walletFilterKey
                                pendingWalletOrderIds = null
                                walletDragBaseOrderIds = null
                                return@LaunchedEffect
                            }
                            if (walletIsDragging) return@LaunchedEffect

                            val preferredOrderIds = pendingWalletOrderIds
                                ?: localFilteredItems.map(WalletListItem::id)
                            val reconciledItems = orderItemsByIds(
                                items = filteredItems,
                                orderedIds = preferredOrderIds,
                                idOf = WalletListItem::id
                            )
                            if (reconciledItems != localFilteredItems) {
                                localFilteredItems = reconciledItems
                            }

                            val pendingOrderIds = pendingWalletOrderIds ?: return@LaunchedEffect
                            val persistedOrderIds = allWalletItems.map(WalletListItem::id)
                            val persistedSortOrders = allWalletItems.associate { walletItem ->
                                walletItem.id to walletItem.item.sortOrder
                            }
                            val writeBackFinished = persistedOrderIds == pendingOrderIds ||
                                pendingOrderIds.withIndex().all { (index, id) ->
                                    persistedSortOrders[id] == index
                                }
                            if (writeBackFinished) {
                                pendingWalletOrderIds = null
                            }
                        }

                        LaunchedEffect(reorderableLazyListState.isAnyItemDragging) {
                            if (reorderableLazyListState.isAnyItemDragging) {
                                if (!walletIsDragging) {
                                    walletDragBaseOrderIds = allWalletItems.map(WalletListItem::id)
                                    walletIsDragging = true
                                }
                            } else if (walletIsDragging) {
                                if (isSelectionMode) {
                                    val baseOrderIds = walletDragBaseOrderIds
                                        ?: allWalletItems.map(WalletListItem::id)
                                    val baseOrderIdSet = baseOrderIds.toSet()
                                    val stableBaseOrderIds = baseOrderIds + allWalletItems
                                        .map(WalletListItem::id)
                                        .filterNot { id -> id in baseOrderIdSet }
                                    val mergedIds = mergeVisibleWalletOrder(
                                        allItemIds = stableBaseOrderIds,
                                        reorderedVisibleItemIds = localFilteredItems.map(WalletListItem::id)
                                    )
                                    val currentItemsById = allWalletItems.associateBy(WalletListItem::id)
                                    val newOrders = mergedIds.mapIndexedNotNull { index, id ->
                                        val walletItem = currentItemsById[id] ?: return@mapIndexedNotNull null
                                        if (walletItem.item.sortOrder == index) null else id to index
                                    }
                                    if (newOrders.isNotEmpty()) {
                                        // 三种类型共用 SecureItemRepository；一次写入可避免
                                        // 多次 Flow 失效把刚完成的本地拖动顺序拉回旧状态。
                                        pendingWalletOrderIds = mergedIds
                                        bankCardViewModel.updateSortOrders(newOrders)
                                    }
                                }
                                walletDragBaseOrderIds = null
                                walletIsDragging = false
                            }
                        }

                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .offset { IntOffset(0, contentPullOffset) }
                                .nestedScroll(nestedScrollConnection),
                            userScrollEnabled = true,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            items(localFilteredItems, key = { it.id }) { walletItem ->
                                val item = walletItem.item
                                ReorderableItem(
                                    reorderableLazyListState,
                                    key = walletItem.id,
                                    enabled = isSelectionMode
                                ) { isDragging ->
                                    val isSelected = selectedIds.contains(walletItem.id)
                                    val toggleSelection = {
                                        val nextSelectedIds = if (isSelected) {
                                            selectedIds - item.id
                                        } else {
                                            selectedIds + item.id
                                        }
                                        selectedIds = nextSelectedIds
                                        isSelectionMode = nextSelectedIds.isNotEmpty()
                                    }
                                    val elevation by animateDpAsState(
                                        if (isDragging) 8.dp else 0.dp,
                                        label = "wallet_drag_elevation"
                                    )
                                    val dragModifier = if (isSelectionMode) {
                                        Modifier.longPressDraggableHandle()
                                    } else {
                                        Modifier
                                    }
                                    val cardModifier = Modifier
                                        .padding(bottom = 8.dp)
                                        .graphicsLayer {
                                            shadowElevation = elevation.toPx()
                                        }
                                        .then(dragModifier)

                                    SwipeActions(
                                        onSwipeLeft = { itemToDelete = item },
                                        onSwipeRight = toggleSelection,
                                        isSwiped = isSelected,
                                        enabled = !isDragging,
                                        allowSwipeLeft = !isSelectionMode,
                                        allowSwipeRight = true,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        when (walletItem.type) {
                                            WalletListItemType.BANK_CARD -> BankCardCard(
                                                item = item,
                                                onClick = {
                                                    if (isSelectionMode) {
                                                        toggleSelection()
                                                    } else {
                                                        onCardClick(item.id)
                                                    }
                                                },
                                                onDelete = { itemToDelete = item },
                                                onToggleFavorite = { id, _ -> bankCardViewModel.toggleFavorite(id) },
                                                isSelectionMode = isSelectionMode,
                                                isSelected = isSelected,
                                                onLongClick = {
                                                    if (!isSelectionMode) {
                                                        isSelectionMode = true
                                                        selectedIds = setOf(item.id)
                                                    } else {
                                                        toggleSelection()
                                                    }
                                                },
                                                modifier = cardModifier,
                                                cardData = walletItem.bankCardData
                                            )

                                            WalletListItemType.DOCUMENT -> DocumentCard(
                                                item = item,
                                                onClick = {
                                                    if (isSelectionMode) {
                                                        toggleSelection()
                                                    } else {
                                                        onDocumentClick(item.id)
                                                    }
                                                },
                                                onDelete = { itemToDelete = item },
                                                onToggleFavorite = { id, _ -> documentViewModel.toggleFavorite(id) },
                                                isSelectionMode = isSelectionMode,
                                                isSelected = isSelected,
                                                onLongClick = {
                                                    if (!isSelectionMode) {
                                                        isSelectionMode = true
                                                        selectedIds = setOf(item.id)
                                                    } else {
                                                        toggleSelection()
                                                    }
                                                },
                                                modifier = cardModifier,
                                                documentData = walletItem.documentData
                                            )

                                            WalletListItemType.BILLING_ADDRESS -> BillingAddressCard(
                                                item = item,
                                                onClick = {
                                                    if (isSelectionMode) {
                                                        toggleSelection()
                                                    } else {
                                                        onBillingAddressClick(item.id)
                                                    }
                                                },
                                                onDelete = { itemToDelete = item },
                                                onToggleFavorite = { id, _ -> billingAddressViewModel.toggleFavorite(id) },
                                                isSelectionMode = isSelectionMode,
                                                isSelected = isSelected,
                                                onLongClick = {
                                                    if (!isSelectionMode) {
                                                        isSelectionMode = true
                                                        selectedIds = setOf(item.id)
                                                    } else {
                                                        toggleSelection()
                                                    }
                                                },
                                                modifier = cardModifier,
                                                addressData = walletItem.billingAddressData
                                            )
                                        }
                                    }
                                }
                            }
                            item { Box(modifier = Modifier.height(80.dp)) }
                        }
                    }
                }
            }
        }
    }

    itemToDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            title = {
                Text(
                    stringResource(
                        when (item.itemType) {
                            ItemType.BANK_CARD -> R.string.delete_bank_card_title
                            ItemType.DOCUMENT -> R.string.delete_document_title
                            ItemType.BILLING_ADDRESS -> R.string.delete_billing_address_title
                            else -> R.string.delete
                        }
                    )
                )
            },
            text = {
                Text(
                    stringResource(
                        when (item.itemType) {
                            ItemType.BANK_CARD -> R.string.delete_bank_card_message
                            ItemType.DOCUMENT -> R.string.delete_document_message
                            ItemType.BILLING_ADDRESS -> R.string.delete_billing_address_list_message
                            else -> R.string.delete_bank_card_message
                        },
                        item.title
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    requestDeleteVerification(setOf(item.id))
                    itemToDelete = null
                }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showBatchDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteDialog = false },
            title = { Text(stringResource(R.string.batch_delete_title)) },
            text = { Text(stringResource(R.string.batch_delete_message, selectedIds.size)) },
            confirmButton = {
                TextButton(onClick = {
                    requestDeleteVerification(selectedIds)
                    showBatchDeleteDialog = false
                }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showVerifyDialog) {
        val skipIdentityVerification = appSettings.disablePasswordVerification
        val biometricAction = if (
            !skipIdentityVerification &&
            activity != null &&
            appSettings.biometricEnabled &&
            biometricHelper.isBiometricAvailable()
        ) {
            {
                biometricHelper.authenticate(
                    activity = activity,
                    title = context.getString(R.string.verify_identity),
                    subtitle = context.getString(R.string.verify_to_delete),
                    onSuccess = {
                        performDelete(verifyDeleteIds)
                        verifyDeleteIds = emptySet()
                        verifyPassword = ""
                        verifyPasswordError = false
                        showVerifyDialog = false
                    },
                    onError = { error ->
                        android.widget.Toast.makeText(context, error, android.widget.Toast.LENGTH_SHORT).show()
                    },
                    onFailed = {}
                )
            }
        } else {
            null
        }

        M3IdentityVerifyDialog(
            title = stringResource(R.string.verify_identity),
            message = if (verifyDeleteIds.size > 1) {
                stringResource(R.string.batch_delete_message, verifyDeleteIds.size)
            } else {
                stringResource(R.string.verify_identity_to_delete)
            },
            passwordValue = verifyPassword,
            onPasswordChange = {
                verifyPassword = it
                verifyPasswordError = false
            },
            onDismiss = {
                showVerifyDialog = false
                verifyDeleteIds = emptySet()
                verifyPassword = ""
                verifyPasswordError = false
            },
            onConfirm = {
                scope.launch {
                    if (skipIdentityVerification || securityManager.verifyMasterPassword(verifyPassword)) {
                        performDelete(verifyDeleteIds)
                        verifyDeleteIds = emptySet()
                        verifyPassword = ""
                        verifyPasswordError = false
                        showVerifyDialog = false
                    } else {
                        verifyPasswordError = true
                    }
                }
            },
            confirmText = stringResource(R.string.delete),
            destructiveConfirm = true,
            requireIdentityVerification = !skipIdentityVerification,
            isPasswordError = verifyPasswordError,
            passwordErrorText = stringResource(R.string.current_password_incorrect),
            onBiometricClick = biometricAction,
            biometricHintText = if (biometricAction == null) {
                context.getString(R.string.biometric_not_available)
            } else {
                null
            }
        )
    }

    if (showBatchMoveCategoryDialog) {
        UnifiedMoveToCategoryBottomSheet(
            visible = true,
            onDismiss = { showBatchMoveCategoryDialog = false },
            initialSource = selectedCategoryFilter.toUnifiedMoveInitialSource(),
            categories = categories,
            keepassDatabases = keepassDatabases,
            mdbxDatabases = mdbxDatabases,
            bitwardenVaults = bitwardenVaults,
            getBitwardenFolders = { vaultId -> database.bitwardenFolderDao().getFoldersByVaultFlow(vaultId) },
            getKeePassGroups = getKeePassGroups,
            getMdbxFolders = passwordViewModel::getMdbxFolders,
            allowCopy = true,
            allowMove = allItems.filter { selectedIds.contains(it.id) }.none { it.isKeePassOwned() },
            onTargetSelected = ::performBatchMove
        )
    }

    CategoryManagementCreateDialog(
        state = categoryMgmt,
        currentFilter = selectedCategoryFilter,
        categories = categories,
        keepassDatabases = keepassDatabases,
        mdbxDatabases = mdbxDatabases,
        bitwardenVaults = bitwardenVaults,
        getKeePassGroups = getKeePassGroups,
        passwordViewModel = passwordViewModel,
        bitwardenRepository = bitwardenRepository,
        keepassBridge = keepassBridge,
        scope = scope
    )
}

private fun encodeCardWalletCategoryFilter(filter: UnifiedCategoryFilterSelection): SavedCategoryFilterState {
    return when (filter) {
        UnifiedCategoryFilterSelection.All -> SavedCategoryFilterState(type = "all")
        UnifiedCategoryFilterSelection.Local -> SavedCategoryFilterState(type = "local")
        UnifiedCategoryFilterSelection.Starred -> SavedCategoryFilterState(type = "starred")
        UnifiedCategoryFilterSelection.Uncategorized -> SavedCategoryFilterState(type = "uncategorized")
        UnifiedCategoryFilterSelection.LocalStarred -> SavedCategoryFilterState(type = "local_starred")
        UnifiedCategoryFilterSelection.LocalUncategorized -> SavedCategoryFilterState(type = "local_uncategorized")
        is UnifiedCategoryFilterSelection.Custom -> SavedCategoryFilterState(type = "custom", primaryId = filter.categoryId)
        is UnifiedCategoryFilterSelection.BitwardenVaultFilter -> SavedCategoryFilterState(type = "bitwarden_vault", primaryId = filter.vaultId)
        is UnifiedCategoryFilterSelection.BitwardenFolderFilter -> SavedCategoryFilterState(type = "bitwarden_folder", primaryId = filter.vaultId, text = filter.folderId)
        is UnifiedCategoryFilterSelection.BitwardenVaultStarredFilter -> SavedCategoryFilterState(type = "bitwarden_vault_starred", primaryId = filter.vaultId)
        is UnifiedCategoryFilterSelection.BitwardenVaultUncategorizedFilter -> SavedCategoryFilterState(type = "bitwarden_vault_uncategorized", primaryId = filter.vaultId)
        is UnifiedCategoryFilterSelection.KeePassDatabaseFilter -> SavedCategoryFilterState(type = "keepass_database", primaryId = filter.databaseId)
        is UnifiedCategoryFilterSelection.KeePassGroupFilter -> SavedCategoryFilterState(
            type = "keepass_group",
            primaryId = filter.databaseId,
            text = filter.groupPath,
            groupUuid = filter.groupUuid
        )
        is UnifiedCategoryFilterSelection.KeePassDatabaseStarredFilter -> SavedCategoryFilterState(type = "keepass_database_starred", primaryId = filter.databaseId)
        is UnifiedCategoryFilterSelection.KeePassDatabaseUncategorizedFilter -> SavedCategoryFilterState(type = "keepass_database_uncategorized", primaryId = filter.databaseId)
        is UnifiedCategoryFilterSelection.MdbxDatabaseFilter -> SavedCategoryFilterState(type = "mdbx_database", primaryId = filter.databaseId)
        else -> SavedCategoryFilterState(type = "all")
    }
}

private val cardWalletCategoryFilterSaver = listSaver<UnifiedCategoryFilterSelection, Any?>(
    save = { filter ->
        val state = encodeCardWalletCategoryFilter(filter)
        listOf(state.type, state.primaryId, state.secondaryId, state.text, state.groupUuid)
    },
    restore = { saved ->
        decodeCardWalletCategoryFilter(
            SavedCategoryFilterState(
                type = saved.getOrNull(0) as? String ?: "all",
                primaryId = saved.getOrNull(1) as? Long,
                secondaryId = saved.getOrNull(2) as? Long,
                text = saved.getOrNull(3) as? String,
                groupUuid = saved.getOrNull(4) as? String
            )
        )
    }
)

private fun decodeCardWalletCategoryFilter(state: SavedCategoryFilterState): UnifiedCategoryFilterSelection {
    return when (state.type) {
        "local" -> UnifiedCategoryFilterSelection.Local
        "starred" -> UnifiedCategoryFilterSelection.Starred
        "uncategorized" -> UnifiedCategoryFilterSelection.Uncategorized
        "local_starred" -> UnifiedCategoryFilterSelection.LocalStarred
        "local_uncategorized" -> UnifiedCategoryFilterSelection.LocalUncategorized
        "custom" -> state.primaryId?.let { UnifiedCategoryFilterSelection.Custom(it) } ?: UnifiedCategoryFilterSelection.All
        "bitwarden_vault" -> state.primaryId?.let { UnifiedCategoryFilterSelection.BitwardenVaultFilter(it) } ?: UnifiedCategoryFilterSelection.All
        "bitwarden_folder" -> {
            val vaultId = state.primaryId
            val folderId = state.text
            if (vaultId != null && !folderId.isNullOrBlank()) {
                UnifiedCategoryFilterSelection.BitwardenFolderFilter(vaultId, folderId)
            } else {
                UnifiedCategoryFilterSelection.All
            }
        }
        "bitwarden_vault_starred" -> state.primaryId?.let { UnifiedCategoryFilterSelection.BitwardenVaultStarredFilter(it) } ?: UnifiedCategoryFilterSelection.All
        "bitwarden_vault_uncategorized" -> state.primaryId?.let { UnifiedCategoryFilterSelection.BitwardenVaultUncategorizedFilter(it) } ?: UnifiedCategoryFilterSelection.All
        "keepass_database" -> state.primaryId?.let { UnifiedCategoryFilterSelection.KeePassDatabaseFilter(it) } ?: UnifiedCategoryFilterSelection.All
        "keepass_group" -> {
            val databaseId = state.primaryId
            val groupPath = state.text
            if (databaseId != null && !groupPath.isNullOrBlank()) {
                UnifiedCategoryFilterSelection.KeePassGroupFilter(databaseId, groupPath, state.groupUuid)
            } else {
                UnifiedCategoryFilterSelection.All
            }
        }
        "keepass_database_starred" -> state.primaryId?.let { UnifiedCategoryFilterSelection.KeePassDatabaseStarredFilter(it) } ?: UnifiedCategoryFilterSelection.All
        "keepass_database_uncategorized" -> state.primaryId?.let { UnifiedCategoryFilterSelection.KeePassDatabaseUncategorizedFilter(it) } ?: UnifiedCategoryFilterSelection.All
        "mdbx_database" -> state.primaryId?.let { UnifiedCategoryFilterSelection.MdbxDatabaseFilter(it) } ?: UnifiedCategoryFilterSelection.All
        else -> UnifiedCategoryFilterSelection.All
    }
}


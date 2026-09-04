package takagi.ru.monica.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.fragment.app.FragmentActivity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.sync.isUserVisibleSyncInProgress
import takagi.ru.monica.bitwarden.ui.BitwardenAutoSyncEffect
import takagi.ru.monica.repository.KeePassCompatibilityBridge
import takagi.ru.monica.repository.KeePassWorkspaceRepository
import takagi.ru.monica.data.BottomNavContentTab
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.isKeePassOwned
import takagi.ru.monica.data.isLocalOnlyItem
import takagi.ru.monica.data.model.PasskeyBindingCodec
import takagi.ru.monica.data.model.TimelineEvent
import takagi.ru.monica.data.model.TotpData
import takagi.ru.monica.utils.BiometricHelper
import takagi.ru.monica.utils.decodeKeePassPathForDisplay
import takagi.ru.monica.utils.planLocalCategoryMove
import takagi.ru.monica.ui.category.CategoryManagementTrailingContent
import takagi.ru.monica.ui.category.CategoryManagementCreateDialog
import takagi.ru.monica.ui.category.rememberCategoryManagementState
import takagi.ru.monica.viewmodel.PasswordViewModel
import takagi.ru.monica.viewmodel.SettingsViewModel
import takagi.ru.monica.viewmodel.TotpViewModel
import takagi.ru.monica.viewmodel.CategoryFilter
import takagi.ru.monica.data.Category
import takagi.ru.monica.data.AuthenticatorLayoutMode
import takagi.ru.monica.data.AppSettings
import takagi.ru.monica.viewmodel.BankCardViewModel
import takagi.ru.monica.viewmodel.DocumentViewModel
import takagi.ru.monica.viewmodel.GeneratorViewModel
import takagi.ru.monica.viewmodel.GeneratorType
import takagi.ru.monica.viewmodel.NoteViewModel
import takagi.ru.monica.viewmodel.PasskeyViewModel
import takagi.ru.monica.viewmodel.TimelineViewModel
import takagi.ru.monica.ui.screens.SettingsScreen
import takagi.ru.monica.ui.screens.GeneratorScreen  // 添加生成器页面导入
import takagi.ru.monica.ui.screens.NoteListScreen
import takagi.ru.monica.ui.screens.NoteListContent
import takagi.ru.monica.ui.screens.PasswordDetailScreen
import takagi.ru.monica.ui.screens.SendScreen
import takagi.ru.monica.ui.screens.CardWalletScreen
import takagi.ru.monica.ui.screens.CardWalletTab
import takagi.ru.monica.ui.screens.BankCardDetailScreen
import takagi.ru.monica.ui.screens.DocumentDetailScreen
import takagi.ru.monica.ui.screens.TimelineScreen
import takagi.ru.monica.ui.screens.PasskeyListScreen
import takagi.ru.monica.ui.gestures.SwipeActions
import takagi.ru.monica.ui.haptic.rememberHapticFeedback
import kotlin.math.absoluteValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import takagi.ru.monica.ui.components.QrCodeDialog
import takagi.ru.monica.ui.components.ExpressiveTopBar
import takagi.ru.monica.ui.components.DraggableBottomNavScaffold
import takagi.ru.monica.ui.components.SwipeableAddFab
import takagi.ru.monica.ui.components.DraggableNavItem
import takagi.ru.monica.ui.components.QuickActionItem
import takagi.ru.monica.ui.components.QuickAddCallback
import takagi.ru.monica.ui.components.SyncStatusIcon
import takagi.ru.monica.ui.components.M3IdentityVerifyDialog
import takagi.ru.monica.ui.components.MonicaTileGrid
import takagi.ru.monica.ui.components.UnifiedCategoryFilterChipMenu
import takagi.ru.monica.ui.components.UnifiedCategoryFilterChipMenuDropdown
import takagi.ru.monica.ui.components.UnifiedCategoryFilterChipMenuOffset
import takagi.ru.monica.ui.components.UnifiedCategoryFilterSelection
import takagi.ru.monica.ui.components.UnifiedMoveAction
import takagi.ru.monica.ui.components.UnifiedMoveCategoryTarget
import takagi.ru.monica.ui.components.UnifiedMoveToCategoryBottomSheet
import takagi.ru.monica.ui.components.toUnifiedMoveInitialSource
import takagi.ru.monica.ui.common.dialog.DeleteConfirmDialog
import takagi.ru.monica.ui.common.layout.DetailPane
import takagi.ru.monica.ui.common.layout.InspectorRow
import takagi.ru.monica.ui.common.layout.ListPane
import takagi.ru.monica.ui.common.pull.rememberPullActionState
import takagi.ru.monica.ui.common.state.rememberSaveableLazyListState
import takagi.ru.monica.ui.common.selection.CategoryListItem
import takagi.ru.monica.ui.common.selection.SelectionActionBar
import takagi.ru.monica.ui.common.selection.SelectionModeTopBar
import takagi.ru.monica.ui.main.navigation.BottomNavItem
import takagi.ru.monica.ui.main.navigation.fullLabelRes
import takagi.ru.monica.ui.main.navigation.indexToDefaultTabKey
import takagi.ru.monica.ui.main.navigation.shortLabelRes
import takagi.ru.monica.ui.main.navigation.toBottomNavItem
import takagi.ru.monica.ui.main.layout.AdaptiveMainScaffold
import takagi.ru.monica.ui.password.buildAdditionalInfoPreview
import takagi.ru.monica.ui.password.MultiPasswordEntryCard
import takagi.ru.monica.ui.password.PasswordTopActionsDropdownMenu
import takagi.ru.monica.ui.password.StackedPasswordGroup
import takagi.ru.monica.ui.password.PasswordEntryCard
import takagi.ru.monica.ui.password.StackCardMode
import takagi.ru.monica.ui.password.getGroupKeyForMode
import takagi.ru.monica.ui.password.getPasswordGroupTitle
import takagi.ru.monica.ui.password.getPasswordInfoKey
import takagi.ru.monica.data.bitwarden.BitwardenPendingOperation
import takagi.ru.monica.data.bitwarden.BitwardenSend
import takagi.ru.monica.bitwarden.sync.SyncStatus
import takagi.ru.monica.security.SecurityManager
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import sh.calvin.reorderable.rememberReorderableLazyGridState
import takagi.ru.monica.ui.screens.AddEditPasswordScreen
import takagi.ru.monica.ui.screens.AddEditTotpScreen
import takagi.ru.monica.ui.screens.AddEditBankCardScreen
import takagi.ru.monica.ui.screens.AddEditDocumentScreen
import takagi.ru.monica.ui.screens.AddEditNoteScreen
import takagi.ru.monica.ui.screens.AddEditSendScreen
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.util.TotpDataResolver
import takagi.ru.monica.util.TotpGenerator
import takagi.ru.monica.data.model.OtpType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TotpListContent(
    viewModel: takagi.ru.monica.viewmodel.TotpViewModel,
    passwordViewModel: PasswordViewModel,
    appSettings: AppSettings,
    onAuthenticatorLayoutModeChange: (AuthenticatorLayoutMode) -> Unit,
    onTotpClick: (Long) -> Unit,
    onDeleteTotp: (takagi.ru.monica.data.SecureItem) -> Unit,
    onQuickScanTotp: () -> Unit,
    onSelectionModeChange: (
        isSelectionMode: Boolean,
        selectedCount: Int,
        onExit: () -> Unit,
        onSelectAll: () -> Unit,
        onMoveToCategory: () -> Unit,
        onDelete: () -> Unit
    ) -> Unit,
    showStandaloneSettingsEntry: Boolean = false,
    onOpenStandaloneSettings: () -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val securityManager = remember(context) { SecurityManager(context.applicationContext) }
    val bitwardenRepository = remember { takagi.ru.monica.bitwarden.repository.BitwardenRepository.getInstance(context) }
    val database = remember { takagi.ru.monica.data.PasswordDatabase.getDatabase(context) }
    val scope = rememberCoroutineScope()
    val keepassDatabases by database.localKeePassDatabaseDao().getAllDatabases().collectAsState(initial = emptyList())
    val mdbxDatabases by database.localMdbxDatabaseDao().getAllDatabases().collectAsState(initial = emptyList())
    val bitwardenVaults by database.bitwardenVaultDao().getAllVaultsFlow().collectAsState(initial = emptyList())
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
        mutableMapOf<Long, kotlinx.coroutines.flow.MutableStateFlow<List<takagi.ru.monica.utils.KeePassGroupInfo>>>()
    }
    val getKeePassGroups: (Long) -> kotlinx.coroutines.flow.Flow<List<takagi.ru.monica.utils.KeePassGroupInfo>> = remember {
        { databaseId ->
            val flow = keepassGroupFlows.getOrPut(databaseId) {
                kotlinx.coroutines.flow.MutableStateFlow(emptyList())
            }
            if (flow.value.isEmpty()) {
                scope.launch {
                    flow.value = keepassBridge.listLegacyGroups(databaseId).getOrDefault(emptyList())
                }
            }
            flow
        }
    }
    val parsedTotpItems by viewModel.parsedTotpItems.collectAsState()
    val totpItems = remember(parsedTotpItems) { parsedTotpItems.map { it.item } }
    val totpDataById = remember(parsedTotpItems) { parsedTotpItems.associate { it.item.id to it.totpData } }
    val searchQuery by viewModel.searchQuery.collectAsState()
    // The list only needs titles for bound-password delete messaging. Use the
    // metadata-only stream so entering the authenticator does not decrypt every
    // password just to build this lookup map.
    val passwords by passwordViewModel.allPasswordsForUi.collectAsState(initial = emptyList())
    val passwordMap = remember(passwords) { passwords.associateBy { it.id } }
    val haptic = rememberHapticFeedback()
    val focusManager = LocalFocusManager.current
    var isSearchExpanded by rememberSaveable { mutableStateOf(false) }
    var showTopActionsMenu by remember { mutableStateOf(false) }

    // 分类选择状态
    var isCategorySheetVisible by rememberSaveable { mutableStateOf(false) }
    var categoryPillBoundsInWindow by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val categories by viewModel.categories.collectAsState()
    val categoryMgmt = rememberCategoryManagementState()
    val currentFilter by viewModel.categoryFilter.collectAsState()
    val totpSelectedFilter = when (val filter = currentFilter) {
        is takagi.ru.monica.viewmodel.TotpCategoryFilter.All -> UnifiedCategoryFilterSelection.All
        is takagi.ru.monica.viewmodel.TotpCategoryFilter.Local -> UnifiedCategoryFilterSelection.Local
        is takagi.ru.monica.viewmodel.TotpCategoryFilter.Starred -> UnifiedCategoryFilterSelection.Starred
        is takagi.ru.monica.viewmodel.TotpCategoryFilter.Uncategorized -> UnifiedCategoryFilterSelection.Uncategorized
        is takagi.ru.monica.viewmodel.TotpCategoryFilter.LocalStarred -> UnifiedCategoryFilterSelection.LocalStarred
        is takagi.ru.monica.viewmodel.TotpCategoryFilter.LocalUncategorized -> UnifiedCategoryFilterSelection.LocalUncategorized
        is takagi.ru.monica.viewmodel.TotpCategoryFilter.Custom -> UnifiedCategoryFilterSelection.Custom(filter.categoryId)
        is takagi.ru.monica.viewmodel.TotpCategoryFilter.KeePassDatabase -> UnifiedCategoryFilterSelection.KeePassDatabaseFilter(filter.databaseId)
        is takagi.ru.monica.viewmodel.TotpCategoryFilter.KeePassGroupFilter -> UnifiedCategoryFilterSelection.KeePassGroupFilter(
            filter.databaseId,
            filter.groupPath,
            filter.groupUuid
        )
        is takagi.ru.monica.viewmodel.TotpCategoryFilter.KeePassDatabaseStarred -> UnifiedCategoryFilterSelection.KeePassDatabaseStarredFilter(filter.databaseId)
        is takagi.ru.monica.viewmodel.TotpCategoryFilter.KeePassDatabaseUncategorized -> UnifiedCategoryFilterSelection.KeePassDatabaseUncategorizedFilter(filter.databaseId)
        is takagi.ru.monica.viewmodel.TotpCategoryFilter.BitwardenVault -> UnifiedCategoryFilterSelection.BitwardenVaultFilter(filter.vaultId)
        is takagi.ru.monica.viewmodel.TotpCategoryFilter.BitwardenFolderFilter -> UnifiedCategoryFilterSelection.BitwardenFolderFilter(filter.vaultId, filter.folderId)
        is takagi.ru.monica.viewmodel.TotpCategoryFilter.BitwardenVaultStarred -> UnifiedCategoryFilterSelection.BitwardenVaultStarredFilter(filter.vaultId)
        is takagi.ru.monica.viewmodel.TotpCategoryFilter.BitwardenVaultUncategorized -> UnifiedCategoryFilterSelection.BitwardenVaultUncategorizedFilter(filter.vaultId)
        is takagi.ru.monica.viewmodel.TotpCategoryFilter.MdbxDatabase -> UnifiedCategoryFilterSelection.MdbxDatabaseFilter(filter.databaseId)
    }
    val handleCategorySelection: (UnifiedCategoryFilterSelection) -> Unit = { selection ->
        when (selection) {
            is UnifiedCategoryFilterSelection.All -> viewModel.setCategoryFilter(takagi.ru.monica.viewmodel.TotpCategoryFilter.All)
            is UnifiedCategoryFilterSelection.Local -> viewModel.setCategoryFilter(takagi.ru.monica.viewmodel.TotpCategoryFilter.Local)
            is UnifiedCategoryFilterSelection.Starred -> viewModel.setCategoryFilter(takagi.ru.monica.viewmodel.TotpCategoryFilter.Starred)
            is UnifiedCategoryFilterSelection.Uncategorized -> viewModel.setCategoryFilter(takagi.ru.monica.viewmodel.TotpCategoryFilter.Uncategorized)
            is UnifiedCategoryFilterSelection.LocalStarred -> viewModel.setCategoryFilter(takagi.ru.monica.viewmodel.TotpCategoryFilter.LocalStarred)
            is UnifiedCategoryFilterSelection.LocalUncategorized -> viewModel.setCategoryFilter(takagi.ru.monica.viewmodel.TotpCategoryFilter.LocalUncategorized)
            is UnifiedCategoryFilterSelection.Custom -> viewModel.setCategoryFilter(takagi.ru.monica.viewmodel.TotpCategoryFilter.Custom(selection.categoryId))
            is UnifiedCategoryFilterSelection.KeePassDatabaseFilter -> viewModel.setCategoryFilter(takagi.ru.monica.viewmodel.TotpCategoryFilter.KeePassDatabase(selection.databaseId))
            is UnifiedCategoryFilterSelection.KeePassGroupFilter -> viewModel.setCategoryFilter(
                takagi.ru.monica.viewmodel.TotpCategoryFilter.KeePassGroupFilter(
                    selection.databaseId,
                    selection.groupPath,
                    selection.groupUuid
                )
            )
            is UnifiedCategoryFilterSelection.KeePassDatabaseStarredFilter -> viewModel.setCategoryFilter(takagi.ru.monica.viewmodel.TotpCategoryFilter.KeePassDatabaseStarred(selection.databaseId))
            is UnifiedCategoryFilterSelection.KeePassDatabaseUncategorizedFilter -> viewModel.setCategoryFilter(takagi.ru.monica.viewmodel.TotpCategoryFilter.KeePassDatabaseUncategorized(selection.databaseId))
            is UnifiedCategoryFilterSelection.BitwardenVaultFilter -> viewModel.setCategoryFilter(takagi.ru.monica.viewmodel.TotpCategoryFilter.BitwardenVault(selection.vaultId))
            is UnifiedCategoryFilterSelection.BitwardenFolderFilter -> viewModel.setCategoryFilter(takagi.ru.monica.viewmodel.TotpCategoryFilter.BitwardenFolderFilter(selection.folderId, selection.vaultId))
            is UnifiedCategoryFilterSelection.BitwardenVaultStarredFilter -> viewModel.setCategoryFilter(takagi.ru.monica.viewmodel.TotpCategoryFilter.BitwardenVaultStarred(selection.vaultId))
            is UnifiedCategoryFilterSelection.BitwardenVaultUncategorizedFilter -> viewModel.setCategoryFilter(takagi.ru.monica.viewmodel.TotpCategoryFilter.BitwardenVaultUncategorized(selection.vaultId))
            is UnifiedCategoryFilterSelection.MdbxDatabaseFilter -> viewModel.setCategoryFilter(takagi.ru.monica.viewmodel.TotpCategoryFilter.MdbxDatabase(selection.databaseId))
            is UnifiedCategoryFilterSelection.MdbxFolderFilter -> viewModel.setCategoryFilter(takagi.ru.monica.viewmodel.TotpCategoryFilter.MdbxDatabase(selection.databaseId))
        }
    }

    // 如果搜索框展开，按返回键关闭搜索框
    BackHandler(enabled = isSearchExpanded) {
        isSearchExpanded = false
        viewModel.updateSearchQuery("")
        focusManager.clearFocus()
    }

    // Pull-to-search state
    val density = androidx.compose.ui.platform.LocalDensity.current
    val isBitwardenDatabaseView = when (currentFilter) {
        is takagi.ru.monica.viewmodel.TotpCategoryFilter.BitwardenVault,
        is takagi.ru.monica.viewmodel.TotpCategoryFilter.BitwardenFolderFilter,
        is takagi.ru.monica.viewmodel.TotpCategoryFilter.BitwardenVaultStarred,
        is takagi.ru.monica.viewmodel.TotpCategoryFilter.BitwardenVaultUncategorized -> true
        else -> false
    }
    val selectedBitwardenVaultId = when (val filter = currentFilter) {
        is takagi.ru.monica.viewmodel.TotpCategoryFilter.BitwardenVault -> filter.vaultId
        is takagi.ru.monica.viewmodel.TotpCategoryFilter.BitwardenFolderFilter -> filter.vaultId
        is takagi.ru.monica.viewmodel.TotpCategoryFilter.BitwardenVaultStarred -> filter.vaultId
        is takagi.ru.monica.viewmodel.TotpCategoryFilter.BitwardenVaultUncategorized -> filter.vaultId
        else -> null
    }
    val selectedKeePassDatabaseId = when (val filter = currentFilter) {
        is takagi.ru.monica.viewmodel.TotpCategoryFilter.KeePassDatabase -> filter.databaseId
        is takagi.ru.monica.viewmodel.TotpCategoryFilter.KeePassGroupFilter -> filter.databaseId
        is takagi.ru.monica.viewmodel.TotpCategoryFilter.KeePassDatabaseStarred -> filter.databaseId
        is takagi.ru.monica.viewmodel.TotpCategoryFilter.KeePassDatabaseUncategorized -> filter.databaseId
        else -> null
    }
    val bitwardenViewModel: takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel = viewModel()
    val bitwardenSyncStatusByVault by bitwardenViewModel.syncStatusByVault.collectAsState()
    BitwardenAutoSyncEffect(
        viewModel = bitwardenViewModel,
        selectedVaultId = selectedBitwardenVaultId,
        isAllView = currentFilter is takagi.ru.monica.viewmodel.TotpCategoryFilter.All
    )
    val isTopBarSyncing = selectedBitwardenVaultId?.let { vaultId ->
        bitwardenSyncStatusByVault[vaultId].isUserVisibleSyncInProgress()
    } == true
    var isBitwardenTotpRepairing by remember { mutableStateOf(false) }
    // Verifier page uses plain pull-to-search only; disable pull-to-sync UX here.
    val enableBitwardenPullSync = false
    val searchTriggerDistance = remember(density) {
        with(density) { 72.dp.toPx() }
    }
    val syncTriggerDistance = remember(density) { with(density) { 72.dp.toPx() } }
    val maxDragDistance = remember(density) { with(density) { 100.dp.toPx() } }
    val lazyListState = rememberSaveableLazyListState()
    val pullAction = rememberPullActionState(
        isBitwardenDatabaseView = enableBitwardenPullSync,
        isSearchExpanded = isSearchExpanded,
        searchTriggerDistance = searchTriggerDistance,
        syncTriggerDistance = syncTriggerDistance,
        maxDragDistance = maxDragDistance,
        bitwardenRepository = bitwardenRepository,
        onSearchTriggered = { isSearchExpanded = true }
    )
    
    // 选择模式状态
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedItems by remember { mutableStateOf(setOf<Long>()) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    var showMoveToCategoryDialog by remember { mutableStateOf(false) }
    var passwordInput by remember { mutableStateOf("") }
    var passwordError by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val activity = context as? FragmentActivity
    val biometricHelper = remember { BiometricHelper(context) }
    val canUseBiometric = activity != null && appSettings.biometricEnabled && biometricHelper.isBiometricAvailable()
    val sharedTickSeconds = rememberTotpTickerMillis(smooth = false) / 1000L
    val totpCardSettings = remember(appSettings) {
        appSettings.copy(
            iconCardsEnabled = appSettings.iconCardsEnabled && appSettings.authenticatorPageIconEnabled
        )
    }
    
    // 添加单项删除对话框状态
    var itemToDelete by remember { mutableStateOf<takagi.ru.monica.data.SecureItem?>(null) }
    var singleItemPasswordInput by remember { mutableStateOf("") }
    var showSingleItemPasswordVerify by remember { mutableStateOf(false) }
    var pendingBoundSingleDelete by remember { mutableStateOf<SecureItem?>(null) }
    var pendingBoundBatchDelete by remember { mutableStateOf<List<SecureItem>>(emptyList()) }
    
    // 待删除项ID集合（用于隐藏即将删除的项）
    var deletedItemIds by remember { mutableStateOf(setOf<Long>()) }
    
    // QR码显示状态
    var itemToShowQr by remember { mutableStateOf<takagi.ru.monica.data.SecureItem?>(null) }
    
    // 过滤掉待删除的项
    val filteredTotpItems = remember(totpItems, deletedItemIds) {
        totpItems.filter { it.id !in deletedItemIds }
    }

    val vibrationGate = remember { TotpCountdownVibrationGate() }
    val lifecycleOwner = LocalLifecycleOwner.current
    var isScreenResumed by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner, haptic) {
        val lifecycle = lifecycleOwner.lifecycle
        val observer = LifecycleEventObserver { _, _ ->
            isScreenResumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (!isScreenResumed) haptic.cancel()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            haptic.cancel()
        }
    }
    val expiringRemainingSeconds = remember(
        sharedTickSeconds,
        filteredTotpItems,
        totpDataById,
        appSettings.totpTimeOffset
    ) {
        filteredTotpItems.mapNotNull { item ->
            val data = totpDataById[item.id] ?: return@mapNotNull null
            if (data.otpType == OtpType.HOTP) return@mapNotNull null
            TotpGenerator.getRemainingSeconds(
                period = data.period,
                timeOffset = appSettings.totpTimeOffset,
                currentSeconds = sharedTickSeconds
            )
        }
    }
    LaunchedEffect(
        sharedTickSeconds,
        expiringRemainingSeconds,
        isScreenResumed,
        appSettings.isPlusActivated,
        appSettings.validatorVibrationEnabled
    ) {
        if (
            isScreenResumed &&
            appSettings.isPlusActivated &&
            appSettings.validatorVibrationEnabled &&
            vibrationGate.shouldVibrate(sharedTickSeconds, expiringRemainingSeconds)
        ) {
            haptic.performCountdownTick()
        }
    }

    fun boundPasswordIdFor(item: SecureItem): Long? {
        return (totpDataById[item.id] ?: viewModel.parseTotpDataForDisplay(item))?.boundPasswordId
    }

    fun requestDeleteItem(item: SecureItem) {
        if (boundPasswordIdFor(item) != null) {
            pendingBoundSingleDelete = item
            return
        }

        itemToDelete = item
        deletedItemIds = deletedItemIds + item.id
    }

    fun requestBatchDelete() {
        val toDelete = totpItems.filter { selectedItems.contains(it.id) }
        if (toDelete.isEmpty()) return

        val boundItems = toDelete.filter { boundPasswordIdFor(it) != null }
        if (boundItems.isNotEmpty()) {
            pendingBoundBatchDelete = boundItems
        } else {
            showBatchDeleteDialog = true
        }
    }

    fun buildBoundDeleteImpacts(items: List<SecureItem>): List<BoundTotpDeleteImpact> {
        return items.mapNotNull { item ->
            val passwordId = boundPasswordIdFor(item) ?: return@mapNotNull null
            val passwordTitle = passwordMap[passwordId]?.title
                ?: context.getString(R.string.bound_totp_delete_missing_password, passwordId)
            BoundTotpDeleteImpact(
                authenticatorTitle = item.title,
                passwordTitle = passwordTitle
            )
        }
    }
    
    // 定义回调函数
    val exitSelection = {
        isSelectionMode = false
        selectedItems = setOf()
    }
    
    val selectAll = {
        selectedItems = if (selectedItems.size == filteredTotpItems.size) {
            setOf()
        } else {
            filteredTotpItems.map { it.id }.toSet()
        }
    }
    
    val deleteSelected = {
        requestBatchDelete()
    }

    val moveToCategory = {
        showMoveToCategoryDialog = true
    }
    
    // 通知父组件选择模式状态变化
    LaunchedEffect(isSelectionMode, selectedItems.size) {
        onSelectionModeChange(
            isSelectionMode,
            selectedItems.size,
            exitSelection,
            selectAll,
            moveToCategory,
            deleteSelected
        )
    }

    UnifiedMoveToCategoryBottomSheet(
        visible = showMoveToCategoryDialog,
        onDismiss = { showMoveToCategoryDialog = false },
        initialSource = totpSelectedFilter.toUnifiedMoveInitialSource(),
        categories = categories,
        keepassDatabases = keepassDatabases,
        mdbxDatabases = mdbxDatabases,
        bitwardenVaults = bitwardenVaults,
        getBitwardenFolders = { vaultId -> database.bitwardenFolderDao().getFoldersByVaultFlow(vaultId) },
        getKeePassGroups = getKeePassGroups,
        getMdbxFolders = passwordViewModel::getMdbxFolders,
        allowCopy = true,
        allowMove = totpItems.filter { it.id in selectedItems.filter { selected -> selected > 0L } }.none { it.isKeePassOwned() },
        onTargetSelected = { target, action ->
            val movableIds = selectedItems.filter { it > 0L }
            val targetCategoryId = when (target) {
                UnifiedMoveCategoryTarget.Uncategorized -> null
                is UnifiedMoveCategoryTarget.MonicaCategory -> target.categoryId
                else -> null
            }
            val isMonicaLocalTarget = target == UnifiedMoveCategoryTarget.Uncategorized ||
                target is UnifiedMoveCategoryTarget.MonicaCategory
            val selectedTotpItems = totpItems.filter { it.id in movableIds }
            val effectiveAction = if (action == UnifiedMoveAction.MOVE && selectedTotpItems.any { it.isKeePassOwned() }) {
                Toast.makeText(
                    context,
                    context.getString(R.string.keepass_copy_only_hint),
                    Toast.LENGTH_SHORT
                ).show()
                UnifiedMoveAction.COPY
            } else {
                action
            }
            if (effectiveAction == UnifiedMoveAction.COPY) {
                coroutineScope.launch {
                    var copiedCount = 0
                    selectedTotpItems.forEach { item ->
                        if (isMonicaLocalTarget) {
                            if (viewModel.copyTotpToMonicaLocal(item, targetCategoryId) != null) {
                                copiedCount++
                            }
                            return@forEach
                        }
                        val totpData = TotpDataResolver.parseStoredItemData(
                            itemData = item.itemData,
                            fallbackIssuer = item.title,
                            decryptIfNeeded = securityManager::decryptDataIfMonicaCiphertext
                        ) ?: return@forEach
                        val detachedTotpData = totpData.copy(
                            boundPasswordId = null,
                            categoryId = null,
                            keepassDatabaseId = null
                        )
                        val targetKeepassDatabaseId = when (target) {
                            is UnifiedMoveCategoryTarget.KeePassDatabaseTarget -> target.databaseId
                            is UnifiedMoveCategoryTarget.KeePassGroupTarget -> target.databaseId
                            else -> null
                        }
                        val targetBitwardenVaultId = when (target) {
                            is UnifiedMoveCategoryTarget.BitwardenVaultTarget -> target.vaultId
                            is UnifiedMoveCategoryTarget.BitwardenFolderTarget -> target.vaultId
                            else -> null
                        }
                        val targetBitwardenFolderId = when (target) {
                            is UnifiedMoveCategoryTarget.BitwardenFolderTarget -> target.folderId
                            is UnifiedMoveCategoryTarget.BitwardenVaultTarget -> ""
                            else -> null
                        }
                        val targetMdbxDatabaseId = when (target) {
                            is UnifiedMoveCategoryTarget.MdbxDatabaseTarget -> target.databaseId
                            is UnifiedMoveCategoryTarget.MdbxFolderTarget -> target.databaseId
                            else -> null
                        }
                        val targetMdbxFolderId = when (target) {
                            is UnifiedMoveCategoryTarget.MdbxFolderTarget -> target.folderId
                            else -> null
                        }
                        viewModel.saveTotpItem(
                            id = null,
                            title = item.title,
                            notes = item.notes,
                            totpData = detachedTotpData,
                            isFavorite = item.isFavorite,
                            categoryId = targetCategoryId,
                            keepassDatabaseId = targetKeepassDatabaseId,
                            bitwardenVaultId = targetBitwardenVaultId,
                            bitwardenFolderId = targetBitwardenFolderId,
                            mdbxDatabaseId = targetMdbxDatabaseId,
                            mdbxFolderId = targetMdbxFolderId
                        )
                        copiedCount++
                    }
                    Toast.makeText(
                        context,
                        context.getString(R.string.selected_items, copiedCount),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } else {
                if (isMonicaLocalTarget) {
                    coroutineScope.launch {
                        var movedCount = 0
                        selectedTotpItems.forEach { item ->
                            if (item.isLocalOnlyItem()) {
                                viewModel.moveToCategory(listOf(item.id), targetCategoryId)
                                movedCount++
                            } else if (viewModel.moveTotpToMonicaLocal(item, targetCategoryId).isSuccess) {
                                movedCount++
                            }
                        }
                        Toast.makeText(
                            context,
                            context.getString(R.string.selected_items, movedCount),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    when (target) {
                        UnifiedMoveCategoryTarget.Uncategorized -> {
                            viewModel.moveToCategory(movableIds, null)
                            Toast.makeText(context, context.getString(R.string.category_none), Toast.LENGTH_SHORT).show()
                        }
                        is UnifiedMoveCategoryTarget.MonicaCategory -> {
                            viewModel.moveToCategory(movableIds, target.categoryId)
                            val name = categories.find { it.id == target.categoryId }?.name ?: ""
                            Toast.makeText(context, "${context.getString(R.string.move_to_category)} $name", Toast.LENGTH_SHORT).show()
                        }
                        is UnifiedMoveCategoryTarget.BitwardenVaultTarget -> {
                            viewModel.moveToBitwardenFolder(movableIds, target.vaultId, "")
                            Toast.makeText(context, context.getString(R.string.filter_bitwarden), Toast.LENGTH_SHORT).show()
                        }
                        is UnifiedMoveCategoryTarget.BitwardenFolderTarget -> {
                            viewModel.moveToBitwardenFolder(movableIds, target.vaultId, target.folderId)
                            Toast.makeText(context, context.getString(R.string.filter_bitwarden), Toast.LENGTH_SHORT).show()
                        }
                        is UnifiedMoveCategoryTarget.KeePassDatabaseTarget -> {
                            viewModel.moveToKeePassDatabase(movableIds, target.databaseId)
                            val name = keepassDatabases.find { it.id == target.databaseId }?.name ?: "KeePass"
                            Toast.makeText(context, "${context.getString(R.string.move_to_category)} $name", Toast.LENGTH_SHORT).show()
                        }
                        is UnifiedMoveCategoryTarget.KeePassGroupTarget -> {
                            viewModel.moveToKeePassGroup(movableIds, target.databaseId, target.groupPath)
                            val groupName = decodeKeePassPathForDisplay(target.groupPath)
                            Toast.makeText(context, "${context.getString(R.string.move_to_category)} $groupName", Toast.LENGTH_SHORT).show()
                        }
                        is UnifiedMoveCategoryTarget.MdbxDatabaseTarget -> {
                            viewModel.moveToMdbxDatabase(movableIds, target.databaseId)
                            val name = mdbxDatabases.find { it.id == target.databaseId }?.name ?: "MDBX"
                            Toast.makeText(context, "${context.getString(R.string.move_to_category)} $name", Toast.LENGTH_SHORT).show()
                        }
                        is UnifiedMoveCategoryTarget.MdbxFolderTarget -> {
                            viewModel.moveToMdbxDatabase(movableIds, target.databaseId, target.folderId)
                            val name = mdbxDatabases.find { it.id == target.databaseId }?.name ?: "MDBX"
                            Toast.makeText(context, "${context.getString(R.string.move_to_category)} $name", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            showMoveToCategoryDialog = false
            isSelectionMode = false
            selectedItems = emptySet()
        }
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
        // M3E Top Bar with integrated search - 根据当前分类过滤器动态显示标题
        val title = when (val filter = currentFilter) {
            is takagi.ru.monica.viewmodel.TotpCategoryFilter.All -> stringResource(R.string.nav_authenticator)
            is takagi.ru.monica.viewmodel.TotpCategoryFilter.Local -> stringResource(R.string.filter_monica)
            is takagi.ru.monica.viewmodel.TotpCategoryFilter.Starred -> stringResource(R.string.filter_starred)
            is takagi.ru.monica.viewmodel.TotpCategoryFilter.Uncategorized -> stringResource(R.string.filter_uncategorized)
            is takagi.ru.monica.viewmodel.TotpCategoryFilter.LocalStarred -> "${stringResource(R.string.filter_monica)} · ${stringResource(R.string.filter_starred)}"
            is takagi.ru.monica.viewmodel.TotpCategoryFilter.LocalUncategorized -> "${stringResource(R.string.filter_monica)} · ${stringResource(R.string.filter_uncategorized)}"
            is takagi.ru.monica.viewmodel.TotpCategoryFilter.Custom -> categories.find { it.id == filter.categoryId }?.name ?: stringResource(R.string.unknown_category)
            is takagi.ru.monica.viewmodel.TotpCategoryFilter.KeePassDatabase -> keepassDatabases.find { it.id == filter.databaseId }?.name ?: "KeePass"
            is takagi.ru.monica.viewmodel.TotpCategoryFilter.KeePassGroupFilter -> decodeKeePassPathForDisplay(filter.groupPath)
            is takagi.ru.monica.viewmodel.TotpCategoryFilter.KeePassDatabaseStarred -> "${keepassDatabases.find { it.id == filter.databaseId }?.name ?: "KeePass"} · ${stringResource(R.string.filter_starred)}"
            is takagi.ru.monica.viewmodel.TotpCategoryFilter.KeePassDatabaseUncategorized -> "${keepassDatabases.find { it.id == filter.databaseId }?.name ?: "KeePass"} · ${stringResource(R.string.filter_uncategorized)}"
            is takagi.ru.monica.viewmodel.TotpCategoryFilter.BitwardenVault -> stringResource(R.string.filter_bitwarden)
            is takagi.ru.monica.viewmodel.TotpCategoryFilter.BitwardenFolderFilter -> stringResource(R.string.filter_bitwarden)
            is takagi.ru.monica.viewmodel.TotpCategoryFilter.BitwardenVaultStarred -> "${stringResource(R.string.filter_bitwarden)} · ${stringResource(R.string.filter_starred)}"
            is takagi.ru.monica.viewmodel.TotpCategoryFilter.BitwardenVaultUncategorized -> "${stringResource(R.string.filter_bitwarden)} · ${stringResource(R.string.filter_uncategorized)}"
            is takagi.ru.monica.viewmodel.TotpCategoryFilter.MdbxDatabase -> mdbxDatabases.find { it.id == filter.databaseId }?.name ?: "MDBX"
        }

        ExpressiveTopBar(
            title = title,
            searchQuery = searchQuery,
            onSearchQueryChange = viewModel::updateSearchQuery,
            isSearchExpanded = isSearchExpanded,
            onSearchExpandedChange = { isSearchExpanded = it },
            searchHint = stringResource(R.string.search_authenticator),
            onActionPillBoundsChanged = { bounds -> categoryPillBoundsInWindow = bounds },
            actions = {
                // 分类选择按钮
                if (appSettings.categorySelectionUiMode == takagi.ru.monica.data.CategorySelectionUiMode.CHIP_MENU) {
                    IconButton(onClick = { isCategorySheetVisible = true }) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = stringResource(R.string.category),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    IconButton(onClick = { isCategorySheetVisible = true }) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = stringResource(R.string.category),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // 搜索按钮
                IconButton(onClick = { isSearchExpanded = true }) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = stringResource(R.string.search),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box {
                    IconButton(onClick = { showTopActionsMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.more_options),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (appSettings.categorySelectionUiMode == takagi.ru.monica.data.CategorySelectionUiMode.CHIP_MENU) {
                        UnifiedCategoryFilterChipMenuDropdown(
                            expanded = isCategorySheetVisible,
                            onDismissRequest = { isCategorySheetVisible = false },
                            offset = UnifiedCategoryFilterChipMenuOffset
                        ) {
                            UnifiedCategoryFilterChipMenu(
                                visible = true,
                                onDismiss = { isCategorySheetVisible = false },
                                selected = totpSelectedFilter,
                                onSelect = handleCategorySelection,
                                categories = categories,
                                keepassDatabases = keepassDatabases,
                                mdbxDatabases = mdbxDatabases,
                                bitwardenVaults = bitwardenVaults,
                                getBitwardenFolders = { vaultId -> database.bitwardenFolderDao().getFoldersByVaultFlow(vaultId) },
                                getKeePassGroups = getKeePassGroups,
                                categoryEditMode = categoryMgmt.categoryEditMode,
                                onRequestCategoryAction = { categoryMgmt.categoryActionTarget = it },
                                trailingContent = {
                                    CategoryManagementTrailingContent(
                                        state = categoryMgmt,
                                        categories = categories,
                                        keepassDatabases = keepassDatabases,
                                        bitwardenVaults = bitwardenVaults,
                                        getBitwardenFolders = { vaultId -> database.bitwardenFolderDao().getFoldersByVaultFlow(vaultId) },
                                        getKeePassGroups = getKeePassGroups,
                                        passwordViewModel = passwordViewModel,
                                        onDismissFilterSheet = { isCategorySheetVisible = false }
                                    )
                                }
                            )
                        }
                    }
                    PasswordTopActionsDropdownMenu(
                        expanded = showTopActionsMenu,
                        onDismissRequest = { showTopActionsMenu = false }
                    ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.quick_action_scan_qr)) },
                                leadingIcon = { Icon(Icons.Default.QrCodeScanner, contentDescription = null) },
                                onClick = {
                                    showTopActionsMenu = false
                                    onQuickScanTotp()
                                }
                            )
                            val isTileLayout =
                                appSettings.authenticatorLayoutMode == AuthenticatorLayoutMode.TILE
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (isTileLayout) {
                                                R.string.authenticator_layout_standard
                                            } else {
                                                R.string.authenticator_layout_tile
                                            }
                                        )
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (isTileLayout) {
                                            Icons.Default.ViewList
                                        } else {
                                            Icons.Default.GridView
                                        },
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    showTopActionsMenu = false
                                    onAuthenticatorLayoutModeChange(
                                        if (isTileLayout) {
                                            AuthenticatorLayoutMode.STANDARD
                                        } else {
                                            AuthenticatorLayoutMode.TILE
                                        }
                                    )
                                }
                            )
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
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(18.dp),
                                                strokeWidth = 2.dp
                                            )
                                        } else {
                                            Icon(Icons.Default.Sync, contentDescription = null)
                                        }
                                    },
                                    enabled = !isTopBarSyncing && !isBitwardenTotpRepairing,
                                    onClick = {
                                        if (isTopBarSyncing || isBitwardenTotpRepairing) return@DropdownMenuItem
                                        val vaultId = selectedBitwardenVaultId ?: return@DropdownMenuItem
                                        showTopActionsMenu = false
                                        bitwardenViewModel.requestManualSync(vaultId)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.repair_bitwarden_totp_menu)) },
                                    leadingIcon = { Icon(Icons.Default.Build, contentDescription = null) },
                                    enabled = !isTopBarSyncing && !isBitwardenTotpRepairing,
                                    onClick = {
                                        if (isTopBarSyncing || isBitwardenTotpRepairing) return@DropdownMenuItem
                                        val vaultId = selectedBitwardenVaultId ?: return@DropdownMenuItem
                                        showTopActionsMenu = false
                                        scope.launch {
                                            isBitwardenTotpRepairing = true
                                            try {
                                                val result = viewModel.repairHistoricalBitwardenTotp(vaultId)
                                                if (result.queuedForSyncCount > 0) {
                                                    bitwardenViewModel.requestManualSync(vaultId)
                                                    Toast.makeText(
                                                        context,
                                                        context.getString(
                                                            R.string.repair_bitwarden_totp_result_sync,
                                                            result.normalizedCount,
                                                            result.queuedForSyncCount,
                                                            result.skippedItems
                                                        ),
                                                        Toast.LENGTH_LONG
                                                    ).show()
                                                } else if (result.normalizedCount > 0) {
                                                    Toast.makeText(
                                                        context,
                                                        context.getString(
                                                            R.string.repair_bitwarden_totp_result_local,
                                                            result.normalizedCount,
                                                            result.skippedItems
                                                        ),
                                                        Toast.LENGTH_LONG
                                                    ).show()
                                                } else {
                                                    Toast.makeText(
                                                        context,
                                                        context.getString(R.string.repair_bitwarden_totp_nothing_to_fix),
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            } catch (e: Exception) {
                                                Toast.makeText(
                                                    context,
                                                    context.getString(
                                                        R.string.repair_bitwarden_totp_failed,
                                                        e.message ?: context.getString(R.string.repair_bitwarden_totp_failed_unknown)
                                                    ),
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            } finally {
                                                isBitwardenTotpRepairing = false
                                            }
                                        }
                                    }
                                )
                            }
                            if (selectedKeePassDatabaseId != null) {
                                DropdownMenuItem(
                                    text = {
                                        Text("${stringResource(R.string.refresh)} ${stringResource(R.string.filter_keepass)}")
                                    },
                                    leadingIcon = { Icon(Icons.Default.Sync, contentDescription = null) },
                                    onClick = {
                                        showTopActionsMenu = false
                                        viewModel.syncKeePassByDatabaseId(selectedKeePassDatabaseId)
                                    }
                                )
                            }
                        }
                    }
            }
        )
        
        // 统一进度条 - 在顶栏下方显示
        if (appSettings.validatorUnifiedProgressBar == takagi.ru.monica.data.UnifiedProgressBarMode.ENABLED && 
            filteredTotpItems.isNotEmpty()) {
            takagi.ru.monica.ui.components.UnifiedProgressBar(
                style = appSettings.validatorProgressBarStyle,
                currentSeconds = sharedTickSeconds,
                period = 30,
                smoothProgress = appSettings.validatorSmoothProgress,
                timeOffset = (appSettings.totpTimeOffset * 1000).toLong() // 传递时间偏移(毫秒)
            )
        }

        val contentPullOffset = if (enableBitwardenPullSync) 0 else pullAction.currentOffset.toInt()

        // TOTP列表
        if (filteredTotpItems.isEmpty()) {
            // 空状态
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset { androidx.compose.ui.unit.IntOffset(0, contentPullOffset) }
                    .nestedScroll(pullAction.nestedScrollConnection)
                    .pointerInput(isSearchExpanded) {
                        detectVerticalDragGestures(
                            onVerticalDrag = { _, dragAmount ->
                                if (!isSearchExpanded) pullAction.onVerticalDrag(dragAmount)
                            },
                            onDragEnd = {
                                pullAction.onDragEnd()
                            },
                            onDragCancel = {
                                pullAction.onDragCancel()
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Icon(
                        Icons.Default.Security,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.no_authenticators_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.no_authenticators_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            // 可拖动排序的列表状态
            // 用于拖动排序的本地列表状态
            var localTotpItems by remember(filteredTotpItems) { 
                mutableStateOf(filteredTotpItems) 
            }
            
            // 当筛选后的列表变化时同步
            LaunchedEffect(filteredTotpItems) {
                localTotpItems = filteredTotpItems
            }

            if (appSettings.authenticatorLayoutMode == AuthenticatorLayoutMode.TILE) {
                val lazyGridState = rememberLazyGridState()
                val reorderableLazyGridState = rememberReorderableLazyGridState(lazyGridState) { from, to ->
                    if (isSelectionMode) {
                        localTotpItems = localTotpItems.toMutableList().apply {
                            add(to.index, removeAt(from.index))
                        }
                    }
                }
                var gridWasDragging by remember { mutableStateOf(false) }
                LaunchedEffect(reorderableLazyGridState.isAnyItemDragging) {
                    if (reorderableLazyGridState.isAnyItemDragging) {
                        gridWasDragging = true
                    } else if (gridWasDragging && isSelectionMode) {
                        gridWasDragging = false
                        val newOrders = localTotpItems.mapIndexed { index, item -> item.id to index }
                        if (newOrders.isNotEmpty()) viewModel.updateSortOrders(newOrders)
                    }
                }

                MonicaTileGrid(
                    state = lazyGridState,
                    modifier = Modifier
                        .fillMaxSize()
                        .offset { androidx.compose.ui.unit.IntOffset(0, contentPullOffset) }
                        .nestedScroll(pullAction.nestedScrollConnection)
                ) {
                    items(
                        items = localTotpItems,
                        key = { it.id }
                    ) { item ->
                        ReorderableItem(
                            reorderableLazyGridState,
                            key = item.id,
                            enabled = isSelectionMode
                        ) { isDragging ->
                            val elevation by animateDpAsState(
                                if (isDragging) 8.dp else 0.dp,
                                label = "tile_drag_elevation"
                            )
                            val dragModifier = if (isSelectionMode) {
                                Modifier.longPressDraggableHandle(
                                    onDragStarted = { haptic.performLongPress() },
                                    onDragStopped = { haptic.performSuccess() }
                                )
                            } else {
                                Modifier
                            }

                            Box(
                                modifier = Modifier
                                    .graphicsLayer { shadowElevation = elevation.toPx() }
                                    .then(dragModifier)
                            ) {
                                TotpItemCard(
                                    item = item,
                                    onEdit = { onTotpClick(item.id) },
                                    onToggleSelect = {
                                        selectedItems = if (selectedItems.contains(item.id)) {
                                            selectedItems - item.id
                                        } else {
                                            selectedItems + item.id
                                        }
                                    },
                                    onDelete = {
                                        haptic.performWarning()
                                        requestDeleteItem(item)
                                    },
                                    onToggleFavorite = { id, isFavorite ->
                                        viewModel.toggleFavorite(id, isFavorite)
                                    },
                                    onGenerateNext = { id -> viewModel.incrementHotpCounter(id) },
                                    onShowQrCode = { itemToShowQr = item },
                                    onLongClick = {
                                        haptic.performLongPress()
                                        if (!isSelectionMode) {
                                            isSelectionMode = true
                                            selectedItems = setOf(item.id)
                                        }
                                    },
                                    isSelectionMode = isSelectionMode,
                                    isSelected = selectedItems.contains(item.id),
                                    sharedTickSeconds = sharedTickSeconds,
                                    appSettings = totpCardSettings,
                                    parsedTotpData = totpDataById[item.id],
                                    compactTile = true
                                )
                            }
                        }
                    }
                }
            } else {
            // Standard authenticator layout
            val reorderableLazyListState = rememberReorderableLazyListState(lazyListState) { from, to ->
                // 只在多选模式下允许排序
                if (isSelectionMode) {
                    localTotpItems = localTotpItems.toMutableList().apply {
                        add(to.index, removeAt(from.index))
                    }
                }
            }

            // 当拖动结束时保存新顺序
            LaunchedEffect(reorderableLazyListState.isAnyItemDragging) {
                if (!reorderableLazyListState.isAnyItemDragging && isSelectionMode) {
                    // 拖动结束，保存新顺序到数据库
                    val newOrders = localTotpItems.mapIndexed { index, item ->
                        item.id to index
                    }
                    if (newOrders.isNotEmpty()) {
                        viewModel.updateSortOrders(newOrders)
                    }
                }
            }

            LazyColumn(
                state = lazyListState,
                modifier = Modifier
                    .fillMaxSize()
                    .offset { androidx.compose.ui.unit.IntOffset(0, contentPullOffset) }
                    .nestedScroll(pullAction.nestedScrollConnection),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(
                    items = localTotpItems,
                    key = { it.id }
                ) { item ->
                    ReorderableItem(
                        reorderableLazyListState,
                        key = item.id,
                        enabled = isSelectionMode
                    ) { isDragging ->
                        val elevation by animateDpAsState(
                            if (isDragging) 8.dp else 0.dp,
                            label = "drag_elevation"
                        )

                        // 在多选模式下使用拖动手柄
                        val dragModifier = if (isSelectionMode) {
                            Modifier.longPressDraggableHandle(
                                onDragStarted = {
                                    haptic.performLongPress()
                                },
                                onDragStopped = {
                                    haptic.performSuccess()
                                }
                            )
                        } else {
                            Modifier
                        }

                        // Keep right-swipe selection available in selection mode; only disable delete swipe there.
                        takagi.ru.monica.ui.gestures.SwipeActions(
                            onSwipeLeft = {
                                // 左滑删除
                                haptic.performWarning()
                                requestDeleteItem(item)
                            },
                            onSwipeRight = {
                                // 右滑选择
                                haptic.performSuccess()
                                if (!isSelectionMode) {
                                    isSelectionMode = true
                                }
                                selectedItems = if (selectedItems.contains(item.id)) {
                                    selectedItems - item.id
                                } else {
                                    selectedItems + item.id
                                }
                            },
                            isSwiped = itemToDelete?.id == item.id,
                            enabled = !isDragging,
                            allowSwipeLeft = !isSelectionMode,
                            allowSwipeRight = true
                        ) {
                            // 包装卡片以支持拖动
                            Box(
                                modifier = Modifier
                                    .graphicsLayer {
                                        shadowElevation = elevation.toPx()
                                    }
                                    .then(dragModifier)
                            ) {
                                TotpItemCard(
                                    item = item,
                                    onEdit = { onTotpClick(item.id) },
                                    onToggleSelect = {
                                        selectedItems = if (selectedItems.contains(item.id)) {
                                            selectedItems - item.id
                                        } else {
                                            selectedItems + item.id
                                        }
                                    },
                                    onDelete = {
                                        haptic.performWarning()
                                        requestDeleteItem(item)
                                    },
                                    onToggleFavorite = { id, isFavorite ->
                                        viewModel.toggleFavorite(id, isFavorite)
                                    },
                                    onGenerateNext = { id ->
                                        viewModel.incrementHotpCounter(id)
                                    },
                                    onMoveUp = null, // 使用拖动排序替代
                                    onMoveDown = null, // 使用拖动排序替代
                                    onShowQrCode = {
                                        itemToShowQr = item
                                    },
                                    onLongClick = {
                                        // 长按进入多选模式
                                        haptic.performLongPress()
                                        if (!isSelectionMode) {
                                            isSelectionMode = true
                                            selectedItems = setOf(item.id)
                                        }
                                    },
                                    isSelectionMode = isSelectionMode,
                                    isSelected = selectedItems.contains(item.id),
                                    sharedTickSeconds = sharedTickSeconds,
                                    appSettings = totpCardSettings,
                                    parsedTotpData = totpDataById[item.id]
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                item {
                    Spacer(modifier = Modifier.height(80.dp))
                }
            }
            }
        }
    }
    
    // QR码对话框
    itemToShowQr?.let { item ->
        QrCodeDialog(
            item = item,
            onDismiss = { itemToShowQr = null }
        )
    }

    pendingBoundSingleDelete?.let { item ->
        BoundTotpDeleteWarningDialog(
            impacts = buildBoundDeleteImpacts(listOf(item)),
            isBatch = false,
            onDismiss = {
                pendingBoundSingleDelete = null
            },
            onConfirm = {
                pendingBoundSingleDelete = null
                itemToDelete = item
                deletedItemIds = deletedItemIds + item.id
            }
        )
    }

    if (pendingBoundBatchDelete.isNotEmpty()) {
        val pendingItems = pendingBoundBatchDelete
        BoundTotpDeleteWarningDialog(
            impacts = buildBoundDeleteImpacts(pendingItems),
            isBatch = true,
            onDismiss = {
                pendingBoundBatchDelete = emptyList()
            },
            onConfirm = {
                pendingBoundBatchDelete = emptyList()
                showBatchDeleteDialog = true
            }
        )
    }
    
    // 单项删除确认对话框(支持指纹和密码验证)
    itemToDelete?.let { item ->
        DeleteConfirmDialog(
            itemTitle = item.title,
            itemType = stringResource(R.string.item_type_authenticator),
            biometricEnabled = appSettings.biometricEnabled,
            skipIdentityVerification = appSettings.disablePasswordVerification,
            onDismiss = {
                // 取消删除，恢复卡片显示
                deletedItemIds = deletedItemIds - item.id
                itemToDelete = null
            },
            onConfirmWithoutVerification = {
                onDeleteTotp(item)
                Toast.makeText(context, context.getString(R.string.deleted), Toast.LENGTH_SHORT).show()
                itemToDelete = null
            },
            onConfirmWithPassword = { password ->
                singleItemPasswordInput = password
                showSingleItemPasswordVerify = true
            },
            onConfirmWithBiometric = {
                // 指纹验证成功，直接删除
                onDeleteTotp(item)
                Toast.makeText(
                    context,
                    context.getString(R.string.deleted),
                    Toast.LENGTH_SHORT
                ).show()
                itemToDelete = null
            }
        )
    }
    
    // 单项删除密码验证
    if (showSingleItemPasswordVerify && itemToDelete != null) {
        LaunchedEffect(Unit) {
            val securityManager = takagi.ru.monica.security.SecurityManager(context)
            if (securityManager.verifyMasterPassword(singleItemPasswordInput)) {
                // 密码正确，删除 TOTP
                onDeleteTotp(itemToDelete!!)
                
                Toast.makeText(
                    context,
                    context.getString(R.string.deleted),
                    Toast.LENGTH_SHORT
                ).show()
                
                // 清理状态（保持在 deletedItemIds 中，因为已真实删除）
                itemToDelete = null
                singleItemPasswordInput = ""
                showSingleItemPasswordVerify = false
            } else {
                // 密码错误，恢复卡片显示
                deletedItemIds = deletedItemIds - itemToDelete!!.id
                
                Toast.makeText(
                    context,
                    context.getString(R.string.current_password_incorrect),
                    Toast.LENGTH_SHORT
                ).show()
                
                // 重置状态
                itemToDelete = null
                singleItemPasswordInput = ""
                showSingleItemPasswordVerify = false
            }
        }
    }
    
    // 批量删除验证对话框（统一 M3 身份验证弹窗）
    if (showBatchDeleteDialog) {
        val skipIdentityVerification = appSettings.disablePasswordVerification
        val biometricAction = if (!skipIdentityVerification && canUseBiometric) {
            {
                biometricHelper.authenticate(
                    activity = activity!!,
                    title = context.getString(R.string.verify_identity),
                    subtitle = context.getString(R.string.verify_to_delete),
                    onSuccess = {
                        coroutineScope.launch {
                            val toDelete = totpItems.filter { selectedItems.contains(it.id) }
                            viewModel.deleteTotpItems(toDelete)
                            android.widget.Toast.makeText(
                                context,
                                context.getString(R.string.deleted_items, toDelete.size),
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            isSelectionMode = false
                            selectedItems = setOf()
                            passwordInput = ""
                            passwordError = false
                            showBatchDeleteDialog = false
                        }
                    },
                    onError = { error ->
                        Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                    },
                    onFailed = {}
                )
            }
        } else {
            null
        }
        M3IdentityVerifyDialog(
            title = stringResource(R.string.verify_identity),
            message = stringResource(R.string.batch_delete_totp_message, selectedItems.size),
            passwordValue = passwordInput,
            onPasswordChange = {
                passwordInput = it
                passwordError = false
            },
            onDismiss = {
                showBatchDeleteDialog = false
                passwordInput = ""
                passwordError = false
            },
            onConfirm = {
                if (skipIdentityVerification || SecurityManager(context).verifyMasterPassword(passwordInput)) {
                    coroutineScope.launch {
                        val toDelete = totpItems.filter { selectedItems.contains(it.id) }
                        viewModel.deleteTotpItems(toDelete)
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.deleted_items, toDelete.size),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                        isSelectionMode = false
                        selectedItems = setOf()
                        passwordInput = ""
                        passwordError = false
                        showBatchDeleteDialog = false
                    }
                } else {
                    passwordError = true
                }
            },
            confirmText = stringResource(R.string.delete),
            destructiveConfirm = true,
            requireIdentityVerification = !skipIdentityVerification,
            isPasswordError = passwordError,
            passwordErrorText = stringResource(R.string.current_password_incorrect),
            onBiometricClick = biometricAction,
            biometricHintText = if (biometricAction == null) {
                context.getString(R.string.biometric_not_available)
            } else {
                null
            }
        )
    }

    CategoryManagementCreateDialog(
        state = categoryMgmt,
        currentFilter = totpSelectedFilter,
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

private data class BoundTotpDeleteImpact(
    val authenticatorTitle: String,
    val passwordTitle: String
)

@Composable
private fun BoundTotpDeleteWarningDialog(
    impacts: List<BoundTotpDeleteImpact>,
    isBatch: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.LinkOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = {
            Text(
                text = stringResource(
                    if (isBatch) {
                        R.string.bound_totp_delete_warning_title_multi
                    } else {
                        R.string.bound_totp_delete_warning_title_single
                    }
                )
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(
                        if (isBatch) {
                            R.string.bound_totp_delete_warning_message_multi
                        } else {
                            R.string.bound_totp_delete_warning_message_single
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                impacts.forEach { impact ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.28f)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = impact.authenticatorTitle,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = impact.passwordTitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(stringResource(R.string.bound_totp_delete_warning_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/**
 * TOTP项卡片
 */


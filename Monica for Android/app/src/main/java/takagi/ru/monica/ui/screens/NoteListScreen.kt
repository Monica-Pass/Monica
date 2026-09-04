package takagi.ru.monica.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Note
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.SecureItemOwnership
import takagi.ru.monica.data.isKeePassOwned
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.Category
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.isLocalOnlyItem
import takagi.ru.monica.data.resolveOwnership
import takagi.ru.monica.bitwarden.sync.isUserVisibleSyncInProgress
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.bitwarden.ui.BitwardenAutoSyncEffect
import takagi.ru.monica.data.KeePassStorageLocation
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.repository.KeePassCompatibilityBridge
import takagi.ru.monica.repository.KeePassWorkspaceRepository
import takagi.ru.monica.viewmodel.NoteViewModel
import takagi.ru.monica.viewmodel.NoteDraftStorageTarget
import takagi.ru.monica.viewmodel.SettingsViewModel
import java.text.SimpleDateFormat
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.utils.BiometricHelper
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.utils.decodeKeePassPathForDisplay
import takagi.ru.monica.utils.planLocalCategoryMove
import takagi.ru.monica.ui.category.CategoryManagementTrailingContent
import takagi.ru.monica.ui.category.CategoryManagementCreateDialog
import takagi.ru.monica.ui.category.rememberCategoryManagementState
import androidx.fragment.app.FragmentActivity
import androidx.compose.ui.res.stringResource
import takagi.ru.monica.R
import takagi.ru.monica.ui.components.M3IdentityVerifyDialog
import takagi.ru.monica.ui.components.ExpressiveTopBar
import takagi.ru.monica.ui.components.MonicaExpressiveFilterChip
import takagi.ru.monica.ui.components.SyncStatusIcon
import takagi.ru.monica.ui.components.UnifiedCategoryFilterChipMenu
import takagi.ru.monica.ui.components.UnifiedCategoryFilterChipMenuDropdown
import takagi.ru.monica.ui.components.UnifiedCategoryFilterChipMenuOffset
import takagi.ru.monica.ui.components.UnifiedCategoryFilterSelection
import takagi.ru.monica.ui.components.UnifiedMoveAction
import takagi.ru.monica.ui.components.UnifiedMoveCategoryTarget
import takagi.ru.monica.ui.components.UnifiedMoveToCategoryBottomSheet
import takagi.ru.monica.ui.components.toUnifiedMoveInitialSource
import takagi.ru.monica.ui.components.PullActionVisualState
import takagi.ru.monica.ui.components.PullGestureIndicator
import takagi.ru.monica.bitwarden.sync.SyncStatus
import takagi.ru.monica.notes.domain.NoteContentCodec
import takagi.ru.monica.notes.ui.model.NoteListItemUiModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import takagi.ru.monica.util.VibrationPatterns
import takagi.ru.monica.utils.SavedCategoryFilterState
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale
import takagi.ru.monica.ui.password.PasswordTopActionsDropdownMenu

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NoteListScreen(
    viewModel: NoteViewModel,
    settingsViewModel: SettingsViewModel,
    onNavigateToAddNote: (Long?) -> Unit,
    onNavigateToSearchedNote: (Long, String) -> Unit = { noteId, _ -> onNavigateToAddNote(noteId) },
    securityManager: SecurityManager,
    passwordViewModel: takagi.ru.monica.viewmodel.PasswordViewModel,
    onSelectionModeChange: (Boolean) -> Unit = {},
    showStandaloneSettingsEntry: Boolean = false,
    onOpenStandaloneSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedTag by remember { mutableStateOf<String?>(null) }
    var isSearchExpanded by rememberSaveable { mutableStateOf(false) }
    val settings by settingsViewModel.settings.collectAsState()
    val isGridLayout = settings.noteGridLayout
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedNoteIds by remember { mutableStateOf(setOf<Long>()) }
    var isCategorySheetVisible by remember { mutableStateOf(false) }
    var categoryPillBoundsInWindow by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showBatchMoveCategoryDialog by remember { mutableStateOf(false) }
    var showBitwardenImageWarningDialog by remember { mutableStateOf(false) }
    var pendingBitwardenMoveTarget by remember { mutableStateOf<UnifiedMoveCategoryTarget?>(null) }
    var pendingBitwardenMoveAction by remember { mutableStateOf<UnifiedMoveAction?>(null) }
    var bitwardenImageWarningCount by remember { mutableStateOf(0) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var showTopActionsMenu by remember { mutableStateOf(false) }
    var masterPassword by remember { mutableStateOf("") }
    var passwordError by remember { mutableStateOf(false) }
    
    // 防止重复点击
    var isNavigating by remember { mutableStateOf(false) }

    fun collapseSearch() {
        isSearchExpanded = false
        searchQuery = ""
    }

    BackHandler(enabled = isSearchExpanded) {
        collapseSearch()
    }

    LaunchedEffect(isSelectionMode) {
        onSelectionModeChange(isSelectionMode)
    }

    DisposableEffect(Unit) {
        onDispose {
            onSelectionModeChange(false)
        }
    }
    
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val database = remember { PasswordDatabase.getDatabase(context) }
    val settingsManager = remember { SettingsManager(context) }
    val scope = rememberCoroutineScope()
    val categories by database.categoryDao().getAllCategories().collectAsState(initial = emptyList())
    val keepassDatabases by database.localKeePassDatabaseDao().getAllDatabases().collectAsState(initial = emptyList())
    val mdbxDatabases by database.localMdbxDatabaseDao().getAllDatabases().collectAsState(initial = emptyList())
    val bitwardenRepository = remember { BitwardenRepository.getInstance(context) }
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
    val biometricHelper = remember { BiometricHelper(context) }
    val canUseBiometric = activity != null && settings.biometricEnabled && biometricHelper.isBiometricAvailable()
    val parsedNotesState by viewModel.parsedNotesState.collectAsState()
    val parsedNotes = parsedNotesState.items
    val notes = remember(parsedNotes) { parsedNotes.map { it.item } }
    val parsedNoteById = remember(parsedNotes) { parsedNotes.associateBy { it.item.id } }
    var selectedCategoryFilter by remember { mutableStateOf<NoteCategoryFilter>(NoteCategoryFilter.All) }
    val savedCategoryFilterState by settingsManager
        .categoryFilterStateFlow(SettingsManager.CategoryFilterScope.NOTE)
        .collectAsState(initial = SavedCategoryFilterState())
    var hasRestoredCategoryFilter by remember { mutableStateOf(false) }

    LaunchedEffect(savedCategoryFilterState, hasRestoredCategoryFilter) {
        if (hasRestoredCategoryFilter) return@LaunchedEffect
        selectedCategoryFilter = decodeNoteCategoryFilter(savedCategoryFilterState)
        hasRestoredCategoryFilter = true
    }

    LaunchedEffect(selectedCategoryFilter) {
        viewModel.setDraftStorageTarget(selectedCategoryFilter.toDraftStorageTarget())
        if (hasRestoredCategoryFilter) {
            settingsManager.updateCategoryFilterState(
                scope = SettingsManager.CategoryFilterScope.NOTE,
                state = encodeNoteCategoryFilter(selectedCategoryFilter)
            )
        }
    }

    val resolvedPasswordViewModel = passwordViewModel
    val categoryMgmt = rememberCategoryManagementState()

    val selectedUnifiedFilter = when (val filter = selectedCategoryFilter) {
        NoteCategoryFilter.All -> UnifiedCategoryFilterSelection.All
        NoteCategoryFilter.Local -> UnifiedCategoryFilterSelection.Local
        NoteCategoryFilter.Starred -> UnifiedCategoryFilterSelection.Starred
        NoteCategoryFilter.Uncategorized -> UnifiedCategoryFilterSelection.Uncategorized
        NoteCategoryFilter.LocalStarred -> UnifiedCategoryFilterSelection.LocalStarred
        NoteCategoryFilter.LocalUncategorized -> UnifiedCategoryFilterSelection.LocalUncategorized
        is NoteCategoryFilter.Custom -> UnifiedCategoryFilterSelection.Custom(filter.categoryId)
        is NoteCategoryFilter.BitwardenVault -> UnifiedCategoryFilterSelection.BitwardenVaultFilter(filter.vaultId)
        is NoteCategoryFilter.BitwardenFolderFilter -> UnifiedCategoryFilterSelection.BitwardenFolderFilter(filter.vaultId, filter.folderId)
        is NoteCategoryFilter.BitwardenVaultStarred -> UnifiedCategoryFilterSelection.BitwardenVaultStarredFilter(filter.vaultId)
        is NoteCategoryFilter.BitwardenVaultUncategorized -> UnifiedCategoryFilterSelection.BitwardenVaultUncategorizedFilter(filter.vaultId)
        is NoteCategoryFilter.KeePassDatabase -> UnifiedCategoryFilterSelection.KeePassDatabaseFilter(filter.databaseId)
        is NoteCategoryFilter.KeePassGroupFilter -> UnifiedCategoryFilterSelection.KeePassGroupFilter(
            filter.databaseId,
            filter.groupPath,
            filter.groupUuid
        )
        is NoteCategoryFilter.KeePassDatabaseStarred -> UnifiedCategoryFilterSelection.KeePassDatabaseStarredFilter(filter.databaseId)
        is NoteCategoryFilter.KeePassDatabaseUncategorized -> UnifiedCategoryFilterSelection.KeePassDatabaseUncategorizedFilter(filter.databaseId)
        is NoteCategoryFilter.MdbxDatabase -> UnifiedCategoryFilterSelection.MdbxDatabaseFilter(filter.databaseId)
    }
    val handleCategorySelection: (UnifiedCategoryFilterSelection) -> Unit = { selection ->
        selectedCategoryFilter = when (selection) {
            is UnifiedCategoryFilterSelection.All -> NoteCategoryFilter.All
            is UnifiedCategoryFilterSelection.Local -> NoteCategoryFilter.Local
            is UnifiedCategoryFilterSelection.Starred -> NoteCategoryFilter.Starred
            is UnifiedCategoryFilterSelection.Uncategorized -> NoteCategoryFilter.Uncategorized
            is UnifiedCategoryFilterSelection.LocalStarred -> NoteCategoryFilter.LocalStarred
            is UnifiedCategoryFilterSelection.LocalUncategorized -> NoteCategoryFilter.LocalUncategorized
            is UnifiedCategoryFilterSelection.Custom -> NoteCategoryFilter.Custom(selection.categoryId)
            is UnifiedCategoryFilterSelection.BitwardenVaultFilter -> NoteCategoryFilter.BitwardenVault(selection.vaultId)
            is UnifiedCategoryFilterSelection.BitwardenFolderFilter -> NoteCategoryFilter.BitwardenFolderFilter(selection.folderId, selection.vaultId)
            is UnifiedCategoryFilterSelection.BitwardenVaultStarredFilter -> NoteCategoryFilter.BitwardenVaultStarred(selection.vaultId)
            is UnifiedCategoryFilterSelection.BitwardenVaultUncategorizedFilter -> NoteCategoryFilter.BitwardenVaultUncategorized(selection.vaultId)
            is UnifiedCategoryFilterSelection.KeePassDatabaseFilter -> NoteCategoryFilter.KeePassDatabase(selection.databaseId)
            is UnifiedCategoryFilterSelection.KeePassGroupFilter -> NoteCategoryFilter.KeePassGroupFilter(
                selection.databaseId,
                selection.groupPath,
                selection.groupUuid
            )
        is UnifiedCategoryFilterSelection.KeePassDatabaseStarredFilter -> NoteCategoryFilter.KeePassDatabaseStarred(selection.databaseId)
        is UnifiedCategoryFilterSelection.KeePassDatabaseUncategorizedFilter -> NoteCategoryFilter.KeePassDatabaseUncategorized(selection.databaseId)
        is UnifiedCategoryFilterSelection.MdbxDatabaseFilter -> NoteCategoryFilter.MdbxDatabase(selection.databaseId)
        is UnifiedCategoryFilterSelection.MdbxFolderFilter -> NoteCategoryFilter.MdbxDatabase(selection.databaseId)
    }
        when (selection) {
            is UnifiedCategoryFilterSelection.KeePassDatabaseFilter -> viewModel.syncKeePassNotes(selection.databaseId)
            is UnifiedCategoryFilterSelection.KeePassGroupFilter -> viewModel.syncKeePassNotes(selection.databaseId)
            else -> Unit
        }
    }

    val title = when (val filter = selectedCategoryFilter) {
        NoteCategoryFilter.All -> stringResource(R.string.filter_all)
        NoteCategoryFilter.Local -> stringResource(R.string.filter_monica)
        NoteCategoryFilter.Starred -> stringResource(R.string.filter_starred)
        NoteCategoryFilter.Uncategorized -> stringResource(R.string.filter_uncategorized)
        NoteCategoryFilter.LocalStarred -> "${stringResource(R.string.filter_monica)} · ${stringResource(R.string.filter_starred)}"
        NoteCategoryFilter.LocalUncategorized -> "${stringResource(R.string.filter_monica)} · ${stringResource(R.string.filter_uncategorized)}"
        is NoteCategoryFilter.Custom -> categories.find { it.id == filter.categoryId }?.name
            ?: stringResource(R.string.unknown_category)
        is NoteCategoryFilter.BitwardenVault -> "Bitwarden"
        is NoteCategoryFilter.BitwardenFolderFilter -> "Bitwarden"
        is NoteCategoryFilter.BitwardenVaultStarred -> "${stringResource(R.string.filter_bitwarden)} · ${stringResource(R.string.filter_starred)}"
        is NoteCategoryFilter.BitwardenVaultUncategorized -> "${stringResource(R.string.filter_bitwarden)} · ${stringResource(R.string.filter_uncategorized)}"
        is NoteCategoryFilter.KeePassDatabase -> keepassDatabases.find { it.id == filter.databaseId }?.name ?: "KeePass"
        is NoteCategoryFilter.KeePassGroupFilter -> decodeKeePassPathForDisplay(filter.groupPath)
        is NoteCategoryFilter.KeePassDatabaseStarred -> "${keepassDatabases.find { it.id == filter.databaseId }?.name ?: "KeePass"} · ${stringResource(R.string.filter_starred)}"
        is NoteCategoryFilter.KeePassDatabaseUncategorized -> "${keepassDatabases.find { it.id == filter.databaseId }?.name ?: "KeePass"} · ${stringResource(R.string.filter_uncategorized)}"
        is NoteCategoryFilter.MdbxDatabase -> mdbxDatabases.find { it.id == filter.databaseId }?.name ?: "MDBX"
    }
    // Notes keep pull-to-search; disable pull-to-sync on Bitwarden filters.
    val isBitwardenDatabaseView = false && when (selectedCategoryFilter) {
        is NoteCategoryFilter.BitwardenVault,
        is NoteCategoryFilter.BitwardenFolderFilter,
        is NoteCategoryFilter.BitwardenVaultStarred,
        is NoteCategoryFilter.BitwardenVaultUncategorized -> true
        else -> false
    }
    val selectedBitwardenVaultId = when (val filter = selectedCategoryFilter) {
        is NoteCategoryFilter.BitwardenVault -> filter.vaultId
        is NoteCategoryFilter.BitwardenFolderFilter -> filter.vaultId
        is NoteCategoryFilter.BitwardenVaultStarred -> filter.vaultId
        is NoteCategoryFilter.BitwardenVaultUncategorized -> filter.vaultId
        else -> null
    }
    val selectedKeePassDatabaseId = when (val filter = selectedCategoryFilter) {
        is NoteCategoryFilter.KeePassDatabase -> filter.databaseId
        is NoteCategoryFilter.KeePassGroupFilter -> filter.databaseId
        is NoteCategoryFilter.KeePassDatabaseStarred -> filter.databaseId
        is NoteCategoryFilter.KeePassDatabaseUncategorized -> filter.databaseId
        else -> null
    }
    val bitwardenViewModel: takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel = viewModel()
    val bitwardenSyncStatusByVault by bitwardenViewModel.syncStatusByVault.collectAsState()
    BitwardenAutoSyncEffect(
        viewModel = bitwardenViewModel,
        selectedVaultId = selectedBitwardenVaultId,
        isAllView = selectedCategoryFilter is NoteCategoryFilter.All,
        // Notes must not start an all-vault Bitwarden sync while viewing local,
        // KeePass, or MDBX notes. Only an explicitly selected Bitwarden vault
        // has a valid sync target here.
        enabled = hasRestoredCategoryFilter && selectedBitwardenVaultId != null
    )
    val isTopBarSyncing = selectedBitwardenVaultId?.let { vaultId ->
        bitwardenSyncStatusByVault[vaultId].isUserVisibleSyncInProgress()
    } == true
    
    // 过滤笔记
    val filteredNotes = remember(notes, searchQuery, selectedCategoryFilter, selectedTag) {
        val categoryFiltered = filterNotesByCategory(notes, selectedCategoryFilter)
        val searchFiltered = if (searchQuery.isBlank()) {
            categoryFiltered
        } else {
            categoryFiltered.filter { item ->
                val decoded = parsedNoteById.getValue(item.id).content
                item.title.contains(searchQuery, ignoreCase = true) ||
                    decoded.content.contains(searchQuery, ignoreCase = true) ||
                    decoded.tags.any { tag -> tag.contains(searchQuery, ignoreCase = true) }
            }
        }
        if (selectedTag.isNullOrBlank()) {
            searchFiltered
        } else {
            searchFiltered.filter { item ->
                val decoded = parsedNoteById.getValue(item.id).content
                decoded.tags.any { tag -> tag.equals(selectedTag, ignoreCase = true) }
            }
        }
    }
    val availableTags = remember(notes, parsedNoteById, selectedCategoryFilter) {
        val categoryFiltered = filterNotesByCategory(notes, selectedCategoryFilter)
        categoryFiltered
            .flatMap { parsedNoteById.getValue(it.id).content.tags }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sortedBy { it.lowercase(Locale.getDefault()) }
    }
    LaunchedEffect(availableTags, selectedTag) {
        if (selectedTag != null && selectedTag !in availableTags) {
            selectedTag = null
        }
    }
    val filteredNoteUiItems = remember(filteredNotes, parsedNoteById) {
        filteredNotes.map { item ->
            item.toNoteListItemUiModel(parsedNoteById.getValue(item.id).content)
        }
    }

    // 删除实际执行
    fun performDelete() {
        val notesToDelete = notes.filter { it.id in selectedNoteIds }
        viewModel.deleteNotes(notesToDelete)
        isSelectionMode = false
        selectedNoteIds = emptySet()
        showDeleteDialog = false
        showPasswordDialog = false
        masterPassword = ""
        passwordError = false
    }

    fun performBatchMove(target: UnifiedMoveCategoryTarget, action: UnifiedMoveAction) {
        scope.launch {
            val selectedItems = notes.filter { selectedNoteIds.contains(it.id) }
            var movedCount = 0
            var failedCount = 0

            selectedItems.forEach { item ->
                val targetCategoryId = when (target) {
                    UnifiedMoveCategoryTarget.Uncategorized -> null
                    is UnifiedMoveCategoryTarget.MonicaCategory -> target.categoryId
                    else -> item.categoryId
                }
                val targetKeepassDatabaseId = when (target) {
                    UnifiedMoveCategoryTarget.Uncategorized -> null
                    is UnifiedMoveCategoryTarget.MonicaCategory -> null
                    is UnifiedMoveCategoryTarget.BitwardenVaultTarget -> null
                    is UnifiedMoveCategoryTarget.BitwardenFolderTarget -> null
                    is UnifiedMoveCategoryTarget.KeePassDatabaseTarget -> target.databaseId
                    is UnifiedMoveCategoryTarget.KeePassGroupTarget -> target.databaseId
                    is UnifiedMoveCategoryTarget.MdbxDatabaseTarget -> null
                    is UnifiedMoveCategoryTarget.MdbxFolderTarget -> null
                }
                val targetKeepassGroupPath = when (target) {
                    is UnifiedMoveCategoryTarget.KeePassGroupTarget -> target.groupPath
                    is UnifiedMoveCategoryTarget.KeePassDatabaseTarget -> null
                    else -> null
                }
                val targetBitwardenVaultId = when (target) {
                    is UnifiedMoveCategoryTarget.BitwardenVaultTarget -> target.vaultId
                    is UnifiedMoveCategoryTarget.BitwardenFolderTarget -> target.vaultId
                    else -> null
                }
                val targetBitwardenFolderId = when (target) {
                    is UnifiedMoveCategoryTarget.BitwardenFolderTarget -> target.folderId
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

                if (action == UnifiedMoveAction.COPY) {
                    if (target is UnifiedMoveCategoryTarget.MonicaCategory || target == UnifiedMoveCategoryTarget.Uncategorized) {
                        if (viewModel.copyNoteToMonicaLocal(item, targetCategoryId) != null) {
                            movedCount++
                        } else {
                            failedCount++
                        }
                    } else {
                        val decodedNote = NoteContentCodec.decodeFromItem(item)
                        viewModel.addNote(
                            content = decodedNote.content,
                            title = item.title,
                            tags = decodedNote.tags,
                            isMarkdown = decodedNote.isMarkdown,
                            isFavorite = item.isFavorite,
                            categoryId = targetCategoryId,
                            imagePaths = item.imagePaths,
                            keepassDatabaseId = targetKeepassDatabaseId,
                            keepassGroupPath = targetKeepassGroupPath,
                            bitwardenVaultId = targetBitwardenVaultId,
                            bitwardenFolderId = targetBitwardenFolderId,
                            mdbxDatabaseId = targetMdbxDatabaseId,
                            mdbxFolderId = targetMdbxFolderId
                        )
                        movedCount++
                    }
                } else {
                    val moved = if (target is UnifiedMoveCategoryTarget.MonicaCategory || target == UnifiedMoveCategoryTarget.Uncategorized) {
                        if (item.isLocalOnlyItem()) {
                            viewModel.moveNoteToStorage(
                                item = item,
                                categoryId = targetCategoryId,
                                keepassDatabaseId = null,
                                keepassGroupPath = null,
                                bitwardenVaultId = null,
                                bitwardenFolderId = null,
                                mdbxDatabaseId = null,
                                mdbxFolderId = null
                            )
                        } else {
                            viewModel.moveNoteToMonicaLocal(item, targetCategoryId).isSuccess
                        }
                    } else {
                        viewModel.moveNoteToStorage(
                            item = item,
                            categoryId = targetCategoryId,
                            keepassDatabaseId = targetKeepassDatabaseId,
                            keepassGroupPath = targetKeepassGroupPath,
                            bitwardenVaultId = targetBitwardenVaultId,
                            bitwardenFolderId = targetBitwardenFolderId,
                            mdbxDatabaseId = targetMdbxDatabaseId,
                            mdbxFolderId = targetMdbxFolderId
                        )
                    }
                    if (moved) movedCount++ else failedCount++
                }
            }

            val baseMessage = context.getString(R.string.selected_items, movedCount)
            val toastMessage = if (failedCount > 0) "$baseMessage，失败$failedCount" else baseMessage
            android.widget.Toast.makeText(context, toastMessage, android.widget.Toast.LENGTH_SHORT).show()

            showBatchMoveCategoryDialog = false
            isSelectionMode = false
            selectedNoteIds = emptySet()
        }
    }

    fun shouldWarnBitwardenImageLoss(
        target: UnifiedMoveCategoryTarget,
        selectedItems: List<SecureItem>
    ): Pair<Boolean, Int> {
        val isBitwardenTarget = when (target) {
            is UnifiedMoveCategoryTarget.BitwardenVaultTarget,
            is UnifiedMoveCategoryTarget.BitwardenFolderTarget -> true
            else -> false
        }
        if (!isBitwardenTarget) return false to 0

        val count = selectedItems.count { item ->
            if (item.itemType != ItemType.NOTE) return@count false
            val hasLegacyImagePaths = NoteContentCodec.decodeImagePaths(item.imagePaths).isNotEmpty()
            val hasInlineImages = NoteContentCodec
                .extractInlineImageIds(NoteContentCodec.decodeFromItem(item).content)
                .isNotEmpty()
            hasLegacyImagePaths || hasInlineImages
        }
        return (count > 0) to count
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            // M3E 风格顶栏（保持与其他页面一致）
            ExpressiveTopBar(
                title = title,
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                isSearchExpanded = isSearchExpanded,
                onSearchExpandedChange = { expanded ->
                    if (expanded) {
                        isSearchExpanded = true
                    } else {
                        collapseSearch()
                    }
                },
                searchHint = stringResource(R.string.search),
                onActionPillBoundsChanged = { bounds -> categoryPillBoundsInWindow = bounds },
                actions = {
                    if (settings.categorySelectionUiMode == takagi.ru.monica.data.CategorySelectionUiMode.CHIP_MENU) {
                        IconButton(onClick = { isCategorySheetVisible = true }) {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = stringResource(R.string.category),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    } else {
                        IconButton(onClick = { isCategorySheetVisible = true }) {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = stringResource(R.string.category),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    // 搜索按钮
                    IconButton(onClick = { isSearchExpanded = true }) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = stringResource(R.string.search),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Box {
                        IconButton(onClick = { showTopActionsMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.more_options),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        if (settings.categorySelectionUiMode == takagi.ru.monica.data.CategorySelectionUiMode.CHIP_MENU) {
                            UnifiedCategoryFilterChipMenuDropdown(
                                expanded = isCategorySheetVisible,
                                onDismissRequest = { isCategorySheetVisible = false },
                                offset = UnifiedCategoryFilterChipMenuOffset
                            ) {
                                UnifiedCategoryFilterChipMenu(
                                    visible = true,
                                    onDismiss = { isCategorySheetVisible = false },
                                    selected = selectedUnifiedFilter,
                                    onSelect = handleCategorySelection,
                                    categories = categories,
                                    keepassDatabases = keepassDatabases,
                                    bitwardenVaults = bitwardenVaults,
                                    getBitwardenFolders = { vaultId -> database.bitwardenFolderDao().getFoldersByVaultFlow(vaultId) },
                                    getKeePassGroups = getKeePassGroups,
                                    categoryEditMode = categoryMgmt.categoryEditMode,
                                    onRequestCategoryAction = { categoryMgmt.categoryActionTarget = it },
                                    quickFilterContent = {
                                        if (availableTags.isNotEmpty()) {
                                            Text(
                                                text = stringResource(R.string.note_tags),
                                                style = MaterialTheme.typography.labelLarge,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .horizontalScroll(rememberScrollState()),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                MonicaExpressiveFilterChip(
                                                    selected = selectedTag == null,
                                                    onClick = { selectedTag = null },
                                                    label = stringResource(R.string.note_all_tags)
                                                )
                                                availableTags.forEach { tag ->
                                                    MonicaExpressiveFilterChip(
                                                        selected = selectedTag == tag,
                                                        onClick = {
                                                            selectedTag = if (selectedTag == tag) null else tag
                                                        },
                                                        label = "#$tag"
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    trailingContent = {
                                        CategoryManagementTrailingContent(
                                            state = categoryMgmt,
                                            categories = categories,
                                            keepassDatabases = keepassDatabases,
                                            bitwardenVaults = bitwardenVaults,
                                            getBitwardenFolders = { vaultId -> database.bitwardenFolderDao().getFoldersByVaultFlow(vaultId) },
                                            getKeePassGroups = getKeePassGroups,
                                            passwordViewModel = resolvedPasswordViewModel,
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
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (isGridLayout) {
                                            stringResource(R.string.authenticator_layout_standard)
                                        } else {
                                            stringResource(R.string.authenticator_layout_tile)
                                        }
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (isGridLayout) Icons.Default.ViewList else Icons.Default.GridView,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    showTopActionsMenu = false
                                    settingsViewModel.updateNoteGridLayout(!isGridLayout)
                                }
                            )
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
                                        val vaultId = selectedBitwardenVaultId
                                        showTopActionsMenu = false
                                        bitwardenViewModel.requestManualSync(vaultId)
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
                                        viewModel.syncKeePassNotes(selectedKeePassDatabaseId)
                                    }
                                )
                            }
                        }
                    }
                }
            )

        },
        bottomBar = {
            if (isSelectionMode) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 20.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    NoteSelectionActionBar(
                        modifier = Modifier.wrapContentWidth(),
                        selectedCount = selectedNoteIds.size,
                        onExit = {
                            isSelectionMode = false
                            selectedNoteIds = emptySet()
                        },
                        onSelectAll = {
                            selectedNoteIds = if (selectedNoteIds.size == filteredNotes.size) {
                                emptySet()
                            } else {
                                filteredNotes.map { it.id }.toSet()
                            }
                        },
                        onMoveToCategory = { showBatchMoveCategoryDialog = true },
                        onDelete = { showDeleteDialog = true }
                    )
                }
            }
        },
        floatingActionButton = {} // FAB moved to SwipeableAddFab in SimpleMainScreen
    ) { paddingValues ->
        // 重置导航状态
        LaunchedEffect(Unit) {
            isNavigating = false
        }

        if (showDeleteDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                title = { Text(stringResource(R.string.delete)) },
                text = { Text(stringResource(R.string.notes_delete_selected_confirm, selectedNoteIds.size)) },
                confirmButton = {
                    TextButton(onClick = { 
                        showDeleteDialog = false
                        showPasswordDialog = true
                    }) {
                        Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        if (showPasswordDialog) {
            val skipIdentityVerification = settings.disablePasswordVerification
            val biometricAction = if (!skipIdentityVerification && activity != null && canUseBiometric) {
                {
                    biometricHelper.authenticate(
                        activity = activity,
                        title = context.getString(R.string.verify_identity),
                        subtitle = context.getString(R.string.verify_to_delete),
                        onSuccess = { performDelete() },
                        onError = { error ->
                            android.widget.Toast.makeText(
                                context,
                                error,
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        },
                        onFailed = {}
                    )
                }
            } else {
                null
            }
            M3IdentityVerifyDialog(
                title = stringResource(R.string.verify_identity),
                message = stringResource(R.string.notes_delete_selected_confirm, selectedNoteIds.size),
                passwordValue = masterPassword,
                onPasswordChange = {
                    masterPassword = it
                    passwordError = false
                },
                onDismiss = {
                    showPasswordDialog = false
                    masterPassword = ""
                    passwordError = false
                },
                onConfirm = {
                    if (skipIdentityVerification || securityManager.verifyMasterPassword(masterPassword)) {
                        performDelete()
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

        UnifiedMoveToCategoryBottomSheet(
            visible = showBatchMoveCategoryDialog,
            onDismiss = { showBatchMoveCategoryDialog = false },
            initialSource = selectedUnifiedFilter.toUnifiedMoveInitialSource(),
            categories = categories,
            keepassDatabases = keepassDatabases,
            mdbxDatabases = mdbxDatabases,
            bitwardenVaults = bitwardenVaults,
            getBitwardenFolders = { vaultId -> database.bitwardenFolderDao().getFoldersByVaultFlow(vaultId) },
            getKeePassGroups = getKeePassGroups,
            getMdbxFolders = resolvedPasswordViewModel::getMdbxFolders,
            allowCopy = true,
            allowMove = notes.filter { selectedNoteIds.contains(it.id) }.none { it.isKeePassOwned() },
            onTargetSelected = { target, action ->
                val selectedItems = notes.filter { selectedNoteIds.contains(it.id) }
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
                val (shouldWarn, count) = shouldWarnBitwardenImageLoss(target, selectedItems)
                if (shouldWarn) {
                    showBatchMoveCategoryDialog = false
                    pendingBitwardenMoveTarget = target
                    pendingBitwardenMoveAction = effectiveAction
                    bitwardenImageWarningCount = count
                    showBitwardenImageWarningDialog = true
                } else {
                    performBatchMove(target, effectiveAction)
                }
            }
        )

        if (showBitwardenImageWarningDialog) {
            AlertDialog(
                onDismissRequest = {
                    showBitwardenImageWarningDialog = false
                    pendingBitwardenMoveTarget = null
                    pendingBitwardenMoveAction = null
                    bitwardenImageWarningCount = 0
                },
                title = { Text(stringResource(R.string.note_bitwarden_move_warning_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.note_bitwarden_move_warning_message,
                            bitwardenImageWarningCount
                        )
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val target = pendingBitwardenMoveTarget
                            val action = pendingBitwardenMoveAction
                            showBitwardenImageWarningDialog = false
                            pendingBitwardenMoveTarget = null
                            pendingBitwardenMoveAction = null
                            bitwardenImageWarningCount = 0
                            if (target != null && action != null) {
                                performBatchMove(target, action)
                            }
                        }
                    ) {
                        Text(stringResource(R.string.continue_action))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showBitwardenImageWarningDialog = false
                            pendingBitwardenMoveTarget = null
                            pendingBitwardenMoveAction = null
                            bitwardenImageWarningCount = 0
                        }
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        NoteListContent(
            notes = filteredNoteUiItems,
            isInitialLoading = !parsedNotesState.isReady,
            isGridLayout = isGridLayout,
            isSearchExpanded = isSearchExpanded,
            onRequestExpandSearch = { isSearchExpanded = true },
            isBitwardenDatabaseView = isBitwardenDatabaseView,
            bitwardenRepository = bitwardenRepository,
            selectedNoteIds = selectedNoteIds,
            onNoteClick = { noteId ->
                if (isSelectionMode) {
                    selectedNoteIds = if (selectedNoteIds.contains(noteId)) {
                        selectedNoteIds - noteId
                    } else {
                        selectedNoteIds + noteId
                    }
                    if (selectedNoteIds.isEmpty()) {
                        isSelectionMode = false
                    }
                } else {
                    if (!isNavigating) {
                        isNavigating = true
                        val activeSearchQuery = searchQuery.trim()
                        if (activeSearchQuery.isNotBlank()) {
                            onNavigateToSearchedNote(noteId, activeSearchQuery)
                        } else {
                            onNavigateToAddNote(noteId)
                        }
                        scope.launch {
                            delay(600)
                            isNavigating = false
                        }
                    }
                }
            },
            onNoteLongClick = { noteId ->
                if (!isSelectionMode) {
                    isSelectionMode = true
                    selectedNoteIds = setOf(noteId)
                }
            },
            modifier = Modifier.padding(paddingValues)
        )
    }

    CategoryManagementCreateDialog(
        state = categoryMgmt,
        currentFilter = selectedUnifiedFilter,
        categories = categories,
        keepassDatabases = keepassDatabases,
        mdbxDatabases = mdbxDatabases,
        bitwardenVaults = bitwardenVaults,
        getKeePassGroups = getKeePassGroups,
        passwordViewModel = resolvedPasswordViewModel,
        bitwardenRepository = bitwardenRepository,
        keepassBridge = keepassBridge,
        scope = scope
    )
}

private fun filterNotesByCategory(
    notes: List<SecureItem>,
    filter: NoteCategoryFilter
): List<SecureItem> {
    return when (filter) {
        NoteCategoryFilter.All -> notes
        NoteCategoryFilter.Local -> notes.filter { it.isLocalOnlyItem() }
        NoteCategoryFilter.Starred -> notes.filter { it.isFavorite }
        NoteCategoryFilter.Uncategorized -> notes.filter { it.categoryId == null }
        NoteCategoryFilter.LocalStarred -> notes.filter { it.isLocalOnlyItem() && it.isFavorite }
        NoteCategoryFilter.LocalUncategorized -> notes.filter { it.isLocalOnlyItem() && it.categoryId == null }
        is NoteCategoryFilter.Custom -> notes.filter { it.categoryId == filter.categoryId && it.isLocalOnlyItem() }
        is NoteCategoryFilter.BitwardenVault -> notes.filter {
            (it.resolveOwnership() as? SecureItemOwnership.Bitwarden)?.vaultId == filter.vaultId
        }
        is NoteCategoryFilter.BitwardenFolderFilter -> notes.filter {
            val ownership = it.resolveOwnership() as? SecureItemOwnership.Bitwarden
            ownership?.vaultId == filter.vaultId && it.bitwardenFolderId == filter.folderId
        }
        is NoteCategoryFilter.BitwardenVaultStarred -> notes.filter {
            (it.resolveOwnership() as? SecureItemOwnership.Bitwarden)?.vaultId == filter.vaultId && it.isFavorite
        }
        is NoteCategoryFilter.BitwardenVaultUncategorized -> notes.filter {
            (it.resolveOwnership() as? SecureItemOwnership.Bitwarden)?.vaultId == filter.vaultId &&
                it.bitwardenFolderId == null
        }
        is NoteCategoryFilter.KeePassDatabase -> notes.filter {
            (it.resolveOwnership() as? SecureItemOwnership.KeePass)?.databaseId == filter.databaseId
        }
        is NoteCategoryFilter.KeePassGroupFilter -> notes.filter {
            takagi.ru.monica.ui.KeePassGroupFilterIdentity(
                filter.databaseId,
                filter.groupPath,
                filter.groupUuid
            ).matches(
                itemDatabaseId = (it.resolveOwnership() as? SecureItemOwnership.KeePass)?.databaseId,
                itemGroupPath = it.keepassGroupPath,
                itemGroupUuid = it.keepassGroupUuid
            )
        }
        is NoteCategoryFilter.KeePassDatabaseStarred -> notes.filter {
            (it.resolveOwnership() as? SecureItemOwnership.KeePass)?.databaseId == filter.databaseId &&
                it.isFavorite
        }
        is NoteCategoryFilter.KeePassDatabaseUncategorized -> notes.filter {
            (it.resolveOwnership() as? SecureItemOwnership.KeePass)?.databaseId == filter.databaseId &&
                it.keepassGroupPath.isNullOrBlank()
        }
        is NoteCategoryFilter.MdbxDatabase -> notes.filter {
            (it.resolveOwnership() as? SecureItemOwnership.Mdbx)?.databaseId == filter.databaseId
        }
    }
}

package takagi.ru.monica.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Note
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import takagi.ru.monica.R
import takagi.ru.monica.ui.common.selection.SelectionActionBar
import takagi.ru.monica.ui.components.SwipeableAddFab
import takagi.ru.monica.data.LocalKeePassDatabase
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.ui.components.ExpressiveTopBar
import takagi.ru.monica.ui.components.UnifiedCategoryFilterChipMenu
import takagi.ru.monica.ui.components.UnifiedCategoryFilterChipMenuDropdown
import takagi.ru.monica.ui.components.UnifiedCategoryFilterChipMenuOffset
import takagi.ru.monica.ui.components.UnifiedCategoryFilterSelection
import takagi.ru.monica.ui.components.TrashSettingsSheet
import takagi.ru.monica.viewmodel.TrashViewModel
import java.text.SimpleDateFormat
import java.util.Locale

/** Recycle bin is independent of the retired activity timeline. */
@Composable
fun TrashScreen(
    trashViewModel: TrashViewModel = viewModel(),
    initialTrashScopeKey: String? = null,
    showBackButton: Boolean = false,
    onNavigateBack: () -> Unit = {},
    showReturnFab: Boolean = true,
    onTrashSelectionModeChange: (Boolean) -> Unit = {}
) {
    var selectingTrash by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TrashContent(viewModel = trashViewModel, initialSelectedScopeKey = initialTrashScopeKey,
            hasReturnAction = showBackButton,
            onSelectionModeChange = { selecting ->
                selectingTrash = selecting
                onTrashSelectionModeChange(selecting)
            })
        if (showReturnFab && showBackButton && !selectingTrash) {
            SwipeableAddFab(fabBottomOffset = 24.dp, onClick = onNavigateBack,
                fabContent = { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) })
        }
    }
}

private data class TrashScopeFilterOption(
    val key: String,
    val label: String,
    val scope: TrashScopeFilter
)

private fun TrashScopeFilter.toUnifiedCategoryFilterSelection(): UnifiedCategoryFilterSelection {
    return when (this) {
        TrashScopeFilter.All -> UnifiedCategoryFilterSelection.All
        TrashScopeFilter.Local -> UnifiedCategoryFilterSelection.Local
        is TrashScopeFilter.BitwardenVaultScope ->
            UnifiedCategoryFilterSelection.BitwardenVaultFilter(vaultId)
        is TrashScopeFilter.KeePassDatabaseScope ->
            UnifiedCategoryFilterSelection.KeePassDatabaseFilter(databaseId)
        is TrashScopeFilter.MdbxDatabaseScope ->
            UnifiedCategoryFilterSelection.MdbxDatabaseFilter(databaseId)
    }
}

@Composable
private fun TrashScopeFilterChipMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    selectedScope: TrashScopeFilter,
    fallbackScope: TrashScopeFilter,
    keepassDatabases: List<LocalKeePassDatabase>,
    mdbxDatabases: List<LocalMdbxDatabase>,
    bitwardenVaults: List<BitwardenVault>,
    database: PasswordDatabase,
    onSelectedScopeKeyChange: (String) -> Unit
) {
    val selected = remember(selectedScope) {
        selectedScope.toUnifiedCategoryFilterSelection()
    }
    UnifiedCategoryFilterChipMenuDropdown(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        offset = UnifiedCategoryFilterChipMenuOffset
    ) {
        UnifiedCategoryFilterChipMenu(
            visible = true,
            onDismiss = onDismissRequest,
            selected = selected,
            onSelect = { selection ->
                onSelectedScopeKeyChange(selection.toTrashScopeFilter(fallbackScope).key)
                onDismissRequest()
            },
            categories = emptyList(),
            keepassDatabases = keepassDatabases,
            mdbxDatabases = mdbxDatabases,
            bitwardenVaults = bitwardenVaults,
            getBitwardenFolders = { vaultId ->
                database.bitwardenFolderDao().getFoldersByVaultFlow(vaultId)
            },
            getKeePassGroups = null
        )
    }
}

private fun resolveScopeFilter(
    bitwardenVaultId: Long?,
    keepassDatabaseId: Long?,
    mdbxDatabaseId: Long? = null
): TrashScopeFilter {
    return when {
        bitwardenVaultId != null -> TrashScopeFilter.BitwardenVaultScope(bitwardenVaultId)
        keepassDatabaseId != null -> TrashScopeFilter.KeePassDatabaseScope(keepassDatabaseId)
        mdbxDatabaseId != null -> TrashScopeFilter.MdbxDatabaseScope(mdbxDatabaseId)
        else -> TrashScopeFilter.Local
    }
}

private fun resolveTrashScope(item: takagi.ru.monica.viewmodel.TrashItem): TrashScopeFilter {
    val bitwardenVaultId = when (val data = item.originalData) {
        is PasswordEntry -> data.bitwardenVaultId
        is SecureItem -> data.bitwardenVaultId
        else -> null
    }
    val keepassDatabaseId = when (val data = item.originalData) {
        is PasswordEntry -> data.keepassDatabaseId
        is SecureItem -> data.keepassDatabaseId
        else -> null
    }
    val mdbxDatabaseId = when (val data = item.originalData) {
        is PasswordEntry -> data.mdbxDatabaseId
        is SecureItem -> data.mdbxDatabaseId
        else -> null
    }

    return resolveScopeFilter(
        bitwardenVaultId = bitwardenVaultId,
        keepassDatabaseId = keepassDatabaseId,
        mdbxDatabaseId = mdbxDatabaseId
    )
}

private fun TrashScopeFilter.matches(item: takagi.ru.monica.viewmodel.TrashItem): Boolean {
    if (this == TrashScopeFilter.All) {
        return true
    }
    return resolveTrashScope(item) == this
}

/**
 * 回收站内容 - 简化版设计，直接显示所有条目
 */
@Composable
private fun TrashContent(
    viewModel: TrashViewModel,
    embeddedInSplitPane: Boolean = false,
    initialSelectedScopeKey: String? = null,
    hasReturnAction: Boolean = false,
    onSelectionModeChange: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val database = remember(context) { PasswordDatabase.getDatabase(context.applicationContext) }
    val bitwardenVaults by database.bitwardenVaultDao().getAllVaultsFlow().collectAsState(initial = emptyList())
    val keepassDatabases by database.localKeePassDatabaseDao().getAllDatabases().collectAsState(initial = emptyList())
    val mdbxDatabases by database.localMdbxDatabaseDao().getAvailableDatabases().collectAsState(initial = emptyList())

    val trashCategories by viewModel.trashCategories.collectAsState()
    val trashSettings by viewModel.trashSettings.collectAsState()
    var selectedScopeKey by rememberSaveable(initialSelectedScopeKey) {
        mutableStateOf(trashScopeFromKey(initialSelectedScopeKey).key)
    }
    var showScopeSelectionSheet by remember { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isSearchExpanded by rememberSaveable { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showEmptyTrashDialog by remember { mutableStateOf(false) }
    var selectedItem by remember { mutableStateOf<takagi.ru.monica.viewmodel.TrashItem?>(null) }

    // 多选模式状态
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedItems by remember { mutableStateOf(setOf<String>()) }
    val reportSelectionMode by rememberUpdatedState(onSelectionModeChange)

    LaunchedEffect(isSelectionMode) {
        reportSelectionMode(isSelectionMode)
    }
    DisposableEffect(Unit) {
        onDispose { reportSelectionMode(false) }
    }

    val colorScheme = MaterialTheme.colorScheme

    val selectedScope = remember(selectedScopeKey) { trashScopeFromKey(selectedScopeKey) }

    // 扁平化所有条目，按删除时间排序
    val allItems = remember(trashCategories) {
        trashCategories.flatMap { it.items }.sortedByDescending { it.deletedAt.time }
    }
    val scopedItems = remember(allItems, selectedScope) {
        allItems.filter { selectedScope.matches(it) }
    }
    val visibleItems = remember(scopedItems, searchQuery) {
        val keyword = searchQuery.trim()
        if (keyword.isBlank()) {
            scopedItems
        } else {
            scopedItems.filter { item -> matchesTrashSearch(item, keyword) }
        }
    }

    LaunchedEffect(selectedScopeKey, searchQuery) {
        isSelectionMode = false
        selectedItems = emptySet()
    }

    // 选择/取消选择条目
    fun toggleItemSelection(item: takagi.ru.monica.viewmodel.TrashItem) {
        val key = "${item.itemType.name}_${item.id}"
        selectedItems = if (selectedItems.contains(key)) {
            selectedItems - key
        } else {
            selectedItems + key
        }
        // 如果取消选择后没有选中项，退出选择模式
        if (selectedItems.isEmpty()) {
            isSelectionMode = false
        }
    }

    fun isItemSelected(item: takagi.ru.monica.viewmodel.TrashItem): Boolean {
        return selectedItems.contains("${item.itemType.name}_${item.id}")
    }

    fun toggleSelectAll() {
        val scopedKeys = visibleItems.map { "${it.itemType.name}_${it.id}" }.toSet()
        if (scopedKeys.isNotEmpty() && selectedItems.containsAll(scopedKeys)) {
            selectedItems = emptySet()
            isSelectionMode = false
        } else {
            selectedItems = scopedKeys
        }
    }

    fun exitSelectionMode() {
        isSelectionMode = false
        selectedItems = emptySet()
    }

    BackHandler(enabled = isSelectionMode) {
        exitSelectionMode()
    }

    fun restoreSelectedItems() {
        val itemsToRestore = visibleItems.filter { isItemSelected(it) }
        itemsToRestore.forEach { item ->
            viewModel.restoreItem(item) { _ -> }
        }
        exitSelectionMode()
    }

    fun showDeleteFailure(result: Result<Unit>) {
        val failure = result.exceptionOrNull() ?: return
        val reason = failure.localizedMessage?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.timeline_trash_delete_unknown_error)
        Toast.makeText(context, context.getString(R.string.delete_failed, reason), Toast.LENGTH_LONG).show()
    }

    fun deleteSelectedItems() {
        val itemsToDelete = visibleItems.filter { isItemSelected(it) }
        viewModel.permanentlyDeleteItems(itemsToDelete) { result ->
            showDeleteFailure(result)
        }
        exitSelectionMode()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background)
    ) {
        if (!trashSettings.enabled) {
            TrashDisabledView(onEnableClick = { showSettingsDialog = true })
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                // 顶部信息栏
                TrashHeaderBar(
                    embeddedInSplitPane = embeddedInSplitPane,
                    searchBackEnabled = !isSelectionMode,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    isSearchExpanded = isSearchExpanded,
                    onSearchExpandedChange = { expanded ->
                        isSearchExpanded = expanded
                        if (!expanded) {
                            searchQuery = ""
                        }
                    },
                    onOpenScopeSheet = { showScopeSelectionSheet = true },
                    onSettingsClick = { showSettingsDialog = true },
                    onEmptyTrashClick = { showEmptyTrashDialog = true },
                    canEmptyTrash = scopedItems.isNotEmpty(),
                    scopeMenu = {
                        TrashScopeFilterChipMenu(
                            expanded = showScopeSelectionSheet,
                            onDismissRequest = { showScopeSelectionSheet = false },
                            selectedScope = selectedScope,
                            fallbackScope = TrashScopeFilter.All,
                            keepassDatabases = keepassDatabases,
                            mdbxDatabases = mdbxDatabases,
                            bitwardenVaults = bitwardenVaults,
                            database = database,
                            onSelectedScopeKeyChange = { selectedScopeKey = it }
                        )
                    }
                )

                if (visibleItems.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f)
                    ) {
                        TrashEmptyView()
                    }
                } else {
                    // 条目列表 - 直接平铺显示
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 8.dp,
                            bottom = if (isSelectionMode || hasReturnAction) 100.dp else 16.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            items = visibleItems,
                            key = { "${it.itemType.name}_${it.id}" }
                        ) { item ->
                            TrashItemCard(
                                item = item,
                                isSelectionMode = isSelectionMode,
                                isSelected = isItemSelected(item),
                                onClick = {
                                    if (isSelectionMode) {
                                        toggleItemSelection(item)
                                    } else {
                                        selectedItem = item
                                    }
                                },
                                onLongClick = {
                                    if (!isSelectionMode) {
                                        isSelectionMode = true
                                        toggleItemSelection(item)
                                    }
                                },
                                onRestore = {
                                    viewModel.restoreItem(item) { _ -> }
                                }
                            )
                        }
                    }
                }
            }
        }

        // 底部浮动操作栏（选择模式）
        AnimatedVisibility(
            visible = isSelectionMode && selectedItems.isNotEmpty(),
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, end = 16.dp, bottom = 20.dp)
        ) {
            TrashSelectionBar(
                selectedCount = selectedItems.size,
                onExit = { exitSelectionMode() },
                onSelectAll = { toggleSelectAll() },
                onRestore = { restoreSelectedItems() },
                onDelete = { deleteSelectedItems() }
            )
        }
    }

    // 回收站设置对话框
    if (showSettingsDialog) {
        TrashSettingsSheet(
            currentSettings = trashSettings,
            onDismiss = { showSettingsDialog = false },
            onConfirm = { enabled, days ->
                viewModel.updateTrashSettings(enabled, days)
                showSettingsDialog = false
            }
        )
    }

    // 清空回收站确认对话框
    if (showEmptyTrashDialog) {
        AlertDialog(
            onDismissRequest = { showEmptyTrashDialog = false },
            icon = { Icon(Icons.Default.DeleteSweep, contentDescription = null) },
            title = { Text(stringResource(R.string.timeline_empty_trash_title)) },
            text = { Text(stringResource(R.string.timeline_empty_trash_message, scopedItems.size)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.permanentlyDeleteItems(scopedItems) { result ->
                            showDeleteFailure(result)
                        }
                        showEmptyTrashDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = colorScheme.error)
                ) {
                    Text(stringResource(R.string.timeline_empty_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmptyTrashDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // 条目操作弹窗
    selectedItem?.let { item ->
        TrashItemActionSheet(
            item = item,
            onDismiss = { selectedItem = null },
            onRestore = {
                viewModel.restoreItem(item) { _ -> selectedItem = null }
            },
            onPermanentDelete = {
                viewModel.permanentlyDeleteItem(item) { result ->
                    showDeleteFailure(result)
                    selectedItem = null
                }
            }
        )
    }
}

/**
 * 回收站顶部信息栏
 */
@Composable
private fun TrashHeaderBar(
    embeddedInSplitPane: Boolean,
    searchBackEnabled: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    isSearchExpanded: Boolean,
    onSearchExpandedChange: (Boolean) -> Unit,
    onOpenScopeSheet: () -> Unit,
    onSettingsClick: () -> Unit,
    onEmptyTrashClick: () -> Unit,
    canEmptyTrash: Boolean,
    scopeMenu: @Composable () -> Unit = {}
) {
    var topActionsMenuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (embeddedInSplitPane) 2.dp else 0.dp)
    ) {
        ExpressiveTopBar(
            title = stringResource(R.string.timeline_trash_title),
            searchBackEnabled = searchBackEnabled,
            searchQuery = searchQuery,
            onSearchQueryChange = onSearchQueryChange,
            isSearchExpanded = isSearchExpanded,
            onSearchExpandedChange = onSearchExpandedChange,
            searchHint = stringResource(R.string.search_passwords_hint),
            actions = {
                Box {
                    IconButton(onClick = onOpenScopeSheet) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = stringResource(R.string.category),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    scopeMenu()
                }
                IconButton(onClick = { onSearchExpandedChange(true) }) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = stringResource(R.string.search),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box {
                    IconButton(onClick = { topActionsMenuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.more_options),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    MaterialTheme(
                        shapes = MaterialTheme.shapes.copy(
                            extraSmall = RoundedCornerShape(20.dp),
                            small = RoundedCornerShape(20.dp)
                        )
                    ) {
                        DropdownMenu(
                            expanded = topActionsMenuExpanded,
                            onDismissRequest = { topActionsMenuExpanded = false },
                            offset = DpOffset(x = 48.dp, y = 6.dp),
                            modifier = Modifier
                                .widthIn(min = 220.dp, max = 260.dp)
                                .shadow(10.dp, RoundedCornerShape(20.dp))
                                .clip(RoundedCornerShape(20.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f),
                                    shape = RoundedCornerShape(20.dp)
                                )
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.timeline_empty_action)) },
                                leadingIcon = { Icon(Icons.Default.DeleteSweep, contentDescription = null) },
                                enabled = canEmptyTrash,
                                onClick = {
                                    topActionsMenuExpanded = false
                                    onEmptyTrashClick()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.trash_settings)) },
                                leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                onClick = {
                                    topActionsMenuExpanded = false
                                    onSettingsClick()
                                }
                            )
                        }
                    }
                }
            }
        )
    }
}

private fun matchesTrashSearch(
    item: takagi.ru.monica.viewmodel.TrashItem,
    keyword: String
): Boolean {
    val query = keyword.trim().lowercase(Locale.getDefault())
    if (query.isEmpty()) return true
    val titleMatched = item.title.lowercase(Locale.getDefault()).contains(query)
    if (titleMatched) return true
    return when (val data = item.originalData) {
        is PasswordEntry -> {
            data.username.lowercase(Locale.getDefault()).contains(query) ||
                data.website.lowercase(Locale.getDefault()).contains(query) ||
                data.notes.lowercase(Locale.getDefault()).contains(query)
        }
        is SecureItem -> {
            data.notes.lowercase(Locale.getDefault()).contains(query)
        }
        else -> false
    }
}

/**
 * 回收站条目卡片 - 简洁直观的设计
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrashItemCard(
    item: takagi.ru.monica.viewmodel.TrashItem,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRestore: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val dateFormat = remember { SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()) }

    // 根据类型获取图标和颜色
    val (icon, iconColor) = when (item.itemType) {
        takagi.ru.monica.data.ItemType.PASSWORD -> Icons.Default.Key to colorScheme.primary
        takagi.ru.monica.data.ItemType.TOTP -> Icons.Default.History to colorScheme.secondary
        takagi.ru.monica.data.ItemType.BANK_CARD -> Icons.Default.CreditCard to colorScheme.tertiary
        takagi.ru.monica.data.ItemType.DOCUMENT -> Icons.Default.Description to colorScheme.error
        takagi.ru.monica.data.ItemType.BILLING_ADDRESS -> Icons.Default.Home to colorScheme.secondary
        takagi.ru.monica.data.ItemType.PAYMENT_ACCOUNT -> Icons.Default.AccountBalanceWallet to colorScheme.tertiary
        takagi.ru.monica.data.ItemType.NOTE -> Icons.Default.Note to colorScheme.outline
    }

    val typeLabel = when (item.itemType) {
        takagi.ru.monica.data.ItemType.PASSWORD -> stringResource(R.string.item_type_password)
        takagi.ru.monica.data.ItemType.TOTP -> stringResource(R.string.item_type_authenticator)
        takagi.ru.monica.data.ItemType.BANK_CARD -> stringResource(R.string.item_type_bank_card)
        takagi.ru.monica.data.ItemType.DOCUMENT -> stringResource(R.string.item_type_document)
        takagi.ru.monica.data.ItemType.BILLING_ADDRESS -> stringResource(R.string.billing_address)
        takagi.ru.monica.data.ItemType.PAYMENT_ACCOUNT -> stringResource(R.string.payment_account)
        takagi.ru.monica.data.ItemType.NOTE -> stringResource(R.string.timeline_item_note)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                colorScheme.primaryContainer.copy(alpha = 0.5f)
            else
                colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 选择模式下显示复选框，否则显示类型图标
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() },
                    modifier = Modifier.size(24.dp)
                )
            } else {
                // 类型图标
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(iconColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // 内容
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 类型标签
                    Text(
                        text = typeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = iconColor
                    )
                        Text(
                        text = stringResource(R.string.timeline_dot_separator),
                        color = colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    // 删除时间
                    Text(
                        text = dateFormat.format(item.deletedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.onSurfaceVariant
                    )
                    // 剩余天数警告
                    if (item.daysRemaining in 0..3) {
                        Text(
                            text = stringResource(R.string.timeline_dot_separator),
                            color = colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Text(
                            text = if (item.daysRemaining == 0) {
                                stringResource(R.string.timeline_clear_today)
                            } else {
                                stringResource(R.string.timeline_clear_after_days_short, item.daysRemaining)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = colorScheme.error,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // 非选择模式下显示恢复按钮
            if (!isSelectionMode) {
                FilledTonalIconButton(
                    onClick = onRestore,
                    modifier = Modifier.size(36.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = colorScheme.primaryContainer,
                        contentColor = colorScheme.onPrimaryContainer
                    )
                ) {
                    Icon(
                        Icons.Default.Restore,
                        contentDescription = stringResource(R.string.timeline_restore_this_item),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/**
 * 底部选择操作栏 - 简洁版
 */
@Composable
private fun TrashSelectionBar(
    selectedCount: Int,
    onExit: () -> Unit,
    onSelectAll: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    var showDeleteConfirm by remember { mutableStateOf(false) }

    SelectionActionBar(
        modifier = modifier,
        selectedCount = selectedCount,
        onExit = onExit,
        onSelectAll = onSelectAll,
        onRestore = onRestore,
        onDelete = { showDeleteConfirm = true }
    )

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text(stringResource(R.string.timeline_permanent_delete_title)) },
            text = { Text(stringResource(R.string.timeline_permanent_delete_selected_message, selectedCount)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = colorScheme.error)
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

/**
 * 条目操作底部弹窗
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrashItemActionSheet(
    item: takagi.ru.monica.viewmodel.TrashItem,
    onDismiss: () -> Unit,
    onRestore: () -> Unit,
    onPermanentDelete: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState()
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    val (icon, iconColor) = when (item.itemType) {
        takagi.ru.monica.data.ItemType.PASSWORD -> Icons.Default.Key to colorScheme.primary
        takagi.ru.monica.data.ItemType.TOTP -> Icons.Default.History to colorScheme.secondary
        takagi.ru.monica.data.ItemType.BANK_CARD -> Icons.Default.CreditCard to colorScheme.tertiary
        takagi.ru.monica.data.ItemType.DOCUMENT -> Icons.Default.Description to colorScheme.error
        takagi.ru.monica.data.ItemType.BILLING_ADDRESS -> Icons.Default.Home to colorScheme.secondary
        takagi.ru.monica.data.ItemType.PAYMENT_ACCOUNT -> Icons.Default.AccountBalanceWallet to colorScheme.tertiary
        takagi.ru.monica.data.ItemType.NOTE -> Icons.Default.Note to colorScheme.outline
    }

    val typeLabel = when (item.itemType) {
        takagi.ru.monica.data.ItemType.PASSWORD -> stringResource(R.string.item_type_password)
        takagi.ru.monica.data.ItemType.TOTP -> stringResource(R.string.item_type_authenticator)
        takagi.ru.monica.data.ItemType.BANK_CARD -> stringResource(R.string.item_type_bank_card)
        takagi.ru.monica.data.ItemType.DOCUMENT -> stringResource(R.string.item_type_document)
        takagi.ru.monica.data.ItemType.BILLING_ADDRESS -> stringResource(R.string.billing_address)
        takagi.ru.monica.data.ItemType.PAYMENT_ACCOUNT -> stringResource(R.string.payment_account)
        takagi.ru.monica.data.ItemType.NOTE -> stringResource(R.string.timeline_item_note)
    }

    var showDeleteConfirm by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 顶部信息
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(iconColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.timeline_deleted_at_with_type, typeLabel, dateFormat.format(item.deletedAt)),
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant
                    )
                }
            }

            // 剩余天数提示
            if (item.daysRemaining >= 0) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = if (item.daysRemaining <= 3)
                        colorScheme.errorContainer.copy(alpha = 0.5f)
                    else
                        colorScheme.surfaceContainerHigh
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.History,
                            contentDescription = null,
                            tint = if (item.daysRemaining <= 3) colorScheme.error else colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = when {
                                item.daysRemaining == 0 -> stringResource(R.string.timeline_auto_permanent_delete_today)
                                item.daysRemaining <= 3 -> stringResource(R.string.timeline_auto_permanent_delete_in_days, item.daysRemaining)
                                else -> stringResource(R.string.timeline_auto_clear_in_days, item.daysRemaining)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (item.daysRemaining <= 3) colorScheme.error else colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            HorizontalDivider(color = colorScheme.outlineVariant.copy(alpha = 0.5f))

            // 操作按钮
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 恢复按钮
                Button(
                    onClick = {
                        onDismiss()
                        onRestore()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.timeline_restore_this_item), fontWeight = FontWeight.Medium)
                }

                // 永久删除按钮
                OutlinedButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colorScheme.error),
                    border = BorderStroke(1.dp, colorScheme.error)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.timeline_permanent_delete_title))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // 删除确认对话框
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = colorScheme.error) },
            title = { Text(stringResource(R.string.timeline_permanent_delete_title)) },
            text = { Text(stringResource(R.string.timeline_permanent_delete_item_message, item.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDismiss()
                        onPermanentDelete()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = colorScheme.error)
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

/**
 * 回收站未启用视图
 */
@Composable
private fun TrashDisabledView(
    onEnableClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Delete,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.timeline_trash_disabled_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.timeline_trash_disabled_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )

        Spacer(modifier = Modifier.height(24.dp))

        FilledTonalButton(onClick = onEnableClick) {
            Icon(Icons.Default.Settings, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.settings))
        }
    }
}

/**
 * 回收站为空视图
 */
@Composable
private fun TrashEmptyView() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Delete,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.timeline_trash_empty_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.timeline_trash_empty_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}

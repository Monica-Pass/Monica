package takagi.ru.monica.ui.vaultv2

import takagi.ru.monica.ui.screens.managerDatabase

import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import takagi.ru.monica.ui.components.UnifiedCategoryFilterSelection
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import takagi.ru.monica.R
import takagi.ru.monica.data.LocalKeePassDatabase
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.VaultOverviewConfig
import takagi.ru.monica.data.VaultOverviewModule
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.password.PasskeyScanTopActionsMenuItem
import takagi.ru.monica.ui.password.PasswordTopActionsDropdownMenu
import takagi.ru.monica.ui.components.ExpressiveTopBar
import takagi.ru.monica.ui.components.GroupedItemDefaults
import takagi.ru.monica.ui.components.UnifiedCategoryFilterChipMenuDropdown
import takagi.ru.monica.ui.components.UnifiedDatabaseFilterChipMenu
import takagi.ru.monica.ui.icons.VaultItemIcon
import takagi.ru.monica.ui.common.pull.PullSearchDefaults
import takagi.ru.monica.ui.common.pull.PullSearchHint
import takagi.ru.monica.ui.common.pull.rememberPullToSearchState
import takagi.ru.monica.ui.gestures.SwipeActions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VaultOverviewScreen(
    snapshot: VaultOverviewSnapshot?,
    sources: List<VaultOverviewSource>,
    keepassDatabases: List<LocalKeePassDatabase>,
    mdbxDatabases: List<LocalMdbxDatabase>,
    bitwardenVaults: List<BitwardenVault>,
    currentScope: String,
    config: VaultOverviewConfig,
    listState: LazyListState,
    selectedCardKey: String? = null,
    onSelectedCardChange: (String) -> Unit = {},
    securityManager: SecurityManager,
    reduceAnimations: Boolean,
    trashCount: Int?,
    onConfigChange: ((VaultOverviewConfig) -> VaultOverviewConfig) -> Unit,
    onSelectScope: (String) -> Unit,
    onOpenSource: (String) -> Unit,
    onOpenItem: (VaultV2Item) -> Unit,
    onOpenType: (VaultV2ItemType) -> Unit,
    onOpenFolder: (VaultOverviewFolder) -> Unit,
    onFavorites: () -> Unit,
    onArchive: () -> Unit,
    onTrash: () -> Unit,
    onAllItems: () -> Unit,
    onSearch: () -> Unit,
    onScanFidoQr: () -> Unit,
    onUnlock: () -> Unit,
    cardStackState: VaultOverviewCardStackState = remember { VaultOverviewCardStackState() },
    isDetailVisible: Boolean = false,
    modifier: Modifier = Modifier,
    selection: VaultOverviewSelectionState = remember { VaultOverviewSelectionState() },
    onRequestDeleteItem: (VaultV2Item) -> Unit = {},
    walletStackLoopEnabled: Boolean = false,
) {
    var showTopActions by rememberSaveable { mutableStateOf(false) }
    var showSources by rememberSaveable { mutableStateOf(false) }
    var showCustomization by rememberSaveable { mutableStateOf(false) }
    var pinModule by rememberSaveable { mutableStateOf<String?>(null) }
    var showAllFolders by rememberSaveable { mutableStateOf(false) }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val searchListState = rememberLazyListState()
    fun setSearchExpanded(expanded: Boolean) {
        searchExpanded = expanded
        if (expanded) {
            selection.clear()
            onSearch()
        } else {
            searchQuery = ""
            selection.exitSearch()
        }
    }
    val sourceByKey = remember(sources) { sources.associateBy(VaultOverviewSource::key) }
    val selectedSource = sourceByKey[currentScope]
    val frequentPreview = snapshot?.selectablePreview(VaultOverviewModule.ITEMS, config).orEmpty()
    val favoritesPreview = snapshot?.selectablePreview(VaultOverviewModule.FAVORITES, config).orEmpty()
    val selectionModule = selection.module
    LaunchedEffect(selectionModule, frequentPreview, favoritesPreview, currentScope, selectedSource?.locked) {
        if (selectionModule != null) {
            val visible = when {
                selectedSource?.locked == true || snapshot?.scope != currentScope -> emptyList()
                selectionModule == VaultOverviewModule.ITEMS -> frequentPreview
                else -> favoritesPreview
            }
            selection.retainVisible(visible.mapTo(hashSetOf()) { it.key })
        }
    }
    LaunchedEffect(cardStackState.expanded) {
        if (cardStackState.expanded) selection.clear()
    }
    val scopeName = selectedSource?.name ?: stringResource(
        if (currentScope == "all") R.string.vault_overview_all_databases else R.string.vault_overview_choose_database)
    val visibleModules = remember(config.order, config.hidden) {
        config.order.filterNot(config.hidden::contains).map(VaultOverviewModule::valueOf)
    }
    val cardsVisible = VaultOverviewModule.CARDS in visibleModules && VaultOverviewModule.CARDS.name !in config.collapsed
    val walletCards = if (cardsVisible && snapshot != null && selectedSource?.locked != true) {
        rememberOverviewWalletCards(snapshot.cards, currentScope, securityManager, cardStackState)
    } else null
    LaunchedEffect(currentScope, cardsVisible, selectedSource?.locked) {
        if (!cardsVisible || selectedSource?.locked == true) cardStackState.clear()
    }
    val density = LocalDensity.current
    val searchTriggerDistance = with(density) { PullSearchDefaults.TriggerDistance.toPx() }
    val pullSearch = rememberPullToSearchState(
        isSearchExpanded = searchExpanded || isDetailVisible || cardStackState.expanded || selectionModule != null,
        searchTriggerDistance = searchTriggerDistance,
        maxDragDistance = with(density) { 100.dp.toPx() },
        onSearchTriggered = { setSearchExpanded(true) },
    )
    Column(modifier.fillMaxSize().testTag("vault_overview_screen")) {
        ExpressiveTopBar(
            title = stringResource(R.string.vault_overview_title),
            searchQuery = searchQuery,
            onSearchQueryChange = { searchQuery = it },
            isSearchExpanded = searchExpanded,
            searchBackEnabled = !isDetailVisible && selection.keys.isEmpty(),
            onSearchExpandedChange = ::setSearchExpanded,
            collapsedTitleEndPadding = 180.dp,
            modifier = Modifier.testTag("overview_top_bar"),
            actions = {
                IconButton(onClick = { showSources = true },
                    modifier = Modifier.testTag("overview_scope").semantics { stateDescription = scopeName }) {
                    Icon(Icons.Default.Folder, stringResource(R.string.vault_overview_choose_database))
                }
                IconButton(onClick = { setSearchExpanded(true) }, modifier = Modifier.testTag("overview_search")) {
                    Icon(Icons.Default.Search, stringResource(R.string.search))
                }
                Box {
                    IconButton(onClick = { showTopActions = true }, modifier = Modifier.testTag("overview_more")) {
                        Icon(Icons.Default.MoreVert, stringResource(R.string.more_options))
                    }
                    PasswordTopActionsDropdownMenu(
                        expanded = showTopActions,
                        onDismissRequest = { showTopActions = false },
                    ) {
                        takagi.ru.monica.ui.screens.DatabaseManagerMenuItem(overviewScopeSelection(currentScope).managerDatabase()) { showTopActions = false }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.vault_overview_customize)) },
                            leadingIcon = { Icon(Icons.Default.Tune, null) },
                            modifier = Modifier.testTag("overview_customize"),
                            onClick = {
                                showTopActions = false
                                showCustomization = true
                            },
                        )
                        PasskeyScanTopActionsMenuItem(
                            onDismissMenu = { showTopActions = false },
                            onScanFidoQr = onScanFidoQr,
                        )
                    }
                    UnifiedCategoryFilterChipMenuDropdown(
                        expanded = showSources,
                        onDismissRequest = { showSources = false },
                    ) {
                        UnifiedDatabaseFilterChipMenu(
                            selected = overviewScopeSelection(currentScope),
                            onSelect = { selection ->
                                showSources = false
                                onSelectScope(selection.overviewScope())
                            },
                            keepassDatabases = keepassDatabases,
                            mdbxDatabases = mdbxDatabases,
                            bitwardenVaults = bitwardenVaults,
                            modifier = Modifier.testTag("overview_database_menu"),
                        )
                    }
                }
            },
        )
        if (selectedSource?.locked == true) {
            Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Lock, null, Modifier.size(40.dp))
                Text(stringResource(R.string.vault_overview_locked), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 16.dp))
                Text(stringResource(R.string.vault_overview_locked_hint), modifier = Modifier.padding(vertical = 16.dp))
                Button(onClick = onUnlock) { Text(stringResource(R.string.vault_overview_unlock)) }
            }
        } else if (snapshot == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (searchExpanded) {
            VaultOverviewSearchResults(
                items = snapshot.items, sources = sources, currentScope = currentScope,
                query = searchQuery, securityManager = securityManager,
                listState = searchListState, onOpenItem = onOpenItem,
                selection = selection, isDetailVisible = isDetailVisible,
                onRequestDeleteItem = onRequestDeleteItem,
                modifier = Modifier.weight(1f),
            )
        } else {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                PullSearchHint(currentOffset = pullSearch.currentOffset, triggerDistance = searchTriggerDistance)
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()
                    .offset { IntOffset(0, pullSearch.currentOffset.toInt()) }
                    .then(pullSearch.gestureModifier)
                    .testTag("overview_modules"),
                    userScrollEnabled = !cardStackState.expanded || isDetailVisible,
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 116.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    if (currentScope == "all" && sources.any { it.locked }) item(key = "locked_notice") {
                        Text(stringResource(R.string.vault_overview_locked_excluded, sources.count { it.locked }),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(visibleModules, key = { it.name }, contentType = { it.name }) { module ->
                        val collapsed = module.name in config.collapsed
                        val count = when (module) {
                            VaultOverviewModule.RECENT -> snapshot.recentItems.size
                            VaultOverviewModule.CARDS -> snapshot.cards.size
                            VaultOverviewModule.ITEMS -> snapshot.frequentItems.size
                            VaultOverviewModule.FAVORITES -> snapshot.favorites.size
                            VaultOverviewModule.FOLDERS -> snapshot.folders.size
                            VaultOverviewModule.DATABASES -> sources.size
                            else -> null
                        }
                        if (module == VaultOverviewModule.ARCHIVE || module == VaultOverviewModule.TRASH) {
                            OverviewNavigationRow(
                                title = stringResource(module.titleRes()),
                                icon = if (module == VaultOverviewModule.ARCHIVE) Icons.Default.Archive else Icons.Default.DeleteOutline,
                                count = if (module == VaultOverviewModule.ARCHIVE) snapshot.archiveCount else trashCount,
                                onClick = if (module == VaultOverviewModule.ARCHIVE) onArchive else onTrash,
                                modifier = Modifier.testTag("overview_${module.name.lowercase()}"),
                            )
                        } else Column(Modifier.testTag("overview_${module.name.lowercase()}")) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Row(Modifier.weight(1f).testTag("overview_toggle_${module.name}").clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    role = Role.Button,
                                ) {
                                    onConfigChange { it.copy(collapsed = if (collapsed) it.collapsed - module.name else it.collapsed + module.name) }
                                }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(if (collapsed) Icons.Default.ChevronRight else Icons.Default.ExpandMore, null, Modifier.size(18.dp))
                                    Text(stringResource(module.titleRes()), Modifier.padding(start = 6.dp),
                                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    count?.let { Text(it.toString(), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                                if (module == VaultOverviewModule.CARDS || module == VaultOverviewModule.ITEMS) {
                                    IconButton(onClick = { pinModule = module.name }, modifier = Modifier.testTag("overview_pin_${module.name.lowercase()}")) {
                                        Icon(Icons.Default.Add, stringResource(if (module == VaultOverviewModule.CARDS) R.string.vault_overview_pin_cards else R.string.vault_overview_pin_items))
                                    }
                                } else if (module == VaultOverviewModule.FAVORITES) {
                                    TextButton(onClick = onFavorites) { Text(stringResource(R.string.vault_overview_view_all)) }
                                }
                            }
                            if (!collapsed) when (module) {
                                VaultOverviewModule.CARDS -> if (snapshot.cards.isEmpty()) OverviewEmpty(R.string.vault_overview_empty_cards)
                                    else OverviewCards(walletCards, selectedCardKey, listState,
                                        cardStackState, isDetailVisible, onManage = { pinModule = VaultOverviewModule.CARDS.name })
                                VaultOverviewModule.ITEMS, VaultOverviewModule.FAVORITES -> {
                                    val frequent = module == VaultOverviewModule.ITEMS
                                    val preview = if (frequent) frequentPreview else favoritesPreview
                                    if (preview.isEmpty()) OverviewEmpty(if (frequent) R.string.vault_overview_empty_items else R.string.vault_overview_empty_favorites)
                                    else Column(verticalArrangement = Arrangement.spacedBy(GroupedItemDefaults.Spacing)) {
                                        preview.forEachIndexed { index, row ->
                                            key(row.key) {
                                                val shape = GroupedItemDefaults.shape(index, preview.size)
                                                SwipeActions(
                                                    onSwipeLeft = {
                                                        if (frequent) {
                                                            val identity = row.overviewIdentity()
                                                            onConfigChange { it.removeFrequentItems(listOf(identity)) }
                                                            selection.clear()
                                                        } else onRequestDeleteItem(row)
                                                    },
                                                    onSwipeRight = { selection.toggle(module, row.key) },
                                                    enabled = !isDetailVisible,
                                                    cardShape = shape,
                                                    leftActionLabel = stringResource(if (frequent) R.string.vault_overview_remove_frequent_items else R.string.swipe_action_delete),
                                                    leftActionIcon = if (frequent) Icons.Default.RemoveCircleOutline else Icons.Default.Delete,
                                                    leftActionColor = if (frequent) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
                                                    leftActionContentColor = if (frequent) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                                                ) {
                                                    OverviewItemRow(
                                                        item = row,
                                                        shape = shape,
                                                        selected = selection.isSelected(module, row.key),
                                                        selectionMode = selectionModule == module,
                                                        onClick = {
                                                            if (selectionModule != null) selection.toggle(module, row.key)
                                                            else onOpenItem(row)
                                                        },
                                                        onLongClick = { selection.toggle(module, row.key) },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                VaultOverviewModule.RECENT -> {
                                    if (snapshot.recentItems.isEmpty()) OverviewEmpty(R.string.vault_overview_recent_empty)
                                    else Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        snapshot.recentItems.forEachIndexed { index, row ->
                                            OverviewItemRow(item = row,
                                                shape = GroupedItemDefaults.shape(index, snapshot.recentItems.size),
                                                selected = false, selectionMode = false,
                                                onClick = { onOpenItem(row) }, rowTag = "overview_recent_${row.key}")
                                        }
                                    }
                                }
                                VaultOverviewModule.TYPES -> VaultOverviewTypeGrid(snapshot.typeCounts, onOpenType)
                                VaultOverviewModule.FOLDERS -> {
                                    if (snapshot.folders.isEmpty()) OverviewEmpty(R.string.vault_overview_empty_folders)
                                    else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        snapshot.folders.take(6).forEach { folder -> OverviewFolderRow(folder, sourceByKey, currentScope, onOpenFolder) }
                                        if (snapshot.folders.size > 6) TextButton(onClick = { showAllFolders = true }) {
                                            Text(stringResource(R.string.vault_overview_folder_list))
                                        }
                                    }
                                }
                                VaultOverviewModule.DATABASES -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    sources.forEach { source -> OverviewSourceRow(source, snapshot.sourceCounts[source.key],
                                        selected = source.key == currentScope, onClick = {
                                            if (source.locked) onSelectScope(source.key) else onOpenSource(source.key)
                                        }) }
                                }
                                else -> Unit
                            }
                        }
                    }
                    if (visibleModules.isEmpty()) item { OverviewEmpty(R.string.vault_overview_hidden_hint) }
                    item(key = "all_items") {
                        FilledTonalButton(onClick = onAllItems, modifier = Modifier.fillMaxWidth().testTag("overview_all_items")) {
                            Text(stringResource(R.string.vault_overview_all_items))
                            Icon(Icons.Default.ChevronRight, null, Modifier.padding(start = 8.dp).size(18.dp))
                        }
                    }
                }
            }
        }
    }
    if (cardsVisible && snapshot != null) OverviewCardStackBrowser(
        walletCards, sourceByKey, selectedCardKey, cardStackState, isDetailVisible, reduceAnimations,
        onSelectedCardChange, onOpenItem, onManage = { pinModule = VaultOverviewModule.CARDS.name },
        loopEnabled = walletStackLoopEnabled,
    )
    if (showCustomization) VaultOverviewCustomizationSheet(config, onConfigChange, { showCustomization = false })
    if (showAllFolders && snapshot != null) OverviewSheet(stringResource(R.string.vault_overview_folder_list), { showAllFolders = false }) {
        items(snapshot.folders, key = { it.key }) { folder ->
            OverviewFolderRow(folder, sourceByKey, currentScope) { showAllFolders = false; onOpenFolder(it) }
        }
    }
    if (pinModule != null && snapshot != null) {
        VaultOverviewPickerSheet(
            cards = pinModule == VaultOverviewModule.CARDS.name,
            items = snapshot.items,
            currentFrequentItems = if (pinModule == VaultOverviewModule.CARDS.name) snapshot.cards else frequentPreview,
            sources = sources,
            currentScope = currentScope,
            keepassDatabases = keepassDatabases,
            mdbxDatabases = mdbxDatabases,
            bitwardenVaults = bitwardenVaults,
            config = config,
            securityManager = securityManager,
            onConfigChange = onConfigChange,
            onDismiss = { pinModule = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun OverviewItemRow(
    item: VaultV2Item,
    shape: Shape,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    subtitle: String = item.subtitle,
    rowTag: String = "overview_item_${item.key}",
) {
    ListItem(headlineContent = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = {
            val password = item.passwordEntry
            if (password != null) VaultItemIcon(
                website = password.website,
                title = password.title,
                appPackageName = password.appPackageName,
                customIconType = password.customIconType,
                customIconValue = password.customIconValue,
                defaultIcon = Icons.Default.Lock,
                keepassDatabaseId = password.keepassDatabaseId,
                keepassEntryUuid = password.keepassEntryUuid,
                modifier = Modifier.testTag("overview_icon_${item.key}"),
            ) else Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                val nativeIcon = takagi.ru.monica.ui.icons.rememberKeePassCustomIcon(item.keepassDatabaseId(), item.keepassEntryUuid())
                if (nativeIcon != null) takagi.ru.monica.ui.icons.KeePassCustomIconImage(nativeIcon, Modifier.size(36.dp))
                else Icon(item.type.icon(), null, tint = MaterialTheme.colorScheme.primary)
            }
        },
        trailingContent = if (selectionMode) ({ Checkbox(checked = selected, onCheckedChange = null) }) else null,
        modifier = Modifier.clip(shape)
            .combinedClickable(
                role = if (selectionMode) Role.Checkbox else Role.Button,
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = if (onLongClick != null) stringResource(R.string.swipe_action_select) else null,
            )
            .semantics { this.selected = selected }
            .testTag(rowTag),
        colors = ListItemDefaults.colors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
            else MaterialTheme.colorScheme.surfaceContainerLow))
}

@Composable
private fun OverviewNavigationRow(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, count: Int?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            count?.let { Text(it.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun OverviewFolderRow(folder: VaultOverviewFolder, sources: Map<String, VaultOverviewSource>, scope: String, onClick: (VaultOverviewFolder) -> Unit) {
    val group = folder.target as? UnifiedCategoryFilterSelection.KeePassGroupFilter
    val nativeIcon = takagi.ru.monica.ui.icons.rememberKeePassCustomIcon(group?.databaseId, groupUuid = group?.groupUuid, groupPath = group?.groupPath)
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        ListItem(headlineContent = { Text(folder.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = if (scope == "all") ({ Text(sources[folder.sourceKey]?.name.orEmpty(), style = MaterialTheme.typography.bodySmall) }) else null,
            leadingContent = {
                if (nativeIcon != null) takagi.ru.monica.ui.icons.KeePassCustomIconImage(nativeIcon, Modifier.size(36.dp))
                else Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary)
            },
            trailingContent = { Text(folder.count.toString(), style = MaterialTheme.typography.labelMedium) },
            modifier = Modifier.clickable(role = Role.Button) { onClick(folder) }.testTag("overview_folder_${folder.key}"),
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow))
    }
}

@Composable
private fun OverviewSourceRow(source: VaultOverviewSource, count: Int?, selected: Boolean, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
        ListItem(headlineContent = { Text(source.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(source.provider, style = MaterialTheme.typography.bodySmall) },
            leadingContent = { Icon(overviewSourceIcon(source.key), null) },
            trailingContent = {
                if (source.locked) Text(stringResource(R.string.vault_overview_locked), style = MaterialTheme.typography.labelSmall)
                else if (selected) Icon(Icons.Default.Check, null)
                else count?.let { Text(it.toString(), style = MaterialTheme.typography.labelMedium) }
            }, modifier = Modifier.clickable(role = Role.Button, onClick = onClick).testTag("overview_source_${source.key}"),
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent))
    }
}

internal fun overviewSourceIcon(source: String): androidx.compose.ui.graphics.vector.ImageVector = when (source.substringBefore(':')) {
    "all" -> Icons.Default.List
    "local" -> Icons.Default.Smartphone
    "keepass" -> Icons.Default.Key
    "bitwarden" -> Icons.Default.CloudSync
    else -> Icons.Default.Storage
}

@Composable
private fun OverviewEmpty(text: Int) { Text(stringResource(text), Modifier.fillMaxWidth().padding(vertical = 16.dp),
    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OverviewSheet(title: String, onDismiss: () -> Unit, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).padding(horizontal = 16.dp)) { Text(stringResource(R.string.vault_overview_done)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VaultOverviewCustomizationSheet(config: VaultOverviewConfig,
    onChange: ((VaultOverviewConfig) -> VaultOverviewConfig) -> Unit, onDismiss: () -> Unit) {
    var order by remember(config.order) { mutableStateOf(config.order) }
    val listState = rememberLazyListState()
    fun move(from: Int, to: Int) {
        if (from !in order.indices || to !in order.indices) return
        order = order.toMutableList().apply { add(to, removeAt(from)) }
        val newOrder = order
        onChange { it.copy(order = newOrder) }
    }
    val reorder = rememberReorderableLazyListState(listState) { from, to -> move(from.index, to.index) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(stringResource(R.string.vault_overview_customize), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
        Text(stringResource(R.string.vault_overview_customize_hint), style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp).testTag("overview_module_settings"),
            contentPadding = PaddingValues(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(order, key = { it }) { id -> ReorderableItem(reorder, key = id) {
                val title = stringResource(VaultOverviewModule.valueOf(id).titleRes())
                val index = order.indexOf(id)
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.DragHandle, null, Modifier.size(36.dp).longPressDraggableHandle())
                        Checkbox(id !in config.hidden, { checked -> onChange { it.copy(hidden = if (checked) it.hidden - id else it.hidden + id) } },
                            modifier = Modifier.testTag("overview_visible_$id"))
                        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        IconButton(onClick = { move(index, index - 1) }, enabled = index > 0,
                            modifier = Modifier.testTag("overview_move_up_$id")) { Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.vault_overview_move_up, title)) }
                        IconButton(onClick = { move(index, index + 1) }, enabled = index < order.lastIndex,
                            modifier = Modifier.testTag("overview_move_down_$id")) { Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.vault_overview_move_down, title)) }
                    }
                }
            } }
        }
        Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { onChange { it.copy(order = VaultOverviewModule.defaultOrder, hidden = emptySet(), collapsed = setOf(VaultOverviewModule.DATABASES.name)) } }) { Text(stringResource(R.string.vault_overview_reset)) }
            Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.vault_overview_done)) }
        }
    }
}

internal fun VaultOverviewModule.titleRes(): Int = when (this) {
    VaultOverviewModule.RECENT -> R.string.vault_overview_recent
    VaultOverviewModule.CARDS -> R.string.vault_overview_cards
    VaultOverviewModule.ITEMS -> R.string.vault_overview_items
    VaultOverviewModule.FAVORITES -> R.string.vault_overview_favorites
    VaultOverviewModule.TYPES -> R.string.vault_overview_types
    VaultOverviewModule.FOLDERS -> R.string.vault_overview_folders
    VaultOverviewModule.DATABASES -> R.string.vault_overview_databases
    VaultOverviewModule.ARCHIVE -> R.string.vault_overview_archive
    VaultOverviewModule.TRASH -> R.string.vault_overview_trash
}

internal fun VaultV2ItemType.titleRes(): Int = when (this) {
    VaultV2ItemType.PASSWORD -> R.string.item_type_password
    VaultV2ItemType.AUTHENTICATOR -> R.string.item_type_authenticator
    VaultV2ItemType.NOTE -> R.string.vault_overview_note
    VaultV2ItemType.PASSKEY -> R.string.vault_overview_passkey
    VaultV2ItemType.BANK_CARD -> R.string.item_type_bank_card
    VaultV2ItemType.DOCUMENT -> R.string.item_type_document
    VaultV2ItemType.BILLING_ADDRESS -> R.string.billing_address
}

internal fun VaultV2ItemType.icon(): androidx.compose.ui.graphics.vector.ImageVector = when (this) {
    VaultV2ItemType.PASSWORD -> Icons.Default.Key
    VaultV2ItemType.AUTHENTICATOR -> Icons.Default.Security
    VaultV2ItemType.NOTE -> Icons.Default.Description
    VaultV2ItemType.PASSKEY -> Icons.Default.VpnKey
    VaultV2ItemType.BANK_CARD -> Icons.Default.CreditCard
    VaultV2ItemType.DOCUMENT -> Icons.Default.Badge
    VaultV2ItemType.BILLING_ADDRESS -> Icons.Default.Home
}

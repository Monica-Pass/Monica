package takagi.ru.monica.ui.vaultv2

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import takagi.ru.monica.R
import takagi.ru.monica.data.LocalKeePassDatabase
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.VAULT_OVERVIEW_MAX_CARD_PINS
import takagi.ru.monica.data.VAULT_OVERVIEW_MAX_ITEM_PINS
import takagi.ru.monica.data.VaultOverviewConfig
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.cardwallet.CardBrandIcon
import takagi.ru.monica.ui.components.GroupedItemDefaults
import takagi.ru.monica.ui.components.UnifiedCategoryFilterChipMenuDropdown
import takagi.ru.monica.ui.components.UnifiedDatabaseFilterChipMenu
import takagi.ru.monica.ui.icons.VaultItemIcon

/** Single-copy mode shares the same search, list and rows as frequent selection. */
internal data class OverviewPickerCopyAction(val title: String, val onSelect: (VaultV2Item) -> Unit)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun VaultOverviewPickerSheet(
    cards: Boolean,
    items: List<VaultV2Item>,
    currentFrequentItems: List<VaultV2Item>,
    sources: List<VaultOverviewSource>,
    currentScope: String,
    keepassDatabases: List<LocalKeePassDatabase>,
    mdbxDatabases: List<LocalMdbxDatabase>,
    bitwardenVaults: List<BitwardenVault>,
    config: VaultOverviewConfig,
    securityManager: SecurityManager,
    onConfigChange: ((VaultOverviewConfig) -> VaultOverviewConfig) -> Unit,
    onDismiss: () -> Unit,
    copyAction: OverviewPickerCopyAction? = null,
) {
    var query by rememberSaveable(cards, currentScope) { mutableStateOf("") }
    var scope by rememberSaveable(cards, currentScope) { mutableStateOf(currentScope) }
    var showDatabases by remember { mutableStateOf(false) }
    val sourceByKey = remember(sources) { sources.associateBy(VaultOverviewSource::key) }
    val source = sourceByKey[scope]
    val scopeName = source?.name ?: stringResource(R.string.vault_overview_all_databases)
    val pins = if (cards) config.pinnedCards else config.pinnedItems
    val maxPins = if (cards) VAULT_OVERVIEW_MAX_CARD_PINS else VAULT_OVERVIEW_MAX_ITEM_PINS
    // Capture the opening order so selecting or removing a pin never moves the
    // row under the user's finger. Reopening reflects the updated selection.
    val priorityIdentities = rememberSaveable(cards, currentScope) {
        (pins + currentFrequentItems.map { it.overviewIdentity() }).distinct()
    }
    val prepared = rememberOverviewPicker(items, sources, cards, priorityIdentities, securityManager)
    val candidatesState = remember(prepared, query, scope) { mutableStateOf<List<OverviewPickerEntry>?>(null) }
    LaunchedEffect(candidatesState) {
        candidatesState.value = withContext(Dispatchers.Default) { prepared?.filter(query, scope) }
    }
    val candidates = candidatesState.value
    val selected = remember(pins) { pins.toHashSet() }
    val recommend = if (cards) config.recommendCards else config.recommendItems
    val listState = rememberLazyListState()
    val horizontalPadding = if (copyAction != null) 12.dp else 20.dp
    LaunchedEffect(query, scope) { listState.scrollToItem(0) }
    val toggle: (String) -> Unit = { key ->
        onConfigChange { old ->
            if (cards) {
                val existing = old.pinnedCards
                val next = when {
                    key in existing -> existing - key
                    existing.size < maxPins -> existing + key
                    else -> existing
                }
                old.copy(pinnedCards = next)
            } else old.togglePinnedItem(key)
        }
    }
    val setRecommend: (Boolean) -> Unit = { checked ->
        onConfigChange { if (cards) it.copy(recommendCards = checked) else it.copy(recommendItems = checked) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag(if (copyAction == null) "overview_pin_sheet" else "embedded_wallet_picker"),
    ) {
        // These locals belong to the dialog's Compose owner, not the activity
        // behind it. Reading them outside the sheet misses its IME and focus.
        val keyboard = LocalSoftwareKeyboardController.current
        val focus = LocalFocusManager.current
        val imeVisible = WindowInsets.isImeVisible
        val hideKeyboard = { keyboard?.hide(); focus.clearFocus() }
        // ModalBottomSheet applies IME insets to its available height. The list
        // takes the remaining space, keeping search and Done above the keyboard.
        // Constrain the content, not the sheet surface: its anchors need the
        // full window height to meet the bottom edge and the keyboard correctly.
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
            Row(Modifier.fillMaxWidth().padding(start = horizontalPadding, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    copyAction?.title ?: stringResource(if (cards) R.string.vault_overview_pin_cards else R.string.vault_overview_pin_items),
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
            }
            if (copyAction == null && !imeVisible) Text(
                stringResource(R.string.vault_overview_pin_hint),
                Modifier.padding(start = 20.dp, end = 20.dp, bottom = 14.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (copyAction != null) Spacer(Modifier.height(12.dp))
            TextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalPadding).testTag("overview_pin_search"),
                singleLine = true, shape = RoundedCornerShape(24.dp),
                placeholder = { Text(stringResource(if (cards) R.string.vault_overview_picker_search_cards
                    else if (copyAction != null) R.string.search else R.string.vault_overview_picker_search_items), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(20.dp)) },
                trailingIcon = if (query.isNotEmpty()) ({
                    IconButton(onClick = { query = "" }, modifier = Modifier.testTag("overview_pin_clear")) {
                        Icon(Icons.Default.Close, stringResource(R.string.clear_search), Modifier.size(20.dp))
                    }
                }) else null,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { hideKeyboard() }),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = horizontalPadding, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) {
                    if (currentScope == "all") {
                        AssistChip(
                            onClick = { hideKeyboard(); showDatabases = true },
                            label = { Text(scopeName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = { Icon(overviewSourceIcon(scope), null, Modifier.size(16.dp)) },
                            trailingIcon = { Icon(Icons.Default.ExpandMore, null, Modifier.size(16.dp)) },
                            shape = CircleShape, modifier = Modifier.testTag("overview_pin_scope"),
                        )
                        UnifiedCategoryFilterChipMenuDropdown(showDatabases, { showDatabases = false }, offset = DpOffset.Zero) {
                            UnifiedDatabaseFilterChipMenu(
                                selected = overviewScopeSelection(scope),
                                onSelect = { scope = it.overviewScope(); showDatabases = false },
                                keepassDatabases = keepassDatabases, mdbxDatabases = mdbxDatabases,
                                bitwardenVaults = bitwardenVaults, modifier = Modifier.testTag("overview_pin_database_menu"),
                            )
                        }
                    } else Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(overviewSourceIcon(scope), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(scopeName, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (candidates != null) Text(stringResource(R.string.vault_overview_picker_results, candidates.size),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("overview_pin_results"))
            }
            LazyColumn(
                state = listState, modifier = Modifier.fillMaxWidth().weight(1f).testTag("overview_pin_list"),
                contentPadding = PaddingValues(start = horizontalPadding, end = horizontalPadding, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(GroupedItemDefaults.Spacing),
            ) {
                if (copyAction == null && query.isBlank() && !imeVisible) item(key = "recommend", contentType = "recommend") {
                    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(GroupedItemDefaults.SingleShape)
                        .toggleable(value = recommend, role = Role.Switch, interactionSource = remember { MutableInteractionSource() },
                            indication = null, onValueChange = setRecommend)
                        .padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(R.string.vault_overview_recommend), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.vault_overview_recommend_hint), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(recommend, onCheckedChange = null, modifier = Modifier.testTag("overview_pin_recommend"))
                    }
                }
                when {
                    candidates == null -> item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(28.dp))
                        }
                    }
                    candidates.isEmpty() -> item(key = "empty") {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(if (source?.locked == true) Icons.Default.Lock else Icons.Default.SearchOff,
                                null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(if (source?.locked == true) R.string.vault_overview_locked_hint
                                else if (copyAction != null) R.string.no_results else R.string.vault_overview_empty_pins), style = MaterialTheme.typography.bodyMedium)
                            if (source?.locked != true) Text(stringResource(R.string.vault_overview_picker_empty_hint),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    else -> itemsIndexed(candidates, key = { _, row -> row.identity }, contentType = { _, _ -> "entry" }) { index, row ->
                        val checked = row.identity in selected
                        OverviewPickerRow(row, sourceByKey[row.source]?.name.takeIf { currentScope == "all" }, checked,
                            enabled = copyAction != null || checked || selected.size < maxPins,
                            shape = GroupedItemDefaults.shape(index, candidates.size), selection = copyAction == null,
                            onToggle = {
                                if (copyAction != null) { hideKeyboard(); copyAction.onSelect(row.item) }
                                else toggle(row.identity)
                            })
                    }
                }
            }
            if (copyAction == null) Surface(modifier = Modifier.testTag("overview_pin_footer"), shadowElevation = 0.dp,
                color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.vault_overview_picker_selected, selected.size), style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.testTag("overview_pin_selected_count"))
                        if (!cards || selected.size >= maxPins) Text(
                            stringResource(R.string.vault_overview_picker_limit, maxPins),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(onClick = { hideKeyboard(); onDismiss() }, modifier = Modifier.testTag("overview_pin_done")) {
                        Text(stringResource(R.string.vault_overview_done))
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewPickerRow(row: OverviewPickerEntry, sourceName: String?, checked: Boolean, enabled: Boolean,
    shape: Shape, selection: Boolean = true, onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = shape, color = if (checked) colors.primary.copy(alpha = 0.07f) else colors.surfaceContainerLowest) {
        Row(Modifier.fillMaxWidth().clip(shape)
            .then(if (selection) Modifier.toggleable(value = checked, enabled = enabled, role = Role.Checkbox,
                interactionSource = remember { MutableInteractionSource() }, indication = null, onValueChange = { onToggle() })
                else Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onToggle))
            .testTag("overview_pin_row_${row.item.key}").heightIn(min = 80.dp).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                val password = row.item.passwordEntry
                when {
                    row.cardBrand != null -> CardBrandIcon(row.cardBrand, colors.primary,
                        Modifier.size(width = 44.dp, height = 30.dp).testTag("overview_pin_brand_${row.item.key}")
                            .semantics { contentDescription = row.cardBrand.displayName })
                    password != null -> VaultItemIcon(password.website, password.title, password.appPackageName,
                        password.customIconType, password.customIconValue, Icons.Default.Lock,
                        modifier = Modifier.testTag("overview_pin_icon_${row.item.key}"),
                        keepassDatabaseId = password.keepassDatabaseId, keepassEntryUuid = password.keepassEntryUuid)
                    else -> {
                        val nativeIcon = takagi.ru.monica.ui.icons.rememberKeePassCustomIcon(row.item.keepassDatabaseId(), row.item.keepassEntryUuid())
                        if (nativeIcon != null) takagi.ru.monica.ui.icons.KeePassCustomIconImage(nativeIcon, Modifier.size(36.dp))
                        else Icon(row.item.type.icon(), null, tint = colors.primary, modifier = Modifier.size(26.dp))
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(row.item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(row.detail.ifBlank { stringResource(if (row.item.type == VaultV2ItemType.PASSWORD)
                        R.string.vault_overview_picker_no_account else row.item.type.titleRes()) },
                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (row.cardLast4.isNotEmpty()) Text("•••• ${row.cardLast4}", style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant, maxLines = 1)
                }
                if (sourceName != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(overviewSourceIcon(row.source), null, Modifier.size(12.dp), tint = colors.onSurfaceVariant)
                    Text(sourceName, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (selection) Surface(modifier = Modifier.size(24.dp), shape = CircleShape,
                color = if (checked) colors.primary else Color.Transparent,
                border = if (checked) null else BorderStroke(1.5.dp, colors.outlineVariant)) {
                if (checked) Icon(Icons.Default.Check, null, Modifier.padding(4.dp), tint = colors.onPrimary)
            }
        }
    }
}

@Composable
internal fun rememberOverviewPicker(items: List<VaultV2Item>, sources: List<VaultOverviewSource>, cards: Boolean?,
    priorityIdentities: List<String>, securityManager: SecurityManager): PreparedOverviewPicker? {
    val state = remember(items, sources, cards, priorityIdentities, securityManager) { mutableStateOf<PreparedOverviewPicker?>(null) }
    LaunchedEffect(state) {
        var prepared: PreparedOverviewPicker? = null
        try {
            withContext(Dispatchers.Default) {
                val context = currentCoroutineContext()
                // Assign inside the worker block: even cancellation at the return
                // dispatch cannot lose a newly allocated native handle.
                prepared = prepareOverviewPicker(items, sources, cards, priorityIdentities,
                    decrypt = securityManager::decryptDataIfMonicaCiphertext,
                    checkActive = { context.ensureActive() })
            }
            state.value = prepared
            awaitCancellation()
        } finally {
            withContext(NonCancellable + Dispatchers.Default) { prepared?.close() }
        }
    }
    return state.value
}

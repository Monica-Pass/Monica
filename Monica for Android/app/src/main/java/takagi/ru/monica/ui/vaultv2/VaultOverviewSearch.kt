package takagi.ru.monica.ui.vaultv2

import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import takagi.ru.monica.R
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.components.GroupedItemDefaults
import takagi.ru.monica.ui.gestures.SwipeActions
import takagi.ru.monica.ui.navigation.EASY_NOTES_SCALE_DURATION
import takagi.ru.monica.ui.navigation.MonicaPageContent
import takagi.ru.monica.ui.navigation.easyNotesScreenEnter

private class OverviewSearchResult(val rows: List<OverviewPickerEntry>) {
    val items = rows.map { it.item }
}

/** Owns one reusable Rust index for the active search; no work runs in animation callbacks. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VaultOverviewSearchResults(
    items: List<VaultV2Item>,
    sources: List<VaultOverviewSource>,
    currentScope: String,
    query: String,
    securityManager: SecurityManager,
    onOpenItem: (VaultV2Item) -> Unit,
    selection: VaultOverviewSelectionState,
    isDetailVisible: Boolean,
    onRequestDeleteItem: (VaultV2Item) -> Unit,
    reduceAnimations: Boolean,
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val selectionActive = selection.keys.isNotEmpty()
    LaunchedEffect(selectionActive) {
        if (selectionActive) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
    }
    val prepared = rememberOverviewPicker(items, sources, cards = null,
        priorityIdentities = emptyList(), securityManager = securityManager)
    // Changing a database or its lock state immediately drops the old results.
    // Typing can keep the last results visible until the latest query completes.
    val resultState = remember(prepared, currentScope) {
        mutableStateOf<OverviewSearchResult?>(null, referentialEqualityPolicy())
    }
    LaunchedEffect(prepared, query, currentScope) {
        val index = prepared ?: return@LaunchedEffect
        if (query.isNotBlank()) delay(80)
        resultState.value = withContext(Dispatchers.Default) { OverviewSearchResult(index.filter(query, currentScope)) }
    }
    LaunchedEffect(query, currentScope) { selection.clear() }
    val result = resultState.value
    LaunchedEffect(result) { selection.updateSearchResults(result?.items.orEmpty()) }
    DisposableEffect(selection) { onDispose { selection.exitSearch() } }
    val results = result?.rows
    Box(Modifier.fillMaxSize().imePadding().clipToBounds()) {
        // Disposing the lazy list also releases any outgoing item animation layers.
        key(prepared, currentScope) {
            if (results == null) {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                }
            } else {
                val lift = with(LocalDensity.current) { 24.dp.roundToPx() }
                // The index can finish after the keyboard appears. Animate the first batch
                // when it is ready, and keep this layer mounted while the query changes.
                MonicaPageContent(
                    pageKey = Unit,
                    modifier = Modifier.fillMaxSize(),
                    animateInitial = true,
                    animate = !reduceAnimations,
                    enter = easyNotesScreenEnter() +
                        slideInVertically(animationSpec = tween(EASY_NOTES_SCALE_DURATION)) { lift },
                ) {
                    OverviewSearchList(results, query, onOpenItem, selection, isDetailVisible, onRequestDeleteItem, reduceAnimations)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
private fun OverviewSearchList(
    results: List<OverviewPickerEntry>,
    query: String,
    onOpenItem: (VaultV2Item) -> Unit,
    selection: VaultOverviewSelectionState,
    isDetailVisible: Boolean,
    onRequestDeleteItem: (VaultV2Item) -> Unit,
    reduceAnimations: Boolean,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(query) {
        if (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0) listState.scrollToItem(0)
    }
    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val movement = MaterialTheme.motionScheme.fastSpatialSpec<IntOffset>()
    val fade = effects.takeUnless { reduceAnimations }
    val placement = movement.takeUnless { reduceAnimations }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag("overview_search_results"),
        contentPadding = PaddingValues(
            start = 20.dp, end = 20.dp, top = 6.dp,
            bottom = if (WindowInsets.isImeVisible) 16.dp else 116.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(GroupedItemDefaults.Spacing),
    ) {
        when {
            results.isEmpty() -> item(key = "empty") {
                Text(stringResource(R.string.no_results),
                    modifier = Modifier.animateItem(fade, placement, fade).fillMaxWidth().padding(vertical = 32.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> itemsIndexed(results, key = { _, row -> row.identity }, contentType = { _, _ -> "entry" }) { index, row ->
                val shape = GroupedItemDefaults.shape(index, results.size)
                val selecting = selection.keys.isNotEmpty()
                SwipeActions(
                    modifier = Modifier.animateItem(fade, placement, fade),
                    onSwipeLeft = { onRequestDeleteItem(row.item) },
                    onSwipeRight = { selection.toggleSearch(row.item.key) },
                    enabled = !isDetailVisible,
                    cardShape = shape,
                ) {
                    OverviewItemRow(
                        item = row.item, shape = shape,
                        selected = row.item.key in selection.keys, selectionMode = selecting,
                        onClick = { if (selecting) selection.toggleSearch(row.item.key) else onOpenItem(row.item) },
                        onLongClick = { selection.toggleSearch(row.item.key) },
                        rowTag = "vault_item_${row.item.key}",
                        subtitle = listOf(row.detail, row.cardLast4.takeIf(String::isNotEmpty)?.let { "•••• $it" }.orEmpty())
                            .filter(String::isNotBlank).joinToString(" · "),
                    )
                }
            }
        }
    }
}

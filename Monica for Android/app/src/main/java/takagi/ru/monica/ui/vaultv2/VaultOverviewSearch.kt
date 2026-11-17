package takagi.ru.monica.ui.vaultv2

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
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

private class OverviewSearchResult(val rows: List<OverviewPickerEntry>) {
    val items = rows.map { it.item }
}

/** One entrance per search opening, even when its first result arrives asynchronously. */
@Composable
internal fun rememberOverviewSearchEntrance(ready: Boolean, reduceAnimations: Boolean): Modifier {
    val progress = remember { Animatable(if (reduceAnimations) 1f else 0f) }
    LaunchedEffect(ready, reduceAnimations) {
        if (reduceAnimations) progress.snapTo(1f)
        else if (ready && progress.value < 1f) {
            // Animatable also follows the system's motion duration scale.
            progress.animateTo(1f, tween(220, easing = LinearOutSlowInEasing))
        }
    }
    return Modifier.graphicsLayer {
        // Keep loading visible and read frames only in the layer, away from index/list work.
        // No outgoing composition is retained when search closes or its source is revoked.
        val fraction = if (ready && !reduceAnimations) progress.value else 1f
        alpha = fraction
        translationY = (1f - fraction) * 24.dp.toPx()
    }
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
    listState: LazyListState,
    onOpenItem: (VaultV2Item) -> Unit,
    selection: VaultOverviewSelectionState,
    isDetailVisible: Boolean,
    onRequestDeleteItem: (VaultV2Item) -> Unit,
    reduceAnimations: Boolean,
    modifier: Modifier = Modifier,
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
    LaunchedEffect(query, currentScope) { selection.clear(); listState.scrollToItem(0) }
    val result = resultState.value
    LaunchedEffect(result) { selection.updateSearchResults(result?.items.orEmpty()) }
    DisposableEffect(selection) { onDispose { selection.exitSearch() } }
    val results = result?.rows
    val entrance = rememberOverviewSearchEntrance(results != null, reduceAnimations)
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().imePadding().then(entrance).testTag("overview_search_results"),
        contentPadding = PaddingValues(
            start = 20.dp, end = 20.dp, top = 6.dp,
            bottom = if (WindowInsets.isImeVisible) 16.dp else 116.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(GroupedItemDefaults.Spacing),
    ) {
        when {
            results == null -> item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                }
            }
            results.isEmpty() -> item(key = "empty") {
                Text(stringResource(R.string.no_results),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> itemsIndexed(results, key = { _, row -> row.identity }, contentType = { _, _ -> "entry" }) { index, row ->
                val shape = GroupedItemDefaults.shape(index, results.size)
                val selecting = selection.keys.isNotEmpty()
                SwipeActions(
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

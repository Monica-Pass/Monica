package takagi.ru.monica.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import takagi.ru.monica.data.PasswordListQuickFilterItem
import takagi.ru.monica.data.PasswordPageContentType

internal data class PasswordQuickFilterChipState(
    val favorite: Boolean,
    val twoFa: Boolean,
    val notes: Boolean,
    val passkey: Boolean,
    val boundNote: Boolean,
    val attachments: Boolean,
    val uncategorized: Boolean,
    val localOnly: Boolean,
    val manualStackOnly: Boolean,
    val neverStack: Boolean,
    val unstacked: Boolean,
    val aggregateSelectedTypes: Set<PasswordPageContentType>,
    val aggregateVisibleTypes: List<PasswordPageContentType>
)

internal data class PasswordQuickFilterChipCallbacks(
    val onFavoriteChange: (Boolean) -> Unit,
    val onTwoFaChange: (Boolean) -> Unit,
    val onNotesChange: (Boolean) -> Unit,
    val onPasskeyChange: (Boolean) -> Unit,
    val onBoundNoteChange: (Boolean) -> Unit,
    val onAttachmentsChange: (Boolean) -> Unit,
    val onUncategorizedChange: (Boolean) -> Unit,
    val onLocalOnlyChange: (Boolean) -> Unit,
    val onManualStackOnlyChange: (Boolean) -> Unit,
    val onNeverStackChange: (Boolean) -> Unit,
    val onUnstackedChange: (Boolean) -> Unit,
    val onToggleAggregateType: (PasswordPageContentType) -> Unit
)

internal data class PasswordQuickFilterEditGridParams(
    val items: List<PasswordListQuickFilterItem>,
    val measuredSizes: MutableMap<PasswordListQuickFilterItem, IntSize>,
    val availableWidth: Dp,
    val chipState: PasswordQuickFilterChipState,
    val chipCallbacks: PasswordQuickFilterChipCallbacks,
    val onOrderCommitted: (List<PasswordListQuickFilterItem>) -> Unit
)

internal fun mergeVisibleQuickFilterOrder(
    fullOrder: List<PasswordListQuickFilterItem>,
    reorderedVisibleItems: List<PasswordListQuickFilterItem>
): List<PasswordListQuickFilterItem> {
    if (reorderedVisibleItems.isEmpty()) return fullOrder
    val visibleSet = reorderedVisibleItems.toSet()
    val reordered = reorderedVisibleItems.iterator()
    return fullOrder.map { item ->
        if (item in visibleSet && reordered.hasNext()) reordered.next() else item
    }
}

@Composable
private fun PasswordQuickFilterEditItem(
    item: PasswordListQuickFilterItem,
    params: PasswordQuickFilterEditGridParams,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PasswordQuickFilterChipItem(
            item = item,
            categoryEditMode = true,
            quickFilterFavorite = params.chipState.favorite,
            onQuickFilterFavoriteChange = params.chipCallbacks.onFavoriteChange,
            quickFilter2fa = params.chipState.twoFa,
            onQuickFilter2faChange = params.chipCallbacks.onTwoFaChange,
            quickFilterNotes = params.chipState.notes,
            onQuickFilterNotesChange = params.chipCallbacks.onNotesChange,
            quickFilterPasskey = params.chipState.passkey,
            onQuickFilterPasskeyChange = params.chipCallbacks.onPasskeyChange,
            quickFilterBoundNote = params.chipState.boundNote,
            onQuickFilterBoundNoteChange = params.chipCallbacks.onBoundNoteChange,
            quickFilterAttachments = params.chipState.attachments,
            onQuickFilterAttachmentsChange = params.chipCallbacks.onAttachmentsChange,
            quickFilterUncategorized = params.chipState.uncategorized,
            onQuickFilterUncategorizedChange = params.chipCallbacks.onUncategorizedChange,
            quickFilterLocalOnly = params.chipState.localOnly,
            onQuickFilterLocalOnlyChange = params.chipCallbacks.onLocalOnlyChange,
            quickFilterManualStackOnly = params.chipState.manualStackOnly,
            onQuickFilterManualStackOnlyChange = params.chipCallbacks.onManualStackOnlyChange,
            quickFilterNeverStack = params.chipState.neverStack,
            onQuickFilterNeverStackChange = params.chipCallbacks.onNeverStackChange,
            quickFilterUnstacked = params.chipState.unstacked,
            onQuickFilterUnstackedChange = params.chipCallbacks.onUnstackedChange,
            aggregateSelectedTypes = params.chipState.aggregateSelectedTypes,
            aggregateVisibleTypes = params.chipState.aggregateVisibleTypes,
            onToggleAggregateType = params.chipCallbacks.onToggleAggregateType,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            imageVector = Icons.Default.DragHandle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
    }
}

@Composable
internal fun PasswordQuickFilterEditGrid(params: PasswordQuickFilterEditGridParams) {
    val visibleItems = remember(params.items, params.chipState.aggregateVisibleTypes) {
        params.items.filter { item ->
            shouldShowQuickFilterItem(item, params.chipState.aggregateVisibleTypes)
        }
    }
    var localOrder by remember { mutableStateOf(visibleItems) }
    val gridState = rememberLazyGridState()
    val reorderableState = rememberReorderableLazyGridState(gridState) { from, to ->
        localOrder = localOrder.toMutableList().apply {
            add(to.index, removeAt(from.index))
        }
    }
    var wasDragging by remember { mutableStateOf(false) }

    LaunchedEffect(visibleItems) {
        if (!reorderableState.isAnyItemDragging) localOrder = visibleItems
    }
    LaunchedEffect(reorderableState.isAnyItemDragging) {
        if (reorderableState.isAnyItemDragging) {
            wasDragging = true
        } else if (wasDragging) {
            wasDragging = false
            val mergedOrder = mergeVisibleQuickFilterOrder(params.items, localOrder)
            if (mergedOrder != params.items) {
                params.onOrderCommitted(mergedOrder)
            }
        }
    }

    val itemSpacing = 8.dp
    val rowCount = (localOrder.size + 1) / 2
    val contentHeight = (52.dp * rowCount) +
        (itemSpacing * (rowCount - 1).coerceAtLeast(0))

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = gridState,
        modifier = Modifier
            .width(params.availableWidth)
            .height(contentHeight),
        verticalArrangement = Arrangement.spacedBy(itemSpacing),
        horizontalArrangement = Arrangement.spacedBy(itemSpacing),
        userScrollEnabled = false
    ) {
        items(
            items = localOrder,
            key = { it.name }
        ) { item ->
            ReorderableItem(
                reorderableState,
                key = item.name
            ) { isDragging ->
                val elevation by animateDpAsState(
                    targetValue = if (isDragging) 8.dp else 0.dp,
                    label = "quick_filter_drag_elevation"
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { shadowElevation = elevation.toPx() }
                        .longPressDraggableHandle()
                ) {
                    PasswordQuickFilterEditItem(
                        item = item,
                        params = params
                    )
                }
            }
        }
    }
}

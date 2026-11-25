package takagi.ru.monica.ui.screens

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import takagi.ru.monica.R
import takagi.ru.monica.ui.components.MonicaTileGrid
import takagi.ru.monica.ui.haptic.rememberHapticFeedback

/** Database handles are dragged by identity, never by grid indexes (the count is a header). */
@Composable
internal fun <T> DatabaseManagementReorderableGrid(
    databases: List<T>,
    idOf: (T) -> Long,
    state: LazyGridState,
    testTag: String,
    onReorder: suspend (List<Long>) -> Unit,
    modifier: Modifier = Modifier,
    tile: @Composable (T, Modifier, Boolean) -> Unit,
) {
    var preferred by remember { mutableStateOf<List<Long>?>(null) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptic = rememberHapticFeedback()
    val latestSave by rememberUpdatedState(onReorder)
    val ids = databases.map(idOf)
    val lookup = databases.associateBy(idOf)
    val orderedIds = (preferred.orEmpty().filter { it in lookup } + ids).distinct()
    val ordered = orderedIds.mapNotNull(lookup::get)
    val latestIds by rememberUpdatedState(ids)
    val latestOrder by rememberUpdatedState(orderedIds)
    val reorder = rememberReorderableLazyGridState(state) { from, to ->
        val order = latestOrder.toMutableList()
        val source = order.indexOf(from.key)
        val target = order.indexOf(to.key)
        if (!saving && source >= 0 && target >= 0 && source != target) {
            order.add(target, order.removeAt(source))
            preferred = order
        }
    }
    LaunchedEffect(ids, reorder.isAnyItemDragging, saving) {
        if (!reorder.isAnyItemDragging && !saving && preferred == ids) preferred = null
    }
    fun saveOrder() {
        haptic.performSuccess()
        val order = latestOrder
        if (order == latestIds) return
        saving = true
        failed = false
        scope.launch {
            try { latestSave(order) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { preferred = null; failed = true }
            finally { saving = false }
        }
    }
    MonicaTileGrid(
        state = state,
        columns = GridCells.Adaptive(156.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
        modifier = modifier.testTag(testTag),
    ) {
        item(key = "database-count", span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Text(stringResource(R.string.mdbx_ui_database_count, databases.size),
                    Modifier.padding(start = 4.dp, top = 4.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (failed) Text(stringResource(R.string.save_failed), color = MaterialTheme.colorScheme.error)
            }
        }
        items(ordered, key = idOf, contentType = { "database" }) { database ->
            ReorderableItem(reorder, key = idOf(database), enabled = !saving) { dragging ->
                val elevation by animateDpAsState(if (dragging) 8.dp else 0.dp, label = "database_drag_elevation")
                tile(database, Modifier.graphicsLayer { shadowElevation = elevation.toPx() }
                    .longPressDraggableHandle(enabled = !saving,
                        onDragStarted = { haptic.performLongPress() }, onDragStopped = { saveOrder() }),
                    reorder.isAnyItemDragging)
            }
        }
    }
}

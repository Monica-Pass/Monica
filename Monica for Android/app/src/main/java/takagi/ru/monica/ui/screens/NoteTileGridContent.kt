package takagi.ru.monica.ui.screens

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.sync.SyncStatus
import takagi.ru.monica.notes.ui.model.NoteListItemUiModel
import takagi.ru.monica.ui.components.MonicaItemCard
import takagi.ru.monica.ui.components.MonicaTileGrid
import takagi.ru.monica.ui.components.SyncStatusIcon
import takagi.ru.monica.ui.haptic.rememberHapticFeedback
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Fixed height used by note tiles. A fixed height keeps both columns aligned
 * and prevents a long markdown note from changing the position of neighbours.
 */
internal val NoteTileCardHeight = 220.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NoteTileCard(
    note: NoteListItemUiModel,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dateFormatter = remember {
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    }
    val contentColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val secondaryContentColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val interactionModifier = if (isSelectionMode) {
        modifier.clickable(onClick = onClick)
    } else {
        modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        )
    }

    MonicaItemCard(
        isSelected = isSelected,
        modifier = interactionModifier
            .fillMaxWidth()
            .height(NoteTileCardHeight)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSelected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                            } else {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = null,
                        modifier = Modifier.size(19.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.width(9.dp))
                Text(
                    text = note.title.ifEmpty { stringResource(R.string.untitled) },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = contentColor,
                    modifier = Modifier.weight(1f)
                )
                if (note.hasImageAttachment) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = stringResource(R.string.note_has_image),
                        modifier = Modifier.size(17.dp),
                        tint = secondaryContentColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = note.previewText.ifBlank { stringResource(R.string.note_detail_empty_content) },
                style = MaterialTheme.typography.bodySmall,
                color = secondaryContentColor,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            if (note.tags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.height(22.dp)
                ) {
                    note.tags.take(2).forEach { tag ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer
                            }
                        ) {
                            Text(
                                text = "#$tag",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(7.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = dateFormatter.format(note.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = secondaryContentColor.copy(alpha = 0.82f),
                    maxLines = 1
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    note.syncStatus?.let { status: SyncStatus ->
                        SyncStatusIcon(status = status, size = 14.dp)
                    }
                    if (note.hasImageAttachment) {
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = stringResource(R.string.note_has_image),
                            modifier = Modifier.size(14.dp),
                            tint = secondaryContentColor
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = stringResource(R.string.encrypted_storage),
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                    )
                }
            }
        }
    }
}

/**
 * Render note tiles with the same reorderable grid primitive as the
 * authenticator tile layout. The full list is used when a search/tag filter is
 * active so hidden notes retain their slots and never get a duplicate order.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NoteTileGridContent(
    notes: List<NoteListItemUiModel>,
    allNotes: List<NoteListItemUiModel>,
    selectedNoteIds: Set<Long>,
    state: LazyGridState,
    onNoteClick: (Long) -> Unit,
    onNoteLongClick: (Long) -> Unit,
    onUpdateSortOrders: (List<Pair<Long, Int>>) -> Unit,
    modifier: Modifier = Modifier
) {
    val isSelectionMode = selectedNoteIds.isNotEmpty()
    val haptic = rememberHapticFeedback()
    val sourceNotes = if (allNotes.isNotEmpty()) allNotes else notes
    var localNotes by remember { mutableStateOf(notes) }
    var wasDragging by remember { mutableStateOf(false) }
    var dragBaseOrderIds by remember { mutableStateOf<List<Long>?>(null) }
    var pendingOrderIds by remember { mutableStateOf<List<Long>?>(null) }

    val reorderableState = rememberReorderableLazyGridState(state) { from, to ->
        if (isSelectionMode && localNotes.isNotEmpty()) {
            val fromIndex = from.index.coerceIn(localNotes.indices)
            val toIndex = to.index.coerceIn(localNotes.indices)
            if (fromIndex != toIndex) {
                localNotes = localNotes.toMutableList().apply {
                    add(toIndex, removeAt(fromIndex))
                }
            }
        }
    }

    val notesToken = System.identityHashCode(notes)
    val allNotesToken = System.identityHashCode(allNotes)
    val allIds = remember(allNotes) { allNotes.map { it.id } }
    val pendingOrderToken = pendingOrderIds?.let { System.identityHashCode(it) } ?: 0

    // Reconcile fresh Room emissions without replacing a settled local drag
    // with the old order for one frame.
    LaunchedEffect(
        notesToken,
        allNotesToken,
        allIds,
        reorderableState.isAnyItemDragging,
        pendingOrderToken
    ) {
        if (reorderableState.isAnyItemDragging) return@LaunchedEffect
        val preferredIds = pendingOrderIds ?: localNotes.map { it.id }
        val reconciledNotes = orderNoteItemsByIds(
            items = notes,
            orderedIds = preferredIds,
            idOf = NoteListItemUiModel::id
        )
        if (reconciledNotes != localNotes) {
            localNotes = reconciledNotes
        }
        val pending = pendingOrderIds
        if (pending != null) {
            val normalizedPending = normalizeNoteOrder(
                preferredIds = pending,
                currentIds = allIds
            )
            if (normalizedPending == allIds) {
                pendingOrderIds = null
            } else if (normalizedPending != pending) {
                pendingOrderIds = normalizedPending
            }
        }
    }

    LaunchedEffect(reorderableState.isAnyItemDragging) {
        if (reorderableState.isAnyItemDragging) {
            if (!wasDragging) {
                wasDragging = true
                dragBaseOrderIds = sourceNotes.map { it.id }
            }
        } else if (wasDragging) {
            val baseOrderIds = dragBaseOrderIds ?: sourceNotes.map { it.id }
            val mergedIds = mergeVisibleNoteOrder(
                allItemIds = baseOrderIds,
                reorderedVisibleItemIds = localNotes.map { it.id }
            )
            if (mergedIds != baseOrderIds) {
                // Publish the local order before Room emits its intermediate
                // snapshot, preventing a one-frame return to the old order.
                pendingOrderIds = mergedIds
                val updates = mergedIds.mapIndexed { index, id -> id to index }
                if (updates.isNotEmpty()) {
                    onUpdateSortOrders(updates)
                }
            }
            dragBaseOrderIds = null
            wasDragging = false
        }
    }

    MonicaTileGrid(
        state = state,
        modifier = modifier
    ) {
        items(
            items = localNotes,
            key = { it.id }
        ) { note ->
            ReorderableItem(
                reorderableState,
                key = note.id,
                enabled = isSelectionMode
            ) { isDragging ->
                val elevation by animateDpAsState(
                    targetValue = if (isDragging) 8.dp else 0.dp,
                    label = "note_tile_drag_elevation"
                )
                val dragModifier = if (isSelectionMode) {
                    Modifier.longPressDraggableHandle(
                        onDragStarted = { haptic.performLongPress() },
                        onDragStopped = { haptic.performSuccess() }
                    )
                } else {
                    Modifier
                }
                NoteTileCard(
                    note = note,
                    isSelectionMode = isSelectionMode,
                    isSelected = selectedNoteIds.contains(note.id),
                    onClick = { onNoteClick(note.id) },
                    onLongClick = { onNoteLongClick(note.id) },
                    modifier = Modifier
                        .graphicsLayer { shadowElevation = elevation.toPx() }
                        .then(dragModifier)
                )
            }
        }
    }
}

package takagi.ru.monica.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Note
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.bitwarden.sync.syncForUserVisibleRequest
import takagi.ru.monica.notes.ui.model.NoteListItemUiModel
import takagi.ru.monica.ui.components.PullActionVisualState
import takagi.ru.monica.ui.components.MonicaTileGrid
import takagi.ru.monica.ui.common.pull.calculateDampedPullOffset
import takagi.ru.monica.ui.common.state.InitialListRenderState
import takagi.ru.monica.ui.common.state.rememberSaveableLazyListState
import takagi.ru.monica.ui.common.state.rememberSaveableLazyGridState
import takagi.ru.monica.ui.common.state.resolveInitialListRenderState
import takagi.ru.monica.ui.haptic.rememberHapticFeedback

@Composable
fun NoteListContent(
    notes: List<NoteListItemUiModel>,
    isInitialLoading: Boolean,
    isGridLayout: Boolean,
    isSearchExpanded: Boolean,
    onRequestExpandSearch: () -> Unit,
    isBitwardenDatabaseView: Boolean,
    bitwardenRepository: BitwardenRepository,
    selectedNoteIds: Set<Long>,
    onNoteClick: (Long) -> Unit,
    onNoteLongClick: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val listState = rememberSaveableLazyListState()
    val gridState = rememberSaveableLazyGridState()
    var currentOffset by remember { mutableFloatStateOf(0f) }
    val searchTriggerDistance = remember(density, isBitwardenDatabaseView) {
        with(density) { (if (isBitwardenDatabaseView) 40.dp else 72.dp).toPx() }
    }
    val syncTriggerDistance = remember(density) { with(density) { 72.dp.toPx() } }
    val maxDragDistance = remember(density) { with(density) { 100.dp.toPx() } }
    val syncHoldMillis = 500L
    var isSettlingBack by remember { mutableStateOf(false) }
    var hasVibrated by remember { mutableStateOf(false) }
    var hasSyncStageVibrated by remember { mutableStateOf(false) }
    var syncHintArmed by remember { mutableStateOf(false) }
    var isBitwardenSyncing by remember { mutableStateOf(false) }
    var lockPullUntilSyncFinished by remember { mutableStateOf(false) }
    var canRunBitwardenSync by remember { mutableStateOf(false) }
    var showSyncFeedback by remember { mutableStateOf(false) }
    var syncFeedbackMessage by remember { mutableStateOf("") }
    var syncFeedbackIsSuccess by remember { mutableStateOf(false) }
    var canTriggerPullToSearch by remember { mutableStateOf(false) }
    val collapseAnimatable = remember { Animatable(0f) }
    val pullHaptic = rememberHapticFeedback()

    suspend fun resolveSyncableVaultId(): Long? {
        val activeVault = bitwardenRepository.getActiveVault() ?: run {
            canRunBitwardenSync = false
            return null
        }
        val unlocked = bitwardenRepository.isVaultUnlocked(activeVault.id)
        canRunBitwardenSync = unlocked
        return if (unlocked) activeVault.id else null
    }

    fun vibratePullThreshold(isSyncStage: Boolean) {
        pullHaptic.performPullThreshold(isSyncStage)
    }

    fun updatePullThresholdHaptics(oldOffset: Float, newOffset: Float) {
        if (oldOffset < searchTriggerDistance && newOffset >= searchTriggerDistance && !hasVibrated) {
            hasVibrated = true
            vibratePullThreshold(isSyncStage = false)
        } else if (newOffset < searchTriggerDistance) {
            hasVibrated = false
        }

        if (!isBitwardenDatabaseView) {
            hasSyncStageVibrated = false
            return
        }

        if (oldOffset < syncTriggerDistance && newOffset >= syncTriggerDistance && !hasSyncStageVibrated) {
            hasSyncStageVibrated = true
            vibratePullThreshold(isSyncStage = true)
        } else if (newOffset < syncTriggerDistance) {
            hasSyncStageVibrated = false
        }
    }

    fun interruptCollapseAnimation() {
        if (!collapseAnimatable.isRunning && !isSettlingBack) return
        isSettlingBack = false
        scope.launch {
            collapseAnimatable.stop()
            collapseAnimatable.snapTo(currentOffset)
        }
    }

    suspend fun collapsePullOffsetSmoothly() {
        if (currentOffset <= 0.5f) {
            currentOffset = 0f
            isSettlingBack = false
            return
        }
        if (collapseAnimatable.isRunning) return
        isSettlingBack = true
        collapseAnimatable.snapTo(currentOffset)
        try {
            collapseAnimatable.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = 140,
                    easing = androidx.compose.animation.core.FastOutLinearInEasing
                )
            ) {
                currentOffset = value
            }
        } finally {
            currentOffset = 0f
            collapseAnimatable.snapTo(0f)
            isSettlingBack = false
        }
    }

    fun onPullRelease(): Boolean {
        if (isBitwardenDatabaseView && syncHintArmed && !isBitwardenSyncing) {
            syncHintArmed = false
            isBitwardenSyncing = true
            lockPullUntilSyncFinished = true
            currentOffset = syncTriggerDistance
            scope.launch {
                val vaultId = resolveSyncableVaultId()
                if (vaultId == null) {
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.pull_sync_requires_bitwarden_login),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    isBitwardenSyncing = false
                    lockPullUntilSyncFinished = false
                    hasVibrated = false
                    hasSyncStageVibrated = false
                    collapsePullOffsetSmoothly()
                    return@launch
                }

                val syncResult = bitwardenRepository.syncForUserVisibleRequest(
                    vaultId = vaultId,
                    requestIdPrefix = "bw-note-list-vault"
                )
                when (syncResult) {
                    is BitwardenRepository.SyncResult.Success -> {
                        syncFeedbackIsSuccess = true
                        syncFeedbackMessage = context.getString(R.string.pull_sync_success)
                        showSyncFeedback = true
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.pull_sync_success),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    is BitwardenRepository.SyncResult.Error -> {
                        syncFeedbackIsSuccess = false
                        syncFeedbackMessage = context.getString(R.string.sync_status_failed_full)
                        showSyncFeedback = true
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.sync_status_failed_full) + ": " + syncResult.message,
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    is BitwardenRepository.SyncResult.EmptyVaultBlocked -> {
                        syncFeedbackIsSuccess = false
                        syncFeedbackMessage = context.getString(R.string.sync_status_failed_full)
                        showSyncFeedback = true
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.sync_status_failed_full) + ": " + syncResult.reason,
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                isBitwardenSyncing = false
                lockPullUntilSyncFinished = false
                hasVibrated = false
                hasSyncStageVibrated = false
                collapsePullOffsetSmoothly()
                delay(1400)
                showSyncFeedback = false
            }
            return true
        }

        if (currentOffset >= searchTriggerDistance) {
            onRequestExpandSearch()
            hasVibrated = false
        }
        return false
    }

    LaunchedEffect(isBitwardenDatabaseView) {
        if (isBitwardenDatabaseView) {
            resolveSyncableVaultId()
        } else {
            interruptCollapseAnimation()
            canRunBitwardenSync = false
            syncHintArmed = false
            isBitwardenSyncing = false
            lockPullUntilSyncFinished = false
            showSyncFeedback = false
            currentOffset = 0f
            hasVibrated = false
            hasSyncStageVibrated = false
        }
    }

    LaunchedEffect(currentOffset >= syncTriggerDistance, isBitwardenDatabaseView, isBitwardenSyncing) {
        if (isBitwardenDatabaseView && currentOffset >= syncTriggerDistance && !isBitwardenSyncing) {
            resolveSyncableVaultId()
        }
    }

    LaunchedEffect(currentOffset, isBitwardenDatabaseView, canRunBitwardenSync, isBitwardenSyncing) {
        if (isBitwardenDatabaseView && currentOffset >= syncTriggerDistance && canRunBitwardenSync && !isBitwardenSyncing) {
            delay(syncHoldMillis)
            if (isBitwardenDatabaseView && currentOffset >= syncTriggerDistance && canRunBitwardenSync && !isBitwardenSyncing) {
                syncHintArmed = true
            }
        } else {
            syncHintArmed = false
        }
    }

    val nestedScrollConnection = remember(isBitwardenDatabaseView) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (lockPullUntilSyncFinished) return available
                if (currentOffset > 0 && available.y < 0) {
                    interruptCollapseAnimation()
                    val newOffset = (currentOffset + available.y).coerceAtLeast(0f)
                    val consumed = currentOffset - newOffset
                    currentOffset = newOffset
                    return Offset(0f, -consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (lockPullUntilSyncFinished) return available
                if (!isSearchExpanded && available.y > 0 && canTriggerPullToSearch) {
                    if (source == NestedScrollSource.UserInput) {
                        interruptCollapseAnimation()
                        val newOffset = calculateDampedPullOffset(
                            currentOffset = currentOffset,
                            dragDelta = available.y,
                            maxDragDistance = maxDragDistance
                        )
                        val oldOffset = currentOffset
                        currentOffset = newOffset
                        updatePullThresholdHaptics(oldOffset = oldOffset, newOffset = newOffset)
                        return available
                    }
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                val syncStarted = onPullRelease()
                if (!syncStarted && !lockPullUntilSyncFinished) {
                    collapsePullOffsetSmoothly()
                }
                return Velocity.Zero
            }
        }
    }

    val emptyStateGestureModifier = Modifier
        .offset { IntOffset(0, currentOffset.toInt()) }
        .pointerInput(isSearchExpanded) {
            detectVerticalDragGestures(
                onVerticalDrag = { _, dragAmount ->
                    if (!isSearchExpanded && dragAmount > 0f) {
                        val newOffset = calculateDampedPullOffset(
                            currentOffset = currentOffset,
                            dragDelta = dragAmount,
                            maxDragDistance = maxDragDistance
                        )
                        val oldOffset = currentOffset
                        currentOffset = newOffset
                        updatePullThresholdHaptics(oldOffset = oldOffset, newOffset = newOffset)
                    }
                },
                onDragEnd = {
                    val syncStarted = onPullRelease()
                    if (!syncStarted && !lockPullUntilSyncFinished) {
                        scope.launch { collapsePullOffsetSmoothly() }
                    }
                },
                onDragCancel = {
                    if (!lockPullUntilSyncFinished) {
                        scope.launch { collapsePullOffsetSmoothly() }
                    }
                }
            )
        }

    val contentPullOffset = if (isBitwardenDatabaseView) {
        (currentOffset * 0.28f).toInt()
    } else {
        currentOffset.toInt()
    }
    val initialRenderState = resolveInitialListRenderState(
        isReady = !isInitialLoading,
        itemCount = notes.size,
    )

    Column(
        modifier = modifier
            .fillMaxSize()
    ) {
        when (initialRenderState) {
            InitialListRenderState.Loading -> {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.CircularProgressIndicator()
                }
            }
            InitialListRenderState.Empty -> {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .offset { IntOffset(0, contentPullOffset) }
                        .then(emptyStateGestureModifier),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = Icons.Default.Note,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.no_results),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            InitialListRenderState.Content -> {
                if (isGridLayout) {
                    MonicaTileGrid(
                        modifier = Modifier
                            .fillMaxSize()
                            .offset { IntOffset(0, contentPullOffset) }
                            .nestedScroll(nestedScrollConnection)
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    val isAtTop = gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0
                                    canTriggerPullToSearch = isAtTop
                                }
                            },
                        state = gridState
                    ) {
                        items(notes, key = { it.id }) { note ->
                            ExpressiveNoteCard(
                                note = note,
                                isSelected = selectedNoteIds.contains(note.id),
                                isGridMode = true,
                                onClick = { onNoteClick(note.id) },
                                onLongClick = { onNoteLongClick(note.id) }
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .offset { IntOffset(0, contentPullOffset) }
                            .nestedScroll(nestedScrollConnection)
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    val isAtTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
                                    canTriggerPullToSearch = isAtTop
                                }
                            },
                        state = listState
                    ) {
                        items(notes, key = { it.id }) { note ->
                            ExpressiveNoteCard(
                                note = note,
                                isSelected = selectedNoteIds.contains(note.id),
                                isGridMode = false,
                                onClick = { onNoteClick(note.id) },
                                onLongClick = { onNoteLongClick(note.id) }
                            )
                        }
                        item { Spacer(modifier = Modifier.height(80.dp)) }
                    }
                }
            }
        }
    }
}

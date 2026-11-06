package takagi.ru.monica.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlin.math.abs
import kotlin.math.roundToInt

private data class ExpressiveScrollbarMetrics(
    val progress: Float,
    val travelPx: Float,
)

private fun expressiveScrollbarMetrics(
    listState: LazyListState,
    tracker: ExpressiveScrollbarAxisTracker,
    availableHeightPx: Float,
    handleHeightPx: Float,
): ExpressiveScrollbarMetrics {
    val layout = listState.layoutInfo
    val visible = layout.visibleItemsInfo
    val totalItems = layout.totalItemsCount
    val travelPx = (availableHeightPx - handleHeightPx).coerceAtLeast(1f)
    if (visible.isEmpty() || totalItems == 0) {
        return ExpressiveScrollbarMetrics(0f, travelPx)
    }
    tracker.observe(layout)
    val viewportPx = (layout.viewportEndOffset - layout.viewportStartOffset).toFloat().coerceAtLeast(1f)
    val currentScrollPx = tracker.distanceBefore(listState.firstVisibleItemIndex) + listState.firstVisibleItemScrollOffset
    val lastIndex = totalItems - 1
    val totalScrollPx = (
        layout.beforeContentPadding + layout.afterContentPadding +
            tracker.distanceBefore(lastIndex) + tracker.itemSize(lastIndex) - viewportPx
        ).coerceAtLeast(1f)
    val progress = when {
        !listState.canScrollBackward -> 0f
        !listState.canScrollForward -> 1f
        else -> (currentScrollPx / totalScrollPx).coerceIn(0f, 1f)
    }
    return ExpressiveScrollbarMetrics(progress, travelPx)
}

@Composable
fun ExpressiveLazyListScrollbar(
    listState: LazyListState,
    modifier: Modifier = Modifier,
    labelForIndex: (Int) -> String? = { null },
    onInteractionChange: (Boolean) -> Unit = {},
    interactionEnabled: Boolean = true,
) {
    val canScroll by remember(listState) {
        derivedStateOf { listState.canScrollBackward || listState.canScrollForward }
    }
    if (!canScroll) return

    val density = LocalDensity.current
    val collapsedWidth = 7.dp
    val expandedWidth = 28.dp
    val handleHeight = 52.dp
    val endPadding = 4.dp
    val interactionWidth = expandedWidth + endPadding
    var isPressed by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }
    var dragTargetIndex by remember { mutableIntStateOf(-1) }
    var pendingTarget by remember(listState) { mutableStateOf<ExpressiveScrollbarTarget?>(null) }
    var retainedLabel by remember { mutableStateOf<String?>(null) }
    val metricsTracker = remember(listState) { ExpressiveScrollbarAxisTracker() }
    val displayedProgress = remember(listState) { Animatable(0f) }
    var hasSyncedDisplayedProgress by remember(listState) { mutableStateOf(false) }
    val interactionCallback by rememberUpdatedState(onInteractionChange)
    val currentLabelForIndex by rememberUpdatedState(labelForIndex)
    val currentInteractionEnabled by rememberUpdatedState(interactionEnabled)
    LaunchedEffect(interactionEnabled) {
        if (!interactionEnabled) {
            pendingTarget = null
            isPressed = false
            isDragging = false
            retainedLabel = null
        }
    }

    LaunchedEffect(isPressed, isDragging) {
        interactionCallback(isPressed || isDragging)
    }
    DisposableEffect(Unit) {
        onDispose { interactionCallback(false) }
    }

    val smoothJumpMinDistancePx = with(density) { 10.dp.toPx() }
    val displayedWidth by animateDpAsState(
        targetValue = if (isPressed || isDragging) expandedWidth else collapsedWidth,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "vaultScrollbarWidth",
    )
    val handleIconAlpha by animateFloatAsState(
        targetValue = if (isPressed || isDragging) 1f else 0f,
        animationSpec = tween(durationMillis = 160),
        label = "vaultScrollbarIcon",
    )
    val activeLabel by remember {
        derivedStateOf {
            if (isDragging && dragTargetIndex >= 0) currentLabelForIndex(dragTargetIndex) else null
        }
    }
    LaunchedEffect(activeLabel) {
        if (!activeLabel.isNullOrBlank()) retainedLabel = activeLabel
    }
    val labelAlpha by animateFloatAsState(
        targetValue = if (isDragging && !activeLabel.isNullOrBlank()) 1f else 0f,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "vaultScrollbarLabelAlpha",
    )
    val labelScale by animateFloatAsState(
        targetValue = if (isDragging && !activeLabel.isNullOrBlank()) 1f else 0.84f,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "vaultScrollbarLabelScale",
    )

    LaunchedEffect(listState, interactionEnabled) {
        if (!interactionEnabled) return@LaunchedEffect
        snapshotFlow { pendingTarget }
            .filterNotNull()
            .collect {
                // Coalesce pointer events into one position request per frame.
                withFrameNanos { }
                val target = pendingTarget ?: return@collect
                pendingTarget = null
                if (currentInteractionEnabled) listState.requestScrollToItem(target.index, target.offset)
            }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .width(interactionWidth),
    ) {
        val heightPx = with(density) { maxHeight.toPx() }
        val handleHeightPx = with(density) { handleHeight.toPx() }
        val travelPx = (heightPx - handleHeightPx).coerceAtLeast(1f)

        LaunchedEffect(listState, metricsTracker, heightPx, handleHeightPx, isDragging) {
            if (isDragging) return@LaunchedEffect
            snapshotFlow {
                expressiveScrollbarMetrics(listState, metricsTracker, heightPx, handleHeightPx)
            }
                .distinctUntilChanged()
                .collectLatest { metrics ->
                    if (!hasSyncedDisplayedProgress) {
                        displayedProgress.snapTo(metrics.progress)
                        hasSyncedDisplayedProgress = true
                    } else {
                        val deltaPx = abs(metrics.progress - displayedProgress.value) * metrics.travelPx
                        if (!listState.isScrollInProgress && deltaPx >= smoothJumpMinDistancePx) {
                            displayedProgress.animateTo(metrics.progress, tween(70, easing = FastOutSlowInEasing))
                        } else {
                            displayedProgress.snapTo(metrics.progress)
                        }
                    }
                }
        }

        LaunchedEffect(isDragging) {
            if (isDragging) {
                hasSyncedDisplayedProgress = true
                // Pointer position is consumed by layout/draw. Observing it here avoids
                // recomposing the scrollbar and restarting an effect for every move.
                snapshotFlow { dragProgress }.collect { progress ->
                    displayedProgress.snapTo(progress)
                }
            }
        }

        fun updateFromTouch(touchY: Float, grabOffset: Float, axis: ExpressiveScrollbarDragAxis) {
            val nextProgress = ((touchY - grabOffset) / travelPx).coerceIn(0f, 1f)
            dragProgress = nextProgress
            metricsTracker.observe(listState.layoutInfo)
            val target = axis.target(nextProgress, metricsTracker)
            dragTargetIndex = target.index
            pendingTarget = target
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(interactionEnabled) {
                    if (!interactionEnabled) return@pointerInput
                    detectTapGestures(
                        onPress = {
                            isPressed = true
                            try {
                                awaitRelease()
                            } finally {
                                isPressed = false
                            }
                        },
                    )
                }
                .pointerInput(listState, heightPx, handleHeightPx, interactionEnabled) {
                    if (!interactionEnabled) return@pointerInput
                    var grabOffset = handleHeightPx / 2f
                    var dragAxis: ExpressiveScrollbarDragAxis? = null
                    detectDragGestures(
                        onDragStart = { position ->
                            isDragging = true
                            dragProgress = displayedProgress.value
                            val currentTop = displayedProgress.value * travelPx
                            grabOffset = if (position.y in currentTop..(currentTop + handleHeightPx)) {
                                position.y - currentTop
                            } else {
                                handleHeightPx / 2f
                            }
                            val axis = metricsTracker.dragAxis(listState.layoutInfo)
                            dragAxis = axis
                            updateFromTouch(position.y, grabOffset, axis)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val axis = dragAxis?.takeIf { it.itemCount == listState.layoutInfo.totalItemsCount }
                                ?: metricsTracker.dragAxis(listState.layoutInfo).also { dragAxis = it }
                            updateFromTouch(change.position.y, grabOffset, axis)
                        },
                        onDragEnd = {
                            isDragging = false
                            dragAxis = null
                            // The final pointer event may still be waiting for its frame.
                        },
                        onDragCancel = {
                            isDragging = false
                            dragAxis = null
                            pendingTarget = null
                        },
                    )
                },
        ) {
            val indicatorPath = remember { Path() }
            val trackColor = MaterialTheme.colorScheme.secondaryContainer
            val indicatorColor = MaterialTheme.colorScheme.primary
            Canvas(modifier = Modifier.fillMaxSize()) {
                val endPaddingPx = endPadding.toPx()
                val collapsedWidthPx = collapsedWidth.toPx()
                val indicatorWidthPx = displayedWidth.toPx()
                val handleTop = (if (isDragging) dragProgress else displayedProgress.value) * travelPx
                val anchorX = size.width - endPaddingPx
                val trackX = anchorX - collapsedWidthPx / 2f
                val gapPx = 7.dp.toPx()

                if (handleTop > gapPx) {
                    drawLine(
                        color = trackColor,
                        start = Offset(trackX, 0f),
                        end = Offset(trackX, handleTop - gapPx),
                        strokeWidth = collapsedWidthPx,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    )
                }
                if (handleTop + handleHeightPx + gapPx < size.height) {
                    drawLine(
                        color = trackColor,
                        start = Offset(trackX, handleTop + handleHeightPx + gapPx),
                        end = Offset(trackX, size.height),
                        strokeWidth = collapsedWidthPx,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    )
                }

                val leftRadius = indicatorWidthPx / 2f
                val rightRadius = 6.dp.toPx().coerceAtMost(leftRadius)
                indicatorPath.reset()
                indicatorPath.addRoundRect(
                    RoundRect(
                        rect = Rect(
                            offset = Offset(anchorX - indicatorWidthPx, handleTop),
                            size = Size(indicatorWidthPx, handleHeightPx),
                        ),
                        topLeft = CornerRadius(leftRadius, leftRadius),
                        bottomLeft = CornerRadius(leftRadius, leftRadius),
                        topRight = CornerRadius(rightRadius, rightRadius),
                        bottomRight = CornerRadius(rightRadius, rightRadius),
                    ),
                )
                drawPath(indicatorPath, indicatorColor)
            }

            if (handleIconAlpha > 0f) {
                Icon(
                    imageVector = Icons.Rounded.UnfoldMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset {
                            val widthPx = with(density) { displayedWidth.toPx() }
                            IntOffset(
                                x = -with(density) { endPadding.toPx() }.roundToInt() -
                                    ((widthPx - with(density) { 24.dp.toPx() }) / 2f).roundToInt(),
                                y = ((if (isDragging) dragProgress else displayedProgress.value) * travelPx +
                                    (handleHeightPx - with(density) { 24.dp.toPx() }) / 2f).roundToInt(),
                            )
                        }
                        .size(24.dp)
                        .graphicsLayer {
                            alpha = handleIconAlpha
                            scaleX = handleIconAlpha
                            scaleY = handleIconAlpha
                        },
                )
            }

            val label = activeLabel ?: retainedLabel
            if (labelAlpha > 0f && !label.isNullOrBlank()) {
                val labelStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                val textMeasurer = rememberTextMeasurer()
                val textSize = remember(label, labelStyle, textMeasurer) {
                    textMeasurer.measure(label, labelStyle, maxLines = 1).size
                }
                val labelWidth = with(density) { textSize.width.toDp() + 24.dp }.coerceAtLeast(44.dp)
                val labelHeight = with(density) { textSize.height.toDp() + 12.dp }.coerceAtLeast(44.dp)
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset {
                            IntOffset(
                                x = -with(density) { (interactionWidth + 10.dp).toPx() }.roundToInt(),
                                y = ((if (isDragging) dragProgress else displayedProgress.value) * travelPx +
                                    (handleHeightPx - with(density) { labelHeight.toPx() }) / 2f)
                                    .coerceIn(0f, (heightPx - with(density) { labelHeight.toPx() }).coerceAtLeast(0f)).roundToInt(),
                            )
                        }
                        // The scrollbar's 32dp hit area must not constrain the floating label.
                        .wrapContentSize(Alignment.TopEnd, unbounded = true)
                        .size(labelWidth, labelHeight)
                        .testTag("vault_scrollbar_label")
                        .graphicsLayer {
                            alpha = labelAlpha
                            scaleX = labelScale
                            scaleY = labelScale
                        },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    tonalElevation = 4.dp,
                    shadowElevation = 2.dp,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = label,
                            style = labelStyle,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}

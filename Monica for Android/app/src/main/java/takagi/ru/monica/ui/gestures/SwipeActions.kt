package takagi.ru.monica.ui.gestures

import androidx.compose.animation.core.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import takagi.ru.monica.R
import takagi.ru.monica.ui.haptic.rememberHapticFeedback
import kotlin.math.abs

/**
 * 双向滑动组件 - 优化版
 * 
 * 特性：
 * - 平滑的圆角过渡
 * - 弹性回弹动画（Q弹效果）
 * - 60fps 流畅表现
 * - 自然的颜色过渡
 * - 自定义弹簧物理模型
 * - 防误触：滑动超过50%宽度才触发
 * 
 * 左滑：删除操作（红色背景）- 需超过50%卡片宽度
 * 右滑：选择操作（蓝色背景）- 需超过50%卡片宽度
 * 
 * @param onSwipeLeft 左滑回调（删除）
 * @param onSwipeRight 右滑回调（选择）
 * @param enabled 是否启用滑动
 * @param allowSwipeLeft 是否允许左滑
 * @param allowSwipeRight 是否允许右滑
 * @param content 内容
 */
@Composable
fun SwipeActions(
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit,
    isSwiped: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    allowSwipeLeft: Boolean = true,
    allowSwipeRight: Boolean = true,
    cardShape: Shape = RoundedCornerShape(16.dp),
    content: @Composable () -> Unit
) {
    // 使用非动画状态记录实时拖动偏移，避免高频创建协程
    var dragOffset by remember { mutableFloatStateOf(0f) }
    // 仅用于回弹动画的 Animatable
    val animatableOffset = remember { Animatable(0f) }
    
    // 追踪最新状态和回调，避免 pointerInput 协程持有旧的选择集合。
    val currentIsSwiped by rememberUpdatedState(isSwiped)
    val currentOnSwipeLeft by rememberUpdatedState(onSwipeLeft)
    val currentOnSwipeRight by rememberUpdatedState(onSwipeRight)
    
    // 监听 isSwiped 状态变化 (主要用于取消删除后的复位)
    LaunchedEffect(isSwiped, enabled, allowSwipeLeft, allowSwipeRight) {
        val total = dragOffset + animatableOffset.value
        val shouldResetForDisabledDirection =
            (!allowSwipeLeft && total < 0f) || (!allowSwipeRight && total > 0f)
        if (
            (!isSwiped || !enabled || shouldResetForDisabledDirection) &&
            (dragOffset != 0f || animatableOffset.value != 0f)
        ) {
            dragOffset = 0f
            animatableOffset.animateTo(0f, spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            ))
        }
    }

    val scope = rememberCoroutineScope()
    val swipeHaptic = rememberHapticFeedback()

    // 震动状态
    var hasVibratedLeft by remember { mutableStateOf(false) }
    var hasVibratedRight by remember { mutableStateOf(false) }
    
    // 卡片宽度
    var cardWidth by remember { mutableFloatStateOf(0f) }
    val maxSwipeDistance = 300f
    
    // The caller can supply the foreground card's exact shape so the swipe
    // background and clipping never expose a second corner treatment.
    val componentShape = cardShape
    
    // 弹性物理模型
    val springSpec = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium,
        visibilityThreshold = 0.2f
    )
    
    val quickSpringSpec = spring<Float>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessHigh,
        visibilityThreshold = 0.2f
    )
    
    // 计算当前显示的总偏移量
    val totalOffset = dragOffset + animatableOffset.value
    
    val swipeThreshold = remember(cardWidth) {
        if (cardWidth > 0f) cardWidth * 0.2f else 60f
    }
    
    // 背景透明度和图标缩放
    val backgroundAlpha = (abs(totalOffset) / swipeThreshold).coerceIn(0f, 1f)
    val iconScale = 0.8f + ((abs(totalOffset) / swipeThreshold).coerceIn(0f, 1.2f) * 0.4f)
    
    // 右滑遮罩透明度
    val cardTintAlpha = if (allowSwipeRight && totalOffset > 0) {
        (totalOffset / swipeThreshold).coerceIn(0f, 0.6f)
    } else {
        0f
    }
    
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(componentShape)
    ) {
        // 左侧背景
        if (allowSwipeRight && totalOffset > 0) {
            Surface(
                modifier = Modifier.fillMaxWidth().matchParentSize(),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = backgroundAlpha),
                shape = componentShape
            ) {
                Box(contentAlignment = Alignment.CenterStart) {
                    Row(
                        modifier = Modifier.padding(start = 24.dp).graphicsLayer {
                            translationX = (totalOffset * 0.3f).coerceIn(0f, 40f)
                            alpha = backgroundAlpha
                        },
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(24.dp).graphicsLayer { scaleX = iconScale; scaleY = iconScale })
                        Text(stringResource(R.string.swipe_action_select), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
        }
        
        // 右侧背景
        if (allowSwipeLeft && totalOffset < 0) {
            Surface(
                modifier = Modifier.fillMaxWidth().matchParentSize(),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = backgroundAlpha),
                shape = componentShape
            ) {
                Box(contentAlignment = Alignment.CenterEnd) {
                    Row(
                        modifier = Modifier.padding(end = 24.dp).graphicsLayer {
                            translationX = (totalOffset * 0.3f).coerceIn(-40f, 0f)
                            alpha = backgroundAlpha
                        },
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(24.dp).graphicsLayer { scaleX = iconScale; scaleY = iconScale })
                        Text(stringResource(R.string.swipe_action_delete), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }
        
        // 前景内容
        Box(modifier = Modifier.fillMaxWidth()) {
            if (cardTintAlpha > 0f) {
                Surface(
                    modifier = Modifier.fillMaxSize().graphicsLayer { translationX = totalOffset; alpha = cardTintAlpha },
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = componentShape
                ) {}
            }
            
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        translationX = totalOffset
                        shadowElevation = (abs(totalOffset) / 100f).coerceIn(0f, 8f)
                        shape = componentShape
                        clip = true
                    }
                    .pointerInput(enabled, allowSwipeLeft, allowSwipeRight) {
                        if (!enabled || (!allowSwipeLeft && !allowSwipeRight)) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val touchSlop = viewConfiguration.touchSlop
                            val horizontalTouchSlop = touchSlop * 1.35f
                            var pointerId = down.id
                            var horizontalLocked = false

                            while (!horizontalLocked) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == pointerId }
                                    ?: event.changes.firstOrNull()
                                    ?: return@awaitEachGesture
                                pointerId = change.id

                                if (!change.pressed) {
                                    return@awaitEachGesture
                                }

                                val drag = change.position - down.position
                                val absX = abs(drag.x)
                                val absY = abs(drag.y)

                                if (absY > touchSlop && absY > absX * 0.8f) {
                                    return@awaitEachGesture
                                }

                                if (absX > horizontalTouchSlop && absX > absY * 1.5f) {
                                    if ((drag.x < 0f && !allowSwipeLeft) || (drag.x > 0f && !allowSwipeRight)) {
                                        return@awaitEachGesture
                                    }
                                    horizontalLocked = true
                                    change.consume()
                                }
                            }

                            if (cardWidth == 0f) cardWidth = size.width.toFloat()
                            hasVibratedLeft = false
                            hasVibratedRight = false

                            var wasCancelled = false

                            var dragEnded = false
                            while (!dragEnded) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == pointerId }
                                    ?: event.changes.firstOrNull()
                                if (change == null) {
                                    wasCancelled = true
                                    dragEnded = true
                                } else if (!change.pressed) {
                                    dragEnded = true
                                } else {
                                    val dragAmount = change.positionChange().x
                                    if (dragAmount != 0f) {
                                        change.consume()
                                    }

                                    val current = dragOffset
                                    val resistance = when {
                                        abs(current) > maxSwipeDistance -> 0.1f
                                        abs(current) > maxSwipeDistance * 0.8f -> 0.5f
                                        else -> 1f
                                    }
                                    val minOffset = if (allowSwipeLeft) -maxSwipeDistance * 1.2f else 0f
                                    val maxOffset = if (allowSwipeRight) maxSwipeDistance * 1.2f else 0f
                                    dragOffset = (current + dragAmount * resistance)
                                        .coerceIn(minOffset, maxOffset)

                                    val dynamicThreshold = cardWidth * 0.2f
                                    if (allowSwipeRight && dragOffset > dynamicThreshold && !hasVibratedRight) {
                                        hasVibratedRight = true
                                        swipeHaptic.performPullThreshold()
                                    } else if (allowSwipeLeft && dragOffset < -dynamicThreshold && !hasVibratedLeft) {
                                        hasVibratedLeft = true
                                        swipeHaptic.performPullThreshold()
                                    } else if (abs(dragOffset) < dynamicThreshold) {
                                        hasVibratedRight = false
                                        hasVibratedLeft = false
                                    }
                                }
                            }

                            if (wasCancelled) {
                                scope.launch {
                                    animatableOffset.snapTo(dragOffset)
                                    dragOffset = 0f
                                    hasVibratedLeft = false
                                    hasVibratedRight = false
                                    animatableOffset.animateTo(0f, quickSpringSpec)
                                }
                            } else {
                                scope.launch {
                                    animatableOffset.snapTo(dragOffset)
                                    dragOffset = 0f
                                    hasVibratedLeft = false
                                    hasVibratedRight = false

                                    val dynamicThreshold = cardWidth * 0.2f
                                    if (allowSwipeLeft && animatableOffset.value < -dynamicThreshold) {
                                        currentOnSwipeLeft()
                                        animatableOffset.animateTo(-cardWidth, tween(300, easing = FastOutSlowInEasing))
                                        if (!currentIsSwiped) {
                                            animatableOffset.animateTo(0f, springSpec)
                                        }
                                    } else if (allowSwipeRight && animatableOffset.value > dynamicThreshold) {
                                        animatableOffset.animateTo(0f, springSpec)
                                        currentOnSwipeRight()
                                    } else {
                                        animatableOffset.animateTo(0f, quickSpringSpec)
                                    }
                                }
                            }
                        }
                    },
                shape = componentShape
            ) {
                content()
            }
        }
    }
}

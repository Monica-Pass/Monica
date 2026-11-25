package takagi.ru.monica.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

val LocalAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * 减少动画模式 - 用于解决部分设备（如 HyperOS 2/Android 15）的动画卡顿问题
 * 当为 true 时，禁用共享元素动画，使用简单的淡入淡出效果
 */
val LocalReduceAnimations = staticCompositionLocalOf { false }

/**
 * 触觉反馈总开关。为 false 时，全应用不再触发任何振动或 View 层触觉反馈。
 * VIBRATE 属于普通权限、无法在系统设置中撤销，因此必须由应用自身提供关闭入口。
 */
val LocalHapticFeedbackEnabled = staticCompositionLocalOf { true }

/** 1 = light (default), 2 = strong; the total switch handles off. */
val LocalHapticFeedbackStrength = staticCompositionLocalOf { 1 }

/*
 * Adapted from ImageToolbox FancySlider.kt.
 * Copyright (c) 2026 T8RIN (Malik Mukhametzyanov)
 * Licensed under the Apache License, Version 2.0.
 * https://www.apache.org/licenses/LICENSE-2.0
 * Distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 *
 * Modified for Monica, 2026-11-25: use the standard Material3 interaction engine,
 * distance-based wheel rotation, retained touch targets, app/system preferences,
 * and throttled user-action haptics. See assets/licenses/ImageToolbox.txt.
 */
package takagi.ru.monica.ui.components

import android.animation.ValueAnimator
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.PI
import kotlin.math.roundToInt
import takagi.ru.monica.ui.haptic.rememberHapticFeedback

/** ImageToolbox's filled track and rolling thumb, with Material's input and semantics. */
@Composable
fun MonicaSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    colors: SliderColors = SliderDefaults.colors(
        activeTrackColor = MaterialTheme.colorScheme.primaryContainer,
        inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        thumbColor = MaterialTheme.colorScheme.onPrimaryContainer,
        activeTickColor = MaterialTheme.colorScheme.onPrimaryContainer,
        inactiveTickColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val state = remember(steps, valueRange) { SliderState(value, steps, valueRange) }
    state.value = value
    val haptic = rememberHapticFeedback()
    val ticks = remember(steps, valueRange) { SliderTickGate(value, valueRange, steps) }
    // Reloading a setting or changing it elsewhere never produces a vibration.
    SideEffect { ticks.synchronize(value) }
    val layoutDirection = LocalLayoutDirection.current
    val motion = LocalExpansionAnimationsEnabled.current && ValueAnimator.areAnimatorsEnabled()
    var trackWidth by remember { mutableIntStateOf(0) }
    val thumbDiameter = with(LocalDensity.current) { 26.dp.toPx() }
    val span = valueRange.endInclusive - valueRange.start
    val fraction = if (span > 0f) ((value - valueRange.start) / span).coerceIn(0f, 1f) else 0f

    Slider(
        state = state,
        onValueChange = { next ->
            if (enabled) {
                if (ticks.onUserValue(next, SystemClock.uptimeMillis())) haptic.performSliderTick()
                onValueChange(next)
            }
        },
        modifier = modifier.heightIn(min = 48.dp).padding(horizontal = 6.dp),
        enabled = enabled,
        onValueChangeFinished = onValueChangeFinished,
        colors = colors,
        interactionSource = interactionSource,
        thumb = {
            Spacer(
                Modifier
                    .zIndex(1f)
                    .size(26.dp)
                    .graphicsLayer {
                        val direction = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
                        rotationZ = if (motion) {
                            direction * fraction * trackWidth / (PI.toFloat() * thumbDiameter) * 360f
                        } else 0f
                    }
                    .indication(interactionSource, ripple(bounded = false, radius = 24.dp))
                    .hoverable(interactionSource, enabled)
                    .background(if (enabled) colors.thumbColor else colors.disabledThumbColor, SliderWheelShape)
            )
        },
        track = {
            Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxWidth().height(38.dp).onSizeChanged { trackWidth = it.width }) {
                val active = if (enabled) colors.activeTrackColor else colors.disabledActiveTrackColor
                val inactive = if (enabled) colors.inactiveTrackColor else colors.disabledInactiveTrackColor
                val activeTick = if (enabled) colors.activeTickColor else colors.disabledActiveTickColor
                val inactiveTick = if (enabled) colors.inactiveTickColor else colors.disabledInactiveTickColor
                val isRtl = layoutDirection == LayoutDirection.Rtl
                val cap = size.height / 2f
                // Extend rounded caps beyond the thumb centres, as in FancyTrack.
                drawRoundRect(inactive, Offset(-cap, 0f), Size(size.width + cap * 2, size.height), CornerRadius(cap))
                val left = if (isRtl) size.width * (1f - fraction) - cap else -cap
                drawRoundRect(active, Offset(left, 0f), Size(size.width * fraction + cap * 2, size.height), CornerRadius(cap))
                if (steps > 0) {
                    // Keep dense integer ranges readable without drawing thousands of points.
                    val intervals = (steps + 1).coerceAtMost(100)
                    for (index in 0..intervals) {
                        val tick = index.toFloat() / intervals
                        val visual = if (isRtl) 1f - tick else tick
                        drawCircle(if (tick <= fraction) activeTick else inactiveTick,
                            radius = 1.dp.toPx(), center = Offset(size.width * visual, center.y))
                    }
                }
            }
            }
        },
    )
}

/** One tick per crossed detent, at most once every 32 ms; no queued catch-up buzz. */
internal class SliderTickGate(
    initialValue: Float,
    private val range: ClosedFloatingPointRange<Float>,
    steps: Int,
) {
    private val intervals = if (steps > 0) steps + 1 else 100
    private fun bucket(value: Float): Int {
        val span = range.endInclusive - range.start
        return if (span > 0f) (((value - range.start) / span).coerceIn(0f, 1f) * intervals).roundToInt() else 0
    }
    private var previous = bucket(initialValue)
    private var lastTick: Long? = null
    fun synchronize(value: Float) { previous = bucket(value) }
    fun onUserValue(value: Float, now: Long): Boolean {
        val next = bucket(value)
        val changed = next != previous
        previous = next
        if (!changed || lastTick?.let { now - it < 32L } == true) return false
        lastTick = now
        return true
    }
}

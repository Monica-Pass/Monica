package takagi.ru.monica.ui.haptic

import androidx.compose.foundation.Indication
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import takagi.ru.monica.ui.LocalHapticFeedbackEnabled

/** Feedback belongs to the user action, never to an observed/reloaded checked state. */
@Composable
internal fun switchCheckedChange(
    checked: Boolean,
    enabled: Boolean = true,
    feedbackOnEnable: Boolean = false,
    onCheckedChange: (Boolean) -> Unit,
): (Boolean) -> Unit {
    val context = LocalContext.current
    val view = LocalView.current
    val feedbackEnabled = LocalHapticFeedbackEnabled.current
    val strength = takagi.ru.monica.ui.LocalHapticFeedbackStrength.current
    val haptic = remember(context, view, strength) { HapticFeedbackHelper(context, view, strength = strength) }
    return { next ->
        if (enabled && next != checked) {
            onCheckedChange(next)
            // Only the feedback preference itself may acknowledge enabling feedback.
            if (if (feedbackOnEnable) next else feedbackEnabled) haptic.performSwitchToggle()
        }
    }
}

/** Retains the existing clickable row and child switch hit targets. */
@Composable
internal fun switchClick(
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
): () -> Unit {
    val toggle = switchCheckedChange(checked, enabled, onCheckedChange = onCheckedChange)
    return { toggle(!checked) }
}

/** Material 3 visuals/semantics, with one light click per user toggle. */
@Composable
fun MonicaSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    thumbContent: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    colors: SwitchColors = SwitchDefaults.colors(),
    interactionSource: MutableInteractionSource? = null,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange?.let {
            switchCheckedChange(checked, enabled, onCheckedChange = it)
        },
        modifier = modifier,
        thumbContent = thumbContent,
        enabled = enabled,
        colors = colors,
        interactionSource = interactionSource,
    )
}

/** The row owns interaction; its trailing MonicaSwitch must have a null callback. */
@Composable
fun Modifier.hapticSwitchToggleable(
    value: Boolean,
    enabled: Boolean = true,
    role: Role? = Role.Switch,
    interactionSource: MutableInteractionSource? = null,
    indication: Indication? = LocalIndication.current,
    feedbackOnEnable: Boolean = false,
    onValueChange: (Boolean) -> Unit,
): Modifier = toggleable(
    value = value,
    enabled = enabled,
    role = role,
    interactionSource = interactionSource,
    indication = indication,
    onValueChange = switchCheckedChange(value, enabled, feedbackOnEnable, onValueChange),
)

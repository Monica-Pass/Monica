package takagi.ru.monica.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import takagi.ru.monica.R
import takagi.ru.monica.ui.LocalHapticFeedbackEnabled
import takagi.ru.monica.ui.haptic.HapticFeedbackHelper
import takagi.ru.monica.ui.haptic.InteractionHapticResult
import takagi.ru.monica.ui.screens.Setting
import takagi.ru.monica.ui.screens.settingsSearchAnchor

/** The total switch remains independent; 0 on this slider disables it atomically. */
@Composable
fun HapticStrengthControl(
    enabled: Boolean,
    strength: Int,
    onStrengthChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val setting = Setting.HAPTIC_STRENGTH
    val context = LocalContext.current
    val view = LocalView.current
    val selected = if (enabled) strength.coerceIn(1, 2) else 0
    var draft by remember(selected) { mutableIntStateOf(selected) }
    var result by remember(selected) { mutableStateOf<InteractionHapticResult?>(null) }
    fun preview(level: Int) {
        result = HapticFeedbackHelper(context, view, enabled = level > 0, strength = level)
            .performSwitchToggle()
    }
    Column(modifier.fillMaxWidth().animateContentSize(animationSpec =
        if (LocalExpansionAnimationsEnabled.current) spring() else snap()).settingsSearchAnchor(setting.title()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(setting.title(), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text(draft.toString(), style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary)
        }
        Text(setting.summary(), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Preview the newly chosen strength once; avoid the slider's generic tick as well.
        CompositionLocalProvider(LocalHapticFeedbackEnabled provides false) {
            MonicaSlider(value = draft.toFloat(), onValueChange = {
                val next = it.roundToInt().coerceIn(0, 2)
                if (next != draft) { draft = next; preview(next) }
            }, valueRange = 0f..2f, steps = 1,
                onValueChangeFinished = { onStrengthChange(draft) },
                modifier = Modifier.testTag("haptic_strength_slider"))
        }
        FilledTonalButton(onClick = { preview(selected) }, enabled = enabled,
            modifier = Modifier.testTag("haptic_test")) {
            Text(stringResource(R.string.haptic_test))
        }
        val message = when (result) {
            InteractionHapticResult.SYSTEM_DISABLED -> R.string.haptic_system_disabled
            InteractionHapticResult.UNAVAILABLE -> R.string.haptic_unavailable
            else -> null
        }
        if (message != null) Text(stringResource(message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

package takagi.ru.monica.ui.components

import takagi.ru.monica.ui.haptic.hapticSwitchToggleable

import takagi.ru.monica.ui.haptic.MonicaSwitch as Switch

import takagi.ru.monica.ui.screens.Setting
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import takagi.ru.monica.ui.haptic.rememberHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import sh.calvin.reorderable.ReorderableColumn
import takagi.ru.monica.R
import takagi.ru.monica.ui.screens.settingsSearchAnchor
import takagi.ru.monica.ui.screens.settingsSectionItemShape

@Composable
internal fun CustomizationPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(topBar = { SettingsSubpageTopBar(title, onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp).padding(bottom = 24.dp), content = content)
    }
}

@Composable
internal fun CustomizationSection(setting: Setting, subtitle: String = setting.summary(), anchor: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val title = setting.title()
    Column(Modifier.fillMaxWidth().then(if (anchor) Modifier.settingsSearchAnchor(title) else Modifier)) {
        SettingsSubpageHeading(title)
        if (subtitle.isNotBlank()) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
    }
}

@Composable
internal fun CustomizationPreview(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp).testTag("customization_preview")) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        Surface(shape = settingsSectionItemShape(0, 1), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }
}

@Composable
internal fun CustomizationToggle(setting: Setting, subtitle: String = setting.summary(), checked: Boolean,
    onCheckedChange: (Boolean) -> Unit, index: Int = 0, count: Int = 1,
    icon: ImageVector = Icons.Default.Tune, enabled: Boolean = true) {
    val title = setting.title()
    val shape = settingsSectionItemShape(index, count)
    Surface(Modifier.fillMaxWidth().settingsSearchAnchor(title).clip(shape)
        .hapticSwitchToggleable(value = checked, enabled = enabled, role = Role.Switch, feedbackOnEnable = setting == Setting.HAPTIC_FEEDBACK, onValueChange = onCheckedChange),
        shape = shape, color = MaterialTheme.colorScheme.surfaceContainer) {
        BoxWithConstraints(Modifier.padding(16.dp)) {
            val stack = maxWidth < 280.dp || LocalDensity.current.fontScale > 1.3f
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(icon, null, Modifier.size(24.dp), tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                    if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    if (stack) Switch(checked, null, enabled = enabled, modifier = Modifier.align(Alignment.End).padding(top = 8.dp))
                }
                if (!stack) Switch(checked, null, enabled = enabled)
            }
        }
    }
}

@Composable
internal fun CustomizationChoice(setting: Setting, title: String, subtitle: String = "", selected: Boolean,
    onClick: () -> Unit, index: Int, count: Int, modifier: Modifier = Modifier) {
    val shape = settingsSectionItemShape(index, count)
    Surface(modifier.testTag("setting_choice_${setting.name}").fillMaxWidth().clip(shape).selectable(selected, role = Role.RadioButton, onClick = onClick),
        shape = shape, color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RadioButton(selected, null)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
internal fun CustomizationDisclosure(setting: Setting, subtitle: String = setting.summary(), expanded: Boolean,
    onExpand: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val title = setting.title()
    val searchFocus = takagi.ru.monica.ui.screens.LocalSettingsSearchNavigation.current?.focusTitleRes ?: 0
    LaunchedEffect(searchFocus) {
        if (searchFocus != 0 && !expanded) onExpand()
    }
    Column(Modifier.fillMaxWidth().padding(top = 20.dp).settingsSearchAnchor(title),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Surface(shape = settingsSectionItemShape(0, if (expanded) 2 else 1), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onExpand).padding(16.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.align(Alignment.End).size(24.dp))
            }
        }
        if (expanded) Column(verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
    }
}

/** Natural-height rows share one scroll container. Reordering never changes the enabled set. */
@Composable
internal fun <T> CustomizationOrderGroup(setting: Setting, items: List<T>, selected: List<T>,
    label: @Composable (T) -> String, icon: (T) -> ImageVector,
    onOrder: (List<T>) -> Unit, onToggle: (T, Boolean) -> Unit, required: (T) -> Boolean = { false }) {
    var sourceIndex by remember { mutableIntStateOf(-1) }
    var targetIndex by remember { mutableIntStateOf(-1) }
    val heights = remember(items.size) { mutableMapOf<T, Int>() }
    val haptics = rememberHapticFeedback()
    val upLabel = stringResource(R.string.move_up)
    val downLabel = stringResource(R.string.move_down)
    fun move(from: Int, to: Int) {
        if (from != to && from in items.indices && to in items.indices) {
            onOrder(items.toMutableList().apply { add(to, removeAt(from)) })
        }
    }
    ReorderableColumn(list = items, onSettle = ::move, onMove = { haptics.performLightClick() },
        modifier = Modifier.testTag("setting_order_${setting.name}").pointerInput(items) {
            awaitPointerEventScope {
                var downY = 0f
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull() ?: continue
                    if (change.pressed && !change.previousPressed) downY = change.position.y
                    if (change.pressed && sourceIndex >= 0) {
                        targetIndex = directReorderTarget(sourceIndex, change.position.y - downY,
                            items.map { heights[it] ?: 0 }, 2.dp.toPx())
                    }
                }
            }
        }, verticalArrangement = Arrangement.spacedBy(2.dp)) { index, item, dragging ->
        val title = label(item)
        val checked = item in selected
        val shape = directReorderShape(directReorderVisualIndex(index, sourceIndex, targetIndex),
            items.size, targetIndex.takeIf { sourceIndex >= 0 })
        ReorderableItem {
            Surface(shape = shape,
                shadowElevation = if (dragging) 3.dp else 0.dp,
                color = if (dragging) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth().testTag("customization_order_$item")
                    .onSizeChanged { heights[item] = it.height }
                    .longPressDraggableHandle(
                        onDragStarted = { sourceIndex = index; targetIndex = index; haptics.performLongPress() },
                        onDragStopped = { sourceIndex = -1; targetIndex = -1; haptics.performLightClick() })
                    .semantics { customActions = buildList {
                        if (index > 0) add(CustomAccessibilityAction(upLabel) { move(index, index - 1); true })
                        if (index < items.lastIndex) add(CustomAccessibilityAction(downLabel) { move(index, index + 1); true })
                    } }) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(icon(item), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.bodyLarge)
                        Text(if (required(item)) stringResource(R.string.add_button_password_required)
                            else if (checked) "${selected.indexOf(item) + 1}" else stringResource(R.string.hidden),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked, { onToggle(item, it) }, enabled = !required(item),
                        modifier = Modifier.testTag("customization_toggle_$item"))
                }
            }
        }
    }
}

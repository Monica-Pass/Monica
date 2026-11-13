package takagi.ru.monica.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import takagi.ru.monica.ui.screens.settingsSearchAnchor

/** Natural-height rows: the outer clip supplies large corners, each row supplies small inner corners. */
@Composable
internal fun SettingsPanelGroup(title: String, modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title.isNotBlank()) Text(title, Modifier.padding(start = 12.dp, top = 8.dp),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)),
            verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
    }
}

@Composable
internal fun Modifier.settingsPanelSurface(): Modifier = fillMaxWidth()
    .clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceContainer)

@Composable
internal fun SettingsPanelRow(icon: ImageVector, title: String, subtitle: String,
    onClick: (() -> Unit)? = null, enabled: Boolean = true,
    checked: Boolean? = null, onCheckedChange: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val interaction = if (checked != null && onCheckedChange != null)
        Modifier.toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
    else if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    else Modifier
    Row(modifier.settingsPanelSurface().settingsSearchAnchor(title).then(interaction)
        .heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else .38f))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .38f))
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when {
            checked != null -> Switch(checked, onCheckedChange = null, enabled = enabled)
            trailing != null -> trailing()
            onClick != null -> Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

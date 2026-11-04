package takagi.ru.monica.bitwarden.ui

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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

internal fun bitwardenGroupShape(first: Boolean = true, last: Boolean = true) = RoundedCornerShape(
    topStart = if (first) 24.dp else 4.dp, topEnd = if (first) 24.dp else 4.dp,
    bottomStart = if (last) 24.dp else 4.dp, bottomEnd = if (last) 24.dp else 4.dp
)

@Composable
internal fun BitwardenSectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
}

/** Flexible rows keep labels and supporting text readable at large system font sizes. */
@Composable
internal fun BitwardenSettingRow(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    first: Boolean = true,
    last: Boolean = true,
    enabled: Boolean = true,
    destructive: Boolean = false,
    checked: Boolean? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val actionModifier = when {
        checked != null && onCheckedChange != null -> Modifier.toggleable(
            value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange
        )
        onClick != null -> Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        else -> Modifier
    }
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Surface(modifier = modifier.fillMaxWidth(), shape = bitwardenGroupShape(first, last),
        color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.then(actionModifier).heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = if (checked != null) 48.dp else 40.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(icon, null, tint = color.copy(alpha = if (enabled) 1f else .38f), modifier = Modifier.size(24.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, color = color.copy(alpha = if (enabled) 1f else .38f))
                    supporting?.takeIf { it.isNotBlank() && checked == null }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (checked != null) Switch(checked, onCheckedChange = null, enabled = enabled)
                else if (onClick != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (checked != null) supporting?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

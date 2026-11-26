package takagi.ru.monica.ui.components

import takagi.ru.monica.ui.haptic.hapticSwitchToggleable

import takagi.ru.monica.ui.haptic.MonicaSwitch as Switch

import takagi.ru.monica.ui.screens.Setting
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.ui.screens.settingsSearchAnchor
import takagi.ru.monica.ui.screens.settingsSectionItemShape

/** Shared only by the customization landing page and permission manager. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsSubpageTopBar(
    title: String,
    onNavigateBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        expandedHeight = 64.dp * LocalDensity.current.fontScale.coerceAtLeast(1f),
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
            }
        },
        actions = actions,
    )
}

@Composable
internal fun SettingsSubpageHeading(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp)
            .semantics { heading() },
    )
}

@Composable
internal fun SettingsSubpageRow(
    setting: Setting,
    subtitle: String = setting.summary(),
    icon: ImageVector,
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    checked: Boolean? = null,
    onCheckedChange: (Boolean) -> Unit = {},
    onClick: () -> Unit = {},
) {
    val title = setting.title()
    val shape = settingsSectionItemShape(index, count)
    val interaction = if (checked != null) {
        Modifier.hapticSwitchToggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
    } else {
        Modifier.clickable(role = Role.Button, onClick = onClick)
    }
    Surface(
        modifier = modifier.fillMaxWidth().settingsSearchAnchor(title),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        BoxWithConstraints(interaction.animateMonicaContentSize().padding(16.dp)) {
            val stackSwitch = checked != null &&
                (LocalDensity.current.fontScale > 1.3f || maxWidth < 280.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (stackSwitch) {
                        Switch(checked = checked == true, onCheckedChange = null,
                            modifier = Modifier.align(Alignment.End).padding(top = 8.dp))
                    }
                }
                if (!stackSwitch) {
                    Spacer(Modifier.width(12.dp))
                    if (checked != null) {
                        Switch(checked = checked, onCheckedChange = null)
                    } else {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                            Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

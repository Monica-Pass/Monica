package takagi.ru.monica.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import takagi.ru.monica.R
import takagi.ru.monica.data.AppLauncherIcon

@Composable
internal fun AppLauncherIconSettings(
    selectedIcon: AppLauncherIcon,
    onIconSelected: (AppLauncherIcon) -> Unit,
) {
    val context = LocalContext.current
    val previewSizePx = with(LocalDensity.current) { 56.dp.roundToPx() }
    Column(modifier = Modifier.settingsSearchAnchor(Setting.ICON_SETTINGS_APP_ICON_TITLE.title()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        takagi.ru.monica.ui.components.SettingsSubpageHeading(Setting.ICON_SETTINGS_APP_ICON_TITLE.title())
        Text(
            text = Setting.ICON_SETTINGS_APP_ICON_TITLE.summary(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Column(
            modifier = Modifier.selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            AppLauncherIcon.entries.forEachIndexed { index, option ->
                val selected = option == selectedIcon
                val title = stringResource(
                    when (option) {
                        AppLauncherIcon.MODERN -> R.string.launcher_icon_default
                        AppLauncherIcon.BLUE_STAR -> R.string.launcher_icon_blue_star
                    }
                )
                val iconRes = when (option) {
                    AppLauncherIcon.BLUE_STAR -> R.mipmap.ic_launcher_blue_star
                    AppLauncherIcon.MODERN -> R.mipmap.ic_launcher_modern
                }
                val bitmap = remember(context, iconRes, previewSizePx) {
                    requireNotNull(ContextCompat.getDrawable(context, iconRes))
                        .toBitmap(previewSizePx, previewSizePx).asImageBitmap()
                }
                val topRadius = if (index == 0) 24.dp else 4.dp
                val bottomRadius = if (index == AppLauncherIcon.entries.lastIndex) 24.dp else 4.dp
                Surface(
                    shape = RoundedCornerShape(topRadius, topRadius, bottomRadius, bottomRadius),
                    color = if (selected) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("launcher_icon_${option.name}")
                            .selectable(selected = selected, role = Role.RadioButton) {
                                if (!selected) onIconSelected(option)
                            }
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Image(bitmap, contentDescription = null, modifier = Modifier.size(56.dp))
                        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        RadioButton(selected = selected, onClick = null)
                    }
                }
            }
        }
        Text(
            text = stringResource(R.string.launcher_icon_refresh_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

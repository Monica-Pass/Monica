package takagi.ru.monica.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.ui.components.BlurredDialogSurface

@Composable
internal fun VersionInfoDialog(fullVersion: String, onOpenLink: (String) -> Unit, onDismiss: () -> Unit) {
    BlurredDialogSurface(onDismiss, Modifier.testTag("version-info-dialog")) {
        val shortWindow = LocalConfiguration.current.screenHeightDp < 520
        val scroll = rememberScrollState()
        Column(Modifier.let { if (shortWindow) it.verticalScroll(scroll) else it }.padding(24.dp)) {
            Column(
                Modifier.fillMaxWidth().let { if (shortWindow) it else it.weight(1f, fill = false).verticalScroll(scroll) },
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Icon(Icons.Default.Info, null, Modifier.padding(12.dp).size(28.dp))
                    }
                    Text(stringResource(R.string.version_info_dialog_title), style = MaterialTheme.typography.headlineSmall)
                }
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.version_info_full_version_label), style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary)
                        Text(fullVersion, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.version_info_internal_codename, stringResource(R.string.version_info_codename)),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val github = { onOpenLink("https://github.com/Monica-Pass/Monica") }
                val website = { onOpenLink("https://monica-pass.github.io/MonicaDocs/") }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth >= 276.dp && LocalDensity.current.fontScale < 1.3f) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ProjectLink(R.string.version_info_github_label, Icons.Default.Code, github, Modifier.weight(1f))
                            ProjectLink(R.string.version_info_website_label, Icons.Default.Language, website, Modifier.weight(1f))
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ProjectLink(R.string.version_info_github_label, Icons.Default.Code, github, Modifier.fillMaxWidth())
                            ProjectLink(R.string.version_info_website_label, Icons.Default.Language, website, Modifier.fillMaxWidth())
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    IconCreditLink(R.string.version_info_icon_source_label, RoundedCornerShape(16.dp, 16.dp, 4.dp, 4.dp)) {
                        onOpenLink("https://github.com/stratumauth/app/tree/v1.4.0/icons")
                    }
                    IconCreditLink(R.string.version_info_icon_release_label, RoundedCornerShape(4.dp, 4.dp, 16.dp, 16.dp)) {
                        onOpenLink("https://github.com/stratumauth/app/releases/tag/v1.4.0")
                    }
                }
                Text(stringResource(R.string.version_info_simple_icons_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onDismiss, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.close))
            }
        }
    }
}

@Composable
private fun ProjectLink(label: Int, icon: ImageVector, onClick: () -> Unit, modifier: Modifier) {
    FilledTonalButton(onClick, modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        Icon(icon, null, Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(label))
    }
}

@Composable
private fun IconCreditLink(label: Int, shape: RoundedCornerShape, onClick: () -> Unit) {
    Surface(onClick, shape = shape, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

package takagi.ru.monica.ui.screens

import takagi.ru.monica.ui.haptic.switchClick

import takagi.ru.monica.ui.haptic.MonicaSwitch as Switch

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.data.AppSettings

@Composable
internal fun PreviewFeaturesDialog(
    settings: AppSettings,
    onHideFabOnScrollChanged: (Boolean) -> Unit,
    onBitwardenStatusChanged: (Boolean) -> Unit,
    onReduceAnimationsChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val previewDialogContentMaxHeight = (LocalConfiguration.current.screenHeightDp * 0.58f).dp
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Science,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(stringResource(R.string.preview_features_title))
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = previewDialogContentMaxHeight)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    stringResource(R.string.preview_features_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))

                // === 实验功能分组 ===
                Text(
                    text = stringResource(R.string.experimental_features_section),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // 滚动隐藏 FAB 开关
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = switchClick(settings.hideFabOnScroll, onCheckedChange = onHideFabOnScrollChanged))
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.VisibilityOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = Setting.HIDE_FAB_ON_SCROLL_TITLE.title(),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = Setting.HIDE_FAB_ON_SCROLL_TITLE.summary(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.hideFabOnScroll,
                        onCheckedChange = { onHideFabOnScrollChanged(it) }
                    )
                }

                // Bitwarden 底部状态栏开关（实验）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = switchClick(settings.bitwardenBottomStatusBarEnabled,
                            onCheckedChange = onBitwardenStatusChanged))
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = Setting.BITWARDEN_BOTTOM_STATUS_BAR_TITLE.title(),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = Setting.BITWARDEN_BOTTOM_STATUS_BAR_TITLE.summary(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.bitwardenBottomStatusBarEnabled,
                        onCheckedChange = { onBitwardenStatusChanged(it) }
                    )
                }

                // 减少动画设置
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = switchClick(settings.reduceAnimations, onCheckedChange = onReduceAnimationsChanged))
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = Setting.REDUCE_ANIMATIONS.title(),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = Setting.REDUCE_ANIMATIONS.summary(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.reduceAnimations,
                        onCheckedChange = { onReduceAnimationsChanged(it) }
                    )
                }

            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

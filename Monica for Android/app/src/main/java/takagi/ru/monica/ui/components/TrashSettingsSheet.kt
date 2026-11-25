package takagi.ru.monica.ui.components

import takagi.ru.monica.ui.haptic.MonicaSwitch as Switch

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import takagi.ru.monica.ui.screens.Setting
import takagi.ru.monica.R
import takagi.ru.monica.viewmodel.TrashSettings

/**
 * 回收站设置底部弹窗 (Material 3 Expressive)
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TrashSettingsSheet(
    currentSettings: TrashSettings,
    onDismiss: () -> Unit,
    onConfirm: (enabled: Boolean, days: Int) -> Unit
) {
    var enabled by remember { mutableStateOf(currentSettings.enabled) }
    var selectedDays by remember { mutableStateOf(currentSettings.autoDeleteDays) }
    
    val dayOptions = listOf(
        0 to stringResource(R.string.trash_no_auto_clear),
        7 to stringResource(R.string.trash_day_format, 7),
        15 to stringResource(R.string.trash_day_format, 15),
        30 to stringResource(R.string.trash_day_format, 30),
        60 to stringResource(R.string.trash_day_format, 60),
        90 to stringResource(R.string.trash_day_format, 90)
    )
    
    MonicaModalBottomSheet(
        onDismissRequest = onDismiss,
        showDragHandle = true
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 标题
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = stringResource(R.string.trash_settings),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
            }
            
            HorizontalDivider()
            
            // 启用/禁用开关
            ListItem(
                headlineContent = { Text(Setting.TRASH_ENABLE.title()) },
                supportingContent = { Text(Setting.TRASH_ENABLE.summary()) },
                trailingContent = {
                    Switch(
                        checked = enabled,
                        onCheckedChange = { enabled = it }
                    )
                },
                colors = ListItemDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow // 适配 BottomSheet 背景
                )
            )
            
            if (enabled) {
                // 自动清空设置
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = Setting.TRASH_AUTO_CLEAR_TIME.title(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        dayOptions.forEach { (days, label) ->
                            FilterChip(
                                selected = selectedDays == days,
                                onClick = { selectedDays = days },
                                label = { Text(label) },
                                leadingIcon = if (selectedDays == days) {
                                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                                } else null
                            )
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 按钮组
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        onConfirm(enabled, selectedDays)
                        onDismiss()
                    }
                ) {
                    Text(stringResource(R.string.save))
                }
            }
        }
    }
}

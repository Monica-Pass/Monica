package takagi.ru.monica.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R

/**
 * 条目类型切换按钮。显示在添加/编辑页顶栏右侧，让用户在「密码 / WIFI / SSH 密钥」之间切换。
 *
 * Material 3 Expressive 风格：去掉 AssistChip 的描边，换成小圆角的 tonal pill，
 * 点击弹出 DropdownMenu 选类型。禁用时只做透明度淡出，保持形状一致。
 */
enum class EntryTypeChipOption { PASSWORD, API_KEY, WIFI, SSH_KEY, BARCODE, API_TOKEN, GPG_KEY }

@Composable
fun EntryTypeChip(
    current: EntryTypeChipOption,
    onSelect: (EntryTypeChipOption) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showApiToken: Boolean = true,
    showGpg: Boolean = false,
    showApiKey: Boolean = false,
    drawContainer: Boolean = true,
    contentColorOverride: Color? = null
) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = stringResource(current.labelRes())
    val chipContentDescription = stringResource(R.string.entry_type_chip_content_description)

    val interactionSource = remember { MutableInteractionSource() }

    // 颜色：使用 secondaryContainer tonal 背景，enabled=false 时降到 0.38 alpha（M3 规范）。
    val targetContainer = MaterialTheme.colorScheme.secondaryContainer
    val targetContent = contentColorOverride ?: if (drawContainer) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val alpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.38f,
        label = "EntryTypeChipAlpha"
    )
    val containerColor by animateColorAsState(
        targetValue = if (drawContainer) targetContainer.copy(alpha = alpha) else Color.Transparent,
        label = "EntryTypeChipContainer"
    )
    val contentColor by animateColorAsState(
        targetValue = targetContent.copy(alpha = alpha),
        label = "EntryTypeChipContent"
    )
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "EntryTypeChipArrow"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .defaultMinSize(minHeight = 32.dp)
            .clip(RoundedCornerShape(50))
            .background(color = containerColor)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(),
                enabled = enabled,
                role = Role.Button,
                onClick = { expanded = true }
            )
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics { contentDescription = chipContentDescription }
    ) {
        Icon(
            imageVector = current.icon(),
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = currentLabel,
            color = contentColor,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1
        )
        Icon(
            imageVector = Icons.Default.ExpandMore,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier
                .size(18.dp)
                .rotate(arrowRotation)
        )
    }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp
    ) {
        EntryTypeChipOption.entries.filter {
            (showApiToken || it != EntryTypeChipOption.API_TOKEN) &&
                (showGpg || it != EntryTypeChipOption.GPG_KEY) &&
                (showApiKey || it != EntryTypeChipOption.API_KEY)
        }.forEach { option ->
            val label = stringResource(option.labelRes())
            val isCurrent = option == current
            DropdownMenuItem(
                text = {
                    Text(
                        label,
                        color = if (isCurrent) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        style = MaterialTheme.typography.bodyLarge
                    )
                },
                leadingIcon = {
                    Icon(
                        option.icon(),
                        contentDescription = null,
                        tint = if (isCurrent) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                },
                trailingIcon = if (isCurrent) {
                    {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                } else null,
                onClick = {
                    expanded = false
                    if (!isCurrent) onSelect(option)
                }
            )
        }
    }
}

private fun EntryTypeChipOption.labelRes() = when (this) {
    EntryTypeChipOption.API_KEY -> R.string.api_key_title
    EntryTypeChipOption.GPG_KEY -> R.string.gpg_title
    EntryTypeChipOption.API_TOKEN -> R.string.entry_type_api_token
    EntryTypeChipOption.PASSWORD -> R.string.entry_type_password
    EntryTypeChipOption.WIFI -> R.string.entry_type_wifi
    EntryTypeChipOption.SSH_KEY -> R.string.entry_type_ssh_key
    EntryTypeChipOption.BARCODE -> R.string.entry_type_barcode
}

private fun EntryTypeChipOption.icon(): ImageVector = when (this) {
    EntryTypeChipOption.API_KEY -> Icons.Default.Key
    EntryTypeChipOption.GPG_KEY -> Icons.Default.Key
    EntryTypeChipOption.API_TOKEN -> Icons.Default.Key
    EntryTypeChipOption.PASSWORD -> Icons.Default.Password
    EntryTypeChipOption.WIFI -> Icons.Default.Wifi
    EntryTypeChipOption.SSH_KEY -> Icons.Default.Key
    EntryTypeChipOption.BARCODE -> Icons.Default.QrCode2
}

/** 保留 import 不造成未使用警告。 */
@Suppress("unused")
private val UnusedColorReferenceForCompiler: Color = Color.Unspecified

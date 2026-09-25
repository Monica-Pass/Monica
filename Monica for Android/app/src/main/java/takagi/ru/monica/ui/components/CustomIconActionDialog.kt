package takagi.ru.monica.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import takagi.ru.monica.ui.components.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import takagi.ru.monica.ui.icons.normalizeEmojiIcon
import takagi.ru.monica.ui.icons.QUICK_PICK_EMOJI_ICONS
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import takagi.ru.monica.R
import takagi.ru.monica.ui.icons.SimpleIconOption
import takagi.ru.monica.ui.icons.EmojiIconText
import takagi.ru.monica.ui.icons.rememberSimpleIconBitmap

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomIconActionDialog(
    showClearAction: Boolean,
    showEmojiAction: Boolean = true,
    onPickFromLibrary: () -> Unit,
    onUploadImage: () -> Unit,
    onPickEmoji: () -> Unit = {},
    onPickInstalledIcon: (() -> Unit)? = null,
    onClearIcon: () -> Unit,
    onDismissRequest: () -> Unit
) {
    MonicaModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.custom_icon_dialog_title),
                style = MaterialTheme.typography.headlineSmall
            )
            IconActionItem(
                icon = Icons.Default.Apps,
                label = stringResource(R.string.custom_icon_pick_simple),
                onClick = onPickFromLibrary
            )
            IconActionItem(
                icon = Icons.Default.Upload,
                label = stringResource(R.string.custom_icon_upload_image),
                onClick = onUploadImage
            )
            if (onPickInstalledIcon != null) {
                IconActionItem(
                    icon = Icons.Default.Palette,
                    label = stringResource(R.string.custom_icon_pick_installed),
                    onClick = onPickInstalledIcon,
                )
            }
            if (showEmojiAction) {
                IconActionItem(
                    icon = Icons.Default.EmojiEmotions,
                    label = stringResource(R.string.custom_icon_use_emoji),
                    onClick = onPickEmoji
                )
            }
            if (showClearAction) {
                IconActionItem(
                    icon = Icons.Default.Delete,
                    label = stringResource(R.string.custom_icon_clear),
                    onClick = onClearIcon,
                    emphasizeError = true
                )
            }
            TextButton(
                onClick = onDismissRequest,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(stringResource(R.string.close))
            }
            Spacer(modifier = Modifier.size(12.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmojiIconInputDialog(
    initialEmoji: String? = null,
    onConfirm: (String) -> Unit,
    onDismissRequest: () -> Unit
) {
    var value by rememberSaveable(initialEmoji) { mutableStateOf(initialEmoji.orEmpty()) }
    val normalized = normalizeEmojiIcon(value)
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.custom_icon_use_emoji)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.headlineMedium,
                    placeholder = { Text(stringResource(R.string.custom_icon_emoji_hint)) },
                    supportingText = {
                        Text(
                            if (value.isBlank() || normalized != null) {
                                stringResource(R.string.custom_icon_emoji_support)
                            } else {
                                stringResource(R.string.custom_icon_emoji_invalid)
                            }
                        )
                    },
                    isError = value.isNotBlank() && normalized == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                )
                // 常用 Emoji 快捷选择，避免依赖输入法的 Emoji 面板
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    QUICK_PICK_EMOJI_ICONS.forEach { emoji ->
                        val selected = normalized == emoji
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .then(
                                    if (selected) {
                                        Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                                    } else {
                                        Modifier
                                    }
                                )
                                .selectable(selected = selected, role = Role.RadioButton, onClick = { value = emoji }),
                            contentAlignment = Alignment.Center
                        ) {
                            EmojiIconText(emoji = emoji, size = 24.dp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = normalized != null, onClick = { normalized?.let(onConfirm) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismissRequest) { Text(stringResource(R.string.close)) } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleIconPickerBottomSheet(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    iconOptions: List<SimpleIconOption>,
    visibleOptions: List<SimpleIconOption>,
    hasMore: Boolean,
    remainingCount: Int,
    iconCardsEnabled: Boolean,
    selectedSlug: String?,
    onSelectOption: (SimpleIconOption) -> Unit,
    onLoadMore: () -> Unit,
    onDismissRequest: () -> Unit
) {
    val rows = remember(visibleOptions) { visibleOptions.chunked(2) }

    MonicaModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.custom_icon_pick_simple),
                style = MaterialTheme.typography.headlineSmall
            )
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.custom_icon_search_hint)) },
                shape = RoundedCornerShape(18.dp)
            )
            if (iconOptions.isEmpty()) {
                Text(
                    text = stringResource(R.string.custom_icon_search_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 560.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(rows) { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            rowItems.forEach { option ->
                                val optionBitmap = rememberSimpleIconBitmap(
                                    slug = option.slug,
                                    tintColor = MaterialTheme.colorScheme.primary,
                                    enabled = iconCardsEnabled
                                )
                                val selected = selectedSlug == option.slug
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { onSelectOption(option) },
                                    shape = RoundedCornerShape(16.dp),
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerLow
                                    },
                                    tonalElevation = if (selected) 2.dp else 0.dp
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        if (optionBitmap != null) {
                                            androidx.compose.foundation.Image(
                                                bitmap = optionBitmap,
                                                contentDescription = option.label,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Outlined.Image,
                                                contentDescription = option.label,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }
                                        Text(
                                            text = option.label,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                            if (rowItems.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                    if (hasMore) {
                        item("load_more_icons") {
                            TextButton(
                                onClick = onLoadMore,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.custom_icon_load_more,
                                        remainingCount
                                    )
                                )
                            }
                        }
                    }
                }
            }
            TextButton(
                onClick = onDismissRequest,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(stringResource(R.string.close))
            }
            Spacer(modifier = Modifier.size(8.dp))
        }
    }
}

@Composable
private fun IconActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    emphasizeError: Boolean = false
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (emphasizeError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                color = if (emphasizeError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

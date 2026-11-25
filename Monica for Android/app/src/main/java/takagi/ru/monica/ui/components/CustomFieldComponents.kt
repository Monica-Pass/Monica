package takagi.ru.monica.ui.components

import takagi.ru.monica.ui.haptic.switchClick

import takagi.ru.monica.ui.haptic.MonicaSwitch as Switch

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import takagi.ru.monica.R
import takagi.ru.monica.data.CustomField
import takagi.ru.monica.data.CustomFieldDraft
import takagi.ru.monica.ui.components.OutlinedTextField

// =====================================================
// 编辑页面组件 (Edit Screen Components)
// =====================================================

/**
 * 自定义字段区域标题 (带添加按钮)
 * 
 * 显示在自定义字段列表上方，右侧带 "+" 按钮
 */
@Composable
fun CustomFieldSectionHeader(
    onAddClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.EditNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = stringResource(R.string.custom_field_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
        FilledTonalIconButton(
            onClick = onAddClick,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = stringResource(R.string.custom_field_add),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * 单个自定义字段编辑卡片 (支持编辑/查看模式切换)
 * 
 * - 编辑模式：显示字段名称、字段内容、敏感开关
 * - 查看模式：紧凑显示，只有标题和内容，点击可编辑
 */
@Composable
fun CustomFieldEditCard(
    index: Int,
    field: CustomFieldDraft,
    onFieldChange: (CustomFieldDraft) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    saveTextState: Boolean = true,
    groupShape: androidx.compose.ui.graphics.Shape? = null
) {
    // 编辑模式：新字段（标题或值为空）默认编辑，已保存字段默认查看
    var isEditing by remember { mutableStateOf(field.title.isBlank() || field.value.isBlank()) }
    var valueVisible by remember(field.id, field.isProtected) { mutableStateOf(!field.isProtected) }
    // 删除确认对话框状态
    var showDeleteConfirm by remember { mutableStateOf(false) }
    
    // 动态标题
    val displayTitle = field.title.ifBlank { stringResource(R.string.custom_field_new_field) }
    
    // 删除确认对话框
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text(stringResource(R.string.custom_field_delete_title)) },
            text = { Text(stringResource(R.string.custom_field_delete_message, displayTitle)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
    
    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = if (groupShape == null) 6.dp else 1.dp),
        shape = groupShape ?: RoundedCornerShape(18.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = if (groupShape == null) 2.dp else 0.dp)
    ) {
        if (isEditing) {
            // ========== 编辑模式 ==========
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 顶部：字段名称和收起按钮
                Box(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedTextField(
                        saveTextState = saveTextState,
                        entryContentStyle = groupShape != null,
                        value = field.title,
                        onValueChange = {
                            if (!field.isPreset) {
                                onFieldChange(field.copy(title = it))
                            }
                        },
                        placeholder = {
                            Text(
                                text = stringResource(R.string.custom_field_name_placeholder),
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        readOnly = field.isPreset,
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    if (field.title.isBlank() && field.value.isBlank()) {
                                        onDelete()
                                    } else {
                                        isEditing = false
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowUp,
                                    contentDescription = stringResource(R.string.collapse),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    )
                }

                // 字段值输入
                OutlinedTextField(
                    saveTextState = saveTextState,
                    entryContentStyle = groupShape != null,
                    value = field.value,
                    onValueChange = { onFieldChange(field.copy(value = it)) },
                    label = { Text(stringResource(R.string.custom_field_value)) },
                    placeholder = { Text(stringResource(R.string.custom_field_value_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    visualTransformation = if (!valueVisible && field.isProtected) 
                        PasswordVisualTransformation() 
                    else 
                        VisualTransformation.None,
                    trailingIcon = {
                        if (field.isProtected) {
                            IconButton(onClick = { valueVisible = !valueVisible }) {
                                Icon(
                                    imageVector = if (valueVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = if (valueVisible) {
                                        stringResource(R.string.custom_field_hide_content)
                                    } else {
                                        stringResource(R.string.custom_field_show_content)
                                    }
                                )
                            }
                        }
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Notes,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    minLines = 1,
                    maxLines = 3
                )
                
                // 敏感数据开关
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = switchClick(field.isProtected) { next ->
                            onFieldChange(field.copy(isProtected = next))
                            if (next) valueVisible = false
                        }),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = if (field.isProtected) Icons.Default.Lock else Icons.Default.LockOpen,
                                contentDescription = null,
                                tint = if (field.isProtected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(22.dp)
                            )
                            Column {
                                Text(
                                    text = stringResource(R.string.custom_field_sensitive),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = stringResource(R.string.custom_field_sensitive_hint),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }
                        Switch(
                            checked = field.isProtected,
                            onCheckedChange = {
                                onFieldChange(field.copy(isProtected = it))
                                if (it) valueVisible = false
                            }
                        )
                    }
                }

                if (field.isPreset || field.isRequired) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (field.isPreset) {
                            AssistChip(
                                onClick = {},
                                label = { Text(stringResource(R.string.custom_field_preset)) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            )
                        }
                        if (field.isRequired) {
                            AssistChip(
                                onClick = {},
                                label = { Text(stringResource(R.string.custom_field_required)) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Star,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            )
                        }
                    }
                }
            }
        } else {
            // ========== 查看模式（紧凑） ==========
            Column(
                modifier = Modifier
                    .clickable { isEditing = true }
                    .padding(16.dp)
            ) {
                // 标题行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = if (field.isProtected) Icons.Default.Lock else Icons.Default.Label,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = displayTitle,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                    // 编辑和删除按钮
                    Row {
                        IconButton(
                            onClick = { isEditing = true },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = stringResource(R.string.edit),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        // 删除按钮（需二次确认）
                        // 预设字段显示锁图标，不可删除
                        if (field.isPreset) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = stringResource(R.string.custom_field_preset_not_deletable),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(horizontal = 8.dp)
                                    .size(18.dp)
                            )
                        } else {
                            IconButton(
                                onClick = { showDeleteConfirm = true },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.delete),
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // 字段值（敏感时隐藏）
                Text(
                    text = if (field.isProtected && !valueVisible) "••••••••" else field.value,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2
                )
                
                // 敏感标记
                if (field.isProtected) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f),
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = stringResource(R.string.custom_field_sensitive),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)
                        )
                    }
                }
                
                // 预设/必填标记行
                if (field.isPreset || field.isRequired) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = if (field.isProtected) 4.dp else 8.dp)
                    ) {
                        if (field.isPreset) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = stringResource(R.string.custom_field_preset),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                )
                            }
                        }
                        if (field.isRequired) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = stringResource(R.string.custom_field_required),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 添加自定义字段按钮 (虚线边框卡片样式)
 */
@Composable
fun AddCustomFieldButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedCard(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        ),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.custom_field_add),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// =====================================================
// 详情页面组件 (Detail Screen Components)
// =====================================================

/** Standalone use of the same field row used inside information sections. */
@Composable
fun CustomFieldDetailCard(
    field: CustomField,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
    groupShape: androidx.compose.ui.graphics.Shape? = null,
    onCreateSend: ((title: String, text: String) -> Unit)? = null,
) {
    DetailCardSurface(modifier, shape = groupShape ?: RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(16.dp)) {
            CustomFieldDetailRow(field, onCopy, onCreateSend)
        }
    }
}

@Composable
private fun CustomFieldDetailRow(
    field: CustomField,
    onCopy: (String) -> Unit,
    onCreateSend: ((String, String) -> Unit)?,
) {
    var valueVisible by remember(field.entryId, field.id, field.value, field.isProtected) {
        mutableStateOf(!field.isProtected)
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val label = field.title.ifBlank { stringResource(R.string.custom_field_new_field) }
    DetailField(
        label = label, value = field.value, context = context,
        displayValue = if (valueVisible) field.value else "••••••••",
        protected = field.isProtected, visible = valueVisible,
        onToggleVisibility = { valueVisible = !valueVisible },
        onCreateSend = onCreateSend,
        onCopy = {
            takagi.ru.monica.utils.ClipboardUtils.copyToClipboard(
                context = context, text = field.value, label = label, sensitive = field.isProtected)
            onCopy(label)
        },
        showDescription = stringResource(R.string.custom_field_show_content),
        hideDescription = stringResource(R.string.custom_field_hide_content),
    )
}

/** Embedded content for sections that already own a surface and padding. */
@Composable
internal fun CustomFieldDetailRows(
    fields: List<CustomField>,
    onCopyField: (String, String) -> Unit = { _, _ -> },
    onCreateSend: ((String, String) -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        fields.forEachIndexed { index, field ->
            androidx.compose.runtime.key(field.entryId, field.id, index) {
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                CustomFieldDetailRow(field, { label -> onCopyField(label, field.value) }, onCreateSend)
            }
        }
    }
}

// =====================================================
// 兼容旧版 API (Backward Compatibility)
// =====================================================

/**
 * 自定义字段编辑器组件 (容器样式 - 渲染为独立卡片)
 * 
 * 保持旧 API 兼容，内部渲染为独立卡片
 */
@Composable
fun CustomFieldEditorSection(
    fields: List<CustomFieldDraft>,
    onFieldsChange: (List<CustomFieldDraft>) -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean = true,
    onExpandedChange: (Boolean) -> Unit = {},
    saveTextState: Boolean = true,
    contentStyle: Boolean = rememberEntryContentStyle(),
    showAddButton: Boolean = true,
) {
    Column(modifier = modifier) {
        fields.forEachIndexed { index, field ->
            androidx.compose.runtime.key(field.id) {
            CustomFieldEditCard(
                saveTextState = saveTextState,
                groupShape = if (contentStyle) entryGroupShape(index, fields.size) else null,
                index = index,
                field = field,
                onFieldChange = { updated ->
                    val newList = fields.toMutableList()
                    newList[index] = updated
                    onFieldsChange(newList)
                },
                onDelete = {
                    val newList = fields.toMutableList()
                    newList.removeAt(index)
                    onFieldsChange(newList)
                }
            )
            }
        }
        
        if (showAddButton && contentStyle) {
            EntryContentFieldButton(fields, onFieldsChange)
        } else if (showAddButton) {
        AddCustomFieldButton(
            onClick = {
                val newList = fields.toMutableList()
                newList.add(CustomFieldDraft(
                    id = CustomFieldDraft.nextTempId(fields.map { it.id }),
                    title = "",
                    value = "",
                    isProtected = false
                ))
                onFieldsChange(newList)
            }
        )
        }
    }
}

/** Custom fields form one section, in either editor style. */
@Composable
fun CustomFieldDisplayCard(
    fields: List<CustomField>,
    modifier: Modifier = Modifier,
    onCopyField: (String, String) -> Unit = { _, _ -> },
    onCreateSend: ((title: String, text: String) -> Unit)? = null,
) {
    if (fields.isEmpty()) return
    DetailSectionLayout(stringResource(R.string.custom_field_title), modifier) {
        fields.forEachIndexed { index, field ->
            androidx.compose.runtime.key(field.entryId, field.id, index) {
                DetailGroupItem(index, fields.size) {
                    CustomFieldDetailRow(field, { label -> onCopyField(label, field.value) }, onCreateSend)
                }
            }
        }
    }
}

/**
 * 简洁的自定义字段列表（用于密码列表项预览）
 */
@Composable
fun CustomFieldPreviewChips(
    fields: List<CustomField>,
    modifier: Modifier = Modifier,
    maxDisplay: Int = 3
) {
    if (fields.isEmpty()) return
    
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        fields.take(maxDisplay).forEach { field ->
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
            ) {
                Text(
                    text = field.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    maxLines = 1
                )
            }
        }
        
        if (fields.size > maxDisplay) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
            ) {
                Text(
                    text = "+${fields.size - maxDisplay}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

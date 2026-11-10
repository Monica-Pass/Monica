package takagi.ru.monica.ui.screens

import takagi.ru.monica.ui.components.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.data.PresetCustomField
import takagi.ru.monica.data.PresetFieldType
import takagi.ru.monica.viewmodel.SettingsViewModel
import java.util.UUID
import takagi.ru.monica.ui.components.OutlinedTextField

/**
 * 添加密码页面字段定制设置页面
 * 管理账号显示方式与预设自定义字段
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordFieldCustomizationScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsState()
    val presetFields by viewModel.presetCustomFields.collectAsState()

    // 添加/编辑预设字段对话框状态
    var showAddPresetDialog by remember { mutableStateOf(false) }
    var editingPresetField by remember { mutableStateOf<PresetCustomField?>(null) }

    CustomizationPage(stringResource(R.string.password_field_customization_title), onNavigateBack) {
        CustomizationSection(stringResource(R.string.password_field_customization_system_fields)) {
            CustomizationToggle(
                icon = Icons.Default.AlternateEmail,
                title = stringResource(R.string.separate_username_account_title),
                subtitle = stringResource(R.string.separate_username_account_desc),
                checked = settings.separateUsernameAccountEnabled,
                onCheckedChange = {
                    viewModel.updateSeparateUsernameAccountEnabled(it)
                }, index = 0, count = 1)

        }
        CustomizationSection(stringResource(R.string.password_field_customization_preset_section_title),
            stringResource(R.string.password_field_customization_preset_section_subtitle)) {
            Text(stringResource(R.string.password_field_customization_preset_notice),
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
            FilledTonalButton(onClick = { showAddPresetDialog = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.add))
            }
            if (presetFields.isEmpty()) {
                Surface(shape = settingsSectionItemShape(0, 1), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.PlaylistAdd, null)
                        Text(stringResource(R.string.password_field_customization_no_preset_fields))
                        Text(stringResource(R.string.password_field_customization_no_preset_fields_hint), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            presetFields.sortedBy { it.order }.forEachIndexed { index, field ->
                key(field.id) {
                    PresetFieldCard(field, { editingPresetField = field },
                        { viewModel.deletePresetCustomField(field.id) }, index, presetFields.size)
                }
            }
        }
        if (presetFields.isNotEmpty()) {
            var confirmClear by remember { mutableStateOf(false) }
            TextButton(onClick = { confirmClear = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.password_field_customization_clear_preset_fields), color = MaterialTheme.colorScheme.error)
            }
            if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
                title = { Text(stringResource(R.string.password_field_customization_clear_preset_fields)) },
                text = { Text(stringResource(R.string.customization_clear_presets_message)) },
                confirmButton = { TextButton(onClick = { viewModel.clearAllPresetCustomFields(); confirmClear = false }) { Text(stringResource(R.string.delete)) } },
                dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } })
        }
    }

    // 添加预设字段对话框
    if (showAddPresetDialog) {
        PresetFieldDialog(
            field = null,
            onDismiss = { showAddPresetDialog = false },
            onSave = { newField ->
                viewModel.addPresetCustomField(newField)
                showAddPresetDialog = false
            }
        )
    }

    // 编辑预设字段对话框
    editingPresetField?.let { field ->
        PresetFieldDialog(
            field = field,
            onDismiss = { editingPresetField = null },
            onSave = { updatedField ->
                viewModel.updatePresetCustomField(updatedField)
                editingPresetField = null
            }
        )
    }
}

/**
 * 预设字段卡片
 */
@Composable
private fun PresetFieldCard(
    field: PresetCustomField,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    index: Int,
    count: Int
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Surface(shape = settingsSectionItemShape(index, count), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(getFieldTypeIcon(field.fieldType), null, tint = MaterialTheme.colorScheme.primary)
            Text(field.fieldName, style = MaterialTheme.typography.titleMedium)
            Text(presetFieldTypeLabel(field.fieldType), style = MaterialTheme.typography.bodyMedium)
            if (field.isRequired) Text(stringResource(R.string.custom_field_required), color = MaterialTheme.colorScheme.primary)
            if (field.isSensitive) Text(stringResource(R.string.custom_field_sensitive), color = MaterialTheme.colorScheme.primary)
            if (field.defaultValue.isNotBlank()) Text(stringResource(R.string.password_field_customization_default_value_suffix,
                if (field.isSensitive) "••••••" else field.defaultValue), style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onEdit, modifier = Modifier.testTag("preset_edit_${field.id}")) { Text(stringResource(R.string.edit)) }
                TextButton(onClick = { showDeleteConfirm = true }, modifier = Modifier.testTag("preset_delete_${field.id}")) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            }
        }
    }

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
            title = { Text(stringResource(R.string.password_field_customization_delete_preset_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.password_field_customization_delete_preset_message,
                        field.fieldName
                    )
                )
            },
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
}

/**
 * 添加/编辑预设字段对话框
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PresetFieldDialog(
    field: PresetCustomField?,
    onDismiss: () -> Unit,
    onSave: (PresetCustomField) -> Unit
) {
    val isEditing = field != null
    var fieldName by rememberSaveable(field?.id) { mutableStateOf(field?.fieldName ?: "") }
    var fieldType by rememberSaveable(field?.id) { mutableStateOf(field?.fieldType ?: PresetFieldType.TEXT) }
    var isSensitive by rememberSaveable(field?.id) { mutableStateOf(field?.isSensitive ?: false) }
    var isRequired by rememberSaveable(field?.id) { mutableStateOf(field?.isRequired ?: false) }
    var defaultValue by rememberSaveable(field?.id) { mutableStateOf(field?.defaultValue ?: "") }
    var placeholder by rememberSaveable(field?.id) { mutableStateOf(field?.placeholder ?: "") }
    var moreOptions by rememberSaveable(field?.id) {
        mutableStateOf(!field?.defaultValue.isNullOrEmpty() || !field?.placeholder.isNullOrEmpty())
    }
    var showTypeDropdown by remember { mutableStateOf(false) }
    var showDefaultValue by remember(field?.id, isSensitive) { mutableStateOf(false) }
    var fieldNameError by remember { mutableStateOf(false) }
    val fieldColors = TextFieldDefaults.colors(
        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent
    )
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth()
            .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.85f).dp)
            .padding(horizontal = 12.dp).testTag("preset_field_sheet")) {
            Text(stringResource(if (isEditing) R.string.password_field_customization_edit_preset_title
                else R.string.password_field_customization_add_preset_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 8.dp).padding(bottom = 16.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextField(value = fieldName, onValueChange = { fieldName = it; fieldNameError = false },
                        label = { Text(stringResource(R.string.password_field_customization_field_name_required)) },
                        modifier = Modifier.fillMaxWidth().testTag("preset_name"), singleLine = true,
                        isError = fieldNameError,
                        supportingText = if (fieldNameError) {{ Text(stringResource(R.string.password_field_customization_field_name_error)) }} else null,
                        shape = settingsSectionItemShape(0, 2), colors = fieldColors)
                    ExposedDropdownMenuBox(expanded = showTypeDropdown, onExpandedChange = { showTypeDropdown = it }) {
                        TextField(value = presetFieldTypeLabel(fieldType), onValueChange = {}, readOnly = true,
                            label = { Text(stringResource(R.string.password_field_customization_field_type)) },
                            leadingIcon = { Icon(getFieldTypeIcon(fieldType), null) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(showTypeDropdown) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).testTag("preset_type"),
                            shape = settingsSectionItemShape(1, 2), colors = fieldColors)
                        ExposedDropdownMenu(expanded = showTypeDropdown, onDismissRequest = { showTypeDropdown = false }) {
                            PresetFieldType.entries.forEach { type ->
                                DropdownMenuItem(text = { Text(presetFieldTypeLabel(type)) },
                                    leadingIcon = { Icon(getFieldTypeIcon(type), null) },
                                    onClick = { fieldType = type; showTypeDropdown = false
                                        if (type == PresetFieldType.PASSWORD) isSensitive = true })
                            }
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    PresetOptionRow(stringResource(R.string.custom_field_sensitive),
                        stringResource(R.string.password_field_customization_sensitive_hint),
                        isSensitive, { isSensitive = it }, 0)
                    PresetOptionRow(stringResource(R.string.password_field_customization_required_field),
                        stringResource(R.string.password_field_customization_required_field_hint),
                        isRequired, { isRequired = it }, 1)
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Surface(modifier = Modifier.testTag("preset_more_options"),
                        shape = settingsSectionItemShape(0, if (moreOptions) 3 else 1),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        onClick = { moreOptions = !moreOptions }) {
                        ListItem(headlineContent = { Text(stringResource(R.string.more_options), style = MaterialTheme.typography.bodyMedium) },
                            trailingContent = { Icon(if (moreOptions) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                    }
                    if (moreOptions) {
                        TextField(value = defaultValue, onValueChange = { defaultValue = it },
                            label = { Text(stringResource(R.string.password_field_customization_default_value_optional)) },
                            singleLine = true, modifier = Modifier.fillMaxWidth().testTag("preset_default"),
                            visualTransformation = if (isSensitive && !showDefaultValue) PasswordVisualTransformation() else VisualTransformation.None,
                            trailingIcon = if (isSensitive) {{
                                IconButton(onClick = { showDefaultValue = !showDefaultValue }) {
                                    Icon(if (showDefaultValue) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        stringResource(if (showDefaultValue) R.string.hide_password else R.string.show_password))
                                }
                            }} else null,
                            shape = settingsSectionItemShape(1, 3), colors = fieldColors)
                        TextField(value = placeholder, onValueChange = { placeholder = it },
                            label = { Text(stringResource(R.string.password_field_customization_placeholder_optional)) },
                            singleLine = true, modifier = Modifier.fillMaxWidth().testTag("preset_placeholder"),
                            shape = settingsSectionItemShape(2, 3), colors = fieldColors)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(modifier = Modifier.testTag("preset_save"), onClick = {
                    if (fieldName.isBlank()) { fieldNameError = true; return@Button }
                    onSave(PresetCustomField(id = field?.id ?: UUID.randomUUID().toString(),
                        fieldName = fieldName.trim(), fieldType = fieldType, isSensitive = isSensitive,
                        isRequired = isRequired, defaultValue = defaultValue.trim(),
                        placeholder = placeholder.trim(), order = field?.order ?: 0))
                }) { Text(stringResource(if (isEditing) R.string.save else R.string.add)) }
            }
        }
    }
}

@Composable
private fun PresetOptionRow(title: String, description: String, checked: Boolean,
    onChange: (Boolean) -> Unit, index: Int) {
    val shape = settingsSectionItemShape(index, 2)
    Surface(modifier = Modifier.fillMaxWidth().clip(shape)
        .semantics { contentDescription = "$title. $description" }
        .toggleable(checked, role = Role.Switch, onValueChange = onChange),
        shape = shape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f))
            Switch(checked, null)
        }
    }
}


@Composable
private fun presetFieldTypeLabel(type: PresetFieldType): String {
    return when (type) {
        PresetFieldType.TEXT -> stringResource(R.string.password_field_customization_type_text)
        PresetFieldType.PASSWORD -> stringResource(R.string.password_field_customization_type_password)
        PresetFieldType.NUMBER -> stringResource(R.string.password_field_customization_type_number)
        PresetFieldType.DATE -> stringResource(R.string.password_field_customization_type_date)
        PresetFieldType.URL -> stringResource(R.string.password_field_customization_type_url)
        PresetFieldType.EMAIL -> stringResource(R.string.password_field_customization_type_email)
        PresetFieldType.PHONE -> stringResource(R.string.password_field_customization_type_phone)
    }
}

/**
 * 根据字段类型获取图标
 */
private fun getFieldTypeIcon(type: PresetFieldType): ImageVector {
    return when (type) {
        PresetFieldType.TEXT -> Icons.Default.TextFields
        PresetFieldType.PASSWORD -> Icons.Default.Password
        PresetFieldType.NUMBER -> Icons.Default.Numbers
        PresetFieldType.DATE -> Icons.Default.DateRange
        PresetFieldType.URL -> Icons.Default.Link
        PresetFieldType.EMAIL -> Icons.Default.Email
        PresetFieldType.PHONE -> Icons.Default.Phone
    }
}

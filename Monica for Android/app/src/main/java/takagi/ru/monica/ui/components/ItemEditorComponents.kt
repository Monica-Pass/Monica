package takagi.ru.monica.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.data.CustomFieldDraft

/** UI-only visibility. Field values stay with the editor and its existing save pipeline. */
@Stable
internal class ItemEditorSections(private val added: MutableState<List<String>>) {
    var focusRequest by mutableStateOf<Pair<String, Int>?>(null)
        private set

    fun visible(key: String, hasData: Boolean = false) = hasData || key in added.value

    fun keepVisible(key: String) {
        if (key !in added.value) added.value += key
    }

    fun reveal(key: String) {
        keepVisible(key)
        focusRequest = key to ((focusRequest?.second ?: 0) + 1)
    }
}

@Composable
internal fun rememberItemEditorSections(): ItemEditorSections {
    val added = rememberSaveable { mutableStateOf(emptyList<String>()) }
    return remember(added) { ItemEditorSections(added) }
}

@Composable
internal fun ItemEditorIdentity(
    title: String, onTitleChange: (String) -> Unit, label: String,
    icon: ImageVector, showIcon: Boolean = true, onIconClick: (() -> Unit)? = null,
    iconContent: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showIcon) {
            if (onIconClick != null) FilledTonalIconButton(
                onClick = onIconClick, modifier = Modifier.size(56.dp).testTag("item_editor_icon"),
            ) { if (iconContent != null) iconContent() else Icon(icon, stringResource(R.string.custom_icon_button)) }
            else Surface(Modifier.size(56.dp), shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.secondaryContainer) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer) }
            }
        }
        CompositionLocalProvider(LocalFilledEntryForm provides true, LocalTemplateFieldShape provides false,
            LocalStandaloneTemplateFields provides false) {
            OutlinedTextField(title, onTitleChange, label = { Text(label) }, singleLine = true, enabled = enabled,
                modifier = Modifier.weight(1f).testTag("item_editor_title"), shape = RoundedCornerShape(24.dp))
        }
    }
}

@Composable
internal fun ItemEditorOptionalSection(
    state: ItemEditorSections, key: String, hasData: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    LaunchedEffect(key, hasData) { if (hasData) state.keepVisible(key) }
    if (!state.visible(key, hasData)) return
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(state.focusRequest) {
        if (state.focusRequest?.first == key) {
            withFrameNanos { }
            bringIntoView.bringIntoView()
        }
    }
    Column(Modifier.fillMaxWidth().bringIntoViewRequester(bringIntoView)
        .testTag("item_editor_section_$key"), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

internal data class ItemEditorContentOption(val key: String, val title: Int, val icon: ImageVector, val selected: Boolean = false)

/** Same connected list sheet for all item types; only genuinely supported choices are supplied. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ItemEditorAddContent(
    sections: ItemEditorSections, options: List<ItemEditorContentOption>,
    fields: List<CustomFieldDraft>? = null, onFieldsChange: ((List<CustomFieldDraft>) -> Unit)? = null,
    enabled: Boolean = true,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    FilledTonalButton(onClick = { open = true }, enabled = enabled,
        modifier = Modifier.fillMaxWidth().testTag("item_editor_add_content")) {
        Icon(Icons.Default.Add, null, Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.password_content_add))
    }
    if (open) ModalBottomSheet(onDismissRequest = { open = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
            .padding(horizontal = 12.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.password_content_add), style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp))
            val custom = if (fields != null && onFieldsChange != null) listOf(
                ItemEditorContentOption("custom_text", R.string.entry_field_text, Icons.Default.TextFields),
                ItemEditorContentOption("custom_hidden", R.string.entry_field_hidden, Icons.Default.Lock),
            ) else emptyList()
            val choices = custom + options
            choices.forEachIndexed { index, option ->
                ListItem(
                    headlineContent = { Text(stringResource(option.title)) },
                    leadingContent = { Icon(option.icon, null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = if (option.selected) {{ Icon(Icons.Default.Check, null) }} else null,
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    modifier = Modifier.fillMaxWidth().clip(entryGroupShape(index, choices.size))
                        .testTag("item_editor_choose_${option.key}").clickable(role = Role.Button) {
                            open = false
                            if (option.key.startsWith("custom_") && fields != null && onFieldsChange != null) {
                                onFieldsChange(fields + CustomFieldDraft(id = CustomFieldDraft.nextTempId(fields.map { it.id }),
                                    title = "", value = "", isProtected = option.key == "custom_hidden"))
                                sections.reveal("custom")
                            } else sections.reveal(option.key)
                        },
                )
            }
        }
    }
}

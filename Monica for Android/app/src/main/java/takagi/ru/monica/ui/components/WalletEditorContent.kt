package takagi.ru.monica.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import sh.calvin.reorderable.rememberReorderableLazyListState
import takagi.ru.monica.R
import takagi.ru.monica.data.CustomFieldDraft
import takagi.ru.monica.data.model.WalletEditorOrder
import takagi.ru.monica.ui.haptic.rememberHapticFeedback

internal val LocalWalletContentEditor = staticCompositionLocalOf { false }

@Composable
internal fun WalletEditorAction(label: String, icon: ImageVector, index: Int, count: Int, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = entryGroupShape(index, count),
        color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Icon(Icons.Default.ChevronRight, null)
        }
    }
}

internal data class WalletEditorSection(
    val key: String,
    val title: Int,
    val icon: ImageVector,
    val visible: Boolean,
    val content: @Composable ColumnScope.() -> Unit,
)

/** Matches password content: one lazy list, connected summaries and a separate editor window. */
@Composable
internal fun WalletEditorContent(
    sections: ItemEditorSections,
    items: List<WalletEditorSection>,
    order: List<String>,
    onOrder: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: @Composable ColumnScope.() -> Unit,
    addContent: @Composable () -> Unit,
) {
    val visible = items.filter { it.visible }
    val keys = WalletEditorOrder.resolve(order, visible.map { it.key })
    val listState = rememberLazyListState()
    val drag = remember { DirectDragState() }
    val haptics = rememberHapticFeedback()
    val focusManager = LocalFocusManager.current
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    val savedEditors = rememberSaveableStateHolder()
    LaunchedEffect(keys) { keys.forEach(sections::keepVisible) }
    LaunchedEffect(sections.focusRequest) {
        sections.focusRequest?.first?.let { editing = it }
    }
    LaunchedEffect(editing) {
        if (editing != null) focusManager.clearFocus(force = true)
    }
    fun move(from: String, to: String) {
        val next = WalletEditorOrder.move(keys, from, to)
        if (next != keys) {
            onOrder(next + order.filterNot { it in next })
            haptics.performLightClick()
        }
    }
    val reorder = rememberReorderableLazyListState(listState, scroller = rememberDirectReorderScroller(listState)) { from, to ->
        move(from.key.toString(), to.key.toString())
    }
    val up = stringResource(R.string.move_up)
    val down = stringResource(R.string.move_down)
    LazyColumn(modifier, state = listState, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item(key = "wallet_primary") {
            Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = primary)
        }
        keys.forEachIndexed { index, key ->
            val section = visible.first { it.key == key }
            reorderableContentItem(key, reorder, enabled, drag, keys) {
                val activeDrag = LocalContentDrag.current
                var menu by remember { mutableStateOf(false) }
                Card(onClick = { editing = key }, enabled = enabled,
                    modifier = Modifier.fillMaxWidth().testTag("wallet_content_$key").then(activeDrag.modifier)
                        .semantics { customActions = buildList {
                            if (enabled && index > 0) add(CustomAccessibilityAction(up) { move(key, keys[index - 1]); true })
                            if (enabled && index < keys.lastIndex) add(CustomAccessibilityAction(down) { move(key, keys[index + 1]); true })
                        } },
                    shape = directReorderShape(index, keys.size, activeDrag.draggedIndex),
                    colors = CardDefaults.cardColors(containerColor = if (activeDrag.dragging) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer),
                    elevation = CardDefaults.cardElevation(defaultElevation = if (activeDrag.dragging) 3.dp else 0.dp)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 80.dp).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(section.icon, null, tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(section.title), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Box {
                            IconButton(onClick = { menu = true }, enabled = enabled, modifier = Modifier.testTag("wallet_actions_$key")) {
                                Icon(Icons.Default.MoreVert, stringResource(R.string.more_options))
                            }
                            EditorActionMenu(menu, { menu = false }, buildList {
                                add(EditorMenuAction(stringResource(R.string.edit), Icons.Default.Edit, onClick = { editing = key }))
                                if (index > 0) add(EditorMenuAction(up, Icons.Default.KeyboardArrowUp, onClick = { move(key, keys[index - 1]) }))
                                if (index < keys.lastIndex) add(EditorMenuAction(down, Icons.Default.KeyboardArrowDown, onClick = { move(key, keys[index + 1]) }))
                            })
                        }
                    }
                }
            }
        }
        item(key = "wallet_add") { Box(Modifier.padding(top = 12.dp)) { addContent() } }
    }
    // Outside the lazy item: scrolling and sorting cannot dispose an open editor.
    visible.firstOrNull { it.key == editing }?.let { section ->
        WalletContentDialog(stringResource(section.title), section.key, onDismiss = { editing = null }) {
            savedEditors.SaveableStateProvider(section.key) {
                Column(Modifier.fillMaxWidth().testTag("item_editor_section_${section.key}"),
                    verticalArrangement = Arrangement.spacedBy(8.dp), content = section.content)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WalletContentDialog(title: String, key: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    // A dialog gets a new Android view owner; keep the editor's font scale and locale.
    val editorDensity = LocalDensity.current
    val editorConfiguration = LocalConfiguration.current
    val editorResources = LocalResources.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        CompositionLocalProvider(LocalDensity provides editorDensity,
            LocalConfiguration provides editorConfiguration, LocalResources provides editorResources) {
            val view = LocalView.current
            val lightSurface = MaterialTheme.colorScheme.surface.luminance() > 0.5f
            SideEffect {
                (view.parent as? DialogWindowProvider)?.window?.let { window ->
                    WindowCompat.getInsetsController(window, view).apply {
                        isAppearanceLightStatusBars = lightSurface
                        isAppearanceLightNavigationBars = lightSurface
                    }
                }
            }
            Scaffold(Modifier.fillMaxSize().testTag("wallet_detail_$key"), topBar = {
                TopAppBar(title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { IconButton(onClick = onDismiss, modifier = Modifier.testTag("wallet_detail_back")) {
                        Icon(Icons.Default.ArrowBack, stringResource(R.string.back))
                    } }, actions = { IconButton(onClick = onDismiss, modifier = Modifier.testTag("wallet_detail_done")) {
                        Icon(Icons.Default.Check, stringResource(R.string.confirm))
                    } })
            }) { padding ->
                CompositionLocalProvider(LocalWalletContentEditor provides true, LocalFilledEntryForm provides true,
                    LocalEntryContentStyle provides true, LocalTemplateFieldShape provides false, LocalStandaloneTemplateFields provides false) {
                    Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
                        .verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
                }
            }
        }
    }
}

/** A compact filled editor; no outline-card nested inside another card. */
@Composable
internal fun WalletCustomFields(fields: List<CustomFieldDraft>, onFields: (List<CustomFieldDraft>) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        fields.forEachIndexed { index, field -> key(field.id) {
            var reveal by remember(field.isProtected) { mutableStateOf(false) }
            var remove by remember { mutableStateOf(false) }
            TemplateFormSection("") {
                OutlinedTextField(field.title, { if (!field.isPreset) onFields(fields.map { f -> if (f.id == field.id) f.copy(title = it) else f }) },
                    readOnly = field.isPreset, label = { Text(stringResource(R.string.custom_field_name)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth().testTag("wallet_custom_name_$index"))
                OutlinedTextField(field.value, { value -> onFields(fields.map { if (it.id == field.id) it.copy(value = value) else it }) },
                    label = { Text(stringResource(R.string.custom_field_value)) },
                    keyboardOptions = if (field.isProtected) KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false) else KeyboardOptions.Default,
                    visualTransformation = if (field.isProtected && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth().testTag("wallet_custom_value_$index"),
                    trailingIcon = if (field.isProtected) {{ IconButton(onClick = { reveal = !reveal }) {
                        Icon(if (reveal) Icons.Default.VisibilityOff else Icons.Default.Visibility, stringResource(if (reveal) R.string.hide_password else R.string.show_password))
                    } }} else null)
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    ListItem(headlineContent = { Text(stringResource(R.string.custom_field_sensitive)) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        trailingContent = { Switch(field.isProtected, { hidden -> onFields(fields.map { if (it.id == field.id) it.copy(isProtected = hidden) else it }) }) })
                }
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(enabled = index > 0, onClick = { onFields(fields.toMutableList().apply { add(index - 1, removeAt(index)) }) }) {
                        Icon(Icons.Default.ArrowUpward, stringResource(R.string.move_up))
                    }
                    IconButton(enabled = index < fields.lastIndex, onClick = { onFields(fields.toMutableList().apply { add(index + 1, removeAt(index)) }) }) {
                        Icon(Icons.Default.ArrowDownward, stringResource(R.string.move_down))
                    }
                    IconButton(onClick = { remove = true }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
                }
                }
            }
            if (remove) AlertDialog(onDismissRequest = { remove = false }, title = { Text(stringResource(R.string.custom_field_delete_title)) },
                text = { Text(stringResource(R.string.custom_field_delete_message, field.title)) },
                confirmButton = { TextButton(onClick = { remove = false; onFields(fields.filterNot { it.id == field.id }) }) { Text(stringResource(R.string.delete)) } },
                dismissButton = { TextButton(onClick = { remove = false }) { Text(stringResource(R.string.cancel)) } })
        } }
        EntryContentFieldButton(fields, onFields, Modifier.fillMaxWidth())
    }
}

package takagi.ru.monica.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Delete
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import takagi.ru.monica.R

data class EntryContentActions(val moveUp: (() -> Unit)? = null, val moveDown: (() -> Unit)? = null,
    val remove: (() -> Unit)? = null, val groupIndex: Int = 0, val groupCount: Int = 1)
val LocalEntryContentActions = staticCompositionLocalOf<((PasswordContentSection) -> EntryContentActions)?> { null }

/** A summary in the editor, a separate full-height editing surface when opened. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryContentPanel(
    section: PasswordContentSection,
    summary: String,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val editorDensity = LocalDensity.current
    val editorConfiguration = LocalConfiguration.current
    val editorResources = LocalResources.current
    val actions = LocalEntryContentActions.current?.invoke(section)
    val drag = LocalContentDrag.current
    val upLabel = stringResource(R.string.move_up)
    val downLabel = stringResource(R.string.move_down)
    var menu by remember { mutableStateOf(false) }
    var confirmRemoval by remember { mutableStateOf(false) }
    val pinnableContainer = LocalPinnableContainer.current
    // The dialog is composed by a lazy row. Keep that row alive while its window is
    // open, including before any text field gains focus and pins itself.
    DisposableEffect(pinnableContainer, open, menu, confirmRemoval) {
        val pin = if (open || menu || confirmRemoval) pinnableContainer?.pin() else null
        onDispose { pin?.release() }
    }
    Card(onClick = { onOpenChange(true) },
        modifier = Modifier.fillMaxWidth().testTag("content_panel_${section.name}")
            .then(drag.modifier)
            .semantics { customActions = buildList {
                actions?.moveUp?.let { add(CustomAccessibilityAction(upLabel) { it(); true }) }
                actions?.moveDown?.let { add(CustomAccessibilityAction(downLabel) { it(); true }) }
            } },
        shape = directReorderShape(actions?.groupIndex ?: 0, actions?.groupCount ?: 1, drag.draggedIndex),
        elevation = CardDefaults.cardElevation(defaultElevation = if (drag.dragging) 3.dp else 0.dp),
        colors = CardDefaults.cardColors(containerColor = if (drag.dragging) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(section.icon, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(section.title), style = MaterialTheme.typography.bodyLarge)
                Text(summary.ifBlank { stringResource(section.description) },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("content_actions_${section.name}")) {
                    Icon(Icons.Default.MoreVert, stringResource(R.string.more_options))
                }
                EditorActionMenu(menu, { menu = false }, buildList {
                    add(EditorMenuAction(stringResource(R.string.edit), Icons.Default.Edit, onClick = { onOpenChange(true) }))
                    actions?.remove?.let { add(EditorMenuAction(stringResource(R.string.delete), Icons.Default.Delete,
                        onClick = { confirmRemoval = true }, destructive = true, tag = "content_remove")) }
                })
            }
        }
    }
    if (confirmRemoval) AlertDialog(onDismissRequest = { confirmRemoval = false },
        title = { Text(stringResource(R.string.entry_content_remove_title, stringResource(section.title))) },
        text = { Text(stringResource(R.string.entry_content_remove_message)) },
        confirmButton = { TextButton(onClick = { confirmRemoval = false; actions?.remove?.invoke() },
            modifier = Modifier.testTag("content_remove_confirm")) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = { confirmRemoval = false }) { Text(stringResource(R.string.cancel)) } })
    if (open) Dialog(onDismissRequest = { onOpenChange(false) },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // A new Android window otherwise replaces the editor's density and resource locals.
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
        Scaffold(modifier = Modifier.fillMaxSize().testTag("content_detail_${section.name}"),
            topBar = { TopAppBar(title = {
                Text(stringResource(section.title), maxLines = if (section == PasswordContentSection.ATTACHMENTS) 1 else Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis)
            },
                navigationIcon = { IconButton(onClick = { onOpenChange(false) }, modifier = Modifier.testTag("content_detail_back")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                } }, actions = {
                if (section == PasswordContentSection.ATTACHMENTS) {
                    IconButton(onClick = { onOpenChange(false) }) {
                        Icon(Icons.Default.Check, stringResource(R.string.confirm))
                    }
                } else TextButton(onClick = { onOpenChange(false) }) { Text(stringResource(R.string.confirm)) }
            }) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
                .verticalScroll(rememberScrollState()).testTag("content_detail_scroll").padding(if (section == PasswordContentSection.ATTACHMENTS) 12.dp else 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
        }
        }
    }
}

@Composable
fun EntryEditorSection(
    contentMode: Boolean, section: PasswordContentSection, summary: String,
    open: Boolean, onOpenChange: (Boolean) -> Unit,
    expanded: Boolean = true, onExpandedChange: (Boolean) -> Unit = {},
    collapsible: Boolean = false,
    templateMode: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (templateMode) TemplateFormSection(stringResource(section.title), content)
    else if (contentMode) EntryContentPanel(section, summary, open, onOpenChange, content)
    else if (collapsible) MonicaExpandableCard(stringResource(section.title), section.icon, expanded, onExpandedChange, content = content)
    else PasswordEditorSection(stringResource(section.title)) { Column(content = content) }
}

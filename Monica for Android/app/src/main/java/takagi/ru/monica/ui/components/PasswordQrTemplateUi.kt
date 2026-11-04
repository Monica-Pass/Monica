package takagi.ru.monica.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import takagi.ru.monica.R
import takagi.ru.monica.data.model.PasswordContentBlocks
import takagi.ru.monica.data.model.PasswordQrTemplate

class QrTemplateActions(
    val read: suspend () -> PasswordQrTemplate.Values,
    val save: suspend (PasswordContentBlocks.Block) -> Unit,
    val forEntry: (Long) -> QrTemplateActions,
)
val LocalQrTemplateActions = staticCompositionLocalOf<QrTemplateActions?> { null }

@Composable
fun QrTemplateEntryButton() {
    val actions = LocalQrTemplateActions.current ?: return
    var draft by remember { mutableStateOf<PasswordContentBlocks.Block?>(null) }
    FilledTonalButton(onClick = { draft = PasswordContentBlocks.create(PasswordContentBlocks.Kind.QR_CODE)
        .edited("", mapOf("mode" to "template", "templateVersion" to "1", "content" to "%PASSWORD%")) },
        modifier = Modifier.fillMaxWidth().testTag("qr_template_open")) {
        Icon(Icons.Default.DataObject, null); Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.qr_template_custom))
    }
    draft?.let { block -> PasswordQrContentEditor(block, actions.read,
        onSave = { actions.save(it); draft = null }, onDismiss = { draft = null }) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PasswordQrContentEditor(
    block: PasswordContentBlocks.Block,
    readValues: (suspend () -> PasswordQrTemplate.Values)?,
    onSave: suspend (PasswordContentBlocks.Block) -> Unit,
    onDismiss: () -> Unit,
) {
    val editorDensity = LocalDensity.current
    val editorConfiguration = LocalConfiguration.current
    val editorResources = LocalResources.current
    var title by remember(block.id) { mutableStateOf(block.title) }
    var template by remember(block.id) { mutableStateOf(PasswordQrTemplate.isTemplate(block)) }
    var content by remember(block.id) { mutableStateOf(TextFieldValue(block.value("content"))) }
    var notes by remember(block.id) { mutableStateOf(block.value("notes")) }
    var reveal by remember(block.id) { mutableStateOf(false) }
    var fields by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val latestRead by rememberUpdatedState(readValues)
    val latestSave by rememberUpdatedState(onSave)
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val fieldColors = TextFieldDefaults.colors(
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
        disabledIndicatorColor = Color.Transparent,
        errorIndicatorColor = Color.Transparent,
    )
    fun draft() = block.edited(title, mapOf("content" to content.text, "notes" to notes,
        "mode" to if (template) "template" else "literal", "templateVersion" to "1"))
    fun failed(cause: Throwable) {
        if (cause is CancellationException) throw cause
        error = if (cause is PasswordQrTemplate.Invalid) context.getString(R.string.qr_template_field_error, cause.field)
            else context.getString(R.string.qr_template_error)
    }
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        CompositionLocalProvider(LocalDensity provides editorDensity,
            LocalConfiguration provides editorConfiguration, LocalResources provides editorResources) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
            .navigationBarsPadding().padding(horizontal = 12.dp).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.content_block_qr), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(12.dp))
            TextField(title, { title = it }, label = { Text(stringResource(R.string.title)) },
                enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("block_title"), shape = RoundedCornerShape(24.dp),
                colors = fieldColors)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !template, onClick = { template = false; error = null }, enabled = !busy,
                    label = { Text(stringResource(R.string.qr_template_literal)) }, modifier = Modifier.testTag("qr_mode_literal"))
                FilterChip(selected = template, onClick = { template = true; error = null }, enabled = !busy,
                    label = { Text(stringResource(R.string.qr_template_mode)) }, modifier = Modifier.testTag("qr_mode_template"))
            }
            TextField(content, { content = it; error = null },
                label = { Text(stringResource(if (template) R.string.qr_template_mode else R.string.content_block_content)) },
                minLines = 3, maxLines = 7, enabled = !busy,
                visualTransformation = if (!template && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
                trailingIcon = { if (!template) IconButton(onClick = { reveal = !reveal }) {
                    Icon(if (reveal) Icons.Default.VisibilityOff else Icons.Default.Visibility, stringResource(R.string.show_password))
                } }, modifier = Modifier.fillMaxWidth().testTag("block_field_content"), shape = RoundedCornerShape(24.dp),
                colors = fieldColors)
            if (template) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = !busy, modifier = Modifier.testTag("qr_insert_field"), onClick = {
                        scope.launch {
                            busy = true
                            try {
                                val snapshot = latestRead?.invoke()
                                fields = PasswordQrTemplate.keys.map { it to "%$it%" } + snapshot?.custom.orEmpty()
                                    .map { it.first }.distinct().map { it to PasswordQrTemplate.customToken(it) }
                            } catch (cause: Exception) { failed(cause) } finally { busy = false }
                        }
                    }) { Icon(Icons.Default.Add, null); Text(stringResource(R.string.qr_template_insert)) }
                    TextButton(enabled = !busy, modifier = Modifier.testTag("qr_wifi_preset"), onClick = {
                        content = TextFieldValue(PasswordQrTemplate.WIFI, TextRange(PasswordQrTemplate.WIFI.length)); error = null
                    }) { Icon(Icons.Default.Wifi, null); Text(stringResource(R.string.qr_template_wifi)) }
                }
                Text(stringResource(R.string.qr_template_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
            TextField(notes, { notes = it }, label = { Text(stringResource(R.string.notes)) }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("block_field_notes"), shape = RoundedCornerShape(24.dp), maxLines = 4,
                colors = fieldColors)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp).testTag("qr_template_error")) }
            FilledTonalButton(enabled = !busy && content.text.isNotEmpty() && (!template || readValues != null),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("qr_template_generate"),
                shape = RoundedCornerShape(24.dp), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp), onClick = {
                    scope.launch {
                        busy = true; error = null
                        try {
                            val current = draft()
                            val values = if (template) requireNotNull(latestRead).invoke() else PasswordQrTemplate.Values(emptyMap())
                            preview = withContext(Dispatchers.Default) { PasswordQrTemplate.resolve(current, values) }
                        } catch (cause: Exception) { failed(cause) } finally { busy = false }
                    }
                }) { Icon(Icons.Default.QrCode, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.qr_template_generate)) }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) }
                Button(modifier = Modifier.testTag("block_save"), enabled = !busy, onClick = {
                    scope.launch { busy = true; error = null
                        try { latestSave(draft()) } catch (cause: Exception) { failed(cause) } finally { busy = false }
                    }
                }) { Text(stringResource(R.string.save)) }
            }
        }
        }
    }
    fields?.let { options ->
        ModalBottomSheet(onDismissRequest = { fields = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            CompositionLocalProvider(LocalDensity provides editorDensity,
                LocalConfiguration provides editorConfiguration, LocalResources provides editorResources) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(12.dp)) {
                Text(stringResource(R.string.qr_template_insert), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(12.dp))
                options.forEachIndexed { index, (name, token) ->
                    Surface(onClick = {
                        val start = content.selection.min; val end = content.selection.max
                        content = TextFieldValue(content.text.replaceRange(start, end, token), TextRange(start + token.length)); fields = null
                    }, shape = entryGroupShape(index, options.size), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp).testTag("qr_insert_$name")) {
                        ListItem(headlineContent = { Text(qrFieldLabel(name)) }, supportingContent = { Text(token) },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh))
                    }
                }
            }
            }
        }
    }
    preview?.let { TextQrCodeDialog(title.ifBlank { stringResource(R.string.content_block_qr) }, it, onDismiss = { preview = null }) }
}

@Composable private fun qrFieldLabel(key: String): String = when (key) {
    "ACCOUNT" -> stringResource(R.string.username)
    "PASSWORD" -> stringResource(R.string.password)
    "TITLE" -> stringResource(R.string.title)
    "URL" -> stringResource(R.string.website)
    "EMAIL" -> stringResource(R.string.email)
    "PHONE" -> stringResource(R.string.phone)
    "NOTES" -> stringResource(R.string.notes)
    else -> key
}

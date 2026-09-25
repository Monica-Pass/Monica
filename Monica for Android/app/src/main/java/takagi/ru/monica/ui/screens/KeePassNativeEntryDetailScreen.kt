package takagi.ru.monica.ui.screens

import takagi.ru.monica.ui.components.animateMonicaContentSize
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import takagi.ru.monica.R
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.AppSettings
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.model.OtpType
import takagi.ru.monica.data.model.TotpData
import takagi.ru.monica.ui.components.TotpCodeCard
import takagi.ru.monica.utils.SettingsManager
import java.time.Instant
import takagi.ru.monica.keepass.KeePassFieldChange
import takagi.ru.monica.keepass.KeePassNativeAttachmentRecord
import takagi.ru.monica.keepass.KeePassNativeEntryRecord
import takagi.ru.monica.keepass.KeePassTemplateEngine
import takagi.ru.monica.keepass.KeePassNativeEntryIdentity
import takagi.ru.monica.keepass.KeePassNativeHistoryVersion

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NativeEntryDetailScreen(
    entry: KeePassNativeEntryRecord,
    modificationEnabled: Boolean,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onAddAttachment: (Uri, (String?) -> Unit) -> Unit,
    onRenameAttachment: (KeePassNativeAttachmentRecord, String, (String?) -> Unit) -> Unit,
    onExportAttachment: (KeePassNativeAttachmentRecord, Uri, (String?) -> Unit) -> Unit,
    onDeleteAttachment: (KeePassNativeAttachmentRecord, (String?) -> Unit) -> Unit,
    onRestoreHistory: (Int, (String?) -> Unit) -> Unit,
    onDeleteHistory: (Int, (String?) -> Unit) -> Unit,
    onAdvanceHotp: ((TotpData, (String?) -> Unit) -> Unit)? = null,
) {
    val context = LocalContext.current
    val settings by remember(context) { SettingsManager(context).settingsFlow }
        .collectAsStateWithLifecycle(initialValue = AppSettings())
    val clipboard = LocalClipboardManager.current
    val draft = remember(entry.identity, entry.fields) {
        ensureNativeEntryEditorStandardFields(
            buildNativeEntryEditorDraft(
                entry.fields
                    .filterNot { field -> field.name.equals(KeePassTemplateEngine.TEMPLATE_MARKER_FIELD, ignoreCase = true) }
                    .map { field ->
                    KeePassFieldChange(field.name, field.displayValue, field.isProtected)
                },
            ),
        )
    }
    val otp = remember(entry.identity, entry.fields) {
        parseNativeTotpFields(entry.fields.map { KeePassFieldChange(it.name, it.displayValue, it.isProtected) })
    }
    val otpItem = remember(entry.identity, entry.title) {
        SecureItem(itemType = ItemType.TOTP, title = entry.title, itemData = "")
    }
    var hotpBusy by remember(entry.identity) { mutableStateOf(false) }
    var hotpError by remember(entry.identity) { mutableStateOf<String?>(null) }
    var revealedIds by remember(entry.identity, entry.fields) { mutableStateOf(emptySet<Long>()) }
    var copiedLabel by remember(entry.identity) { mutableStateOf<String?>(null) }
    var showMetadata by remember(entry.identity) { mutableStateOf(false) }
    var showHistory by remember(entry.identity) { mutableStateOf(false) }
    var attachmentBusy by remember(entry.identity) { mutableStateOf<String?>(null) }
    var attachmentError by remember(entry.identity) { mutableStateOf<String?>(null) }
    var attachmentMessage by remember(entry.identity) { mutableStateOf<String?>(null) }
    var exportTarget by remember(entry.identity) { mutableStateOf<KeePassNativeAttachmentRecord?>(null) }
    var renameTarget by remember(entry.identity) { mutableStateOf<KeePassNativeAttachmentRecord?>(null) }
    var renameName by remember(entry.identity) { mutableStateOf("") }
    var deleteTarget by remember(entry.identity) { mutableStateOf<KeePassNativeAttachmentRecord?>(null) }
    val copyLabel = stringResource(R.string.copy)

    val addLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        attachmentBusy = "add"
        onAddAttachment(uri) { failure ->
            attachmentBusy = null
            if (failure == null) attachmentMessage = "added" else attachmentError = failure
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val target = exportTarget
        exportTarget = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        attachmentBusy = attachmentKey("export", target)
        onExportAttachment(target, uri) { failure ->
            attachmentBusy = null
            if (failure == null) attachmentMessage = "exported" else attachmentError = failure
        }
    }

    fun copyValue(value: String, label: String) {
        if (value.isBlank()) return
        clipboard.setText(AnnotatedString(value))
        copiedLabel = label
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        entry.title.ifBlank { stringResource(R.string.keepass_native_untitled_entry) },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.go_back))
                    }
                },
                actions = {
                    if (entry.history.isNotEmpty()) {
                        IconButton(onClick = { showHistory = true }) {
                            Icon(Icons.Default.History, contentDescription = stringResource(R.string.history))
                        }
                    }
                    IconButton(onClick = onEdit, enabled = modificationEnabled && !hotpBusy) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    color = nativeKindColor(entry.kind).copy(alpha = 0.12f),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Surface(
                            modifier = Modifier.size(48.dp),
                            shape = RoundedCornerShape(15.dp),
                            color = nativeKindColor(entry.kind).copy(alpha = 0.2f),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(nativeKindIcon(entry.kind), contentDescription = null, tint = nativeKindColor(entry.kind))
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                entry.title.ifBlank { stringResource(R.string.keepass_native_untitled_entry) },
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(nativeKindLabel(entry.kind), color = nativeKindColor(entry.kind))
                            entry.legacyGroupPath?.takeIf { it.isNotBlank() }?.let { path ->
                                Text(path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            copiedLabel?.let { label ->
                item {
                    NativeDetailMessage(
                        text = label,
                        error = false,
                        onDismiss = { copiedLabel = null },
                    )
                }
            }
            attachmentMessage?.let { message ->
                item {
                    NativeDetailMessage(
                        text = when (message) {
                            "added" -> stringResource(R.string.keepass_native_attachment_added)
                            "exported" -> stringResource(R.string.keepass_native_attachment_exported)
                            "renamed" -> stringResource(R.string.keepass_native_attachment_renamed)
                            "deleted" -> stringResource(R.string.keepass_native_attachment_deleted)
                            else -> message
                        },
                        error = false,
                        onDismiss = { attachmentMessage = null },
                    )
                }
            }
            attachmentError?.let { failure ->
                item {
                    NativeDetailMessage(text = failure, error = true, onDismiss = { attachmentError = null })
                }
            }

            otp?.let { data ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TotpCodeCard(
                            item = otpItem,
                            parsedTotpData = data,
                            appSettings = settings,
                            onCopyCode = { copyValue(it, copyLabel) },
                            modifier = Modifier.testTag("native_entry_otp"),
                        )
                        if (data.otpType == OtpType.HOTP) {
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth().testTag("native_hotp_next"),
                                enabled = modificationEnabled && !hotpBusy && attachmentBusy == null &&
                                    data.counter < Long.MAX_VALUE && onAdvanceHotp != null,
                                onClick = {
                                    hotpBusy = true
                                    hotpError = null
                                    onAdvanceHotp?.invoke(data) { failure ->
                                        hotpBusy = false
                                        hotpError = failure
                                    }
                                },
                            ) { Text(stringResource(if (hotpBusy) R.string.keepass_hotp_saving else R.string.generate_next)) }
                            if (data.counter == Long.MAX_VALUE) {
                                Text(stringResource(R.string.keepass_hotp_counter_exhausted), color = MaterialTheme.colorScheme.error)
                            }
                        }
                        hotpError?.let { failure ->
                            NativeDetailMessage(failure, error = true, onDismiss = { hotpError = null })
                        }
                    }
                }
            }
            if (entry.tags.isNotEmpty() || entry.times?.expires == true) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth().testTag("native_entry_properties_summary"),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (entry.tags.isNotEmpty()) {
                                NativeDetailMetaRow(stringResource(R.string.keepass_native_tags), entry.tags.joinToString(", "))
                            }
                            entry.times?.takeIf { it.expires }?.expiryTime?.let { expiry ->
                                val expired = !expiry.isAfter(Instant.now())
                                Text(stringResource(
                                    if (expired) R.string.keepass_entry_expired_at else R.string.keepass_entry_expires_at,
                                    formatNativeEntryTime(expiry, context.resources.configuration.locales[0]),
                                ), color = if (expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            val standardFields = NativeEntryStandardSlot.entries
                .filter { it != NativeEntryStandardSlot.NOTES }
                .mapNotNull { slot ->
                    draft.standard(slot)?.takeIf { it.value.isNotBlank() }
                }
            if (standardFields.isNotEmpty()) {
                item {
                    NativeDetailSectionTitle(stringResource(R.string.keepass_native_credentials_section))
                }
                item {
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            standardFields.forEach { field ->
                                NativeDetailFieldRow(
                                    field = field,
                                    label = nativeEntryDetailLabel(field.slot),
                                    revealed = field.id in revealedIds,
                                    onReveal = {
                                        revealedIds = if (field.id in revealedIds) revealedIds - field.id else revealedIds + field.id
                                    },
                                    onCopy = { copyValue(field.value, copyLabel) },
                                    onOpen = if (field.slot == NativeEntryStandardSlot.URL) {
                                        {
                                            runCatching {
                                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(field.value)))
                                            }
                                        }
                                    } else null,
                                )
                            }
                        }
                    }
                }
            }

            val notes = draft.standard(NativeEntryStandardSlot.NOTES)?.takeIf { it.value.isNotBlank() }
            notes?.let { note ->
                item { NativeDetailSectionTitle(stringResource(R.string.notes)) }
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        SelectionContainer {
                            Text(note.value, modifier = Modifier.padding(16.dp))
                        }
                    }
                }
            }

            val customFields = draft.customFields
            if (customFields.isNotEmpty()) {
                item { NativeDetailSectionTitle(stringResource(R.string.custom_field_title)) }
                items(customFields, key = { it.id }) { field ->
                    NativeDetailFieldRow(
                        field = field,
                        label = field.name,
                        revealed = field.id in revealedIds,
                        onReveal = {
                            revealedIds = if (field.id in revealedIds) revealedIds - field.id else revealedIds + field.id
                        },
                        onCopy = { copyValue(field.value, field.name) },
                        onOpen = null,
                    )
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NativeDetailSectionTitle(stringResource(R.string.attachments))
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = { addLauncher.launch(arrayOf("*/*")) },
                        enabled = modificationEnabled && attachmentBusy == null,
                    ) {
                        Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(R.string.attachment_add))
                    }
                }
            }
            if (entry.attachments.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.keepass_native_no_attachments),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(entry.attachments, key = { "${it.hash}:${it.name}" }) { attachment ->
                    NativeDetailAttachmentRow(
                        attachment = attachment,
                        busy = attachmentBusy == attachmentKey("export", attachment) ||
                            attachmentBusy == attachmentKey("rename", attachment) ||
                            attachmentBusy == attachmentKey("delete", attachment),
                        modificationEnabled = modificationEnabled && attachmentBusy == null,
                        onExport = {
                            exportTarget = attachment
                            exportLauncher.launch(attachment.name)
                        },
                        onRename = {
                            renameTarget = attachment
                            renameName = attachment.name
                        },
                        onDelete = { deleteTarget = attachment },
                    )
                }
            }

            item {
                OutlinedButton(
                    onClick = { showMetadata = !showMetadata },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.keepass_native_metadata))
                }
            }
            if (showMetadata) {
                item { NativeDetailMetadataCard(entry) }
            }
        }
    }

    if (showHistory) {
        NativeHistorySheet(
            entry = entry,
            onDismiss = { showHistory = false },
            onRestore = onRestoreHistory,
            onDelete = onDeleteHistory,
        )
    }
    renameTarget?.let { attachment ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(R.string.keepass_native_attachment_rename)) },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = renameName,
                    onValueChange = { renameName = it },
                    label = { Text(stringResource(R.string.keepass_native_attachment_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        attachmentBusy = attachmentKey("rename", attachment)
                        onRenameAttachment(attachment, renameName) { failure ->
                            attachmentBusy = null
                            if (failure == null) {
                                renameTarget = null
                                attachmentMessage = "renamed"
                            } else attachmentError = failure
                        }
                    },
                    enabled = renameName.isNotBlank() && attachmentBusy == null,
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    deleteTarget?.let { attachment ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text(stringResource(R.string.attachment_delete)) },
            text = { Text(stringResource(R.string.keepass_native_attachment_delete_confirmation, attachment.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        attachmentBusy = attachmentKey("delete", attachment)
                        onDeleteAttachment(attachment) { failure ->
                            attachmentBusy = null
                            if (failure == null) {
                                deleteTarget = null
                                attachmentMessage = "deleted"
                            } else attachmentError = failure
                        }
                    },
                    enabled = attachmentBusy == null,
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun NativeDetailSectionTitle(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun NativeDetailFieldRow(
    field: NativeEntryEditorField,
    label: String,
    revealed: Boolean,
    onReveal: () -> Unit,
    onCopy: () -> Unit,
    onOpen: (() -> Unit)?,
) {
    val protected = field.protected || field.slot == NativeEntryStandardSlot.PASSWORD ||
        takagi.ru.monica.keepass.KeePassTotpCodec.isSecretField(field.name)
    Surface(color = androidx.compose.ui.graphics.Color.Transparent) {
        ListItem(
            modifier = Modifier.animateMonicaContentSize(),
            headlineContent = { Text(label, style = MaterialTheme.typography.labelLarge) },
            supportingContent = {
                SelectionContainer {
                    Text(
                        if (protected && !revealed) "••••••••" else field.value.ifBlank { "—" },
                        maxLines = when {
                            protected && !revealed -> 1
                            protected -> Int.MAX_VALUE
                            field.slot == NativeEntryStandardSlot.NOTES -> 4
                            else -> 2
                        },
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            leadingContent = {
                Icon(
                    when (field.slot) {
                        NativeEntryStandardSlot.URL -> Icons.Default.Link
                        NativeEntryStandardSlot.USERNAME -> Icons.Default.Person
                        NativeEntryStandardSlot.PASSWORD -> Icons.Default.VpnKey
                        NativeEntryStandardSlot.NOTES -> Icons.Outlined.DataObject
                        else -> Icons.Default.Edit
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            trailingContent = {
                Row {
                    if (protected) {
                        IconButton(onClick = onReveal) {
                            Icon(
                                if (revealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = stringResource(if (revealed) R.string.hide else R.string.show),
                            )
                        }
                    }
                    if (onOpen != null && field.value.isNotBlank()) {
                        IconButton(onClick = onOpen) {
                            Icon(Icons.Default.OpenInNew, contentDescription = stringResource(R.string.open_link))
                        }
                    }
                    if (field.value.isNotBlank()) {
                        IconButton(onClick = onCopy) {
                            Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.copy))
                        }
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        )
    }
}

@Composable
private fun NativeDetailAttachmentRow(
    attachment: KeePassNativeAttachmentRecord,
    busy: Boolean,
    modificationEnabled: Boolean,
    onExport: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember(attachment.hash, attachment.name) { mutableStateOf(false) }
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        ListItem(
            headlineContent = { Text(attachment.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Text(
                    if (attachment.isMissing) stringResource(R.string.keepass_native_attachment_missing)
                    else stringResource(R.string.keepass_native_attachment_size, attachment.binary?.rawContent?.size ?: 0),
                )
            },
            leadingContent = { Icon(Icons.Outlined.DataObject, contentDescription = null) },
            trailingContent = {
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.attachment_save_to_device)) },
                                leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                                enabled = !attachment.isMissing,
                                onClick = { menuExpanded = false; onExport() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.keepass_native_attachment_rename)) },
                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                enabled = modificationEnabled,
                                onClick = { menuExpanded = false; onRename() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.attachment_delete)) },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                enabled = modificationEnabled,
                                onClick = { menuExpanded = false; onDelete() },
                            )
                        }
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        )
    }
}

@Composable
private fun NativeDetailMetadataCard(entry: KeePassNativeEntryRecord) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.keepass_native_metadata), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            NativeDetailMetaRow("UUID", entry.identity.entryUuid.toString())
            entry.legacyGroupPath?.takeIf { it.isNotBlank() }?.let { NativeDetailMetaRow(stringResource(R.string.folder_generic), it) }
            NativeDetailMetaRow(stringResource(R.string.keepass_native_tags), entry.tags.joinToString(", "))
            NativeDetailMetaRow(stringResource(R.string.keepass_native_history_count), entry.history.size.toString())
            entry.customData.forEach { (key, value) -> NativeDetailMetaRow(key, value.value) }
        }
    }
}

@Composable
private fun NativeDetailMetaRow(label: String, value: String) {
    if (value.isBlank()) return
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(value, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun NativeDetailMessage(text: String, error: Boolean, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text,
                Modifier.weight(1f),
                color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
            )
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
        }
    }
}

private fun attachmentKey(action: String, attachment: KeePassNativeAttachmentRecord): String =
    "$action:${attachment.hash}:${attachment.name}"

@Composable
private fun nativeEntryDetailLabel(slot: NativeEntryStandardSlot?): String = when (slot) {
    NativeEntryStandardSlot.TITLE -> stringResource(R.string.title)
    NativeEntryStandardSlot.USERNAME -> stringResource(R.string.username)
    NativeEntryStandardSlot.PASSWORD -> stringResource(R.string.password)
    NativeEntryStandardSlot.URL -> stringResource(R.string.website_url)
    NativeEntryStandardSlot.NOTES -> stringResource(R.string.notes)
    null -> stringResource(R.string.custom_field_name)
}

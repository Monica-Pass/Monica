package takagi.ru.monica.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.data.BackupEncryptionFormat
import takagi.ru.monica.ui.components.MonicaExpandableContent
import takagi.ru.monica.utils.BackupFile
import takagi.ru.monica.utils.FileSourceEntry
import java.text.DateFormat

/** Full-backup providers share presentation, while each route owns its operations and dialogs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CloudBackupPage(
    title: String,
    configured: Boolean,
    backups: List<BackupFile>,
    loading: Boolean,
    refreshEnabled: Boolean,
    onRefresh: () -> Unit,
    onNavigateBack: () -> Unit,
    onCancelConnection: (() -> Unit)? = null,
    errorMessage: String? = null,
    primaryAction: @Composable ColumnScope.() -> Unit,
    location: @Composable () -> Unit,
    connection: @Composable ColumnScope.() -> Unit,
    settings: @Composable ColumnScope.() -> Unit,
    backupRow: @Composable (Int, BackupFile) -> Unit,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val historyState = rememberLazyListState()
    val settingsState = rememberLazyListState()
    val connectionState = rememberLazyListState()
    val stateHolder = rememberSaveableStateHolder()
    val navigateBack: () -> Unit = {
        when {
            !configured && onCancelConnection != null -> {
                selectedTab = 0
                onCancelConnection()
            }
            configured && selectedTab != 0 -> selectedTab = 0
            else -> onNavigateBack()
        }
    }
    BackHandler(onBack = navigateBack)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            DatabaseManagementTopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = navigateBack, modifier = Modifier.testTag("cloud_backup_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
            )
        },
        bottomBar = { CloudBackupActionBar(content = primaryAction) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            if (configured) {
                PrimaryTabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surface,
                    divider = {},
                ) {
                    listOf(R.string.webdav_backup_list, R.string.settings).forEachIndexed { index, label ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(stringResource(label)) },
                            modifier = Modifier.clip(DatabaseManagementFieldShape)
                                .testTag("cloud_backup_tab_$index"),
                        )
                    }
                }
            }
            val section = if (configured) selectedTab else 2
            stateHolder.SaveableStateProvider(section) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().testTag("cloud_backup_content"),
                    state = when (section) { 0 -> historyState; 1 -> settingsState; else -> connectionState },
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (section != 1) item(key = "location") { location() }
                    errorMessage?.takeIf(String::isNotBlank)?.let { message ->
                        item(key = "error") {
                            CloudBackupNotice(message, error = true, modifier = Modifier.padding(top = 12.dp))
                        }
                    }
                    when (section) {
                        0 -> {
                            item(key = "history-heading") {
                                Row(
                                    Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(stringResource(R.string.webdav_backup_list),
                                        style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                    IconButton(onClick = onRefresh, enabled = refreshEnabled && !loading,
                                        modifier = Modifier.testTag("cloud_backup_refresh")) {
                                        Icon(Icons.Default.Refresh, stringResource(R.string.refresh))
                                    }
                                }
                            }
                            if (loading) {
                                item(key = "loading") {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                                        .testTag("cloud_backup_loading"))
                                }
                            }
                            if (backups.isEmpty() && !loading && errorMessage.isNullOrBlank()) {
                                item(key = "empty") {
                                    CloudBackupNotice(stringResource(R.string.webdav_no_backups), icon = Icons.Default.Backup)
                                }
                            }
                            itemsIndexed(backups, key = { _, backup -> backup.path }) { index, backup ->
                                backupRow(index, backup)
                            }
                        }
                        1 -> item(key = "settings") {
                            Column(Modifier.fillMaxWidth().padding(top = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp), content = settings)
                        }
                        else -> item(key = "connection") {
                            Column(Modifier.fillMaxWidth().padding(top = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp), content = connection)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CloudBackupActionBar(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding()
                .padding(horizontal = 12.dp, vertical = 10.dp).testTag("cloud_backup_actions"),
            verticalArrangement = Arrangement.spacedBy(4.dp), content = content,
        )
    }
}

@Composable
internal fun CloudBackupPrimaryButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    busy: Boolean = false,
    icon: ImageVector = Icons.Default.CloudUpload,
) {
    Button(
        onClick = onClick, enabled = enabled && !busy,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("cloud_backup_primary"),
        shape = RoundedCornerShape(28.dp), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, modifier = Modifier.weight(1f, fill = false), textAlign = TextAlign.Center)
    }
}

@Composable
internal fun CloudBackupLocationCard(
    title: String,
    subtitle: String,
    supporting: String? = null,
    actionLabel: String? = null,
    onClick: (() -> Unit)? = null,
    busy: Boolean = false,
) {
    val content: @Composable ColumnScope.() -> Unit = {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Cloud, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(28.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                supporting?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            }
            if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            else if (onClick != null) Icon(Icons.Default.Edit, actionLabel, modifier = Modifier.size(20.dp))
        }
    }
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
    if (onClick == null) DatabaseManagementCard(Modifier.fillMaxWidth().testTag("cloud_backup_location"), colors = colors, content = content)
    else DatabaseManagementCard(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("cloud_backup_location"),
        enabled = !busy, colors = colors, content = content)
}

@Composable
internal fun CloudBackupNotice(
    message: String,
    modifier: Modifier = Modifier,
    error: Boolean = false,
    icon: ImageVector = if (error) Icons.Default.ErrorOutline else Icons.Default.Info,
) {
    Surface(modifier.fillMaxWidth(), shape = DatabaseManagementFieldShape,
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, modifier = Modifier.size(20.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
internal fun CloudBackupConnectionSettings(
    title: String,
    fields: List<Pair<String, String>>,
    actions: List<DatabaseManagementAction>,
) {
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    val count = fields.size + actions.size
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        fields.forEachIndexed { index, (label, value) ->
            DatabaseManagementCard(Modifier.fillMaxWidth(), shape = settingsSectionItemShape(index, count)) {
                Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value, style = MaterialTheme.typography.bodyMedium)
                    }
                    IconButton(onClick = {
                        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
                        Toast.makeText(context, context.getString(R.string.copied_field_name, label), Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.ContentCopy, "${stringResource(R.string.copy)} $label")
                    }
                }
            }
        }
        actions.forEachIndexed { index, action ->
            DatabaseManagementActionRow(action, fields.size + index, count)
        }
    }
}

@Composable
internal fun CloudBackupEncryptionSettings(
    enabled: Boolean,
    password: String,
    passwordVisible: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onPasswordChange: (String) -> Unit,
    onVisibilityChange: () -> Unit,
    format: BackupEncryptionFormat = BackupEncryptionFormat.COMPATIBLE_V1,
    onFormatChange: (BackupEncryptionFormat) -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.onedrive_backup_security_title), modifier = Modifier.padding(start = 12.dp),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        DatabaseManagementCard(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.onedrive_backup_encryption_title), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.webdav_encryption_description), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChange, modifier = Modifier.testTag("cloud_backup_encryption"))
            }
            MonicaExpandableContent(enabled) {
                Column {
                    OutlinedTextField(
                        value = password, onValueChange = onPasswordChange,
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                            .testTag("cloud_backup_encryption_password"),
                        shape = DatabaseManagementFieldShape,
                        label = { Text(stringResource(R.string.webdav_encryption_password)) },
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = onVisibilityChange) {
                                Icon(if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    stringResource(if (passwordVisible) R.string.hide_password else R.string.show_password))
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    )
                    BackupEncryptionFormatSelector(format, onFormatChange,
                        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp))
                }
            }
        }
    }
}

@Composable
internal fun CloudBackupOptionSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    DatabaseManagementCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    }
}

@Composable
internal fun backupModifiedLabel(backup: BackupFile): String {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale) }
    return if (backup.modified.time > 0L) formatter.format(backup.modified)
    else stringResource(R.string.webdav_backup_unknown_date)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CloudBackupFileRow(
    backup: BackupFile,
    sizeLabel: String,
    index: Int,
    count: Int,
    onRestore: () -> Unit,
    onTogglePermanent: () -> Unit,
    onDelete: () -> Unit,
    expiring: Boolean = false,
    busy: Boolean = false,
    enabled: Boolean = true,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    DatabaseManagementCard(
        onClick = onRestore, enabled = enabled && !busy,
        modifier = Modifier.fillMaxWidth().testTag("cloud_backup_file_${backup.path}"),
        shape = settingsSectionItemShape(index, count),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (backup.isEncrypted()) Icons.Default.Lock else Icons.Default.Inventory2, null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(backup.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${backupModifiedLabel(backup)} · $sizeLabel", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (backup.isEncrypted()) BackupFileLabel(stringResource(R.string.webdav_backup_encrypted))
                    if (backup.isPermanent) BackupFileLabel(stringResource(R.string.webdav_tag_permanent))
                    if (expiring) BackupFileLabel(stringResource(R.string.webdav_tag_expiring), warning = true)
                }
            }
            if (busy) CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
            else Box {
                IconButton(onClick = { menuExpanded = true }, enabled = enabled,
                    modifier = Modifier.testTag("cloud_backup_menu_${backup.path}")) {
                    Icon(Icons.Default.MoreVert, stringResource(R.string.more_options))
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false },
                    shape = DatabaseManagementPanelShape) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.webdav_restore_action)) },
                        leadingIcon = { Icon(Icons.Default.Restore, null) }, onClick = { menuExpanded = false; onRestore() })
                    DropdownMenuItem(
                        text = { Text(stringResource(if (backup.isPermanent) R.string.webdav_unmark_permanent else R.string.webdav_mark_permanent)) },
                        leadingIcon = { Icon(if (backup.isPermanent) Icons.Default.BookmarkRemove else Icons.Default.BookmarkAdd, null) },
                        onClick = { menuExpanded = false; onTogglePermanent() },
                    )
                    DropdownMenuItem(text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menuExpanded = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun BackupFileLabel(label: String, warning: Boolean = false) {
    Surface(shape = RoundedCornerShape(8.dp),
        color = if (warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (warning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant) {
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

/** Confirmation stays outside the scrollable body, including large type and the keyboard. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CloudBackupSheet(
    title: String,
    onDismiss: () -> Unit,
    actions: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.88f)) {
            Text(title, style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp))
            Column(Modifier.fillMaxWidth().weight(1f, fill = false)
                .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(bottom = 12.dp)
                .testTag("cloud_backup_sheet_content"),
                verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
            CloudBackupActionBar(content = actions)
        }
    }
}

@Composable
internal fun CloudBackupRestoreSheet(
    backup: BackupFile,
    sizeLabel: String,
    merge: Boolean,
    globalDedup: Boolean,
    onMergeChange: (Boolean) -> Unit,
    onGlobalDedupChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onRestore: () -> Unit,
) {
    CloudBackupSheet(
        title = stringResource(R.string.webdav_restore_backup_title), onDismiss = onDismiss,
        actions = {
            CloudBackupPrimaryButton(stringResource(R.string.webdav_restore_action), onRestore, icon = Icons.Default.Restore)
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
        },
    ) {
        DatabaseManagementCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(backup.name, style = MaterialTheme.typography.titleSmall)
                Text("${backupModifiedLabel(backup)} · $sizeLabel", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            RestoreChoice(merge, 0, stringResource(R.string.webdav_restore_mode_merge_title),
                stringResource(R.string.webdav_restore_mode_merge_desc), Icons.Default.Merge, { onMergeChange(true) })
            RestoreChoice(!merge, 1, stringResource(R.string.webdav_restore_mode_replace_title),
                stringResource(R.string.webdav_restore_mode_replace_desc), Icons.Default.Restore, { onMergeChange(false) })
        }
        MonicaExpandableContent(merge) {
            DatabaseManagementCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.webdav_restore_mode_global_dedup), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.webdav_restore_mode_local_safe_desc), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.webdav_restore_mode_global_dedup_desc), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(globalDedup, onGlobalDedupChange, modifier = Modifier.testTag("cloud_backup_global_dedup"))
                }
            }
        }
        MonicaExpandableContent(!merge) {
            CloudBackupNotice(stringResource(R.string.webdav_restore_mode_replace_warning), error = true,
                modifier = Modifier.testTag("cloud_backup_replace_warning"))
        }
    }
}

@Composable
private fun RestoreChoice(selected: Boolean, index: Int, title: String, description: String,
    icon: ImageVector, onClick: () -> Unit) {
    val shape = settingsSectionItemShape(index, 2)
    Surface(
        modifier = Modifier.fillMaxWidth().clip(shape).selectable(selected = selected, role = Role.RadioButton,
            interactionSource = null, indication = ripple(color = MaterialTheme.colorScheme.primary), onClick = onClick)
            .testTag("cloud_backup_restore_mode_$index"),
        shape = shape,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
            RadioButton(selected, onClick = null)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CloudBackupFolderSheet(
    account: String,
    currentPath: String,
    entries: List<FileSourceEntry>,
    loading: Boolean,
    enabled: Boolean,
    errorMessage: String?,
    onNavigateUp: () -> Unit,
    onRefresh: () -> Unit,
    onCreateFolder: () -> Unit,
    onEntryClick: (FileSourceEntry) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(currentPath) { listState.scrollToItem(0) }
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f)) {
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.onedrive_backup_browser_title), style = MaterialTheme.typography.titleLarge)
                Text(account, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onNavigateUp, enabled = enabled && !loading && currentPath.trim('/').isNotEmpty()) {
                    Icon(Icons.Default.ArrowUpward, stringResource(R.string.onedrive_parent_folder))
                }
                Text(currentPath.toOneDriveDisplayPath(), style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).testTag("cloud_backup_folder_path"), maxLines = 3, overflow = TextOverflow.Ellipsis)
                IconButton(onRefresh, enabled = !loading) { Icon(Icons.Default.Refresh, stringResource(R.string.onedrive_refresh_folder)) }
                IconButton(onCreateFolder, enabled = enabled && !loading) { Icon(Icons.Default.CreateNewFolder, stringResource(R.string.onedrive_create_folder)) }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("cloud_backup_folders"), state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                errorMessage?.takeIf(String::isNotBlank)?.let { item { CloudBackupNotice(it, error = true) } }
                if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp)) }
                if (entries.isEmpty() && !loading && errorMessage.isNullOrBlank()) {
                    item { CloudBackupNotice(stringResource(R.string.onedrive_backup_no_folders), icon = Icons.Default.FolderOpen) }
                }
                itemsIndexed(entries, key = { _, entry -> entry.path }) { index, entry ->
                    DatabaseManagementActionRow(
                        DatabaseManagementAction(Icons.Default.Folder, entry.name, { onEntryClick(entry) }, enabled = enabled && !loading),
                        index, entries.size, modifier = Modifier.testTag("cloud_backup_folder_${entry.path}"),
                    )
                }
            }
            CloudBackupActionBar {
                CloudBackupPrimaryButton(stringResource(R.string.onedrive_backup_use_current_folder), onSave,
                    enabled = enabled && !loading, icon = Icons.Default.Done)
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}

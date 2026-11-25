package takagi.ru.monica.ui.screens

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import takagi.ru.monica.R
import takagi.ru.monica.data.LocalKeePassDatabase
import takagi.ru.monica.data.isRemoteSource
import takagi.ru.monica.keepass.KeePassIntegrityReport
import takagi.ru.monica.keepass.KeePassNativeBrowserSnapshot
import takagi.ru.monica.keepass.KeePassRecoveryRecord
import takagi.ru.monica.utils.KEEPASS_KDBX_MIME_TYPE
import takagi.ru.monica.viewmodel.LocalKeePassViewModel
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KeePassNativeDatabaseToolsScreen(
    database: LocalKeePassDatabase,
    viewModel: LocalKeePassViewModel,
    onBack: () -> Unit,
    onDatabaseChanged: () -> Unit
) {
    var showComparison by remember { mutableStateOf(false) }
    if (showComparison) {
        DatabaseCompareScreen(initialDatabaseId = database.id, onBack = { showComparison = false })
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val conflictResolutionVersion by viewModel.conflictResolution.resolutionVersion.collectAsStateWithLifecycle()
    var browser by remember(database.id) { mutableStateOf<KeePassNativeBrowserSnapshot?>(null) }
    var integrityReport by remember(database.id) { mutableStateOf<KeePassIntegrityReport?>(null) }
    var recoveryCopies by remember(database.id) { mutableStateOf<List<KeePassRecoveryRecord>>(emptyList()) }
    var showRecoveryCopies by remember { mutableStateOf(false) }
    var pendingRecoveryExport by remember { mutableStateOf<KeePassRecoveryRecord?>(null) }
    var pendingRecoveryRestore by remember { mutableStateOf<KeePassRecoveryRecord?>(null) }
    var pendingRecoveryDelete by remember { mutableStateOf<KeePassRecoveryRecord?>(null) }
    var showRepairConfirmation by remember { mutableStateOf(false) }
    var mergeSourceUri by remember { mutableStateOf<Uri?>(null) }
    var mergePassword by remember { mutableStateOf("") }
    var mergeKeyFileUri by remember { mutableStateOf<Uri?>(null) }
    var mergeTargetGroupUuid by remember { mutableStateOf<java.util.UUID?>(null) }
    var mergeGroupMenuExpanded by remember { mutableStateOf(false) }
    var busyAction by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refreshBrowser() {
        scope.launch {
            viewModel.openNativeBrowser(database.id)
                .onSuccess { snapshot ->
                    browser = snapshot
                    if (mergeTargetGroupUuid == null) mergeTargetGroupUuid = snapshot.rootGroup.identity.groupUuid
                }
                .onFailure { failure -> error = failure.message ?: failure.javaClass.simpleName }
        }
    }

    fun refreshRecoveryCopies() {
        scope.launch { recoveryCopies = viewModel.listRecoveryCopies(database.id) }
    }

    LaunchedEffect(database.id, conflictResolutionVersion) {
        refreshBrowser()
        refreshRecoveryCopies()
    }

    val saveCopyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(KEEPASS_KDBX_MIME_TYPE)
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busyAction = "save-copy"
            error = null
            viewModel.saveNativeDatabaseCopy(database.id, uri)
                .onSuccess { message = context.getString(R.string.keepass_database_copy_saved) }
                .onFailure { failure -> error = failure.message ?: failure.javaClass.simpleName }
            busyAction = null
        }
    }
    val recoveryExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(KEEPASS_KDBX_MIME_TYPE)
    ) { uri ->
        val record = pendingRecoveryExport
        pendingRecoveryExport = null
        if (uri == null || record == null) return@rememberLauncherForActivityResult
        scope.launch {
            busyAction = "recovery-export"
            error = null
            viewModel.exportRecoveryCopy(record, uri)
                .onSuccess { message = context.getString(R.string.keepass_recovery_copy_exported) }
                .onFailure { failure -> error = failure.message ?: failure.javaClass.simpleName }
            busyAction = null
        }
    }
    val mergeSourceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        mergeSourceUri = uri
        mergePassword = ""
        mergeKeyFileUri = null
        mergeTargetGroupUuid = browser?.rootGroup?.identity?.groupUuid
    }
    val mergeKeyFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> mergeKeyFileUri = uri }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.keepass_database_tools_title))
                        Text(
                            database.name,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = busyAction == null) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.go_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 36.dp),
            verticalArrangement = Arrangement.spacedBy(DatabaseManagementGroupSpacing)
        ) {
            item {
                Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(database.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.keepass_database_tools_summary), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            message?.let { value ->
                item { ToolMessage(value, error = false, onDismiss = { message = null }) }
            }
            error?.let { value ->
                item { ToolMessage(value, error = true, onDismiss = { error = null }) }
            }
            item {
                DatabaseToolCard(
                    index = 0, count = 2,
                    icon = Icons.Default.CheckCircle,
                    title = stringResource(R.string.keepass_integrity_check),
                    summary = stringResource(R.string.keepass_integrity_check_summary),
                    busy = busyAction == "inspect",
                    enabled = busyAction == null,
                    onClick = {
                        scope.launch {
                            busyAction = "inspect"
                            error = null
                            viewModel.inspectNativeDatabaseIntegrity(database.id)
                                .onSuccess { integrityReport = it }
                                .onFailure { failure -> error = failure.message ?: failure.javaClass.simpleName }
                            busyAction = null
                        }
                    }
                )
            }
            item {
                DatabaseToolCard(
                    index = 1, count = 2,
                    icon = Icons.Default.Build,
                    title = stringResource(R.string.keepass_database_repair),
                    summary = stringResource(R.string.keepass_database_repair_summary),
                    busy = busyAction == "repair",
                    enabled = busyAction == null && !viewModel.isKeePassDatabaseReadOnly(database.id),
                    onClick = { showRepairConfirmation = true }
                )
            }
            item { Spacer(Modifier.height(12.dp)) }
            item {
                DatabaseToolCard(
                    index = 0, count = 3,
                    icon = Icons.Default.Download,
                    title = stringResource(R.string.keepass_recovery_copies),
                    summary = stringResource(R.string.keepass_recovery_copies_summary, recoveryCopies.size),
                    busy = false,
                    enabled = busyAction == null,
                    onClick = {
                        refreshRecoveryCopies()
                        showRecoveryCopies = true
                    }
                )
            }
            item {
                DatabaseToolCard(
                    index = 1, count = 3,
                    icon = Icons.Default.SaveAlt,
                    title = stringResource(R.string.keepass_save_copy),
                    summary = stringResource(R.string.keepass_save_copy_summary),
                    busy = busyAction == "save-copy",
                    enabled = busyAction == null,
                    onClick = { saveCopyLauncher.launch("${database.name}.kdbx") }
                )
            }
            item {
                DatabaseToolCard(
                    index = 2, count = 3,
                    icon = Icons.Default.Merge,
                    title = stringResource(R.string.keepass_merge_from),
                    summary = stringResource(R.string.keepass_merge_from_summary),
                    busy = busyAction == "merge",
                    enabled = busyAction == null && browser != null && !viewModel.isKeePassDatabaseReadOnly(database.id),
                    onClick = { mergeSourceLauncher.launch(arrayOf(KEEPASS_KDBX_MIME_TYPE, "application/octet-stream", "*/*")) }
                )
            }
            item {
                DatabaseToolCard(
                    index = 0, count = 1,
                    icon = Icons.Default.Merge,
                    title = stringResource(R.string.db_compare_title),
                    summary = stringResource(R.string.db_compare_intro),
                    busy = false,
                    enabled = busyAction == null,
                    onClick = { showComparison = true }
                )
            }
            if (database.isRemoteSource()) {
                item {
                    DatabaseToolCard(
                        index = 0, count = 1,
                        icon = Icons.Default.CloudSync,
                        title = stringResource(R.string.keepass_conflict_center),
                        summary = stringResource(R.string.keepass_conflict_center_summary),
                        busy = false,
                        enabled = busyAction == null,
                        onClick = { viewModel.conflictResolution.open(database.id, database.name) }
                    )
                }
            }
        }
    }

    integrityReport?.let { report ->
        IntegrityReportDialog(report = report, onDismiss = { integrityReport = null })
    }

    if (showRepairConfirmation) {
        AlertDialog(
            onDismissRequest = { showRepairConfirmation = false },
            icon = { Icon(Icons.Default.Build, contentDescription = null) },
            title = { Text(stringResource(R.string.keepass_database_repair)) },
            text = { Text(stringResource(R.string.keepass_database_repair_confirmation)) },
            confirmButton = {
                Button(onClick = {
                    showRepairConfirmation = false
                    scope.launch {
                        busyAction = "repair"
                        error = null
                        viewModel.repairNativeDatabase(database.id)
                            .onSuccess { execution ->
                                integrityReport = execution.result.after
                                message = getApplicationMessage(
                                    context = context,
                                    actionCount = execution.result.actions.size,
                                    recoveryTime = execution.recoveryRecord.createdAt.toEpochMilli()
                                )
                                refreshRecoveryCopies()
                                refreshBrowser()
                                onDatabaseChanged()
                            }
                            .onFailure { failure -> error = failure.message ?: failure.javaClass.simpleName }
                        busyAction = null
                    }
                }) { Text(stringResource(R.string.repair)) }
            },
            dismissButton = {
                TextButton(onClick = { showRepairConfirmation = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showRecoveryCopies) {
        ModalBottomSheet(onDismissRequest = { showRecoveryCopies = false }) {
            Text(
                stringResource(R.string.keepass_recovery_copies),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            if (recoveryCopies.isEmpty()) {
                Text(
                    stringResource(R.string.keepass_no_recovery_copies),
                    modifier = Modifier.padding(20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                    contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 28.dp),
                    verticalArrangement = Arrangement.spacedBy(DatabaseManagementGroupSpacing)
                ) {
                    itemsIndexed(recoveryCopies, key = { _, record -> record.file.absolutePath }) { index, record ->
                        RecoveryCopyCard(
                            index = index, count = recoveryCopies.size,
                            record = record,
                            enabled = busyAction == null,
                            onRestore = { pendingRecoveryRestore = record },
                            onExport = {
                                pendingRecoveryExport = record
                                recoveryExportLauncher.launch("${database.name}-recovery.kdbx")
                            },
                            onDelete = { pendingRecoveryDelete = record }
                        )
                    }
                }
            }
        }
    }

    pendingRecoveryRestore?.let { record ->
        AlertDialog(
            onDismissRequest = { pendingRecoveryRestore = null },
            title = { Text(stringResource(R.string.keepass_restore_recovery_copy)) },
            text = { Text(stringResource(R.string.keepass_restore_recovery_confirmation)) },
            confirmButton = {
                Button(onClick = {
                    pendingRecoveryRestore = null
                    scope.launch {
                        busyAction = "recovery-restore"
                        error = null
                        viewModel.restoreRecoveryCopy(database.id, record)
                            .onSuccess {
                                message = context.getString(R.string.keepass_recovery_copy_restored)
                                refreshBrowser()
                                onDatabaseChanged()
                            }
                            .onFailure { failure -> error = failure.message ?: failure.javaClass.simpleName }
                        busyAction = null
                    }
                }) { Text(stringResource(R.string.restore)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRecoveryRestore = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    pendingRecoveryDelete?.let { record ->
        AlertDialog(
            onDismissRequest = { pendingRecoveryDelete = null },
            title = { Text(stringResource(R.string.delete)) },
            text = { Text(stringResource(R.string.keepass_delete_recovery_confirmation)) },
            confirmButton = {
                Button(onClick = {
                    pendingRecoveryDelete = null
                    scope.launch {
                        viewModel.deleteRecoveryCopy(record)
                            .onSuccess { refreshRecoveryCopies() }
                            .onFailure { failure -> error = failure.message ?: failure.javaClass.simpleName }
                    }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRecoveryDelete = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    mergeSourceUri?.let { sourceUri ->
        val snapshot = browser
        AlertDialog(
            onDismissRequest = { mergeSourceUri = null; mergePassword = ""; mergeKeyFileUri = null },
            title = { Text(stringResource(R.string.keepass_merge_from)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.keepass_merge_credentials_summary))
                    OutlinedTextField(
                        value = mergePassword,
                        onValueChange = { mergePassword = it },
                        label = { Text(stringResource(R.string.database_password)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedButton(
                        onClick = { mergeKeyFileLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.UploadFile, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (mergeKeyFileUri == null) stringResource(R.string.select_key_file_optional)
                            else stringResource(R.string.key_file_selected)
                        )
                    }
                    Box {
                        OutlinedButton(
                            onClick = { mergeGroupMenuExpanded = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            val selected = snapshot?.groups?.firstOrNull { it.identity.groupUuid == mergeTargetGroupUuid }
                            Text(selected?.legacyPath ?: selected?.name ?: stringResource(R.string.root_directory))
                        }
                        DropdownMenu(
                            expanded = mergeGroupMenuExpanded,
                            onDismissRequest = { mergeGroupMenuExpanded = false }
                        ) {
                            snapshot?.groups.orEmpty().forEach { group ->
                                DropdownMenuItem(
                                    text = { Text(group.legacyPath ?: group.name) },
                                    onClick = {
                                        mergeTargetGroupUuid = group.identity.groupUuid
                                        mergeGroupMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val target = mergeTargetGroupUuid ?: return@Button
                        mergeSourceUri = null
                        scope.launch {
                            busyAction = "merge"
                            error = null
                            viewModel.mergeNativeDatabaseFrom(
                                databaseId = database.id,
                                sourceUri = sourceUri,
                                sourcePassword = mergePassword,
                                sourceKeyFileUri = mergeKeyFileUri,
                                targetGroupUuid = target,
                                expectedRevisionToken = snapshot?.sourceRevision?.sha256.orEmpty()
                            ).onSuccess {
                                browser = it
                                message = context.getString(R.string.keepass_database_merged)
                                refreshRecoveryCopies()
                                onDatabaseChanged()
                            }.onFailure { failure -> error = failure.message ?: failure.javaClass.simpleName }
                            mergePassword = ""
                            mergeKeyFileUri = null
                            busyAction = null
                        }
                    },
                    enabled = mergeTargetGroupUuid != null && snapshot != null
                ) { Text(stringResource(R.string.merge)) }
            },
            dismissButton = {
                TextButton(onClick = { mergeSourceUri = null; mergePassword = ""; mergeKeyFileUri = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }


}

@Composable
internal fun DatabaseToolCard(
    icon: ImageVector,
    title: String,
    summary: String,
    busy: Boolean,
    enabled: Boolean,
    index: Int = 0,
    count: Int = 1,
    onClick: () -> Unit
) {
    DatabaseManagementActionRow(DatabaseManagementAction(icon, title, onClick,
        subtitle = summary, enabled = enabled, busy = busy), index, count)
}

@Composable
private fun ToolMessage(value: String, error: Boolean, onDismiss: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                value,
                modifier = Modifier.weight(1f),
                color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
            )
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        }
    }
}

@Composable
private fun IntegrityReportDialog(report: KeePassIntegrityReport, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(if (report.hasProblems) Icons.Default.Build else Icons.Default.CheckCircle, contentDescription = null) },
        title = { Text(stringResource(R.string.keepass_integrity_report)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (report.hasProblems) stringResource(R.string.keepass_integrity_problems_found)
                    else stringResource(R.string.keepass_integrity_healthy),
                    fontWeight = FontWeight.Bold
                )
                Text(stringResource(R.string.keepass_integrity_counts, report.groupCount, report.entryCount))
                IntegrityLine(stringResource(R.string.keepass_duplicate_group_uuids), report.duplicateGroupUuids.values.sum())
                IntegrityLine(stringResource(R.string.keepass_duplicate_entry_uuids), report.duplicateEntryUuids.values.sum())
                IntegrityLine(stringResource(R.string.keepass_missing_attachments), report.missingBinaryHashes.size)
                IntegrityLine(stringResource(R.string.keepass_unreferenced_attachments), report.unreferencedBinaryHashes.size)
                IntegrityLine(stringResource(R.string.keepass_missing_custom_icons), report.missingCustomIconUuids.size)
                IntegrityLine(stringResource(R.string.keepass_unreferenced_custom_icons), report.unreferencedCustomIconUuids.size)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } }
    )
}

@Composable
private fun IntegrityLine(label: String, count: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(count.toString(), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun RecoveryCopyCard(
    record: KeePassRecoveryRecord,
    enabled: Boolean,
    index: Int = 0,
    count: Int = 1,
    onRestore: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember(record) { mutableStateOf(false) }
    DatabaseManagementCard(modifier = Modifier.fillMaxWidth(), shape = settingsSectionItemShape(index, count)) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (record.verified) Icons.Default.CheckCircle else Icons.Default.Build, contentDescription = null,
                tint = if (record.verified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(DateFormat.getDateTimeInstance().format(Date(record.createdAt.toEpochMilli())), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.keepass_recovery_size, record.revision.sizeBytes / 1024L),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { menuExpanded = true }, enabled = enabled) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.restore)) }, enabled = enabled && record.verified,
                        onClick = { menuExpanded = false; onRestore() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.export)) }, enabled = enabled && record.verified,
                        onClick = { menuExpanded = false; onExport() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, enabled = enabled,
                        onClick = { menuExpanded = false; onDelete() })
                }
            }
        }
    }
}

private fun getApplicationMessage(context: android.content.Context, actionCount: Int, recoveryTime: Long): String =
    context.getString(
        R.string.keepass_repair_completed,
        actionCount,
        DateFormat.getDateTimeInstance().format(Date(recoveryTime))
    )

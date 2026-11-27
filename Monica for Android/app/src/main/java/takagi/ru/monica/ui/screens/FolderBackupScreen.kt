package takagi.ru.monica.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import takagi.ru.monica.R
import takagi.ru.monica.backup.*
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.ui.components.SettingsPanelGroup
import takagi.ru.monica.ui.components.SettingsPanelRow
import takagi.ru.monica.utils.BackupFile
import java.text.DateFormat
import java.util.Date

@Composable
internal fun FolderBackupScreen(onNavigateBack: () -> Unit, onRestore: (Uri) -> Unit) {
    val model: FolderBackupViewModel = viewModel()
    val state by model.state.collectAsState()
    val running by FolderBackupRunner.running.collectAsState()
    val status by FolderBackupRunner.status.collectAsState()
    val unlocked by SessionManager.isUnlocked.collectAsState()
    val context = LocalContext.current
    var editing by remember { mutableStateOf(false) }
    var selectedTree by remember { mutableStateOf<Uri?>(null) }
    var password by remember { mutableStateOf("") }
    var passwordAgain by remember { mutableStateOf("") }
    var pickerError by remember { mutableIntStateOf(0) }
    var keepText by remember(state.keep) { mutableStateOf(state.keep.toString()) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, SafBackupDestination.GRANTS)
                selectedTree = uri
                pickerError = 0
            } catch (_: Exception) { pickerError = R.string.folder_backup_permission_error }
        }
    }
    LaunchedEffect(running) { if (!running) model.refresh() }
    LaunchedEffect(state.busy) {
        if (!state.busy && state.error == 0 && state.configured) {
            editing = false
            selectedTree = null
            password = ""
            passwordAgain = ""
        }
    }
    if (!unlocked) {
        LaunchedEffect(Unit) { password = ""; passwordAgain = ""; selectedTree = null }
        return
    }
    val busy = state.busy || running
    val error = pickerError.takeIf { it != 0 } ?: state.error.takeIf { it != 0 } ?: status.takeUnless { it == R.string.folder_backup_written || it == R.string.folder_backup_cleanup_deferred }.orZero()
    CloudBackupPage(
        title = Setting.FOLDER_BACKUP.title(), configured = state.configured && !editing,
        backups = state.records.map { BackupFile(it.name, it.uri, it.size, Date(it.createdAt)) },
        loading = busy, refreshEnabled = !busy, onRefresh = { model.refresh() }, onNavigateBack = onNavigateBack,
        onCancelConnection = if (state.configured) ({ editing = false; password = ""; passwordAgain = ""; selectedTree = null }) else null,
        errorMessage = error.takeIf { it != 0 }?.let { stringResource(it) },
        primaryAction = {
            if (state.configured && !editing) {
                CloudBackupPrimaryButton(stringResource(R.string.webdav_backup_now),
                    onClick = { FolderBackupRunner.startManual(context) }, busy = busy, icon = Icons.Default.Backup)
            } else {
                CloudBackupPrimaryButton(stringResource(R.string.save), onClick = {
                    model.save(selectedTree, password, state.automatic, state.afterChanges, state.keep)
                }, enabled = (selectedTree != null || state.configured) &&
                    ((password.isNotBlank() && password == passwordAgain) || (state.configured && password.isEmpty() && passwordAgain.isEmpty())),
                    busy = busy, icon = Icons.Default.Save)
            }
        },
        location = {
            CloudBackupLocationCard(Setting.FOLDER_BACKUP_FOLDER.title(),
                if (selectedTree != null) stringResource(R.string.folder_backup_selected) else state.label.ifBlank { Setting.FOLDER_BACKUP_FOLDER.summary() },
                supporting = stringResource(if (status == R.string.folder_backup_written || status == R.string.folder_backup_cleanup_deferred) status else R.string.folder_backup_provider_note),
                actionLabel = Setting.FOLDER_BACKUP_FOLDER.title(),
                onClick = { if (state.configured && !editing) editing = true else picker.launch(null) }, busy = busy)
        },
        connection = {
            CloudBackupNotice(stringResource(R.string.folder_backup_scope))
            SettingsPanelRow(Icons.Default.FolderOpen, Setting.FOLDER_BACKUP_FOLDER,
                onClick = { picker.launch(null) }, enabled = !busy)
            OutlinedTextField(password, { password = it }, modifier = Modifier.fillMaxWidth().testTag("folder_backup_password"),
                label = { Text(Setting.FOLDER_BACKUP_PASSWORD.title()) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                supportingText = { Text(Setting.FOLDER_BACKUP_PASSWORD.summary()) }, enabled = !busy)
            OutlinedTextField(passwordAgain, { passwordAgain = it }, modifier = Modifier.fillMaxWidth().testTag("folder_backup_password_confirm"),
                label = { Text(stringResource(R.string.folder_backup_confirm_password)) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), enabled = !busy)
        },
        settings = {
            SettingsPanelGroup(Setting.FOLDER_BACKUP.title()) {
                SettingsPanelRow(Icons.Default.FolderOpen, Setting.FOLDER_BACKUP_FOLDER, state.label,
                    onClick = { editing = true }, enabled = !busy)
                SettingsPanelRow(Icons.Default.Lock, Setting.FOLDER_BACKUP_PASSWORD,
                    onClick = { editing = true }, enabled = !busy)
                SettingsPanelRow(Icons.Default.Schedule, Setting.FOLDER_BACKUP_AUTOMATIC,
                    checked = state.automatic, onCheckedChange = { model.save(null, "", it, state.afterChanges, state.keep) }, enabled = !busy)
                SettingsPanelRow(Icons.Default.Update, Setting.FOLDER_BACKUP_CHANGES,
                    checked = state.afterChanges, onCheckedChange = { model.save(null, "", state.automatic, it, state.keep) }, enabled = !busy)
            }
            OutlinedTextField(keepText, { value -> if (value.length <= 4 && value.all { it in '0'..'9' }) keepText = value },
                modifier = Modifier.fillMaxWidth().settingsSearchAnchor(Setting.FOLDER_BACKUP_KEEP.title()).testTag("folder_backup_keep"),
                label = { Text(Setting.FOLDER_BACKUP_KEEP.title()) }, supportingText = { Text(Setting.FOLDER_BACKUP_KEEP.summary()) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, enabled = !busy)
            TextButton(onClick = { model.save(null, "", state.automatic, state.afterChanges, keepText.toInt()) },
                enabled = !busy && keepText.toIntOrNull() in 1..1000 && keepText.toIntOrNull() != state.keep) { Text(stringResource(R.string.save)) }
            CloudBackupNotice(stringResource(R.string.folder_backup_scope))
        },
        backupRow = { _, file ->
            val record = state.records.first { it.uri == file.path }
            DatabaseManagementCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(DateFormat.getDateTimeInstance().format(file.modified), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(if (!record.complete) R.string.folder_backup_unverified else if (record.permanent)
                        R.string.folder_backup_permanent else R.string.folder_backup_written), style = MaterialTheme.typography.bodySmall)
                    Text(file.name, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { onRestore(Uri.parse(file.path)) }, enabled = record.complete && !busy) {
                        Icon(Icons.Default.Restore, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.folder_backup_restore))
                    }
                }
            }
        },
    )
}

private fun Int?.orZero(): Int = this ?: 0
